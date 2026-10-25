/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/speed/Fingerprints.kt
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object PlaybackSpeedSelectionBoundaryFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "F",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    strings = listOf(
        "swipe_up_lock_persist",
        "click_share_button",
        "long_press",
    ).map { string(it, StringComparisonType.CONTAINS) },
)

internal fun Method.playerManagerSpeedBoundary(): MethodReference? {
    val candidates = implementation?.instructions?.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        instruction.getReference<MethodReference>()?.takeIf { reference ->
            reference.parameterTypes == listOf("F") && reference.returnType == "V"
        }
    }?.distinctBy(MethodReference::toString).orEmpty()
    return candidates.singleOrNull()
}

internal object PlayerControllerSetSpeedFingerprint : Fingerprint(
    definingClass = "/feed/controller/PlayerController;",
    returnType = "V",
    parameters = listOf("F"),
    strings = listOf("speed_begin", "begin_speed").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.playerManagerSpeedBoundary() != null },
)

internal object PlaybackSpeedMenuFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"),
    strings = listOf("is_highlight_fast_speed").map { string(it, StringComparisonType.CONTAINS) },
)

internal object LongPressSpeedUpEnableFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf("long_press_speed_up_enable").map { string(it, StringComparisonType.CONTAINS) },
    // Match the native experiment read, not the same key in Feature Gate Lab's catalog.
    filters = listOf(methodCall(parameters = listOf("I", "Ljava/lang/String;", "Z", "Z"), returnType = "Z")),
)

internal object LongPressSpeedUpLockFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = emptyList(),
    strings = listOf(string("long_press_speed_up_lock")),
    filters = listOf(methodCall(parameters = listOf("I", "I", "Ljava/lang/String;", "Z"), returnType = "I")),
)

/**
 * The hold gesture's press and release on the edge speed-up component. The class keeps its
 * name on every build and the methods are renamed, so each is found by the Keva keys and
 * telemetry names it alone writes. Shared with the fixture test that pins the 2x literals.
 */
internal val EDGE_SPEEDUP_PRESS_STRINGS = listOf("ripple_shown_count", "triggered_ripple_by_comment_or_share_button")
internal val EDGE_SPEEDUP_RELEASE_STRINGS = listOf("speedup_duration", "edges_shown_cnt", "used_lock_speedup")

/** Press: stores the speed the video was at, applies the 2x, shows the edge ripples. */
internal object EdgeSpeedupPressFingerprint : Fingerprint(
    definingClass = "/feed/longvideo/edgespeedup/EdgeSpeedupAssem;",
    returnType = "V",
    parameters = listOf("F", "F"),
    strings = EDGE_SPEEDUP_PRESS_STRINGS.map { string(it, StringComparisonType.CONTAINS) },
)

/** Release: the speed-up telemetry, then the speed the video goes back to or locks at. */
internal object EdgeSpeedupReleaseFingerprint : Fingerprint(
    definingClass = "/feed/longvideo/edgespeedup/EdgeSpeedupAssem;",
    returnType = "V",
    parameters = listOf("Z"),
    strings = EDGE_SPEEDUP_RELEASE_STRINGS.map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The feed's receiver for the event the press method posts: it reads the event's map and
 * shows either the plain speed banner or, with the lock on, the lock banner. The map's keys
 * are read here and nowhere else together.
 */
internal val HOLD_BANNER_RECEIVER_STRINGS = listOf("pull_down", "need_guide", "current_speed")

internal object HoldBannerReceiverFingerprint : Fingerprint(
    returnType = "V",
    strings = HOLD_BANNER_RECEIVER_STRINGS.map { string(it, StringComparisonType.CONTAINS) },
)
