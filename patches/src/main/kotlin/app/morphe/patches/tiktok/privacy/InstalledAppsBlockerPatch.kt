/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/InstalledAppsBlocker;"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"

@Suppress("unused")
val installedAppsBlockerPatch = bytecodePatch(
    name = "Block installed app scanning",
    description = "Gives TikTok an empty list when it asks which apps are on your phone, so " +
        "it learns less about you. Opening another app from TikTok still works. Starts off. Turn " +
        "it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableInstalledAppsBlocker()V",
        )

        // TikTok 46.2.3 never calls getInstalledPackages or getInstalledApplications. The app
        // inventory is read the modern way, by asking for every launchable activity, so that is
        // the call that matters; the two list calls stay for a build that uses them.
        val replacements = mapOf(
            "$PACKAGE_MANAGER->getInstalledPackages(I)Ljava/util/List;" to
                "$EXTENSION->interceptGetInstalledPackages(${PACKAGE_MANAGER}I)Ljava/util/List;",
            "$PACKAGE_MANAGER->getInstalledApplications(I)Ljava/util/List;" to
                "$EXTENSION->interceptGetInstalledApplications(${PACKAGE_MANAGER}I)Ljava/util/List;",
            "$PACKAGE_MANAGER->queryIntentActivities(Landroid/content/Intent;I)Ljava/util/List;" to
                "$EXTENSION->interceptQueryIntentActivities(${PACKAGE_MANAGER}Landroid/content/Intent;I)Ljava/util/List;",
        )
        val sites = invokeSitesOf(replacements.keys)
        if (sites.isEmpty()) {
            throw PatchException("Block installed app scanning: no package query call site was found.")
        }
        replaceSites(sites, replacements)
        println("[Installed apps blocker] Intercepted ${sites.size} package query sites.")
    }
}
