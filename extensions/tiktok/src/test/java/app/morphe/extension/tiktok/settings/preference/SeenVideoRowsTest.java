package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;

import android.app.Activity;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The seen-video rows say whose record they act on, since each account keeps its own. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SeenVideoRowsTest {
    private Activity activity;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Settings.HIDE_SEEN_VIDEOS.save(true);
        // An account of its own per test: a switch starts from an empty record with no undo on offer.
        SignedInUser.idForTests = "1" + System.nanoTime();
    }

    @After public void tearDown() {
        SignedInUser.idForTests = null;
        SignedInUser.handleForTests = null;
        Settings.HIDE_SEEN_VIDEOS.resetToDefault();
    }

    @Test public void theClearRowNamesTheAccountWhoseRecordItForgets() {
        SeenVideoHistory.onPlayProgressChange("v1", 5000, 10000);
        SeenVideoHistory.onPlayProgressChange("v2", 5000, 10000);

        SignedInUser.handleForTests = "poster";
        assertEquals("Forget the 2 videos @poster has seen.",
                String.valueOf(new ClearSeenVideoHistoryPreference(activity).getSummary()));

        // Without a handle to name, the row says what it did before.
        SignedInUser.handleForTests = null;
        assertEquals("Forget the 2 seen videos.",
                String.valueOf(new ClearSeenVideoHistoryPreference(activity).getSummary()));
    }

    /** One record reads as one: the S22 showed "Forget the 1 videos @... has seen." */
    @Test public void aSingleRecordIsNamedInTheSingular() {
        SeenVideoHistory.onPlayProgressChange("v1", 5000, 10000);
        SignedInUser.handleForTests = "poster";
        assertEquals("Forget the video @poster has seen.",
                String.valueOf(new ClearSeenVideoHistoryPreference(activity).getSummary()));
        SignedInUser.handleForTests = null;
        assertEquals("Forget the seen video.",
                String.valueOf(new ClearSeenVideoHistoryPreference(activity).getSummary()));
        assertEquals("One video was recorded before each account kept its own list. It hides nothing "
                + "until you add it here.",
                String.valueOf(new AdoptSeenVideoHistoryPreference(activity, 1).getSummary()));
    }

    @Test public void theAddRowSaysHowManyOlderRecordsItWouldAdd() {
        AdoptSeenVideoHistoryPreference row = new AdoptSeenVideoHistoryPreference(activity, 1234);
        assertEquals(AdoptSeenVideoHistoryPreference.TITLE, String.valueOf(row.getTitle()));
        assertEquals("1,234 videos were recorded before each account kept its own list. They hide "
                + "nothing until you add them here.", String.valueOf(row.getSummary()));
    }
}
