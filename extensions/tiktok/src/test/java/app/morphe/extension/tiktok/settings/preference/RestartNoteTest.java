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

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsRegistryRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A switch that needs a restart has to say so.
 *
 * <p>Fifty settings carry the flag and thirty-four of their summaries mentioned it, each in
 * their own words, so sixteen switches looked like they did nothing at all when pressed. Saying
 * it once, from the flag rather than from hand written copy, is the only version that cannot
 * drift as settings are added.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RestartNoteTest {
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    private Context context;

    @Before public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        context = activity;
    }

    private static BooleanSetting setting(String key, boolean rebootApp) {
        return new BooleanSetting(key, Boolean.FALSE, rebootApp);
    }

    @Test public void aRestartGatedSwitchSaysSoEvenWhenItsSummaryDoesNot() {
        TogglePreference row = new TogglePreference(context, "Some switch",
                "What this switch does.", setting("restart_note_gated", true));

        assertTrue("a restart-gated switch does not tell the reader to restart: "
                        + row.getSummary(),
                row.getSummary().toString().contains("Restart TikTok"));
        assertTrue("the summary lost the sentence it started with",
                row.getSummary().toString().startsWith("What this switch does."));
    }

    @Test public void aSwitchThatTakesEffectAtOnceIsLeftAlone() {
        TogglePreference row = new TogglePreference(context, "Some switch",
                "What this switch does.", setting("restart_note_immediate", false));

        assertEquals("a switch that needs no restart was told to restart",
                "What this switch does.", row.getSummary().toString());
    }

    @Test public void aSummaryThatAlreadySaysItDoesNotSayItTwice() {
        String summary = "What this switch does. Restart TikTok to apply it.";
        TogglePreference row = new TogglePreference(context, "Some switch", summary,
                setting("restart_note_already", true));

        String text = row.getSummary().toString();
        assertEquals("the restart sentence was added on top of one already there",
                text.indexOf("Restart TikTok"), text.lastIndexOf("Restart TikTok"));
    }

    /**
     * A summary that asks for a restart in its own words is left alone too.
     *
     * <p>The check was the literal "Restart TikTok", and the two Region rows say "and a restart",
     * so both shipped the sentence twice in all five languages: the join happens after the
     * summary is translated, so no table could have caught it.
     */
    @Test public void aSummaryThatAsksForARestartInItsOwnWordsIsLeftAlone() {
        TogglePreference row = new TogglePreference(context, "Some switch",
                "What this switch does. Needs Override SIM details and a restart.",
                setting("restart_note_own_words", true));

        assertEquals("a summary that already asks for a restart was told to restart again",
                "What this switch does. Needs Override SIM details and a restart.",
                row.getSummary().toString());
    }

    /** Restarting is the word, not a word inside another one: "restarted" counts, "start" does not. */
    @Test public void aSummaryThatMerelyStartsSomethingStillGetsTheSentence() {
        TogglePreference row = new TogglePreference(context, "Some switch",
                "Start the feed paused.", setting("restart_note_start", true));

        assertTrue("a summary about starting was read as one about restarting: "
                        + row.getSummary(),
                row.getSummary().toString().contains("Restart TikTok to apply this."));
    }

    /**
     * The mutation control. Reading the flag is what decides this, so a row built from a setting
     * whose flag is off must not pick the sentence up from anywhere else.
     */
    @Test public void theNoteComesFromTheFlagAndNotFromTheWording() {
        TogglePreference restart = new TogglePreference(context, "A", "Same words.",
                setting("restart_note_flag_on", true));
        TogglePreference immediate = new TogglePreference(context, "A", "Same words.",
                setting("restart_note_flag_off", false));

        assertTrue(restart.getSummary().toString().contains("Restart TikTok"));
        assertFalse("the sentence arrived without the flag asking for it",
                immediate.getSummary().toString().contains("Restart TikTok"));
    }

    /**
     * On a German phone the sentence arrives in German.
     *
     * <p>Asked through the same call the row makes rather than by reading the table, and checked
     * by not being the English, so the table can reword it without this having to be edited.
     */
    @Test
    @Config(sdk = 28, qualifiers = "de")
    public void theSentenceTravelsTranslated() {
        Activity german = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(german);

        String translated = app.morphe.extension.tiktok.settings.L10n
                .t(german, TogglePreference.RESTART_SENTENCE);

        assertFalse("the restart sentence ships in English on a translated phone",
                TogglePreference.RESTART_SENTENCE.equals(translated));
        assertTrue("the translated restart sentence does not mention TikTok",
                translated.contains("TikTok"));
    }
}
