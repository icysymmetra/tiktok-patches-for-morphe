/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/shared/Fingerprints.kt
 */
package app.morphe.patches.tiktok.shared

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object GetEnterFromFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Z"),
    custom = { method, classDef ->
        method.definingClass.endsWith("/BaseListFragmentPanel;") &&
            method.returnType == "Ljava/lang/String;" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Z"
    },
)

internal object OnRenderFirstFrameFingerprint : Fingerprint(
    definingClass = "/feed/controller/PlayerController;",
    name = "onRenderFirstFrame",
    returnType = "V",
    parameters = listOf("L"),
)

