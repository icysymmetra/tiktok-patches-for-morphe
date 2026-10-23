/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.haptics

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.patches.tiktok.privacy.replaceSites
import app.morphe.util.addInstruction

private const val HAPTICS = "Lapp/morphe/extension/tiktok/interaction/Haptics;"
private const val VIEW = "Landroid/view/View;"
private const val VIBRATOR = "Landroid/os/Vibrator;"
private const val EFFECT = "Landroid/os/VibrationEffect;"
private const val AUDIO_ATTRIBUTES = "Landroid/media/AudioAttributes;"
private const val LONG_CLICK = "Landroid/view/View\$OnLongClickListener;"

/*
 * TikTok 47.1.4 names View or Vibrator itself in every one of these calls, never a subclass:
 * 54 performHapticFeedback(int) and 6 performHapticFeedback(int, int), 56 timed vibrate(long), 25
 * vibrate(VibrationEffect) and 3 vibrate(VibrationEffect, AudioAttributes). Each becomes a static
 * call of the extension on the same registers, so a move-result after it reads the extension's
 * answer. The 4 patterned vibrate(long[], int) calls stay: that's an alert's shape, not a tap's.
 */
internal val VIEW_HAPTICS = mapOf(
    "$VIEW->performHapticFeedback(I)Z" to "$HAPTICS->performHapticFeedback(${VIEW}I)Z",
    "$VIEW->performHapticFeedback(II)Z" to "$HAPTICS->performHapticFeedback(${VIEW}II)Z",
)

internal val VIBRATIONS = mapOf(
    "$VIBRATOR->vibrate(J)V" to "$HAPTICS->vibrate(${VIBRATOR}J)V",
    "$VIBRATOR->vibrate($EFFECT)V" to "$HAPTICS->vibrate($VIBRATOR$EFFECT)V",
    "$VIBRATOR->vibrate($EFFECT$AUDIO_ATTRIBUTES)V" to "$HAPTICS->vibrate($VIBRATOR$EFFECT$AUDIO_ATTRIBUTES)V",
)

/*
 * Android plays the long press buzz itself once a long click listener answers true, so the
 * listener is wrapped where TikTok sets it. 423 of the 428 calls on 47.1.4 name View itself; the
 * other five name an obfuscated class and stay as they are.
 */
internal val LONG_CLICKS = mapOf(
    "$VIEW->setOnLongClickListener($LONG_CLICK)V" to "$HAPTICS->setOnLongClickListener($VIEW$LONG_CLICK)V",
)

/**
 * Holds back the vibrations TikTok plays on its own taps and gestures. See the extension's
 * Haptics for when.
 *
 * In the default selection with its switch off: TikTok's haptics are a matter of taste, not
 * something it does to you, so nothing changes until the user asks.
 */
@Suppress("unused")
val turnOffHapticsPatch = bytecodePatch(
    name = "Turn off haptics",
    description = "Stops the little vibrations TikTok makes when you tap or hold things. Your keyboard and your phone's own vibrations stay. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableTurnOffHaptics()V",
        )
        val replacements = VIEW_HAPTICS + VIBRATIONS + LONG_CLICKS
        val sites = invokeSitesOf(replacements.keys)
        if (sites.none { it.target in VIEW_HAPTICS }) {
            throw PatchException("Turn off haptics: found no View haptic call outside the extension.")
        }
        if (sites.none { it.target in VIBRATIONS }) {
            throw PatchException("Turn off haptics: found no vibrator call outside the extension.")
        }
        if (sites.none { it.target in LONG_CLICKS }) {
            throw PatchException("Turn off haptics: found no long click listener outside the extension.")
        }
        replaceSites(sites, replacements)
    }
}
