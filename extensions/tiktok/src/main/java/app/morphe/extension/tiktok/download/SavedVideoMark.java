/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * The check mark a profile grid cell puts before its view count when its video is one Hushfeed
 * saved here ("✓ 12.3K"), and a feed video's creator row before its time ("✓ 2d ago").
 *
 * <p>Saved means in {@link SavedVideoArchive}'s record, which Check for already-saved videos
 * keeps. That record only takes saves made while the check is on, so the mark needs the check
 * too: with it off a save leaves no trace, and a mark that covered only the saves from before
 * would pass for one that covers them all. It's the same record the check offers Open or Save
 * again from, so a video deleted since keeps its mark until the record is forgotten. Photo posts
 * saved here are in the record too, under their aweme id, so they're marked the same way.
 *
 * <p>The grid binds its cells on the main thread, many in a frame, so this never reads the
 * database: the archive answers from the ids it holds in memory. They're read off the main
 * thread soon after TikTok starts ({@link #warm}, from the start's sweep) or, failing that,
 * when the first cell asks, and a cell bound before they're in stays unmarked until it's bound
 * again.
 */
public final class SavedVideoMark {
    /** U+2713, a plain check mark every system font draws as text rather than as an emoji. */
    static final String MARK = "✓";
    /** A no-break space, so a count that wraps never leaves the mark on a line of its own. */
    private static final String GAP = " ";

    private SavedVideoMark() {
    }

    /**
     * Off unless Advanced downloads is in the bundle and both switches are on. Advanced downloads
     * goes first, so a process without it never touches the settings from here.
     */
    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.MARK_SAVED_VIDEOS.get()
                && Settings.CHECK_SAVED_VIDEOS.get();
    }

    /** Has the saved ids read ahead of the first grid, when the mark is on. Main thread. */
    static void warm() {
        try {
            if (enabled()) SavedVideoArchive.readIdsLater();
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not get the saved-video marks ready", ex);
        }
    }

    /**
     * What a profile grid cell shows for its view count: {@code text}, with the mark before it
     * while the switch is on and the cell's video is in the record.
     */
    public static String gridText(String text, Object item) {
        return marked(text, item);
    }

    /**
     * What a feed video's creator row shows for its time: {@code text}, with the mark before it
     * the same way. The row binds as the feed scrolls, so this answers from memory too.
     */
    public static String rowText(String text, Object item) {
        return marked(text, item);
    }

    private static String marked(String text, Object item) {
        if (text == null || !(item instanceof Aweme)) return text;
        try {
            if (!enabled()) return text;
            String id = ((Aweme) item).getAid();
            return SavedVideoArchive.isSaved(id) ? MARK + GAP + text : text;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not mark a saved video", ex);
            return text;
        }
    }
}
