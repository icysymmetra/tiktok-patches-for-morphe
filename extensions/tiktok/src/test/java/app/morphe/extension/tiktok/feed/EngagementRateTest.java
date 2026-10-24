package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class EngagementRateTest {
    public static final class Stats extends AwemeStatistics {
        long views, likes, comments, shares, saves;

        Stats(long views, long likes, long comments, long shares, long saves) {
            this.views = views;
            this.likes = likes;
            this.comments = comments;
            this.shares = shares;
            this.saves = saves;
        }

        @Override public long getPlayCount() { return views; }
        @Override public long getDiggCount() { return likes; }
        @Override public long getCommentCount() { return comments; }
        @Override public long getShareCount() { return shares; }
        @Override public long getCollectCount() { return saves; }
    }

    /** An item as the grid and the player hand it over. The region is for the author row. */
    public static final class Item extends Aweme {
        final Stats stats;
        final String region;

        Item(Stats stats, String region) {
            this.stats = stats;
            this.region = region;
        }

        @Override public AwemeStatistics getStatistics() { return stats; }
        public String getRegion() { return region; }
    }

    private Locale locale;
    private boolean engagementEnabled;
    private boolean regionEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        locale = Locale.getDefault();
        engagementEnabled = SettingsStatus.engagementRateEnabled;
        regionEnabled = SettingsStatus.authorRegionEnabled;
        SettingsStatus.engagementRateEnabled = true;
        Locale.setDefault(Locale.US);
    }

    @After public void tearDown() {
        Locale.setDefault(locale);
        SettingsStatus.engagementRateEnabled = engagementEnabled;
        SettingsStatus.authorRegionEnabled = regionEnabled;
        Settings.SHOW_ENGAGEMENT_RATE.resetToDefault();
        Settings.SHOW_AUTHOR_REGION.resetToDefault();
    }

    private static Item item(long views, long likes, long comments, long shares, long saves) {
        return new Item(new Stats(views, likes, comments, shares, saves), null);
    }

    @Test public void theSwitchStartsOffAndLeavesTheGridCountAlone() {
        assertEquals(Boolean.FALSE, Settings.SHOW_ENGAGEMENT_RATE.defaultValue);
        Item video = item(10_000, 300, 50, 40, 30);
        assertEquals("12.3K", EngagementRate.gridCount("12.3K", video));
        assertNull(EngagementRate.forVideo(video));
    }

    @Test public void likesCommentsSharesAndSavesOverViews() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        // (300 + 50 + 40 + 30) / 10,000 = 4.2%
        Item video = item(10_000, 300, 50, 40, 30);
        assertEquals("12.3K · 4.2%", EngagementRate.gridCount("12.3K", video));
        assertEquals("4.2%", EngagementRate.forVideo(video));
        assertEquals("0.0%", EngagementRate.format(1_000_000, 0));
        assertEquals("150.0%", EngagementRate.format(2, 3));
    }

    @Test public void thePercentFollowsThePhonesLocale() {
        Locale.setDefault(Locale.GERMANY);
        assertEquals("4,2 %", EngagementRate.format(10_000, 420));
        Locale.setDefault(new Locale("tr", "TR"));
        assertEquals("%4,2", EngagementRate.format(10_000, 420));
    }

    @Test public void noViewsOrNoCountsShowsNothing() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        assertEquals("0", EngagementRate.gridCount("0", item(0, 5, 0, 0, 0)));
        assertEquals("0", EngagementRate.gridCount("0", item(-1, 5, 0, 0, 0)));
        assertEquals("0", EngagementRate.gridCount("0", new Item(null, null)));
        assertEquals("0", EngagementRate.gridCount("0", "not a video"));
        assertEquals("0", EngagementRate.gridCount("0", null));
        assertNull(EngagementRate.gridCount(null, item(10_000, 300, 50, 40, 30)));
        assertNull(EngagementRate.forVideo(item(0, 5, 0, 0, 0)));
        assertNull(EngagementRate.forVideo(null));
    }

    @Test public void aNegativeCountAddsNothing() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        assertEquals("4.2%", EngagementRate.forVideo(item(10_000, 420, -1, -1, -1)));
    }

    @Test public void withoutThePatchTheSwitchDoesNothing() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        SettingsStatus.engagementRateEnabled = false;
        Item video = item(10_000, 300, 50, 40, 30);
        assertEquals("12.3K", EngagementRate.gridCount("12.3K", video));
        assertNull(EngagementRate.forVideo(video));
    }

    @Test public void theVideosRateMovesWithItsCounts() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        Item video = item(10_000, 300, 50, 40, 30);
        assertEquals("4.2%", EngagementRate.forVideo(video));
        video.stats.likes += 100;
        assertEquals("5.2%", EngagementRate.forVideo(video));
        Locale.setDefault(Locale.GERMANY);
        assertEquals("5,2 %", EngagementRate.forVideo(video));
    }

    @Test public void theRateFollowsTheCountryOnTheCreatorsRow() {
        Settings.SHOW_ENGAGEMENT_RATE.save(true);
        Item video = new Item(new Stats(10_000, 300, 50, 40, 30), "gb");
        SettingsStatus.authorRegionEnabled = false;
        assertArrayEquals(new String[]{null, "4.2%"}, AuthorRegion.decoration(video));
        SettingsStatus.authorRegionEnabled = true;
        Settings.SHOW_AUTHOR_REGION.save(true);
        assertArrayEquals(new String[]{null, "GB · 4.2%"}, AuthorRegion.decoration(video));
        Settings.SHOW_ENGAGEMENT_RATE.save(false);
        assertArrayEquals(new String[]{null, "GB"}, AuthorRegion.decoration(video));
    }
}
