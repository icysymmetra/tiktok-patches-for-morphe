/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.offlinevideos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** The log line TikTok writes when a phone low on space shortens the lifetime. Only the calculation writes it. */
internal const val OFFLINE_LIFETIME_LOG = "resolveExpiredIntervalMs: low-storage override="

/** When the offline page was last opened, which picks between TikTok's two fallback lifetimes. */
internal const val OFFLINE_DETAIL_VISIT_KEY = "key_last_enter_offline_detail_timestamp"

private const val OFFLINE_LIFETIME_EXTENSION = "Lapp/morphe/extension/tiktok/offline/OfflineVideoExpiry;"

/**
 * How long an offline video lives, in milliseconds (#123). TikTok answers with a low-storage
 * figure in hours when the phone is short on room, else a server figure in hours, else 48 hours
 * when the offline page was opened in the last two days and 90 days when it wasn't. One getter
 * caches the answer per account, and every query over the offline table hides a row once
 * `insert_time` plus that lifetime has passed; the start-up task then deletes those rows and
 * their files. That is how a list saved in one go disappears in one go, watched or not.
 *
 * The method is static, takes nothing and is renamed per build, so it is found by the two strings
 * it loads: its own low-storage log line and the key of the last visit to the offline page.
 */
internal object OfflineVideoLifetimeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "J",
    parameters = emptyList(),
    strings = listOf(OFFLINE_LIFETIME_LOG, OFFLINE_DETAIL_VISIT_KEY).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Puts the Keep offline videos switch in front of the lifetime. With the switch on the method
 * answers the extension's kept lifetime at once, so nothing expires and the start-up task finds
 * nothing to delete, and with it off TikTok's own calculation runs from its first instruction.
 * The answer is a long, a pair of registers, so the frame needs two locals; the method has no
 * parameters, so every register it has is one.
 */
internal fun MutableMethod.keepOfflineVideos(patch: String) {
    requireLocals(patch, 2)
    guardAtEntry(
        patch,
        "invoke-static {}, $OFFLINE_LIFETIME_EXTENSION->keepOfflineVideos()Z",
        """
            invoke-static {}, $OFFLINE_LIFETIME_EXTENSION->keptLifetimeMs()J
            move-result-wide v0
            return-wide v0
        """,
    )
}

/** Written by the boot step that moves Auto adjust's tier. Its downgrade branch is the only place that line appears. */
internal const val AUTO_ADJUST_BOOT_LOG = "tryEvaluateOnBoot: DOWNGRADE triggered, target="

/** Written by the step that puts the tier back to a legacy one. Only that method writes it. */
internal const val AUTO_ADJUST_ROLLBACK_LOG = "rollbackToLegacyTier: "

/** Written when the experiment that turned offline mode on by default has ended and TikTok clears what it saved. */
internal const val DEFAULT_ENABLE_CLEANUP_LOG = "initiateDefaultEnableState: exp disabled, need cleanup"

/**
 * Auto adjust's boot step (#123). At every start it clamps the offline limit into the server's
 * range, moves the tier up or down, and after a downgrade the boot task trims the list to the
 * lower count ("bootTask: trim exceeded on paused downgrade"). Static, no parameters, renamed per
 * build, so it is found by the line its downgrade branch logs.
 */
internal object AutoAdjustBootFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(AUTO_ADJUST_BOOT_LOG).map { string(it, StringComparisonType.CONTAINS) },
)

/** The step that drops Auto adjust back to a legacy tier, another way the count falls. */
internal object AutoAdjustRollbackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(AUTO_ADJUST_ROLLBACK_LOG).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The start-up question whether the default-on experiment has ended. A yes makes the boot task
 * run the same clear the user's Delete all runs. Static, no parameters, answers `Z`.
 */
internal object DefaultEnableStateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = emptyList(),
    strings = listOf(DEFAULT_ENABLE_CLEANUP_LOG).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Lets Auto adjust's boot step and its legacy rollback leave the limit alone while the switch is
 * on. Both are static with no parameters and a few dozen locals, so the guard's v0 is free, and
 * with the answer true they return at once, before anything is read or written.
 */
internal fun MutableMethod.keepThroughAutoAdjust(patch: String) {
    requireLocals(patch, 1)
    guardAtEntry(
        patch,
        "invoke-static {}, $OFFLINE_LIFETIME_EXTENSION->keepThroughAutoAdjust()Z",
        "return-void",
    )
}

/**
 * The index of the `return` that answers yes to the default-on clean-up: the first one after the
 * log line, with at most the log call between. Fails closed on any other shape.
 */
internal fun MutableMethod.defaultEnableCleanupReturnIndex(patch: String): Int {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$patch: $definingClass->$name has no implementation")
    val logs = instructions.withIndex().filter { (_, instruction) ->
        (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
            instruction.getReference<StringReference>()?.string == DEFAULT_ENABLE_CLEANUP_LOG
    }
    if (logs.size != 1) {
        throw PatchException("$patch: expected one \"$DEFAULT_ENABLE_CLEANUP_LOG\" in $definingClass->$name, found ${logs.size}.")
    }
    val logIndex = logs.single().index
    val returnIndex = (logIndex + 1..minOf(logIndex + 3, instructions.lastIndex))
        .firstOrNull { instructions[it].opcode == Opcode.RETURN }
        ?: throw PatchException("$patch: no return within three instructions of the clean-up line in $definingClass->$name.")
    val between = instructions.subList(logIndex + 1, returnIndex)
    if (between.any { it.opcode.name.startsWith("IF_") || it.opcode.name.startsWith("GOTO") || it.opcode == Opcode.THROW }) {
        throw PatchException("$patch: a branch sits between the clean-up line and its return in $definingClass->$name.")
    }
    return returnIndex
}

/**
 * Turns the default-on clean-up's yes into "nothing to clean up" while the switch is on. The
 * extension gets TikTok's answer and hands back the one to return, in the register the return
 * already reads.
 */
internal fun MutableMethod.keepThroughDefaultEnableCleanup(patch: String) {
    val returnIndex = defaultEnableCleanupReturnIndex(patch)
    val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
    if (register > 15) {
        throw PatchException("$patch: the clean-up answer sits in v$register of $definingClass->$name, past what a plain invoke names.")
    }
    addInstructions(
        returnIndex,
        """
            invoke-static {v$register}, $OFFLINE_LIFETIME_EXTENSION->keepThroughDefaultEnableCleanup(Z)Z
            move-result v$register
        """,
    )
}
