package app.morphe.extension.tiktok.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The offline video limit and the three numbers TikTok's own screen reads from it.
 *
 * <p>The class arithmetic had no test of its own: a stored value outside the range, the option
 * list TikTok offers, and the minutes and megabytes shown beside it are all worked out here.
 *
 * <p>The range is guarded twice over, by IntegerSetting.withRange on the setting and again by
 * this class's own clamp, and either one alone holds these cases up. That is worth knowing when
 * reading a failure here: it takes losing both for an out of range limit to reach TikTok.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CustomOfflineVideosLimitPatchTest {
    private static final List<Integer> STOCK = Arrays.asList(10, 20, 50);

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.CUSTOM_OFFLINE_VIDEOS.save(true);
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.resetToDefault();
    }

    @Test
    public void aStoredValueOutsideTheRangeIsPulledToTheNearestEnd() {
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(0);
        assertEquals(CustomOfflineVideosLimitPatch.MIN_LIMIT,
                CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimit());

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(1);
        assertEquals(1, CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimit());

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(5000);
        assertEquals("past TikTok's old 1,000", 5000, CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimit());

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(10_000);
        assertEquals(10_000, CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimit());

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(10_001);
        assertEquals(CustomOfflineVideosLimitPatch.MAX_LIMIT,
                CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimit());

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(-40);
        assertEquals(CustomOfflineVideosLimitPatch.MIN_LIMIT,
                CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimit());
    }

    @Test
    public void theChosenLimitIsAddedToTikTokListOnlyWhenItIsNotAlreadyThere() {
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(75);
        assertEquals(Arrays.asList(10, 20, 50, 75),
                CustomOfflineVideosLimitPatch.getOfflineVideoOptions(STOCK));

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(20);
        assertSame("a limit TikTok already offers should leave the list alone",
                STOCK, CustomOfflineVideosLimitPatch.getOfflineVideoOptions(STOCK));

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(5000);
        assertEquals(Arrays.asList(10, 20, 50, 5000),
                CustomOfflineVideosLimitPatch.getOfflineVideoOptions(STOCK));

        // The clamp applies here too: 20,000 is offered as 10,000.
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(20_000);
        assertEquals(Arrays.asList(10, 20, 50, 10_000),
                CustomOfflineVideosLimitPatch.getOfflineVideoOptions(STOCK));
    }

    @Test
    public void withTheSwitchOffEverythingTikTokPassesInComesBack() {
        Settings.CUSTOM_OFFLINE_VIDEOS.save(false);
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(750);

        assertSame(STOCK, CustomOfflineVideosLimitPatch.getOfflineVideoOptions(STOCK));
        assertEquals(30, CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimitOrOriginal(30));
        assertEquals(18, CustomOfflineVideosLimitPatch.getCustomOfflineVideoMinutesOrOriginal(18));
        assertEquals(60, CustomOfflineVideosLimitPatch.getCustomOfflineVideoSizeMbOrOriginal(60));
        assertNull(CustomOfflineVideosLimitPatch.getOfflineVideoOptions(null));
    }

    @Test
    public void theMinutesAndMegabytesFollowTheLimit() {
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(500);
        assertEquals(500, CustomOfflineVideosLimitPatch.getCustomOfflineVideoLimitOrOriginal(30));
        assertEquals(300, CustomOfflineVideosLimitPatch.getCustomOfflineVideoMinutesOrOriginal(18));
        assertEquals(1000, CustomOfflineVideosLimitPatch.getCustomOfflineVideoSizeMbOrOriginal(60));

        // 0.6 minutes a video rounds up rather than down, so one video is never "0 minutes".
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(1);
        assertEquals(1, CustomOfflineVideosLimitPatch.getCustomOfflineVideoMinutesOrOriginal(18));
        assertEquals(2, CustomOfflineVideosLimitPatch.getCustomOfflineVideoSizeMbOrOriginal(60));

        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(2);
        assertEquals(2, CustomOfflineVideosLimitPatch.getCustomOfflineVideoMinutesOrOriginal(18));
    }

    @Test
    public void theStorageEstimateIsTheSizeTikTokIsToldInBytes() {
        assertEquals(2_000_000L, CustomOfflineVideosLimitPatch.storageBytes(1));
        assertEquals("20 GB at the most, not the 21 a 1024-based megabyte makes it",
                20_000_000_000L, CustomOfflineVideosLimitPatch.storageBytes(10_000));
        assertEquals("clamped like the limit", CustomOfflineVideosLimitPatch.storageBytes(10_000),
                CustomOfflineVideosLimitPatch.storageBytes(50_000));
        Settings.CUSTOM_OFFLINE_VIDEO_LIMIT.save(5000);
        assertEquals(CustomOfflineVideosLimitPatch.getCustomOfflineVideoSizeMbOrOriginal(60) * 1_000_000L,
                CustomOfflineVideosLimitPatch.storageBytes(5000));
    }
}
