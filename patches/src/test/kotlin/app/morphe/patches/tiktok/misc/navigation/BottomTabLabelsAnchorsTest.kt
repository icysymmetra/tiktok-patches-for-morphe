/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TUX_TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"

/**
 * The names under the bottom tab icons. Every site is found from the tab icon (the class
 * TabBadgeAnchorsTest pins down) the way the patch finds it, held to every declared build, and
 * each hook is applied to the real method it would patch.
 */
class BottomTabLabelsAnchorsTest {
    @Test
    fun `each declared build reaches the tab tag and the one store of the name view from the tab icon`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val icon = app.values.single { classDef -> classDef.methods.any { it.name == "setCountDotVisibility" } }
            val sites = bottomTabLabelSites(icon) { app[it] }

            val dataType = icon.methods.single { it.name == "getIconData" }.returnType
            assertEquals("$version: icon constructor", listOf(dataType), sites.constructor.parameterTypes.map(CharSequence::toString))
            assertEquals("$version: tag field", "Ljava/lang/String;", sites.tag.type)
            assertEquals("$version: tag owner", app.getValue(dataType).superclass, sites.tag.definingClass)

            // The name view the icon's setters use is stored by the tab logic and nowhere else.
            val title = icon.methods.single { it.name == "setTitleText" }.implementation!!.instructions
                .first { it.opcode == Opcode.IGET_OBJECT }.getReference<FieldReference>()!!
            assertEquals(TUX_TEXT_VIEW, title.type)
            val writers = app.values.flatMap { classDef ->
                classDef.methods.filter { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.IPUT_OBJECT &&
                            instruction.getReference<FieldReference>()?.let { it.definingClass == title.definingClass && it.name == title.name } == true
                    } == true
                }.map { "${classDef.type}->${it.name}" }
            }
            assertEquals(
                "$version: name view writers",
                listOf("${sites.labelSetter.definingClass}->${sites.labelSetter.name}"),
                writers,
            )
            assertEquals(
                "$version: the store isn't in the tab logic",
                icon.methods.single { it.name == "setIconTabLogic" }.parameterTypes.single().toString(),
                sites.labelSetter.definingClass,
            )

            // The store puts a TuxTextView the tab logic just made onto the logic's own icon.
            val code = sites.labelSetter.implementation!!.instructions.toList()
            val before = code.subList(0, sites.labelStore)
            // Writes only: the label's null check reads the same register between its load and the store.
            fun writes(register: Int) = { instruction: Instruction ->
                instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == register
            }
            val iconLoad = before.last(writes(sites.icon))
            assertEquals("$version: what the store goes on", icon.type, iconLoad.getReference<FieldReference>()?.type)
            val labelLoad = before.indexOfLast(writes(sites.label))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, before[labelLoad].opcode)
            assertEquals("$version: what the store holds", TUX_TEXT_VIEW, before[labelLoad - 1].getReference<MethodReference>()?.returnType)

            checkTagHook(version, sites)
            checkLabelHook(version, sites)
        }
    }

    private fun checkTagHook(version: String, sites: BottomTabLabelSites) {
        val constructor = MutableMethod(sites.constructor)
        val registers = constructor.implementation!!.registerCount
        val size = constructor.implementation!!.instructions.count()
        constructor.reportBottomTab(sites.tag)
        val after = constructor.implementation!!.instructions.toList()
        assertEquals(size + 2, after.size)
        // One return, though not always last: R8 can move a branch's tail past it (47.1.4 has a
        // const and a goto back after the return-void).
        val end = after.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
        assertEquals("$version: returns", 1, after.count { it.opcode == Opcode.RETURN_VOID })
        assertEquals(
            "$version: the tag hook",
            listOf(Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID),
            after.subList(end - 2, end + 1).map { it.opcode },
        )
        val read = after[end - 2] as TwoRegisterInstruction
        assertEquals(0, read.registerA)
        assertEquals("$version: the tag is read off the icon data", registers - 1, read.registerB)
        assertEquals(sites.tag.name, after[end - 2].getReference<FieldReference>()!!.name)
        val call = after[end - 1] as FiveRegisterInstruction
        assertEquals(2, call.registerCount)
        assertEquals("$version: the icon handed over", registers - 2, call.registerC)
        assertEquals(0, call.registerD)
        val target = after[end - 1].getReference<MethodReference>()!!
        assertEquals(BOTTOM_TAB_LABELS_EXTENSION, target.definingClass)
        assertEquals("tabCreated", target.name)
        assertEquals(listOf("Landroid/view/View;", "Ljava/lang/String;"), target.parameterTypes.map(CharSequence::toString))
    }

    private fun checkLabelHook(version: String, sites: BottomTabLabelSites) {
        val setter = MutableMethod(sites.labelSetter)
        val size = setter.implementation!!.instructions.count()
        setter.reportBottomTabLabel(sites)
        val after = setter.implementation!!.instructions.toList()
        assertEquals(size + 1, after.size)
        assertEquals(Opcode.IPUT_OBJECT, after[sites.labelStore].opcode)
        val call = after[sites.labelStore + 1] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, after[sites.labelStore + 1].opcode)
        assertEquals(2, call.registerCount)
        assertEquals("$version: the icon handed over", sites.icon, call.registerC)
        assertEquals("$version: the name view handed over", sites.label, call.registerD)
        val target = after[sites.labelStore + 1].getReference<MethodReference>()!!
        assertEquals(BOTTOM_TAB_LABELS_EXTENSION, target.definingClass)
        assertEquals("labelCreated", target.name)
        assertEquals(listOf("Landroid/view/View;", "Landroid/widget/TextView;"), target.parameterTypes.map(CharSequence::toString))
    }

    @Test
    fun `a tab logic that sets the name view twice or never leaves the names out`() {
        val apk = Fixtures.declared().firstOrNull { it.isFile } ?: return
        val app = load(apk)
        val icon = app.values.single { classDef -> classDef.methods.any { it.name == "setCountDotVisibility" } }
        val sites = bottomTabLabelSites(icon) { app[it] }
        val logic = app.getValue(sites.labelSetter.definingClass)

        val never = logic.withMethods(logic.methods.filterNot { it.name == sites.labelSetter.name && it.parameterTypes == sites.labelSetter.parameterTypes })
        val twice = logic.withMethods(logic.methods + sites.labelSetter.renamed("again"))
        for ((broken, count) in listOf(never to 0, twice to 2)) {
            val failure = assertThrows(PatchException::class.java) {
                bottomTabLabelSites(icon) { if (it == logic.type) broken else app[it] }
            }
            assertTrue(failure.message, failure.message!!.contains("sets the name view $count times"))
        }
    }

    @Test
    fun `an icon constructor that reuses its view or data register leaves the names out`() {
        val apk = Fixtures.declared().firstOrNull { it.isFile } ?: return
        val app = load(apk)
        val icon = app.values.single { classDef -> classDef.methods.any { it.name == "setCountDotVisibility" } }
        val constructor = bottomTabLabelSites(icon) { app[it] }.constructor
        for (register in listOf("p0", "p1")) {
            val broken = icon.withMethods(icon.methods.map { method ->
                if (method != constructor) return@map method
                MutableMethod(method).apply { addInstructionsWithLabels(0, "const/4 $register, 0x0") }
            })
            val failure = assertThrows(PatchException::class.java) { bottomTabLabelSites(broken) { app[it] } }
            assertTrue(failure.message, failure.message!!.contains("reuses its view or data register"))
        }
    }

    private fun ClassDef.withMethods(methods: Iterable<Method>) = ImmutableClassDef(
        type, accessFlags, superclass, interfaces, sourceFile, annotations, fields, methods,
    )

    private fun Method.renamed(name: String) = ImmutableMethod(
        definingClass, name, parameters, returnType, accessFlags, annotations, hiddenApiRestrictions, implementation,
    )

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
