/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import app.morphe.util.findFreeRegister

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.BitSet

/** Every register an instruction names, in operand order, whether it reads or writes it. */
fun Instruction.namedRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction ->
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is ThreeRegisterInstruction -> listOf(registerA, registerB, registerC)
    is TwoRegisterInstruction -> listOf(registerA, registerB)
    is OneRegisterInstruction -> listOf(registerA)
    else -> emptyList()
}

/**
 * Which registers are live on entry to each instruction of a method: read on some path from
 * there before they are written.
 *
 * <p>Computed backwards over the whole control-flow graph. Conditional branches, gotos, packed
 * and sparse switches and exception handlers are all followed, so the answer holds on every
 * path and not only on the one a forward walk happens to take. [findFreeRegister] scans
 * forward from an index and treats a switch as a leaf, which is enough for most hooks and
 * gave up on a 509-instruction profile view-model where a report call had to be guarded; this
 * is the same question answered properly.
 *
 * <p>Reads are over-approximated on purpose. A register named by an instruction counts as
 * read unless it is the plain destination of an instruction that only writes it, and any
 * register named by a wide, long or double instruction counts along with the one above it.
 * Over-counting reads can only make a register look live, never free, so a register this
 * says is dead is dead.
 *
 * <p>The question asked of it is which registers a hook put in front of an instruction must
 * leave alone, so inside a try a handler's reads count before the instruction's own write, even
 * where the instruction can't throw: the hook's code can.
 */
class RegisterLiveness private constructor(private val liveIn: Array<BitSet>) {
    // The graph is built by [ControlFlow], which [literalReads] walks forwards over as well.

    /** How many instructions the method had when this was computed. */
    val size: Int get() = liveIn.size

    /**
     * Registers some path from the instruction at [index] reads before writing, a handler of a
     * try it sits in counting as reachable before its write.
     */
    fun liveInto(index: Int): Set<Int> {
        val bits = liveIn[index]
        val registers = mutableSetOf<Int>()
        var register = bits.nextSetBit(0)
        while (register >= 0) {
            registers += register
            register = bits.nextSetBit(register + 1)
        }
        return registers
    }

    companion object {
        /**
         * @throws IllegalArgumentException when the method has no body, or a branch lands
         *         between instructions.
         */
        fun of(method: Method): RegisterLiveness {
            val flow = ControlFlow.of(method)
            val instructions = flow.instructions
            val count = instructions.size
            val use = Array(count) { BitSet() }
            val def = Array(count) { BitSet() }
            for (index in 0 until count) {
                val instruction = instructions[index]
                val opcode = instruction.opcode
                // dexlib2's Opcode carries its own `name` field ("move-wide", "add-int/2addr"),
                // which is what Kotlin resolves here rather than the enum constant, so match
                // both spellings.
                val name = opcode.name.lowercase()
                val wide = "wide" in name || "long" in name || "double" in name
                // A destination that is also read: two-address arithmetic, and check-cast,
                // which narrows the register it was given.
                val twoAddress = name.endsWith("/2addr") || name.endsWith("_2addr")
                val plainWrite = opcode.setsRegister() && !twoAddress && opcode != Opcode.CHECK_CAST
                instruction.namedRegisters().forEachIndexed { position, register ->
                    if (position == 0 && opcode.setsRegister()) {
                        def[index].set(register)
                        if (opcode.setsWideRegister()) def[index].set(register + 1)
                        if (plainWrite) return@forEachIndexed
                    }
                    use[index].set(register)
                    if (wide) use[index].set(register + 1)
                }
            }

            val liveIn = Array(count) { BitSet() }
            var changed = true
            while (changed) {
                changed = false
                for (index in count - 1 downTo 0) {
                    val live = BitSet()
                    flow.normal[index].forEach { live.or(liveIn[it]) }
                    live.andNot(def[index])
                    // What a handler reads is what the register held before this instruction:
                    // a throw from the instruction never writes its destination, and a hook put
                    // in front of it runs code that can throw whatever the instruction does. So
                    // the handlers' live-in joins after this instruction's write is taken away,
                    // for every instruction in a try (refutation review of 4fe7b503).
                    flow.exceptional[index].forEach { live.or(liveIn[it]) }
                    live.or(use[index])
                    if (live != liveIn[index]) {
                        liveIn[index] = live
                        changed = true
                    }
                }
            }
            return RegisterLiveness(liveIn)
        }
    }
}

