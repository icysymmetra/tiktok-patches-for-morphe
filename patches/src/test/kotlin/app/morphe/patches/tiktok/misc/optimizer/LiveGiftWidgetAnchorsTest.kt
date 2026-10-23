/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remove LIVE extras skips the gift-effect widget's setup but keeps its LiveWidget.onCreate call,
 * which creates the CompositeDisposable LiveWidget.onDestroy disposes. Returning before that call
 * crashed TikTok on leaving a LIVE room (#119).
 */
class LiveGiftWidgetAnchorsTest {
    @Test
    fun `the gift widget still runs LiveWidget's onCreate and tears down without its own setup`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val widget = app.values.single { classDef -> classDef.methods.any { LiveGiftOnCreateFingerprint.takes(it, classDef) } }
            assertEquals("$version: the gift widget's parent", LIVE_WIDGET_DESCRIPTOR, widget.superclass)
            val onCreate = widget.methods.single { LiveGiftOnCreateFingerprint.takes(it, widget) }

            // LiveWidget.onCreate is where the disposable LiveWidget.onDestroy needs is made.
            val parentCreate = app.getValue(LIVE_WIDGET_DESCRIPTOR).methods.single { it.name == "onCreate" && it.parameterTypes.isEmpty() }
            assertTrue(
                "$version: LiveWidget.onCreate sets compositeDisposable",
                parentCreate.implementation!!.instructions.any {
                    it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>()?.name == "compositeDisposable"
                },
            )

            val patched = MutableMethod(onCreate).apply { keepOnlyLiveWidgetCreate() }
            val after = patched.implementation!!.instructions.toList()
            val call = after[0].getReference<MethodReference>()!!
            assertTrue("$version: opens with the super call", after[0].opcode in setOf(Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE))
            assertEquals("$version: the super call", "$LIVE_WIDGET_DESCRIPTOR->onCreate", "${call.definingClass}->${call.name}")
            assertEquals("$version: returns right after it", Opcode.RETURN_VOID, after[1].opcode)

            // With the widget's own setup skipped, every field onDestroy calls through without a
            // null test has to be one a constructor sets.
            val built = widget.methods.filter { it.name == "<init>" }.flatMap { it.fieldsSetOnSelf() }.toSet()
            val unsafe = widget.methods.single { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
                .uncheckedFieldCalls().filter { it !in built }
            assertEquals("$version: fields onDestroy calls through that only setup fills", emptyList<String>(), unsafe)
        }
    }

    @Test
    fun `an onCreate that doesn't open with LiveWidget's leaves the patch out`() {
        val apk = Fixtures.declared().firstOrNull { it.isFile } ?: return
        val app = load(apk)
        val widget = app.values.single { classDef -> classDef.methods.any { LiveGiftOnCreateFingerprint.takes(it, classDef) } }
        val onCreate = widget.methods.single { LiveGiftOnCreateFingerprint.takes(it, widget) }
        val moved = MutableMethod(onCreate).apply { implementation!!.removeInstruction(0) }
        val failure = assertThrows(PatchException::class.java) { moved.keepOnlyLiveWidgetCreate() }
        assertTrue(failure.message, failure.message!!.contains("doesn't start with LiveWidget.onCreate"))
    }

    /** The register holding this: the parameters fill the last registers, after it. */
    private val Method.self
        get() = implementation!!.registerCount - 1 -
            parameterTypes.sumOf { type -> if (type.toString() == "J" || type.toString() == "D") 2 else 1 }

    private fun Method.fieldsSetOnSelf(): Set<String> = implementation!!.instructions.filter { instruction ->
        instruction.opcode.name.startsWith("iput") && (instruction as TwoRegisterInstruction).registerB == self
    }.mapNotNull { it.getReference<FieldReference>()?.name }.toSet()

    /** Fields read off this whose first use dereferences them (a call or a field access), not a null test. */
    private fun Method.uncheckedFieldCalls(): List<String> {
        val body = implementation!!.instructions.toList()
        return body.indices.filter { at ->
            body[at].opcode == Opcode.IGET_OBJECT && (body[at] as TwoRegisterInstruction).registerB == self
        }.mapNotNull { at ->
            val register = (body[at] as OneRegisterInstruction).registerA
            val use = body.drop(at + 1).firstOrNull { reads(it, register) } ?: return@mapNotNull null
            val receiver = when (use) {
                is FiveRegisterInstruction -> use.registerCount > 0 && use.registerC == register
                is RegisterRangeInstruction -> use.startRegister == register
                is TwoRegisterInstruction -> use.registerB == register &&
                    (use.opcode.name.startsWith("iget") || use.opcode.name.startsWith("iput"))
                else -> false
            }
            if (receiver && use.opcode != Opcode.INVOKE_STATIC && use.opcode != Opcode.INVOKE_STATIC_RANGE) {
                body[at].getReference<FieldReference>()!!.name
            } else {
                null
            }
        }
    }

    private fun reads(instruction: Instruction, register: Int): Boolean = when (instruction) {
        is FiveRegisterInstruction -> listOf(
            instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
        ).take(instruction.registerCount).contains(register)
        is RegisterRangeInstruction ->
            register in instruction.startRegister until instruction.startRegister + instruction.registerCount
        is TwoRegisterInstruction -> instruction.registerB == register ||
            (instruction.registerA == register && instruction.opcode.name.startsWith("iput"))
        is OneRegisterInstruction -> instruction.registerA == register && !instruction.opcode.setsRegister()
        else -> false
    }

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
