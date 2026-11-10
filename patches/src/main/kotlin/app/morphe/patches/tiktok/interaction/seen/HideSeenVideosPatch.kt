/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows BlueDragon4251/tiktok-patches-for-morphe.
 */
package app.morphe.patches.tiktok.interaction.seen

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.feedfilter.feedFilterPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val HISTORY_DESCRIPTOR = "Lapp/morphe/extension/tiktok/seen/SeenVideoHistory;"

/** The player's progress callback, which carries the video id, position and duration. */
private object PlayerProgressFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
    custom = { method, classDef ->
        method.name == "onPlayProgressChange" && classDef.endsWith("/PlayerController;")
    },
)

/**
 * Records what has been watched and drops it from later feed pages.
 *
 * Only the recording side needs bytecode. The filtering rides on the feed filter's own
 * list, so every feed path that patch already covers gets it for free, instead of a second
 * pass over the same responses.
 */
@Suppress("unused")
val hideSeenVideosPatch = bytecodePatch(
    name = "Hide already seen videos",
    description = "Remembers the videos you've watched and hides them when the feed sends " +
        "them again, so you only see new ones. The list stays on your phone. Starts off. Turn it " +
        "on in Metra settings > Feed controls.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch, feedFilterPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSeenVideoFilter()V",
        )

        // p1 is the video id, p2 and p4 the wide position and duration.
        PlayerProgressFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p1 .. p5}, " +
                "$HISTORY_DESCRIPTOR->onPlayProgressChange(Ljava/lang/String;JJ)V",
        )
    }
}
