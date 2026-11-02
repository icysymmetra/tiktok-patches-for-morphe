/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import java.util.Locale;

/**
 * Counts written the way TikTok writes them.
 *
 * <p>The feed says 1.5M under a video, and someone filtering on it had to type seven digits and
 * get them right. Both forms are read here, and only the plain number is ever stored, so nothing
 * downstream has to know this exists.
 *
 * <p>The multipliers are the ones the app itself uses: a thousand, a million, a thousand million.
 * Nothing is rounded on the way in, so 1.5M is exactly 1500000 and 1.234M is exactly 1234000; a
 * value with more precision than its suffix can carry is refused rather than quietly truncated.
 */
public final class CompactCount {
    private static final long THOUSAND = 1_000L;
    private static final long MILLION = 1_000_000L;
    private static final long BILLION = 1_000_000_000L;

    private CompactCount() {
    }

    /**
     * The number {@code text} means, or -1 when it does not mean one.
     *
     * <p>-1 rather than an exception because every caller here has something to do with a value
     * it cannot read, and none of them want a stack trace for a typo.
     */
    public static long parse(String text) {
        if (text == null) return -1;
        String value = text.trim().replace(",", "").replace(" ", "");
        if (value.isEmpty()) return -1;

        long multiplier = 1;
        char last = value.charAt(value.length() - 1);
        if (last == 'k' || last == 'K') multiplier = THOUSAND;
        else if (last == 'm' || last == 'M') multiplier = MILLION;
        else if (last == 'b' || last == 'B') multiplier = BILLION;
        if (multiplier != 1) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) return -1;

        int dot = value.indexOf('.');
        String whole = dot < 0 ? value : value.substring(0, dot);
        String fraction = dot < 0 ? "" : value.substring(dot + 1);
        if (fraction.indexOf('.') >= 0) return -1;
        if (whole.isEmpty() && fraction.isEmpty()) return -1;
        if (!digitsOnly(whole) || !digitsOnly(fraction)) return -1;
        // A plain number with a decimal point is not a count of anything.
        if (dot >= 0 && multiplier == 1) return -1;

        try {
            long result = whole.isEmpty() ? 0 : Math.multiplyExact(Long.parseLong(whole), multiplier);
            for (int place = 0; place < fraction.length(); place++) {
                long step = multiplier / power(10, place + 1);
                // 1.2345M would need a tenth of a unit; a number cannot be a fraction of one.
                if (step == 0) return -1;
                result = Math.addExact(result, (fraction.charAt(place) - '0') * step);
            }
            return result < 0 ? -1 : result;
        } catch (ArithmeticException | NumberFormatException overflow) {
            return -1;
        }
    }

    /** How the same number reads back, so a field shows what was typed rather than the digits. */
    public static String format(long value) {
        if (value < 0) return "";
        if (value >= BILLION && value % (BILLION / 10) == 0) return trim(value, BILLION, "B");
        if (value >= MILLION && value % (MILLION / 10) == 0) return trim(value, MILLION, "M");
        if (value >= THOUSAND && value % (THOUSAND / 10) == 0) return trim(value, THOUSAND, "K");
        return Long.toString(value);
    }

    private static String trim(long value, long unit, String suffix) {
        long whole = value / unit;
        long tenths = (value % unit) / (unit / 10);
        return tenths == 0
                ? whole + suffix
                : String.format(Locale.ROOT, "%d.%d", whole, tenths) + suffix;
    }

    private static boolean digitsOnly(String text) {
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    private static long power(int base, int exponent) {
        long result = 1;
        for (int step = 0; step < exponent; step++) result *= base;
        return result;
    }
}
