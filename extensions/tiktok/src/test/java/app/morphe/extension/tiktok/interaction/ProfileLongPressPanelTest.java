/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** A creator's videos get For You's long-press menu only when asked for, and only the profile's. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileLongPressPanelTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        SettingsStatus.longPressEnabled = true;
    }

    @After public void tearDown() {
        SettingsStatus.longPressEnabled = false;
        Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.resetToDefault();
        Settings.LONG_PRESS_FULL_MENU_IN_SEARCH.resetToDefault();
        Settings.LONG_PRESS_ACTION.resetToDefault();
    }

    @Test public void offEveryPageKeepsItsOwnMenu() {
        assertFalse(Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.defaultValue);
        assertEquals("others_homepage", ProfileLongPressPanel.scene("others_homepage"));
    }

    @Test public void onAProfileReadsAsForYouAndNothingElseChanges() {
        Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.save(true);
        assertEquals("homepage_hot", ProfileLongPressPanel.scene("others_homepage"));
        assertEquals("your own profile keeps its actions", "personal_homepage",
                ProfileLongPressPanel.scene("personal_homepage"));
        assertEquals("homepage_series", ProfileLongPressPanel.scene("homepage_series"));
        assertNull(ProfileLongPressPanel.scene(null));
    }

    @Test public void aLongPressActionOfHushfeedsLeavesTheProfileAlone() {
        Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.save(true);
        Settings.LONG_PRESS_ACTION.save("copy_link");
        assertEquals("others_homepage", ProfileLongPressPanel.scene("others_homepage"));
    }

    @Test public void searchResultsReadAsForYouWithTheirOwnSwitch() {
        assertFalse(Settings.LONG_PRESS_FULL_MENU_IN_SEARCH.defaultValue);
        assertEquals("general_search", ProfileLongPressPanel.scene("general_search"));

        Settings.LONG_PRESS_FULL_MENU_IN_SEARCH.save(true);
        assertEquals("homepage_hot", ProfileLongPressPanel.scene("general_search"));
        assertEquals("homepage_hot", ProfileLongPressPanel.scene("search_result"));
        assertEquals("the profile switch is still its own", "others_homepage",
                ProfileLongPressPanel.scene("others_homepage"));

        Settings.LONG_PRESS_ACTION.save("copy_link");
        assertEquals("general_search", ProfileLongPressPanel.scene("general_search"));
    }

    @Test public void aSavedSwitchWithoutLongPressControlsDoesNothing() {
        Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.save(true);
        SettingsStatus.longPressEnabled = false;
        assertEquals("others_homepage", ProfileLongPressPanel.scene("others_homepage"));
    }
}
