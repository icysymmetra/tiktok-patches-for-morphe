/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.captions.CaptionToolsTest;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * The Creator exceptions row as the Feed filter page carries it: the value line every text
 * row has, a line naming the entries a block list also holds that follows both lists, and a
 * refusal of a pattern or a display name before it saves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CreatorExceptionsRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void thePageIsThere() {
        SettingsStatus.feedFilterEnabled = true;
    }

    @After
    public void reset() {
        SettingsStatus.feedFilterEnabled = false;
        Settings.CREATOR_FILTER_EXCEPTIONS.resetToDefault();
        Settings.BLOCKED_CREATORS.resetToDefault();
        Settings.LOCAL_HIDDEN_CREATORS.resetToDefault();
        ShadowToast.reset();
    }

    private static InputTextPreference open(Activity activity) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "FEED_FILTER");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        InputTextPreference row = (InputTextPreference)
                fragment.findPreference(Settings.CREATOR_FILTER_EXCEPTIONS.key);
        assertNotNull("the Feed filter page has no creator exceptions row", row);
        return row;
    }

    @Test
    public void theRowNamesTheEntriesABlockListAlsoHoldsAndFollowsBothLists() {
        try (var owner = Robolectric.buildActivity(CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.CREATOR_FILTER_EXCEPTIONS.save("alice, bob");
            Settings.BLOCKED_CREATORS.save("@Alice");
            InputTextPreference row = open(activity);
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("\nCurrent: alice, bob\n"));
            assertTrue(summary, summary.endsWith(
                    "Also on a block list, so still hidden: " + L10n.isolate("alice")));

            // The lists change under the row; its next draw says so.
            Settings.BLOCKED_CREATORS.save("");
            Settings.LOCAL_HIDDEN_CREATORS.save("bob");
            row.getView(null, null);
            summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.endsWith(
                    "Also on a block list, so still hidden: " + L10n.isolate("bob")));

            Settings.LOCAL_HIDDEN_CREATORS.save("");
            row.getView(null, null);
            summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.endsWith("\nCurrent: alice, bob"));

            // A list a backup brought in, with an entry the editor would have refused.
            Settings.CREATOR_FILTER_EXCEPTIONS.save("alice, /^news_/");
            row.setText("alice, /^news_/");
            summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.endsWith(
                    "Not a handle or id, so ignored: " + L10n.isolate("/^news_/")));
        }
    }

    @Test
    public void aPatternOrADisplayNameIsRefusedBeforeItSaves() {
        try (var owner = Robolectric.buildActivity(CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            InputTextPreference row = open(activity);
            assertTrue(String.valueOf(row.getSummary()).endsWith("\nCurrent: Empty"));

            assertFalse(row.callChangeListener("alice, /^news_/"));
            String toast = ShadowToast.getTextOfLatestToast();
            assertNotNull(toast);
            assertTrue(toast, toast.contains("a pattern can't be one") && toast.contains("/^news_/"));
            assertFalse(row.callChangeListener("Poster Person"));
            toast = ShadowToast.getTextOfLatestToast();
            assertTrue(toast, toast.contains("not a display name") && toast.contains("Poster Person"));
            assertEquals("", Settings.CREATOR_FILTER_EXCEPTIONS.get());

            assertTrue(row.callChangeListener("@alice, 123456"));
            row.setText("@alice, 123456");
            assertEquals("@alice, 123456", Settings.CREATOR_FILTER_EXCEPTIONS.get());
            assertTrue(String.valueOf(row.getSummary()).endsWith("\nCurrent: @alice, 123456"));
        }
    }
}
