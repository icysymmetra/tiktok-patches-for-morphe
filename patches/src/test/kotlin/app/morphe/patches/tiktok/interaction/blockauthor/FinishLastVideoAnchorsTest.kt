/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.Fixtures
import app.morphe.patches.tiktok.interaction.resume.FeedPlayCompletedFingerprint
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PAGER = "Lcom/ss/android/ugc/aweme/common/widget/VerticalViewPager;"
private const val PLAYER_CONTROLLER = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"

/**
 * Let the last video finish answers false at the start of the feed pager's two touch methods.
 * That is only TikTok's own behaviour, and not a new one, while TikTok's setDisableScroll stores
 * a flag both methods read first and answer false for. Held to each declared build, with the
 * completion callback that ends the wait.
 */
class FinishLastVideoAnchorsTest {
    @Test
    fun `the pager's own disable-scroll answer is the one the hold gives`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = wanted(apk)
            val pager = classes[PAGER] ?: error("$version: no VerticalViewPager")

            val disable = pager.methods.single { it.name == "setDisableScroll" && it.parameterTypes == listOf("Z") }
            val store = disable.implementation!!.instructions.first()
            assertEquals("$version: setDisableScroll no longer stores its flag first", Opcode.IPUT_BOOLEAN, store.opcode)
            val flag = store.getReference<FieldReference>()!!
            assertEquals(PAGER, flag.definingClass)

            for (name in listOf("onInterceptTouchEvent", "onTouchEvent")) {
                val method = pager.methods.single {
                    it.name == name && it.parameterTypes == listOf("Landroid/view/MotionEvent;") && it.returnType == "Z"
                }
                val problem = falseAnswerProblem(method, flag)
                assertTrue("$version: $name doesn't answer false for disabled scrolling the way the " +
                    "hold does: $problem. It opens with " +
                    method.implementation!!.instructions.take(6).joinToString { it.opcode.name }, problem == null,
                )
            }
        }
    }

    @Test
    fun `the completion that ends the wait resolves on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val controller = wanted(apk)[PLAYER_CONTROLLER] ?: error("$version: no PlayerController")
            val completed = controller.methods.filter { FeedPlayCompletedFingerprint.takes(it, controller) }
            assertEquals("$version: onPlayCompleted(String) takes ${completed.size}", 1, completed.size)
            assertNotNull(completed.single().implementation)
        }
    }

    /**
     * Reads the flag in the first few instructions and, when it is set, returns a register the
     * method has just set to zero: `iget-boolean vA, p0, flag`, `const/4 vR, 0`, `if-eqz vA`,
     * `return vR`, in that order with nothing else between the branch and the return.
     *
     * @return null when it does, or what was found instead
     */
    private fun falseAnswerProblem(method: Method, flag: FieldReference): String? {
        val body = method.implementation!!.instructions.toList()
        val read = body.take(6).indexOfFirst {
            it.opcode == Opcode.IGET_BOOLEAN && it.getReference<FieldReference>() == flag
        }
        if (read < 0) return "no read of $flag in the first six"
        val readRegister = (body[read] as TwoRegisterInstruction).registerA
        // Opcode.name is dexlib2's smali mnemonic field, "if-eqz", not the enum constant's name.
        val branch = (read + 1 until body.size).firstOrNull { body[it].opcode.name.startsWith("if-") }
            ?: return "no branch after the read"
        if (body[branch].opcode != Opcode.IF_EQZ || (body[branch] as OneRegisterInstruction).registerA != readRegister) {
            return "the branch after the read is ${body[branch].opcode} rather than if-eqz on v$readRegister"
        }
        val exit = body[branch + 1]
        if (exit.opcode != Opcode.RETURN) return "the flag set leads to ${exit.opcode}"
        val answer = (exit as OneRegisterInstruction).registerA
        val zeroed = (read + 1 until branch).any {
            val zero = body[it]
            zero.opcode == Opcode.CONST_4 && (zero as OneRegisterInstruction).registerA == answer &&
                (zero as NarrowLiteralInstruction).narrowLiteral == 0
        }
        return if (zeroed) null else "v$answer isn't set to zero between the read and the branch"
    }

    private fun wanted(apk: java.io.File): Map<String, ClassDef> {
        val found = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type == PAGER || classDef.type == PLAYER_CONTROLLER) found.putIfAbsent(classDef.type, classDef)
            }
            if (found.size == 2) break
        }
        return found
    }
}
