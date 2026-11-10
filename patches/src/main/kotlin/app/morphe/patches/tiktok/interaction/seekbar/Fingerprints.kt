/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/seekbar/Fingerprints.kt
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val AWEME_CLASS = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val VIDEO_CONTROL_CLASS = "Lcom/ss/android/ugc/aweme/feed/model/VideoControl;"
internal const val SHOW_PROGRESS_BAR_FIELD = "showProgressBar"

/**
 * TikTok's "may this video show the progress bar" check, which reads the server's
 * `VideoControl.showProgressBar`. The seek bar asks it before showing ("can not show seekbar,
 * state: 4, can not show progressbar"), and the player asks it before posting play progress, which
 * is what brings the bar back while a video plays.
 *
 * <p>The field read is what pins it. The shape alone (public static final, takes the video,
 * answers a boolean, short, in a class that names a feed tab) fits about twenty methods on 47.1.4,
 * and the patcher took the first in dex order: the ad-traffic check in classes3, ahead of this one
 * in classes4. With the switch on every video then read as an ad, and this check was never
 * answered, so the bar stayed off the For You feed (#134).
 */
internal object ShouldShowProgressBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(AWEME_CLASS),
    // The filter repeats the read the custom block requires, so the patcher reads only the classes
    // that make it.
    filters = listOf(fieldAccess(definingClass = VIDEO_CONTROL_CLASS, name = SHOW_PROGRESS_BAR_FIELD, opcode = Opcode.IGET)),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.IGET &&
                instruction.getReference<FieldReference>()?.let { field ->
                    field.definingClass == VIDEO_CONTROL_CLASS && field.name == SHOW_PROGRESS_BAR_FIELD
                } == true
        } == true
    },
)

/**
 * The setter that logs the type it was handed. The patch reads that type from the last
 * parameter, so the last parameter has to be an int, and there has to be one: the string alone
 * would also match a method that logs it from a field.
 */
internal object SetSeekBarShowTypeFingerprint : Fingerprint(
    strings = listOf("seekbar show type change, change to:").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.parameterTypes.lastOrNull()?.toString() == "I" },
)