/**
 * A method's instructions and, for each, where execution can go next: [normal] for fall
 * through, branches, gotos and switch arms, [exceptional] for the handlers of every try block
 * the instruction sits in. The two are kept apart because an instruction that throws never
 * writes its destination, so a handler still sees the value that register held before it.
 */
class ControlFlow private constructor(
    val instructions: List<Instruction>,
    val normal: Array<List<Int>>,
    val exceptional: Array<List<Int>>,
) {
    companion object {
        private val payloadOpcodes = setOf(
            Opcode.PACKED_SWITCH_PAYLOAD, Opcode.SPARSE_SWITCH_PAYLOAD, Opcode.ARRAY_PAYLOAD,
        )
        private val switchOpcodes = setOf(Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH)

        /**
         * @throws IllegalArgumentException when the method has no body, or a branch lands
         *         between instructions.
         */
        fun of(method: Method): ControlFlow {
            val implementation = requireNotNull(method.implementation) { "Method has no implementation: $method" }
            val instructions = implementation.instructions.toList()
            val count = instructions.size
            val address = IntArray(count + 1)
            val indexAt = HashMap<Int, Int>(count * 2)
            for (index in 0 until count) {
                indexAt[address[index]] = index
                address[index + 1] = address[index] + instructions[index].codeUnits
            }
            fun target(from: Int, offset: Int): Int = indexAt[address[from] + offset]
                ?: throw IllegalArgumentException(
                    "A branch at instruction $from of $method lands between instructions.",
                )

            val normal = Array(count) { mutableListOf<Int>() }
            for (index in 0 until count) {
                val instruction = instructions[index]
                val opcode = instruction.opcode
                val out = normal[index]
                when {
                    // Data, never executed: no successors.
                    opcode in payloadOpcodes -> {}
                    opcode in switchOpcodes -> {
                        val payload = instructions[target(index, (instruction as OffsetInstruction).codeOffset)]
                            as SwitchPayload
                        payload.switchElements.forEach { out += target(index, it.offset) }
                        if (index + 1 < count) out += index + 1
                    }
                    // The offset points at data, and execution carries on below.
                    opcode == Opcode.FILL_ARRAY_DATA -> if (index + 1 < count) out += index + 1
                    instruction is OffsetInstruction -> {
                        out += target(index, instruction.codeOffset)
                        if (opcode.canContinue() && index + 1 < count) out += index + 1
                    }
                    opcode.canContinue() -> if (index + 1 < count) out += index + 1
                }
            }
            // Any instruction inside a try block may end up in its handlers.
            val exceptional = Array(count) { mutableListOf<Int>() }
            implementation.tryBlocks.forEach { tryBlock ->
                val start = tryBlock.startCodeAddress
                val end = start + tryBlock.codeUnitCount
                val handlers = tryBlock.exceptionHandlers.map { handler ->
                    indexAt[handler.handlerCodeAddress] ?: throw IllegalArgumentException(
                        "A handler of $method starts between instructions.",
                    )
                }
                for (index in 0 until count) {
                    if (address[index] >= start && address[index] < end) exceptional[index] += handlers
                }
            }
            return ControlFlow(instructions, Array(count) { normal[it] }, Array(count) { exceptional[it] })
        }
    }
}

/** Whether [instruction] reads [register], counting the upper half of a wide operand. */
private fun readsRegister(instruction: Instruction, register: Int): Boolean {
    val opcode = instruction.opcode
    val name = opcode.name.lowercase()
    val wide = "wide" in name || "long" in name || "double" in name
    val twoAddress = name.endsWith("/2addr") || name.endsWith("_2addr")
    val plainWrite = opcode.setsRegister() && !twoAddress && opcode != Opcode.CHECK_CAST
    instruction.namedRegisters().forEachIndexed { position, named ->
        if (position == 0 && plainWrite) return@forEachIndexed
        if (named == register || (wide && named + 1 == register)) return true
    }
    return false
}

