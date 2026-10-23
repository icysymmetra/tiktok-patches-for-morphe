package app.morphe.patches.tiktok.misc.follow

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FollowDiagnosticsCallTest {
    private val userService = "Lcom/ss/android/ugc/aweme/userservice/api/IUserService;"
    private val followStatus = "Lcom/ss/android/ugc/aweme/profile/model/FollowStatus;"
    private val string = "Ljava/lang/String;"
    private val integer = "Ljava/lang/Integer;"
    private val commonParameters = listOf("I", "I", "I", "I") + List(5) { string } + "Ljava/util/Map;"
    private val jediParameters = listOf(string, string, "I", "I", integer, string, integer, string, string, string, integer)
    private val diagnostics = "Lapp/morphe/extension/tiktok/follow/FollowDiagnostics;"

    @Test
    fun `detailed user service delegates without an outer logger that treats integers as strings`() {
        // LX/07mn.subscribe uses v3..v13. v4 is action, v5 is from, and v6 is channel.
        val method = caller(
            "invoke-interface/range {v3 .. v13}, $userService->LJ(${commonParameters.joinToString("")})$followStatus",
        )
        assertUnchanged(method)
    }

    @Test
    fun `simple user service delegates to the common hook without another diagnostic request`() {
        assertUnchanged(caller("invoke-interface {v0, v1, v2, v3}, $userService->LIZLLL(I$string$string)$followStatus"))
    }

    @Test
    fun `user observation stream is never treated as a follow request`() {
        for (invoke in listOf("invoke-interface {v1, v2}", "invoke-interface/range {v1 .. v2}")) {
            assertUnchanged(caller("$invoke, $userService->LJFF($string)LX/0zX6;"))
        }
    }

    @Test
    fun `both native Jedi branches log their eleven arguments and complete with the returned stream`() {
        // LX/0lLL.requestActual has these two invoke ranges, both followed by move-result-object v1.
        for (firstRegister in listOf(0, 2)) {
            val method = caller(
                "invoke-interface/range {v$firstRegister .. v${firstRegister + 11}}, " +
                    "Lcom/ss/android/ugc/aweme/userservice/jedi/model/JediFollowApi;->" +
                    "followWithRetrofitPost(${jediParameters.joinToString("")})LX/0zX6;",
                resultRegister = 1,
            )
            val original = method.implementation!!.instructions.toList()
            instrumentCalls(method)
            val after = method.implementation!!.instructions.toList()
            assertEquals(original.size + 2, after.size)
            assertSame(original[0], after[1])
            assertSame(original[1], after[2])
            assertSame(original[2], after[4])
            assertLogger(after[0], "logJediFollowRequest", jediParameters, firstRegister + 1, 11)
            assertLogger(after[3], "logFollowStream", listOf("Ljava/lang/Object;"), 1, 1)
        }
    }

    @Test
    fun `common API logs actual parameters and every returned follow status`() {
        val method = MutableMethod(
            ImmutableMethod(
                "Lcom/ss/android/ugc/aweme/userservice/CommonFollowApi;", "LIZ",
                commonParameters.map { ImmutableMethodParameter(it, null, null) }, followStatus,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(15, emptyList(), null, null),
            ),
        )
        method.appendFixtureInstructions(
            """
                const/4 v0, 0
                if-eqz p0, :second
                return-object v0
                :second
                const/4 v4, 0
                return-object v4
            """,
        )
        patchCommonFollowApi(method)
        val instructions = method.implementation!!.instructions.toList()
        assertLogger(instructions[0], "logCommonFollowRequest", commonParameters, 5, 10)
        assertLogger(instructions[3], "logFollowResult", listOf("Ljava/lang/Object;"), 0, 1)
        assertEquals(Opcode.RETURN_OBJECT, instructions[4].opcode)
        assertLogger(instructions[6], "logFollowResult", listOf("Ljava/lang/Object;"), 4, 1)
        assertEquals(Opcode.RETURN_OBJECT, instructions[7].opcode)
    }

    private fun caller(call: String, resultRegister: Int = 0): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lcom/example/FollowCaller;", "call", emptyList(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(16, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions("$call\nmove-result-object v$resultRegister\nreturn-object v$resultRegister")
    }

    private fun instrumentCalls(method: MutableMethod) {
        val original = method.implementation!!.instructions.toList()
        for (index in original.indices.reversed()) {
            val before = followRequestInstructions(original[index]) ?: continue
            method.patchFollowCall(index, before)
        }
    }

    private fun assertUnchanged(method: MutableMethod) {
        val original = method.implementation!!.instructions.toList()
        instrumentCalls(method)
        val after = method.implementation!!.instructions.toList()
        assertEquals(original.size, after.size)
        for (index in original.indices) assertSame(original[index], after[index])
    }

    private fun assertLogger(
        instruction: Instruction,
        methodName: String,
        parameters: List<String>,
        firstRegister: Int,
        registerCount: Int,
    ) {
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instruction.opcode)
        val target = (instruction as ReferenceInstruction).reference as MethodReference
        assertEquals(diagnostics, target.definingClass)
        assertEquals(methodName, target.name)
        assertEquals(parameters, target.parameterTypes.map(CharSequence::toString))
        assertEquals("V", target.returnType)
        val call = instruction as RegisterRangeInstruction
        assertEquals(firstRegister, call.startRegister)
        assertEquals(registerCount, call.registerCount)
    }
}
