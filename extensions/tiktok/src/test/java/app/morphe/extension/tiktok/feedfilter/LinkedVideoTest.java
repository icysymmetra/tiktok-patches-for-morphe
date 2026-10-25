/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * A link from outside TikTok lands on a For You response holding the one video it opened. With
 * Hide already seen videos on and that video seen, the filter emptied it and TikTok showed its
 * error screen instead (#117, seen on a phone with 0.68.0 on 47.1.4: "FeedItemList:response kept
 * nothing out of 1"). The linked video now gets past the rules, and nothing else does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LinkedVideoTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final long[] now = {1_000_000L};

    @Before
    public void setUp() {
        BaseSettings.DEBUG.save(false);
        Settings.HIDE_SEEN_VIDEOS.save(true);
        SeenVideoHistory.clear();
        FeedItemsFilter.resetDiagnosticsForTests();
        LinkedVideo.forgetForTests();
        LinkedVideo.setClockForTests(() -> now[0]);
    }

    @After
    public void tearDown() {
        LinkedVideo.forgetForTests();
        SeenVideoHistory.clear();
        Settings.HIDE_SEEN_VIDEOS.resetToDefault();
        Settings.REMOVE_ADS.resetToDefault();
        BaseSettings.DEBUG.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    @Test
    public void aSeenVideoALinkOpensStaysOnItsLandingPage() {
        seen("7001");
        assertEquals("without a link the seen video goes", List.of(), survivors(page("7001")));

        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone/video/7001?is_from_webapp=1"));
        assertEquals(List.of("7001"), survivors(page("7001")));
    }

    @Test
    public void onlyTheVideoTheLinkNamesGetsThrough() {
        seen("7001");
        seen("7002");
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone/video/7001"));
        assertEquals(List.of("7001", "7003"), survivors(page("7001", "7002", "7003")));
    }

    @Test
    public void theAppsOwnDetailAddressNamesItsVideoToo() {
        seen("7001");
        seen("7002");
        LinkedVideo.onNewIntent(view("snssdk1233://aweme/detail/7002"));
        assertEquals(List.of("7002"), survivors(page("7001", "7002")));
    }

    @Test
    public void aPhotoPostLinkAndAMobileSiteLinkNameTheirPost() {
        seen("7001");
        seen("7002");
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone/photo/7001"));
        assertEquals(List.of("7001"), survivors(page("7001", "7002")));
        LinkedVideo.onNewIntent(view("https://m.tiktok.com/v/7002.html"));
        assertEquals(List.of("7002"), survivors(page("7001", "7002")));
    }

    @Test
    public void aShortLinkLetsTheLoneVideoOfAOneVideoResponseThrough() {
        seen("7001");
        seen("7002");
        seen("7003");
        LinkedVideo.onNewIntent(view("https://vm.tiktok.com/ZMabcdef/"));
        assertEquals(List.of("7001"), survivors(page("7001")));
        assertEquals("a page of the feed is never the link's", List.of(), survivors(page("7002", "7003")));
    }

    @Test
    public void aShortLinkVouchesForOneVideoOnly() {
        seen("7001");
        seen("7002");
        LinkedVideo.onNewIntent(view("https://vt.tiktok.com/ZSabcdef/"));
        assertEquals(List.of("7001"), survivors(page("7001")));
        assertEquals("the same video again, as a retry asks for it", List.of("7001"), survivors(page("7001")));
        assertEquals("a later one-video response isn't the link's", List.of(), survivors(page("7002")));
    }

    @Test
    public void aShortLinkNeverVouchesForAnAd() {
        Settings.REMOVE_ADS.save(true);
        LinkedVideo.onNewIntent(view("https://vm.tiktok.com/ZMabcdef/"));
        CreatorExceptionsTest.Item ad = stranger("7009");
        ad.ad = true;
        assertEquals(List.of(), survivors(page(ad)));
        assertEquals("the ad didn't use up the link", List.of("7001"), survivors(page(seenStranger("7001"))));
    }

    @Test
    public void aVideoAFullLinkNamesStaysWhateverRuleMatches() {
        Settings.REMOVE_ADS.save(true);
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone/video/7009"));
        CreatorExceptionsTest.Item labelled = stranger("7009");
        labelled.ad = true;
        assertEquals(List.of("7009"), survivors(page(labelled)));
    }

    @Test
    public void onlyTheResponseAListOpensIsSpared() {
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone/video/7001"));
        CreatorExceptionsTest.Item item = stranger("7001");
        assertEquals(false, LinkedVideo.spares("FeedItemList:cold-cache", item, 1, "SeenVideosFilter"));
        assertEquals(false, LinkedVideo.spares(FeedItemsFilter.OFFLINE_FALLBACK_SOURCE, item, 1, "SeenVideosFilter"));
        assertEquals(true, LinkedVideo.spares(LinkedVideo.RESPONSE_SOURCE, item, 1, "SeenVideosFilter"));
    }

    @Test
    public void theLinkIsForgottenOnceItsWindowPasses() {
        seen("7001");
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone/video/7001"));
        now[0] += LinkedVideo.WINDOW_MS + 1;
        assertEquals(List.of(), survivors(page("7001")));
    }

    @Test
    public void aLinkToAnythingButOneVideoLetsNothingThrough() {
        seen("7001");
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/@someone"));
        LinkedVideo.onNewIntent(view("https://www.tiktok.com/tag/cats"));
        LinkedVideo.onNewIntent(new Intent(Intent.ACTION_MAIN));
        assertEquals(List.of(), survivors(page("7001")));
    }

    @Test
    public void aColdStartReadsItsLinkButARestoreOrARecentsStartDoesNot() {
        seen("7001");
        Intent link = view("https://www.tiktok.com/@someone/video/7001");

        LinkedVideo.onCreate(activityWith(link), new Bundle());
        Intent fromRecents = view("https://www.tiktok.com/@someone/video/7001")
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
        LinkedVideo.onCreate(activityWith(fromRecents), null);
        assertEquals(List.of(), survivors(page("7001")));

        LinkedVideo.onCreate(activityWith(link), null);
        assertEquals(List.of("7001"), survivors(page("7001")));
    }

    private static Activity activityWith(Intent intent) {
        return Robolectric.buildActivity(Activity.class, intent).get();
    }

    private static Intent view(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private static void seen(String aid) {
        SeenVideoHistory.onPlayProgressChange(aid, 9_000, 10_000);
    }

    private static CreatorExceptionsTest.Item stranger(String aid) {
        CreatorExceptionsTest.Item item = new CreatorExceptionsTest.Item(aid);
        item.author.handle = "creator_" + aid;
        item.author.uid = "uid_" + aid;
        item.author.secUid = "sec_" + aid;
        return item;
    }

    private static CreatorExceptionsTest.Item seenStranger(String aid) {
        seen(aid);
        return stranger(aid);
    }

    private static FeedItemList page(String... aids) {
        List<Object> items = new ArrayList<>();
        for (String aid : aids) items.add(stranger(aid));
        FeedItemList list = new FeedItemList();
        list.items = items;
        return list;
    }

    private static FeedItemList page(CreatorExceptionsTest.Item... items) {
        FeedItemList list = new FeedItemList();
        list.items = new ArrayList<Object>(List.of((Object[]) items));
        return list;
    }

    /** The aids left on a page after the filter, in order, with no filter having thrown. */
    private static List<String> survivors(FeedItemList list) {
        FeedItemsFilter.filter(list);
        try {
            Field errors = FeedItemsFilter.class.getDeclaredField("filterExceptionLogCount");
            errors.setAccessible(true);
            assertEquals("a filter swallowed an unstubbed getter or another runtime failure", 0,
                    ((AtomicInteger) errors.get(null)).get());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        List<String> aids = new ArrayList<>();
        for (Object item : list.items) aids.add(((Aweme) item).getAid());
        return aids;
    }
}
