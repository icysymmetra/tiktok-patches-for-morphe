package app.morphe.extension.tiktok.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Keep offline videos until you delete them (#123): the switch TikTok's lifetime calculation asks
 * at its entry, the lifetime it hands back while the switch is on, and the row that offers it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OfflineVideoExpiryTest {
    private static final String TITLE = "Keep offline videos until you delete them";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        HookStatus.clear();
        Settings.KEEP_OFFLINE_VIDEOS.resetToDefault();
    }

    @Test
    public void tikTokKeepsItsOwnLifetimeUntilTheSwitchIsTurnedOn() {
        assertEquals("the switch is off by default", Boolean.FALSE, Settings.KEEP_OFFLINE_VIDEOS.defaultValue);
        assertFalse(OfflineVideoExpiry.keepOfflineVideos());

        Settings.KEEP_OFFLINE_VIDEOS.save(true);
        assertTrue(OfflineVideoExpiry.keepOfflineVideos());
        assertTrue("the build report should say the lifetime hook ran",
                String.join(" ", HookStatus.report()).contains("offline video expiry"));

        PausedProcess.set(true);
        assertFalse("a paused Metra still kept the videos", OfflineVideoExpiry.keepOfflineVideos());
        PausedProcess.set(false);

        Settings.KEEP_OFFLINE_VIDEOS.save(false);
        assertFalse(OfflineVideoExpiry.keepOfflineVideos());
    }

    /** Auto adjust's boot step and its legacy rollback sit out only while the switch is on. */
    @Test
    public void autoAdjustRunsAsTikTokWroteItUntilTheSwitchIsTurnedOn() {
        assertFalse(OfflineVideoExpiry.keepThroughAutoAdjust());

        Settings.KEEP_OFFLINE_VIDEOS.save(true);
        assertTrue(OfflineVideoExpiry.keepThroughAutoAdjust());
        assertTrue("the build report should say the Auto adjust hook ran",
                String.join(" ", HookStatus.report()).contains("offline auto adjust"));

        PausedProcess.set(true);
        assertFalse("a paused Metra still held Auto adjust back", OfflineVideoExpiry.keepThroughAutoAdjust());
        PausedProcess.set(false);

        Settings.KEEP_OFFLINE_VIDEOS.save(false);
        assertFalse(OfflineVideoExpiry.keepThroughAutoAdjust());
    }

    /** TikTok's yes to "clear what the default-on experiment saved" becomes a no only with the switch on. */
    @Test
    public void theDefaultOnCleanupIsCancelledOnlyWhileTheSwitchIsOn() {
        assertTrue("TikTok's own yes should stand with the switch off", OfflineVideoExpiry.keepThroughDefaultEnableCleanup(true));
        assertFalse(OfflineVideoExpiry.keepThroughDefaultEnableCleanup(false));

        Settings.KEEP_OFFLINE_VIDEOS.save(true);
        assertFalse("the list was cleared with the switch on", OfflineVideoExpiry.keepThroughDefaultEnableCleanup(true));
        assertFalse(OfflineVideoExpiry.keepThroughDefaultEnableCleanup(false));
        assertTrue("the build report should say the clean-up hook ran",
                String.join(" ", HookStatus.report()).contains("offline default cleanup"));

        PausedProcess.set(true);
        assertTrue("a paused Metra still cancelled the clean-up", OfflineVideoExpiry.keepThroughDefaultEnableCleanup(true));
        PausedProcess.set(false);
    }

    /** TikTok works the lifetime out once per account and keeps it, so a change waits for a restart. */
    @Test
    public void theSwitchAsksForARestart() {
        assertTrue(Settings.KEEP_OFFLINE_VIDEOS.rebootApp);
    }

    @Test
    public void theKeptLifetimeIsTenYears() {
        assertEquals(TimeUnit.DAYS.toMillis(3650), OfflineVideoExpiry.keptLifetimeMs());
        assertEquals(OfflineVideoExpiry.KEPT_LIFETIME_MS, OfflineVideoExpiry.keptLifetimeMs());
    }

    /**
     * TikTok calls its list stale when {@code now - lastRefresh} is over the lifetime, and a list
     * it never filled has a last refresh of 0. The kept lifetime must stay under the time since
     * 1970, or an empty list would count as fresh and never be filled.
     */
    @Test
    public void aListThatWasNeverFilledStillCountsAsStale() {
        long neverRefreshed = 0L;
        assertTrue(System.currentTimeMillis() - neverRefreshed > OfflineVideoExpiry.keptLifetimeMs());
    }

    /** TikTok's own longest lifetime is 90 days. The kept one is far past it and still adds safely. */
    @Test
    public void theKeptLifetimeOutlastsTikTokOwnAndDoesNotOverflow() {
        assertTrue(OfflineVideoExpiry.keptLifetimeMs() > TimeUnit.DAYS.toMillis(90) * 10);
        long insertedNow = System.currentTimeMillis();
        long expires = insertedNow + OfflineVideoExpiry.keptLifetimeMs();
        assertTrue("insert_time + lifetime must not wrap", expires > insertedNow);
    }

    /** The row shows only where the patch found the lifetime, under the offline videos limit. */
    @Test
    public void theRowShowsOnlyWhereTheLifetimeWasHooked() {
        boolean offline = SettingsStatus.customOfflineVideosEnabled;
        boolean keep = SettingsStatus.keepOfflineVideosEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.customOfflineVideosEnabled = true;

            SettingsStatus.keepOfflineVideosEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, without);
            assertNotNull(find(without, "Offline videos limit"));
            assertNull("the row showed on a build where the lifetime wasn't hooked", find(without, TITLE));

            SettingsStatus.keepOfflineVideosEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, with);
            Preference row = find(with, TITLE);
            assertNotNull(row);
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.startsWith("TikTok removes videos saved for offline viewing"));
            assertTrue("the restart note is missing: " + summary, summary.contains("Restart TikTok"));
        } finally {
            SettingsStatus.customOfflineVideosEnabled = offline;
            SettingsStatus.keepOfflineVideosEnabled = keep;
        }
    }

    private static Preference find(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            if (preference.getTitle() != null && title.contentEquals(preference.getTitle())) return preference;
            if (preference instanceof PreferenceGroup) {
                Preference nested = find((PreferenceGroup) preference, title);
                if (nested != null) return nested;
            }
        }
        return null;
    }
}
