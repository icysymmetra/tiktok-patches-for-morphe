/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.classesCalling
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.ReferenceType
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val GHOST_MODE_EXTENSION = "Lapp/morphe/extension/tiktok/ghostmode/GhostMode;"

/**
 * How far past a report call the send is looked for. The real chains are five to nine
 * instructions; the cap is there so a drifted build fails the patch instead of walking a
 * method looking for something that is not there.
 */
private const val MAX_CHAIN_LENGTH = 24

/**
 * What a report call's result feeds, as far as a straight-line walk from the call can see.
 *
 * <p>The reporters that hand something back are lazy: a Retrofit `Call` sends nothing until
 * `enqueue`, an Rx `Observable` or `Single` nothing until `subscribe`. So the send is not the
 * report call, it is the last call in the chain the result flows through, and that chain is
 * what has to be skipped. Returning early from the reporter instead, with a fabricated null
 * where the caller expects the lazy object, is exactly the crash the old guard caused.
 *
 * @property defined every register the chain writes, from the call's result to the send.
 *           On the guarded path none of those writes happen, so none of them may be read
 *           after the chain.
 */
internal sealed class ReportChain {
    abstract val defined: Set<Int>

    /**
     * The result is passed along a straight line of calls and the last one discards what it
     * gets back. That last call is the send; nothing after it holds the report.
     */
    data class Sent(val terminalIndex: Int, override val defined: Set<Int>) : ReportChain()

    /**
     * The result is only compared against the coroutine machinery's suspended marker, which is
     * what a `suspend` reporter's caller does with it. There is no send to skip; the coroutine
     * simply completes with nothing when the call is not made.
     */
    data class SuspendedCheck(val compareIndex: Int, override val defined: Set<Int>) : ReportChain()
}

private val moveResultOpcodes = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT)
private val moveObjectOpcodes = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val compareOpcodes = setOf(Opcode.IF_EQ, Opcode.IF_NE)

private fun Instruction.isInvoke() =
    getReference<MethodReference>() != null &&
        (this is FiveRegisterInstruction || this is RegisterRangeInstruction)

/**
 * Follows the result of the report call at [callIndex] until it is sent or until the walk
 * can no longer say what happens to it.
 *
 * <p>The chain register starts as whatever `move-result-object` puts the result in. A call
 * that takes the chain register and returns something moves the chain onto that result
 * (`subscribeOn` hands back a new Observable). A call that takes the chain register and
 * returns nothing, or whose result is dropped, is the send. Calls that do not touch the chain
 * are the caller building the scheduler or the callback and are stepped over. Anything that
 * leaves the straight line, or writes over the chain register before it is sent, ends the walk
 * with a [PatchException]: a guard that skips half a chain is worse than no guard.
 *
 * @throws PatchException when the chain cannot be followed to its send.
 */
