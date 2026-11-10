/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;

import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/** The video's length under a profile grid cell's view count (#181). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GridVideoLengthTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** 2025-10-07 15:04:05 UTC. */
    private static final long POSTED = 1_759_849_445L;

    /** TikTok's Video model, read by its duration getter in milliseconds. */
    public static final class Clip {
        final Object duration;
        Clip(Object duration) { this.duration = duration; }
        public Object getDuration() { return duration; }
    }

    /** A grid cell's item: a video, or a photo post when it carries images. */
    public static final class Cell extends Aweme {
        final Clip video;
        final List<Object> images;

        Cell(Clip video, List<Object> images) {
            this.video = video;
            this.images = images;
        }

        static Cell video(Object durationMs) { return new Cell(new Clip(durationMs), null); }

        public Object getVideo() { return video; }
        @Override public List getImageInfos() { return images; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        @Override public long getCreateTime() { return POSTED; }
        @Override public String getAid() { return "1"; }
    }

    private Locale locale;
    private TimeZone zone;
    private boolean publishDateEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
        zone = TimeZone.getDefault();
        publishDateEnabled = SettingsStatus.alwaysShowPublishDateEnabled;
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SettingsStatus.alwaysShowPublishDateEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        Locale.setDefault(locale);
        TimeZone.setDefault(zone);
        SettingsStatus.alwaysShowPublishDateEnabled = publishDateEnabled;
        Settings.VIDEO_LENGTH_ON_GRID.resetToDefault();
        Settings.PUBLISH_DATE_ON_GRID.resetToDefault();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    @Test public void lengthsReadLikeAPlayerClock() {
        assertEquals("0:01", GridVideoLength.format(400));
        assertEquals("0:42", GridVideoLength.format(41_600));
        assertEquals("1:00", GridVideoLength.format(59_700));
        assertEquals("12:05", GridVideoLength.format(725_000));
        assertEquals("1:02:33", GridVideoLength.format(3_753_000));
    }

    @Test public void theSwitchPutsTheLengthUnderTheCount() {
        assertNull("off by default", GridVideoLength.gridLength(Cell.video(42_000)));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", Cell.video(42_000)));

        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        // TikTok's getter returns an int; a long reads the same.
        assertEquals("12.3K\n0:42", ProfileGridCount.text("12.3K", Cell.video(42_000)));
        assertEquals("12.3K\n0:07", ProfileGridCount.text("12.3K", Cell.video(7_000L)));
    }

    @Test public void theLengthSharesTheDatesLine() {
        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        Settings.PUBLISH_DATE_ON_GRID.save(true);
        Cell cell = Cell.video(42_000);
        String date = AlwaysShowPublishDatePatch.gridDate(cell);
        assertEquals("12.3K\n" + date + " · 0:42", ProfileGridCount.text("12.3K", cell));

        Settings.VIDEO_LENGTH_ON_GRID.save(false);
        assertEquals("12.3K\n" + date, ProfileGridCount.text("12.3K", cell));
    }

    @Test public void nothingIsMadeUpWhereTikTokSentNoLength() {
        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        assertNull("a photo post's length is its sound's",
                GridVideoLength.gridLength(new Cell(new Clip(15_000), List.of(new Object()))));
        assertNull(GridVideoLength.gridLength(Cell.video(0)));
        assertNull(GridVideoLength.gridLength(Cell.video(-5)));
        assertNull(GridVideoLength.gridLength(Cell.video(null)));
        assertNull(GridVideoLength.gridLength(new Cell(null, null)));
        assertNull(GridVideoLength.gridLength("not a video"));
        assertNull(GridVideoLength.gridLength(null));
        assertEquals("12.3K", ProfileGridCount.text("12.3K", Cell.video(0)));
    }

    @Test public void pauseAndAMissingPatchLeaveTheCountAlone() {
        Settings.VIDEO_LENGTH_ON_GRID.save(true);
        setPaused(true);
        assertEquals("12.3K", ProfileGridCount.text("12.3K", Cell.video(42_000)));
        setPaused(false);
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        assertEquals("12.3K", ProfileGridCount.text("12.3K", Cell.video(42_000)));
    }
}
