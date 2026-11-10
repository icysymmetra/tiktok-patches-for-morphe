/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"
private const val PATCH = "Show the progress bar"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

internal const val INVERSE_EXPERIMENT_LOG = "can not show seekbar, state: 6, hit inverse expriment"
internal const val CANNOT_DRAG_LOG = "can not show seekbar, state: 2, can not drag"
internal const val DRAFT_PROGRESS_BAR = "draftProgressBar"

/**
 * TikTok's list of reasons for keeping the seek bar off a video: one method that logs each by
 * name ("can not show seekbar, state: N, ...") and answers false. The bar's show type, which
 * Show the progress bar already rewrites, is only asked once this method has said yes.
 *
 * Two of the reasons are experiments rather than facts about the video. State 6 is an "inverse
 * experiment" that hides the bar on every video but paid content for whoever the server puts
 * in that group, which is how a bar can be there one day, gone the next and back after a
 * restart (issue #16: gone from the whole feed but present in the drama player, which plays
 * paid content). State 2, "can not drag", asks a second experiment and, without it, hides the
 * bar on any video the server did not flag as draggable. The gates themselves are renamed on
 * every build; the log lines are not, so each gate is found from its line.
 */
internal object SeekbarGateLogFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(AWEME),
    strings = listOf(INVERSE_EXPERIMENT_LOG, CANNOT_DRAG_LOG).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The gate a log line answers for: the nearest static call above the line, which has to take
 * the video and answer Z. Each check is a call, a move-result and a branch, and the log line is
 * the branch's fall-through, so the call sits a handful of instructions above it. The nearest
 * static call rather than the nearest one of the right shape, because the check before it also
 * has the right shape and is not far away.
 */
internal fun Method.gateBefore(log: String): MethodReference {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$PATCH: $definingClass->$name has no implementation")
    val at = instructions.indexOfFirst { it.getReference<StringReference>()?.string == log }
    if (at < 0) throw PatchException("$PATCH: $definingClass->$name does not log \"$log\"")
    for (index in at - 1 downTo maxOf(0, at - 12)) {
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) continue
        val reference = instruction.getReference<MethodReference>()
            ?: throw PatchException("$PATCH: the call above \"$log\" in $definingClass->$name names no method")
        val shape = "(${reference.parameterTypes.joinToString("")})${reference.returnType}"
        if (reference.returnType != "Z" || reference.parameterTypes.map(CharSequence::toString) != listOf(AWEME)) {
            throw PatchException(
                "$PATCH: the call above \"$log\" in $definingClass->$name is ${reference.name}$shape, not a (Aweme)Z gate",
            )
        }
        return reference
    }
    throw PatchException(
        "$PATCH: no static call within twelve instructions above \"$log\" in $definingClass->$name",
    )
}

/** The method [reference] names, or a refusal that says which class lost it. */
internal fun Iterable<MutableMethod>.named(reference: MethodReference): MutableMethod =
    firstOrNull {
        it.name == reference.name &&
            it.returnType == reference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
    } ?: throw PatchException("$PATCH: ${reference.definingClass} has no ${reference.name}")

/** Makes the inverse experiment answer that the bar may show, whenever the switch is on. */
internal fun MutableMethod.answerInverseExperiment() {
    guardAtEntry(
        PATCH,
        "invoke-static {}, $EXTENSION->isEnabled()Z",
        """
            const/4 v0, 0x1
            return v0
        """,
    )
}

/**
 * Overrides the video's draggable flag inside the can-drag gate: the read of
 * VideoControl.draftProgressBar, the last thing the gate asks once its experiment has said no.
 * The refusals before it, for an ad, a LIVE, a photo post or paid content, keep their answer,
 * because those never get a seek bar to begin with.
 *
 * @return false when the gate reads no such flag, which is left to the fixture test to hold
 *         against the builds that do; true when the read was wrapped.
 */
internal fun MutableMethod.answerDraggable(): Boolean {
    val reads = implementation!!.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IGET &&
            instruction.getReference<FieldReference>()?.name == DRAFT_PROGRESS_BAR
    }
    if (reads.isEmpty()) return false
    if (reads.size != 1) {
        throw PatchException("$PATCH: expected one $DRAFT_PROGRESS_BAR read in $definingClass->$name, found ${reads.size}")
    }
    val (index, instruction) = reads.single()
    val register = (instruction as TwoRegisterInstruction).registerA
    addInstructions(
        index + 1,
        """
            invoke-static/range {v$register .. v$register}, $EXTENSION->overrideDraftProgressBar(I)I
            move-result v$register
        """,
    )
    return true
}
