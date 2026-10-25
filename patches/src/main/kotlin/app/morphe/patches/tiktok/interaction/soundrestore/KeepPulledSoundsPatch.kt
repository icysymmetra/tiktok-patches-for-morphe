/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.soundrestore

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Keep pulled sounds"
internal const val PULLED_SOUNDS = "Lapp/morphe/extension/tiktok/playback/PulledSounds;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val MUSIC = "Lcom/ss/android/ugc/aweme/music/model/Music;"
internal const val VIDEO_MUTE_INFO = "Lcom/ss/android/ugc/aweme/feed/model/AwemeStatus\$VideoMuteInfo;"

/** A no-argument read of [owner].[name] answering [returnType], in either invoke shape. */
private fun Method.readsOf(owner: String, name: String, returnType: String): List<Int> =
    implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            instruction.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == owner && reference.name == name &&
                    reference.parameterTypes.isEmpty() && reference.returnType == returnType
            } == true
    }?.map { it.index }.orEmpty()

/** Where the mute decision reads the sound's status: 0 is a sound TikTok pulled. */
internal fun Method.musicStatusReads() = readsOf(MUSIC, "getMusicStatus", "I")

/** Where the mute decision reads the post's own mute flag, set when TikTok silences a post. */
internal fun Method.videoMuteReads() = readsOf(VIDEO_MUTE_INFO, "isMute", "Z")

/**
 * The feed player's mute decision, called as each video starts: it mutes the player when the
 * post's sound has status 0 (pulled, with TikTok's own exceptions for stories and verified ad
 * measurement), then again when the post's VideoMuteInfo says muted, showing that info's reason
 * once; otherwise it unmutes. PlayerController keeps its name on every build and the method is
 * renamed (LLJLLIL on 47.0.3, LLJZ on 47.1.3 and 47.1.4), so it's the controller's one
 * (Aweme, player, boolean) void that reads both answers, each exactly once.
 */
internal object PlayerMuteDecisionFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
    returnType = "V",
    parameters = listOf(AWEME, "L", "Z"),
    custom = { method, _ -> method.musicStatusReads().size == 1 && method.videoMuteReads().size == 1 },
)

/**
 * Search's copyright mute: true for a post TikTok silenced for its sound, when it's shown in
 * search results. The service's other answer and its "sound removed" label both go through it.
 * The class keeps its name on every build; the method is the one taking the post and the page
 * name that compares that name with both search pages.
 */
internal object SearchCopyrightMuteFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/pages/result/common/copyrightmute/core/communicate/" +
        "SearchCopyrightMuteServiceImpl;",
    returnType = "Z",
    parameters = listOf(AWEME, "Ljava/lang/String;"),
    strings = listOf("general_search", "search_result").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Hands each of the decision's two answers to the extension right after TikTok reads it, so
 * TikTok's own reads, exceptions and order stay as they are and only the answer changes. A
 * range call is used because the result register isn't guaranteed to be below v16.
 */
internal fun MutableMethod.passMuteAnswersThroughExtension() {
    val status = musicStatusReads()
    val muted = videoMuteReads()
    if (status.size != 1 || muted.size != 1) {
        throw PatchException(
            "$PATCH: the mute decision reads the sound's status ${status.size} time(s) and the post's mute " +
                "${muted.size} time(s), expected once each.",
        )
    }
    val instructions = implementation!!.instructions.toList()
    val hooks = listOf(
        status.single() to "keepStatus(I)I",
        muted.single() to "keepMuted(Z)Z",
    ).sortedByDescending { it.first }
    for ((index, callback) in hooks) {
        val result = instructions.getOrNull(index + 1)
        if (result?.opcode != Opcode.MOVE_RESULT) {
            throw PatchException("$PATCH: a mute decision read isn't followed by its result.")
        }
        val register = (result as OneRegisterInstruction).registerA
        addInstructions(
            index + 2,
            """
                invoke-static/range {v$register .. v$register}, $PULLED_SOUNDS->$callback
                move-result v$register
            """,
        )
    }
}

@Suppress("unused")
val keepPulledSoundsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Keep pulled sounds",
    description = "Plays the sound on videos TikTok muted for copyright or because of where " +
        "you live. TikTok may still say the sound isn't available. Starts off. Turn it on in " +
        "Metra settings > App behavior.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val decision = PlayerMuteDecisionFingerprint.method
        val search = SearchCopyrightMuteFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableKeepPulledSounds()V",
        )
        decision.passMuteAnswersThroughExtension()
        search.guardAtEntry(
            PATCH,
            "invoke-static {}, $PULLED_SOUNDS->keepInSearch()Z",
            "const/4 v0, 0x0\nreturn v0",
        )
    }
}
