/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * Picking posts on a profile grid (#27): a recycled cell answers for the post it shows now and
 * never for one it showed before, picks are kept by post and survive scrolling, a story or an ad
 * gets no circle, the run takes at most {@link GridPicker#MAX_PICKED}, Save hands the picks over
 * in the order picked, and off or paused the cell is TikTok's as it was.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class GridPickerTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private ActivityController<SaveNoticeTest.HostActivity> owner;
    private Activity activity;
    private final List<List<Object>> runs = new ArrayList<>();

    /** A grid cell's post as the holder's bind hands it over. */
    public static final class Post extends Aweme {
        final String aid;
        boolean story;
        boolean ad;

        Post(String aid) {
            this.aid = aid;
        }

        @Override public String getAid() { return aid; }
        @Override public boolean getIsTikTokStory() { return story; }
        @Override public int getAwemeType() { return story ? 40 : 0; }
        @Override public boolean isAd() { return ad; }
    }

    @Before public void setUp() {
        owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        activity = owner.get();
        Utils.setActivity(activity);
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.PICK_GRID_VIDEOS.save(true);
        GridPicker.resetForTests();
        GridPicker.runsForTests = runs;
        ShadowToast.reset();
    }

    @After public void tearDown() {
        GridPicker.resetForTests();
        PausedProcess.set(false);
        Settings.PICK_GRID_VIDEOS.save(false);
        SettingsStatus.advancedDownloadsEnabled = false;
        Utils.setActivity(null);
        owner.pause().stop().destroy();
    }

    /** A cell the way the grid lays it out: a FrameLayout on the screen with TikTok's cover in it. */
    private FrameLayout cell() {
        FrameLayout cell = new FrameLayout(activity);
        View cover = new View(activity);
        cover.setOnClickListener(view -> { });
        cell.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(cell, new FrameLayout.LayoutParams(300, 400));
        return cell;
    }

    private static TextView circle(View cell) {
        return cell.findViewWithTag(GridPicker.CIRCLE_TAG);
    }

    private static View cover(View cell) {
        return cell.findViewWithTag(GridPicker.COVER_TAG);
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private TextView barText(String text) {
        View bar = GridPicker.barForTests();
        if (!(bar instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) bar;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && text.contentEquals(((TextView) child).getText())) return (TextView) child;
        }
        return null;
    }

    @Test public void theSwitchStartsOff() {
        assertFalse(Settings.PICK_GRID_VIDEOS.defaultValue);
    }

    @Test public void aCellGetsACircleOverTikToksViewsAndNoCoverUntilSomethingIsPicked() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("1"));
        TextView circle = circle(cell);
        assertNotNull("no circle on the cell", circle);
        assertSame("the circle isn't on top", circle, cell.getChildAt(cell.getChildCount() - 1));
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.END,
                ((FrameLayout.LayoutParams) circle.getLayoutParams()).gravity);
        assertEquals(View.GONE, cover(cell).getVisibility());
        assertFalse(circle.isSelected());
        // Bound again, the cell keeps one circle rather than gaining another.
        GridPicker.bind(cell, new Post("1"));
        int circles = 0;
        for (int i = 0; i < cell.getChildCount(); i++) {
            if (GridPicker.CIRCLE_TAG.equals(cell.getChildAt(i).getTag())) circles++;
        }
        assertEquals(1, circles);
    }

    @Test public void aRecycledCellAnswersForThePostItShowsNow() {
        FrameLayout cell = cell();
        Post first = new Post("1");
        Post second = new Post("2");
        GridPicker.bind(cell, first);
        circle(cell).performClick();
        assertTrue(GridPicker.isPicked("1"));
        assertEquals(1, GridPicker.pickedCount());
        assertNotNull("the bar doesn't count one pick", barText("One picked"));

        // Scrolled: the same view now shows another post, which isn't picked.
        GridPicker.bind(cell, second);
        assertFalse("a recycled cell kept the tick of the post it showed before", circle(cell).isSelected());
        assertEquals("with a pick made, the whole cell picks", View.VISIBLE, cover(cell).getVisibility());
        cover(cell).performClick();
        assertTrue("the tap picked another post than the one on screen", GridPicker.isPicked("2"));
        assertTrue(GridPicker.isPicked("1"));
        assertNotNull(barText("2 picked"));

        // Scrolled back: the first post's tick returns with it.
        GridPicker.bind(cell, first);
        assertTrue(circle(cell).isSelected());
        cover(cell).performClick();
        assertFalse("the tap didn't unpick the post on screen", GridPicker.isPicked("1"));
        assertTrue(GridPicker.isPicked("2"));
    }

    @Test public void aTapReadsTheLatestBindNotTheFirst() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("old"));
        GridPicker.bind(cell, new Post("new"));
        circle(cell).performClick();
        assertTrue(GridPicker.isPicked("new"));
        assertFalse(GridPicker.isPicked("old"));
    }

    @Test public void twoCellsOfOnePostShareItsTick() {
        FrameLayout one = cell();
        FrameLayout two = cell();
        GridPicker.bind(one, new Post("7"));
        GridPicker.bind(two, new Post("7"));
        circle(one).performClick();
        assertTrue("the other cell of the same post wasn't redrawn", circle(two).isSelected());
    }

    @Test public void storiesAndAdsAreTikToksToTap() {
        FrameLayout cell = cell();
        Post story = new Post("s");
        story.story = true;
        GridPicker.bind(cell, story);
        assertNull("a story cell got a circle", circle(cell));
        Post ad = new Post("a");
        ad.ad = true;
        GridPicker.bind(cell, ad);
        assertNull("an ad got a circle", circle(cell));
        GridPicker.bind(cell, new Post(null));
        assertNull("a post without an id got a circle", circle(cell));
        GridPicker.bind(cell, "not a post");
        assertNull(circle(cell));

        // A cell that was a video and is recycled into a story loses its circle and cover.
        GridPicker.bind(cell, new Post("v"));
        circle(cell).performClick();
        GridPicker.bind(cell, story);
        assertNull(circle(cell));
        assertNull("the story cell's tap is still taken", cover(cell));
        assertTrue("the pick made before stays", GridPicker.isPicked("v"));
    }

    @Test public void aRunTakesAtMostTheCap() {
        FrameLayout cell = cell();
        for (int index = 0; index < GridPicker.MAX_PICKED; index++) {
            GridPicker.bind(cell, new Post("p" + index));
            circle(cell).performClick();
        }
        assertEquals(GridPicker.MAX_PICKED, GridPicker.pickedCount());
        GridPicker.bind(cell, new Post("one too many"));
        cover(cell).performClick();
        assertFalse(GridPicker.isPicked("one too many"));
        assertEquals(GridPicker.MAX_PICKED, GridPicker.pickedCount());
        assertEquals("You can pick up to 50 at a time", ShadowToast.getTextOfLatestToast());
    }

    @Test public void saveHandsThePicksOverInTheOrderPickedAndGivesTheGridBack() {
        FrameLayout cell = cell();
        Post a = new Post("a");
        Post b = new Post("b");
        Post c = new Post("c");
        for (Post post : new Post[]{a, c, b}) {
            GridPicker.bind(cell, post);
            circle(cell).performClick();
        }
        TextView save = barText("Save");
        assertNotNull("the bar has no Save", save);
        save.performClick();
        assertEquals(1, runs.size());
        assertEquals(List.of(a, c, b), runs.get(0));
        assertEquals(0, GridPicker.pickedCount());
        assertNull("the bar stayed up", GridPicker.barForTests());
        assertEquals("the cells still take the tap", View.GONE, cover(cell).getVisibility());
        assertFalse(circle(cell).isSelected());
    }

    @Test public void cancelUnpicksEverythingAndSavesNothing() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("1"));
        circle(cell).performClick();
        barText("Cancel").performClick();
        assertEquals(0, GridPicker.pickedCount());
        assertNull(GridPicker.barForTests());
        assertTrue(runs.isEmpty());
        assertEquals(View.GONE, cover(cell).getVisibility());
    }

    @Test public void offOrPausedTheCellIsTikToksAsItWas() {
        FrameLayout cell = cell();
        int before = cell.getChildCount();
        GridPicker.bind(cell, new Post("1"));
        circle(cell).performClick();
        assertNotNull(GridPicker.barForTests());

        Settings.PICK_GRID_VIDEOS.save(false);
        GridPicker.bind(cell, new Post("1"));
        assertEquals("our views stayed on the cell", before, cell.getChildCount());
        assertEquals("the picks outlived the switch", 0, GridPicker.pickedCount());
        assertNull(GridPicker.barForTests());

        Settings.PICK_GRID_VIDEOS.save(true);
        PausedProcess.set(true);
        GridPicker.bind(cell, new Post("1"));
        assertEquals("paused, the cell still got a circle", before, cell.getChildCount());
    }

    @Test public void aCircleLeftFromBeforeTheSwitchWentOffGivesTheGridBack() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("1"));
        TextView circle = circle(cell);
        Settings.PICK_GRID_VIDEOS.save(false);
        circle.performClick();
        assertEquals(0, GridPicker.pickedCount());
        assertNull("the circle stayed after a tap with the switch off", circle(cell));
    }

    @Test public void closingTheScreenDropsItsPicks() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("1"));
        circle(cell).performClick();
        assertEquals(1, GridPicker.pickedCount());
        activity.finish();
        owner.pause().stop().destroy();
        idle();
        assertEquals(0, GridPicker.pickedCount());
        // A fresh host for tearDown.
        owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        activity = owner.get();
    }

    @Test public void theCircleReadsAsACheckBoxThatSaysWhetherItIsPicked() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("1"));
        TextView circle = circle(cell);
        assertEquals("Pick for saving", circle.getContentDescription().toString());
        AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
        circle.onInitializeAccessibilityNodeInfo(info);
        assertTrue(info.isCheckable());
        assertFalse(info.isChecked());
        assertEquals("android.widget.CheckBox", info.getClassName().toString());
        circle.performClick();
        AccessibilityNodeInfo after = AccessibilityNodeInfo.obtain();
        circle(cell).onInitializeAccessibilityNodeInfo(after);
        assertTrue(after.isChecked());
        assertEquals("the cover doubles the cell for a screen reader",
                View.IMPORTANT_FOR_ACCESSIBILITY_NO, cover(cell).getImportantForAccessibility());
    }

    /** A grid list the way TikTok's is read: a RecyclerView whose adapter hands its posts over by getData(). */
    public static final class RecyclerView extends FrameLayout {
        final List<Object> data = new ArrayList<>();

        RecyclerView(android.content.Context context) {
            super(context);
        }

        public Adapter getAdapter() {
            return new Adapter(data);
        }
    }

    public static final class Adapter {
        private final List<Object> data;

        Adapter(List<Object> data) {
            this.data = data;
        }

        public List<Object> getData() {
            return data;
        }
    }

    private RecyclerView list(Object... posts) {
        RecyclerView list = new RecyclerView(activity);
        for (Object post : posts) list.data.add(post);
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(list, new FrameLayout.LayoutParams(-1, -1));
        return list;
    }

    private FrameLayout cellIn(RecyclerView list) {
        FrameLayout cell = new FrameLayout(activity);
        cell.addView(new View(activity), new FrameLayout.LayoutParams(-1, -1));
        list.addView(cell, new FrameLayout.LayoutParams(300, 400));
        return cell;
    }

    private static Post[] posts(int from, int to) {
        Post[] posts = new Post[to - from];
        for (int index = from; index < to; index++) posts[index - from] = new Post("p" + index);
        return posts;
    }

    @Test public void pickAllTakesWhatTheListHasLoadedInItsOrder() {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        Post a = new Post("a");
        Post story = new Post("s");
        story.story = true;
        Post b = new Post("b");
        Post ad = new Post("ad");
        ad.ad = true;
        Post d = new Post("d");
        RecyclerView list = list(a, story, b, "a header", ad, new Post(null), d);
        FrameLayout cell = cellIn(list);
        GridPicker.bind(cell, b);
        circle(cell).performClick();
        TextView pickAll = barText("Pick all");
        assertNotNull("the bar has no Pick all", pickAll);
        assertEquals(View.VISIBLE, pickAll.getVisibility());

        pickAll.performClick();
        assertEquals("stories, ads and items without an id were picked", 3, GridPicker.pickedCount());
        assertNotNull(barText("3 picked"));
        barText("Save").performClick();
        assertEquals("the post picked by hand stays first, then the list's order", List.of(b, a, d), runs.get(0));
    }

    @Test public void pickAllStopsAtTheCapAndSaysHowToGetTheRest() {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        RecyclerView list = list((Object[]) posts(0, 53));
        FrameLayout cell = cellIn(list);
        GridPicker.bind(cell, list.data.get(0));
        circle(cell).performClick();
        barText("Pick all").performClick();
        assertEquals(GridPicker.MAX_PICKED, GridPicker.pickedCount());
        assertTrue(GridPicker.isPicked("p49"));
        assertFalse(GridPicker.isPicked("p50"));
        assertEquals("Picked the first 50. Save them, then tap Pick all again for the rest.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void pickAllLeavesOutVideosSavedBeforeSoTheNextRunCarriesOn() throws Exception {
        android.content.Context context = activity.getApplicationContext();
        Utils.awaitBackgroundTasksForTests();
        context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
        SavedVideoArchive.resetForTests();
        Settings.CHECK_SAVED_VIDEOS.save(true);
        try {
            SavedVideoArchive.readIdsLater();
            Utils.awaitBackgroundTasksForTests();
            // The first run saved p0 to p49.
            for (int index = 0; index < GridPicker.MAX_PICKED; index++) remember(context, "p" + index);
            RecyclerView list = list((Object[]) posts(0, 53));
            FrameLayout cell = cellIn(list);
            GridPicker.bind(cell, list.data.get(51));
            circle(cell).performClick();
            barText("Pick all").performClick();

            assertEquals("the saved videos were picked again", 3, GridPicker.pickedCount());
            barText("Save").performClick();
            assertEquals(List.of(list.data.get(51), list.data.get(50), list.data.get(52)), runs.get(0));

            // That run saved the rest. A post picked by hand still counts, and Pick all, with
            // nothing left to add, says so rather than doing nothing.
            for (int index = 50; index < 53; index++) remember(context, "p" + index);
            GridPicker.bind(cell, list.data.get(52));
            circle(cell).performClick();
            ShadowToast.reset();
            barText("Pick all").performClick();
            assertEquals(1, GridPicker.pickedCount());
            assertEquals("Everything this list has loaded is picked or saved. Scroll down to load more.",
                    ShadowToast.getTextOfLatestToast());
        } finally {
            Utils.awaitBackgroundTasksForTests();
            Settings.CHECK_SAVED_VIDEOS.save(false);
            SavedVideoArchive.resetForTests();
            context.deleteDatabase(SavedVideoArchive.DATABASE_NAME);
        }
    }

    private static void remember(android.content.Context context, String id) throws java.io.IOException {
        java.io.File file = new java.io.File(context.getCacheDir(), id + ".mp4");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        SavedVideoArchive.remember(context, id, new MediaFileWriter.Saved(id + ".mp4", null, file),
                SavedVideoArchive.generation());
    }

    /** A Favorites collection folder's list row: its post in a field beside an int, none on the header. */
    public static final class Row {
        final int kind;
        final Aweme post;

        Row(int kind, Aweme post) {
            this.kind = kind;
            this.post = post;
        }
    }

    @Test public void pickAllReadsThePostEachCollectionRowWraps() {
        Settings.CHECK_SAVED_VIDEOS.save(false);
        Post a = new Post("a");
        Post b = new Post("b");
        Post story = new Post("s");
        story.story = true;
        RecyclerView list = list(new Row(1, null), new Row(0, a), new Row(0, story), new Row(0, b), new Row(2, null));
        FrameLayout cell = cellIn(list);
        // The folder's holder hands over the post itself, as the profile grid's does.
        GridPicker.bind(cell, b);
        circle(cell).performClick();
        assertEquals(View.VISIBLE, barText("Pick all").getVisibility());
        barText("Pick all").performClick();
        assertEquals("the header, the banner or the story was picked", 2, GridPicker.pickedCount());
        barText("Save").performClick();
        assertEquals("a run holds the posts, never the rows around them", List.of(b, a), runs.get(0));
    }

    @Test public void aCellThatIsNotAFrameLayoutGetsALayerOfOursAndGetsItBack() {
        // A Favorites collection folder's cell is a ConstraintLayout: the circle and cover go in a
        // full-size layer laid last in it, not loose in its top left corner.
        android.widget.LinearLayout cell = new android.widget.LinearLayout(activity);
        View tikTok = new View(activity);
        cell.addView(tikTok, new android.widget.LinearLayout.LayoutParams(300, 400));
        ViewGroup content = activity.findViewById(android.R.id.content);
        content.addView(cell, new FrameLayout.LayoutParams(300, 400));
        GridPicker.bind(cell, new Post("1"));

        View layer = cell.getChildAt(cell.getChildCount() - 1);
        assertTrue("no layer laid last in the cell", layer instanceof FrameLayout);
        assertEquals(GridPicker.LAYER_TAG, layer.getTag());
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, layer.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, layer.getLayoutParams().height);
        assertSame("the circle isn't in the layer", layer, circle(cell).getParent());
        assertEquals("the circle sits on TikTok's Select posts CheckBox in the top right corner",
                android.view.Gravity.TOP | android.view.Gravity.START,
                ((FrameLayout.LayoutParams) circle(cell).getLayoutParams()).gravity);
        assertFalse("the layer takes the cell's taps for itself", layer.isClickable());
        GridPicker.bind(cell, new Post("1"));
        assertEquals("a second bind laid another layer", 2, cell.getChildCount());

        circle(cell).performClick();
        assertTrue("a tap in the layer didn't reach the cell's post", GridPicker.isPicked("1"));
        cover(cell).performClick();
        assertFalse(GridPicker.isPicked("1"));

        Settings.PICK_GRID_VIDEOS.save(false);
        GridPicker.bind(cell, new Post("1"));
        assertEquals("our layer stayed on the cell", 1, cell.getChildCount());
        assertSame(tikTok, cell.getChildAt(0));
    }

    @Test public void pickAllIsOnlyOfferedWhereTheListCanBeRead() {
        FrameLayout cell = cell();
        GridPicker.bind(cell, new Post("1"));
        circle(cell).performClick();
        assertEquals("a cell outside a list offered Pick all", View.GONE, barText("Pick all").getVisibility());
    }

    @Test public void aViewThatIsNotACellIsLeftAlone() {
        TextView view = new TextView(activity);
        GridPicker.bind(view, new Post("1"));
        GridPicker.bind(null, new Post("1"));
        assertNull(GridPicker.boundTo(view));
    }
}
