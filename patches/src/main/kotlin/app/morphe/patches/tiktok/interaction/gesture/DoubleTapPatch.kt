/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.numberOfParameterRegisters

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/GestureActions;"
private const val COMMENT_CLASS = "Lcom/ss/android/ugc/aweme/feed/assem/videocomment/VideoCommentAssem;"

/**
 * Every place a double tap on a video turns into a like on 46.2.3, 46.7.3 and 46.8.3.
 *
 * Guarding one of them is not enough, and guarding the legacy panel alone is what the bug
 * report was about: `BaseListFragmentPanel` is reached through the `LX/0QfZ` interface from the
 * gesture detector, but the modern feed panel never goes through it. There, the detector holds
 * the digg component directly and calls `DiggPanelComponent.handleDoubleClick`, so the like
 * happened whatever the setting said. The landscape player carries a third implementation of
 * the same gesture. The Friends tab had a fourth until 47.4.3 removed its V3 feed, and its feed
 * now goes through the legacy panel.
 *
 * One more class declares the method and can't like anything (`LX/0SPC` on 46.x returns
 * immediately), so it's left alone deliberately rather than missed.
 */
internal val doubleTapRouteMethods = listOf(
    // The legacy feed panel, reached through LX/0QfZ.handleDoubleClick.
    "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;" to "handleDoubleClick",
    // The modern feed panel. Its gesture listener calls this directly, and it is where the
    // legacy panel ends up too, so this one covers the ordinary feed on its own.
    "Lcom/ss/android/ugc/feed/platform/panel/digg/DiggPanelComponent;" to "handleDoubleClick",
    // The landscape player, which builds its own click_double_like event.
    "Lcom/ss/android/ugc/aweme/feed/landscape/LandscapeFragmentPanel;" to "handleDoubleClick",
)

private fun doubleTapRoute(owner: String, method: String) = Fingerprint(
    definingClass = owner, name = method,
    parameters = listOf("Landroid/view/MotionEvent;"), returnType = "V",
)

private object CommentViewFingerprint : Fingerprint(
    definingClass = COMMENT_CLASS, name = "onViewCreated",
    parameters = listOf("Landroid/view/View;"), returnType = "V",
)
private object CommentBindFingerprint : Fingerprint(
    definingClass = COMMENT_CLASS,
    // This generic callback is erased to Object in DEX, despite jadx displaying VideoItemParams.
    parameters = listOf("Ljava/lang/Object;"), returnType = "V",
)

@Suppress("unused")
val doubleTapPatch = bytecodePatch(
    name = "Double-tap controls",
    description = "Lets a double tap on a video do nothing, open its comments, copy the link, save the sound or the frame on screen, speed the video up, or set a sleep timer instead of liking it, so you don't like videos by accident. Starts off. Pick an action in Metra settings > Interface.",
) {
    category("Interaction")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch)
    execute {
        doubleTapRouteMethods.forEach { (owner, name) ->
            doubleTapRoute(owner, name).method.apply {
                requireLocals("Double tap", 1)
                // The tap's event, the last register as the only parameter: it tells the extension
                // a tap a second guard sees again from a new one.
                val event = implementation!!.registerCount - 1
                addInstructionsWithLabels(0, """
                    invoke-static/range { v$event .. v$event }, $EXTENSION->onDoubleTap(Landroid/view/MotionEvent;)Z
                    move-result v0
                    if-eqz v0, :original
                    return-void
                """, ExternalLabel("original", getInstruction(0)))
            }
        }
        CommentViewFingerprint.method.addInstruction(0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION->registerCommentView(Ljava/lang/Object;Landroid/view/View;)V")
        CommentBindFingerprint.method.addInstruction(0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION->bindCommentView(Ljava/lang/Object;Ljava/lang/Object;)V")
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDoubleTap()V")
    }
}
