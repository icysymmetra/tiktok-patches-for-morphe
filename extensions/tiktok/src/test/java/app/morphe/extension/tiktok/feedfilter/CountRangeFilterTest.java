/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Comments, favourites and shares sit on the same statistics model as views and likes, and
 * their ranges behave the same way: a range that was never set filters nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CountRangeFilterTest {
    private static final String UNSET = "0-" + Long.MAX_VALUE;

    /** The stub throws for every getter, so a usable video answers all of them. */
    private static Aweme video(long comments, long favourites, long shares) {
        AwemeStatistics statistics = new AwemeStatistics() {
            @Override
            public long getCommentCount() {
                return comments;
            }

            @Override
            public long getCollectCount() {
                return favourites;
            }

            @Override
            public long getShareCount() {
                return shares;
            }
        };
        return new Aweme() {
            @Override
            public AwemeStatistics getStatistics() {
                return statistics;
            }
        };
    }

    private static Aweme videoWithoutStatistics() {
        return new Aweme() {
            @Override
            public AwemeStatistics getStatistics() {
                return null;
            }
        };
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        for (StringSetting range : new StringSetting[]{
                Settings.MIN_MAX_COMMENTS, Settings.MIN_MAX_FAVOURITES, Settings.MIN_MAX_SHARES}) {
            range.save(UNSET);
        }
    }

    @Test
    public void aRangeThatWasNeverSetFiltersNothing() {
        assertFalse(new CommentCountFilter().getEnabled());
        assertFalse(new FavouriteCountFilter().getEnabled());
        assertFalse(new ShareCountFilter().getEnabled());
    }

    @Test
    public void eachRangeReadsItsOwnCount() {
        Settings.MIN_MAX_COMMENTS.save("100-500");
        Settings.MIN_MAX_FAVOURITES.save("10-50");
        Settings.MIN_MAX_SHARES.save("1-5");

        CommentCountFilter comments = new CommentCountFilter();
        FavouriteCountFilter favourites = new FavouriteCountFilter();
        ShareCountFilter shares = new ShareCountFilter();
        assertTrue(comments.getEnabled());
        assertTrue(favourites.getEnabled());
        assertTrue(shares.getEnabled());

        // Inside every range.
        Aweme inside = video(300, 30, 3);
        assertFalse(comments.getFiltered(inside));
        assertFalse(favourites.getFiltered(inside));
        assertFalse(shares.getFiltered(inside));

        // Each filter answers for its own count and ignores the other two.
        assertTrue(comments.getFiltered(video(99, 30, 3)));
        assertFalse(favourites.getFiltered(video(99, 30, 3)));

        assertTrue(favourites.getFiltered(video(300, 51, 3)));
        assertFalse(comments.getFiltered(video(300, 51, 3)));

        assertTrue(shares.getFiltered(video(300, 30, 0)));
        assertFalse(comments.getFiltered(video(300, 30, 0)));
    }

    @Test
    public void bothEndsOfARangeAreInclusive() {
        Settings.MIN_MAX_COMMENTS.save("100-500");
        CommentCountFilter comments = new CommentCountFilter();

        assertFalse(comments.getFiltered(video(100, 0, 0)));
        assertFalse(comments.getFiltered(video(500, 0, 0)));
        assertTrue(comments.getFiltered(video(501, 0, 0)));
    }

    @Test
    public void aVideoWithNoStatisticsIsKept() {
        Settings.MIN_MAX_COMMENTS.save("100-500");
        Settings.MIN_MAX_FAVOURITES.save("100-500");
        Settings.MIN_MAX_SHARES.save("100-500");

        assertFalse(new CommentCountFilter().getFiltered(videoWithoutStatistics()));
        assertFalse(new FavouriteCountFilter().getFiltered(videoWithoutStatistics()));
        assertFalse(new ShareCountFilter().getFiltered(videoWithoutStatistics()));
    }

    @Test
    public void aRangeIsReadOnceWhenTheFilterIsBuilt() {
        // The filters are built when the feed set is assembled, which is why the settings
        // are marked as needing a restart.
        Settings.MIN_MAX_SHARES.save("1-5");
        ShareCountFilter shares = new ShareCountFilter();
        assertEquals(1L, shares.min);
        assertEquals(5L, shares.max);
    }

    @Test
    public void aRangeWithSpacesIsReadNotResetToAny() {
        // The settings row trims each number when it shows a stored range, so a restored
        // "10 - 500" read there as ten to five hundred while the filter read it as unreadable and
        // saved "any" over it.
        Settings.MIN_MAX_COMMENTS.save(" 10 - 500 ");
        long[] range = app.morphe.extension.tiktok.Utils.parseMinMax(Settings.MIN_MAX_COMMENTS);
        assertEquals(10L, range[0]);
        assertEquals(500L, range[1]);
        assertEquals("the filter wrote over a range it could read",
                " 10 - 500 ", Settings.MIN_MAX_COMMENTS.get());

        // One it can't read still becomes "any", as before.
        Settings.MIN_MAX_COMMENTS.save("ten-500");
        assertEquals(Long.MAX_VALUE, app.morphe.extension.tiktok.Utils.parseMinMax(Settings.MIN_MAX_COMMENTS)[1]);
        assertEquals(UNSET, Settings.MIN_MAX_COMMENTS.get());
    }
}
