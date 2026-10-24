/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.text.InputType;

import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.tiktok.settings.L10n;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An hour of the day rather than a count of things. "Current: 13 o'clock" is not how anyone says
 * it, and "Current: 0 o'clock" is not how anyone says anything.
 */
@SuppressWarnings("deprecation")
public class ClockHourPreference extends NumberInputPreference {
    /**
     * An hour with or without its ":00": 6, 06, 6:00, 06:00 and 0600 are all six o'clock. Any
     * script's digits, as the plain number parse took before: a time keyboard can type them.
     * {@code \p{Nd}} rather than a flag: Android's regex is ICU, which refuses
     * UNICODE_CHARACTER_CLASS where the JVM the tests run on takes it, and the settings screen
     * crashed opening this row.
     */
    private static final Pattern HOUR = Pattern.compile("(\\p{Nd}{1,2})(?::?(\\p{Nd}\\p{Nd}))?");

    public ClockHourPreference(Context context, String title, String summary, IntegerSetting setting) {
        super(context, title, summary, setting, "", "");
    }

    @Override
    protected String displayValue(int value) {
        return String.format(Locale.getDefault(), "%02d:00", value);
    }

    /**
     * The row shows "06:00", so "06:00" and "0600" have to be read as six. Digits alone were the
     * only thing it took, and "0600" was read as six hundred and pulled down to 23:00.
     */
    @Override
    protected Integer parseTyped(String typed) {
        if (typed == null) return null;
        Matcher hour = HOUR.matcher(typed.trim());
        if (!hour.matches()) return null;
        // An hour, not a time: the minutes, when there are any, have to be none.
        if (hour.group(2) != null && Integer.parseInt(hour.group(2)) != 0) return null;
        return Integer.valueOf(hour.group(1));
    }

    @Override
    protected String unreadableMessage() {
        return L10n.t(getContext(), "Enter an hour, like 6 or 06:00.");
    }

    /** A time keyboard, which has the colon a number keyboard leaves out. */
    @Override
    protected int inputType() {
        return InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME;
    }
}
