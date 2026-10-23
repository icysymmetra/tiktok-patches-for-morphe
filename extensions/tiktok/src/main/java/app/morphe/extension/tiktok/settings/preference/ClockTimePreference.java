/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.TimePickerDialog;
import android.content.Context;
import android.text.format.DateFormat;

import java.util.Calendar;

import app.morphe.extension.shared.settings.IntegerSetting;

/**
 * A time of day, stored as minutes after midnight and picked on the phone's own clock face
 * rather than typed. The stored text stays the plain number, so the settings screen reads and
 * restores it the way it does every other number row.
 */
@SuppressWarnings("deprecation")
public class ClockTimePreference extends NumberInputPreference {
    public ClockTimePreference(Context context, String title, String summary, IntegerSetting setting) {
        super(context, title, summary, setting, "", "");
    }

    /** In the phone's own format: "8:30 PM" or "20:30". */
    @Override
    protected String displayValue(int value) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, value / 60);
        calendar.set(Calendar.MINUTE, value % 60);
        return DateFormat.getTimeFormat(getContext()).format(calendar.getTime());
    }

    /** The picker only offers times of day, so "12:00 AM to 11:59 PM" told the reader nothing. */
    @Override
    protected boolean showsRange() {
        return false;
    }

    /**
     * The picker took its look from TikTok's activity theme, which can disagree with the dark or
     * light mode the settings pages follow, so it could open as a light clock over a dark page.
     */
    @Override
    protected void onClick() {
        int value = clamp(Integer.parseInt(getValue()));
        int theme = SettingsUi.isDarkMode()
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
        new TimePickerDialog(getContext(), theme, (picker, hour, minute) -> {
            String picked = String.valueOf(hour * 60 + minute);
            if (callChangeListener(picked)) setValue(picked);
        }, value / 60, value % 60, DateFormat.is24HourFormat(getContext())).show();
    }
}
