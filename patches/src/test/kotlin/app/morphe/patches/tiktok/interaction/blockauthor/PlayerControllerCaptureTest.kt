package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerControllerCaptureTest {
    @Test
    fun `progress captures its controller and aid without overwriting wide parameters`() {
        val controller = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
        val method = MutableMethod(
            ImmutableMethod(
                controller, "onPlayProgressChange",
                listOf("Ljava/lang/String;", "J", "J").map { ImmutableMethodParameter(it, null, null) },
                "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(37, emptyList(), null, null),
            ),
        ).apply {
            // The native 46.2.3 frame has p0=v31, p1=v32, p2/p3=v33/v34, p4/p5=v35/v36.
            // Retain its opening wide moves and position write to expose any local clobber.
            appendFixtureInstructions(
                """
                    move-wide/from16 v11, p4
                    move-wide/from16 v0, p2
                    move-object/from16 v4, p0
                    iput-wide v0, v4, $controller->lastPosition:J
                    return-void
                """,
            )
        }
        val original = method.implementation!!.instructions.toList()

        method.capturePlayingAweme()

        val implementation = method.implementation!!
        val instructions = implementation.instructions.toList()
        assertEquals(37, implementation.registerCount)
        assertEquals(original.size + 1, instructions.size)
        for (index in original.indices) assertSame(original[index], instructions[index + 1])
        val call = instructions.first()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val range = call as RegisterRangeInstruction
        assertEquals(31, range.startRegister)
        assertEquals(2, range.registerCount)
        val target = (call as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/blockauthor/BlockAuthorPatch;", target.definingClass)
        assertEquals("setPlayingAweme", target.name)
        assertEquals(listOf("Ljava/lang/Object;", "Ljava/lang/String;"), target.parameterTypes.map(CharSequence::toString))
        assertEquals("V", target.returnType)
    }
}
