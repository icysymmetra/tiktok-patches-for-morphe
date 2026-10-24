package app.morphe.patches.tiktok.interaction.systemfont

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the "Use system emoji" switch inside "Use system font" rests on, held to each declared
 * TikTok build.
 *
 * The patch answers true first in androidx EmojiCompat's glyph check. This pins that the patch's
 * own rule finds exactly one such check, with a local register for the guard to write; that every
 * call to it hands the emoji to the span callback only when it answers false, so a true answer
 * leaves the emoji to the device's font; and that TikTok starts EmojiCompat at all, from its
 * EmojiTask, with the config that asks the system font provider for the downloadable emoji font.
 * How the unwrapped emoji then looks is the device check.
 */
class SystemEmojiAnchorsTest {
    @Test
    fun `there is one emoji glyph check on each build, with a local for the guard`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val checks = appClassesOf(apk).flatMap { owner -> owner.methods.filter(::isEmojiGlyphCheck) }
            assertEquals("$version: glyph checks ${checks.map { it.describe() }}", 1, checks.size)

            val check = checks.single()
            val registers = check.implementation!!.registerCount
            val locals = registers - MethodUtil.getParameterRegisterCount(check)
            assertTrue("$version: ${check.describe()} has $locals locals", locals >= 1)
        }
    }

    @Test
    fun `an emoji is wrapped only when the glyph check answers false on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = appClassesOf(apk)
            val check = classes.flatMap { owner -> owner.methods.filter(::isEmojiGlyphCheck) }.single()
            val checkParameters = check.parameterTypes.map { it.toString() }

            var calls = 0
            classes.flatMap { owner -> owner.methods }.forEach { caller ->
                val instructions = caller.implementation?.instructions?.toList().orEmpty()
                instructions.forEachIndexed { index, instruction ->
                    if (!instruction.calls(check)) return@forEachIndexed
                    calls++
                    val where = "$version: ${caller.describe()} calls the glyph check at $index"
                    val result = instructions.getOrNull(index + 1)
                    val branch = instructions.getOrNull(index + 2)
                    assertEquals("$where, then not move-result", Opcode.MOVE_RESULT, result?.opcode)
                    assertEquals("$where, then not if-nez on its answer", Opcode.IF_NEZ, branch?.opcode)
                    assertEquals(
                        "$where, and branches on another register",
                        (result as OneRegisterInstruction).registerA,
                        (branch as OneRegisterInstruction).registerA,
                    )
                    // Falling through, on false, the emoji goes to the span callback, which takes
                    // the same text, range and emoji the check was asked about.
                    val wrap = instructions.subList(index + 3, minOf(index + 7, instructions.size)).firstOrNull {
                        it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE
                    }
                    assertNotNull("$where, and no span callback follows a false answer", wrap)
                    val callback = (wrap as ReferenceInstruction).reference as MethodReference
                    assertEquals(
                        "$where, and the callback after it takes ${callback.parameterTypes}",
                        checkParameters,
                        callback.parameterTypes.map { it.toString() },
                    )
                }
            }
            assertTrue("$version: nothing calls the glyph check", calls > 0)
        }
    }

    @Test
    fun `TikTok starts EmojiCompat with the downloadable emoji font on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = appClassesOf(apk)

            val factories = classes.flatMap { owner -> owner.methods }.filter { method ->
                val strings = method.strings()
                EMOJI_FONT_QUERY in strings && LOAD_EMOJI_FONT in strings
            }
            assertTrue("$version: no EmojiCompat config asks for $EMOJI_FONT_QUERY", factories.isNotEmpty())

            val task = classes.firstOrNull { it.type == EMOJI_TASK }
            assertNotNull("$version: $EMOJI_TASK is gone", task)
            val run = task!!.methods.firstOrNull { method ->
                method.name == "run" && method.parameterTypes.map { it.toString() } == listOf(CONTEXT)
            }
            assertNotNull("$version: $EMOJI_TASK has no run(Context)", run)
            val startsWithFactory = run!!.implementation?.instructions?.toList().orEmpty().any { instruction ->
                factories.any { factory -> instruction.calls(factory) }
            }
            assertTrue("$version: $EMOJI_TASK no longer builds the downloadable-font config", startsWithFactory)
        }
    }

    private companion object {
        const val EMOJI_TASK = "Lcom/ss/android/ugc/aweme/legoImp/task/EmojiTask;"
        const val CONTEXT = "Landroid/content/Context;"
        const val EMOJI_FONT_QUERY = "emojicompat-emoji-font"
        const val LOAD_EMOJI_FONT = "androidx.content.action.LOAD_EMOJI_FONT"

        fun appClassesOf(apk: File): List<ClassDef> {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            return container.dexEntryNames
                .flatMap { entry -> container.getEntry(entry)!!.dexFile.classes }
                .filterNot { it.type.startsWith("Lapp/morphe/extension/") }
        }

        fun Method.describe() = "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"

        fun Method.strings(): Set<String> = implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }.toSet()

        fun Instruction.calls(target: Method): Boolean {
            val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
            return reference.definingClass == target.definingClass && reference.name == target.name &&
                reference.returnType == target.returnType &&
                reference.parameterTypes.map { it.toString() } == target.parameterTypes.map { it.toString() }
        }
    }
}
