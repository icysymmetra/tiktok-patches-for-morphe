/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DmTranslationAccountHooksTest {
    companion object {
        private const val USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
        private val cache = mutableMapOf<String, Map<String, ClassDef>>()
    }

    @Test fun `login logout and switch dispatch exact synchronous native event arguments`() = withClasses { classes ->
        val targets = findDmAccountTargets(classes::get)
        for ((method, expected) in listOf(
            targets.login to listOf(2, 0, 0, 1, 4),
            targets.logout to listOf(4, 1, 0, 3, 2),
            targets.switch to listOf(2, 1, 6, 0, 5),
        )) {
            val instructions = method.implementation!!.instructions.toList()
            val callback = instructions.single { it.getReference<MethodReference>()?.definingClass == DM_ACCOUNT_LISTENER }
            assertEquals(Opcode.INVOKE_INTERFACE, callback.opcode)
            val invoke = callback as FiveRegisterInstruction
            assertEquals(5, invoke.registerCount)
            assertEquals(expected, listOf(invoke.registerC, invoke.registerD, invoke.registerE, invoke.registerF, invoke.registerG))
            assertEquals(listOf("I", "Z", "I", USER), callback.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            assertFalse(instructions.mapNotNull { it.getReference<MethodReference>() }.any {
                it.name in listOf("post", "execute", "enqueue", "launch", "LIZLLL")
            })
        }
        assertEquals(listOf(0, 1), constants(targets.login))
        assertEquals(listOf(0, 0, 4, 1), constants(targets.logout))
        assertEquals(listOf(71776, 2, 0), constants(targets.switch))
        val adapter = classes.getValue("LX/03Sl;").methods.single { it.name == "onAccountResult" }
        assertTrue(adapter.implementation!!.instructions.any {
            it.getReference<MethodReference>()?.let { call -> call.definingClass == "LX/03Q7;" && call.name == "onAccountResult" } == true
        })
    }

    @Test fun `each broadcaster gets one entry call with original body branches registers and arguments intact`() = withClasses { classes ->
        val targets = findDmAccountTargets(classes::get)
        for (original in listOf(targets.login, targets.logout, targets.switch)) {
            val method = MutableMethod(original)
            val before = original.implementation!!
            method.captureDmAccountEvent(original === targets.switch)
            val after = method.implementation!!
            val code = after.instructions.toList()
            assertEquals(before.registerCount, after.registerCount)
            assertEquals(before.tryBlocks, after.tryBlocks)
            assertEquals(before.instructions.map(::signature), code.drop(1).map(::signature))
            assertEquals(1, code.count { it.getReference<MethodReference>()?.definingClass == DM_ACCOUNT_EVENTS })
            val added = code.first().getReference<MethodReference>()!!
            assertEquals(DM_ACCOUNT_EVENTS, added.definingClass)
            assertEquals("V", added.returnType)
            if (original === targets.switch) {
                assertEquals("switchResult", added.name)
                assertEquals(listOf("Z"), added.parameterTypes.map(CharSequence::toString))
                assertEquals(Opcode.INVOKE_STATIC_RANGE, code.first().opcode)
                val range = code.first() as RegisterRangeInstruction
                assertEquals(1, range.registerCount)
                assertEquals(after.registerCount - 1, range.startRegister)
            } else {
                assertEquals("changed", added.name)
                assertTrue(added.parameterTypes.isEmpty())
                assertEquals(0, (code.first() as FiveRegisterInstruction).registerCount)
            }
        }
    }

    @Test fun `changed dispatch success user or parameter ownership fails read only qualification`() = withClasses { classes ->
        val targets = findDmAccountTargets(classes::get)
        val owner = classes.getValue(DM_ACCOUNT_BROADCASTER)
        val callback = targets.switch.implementation!!.instructions.indexOfFirst {
            it.getReference<MethodReference>()?.definingClass == DM_ACCOUNT_LISTENER
        }
        val result = targets.switch.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.MOVE_RESULT }
        assertTrue(callback > 0 && result >= 0)
        for ((index, replacement) in listOf(
            callback to "invoke-interface {v2, v1, v0, v0, p0}, $DM_ACCOUNT_LISTENER->onAccountResult(IZI$USER)V",
            callback to "invoke-interface {v2, v1, p1, v0, v0}, $DM_ACCOUNT_LISTENER->onAccountResult(IZI$USER)V",
            callback - 2 to "const/4 v1, 0x4",
            result to "move-result p1",
        )) {
            val changed = MutableMethod(targets.switch)
            changed.replaceInstruction(index, replacement)
            val changedOwner = ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces,
                owner.sourceFile, owner.annotations, owner.fields, owner.methods.filterNot { it == targets.switch } + changed)
            val loginBefore = targets.login.implementation!!.instructions.map(::signature)
            val logoutBefore = targets.logout.implementation!!.instructions.map(::signature)
            assertThrows(PatchException::class.java) { findDmAccountTargets { if (it == owner.type) changedOwner else classes[it] } }
            assertEquals(loginBefore, targets.login.implementation!!.instructions.map(::signature))
            assertEquals(logoutBefore, targets.logout.implementation!!.instructions.map(::signature))
        }
    }

    @Test fun `successful account switch broadcasts before its UI continuation while failure preserves false`() = withClasses { classes ->
        val callers = listOf(
            classes.getValue("LX/16Xe;").methods.single { it.name == "then" } to true,
            classes.getValue("LX/1DXe;").methods.single { it.name == "run" } to false,
            classes.getValue("LY/ARunnableS128S0100000_30_I1;").methods.single { it.name == "run\$26" } to true,
            classes.getValue("LY/ARunnableS72S0200000_30_I1;").methods.single { it.name == "LIZ\$19" } to true,
        )
        for ((method, success) in callers) {
            val code = method.implementation!!.instructions.toList()
            val index = code.indexOfFirst { it.getReference<MethodReference>()?.let { call ->
                call.definingClass == DM_ACCOUNT_BROADCASTER && call.name == "LJIIIZ"
            } == true }
            assertTrue(index >= 3)
            val call = code[index] as FiveRegisterInstruction
            assertEquals(2, call.registerCount)
            if (success) {
                assertEquals(call.registerD, (code[index - 1] as OneRegisterInstruction).registerA)
                assertEquals(1, (code[index - 1] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, code[index - 2].opcode)
                assertEquals(call.registerC, (code[index - 2] as OneRegisterInstruction).registerA)
                assertEquals("getCurUser", code[index - 3].getReference<MethodReference>()!!.name)
            } else {
                // The failure path loads its two zeros in either order (47.4.3 writes the flag first).
                val loads = (index - 2 until index).associate {
                    (code[it] as OneRegisterInstruction).registerA to (code[it] as NarrowLiteralInstruction).narrowLiteral
                }
                assertEquals(mapOf(call.registerC to 0, call.registerD to 0), loads)
            }
            if (method.definingClass == "LX/16Xe;") assertTrue(index < code.indexOfFirst {
                it.getReference<MethodReference>()?.let { continuation -> continuation.definingClass == "LX/16Xf;" && continuation.name == "LIZIZ" } == true
            })
        }
    }

    private fun constants(method: Method) = method.implementation!!.instructions.mapNotNull { (it as? NarrowLiteralInstruction)?.narrowLiteral }
    private fun signature(instruction: Instruction): List<Any?> = listOf(instruction.opcode,
        instruction.getReference<Reference>()?.toString(),
        (instruction as? OneRegisterInstruction)?.registerA, (instruction as? TwoRegisterInstruction)?.registerB,
        (instruction as? FiveRegisterInstruction)?.let { listOf(it.registerCount, it.registerC, it.registerD, it.registerE, it.registerF, it.registerG) },
        (instruction as? RegisterRangeInstruction)?.let { listOf(it.registerCount, it.startRegister) },
        (instruction as? NarrowLiteralInstruction)?.narrowLiteral, (instruction as? OffsetInstruction)?.codeOffset)

    private fun withClasses(block: (Map<String, ClassDef>) -> Unit) = Fixtures.forEachDeclared { apk ->
        block(cache.getOrPut(apk.canonicalPath) { owners(apk) })
    }
    private fun owners(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk)
        return listOf("classes2.dex", "classes32.dex", "classes35.dex").asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.associateBy { it.type }
    }
}
