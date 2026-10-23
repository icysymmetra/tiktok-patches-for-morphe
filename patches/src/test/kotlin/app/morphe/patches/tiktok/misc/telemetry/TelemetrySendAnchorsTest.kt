package app.morphe.patches.tiktok.misc.telemetry

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Disable telemetry guards on the three sends to the log hosts rest on, held to
 * TikTok 47.0.3.
 *
 * The AppLog pack send answers 200 without a request when the switch is on, so the SDK takes
 * the pack as delivered and clears it; that holds only if one method sends every pack and its
 * callers read 200 as success. The AppLog forward send returns before its post; that leaves
 * nothing to pile up only because the worker deletes the forward rows before it calls the send.
 * The install SDK's activation helper answers success without its fetch; that keeps the active
 * job's own bookkeeping only if the job reads nothing but success out of the reply. A build
 * that splits a send, stops comparing the status with 200, posts the forward rows before
 * deleting them, or reads more out of the activation reply would leave requests going out or
 * piling up, and would fail here.
 */
class TelemetrySendAnchorsTest {
    /** The build the running check reads, set by [onEachDeclared] for the length of one pass. */
    private var container: MultiDexContainer<out DexFile>? = null

    /** Runs [check] once per declared build with [container] loaded from that build's APK. */
    private fun onEachDeclared(check: () -> Unit) = Fixtures.forEachDeclared { apk ->
        container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        try {
            check()
        } finally {
            container = null
        }
    }

    // Streaming passes: holding every method of the APK at once runs the heap out.
    private fun classes(): Sequence<ClassDef> = checkNotNull(container).let { dex ->
        dex.dexEntryNames.asSequence().flatMap { dex.getEntry(it)!!.dexFile.classes.asSequence() }
    }

    private fun methods(): Sequence<Method> = classes()
        .flatMap { it.methods.asSequence() }
        .filter { it.implementation != null }

    /**
     * Exactly one method carries the response's magic tag and the forward header together and
     * takes the pack bytes, it is called from exactly two other methods (the pack worker and the
     * real-time sender) besides its own retry, and each of those two compares its result with
     * 200. The device registration, the settings fetch and the forward send do not go through
     * it, which is what the caller count says.
     */
    @Test
    fun `each declared build sends every AppLog pack through one method whose callers read 200 as sent`() {
        onEachDeclared {
            val senders = methods().filter { method ->
                val strings = method.strings()
                // The pack builder names both strings too; the send is the one that takes the pack
                // bytes and answers a status, which is how the fingerprint tells them apart.
                "ss_app_log" in strings && "applog_forward" in strings && method.returnType == "I" &&
                    method.parameterTypes.size == 10 && method.parameterTypes[1].toString() == "[B"
            }.map { it.signature() }.toList()
            assertEquals("methods carrying both strings: $senders", 1, senders.size)
            val send = senders.single()
            assertEquals("the send returns the status", "I", send.returnType)
            assertEquals("the send takes the pack bytes second", "[B", send.parameters[1])
            assertEquals("the send's parameter count", 10, send.parameters.size)

            val callers = methods().filter { method -> method.calls().any { it.matches(send) } }.map { method ->
                Caller(method.definingClass, method.name, method.comparesResultOf(send, PACK_SENT_STATUS))
            }.toList()
            val others = callers.filter { it.owner != send.owner || it.name != send.name }
            assertEquals("callers besides the send's own retry: $others", 2, others.size)
            for (caller in others) {
                assertTrue(
                    "${caller.owner}->${caller.name} does not compare the send's result with the status the guard answers ($PACK_SENT_STATUS)",
                    caller.comparesWithAnswer,
                )
            }
            assertEquals("the two callers are of two classes", 2, others.map { it.owner }.toSet().size)
        }
    }

