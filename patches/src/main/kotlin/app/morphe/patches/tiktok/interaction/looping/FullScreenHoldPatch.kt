/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.looping

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.implementationOrPatchException

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/FullScreenHold;"
internal const val LANDSCAPE_AUTOPLAY_HINT =
    "Lcom/ss/android/ugc/aweme/feed/landscape/cell/assem/autoplay/LandScapeAutoPlayHintAssem;"

/**
 * The full-screen viewer's own auto-next gate. When a video completes, the viewer's autoplay hint
 * (a real class name on every build) asks this method whether to move to the next cell, and its
 * progress handler asks the same one whether to show the "next video in N" countdown. It is the
 * hint's only method that takes nothing and returns a boolean; its own name is R8's and changes
 * between builds (Rr on 47.0.3, Kr on 47.1.3).
 */
internal object LandscapeAutoPlayGateFingerprint : Fingerprint(
    definingClass = LANDSCAPE_AUTOPLAY_HINT,
    returnType = "Z",
    parameters = emptyList(),
)

@Suppress("unused")
val fullScreenHoldPatch = bytecodePatch(
    name = "Stay on the video in full screen",
    description = "When a video ends in TikTok's full screen view, it stays on that video " +
        "instead of moving to the next one. Swiping still moves on. Starts off. Turn it on in " +
        "Metra settings > App behavior.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFullScreenHold()V",
        )

        val gate = LandscapeAutoPlayGateFingerprint.method
        val body = gate.implementationOrPatchException("Stay on the video in full screen")
        // p0 is the highest register, so v0 is free at entry whenever there is more than one.
        if (body.registerCount < 2) {
            throw PatchException(
                "Stay on the video in full screen: the autoplay gate has ${body.registerCount} " +
                    "register(s), so there is no free one to answer with.",
            )
        }
        gate.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION->hold()Z
                move-result v0
                if-eqz v0, :morphe_full_screen_native
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("morphe_full_screen_native", gate.getInstruction(0)),
        )
    }
}
