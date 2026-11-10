/*
 * Bytecode behavior adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0).
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.optimizer

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val reviewedSplashGateInstructionCounts = setOf(
    listOf(4, 4, 30),
    listOf(4, 4),
)

internal fun isReviewedSplashGateShape(instructionCounts: List<Int>): Boolean =
    instructionCounts.sorted() in reviewedSplashGateInstructionCounts

internal const val ANIMATED_DRAWABLE_DESCRIPTOR = "Lcom/facebook/fresco/animation/drawable/AnimatedDrawable2;"

/**
 * Fresco's animated drawable factory: it reads the caching strategy from a supplier, compares it
 * with 1, 2 and 3, and builds a FrescoFrameCache for the first two. R8 renames the class and the
 * method; the drawable it returns and the cache it builds keep their names.
 */
internal fun Method.isAnimatedDrawableFactory(): Boolean =
    returnType == ANIMATED_DRAWABLE_DESCRIPTOR && parameterTypes.size == 1 &&
        implementation?.instructions?.any { it.methodReference()?.let { ref ->
            ref.definingClass == FRESCO_FRAME_CACHE_DESCRIPTOR && ref.name == "<init>"
        } == true } == true

/**
 * The animation backend builder the factory calls just before it constructs the drawable. It
 * reads how many frames to decode ahead (the request's own count, else a supplier's) into one
 * register and skips the frame preparer on if-lez. Returns the if-lez index, or null.
 */
internal fun Method.framePreparerGateIndex(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val read = instructions.indexOfFirst { it.isIntegerIntValue() }
    val result = instructions.getOrNull(read + 1)
    if (read < 0 || result?.opcode != Opcode.MOVE_RESULT) return null
    val register = (result as OneRegisterInstruction).registerA
    val gate = (read + 2 until instructions.size).firstOrNull {
        instructions[it].opcode == Opcode.IF_LEZ && (instructions[it] as OneRegisterInstruction).registerA == register
    } ?: return null
    // Nothing may jump straight to the gate, or a const put in front of it would be skipped.
    val addresses = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val gateAddress = addresses[gate]
    val targeted = instructions.indices.any { i ->
        val branch = instructions[i] as? OffsetInstruction
        branch != null && addresses[i] + branch.codeOffset == gateAddress
    }
    return if (targeted) null else gate
}

/**
 * Whether anything in the method lands on the instruction at [index]: a branch, a switch case or
 * an exception handler. Code put in front of such an instruction would be skipped on that path.
 */
internal fun Method.isBranchTarget(index: Int): Boolean {
    val body = implementation ?: return false
    val instructions = body.instructions.toList()
    val addresses = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val target = addresses.getOrNull(index) ?: return false
    val branched = instructions.indices.any { i ->
        val branch = instructions[i] as? OffsetInstruction ?: return@any false
        val landing = addresses[i] + branch.codeOffset
        if (branch.opcode == Opcode.PACKED_SWITCH || branch.opcode == Opcode.SPARSE_SWITCH) {
            val payload = instructions.getOrNull(addresses.indexOf(landing)) as? SwitchPayload
            payload?.switchElements?.any { addresses[i] + it.offset == target } == true
        } else {
            landing == target
        }
    }
    return branched || body.tryBlocks.any { block -> block.exceptionHandlers.any { it.handlerCodeAddress == target } }
}

/** The backend builder call in the factory: the last call on its own class before the drawable is made. */
internal fun Method.backendBuilderCall(): MethodReference? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val drawable = instructions.indexOfLast {
        it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == ANIMATED_DRAWABLE_DESCRIPTOR
    }
    if (drawable < 0) return null
    return instructions.subList(0, drawable).asReversed().firstNotNullOfOrNull { instruction ->
        instruction.methodReference()?.takeIf { it.definingClass == definingClass && instruction.opcode == Opcode.INVOKE_VIRTUAL }
    }
}

private fun Instruction.methodReference(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.isIntegerIntValue(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && methodReference()?.let {
        it.definingClass == "Ljava/lang/Integer;" && it.name == "intValue"
    } == true
