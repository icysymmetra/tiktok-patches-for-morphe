package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A recorded screen layout says how the screen is built and nothing the reader read on it.
 *
 * <p>Robolectric moves the looper clock on every activity setup, so each test builds its screens
 * before the capture starts, or counts the time the setup took.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ScreenLayoutTest {
    /** The screen with the thing a reader wants hidden: a banner with words and a handle. */
    public static class TargetActivity extends Activity {
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            FrameLayout root = new FrameLayout(this);
            TextView banner = new TextView(this);
            banner.setId(android.R.id.button1);
            banner.setText("Earn points @private_handle 7291038475610293847");
            banner.setContentDescription("Promoted by someone@example.com");
            TextView hidden = new TextView(this);
            hidden.setId(android.R.id.button2);
            hidden.setVisibility(View.GONE);
            root.addView(banner);
            root.addView(hidden);
            setContentView(root);
        }
    }

    private final List<ScreenLayout.Result> results = new ArrayList<>();
    private Activity settings;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        ScreenLayout.resetForTests();
        // The settings screen the capture starts from, in front and followed as the resumed one.
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        settings = controller.get();
        Utils.setActivity(settings);
        controller.pause().resume();
        emptyDiagnostics();
    }

    @After public void tearDown() {
        ScreenLayout.resetForTests();
        emptyDiagnostics();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @Test public void theScreenInFrontAfterTheDelayIsRecordedWithoutItsText() {
        Robolectric.buildActivity(TargetActivity.class).setup();
        ScreenLayout.start(settings, results::add);
        assertTrue(ScreenLayout.isPending());

        idle(ScreenLayout.DELAY_MS - 1);
        assertTrue("nothing is recorded before the reader's time is up", results.isEmpty());
        idle(1);

        assertEquals(Arrays.asList(ScreenLayout.Result.RECORDED), results);
        assertFalse(ScreenLayout.isPending());
        List<String> lines = ScreenLayout.lines();
        assertEquals("activity: " + TargetActivity.class.getName(), lines.get(0));
        String all = String.join("\n", lines);
        assertTrue(all, all.contains("TextView #button1 "));
        assertTrue("a hidden view says so and its entry name still names it: " + all,
                all.contains("TextView #button2 gone "));
        assertTrue(all, all.contains("window: "));
        for (String leaked : new String[]{"Earn points", "private_handle", "7291038475610293847",
                "someone@example.com", "Promoted"}) {
            assertFalse("the layout carries on-screen text: " + leaked, all.contains(leaked));
        }
    }

    @Test public void theExportCarriesTheLayoutWithNothingElseToReport() {
        Robolectric.buildActivity(TargetActivity.class).setup();
        emptyDiagnostics();
        assertEquals("nothing to report before a capture", "", LogBufferManager.buildExportText());
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        // Even with the reader's event filter set to one category, the layout is included.
        BaseSettings.DEBUG_LOG_FILTERS.save("playback");
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("\n[SCREEN LAYOUT]\nactivity: " + TargetActivity.class.getName()));
        List<String> lines = ScreenLayout.lines();
        assertTrue("the layout is last, so a quick copy that keeps the end keeps it",
                report.endsWith(lines.get(lines.size() - 1) + "\n"));
    }

    @Test public void theExportRedactsEveryLayoutLine() {
        ScreenLayout.restore(Arrays.asList("activity: x.Y",
                " FrameLayout #row 10.1.2.3 1x1", " View #cell 7291038475610293847 2x2"));
        String report = LogBufferManager.buildExportText();
        assertFalse(report, report.contains("10.1.2.3"));
        assertFalse(report, report.contains("7291038475610293847"));
        assertTrue(report, report.contains("activity: x.Y"));
    }

    @Test public void stillInSettingsRecordsNothing() {
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        assertEquals(Arrays.asList(ScreenLayout.Result.STILL_HERE), results);
        assertTrue(ScreenLayout.lines().isEmpty());
    }

    @Test public void aSecondStartReplacesTheFirstAndAnswersOnce() {
        List<ScreenLayout.Result> first = new ArrayList<>();
        ScreenLayout.start(settings, first::add);
        idle(ScreenLayout.DELAY_MS / 2);
        Robolectric.buildActivity(TargetActivity.class).setup();
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        assertTrue("the replaced capture never answers", first.isEmpty());
        assertEquals(Arrays.asList(ScreenLayout.Result.RECORDED), results);
    }

    @Test public void aLargeScreenIsBounded() {
        Activity big = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout column = new LinearLayout(big);
        for (int index = 0; index < ScreenLayout.MAX_VIEWS * 2; index++) {
            column.addView(new View(big));
        }
        big.setContentView(column);
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        assertEquals(Arrays.asList(ScreenLayout.Result.RECORDED), results);
        List<String> lines = ScreenLayout.lines();
        int chars = 0;
        for (String line : lines) chars += line.length() + 1;
        assertTrue("lines: " + lines.size(), lines.size() <= ScreenLayout.MAX_VIEWS + 8);
        assertTrue("chars: " + chars, chars <= ScreenLayout.MAX_CHARS + 400);
        String last = lines.get(lines.size() - 1);
        assertTrue(last, last.matches("\\(\\d+ more views not listed\\)"));
    }

    @Test public void clearTakesTheLayoutAndUndoPutsItBackUnlessANewOneWasRecorded() {
        ScreenLayout.restore(Arrays.asList("activity: first.Screen"));
        LogBufferManager.clearLogBuffer();
        assertTrue(ScreenLayout.lines().isEmpty());
        assertTrue("a clear of only a layout can still be undone", LogBufferManager.canUndoClear());
        LogBufferManager.undoClear();
        assertEquals(Arrays.asList("activity: first.Screen"), ScreenLayout.lines());

        LogBufferManager.clearLogBuffer();
        ScreenLayout.restore(Arrays.asList("activity: newer.Screen"));
        LogBufferManager.undoClear();
        assertEquals("a layout recorded after the clear is the one kept",
                Arrays.asList("activity: newer.Screen"), ScreenLayout.lines());
    }

    @Test @Config(sdk = 35)
    public void aDialogOverTheScreenIsRecordedAsItsOwnWindowWithoutItsTitle() {
        // A dialog left up on the settings screen behind belongs to that screen, not this one.
        AlertDialog behind = new AlertDialog.Builder(settings).setTitle("Settings dialog").create();
        behind.show();
        TargetActivity target = Robolectric.buildActivity(TargetActivity.class).setup().get();
        // Dialog.setTitle writes the title into the window's own title, which a layout line
        // used to print: a promotion sheet's headline is on-screen text.
        AlertDialog sheet = new AlertDialog.Builder(target).setTitle("Earn 500 points now")
                .setMessage("Buy now for $4.99").create();
        sheet.show();
        idle(0);
        // A menu opened from that sheet takes the sheet's window token, not the activity's.
        TextView menuItem = new TextView(target);
        menuItem.setText("Claim now");
        android.widget.PopupWindow menu = new android.widget.PopupWindow(menuItem, 200, 100);
        menu.showAtLocation(sheet.getWindow().getDecorView(), android.view.Gravity.CENTER, 0, 0);
        idle(0);
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        String all = String.join("\n", ScreenLayout.lines());
        int windows = all.split("\nwindow: ", -1).length - 1;
        assertEquals("the screen, its sheet and the sheet's menu: " + all, 3, windows);
        for (String leaked : new String[]{"Buy now", "Earn 500", "points", "Settings dialog", "Claim now"}) {
            assertFalse("the layout carries on-screen text: " + leaked + "\n" + all, all.contains(leaked));
        }
        menu.dismiss();
        sheet.dismiss();
        behind.dismiss();
    }

    @Test @Config(sdk = 35)
    public void aPopupOverAScreenThatFillsTheBoundsIsStillListed() {
        // #162: the feed alone used the whole bounds, so the card over it came out as a bare
        // window line and "(478 more views not listed)".
        Activity feed = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout column = new LinearLayout(feed);
        for (int index = 0; index < ScreenLayout.MAX_VIEWS * 2; index++) {
            column.addView(new View(feed));
        }
        feed.setContentView(column);
        idle(0);
        FrameLayout card = new FrameLayout(feed);
        TextView cardButton = new TextView(feed);
        cardButton.setId(android.R.id.button3);
        cardButton.setText("Discover more");
        card.addView(cardButton);
        android.widget.PopupWindow popup = new android.widget.PopupWindow(card, 600, 300);
        popup.showAtLocation(feed.getWindow().getDecorView(), android.view.Gravity.BOTTOM, 0, 0);
        idle(0);
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        List<String> lines = ScreenLayout.lines();
        String all = String.join("\n", lines);
        int popupAt = all.indexOf("\nwindow: PopupWindow$PopupDecorView");
        assertTrue("the popup window is listed: " + all.substring(Math.max(0, all.length() - 400)),
                popupAt > 0);
        assertTrue("the screen comes first", all.indexOf("\nwindow: ") < popupAt);
        assertTrue("the popup's own views are listed",
                all.substring(popupAt).contains("TextView #button3 "));
        assertFalse(all.contains("Discover more"));
        int views = 0;
        for (String line : lines) {
            if (!line.startsWith("window: ") && !line.startsWith("activity: ")
                    && !line.startsWith("recorded_utc: ") && !line.startsWith("(")) views++;
        }
        assertTrue("views: " + views, views <= ScreenLayout.MAX_VIEWS);
        assertTrue(lines.get(lines.size() - 1).matches("\\(\\d+ more views not listed\\)"));
        popup.dismiss();
    }

    @Test public void aGroupClippedOutOfSightIsCountedNotWalked() {
        // #162: the feed keeps the next video laid out under the screen, clipped by the pager,
        // and walking it used the bounds before the views on screen were reached.
        Activity feed = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout pager = new FrameLayout(feed);
        LinearLayout next = new LinearLayout(feed);
        next.setId(android.R.id.button1);
        for (int index = 0; index < 50; index++) next.addView(new View(feed), 10, 10);
        FrameLayout.LayoutParams below = new FrameLayout.LayoutParams(200, 200);
        below.topMargin = 400;
        pager.addView(next, below);
        TextView shown = new TextView(feed);
        shown.setId(android.R.id.button2);
        pager.addView(shown, new FrameLayout.LayoutParams(100, 100));
        FrameLayout screen = new FrameLayout(feed);
        screen.addView(pager, new FrameLayout.LayoutParams(300, 300));
        feed.setContentView(screen);
        idle(0);
        ScreenLayout.start(settings, results::add);
        idle(ScreenLayout.DELAY_MS);

        String all = String.join("\n", ScreenLayout.lines());
        assertTrue(all, all.contains("LinearLayout #button1 ") && all.contains(" offscreen"));
        assertTrue(all, all.contains("TextView #button2 "));
        assertTrue(all, all.contains("\n(50 views off screen not listed)"));
        for (String line : ScreenLayout.lines()) {
            assertFalse("the clipped group's children are not walked: " + all,
                    line.trim().startsWith("View ") && line.endsWith(" 10x10"));
        }
    }

    @Test public void frameworkClassesAreShortAndTikToksAreInFull() {
        assertEquals("FrameLayout", ScreenLayout.className(FrameLayout.class));
        assertEquals(TargetActivity.class.getName(), ScreenLayout.className(TargetActivity.class));
    }

    private static void idle(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }

    private static void emptyDiagnostics() {
        LogBufferManager.clearLogBuffer();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }
}
