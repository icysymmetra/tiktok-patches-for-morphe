/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import androidx.annotation.Nullable;

import app.morphe.extension.tiktok.settings.L10n;

import java.nio.charset.StandardCharsets;

/** Storage limits for lists evaluated on every feed batch. */
public final class FeedRuleLimits {
    public static final int MAX_UTF8_BYTES = 256 * 1024;
    public static final int MAX_ENTRIES = 10_000;

    public enum Violation {
        UTF8_BYTES,
        ENTRIES
    }

    private FeedRuleLimits() {}

    @Nullable
    public static Violation captionViolation(String value) {
        return violation(value, true);
    }

    @Nullable
    public static Violation creatorViolation(String value) {
        return violation(value, false);
    }

    @Nullable
    public static String captionProblem(String value) {
        return message(captionViolation(value));
    }

    @Nullable
    public static String creatorProblem(String value) {
        return message(creatorViolation(value));
    }

    public static String requireCaption(String value) {
        String problem = captionProblem(value);
        if (problem != null) throw new IllegalArgumentException(problem);
        return value;
    }

    public static String requireCreators(String value) {
        String problem = creatorProblem(value);
        if (problem != null) throw new IllegalArgumentException(problem);
        return value;
    }

    @Nullable
    public static String message(@Nullable Violation violation) {
        if (violation == null) return null;
        // The limits are formatted in from the constants above, so the message and the check
        // cannot say different numbers.
        return violation == Violation.UTF8_BYTES
                ? L10n.f("That list is too large. Keep it to %1$s KB or less.",
                        java.text.NumberFormat.getInstance().format(MAX_UTF8_BYTES / 1024))
                : L10n.f("That list has too many entries. Keep it to %1$s or fewer.",
                        java.text.NumberFormat.getInstance().format(MAX_ENTRIES));
    }

    @Nullable
    private static Violation violation(String value, boolean captions) {
        String text = value == null ? "" : value;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return Violation.UTF8_BYTES;
        }
        int entries;
        if (captions) {
            entries = KeywordRules.split(text).size();
        } else {
            entries = 0;
            for (String entry : AdvancedFeedRules.rawTerms(text)) {
                if (!entry.trim().isEmpty()) entries++;
            }
        }
        return entries > MAX_ENTRIES ? Violation.ENTRIES : null;
    }
}
