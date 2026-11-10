package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * How the page site's mark picks the scene register, on bodies shaped the way a host build could
 * shape them. The mark goes before the struct's constructor, and the register the play call reads
 * the scene from holds the struct there on every fixture, so the mark may only take a register it
 * can show holds the int scene at the constructor: the source of a plain copy, or a parameter
 * nothing writes. Anything else has to stop the build rather than emit a call that fails
 * verification of TikTok's comment list class.
 */
class CommentPageSceneRegisterTest {
    private val struct = "Lcom/ss/android/ugc/aweme/comment/model/CommentSurpriseStruct;"
    private val constructor = ImmutableInstruction35c(
        Opcode.INVOKE_DIRECT, 3, 0, 1, 2, 0, 0,
        ImmutableMethodReference(struct, "<init>", listOf("Lcom/ss/android/ugc/aweme/comment/model/Comment;", "Lcom/ss/android/ugc/aweme/comment/model/CommentSurprise;", "Z"), "V"),
    )

    /** (this, struct, scene, string) with the scene in [scene]. */
    private fun play(scene: Int) = ImmutableInstruction35c(
        Opcode.INVOKE_VIRTUAL, 4, 5, 0, scene, 6, 0,
        ImmutableMethodReference("LX/0Gsw;", "LIZ", listOf(struct, "I", "Ljava/lang/String;"), "V"),
    )

    /** The same call as a range invoke, which is how a body passes registers past v15: (this, struct, scene, string) from [scene] - 2. */
    private fun playRange(scene: Int) = ImmutableInstruction3rc(
        Opcode.INVOKE_VIRTUAL_RANGE, scene - 2, 4,
        ImmutableMethodReference("LX/0Gsw;", "LIZ", listOf(struct, "I", "Ljava/lang/String;"), "V"),
    )

    private fun body(vararg between: Instruction, scene: Int): List<Instruction> =
        listOf(constructor) + between.toList() + (if (scene > 15) playRange(scene) else play(scene))

    @Test
    fun `a plain copy after the constructor is followed back to its source`() {
        val instructions = body(ImmutableInstruction22x(Opcode.MOVE_FROM16, 0, 39), scene = 0)
        assertEquals(39, commentPageSceneRegister(instructions, 0, instructions.size - 1, 30))
    }

    @Test
    fun `a copy whose source was rewritten after the constructor stops the build`() {
        val instructions = body(
            ImmutableInstruction11n(Opcode.CONST_4, 9, 2),
            ImmutableInstruction22x(Opcode.MOVE_FROM16, 0, 9),
            scene = 0,
        )
        assertRefused(instructions, 30)
    }

    @Test
    fun `an untouched parameter handed straight to the play call is taken as it is`() {
        val instructions = body(ImmutableInstruction10x(Opcode.NOP), scene = 35)
        assertEquals(35, commentPageSceneRegister(instructions, 0, instructions.size - 1, 30))
    }

    @Test
    fun `a register that is not a parameter and was never copied stops the build`() {
        val instructions = body(ImmutableInstruction10x(Opcode.NOP), scene = 0)
        assertRefused(instructions, 30)
    }

    @Test
    fun `a parameter written before the play call stops the build`() {
        val instructions = listOf(ImmutableInstruction11n(Opcode.CONST_4, 3, 1)) +
            body(ImmutableInstruction10x(Opcode.NOP), scene = 3)
        assertRefused(instructions, 3, constructorIndex = 1)
    }

    @Test
    fun `a scene that reaches the play call some other way than a copy stops the build`() {
        val instructions = body(ImmutableInstruction11n(Opcode.CONST_4, 0, 5), scene = 0)
        assertRefused(instructions, 30)
    }

    private fun assertRefused(instructions: List<Instruction>, firstParameter: Int, constructorIndex: Int = 0) {
        try {
            val register = commentPageSceneRegister(instructions, constructorIndex, instructions.size - 1, firstParameter)
            fail("the mark was given v$register, which nothing shows holds the scene at the constructor")
        } catch (expected: PatchException) {
            // The build stops with a reason instead.
        }
    }
}
