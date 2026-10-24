/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/share/Fingerprints.kt
 */
package app.morphe.patches.tiktok.misc.share

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object ShareUrlTrackerFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("utm_campaign", "share_link_id").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.count { it == "Ljava/lang/String;" } >= 2
    },
)
