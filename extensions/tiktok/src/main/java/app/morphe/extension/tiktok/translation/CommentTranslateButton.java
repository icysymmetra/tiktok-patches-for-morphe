/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * TikTok's translate button in the comment header (#85), the icon beside the comment count that
 * translates every loaded comment on one tap and shows the originals on the next.
 *
 * <p>The button, the toggle behind it and the translating are all TikTok's own. The patch answers
 * yes to the two gates TikTok builds the button behind while {@link #isOn()} says so; with the
 * switch off, and while Hushfeed is paused, TikTok reads its own values.
 *
 * <p>The toggle is TikTok's stored state, and it outlives the switch. Left on, it would keep TikTok
 * translating comments with the button gone and nothing on screen to turn it off. So the toggle's
 * writes are reported here first, and a write the header's button makes while the switch is on
 * marks the toggle as Hushfeed's to answer for. Its reads come through {@link #toggleOn(boolean)},
 * which answers off for that toggle once the switch is off or Hushfeed is paused. Any write TikTok
 * makes on its own (its server sync, its offer after a few taps of See translation, or the button
 * on an account TikTok built it for) clears the mark, and the toggle is TikTok's again.
 */
@SuppressWarnings("unused")
public final class CommentTranslateButton {
    /** {@link Settings#COMMENT_TRANSLATE_BUTTON}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "comment_translate_button";

    private CommentTranslateButton() {
    }

    /** Whether the header builds TikTok's translate button. */
    public static boolean isOn() {
        return Utils.getContext() != null ? Settings.COMMENT_TRANSLATE_BUTTON.get() : EarlySwitch.isOn(SWITCH_KEY);
    }

    /**
     * Called first thing when TikTok is about to store the toggle.
     *
     * @param value  what the toggle is being set to; null erases it.
     * @param source where the change comes from. The header's buttons pass none; TikTok's own
     *               changes pass one.
     */
    public static void onToggleWritten(@Nullable Boolean value, @Nullable Object source) {
        if (Utils.getContext() == null) return;
        try {
            boolean ours = source == null && Boolean.TRUE.equals(value) && isOn();
            if (Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get() != ours) {
                Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.save(ours);
            }
        } catch (RuntimeException problem) {
            Logger.printException(() -> "Comment translate button: could not note the toggle", problem);
        }
    }

    /**
     * The toggle as TikTok's code sees it. TikTok's own answer, except that a toggle Hushfeed's
     * button turned on reads off while the switch is off or Hushfeed is paused.
     */
    public static boolean toggleOn(boolean tiktoks) {
        if (!tiktoks || Utils.getContext() == null) return tiktoks;
        try {
            if (isOn()) return true;
            return !Settings.COMMENT_TRANSLATE_BUTTON_TURNED_ON.get();
        } catch (RuntimeException problem) {
            Logger.printException(() -> "Comment translate button: could not read the toggle", problem);
            return tiktoks;
        }
    }
}
