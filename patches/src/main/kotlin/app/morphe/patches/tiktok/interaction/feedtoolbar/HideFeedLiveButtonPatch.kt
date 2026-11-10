/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import com.android.tools.smali.dexlib2.iface.Method

private const val LIVE_ICON_GENERATOR_DESCRIPTOR =
    "Lcom/bytedance/tiktok/homepage/mainfragment/toolbar/LiveIconGenerator;"

private const val SIDEBAR_ICON_SOURCE_DESCRIPTOR =
    "Lcom/ss/android/ugc/aweme/homepage/api/ui/HomePageUIFrameService;"

private object LiveIconEnabledFingerprint : Fingerprint(
    definingClass = LIVE_ICON_GENERATOR_DESCRIPTOR,
    name = "enabled",
    returnType = "Z",
    parameters = emptyList(),
)

/**
 * The view method of the feed toolbar's side menu button (#128), TikTok's own button beside
 * LIVE at the top left that opens the drawer with Your orders, TikTok Minis and the rest. It's
 * one of the toolbar's icon generators, like LiveIconGenerator, but obfuscated (`16o0` on
 * 47.1.4, tagged `SIDEBAR`), so it's found by the one method that asks HomePageUIFrameService
 * for the inflated sidebar icon. That icon is built in code with no view id, so the overlay
 * hider has nothing to look it up by.
 */
internal object SidebarIconViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(
            definingClass = SIDEBAR_ICON_SOURCE_DESCRIPTOR,
            name = "getInflatedSidebarIcon",
            returnType = "Landroid/view/View;",
        ),
    ),
)

/**
 * A toolbar icon generator's own yes or no. The toolbar asks it before it builds the icon's
 * view and leaves the icon out on a no, the same question LIVE and search answer.
 */
internal fun isToolbarEnabledCheck(method: Method): Boolean =
    method.name == "enabled" && method.parameterTypes.isEmpty() && method.returnType == "Z"

@Suppress("unused")
val hideFeedLiveButtonPatch = bytecodePatch(
    name = "Hide feed LIVE button",
    description = "Removes the LIVE button and the side menu button from the top left of the " +
        "feed, for a cleaner screen. Each has its own switch, and both start off. Turn them on " +
        "in Metra settings > Interface.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedLiveButton()V",
        )
        LiveIconEnabledFingerprint.method.overrideToolbarButtonEnabled(
            "hideFeedLiveButtonEnabled",
        )

        hookSidebarButtonOn(packageMetadata.versionName)
    }
}

/**
 * The side menu button (#128), and whether it went in. Required on a declared build, where the
 * anchors test holds it, and left out with a note on any other, so a reworked toolbar there
 * doesn't take the LIVE switch down with it. Its settings row shows only when the hook went in.
 * Takes the version so a test can stand in for a build that isn't declared.
 */
internal fun BytecodePatchContext.hookSidebarButtonOn(versionName: String): Boolean {
    try {
        hookSidebarButton()
    } catch (problem: Exception) {
        if (versionName in declaredVersions()) throw problem
        println("[Hide feed LIVE button] Left out the side menu button on $versionName: ${problem.message}")
        return false
    }
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, " +
            "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedSidebarButton()V",
    )
    return true
}

private fun BytecodePatchContext.hookSidebarButton() {
    val sidebarGenerators = SidebarIconViewFingerprint.matchAllOrNull().orEmpty()
    if (sidebarGenerators.size != 1) {
        throw PatchException(
            "Hide feed LIVE button: expected one side menu button, found ${sidebarGenerators.size}.",
        )
    }
    val sidebarEnabled = sidebarGenerators.single().classDef.methods.filter(::isToolbarEnabledCheck)
    if (sidebarEnabled.size != 1) {
        throw PatchException(
            "Hide feed LIVE button: expected one side menu enabled check, found ${sidebarEnabled.size}.",
        )
    }
    sidebarEnabled.single().overrideToolbarButtonEnabled("hideFeedSidebarButtonEnabled")
}
