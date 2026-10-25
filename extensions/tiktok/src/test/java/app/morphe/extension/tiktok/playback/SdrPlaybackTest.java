package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.PlaybackPreferenceCategory;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Play SDR instead of HDR: which gears reach the player, and where the switch lives. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SdrPlaybackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    public static final class Address extends UrlModel {
        private final String url;
        Address(String url) { this.url = url; }
        @Override public List<String> getUrlList() { return List.of(url); }
    }

    /** SimBitRate's shape: hdrType 0 is SDR, 1 and 2 are HDR. */
    public static final class Gear {
        public final String gearName;
        public final int bitRate;
        public final UrlModel playAddr;
        private final int hdrType;
        Gear(String name, int rate, int hdrType) {
            gearName = name;
            bitRate = rate;
            playAddr = new Address("https://example.com/" + name + "-" + hdrType);
            this.hdrType = hdrType;
        }
        public int getHdrType() { return hdrType; }
        @Override public String toString() { return gearName + "/" + hdrType; }
    }

    @After public void tearDown() {
        Settings.PLAY_SDR.save(false);
        Settings.PLAYBACK_QUALITY.save("auto");
        SettingsStatus.sdrPlaybackEnabled = false;
        SdrPlayback.resetForTests();
    }

    @Test public void offLeavesTheListAndTikTokAnswerAlone() {
        Settings.PLAY_SDR.save(false);
        List<Gear> gears = List.of(new Gear("1080p", 900, 1), new Gear("1080p", 600, 0));
        assertSame(gears, SdrPlayback.filterPlayerUrlModelGears(gears));
        assertSame(gears, SdrPlayback.filterPlayerVideoGears(gears));
        assertFalse(SdrPlayback.forceHdrOff(false));
        assertTrue(SdrPlayback.forceHdrOff(true));
    }

    @Test public void onKeepsOnlyTheSdrGears() {
        Settings.PLAY_SDR.save(true);
        Gear hdr10 = new Gear("1080p", 900, 1);
        Gear hlg = new Gear("720p", 700, 2);
        Gear sdr1080 = new Gear("1080p", 600, 0);
        Gear sdr540 = new Gear("540p", 300, 0);
        List<Gear> gears = List.of(hdr10, sdr1080, hlg, sdr540);
        assertEquals(List.of(sdr1080, sdr540), SdrPlayback.filterPlayerUrlModelGears(gears));
        assertEquals(List.of(sdr1080, sdr540), SdrPlayback.filterPlayerVideoGears(gears));
        assertEquals("the hooked list is never changed in place", 4, gears.size());
        assertTrue(SdrPlayback.forceHdrOff(false));
    }

    @Test public void aVideoOnlyOfferedInHdrStillPlays() {
        Settings.PLAY_SDR.save(true);
        List<Gear> onlyHdr = List.of(new Gear("1080p", 900, 1), new Gear("720p", 700, 2));
        assertSame(onlyHdr, SdrPlayback.filterPlayerUrlModelGears(onlyHdr));
        List<Gear> one = List.of(new Gear("1080p", 900, 1));
        assertSame(one, SdrPlayback.filterPlayerUrlModelGears(one));
        assertNull(SdrPlayback.filterPlayerUrlModelGears(null));
        List<Gear> allSdr = List.of(new Gear("1080p", 600, 0), new Gear("540p", 300, 0));
        assertSame(allSdr, SdrPlayback.filterPlayerUrlModelGears(allSdr));
    }

    @Test public void aGearWithoutAReadableTypeCountsAsSdr() {
        Settings.PLAY_SDR.save(true);
        List<Object> gears = List.of(new Object(), "gear");
        assertSame(gears, SdrPlayback.filterPlayerUrlModelGears(gears));
    }

    @Test public void playbackQualityChoosesAmongSdrGearsOnly() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Gear hdr = new Gear("1080p", 900, 1);
        Gear sdr1080 = new Gear("1080p", 600, 0);
        Gear sdr540 = new Gear("540p", 300, 0);
        List<Gear> gears = List.of(hdr, sdr1080, sdr540);
        Settings.PLAYBACK_QUALITY.save("highest");
        Settings.PLAY_SDR.save(false);
        assertEquals(List.of(hdr), PlaybackQuality.filterPlayerUrlModelGears(gears));
        Settings.PLAY_SDR.save(true);
        assertEquals(List.of(sdr1080), PlaybackQuality.filterPlayerUrlModelGears(gears));
        // Run in the other order, the SDR hook leaves the single SDR gear quality picked alone.
        assertEquals(List.of(sdr1080),
                SdrPlayback.filterPlayerUrlModelGears(PlaybackQuality.filterPlayerUrlModelGears(gears)));
        assertEquals(List.of(sdr1080),
                PlaybackQuality.filterPlayerUrlModelGears(SdrPlayback.filterPlayerUrlModelGears(gears)));
    }

    @Test public void theSwitchIsOnThePlaybackPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.sdrPlaybackEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.PLAY_SDR.key));

            SettingsStatus.sdrPlaybackEnabled = true;
            assertTrue(PlaybackPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.PLAY_SDR.key));
        }
    }
}
