/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/**
 * The register typing ART's verifier applies to an owned bridge, as far as a bridge needs it.
 *
 * A reference keeps its declared type on both sides of an if-eqz, two different references meet
 * at a label as Object, and an Object or a primitive handed to a narrower parameter, or returned
 * where a narrower type is declared, rejects the whole class on the phone. Nothing off a device
 * runs the verifier, so this is the check that would have caught #165 before 0.72.0 shipped:
 * the profile tab crashed with "register v0 has type Reference java.lang.Object but expected
 * Reference: java.lang.String" in a bridge every fixture test had passed.
 *
 * Class hierarchies are not known here, so two named classes are never judged against each
 * other. The checks are exactly the ones a merge or a stray primitive can fail.
 */
object BridgeTypeFlow {
    private const val ZERO = "0"
    private const val UNSET = "?"
    private const val CLASH = "!"
    private const val NEUTRAL = "*"
    private const val OBJECT = "Ljava/lang/Object;"

    data class Problem(val index: Int, val message: String) {
        override fun toString() = "[$index] $message"
    }

    fun check(method: Method): List<Problem> {
        val implementation = method.implementation ?: return emptyList()
        val code = implementation.instructions.toList()
        val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        fun branchTarget(index: Int) = addresses.indexOf(addresses[index] + (code[index] as OffsetInstruction).codeOffset)
        val registers = implementation.registerCount
        val parameters = (if (AccessFlags.STATIC.isSet(method.accessFlags)) emptyList() else listOf(method.definingClass)) +
            method.parameterTypes.map { it.toString() }
        val entry = Array(registers) { UNSET }
        var slot = registers - parameters.sumOf { width(it) }
        for (parameter in parameters) {
            entry[slot] = normalize(parameter)
            slot += width(parameter)
        }
        val states = HashMap<Int, Array<String>>()
        val problems = linkedSetOf<Problem>()
        val work = ArrayDeque<Int>()
        fun arrive(index: Int, state: Array<String>) {
            if (index !in code.indices) return
            val known = states[index]
            val merged = if (known == null) state.copyOf() else Array(registers) { join(known[it], state[it]) }
            if (known == null || !merged.contentEquals(known)) {
                states[index] = merged
                work.addLast(index)
            }
        }
        arrive(0, entry)
        while (work.isNotEmpty()) {
            val at = work.removeFirst()
            val state = states.getValue(at).copyOf()
            val instruction = code[at]
            val name = instruction.opcode.name
            var next = listOf(at + 1)
            when {
                instruction.opcode == Opcode.NOP -> Unit
                name.startsWith("const-string") -> set(state, instruction, "Ljava/lang/String;")
                instruction.opcode == Opcode.CONST_CLASS -> set(state, instruction, "Ljava/lang/Class;")
                name.startsWith("const") -> set(state, instruction, when {
                    (instruction as WideLiteralInstruction).wideLiteral == 0L -> ZERO
                    name.contains("wide") -> "J"
                    else -> "I"
                })
                name.startsWith("move-result") -> set(state, instruction, returnTypeBefore(code, at))
                instruction.opcode == Opcode.MOVE_EXCEPTION -> set(state, instruction, "Ljava/lang/Throwable;")
                name.startsWith("move") -> {
                    val move = instruction as TwoRegisterInstruction
                    state[move.registerA] = state[move.registerB]
                    if (name.contains("wide")) state[move.registerA + 1] = state[move.registerB + 1]
                }
                instruction.opcode == Opcode.RETURN_VOID || instruction.opcode == Opcode.THROW -> next = emptyList()
                name.startsWith("return") -> {
                    val register = (instruction as OneRegisterInstruction).registerA
                    expect(problems, at, "the return", register, state[register], normalize(method.returnType))
                    next = emptyList()
                }
                name.startsWith("iget") || name.startsWith("sget") ->
                    set(state, instruction, normalize(((instruction as ReferenceInstruction).reference as FieldReference).type))
                name.startsWith("iput") || name.startsWith("sput") -> Unit
                instruction.opcode == Opcode.INSTANCE_OF || instruction.opcode == Opcode.ARRAY_LENGTH -> set(state, instruction, "I")
                instruction.opcode == Opcode.CHECK_CAST || instruction.opcode == Opcode.NEW_INSTANCE || instruction.opcode == Opcode.NEW_ARRAY ->
                    set(state, instruction, normalize(((instruction as ReferenceInstruction).reference as TypeReference).type))
                name.startsWith("invoke") -> checkCall(problems, at, instruction, state)
                name.startsWith("if-") -> next = listOf(at + 1, branchTarget(at))
                name.startsWith("goto") -> next = listOf(branchTarget(at))
                instruction is OneRegisterInstruction -> set(state, instruction, NEUTRAL)
            }
            next.forEach { arrive(it, state) }
        }
        return problems.toList()
    }

    private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

    private fun normalize(type: String) = if (type in listOf("Z", "B", "S", "C", "I")) "I" else type

    private fun isReference(type: String) = type.startsWith("L") || type.startsWith("[")

    private fun set(state: Array<String>, instruction: Instruction, type: String) {
        val register = (instruction as OneRegisterInstruction).registerA
        state[register] = type
        if (type == "J" || type == "D") state[register + 1] = type
    }

    private fun join(a: String, b: String): String = when {
        a == b -> a
        a == UNSET || b == UNSET || a == CLASH || b == CLASH -> CLASH
        a == NEUTRAL || b == NEUTRAL -> NEUTRAL
        a == ZERO -> if (isReference(b) || b == "I") b else CLASH
        b == ZERO -> if (isReference(a) || a == "I") a else CLASH
        isReference(a) && isReference(b) -> OBJECT
        else -> CLASH
    }

    private fun returnTypeBefore(code: List<Instruction>, at: Int): String {
        val call = code.getOrNull(at - 1) as? ReferenceInstruction ?: return NEUTRAL
        return when (val reference = call.reference) {
            is MethodReference -> normalize(reference.returnType)
            is TypeReference -> normalize(reference.type)
            else -> NEUTRAL
        }
    }

    private fun checkCall(problems: MutableSet<Problem>, at: Int, instruction: Instruction, state: Array<String>) {
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        val arguments = when (instruction) {
            is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                instruction.registerF, instruction.registerG).take(instruction.registerCount)
            is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
            else -> return
        }
        val static = instruction.opcode.name.startsWith("invoke-static")
        val parameters = (if (static) emptyList() else listOf(reference.definingClass)) + reference.parameterTypes.map { it.toString() }
        var slot = 0
        for (parameter in parameters) {
            val register = arguments.getOrNull(slot) ?: break
            expect(problems, at, "${reference.name}'s argument", register, state[register], normalize(parameter))
            slot += width(parameter)
        }
    }

    private fun expect(problems: MutableSet<Problem>, at: Int, what: String, register: Int, actual: String, expected: String) {
        val message = when {
            actual == NEUTRAL -> return
            actual == UNSET -> "register v$register is not set on every path into $what"
            actual == CLASH -> "register v$register holds different kinds on the paths into $what"
            isReference(expected) -> when {
                actual == ZERO || expected == OBJECT -> return
                actual == OBJECT -> "register v$register has type $actual but expected $expected: two references met at a label"
                isReference(actual) -> return
                else -> "register v$register holds $actual but expected $expected"
            }
            actual == ZERO && expected == "I" -> return
            actual == expected -> return
            else -> "register v$register holds $actual but expected $expected"
        }
        problems.add(Problem(at, "$what: $message"))
    }
}
