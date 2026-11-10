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

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/ContactListBlocker;"
private const val RESOLVER = "Landroid/content/ContentResolver;"

@Suppress("unused")
val contactListBlockerPatch = bytecodePatch(
    name = "Block contact list access",
    description = "Gives TikTok an empty contact list when it tries to read your phone's contacts, so it can't use them to suggest friends. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableContactListBlocker()V",
        )

        // Every ContentResolver.query overload TikTok reaches. The five-parameter form needs six
        // registers, so it is always the /range form, which the first version of this patch
        // never matched: it applied, intercepted nothing, and said it blocked contacts.
        val replacements = mapOf(
            "$RESOLVER->query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;" to
                "$EXTENSION->interceptQuery($RESOLVER" +
                "Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
            "$RESOLVER->query(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;" to
                "$EXTENSION->interceptQuery($RESOLVER" +
                "Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
            "$RESOLVER->query(Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;" to
                "$EXTENSION->interceptQuery($RESOLVER" +
                "Landroid/net/Uri;[Ljava/lang/String;Landroid/os/Bundle;Landroid/os/CancellationSignal;)Landroid/database/Cursor;",
        )
        val sites = invokeSitesOf(replacements.keys)
        if (sites.isEmpty()) {
            throw PatchException("Block contact list access: no ContentResolver.query call site was found.")
        }
        replaceSites(sites, replacements)
        println("[Contact list blocker] Intercepted ${sites.size} ContentResolver.query sites.")
    }
}
