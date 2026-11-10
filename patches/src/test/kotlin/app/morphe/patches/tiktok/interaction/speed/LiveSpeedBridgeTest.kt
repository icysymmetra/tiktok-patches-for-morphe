package app.morphe.patches.tiktok.interaction.speed

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two bridges the edge swipe's Speed choice reaches TikTok's player through, built the way the
 * patch builds them: the extension's stub cloned with two more registers, the body put in front.
 *
 * <p>The stubs are static, so the cloned frame is the stub's own registers (v0 and v1 here, both
 * free for the body) and then the parameters. The speed bridge has to hand the controller and the
 * speed to the player's setSpeed as two adjacent registers (a range call), write the selected video
 * and both current-speed fields the way the first-frame bridge does, and leave the stub's own
 * return as dead code behind its own.
 */
class LiveSpeedBridgeTest {
    private val extension = "Lapp/morphe/extension/tiktok/speed/PlaybackSpeedPatch;"
    private val controller = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val selection = "LX/08IB;"

    private val awemeGetter = ImmutableMethodReference(controller, "LLJJJIL", emptyList(), aweme)
    private val setSpeed = ImmutableMethodReference(controller, "LJJIJLIJ", listOf("F"), "V")
    private val awemeField = ImmutableFieldReference(selection, "LIZIZ", aweme)
    private val speedA = ImmutableFieldReference(selection, "LIZLLL", "F")
    private val speedB = ImmutableFieldReference(selection, "LIZJ", "F")

    @Test
    fun `the speed bridge selects the controller's video, both speeds and the speed, then returns`() {
        val stub = stub("setNativeSpeed", listOf("Ljava/lang/Object;", "F"), "V", "return-void")
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        val original = stub.implementation!!.instructions.toList()
        bridge.addInstructions(0, liveSpeedBridgeBody(controller, awemeGetter, awemeField, listOf(speedA, speedB), setSpeed))

        val code = bridge.implementation!!.instructions.toList()
        // The stub had v0 and v1 for p0 and p1; two more take the parameters' place: p0=v2, p1=v3.
        assertEquals(4, bridge.implementation!!.registerCount)
        assertEquals(
            listOf(
                Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.SPUT_OBJECT, Opcode.SPUT, Opcode.SPUT, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.RETURN_VOID,
                // The clone's own parameter copies and the stub's return, dead behind the first return.
                Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_FROM16, Opcode.RETURN_VOID,
            ),
            code.map { it.opcode },
        )
        assertEquals(original.size + 2 + 8, code.size)

        val cast = code[0] as OneRegisterInstruction
        assertEquals("p0 (v2) is the controller", 2, cast.registerA)
        assertEquals(controller, ((code[0] as ReferenceInstruction).reference as TypeReference).type)

        val getter = code[1] as RegisterRangeInstruction
        assertEquals(2, getter.startRegister)
        assertEquals(1, getter.registerCount)
        assertEquals(awemeGetter.toString(), ((code[1] as ReferenceInstruction).reference as MethodReference).toString())

        val video = (code[2] as OneRegisterInstruction).registerA
        assertTrue("the video goes in a scratch register below the parameters, got v$video", video < 2)
        assertEquals(video, (code[3] as OneRegisterInstruction).registerA)
        assertEquals(awemeField.toString(), ((code[3] as ReferenceInstruction).reference as FieldReference).toString())

        for ((at, field) in listOf(4 to speedA, 5 to speedB)) {
            assertEquals("the speed (p1, v3) is written at $at", 3, (code[at] as OneRegisterInstruction).registerA)
            assertEquals(field.toString(), ((code[at] as ReferenceInstruction).reference as FieldReference).toString())
        }

        val call = code[6] as RegisterRangeInstruction
        assertEquals("the controller and the speed sit side by side", 2, call.startRegister)
        assertEquals(2, call.registerCount)
        assertEquals(setSpeed.toString(), ((code[6] as ReferenceInstruction).reference as MethodReference).toString())
    }

    @Test
    fun `the speed bridge writes every speed field it is given and no other`() {
        val stub = stub("setNativeSpeed", listOf("Ljava/lang/Object;", "F"), "V", "return-void")
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        bridge.addInstructions(0, liveSpeedBridgeBody(controller, awemeGetter, awemeField, listOf(speedB), setSpeed))

        val writes = bridge.implementation!!.instructions.filter { it.opcode == Opcode.SPUT }
        assertEquals(listOf(speedB.toString()), writes.map { ((it as ReferenceInstruction).reference as FieldReference).toString() })
    }

    @Test
    fun `the video bridge answers the controller's own video from a scratch register`() {
        val stub = stub(
            "nativeAweme", listOf("Ljava/lang/Object;"), aweme,
            """
                const/4 v0, 0
                return-object v0
            """,
        )
        val bridge = stub.cloneMutable(additionalRegisters = 2)
        bridge.addInstructions(0, liveAwemeBridgeBody(controller, awemeGetter))

        val code = bridge.implementation!!.instructions.toList()
        // v0 was the stub's own local, v1 the added one; the parameter moves to v3 with the two added.
        assertEquals(4, bridge.implementation!!.registerCount)
        assertEquals(
            listOf(
                Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT,
                Opcode.MOVE_OBJECT_FROM16, Opcode.CONST_4, Opcode.RETURN_OBJECT,
            ),
            code.map { it.opcode },
        )

        val parameter = (code[0] as OneRegisterInstruction).registerA
        assertEquals("p0 is the last register", 3, parameter)
        assertEquals(controller, ((code[0] as ReferenceInstruction).reference as TypeReference).type)
        val getter = code[1] as RegisterRangeInstruction
        assertEquals(parameter, getter.startRegister)
        assertEquals(1, getter.registerCount)
        assertEquals(awemeGetter.toString(), ((code[1] as ReferenceInstruction).reference as MethodReference).toString())

        val answer = (code[2] as OneRegisterInstruction).registerA
        assertTrue("the answer sits below the parameter, got v$answer", answer < parameter)
        assertEquals("the video that came back is the one returned", answer, (code[3] as OneRegisterInstruction).registerA)
    }

    private fun stub(name: String, parameters: List<String>, returnType: String, body: String): MutableMethod {
        val registers = parameters.size + if (returnType == "V") 0 else 1
        return MutableMethod(
            ImmutableMethod(
                extension, name,
                parameters.map { ImmutableMethodParameter(it, null, null) },
                returnType, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { appendFixtureInstructions(body) }
    }
}