internal fun Method.reportChainAt(callIndex: Int): ReportChain {
    val instructions = instructions.toList()
    val call = instructions.getOrNull(callIndex)
    if (call == null || !call.isInvoke()) {
        throw PatchException("Ghost mode: instruction $callIndex of $this is not a call.")
    }
    val reference = call.getReference<MethodReference>()!!
    val captured = instructions.getOrNull(callIndex + 1)
    if (captured == null || captured.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException(
            "Ghost mode: the result of ${reference.name} at $callIndex of $this is not captured, " +
                "so there is no chain to skip past.",
        )
    }
    val result = (captured as OneRegisterInstruction).registerA
    var chain = setOf(result)
    val defined = mutableSetOf(result)
    var index = callIndex + 2
    val limit = minOf(instructions.size, callIndex + 2 + MAX_CHAIN_LENGTH)
    while (index < limit) {
        val instruction = instructions[index]
        val opcode = instruction.opcode
        if (index == callIndex + 2 && opcode in compareOpcodes) {
            val compare = instruction as TwoRegisterInstruction
            if (compare.registerA in chain || compare.registerB in chain) {
                return ReportChain.SuspendedCheck(index, defined)
            }
        }
        if (instruction is OffsetInstruction || !opcode.canContinue()) {
            throw PatchException(
                "Ghost mode: the result of ${reference.name} leaves the straight line at " +
                    "$index ($opcode) of $this before it is sent.",
            )
        }
        if (opcode in moveResultOpcodes) {
            throw PatchException("Ghost mode: a stray $opcode at $index of $this.")
        }
        if (instruction.isInvoke()) {
            val next = instructions.getOrNull(index + 1)
            val keepsResult = next != null && next.opcode in moveResultOpcodes
            if (instruction.namedRegisters().none { it in chain }) {
                // The caller building an argument: a scheduler, a callback, a consumer.
                if (keepsResult) defined += (next as OneRegisterInstruction).registerA
                index += if (keepsResult) 2 else 1
                continue
            }
            if (!keepsResult) return ReportChain.Sent(index, defined)
            if (next?.opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw PatchException(
                    "Ghost mode: the chain from ${reference.name} turns into a primitive at " +
                        "$index of $this, which is not a send.",
                )
            }
            val moved = (next as OneRegisterInstruction).registerA
            chain = setOf(moved)
            defined += moved
            index += 2
            continue
        }
        if (opcode in moveObjectOpcodes) {
            val move = instruction as TwoRegisterInstruction
            chain = when {
                move.registerB in chain -> chain + move.registerA
                else -> chain - move.registerA
            }
            defined += move.registerA
            index++
            continue
        }
        if (opcode.setsRegister() && opcode != Opcode.CHECK_CAST) {
            val written = (instruction as OneRegisterInstruction).registerA
            if (written in chain) {
                throw PatchException(
                    "Ghost mode: the result of ${reference.name} is written over at $index " +
                        "($opcode) of $this before it is sent.",
                )
            }
            defined += written
            if (opcode.setsWideRegister()) defined += written + 1
        }
        index++
    }
    throw PatchException(
        "Ghost mode: no send within $MAX_CHAIN_LENGTH instructions of ${reference.name} at " +
            "$callIndex of $this.",
    )
}

/** `const` of zero into a register, which the verifier accepts as null for a reference. */
private fun nullInto(register: Int) =
    if (register < 16) "const/4 v$register, 0x0" else "const/16 v$register, 0x0"

/**
 * Guards the report call at [callIndex] so that, when the extension says so, the whole chain
 * that would send it is stepped over and the caller carries on from the instruction after.
 *
 * <p>For a [ReportChain.Sent] chain the guard is a branch from just before the call to just
 * after the send. For a [ReportChain.SuspendedCheck] chain the call is replaced by a null
 * result on the guarded path, which the caller then finds is not the suspended marker and
 * treats as the coroutine having completed.
 *
 * <p>The flag register is chosen from a liveness pass over the whole method: it is dead on
 * entry to the call, so writing it before the call clobbers nothing on the ordinary path, and
 * dead where the guarded path lands, so nothing reads it there either. The same pass refuses
 * the site when a register the chain writes is read after the send, because on the guarded
 * path that write never happens.
 *
 * <p>The guard is placed with the call's own control-flow label, so a branch into the call
 * from elsewhere in the method reaches the guard rather than the call.
 *
 * @return the chain that was guarded.
 * @throws PatchException when the chain cannot be followed, a chain register outlives the
 *         send, or no flag register is free.
 */
internal fun MutableMethod.skipReportAtCallSite(callIndex: Int, guard: String): ReportChain {
    val chain = reportChainAt(callIndex)
    val liveness = RegisterLiveness.of(this)
    val guardCall = "invoke-static {}, $GHOST_MODE_EXTENSION->$guard()Z"
    when (chain) {
        is ReportChain.Sent -> {
            val landing = chain.terminalIndex + 1
            if (landing >= instructions.size) {
                throw PatchException("Ghost mode: the send at ${chain.terminalIndex} of $this ends the method.")
            }
            val liveAtLanding = liveness.liveInto(landing)
            val outlives = chain.defined.filter { it in liveAtLanding }
            if (outlives.isNotEmpty()) {
                throw PatchException(
                    "Ghost mode: register(s) ${outlives.joinToString { "v$it" }} written by the " +
                        "report chain at $callIndex of $this are read after the send, so the " +
                        "send cannot be skipped.",
                )
            }
            val flag = flagRegister(liveness.liveInto(callIndex) + liveAtLanding, landing)
            addInstructionsAtControlFlowLabel(
                callIndex,
                """
                    $guardCall
                    move-result v$flag
                    if-nez v$flag, :morphe_ghost_skip
                """,
                ExternalLabel("morphe_ghost_skip", getInstruction(landing)),
            )
        }
        is ReportChain.SuspendedCheck -> {
            val result = (getInstruction(callIndex + 1) as OneRegisterInstruction).registerA
            val compare = getInstruction(chain.compareIndex)
            // On the guarded path the result register is written by the null just before the
            // compare, so it is the one register the compare's liveness does not hold against.
            val liveAtLanding = liveness.liveInto(chain.compareIndex) - result
            val flag = flagRegister(liveness.liveInto(callIndex) + liveAtLanding, chain.compareIndex)
            // The null result sits on its own path just before the compare. The fall-through
            // path from the real call jumps over it; the guarded path lands on it.
            addInstructionsWithLabels(
                chain.compareIndex,
                """
                    goto :morphe_ghost_join
                    ${nullInto(result)}
                """,
                ExternalLabel("morphe_ghost_join", compare),
            )
            val skipped = getInstruction(chain.compareIndex + 1)
            addInstructionsAtControlFlowLabel(
                callIndex,
                """
                    $guardCall
                    move-result v$flag
                    if-nez v$flag, :morphe_ghost_skipped
                """,
                ExternalLabel("morphe_ghost_skipped", skipped),
            )
        }
    }
    return chain
}

