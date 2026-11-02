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
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TRANSLATE_INTO_EXTENSION = "Lapp/morphe/extension/tiktok/translation/TranslateInto;"

/**
 * What Translate into hooks, held to each declared build: the translation service has exactly one
 * answer to which language to translate into (a no-argument instance method returning String that
 * respells zh-hans, zh-hant and fr-ca), and every value it returns goes through the extension
 * first, with TikTok's own instructions left in their order.
 */
class TranslateIntoAnchorsTest {
    @Test
    fun `each declared build has one target language answer and every return goes through the extension`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val answers = mutableListOf<Method>()
            var found = false
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type != TRANSLATION_SERVICE) continue
                    found = true
                    answers += classDef.methods.filter { it.isTargetLanguageAnswer() }
                }
            }
            assertTrue("$version: $TRANSLATION_SERVICE is missing", found)
            assertEquals("$version: target language answers: ${answers.map { it.name }}", 1, answers.size)

            val before = answers.single().implementation!!.instructions.toList()
            val returns = before.count { it.opcode == Opcode.RETURN_OBJECT }
            assertTrue("$version: the answer returns nothing", returns > 0)
            val hooked = MutableMethod(answers.single())
            hooked.sendTargetThroughExtension()
            val after = hooked.implementation!!.instructions.toList()
            assertEquals("$version: two instructions per return", before.size + 2 * returns, after.size)
            val added = mutableSetOf<Int>()
            after.forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.RETURN_OBJECT) return@forEachIndexed
                val register = (instruction as OneRegisterInstruction).registerA
                val call = after[index - 2]
                assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
                val target = call.getReference<MethodReference>()!!
                assertEquals(TRANSLATE_INTO_EXTENSION, target.definingClass)
                assertEquals("target", target.name)
                assertEquals("Ljava/lang/String;", target.returnType)
                assertEquals(listOf("Ljava/lang/String;"), target.parameterTypes.map { it.toString() })
                assertEquals(Opcode.MOVE_RESULT_OBJECT, after[index - 1].opcode)
                assertEquals("$version: the answer is moved back where it's returned from",
                    register, (after[index - 1] as OneRegisterInstruction).registerA)
                added += index - 2
                added += index - 1
            }
            assertEquals(
                "$version: TikTok's own instructions keep their order",
                before.map { it.opcode },
                after.filterIndexed { index, _ -> index !in added }.map { it.opcode },
            )
        }
    }

    @Test
    fun `only an instance method taking nothing that spells all three codes is the answer`() {
        assertTrue(answerLike("zh-hans", "zh-hant", "fr-ca").isTargetLanguageAnswer())
        assertFalse("one code short", answerLike("zh-hans", "zh-hant").isTargetLanguageAnswer())
        assertFalse("static", answerLike("zh-hans", "zh-hant", "fr-ca", static = true).isTargetLanguageAnswer())
        assertFalse("takes an argument",
            answerLike("zh-hans", "zh-hant", "fr-ca", parameter = "Ljava/lang/String;").isTargetLanguageAnswer())
    }

    private fun answerLike(vararg strings: String, static: Boolean = false, parameter: String? = null): Method {
        val instructions = strings.map {
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it))
        } + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        val access = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        return ImmutableMethod(
            TRANSLATION_SERVICE, "answer",
            listOfNotNull(parameter?.let { ImmutableMethodParameter(it, null, null) }),
            "Ljava/lang/String;", access, null, null,
            ImmutableMethodImplementation(3, instructions, null, null),
        )
    }
}
