/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Which settings differ from their defaults, for the diagnostic export.
 *
 * <p>#149's reporter pasted an export for a search bar and captions that had gone missing and was
 * sure no switch hid them, and nothing in the export could say either way: it counted what each
 * hook did and named the switches the patcher registered, never which of them were on. #110, #143
 * and #82 each waited on the same question. This lists every setting the reader changed, by key.
 *
 * <p>A switch is listed as on or off, and a number or a listed choice with its value. Some
 * choices are kept as text (a gesture's action, a quality, the tabs kept), and those are told from
 * typed text by their default: a choice starts on one of its own values, and what a reader types
 * starts empty. Typed text is only named: it holds blocked words, creator names, proxy hosts and
 * SIM details, which a report posted on a public tracker must not carry.
 *
 * <p>Settings left out of a backup are bookkeeping the patches keep for themselves, not choices,
 * and stay out of this. So do the two kinds a backup does carry: the lists of what TikTok has
 * sent this phone, and where a button was dragged to. On the S22 those were six of the twelve
 * lines and said nothing about what the reader had switched on.
 *
 * <p>The keys go in lists after one label, never as {@code key: value}. The export's redactor
 * blanks the value after any name holding "auth", "sid", "session" or "uid", and written that way
 * {@code show_author_region} and {@code hide_feed_sidebar_button} would each read "[omitted]".
 */
public final class ChangedSettingsReport implements LogBufferManager.ReportSection {
    static final String TITLE = "CHANGED SETTINGS";
    static final String NONE = "Every setting is at its default";
    /** Keys on one line, so a long list stays readable in an issue comment. */
    static final int PER_LINE = 6;
    /**
     * A choice's value, or a number kept as text: letters, digits and the marks a list of keys, a
     * range or a package name is written with. No spaces, slashes, colons or at signs, so a
     * sentence, a folder, an address and a handle never pass.
     */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_.,-]{1,96}");
    private static final ChangedSettingsReport INSTANCE = new ChangedSettingsReport();

    private ChangedSettingsReport() {
    }

    /** Registered once, at settings load, so every export from then on carries the section. */
    public static void install() {
        LogBufferManager.registerReportSection(INSTANCE);
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        return lines(Setting.allLoadedSettings());
    }

    /** A count, then the changed settings by kind in key order; {@link #NONE} when none did. */
    static List<String> lines(List<Setting<?>> settings) {
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        List<String> valued = new ArrayList<>(), text = new ArrayList<>();
        int choices = 0;
        for (Setting<?> setting : settings) {
            if (!setting.includeWithImportExport || keptForItself(setting.key)) continue;
            choices++;
            if (setting.isSetToDefault()) continue;
            Object value = setting.savedValue();
            if (setting instanceof BooleanSetting) {
                (Boolean.TRUE.equals(value) ? on : off).add(setting.key);
            } else if (setting instanceof IntegerSetting || setting instanceof LongSetting
                    || setting instanceof FloatSetting || setting instanceof EnumSetting
                    || (showsItsValue(setting) && plain(value))) {
                valued.add(setting.key + " " + value);
            } else {
                text.add(setting.key);
            }
        }
        int changed = on.size() + off.size() + valued.size() + text.size();
        if (changed == 0) return Collections.singletonList(NONE);
        List<String> lines = new ArrayList<>();
        lines.add(changed + " of " + choices
                + " settings differ from their defaults; typed text shows no value");
        list(lines, "on", on);
        list(lines, "off", off);
        list(lines, "set", valued);
        list(lines, "text changed", text);
        return lines;
    }

    /** What TikTok sent this phone, and where a button was dragged to. */
    private static boolean keptForItself(String key) {
        return key.endsWith("_catalog") || key.endsWith("_position");
    }

    /**
     * Whether a text setting is a choice, going by its default. The SIM details start on a
     * country and a carrier and are typed over, so they are named here and never pass.
     */
    static boolean showsItsValue(Setting<?> setting) {
        return plain(setting.defaultValue) && !setting.key.startsWith("simspoof_");
    }

    private static boolean plain(Object value) {
        return value instanceof String && PLAIN.matcher((String) value).matches();
    }

    private static void list(List<String> lines, String label, List<String> entries) {
        Collections.sort(entries);
        for (int start = 0; start < entries.size(); start += PER_LINE) {
            StringBuilder line = new StringBuilder(label).append(": ");
            int end = Math.min(entries.size(), start + PER_LINE);
            for (int index = start; index < end; index++) {
                if (index > start) line.append(", ");
                line.append(entries.get(index));
            }
            lines.add(line.toString());
        }
    }
}