/** The lowest register nothing reads on either path, and one `move-result` can name. */
private fun MutableMethod.flagRegister(unsafe: Set<Int>, landingIndex: Int): Int {
    val flag = (0 until implementation!!.registerCount).firstOrNull { it !in unsafe }
        ?: throw PatchException(
            "Ghost mode: every register of $this is live at $landingIndex or into the call, " +
                "so there is nowhere to hold the guard's answer.",
        )
    if (flag > 0xFF) {
        throw PatchException("Ghost mode: the free register v$flag at $landingIndex of $this is too high for move-result.")
    }
    return flag
}

/**
 * Guards every call to the given reporters, wherever they are called from.
 *
 * <p>A call is matched by name and parameters against the reporter class and every interface
 * it implements, because the wrapper delegates to a Retrofit interface and a caller could go
 * to either. The wrappers themselves are not callers. Every match is guarded or the patch
 * fails, and a reporter nobody calls fails it too: that is a selector that drifted, not a
 * report that stopped existing.
 *
 * @return how many call sites were guarded.
 */
internal fun BytecodePatchContext.skipReportsAtEveryCallSite(reporters: List<Method>, guard: String): Int {
    val wrapperTypes = reporters.map { it.definingClass }.toSet()
    val acceptedOwners = wrapperTypes + wrapperTypes.flatMap { classDefByOrNull(it)?.interfaces.orEmpty() }
    val signatures = reporters.map { it.name to it.parameterTypes.map(CharSequence::toString) }.toSet()
    fun matches(reference: MethodReference) =
        reference.definingClass in acceptedOwners &&
            (reference.name to reference.parameterTypes.map(CharSequence::toString)) in signatures

    // Only classes the patcher's index says call something on an accepted owner (#54).
    val callingClasses = classesCalling(acceptedOwners)

    // Collected first, mutated after: the walk is over the immutable classes.
    val callers = mutableListOf<Triple<ClassDef, Method, List<Int>>>()
    classDefForEach { classDef ->
        if (classDef.type !in callingClasses || classDef.type in wrapperTypes) return@classDefForEach
        classDef.methods.forEach { method ->
            // A call is an invoke; checking that first spares reading a reference off every
            // other instruction of TikTok (#54).
            val indexes = method.implementation?.instructions?.withIndex()
                ?.filter { (_, instruction) ->
                    instruction.opcode.referenceType == ReferenceType.METHOD &&
                        instruction.getReference<MethodReference>()?.let(::matches) == true
                }
                ?.map { it.index }
                .orEmpty()
            if (indexes.isNotEmpty()) callers += Triple(classDef, method, indexes)
        }
    }
    if (callers.isEmpty()) {
        throw PatchException(
            "Ghost mode: nothing calls ${signatures.joinToString { it.first }}; the selector " +
                "drifted or the report moved.",
        )
    }
    var guarded = 0
    callers.forEach { (classDef, method, indexes) ->
        val mutable = mutableClassDefBy(classDef).findMutableMethodOf(method)
        // Last call first, so the earlier indexes still point at their calls.
        indexes.sortedDescending().forEach { index ->
            mutable.skipReportAtCallSite(index, guard)
            guarded++
        }
    }
    return guarded
}
