package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeedFilterFeedbackTest {
    private static final long START = 1_700_000_000_000L;
    /**
     * The two routes that run the content filters and so can reach the notice. The profile
     * grid reads through the same FeedItemList getter as For You: issue #20's export shows its
     * wipes on "FeedItemList:response" (522 lists, 32 left empty, last reason SeriesFilter),
     * while "ProfileAwemeList" is the ad-only hook that saw 34 already-empty lists.
     */
    private static final String FEED = "FeedItemList:response";
    private static final String FOLLOWING = "FollowFeedList:response";

    @Before public void reset() {
        FeedFilterFeedback.resetForTests();
        FeedFilterCounters.clear();
    }

    @After public void cleanup() {
        FeedFilterFeedback.resetForTests();
        FeedFilterCounters.clear();
    }

    @Test public void emptyServerResponsesDoNotCountAsFilteredBatches() {
        Map<String, Integer> reasons = reasons("KeywordFilter", 4);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 0, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 4, 0, reasons, START + 1));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 4, 0, reasons, START + 2));
        FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FEED, 4, 0, reasons, START + 3);
        assertNotNull(notice);
        // "Batch" is a word from inside the code, so the notice counts in the reader's terms.
        assertTrue(notice.message, notice.message.contains("3 times in a row"));
        assertFalse("the notice still speaks in batches", notice.message.contains("batch"));
    }

    @Test public void aPartiallyKeptBatchResetsTheRepeatedEmptyWindow() {
        Map<String, Integer> reasons = reasons("CreatorFilter", 2);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 2, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 2, 0, reasons, START + 1));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 2, 1, reasons, START + 2));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 2, 0, reasons, START + 3));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 2, 0, reasons, START + 4));
    }

    @Test public void anOldWindowDoesNotCombineWithCurrentBatches() {
        Map<String, Integer> reasons = reasons("RegionFilter", 1);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 1, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 1, 0, reasons, START + 1));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 1, 0, reasons, START + 30_001));
    }

    @Test public void reasonSummaryIsBoundedAndContainsNoItemIdentity() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("KeywordFilter", 1);
        reasons.put("CreatorFilter", 2);
        reasons.put("PublicationAgeFilter", 3);
        reasons.put("RegionFilter", 4);
        reasons.put("SoundFilter", 5);
        reasons.put("AdsFilter", 6);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 6, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 6, 0, reasons, START + 1));
        FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FEED, 6, 0, reasons, START + 2);
        assertNotNull(notice);
        assertTrue(notice.message.contains("Blocked caption words"));
        assertTrue(notice.message.contains("Other feed filters"));
        assertFalse(notice.message.contains("aid"));
        assertTrue(notice.message.split("\\(").length - 1 <= 4);
        assertNull("six mixed reasons name no single switch", notice.settingKey());
    }

    @Test public void feedbackActionTargetsTheFeedFilterSettingsPage() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokActivityHook.openFeedFilterSettings();
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertTrue(intent.getBooleanExtra("morphe", false));
            assertTrue("morphe_settings".equals(intent.getAction()));
            assertTrue("FEED_FILTER".equals(intent.getStringExtra("morphe_settings_section")));
            assertNull(intent.getStringExtra("morphe_settings_target_key"));
        }
    }

    @Test public void threeListsWipedByOneMarkerFilterNameItsSwitch() {
        // Issue #20: 32 lists of profile videos reduced to 0 by SeriesFilter on the FeedItemList
        // route, and the reader was told their filters did it without being told which one.
        // Held on the Following route here so the per-route test below has two real routes.
        Map<String, Integer> reasons = reasons("SeriesFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + 1));
        FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + 2);
        assertNotNull(notice);
        assertTrue(notice.message, notice.message.startsWith("Hide Series hid everything TikTok sent, 3 times in a row."));
        assertTrue(notice.message, notice.message.contains("Turn it off"));
        assertEquals("hide_series", notice.settingKey());
        assertTrue(FeedFilterCounters.report().toString(),
                lineFor(FOLLOWING).contains("suspect=SeriesFilter lists=3"));
    }

    @Test public void theNamedSwitchOpensItsOwnRowOnItsOwnPage() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokActivityHook.openSettingsRow("INTERFACE", "hide_inserted_cards");
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent);
            assertEquals("morphe_settings", intent.getAction());
            assertEquals("INTERFACE", intent.getStringExtra("morphe_settings_section"));
            assertEquals("hide_inserted_cards", intent.getStringExtra("morphe_settings_target_key"));
        }
    }

    @Test public void anAdOrALiveRoomInTheSameListDoesNotBreakTheRun() {
        // Ads come first in the filter order, so a broken predicate on a For You batch that
        // also carried an ad would otherwise never be pinned on the predicate.
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("AdsFilter", 1);
        reasons.put("SeriesFilter", 8);
        reasons.put("LiveFilter", 1);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 1));
        FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 2);
        assertNotNull(notice);
        assertEquals("hide_series", notice.settingKey());
    }

    @Test public void theRunKeepsCountingAndTheExportKeepsTheLongestRun() {
        Map<String, Integer> reasons = reasons("PlaylistFilter", 10);
        for (int list = 0; list < 29; list++) {
            FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + list);
            // Only the third list is allowed to speak: the notice it produced is still pending.
            assertEquals("list " + list, list == 2, notice != null);
        }
        assertTrue(lineFor(FOLLOWING), lineFor(FOLLOWING).contains("suspect=PlaylistFilter lists=29"));

        // A page that keeps something ends the run, and the longest run stays in the export.
        assertNull(FeedFilterFeedback.recordBatch(FOLLOWING, 10, 4, reasons, START + 40));
        assertNull(FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + 41));
        assertTrue(lineFor(FOLLOWING), lineFor(FOLLOWING).contains("suspect=PlaylistFilter lists=29"));
    }

    @Test public void aListThatKeepsAnythingBreaksTheRun() {
        Map<String, Integer> reasons = reasons("VerifiedFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 1));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 1, reasons, START + 2));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 3));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 4));
        assertNull("nothing was pinned on a run of two", lineFor(FEED));
        FeedFilterFeedback.Notice notice = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 5);
        assertNotNull(notice);
        assertEquals("hide_verified", notice.settingKey());
        assertTrue(notice.message, notice.message.contains("3 times in a row"));
    }

    @Test public void aSmallWipedListNeitherCountsNorBreaksTheRun() {
        Map<String, Integer> reasons = reasons("ShopFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 4, 0, reasons("ShopFilter", 4), START + 1));
        // The generic notice fires on the third wiped batch whatever its size; the run is two.
        FeedFilterFeedback.Notice generic = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 2);
        assertNotNull(generic);
        assertNull(generic.settingKey());
        assertNull("a run of two is not pinned on anyone", lineFor(FEED));
        // The pending generic notice has been shown; the next wiped list is the run's third.
        FeedFilterFeedback.noticeShownForTests();
        FeedFilterFeedback.Notice named = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 3);
        assertNotNull("a run reaching three beats the cooldown once", named);
        assertEquals("hide_shop", named.settingKey());
    }

    @Test public void runsAreCountedPerRoute() {
        Map<String, Integer> reasons = reasons("StoryFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + 1));
        FeedFilterFeedback.Notice third = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 2);
        assertNotNull("three wiped batches raise the generic notice", third);
        assertNull("two on one route and one on another is no run of three", third.settingKey());
    }

    @Test public void aDifferentMarkerFilterStartsANewRun() {
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons("SeriesFilter", 10), START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons("PlaylistFilter", 10), START + 1));
        FeedFilterFeedback.Notice third = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons("PlaylistFilter", 10), START + 2);
        assertNotNull(third);
        assertNull("PlaylistFilter has wiped two lists, not three", third.settingKey());
    }

    @Test public void userRulesAdsAndLiveRoomsNeverNameASwitch() {
        // A keyword list, a creator list, a blocked sound, a count range, a region list, the
        // seen history and the two page-level filters can all legitimately wipe list after
        // list. None of them is a predicate on a marker, and none is pinned.
        for (String reason : new String[]{"KeywordFilter", "CreatorFilter", "SoundFilter",
                "ViewCountFilter", "LikeCountFilter", "RegionFilter", "PublicationAgeFilter",
                "QualityFilter", "SeenVideoFilter", "LiveFilter", "AdsFilter", "MidAdFilter", "CaptionLanguageFilter"}) {
            FeedFilterFeedback.resetForTests();
            FeedFilterCounters.clear();
            Map<String, Integer> reasons = reasons(reason, 10);
            FeedFilterFeedback.Notice notice = null;
            for (int list = 0; list < 5; list++) {
                FeedFilterFeedback.Notice result = FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + list);
                if (result != null) notice = result;
            }
            assertNotNull(reason + " never raised the generic notice", notice);
            assertNull(reason + " was pinned as a switch", notice.settingKey());
            assertFalse(reason + " reached the export as a suspect", String.valueOf(lineFor(FOLLOWING)).contains("suspect="));
        }
    }

    @Test public void aMarkerFilterThatTookLessThanHalfIsNotTheSuspect() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("KeywordFilter", 6);
        reasons.put("SeriesFilter", 4);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 1));
        FeedFilterFeedback.Notice third = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 2);
        assertNotNull(third);
        assertNull("the keyword list did most of it", third.settingKey());
        assertNull(lineFor(FEED));

        // Exactly half is not more than half: five ads and five series posts is a feed that
        // looked like that, not a predicate matching everything.
        FeedFilterFeedback.resetForTests();
        Map<String, Integer> split = new LinkedHashMap<>();
        split.put("AdsFilter", 5);
        split.put("SeriesFilter", 5);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, split, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, split, START + 1));
        FeedFilterFeedback.Notice even = FeedFilterFeedback.recordBatch(FEED, 10, 0, split, START + 2);
        assertNotNull(even);
        assertNull("half the list is not a majority", even.settingKey());
        assertNull(lineFor(FEED));
    }

    @Test public void clearingTheDiagnosticDataAndUndoingKeepsTheLongestRun() {
        Map<String, Integer> reasons = reasons("AiGeneratedFilter", 10);
        for (int list = 0; list < 4; list++) {
            FeedFilterFeedback.recordBatch(FOLLOWING, 10, 0, reasons, START + list);
        }
        FeedFilterCounters.Snapshot cleared = FeedFilterCounters.snapshotAndClear();
        assertNull(lineFor(FOLLOWING));
        FeedFilterCounters.suspect(FOLLOWING, "AiGeneratedFilter", 3);
        FeedFilterCounters.restore(cleared);
        assertTrue(lineFor(FOLLOWING), lineFor(FOLLOWING).contains("suspect=AiGeneratedFilter lists=4"));
    }

    @Test public void aNamedNoticeIsNotDroppedBehindAPendingGenericOne() {
        // A small wiped list starts the generic count one list before the run, so the generic
        // notice is posted first. The named one must still reach the reader: the banner
        // replaces the one before it, and the earlier notice said less.
        Map<String, Integer> reasons = reasons("SeriesFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 4, 0, reasons("SeriesFilter", 4), START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 1));
        FeedFilterFeedback.Notice generic = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 2);
        assertNotNull(generic);
        assertNull(generic.settingKey());
        // Nothing has shown the generic notice yet; it is still pending.
        FeedFilterFeedback.Notice named = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 3);
        assertNotNull("the named notice was dropped behind the pending generic one", named);
        assertEquals("hide_series", named.settingKey());
    }

    @Test public void aRunIsNamedOnceAndASecondRunWaitsTheCooldownOut() {
        Map<String, Integer> reasons = reasons("SeriesFilter", 10);
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 1));
        assertNotNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 2));
        FeedFilterFeedback.noticeShownForTests();
        // The same run goes on: named once, counted on.
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 3));
        assertTrue(lineFor(FEED), lineFor(FEED).contains("lists=4"));
        // A kept list ends it; a new run of three inside the cooldown stays quiet.
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 2, reasons, START + 4));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 5));
        assertNull(FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 6));
        assertNull("a second run was named inside the cooldown",
                FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 7));
        // Once the cooldown has passed, the run that is still going is named.
        FeedFilterFeedback.Notice later = FeedFilterFeedback.recordBatch(FEED, 10, 0, reasons, START + 70_000);
        assertNotNull(later);
        assertEquals("hide_series", later.settingKey());
        assertTrue(later.message, later.message.contains("4 times in a row"));
    }

    private static String lineFor(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    private static Map<String, Integer> reasons(String name, int count) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put(name, count);
        return reasons;
    }
}
