package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RESULT = "LX/CacheLoadResult;"
private const val PAYLOAD = "LX/CachePayload;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;"

/** Immutable handoffs retain the native cleanup and never write final result state. */
class CacheDeliveryHookTest {
    private val payloadField = ImmutableFieldReference(RESULT, "value", PAYLOAD)
    private val awemeField = ImmutableFieldReference(PAYLOAD, "aweme", AWEME)
    private val successField = ImmutableFieldReference(RESULT, "success", "Z")
    private val nativeNormalizer = ImmutableMethodReference("LX/Normalizer;", "nativeCleanup", listOf(RESULT), "V")
    private val emptyCopy = ImmutableMethodReference(RESULT, EMPTY_CACHE_COPY, listOf(RESULT), RESULT)
    private val rejectedCopy = ImmutableMethodReference(RESULT, REJECTED_CACHE_COPY, listOf(RESULT), RESULT)
    private fun bridge() = normalizedCacheBridge(nativeNormalizer, payloadField, awemeField, emptyCopy)

    @Test
    fun `the rebuilt chain turns a rejected payload into the native empty-result signal`() {
        val method = bridge()
        val result = CacheResult(payload = CachePayload(Any()))
        var delivered: CacheResult? = null
        var cleanups = 0
        val calls = runHook(method, result, keep = false, delivered = { delivered = it }, cleanup = { cleanups++ })
        assertNotSame(result, delivered)
        assertNull(delivered!!.payload)
        assertTrue(result.payload != null)
        assertEquals(result.success, delivered!!.success)
        assertEquals(1, cleanups)
        assertEquals(1, calls)
    }

    @Test
    fun `the rebuilt chain preserves accepted and already empty results`() {
        val acceptedPayload = CachePayload(Any())
        val accepted = CacheResult(payload = acceptedPayload)
        var delivered: CacheResult? = null
        assertEquals(1, runHook(bridge(), accepted, keep = true, delivered = { delivered = it }))
        assertSame(accepted, delivered)
        assertSame(acceptedPayload, accepted.payload)

        val empty = CacheResult(payload = null)
        assertEquals(0, runHook(bridge(), empty, keep = false, delivered = { delivered = it }))
        assertSame(empty, delivered)
        assertNull(empty.payload)
    }

    @Test
    fun `the rebuilt chain calls the feed predicate with the cached Aweme`() {
        val method = bridge()
        val call = method.implementation!!.instructions.single {
            it.opcode == Opcode.INVOKE_STATIC_RANGE &&
                ((it as ReferenceInstruction).reference as MethodReference).name == "shouldKeepNormalizedCache"
        }
        val target = (call as ReferenceInstruction).reference as MethodReference

        assertEquals(EXTENSION, target.definingClass)
        assertEquals("shouldKeepNormalizedCache", target.name)
        assertEquals(listOf(AWEME), target.parameterTypes.map(CharSequence::toString))
        assertEquals("Z", target.returnType)
        assertEquals(0, (call as RegisterRangeInstruction).startRegister)
        assertEquals(1, call.registerCount)
    }

    @Test
    fun `the bridge performs cleanup before deciding and has no field stores`() {
        val code = bridge().implementation!!.instructions.toList()
        assertEquals(nativeNormalizer, (code.first() as ReferenceInstruction).reference)
        assertTrue(code.none { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.IPUT_BOOLEAN })
    }

    @Test
    fun `a null result survives when native cleanup returns without inspecting it`() {
        // The exact target's normalizer returns before reading the result when its feature
        // flag is off. The bridge must not introduce a dereference after that native return.
        var delivered = false
        var cleanups = 0
        assertEquals(0, runHook(bridge(), null, keep = true,
            delivered = { assertNull(it); delivered = true }, cleanup = { cleanups++ }))
        assertTrue(delivered)
        assertEquals(1, cleanups)
    }

    @Test
    fun `a rejected reach-bottom result is marked unsuccessful`() {
        val rejected = CacheResult(success = true, payload = CachePayload(Any()))
        val rejectedMethod = reachBottom().apply {
            filterReachBottomCacheDelivery(payloadField, awemeField, rejectedCopy)
        }
        var delivered: CacheResult? = null
        assertEquals(1, runHook(rejectedMethod, rejected, keep = false, predicate = "shouldKeepReachBottomCache", delivered = { delivered = it }))
        assertEquals(true, rejected.success)
        assertEquals(false, delivered!!.success)
        assertNotSame(rejected, delivered)
        assertSame(rejected.payload, delivered!!.payload)

        val accepted = CacheResult(success = true, payload = CachePayload(Any()))
        val acceptedMethod = reachBottom().apply {
            filterReachBottomCacheDelivery(payloadField, awemeField, rejectedCopy)
        }
        assertEquals(1, runHook(acceptedMethod, accepted, keep = true, predicate = "shouldKeepReachBottomCache"))
        assertEquals(true, accepted.success)
    }

    @Test
    fun `the removed recommendation-card surface can be absent but cannot drift silently`() {
        assertNull(selectRecUserCardInsertion(insertion = null, surfacePresent = false))

        val insertion = normalizer()
        assertSame(insertion, selectRecUserCardInsertion(insertion, surfacePresent = true))

        val error = assertThrows(PatchException::class.java) {
            selectRecUserCardInsertion(insertion = null, surfacePresent = true)
        }
        assertTrue(error.message!!.contains("insertion method was not found"))
    }

