/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction as patcherAddInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions as patcherAddInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels as patcherAddInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction

/*
 * The patcher's inline smali, checked.
 *
 * morphe-patcher's smali compiler never reads its tree walker's error count, so an instruction
 * whose register doesn't fit its operand is left out of the output instead of refused: p0 in
 * `iget-object v0, p0, ...` or `invoke-static {p0}, ...` when the method has more than 16
 * registers. A dropped hook call is silent; the patch applies, the dex verifies and the hook
 * never runs (found by the Hushfacebook anchor audit, 2026-09-26). These keep the patcher's names
 * and count what the compiler kept against the instruction lines the patch wrote, so a drop
 * stops the patch and names the method. Patches import these, never the patcher's own; a
 * contract test holds that.
 */

fun MutableMethod.addInstruction(index: Int, smali: String) =
    keepsEvery(smali) { patcherAddInstruction(index, smali) }

fun MutableMethod.addInstruction(smali: String) =
    keepsEvery(smali) { patcherAddInstruction(smali) }

fun MutableMethod.addInstructions(index: Int, smali: String) =
    keepsEvery(smali) { patcherAddInstructions(index, smali) }

fun MutableMethod.addInstructions(smali: String) =
    keepsEvery(smali) { patcherAddInstructions(implementation!!.instructions.size, smali) }

fun MutableMethod.addInstructionsWithLabels(index: Int, smali: String, vararg externalLabels: ExternalLabel) =
    keepsEvery(smali) { patcherAddInstructionsWithLabels(index, smali, *externalLabels) }

// Built instructions go in as they are; there is nothing to compile and nothing to drop.
fun MutableMethod.addInstruction(index: Int, instruction: BuilderInstruction) =
    patcherAddInstruction(index, instruction)

fun MutableMethod.addInstruction(instruction: BuilderInstruction) =
    patcherAddInstruction(instruction)

fun MutableMethod.addInstructions(index: Int, instructions: List<BuilderInstruction>) =
    patcherAddInstructions(index, instructions)

fun MutableMethod.addInstructions(instructions: List<BuilderInstruction>) =
    patcherAddInstructions(instructions)

private fun MutableMethod.keepsEvery(smali: String, add: () -> Unit) =
    keepsInstructionCount(smaliInstructionCount(smali), smali, add)

/** Also checks Patcher-owned emitters that build their inline smali internally. */
internal fun MutableMethod.keepsInstructionCount(written: Int, description: String, add: () -> Unit) {
    val before = countedInstructions()
    // When the compiler leaves out every instruction, the patcher asks the empty result for its
    // first one instead of saying why.
    val kept = try {
        add()
        countedInstructions() - before
    } catch (_: NoSuchElementException) {
        0
    }
    if (kept != written) {
        throw PatchException(
            "$definingClass->$name: the inline smali compiler kept $kept of $written instructions. " +
                "A register the operand can't hold (a p register above v15 in a 4-bit operand, " +
                "with ${implementation!!.registerCount} registers here) is the usual cause: " +
                description.trim().lines().joinToString(" | ") { it.trim() },
        )
    }
}

/**
 * The method's instructions without its nops. dexlib2 keeps every switch and array payload on a
 * four-byte boundary by adding or taking away a nop before it whenever an insert moves it, so a
 * one-instruction hook in a method with a payload can leave the count where it was or raise it
 * by two (Disable telemetry, Playback speed, Region spoof and the browser guard on 47.0.3).
 */
private fun MutableMethod.countedInstructions() = implementation!!.instructions.count { it.opcode != Opcode.NOP }

/**
 * The instructions a snippet of inline smali names: every line but blanks, comments, labels, nops
 * (which [countedInstructions] leaves out) and the directives that only annotate. A payload directive (a switch or array table) compiles to
 * one instruction of its own, which no patch here writes, so one is refused rather than counted
 * wrong.
 */
internal fun smaliInstructionCount(smali: String): Int {
    var count = 0
    for (raw in smali.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(":")) continue
        if (line == "nop" || line.startsWith("nop ") || line.startsWith("nop#")) continue
        if (line.startsWith(".")) {
            if (PAYLOADS.any { line.startsWith(it) }) {
                throw PatchException("Inline smali with a payload table can't be counted: $line")
            }
            continue
        }
        count++
    }
    return count
}

private val PAYLOADS = listOf(".packed-switch", ".sparse-switch", ".array-data")
