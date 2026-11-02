/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/share/ShareSheetTools;"

@Suppress("unused")
val shareSheetToolsPatch = bytecodePatch(
    name = "Share sheet tools",
    description = "Lets you hide people, apps and buttons you don't use from the share sheet, " +
        "add apps you do use, and copy a video's link with one press on Share. Starts off. " +
        "Turn it on in Metra settings > Share sheet.",
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch, ownedShareActionsPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        hookShareModel()
        hookShareTargets()
        hookFinishedChannelRow()
        hookShareModes()
        hookShareContactBinds()
        hookSharePanel()
        addOwnedShareAction(SHARE_BLOCK_AUTHOR)
        hookShareButtonTap()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableShareSheet()V",
        )

        // p0 is the activity. /range because a parameter register is usually above v15.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, " +
                "$EXTENSION_CLASS_DESCRIPTOR->install(Landroid/app/Activity;)V",
        )
    }
}
