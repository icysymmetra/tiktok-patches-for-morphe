/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.looping

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val EXTENSION_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/interaction/StopVideoLoopingPatch;"

@Suppress("unused")
val stopVideoLoopingPatch = bytecodePatch(
    name = "Stop video looping",
    description = "Stops a video at its end instead of playing it again. Starts off. Turn it " +
        "on in Metra settings > App behavior.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableStopVideoLooping()V",
        )

        VideoEngineSetLoopingFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p1 .. p1}, $EXTENSION_DESCRIPTOR->overrideLooping(Z)Z
                move-result p1
            """,
        )
    }
}
