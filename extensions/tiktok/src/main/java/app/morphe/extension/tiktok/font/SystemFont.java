/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import android.graphics.Typeface;
import android.os.Build;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Swaps TikTok's own text font for the device's system font (issue #7), or for a font file the
 * reader imported.
 *
 * <p>TikTok draws its interface text in a bundled family, TikTok Sans, built by one font engine
 * (two implementations of one interface, renamed on every build; SystemFontAnchorsTest holds
 * the patch's match to each declared build). Every text
 * typeface the app asks for is produced by that engine's two methods, the variable-font builder
 * and the static asset loader, and this stands at their exit. The engine handles only the
 * TikTok text and display fonts, so the icon glyph fonts, the gift combo font and the
 * mention/hashtag font, which load through other paths, are left alone. TikTok's static
 * typeface factory (the nine-parameter one that picks TikTok Display or TikTok Text by size)
 * and its Compose font loader both build through that engine, so they reach this too.
 *
 * <p>The replacement is returned at the same weight and slant the engine built, so bold stays
 * bold. The weight is read back from the typeface the engine produced rather than trusted from a
 * parameter, so a build that reshuffles the engine cannot feed a stale register in. An imported
 * font with one weight is made bolder by Android where TikTok asks for a heavier one.
 */
public final class SystemFont {
    private static final String HOOK_FAMILY = "system font";

    private SystemFont() {}

    /**
     * The replacement at {@code original}'s weight and slant while a switch asks for one,
     * otherwise {@code original} unchanged. Null passes through, which is the engine's own "no
     * typeface" answer.
     */
    public static Typeface systemize(Typeface original) {
        Typeface family = replacementFamily();
        // Reported whichever way the switches are set, so an export separates "patch not present"
        // from "present and off" from "present and swapping", and says which font it swaps in.
        HookStatus.bound(HOOK_FAMILY, family == null ? "tiktok" : family == Typeface.DEFAULT ? "system" : "imported");
        if (family == null || original == null) return original;
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                return Typeface.create(family, original.getWeight(), original.isItalic());
            }
            return Typeface.create(family, original.getStyle());
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "systemize", failure);
            return original;
        }
    }

    /**
     * The one place that decides what TikTok's text is drawn in: the family to restyle into, or
     * null to keep TikTok's own. An imported font wins while Use an imported font is on and the
     * file loads. With that switch off, or the file gone or unreadable, the answer is what Use
     * system font alone would give: the device font while it's on, TikTok's own while it's off.
     */
    static Typeface replacementFamily() {
        if (Settings.IMPORTED_FONT.get()) {
            Typeface imported = ImportedFont.typeface();
            if (imported != null) return imported;
        }
        return Settings.SYSTEM_FONT.get() ? Typeface.DEFAULT : null;
    }
}
