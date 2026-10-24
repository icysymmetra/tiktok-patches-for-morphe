/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedmute

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal const val TT_VIDEO_ENGINE = "Lcom/ss/ttvideoengine/TTVideoEngine;"
internal const val SIM_AUDIO_CHECK_START =
    "Lcom/ss/android/ugc/aweme/player/sdk/audio/SimAudioFocusManager\$checkStart\$1;"

/**
 * The feed player's engine starting: TikTok's TTVideoEngine subclass that logs as
 * "MTTVideoEngine" (47.0.3 `LX/04jv;`, 47.1.3 `LX/05Nu;`). Its play() keeps its real name and
 * opens with the call to TTVideoEngine's own, which tells it from the other play() methods
 * carrying the same log tag.
 */
internal object FeedEnginePlayFingerprint : Fingerprint(
    name = "play",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    strings = listOf("MTTVideoEngine").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.implementation?.instructions?.firstOrNull()?.let {
            it.opcode == Opcode.INVOKE_SUPER &&
                (it as ReferenceInstruction).reference.toString() == "$TT_VIDEO_ENGINE->play()V"
        } == true
    },
)

/**
 * The engine's setIsMute, by its log line (47.0.3 `LX/04ko;->LJI(Z)V`, 47.1.3
 * `LX/05PX;->LJII(Z)V`). The one to call is TTVideoEngine's public forwarder to it.
 */
internal object EngineSetIsMuteFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("setIsMute:%s").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * SimAudioFocusManager's request, keyed by the player session asking (47.0.3
 * `LX/03kz;->LJIIIIZZ`, 47.1.3 `LX/03hz;`): the key, the session's listener, and a Handler.
 */
internal object SimAudioFocusRequestFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("SimAudioFocusManager").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.parameterTypes.size == 3 &&
            method.parameterTypes.first().toString() == "Ljava/lang/Object;" &&
            method.parameterTypes.last().toString() == "Landroid/os/Handler;"
    },
)

/**
 * A player session's give-up of that focus (47.0.3 `LX/03kc;->LIZ()V`, 47.1.3 `LX/03ho;`),
 * the one method with the manager's log tag that builds its checkStart callback. Its class
 * holds the session's request too.
 */
internal object SimAudioSessionAbandonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    strings = listOf("SimAudioFocusManager").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            it.opcode == Opcode.NEW_INSTANCE &&
                (it as ReferenceInstruction).reference.toString() == SIM_AUDIO_CHECK_START
        } == true
    },
)
