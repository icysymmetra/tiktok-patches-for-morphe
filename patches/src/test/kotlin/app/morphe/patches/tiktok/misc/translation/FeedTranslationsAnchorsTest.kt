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
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What keeps See translation on videos (#143), held to each declared build: TikTok reads
 * `feed_translation_reverse` once, handing the App AB int getter the key as its third argument,
 * and that answer goes through the extension before TikTok keeps it. The key's other places in
 * the app (the list of every AB value, a debug map) aren't reads and stay as they are.
 */
class FeedTranslationsAnchorsTest {
    @Test
    fun `each declared build reads the reverse value once and the answer goes through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val readers = mutableListOf<Method>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    readers += classDef.methods.filter { it.feedTranslationReverseRead() != null }
                }
            }
            assertEquals("$version: methods that read $FEED_TRANSLATION_REVERSE", 1, readers.size)
            val reader = readers.single()
            assertEquals("$version: the read is a lazy's invoke", "Ljava/lang/Object;", reader.returnType)
            assertEquals("$version: the read is a lazy's invoke", 0, reader.parameterTypes.size)

            val move = reader.feedTranslationReverseRead()!!
            val before = reader.implementation!!.instructions.toList()
            val hooked = MutableMethod(reader)
            hooked.keepTranslationOnVideos()
            val after = hooked.implementation!!.instructions.toList()
            assertEquals("$version: two instructions", before.size + 2, after.size)
            val register = (before[move] as OneRegisterInstruction).registerA
            assertAnswerGoesThroughTheExtension(version, after, move + 1, register)
            assertEquals(
                "$version: TikTok's own instructions keep their order",
                before.map { it.opcode },
                after.filterIndexed { index, _ -> index != move + 1 && index != move + 2 }.map { it.opcode },
            )
        }
    }

    @Test
    fun `the key handed to the int getter is the read`() {
        assertEquals(3, reader(keyRegister = 3).feedTranslationReverseRead())
        val hooked = MutableMethod(reader(keyRegister = 3))
        hooked.keepTranslationOnVideos()
        assertAnswerGoesThroughTheExtension("synthetic", hooked.implementation!!.instructions.toList(), 4, 0)
    }

    @Test
    fun `anything else is not`() {
        assertNull("the key in another argument", reader(keyRegister = 2).feedTranslationReverseRead())
        assertNull("another key", reader(keyRegister = 3, key = "feed_translation_prefetch").feedTranslationReverseRead())
        assertNull("a getter of another shape", reader(keyRegister = 3, getterReturns = "Z").feedTranslationReverseRead())
        assertNull("nothing takes the answer", reader(keyRegister = 3, takesAnswer = false).feedTranslationReverseRead())
    }

    private fun assertAnswerGoesThroughTheExtension(version: String, after: List<Instruction>, at: Int, register: Int) {
        val call = after[at]
        assertEquals("$version: the answer goes to the extension", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val target = call.getReference<MethodReference>()!!
        assertEquals(LANGUAGE_PAGE_TRANSLATIONS, target.definingClass)
        assertEquals("feedReverse", target.name)
        assertEquals("I", target.returnType)
        assertEquals(listOf("I"), target.parameterTypes.map { it.toString() })
        assertEquals(Opcode.MOVE_RESULT, after[at + 1].opcode)
        assertEquals("$version: the answer goes back where TikTok reads it",
            register, (after[at + 1] as OneRegisterInstruction).registerA)
    }

    /**
     * The lazy in miniature, as 47.1.4 has it: the key and the getter's other arguments loaded,
     * `invoke-virtual {v4, v1, v0, v3, v2}` on the int getter, then the answer moved into v0.
     * [keyRegister] is the register the key is loaded into; 3 is the getter's key argument.
     */
    private fun reader(
        keyRegister: Int,
        key: String = FEED_TRANSLATION_REVERSE,
        getterReturns: String = "I",
        takesAnswer: Boolean = true,
    ): Method {
        val instructions = mutableListOf<Instruction>()
        instructions += ImmutableInstruction21c(Opcode.CONST_STRING, keyRegister, ImmutableStringReference(key))
        instructions += ImmutableInstruction11n(Opcode.CONST_4, 1, 0)
        instructions += ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 5, 4, 1, 0, 3, 2,
            ImmutableMethodReference("LX/appAb;", "LJIIJJI", listOf("I", "I", "Ljava/lang/String;", "Z"), getterReturns),
        )
        instructions += if (takesAnswer) ImmutableInstruction11x(Opcode.MOVE_RESULT, 0)
        else ImmutableInstruction11x(Opcode.RETURN, 1)
        instructions += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        return ImmutableMethod(
            "LX/reverseLazy;", "invoke", listOf(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(5, instructions, null, null),
        )
    }
}
