/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.feedfilter.adskip

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerPlayFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.VideoAuthorInfoParamsFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.awemeParameterRegister
import app.morphe.patches.tiktok.interaction.blockauthor.registerOfParameter
import app.morphe.patches.tiktok.interaction.blockauthor.sessionPlaybackBridgePatch
import app.morphe.patches.tiktok.interaction.sharesheet.addOwnedShareAction
import app.morphe.patches.tiktok.interaction.sharesheet.ownedShareActionsPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction

private const val AD_SKIP = "Lapp/morphe/extension/tiktok/adskip/AdSkip;"
private const val SHARE_MARK_AD = "Lapp/morphe/extension/tiktok/share/ShareMarkAd;"
private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"

/** PlayerController's progress callback: p1 the video id, p2 and p4 the wide position and length. */
private object AdSkipProgressFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
    custom = { method, classDef ->
        method.name == "onPlayProgressChange" && classDef.endsWith("/PlayerController;")
    },
)

@Suppress("unused")
val adSkipPatch = bytecodePatch(
    name = "Skip ads by sound",
    description = "Lets you mark an ad in any video from its share sheet, then skips that ad by " +
        "its sound wherever it plays again, even under music or noise, the way Shazam finds a " +
        "song. Ads are fingerprinted and matched on your phone, and marks can be shared with other " +
        "users through an optional server. Starts off. Turn it on in Metra settings > Feed controls.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch, sessionPlaybackBridgePatch, ownedShareActionsPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // The video a player is about to play; p0 the controller, the Aweme beside it.
        PlayerPlayFingerprint.method.apply {
            val aweme = awemeParameterRegister()
            if (aweme != 1) throw PatchException("Skip ads by sound: the play method's Aweme is p$aweme, not p1.")
            addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $AD_SKIP->onPlay(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }

        // Each tick of the playing video, where a found ad is skipped.
        AdSkipProgressFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p5 }, $AD_SKIP->onProgress(Ljava/lang/Object;Ljava/lang/String;JJ)V",
        )

        // The posts the feed binds either side of the current one, to scan them ahead.
        VideoAuthorInfoParamsFingerprint.method.apply {
            val params = registerOfParameter(VIDEO_ITEM_PARAMS)
                ?: throw PatchException("Skip ads by sound: no VideoItemParams parameter on the bind.")
            addInstruction(
                0,
                "invoke-static/range { $params .. $params }, $AD_SKIP->onBind(Ljava/lang/Object;)V",
            )
        }

        addOwnedShareAction(SHARE_MARK_AD)

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAdSkip()V",
        )
    }
}
