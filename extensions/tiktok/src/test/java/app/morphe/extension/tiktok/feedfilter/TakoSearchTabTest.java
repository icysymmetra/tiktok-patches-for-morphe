/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Ask Tako surfaces on the search results page: the pill at the head of the tab strip, a view
 * of its own found by the real id name its text keeps and hidden with its column, and the served
 * tab list the strip is built from, filtered at its two reads for a build that serves the tab as
 * data. The tab keys met go into the export as a marker and, with logging on, a line. The "People
 * also ask Tako" card further down the results goes with its row.
 *
 * <p>The export names a family with the count of what its hooks bound, never the markers'
 * names: for the list, the read, the keys met and the tab's fate make three, a strip without the
 * tab two, a read the strip refuses one found and one missing; for the pill, one each way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TakoSearchTabTest {
    /** A served tab info: the extension reads its key by the getter TikTok's model has. */
    public static final class Tab {
        private final String tabKey;
        public Tab(String tabKey) { this.tabKey = tabKey; }
        public String getTabKey() { return tabKey; }
        @Override public String toString() { return tabKey; }
    }

    /** A served tab info with its name too, for a build that keys the tab by something else. */
    public static final class NamedTab {
        private final String tabKey;
        private final String tabName;
        public NamedTab(String tabKey, String tabName) { this.tabKey = tabKey; this.tabName = tabName; }
        public String getTabKey() { return tabKey; }
        public String getTabName() { return tabName; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
        Settings.HIDE_TAKO_AI.save(false);
    }

    @After public void tearDown() {
        Settings.HIDE_TAKO_AI.resetToDefault();
        HookStatus.clear();
    }

    private static List tabs(String... keys) {
        List list = new ArrayList();
        for (String key : keys) list.add(new Tab(key));
        return list;
    }

    private static List keys(List tabs) {
        List keys = new ArrayList();
        for (Object tab : tabs) keys.add(((Tab) tab).getTabKey());
        return keys;
    }

    private static String report() {
        return String.join("\n", HookStatus.report());
    }

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    // The served list.

    @Test public void theAskTakoTabIsDroppedAndTheOthersKeepTheirOrder() {
        Settings.HIDE_TAKO_AI.save(true);
        List served = tabs("tako", "general", "video", "user");
        List shown = TakoAiFilter.filterSearchTabs(served);
        assertNotSame("a new list, the served one untouched", served, shown);
        assertEquals(Arrays.asList("general", "video", "user"), keys(shown));
        assertEquals("the served list is as it came", 4, served.size());
        // The read, the keys met and the tab hidden.
        assertTrue(report(), report().contains("tako AI: 3 found, 0 missing"));
    }

    @Test public void withTheSwitchOffTheStripGoesThroughAsServed() {
        List served = tabs("tako", "general", "video");
        assertSame(served, TakoAiFilter.filterSearchTabs(served));
        // The read, the keys met and the tab left.
        assertTrue(report(), report().contains("tako AI: 3 found, 0 missing"));
    }

    @Test public void aStripWithoutTheTabIsHandedBackAsItself() {
        Settings.HIDE_TAKO_AI.save(true);
        List served = tabs("general", "video", "user");
        assertSame(served, TakoAiFilter.filterSearchTabs(served));
        assertNull(TakoAiFilter.filterSearchTabs(null));
        List none = new ArrayList();
        assertSame(none, TakoAiFilter.filterSearchTabs(none));
        // The read and the keys met; nothing to hide or leave.
        assertTrue(report(), report().contains("tako AI: 2 found, 0 missing"));
    }

    @Test public void theKeysMetAreOneMarkerPerSetHoweverOftenTheStripIsRead() {
        Settings.HIDE_TAKO_AI.save(true);
        TakoAiFilter.filterSearchTabs(tabs("tako", "general"));
        TakoAiFilter.filterSearchTabs(tabs("tako", "general"));
        assertTrue(report(), report().contains("tako AI: 3 found, 0 missing"));
        TakoAiFilter.filterSearchTabs(tabs("general", "video"));
        // A new set of keys is one more marker; a repeat of one already met is not.
        assertTrue(report(), report().contains("tako AI: 4 found, 0 missing"));
    }

    @Test public void theKeyIsMatchedWhateverItsCaseOrSpacingAndTheNameCountsToo() {
        Settings.HIDE_TAKO_AI.save(true);
        assertEquals(Arrays.asList("general"), keys(TakoAiFilter.filterSearchTabs(tabs(" Tako ", "general"))));
        assertEquals(Arrays.asList("general"), keys(TakoAiFilter.filterSearchTabs(tabs("general", "ASK_TAKO"))));
        assertTrue(TakoAiFilter.isTakoTab(new Tab("tako_tab")));
        assertFalse(TakoAiFilter.isTakoTab(new Tab("general")));
        assertFalse(TakoAiFilter.isTakoTab(new Object()));
        // A build that keys the tab by something else still names it.
        assertTrue(TakoAiFilter.isTakoTab(new NamedTab("smart_search", "Ask Tako")));
        assertFalse(TakoAiFilter.isTakoTab(new NamedTab("general", "Top")));
    }

    @Test public void aFailingStripReadNeverReachesTheViewModel() {
        Settings.HIDE_TAKO_AI.save(true);
        // Not empty, so the read gets past the guard, and refusing its first touch inside the try.
        // An empty ArrayList that throws from size() never gets there: isEmpty() reads the field,
        // and the guard hands the list back before the catch is reached.
        List hostile = new AbstractList() {
            @Override public int size() { return 1; }
            @Override public Object get(int index) { throw new IllegalStateException("host list refused"); }
            @Override public Iterator iterator() { throw new IllegalStateException("host list refused"); }
            // A failed assertion prints its operands, and the collection's own toString iterates.
            @Override public String toString() { return "the hostile strip"; }
        };
        assertSame("the view model got its own list back", hostile, TakoAiFilter.filterSearchTabs(hostile));
        // The read found, the hook's throw the one thing missing, and the export names it.
        assertTrue(report(), report().contains("tako AI: 1 found, 1 missing"));
        assertTrue(report(), report().contains("'search tabs' hook (it threw java.lang.IllegalStateException)"));
    }

    // The pill.

    @Test public void thePillsColumnGoesGoneAndTheTabRowStays() {
        Settings.HIDE_TAKO_AI.save(true);
        // The strip as 47.0.3 inflates it: the text in a pill (with a GONE icon), in a frame, in a
        // column beside the tab row.
        FrameLayout root = new FrameLayout(context());
        FrameLayout row = new FrameLayout(context());
        LinearLayout column = new LinearLayout(context());
        FrameLayout frame = new FrameLayout(context());
        LinearLayout pill = new LinearLayout(context());
        View icon = new View(context());
        icon.setVisibility(View.GONE);
        TextView text = new TextView(context());
        text.setText("Ask Tako");
        View tabs = new View(context());
        pill.addView(icon);
        pill.addView(text);
        frame.addView(pill);
        column.addView(frame);
        row.addView(column);
        row.addView(tabs);
        root.addView(row);
        TakoAiFilter.hideSearchTabEntrance(root, text);
        assertEquals("the column beside the tab row", View.GONE, column.getVisibility());
        assertEquals("the row that holds the tabs", View.VISIBLE, row.getVisibility());
        assertEquals("the tabs", View.VISIBLE, tabs.getVisibility());
        assertTrue(report(), report().contains("tako AI: 1 found, 0 missing"));
    }

    @Test public void theClimbStopsShortOfTheRootAndAtItsCap() {
        FrameLayout root = new FrameLayout(context());
        LinearLayout pill = new LinearLayout(context());
        TextView text = new TextView(context());
        pill.addView(text);
        root.addView(pill);
        root.addView(new View(context()));
        // The pill right under the root: the pill goes, the root and its other child stay.
        assertSame(pill, TakoAiFilter.searchTabEntranceColumn(root, text));

        FrameLayout deep = new FrameLayout(context());
        FrameLayout level = deep;
        for (int i = 0; i < 8; i++) {
            FrameLayout next = new FrameLayout(context());
            level.addView(next);
            level = next;
        }
        TextView deepText = new TextView(context());
        level.addView(deepText);
        View stopped = TakoAiFilter.searchTabEntranceColumn(deep, deepText);
        assertNotSame("never the root", deep, stopped);
        int climbed = 0;
        for (View view = deepText; view != stopped; view = (View) view.getParent()) climbed++;
        assertEquals("more wrappers than the climb allows: it stops at its cap", 4, climbed);
    }

    @Test public void aStripInflatedWithoutThePillIsLeftAlone() {
        Settings.HIDE_TAKO_AI.save(true);
        FrameLayout root = new FrameLayout(context());
        TakoAiFilter.hideSearchTabEntrance(root, null);
        assertEquals(View.VISIBLE, root.getVisibility());
        // Absent, and nothing missing.
        assertTrue(report(), report().contains("tako AI: 1 found, 0 missing"));
    }

    @Test public void withTheSwitchOffThePillIsLeftAndNothingIsLookedUp() {
        FrameLayout root = new FrameLayout(context());
        TakoAiFilter.hideSearchTabEntrance(root);
        // Left, and no miss for the id this test app does not have: the lookup never ran.
        assertTrue(report(), report().contains("tako AI: 1 found, 0 missing"));
    }

    @Test public void aBuildWithoutTheIdNameIsNamedInTheExport() {
        Settings.HIDE_TAKO_AI.save(true);
        FrameLayout root = new FrameLayout(context());
        TakoAiFilter.hideSearchTabEntrance(root);
        assertTrue(report(), report().contains(
                "tako AI: 0 found, 1 missing. First missing: view id 'tv_tab_tako_entrance'"));
    }

    // The "People also ask Tako" card in the results.

    /** Stands in for androidx's RecyclerView: the extension knows a list by that class name. */
    public static class RecyclerView extends FrameLayout {
        public RecyclerView(Context context) { super(context); }
    }

    /** TikTok's results grid, an R8-named subclass of the list. */
    public static class Grid extends RecyclerView {
        public Grid(Context context) { super(context); }
    }

    /** The results as 47.4.3 lays them out: the card's view in its full-width row, between video rows. */
    private static final class Results {
        final Grid grid = new Grid(context());
        final View videosAbove = new View(context());
        final FrameLayout row = new FrameLayout(context());
        final LinearLayout card = new LinearLayout(context());
        final View videosBelow = new View(context());

        Results() {
            card.addView(new View(context()));
            card.addView(new View(context()));
            row.addView(card, new FrameLayout.LayoutParams(720, 464));
            grid.addView(videosAbove, new FrameLayout.LayoutParams(348, 725));
            grid.addView(row, new FrameLayout.LayoutParams(720, 464));
            grid.addView(videosBelow, new FrameLayout.LayoutParams(348, 725));
        }
    }

    @Test public void theQuestionsCardAndItsRowGoToNothingAndTheVideosStay() {
        Settings.HIDE_TAKO_AI.save(true);
        Results results = new Results();
        TakoAiFilter.hideSearchSugCard(results.card);
        assertEquals("the card", View.GONE, results.card.getVisibility());
        assertEquals("the card's height", 0, results.card.getLayoutParams().height);
        // The row is collapsed once it is in the list, which the hook posts for.
        TakoAiFilter.collapseSearchSugCardRow(results.card);
        assertEquals("the row", View.GONE, results.row.getVisibility());
        assertEquals("the row's height, which the list lays out whatever the visibility", 0, results.row.getLayoutParams().height);
        assertEquals("the videos above", View.VISIBLE, results.videosAbove.getVisibility());
        assertEquals(725, results.videosAbove.getLayoutParams().height);
        assertEquals("the videos below", View.VISIBLE, results.videosBelow.getVisibility());
        assertEquals("the list", View.VISIBLE, results.grid.getVisibility());
        // Hidden, and its row collapsed.
        assertTrue(report(), report().contains("tako AI: 2 found, 0 missing"));
    }

    @Test public void withTheSwitchOffTheQuestionsCardIsLeft() {
        Results results = new Results();
        TakoAiFilter.hideSearchSugCard(results.card);
        assertEquals(View.VISIBLE, results.card.getVisibility());
        assertEquals(464, results.card.getLayoutParams().height);
        assertEquals(View.VISIBLE, results.row.getVisibility());
        assertEquals(464, results.row.getLayoutParams().height);
        assertTrue(report(), report().contains("tako AI: 1 found, 0 missing"));
    }

    @Test public void aCardLaidOutBeforeItsRowJoinsTheListTakesTheRowWithItOnceAttached() {
        Settings.HIDE_TAKO_AI.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Results results = new Results();
            TakoAiFilter.hideSearchSugCard(results.card);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the row, before the results are on screen", 464, results.row.getLayoutParams().height);

            owner.get().setContentView(results.grid);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the row", View.GONE, results.row.getVisibility());
            assertEquals("the row's height", 0, results.row.getLayoutParams().height);
            assertEquals("the videos above", View.VISIBLE, results.videosAbove.getVisibility());
            assertEquals("the videos below", View.VISIBLE, results.videosBelow.getVisibility());
            assertTrue(report(), report().contains("tako AI: 2 found, 0 missing"));
        }
    }

    @Test public void aCardLaidOutInAListAlreadyOnScreenTakesItsRowWithIt() {
        Settings.HIDE_TAKO_AI.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Results results = new Results();
            owner.get().setContentView(results.grid);
            TakoAiFilter.hideSearchSugCard(results.card);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("the row", View.GONE, results.row.getVisibility());
            assertEquals("the row's height", 0, results.row.getLayoutParams().height);
            assertEquals("the videos above", View.VISIBLE, results.videosAbove.getVisibility());
        }
    }

    @Test public void theRowIsTheViewUnderTheListAndNoListMeansNoRow() {
        Results results = new Results();
        assertSame("the card's own wrapper", results.row, TakoAiFilter.searchSugCardRow(results.card));
        assertSame("a card handed in as the row itself", results.row, TakoAiFilter.searchSugCardRow(results.row));

        FrameLayout page = new FrameLayout(context());
        LinearLayout loose = new LinearLayout(context());
        page.addView(loose);
        assertNull("a card in no list", TakoAiFilter.searchSugCardRow(loose));
        assertNull("a card in nothing at all", TakoAiFilter.searchSugCardRow(new LinearLayout(context())));
        TakoAiFilter.collapseSearchSugCardRow(loose);
        assertEquals("the card's wrapper, with no row to collapse", View.VISIBLE, loose.getVisibility());
        assertEquals("the page around it", View.VISIBLE, page.getVisibility());
        // Not found is a marker, not a miss.
        assertTrue(report(), report().contains("tako AI: 1 found, 0 missing"));
    }

    @Test public void aRowHoldingMoreThanTheCardIsLeftStanding() {
        Settings.HIDE_TAKO_AI.save(true);
        Results results = new Results();
        View neighbor = new View(context());
        results.row.addView(neighbor, new FrameLayout.LayoutParams(720, 120));
        assertNull("a wrapper with something else showing", TakoAiFilter.searchSugCardRow(results.card));

        TakoAiFilter.hideSearchSugCard(results.card);
        TakoAiFilter.collapseSearchSugCardRow(results.card);
        assertEquals("the card", View.GONE, results.card.getVisibility());
        assertEquals("the row", View.VISIBLE, results.row.getVisibility());
        assertEquals("the row's height", 464, results.row.getLayoutParams().height);
        assertEquals("what shares the row", View.VISIBLE, neighbor.getVisibility());

        // Once that neighbor is gone itself, the row holds only the card again.
        neighbor.setVisibility(View.GONE);
        assertSame(results.row, TakoAiFilter.searchSugCardRow(results.card));
    }

    @Test public void theClimbToTheRowStopsAtItsCap() {
        FrameLayout[] four = wrappers(4);
        View card = new View(context());
        four[3].addView(card);
        assertSame("a row four wrappers up", four[0], TakoAiFilter.searchSugCardRow(card));

        FrameLayout[] five = wrappers(5);
        View deeper = new View(context());
        five[4].addView(deeper);
        assertNull("a list five wrappers up, past the climb", TakoAiFilter.searchSugCardRow(deeper));
    }

    /** Single-child wrappers nested from the top, the first one in a list. */
    private static FrameLayout[] wrappers(int count) {
        FrameLayout[] chain = new FrameLayout[count];
        ViewGroup parent = new Grid(context());
        for (int i = 0; i < count; i++) {
            chain[i] = new FrameLayout(context());
            parent.addView(chain[i]);
            parent = chain[i];
        }
        return chain;
    }
}
