/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The About row is where a reporter reads the two numbers every bug report is asked for. The
 * stamp is written at patch time, so the row is built here with the values a patched phone
 * would hand it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AboutRowTest {

    private Context context;

    @Before public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        context = activity;
    }

    @Test public void thePatchedRowNamesTheBundleAndTheAppItRunsIn() {
        MorpheTikTokAboutPreference row = new MorpheTikTokAboutPreference(context, "0.31.0", "46.2.3");

        assertEquals("Version 0.31.0 for TikTok 46.2.3. Opens github.com in your browser",
                row.getSummary().toString());
        assertEquals("Metra", row.getTitle().toString());
        assertEquals("a row with no key is skipped by the settings search",
                MorpheTikTokAboutPreference.KEY, row.getKey());
    }

    @Test public void theStatusCardShowsTheSameVersionsAndOpensDiagnostics() {
        boolean[] opened = {false};
        SettingsStatusPreference status = new SettingsStatusPreference(
                context, "0.47.0", "47.0.3", () -> opened[0] = true);

        assertEquals("Metra is active", status.getTitle().toString());
        assertEquals("Version 0.47.0 for TikTok 47.0.3", status.getSummary().toString());
        android.view.View view = status.getView(null, null);
        android.view.View diagnostics = view.findViewWithTag(
                SettingsStatusPreference.ACTION_TAG);
        assertTrue(diagnostics.performClick());
        assertTrue("the status action did not open Diagnostics", opened[0]);
    }

    @Test public void anUnpatchedBuildKeepsWhatTheRowAlwaysSaid() {
        MorpheTikTokAboutPreference row = new MorpheTikTokAboutPreference(context, "", "46.2.3");

        assertEquals("Opens github.com in your browser", row.getSummary().toString());
        assertEquals("the row built for this test build shows a version it cannot know",
                "Opens github.com in your browser", new MorpheTikTokAboutPreference(context).getSummary().toString());
    }

    /**
     * The package manager can refuse, and what it leaves behind is the literal English word
     * "Unknown" for the rest of the process. Splicing that into a German sentence reads worse
     * than leaving TikTok's version out, so the row falls back to the bundle version alone.
     */
    @Test public void anAppWhoseVersionCannotBeReadLeavesTikTokOutRatherThanSayingUnknown() {
        assertEquals("Version 0.31.0. Opens github.com in your browser",
                MorpheTikTokAboutPreference.summaryFor(context, "0.31.0", "Unknown").toString());
        assertEquals("Version 0.31.0. Opens github.com in your browser",
                MorpheTikTokAboutPreference.summaryFor(context, "0.31.0", "").toString());
        assertEquals("Version 0.31.0. Opens github.com in your browser",
                MorpheTikTokAboutPreference.summaryFor(context, "0.31.0", null).toString());
    }

    /**
     * On a German phone the sentence arrives in German with both numbers in it. Checked by not
     * being the English, so the table can reword it without this having to be edited.
     */
    @Test
    @Config(sdk = 28, qualifiers = "de")
    public void theSummaryTravelsTranslatedWithBothVersionsInIt() {
        Activity german = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(german);

        String summary = MorpheTikTokAboutPreference.summaryFor(german, "0.31.0", "46.2.3").toString();

        assertFalse("the About summary ships in English on a translated phone: " + summary,
                summary.startsWith("Version 0.31.0 for TikTok 46.2.3."));
        assertTrue(summary.contains("0.31.0"));
        assertTrue(summary.contains("46.2.3"));
    }
}
