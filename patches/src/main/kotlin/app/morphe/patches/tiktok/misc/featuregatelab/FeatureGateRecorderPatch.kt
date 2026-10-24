/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

@Suppress("unused")
val featureGateRecorderPatch = bytecodePatch(
    name = "Feature Gate Recorder",
    description = "Records which hidden TikTok settings the app reads while you use it, and " +
        "which ones changed. It's a research tool and changes nothing in TikTok. Start a " +
        "recording in Metra settings > Diagnostics.",
    default = false,
) {
    category("Settings")
    dependsOn(settingsPatch, featureGateLabPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeatureGateRecorder()V")
    }
}
