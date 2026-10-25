/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Show translation options in Language settings hooks (#143), held to each declared build:
 * TikTok's Language page reads two gates in a row at the top of onViewCreated, each a static
 * `()Z` that skips the state update showing the translation group when true, both skipping to
 * the same place. Each answer goes through the extension before the page branches on it, with
 * TikTok's own instructions left in their order.
 */
class LanguagePageTranslationsAnchorsTest {
    @Test
    fun `each declared build reads both gates on the Language page and each answer goes through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val created = mutableListOf<Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type != LANGUAGE_PAGE) continue
                    created += classDef.methods.filter { it.name == "onViewCreated" }
                }
            }
            assertEquals("$version: onViewCreated in $LANGUAGE_PAGE", 1, created.size)

            val reads = created.single().languagePageGateReads()
            assertEquals("$version: the Language page's gate reads", 2, reads.size)

            val before = created.single().implementation!!.instructions.toList()
            val hooked = MutableMethod(created.single())
            hooked.showLanguagePageTranslations()
            val after = hooked.implementation!!.instructions.toList()
            assertEquals("$version: two instructions per gate", before.size + 4, after.size)

            // The first gate's move-result keeps its index; the second's moves two along.
            val added = mutableSetOf<Int>()
            reads.forEachIndexed { number, original ->
                val move = original + 2 * number
                assertEquals(Opcode.MOVE_RESULT, after[move].opcode)
                val register = (after[move] as OneRegisterInstruction).registerA
                assertGateAnswerGoesThroughTheExtension(version, after, move + 1, register)
                assertEquals("$version: TikTok still branches on the answer", Opcode.IF_NEZ, after[move + 3].opcode)
                assertEquals(register, (after[move + 3] as OneRegisterInstruction).registerA)
                added += move + 1
                added += move + 2
            }
            assertEquals(
                "$version: TikTok's own instructions keep their order",
                before.map { it.opcode },
                after.filterIndexed { index, _ -> index !in added }.map { it.opcode },
            )
        }
    }

    @Test
    fun `two gates skipping to the same place before the state update are the hook point`() {
        assertEquals(listOf(1, 4), page(secondSkip = 6).languagePageGateReads())
        val hooked = MutableMethod(page(secondSkip = 6))
        hooked.showLanguagePageTranslations()
        val after = hooked.implementation!!.instructions.toList()
        assertGateAnswerGoesThroughTheExtension("synthetic", after, 2, 0)
        assertGateAnswerGoesThroughTheExtension("synthetic", after, 7, 0)
    }

    @Test
    fun `anything else is not`() {
        assertTrue("the gates skip to different places", page(secondSkip = 3).languagePageGateReads().isEmpty())
        assertTrue("only one gate", page(secondSkip = 6, gates = 1).languagePageGateReads().isEmpty())
        assertTrue("no state update after them", page(secondSkip = 6, update = "update").languagePageGateReads().isEmpty())
        assertTrue("a gate that takes an argument", page(secondSkip = 6, gateParameter = "I").languagePageGateReads().isEmpty())
    }

    private fun assertGateAnswerGoesThroughTheExtension(version: String, after: List<Instruction>, at: Int, register: Int) {
        val call = after[at]
        assertEquals("$version: the answer goes to the extension", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val target = call.getReference<MethodReference>()!!
        assertEquals(LANGUAGE_PAGE_TRANSLATIONS, target.definingClass)
        assertEquals("hidden", target.name)
        assertEquals("Z", target.returnType)
        assertEquals(listOf("Z"), target.parameterTypes.map { it.toString() })
        assertEquals(Opcode.MOVE_RESULT, after[at + 1].opcode)
        assertEquals("$version: the answer goes back where TikTok reads it",
            register, (after[at + 1] as OneRegisterInstruction).registerA)
    }

    /**
     * The page's shape in miniature: two gate reads, then the state update, then the place both
     * skip to. [secondSkip] is the second branch's offset in code units; 6 lands on return-void,
     * where the first one lands.
     */
    private fun page(secondSkip: Int, gates: Int = 2, update: String = "setState", gateParameter: String? = null): Method {
        fun gate(name: String) = ImmutableInstruction35c(
            Opcode.INVOKE_STATIC, if (gateParameter == null) 0 else 1, 0, 0, 0, 0, 0,
            ImmutableMethodReference("LX/gate;", name, listOfNotNull(gateParameter), "Z"),
        )
        val instructions = mutableListOf<Instruction>()
        // invoke-static (3) at 0, move-result (1) at 3, if-nez (2) at 4
        instructions += gate("first")
        instructions += ImmutableInstruction11x(Opcode.MOVE_RESULT, 0)
        instructions += ImmutableInstruction21t(Opcode.IF_NEZ, 0, if (gates == 2) 12 else 6)
        if (gates == 2) {
            // invoke-static at 6, move-result at 9, if-nez at 10
            instructions += gate("second")
            instructions += ImmutableInstruction11x(Opcode.MOVE_RESULT, 0)
            instructions += ImmutableInstruction21t(Opcode.IF_NEZ, 0, secondSkip)
        }
        // const/4 (1), invoke-virtual (3), then return-void
        instructions += ImmutableInstruction11n(Opcode.CONST_4, 0, 5)
        instructions += ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0,
            ImmutableMethodReference(
                "Lcom/bytedance/assem/arch/viewModel/AssemViewModel;", update,
                listOf("Lkotlin/jvm/functions/Function1;"), "V",
            ),
        )
        instructions += ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableMethod(
            LANGUAGE_PAGE, "onViewCreated",
            listOf(ImmutableMethodParameter("Landroid/view/View;", null, null),
                ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(4, instructions, null, null),
        )
    }
}
