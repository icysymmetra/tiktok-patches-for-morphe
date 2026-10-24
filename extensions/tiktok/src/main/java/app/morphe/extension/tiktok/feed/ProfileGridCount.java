/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.tiktok.download.SavedVideoMark;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;

/**
 * What a profile grid cell shows for its view count. The grid adapter's bind and the cell holder's
 * own bind both ask here right after TikTok formats the count, and Show engagement rate,
 * Always show publish date and Advanced downloads each add their part while their switch is on:
 * the rate after the count ("12.3K · 4.2%"), the date and the video's length on a line of their
 * own under it ("Oct 7 · 0:42"), and a check mark before the count for a video saved here. The
 * count's view takes the cell's width less its icons and wraps in a 50dp bar, so the date and
 * length get their own line rather than pushing the count and the rate over it.
 */
public final class ProfileGridCount {
    private static final String SEPARATOR = " · ";

    private ProfileGridCount() {
    }

    public static String text(String count, Object item) {
        if (count == null) {
            return null;
        }
        String text = SavedVideoMark.gridText(EngagementRate.gridCount(count, item), item);
        String date = AlwaysShowPublishDatePatch.gridDate(item);
        String length = GridVideoLength.gridLength(item);
        String under = date == null ? length : length == null ? date : date + SEPARATOR + length;
        return under == null ? text : text + "\n" + under;
    }
}