/** Whether [instruction] writes [register], or a wide pair that covers it. */
private fun writesRegister(instruction: Instruction, register: Int): Boolean {
    val opcode = instruction.opcode
    if (!opcode.setsRegister()) return false
    val destination = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * Every instruction that can read the value the instruction at [index] writes, before the
 * register it wrote is written again: the value's uses, on every path from there, following
 * branches, switch arms and exception handlers. An instruction inside a try block that throws
 * never writes its destination, so a handler reached from it still sees the value.
 *
 * @throws IllegalArgumentException when the instruction at [index] writes no single register.
 */
fun Method.literalReads(index: Int): List<Int> {
    val flow = ControlFlow.of(this)
    val definition = flow.instructions[index]
    require(definition.opcode.setsRegister() && !definition.opcode.setsWideRegister()) {
        "Instruction $index of $this does not write a single register."
    }
    // A handler reached from the definition itself would see the register's old value, so the
    // definition hands the value on by its normal successors only.
    return flow.readsFrom(flow.normal[index], (definition as OneRegisterInstruction).registerA)
}

/**
 * Every instruction that can read what [register] holds once the instruction at [index] has
 * run, before something writes it again. Empty means code inserted right after that
 * instruction may use the register for itself. The instruction's own handlers are included:
 * code inserted after it sits in the same try block.
 */
fun Method.readsAfter(index: Int, register: Int): List<Int> {
    val flow = ControlFlow.of(this)
    return flow.readsFrom(flow.normal[index] + flow.exceptional[index], register)
}

private fun ControlFlow.readsFrom(starts: List<Int>, register: Int): List<Int> {
    val reads = sortedSetOf<Int>()
    val seen = BitSet()
    val pending = ArrayDeque<Int>()
    fun enqueue(successors: List<Int>) = successors.forEach { if (!seen[it]) { seen.set(it); pending += it } }
    enqueue(starts)
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        val instruction = instructions[at]
        if (readsRegister(instruction, register)) reads += at
        // An instruction that throws never writes its destination, so its handlers see the value.
        enqueue(exceptional[at])
        if (!writesRegister(instruction, register)) enqueue(normal[at])
    }
    return reads.toList()
}

/**
 * Whether the instruction at [index] reads [register] as a float and as nothing else: a float
 * compare, float arithmetic or conversion, a float argument, a float field or a float return.
 * Anything else, a move included, answers false, so a caller that needs every use to be a
 * float refuses rather than guesses.
 */
fun Method.readsRegisterAsFloat(index: Int, register: Int): Boolean {
    val instruction = implementation!!.instructions.elementAt(index)
    val registers = instruction.namedRegisters()
    return when (instruction.opcode) {
        Opcode.CMPL_FLOAT, Opcode.CMPG_FLOAT,
        Opcode.ADD_FLOAT, Opcode.SUB_FLOAT, Opcode.MUL_FLOAT, Opcode.DIV_FLOAT, Opcode.REM_FLOAT,
        Opcode.ADD_FLOAT_2ADDR, Opcode.SUB_FLOAT_2ADDR, Opcode.MUL_FLOAT_2ADDR,
        Opcode.DIV_FLOAT_2ADDR, Opcode.REM_FLOAT_2ADDR,
        Opcode.NEG_FLOAT, Opcode.FLOAT_TO_INT, Opcode.FLOAT_TO_LONG, Opcode.FLOAT_TO_DOUBLE,
        -> register in registers
        // The value register of a field write is operand A; B is the object.
        Opcode.IPUT, Opcode.SPUT -> {
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            (instruction as OneRegisterInstruction).registerA == register &&
                (instruction !is TwoRegisterInstruction || instruction.registerB != register) &&
                field.type == "F"
        }
        Opcode.RETURN -> returnType == "F" && (instruction as OneRegisterInstruction).registerA == register
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_SUPER, Opcode.INVOKE_DIRECT, Opcode.INVOKE_STATIC,
        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_SUPER_RANGE,
        Opcode.INVOKE_DIRECT_RANGE, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_INTERFACE_RANGE,
        -> {
            val reference = (instruction as ReferenceInstruction).reference as MethodReference
            val slots = mutableListOf<String>()
            if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
                slots += reference.definingClass
            }
            reference.parameterTypes.forEach { type ->
                slots += type.toString()
                if (type == "J" || type == "D") slots += "$type (upper half)"
            }
            registers.contains(register) &&
                registers.withIndex().all { (slot, named) -> named != register || slots.getOrNull(slot) == "F" }
        }
        else -> false
    }
}
