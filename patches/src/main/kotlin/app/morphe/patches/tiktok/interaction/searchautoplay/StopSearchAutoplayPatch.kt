/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.searchautoplay

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION = "Lapp/morphe/extension/tiktok/search/SearchAutoplay;"

/**
 * The search results list's autoplay check, which picks the visible result to play and starts
 * it. Renamed on every build (0J9I.LIZIZ on 47.0.3, 0JFz.LIZIZ on 47.1.3, 0JG3.LIZIZ on 47.1.4),
 * and the only method that carries its main-thread assertion message. It already returns
 * early while the list is hidden, so returning at entry is a state TikTok handles: nothing in
 * the results starts by itself.
 */
internal object SearchAutoplayCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z", "L"),
    strings = listOf("checkLogic() is not called on main thread").map { string(it, StringComparisonType.CONTAINS) },
)

@Suppress("unused")
val stopSearchAutoplayPatch = bytecodePatch(
    name = "Stop search autoplay",
    description = "Stops videos in search results from playing by themselves. Each one shows " +
        "its cover until you open it, so searching stays quiet. Starts off. Turn it on in " +
        "Metra settings > App behavior.",
) {
    category("Search")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val check = SearchAutoplayCheckFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSearchAutoplay()V",
        )
        check.guardAtEntry(
            "Stop search autoplay",
            "invoke-static {}, $EXTENSION->shouldSkip()Z",
            "return-void",
        )
    }
}