    /**
     * The patch's own list of guards, each against what its caller reads on 47.0.3: the pack
     * answer is the status both callers compare with, the activation answer is the non-zero the
     * active job branches on, the forward send stops before its post, and the priority send hands
     * back the extension's reply, whose data the priority checker requires. A guard dropped from
     * the list, or an answer changed to one the caller reads as failure, fails here.
     */
    @Test
    fun `each declared build guards every send that reached the log hosts with the answer its caller reads as success`() {
        onEachDeclared {
            assertEquals(
                "the guarded sends",
                setOf(AppLogSendPackFingerprint, AppLogForwardSendFingerprint, InstallActiveCheckFingerprint, AppLogPrioritySendFingerprint),
                TELEMETRY_SEND_GUARDS.map { it.fingerprint }.toSet(),
            )
            fun answer(fingerprint: Any) = TELEMETRY_SEND_GUARDS.single { it.fingerprint == fingerprint }.answer(7)
                .lines().map(String::trim).filter(String::isNotEmpty)
            assertEquals(listOf("const/16 v7, $PACK_SENT_STATUS", "return v7"), answer(AppLogSendPackFingerprint))
            assertEquals(listOf("return-void"), answer(AppLogForwardSendFingerprint))
            assertEquals(listOf("const/4 v7, $ACTIVE_CHECK_PASSED", "return v7"), answer(InstallActiveCheckFingerprint))
            assertNotEquals("the activation answer is the job's failure", 0, ACTIVE_CHECK_PASSED)
            val priority = answer(AppLogPrioritySendFingerprint)
            assertTrue("the priority guard does not hand back the extension's reply: $priority",
                priority.first().endsWith("->deliveredPriorityResponse()Ljava/lang/Object;") && priority.last() == "return-object v7")

            // The active job branches on the helper's answer, true being the success it records.
            val helper = methods().single { method ->
                ACTIVE_TAG in method.strings() && method.returnType == "Z" && method.parameterTypes.size == 6
            }.signature()
            val job = methods().single { method -> method.calls().any { it.matches(helper) } }
            assertTrue("the active job does not branch on the helper's answer", job.branchesOnResultOf(helper))

            // The priority uploader is the method the fingerprint names, and every checker of its
            // reply takes it as delivered only with message success and the SDK's magic tag, which is
            // what the extension's reply carries (DisableTelemetryPatchTest).
            val priorityClass = classes().single { it.type == "Lcom/bytedance/applog/priority/PriorityCallbackImpl;" }
            assertEquals(1, priorityClass.methods.count {
                it.name == "doHttpPost" && it.returnType == PRIORITY_RESPONSE &&
                    it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "[B", "Lkotlin/Pair;")
            })
            val checkers = methods().filter { method ->
                method.returnType == "Z" && method.parameterTypes.map(Any::toString) == listOf(PRIORITY_RESPONSE, "Ljava/util/Map;")
            }.map { it.signature() to it.strings() }.toList()
            assertTrue("no method checks a priority reply", checkers.isNotEmpty())
            for ((checker, strings) in checkers) {
                assertTrue("$checker reads a reply by something other than message success and the magic tag: $strings",
                    strings.containsAll(listOf("message", "success", "magic_tag", "ss_app_log")))
            }
        }
    }

    /**
     * Exactly one method carries the forward send's log line and its shape, it posts through
     * the SDK's client interface, its one caller is the worker in its own class, and that
     * worker hands the store the rows for a delete on the forward table before it calls the
     * send. On the S22 that pair of forward requests (26 KB) was what outlived the pack send's
     * guard with the switch on.
     */
    @Test
    fun `each declared build's forward worker deletes its rows before the one forward send posts them`() {
        onEachDeclared {
            val senders = methods().filter { method ->
                FORWARD_LINE in method.strings() && method.returnType == "V" &&
                    method.parameterTypes.map(Any::toString) == listOf("I", "Ljava/util/List;", "Lorg/json/JSONObject;")
            }.map { it.signature() to it.calls() }.toList()
            assertEquals("methods carrying the forward send's log line: ${senders.map { it.first }}", 1, senders.size)
            val (send, sendCalls) = senders.single()
            assertTrue(
                "the forward send does not post through the client interface: $sendCalls",
                sendCalls.any {
                    it.name == "post" && it.returnType == "Ljava/lang/String;" &&
                        it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Ljava/util/Map;", "[B")
                },
            )

            val callers = methods()
                .filter { method -> method.signature() != send && method.calls().any { it.matches(send) } }
                .map { it.signature() to it.calls() }.toList()
            assertEquals("callers of the forward send: ${callers.map { it.first }}", 1, callers.size)
            val (worker, workerCalls) = callers.single()
            assertEquals("the worker is of the send's class", send.owner, worker.owner)

            val beforeSend = workerCalls.take(workerCalls.indexOfFirst { it.matches(send) })
            val storeCalls = beforeSend.filter {
                it.definingClass.endsWith("/DbStore;") && it.returnType == "V" &&
                    it.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;")
            }
            assertTrue("the worker hands the store no list before the send: $workerCalls", storeCalls.isNotEmpty())
            val store = classes().single { it.type == storeCalls.first().definingClass }
            val deletes = storeCalls.filter { call ->
                val method = store.methods.single {
                    it.name == call.name && it.returnType == call.returnType &&
                        it.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
                }
                "forward_eventv3" in method.strings() &&
                    method.calls().any { it.name == "delete" && it.definingClass == "Landroid/database/sqlite/SQLiteDatabase;" }
            }
            assertEquals("store calls before the send that delete the forward rows: $storeCalls", 1, deletes.size)
        }
    }

    /**
     * Exactly one method carries the activation check's tag and its shape, it fetches through
     * the SDK's client interface and reads the reply for the word success, and its one caller
     * is the active job, which builds the query the guard keeps off the wire: the advertising
     * id, carrier, SIM region and time zone. The device registration goes through the other
     * helper of the same class, which is not touched.
     */
    @Test
    fun `each declared build's install active job fetches the alert check through one helper that reads success`() {
        onEachDeclared {
            val helpers = methods().filter { method ->
                ACTIVE_TAG in method.strings() && method.returnType == "Z" && method.parameterTypes.size == 6 &&
                    method.parameterTypes[1].toString() == "Ljava/lang/String;" &&
                    method.parameterTypes[5].toString() == "Ljava/util/HashMap;"
            }.map { Triple(it.signature(), it.calls(), it.strings()) }.toList()
            assertEquals("methods carrying the activation tag: ${helpers.map { it.first }}", 1, helpers.size)
            val (helper, helperCalls, helperStrings) = helpers.single()
            assertTrue(
                "the helper does not fetch through the client interface: $helperCalls",
                helperCalls.any {
                    it.name == "get" && it.returnType == "Ljava/lang/String;" &&
                        it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Ljava/util/Map;")
                },
            )
            assertTrue("the helper does not read the reply for success: $helperStrings", "success" in helperStrings && "message" in helperStrings)

            val registerHelpers = classes().single { it.type == helper.owner }.methods
                .filter { it.implementation != null && REGISTER_TAG in it.strings() }.map { it.signature() }
            assertEquals("register helpers of the same class: $registerHelpers", 1, registerHelpers.size)
            assertNotEquals("the register helper is the activation helper", helper, registerHelpers.single())

            val callers = methods().filter { method -> method.calls().any { it.matches(helper) } }
                .map { it.signature() to it.strings() }.toList()
            assertEquals("callers of the activation helper: ${callers.map { it.first }}", 1, callers.size)
            val (job, jobStrings) = callers.single()
            assertEquals("the active job answers whether it is done", "Z", job.returnType)
            assertEquals("the active job takes nothing", emptyList<String>(), job.parameters)
            for (key in listOf("google_aid", "carrier", "sim_region", "timezone")) {
                assertTrue("the active job does not put $key in the query: $jobStrings", key in jobStrings)
            }
        }
    }

    private fun Method.strings(): Set<String> = implementation!!.instructions
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.toSet()

    /** The methods this one calls, in instruction order. */
    private fun Method.calls(): List<MethodReference> = implementation!!.instructions
        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }

    private fun Method.signature() = Signature(definingClass, name, parameterTypes.map(Any::toString), returnType)

    private fun MethodReference.matches(target: Signature) =
        definingClass == target.owner && name == target.name && parameterTypes.map(Any::toString) == target.parameters

    private data class Signature(val owner: String, val name: String, val parameters: List<String>, val returnType: String)

    private data class Caller(val owner: String, val name: String, val comparesWithAnswer: Boolean)

    /**
     * Whether the int this method gets back from [target] is compared with [literal]: from the
     * call's `move-result`, along every path the code can take while the result register still
     * holds it, an if-eq or if-ne on that register and one whose last write before the branch was
     * a constant of [literal].
     */
    private fun Method.comparesResultOf(target: Signature, literal: Int): Boolean {
        val instructions = implementation!!.instructions.toList()
        val addresses = IntArray(instructions.size)
        var address = 0
        for ((index, instruction) in instructions.withIndex()) {
            addresses[index] = address
            address += instruction.codeUnits
        }
        val indexAt = addresses.withIndex().associate { (index, at) -> at to index }
        fun successors(index: Int): List<Int> {
            val instruction = instructions[index]
            val next = if (index + 1 < instructions.size) listOf(index + 1) else emptyList()
            if (instruction.opcode in ENDS) return emptyList()
            if (instruction !is OffsetInstruction) return next
            val jump = indexAt[addresses[index] + instruction.codeOffset]
            return when (instruction.opcode) {
                Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32 -> listOfNotNull(jump)
                Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH, Opcode.FILL_ARRAY_DATA -> next
                else -> next + listOfNotNull(jump)
            }
        }
        fun constantBefore(index: Int, register: Int): Int? {
            for (back in index - 1 downTo 0) {
                val instruction = instructions[back]
                if (instruction is OneRegisterInstruction && instruction.opcode.setsRegister() && instruction.registerA == register) {
                    return (instruction as? NarrowLiteralInstruction)?.narrowLiteral
                }
            }
            return null
        }
        for ((index, instruction) in instructions.withIndex()) {
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (!reference.matches(target)) continue
            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction ?: continue
            if (moveResult.opcode != Opcode.MOVE_RESULT) continue
            val result = moveResult.registerA
            val seen = mutableSetOf<Int>()
            val pending = ArrayDeque(listOf(index + 2))
            while (pending.isNotEmpty()) {
                val at = pending.removeFirst()
                if (at >= instructions.size || !seen.add(at)) continue
                val current = instructions[at]
                if (current is TwoRegisterInstruction && (current.opcode == Opcode.IF_EQ || current.opcode == Opcode.IF_NE)) {
                    val other = when (result) {
                        current.registerA -> current.registerB
                        current.registerB -> current.registerA
                        else -> null
                    }
                    if (other != null && constantBefore(at, other) == literal) return true
                }
                if (current is OneRegisterInstruction && current.opcode.setsRegister() && current.registerA == result) continue
                pending.addAll(successors(at))
            }
        }
        return false
    }

    /** Whether the boolean this method gets back from [target] decides a branch (if-eqz or if-nez on it). */
    private fun Method.branchesOnResultOf(target: Signature): Boolean {
        val instructions = implementation!!.instructions.toList()
        for ((index, instruction) in instructions.withIndex()) {
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (!reference.matches(target)) continue
            val moveResult = instructions.getOrNull(index + 1) as? OneRegisterInstruction ?: continue
            if (moveResult.opcode != Opcode.MOVE_RESULT) continue
            val result = moveResult.registerA
            for (next in instructions.drop(index + 2).take(24)) {
                if (next is OneRegisterInstruction && (next.opcode == Opcode.IF_EQZ || next.opcode == Opcode.IF_NEZ) &&
                    next.registerA == result
                ) return true
                if (next is OneRegisterInstruction && next.opcode.setsRegister() && next.registerA == result) break
            }
        }
        return false
    }

    private companion object {
        val ENDS = setOf(Opcode.RETURN_VOID, Opcode.RETURN, Opcode.RETURN_WIDE, Opcode.RETURN_OBJECT, Opcode.THROW)
        const val PRIORITY_RESPONSE = "Lcom/bytedance/applog/priority/PriorityHttpResponse;"
        const val FORWARD_LINE = "trySendForward start requestId={}, url={}"
        const val ACTIVE_TAG = "Register#active http error = "
        const val REGISTER_TAG = "Register#doRegister http error = "
    }
}
