/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.BackgroundPoolSaturation;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.SavedVideoArchiveAccess;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * The shared background queue holds 32 tasks and refuses the next one. A settings row that
 * marked itself busy before asking used to stay busy for good when the queue said no.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class BackgroundQueueRefusalTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Activity activity;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        SavedVideoArchiveAccess.reset(activity);
        ForgetSavedVideosPreference.resetForTests();
    }

    @After public void tearDown() {
        ForgetSavedVideosPreference.resetForTests();
        SavedVideoArchiveAccess.reset(activity);
    }

    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void tap(ForgetSavedVideosPreference row) {
        assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
    }

    /**
     * A refused Forget or Undo changes nothing and gives the row back, and an Undo on offer stays
     * on offer. Each one that does run still does its job.
     */
    @Test public void aFullQueueLeavesForgetAndItsUndoReadyToTryAgain() throws Exception {
        SavedVideoArchiveAccess.remember(activity, "1");
        ForgetSavedVideosPreference row = new ForgetSavedVideosPreference(activity);

        try (BackgroundPoolSaturation full = BackgroundPoolSaturation.fill()) {
            tap(row);
            assertTrue("Forget stayed busy after the queue refused it", row.isEnabled());
            assertEquals(ForgetSavedVideosPreference.SUMMARY, String.valueOf(row.getSummary()));
        }
        assertTrue("a refused Forget erased the record", SavedVideoArchiveAccess.isRecorded(activity, "1"));

        tap(row);
        settle();
        assertFalse(SavedVideoArchiveAccess.isRecorded(activity, "1"));
        assertEquals(ForgetSavedVideosPreference.UNDO_TITLE, String.valueOf(row.getTitle()));

        try (BackgroundPoolSaturation full = BackgroundPoolSaturation.fill()) {
            tap(row);
            assertTrue("Undo stayed busy after the queue refused it", row.isEnabled());
            assertEquals("the refused Undo took its offer with it",
                    ForgetSavedVideosPreference.UNDO_TITLE, String.valueOf(row.getTitle()));
        }
        assertFalse("a refused Undo put the record back", SavedVideoArchiveAccess.isRecorded(activity, "1"));

        tap(row);
        settle();
        assertTrue("the Undo that ran left the record out", SavedVideoArchiveAccess.isRecorded(activity, "1"));
    }

    /**
     * The app list says it couldn't look, whether the queue refused the lookup or the lookup
     * threw, and a tap looks again. Only a lookup that worked and found nothing tells the
     * reader to install an app.
     */
    @Test public void theAppListSaysWhenItCouldNotLookAndLooksAgainOnATap() throws Exception {
        boolean[] failLookups = {false};
        Context context = new ContextWrapper(activity) {
            @Override public PackageManager getPackageManager() {
                if (failLookups[0] && Looper.myLooper() != Looper.getMainLooper()) {
                    throw new SecurityException("package visibility denied");
                }
                return super.getPackageManager();
            }
        };
        ShareAppPickerPreference picker = new ShareAppPickerPreference(context);
        View root;
        try (BackgroundPoolSaturation full = BackgroundPoolSaturation.fill()) {
            root = picker.onCreateDialogView();
            assertFalse("a refused lookup left the list looking", hasText(root, "Looking for apps"));
            assertNotNull("a refused lookup offered no way to try again", root.findViewWithTag("share_app_retry"));
            assertFalse(hasText(root, "No other app takes shared text"));
        }

        failLookups[0] = true;
        assertTrue(root.findViewWithTag("share_app_retry").performClick());
        settle();
        assertTrue("a lookup that threw didn't say so",
                hasText(root, "Couldn't list your apps. Tap here to try again."));
        assertFalse("a lookup that threw said no app takes shared text",
                hasText(root, "No other app takes shared text"));

        failLookups[0] = false;
        assertTrue(root.findViewWithTag("share_app_retry").performClick());
        settle();
        assertNull("the retry offer stayed after a lookup that worked", root.findViewWithTag("share_app_retry"));
        assertTrue(hasText(root, "No other app takes shared text"));
    }

    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && text.equals(String.valueOf(((TextView) view).getText()))) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), text)) return true;
            }
        }
        return false;
    }
}
