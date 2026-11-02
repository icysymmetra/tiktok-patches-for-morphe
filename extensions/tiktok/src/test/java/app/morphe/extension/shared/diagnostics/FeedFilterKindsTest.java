/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/** The element kinds a route counts for the export, next to its list and removal counts. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeedFilterKindsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before @After public void startClean() {
        FeedFilterCounters.clear();
    }

    private static String lineFor(String source) {
        for (String line : FeedFilterCounters.report()) {
            if (line.startsWith(source + ":")) return line;
        }
        return null;
    }

    @Test public void aRouteNamesTheKindsItWasHandedMostFirstAndCapsThem() {
        FeedFilterCounters.sawList("Route", 20);
        for (int i = 0; i < FeedFilterCounters.MAX_KINDS; i++) {
            FeedFilterCounters.sawKind("Route", "kind " + (char) ('a' + i));
        }
        FeedFilterCounters.sawKind("Route", "kind a");
        FeedFilterCounters.sawKind("Route", "one too many");
        FeedFilterCounters.sawKind("Route", "two too many");
        FeedFilterCounters.sawKind("Route", null);

        String line = lineFor("Route");
        assertTrue(line, line.startsWith("Route: 1 lists, 20 items, 0 removed. Kinds: kind a 2, "
                + FeedFilterCounters.OTHER_KINDS + " 2, kind b 1, kind c 1, "));
        assertFalse("a kind past the cap was named", line.contains("too many"));
    }

    @Test public void kindsGoWithAClearAndComeBackWithItsUndo() {
        FeedFilterCounters.sawList("Route", 1);
        FeedFilterCounters.sawKind("Route", "type 96 product");
        FeedFilterCounters.Snapshot cleared = FeedFilterCounters.snapshotAndClear();
        assertEquals(List.of(), FeedFilterCounters.report());

        // Counted between the clear and the undo, and kept by it.
        FeedFilterCounters.sawList("Route", 1);
        FeedFilterCounters.sawKind("Route", "type 96 product");
        FeedFilterCounters.sawKind("Route", "type 1 video");
        FeedFilterCounters.restore(cleared);

        assertEquals("Route: 2 lists, 2 items, 0 removed. Kinds: type 96 product 2, type 1 video 1", lineFor("Route"));
    }

    @Test public void anUndoWithNothingCountedBetweenGivesBackTheSameKindsLine() {
        FeedFilterCounters.sawList("Route", 13);
        for (int i = 0; i < FeedFilterCounters.MAX_KINDS; i++) {
            for (int n = 0; n <= i; n++) FeedFilterCounters.sawKind("Route", "kind " + (char) ('a' + i));
        }
        FeedFilterCounters.sawKind("Route", "one too many");
        String before = lineFor("Route");
        assertTrue(before, before.contains(FeedFilterCounters.OTHER_KINDS + " 1"));

        // Replayed in hash order, the overflow took a slot and the last named kind fell into it.
        FeedFilterCounters.restore(FeedFilterCounters.snapshotAndClear());
        assertEquals("undo changed the kinds", before, lineFor("Route"));
    }

    @Test public void parsersOnSeveralThreadsCannotPushTheNamedKindsPastTheCap() throws Exception {
        for (int round = 0; round < 20; round++) {
            int threads = 8;
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads);
            List<Thread> workers = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                Thread worker = new Thread(() -> {
                    try {
                        start.await();
                    } catch (Exception interrupted) {
                        throw new RuntimeException(interrupted);
                    }
                    for (int k = 0; k < 40; k++) FeedFilterCounters.sawKind("Race", "kind " + thread + "-" + k);
                });
                workers.add(worker);
                worker.start();
            }
            for (Thread worker : workers) worker.join();

            String line = lineFor("Race");
            int named = 0;
            for (String kind : line.substring(line.indexOf("Kinds: ") + "Kinds: ".length()).split(", ")) {
                if (!kind.startsWith(FeedFilterCounters.OTHER_KINDS + " ")) named++;
            }
            assertTrue(line, named <= FeedFilterCounters.MAX_KINDS);
            FeedFilterCounters.clear();
        }
    }

    @Test public void aRouteThatNamesNoKindsKeepsItsLine() {
        FeedFilterCounters.sawList("Plain", 3);
        FeedFilterCounters.removed("Plain", 1, "AdsFilter");
        assertEquals("Plain: 1 lists, 3 items, 1 removed. Last reason: AdsFilter", lineFor("Plain"));
    }
}
