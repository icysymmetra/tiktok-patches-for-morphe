package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.profile.model.User;

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
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;

import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class CopyIdsTest {
    private ActivityController<Activity> owner;
    private Activity activity;
    private boolean previousSetting, previousStatus;

    /** A video's share package, with the members the extension reads by name. */
    public static class VideoPackage {
        public String itemType = "aweme";
        public Video aweme;
    }

    public static final class Video {
        public String aid;

        public String getAid() { return aid; }
    }

    public static class ProfilePackage {
        public String itemType = "user";
        public User user;
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousSetting = Settings.COPY_IDS.get();
        previousStatus = SettingsStatus.copyIdsEnabled;
        Settings.COPY_IDS.save(true);
        SettingsStatus.copyIdsEnabled = true;
        owner = Robolectric.buildActivity(Activity.class).setup();
        activity = owner.get();
    }

    @After public void tearDown() {
        Settings.COPY_IDS.save(previousSetting);
        SettingsStatus.copyIdsEnabled = previousStatus;
        Settings.ACCOUNT_FACTS.resetToDefault();
        CopyIds.onSharePackage(null);
        if (owner != null) owner.close();
    }

    @Test public void aProfileSheetCopiesTheUsernameAndUserIdAndAVideoSheetItsId() {
        List<CopyIds.Target> profile = CopyIds.targetsOf(profile("6812345678901234567", "nasa"));
        assertEquals(2, profile.size());
        assertEquals(CopyIds.COPY_USERNAME, profile.get(0).label);
        assertEquals("nasa", profile.get(0).text);
        assertEquals(CopyIds.COPY_USER_ID, profile.get(1).label);
        assertEquals("6812345678901234567", profile.get(1).text);

        List<CopyIds.Target> video = CopyIds.targetsOf(video("7312345678901234567"));
        assertEquals(1, video.size());
        assertEquals(CopyIds.COPY_VIDEO_ID, video.get(0).label);
        assertEquals("7312345678901234567", video.get(0).text);

        assertTrue("a profile without an account", CopyIds.targetsOf(new ProfilePackage()).isEmpty());
        assertTrue("a sheet for something else", CopyIds.targetsOf(new Object()).isEmpty());
        assertTrue(CopyIds.targetsOf(null).isEmpty());
    }

    @Test public void longPressingTheBioCopiesWhatItWasBoundWith() {
        TextView bio = new TextView(activity);
        bio.setText("Exploring the universe... more");
        activity.setContentView(bio);

        CopyIds.onBio(bio, "Exploring the universe and our home planet");
        assertTrue(bio.performLongClick());
        assertEquals("Exploring the universe and our home planet", clip());
        assertEquals("Bio copied", ShadowToast.getTextOfLatestToast());

        Settings.COPY_IDS.save(false);
        clipboard().setPrimaryClip(android.content.ClipData.newPlainText("before", "before"));
        assertFalse("the switch is read at the press", bio.performLongClick());
        assertEquals("before", clip());
    }

    @Test public void anOffSwitchLeavesTheBioAlone() {
        Settings.COPY_IDS.save(false);
        TextView bio = new TextView(activity);
        CopyIds.onBio(bio, "Hello");
        assertFalse(bio.isLongClickable());
    }

    @Test public void theSheetGetsAButtonRowThatMovesTheActionsUpAndGoesAwayAgain() {
        FrameLayout panel = panel();
        View actions = panel.getChildAt(0);

        CopyIds.onSharePackage(profile("6812345678901234567", "nasa"));
        CopyIds.onSharePanel(panel);
        idle();

        assertEquals(2, panel.getChildCount());
        LinearLayout row = (LinearLayout) panel.getChildAt(1);
        assertEquals(CopyIds.ROW_TAG, row.getTag());
        assertEquals(2, row.getChildCount());
        assertEquals("Copy username", ((TextView) row.getChildAt(0)).getText().toString());
        int moved = ((ViewGroup.MarginLayoutParams) actions.getLayoutParams()).bottomMargin;
        assertTrue("the action list makes room for the row", moved > 0);

        assertTrue(row.getChildAt(1).performClick());
        assertEquals("6812345678901234567", clip());
        assertEquals("User ID copied", ShadowToast.getTextOfLatestToast());

        // The same panel attached again for a video reuses the row.
        CopyIds.onSharePackage(video("7312345678901234567"));
        CopyIds.onSharePanel(panel);
        idle();
        assertEquals(2, panel.getChildCount());
        assertEquals(1, row.getChildCount());
        assertEquals("Copy video ID", ((TextView) row.getChildAt(0)).getText().toString());
        assertTrue(row.getChildAt(0).performClick());
        assertEquals("7312345678901234567", clip());
        assertEquals("Video ID copied", ShadowToast.getTextOfLatestToast());

        // A sheet with nothing to copy hides the row and gives the actions their margin back.
        CopyIds.onSharePackage(new Object());
        CopyIds.onSharePanel(panel);
        idle();
        assertEquals(View.GONE, row.getVisibility());
        assertEquals(0, ((ViewGroup.MarginLayoutParams) actions.getLayoutParams()).bottomMargin);
    }

    @Test public void aProfileSheetWithoutActionsGetsTheRowUnderItsApps() {
        LinearLayout sheet = new LinearLayout(activity);
        FrameLayout apps = frame("Copy link");
        sheet.addView(apps);
        activity.setContentView(sheet);

        CopyIds.onSharePackage(profile("6812345678901234567", "nasa"));
        CopyIds.onAppsPanel(apps);
        idle();

        assertEquals(2, apps.getChildCount());
        LinearLayout row = (LinearLayout) apps.getChildAt(1);
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals("Copy username", ((TextView) row.getChildAt(0)).getText().toString());
        assertEquals("Copy user ID", ((TextView) row.getChildAt(1)).getText().toString());
        assertTrue("the apps make room for the row",
                ((ViewGroup.MarginLayoutParams) apps.getChildAt(0).getLayoutParams()).bottomMargin > 0);
    }

    @Test public void aSheetWithActionsKeepsTheRowThereEvenWhenTheAppsCameFirst() {
        LinearLayout sheet = new LinearLayout(activity);
        FrameLayout apps = frame("Copy link");
        sheet.addView(apps);
        activity.setContentView(sheet);
        CopyIds.onSharePackage(video("7312345678901234567"));
        CopyIds.onAppsPanel(apps);
        idle();
        assertEquals("no action panel yet, so the apps show it", View.VISIBLE, apps.getChildAt(1).getVisibility());

        FrameLayout actions = frame("Report");
        sheet.addView(actions);
        CopyIds.onSharePanel(actions);
        idle();
        assertEquals(View.GONE, apps.getChildAt(1).getVisibility());
        assertEquals(0, ((ViewGroup.MarginLayoutParams) apps.getChildAt(0).getLayoutParams()).bottomMargin);
        assertEquals(2, actions.getChildCount());
        assertEquals("Copy video ID", ((TextView) ((LinearLayout) actions.getChildAt(1)).getChildAt(0)).getText().toString());

        // The apps panel attached again in the same sheet leaves the row to the actions.
        CopyIds.onAppsPanel(apps);
        idle();
        assertEquals(View.GONE, apps.getChildAt(1).getVisibility());
        assertEquals(View.VISIBLE, actions.getChildAt(1).getVisibility());
    }

    @Test public void anOffSwitchAddsNoRow() {
        Settings.COPY_IDS.save(false);
        FrameLayout panel = panel();
        CopyIds.onSharePackage(video("7312345678901234567"));
        CopyIds.onSharePanel(panel);
        idle();
        assertEquals(1, panel.getChildCount());
        assertEquals(0, ((ViewGroup.MarginLayoutParams) panel.getChildAt(0).getLayoutParams()).bottomMargin);
    }

    @Test public void accountFactsAddsItsOwnButtonToProfileSheetsOnly() {
        Settings.ACCOUNT_FACTS.save(true);
        List<CopyIds.Target> profile = CopyIds.targetsOf(profile("6812345678901234567", "nasa"));
        assertEquals(3, profile.size());
        assertEquals(AccountFacts.SHOW_FACTS, profile.get(2).label);
        assertNotNull(profile.get(2).action);
        assertTrue("a video sheet has no account facts",
                CopyIds.targetsOf(video("7312345678901234567")).size() == 1);

        Settings.COPY_IDS.save(false);
        profile = CopyIds.targetsOf(profile("6812345678901234567", "nasa"));
        assertEquals("the facts stand without the copy buttons", 1, profile.size());
        assertEquals(AccountFacts.SHOW_FACTS, profile.get(0).label);
        assertTrue(CopyIds.targetsOf(video("7312345678901234567")).isEmpty());

        Settings.ACCOUNT_FACTS.save(false);
        assertTrue(CopyIds.targetsOf(profile("6812345678901234567", "nasa")).isEmpty());
    }

    @Test public void theFactsButtonOpensTheSheetAndCopyTakesItAll() {
        Settings.ACCOUNT_FACTS.save(true);
        Utils.setActivity(activity);
        ProfilePackage nasa = profile("6812345678901234567", "nasa");
        nasa.user.region = "US";
        nasa.user.isSecret = true;
        FrameLayout panel = panel();
        CopyIds.onSharePackage(nasa);
        CopyIds.onSharePanel(panel);
        idle();

        LinearLayout row = (LinearLayout) panel.getChildAt(1);
        assertEquals(3, row.getChildCount());
        TextView facts = (TextView) row.getChildAt(2);
        assertEquals("Account facts", facts.getText().toString());
        clipboard().setPrimaryClip(android.content.ClipData.newPlainText("before", "before"));
        assertTrue(facts.performClick());
        assertEquals("the button opens the sheet rather than copying", "before", clip());

        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertEquals("@nasa", Shadows.shadowOf(dialog).getTitle().toString());
        String message = Shadows.shadowOf(dialog).getMessage().toString();
        assertTrue(message, message.contains("Region: United States (US)"));
        assertTrue(message, message.contains("Private account: Yes"));

        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick());
        idle();
        assertEquals(message, clip());
        assertEquals("Account facts copied", ShadowToast.getTextOfLatestToast());
    }

    @Test public void somethingOtherThanTheFrameIsLeftAlone() {
        LinearLayout notAPanel = new LinearLayout(activity);
        CopyIds.onSharePackage(video("7312345678901234567"));
        CopyIds.onSharePanel(notAPanel);
        CopyIds.onSharePanel(null);
        idle();
        assertEquals(0, notAPanel.getChildCount());
        assertNull(notAPanel.getTag());
    }

    private FrameLayout panel() {
        FrameLayout panel = frame("Report");
        activity.setContentView(panel);
        return panel;
    }

    /** A panel holding one list, standing in for TikTok's action or apps list. */
    private FrameLayout frame(String label) {
        FrameLayout panel = new FrameLayout(activity);
        TextView list = new TextView(activity);
        list.setText(label);
        panel.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private static ProfilePackage profile(String uid, String handle) {
        ProfilePackage sharePackage = new ProfilePackage();
        sharePackage.user = new User();
        sharePackage.user.uid = uid;
        sharePackage.user.uniqueId = handle;
        return sharePackage;
    }

    private static VideoPackage video(String aid) {
        VideoPackage sharePackage = new VideoPackage();
        sharePackage.aweme = new Video();
        sharePackage.aweme.aid = aid;
        return sharePackage;
    }

    private ClipboardManager clipboard() {
        return (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    private String clip() {
        return clipboard().getPrimaryClip().getItemAt(0).getText().toString();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
