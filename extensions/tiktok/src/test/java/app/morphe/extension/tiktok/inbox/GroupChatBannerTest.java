package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InboxPreferenceCategory;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Inbox's group chat prompt and the settings rows for it, Bulletin board and Footnotes: the
 * answer the banner's hook reads, that it honours Pause, and that each switch has a plain row
 * on the page its patch puts it on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GroupChatBannerTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void restore() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.HIDE_INBOX_GROUP_CHAT_BANNER.resetToDefault();
        Settings.HIDE_INBOX_BULLETIN_BOARDS.resetToDefault();
        Settings.HIDE_FOOTNOTES.resetToDefault();
    }

    @Test public void theBannerIsLeftAloneUntilItsSwitchIsOn() {
        assertFalse(Settings.HIDE_INBOX_GROUP_CHAT_BANNER.defaultValue);
        assertFalse(InboxControls.shouldHideGroupChatBanner());
        Settings.HIDE_INBOX_GROUP_CHAT_BANNER.save(true);
        assertTrue(InboxControls.shouldHideGroupChatBanner());
        Settings.HIDE_INBOX_GROUP_CHAT_BANNER.save(false);
        assertFalse(InboxControls.shouldHideGroupChatBanner());
    }

    @Test public void otherInboxSwitchesDoNotHideTheBanner() {
        Settings.HIDE_CHAT_STICKER_BANNER.save(true);
        Settings.HIDE_INBOX_BULLETIN_BOARDS.save(true);
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
        try {
            assertFalse(InboxControls.shouldHideGroupChatBanner());
        } finally {
            Settings.HIDE_CHAT_STICKER_BANNER.resetToDefault();
            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.resetToDefault();
        }
    }

    @Test public void pausedTheBannerIsTikToks() {
        Settings.HIDE_INBOX_GROUP_CHAT_BANNER.save(true);
        PausedProcess.set(true);
        assertFalse(InboxControls.shouldHideGroupChatBanner());
    }

    @Test public void theHookReportsThatItRan() {
        InboxControls.shouldHideGroupChatBanner();
        assertTrue(String.join(" ", HookStatus.report()).contains("group chat banner"));
    }

    @Test public void theInboxPageCarriesTheTwoRowsOffByDefaultWithPlainText() {
        boolean before = SettingsStatus.inboxFilterEnabled;
        boolean hooked = SettingsStatus.groupChatBannerEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.groupChatBannerEnabled = true;
            PreferenceScreen screen = activity
                    .getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, screen);
            Preference bulletin = byTitle(screen, "Hide Bulletin board");
            Preference group = byTitle(screen, "Hide the group chat prompt");
            for (Preference row : new Preference[]{bulletin, group}) {
                assertNotNull(row);
                assertNotNull(row.getSummary());
                assertNoDashes(row.getTitle().toString());
                assertNoDashes(row.getSummary().toString());
            }
            assertEquals(Settings.HIDE_INBOX_BULLETIN_BOARDS.key, bulletin.getKey());
            assertEquals(Settings.HIDE_INBOX_GROUP_CHAT_BANNER.key, group.getKey());
        } finally {
            SettingsStatus.inboxFilterEnabled = before;
            SettingsStatus.groupChatBannerEnabled = hooked;
        }
    }

    @Test public void aBuildWhereTheBannersHooksWereLeftOutShowsNoRowForThem() {
        boolean inbox = SettingsStatus.inboxFilterEnabled;
        boolean overlays = SettingsStatus.videoOverlaysEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.inboxFilterEnabled = true;
            SettingsStatus.videoOverlaysEnabled = true;
            SettingsStatus.groupChatBannerEnabled = false;
            SettingsStatus.footnotesEnabled = false;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InboxPreferenceCategory(activity, screen);
            new InterfacePreferenceCategory(activity, screen);
            assertNull(byTitle(screen, "Hide the group chat prompt"));
            assertNull(byTitle(screen, "Hide Footnotes"));
            assertNotNull("Bulletin board needs no hook of its own", byTitle(screen, "Hide Bulletin board"));
        } finally {
            SettingsStatus.inboxFilterEnabled = inbox;
            SettingsStatus.videoOverlaysEnabled = overlays;
        }
    }

    @Test public void theFeedScreenCarriesTheFootnotesRow() {
        boolean before = SettingsStatus.videoOverlaysEnabled;
        boolean hooked = SettingsStatus.footnotesEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.videoOverlaysEnabled = true;
            SettingsStatus.footnotesEnabled = true;
            PreferenceScreen screen = activity
                    .getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            Preference footnotes = byTitle(screen, "Hide Footnotes");
            assertNotNull(footnotes);
            assertNotNull(footnotes.getSummary());
            assertNoDashes(footnotes.getSummary().toString());
            assertEquals(Settings.HIDE_FOOTNOTES.key, footnotes.getKey());
        } finally {
            SettingsStatus.videoOverlaysEnabled = before;
            SettingsStatus.footnotesEnabled = hooked;
        }
    }

    private static void assertNoDashes(String text) {
        assertFalse(text, text.indexOf((char) 0x2014) >= 0 || text.indexOf((char) 0x2013) >= 0 || text.contains(" - "));
    }

    /** The one row with this title, or null when there's none. */
    private static Preference byTitle(PreferenceGroup group, String title) {
        List<Preference> found = new ArrayList<>();
        collect(group, title, found);
        assertTrue("rows titled " + title + ": " + found.size(), found.size() <= 1);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void collect(PreferenceGroup group, String title, List<Preference> found) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (child.getTitle() != null && title.contentEquals(child.getTitle())) found.add(child);
            if (child instanceof PreferenceGroup) collect((PreferenceGroup) child, title, found);
        }
    }
}
