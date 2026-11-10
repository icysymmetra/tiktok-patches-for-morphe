package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
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

/** Prefer H.264 video: which gears reach the player, how it sits with SDR and quality, and where the switch lives. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class H264PlaybackTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    public static final class Address extends UrlModel {
        private final List<String> urls;
        Address(String... urls) { this.urls = List.of(urls); }
        @Override public List<String> getUrlList() { return urls; }
    }

    /** SimBitRate's shape: codecType 0 is H.264, 1 ByteVC1 (HEVC), 2 ByteVC2; hdrType 1 and 2 are HDR. */
    public static final class Gear {
        public final String gearName;
        public final int bitRate;
        public final Object playAddr;
        private final int codecType;
        private final int hdrType;
        Gear(String name, int rate, int codecType) { this(name, rate, codecType, 0); }
        Gear(String name, int rate, int codecType, int hdrType) {
            this(name, rate, codecType, hdrType,
                    new Address("https://example.com/" + name + "-" + codecType + "-" + hdrType));
        }
        Gear(String name, int rate, int codecType, int hdrType, Object playAddr) {
            gearName = name;
            bitRate = rate;
            this.playAddr = playAddr;
            this.codecType = codecType;
            this.hdrType = hdrType;
        }
        public int getCodecType() { return codecType; }
        public int getHdrType() { return hdrType; }
        @Override public String toString() { return gearName + "/" + codecType + "/" + hdrType; }
    }

    @org.junit.Before public void patched() {
        SettingsStatus.h264PlaybackEnabled = true;
    }

    @After public void tearDown() {
        Settings.PREFER_H264.save(false);
        Settings.PLAY_SDR.save(false);
        Settings.PLAYBACK_QUALITY.save("auto");
        SettingsStatus.h264PlaybackEnabled = false;
        H264Playback.resetForTests();
        SdrPlayback.resetForTests();
        PlaybackQuality.resetForTests();
    }

    @Test public void offByDefaultAndOffLeavesTheListAlone() {
        assertEquals(Boolean.FALSE, Settings.PREFER_H264.defaultValue);
        assertEquals("prefer_h264", Settings.PREFER_H264.key);
        List<Gear> gears = List.of(new Gear("1080p", 900, 1), new Gear("720p", 600, 0));
        assertSame(gears, H264Playback.filterPlayerUrlModelGears(gears));
        assertSame(gears, H264Playback.filterPlayerVideoGears(gears));
    }

    @Test public void onKeepsOnlyTheH264Gears() {
        Settings.PREFER_H264.save(true);
        Gear hevc = new Gear("1080p", 900, 1);
        Gear h264720 = new Gear("720p", 600, 0);
        Gear vc2 = new Gear("1080p", 500, 2);
        Gear h264540 = new Gear("540p", 300, 0);
        List<Gear> gears = List.of(hevc, h264720, vc2, h264540);
        assertEquals(List.of(h264720, h264540), H264Playback.filterPlayerUrlModelGears(gears));
        assertEquals(List.of(h264720, h264540), H264Playback.filterPlayerVideoGears(gears));
        assertEquals("the hooked list is never changed in place", 4, gears.size());
    }

    /** A switch saved on by an earlier build keeps its value after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.PREFER_H264.save(true);
        SettingsStatus.h264PlaybackEnabled = false;
        List<Gear> gears = List.of(new Gear("1080p", 900, 1), new Gear("720p", 600, 0));
        assertSame(gears, H264Playback.filterPlayerUrlModelGears(gears));
        assertSame(gears, H264Playback.filterPlayerVideoGears(gears));
        Settings.PLAY_SDR.save(true);
        assertSame(gears, SdrPlayback.filterPlayerUrlModelGears(gears));
    }

    @Test public void aVideoWithoutAnH264VersionPlaysAsBefore() {
        Settings.PREFER_H264.save(true);
        List<Gear> noH264 = List.of(new Gear("1080p", 900, 1), new Gear("720p", 700, 2));
        assertSame(noH264, H264Playback.filterPlayerUrlModelGears(noH264));
        List<Gear> one = List.of(new Gear("1080p", 900, 1));
        assertSame(one, H264Playback.filterPlayerUrlModelGears(one));
        assertNull(H264Playback.filterPlayerUrlModelGears(null));
        List<Gear> allH264 = List.of(new Gear("1080p", 600, 0), new Gear("540p", 300, 0));
        assertSame(allH264, H264Playback.filterPlayerUrlModelGears(allH264));
    }

    @Test public void aGearWithoutAReadableCodecIsNotH264() {
        Settings.PREFER_H264.save(true);
        List<Object> unreadable = List.of(new Object(), "gear");
        assertSame(unreadable, H264Playback.filterPlayerUrlModelGears(unreadable));
        Gear h264 = new Gear("720p", 600, 0);
        assertEquals(List.of(h264), H264Playback.filterPlayerUrlModelGears(List.of(new Object(), h264)));
    }

    @Test public void theSdrAndH264HooksAgreeInEitherOrder() {
        Settings.PREFER_H264.save(true);
        Settings.PLAY_SDR.save(true);
        Gear hdrHevc = new Gear("1080p", 900, 1, 1);
        Gear sdrHevc = new Gear("1080p", 700, 1, 0);
        Gear hdrH264 = new Gear("720p", 650, 0, 2);
        Gear sdrH264 = new Gear("720p", 500, 0, 0);
        List<Gear> gears = List.of(hdrHevc, sdrHevc, hdrH264, sdrH264);
        assertEquals(List.of(sdrH264), H264Playback.filterPlayerUrlModelGears(gears));
        assertEquals(List.of(sdrH264), SdrPlayback.filterPlayerUrlModelGears(gears));
        assertEquals(List.of(sdrH264),
                SdrPlayback.filterPlayerUrlModelGears(H264Playback.filterPlayerUrlModelGears(gears)));
        assertEquals(List.of(sdrH264),
                H264Playback.filterPlayerUrlModelGears(SdrPlayback.filterPlayerUrlModelGears(gears)));

        // H.264 comes first: an H.264 video only offered in HDR beats an HEVC one in SDR, and
        // both hooks say so whichever runs first.
        List<Gear> split = List.of(hdrH264, sdrHevc);
        assertEquals(List.of(hdrH264), H264Playback.filterPlayerVideoGears(split));
        assertEquals(List.of(hdrH264), SdrPlayback.filterPlayerVideoGears(split));
        assertEquals(List.of(hdrH264),
                SdrPlayback.filterPlayerVideoGears(H264Playback.filterPlayerVideoGears(split)));
        assertEquals(List.of(hdrH264),
                H264Playback.filterPlayerVideoGears(SdrPlayback.filterPlayerVideoGears(split)));
    }

    @Test public void playbackQualityChoosesAmongH264GearsOnly() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Gear hevc1080 = new Gear("1080p", 900, 1);
        Gear h264720 = new Gear("720p", 600, 0);
        Gear h264540 = new Gear("540p", 300, 0);
        List<Gear> gears = List.of(hevc1080, h264720, h264540);
        Settings.PLAYBACK_QUALITY.save("highest");
        assertEquals(List.of(hevc1080), PlaybackQuality.filterPlayerUrlModelGears(gears));
        Settings.PREFER_H264.save(true);
        assertEquals(List.of(h264720), PlaybackQuality.filterPlayerUrlModelGears(gears));
        assertEquals(List.of(h264720),
                H264Playback.filterPlayerUrlModelGears(PlaybackQuality.filterPlayerUrlModelGears(gears)));
        assertEquals(List.of(h264720),
                PlaybackQuality.filterPlayerUrlModelGears(H264Playback.filterPlayerUrlModelGears(gears)));
        Settings.PLAYBACK_QUALITY.save("lowest");
        assertEquals(List.of(h264540), PlaybackQuality.filterPlayerVideoGears(gears));
    }

    /** Play addresses the player has nothing to open from: none, no URL, not a URL list, a URL no gear is chosen by. */
    private static List<Object> deadAddresses() {
        java.util.ArrayList<Object> dead = new java.util.ArrayList<>();
        dead.add(null);
        dead.add(new Address());
        dead.add(new Object());
        dead.add(new Address("file:///sdcard/clip.mp4"));
        return dead;
    }

    /** What reaches the player through every setter hook in every order, Playback quality on Auto. */
    private static void assertPlayerGets(List<?> expected, List<?> gears) {
        for (List<?> result : List.of(
                H264Playback.filterPlayerUrlModelGears(gears),
                H264Playback.filterPlayerVideoGears(gears),
                SdrPlayback.filterPlayerUrlModelGears(gears),
                SdrPlayback.filterPlayerVideoGears(gears),
                SdrPlayback.filterPlayerUrlModelGears(H264Playback.filterPlayerUrlModelGears(gears)),
                H264Playback.filterPlayerUrlModelGears(SdrPlayback.filterPlayerUrlModelGears(gears)),
                PlaybackQuality.filterPlayerUrlModelGears(H264Playback.filterPlayerUrlModelGears(gears)))) {
            assertEquals(expected, result);
            assertTrue("a preference emptied the list", result == null || !result.isEmpty());
        }
    }

    /** What an explicit quality hands the player, with the H.264 and SDR hooks before and after it. */
    private static void assertQualityPicks(Object expected, List<?> gears) {
        List<?> picked = List.of(expected);
        assertEquals(picked, PlaybackQuality.filterPlayerUrlModelGears(gears));
        assertEquals(picked, PlaybackQuality.filterPlayerVideoGears(gears));
        assertEquals(picked, H264Playback.filterPlayerUrlModelGears(PlaybackQuality.filterPlayerUrlModelGears(gears)));
        assertEquals(picked, SdrPlayback.filterPlayerUrlModelGears(PlaybackQuality.filterPlayerUrlModelGears(gears)));
        assertEquals(picked, PlaybackQuality.filterPlayerUrlModelGears(H264Playback.filterPlayerUrlModelGears(gears)));
        assertEquals(picked, PlaybackQuality.filterPlayerUrlModelGears(SdrPlayback.filterPlayerUrlModelGears(gears)));
    }

    @Test public void h264GearsWithNoPlayableAddressLeaveTheHevcOnesThatPlay() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PREFER_H264.save(true);
        for (Object dead : deadAddresses()) {
            Gear h264720 = new Gear("720p", 600, 0, 0, dead);
            Gear h264540 = new Gear("540p", 300, 0, 0, dead);
            Gear hevc1080 = new Gear("1080p", 900, 1);
            Gear hevc720 = new Gear("720p", 500, 1);
            List<Gear> gears = List.of(h264720, hevc1080, h264540, hevc720);
            Settings.PLAYBACK_QUALITY.save("auto");
            assertPlayerGets(gears, gears);
            Settings.PLAYBACK_QUALITY.save("highest");
            assertQualityPicks(hevc1080, gears);
            Settings.PLAYBACK_QUALITY.save("720");
            assertQualityPicks(hevc720, gears);
        }
    }

    @Test public void sdrGearsWithNoPlayableAddressLeaveTheHdrOnesThatPlay() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PLAY_SDR.save(true);
        for (Object dead : deadAddresses()) {
            Gear sdr1080 = new Gear("1080p", 600, 1, 0, dead);
            Gear hdr1080 = new Gear("1080p", 900, 1, 1);
            Gear hlg540 = new Gear("540p", 400, 1, 2);
            List<Gear> gears = List.of(sdr1080, hdr1080, hlg540);
            Settings.PLAYBACK_QUALITY.save("auto");
            assertPlayerGets(gears, gears);
            Settings.PLAYBACK_QUALITY.save("lowest");
            assertQualityPicks(hlg540, gears);
            Settings.PLAYBACK_QUALITY.save("1080");
            assertQualityPicks(hdr1080, gears);
        }
    }

    @Test public void bothPreferencesTogetherKeepWhatPlaysInEitherOrder() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PREFER_H264.save(true);
        Settings.PLAY_SDR.save(true);
        for (Object dead : deadAddresses()) {
            // No H.264 gear plays, so SDR picks among all of them: the dead H.264 one stays with
            // the playable SDR HEVC gear, and the HDR one goes.
            Gear deadH264 = new Gear("720p", 600, 0, 0, dead);
            Gear sdrHevc = new Gear("1080p", 700, 1, 0);
            Gear hdrHevc = new Gear("1080p", 900, 1, 1);
            List<Gear> codecDead = List.of(deadH264, hdrHevc, sdrHevc);
            Settings.PLAYBACK_QUALITY.save("auto");
            assertPlayerGets(List.of(deadH264, sdrHevc), codecDead);
            Settings.PLAYBACK_QUALITY.save("highest");
            assertQualityPicks(sdrHevc, codecDead);

            // H.264 plays only in HDR: H.264 still wins, and among its gears the HDR one stays
            // because the SDR one can't play.
            Gear hdrH264 = new Gear("720p", 650, 0, 2);
            Gear deadSdrH264 = new Gear("720p", 500, 0, 0, dead);
            List<Gear> sdrDead = List.of(sdrHevc, hdrH264, deadSdrH264);
            Settings.PLAYBACK_QUALITY.save("auto");
            assertPlayerGets(List.of(hdrH264, deadSdrH264), sdrDead);
            Settings.PLAYBACK_QUALITY.save("720");
            assertQualityPicks(hdrH264, sdrDead);

            // Neither preference has a gear that plays: the list reaches the player whole.
            Gear deadSdrHevc = new Gear("1080p", 700, 1, 0, dead);
            List<Gear> bothDead = List.of(deadH264, hdrHevc, deadSdrHevc);
            Settings.PLAYBACK_QUALITY.save("auto");
            assertPlayerGets(bothDead, bothDead);
            Settings.PLAYBACK_QUALITY.save("highest");
            assertQualityPicks(hdrHevc, bothDead);
        }
    }

    @Test public void aPlayablePreferredGearStillNarrowsTheList() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PREFER_H264.save(true);
        Settings.PLAY_SDR.save(true);
        Gear h264 = new Gear("540p", 300, 0);
        Gear deadH264 = new Gear("720p", 600, 0, 0, null);
        Gear hevc = new Gear("1080p", 900, 1);
        List<Gear> gears = List.of(hevc, deadH264, h264);
        Settings.PLAYBACK_QUALITY.save("auto");
        assertPlayerGets(List.of(deadH264, h264), gears);
        Settings.PLAYBACK_QUALITY.save("highest");
        assertQualityPicks(h264, gears);
    }

    /** ByteVC2 isn't a file other players open, but TikTok's own player decodes it. */
    @Test public void aPlayableByteVc2GearIsKeptWhenTheH264OnesCantPlay() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PREFER_H264.save(true);
        Gear deadH264 = new Gear("720p", 600, 0, 0, new Address());
        Gear vc2 = new Gear("720p", 500, 2);
        List<Gear> gears = List.of(deadH264, vc2);
        Settings.PLAYBACK_QUALITY.save("auto");
        assertPlayerGets(gears, gears);
        Settings.PLAYBACK_QUALITY.save("720");
        assertQualityPicks(vc2, gears);
    }

    /** No gear has an address that tells which plays: the preferences narrow as they always did, never to nothing. */
    @Test public void aListWithNoPlayableAddressKeepsThePreferredGears() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.PREFER_H264.save(true);
        Settings.PLAY_SDR.save(true);
        Gear h264 = new Gear("720p", 600, 0, 0, null);
        Gear hevc = new Gear("1080p", 900, 1, 0, new Object());
        Gear hdrH264 = new Gear("1080p", 800, 0, 1, new Address());
        List<Gear> gears = List.of(hevc, hdrH264, h264);
        Settings.PLAYBACK_QUALITY.save("auto");
        assertPlayerGets(List.of(h264), gears);
        for (String quality : List.of("highest", "lowest", "720")) {
            Settings.PLAYBACK_QUALITY.save(quality);
            assertEquals(List.of(h264), PlaybackQuality.filterPlayerUrlModelGears(gears));
            assertEquals(List.of(h264), PlaybackQuality.filterPlayerVideoGears(gears));
        }
    }

    @Test public void theSwitchIsOnThePlaybackPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.h264PlaybackEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, without);
            assertNull(without.findPreference(Settings.PREFER_H264.key));

            SettingsStatus.h264PlaybackEnabled = true;
            assertTrue(PlaybackPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new PlaybackPreferenceCategory(activity, with);
            assertNotNull(with.findPreference(Settings.PREFER_H264.key));
        }
    }
}
