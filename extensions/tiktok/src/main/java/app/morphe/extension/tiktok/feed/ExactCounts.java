/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import java.text.NumberFormat;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Show exact counts. TikTok's count formatters cut anything from 10,000 up to 12.3K, 1.2M or
 * 1.2B. Each one asks here first, and with the switch on gets the whole number back with the
 * phone's digit grouping, so 1,234,567 rather than 1.2M. Counts under 10,000 already come back
 * whole, and a negative count stays TikTok's to turn into 0.
 */
public final class ExactCounts {
    private ExactCounts() {
    }

    /** Called first in each count formatter. Null leaves the count to TikTok. */
    public static String format(long count) {
        if (count < 0 || !Settings.SHOW_EXACT_COUNTS.get()) return null;
        // A new instance per call: NumberFormat isn't thread safe, and the view models format
        // counts off the main thread.
        return NumberFormat.getIntegerInstance().format(count);
    }
}
