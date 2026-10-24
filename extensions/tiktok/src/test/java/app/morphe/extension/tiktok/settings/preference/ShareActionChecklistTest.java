package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.share.ShareSurface;

import org.junit.Rule;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ShareActionChecklistTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void reset() {
        Settings.SHARE_ACTION_CATALOG.save("");
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.SHARE_HIDDEN_ITEMS_PROFILE.save(Settings.SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO);
        Settings.SHARE_HIDDEN_ITEMS_LIVE.save(Settings.SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO);
    }

    @Test public void theProfileChecklistStartsFromTheVideoListAndSavesItsOwn() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.SHARE_ACTION_CATALOG.save(
                    "copy\tCopy link\tvp\nqr code\tQR code\tp\nduet\tDuet\tv");
            Settings.SHARE_HIDDEN_ITEMS.save("copy, unknown_key");

            ShareActionChecklistPreference preference =
                    new ShareActionChecklistPreference(activity, ShareSurface.PROFILE);
            assertEquals("Profile share actions", preference.getTitle().toString());
            View view = preference.onCreateDialogView();
            assertTrue("the profile list opens on what that sheet hides today",
                    findCheckBox(view, "share_action_copy").isChecked());
            assertNull("an action only ever seen on videos is not offered for profiles",
                    findCheckBox(view, "share_action_duet"));
            CheckBox qr = findCheckBox(view, "share_action_qr code");
            assertNotNull(qr);
            assertFalse(qr.isChecked());
            qr.performClick();
            preference.onDialogClosed(true);

            assertEquals("the video list is untouched", "copy, unknown_key",
                    Settings.SHARE_HIDDEN_ITEMS.get());
            assertEquals("unknown_key, copy, qr code", Settings.SHARE_HIDDEN_ITEMS_PROFILE.get());
            assertEquals("LIVE still follows the video list",
                    Settings.SHARE_HIDDEN_ITEMS_FOLLOW_VIDEO, Settings.SHARE_HIDDEN_ITEMS_LIVE.get());
        }
    }

    @Test public void checklistSearchesObservedActionsAndPreservesUnknownIdentifiers() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Settings.SHARE_ACTION_CATALOG.save("copy\tCopy link\nweird action\tCustom action");
            Settings.SHARE_HIDDEN_ITEMS.save("copy, unknown_key");

            ShareActionChecklistPreference preference = new ShareActionChecklistPreference(activity);
            View view = preference.onCreateDialogView();
            EditText search = view.findViewWithTag("share_action_search");
            assertNotNull(search);
            TextView count = view.findViewWithTag("share_action_result_count");
            assertNotNull(count);
            assertEquals("2 results", count.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    count.getAccessibilityLiveRegion());
            TextView title = (TextView) ((ViewGroup) view).getChildAt(0);
            assertTrue("the dialog title is not exposed as a heading",
                    title.isAccessibilityHeading());
            CheckBox copy = findCheckBox(view, "share_action_copy");
            CheckBox weird = findCheckBox(view, "share_action_weird action");
            assertNotNull(copy);
            assertNotNull(weird);
            assertTrue(copy.isChecked());
            copy.performClick();

            search.setText("custom");
            assertNotNull(findCheckBox(view, "share_action_weird action"));
            assertEquals("1 result", count.getText().toString());
            search.setText("does not exist");
            assertEquals("0 results", count.getText().toString());
            search.setText("custom");
            weird.performClick();
            preference.onDialogClosed(true);
            assertEquals("unknown_key, weird action", Settings.SHARE_HIDDEN_ITEMS.get());

            ShareActionChecklistPreference reopened = new ShareActionChecklistPreference(activity);
            View reopenedView = reopened.onCreateDialogView();
            assertTrue(findCheckBox(reopenedView, "share_action_weird action").isChecked());
            assertFalse(findCheckBox(reopenedView, "share_action_copy").isChecked());
        }
    }

    private static CheckBox findCheckBox(View view, String tag) {
        if (view instanceof CheckBox && tag.equals(view.getTag())) {
            return (CheckBox) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                CheckBox found = findCheckBox(group.getChildAt(index), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test @Config(qualifiers = "night")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aFailedChecklistWriteKeepsItsSelectionAndCanBeRetried() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            SettingsUi.syncDarkMode(activity);
            String theme = SettingsUi.isDarkMode() ? "dark" : "light";
            Settings.SHARE_ACTION_CATALOG.save("copy\tCopy link");
            Settings.SHARE_HIDDEN_ITEMS.save("unknown_key");
            var fragment = new ActionRowChevronTest.HostFragment();
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();
            var screen = fragment.getPreferenceManager().createPreferenceScreen(activity);
            fragment.setPreferenceScreen(screen);
            var row = new ShareActionChecklistPreference(activity);
            screen.addPreference(row);
            var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
            try (var failure = new app.morphe.extension.tiktok.PreferenceCommitFailure(
                    keys -> keys.contains(Settings.SHARE_HIDDEN_ITEMS.key)
                            && failOnce.getAndSet(false), false)) {
                row.showDialog(null);
                var dialog = (android.app.AlertDialog) row.getDialog();
                CheckBox copy = findCheckBox(dialog.getWindow().getDecorView(), "share_action_copy");
                assertNotNull(copy);
                copy.performClick();
                org.robolectric.shadows.ShadowToast.reset();
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                Utils.awaitBackgroundTasksForTests();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertEquals("unknown_key", Settings.SHARE_HIDDEN_ITEMS.get());
                assertTrue("a failed write discarded the checklist", dialog.isShowing());
                assertTrue("the draft selection was lost", copy.isChecked());
                assertNotNull("the save failed silently",
                        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
                View decor = dialog.getWindow().getDecorView();
                decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST));
                app.morphe.extension.tiktok.UiCapture.save(decor,
                        "audit/share-checklist-save-failure-" + theme + ".png", 480, decor.getMeasuredHeight());
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                Utils.awaitBackgroundTasksForTests();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertEquals("unknown_key, copy", Settings.SHARE_HIDDEN_ITEMS.get());
                assertFalse("a successful retry did not close the checklist", dialog.isShowing());
            }
        }
    }

    @Test @Config(qualifiers = "notnight")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aLightChecklistKeepsItsFailedDraftAndRetry() throws Exception {
        aFailedChecklistWriteKeepsItsSelectionAndCanBeRetried();
    }
}
