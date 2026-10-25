package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** What is handed to the launcher when TikTok publishes its shortcuts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LauncherShortcutsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theHostsOwnListIsPublishedWhileTheSwitchIsOff() {
        try {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            List<String> built = Arrays.asList("search", "messages");
            // The same list, not a copy of it: the call this sits on is TikTok's, and handing
            // back something it did not build would be a change nobody asked for.
            assertSame(built, LauncherShortcuts.publish(built));
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
        }
    }

    @Test public void nothingIsPublishedWhileTheSwitchIsOn() {
        try {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            List<String> built = Arrays.asList("search", "messages");
            List<?> published = LauncherShortcuts.publish(built);
            assertNotNull(published);
            assertTrue("the launcher was still offered something", published.isEmpty());
            // An empty list is what tells the platform to drop what it is already showing, so
            // a list that was published before the switch went on clears itself.
            assertEquals(0, published.size());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
        }
    }

    @Test public void aListTheHostBuiltEmptyStaysEmptyEitherWay() {
        try {
            List<String> none = new ArrayList<>();
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            assertTrue(LauncherShortcuts.publish(none).isEmpty());
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            assertTrue(LauncherShortcuts.publish(none).isEmpty());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
        }
    }

    @Test public void nothingHandedOverIsNotACrash() {
        try {
            // The handover sits inside TikTok's own try block, so anything thrown here would
            // surface as its "add shortcut error" and the reader would never see a cause.
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            assertNull(LauncherShortcuts.publish(null));
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            assertTrue(LauncherShortcuts.publish(null).isEmpty());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
        }
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.launcherShortcutsEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("hide_launcher_shortcuts"));
        } finally {
            SettingsStatus.launcherShortcutsEnabled = false;
        }
    }

    /** A manager holding the two entries TikTok publishes on this target. */
    private static ShortcutManager managerWithTwo(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        manager.setDynamicShortcuts(Arrays.asList(
                shortcut(context, "search"), shortcut(context, "messages")));
        assertEquals(2, manager.getDynamicShortcuts().size());
        return manager;
    }

    private static ShortcutInfo shortcut(Context context, String id) {
        return new ShortcutInfo.Builder(context, id)
                .setShortLabel(id)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .build();
    }

    @Test public void turningItOnTakesAwayWhatIsAlreadyPublished() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            ShortcutManager manager = managerWithTwo(activity);

            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
            LauncherShortcuts.apply(activity);

            // The list TikTok published before the switch was ever touched is the case every
            // reader is in, and the handover this could otherwise wait for does not come.
            assertTrue("the launcher menu still has entries", manager.getDynamicShortcuts().isEmpty());
            assertTrue("nothing recorded that these were taken away",
                    Settings.LAUNCHER_SHORTCUTS_REMOVED.get());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    /**
     * Stands in for what the patch writes into the bridge, and counts the asks. Without this the
     * only thing a test could see is the flag, and deleting the ask entirely would still pass.
     */
    @Implements(LauncherShortcuts.class)
    public static class RebuildBridge {
        static int asks;
        static boolean throwOnAsk;
        @Implementation protected static void askHostToRebuild() {
            asks++;
            if (throwOnAsk) throw new IllegalStateException("no shortcut service yet");
        }
    }

    @Test @Config(shadows = RebuildBridge.class)
    public void turningItOffAsksForThemBackOnceAndThenStops() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            RebuildBridge.asks = 0;
            RebuildBridge.throwOnAsk = false;

            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(true);
            LauncherShortcuts.apply(activity);
            assertEquals("TikTok was not asked to build them again", 1, RebuildBridge.asks);
            assertFalse("the ask would be repeated on every launch",
                    Settings.LAUNCHER_SHORTCUTS_REMOVED.get());

            // Second launch with the switch still off: nothing left to put back, so TikTok is
            // left alone rather than asked again.
            LauncherShortcuts.apply(activity);
            assertEquals("asked again with nothing to put back", 1, RebuildBridge.asks);
            assertFalse(Settings.LAUNCHER_SHORTCUTS_REMOVED.get());
        } finally {
            RebuildBridge.throwOnAsk = false;
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    @Test @Config(shadows = RebuildBridge.class)
    public void anAskThatThrowsIsTriedAgainOnTheNextLaunch() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            RebuildBridge.asks = 0;
            RebuildBridge.throwOnAsk = true;

            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(true);
            LauncherShortcuts.apply(activity);

            // The service the patch calls need not be registered this early. Clearing the record
            // before the ask would leave a reader with an empty menu and no later launch willing
            // to try again, which is the one failure this whole path exists to avoid.
            assertEquals(1, RebuildBridge.asks);
            assertTrue("a failed ask threw the record away", Settings.LAUNCHER_SHORTCUTS_REMOVED.get());

            RebuildBridge.throwOnAsk = false;
            LauncherShortcuts.apply(activity);
            assertEquals(2, RebuildBridge.asks);
            assertFalse(Settings.LAUNCHER_SHORTCUTS_REMOVED.get());
        } finally {
            RebuildBridge.throwOnAsk = false;
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    @Test @Config(shadows = RebuildBridge.class)
    public void publishingNothingCountsAsTakingThemAway() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            RebuildBridge.asks = 0;
            RebuildBridge.throwOnAsk = false;

            // The switch goes on and TikTok publishes during the same run, so the handover is
            // what empties the menu and no launch has passed through apply() with it on.
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
            assertTrue(LauncherShortcuts.publish(Arrays.asList("search", "messages")).isEmpty());
            assertTrue("the handover took them away without recording it",
                    Settings.LAUNCHER_SHORTCUTS_REMOVED.get());

            // Switch off again, next launch: there is something to put back and it is asked for.
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            LauncherShortcuts.apply(activity);
            assertEquals(1, RebuildBridge.asks);
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    @Test public void aReaderWhoNeverTurnedItOnKeepsWhatTikTokPublished() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            ShortcutManager manager = managerWithTwo(activity);

            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
            LauncherShortcuts.apply(activity);

            assertEquals("the switch was never on and the menu changed anyway",
                    2, manager.getDynamicShortcuts().size());
            assertFalse(Settings.LAUNCHER_SHORTCUTS_REMOVED.get());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    @Test public void turningItOnWithNothingPublishedStillRecordsIt() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            ShortcutManager manager = activity.getSystemService(ShortcutManager.class);
            manager.removeAllDynamicShortcuts();

            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
            LauncherShortcuts.apply(activity);

            // Nothing to remove now, but turning the switch back off still has to put back what
            // TikTok would have had, so the record matters even when the removal is a no-op.
            assertTrue(Settings.LAUNCHER_SHORTCUTS_REMOVED.get());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    @Test public void noContextIsNotACrash() {
        try {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(true);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
            LauncherShortcuts.apply(null);
            // Not merely "did not throw": with nothing to ask about the published list, there is
            // nothing to record either, and recording a removal that did not happen would have
            // the next launch ask TikTok to rebuild for no reason.
            assertFalse("a call with no context recorded a removal",
                    Settings.LAUNCHER_SHORTCUTS_REMOVED.get());
        } finally {
            Settings.HIDE_LAUNCHER_SHORTCUTS.save(false);
            Settings.LAUNCHER_SHORTCUTS_REMOVED.save(false);
        }
    }

    @Test public void theInternalRecordDoesNotTravelInABackup() {
        // It describes what happened on one phone. Restoring it onto another would have that
        // phone ask TikTok to rebuild shortcuts nothing had removed.
        assertFalse(Settings.LAUNCHER_SHORTCUTS_REMOVED.includeWithImportExport);
        assertTrue(Settings.HIDE_LAUNCHER_SHORTCUTS.includeWithImportExport);
    }

    @Test public void theRowIsAbsentUntilThePatchSaysItIsThere() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.launcherShortcutsEnabled = false;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNull(screen.findPreference("hide_launcher_shortcuts"));
        }
    }
}
