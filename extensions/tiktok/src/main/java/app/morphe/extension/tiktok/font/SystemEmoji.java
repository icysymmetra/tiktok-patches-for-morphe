/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Leaves every emoji to the device's own emoji font.
 *
 * <p>TikTok draws an emoji as text. None of its bundled fonts (TikTok Sans and the TikTok Text and
 * Display families) carries an emoji glyph, and the font engine builds TikTok Sans with the system
 * fallback chain behind it, so an emoji in a caption or a comment falls through to the device's
 * emoji font whether Use system font is on or off. TikTok's own emoji sets (the small emoji and
 * stickers its panels download) are pictures with no Unicode counterpart and aren't text at all.
 *
 * <p>The exception is androidx EmojiCompat. TikTok starts it at boot (its EmojiTask) with the
 * library's default config, which asks the system font provider for a downloadable emoji font:
 * Noto Color Emoji from Google Play services on most phones. AppCompat text views, LIVE's text
 * view and the few places TikTok asks it directly send their text through EmojiCompat, which looks
 * each emoji up with {@code Paint.hasGlyph} and, when the device's font can't draw it, wraps it in
 * a span drawn with Google's font. Replace-all is off, so an emoji the device can draw is already
 * the device's; what changes with this switch is the newer ones the device's font doesn't have.
 *
 * <p>{@link #leaveToDevice} runs first in that glyph check. R8 folded emoji2's default glyph
 * checker into its processor, so the check is the processor's method that calls
 * {@code Paint.hasGlyph}, and the processor wraps an emoji only when it answers false
 * (SystemEmojiAnchorsTest holds both to each declared build). While the switch is on this answers
 * true and EmojiCompat adds no span, so the device's font draws every emoji, a missing one the way
 * it would in an app without EmojiCompat. Otherwise it answers false and TikTok's own check runs.
 * That check remembers its answer per emoji, which this one doesn't, so it's asked every time.
 *
 * <p>A span already made keeps Google's drawing until its text is set again, which is why the
 * switch asks for a restart.
 */
public final class SystemEmoji {
    private static final String HOOK_FAMILY = "system emoji";

    private SystemEmoji() {}

    /**
     * Whether EmojiCompat should take the device's font as having the emoji it's checking. True
     * only while the switch is on; false, so TikTok's own check runs, while it's off, while
     * Hushfeed is paused or on any failure. Called first thing in EmojiCompat's glyph check, from
     * whichever thread lays out the text.
     */
    public static boolean leaveToDevice() {
        try {
            boolean on = Settings.SYSTEM_EMOJI.get();
            // Reported whichever way the switch is set, so an export separates "patch not
            // present" from "present and off" from "present and leaving emoji to the device".
            HookStatus.bound(HOOK_FAMILY, on ? "device" : "emojicompat");
            return on;
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "leaveToDevice", failure);
            return false;
        }
    }
}
