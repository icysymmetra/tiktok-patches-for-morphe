/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class CommentCopySanitizer {
    private static final String FAMILY = "comment copy";

    private CommentCopySanitizer() {}

    /**
     * The name prefix TikTok's comment menu puts before the copied text. Since 46.9.3 the menu
     * builds its ClipData through a helper that takes the prefix, the text and the text's emoji
     * spans apart, so the older hook on the joined string never saw the main comment sheet
     * (issue #28). Blank with the switch on; the text and its emoji encoding are untouched.
     */
    public static String copiedPrefix(String prefix) {
        if (!Settings.COPY_COMMENTS_WITHOUT_USERNAME.get()) return prefix;
        HookStatus.bound(FAMILY, "menu prefix blanked");
        return "";
    }

    public static String sanitizeCopiedCommentText(String copiedText, String commentText) {
        if (!Settings.COPY_COMMENTS_WITHOUT_USERNAME.get()) return copiedText;
        if (commentText == null) return copiedText;

        if (Settings.DEBUG.get()) {
            Logger.printInfo(() -> "[Morphe TikTok CommentCopy] Removed copied comment username prefix");
        }
        return commentText;
    }
}
