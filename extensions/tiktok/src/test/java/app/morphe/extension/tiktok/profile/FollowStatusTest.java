package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
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

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class FollowStatusTest {
    private ActivityController<Activity> owner;
    private Activity activity;
    private boolean previousSetting, previousStatus;

    /** A profile header's common info, with the members the extension reads by name. */
    public static final class Profile {
        public String uid;
        public String username;
        public String nickname;
    }

    public static final class Relation {
        public Integer followStatus;
        public Integer followerStatus;
    }

    public static final class CommonInfo {
        public Profile userProfileInfo = new Profile();
        public Relation userRelationInfo = new Relation();
    }

    /** A follow list item: TikTok's holds its account in one field of the User type. */
    public static final class Item {
        public int index;
        public User account;
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousSetting = Settings.SHOW_FOLLOW_STATUS.get();
        previousStatus = SettingsStatus.followStatusEnabled;
        Settings.SHOW_FOLLOW_STATUS.save(true);
        SettingsStatus.followStatusEnabled = true;
        SignedInUser.idForTests = "me";
        owner = Robolectric.buildActivity(Activity.class).setup();
        activity = owner.get();
    }

    @After public void tearDown() {
        Settings.SHOW_FOLLOW_STATUS.save(previousSetting);
        SettingsStatus.followStatusEnabled = previousStatus;
        SignedInUser.idForTests = null;
        if (owner != null) owner.close();
    }

    @Test public void theRelationNumbersPickTheLabel() {
        assertEquals(FollowStatus.FOLLOWS_YOU, FollowStatus.headerLabel(0, 1));
        assertEquals(FollowStatus.FOLLOWS_YOU, FollowStatus.headerLabel(1, 1));
        assertEquals("a mutual follow follows you", FollowStatus.FOLLOWS_YOU, FollowStatus.headerLabel(2, null));
        assertEquals(FollowStatus.NOT_FOLLOWING_BACK, FollowStatus.headerLabel(1, 0));
        assertNull(FollowStatus.headerLabel(0, 0));
        assertNull(FollowStatus.headerLabel(null, null));

        assertEquals(FollowStatus.NOT_FOLLOWING_BACK, FollowStatus.listLabel(1, 0));
        assertNull("a list doesn't repeat Follows you on every follower", FollowStatus.listLabel(0, 1));
        assertNull(FollowStatus.listLabel(2, 1));
        assertNull(FollowStatus.listLabel(1, 1));
        assertNull(FollowStatus.listLabel(null, null));
    }

    @Test public void theHandleMatchesWithOrWithoutItsAtSignAndBadges() {
        assertTrue(FollowStatus.isHandle("@nasa", "nasa"));
        assertTrue(FollowStatus.isHandle("nasa", "nasa"));
        assertTrue("the verified badge is a placeholder at the end", FollowStatus.isHandle("@nasa ￼", "nasa"));
        assertFalse(FollowStatus.isHandle("@nasa2", "nasa"));
        assertFalse(FollowStatus.isHandle("NASA", "nasa"));
        assertFalse(FollowStatus.isHandle("@nasa", null));
    }

    @Test public void aProfileThatFollowsYouSaysSoUnderItsHandle() {
        LinearLayout header = column();
        TextView name = text(header, "NASA");
        TextView handle = text(header, "@nasa");
        TextView bio = text(header, "Exploring the universe");

        CommonInfo info = info("1", "nasa", 0, 1);
        for (TextView item : new TextView[]{name, handle, bio}) FollowStatus.onHeaderText(item, info);
        idle();

        assertEquals(4, header.getChildCount());
        TextView label = (TextView) header.getChildAt(2);
        assertEquals(FollowStatus.LABEL_TAG, label.getTag());
        assertEquals("Follows you", label.getText().toString());
        assertEquals(View.VISIBLE, label.getVisibility());

        // A rebind of the same header reuses the label, and a profile you follow one way changes it.
        FollowStatus.onHeaderText(handle, info("1", "nasa", 1, 0));
        idle();
        assertEquals(4, header.getChildCount());
        assertEquals("Doesn't follow you back", label.getText().toString());

        FollowStatus.onHeaderText(handle, info("1", "nasa", 0, 0));
        idle();
        assertEquals(View.GONE, label.getVisibility());
    }

    @Test public void yourOwnProfileAndAnOffSwitchShowNothing() {
        LinearLayout header = column();
        TextView handle = text(header, "@me_myself");
        FollowStatus.onHeaderText(handle, info("me", "me_myself", 0, 1));
        idle();
        assertEquals(1, header.getChildCount());

        FollowStatus.onHeaderText(handle, info("2", "me_myself", 0, 1));
        idle();
        assertEquals(2, header.getChildCount());
        Settings.SHOW_FOLLOW_STATUS.save(false);
        FollowStatus.onHeaderText(handle, info("2", "me_myself", 0, 1));
        idle();
        assertEquals(View.GONE, header.getChildAt(1).getVisibility());
    }

    @Test public void aHandleInAHorizontalRowGetsTheLabelInTheColumnAbove() {
        LinearLayout header = column();
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        header.addView(row);
        TextView handle = text(row, "@nasa");
        text(header, "Exploring the universe");

        FollowStatus.onHeaderText(handle, info("1", "nasa", 0, 1));
        idle();

        assertEquals(1, row.getChildCount());
        assertEquals(3, header.getChildCount());
        assertEquals(FollowStatus.LABEL_TAG, header.getChildAt(1).getTag());
    }

    @Test public void aLayoutThatIsntALinearLayoutGetsNothing() {
        // Deeper than the label ever climbs, so no LinearLayout of the window's own is in reach.
        FrameLayout outer = new FrameLayout(activity);
        activity.setContentView(outer);
        FrameLayout header = outer;
        for (int depth = 0; depth < 4; depth++) {
            FrameLayout inner = new FrameLayout(activity);
            header.addView(inner);
            header = inner;
        }
        TextView handle = new TextView(activity);
        handle.setText("@nasa");
        header.addView(handle);

        FollowStatus.onHeaderText(handle, info("1", "nasa", 0, 1));
        idle();

        assertEquals(0, labels(activity.getWindow().getDecorView()));
    }

    @Test public void aFollowListCellMarksOnlyWhoDoesntFollowBackAndARecycledCellLetsGo() {
        LinearLayout cell = column();
        LinearLayout names = new LinearLayout(activity);
        names.setOrientation(LinearLayout.VERTICAL);
        cell.addView(names);
        TextView nickname = text(names, "Jo");
        TextView handle = text(names, "jo.smith");

        FollowStatus.onRelationCell(cell, item(user("5", "jo.smith", "Jo", 1, 0)));
        idle();
        assertEquals(3, names.getChildCount());
        TextView label = (TextView) names.getChildAt(2);
        assertEquals("Doesn't follow you back", label.getText().toString());

        // The same cell, recycled for a mutual friend.
        nickname.setText("Sam");
        handle.setText("sam");
        FollowStatus.onRelationCell(cell, item(user("6", "sam", "Sam", 2, 1)));
        idle();
        assertEquals(View.GONE, label.getVisibility());

        // An item with no account in it does nothing at all.
        FollowStatus.onRelationCell(cell, new Object());
        idle();
        assertEquals(3, names.getChildCount());
    }

    @Test public void aFollowListCellWithoutALinearLayoutLeavesThePageAlone() {
        // The page around the list is a vertical column, within the label's climb from the name.
        LinearLayout page = column();
        FrameLayout cell = new FrameLayout(activity);
        page.addView(cell);
        TextView handle = new TextView(activity);
        handle.setText("jo.smith");
        cell.addView(handle);

        FollowStatus.onRelationCell(cell, item(user("5", "jo.smith", "Jo", 1, 0)));
        idle();

        assertEquals(1, page.getChildCount());
        assertEquals(0, labels(activity.getWindow().getDecorView()));
    }

    @Test public void theListItemsAccountIsFoundByItsType() {
        User account = user("5", "jo.smith", "Jo", 1, 0);
        assertSame(account, FollowStatus.userOf(item(account)));
        assertNull(FollowStatus.userOf(new Object()));
        assertNull(FollowStatus.userOf(null));
    }

    private static int labels(View view) {
        if (FollowStatus.LABEL_TAG.equals(view.getTag())) return 1;
        if (!(view instanceof android.view.ViewGroup)) return 0;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        int count = 0;
        for (int index = 0; index < group.getChildCount(); index++) count += labels(group.getChildAt(index));
        return count;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(layout);
        return layout;
    }

    private TextView text(LinearLayout parent, String value) {
        TextView view = new TextView(activity);
        view.setText(value);
        parent.addView(view);
        return view;
    }

    private static CommonInfo info(String uid, String username, int followStatus, int followerStatus) {
        CommonInfo info = new CommonInfo();
        info.userProfileInfo.uid = uid;
        info.userProfileInfo.username = username;
        info.userRelationInfo.followStatus = followStatus;
        info.userRelationInfo.followerStatus = followerStatus;
        return info;
    }

    private static User user(String uid, String handle, String nickname, int followStatus, int followerStatus) {
        User user = new User();
        user.uid = uid;
        user.uniqueId = handle;
        user.nickname = nickname;
        user.followStatus = followStatus;
        user.followerStatus = followerStatus;
        return user;
    }

    private static Item item(User account) {
        Item item = new Item();
        item.account = account;
        return item;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
