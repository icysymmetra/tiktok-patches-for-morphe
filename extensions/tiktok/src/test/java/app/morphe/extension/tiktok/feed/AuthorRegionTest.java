package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.main.MainActivity;

import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The feed's author row and a comment row both carry a {@code title} view, so the row is
 * identified by the post time sitting beside the name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AuthorRegionTest {
    private static final int NAME_ID = 0x7f0a0001;
    private static final int POST_TIME_ID = 0x7f0a0002;

    /** Stands in for the Aweme the player says is on screen. */
    public static final class Clip {
        public final String aid;
        public final String region;
        public final Author author;

        Clip(String aid, String handle, String nickname, String region) {
            this.aid = aid;
            this.region = region;
            this.author = new Author(handle, nickname);
        }
    }

    public static final class Author {
        public final String uid = "1234";
        public final String uniqueId;
        public final String nickname;

        Author(String uniqueId, String nickname) {
            this.uniqueId = uniqueId;
            this.nickname = nickname;
        }
    }

    /** A row that says how many times its text was written, not just what it says. */
    public static final class CountingTextView extends TextView {
        int writes;

        CountingTextView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            writes++;
        }
    }

    private static Clip playing(String aid, String handle, String nickname, String region) {
        return new Clip(aid, handle, nickname, region);
    }

    private Context context;
    private boolean regionPatched;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        AuthorRegion.setViewIds(NAME_ID, POST_TIME_ID);
        AuthorRegion.restore();
        // The country and handle switches only count in a build with Show author region.
        regionPatched = SettingsStatus.authorRegionEnabled;
        SettingsStatus.authorRegionEnabled = true;
    }

    @After
    public void tearDown() {
        SettingsStatus.authorRegionEnabled = regionPatched;
    }

    /** name + post time in one row, the shape the feed uses. */
    private LinearLayout feedRow(String name) {
        LinearLayout row = new LinearLayout(context);
        TextView title = new TextView(context);
        title.setId(NAME_ID);
        title.setText(name);
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        postTime.setText("· 4h ago");
        row.addView(title);
        row.addView(postTime);
        return row;
    }

    /** A comment row: same name id, no post time beside it. */
    private LinearLayout commentRow(String name) {
        LinearLayout row = new LinearLayout(context);
        TextView title = new TextView(context);
        title.setId(NAME_ID);
        title.setText(name);
        row.addView(title);
        return row;
    }

    @Test
    public void theCountryIsAppendedOnceAndRemovedAgain() {
        LinearLayout row = feedRow("My path forward");
        TextView name = AuthorRegion.findName(row);
        assertSame(row.getChildAt(0), name);

        AuthorRegion.decorate(name, null, "US");
        assertEquals("My path forward · US", name.getText().toString());

        // Every layout pass runs this, so it must not keep appending.
        AuthorRegion.decorate(name, null, "US");
        AuthorRegion.decorate(name, null, "US");
        assertEquals("My path forward · US", name.getText().toString());

        // Switching the option off, or leaving the feed, puts the name back.
        AuthorRegion.restore();
        assertEquals("My path forward", name.getText().toString());
    }

    /** TikTok keeps the post time GONE unless the publish date shows; the row is still the author's (#75). */
    @Test
    public void aHiddenPostTimeStillMarksTheAuthorRow() {
        LinearLayout row = feedRow("My path forward");
        row.getChildAt(1).setVisibility(View.GONE);
        assertSame(row.getChildAt(0), AuthorRegion.findName(row));

        row.getChildAt(0).setVisibility(View.GONE);
        assertNull(AuthorRegion.findName(row));
    }

    @Test
    public void aCommentRowIsNotTheAuthorRow() {
        assertNull(AuthorRegion.findName(commentRow("Joshbetancourt28")));
    }

    @Test
    public void aVideoWithNoRegionLeavesTheNameAlone() {
        LinearLayout row = feedRow("My path forward");
        TextView name = AuthorRegion.findName(row);

        AuthorRegion.decorate(name, null, null);
        assertEquals("My path forward", name.getText().toString());
    }

    @Test
    public void movingToAnotherVideoDecoratesTheNewNameAndReleasesTheOld() {
        TextView first = AuthorRegion.findName(feedRow("first creator"));
        AuthorRegion.decorate(first, null, "US");
        assertEquals("first creator · US", first.getText().toString());

        // TikTok rebinds the row for the next video before this runs again.
        TextView second = AuthorRegion.findName(feedRow("second creator"));
        AuthorRegion.decorate(second, null, "GB");
        assertEquals("second creator · GB", second.getText().toString());
        assertEquals("first creator", first.getText().toString());
    }

    @Test
    public void aVideoChangeOnTheSameRowReplacesTheCountryInsteadOfStackingIt() {
        // The feed recycles the row: the same TextView is rebound for the next video, and
        // the player can name that video before the row is redecorated.
        LinearLayout row = feedRow("alice");
        TextView name = AuthorRegion.findName(row);

        AuthorRegion.decorate(name, null, "US");
        AuthorRegion.decorate(name, null, "GB");
        AuthorRegion.decorate(name, null, "DE");
        assertEquals("alice · DE", name.getText().toString());

        AuthorRegion.restore();
        assertEquals("alice", name.getText().toString());
    }

    @Test
    public void aRebuiltRowWhoseNameExtendsTheOldOneIsLeftAlone() {
        LinearLayout row = feedRow("Sam");
        TextView name = AuthorRegion.findName(row);
        AuthorRegion.decorate(name, null, "US");
        assertEquals("Sam · US", name.getText().toString());

        // TikTok rebinds the recycled row to a creator whose name starts with the old one.
        name.setText("Sam Smith");
        AuthorRegion.restore();
        assertEquals("Sam Smith", name.getText().toString());
    }

    @Test
    public void theHandleReplacesTheDisplayName() {
        LinearLayout row = feedRow("Sam Smith");
        TextView name = AuthorRegion.findName(row);

        AuthorRegion.decorate(name, "samsmith", null);
        assertEquals("@samsmith", name.getText().toString());

        // Every layout pass runs this, so it must settle.
        AuthorRegion.decorate(name, "samsmith", null);
        assertEquals("@samsmith", name.getText().toString());

        AuthorRegion.restore();
        assertEquals("Sam Smith", name.getText().toString());
    }

    @Test
    public void bothSwitchesTogetherGiveTheHandleAndTheCountry() {
        LinearLayout row = feedRow("Sam Smith");
        TextView name = AuthorRegion.findName(row);

        AuthorRegion.decorate(name, "samsmith", "US");
        assertEquals("@samsmith · US", name.getText().toString());

        AuthorRegion.restore();
        assertEquals("Sam Smith", name.getText().toString());
    }

    @Test
    public void aRecycledRowTakesTheNextCreatorsHandle() {
        // The feed rebinds the same TextView for the next video.
        LinearLayout row = feedRow("alice");
        TextView name = AuthorRegion.findName(row);

        AuthorRegion.decorate(name, "alice_v", "US");
        AuthorRegion.decorate(name, "bob_v", "GB");
        assertEquals("@bob_v · GB", name.getText().toString());

        AuthorRegion.restore();
        assertEquals("alice", name.getText().toString());
    }

    @Test
    public void eachSwitchOnlyEverAsksForItsOwnValue() {
        Clip video = playing("one", "samsmith", "Sam Smith", "GB");

        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(false);
        assertNull(AuthorRegion.decoration(video));

        // The country switch on its own must not start replacing names with handles.
        Settings.SHOW_AUTHOR_REGION.save(true);
        assertArrayEquals(new String[]{null, "GB"}, AuthorRegion.decoration(video));

        Settings.SHOW_AUTHOR_HANDLE.save(true);
        Settings.SHOW_AUTHOR_REGION.save(false);
        assertArrayEquals(new String[]{"samsmith", null}, AuthorRegion.decoration(video));

        Settings.SHOW_AUTHOR_REGION.save(true);
        assertArrayEquals(new String[]{"samsmith", "GB"}, AuthorRegion.decoration(video));
    }

    /** #157: the country as its flag, built from the two letters, and only from two letters. */
    @Test
    public void theCountryCanShowAsItsFlag() {
        Settings.SHOW_AUTHOR_REGION.save(true);
        Settings.REGION_AS_FLAG.save(true);
        try {
            assertArrayEquals(new String[]{null, "🇬🇧"},
                    AuthorRegion.decoration(playing("flag", "samsmith", "Sam Smith", "gb")));
            assertEquals("🇪🇪", AuthorRegion.shown("EE"));
            assertEquals("a code that isn't two letters stays as it is", "419", AuthorRegion.shown("419"));
            assertEquals("X1", AuthorRegion.shown("X1"));
            assertNull(AuthorRegion.shown(null));

            Settings.REGION_AS_FLAG.save(false);
            assertEquals("GB", AuthorRegion.shown("GB"));
        } finally {
            Settings.REGION_AS_FLAG.resetToDefault();
        }
    }

    @Test
    public void aVideoWithNothingToShowAsksForNothing() {
        Settings.SHOW_AUTHOR_HANDLE.save(true);
        Settings.SHOW_AUTHOR_REGION.save(true);

        assertNull(AuthorRegion.decoration(playing("two", null, "Nobody", null)));

        // A handle with no country still gives the handle.
        assertArrayEquals(new String[]{"someone", null},
                AuthorRegion.decoration(playing("three", "someone", "Some One", null)));
    }

    /**
     * The engagement rate patch installs the same row. Without Show author region in the build,
     * a country or handle switch saved earlier must not start showing through it.
     */
    @Test
    public void theRegionSwitchesNeedTheirOwnPatch() {
        SettingsStatus.authorRegionEnabled = false;
        try {
            Settings.SHOW_AUTHOR_HANDLE.save(true);
            Settings.SHOW_AUTHOR_REGION.save(true);
            assertNull(AuthorRegion.decoration(playing("four", "samsmith", "Sam Smith", "GB")));
        } finally {
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
        }
    }

    @Test
    public void aSettledRowIsNotWrittenAgainOnEveryLayoutPass() {
        // This runs from a global layout listener, so writing on every pass would ask for
        // another layout, and another.
        LinearLayout row = new LinearLayout(context);
        CountingTextView name = new CountingTextView(context);
        name.setId(NAME_ID);
        name.setText("alice");
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        row.addView(name);
        row.addView(postTime);

        AuthorRegion.decorate(name, null, "US");
        int afterFirst = name.writes;
        assertEquals("alice · US", name.getText().toString());

        for (int i = 0; i < 5; i++) AuthorRegion.decorate(name, null, "US");
        assertEquals(afterFirst, name.writes);

        AuthorRegion.restore();
    }

    @Test
    public void aStyledNameKeepsItsSpans() {
        LinearLayout row = feedRow("");
        TextView name = AuthorRegion.findName(row);
        SpannableString styled = new SpannableString("alice");
        styled.setSpan(new StyleSpan(Typeface.BOLD), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.setText(styled);

        AuthorRegion.decorate(name, null, "US");

        assertEquals("alice · US", name.getText().toString());
        CharSequence decorated = name.getText();
        assertTrue(decorated instanceof Spanned);
        assertEquals(1, ((Spanned) decorated).getSpans(0, 5, StyleSpan.class).length);
    }

    private static final int PAGE_WIDTH = 200;
    private static final int PAGE_HEIGHT = 300;

    /**
     * TikTok's pager with an author row in a cell for each video: the cell at the pager's
     * scroll on screen, the others a page away and clipped, all of them attached.
     */
    private VerticalViewPager feed(Activity activity, String... names) {
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        VerticalViewPager pager = new VerticalViewPager(activity);
        root.addView(pager, new FrameLayout.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT));
        for (int i = 0; i < names.length; i++) {
            FrameLayout cell = new FrameLayout(activity);
            FrameLayout.LayoutParams place = new FrameLayout.LayoutParams(PAGE_WIDTH, PAGE_HEIGHT);
            place.topMargin = i * PAGE_HEIGHT;
            pager.addView(cell, place);
            cell.addView(feedRow(names[i]));
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = root.getRootView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 800);
        return pager;
    }

    private static TextView nameIn(VerticalViewPager pager, int index) {
        return (TextView) ((LinearLayout) ((FrameLayout) pager.getChildAt(index)).getChildAt(0)).getChildAt(0);
    }

    @Test
    public void theRowOnScreenIsDecoratedNotTheFirstOneInTheTree() {
        // #75: after a swipe down the cell above stays attached and comes first in the tree,
        // so the country went on it, off screen, and only showed after swiping back up.
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            VerticalViewPager pager = feed(activity, "first creator", "second creator", "third creator");
            View content = activity.findViewById(android.R.id.content);

            assertSame(nameIn(pager, 0), AuthorRegion.findName(content));

            pager.scrollTo(0, PAGE_HEIGHT);
            TextView onScreen = AuthorRegion.findName(content);
            assertSame(nameIn(pager, 1), onScreen);
            AuthorRegion.decorate(onScreen, null, "AZ");
            assertEquals("second creator · AZ", nameIn(pager, 1).getText().toString());
            assertEquals("first creator", nameIn(pager, 0).getText().toString());

            // And on to the next, which takes the country off the row it leaves.
            pager.scrollTo(0, 2 * PAGE_HEIGHT);
            TextView next = AuthorRegion.findName(content);
            assertSame(nameIn(pager, 2), next);
            AuthorRegion.decorate(next, null, "TR");
            assertEquals("third creator · TR", nameIn(pager, 2).getText().toString());
            assertEquals("second creator", nameIn(pager, 1).getText().toString());

            AuthorRegion.restore();
        }
    }

    @Test
    public void withNoRowOnScreenNothingIsDecorated() {
        // A feed behind another page keeps its cells attached with their last layout.
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            VerticalViewPager pager = feed(activity, "first creator", "second creator");
            pager.setVisibility(View.INVISIBLE);

            assertNull(AuthorRegion.findName(activity.findViewById(android.R.id.content)));
        }
    }

    /** Stands in for VideoItemParams, which hands the player its Aweme. */
    public static final class Params {
        public final Clip aweme;

        Params(Clip aweme) {
            this.aweme = aweme;
        }
    }

    /** Tells CurrentVideoAuthor this clip is the one playing, the way the player hooks do. */
    private static void play(Clip clip) throws Exception {
        Method update = CurrentVideoAuthor.class.getDeclaredMethod("update", Object.class);
        update.setAccessible(true);
        update.invoke(null, new Params(clip));
        Method onPlaying = CurrentVideoAuthor.class.getDeclaredMethod("onPlaying", String.class);
        onPlaying.setAccessible(true);
        onPlaying.invoke(null, clip.aid);
    }

    private static void layOut(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
    }

    /** #75: a video opened from search plays in a detail pager of its own, and gets the country too. */
    @Test
    public void aVideoOpenedInTheDetailPagerGetsTheCountry() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            Utils.setContext(feed.get());
            AuthorRegion.install(feed.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            feed.pause();

            try (ActivityController<DetailActivity> pager = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
                LinearLayout row = feedRow("aittaac");
                pager.get().setContentView(row);
                play(new Clip("opened", "aittaac", "aittaac", "AZ"));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                layOut(row.getRootView());
                row.getViewTreeObserver().dispatchOnGlobalLayout();

                assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
                pager.pause().stop();
            }
        } finally {
            AuthorRegion.restore();
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
            reset.invoke(null);
        }
    }

    /** #75: TikTok cuts a long name with an ellipsis, and the country after it went too. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aLongNameIsShortenedSoTheCountryStaysWhole() {
        LinearLayout row = longNameRow(LONG_NAME);
        TextView name = (TextView) row.getChildAt(0);

        AuthorRegion.decorate(name, null, "AZ");
        layOut(row);
        assertTrue("the fixture name isn't long enough to be cut", name.getLayout().getEllipsisCount(0) > 0);

        // The next layout pass fits it.
        AuthorRegion.decorate(name, null, "AZ");
        layOut(row);
        String shown = name.getText().toString();
        assertTrue(shown, shown.endsWith(" · AZ"));
        assertTrue(shown, shown.startsWith("Aysel_"));
        assertTrue(shown, shown.contains("…"));
        assertEquals(shown, 0, name.getLayout().getEllipsisCount(0));

        // Settled, so later passes leave it as it is.
        AuthorRegion.decorate(name, null, "AZ");
        assertEquals(shown, name.getText().toString());

        AuthorRegion.restore();
        assertEquals(LONG_NAME, name.getText().toString());
    }

    private static final String LONG_NAME = "Aysel_Salehli_Coach_Yasam_Kocu_Spiritual_Mentor_Official";

    /** A single-line name 160 px wide that TikTok cuts with an ellipsis, beside a hidden post time. */
    private LinearLayout longNameRow(String full) {
        LinearLayout row = new LinearLayout(context);
        TextView name = new TextView(context);
        name.setId(NAME_ID);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(full);
        row.addView(name, new LinearLayout.LayoutParams(160, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        postTime.setVisibility(View.GONE);
        row.addView(postTime);
        return row;
    }

    /**
     * A row with a fixed width takes new text with a redraw and no layout pass, so nothing would
     * come back to fit it. It's fitted as it's written.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aFixedWidthRowIsFittedWithoutWaitingForALayoutPass() {
        LinearLayout row = longNameRow(LONG_NAME);
        TextView name = (TextView) row.getChildAt(0);
        layOut(row);

        AuthorRegion.decorate(name, null, "AZ");
        String shown = name.getText().toString();
        assertTrue(shown, shown.endsWith(" · AZ"));
        assertTrue(shown, shown.contains("…"));

        AuthorRegion.restore();
        assertEquals(LONG_NAME, name.getText().toString());
    }

    /**
     * #127: TikTok's name is 0dp with weight 1 in a wrap_content row. Written without a layout,
     * the country was squeezed into the short name's own width and the name cut to "Rn D… · TR"
     * with half the row empty.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aShortNameInAWeightedRowGrowsRatherThanBeingCut() {
        LinearLayout row = new LinearLayout(context);
        TextView name = new TextView(context);
        name.setId(NAME_ID);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText("Rn Dunyasi");
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        postTime.setText(" · 13s ago");
        row.addView(postTime);
        FrameLayout root = new FrameLayout(context);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layOut(root);
        int before = name.getWidth();

        AuthorRegion.decorate(name, null, "TR");
        layOut(root);
        AuthorRegion.decorate(name, null, "TR");

        assertEquals("Rn Dunyasi · TR", name.getText().toString());
        assertEquals(0, name.getLayout().getEllipsisCount(0));
        assertTrue("the name took the room it needed", name.getWidth() > before);

        AuthorRegion.restore();
        assertEquals("Rn Dunyasi", name.getText().toString());
    }

    /**
     * #127: putting a long display name back over a short "@handle · US" kept the short width in
     * the weighted row, so the name read cut beside an empty row until something else laid it out.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aNamePutBackInAWeightedRowGetsItsWidthBack() {
        LinearLayout row = new LinearLayout(context);
        TextView name = new TextView(context);
        name.setId(NAME_ID);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText("Abdullah Al Mamun Official");
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        postTime.setText(" · 13s ago");
        row.addView(postTime);
        FrameLayout root = new FrameLayout(context);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layOut(root);
        int whole = name.getWidth();

        AuthorRegion.decorate(name, "ab", "US");
        layOut(root);
        AuthorRegion.decorate(name, "ab", "US");
        assertEquals("@ab · US", name.getText().toString());
        assertTrue("the short handle didn't narrow the name", name.getWidth() < whole);

        AuthorRegion.restore();
        layOut(root);

        assertEquals("Abdullah Al Mamun Official", name.getText().toString());
        assertEquals("the name came back cut to the handle's width", 0, name.getLayout().getEllipsisCount(0));
        assertEquals(whole, name.getWidth());
    }

    /**
     * #127 again on 0.71.2 (ottaline): a video change posted ahead of the layout pass reached the
     * row before it was measured with the country, fitted the name to the width the row had for
     * the name alone, and "Shaur… · CA" never grew back, since the name is as wide as its text.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aCallBeforeTheRowIsMeasuredLeavesTheNameWhole() {
        LinearLayout row = new LinearLayout(context);
        TextView name = new TextView(context);
        name.setId(NAME_ID);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText("Rn Dunyasi");
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        postTime.setText(" · 13s ago");
        row.addView(postTime);
        FrameLayout root = new FrameLayout(context);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layOut(root);

        AuthorRegion.decorate(name, null, "TR");
        AuthorRegion.decorate(name, null, "TR");
        layOut(root);
        AuthorRegion.decorate(name, null, "TR");

        assertEquals("the name was cut before the row could make room", "Rn Dunyasi · TR", name.getText().toString());
        assertEquals(0, name.getLayout().getEllipsisCount(0));
        AuthorRegion.restore();
    }

    /**
     * #127, seen on the S22: a photo post's badge reads "Photo" as the post comes up and shrinks
     * to an icon a moment later. A name cut beside the wide badge stayed cut beside the small one.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aCutNameGrowsBackWhenTheRowMakesRoom() {
        LinearLayout row = new LinearLayout(context);
        TextView name = new TextView(context);
        name.setId(NAME_ID);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText("Danielle Scarborough");
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int whole = (int) Math.ceil(name.getPaint().measureText("Danielle Scarborough · US"));
        View badge = new View(context);
        row.addView(badge, new LinearLayout.LayoutParams(400 - whole + 40, 10));
        TextView postTime = new TextView(context);
        postTime.setId(POST_TIME_ID);
        postTime.setVisibility(View.GONE);
        row.addView(postTime);
        FrameLayout root = new FrameLayout(context);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        layOut(root);

        AuthorRegion.decorate(name, null, "US");
        layOut(root);
        AuthorRegion.decorate(name, null, "US");
        layOut(root);
        AuthorRegion.decorate(name, null, "US");
        assertTrue("the wide badge left no room for the whole name", name.getText().toString().contains("…"));
        assertTrue(name.getText().toString().endsWith(" · US"));

        badge.getLayoutParams().width = 400 - whole - 60;
        badge.requestLayout();
        for (int pass = 0; pass < 3; pass++) {
            layOut(root);
            AuthorRegion.decorate(name, null, "US");
        }
        assertEquals("the name stayed cut once the badge gave up its room",
                "Danielle Scarborough · US", name.getText().toString());
        assertEquals(0, name.getLayout().getEllipsisCount(0));

        // Settled: more passes leave it be.
        layOut(root);
        AuthorRegion.decorate(name, null, "US");
        assertEquals("Danielle Scarborough · US", name.getText().toString());
        AuthorRegion.restore();
        assertEquals("Danielle Scarborough", name.getText().toString());
    }

    /** A photo post's badge, with the room it takes before and after it shrinks to an icon. */
    private static final class BadgeRow {
        final FrameLayout root;
        final TextView name;
        final View badge;
        final int whole;

        BadgeRow(Context context, boolean rowKeepsItsWidth) {
            LinearLayout row = new LinearLayout(context);
            name = new TextView(context);
            name.setId(NAME_ID);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText("Danielle Scarborough");
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            whole = (int) Math.ceil(name.getPaint().measureText("Danielle Scarborough · US"));
            badge = new View(context);
            row.addView(badge, new LinearLayout.LayoutParams(400 - whole + 40, 10));
            TextView postTime = new TextView(context);
            postTime.setId(POST_TIME_ID);
            postTime.setVisibility(View.GONE);
            row.addView(postTime);
            root = new FrameLayout(context);
            root.addView(row, new FrameLayout.LayoutParams(rowKeepsItsWidth
                    ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        /** Writes the country and lets the row cut the name beside the wide badge. */
        String cut() {
            layOut(root);
            AuthorRegion.decorate(name, null, "US");
            layOut(root);
            AuthorRegion.decorate(name, null, "US");
            String shown = name.getText().toString();
            assertTrue("the wide badge left no room for the whole name", shown.contains("…"));
            assertTrue(shown.endsWith(" · US"));
            return shown;
        }

        void shrinkBadge() {
            badge.getLayoutParams().width = 400 - whole - 60;
            badge.requestLayout();
        }

        void passes(int count) {
            for (int pass = 0; pass < count; pass++) {
                layOut(root);
                AuthorRegion.decorate(name, null, "US");
            }
        }
    }

    /**
     * The cut is the last thing a pass does, so the very next pass can be the one where the badge
     * shrank. A width read then made the smaller row the baseline and the name never grew back.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aNameCutRightBeforeTheBadgeShrinksStillGrowsBack() {
        BadgeRow photo = new BadgeRow(context, false);
        photo.cut();

        photo.shrinkBadge();
        photo.passes(2);

        assertEquals("Danielle Scarborough · US", photo.name.getText().toString());
        assertEquals(0, photo.name.getLayout().getEllipsisCount(0));
        AuthorRegion.restore();
    }

    /** A row that keeps its width hands the room to the name instead of shrinking. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aCutNameGrowsBackInARowThatKeepsItsWidth() {
        BadgeRow photo = new BadgeRow(context, true);
        photo.cut();

        photo.shrinkBadge();
        photo.passes(2);

        assertEquals("Danielle Scarborough · US", photo.name.getText().toString());
        assertEquals(0, photo.name.getLayout().getEllipsisCount(0));
        AuthorRegion.restore();
    }

    /**
     * A row measured again with nothing beside the name changed gives back only the part of a
     * letter the cut left unused. The cut stays put rather than flashing the whole name.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void measuringTheRowAgainLeavesTheCutAlone() {
        BadgeRow photo = new BadgeRow(context, false);
        String cut = photo.cut();
        CharSequence written = photo.name.getText();

        for (int pass = 0; pass < 3; pass++) {
            photo.name.getParent().requestLayout();
            photo.passes(1);
        }

        assertEquals(cut, photo.name.getText().toString());
        assertSame("the cut text was written again", written, photo.name.getText());
        AuthorRegion.restore();
    }

    /** On a LIVE preview or an ad with no author row, the only row left can be the cell beside it. */
    @Test
    public void aLoneRowOffScreenIsNotTheVideosRow() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            VerticalViewPager pager = feed(activity, "neighbour");
            View content = activity.findViewById(android.R.id.content);

            pager.scrollTo(0, PAGE_HEIGHT);
            assertNull(AuthorRegion.findName(content));

            pager.scrollTo(0, 0);
            assertSame(nameIn(pager, 0), AuthorRegion.findName(content));
        }
    }

    /**
     * A detail page that finishes as it resumes (a trampoline, a video that's gone) took the hook
     * off the feed, and the feed coming back still looked like the window the hook was on.
     */
    @Test
    public void aDetailPageClosingAsItOpensLeavesTheFeedHooked() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            LinearLayout row = feedRow("aittaac");
            feed.get().setContentView(row);
            Utils.setContext(feed.get());
            AuthorRegion.install(feed.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            feed.pause();

            try (ActivityController<DetailActivity> detail = Robolectric.buildActivity(DetailActivity.class).create().start()) {
                detail.get().finish();
                detail.resume();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
            feed.resume();
            play(new Clip("feed", "aittaac", "aittaac", "AZ"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layOut(row.getRootView());
            row.getViewTreeObserver().dispatchOnGlobalLayout();

            assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
        } finally {
            AuthorRegion.restore();
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
            reset.invoke(null);
        }
    }

    /**
     * Opening the feed's own video from the creator's grid strips the feed row, and coming back
     * neither lays the feed out again nor changes the video, so the country has to be put back
     * when the hook returns.
     */
    @Test
    public void aFeedComingBackFromADetailPageGetsItsCountryBackWithoutALayout() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            LinearLayout row = feedRow("aittaac");
            feed.get().setContentView(row);
            Utils.setContext(feed.get());
            AuthorRegion.install(feed.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            play(new Clip("feed", "aittaac", "aittaac", "AZ"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layOut(row.getRootView());
            row.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());

            feed.pause();
            try (ActivityController<DetailActivity> detail = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
                detail.get().setContentView(feedRow("aittaac"));
                AuthorRegion.install(detail.get());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("aittaac", ((TextView) row.getChildAt(0)).getText().toString());
                detail.pause();
                detail.get().finish();
            }
            feed.resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
        } finally {
            AuthorRegion.restore();
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
            reset.invoke(null);
        }
    }

    /**
     * Back pressed within a frame of opening a video: the detail page's posted install runs after
     * it has finished and the feed has resumed. It must leave the feed's row and hook alone.
     */
    @Test
    public void aDetailInstallLandingAfterTheUserWentBackLeavesTheFeedDecorated() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup().visible()) {
            LinearLayout row = feedRow("aittaac");
            feed.get().setContentView(row);
            Utils.setContext(feed.get());
            AuthorRegion.install(feed.get());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            play(new Clip("feed", "aittaac", "aittaac", "AZ"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layOut(row.getRootView());
            row.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());

            feed.pause();
            try (ActivityController<DetailActivity> detail = Robolectric.buildActivity(DetailActivity.class).create().start()) {
                AuthorRegion.install(detail.get());
                detail.get().finish();
                feed.resume();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }

            assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
            ((TextView) row.getChildAt(0)).setText("aittaac");
            row.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("the feed's hook went with the detail page",
                    "aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
        } finally {
            AuthorRegion.restore();
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
            reset.invoke(null);
        }
    }

    /**
     * TikTok's launcher entry is a second MainActivity under the feed's, and a theme change can
     * resume it for a moment while the feed stays in front and gets no new resume. The hook went
     * with it, taking the country off the feed's row, and the install its onCreate posted ran
     * after it had stopped again.
     */
    @Test
    public void aBuriedMainActivityResumedForAMomentHandsTheCountryBack() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<MainActivity> feed = Robolectric.buildActivity(MainActivity.class).setup().visible();
             ActivityController<MainActivity> launcher = Robolectric.buildActivity(MainActivity.class).create()) {
            LinearLayout row = feedRow("aittaac");
            feed.get().setContentView(row);
            Utils.setContext(feed.get());
            AuthorRegion.install(feed.get());
            feed.pause().resume();
            play(new Clip("feed", "aittaac", "aittaac", "AZ"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layOut(row.getRootView());
            row.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());

            launcher.get().setContentView(feedRow("aittaac"));
            AuthorRegion.install(launcher.get());
            // Recreated, the buried copy is resumed for a moment and stopped again, and the
            // install its onCreate posted runs after that.
            launcher.start().resume().pause().stop();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("the feed in front lost its country",
                    "aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
            ((TextView) row.getChildAt(0)).setText("aittaac");
            row.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals("the feed's hook stayed on the buried copy",
                    "aittaac · AZ", ((TextView) row.getChildAt(0)).getText().toString());
        } finally {
            AuthorRegion.restore();
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
            reset.invoke(null);
        }
    }

    /**
     * A detail page started with no feed behind it (a video restored after the process was killed) is hooked from its own onCreate.
     * It lays out while the feed's video is still the current one, and its own video starting is
     * what puts the right country on it.
     */
    @Test
    public void aDetailPageCatchesUpWhenItsOwnVideoStarts() throws Exception {
        Method reset = CurrentVideoAuthor.class.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(null);
        boolean enabled = SettingsStatus.authorRegionEnabled;
        SettingsStatus.authorRegionEnabled = true;
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(true);
        try (ActivityController<DetailActivity> pager = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
            LinearLayout row = feedRow("aittaac");
            pager.get().setContentView(row);
            Utils.setContext(pager.get());
            AuthorRegion.install(pager.get());
            play(new Clip("feed", "aittaac", "aittaac", "US"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            layOut(row.getRootView());
            row.getViewTreeObserver().dispatchOnGlobalLayout();
            TextView name = (TextView) row.getChildAt(0);
            assertEquals("aittaac · US", name.getText().toString());
            // The write's own relayout runs now, so the next video gets no layout pass to ride on.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("aittaac · US", name.getText().toString());

            play(new Clip("opened", "aittaac", "aittaac", "AZ"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("aittaac · AZ", name.getText().toString());
        } finally {
            AuthorRegion.restore();
            SettingsStatus.authorRegionEnabled = enabled;
            Settings.SHOW_AUTHOR_HANDLE.resetToDefault();
            Settings.SHOW_AUTHOR_REGION.resetToDefault();
            reset.invoke(null);
        }
    }

    @Test
    public void aBoundVideoRemembersItsCreatorsCountryForTheCommentList() {
        AuthorRegion.forgetSeenRegions();
        Settings.SHOW_AUTHOR_HANDLE.save(false);
        Settings.SHOW_AUTHOR_REGION.save(false);
        Settings.SHOW_COMMENTER_REGION.save(false);
        AuthorRegion.decoration(playing("a", "SamSmith", "Sam", "gb"));
        assertNull("nothing asks for the country, so nothing is read", AuthorRegion.seenRegion("samsmith"));

        Settings.SHOW_AUTHOR_REGION.save(true);
        AuthorRegion.decoration(playing("b", "SamSmith", "Sam", "gb"));
        assertEquals("GB", AuthorRegion.seenRegion("@samsmith"));
        assertNull(AuthorRegion.seenRegion("someone.else"));
        AuthorRegion.forgetSeenRegions();
    }
}
