/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.banner.BannerCommonKey;
import com.ss.android.ugc.aweme.feed.model.banner.BannerCommonStruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #6 as its reporter still saw it on 0.40.0: the "Ask · topic" bar under a For You video is
 * a common bottom banner keyed {@code bottom_banner_tako}, not one of the Tako components.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TakoAskBannerTest {
    /** A video whose banner list can be read back after the filter replaced it. */
    static final class Video extends Aweme {
        List banners;
        int replaced;

        @Override public List getBanners() { return banners; }
        @Override public void setBanners(List banners) { this.banners = banners; replaced++; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        TakoAiFilter.resetBannerKindsForTests();
        LogBufferManager.clearLogBuffer();
        FeedFilterCounters.clear();
        Settings.HIDE_TAKO_AI.save(false);
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(false);
    }

    @After public void tearDown() {
        Settings.HIDE_TAKO_AI.resetToDefault();
        Settings.HIDE_BOTTOM_SEARCH_BAR.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        HookStatus.clear();
        TakoAiFilter.resetBannerKindsForTests();
        LogBufferManager.clearLogBuffer();
        FeedFilterCounters.clear();
    }

    private static BannerCommonStruct banner(String componentKey) {
        BannerCommonStruct banner = new BannerCommonStruct();
        banner.bannerKey = new BannerCommonKey();
        banner.bannerKey.componentKey = componentKey;
        return banner;
    }

    @Test public void theAskBannerIsTakenOutAndTheOthersStay() {
        Settings.HIDE_TAKO_AI.save(true);
        BannerCommonStruct search = banner("bottom_banner_search_rs");
        BannerCommonStruct ask = banner("bottom_banner_tako");
        BannerCommonStruct drama = banner("bottom_banner_series_mini_drama");
        List<Object> original = new ArrayList<>(Arrays.asList(search, ask, drama));
        Video video = new Video();
        video.banners = original;

        List<?> answer = TakoAiFilter.filterBanners(video, original);

        assertEquals(Arrays.asList(search, drama), answer);
        assertSame("the video kept the list with the Ask banner in it", answer, video.banners);
        assertEquals(Arrays.asList(search, ask, drama), original);

        // The next read gets the replaced list and finds nothing to do.
        assertSame(answer, TakoAiFilter.filterBanners(video, video.banners));
        assertEquals(1, video.replaced);
    }

    @Test public void withTheSwitchOffTheAskBannerIsLeft() {
        List<Object> original = new ArrayList<>(Arrays.asList(banner("bottom_banner_tako")));
        Video video = new Video();
        video.banners = original;

        assertSame(original, TakoAiFilter.filterBanners(video, original));
        assertEquals(0, video.replaced);
        assertEquals(1, original.size());
    }

    @Test public void aVideoWithoutAnAskBannerIsNotTouched() {
        Settings.HIDE_TAKO_AI.save(true);
        List<Object> original = new ArrayList<>(Arrays.asList(banner("bottom_banner_search_rs"),
                new Object(), new BannerCommonStruct()));
        Video video = new Video();
        video.banners = original;

        assertSame(original, TakoAiFilter.filterBanners(video, original));
        assertEquals(0, video.replaced);
        assertNull(TakoAiFilter.filterBanners(video, null));
    }

    @Test public void aMissingVideoStillGetsTheFilteredAnswer() {
        Settings.HIDE_TAKO_AI.save(true);
        List<Object> original = new ArrayList<>(Arrays.asList(banner("bottom_banner_tako")));

        assertTrue(TakoAiFilter.filterBanners(null, original).isEmpty());
    }

    @Test public void theExportNamesTheBannerHookWheneverItRuns() {
        TakoAiFilter.filterBanners(new Video(), new ArrayList<>());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("tako AI"));
    }

    @Test public void aBannerKindMetWithLoggingOffIsNamedOnceLoggingIsOn() {
        List<Object> banners = new ArrayList<>(Arrays.asList(banner("bottom_banner_playlist")));
        TakoAiFilter.filterBanners(new Video(), banners);
        assertFalse(LogBufferManager.buildExportText().contains("Bottom banner served"));

        BaseSettings.DEBUG.save(true);
        TakoAiFilter.filterBanners(new Video(), banners);
        TakoAiFilter.filterBanners(new Video(), banners);
        String report = LogBufferManager.buildExportText();
        String named = "Bottom banner served: bottom_banner_playlist";
        assertEquals(report, 1, report.split(Pattern.quote(named), -1).length - 1);
    }

    @Test public void theExportCountsEachBannerKindAndWhatTheSwitchesTookOut() {
        Settings.HIDE_TAKO_AI.save(true);
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        BannerCommonStruct drama = banner("bottom_banner_series_mini_drama");
        Video video = new Video();
        video.banners = new ArrayList<>(Arrays.asList(banner("bottom_banner_search_rs"),
                banner("bottom_banner_tako"), drama, banner("Find an ice cream maker")));

        TakoAiFilter.filterBanners(video, video.banners);
        assertEquals(2, video.banners.size());
        assertSame(drama, video.banners.get(0));
        String first = String.join("|", FeedFilterCounters.report());
        assertTrue(first, first.contains("BottomBanner: 1 lists, 4 items, 2 removed. Last reason: searchBanner. "));
        TakoAiFilter.filterBanners(video, video.banners);
        TakoAiFilter.filterBanners(video, new ArrayList<>());
        TakoAiFilter.filterBanners(new Video(), new ArrayList<>(Arrays.asList(banner("bottom_banner_tako"))));

        String report = String.join("|", FeedFilterCounters.report());
        assertTrue(report, report.contains("BottomBanner: 4 lists, 7 items, 3 removed. Last reason: takoAskBanner. "
                + "Kinds: bottom_banner_series_mini_drama 2, bottom_banner_tako 2, bottom_banner_search_rs 1"));
        assertFalse(report, report.contains("ice cream"));
        assertFalse(LogBufferManager.buildExportText().contains("Bottom banner served"));
    }
}
