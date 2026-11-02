package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Color;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
public class InboxLayoutLifecycleTest {
    /**
     * TikTok 47.0.3's names, two of the 46.2.3 names they replaced (v15, vid) and the wrapper
     * every 47.0.3 chat cell shares (zci). Either older name put back into InboxFilter as a
     * fallback would resolve here and fail the test that says it is never tried. The other
     * 46.2.3 names are not registered, so this class would not notice one of them coming back;
     * RuntimeViewIdAnchorsTest's rule that a group looks up one name is what stops those.
     */
    private static final String[] RESOURCE_NAMES = {"47.4.3:oni", "47.4.3:l92", "47.4.3:q4f", "47.4.3:u2n", "47.4.3:fy6", "47.4.3:wrv",
            "user_name", "47.4.3:uyv", "47.4.3:brh", "47.4.3:w24", "tv_request_unread_count", "47.4.3:omm", "zci", "47.4.3:fhd",
            "47.4.3:kp7", "47.4.3:l7j", "v15", "vid"};
    private final List<Inbox> inboxes = new ArrayList<>();
    private BooleanSetting[] switches;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.inboxFilterEnabled = true;
        switches = new BooleanSetting[]{Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS,
                Settings.HIDE_INBOX_STORIES, Settings.HIDE_INBOX_CONVERSATIONS,
                Settings.HIDE_INBOX_MESSAGE_REQUESTS, Settings.HIDE_INBOX_NEW_FOLLOWERS,
                Settings.HIDE_INBOX_ACTIVITY, Settings.HIDE_INBOX_ARCHIVE, Settings.HIDE_INBOX_TAKO,
                Settings.HIDE_INBOX_SHOP, Settings.HIDE_INBOX_ADD_PEOPLE, Settings.HIDE_INBOX_SEARCH,
                Settings.HIDE_INBOX_ACTIVITY_STATUS, Settings.HIDE_INBOX_BULLETIN_BOARDS,
                Settings.HIDE_INBOX_GROUP_CHAT_BANNER};
        for (BooleanSetting setting : switches) setting.save(false);
        Settings.HIDE_INBOX_CUSTOM_TITLES.save("");
        String packageName = RuntimeEnvironment.getApplication().getPackageName();
        for (String name : RESOURCE_NAMES) InboxFilter.resolveForTests(packageName, name, id(name));
        FeedVisibility.resolveForTests(packageName, "47.4.3:oni", id("47.4.3:oni"));
    }

    @After public void tearDown() {
        for (int i = inboxes.size() - 1; i >= 0; i--) {
            Inbox inbox = inboxes.get(i);
            inbox.activity.finish();
            inbox.layout();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            inbox.owner.close();
        }
        for (BooleanSetting setting : switches) setting.save(false);
        Settings.HIDE_INBOX_BULLETIN_BOARDS.resetToDefault();
        Settings.HIDE_INBOX_GROUP_CHAT_BANNER.resetToDefault();
        Settings.HIDE_INBOX_CUSTOM_TITLES.save("");
        SettingsStatus.inboxFilterEnabled = false;
    }

    @Test public void movedHeaderKeepsItsControlAndReplacementGetsItsOwnUsableControl() {
        Inbox inbox = openInbox();
        LinearLayout first = new LinearLayout(inbox.activity);
        first.setOrientation(LinearLayout.HORIZONTAL);
        TextView firstTitle = addHeading(inbox, first, Color.WHITE);
        inbox.rows.addView(first, new LinearLayout.LayoutParams(-1, 64));
        inbox.layout();
        TextView firstClear = clearControl(first);
        assertSame(first, firstClear.getParent());
        assertSame(first, firstTitle.getParent().getParent());

        FrameLayout wrapper = new FrameLayout(inbox.activity);
        inbox.rows.removeView(first);
        wrapper.addView(first, new FrameLayout.LayoutParams(-1, 64));
        inbox.rows.addView(wrapper, new LinearLayout.LayoutParams(-1, 64));
        inbox.layout();
        assertSame("moving a header duplicated or replaced its action", firstClear, clearControl(first));
        assertEquals(2, first.getChildCount());

        inbox.rows.removeView(wrapper);
        FrameLayout replacement = new FrameLayout(inbox.activity);
        TextView replacementTitle = addHeading(inbox, replacement, Color.BLACK);
        int[] hostClicks = {0};
        replacementTitle.setOnClickListener(view -> hostClicks[0]++);
        inbox.rows.addView(replacement, new LinearLayout.LayoutParams(-1, 64));
        int[] dismissed = {0};
        LinearLayout account = new LinearLayout(inbox.activity);
        View remove = new View(inbox.activity);
        remove.setId(id("47.4.3:fy6"));
        remove.setContentDescription("Remove current account from suggested accounts");
        remove.setOnClickListener(view -> { dismissed[0]++; inbox.rows.removeView(account); });
        account.addView(remove, new LinearLayout.LayoutParams(48, 48));
        inbox.rows.addView(account, new LinearLayout.LayoutParams(-1, 72));
        inbox.layout();

        TextView replacementClear = clearControl(replacement);
        assertNotSame(firstClear, replacementClear);
        assertSame("a replacement must not steal the detached header's action", first, firstClear.getParent());
        assertSame(replacement, replacementClear.getParent());
        assertEquals(Color.BLACK, replacementClear.getCurrentTextColor());
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, replacementClear.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, replacementClear.getLayoutParams().height);
        replacement.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        replacement.layout(0, 0, replacement.getMeasuredWidth(), replacement.getMeasuredHeight());
        assertTrue("the replacement keeps a full-size touch target",
                replacementClear.getMeasuredHeight() >= Math.round(
                        48 * inbox.activity.getResources().getDisplayMetrics().density));
        assertTrue(replacementClear.isShown());
        assertTrue(replacementTitle.performClick());
        assertEquals("the host title's own click remains installed", 1, hostClicks[0]);
        assertTrue(replacementClear.performClick());
        assertEquals("the replacement action did not reach the current row", 1, dismissed[0]);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    @Test public void liveLayoutsFollowRecycledRowShapesAndWaitUntilInboxIsSelected() {
        Inbox inbox = openInbox();
        LinearLayout row = new LinearLayout(inbox.activity);
        inbox.rows.addView(row, new LinearLayout.LayoutParams(-1, 72));
        Settings.HIDE_INBOX_ARCHIVE.save(true);
        reshape(inbox, row, "47.4.3:uyv", "47.4.3:brh", "Archiv");
        InboxFilter.onRowBound(new Holder(row), 0, new Archive());
        inbox.layout();
        assertRow(row, true);

        inbox.tab.setSelected(false);
        Settings.HIDE_INBOX_ARCHIVE.save(false);
        Settings.HIDE_INBOX_ADD_PEOPLE.save(true);
        Settings.HIDE_INBOX_SEARCH.save(true);
        Settings.HIDE_INBOX_ACTIVITY_STATUS.save(true);
        inbox.layout();
        assertRow(row, true);
        assertEquals(View.VISIBLE, inbox.addPeople.getVisibility());
        assertEquals(View.VISIBLE, inbox.search.getVisibility());
        assertEquals(View.VISIBLE, inbox.status.getVisibility());

        inbox.tab.setSelected(true);
        inbox.layout();
        assertRow(row, false);
        assertEquals(View.GONE, inbox.addPeople.getVisibility());
        assertEquals(View.GONE, inbox.search.getVisibility());
        assertEquals(View.GONE, inbox.status.getVisibility());
        Settings.HIDE_INBOX_ADD_PEOPLE.save(false);
        Settings.HIDE_INBOX_SEARCH.save(false);
        Settings.HIDE_INBOX_ACTIVITY_STATUS.save(false);

        // A chat row titled like a system row: the archive switch leaves it alone and the
        // request count alone decides which of the two chat switches it answers to.
        Settings.HIDE_INBOX_ARCHIVE.save(true);
        Settings.HIDE_INBOX_CONVERSATIONS.save(true);
        reshape(inbox, row, "47.4.3:w24", "user_name", "Archiv");
        InboxFilter.onRowBound(new Holder(row), 1, new Object());
        inbox.layout();
        assertRow(row, true);
        assertEquals(View.VISIBLE, inbox.search.getVisibility());
        View requestCount = new View(inbox.activity);
        requestCount.setId(id("tv_request_unread_count"));
        row.addView(requestCount);
        inbox.layout();
        assertRow(row, false);
        row.removeView(requestCount);
        inbox.layout();
        assertRow(row, true);

        Settings.HIDE_INBOX_STORIES.save(true);
        reshape(inbox, row, null, "47.4.3:wrv", "Story account");
        InboxFilter.onRowBound(new Holder(row), 2, new Object());
        inbox.layout();
        assertRow(row, true);
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
        reshape(inbox, row, null, "47.4.3:fy6", "Suggested account");
        InboxFilter.onRowBound(new Holder(row), 3, new Object());
        inbox.layout();
        assertRow(row, false);
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
        inbox.layout();
        assertRow(row, true);
        reshape(inbox, row, null, null, "Unknown row");
        InboxFilter.onRowBound(new Holder(row), 4, new Object());
        inbox.layout();
        assertRow(row, false);
    }

    /**
     * The row shapes TikTok 47.0.3 builds. The three chat cells come from its precompiled
     * inflaters: each has the root w1f and the name wrapper zci, only the single chat has
     * its own title wrapper, and only the requests row has the request count. The "Say hi
     * to" row is as the S22's Inbox showed it on 2026-09-21, an account with a Follow
     * button and a wave, which 0.58.0 took for a conversation.
     */
    @Test public void chatRowsSortOnTheRequestCountAndSayHiRowsAreSuggestions() {
        Inbox inbox = openInbox();
        LinearLayout single = chatRow(inbox, "47.4.3:w24", "Sam", true, false);
        LinearLayout group = chatRow(inbox, "47.4.3:w24", "Weekend plans", false, false);
        LinearLayout requests = chatRow(inbox, "47.4.3:w24", "Message requests", false, true);
        LinearLayout sayHi = chatRow(inbox, "47.4.3:omm", "Andrew", false, false);

        Settings.HIDE_INBOX_CONVERSATIONS.save(true);
        inbox.layout();
        assertRow(single, true);
        assertRow(group, true);
        assertRow(requests, false);
        assertRow(sayHi, false);

        Settings.HIDE_INBOX_CONVERSATIONS.save(false);
        Settings.HIDE_INBOX_MESSAGE_REQUESTS.save(true);
        inbox.layout();
        assertRow(single, false);
        assertRow(group, false);
        assertRow(requests, true);
        assertRow(sayHi, false);

        Settings.HIDE_INBOX_MESSAGE_REQUESTS.save(false);
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
        inbox.layout();
        assertRow(single, false);
        assertRow(group, false);
        assertRow(requests, false);
        assertRow(sayHi, true);

        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
        Settings.HIDE_INBOX_CUSTOM_TITLES.save("Weekend plans, Message requests, Andrew");
        inbox.layout();
        assertRow(single, false);
        assertRow(group, true);
        assertRow(requests, true);
        assertRow(sayHi, false);
    }

    /**
     * A Bulletin board cell is told apart by the model, not by its layout. Whatever root it is
     * drawn on, its own switch hides it, the conversations switch still hides it where it shares
     * a chat's root, and a view recycled for a conversation forgets it at the next bind.
     */
    @Test public void aBulletinBoardRowFollowsItsOwnSwitchOnAnyRootAndLetsGoWhenRecycled() {
        Inbox inbox = openInbox();
        LinearLayout row = new LinearLayout(inbox.activity);
        inbox.rows.addView(row, new LinearLayout.LayoutParams(-1, 72));

        reshape(inbox, row, "47.4.3:uyv", "47.4.3:brh", "Bulletin board");
        InboxFilter.onRowBound(new Holder(row), 0, new Bulletin());
        inbox.layout();
        assertRow(row, false);
        Settings.HIDE_INBOX_BULLETIN_BOARDS.save(true);
        inbox.layout();
        assertRow(row, true);

        reshape(inbox, row, "47.4.3:w24", "user_name", "Bulletin board");
        InboxFilter.onRowBound(new Holder(row), 1, new Bulletin());
        inbox.layout();
        assertRow(row, true);
        Settings.HIDE_INBOX_BULLETIN_BOARDS.save(false);
        inbox.layout();
        assertRow(row, false);
        Settings.HIDE_INBOX_CONVERSATIONS.save(true);
        inbox.layout();
        assertRow(row, true);

        Settings.HIDE_INBOX_CONVERSATIONS.save(false);
        Settings.HIDE_INBOX_BULLETIN_BOARDS.save(true);
        InboxFilter.onRowBound(new Holder(row), 2, new Object());
        inbox.layout();
        assertRow(row, false);
    }

    /**
     * On 47.0.3 the 46.2.3 names are other views, and zci, the name wrapper, sits in every
     * chat cell and in dozens of unrelated layouts. None of them may mark a conversation.
     */
    @Test public void olderBuildNamesAndTheSharedWrapperMarkNothing() {
        Inbox inbox = openInbox();
        for (BooleanSetting setting : switches) setting.save(true);
        LinearLayout oldContainer = chatRow(inbox, "v15", "Sam", false, false);
        LinearLayout oldConversation = new LinearLayout(inbox.activity);
        LinearLayout oldWrapper = new LinearLayout(inbox.activity);
        oldWrapper.setId(id("vid"));
        TextView oldTitle = new TextView(inbox.activity);
        oldTitle.setId(id("user_name"));
        oldTitle.setText("Alex");
        oldWrapper.addView(oldTitle);
        oldConversation.addView(oldWrapper);
        inbox.rows.addView(oldConversation, new LinearLayout.LayoutParams(-1, 72));
        LinearLayout wrapperOnly = chatRow(inbox, null, "Pat", false, false);

        inbox.layout();

        assertRow(oldContainer, false);
        assertRow(oldConversation, false);
        assertRow(wrapperOnly, false);
    }

    @Test public void storiesOnlyModeLeavesOtherInboxViewsAloneAndRestoresRecycledRows() {
        SettingsStatus.inboxFilterEnabled = false;
        Settings.HIDE_INBOX_STORIES.save(true);
        Inbox inbox = openInbox();

        LinearLayout story = new LinearLayout(inbox.activity);
        reshape(inbox, story, null, "47.4.3:wrv", "Story account");
        inbox.rows.addView(story, new LinearLayout.LayoutParams(-1, 72));

        View nativeHidden = new View(inbox.activity);
        nativeHidden.setVisibility(View.GONE);
        inbox.rows.addView(nativeHidden, new LinearLayout.LayoutParams(-1, 0));

        LinearLayout suggested = new LinearLayout(inbox.activity);
        suggested.setOrientation(LinearLayout.HORIZONTAL);
        addHeading(inbox, suggested, Color.BLACK);
        inbox.rows.addView(suggested, new LinearLayout.LayoutParams(-1, 64));

        inbox.layout();
        assertRow(story, true);
        assertEquals("story-only filtering changed a host-hidden row", View.GONE,
                nativeHidden.getVisibility());
        assertEquals(0, nativeHidden.getLayoutParams().height);
        assertEquals("story-only filtering added another Inbox feature", 1,
                suggested.getChildCount());

        Settings.HIDE_INBOX_STORIES.save(false);
        inbox.layout();
        assertRow(story, false);

        Settings.HIDE_INBOX_STORIES.save(true);
        inbox.layout();
        assertRow(story, true);
        reshape(inbox, story, null, null, "Recycled conversation");
        inbox.layout();
        assertRow(story, false);

        reshape(inbox, story, null, "47.4.3:wrv", "Another story account");
        inbox.layout();
        assertRow(story, true);
        inbox.tab.setSelected(false);
        Settings.HIDE_INBOX_STORIES.save(false);
        inbox.layout();
        assertRow(story, true);
        inbox.tab.setSelected(true);
        inbox.layout();
        assertRow(story, false);

        Settings.HIDE_INBOX_STORIES.save(true);
        Inbox restarted = openInbox();
        LinearLayout restartedStory = new LinearLayout(restarted.activity);
        reshape(restarted, restartedStory, null, "47.4.3:wrv", "Story after restart");
        restarted.rows.addView(restartedStory, new LinearLayout.LayoutParams(-1, 72));
        restarted.layout();
        assertRow(restartedStory, true);
    }

    @Test public void installingNewActivityDetachesOldLayoutsAndFinishingStopsFiltering() {
        Inbox old = openInbox();
        ViewTreeObserver oldObserver = old.activity.findViewById(android.R.id.content).getViewTreeObserver();
        Inbox current = openInbox();
        Settings.HIDE_INBOX_SEARCH.save(true);
        oldObserver.dispatchOnGlobalLayout();
        assertEquals("the old root still invokes the new activity's filter", View.VISIBLE, current.search.getVisibility());
        assertEquals(View.VISIBLE, old.search.getVisibility());
        current.layout();
        assertEquals(View.GONE, current.search.getVisibility());

        current.activity.finish();
        current.layout();
        Settings.HIDE_INBOX_SEARCH.save(false);
        current.layout();
        assertEquals("a finishing activity kept filtering its hierarchy", View.GONE, current.search.getVisibility());
    }

    /**
     * Android 17 replaced the queue behind the main Looper. Installing the layout observer is
     * posted, not run, so a queue that delivered nothing would leave the inbox unfiltered with
     * nothing failing. The install is posted here and left alone: a layout before the queue
     * delivers it reaches no filter, and the same layout after it does.
     */
    @Test @Config(sdk = 37)
    public void onAndroidSeventeenTheObserverIsStillInstalledThroughTheQueue() {
        Inbox inbox = new Inbox();
        inboxes.add(inbox);
        Settings.HIDE_INBOX_SEARCH.save(true);
        InboxFilter.install(inbox.activity);

        inbox.layout();
        assertEquals("the filter ran before the queue had installed it",
                View.VISIBLE, inbox.search.getVisibility());

        Shadows.shadowOf(Looper.getMainLooper()).idle();
        inbox.layout();
        assertEquals("the queue never delivered the install", View.GONE, inbox.search.getVisibility());
    }

    /**
     * TikTok's launcher entry is a second MainActivity under the feed's. Changing TikTok's
     * appearance recreates both, the buried one last, and the filter's posted install followed the
     * newest one, so the Inbox in front went unfiltered until TikTok restarted. The Home long press
     * had the same fault (S22, 47.1.4, Dark then Light).
     */
    @Test public void aThemeChangeLeavesTheFilterOnTheInboxInFront() {
        Settings.HIDE_INBOX_SEARCH.save(true);
        Inbox feed = new Inbox();
        inboxes.add(feed);
        InboxFilter.install(feed.activity);
        feed.owner.pause().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        // The feed's copy goes behind the settings page that changed the theme.
        feed.owner.pause().stop();
        Inbox launcher = new Inbox(Robolectric.buildActivity(Activity.class).create());
        inboxes.add(launcher);
        InboxFilter.install(launcher.activity);
        // Recreated last, the launcher's copy is resumed for a moment and stopped again, and the
        // install its onCreate posted runs after that.
        launcher.owner.start().resume().pause().stop();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        feed.owner.restart().start().resume();
        // The recreated Inbox comes up with TikTok's own header.
        feed.search.setVisibility(View.VISIBLE);
        feed.layout();
        assertEquals("the Inbox in front went unfiltered after a theme change",
                View.GONE, feed.search.getVisibility());
        launcher.layout();
        assertEquals("the buried copy kept the filter", View.VISIBLE, launcher.search.getVisibility());
    }

    /** The buried copy can also blink on while the Inbox stays in front and gets no new resume. */
    @Test public void aBuriedMainActivityResumedForAMomentHandsTheFilterBack() {
        Settings.HIDE_INBOX_SEARCH.save(true);
        Inbox feed = new Inbox();
        inboxes.add(feed);
        InboxFilter.install(feed.activity);
        feed.owner.pause().resume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Inbox launcher = new Inbox(Robolectric.buildActivity(Activity.class).create());
        inboxes.add(launcher);
        InboxFilter.install(launcher.activity);
        launcher.owner.start().resume().pause().stop();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        feed.search.setVisibility(View.VISIBLE);
        feed.layout();
        assertEquals("the Inbox in front lost its filter", View.GONE, feed.search.getVisibility());
    }

    private Inbox openInbox() {
        Inbox inbox = new Inbox();
        inboxes.add(inbox);
        InboxFilter.install(inbox.activity);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        inbox.layout();
        return inbox;
    }

    /**
     * A chat-shaped row: the root, then the name wrapper every 47.0.3 chat cell has (zci)
     * holding the title. A single chat adds a title wrapper of its own around that, and the
     * requests row the request count.
     */
    private static LinearLayout chatRow(Inbox inbox, String rootName, String title,
                                        boolean singleChatWrapper, boolean requestCount) {
        LinearLayout row = new LinearLayout(inbox.activity);
        row.setId(rootName == null ? View.NO_ID : id(rootName));
        LinearLayout nameWrapper = new LinearLayout(inbox.activity);
        nameWrapper.setId(id("zci"));
        TextView name = new TextView(inbox.activity);
        name.setId(id("user_name"));
        name.setText(title);
        nameWrapper.addView(name);
        if (singleChatWrapper) {
            LinearLayout titleWrapper = new LinearLayout(inbox.activity);
            titleWrapper.addView(nameWrapper);
            row.addView(titleWrapper);
        } else {
            row.addView(nameWrapper);
        }
        if (requestCount) {
            TextView count = new TextView(inbox.activity);
            count.setId(id("tv_request_unread_count"));
            count.setText("3");
            row.addView(count);
        }
        inbox.rows.addView(row, new LinearLayout.LayoutParams(-1, 72));
        return row;
    }

    private static TextView addHeading(Inbox inbox, ViewGroup header, int color) {
        header.setId(id("47.4.3:q4f"));
        LinearLayout titleWrapper = new LinearLayout(inbox.activity);
        TextView title = new TextView(inbox.activity);
        title.setId(id("47.4.3:u2n"));
        title.setText("Suggested accounts");
        title.setTextColor(color);
        titleWrapper.addView(title);
        header.addView(titleWrapper);
        return title;
    }

    private static TextView clearControl(ViewGroup header) {
        TextView control = null;
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if ("Clear all suggested accounts".contentEquals(String.valueOf(child.getContentDescription()))) {
                assertNull("one header received two clear controls", control);
                control = (TextView) child;
            }
        }
        assertNotNull("the installed layout callback did not populate the current header", control);
        return control;
    }

    private static void reshape(Inbox inbox, LinearLayout row, String rowId, String childId, String text) {
        row.setId(rowId == null ? View.NO_ID : id(rowId));
        row.removeAllViews();
        TextView title = new TextView(inbox.activity);
        title.setId(childId == null ? View.NO_ID : id(childId));
        title.setText(text);
        row.addView(title);
    }

    private static void assertRow(View row, boolean hidden) {
        assertEquals(hidden ? View.GONE : View.VISIBLE, row.getVisibility());
        assertEquals(hidden ? 0 : 72, row.getLayoutParams().height);
    }

    private static int id(String name) {
        for (int i = 0; i < RESOURCE_NAMES.length; i++) if (RESOURCE_NAMES[i].equals(name)) return 7000 + i;
        throw new AssertionError("unknown fixture resource " + name);
    }

    private static final class Holder {
        public final View itemView;
        Holder(View itemView) { this.itemView = itemView; }
    }

    private static final class Archive {
        public String itemUniqueId() { return "archive_entrance"; }
    }

    /** InboxEntrancePod as the model shows it: the cell's server id is all that names this row. */
    private static final class Bulletin {
        public final Cell entranceCell = new Cell();
        static final class Cell {
            public final int cellId;
            Cell() { cellId = 15; }
        }
    }

    private static final class Inbox {
        final ActivityController<Activity> owner;
        final Activity activity;
        final LinearLayout rows;
        final View tab, addPeople, search, status;

        Inbox() {
            this(Robolectric.buildActivity(Activity.class).setup().visible());
        }

        /** On an activity left wherever the caller put it, created at least. */
        Inbox(ActivityController<Activity> owner) {
            this.owner = owner;
            activity = owner.get();
            rows = new LinearLayout(activity);
            tab = new View(activity);
            addPeople = new View(activity);
            search = new View(activity);
            status = new View(activity);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            tab.setId(id("47.4.3:oni"));
            tab.setSelected(true);
            addPeople.setId(id("47.4.3:fhd"));
            search.setId(id("47.4.3:kp7"));
            status.setId(id("47.4.3:l7j"));
            for (View view : new View[]{tab, addPeople, search, status}) root.addView(view, new LinearLayout.LayoutParams(-1, 48));
            rows.setId(id("47.4.3:l92"));
            rows.setOrientation(LinearLayout.VERTICAL);
            root.addView(rows, new LinearLayout.LayoutParams(-1, -1));
            activity.setContentView(root);
        }

        void layout() { activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnGlobalLayout(); }
    }
}