    @Test
    fun `the recommendation-card selector accepts both known parameter orders`() {
        fun candidate(parameters: List<String>) = ImmutableMethod(
            "LX/RecUserCard;",
            "insert",
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "Lkotlin/Pair;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            null,
        )

        assertTrue(
            candidate(
                listOf("I", "Ljava/util/List;", "Ljava/lang/String;", "Lkotlin/jvm/functions/Function0;"),
            ).isRecUserCardInsertion(),
        )
        assertTrue(
            candidate(
                listOf("I", "Ljava/lang/String;", "Ljava/util/List;", "Lkotlin/jvm/functions/Function0;"),
            ).isRecUserCardInsertion(),
        )
    }

    private fun normalizer(registers: Int = 2) = method(
        name = "normalize",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        registers = registers,
    )

    private fun reachBottom() = method(
        name = "deliver",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        registers = 4,
    )

    private fun method(name: String, accessFlags: Int, registers: Int) = MutableMethod(
        ImmutableMethod(
            "LX/CacheConsumer;",
            name,
            listOf(ImmutableMethodParameter(RESULT, null, null)),
            "V",
            accessFlags,
            null,
            null,
            ImmutableMethodImplementation(
                registers,
                listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                null,
                null,
            ),
        ),
    )

    /** Executes the small injected prefix until TikTok's original return instruction. */
    private fun runHook(
        method: MutableMethod,
        result: CacheResult?,
        keep: Boolean,
        // Each cache route calls its own predicate, so a report can say which one ran.
        predicate: String = "shouldKeepNormalizedCache",
        delivered: (CacheResult?) -> Unit = {},
        cleanup: () -> Unit = {},
    ): Int {
        val instructions = method.implementation!!.instructions.toList()
        val addresses = IntArray(instructions.size)
        var address = 0
        instructions.forEachIndexed { index, instruction ->
            addresses[index] = address
            address += instruction.codeUnits
        }
        val indexAtAddress = addresses.withIndex().associate { it.value to it.index }
        val registers = arrayOfNulls<Any>(method.implementation!!.registerCount)
        registers[registers.lastIndex] = result
        var predicateResult = false
        var objectResult: Any? = null
        var calls = 0
        var index = 0

        while (index < instructions.size) {
            val instruction = instructions[index]
            var next = index + 1
            when (instruction.opcode) {
                Opcode.NOP -> Unit
                Opcode.MOVE_OBJECT_FROM16 -> {
                    instruction as TwoRegisterInstruction
                    registers[instruction.registerA] = registers[instruction.registerB]
                }
                Opcode.IGET_OBJECT -> {
                    instruction as TwoRegisterInstruction
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    registers[instruction.registerA] = when (field) {
                        payloadField -> (registers[instruction.registerB] as CacheResult).payload
                        awemeField -> (registers[instruction.registerB] as CachePayload).aweme
                        else -> error("Unexpected field read: $field")
                    }
                }
                Opcode.INVOKE_STATIC_RANGE -> {
                    val target = (instruction as ReferenceInstruction).reference as MethodReference
                    val call = instruction as RegisterRangeInstruction
                    when (target.name) {
                        "nativeCleanup" -> cleanup()
                        EMPTY_CACHE_COPY -> objectResult = (registers[call.startRegister] as CacheResult).copy(payload = null)
                        REJECTED_CACHE_COPY -> objectResult = (registers[call.startRegister] as CacheResult).copy(success = false)
                        else -> {
                            assertEquals(predicate, target.name)
                            check(registers[call.startRegister] != null) { "The predicate was handed no Aweme" }
                            predicateResult = keep
                            calls++
                        }
                    }
                }
                Opcode.MOVE_RESULT ->
                    registers[(instruction as OneRegisterInstruction).registerA] = predicateResult
                Opcode.MOVE_RESULT_OBJECT ->
                    registers[(instruction as OneRegisterInstruction).registerA] = objectResult
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val value = registers[(instruction as OneRegisterInstruction).registerA]
                    val zero = value == null || value == false || value == 0
                    val taken = instruction.opcode == Opcode.IF_EQZ && zero ||
                        instruction.opcode == Opcode.IF_NEZ && !zero
                    if (taken) {
                        val target = addresses[index] + (instruction as OffsetInstruction).codeOffset
                        next = indexAtAddress[target] ?: error("No instruction at branch target $target")
                    }
                }
                Opcode.CONST_4 ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        (instruction as WideLiteralInstruction).wideLiteral.toInt()
                Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN -> error("Illegal store to immutable cache state")
                Opcode.RETURN_OBJECT -> {
                    delivered(registers[(instruction as OneRegisterInstruction).registerA] as CacheResult?)
                    return calls
                }
                Opcode.RETURN_VOID -> {
                    delivered(registers[registers.lastIndex] as CacheResult?)
                    return calls
                }
                else -> error("Unexpected instruction: ${instruction.opcode}")
            }
            index = next
        }
        error("The hook did not reach the host return")
    }

    private data class CacheResult(
        val success: Boolean = true,
        val payload: CachePayload?,
    )

    private data class CachePayload(val aweme: Any?)
}
