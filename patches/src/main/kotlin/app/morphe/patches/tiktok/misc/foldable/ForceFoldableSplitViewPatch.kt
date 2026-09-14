/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.foldable

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/foldable/FoldableSplitView;"

@Suppress("unused")
val foldableSplitViewPatch = bytecodePatch(
    name = "Foldable split comment view",
    description = "Forces TikTok's tablet-style split layout (video beside comments instead of a bottom sheet) " +
        "once the screen is at least as wide as a configurable threshold, for foldables TikTok doesn't " +
        "already recognize as tablet-class.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFoldableSplitView()V",
        )

        CommentSplitLiveCheckFingerprint.method.addInstructions(
            0,
            """
                invoke-static {p0, p1}, $EXTENSION_CLASS_DESCRIPTOR->shouldForceCommentSplit(Landroid/app/Activity;Landroid/content/res/Configuration;)Z
                move-result v0
                if-eqz v0, :morphe_stock_comment_split_check
                const/4 v0, 0x1
                return v0
                :morphe_stock_comment_split_check
                nop
            """,
        )

        CommentSplitContainerCheckFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldForceCommentSplitContainer()Z
                move-result v0
                if-eqz v0, :morphe_stock_split_container_check
                const/4 v0, 0x1
                return v0
                :morphe_stock_split_container_check
                nop
            """,
        )
    }
}
