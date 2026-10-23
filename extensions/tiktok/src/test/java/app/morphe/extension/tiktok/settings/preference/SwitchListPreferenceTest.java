package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * One row over several switches. The second line has to say what is on, the dialog has to
 * write every tick back to its own setting, and Cancel has to write nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SwitchListPreferenceTest {
    private static final String DESCRIPTION =
            "Pick the buttons to take off the column. The rest stay where they are.";

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_RAIL_LIKE.resetToDefault();
        Settings.HIDE_RAIL_SHARE.resetToDefault();
    }

    @After public void tearDown() {
        Settings.HIDE_RAIL_LIKE.resetToDefault();
        Settings.HIDE_RAIL_SHARE.resetToDefault();
    }

    private static SwitchListPreference row(Activity activity) {
        return new SwitchListPreference(activity, "test_hides", "Hide buttons in the right column",
                DESCRIPTION, List.of(
                        new SwitchListPreference.Item("Like", Settings.HIDE_RAIL_LIKE),
                        new SwitchListPreference.Item("Share", Settings.HIDE_RAIL_SHARE)));
    }

    @Test public void theSecondLineNamesWhatIsHiddenAndSaveWritesTheTicks() throws Exception {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            SwitchListPreference row = row(activity);
            assertEquals(DESCRIPTION + "\nNothing hidden.", row.getSummary().toString());
            assertEquals(2, row.settings().size());

            Settings.HIDE_RAIL_SHARE.save(true);
            assertEquals(DESCRIPTION + "\nHidden: Share", row.getSummary().toString());

            row.onClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the row opened no dialog", dialog);
            ListView list = dialog.getListView();
            // The platform applies the starting ticks as it builds each row, so the list has
            // to lay out before they can be read.
            list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST));
            list.layout(0, 0, 1080, list.getMeasuredHeight());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("Share starts ticked", list.isItemChecked(1));
            assertFalse("Like starts clear", list.isItemChecked(0));
            list.performItemClick(null, 0, 0);
            list.performItemClick(null, 1, 1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("Like was ticked and saved", Settings.HIDE_RAIL_LIKE.get());
            assertFalse("Share was cleared and saved", Settings.HIDE_RAIL_SHARE.get());
            assertEquals(DESCRIPTION + "\nHidden: Like", row.getSummary().toString());
        }
    }

    /**
     * A recreation while the save runs takes the dialog's window with the old activity. The save
     * still lands and the row still updates; dismissing the gone window threw instead.
     */
    @Test public void aSaveThatEndsAfterTheWindowIsGoneStillLands() throws Exception {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            SwitchListPreference row = row(activity);
            row.onClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialog.getListView().performItemClick(null, 1, 1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Utils.awaitBackgroundTasksForTests();
            activity.getWindowManager().removeViewImmediate(dialog.getWindow().getDecorView());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the save was lost", Settings.HIDE_RAIL_SHARE.get());
            assertEquals(DESCRIPTION + "\nHidden: Share", row.getSummary().toString());
            // On a phone, dismissing it throws "not attached to window manager". Robolectric's
            // window manager doesn't, so what's checked is that nothing tried: dismiss() is the
            // only thing that clears isShowing.
            assertTrue("the gone dialog was dismissed again", dialog.isShowing());
        }
    }

    @Test public void cancelLeavesEverySwitchAsItWas() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            SwitchListPreference row = row(activity);
            row.onClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            dialog.getListView().performItemClick(null, 0, 0);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("Cancel wrote a tick", Settings.HIDE_RAIL_LIKE.get());
            assertEquals(DESCRIPTION + "\nNothing hidden.", row.getSummary().toString());
        }
    }

    @Test @Config(qualifiers = "night")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aFailedChecklistBatchChangesNothingAndKeepsAWorkingRetry() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsUi.syncDarkMode(activity);
            String theme = SettingsUi.isDarkMode() ? "dark" : "light";
            SwitchListPreference row = row(activity);
            var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
            try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(
                    keys -> keys.contains(Settings.HIDE_RAIL_SHARE.key)
                            && failOnce.getAndSet(false), false)) {
                row.onClick();
                AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
                ListView list = dialog.getListView();
                list.performItemClick(null, 0, 0);
                list.performItemClick(null, 1, 1);
                org.robolectric.shadows.ShadowToast.reset();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                Utils.awaitBackgroundTasksForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertFalse("the first setting survived a failed batch", Settings.HIDE_RAIL_LIKE.get());
                assertFalse(Settings.HIDE_RAIL_SHARE.get());
                assertTrue("the failed batch discarded the choices", dialog.isShowing());
                assertTrue(list.isItemChecked(0));
                assertTrue(list.isItemChecked(1));
                assertNotNull("the write failed silently",
                        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
                View decor = dialog.getWindow().getDecorView();
                decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST));
                app.morphe.extension.tiktok.UiCapture.save(decor,
                        "audit/switch-list-save-failure-" + theme + ".png", 480, decor.getMeasuredHeight());
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                Utils.awaitBackgroundTasksForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(Settings.HIDE_RAIL_LIKE.get());
                assertTrue(Settings.HIDE_RAIL_SHARE.get());
                assertFalse(dialog.isShowing());
            }
        }
    }

    @Test @Config(qualifiers = "notnight")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aLightChecklistKeepsItsFailedBatchAndRetry() throws Exception {
        aFailedChecklistBatchChangesNothingAndKeepsAWorkingRetry();
    }
}
