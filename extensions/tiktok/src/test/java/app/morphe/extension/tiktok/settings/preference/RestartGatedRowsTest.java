/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every row whose setting does nothing until TikTok is restarted says so in its summary, not
 * only in the toast after the change.
 *
 * <p>{@link RestartNoteTest} proves the switch row adds the sentence from the flag. This walks
 * the pages the way a reader does and holds every row to it, whatever kind of row it is: the
 * number editors, the text editors and the range editors carry restart-gated settings too, and
 * a reader who changes one and sees nothing happen cannot tell a restart from a broken hook.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class RestartGatedRowsTest {

    /**
     * The pages, by the argument the fragment takes.
     *
     * <p>Held against the fragment's own Section enum below rather than trusted. An unknown
     * name is answered with null and a silent fallback, not an error, so a renamed page would
     * quietly drop out of this walk and take its rows with it.
     */
    private static final String[] SECTIONS = {
            "FEED_FILTER", "FEED_NAVIGATION", "INTERFACE", "COMMENTS", "DOWNLOADS", "PLAYBACK",
            "SCREEN_TIME", "INBOX", "SHARE", "REGION", "PRIVACY", "BEHAVIOR", "DIAGNOSTICS", "BACKUP",
    };

    @Test public void theWalkCoversEveryPageTheSettingsScreenHas() throws Exception {
        Class<?> section = Class.forName(
                "app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment$Section");
        List<String> declared = new ArrayList<>();
        for (Object value : section.getEnumConstants()) declared.add(((Enum<?>) value).name());
        List<String> walked = new ArrayList<>(java.util.Arrays.asList(SECTIONS));

        java.util.Collections.sort(declared);
        java.util.Collections.sort(walked);
        assertTrue("the pages this walks are not the pages the settings screen has. Walked "
                + walked + ", declared " + declared, declared.equals(walked));
    }

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private Activity activity;

    @Before public void setUp() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
    }

    @Test public void everyRestartGatedRowSaysSoInItsSummary() {
        List<String> silent = new ArrayList<>();
        int gated = 0;
        for (String section : SECTIONS) {
            TikTokPreferenceFragment page = attach(section);
            for (Preference row : rows(page.getPreferenceScreen(), new ArrayList<>())) {
                if (!row.hasKey()) continue;
                Setting<?> setting = Setting.getSettingFromPath(row.getKey());
                if (setting == null || !setting.rebootApp) continue;
                gated++;
                CharSequence summary = row.getSummary();
                // Its own literal rather than the production predicate: a predicate that always
                // answered yes would leave every row silent and still pass a test that asked it.
                if (summary == null
                        || !summary.toString().toLowerCase(java.util.Locale.ROOT).contains("restart")) {
                    silent.add(section + " / " + row.getClass().getSimpleName() + " " + row.getKey()
                            + ": " + summary);
                }
            }
        }

        assertTrue("the walk saw too few restart-gated rows to mean anything: " + gated, gated >= 40);
        assertTrue("rows whose setting needs a restart and whose summary does not say so:\n"
                + String.join("\n", silent), silent.isEmpty());
    }

    /**
     * And no row says it twice.
     *
     * <p>The other half of the same walk. The sentence is appended to any summary that does not
     * already ask for a restart, so a summary that asks in its own words used to collect a second
     * copy: both Region rows shipped "and a restart. Restart TikTok to apply this." in every
     * language, because the join happens after the summary is translated.
     */
    @Test public void noRowAsksForARestartTwice() {
        List<String> doubled = new ArrayList<>();
        int saying = 0;
        for (String section : SECTIONS) {
            TikTokPreferenceFragment page = attach(section);
            for (Preference row : rows(page.getPreferenceScreen(), new ArrayList<>())) {
                CharSequence summary = row.getSummary();
                if (summary == null) continue;
                String text = summary.toString();
                if (text.contains(TogglePreference.RESTART_SENTENCE)) saying++;
                int first = text.indexOf(TogglePreference.RESTART_SENTENCE);
                if (first >= 0 && text.indexOf(TogglePreference.RESTART_SENTENCE, first + 1) >= 0) {
                    doubled.add(section + " / " + row.getKey() + ": " + text);
                    continue;
                }
                // The shape the two Region rows had: their own wording, then the sentence.
                if (first > 0 && text.substring(0, first)
                        .toLowerCase(java.util.Locale.ROOT).contains("restart")) {
                    doubled.add(section + " / " + row.getKey() + ": " + text);
                }
            }
        }

        // A page walk that reaches nothing passes every "none of them is wrong" assertion, so it
        // has to show it saw the rows first.
        assertTrue("the walk saw too few rows carrying the sentence to mean anything: " + saying,
                saying >= 40);
        assertTrue("rows that ask for a restart twice:\n" + String.join("\n", doubled),
                doubled.isEmpty());
    }

    private static List<Preference> rows(PreferenceGroup group, List<Preference> into) {
        for (int index = 0; index < group.getPreferenceCount(); index++) {
            Preference row = group.getPreference(index);
            into.add(row);
            if (row instanceof PreferenceGroup) rows((PreferenceGroup) row, into);
        }
        return into;
    }

    private TikTokPreferenceFragment attach(String section) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", section);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
}
