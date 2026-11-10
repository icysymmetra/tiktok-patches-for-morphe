/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe/commit/0e4a6e1d
 */
package app.morphe.patches.tiktok.misc.voicecomments

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.returnBooleanWhenOn
import app.morphe.util.addInstruction

internal const val VOICE_COMMENTS_SWITCH = "Lapp/morphe/extension/tiktok/comment/VoiceComments;->isOn()Z"

/**
 * The gate answers yes only while the switch is on, which starts off, so TikTok reads its own
 * value until the reader asks for voice comments. Nobody here has seen what the entry points do
 * on a real account: whether a voice comment records, publishes and plays back is a device check.
 */
@Suppress("unused")
val enableVoiceCommentsPatch = bytecodePatch(
    name = "Enable voice comments",
    description = "Lets you turn on TikTok's voice comments, so you can record and post a " +
        "spoken comment, for accounts that don't have them yet. It hasn't been tried on a real " +
        "account, so it may not work. Starts off. Turn it on in Metra settings > Comments and translation.",
) {
    category("Comments")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installVoiceCommentSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableVoiceComments()V",
        )
    }
}

/** The patch's hook, without its settings row, so a test can put it on a build with no extension. */
context(patchContext: BytecodePatchContext)
internal fun installVoiceCommentSwitch() {
    patchContext.resolveVoiceCommentPublishGate()
        .returnBooleanWhenOn("Enable voice comments", VOICE_COMMENTS_SWITCH, true)
}
