/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.voicecomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities

@Suppress("unused")
val enableVoiceCommentsPatch = bytecodePatch(
    name = "Enable voice comments",
    description = "Enables TikTok's native voice-comment recording and publishing entry points.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        VoiceCommentPublishGateFingerprint.method.addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}
