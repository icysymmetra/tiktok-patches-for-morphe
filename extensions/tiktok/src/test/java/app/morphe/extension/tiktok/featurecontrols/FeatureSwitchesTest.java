package app.morphe.extension.tiktok.featurecontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.ghostmode.GhostMode;
import app.morphe.extension.tiktok.inbox.InboxControls;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.telemetry.DisableTelemetryPatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The answers TikTok's own code gets back from a switch. Each of these classes is a handful of
 * one-line replies, injected where the host asks a question, and none of them had a test: a
 * reply inverted or left constant would have shown up only on a phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeatureSwitchesTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        HookStatus.clear();
        for (var setting : new app.morphe.extension.shared.settings.BooleanSetting[]{
                Settings.GHOST_MODE, Settings.DISABLE_ANALYTICS,
                Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS, Settings.HIDE_INBOX_STORIES,
                Settings.EXPAND_ACTIVITY_LIST, Settings.HIDE_HOMEPAGE_COIN,
                Settings.HIDE_PROFILE_REWARDS_SHORTCUT,
                Settings.HIDE_FEED_FOLLOW_BUTTON, Settings.HIDE_FEED_SAVE_BUTTON,
                Settings.HIDE_LIVE_ENTRANCE, Settings.HIDE_FEED_SEARCH_BUTTON,
                Settings.HIDE_FEED_SIDEBAR_BUTTON,
                Settings.HIDE_COMMENT_QUICK_REACTIONS, Settings.ENABLE_LONG_PRESS_SPEED_LOCK,
                Settings.ENABLE_NON_PERSONALIZED_SEARCH, Settings.ENABLE_LIVE_SEARCH}) {
            setting.save(setting.defaultValue);
        }
    }

    @Test public void ghostModeAnswersEveryReportingCallTogether() {
        Settings.GHOST_MODE.save(false);
        assertFalse(GhostMode.shouldBlockStoryView());
        assertFalse(GhostMode.shouldBlockProfileView());
        assertFalse(GhostMode.shouldBlockTypingStatus());

        Settings.GHOST_MODE.save(true);
        assertTrue(GhostMode.shouldBlockStoryView());
        assertTrue(GhostMode.shouldBlockProfileView());
        assertTrue(GhostMode.shouldBlockTypingStatus());
    }

    @Test public void telemetryStaysOnUntilTheSwitchIsOnAndTheAppIsUp() {
        Settings.DISABLE_ANALYTICS.save(false);
        assertFalse(DisableTelemetryPatch.isTelemetryDisabled());

        Settings.DISABLE_ANALYTICS.save(true);
        assertTrue(DisableTelemetryPatch.isTelemetryDisabled());
    }

    @Test public void nativeInboxControlsFollowTheirSwitches() {
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
        Settings.EXPAND_ACTIVITY_LIST.save(false);
        assertTrue(InboxControls.shouldShowSuggestedAccounts());
        // With the switch off, native expanded and collapsed lists retain their state.
        assertFalse(InboxControls.shouldCollapseActivityList(false));
        assertTrue(InboxControls.shouldCollapseActivityList(true));

        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
        Settings.EXPAND_ACTIVITY_LIST.save(true);
        assertFalse(InboxControls.shouldShowSuggestedAccounts());
        assertFalse(InboxControls.shouldCollapseActivityList(false));
        assertFalse(InboxControls.shouldCollapseActivityList(true));
    }

    /** Only the coin pendant shapes are dropped, and only while the switch is on. */
    @Test public void theCoinPendantGoesAndOtherTouchPointsStay() {
        Object pendant = new Object() {
            @Override public String toString() { return "pendant"; }
        };
        Settings.HIDE_HOMEPAGE_COIN.save(true);
        // Not one of the named ByteDance classes, so it is left alone whatever the switch says.
        assertNotNull(FeatureControls.filterPromotionalTouchPoint(pendant));
        assertNull("a null touch point must stay null",
                FeatureControls.filterPromotionalTouchPoint(null));

        Settings.HIDE_HOMEPAGE_COIN.save(false);
        assertEquals(pendant, FeatureControls.filterPromotionalTouchPoint(pendant));
        assertEquals(pendant, FeatureControls.filterNormalPendant(pendant));
    }

    @Test public void aHiddenFeedButtonIsGoneAndAShownOneKeepsItsVisibility() {
        Settings.HIDE_FEED_FOLLOW_BUTTON.save(false);
        Settings.HIDE_FEED_SAVE_BUTTON.save(false);
        assertEquals(View.VISIBLE, FeatureControls.hideFeedFollowButtonVisibility(View.VISIBLE));
        assertEquals(View.INVISIBLE, FeatureControls.hideFeedSaveButtonVisibility(View.INVISIBLE));

        Settings.HIDE_FEED_FOLLOW_BUTTON.save(true);
        Settings.HIDE_FEED_SAVE_BUTTON.save(true);
        assertEquals(View.GONE, FeatureControls.hideFeedFollowButtonVisibility(View.VISIBLE));
        assertEquals(View.GONE, FeatureControls.hideFeedSaveButtonVisibility(View.VISIBLE));
    }

    @Test public void profileRewardsShortcutUsesItsOwnDeclutterSwitch() {
        // The hook the patch calls, with the icon TikTok has just set up. This used to test an
        // int visibility hook that no patch had called since 55be3bd9.
        View shortcut = new View(org.robolectric.RuntimeEnvironment.getApplication());
        Settings.HIDE_PROFILE_REWARDS_SHORTCUT.save(false);
        FeatureControls.hideProfileRewardsShortcut(shortcut);
        assertEquals(View.VISIBLE, shortcut.getVisibility());
        shortcut.setVisibility(View.INVISIBLE);
        FeatureControls.hideProfileRewardsShortcut(shortcut);
        assertEquals("TikTok's own state was changed with the switch off",
                View.INVISIBLE, shortcut.getVisibility());

        Settings.HIDE_PROFILE_REWARDS_SHORTCUT.save(true);
        shortcut.setVisibility(View.VISIBLE);
        FeatureControls.hideProfileRewardsShortcut(shortcut);
        assertEquals(View.GONE, shortcut.getVisibility());
        FeatureControls.hideProfileRewardsShortcut(null);
    }

    @Test public void quickCommentReactionsKeepNativeVisibilityUntilHidden() {
        Settings.HIDE_COMMENT_QUICK_REACTIONS.save(false);
        assertEquals(View.VISIBLE,
                FeatureControls.hideQuickCommentReactionsVisibility(View.VISIBLE));
        assertEquals(View.INVISIBLE,
                FeatureControls.hideQuickCommentReactionsVisibility(View.INVISIBLE));
        assertFalse(FeatureControls.hideQuickCommentReactions());

        Settings.HIDE_COMMENT_QUICK_REACTIONS.save(true);
        assertEquals(View.GONE,
                FeatureControls.hideQuickCommentReactionsVisibility(View.VISIBLE));
        assertEquals(View.GONE,
                FeatureControls.hideQuickCommentReactionsVisibility(View.INVISIBLE));
        assertTrue(FeatureControls.hideQuickCommentReactions());
    }

    /** These two answer "is it enabled", so hiding means returning false rather than GONE. */
    @Test public void theLiveAndSearchButtonsAreDisabledRatherThanHidden() {
        Settings.HIDE_LIVE_ENTRANCE.save(false);
        Settings.HIDE_FEED_SEARCH_BUTTON.save(false);
        assertTrue(FeatureControls.hideFeedLiveButtonEnabled(true));
        assertTrue(FeatureControls.hideFeedSearchButtonEnabled(true));
        // The host's own answer still wins when it was already no.
        assertFalse(FeatureControls.hideFeedLiveButtonEnabled(false));

        Settings.HIDE_LIVE_ENTRANCE.save(true);
        Settings.HIDE_FEED_SEARCH_BUTTON.save(true);
        assertFalse(FeatureControls.hideFeedLiveButtonEnabled(true));
        assertFalse(FeatureControls.hideFeedSearchButtonEnabled(true));
    }

    /**
     * #128: the side menu button beside LIVE answers the same "is it enabled" question, with its
     * own switch. Pause Hushfeed hands it back to TikTok, and the Hook status line says the check
     * ran, since the button has no view id a report could name.
     */
    @Test public void theSideMenuButtonIsDisabledByItsOwnSwitchAndComesBackWhilePaused() {
        assertFalse("the switch starts off", Settings.HIDE_FEED_SIDEBAR_BUTTON.get());
        assertTrue(FeatureControls.hideFeedSidebarButtonEnabled(true));
        assertFalse("TikTok's own no still wins", FeatureControls.hideFeedSidebarButtonEnabled(false));
        assertTrue(String.join(" ", HookStatus.report()).contains(FeatureControls.SIDEBAR_BUTTON_FAMILY));

        Settings.HIDE_LIVE_ENTRANCE.save(true);
        assertTrue("the LIVE switch must not take the side menu button too",
                FeatureControls.hideFeedSidebarButtonEnabled(true));

        Settings.HIDE_FEED_SIDEBAR_BUTTON.save(true);
        assertFalse(FeatureControls.hideFeedSidebarButtonEnabled(true));
        assertFalse(FeatureControls.hideFeedSidebarButtonEnabled(false));

        PausedProcess.set(true);
        assertTrue("Pause Metra leaves the button to TikTok",
                FeatureControls.hideFeedSidebarButtonEnabled(true));
    }

    /**
     * The search gates add to TikTok's answer, never take from it, and say in Hook status that
     * TikTok asked. A gate recorder reads the settings value under the gate, which stays 0, so
     * that line is how a device check sees the gate forced.
     */
    @Test public void theSearchGatesOpenWithTheirSwitchesAndSayTikTokAsked() {
        assertFalse(FeatureControls.enableNonPersonalizedSearch(false));
        assertTrue("TikTok's own yes still stands", FeatureControls.enableNonPersonalizedSearch(true));
        assertFalse(FeatureControls.enableLiveSearch(false));
        assertTrue(String.join(" ", HookStatus.report()).contains(FeatureControls.NON_PERSONALIZED_SEARCH_FAMILY));
        assertTrue(String.join(" ", HookStatus.report()).contains(FeatureControls.LIVE_SEARCH_FAMILY));

        Settings.ENABLE_NON_PERSONALIZED_SEARCH.save(true);
        Settings.ENABLE_LIVE_SEARCH.save(true);
        assertTrue(FeatureControls.enableNonPersonalizedSearch(false));
        assertTrue(FeatureControls.enableLiveSearch(false));
    }

    @Test public void theSpeedLockKeepsTheHostsDistanceAndFillsInAMissingOne() {
        Settings.ENABLE_LONG_PRESS_SPEED_LOCK.save(false);
        assertFalse(FeatureControls.overrideLongPressSpeedUpEnabled(false));
        assertEquals("the host's own distance is untouched while the switch is off",
                0, FeatureControls.overrideLongPressSpeedUpLockDistance(0));

        Settings.ENABLE_LONG_PRESS_SPEED_LOCK.save(true);
        assertTrue(FeatureControls.overrideLongPressSpeedUpEnabled(false));
        assertEquals(40, FeatureControls.overrideLongPressSpeedUpLockDistance(40));
        assertTrue("a distance of zero needs one of ours",
                FeatureControls.overrideLongPressSpeedUpLockDistance(0) > 0);
    }
}
