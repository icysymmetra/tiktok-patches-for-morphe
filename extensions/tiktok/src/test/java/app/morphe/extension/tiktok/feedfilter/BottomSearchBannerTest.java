package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.banner.BannerCommonKey;
import com.ss.android.ugc.aweme.feed.model.banner.BannerCommonStruct;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35})
public class BottomSearchBannerTest {
    static class Video extends Aweme {
        List banners;
        int replacements;
        @Override public List getBanners() { return banners; }
        @Override public void setBanners(List value) { banners = value; replacements++; }
    }

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(false);
        Settings.HIDE_TAKO_AI.save(false);
        HookStatus.clear();
    }

    @After public void cleanup() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.resetToDefault();
        Settings.HIDE_TAKO_AI.resetToDefault();
        HookStatus.clear();
    }

    private static BannerCommonStruct banner(String key) {
        BannerCommonStruct value = new BannerCommonStruct();
        value.bannerKey = new BannerCommonKey();
        value.bannerKey.componentKey = key;
        return value;
    }

    @Test public void offLeavesTheOriginalListAndVideoUntouched() {
        List<?> original = Collections.singletonList(banner("bottom_banner_search_rs"));
        Video video = new Video();
        video.banners = original;
        assertSame(original, TakoAiFilter.filterBanners(video, original));
        assertSame(original, video.banners);
        assertEquals(0, video.replacements);
    }

    @Test public void searchOnlyDropsItsExactKeyAndPreservesOtherBannersAndOriginalOrder() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        Object ask = banner("bottom_banner_tako"), other = banner("bottom_banner_series_mini_drama");
        List<?> original = Collections.unmodifiableList(Arrays.asList(ask, banner("bottom_banner_search_rs"),
                null, other, banner("bottom_banner_search_rs")));
        Video video = new Video();
        video.banners = original;
        List<?> kept = TakoAiFilter.filterBanners(video, original);
        assertEquals(Arrays.asList(ask, null, other), kept);
        assertEquals(5, original.size());
        assertSame(kept, video.banners);
        assertSame(kept, TakoAiFilter.filterBanners(video, kept));
        assertEquals(1, video.replacements);
    }

    @Test public void bothSwitchesRemoveOnlyTheirOwnBannerKinds() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        Settings.HIDE_TAKO_AI.save(true);
        Object other = banner("bottom_banner_unknown");
        List<?> original = Arrays.asList(banner("bottom_banner_search_rs"), other, banner("bottom_banner_tako"));
        assertEquals(Collections.singletonList(other), TakoAiFilter.filterBanners(new Video(), original));
        assertEquals(3, original.size());
    }

    @Test public void unknownMalformedAndSimilarKeysFailOpen() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        List<?> original = Arrays.asList(null, new Object(), new BannerCommonStruct(), banner(null),
                banner("bottom_banner_search"), banner("BOTTOM_BANNER_SEARCH_RS"), banner("bottom_banner_search_rs_extra"));
        Video video = new Video();
        assertSame(original, TakoAiFilter.filterBanners(video, original));
        assertEquals(0, video.replacements);
        assertNull(TakoAiFilter.filterBanners(video, null));
    }

    @Test public void lastSearchBannerBecomesAnEmptyListForNativeLayoutNotAnInvisibleSlot() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        Video video = new Video();
        video.banners = Collections.singletonList(banner("bottom_banner_search_rs"));
        assertTrue(TakoAiFilter.filterBanners(video, video.banners).isEmpty());
        assertTrue(video.banners.isEmpty());
        assertTrue(TakoAiFilter.filterBanners(null,
                Collections.singletonList(banner("bottom_banner_search_rs"))).isEmpty());
    }

    @Test public void setterFailureReturnsTheOriginalListWithoutMutatingIt() {
        Settings.HIDE_BOTTOM_SEARCH_BAR.save(true);
        Video video = new Video() {
            @Override public void setBanners(List value) { throw new IllegalStateException("model changed"); }
        };
        List<?> original = Collections.singletonList(banner("bottom_banner_search_rs"));
        assertSame(original, TakoAiFilter.filterBanners(video, original));
        assertEquals(1, original.size());
    }
}
