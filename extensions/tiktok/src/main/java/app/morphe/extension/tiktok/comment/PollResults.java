/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Shows a comment poll's results before the reader votes.
 *
 * <p>A creator's poll arrives with the comment page, counts and all, whether or not the reader
 * has voted: on the S22 with 47.0.3 a poll the test account had not voted on came with every
 * option's count and the total (its own vote read -1). TikTok's poll row draws the bars and the
 * percentages only once the poll has ended or the reader has voted, and one method on the row
 * answers that for every part that draws. The patch asks here first. The row wires its answers
 * for a tap either way, so a tap still casts a vote, and until one is cast no answer is marked
 * as the reader's.
 */
public final class PollResults {
    static final String FAMILY = "comment poll results";

    private PollResults() {
    }

    /** Whether the poll row draws its results whatever the reader has done. */
    public static boolean showBeforeVoting() {
        HookStatus.bound(FAMILY, "result check");
        if (!Settings.SHOW_POLL_RESULTS.get()) return false;
        HookStatus.bound(FAMILY, "shown before voting");
        return true;
    }
}
