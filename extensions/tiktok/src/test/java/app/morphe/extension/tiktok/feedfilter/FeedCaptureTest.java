package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.FeedItemList;
import com.ss.android.ugc.aweme.feed.model.RecReasonsStruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The feed capture: off until it's started, a kept and a hidden decision written under their
 * list, every id a salted code and no caption, handle or address anywhere in the file, a full
 * buffer losing its oldest events and saying so, and the file reading the same way every time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class FeedCaptureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String HIDDEN_AID = "7301234567890123456";
    private static final String KEPT_AID = "7301234567890123999";
    private static final String HIDDEN_HANDLE = "zzhiddenmaker";
    private static final String KEPT_HANDLE = "friendlymaker";
    private static final String CAPTION = "zz secret caption words";

    public static final class Author {
        final String handle;
        Author(String handle) { this.handle = handle; }
        public String getUniqueId() { return handle; }
        public String getUid() { return "uid-" + handle; }
        public String getSecUid() { return "sec-" + handle; }
        public String getNickname() { return "Nick " + handle; }
    }

    /** A video with a caption, a handle and a share link, none of which may reach the file. */
    public static final class Item extends Aweme {
        final String aid;
        final Author author;

        Item(String aid, String handle) {
            this.aid = aid;
            this.author = new Author(handle);
        }

        @Override public String getAid() { return aid; }
        @Override public String getItemDistributeSource() { return "for_you_page_1"; }
        @Override public RecReasonsStruct getRecReasonsStruct() { return new RecReasonsStruct(); }
        @Override public String getShareUrl() {
            return "https://www.tiktok.com/@" + author.handle + "/video/" + aid;
        }
        public Object getAuthor() { return author; }
        public String getDesc() { return CAPTION; }
    }

    @Before public void reset() {
        FeedCapture.stop();
        FeedItemsFilter.resetDiagnosticsForTests();
        Settings.BLOCKED_CREATORS.save(HIDDEN_HANDLE);
    }

    @After public void restore() {
        FeedCapture.stop();
        Settings.BLOCKED_CREATORS.resetToDefault();
        FeedItemsFilter.resetDiagnosticsForTests();
    }

    private static List<String> survivors(Aweme... items) {
        FeedItemList page = new FeedItemList();
        page.items = new ArrayList<>(Arrays.asList(items));
        FeedItemsFilter.filter(page);
        List<String> aids = new ArrayList<>();
        for (Object item : page.items) aids.add(((Aweme) item).getAid());
        return aids;
    }

    private static List<String> codes(String text, String name) {
        Matcher matcher = Pattern.compile(" " + name + "=([0-9a-f]{12})\\b").matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) found.add(matcher.group(1));
        return found;
    }

    @Test public void nothingIsRecordedWhileNoCaptureRuns() {
        assertFalse(FeedCapture.isRecording());
        assertNull("a route got somewhere to write with no capture running",
                FeedCapture.batch("FeedItemList:response", 2));
        assertNull("stop answered with a capture that never started", FeedCapture.stop());

        // The blocked creator still goes: the capture being off changes nothing about filtering.
        assertEquals(List.of(KEPT_AID),
                survivors(new Item(HIDDEN_AID, HIDDEN_HANDLE), new Item(KEPT_AID, KEPT_HANDLE)));
        FeedCapture.single("CachedItem:cache-chain", new Item(KEPT_AID, KEPT_HANDLE), null);
        FeedCapture.noRules("FeedItemList:profile");

        // A capture started afterwards holds nothing from before it.
        FeedCapture.start();
        String text = FeedCapture.stop().text();
        assertTrue(text, text.contains("\nNothing was recorded."));
        assertFalse(text, text.contains("\n  HIDE "));
        assertFalse(text, text.contains("\n  KEEP "));
        assertTrue(text, text.contains("\nNo rule on, counted only: none\n"));
    }

    @Test public void aCaptureRecordsAKeptAndAHiddenDecisionUnderTheirList() {
        FeedCapture.start();
        assertTrue(FeedCapture.isRecording());
        assertEquals(List.of(KEPT_AID),
                survivors(new Item(HIDDEN_AID, HIDDEN_HANDLE), new Item(KEPT_AID, KEPT_HANDLE)));
        FeedCapture.Recording recording = FeedCapture.stop();
        assertFalse(FeedCapture.isRecording());
        String text = recording.text();

        Matcher list = Pattern.compile("^\\+\\d+ms #1 FeedItemList:response in=2 out=1 hidden=CreatorFilter:1\\b.*$",
                Pattern.MULTILINE).matcher(text);
        assertTrue(text, list.find());
        assertTrue("the first list of a route names the rules it ran with: " + list.group(),
                list.group().contains(" rules=") && list.group().contains("CreatorFilter"));
        assertTrue(text, text.contains("\n  HIDE CreatorFilter item="));
        assertTrue(text, text.contains("\n  KEEP - item="));
        // The facts a fixture can't answer are named, not guessed.
        assertTrue(text, text.contains(" pool=for_you_page_1"));
        assertTrue(text, text.contains(" is=") && text.contains("reasonGiven"));
        assertTrue(text, text.contains(" unread="));
        assertFalse(text, text.contains("Nothing was recorded"));
    }

    @Test public void idsComeOutAsSaltedCodesAndNoCaptionHandleOrAddressAppears() {
        FeedCapture.start();
        survivors(new Item(HIDDEN_AID, HIDDEN_HANDLE), new Item(KEPT_AID, KEPT_HANDLE));
        survivors(new Item(KEPT_AID, KEPT_HANDLE), new Item("7301234567890100001", "someoneelse"));
        // A label from the host passes through the redactor, a handle glued to a colon included.
        FeedCapture.single("FeedInsertion:@" + HIDDEN_HANDLE + " https://example.com/x " + HIDDEN_AID,
                new Item(KEPT_AID, KEPT_HANDLE), null);
        String text = FeedCapture.stop().text();

        for (String secret : new String[]{HIDDEN_AID, KEPT_AID, "7301234567890100001", HIDDEN_HANDLE,
                KEPT_HANDLE, "someoneelse", "uid-", "sec-", "Nick ", "secret caption", "https:",
                "tiktok.com", "example.com", "@"}) {
            assertFalse("the capture carries \"" + secret + "\":\n" + text, text.contains(secret));
        }

        List<String> items = codes(text, "item");
        List<String> creators = codes(text, "creator");
        assertEquals(text, 5, items.size());
        assertEquals(text, 5, creators.size());
        // In order: hidden, kept, kept, someone else, kept again under the insertion label.
        assertEquals("one video reads as one code all through a capture", items.get(1), items.get(2));
        assertEquals(items.get(1), items.get(4));
        assertNotEquals(items.get(0), items.get(1));
        assertEquals("one creator reads as one code", creators.get(1), creators.get(2));
        assertNotEquals(creators.get(0), creators.get(1));

        // A new capture draws a new salt, so the same video gets a code that matches nothing before.
        FeedItemsFilter.resetDiagnosticsForTests();
        FeedCapture.start();
        survivors(new Item(KEPT_AID, KEPT_HANDLE));
        String next = FeedCapture.stop().text();
        List<String> again = codes(next, "item");
        assertEquals(next, 1, again.size());
        assertNotEquals("codes carried over from one capture to the next", items.get(1), again.get(0));
    }

    @Test public void aFullBufferDropsTheOldestEventsAndSaysHowMany() {
        FeedCapture.start(2048);
        int events = 60;
        for (int index = 1; index <= events; index++) {
            FeedCapture.single("CapTest", new Item(KEPT_AID, KEPT_HANDLE), index % 2 == 0 ? "CreatorFilter" : null);
        }
        String text = FeedCapture.stop().text();

        Matcher kept = Pattern.compile("^Events kept: (\\d+) \\((\\d+) of 2048 bytes\\)$", Pattern.MULTILINE).matcher(text);
        assertTrue(text, kept.find());
        int keptEvents = Integer.parseInt(kept.group(1));
        assertTrue("the buffer went over its budget: " + kept.group(), Integer.parseInt(kept.group(2)) <= 2048);
        Matcher dropped = Pattern.compile("^Older events dropped to stay under the limit: (\\d+) \\((\\d+) bytes\\)$",
                Pattern.MULTILINE).matcher(text);
        assertTrue("a full buffer said nothing about what it dropped:\n" + text, dropped.find());
        int droppedEvents = Integer.parseInt(dropped.group(1));
        assertTrue(droppedEvents > 0 && keptEvents > 0);
        assertEquals("every event is either kept or counted as dropped", events, keptEvents + droppedEvents);

        // The oldest went and the newest stayed.
        assertFalse(text, text.contains(" #1 CapTest "));
        assertTrue(text, text.contains(" #" + events + " CapTest "));
        Matcher listed = Pattern.compile("^\\+\\d+ms #\\d+ CapTest ", Pattern.MULTILINE).matcher(text);
        int count = 0;
        while (listed.find()) count++;
        assertEquals(keptEvents, count);
    }

    @Test public void theSavedTextHasItsHeaderThenItsEventsAndReadsTheSameEachTime() {
        FeedCapture.start();
        FeedCapture.noRules("FeedItemList:profile");
        FeedCapture.noRules("FeedItemList:profile");
        FeedCapture.unchanged("FeedItemList:response");
        survivors(new Item(HIDDEN_AID, HIDDEN_HANDLE), new Item(KEPT_AID, KEPT_HANDLE));
        FeedCapture.Recording recording = FeedCapture.stop();
        assertNotNull(recording);
        String text = recording.text();

        String[] lines = text.split("\n");
        assertEquals("Metra feed capture", lines[0]);
        assertTrue(lines[1], lines[1].startsWith("Read this file before you share it. "));
        assertTrue(text, lines[2].startsWith("Metra: "));
        assertTrue(text, lines[3].startsWith("TikTok: "));
        assertTrue(text, lines[4].startsWith("Android API: "));
        assertTrue(text, lines[5].matches("Started: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC"));
        assertTrue(text, lines[6].matches("Stopped: \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC \\(\\d+ s\\)"));
        assertTrue(text, lines[7].matches("Events kept: 1 \\(\\d+ of " + FeedCapture.BUDGET_BYTES + " bytes\\)"));
        assertEquals("Older events dropped: none", lines[8]);
        assertEquals("Read again unchanged, counted only: FeedItemList:response 1", lines[9]);
        assertEquals("No rule on, counted only: FeedItemList:profile 2", lines[10]);
        assertTrue(text, lines[11].startsWith("Verdicts: KEEP stays, HIDE "));
        int events = Arrays.asList(lines).indexOf("Events");
        assertTrue(text, events > 11);
        assertTrue(text, lines[events + 1].startsWith("+"));
        assertTrue(text, text.endsWith("\n"));

        // Stopped is stopped: a route still holding the capture can't add to it after the fact.
        FeedCapture.single("Late", new Item(KEPT_AID, KEPT_HANDLE), null);
        assertEquals(text, recording.text());
    }

    @Test public void playCountsGoInAsBands() {
        assertEquals("1-999", bandOf(999));
        assertEquals("1K+", bandOf(1_000));
        assertEquals("100K+", bandOf(123_456));
        assertEquals("100M+", bandOf(2_000_000_000L));
        assertEquals("0", bandOf(0));
    }

    private static String bandOf(long plays) {
        return FeedCapture.plays(new Aweme() {
            @Override public com.ss.android.ugc.aweme.feed.model.AwemeStatistics getStatistics() {
                return new com.ss.android.ugc.aweme.feed.model.AwemeStatistics() {
                    @Override public long getPlayCount() { return plays; }
                };
            }
        });
    }
}
