package app.morphe.extension.tiktok.favorites;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The two server gates that empty the profile's Favorites tab, answered with the tab kept.
 *
 * <p>Issue #4 on the S22: forcing either gate through the Feature Gate Lab left the tab in
 * place with "No Favorites" where two saved posts had been, and no profile list reached the
 * feed filter. These pin that the hook answers exactly those two reads, nothing else, only
 * while the switch is on, and says so in the report once per gate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FavoritesTabTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.KEEP_FAVORITES_TAB.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        FavoritesTab.resetForTests();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() {
        Settings.KEEP_FAVORITES_TAB.resetToDefault();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        FavoritesTab.resetForTests();
        HookStatus.clear();
        LogBufferManager.clearLogBuffer();
    }

    @Test public void theTwoReversalGatesAreAnsweredWithTheTabKeptAndTheReportSaysSo() {
        FavoritesTab.installed();

        assertEquals(0, FavoritesTab.keepInt(FavoritesTab.REVERSE_GATE, 1));
        assertEquals(0, FavoritesTab.keepInt(FavoritesTab.REVERSE_GATE, 1));
        assertFalse(FavoritesTab.keepBoolean(FavoritesTab.NEW_USER_GATE, true));

        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("favorites tab: 3 found, 0 missing"));
        String report = LogBufferManager.buildExportText();
        String reverse = "Kept the Favorites tab: favorite_reverse came back 1";
        assertTrue(report, report.contains(reverse));
        assertTrue(report, report.contains(
                "Kept the Favorites tab: new_user_favorite_full_reversal_exp came back true"));
        // Once per gate, not once per profile visit.
        assertEquals(report, report.indexOf(reverse), report.lastIndexOf(reverse));
    }

    @Test public void everyOtherReadAndEveryOtherAnswerPassesThrough() {
        FavoritesTab.installed();

        assertEquals(0, FavoritesTab.keepInt(FavoritesTab.REVERSE_GATE, 0));
        assertEquals(1, FavoritesTab.keepInt("seekbar_show_thumbnail_when_drag", 1));
        assertEquals(7, FavoritesTab.keepInt(null, 7));
        assertTrue(FavoritesTab.keepBoolean("favorite_video_tab_search", true));
        assertFalse(FavoritesTab.keepBoolean(FavoritesTab.NEW_USER_GATE, false));

        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("favorites tab: 1 found, 0 missing"));
        assertFalse(LogBufferManager.buildExportText().contains("Kept the Favorites tab"));
    }

    @Test public void theSwitchOffLetsTheServerHaveItsWay() {
        Settings.KEEP_FAVORITES_TAB.save(false);

        assertEquals(1, FavoritesTab.keepInt(FavoritesTab.REVERSE_GATE, 1));
        assertTrue(FavoritesTab.keepBoolean(FavoritesTab.NEW_USER_GATE, true));
        assertFalse(LogBufferManager.buildExportText().contains("Kept the Favorites tab"));
    }
}
