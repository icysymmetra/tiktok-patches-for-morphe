package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.main.MainActivity;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** The music title belongs to its own container, separate from the caption and rail disc (#68). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoMusicLineTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The declared build. OLD_TITLE stands for the id an older build gave the title. */
    private static final String[] BUILDS = {"47.4.3"};
    private static final int CELL = 0x7f0a7800;
    private static final int OLD_TITLE = 0x7f0a7801;
    private static final int NEW_TITLE = 0x7f0a7802;
    private static final int CAPTION = 0x7f0a7803;
    private static final int COVER = 0x7f0a7804;
    private static final int NEW_DISC = 0x7f0a7806;

    @Before public void setUp() {
        PausedProcess.set(false);
        Settings.HIDE_FEED_CAPTION.save(false);
        Settings.CAPTION_ABOVE_COMMENTS.save(false);
        Settings.HIDE_FEED_MUSIC.save(false);
        Settings.HIDE_RAIL_MUSIC.save(false);
    }

    @After public void reset() throws ReflectiveOperationException {
        PausedProcess.set(false);
        Settings.HIDE_FEED_CAPTION.save(false);
        Settings.HIDE_FEED_MUSIC.save(false);
        Settings.HIDE_RAIL_MUSIC.save(false);
        BuildNames.setRunningBuildForTests(null);
        // Robolectric reuses the sandbox. These cell IDs must not select another test's roots.
        var ids = VideoOverlayHider.class.getDeclaredField("RESOURCE_IDS");
        ids.setAccessible(true);
        ((ResourceIdCache) ids.get(null)).clear();
    }

    @Test public void ordinaryAndContainsTitlesFollowRecycledCellsAndBothWindows() {
        for (String build : BUILDS) {
            bindIds(build);
            try (var main = Robolectric.buildActivity(MainActivity.class).setup();
                 var detail = Robolectric.buildActivity(DetailActivity.class).create().start()) {
                LinearLayout feed = new LinearLayout(main.get());
                Title ordinary = new Title(main.get(), titleId(build), false);
                Title contains = new Title(main.get(), titleId(build), true);
                feed.addView(cell(main.get(), ordinary.root));
                feed.addView(cell(main.get(), contains.root));
                main.get().setContentView(feed);
                Settings.HIDE_FEED_MUSIC.save(true);
                VideoOverlayHider.install(main.get());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                layout(main.get());
                ordinary.assertHidden();
                contains.assertHidden();

                // TikTok rebinds an existing cell and inflates another neighbouring cell.
                ordinary.bind(true);
                ordinary.root.setVisibility(View.VISIBLE);
                Title added = new Title(main.get(), titleId(build), false);
                feed.addView(cell(main.get(), added.root));
                layout(main.get());
                ordinary.assertHidden();
                contains.assertHidden();
                added.assertHidden();

                main.pause();
                Title detailOrdinary = new Title(detail.get(), titleId(build), false);
                Title detailContains = new Title(detail.get(), titleId(build), true);
                detail.get().setContentView(cell(detail.get(), detailOrdinary.root, detailContains.root));
                detail.resume().visible();
                layout(detail.get());
                detailOrdinary.assertHidden();
                detailContains.assertHidden();

                Settings.HIDE_FEED_MUSIC.save(false);
                layout(detail.get());
                assertEquals(View.VISIBLE, detailOrdinary.root.getVisibility());
                assertEquals(View.VISIBLE, detailContains.root.getVisibility());
                detail.pause();
                main.resume();
                layout(main.get());
                assertEquals(View.VISIBLE, ordinary.root.getVisibility());
                assertEquals(View.VISIBLE, contains.root.getVisibility());
                assertEquals(View.VISIBLE, added.root.getVisibility());
            }
        }
    }

    @Test public void switchOffAndPauseRestoreTheOriginalTitleVisibilityOnBothRoutes() {
        for (String build : BUILDS) {
            bindIds(build);
            for (Class<? extends Activity> route : List.of(MainActivity.class, DetailActivity.class)) {
                try (var controller = Robolectric.buildActivity(route).setup()) {
                    Activity activity = controller.get();
                    for (boolean paused : new boolean[]{false, true}) {
                        int[] original = {View.VISIBLE, View.INVISIBLE, View.GONE};
                        Title[] titles = new Title[original.length];
                        LinearLayout feed = new LinearLayout(activity);
                        for (int i = 0; i < titles.length; i++) {
                            titles[i] = new Title(activity, titleId(build), i != 0);
                            titles[i].root.setVisibility(original[i]);
                            feed.addView(cell(activity, titles[i].root));
                        }
                        activity.setContentView(feed);
                        Settings.HIDE_FEED_MUSIC.save(true);
                        VideoOverlayHider.applyTo(activity);
                        VideoOverlayHider.applyTo(activity);
                        for (Title title : titles) title.assertHidden();

                        if (paused) PausedProcess.set(true);
                        else Settings.HIDE_FEED_MUSIC.save(false);
                        VideoOverlayHider.applyTo(activity);
                        for (int i = 0; i < titles.length; i++) {
                            assertEquals(build + " / " + route.getSimpleName() + " / paused=" + paused,
                                    original[i], titles[i].root.getVisibility());
                        }
                        if (paused) {
                            assertTrue("Pause preserves the saved switch", Settings.HIDE_FEED_MUSIC.savedValue());
                            PausedProcess.set(false);
                            VideoOverlayHider.applyTo(activity);
                            for (Title title : titles) title.assertHidden();
                        }
                        Settings.HIDE_FEED_MUSIC.save(false);
                        VideoOverlayHider.applyTo(activity);
                    }
                }
            }
        }
    }

    @Test public void theCaptionDiscCoverAndOutOfCellOrOtherBuildNamesStayIndependent() {
        for (String build : BUILDS) {
            bindIds(build);
            for (Class<? extends Activity> route : List.of(MainActivity.class, DetailActivity.class)) {
                try (var controller = Robolectric.buildActivity(route).setup()) {
                    Activity activity = controller.get();
                    FrameLayout root = new FrameLayout(activity);
                    Title title = new Title(activity, titleId(build), true);
                    View caption = view(activity, CAPTION);
                    View disc = view(activity, discId(build));
                    View cover = view(activity, COVER);
                    Title otherBuild = new Title(activity, OLD_TITLE, false);
                    Title outside = new Title(activity, titleId(build), true);
                    root.addView(cell(activity, title.root, caption, disc, cover, otherBuild.root));
                    root.addView(outside.root);
                    activity.setContentView(root);

                    Settings.HIDE_FEED_MUSIC.save(true);
                    VideoOverlayHider.applyTo(activity);
                    title.assertHidden();
                    for (View kept : new View[]{caption, disc, cover, otherBuild.root, outside.root}) {
                        assertEquals("a label hide took unrelated content", View.VISIBLE, kept.getVisibility());
                    }

                    Settings.HIDE_FEED_MUSIC.save(false);
                    Settings.HIDE_FEED_CAPTION.save(true);
                    Settings.HIDE_RAIL_MUSIC.save(true);
                    VideoOverlayHider.applyTo(activity);
                    assertEquals(View.VISIBLE, title.root.getVisibility());
                    assertEquals(View.GONE, caption.getVisibility());
                    assertEquals(View.GONE, disc.getVisibility());
                    assertEquals(View.VISIBLE, cover.getVisibility());
                    Settings.HIDE_FEED_CAPTION.save(false);
                    Settings.HIDE_RAIL_MUSIC.save(false);
                    VideoOverlayHider.applyTo(activity);
                    assertEquals(View.VISIBLE, caption.getVisibility());
                    assertEquals(View.VISIBLE, disc.getVisibility());
                }
            }
        }
    }

    private static void bindIds(String build) {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BuildNames.setRunningBuildForTests(build);
        VideoOverlayHider.resolveForTests("view_rootview", CELL);
        VideoOverlayHider.resolveForTests("desc", CAPTION);
        VideoOverlayHider.resolveForTests("videomusiccoverblock", COVER);
        VideoOverlayHider.resolveForTests("o6f", OLD_TITLE);
        VideoOverlayHider.resolveForTests("o7a", NEW_TITLE);
        for (String candidate : BUILDS) {
            String title = candidate + ":o7a";
            String disc = candidate + ":pop";
            // The cache's test override is literal. Another build resolves to zero in the app.
            VideoOverlayHider.resolveForTests(title, BuildNames.entryName(title) == null ? 0 : titleId(candidate));
            VideoOverlayHider.resolveForTests(disc, BuildNames.entryName(disc) == null ? 0 : discId(candidate));
        }
    }

    private static int titleId(String build) { return NEW_TITLE; }
    private static int discId(String build) { return NEW_DISC; }

    private static View view(Activity activity, int id) {
        View view = new View(activity);
        view.setId(id);
        return view;
    }

    private static FrameLayout cell(Activity activity, View... children) {
        FrameLayout cell = new FrameLayout(activity);
        cell.setId(CELL);
        for (View child : children) cell.addView(child);
        return cell;
    }

    private static void layout(Activity activity) {
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout();
    }

    /** VideoMusicTitleAssem writes both its ordinary TextView and alternate marquee branch. */
    private static final class Title {
        final LinearLayout root;
        final TextView ordinary;
        final TextView marquee;
        boolean matched;

        Title(Activity activity, int id, boolean matched) {
            root = new LinearLayout(activity);
            root.setId(id);
            ordinary = new TextView(activity);
            marquee = new TextView(activity);
            root.addView(ordinary);
            root.addView(marquee);
            bind(matched);
        }

        void bind(boolean matched) {
            this.matched = matched;
            ordinary.setText(matched ? "Contains: matched song" : "ordinary song");
            marquee.setText(ordinary.getText());
            ordinary.setVisibility(matched ? View.GONE : View.VISIBLE);
            marquee.setVisibility(matched ? View.VISIBLE : View.GONE);
        }

        void assertHidden() {
            assertEquals(View.GONE, root.getVisibility());
            assertFalse(ordinary.isShown());
            assertFalse(marquee.isShown());
            assertEquals(matched ? View.GONE : View.VISIBLE, ordinary.getVisibility());
            assertEquals(matched ? View.VISIBLE : View.GONE, marquee.getVisibility());
        }
    }
}
