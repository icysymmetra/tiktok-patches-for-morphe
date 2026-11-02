/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.authorregion

import app.morphe.util.addInstruction
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/feed/AuthorRegion;"

/**
 * Videos opened from a profile play in the detail page, and a process restored with a video open
 * can start it with no main feed behind it yet, so the hook in MainActivity.onCreate never runs
 * before it. A share link opens in MainActivity on 47.1.x.
 */
internal object DetailActivityOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/detail/ui/DetailActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

/**
 * Installs the creator's row decoration in both windows a video plays in. Show author region and
 * Show engagement rate share it, so a build with both installs the row once and each adds its
 * part. Depends on the block author patch only for its tracking of which video is on screen; what
 * the row shows is read from that Aweme, so it follows the player rather than the feed's prefetch.
 */
internal val authorRowPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch, blockAuthorPatch)

    execute {
        // p0 is the activity. /range because a parameter register is usually above v15.
        listOf(MainActivityOnCreateFingerprint, DetailActivityOnCreateFingerprint).forEach {
            it.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, " +
                    "$EXTENSION_CLASS_DESCRIPTOR->install(Landroid/app/Activity;)V",
            )
        }
    }
}

@Suppress("unused")
val showAuthorRegionPatch = bytecodePatch(
    name = "Show author region",
    description = "Shows the country a video was posted from next to the creator's name. " +
        "Starts off. Turn it on in Metra settings > Interface.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch, authorRowPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAuthorRegion()V",
        )
    }
}
