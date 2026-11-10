/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.returnNullWhenOn
import app.morphe.patches.tiktok.shared.returnVoidWhenOn
import app.morphe.util.addInstruction

private const val PATCH = "Stop on-device AI profiling"
internal const val AI_PROFILING_SWITCH = "Lapp/morphe/extension/tiktok/privacy/AiProfiling;->stopsEngine()Z"

@Suppress("unused")
val aiProfilingGovernorPatch = bytecodePatch(
    name = "Stop on-device AI profiling",
    description = "Lets you stop TikTok's built-in AI engine from starting, so it doesn't get a " +
        "copy of everything TikTok logs about your use. TikTok works as if the engine weren't " +
        "there. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installAiProfilingSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAiProfiling()V",
        )
    }
}

/**
 * The patch's hooks, without its settings row, so a test can put them on a build that carries no
 * extension. With the switch off all three run as TikTok wrote them.
 */
context(patchContext: BytecodePatchContext)
internal fun installAiProfilingSwitch() {
    // No plugin: TikTok takes its own "not installed" path, so the plugin's start-up (its
    // native libraries, boot executor and real core) never runs and no event is copied to it.
    PitayaPluginLookupFingerprint.method.returnNullWhenOn(PATCH, AI_PROFILING_SWITCH)
    // Should the plugin arrive another way, its engine still can't attach, and TikTok's
    // stand-in core keeps answering "host not ready".
    PitayaRealProviderFingerprint.method.returnVoidWhenOn(PATCH, AI_PROFILING_SWITCH)
    PitayaLiteStartFingerprint.method.returnVoidWhenOn(PATCH, AI_PROFILING_SWITCH)
}
