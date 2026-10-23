package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.*;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class AdvancedFeedRulesTest {
    public static final class Video {
        long duration;
        public long getDuration() { return duration; }
    }
    public static final class Author {
        String handle;
        String secUid = "";
        public String getUniqueId() { return handle; }
        public String getUid() { return "123"; }
        public String getSecUid() { return secUid; }
    }
    public static final class Stats extends AwemeStatistics {
        long views, likes;
        public long getPlayCount() { return views; }
        public long getDiggCount() { return likes; }
    }
    public static final class Item extends Aweme {
        final String id;
        String desc = "";
        final Video video = new Video();
        final Author author = new Author();
        final Stats stats = new Stats();
        long createTime;
        PhotoModeImageInfo photos;
        Item(String id, String handle, long ms) {
            this.id = id;
            author.handle = handle;
            video.duration = ms;
        }
        public java.util.List getImageInfos() { return null; }
        public PhotoModeImageInfo getPhotoModeImageInfo() { return photos; }
        public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        public String getAid() { return id; }
        public String getDesc() { return desc; }
        public Object getAuthor() { return author; }
        public Object getVideo() { return video; }
        public AwemeStatistics getStatistics() { return stats; }
        public String getShareUrl() { return null; }
        public long getCreateTime() { return createTime; }
    }
    @Before public void reset() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REMOVE_ADS.save(false);
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.BLOCKED_CREATORS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        Settings.MAX_VIDEO_SECONDS.save(0);
        Settings.MIN_VIDEO_SECONDS.save(0);
        Settings.MAX_PUBLICATION_AGE_DAYS.save(0);
        Settings.MAX_VIEWS_PER_LIKE.save(0);
    }
    private FeedItemList page(Item... items) {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(page);
        return page;
    }
    @Test public void captionAndCreatorMatchingIsCaseInsensitiveButCreatorNamesAreExact() {
        Item item = new Item("a", "Poster", 800);
        item.desc = "A SPONSORED demonstration";
        Settings.BLOCKED_CAPTION_WORDS.save("sponsored, unrelated");
        assertTrue(new AdvancedFeedRules.KeywordFilter().getFiltered(item));
        Settings.BLOCKED_CREATORS.save("@post");
        assertFalse(new AdvancedFeedRules.CreatorFilter().getFiltered(item));
        Settings.BLOCKED_CREATORS.save("@POSTER");
        assertTrue(new AdvancedFeedRules.CreatorFilter().getFiltered(item));
        Settings.BLOCKED_CREATORS.save("123");
        assertTrue(new AdvancedFeedRules.CreatorFilter().getFiltered(item));
    }

    @Test public void aFeedBatchParsesEachChangedRuleStringOnce() {
        AtomicInteger captionParses = new AtomicInteger();
        AtomicInteger creatorParses = new AtomicInteger();
        KeywordRules.setParseTestHookForTests(captionParses::incrementAndGet);
        AdvancedFeedRules.setCreatorParseTestHookForTests(creatorParses::incrementAndGet);
        try {
            Settings.BLOCKED_CAPTION_WORDS.save("cache-caption-a, cache-caption-b");
            Settings.BLOCKED_CREATORS.save("cache-blocked-a, cache-blocked-b");
            Settings.LOCAL_HIDDEN_CREATORS.save("cache-local-a, cache-local-b");

            Item[] first = new Item[20];
            for (int index = 0; index < first.length; index++) {
                first[index] = new Item("cache-first-" + index, "allowed-" + index, 800);
                first[index].desc = "ordinary caption " + index;
            }
            page(first);
            assertEquals("caption rules were rebuilt for items in one batch", 1,
                    captionParses.get());
            assertEquals("creator lists were rebuilt for items in one batch", 2,
                    creatorParses.get());

            Settings.BLOCKED_CAPTION_WORDS.save("cache-caption-c");
            Item captionChange = new Item("cache-caption-change", "allowed-caption", 800);
            captionChange.desc = "ordinary changed caption";
            assertFalse(new AdvancedFeedRules.KeywordFilter().getFiltered(captionChange));
            assertEquals("a changed caption string was not parsed exactly once", 2,
                    captionParses.get());
            assertEquals("caption changes rebuilt creator rules", 2, creatorParses.get());

            Settings.LOCAL_HIDDEN_CREATORS.save("cache-local-c");
            assertFalse(new AdvancedFeedRules.CreatorFilter().getFiltered(
                    new Item("cache-local-change", "allowed-local", 800)));
            assertEquals("an unchanged caption string was parsed again", 2,
                    captionParses.get());
            assertEquals("a local-list change rebuilt both creator strings", 3,
                    creatorParses.get());

            Settings.BLOCKED_CREATORS.save("cache-blocked-c");
            assertFalse(new AdvancedFeedRules.CreatorFilter().getFiltered(
                    new Item("cache-blocked-change", "allowed-blocked", 800)));
            assertEquals("a blocked-list change rebuilt both creator strings", 4,
                    creatorParses.get());
        } finally {
            KeywordRules.setParseTestHookForTests(null);
            AdvancedFeedRules.setCreatorParseTestHookForTests(null);
        }
    }

    @Test public void tenThousandCaptionEntriesMeetTheAndroidBatchBudget() {
        StringBuilder stored = new StringBuilder();
        for (int index = 0; index < FeedRuleLimits.MAX_ENTRIES; index++) {
            if (stored.length() > 0) stored.append(", ");
            stored.append("term").append(index);
        }
        Settings.BLOCKED_CAPTION_WORDS.save(stored.toString());

        Item warm = new Item("benchmark-warm", "allowed-warm", 800);
        warm.desc = "ordinary warm caption";
        page(warm);

        Item[] batch = new Item[20];
        for (int index = 0; index < batch.length; index++) {
            batch[index] = new Item("benchmark-" + index, "allowed-" + index, 800);
            batch[index].desc = "ordinary benchmark caption " + index;
        }
        long started = System.nanoTime();
        page(batch);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        long androidBatchBudgetMs = 250;
        assertTrue("a warm 20-item Android feed batch with 10,000 caption rules took "
                        + elapsedMs + " ms, over the " + androidBatchBudgetMs + " ms budget",
                elapsedMs <= androidBatchBudgetMs);
    }
    @Test public void nearestQualityFallbackCannotRestoreAHardBlockedCreator() {
        Settings.MAX_VIDEO_SECONDS.save(1);
        Settings.BLOCKED_CREATORS.save("blocked");
        Item blocked = new Item("1", "blocked", 1010);
        Item longer = new Item("2", "allowed", 5000);
        Item closest = new Item("3", "allowed", 2000);
        assertEquals(Arrays.asList(closest), page(blocked, longer, closest).items);
        assertTrue(page(blocked).items.isEmpty());
    }

    @Test public void localCreatorEntriesMatchStableSecUidAndCanBeEditedIndividually() {
        Item item = new Item("local", "poster", 800);
        item.author.secUid = "sec-uid";
        Settings.LOCAL_HIDDEN_CREATORS.save("other, sec-uid");
        AdvancedFeedRules.CreatorFilter filter = new AdvancedFeedRules.CreatorFilter();
        assertTrue(filter.getEnabled());
        assertTrue(filter.getFiltered(item));
        assertEquals("other, sec-uid, new-id",
                AdvancedFeedRules.addCreatorEntry(Settings.LOCAL_HIDDEN_CREATORS.get(), "new-id"));
        assertEquals("other, new-id",
                AdvancedFeedRules.removeCreatorEntry("other, sec-uid, new-id", "SEC-UID"));
        assertTrue(AdvancedFeedRules.hasCreatorEntry("@poster", "poster"));
    }
    @Test public void qualityRuleUsesMillisecondsAndAllowsUnknownStats() {
        Settings.MAX_VIDEO_SECONDS.save(1);
        Item shortVideo = new Item("1", "allowed", 800);
        Item longVideo = new Item("2", "allowed", 1500);
        assertEquals(Arrays.asList(shortVideo), page(shortVideo, longVideo).items);
        Settings.MAX_VIEWS_PER_LIKE.save(10);
        shortVideo.stats.views = 100;
        shortVideo.stats.likes = 0;
        assertTrue(new AdvancedFeedRules.QualityFilter().getFiltered(shortVideo));
        shortVideo.stats.likes = 20;
        assertFalse(new AdvancedFeedRules.QualityFilter().getFiltered(shortVideo));
    }

    /**
     * A minimum beside the maximum (#154): shorter videos go, photo posts and unknown lengths
     * stay, and a page it would empty keeps the video closest to the limit.
     */
    @Test public void minimumLengthHidesShortVideosAndKeepsTheClosestWhenAllWouldGo() {
        Settings.MIN_VIDEO_SECONDS.save(10);
        Item clip = new Item("1", "allowed", 4000);
        Item full = new Item("2", "allowed", 10000);
        Item unknown = new Item("3", "allowed", 0);
        Item slideshow = new Item("4", "allowed", 3000);
        slideshow.photos = new PhotoModeImageInfo();
        assertTrue(new AdvancedFeedRules.QualityFilter().getEnabled());
        assertEquals(Arrays.asList(full, unknown, slideshow), page(clip, full, unknown, slideshow).items);

        Item nearer = new Item("5", "allowed", 8000);
        assertEquals(Arrays.asList(nearer), page(clip, nearer).items);

        Settings.MAX_VIDEO_SECONDS.save(20);
        assertEquals(Arrays.asList(full), page(clip, full, new Item("6", "allowed", 30000)).items);
        Settings.MIN_VIDEO_SECONDS.save(0);
        assertEquals(Arrays.asList(clip, full), page(clip, full).items);
    }

    @Test public void publicationAgeKeepsUnknownFutureAndBoundaryTimestamps() {
        long now = 1_700_000_000_000L;
        long day = 86_400_000L;
        assertFalse(AdvancedFeedRules.PublicationAgeFilter.olderThan(now - 7 * day, now, 7));
        assertTrue(AdvancedFeedRules.PublicationAgeFilter.olderThan(now - 7 * day - 1, now, 7));
        assertFalse(AdvancedFeedRules.PublicationAgeFilter.olderThan(0, now, 7));
        assertFalse(AdvancedFeedRules.PublicationAgeFilter.olderThan(now + 1, now, 7));
        assertEquals(now, AdvancedFeedRules.PublicationAgeFilter.publicationTimeMillis(now));
        assertEquals(1_700_000_000_000L,
                AdvancedFeedRules.PublicationAgeFilter.publicationTimeMillis(1_700_000_000L));
        assertEquals(Long.MAX_VALUE,
                AdvancedFeedRules.PublicationAgeFilter.publicationTimeMillis(Long.MAX_VALUE));
    }

    @Test public void publicationAgeDoesNotReturnAgeRejectedItemsAsQualityFallback() {
        Settings.MAX_PUBLICATION_AGE_DAYS.save(7);
        Settings.MAX_VIDEO_SECONDS.save(1);
        Item old = new Item("old", "old", 2_000);
        old.createTime = (System.currentTimeMillis() - 8 * 86_400_000L) / 1000L;
        assertTrue(new AdvancedFeedRules.PublicationAgeFilter().getFiltered(old));
        assertTrue(page(old).items.isEmpty());
    }
}
