/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/StoreIdentity;"
private const val INSTALL_SOURCE_EXTENSION = "Lapp/morphe/extension/tiktok/privacy/StoreInstallSource;"
private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
private const val INSTALL_SOURCE_INFO = "Landroid/content/pm/InstallSourceInfo;"
private const val GET_INSTALLER =
    "$PACKAGE_MANAGER->getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;"

/**
 * The Android 11 install source reads, each to the extension method that stands in for it. TikTok
 * asks for the install source first and reads these three off it; nothing else on it is read.
 */
internal val INSTALL_SOURCE_READS = mapOf(
    "$PACKAGE_MANAGER->getInstallSourceInfo(Ljava/lang/String;)$INSTALL_SOURCE_INFO" to
        "$INSTALL_SOURCE_EXTENSION->sourceFor(${PACKAGE_MANAGER}Ljava/lang/String;)$INSTALL_SOURCE_INFO",
    "$INSTALL_SOURCE_INFO->getInstallingPackageName()Ljava/lang/String;" to
        "$INSTALL_SOURCE_EXTENSION->installingOf($INSTALL_SOURCE_INFO)Ljava/lang/String;",
    "$INSTALL_SOURCE_INFO->getInitiatingPackageName()Ljava/lang/String;" to
        "$INSTALL_SOURCE_EXTENSION->initiatingOf($INSTALL_SOURCE_INFO)Ljava/lang/String;",
    "$INSTALL_SOURCE_INFO->getOriginatingPackageName()Ljava/lang/String;" to
        "$INSTALL_SOURCE_EXTENSION->originatingOf($INSTALL_SOURCE_INFO)Ljava/lang/String;",
)

@Suppress("unused")
val storeIdentityPatch = bytecodePatch(
    name = "Look like the store app",
    description = "Makes the patched app answer TikTok's checks as if it came from the Play " +
        "Store, which can help when follows or likes undo themselves. TikTok has other checks, so " +
        "it may not be enough. On once picked. Turn it off in Metra settings > App behavior.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableStoreIdentity()V",
        )

        // TikTok's own cached self-package lookup. When the extension answers with a package that
        // carries TikTok's certificate, return it; otherwise the wrapper runs as it always did.
        // The result lands in v0, a local the wrapper already has (it caches into v0 itself).
        val wrapper = SelfPackageInfoCacheFingerprint.method
        wrapper.requireLocals("Look like the store app", 1)
        wrapper.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1, p2 }, $EXTENSION->packageInfo(Landroid/content/pm/PackageManager;Ljava/lang/String;I)Landroid/content/pm/PackageInfo;
                move-result-object v0
                if-eqz v0, :run_wrapper
                return-object v0
                :run_wrapper
                nop
            """,
        )

        // Every getInstallerPackageName call: the extension answers the Play Store for TikTok's own
        // package and the real installer for anything else. Both invoke forms, any build with none.
        val sites = invokeSitesOf(setOf(GET_INSTALLER))
        replaceSites(
            sites,
            sites.associate {
                it.target to "$EXTENSION->installerFor(${PACKAGE_MANAGER}Ljava/lang/String;)Ljava/lang/String;"
            },
        )

        // From Android 11 TikTok reads the install source instead, and only falls back to the call
        // above. The install source itself comes back real and the three reads off it answer.
        val sourceSites = invokeSitesOf(INSTALL_SOURCE_READS.keys)
        replaceSites(sourceSites, sourceSites.associate { it.target to INSTALL_SOURCE_READS.getValue(it.target) })
    }
}
