package app.morphe.extension.tiktok.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StartPageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's main activity as the hook sees it: something whose intent started the app. */
    static final class Host extends Activity {
        Host(Intent intent) {
            setIntent(intent);
        }
    }

    /** An activity that fails when asked for its intent, which the start page must survive. */
    static final class BrokenHost extends Activity {
        @Override public Intent getIntent() {
            throw new IllegalStateException("no intent");
        }
    }

    @Before public void setUp() {
        resetSettings();
        HookStatus.clear();
        StartPage.resetForTests();
    }

    @After public void tearDown() {
        resetSettings();
        HookStatus.clear();
        StartPage.resetForTests();
    }

    /** A Setting keeps the value it last loaded in memory, whichever test's store it came from. */
    private static void resetSettings() {
        Settings.START_PAGE.resetToDefault();
        Settings.BOTTOM_NAVIGATION.resetToDefault();
        Settings.BOTTOM_NAVIGATION_TABS.resetToDefault();
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.resetToDefault();
        Settings.FEED_NAVIGATION.resetToDefault();
        Settings.FEED_NAVIGATION_TABS.resetToDefault();
    }

    private static Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    }

    private static String start(Intent intent, String tag) {
        return StartPage.coldStartTag(new Host(intent), tag, null);
    }

    @Test public void tikTokKeepsItsOwnTabByDefault() {
        assertEquals("tiktok", Settings.START_PAGE.get());
        assertEquals("HOME", start(launcher(), "HOME"));
        assertEquals("SHOP_MALL", start(launcher(), "SHOP_MALL"));
    }

    @Test public void eachChoiceNamesTheTagTikTokUsesForThatTab() {
        Settings.START_PAGE.save(StartPage.INBOX);
        assertEquals("NOTIFICATION", start(launcher(), "HOME"));
        Settings.START_PAGE.save(StartPage.PROFILE);
        assertEquals("USER", start(launcher(), "HOME"));
        Settings.START_PAGE.save(StartPage.FOR_YOU);
        assertEquals("a landing rule's Shop gives way to For You", "HOME", start(launcher(), "SHOP_MALL"));
        Settings.START_PAGE.save("somewhere_new");
        assertEquals("a value this build has no tab for", "HOME", start(launcher(), "HOME"));
    }

    @Test public void friendsFollowsWhereThisPhoneShowedTheFriendsTab() {
        Settings.START_PAGE.save(StartPage.FRIENDS);
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,FRIENDS,PUBLISH,INBOX,PROFILE");
        assertEquals("a bottom tab", "FRIENDS_TAB", start(launcher(), "HOME"));
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,INBOX,PROFILE");
        assertEquals("a feed along the top", "FRIENDS_FEED", start(launcher(), "HOME"));
    }

    @Test public void onlyAPlainLauncherStartChanges() {
        Settings.START_PAGE.save(StartPage.INBOX);
        assertEquals("a restored activity", "HOME",
                StartPage.coldStartTag(new Host(launcher()), "HOME", new Bundle()));
        assertEquals("a link", "HOME",
                start(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.tiktok.com/@someone")), "HOME"));
        assertEquals("a launcher category on a link", "HOME",
                start(launcher().setData(Uri.parse("snssdk1233://feed")), "HOME"));
        assertEquals("a notification's tab", "USER",
                start(launcher().putExtra(StartPage.PUSH_TAB, "USER"), "USER"));
        assertEquals("a main action without the launcher category", "HOME",
                start(new Intent(Intent.ACTION_MAIN), "HOME"));
        assertEquals("no intent at all", "HOME", start(null, "HOME"));
        assertEquals("no activity", "HOME", StartPage.coldStartTag(null, "HOME", null));
    }

    @Test public void aTabTheFeedTabsPageHidesIsNotOpened() {
        Settings.BOTTOM_NAVIGATION.save(true);
        Settings.BOTTOM_NAVIGATION_TABS.save("HOME,PROFILE");
        Settings.START_PAGE.save(StartPage.INBOX);
        assertEquals("a hidden Inbox", "HOME", start(launcher(), "HOME"));
        Settings.START_PAGE.save(StartPage.PROFILE);
        assertEquals("Profile always stays", "USER", start(launcher(), "HOME"));
        Settings.START_PAGE.save(StartPage.FRIENDS);
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,FRIENDS,INBOX,PROFILE");
        assertEquals("a hidden Friends tab", "HOME", start(launcher(), "HOME"));

        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,INBOX,PROFILE");
        Settings.FEED_NAVIGATION.save(true);
        Settings.FEED_NAVIGATION_TABS.save("HOT,FOLLOWING");
        assertEquals("a hidden Friends feed", "HOME", start(launcher(), "HOME"));
        Settings.FEED_NAVIGATION_TABS.save("HOT,FRIENDS");
        assertEquals("a shown Friends feed", "FRIENDS_FEED", start(launcher(), "HOME"));

        Settings.BOTTOM_NAVIGATION.save(false);
        Settings.START_PAGE.save(StartPage.INBOX);
        assertEquals("the bottom filter off shows every tab", "NOTIFICATION", start(launcher(), "HOME"));
    }

    @Test public void aFailingActivityLeavesTikTokItsTab() {
        Settings.START_PAGE.save(StartPage.INBOX);
        assertEquals("HOME", StartPage.coldStartTag(new BrokenHost(), "HOME", null));
    }

    @Test public void theExportCountsTheStartAndTheTabItOpened() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (StartPage.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            start(launcher(), "HOME");
            HookStatus.report();
            assertEquals("the start alone", 1, found[0]);
            Settings.START_PAGE.save(StartPage.PROFILE);
            start(launcher(), "HOME");
            HookStatus.report();
            assertEquals("the start and the tab it opened", 2, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }

    @Test public void theLauncherTestReadsActionCategoryDataAndTheNotificationTab() {
        assertTrue(StartPage.isLauncherStart(launcher()));
        assertFalse(StartPage.isLauncherStart(launcher().setAction(Intent.ACTION_VIEW)));
        assertFalse(StartPage.isLauncherStart(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)));
        assertFalse(StartPage.isLauncherStart(null));
    }

    @Test public void followingOpensHomeAndThenItsFollowingFeed() {
        Settings.START_PAGE.save(StartPage.FOLLOWING);
        assertEquals("the bottom tab is Home", "HOME", start(launcher(), "HOME"));
        assertEquals("Home's pager switches to Following", "Following", StartPage.firstTopTab("For You"));
        assertTrue("and takes it as its default page", StartPage.followingFirst(false));
        assertEquals("every pick right after the start agrees", "Following", StartPage.firstTopTab("For You"));
        assertTrue(StartPage.followingFirst(false));
    }

    @Test public void forYouHoldsHomeToForYouWhateverTikTokPicks() {
        Settings.START_PAGE.save(StartPage.FOR_YOU);
        assertEquals("HOME", start(launcher(), "SHOP_MALL"));
        assertEquals("For You", StartPage.firstTopTab("Following"));
        assertFalse("TikTok's own Following default gives way", StartPage.followingFirst(true));
    }

    @Test public void theOtherChoicesLeaveHomesPickAlone() {
        Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save("HOME,INBOX,PROFILE");
        for (String choice : new String[]{StartPage.TIKTOK, StartPage.FRIENDS, StartPage.INBOX, StartPage.PROFILE, "somewhere_new"}) {
            Settings.START_PAGE.save(choice);
            start(launcher(), "HOME");
            assertEquals(choice, "Following", StartPage.firstTopTab("Following"));
            assertTrue(choice, StartPage.followingFirst(true));
            assertFalse(choice, StartPage.followingFirst(false));
        }
    }

    @Test public void aFeedTabTheFeedTabsPageHidesIsNotOpened() {
        Settings.FEED_NAVIGATION.save(true);
        Settings.FEED_NAVIGATION_TABS.save("HOT,FRIENDS");
        Settings.START_PAGE.save(StartPage.FOLLOWING);
        assertEquals("TikTok's own tab", "SHOP_MALL", start(launcher(), "SHOP_MALL"));
        assertEquals("Home's own pick", "For You", StartPage.firstTopTab("For You"));
        assertFalse("and its own default", StartPage.followingFirst(false));

        Settings.FEED_NAVIGATION_TABS.save("FOLLOWING,FRIENDS");
        Settings.START_PAGE.save(StartPage.FOR_YOU);
        assertEquals("the bottom tab is still Home", "HOME", start(launcher(), "HOME"));
        assertEquals("For You stays whatever the list says", "For You", StartPage.firstTopTab("Following"));

        Settings.FEED_NAVIGATION.save(false);
        Settings.START_PAGE.save(StartPage.FOLLOWING);
        start(launcher(), "HOME");
        assertEquals("the feed filter off shows every feed tab", "Following", StartPage.firstTopTab("For You"));
    }

    @Test public void onlyALauncherStartAsksThePager() {
        Settings.START_PAGE.save(StartPage.FOLLOWING);
        start(launcher().putExtra(StartPage.PUSH_TAB, "USER"), "USER");
        assertEquals("a notification's start", "For You", StartPage.firstTopTab("For You"));
        assertFalse(StartPage.followingFirst(false));
        start(launcher(), "HOME");
        StartPage.coldStartTag(new Host(launcher()), "HOME", new Bundle());
        assertEquals("a restore after it clears what the start left", "For You", StartPage.firstTopTab("For You"));
        assertFalse(StartPage.followingFirst(false));
        start(launcher().setAction(Intent.ACTION_VIEW), "HOME");
        assertEquals("a pager with no start from the icon before it", "For You", StartPage.firstTopTab("For You"));
    }

    @Test public void aStartsTopTabAnswersOnlySoLong() {
        Settings.START_PAGE.save(StartPage.FOLLOWING);
        start(launcher(), "HOME");
        shadowOf(Looper.getMainLooper()).idleFor(StartPage.TOP_TAB_WINDOW_MS, TimeUnit.MILLISECONDS);
        assertEquals("at the end of the wait", "Following", StartPage.firstTopTab("For You"));
        assertTrue(StartPage.followingFirst(false));
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
        assertEquals("past it", "For You", StartPage.firstTopTab("For You"));
        assertFalse(StartPage.followingFirst(false));
        assertTrue("TikTok's own preference again", StartPage.followingFirst(true));
    }

    @Test public void theExportCountsThePagersAsksAndTheirAnswers() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (StartPage.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            StartPage.firstTopTab("For You");
            StartPage.followingFirst(false);
            HookStatus.report();
            assertEquals("the pager's two asks alone", 2, found[0]);
            Settings.START_PAGE.save(StartPage.FOLLOWING);
            start(launcher(), "HOME");
            StartPage.firstTopTab("For You");
            StartPage.followingFirst(false);
            HookStatus.report();
            assertEquals("the start, its choice and both answers", 6, found[0]);
            Settings.START_PAGE.save(StartPage.FOR_YOU);
            start(launcher(), "HOME");
            StartPage.firstTopTab("Following");
            StartPage.followingFirst(true);
            HookStatus.report();
            assertEquals("For You's choice and answers too", 9, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }

    @Test public void aSignedOutInboxLeavesTikTokItsTab() {
        Settings.START_PAGE.save(StartPage.INBOX);
        StartPage.signedInForTests = false;
        assertEquals("TikTok would send a start on Inbox to its login screen", "HOME", start(launcher(), "HOME"));
        StartPage.signedInForTests = true;
        assertEquals("NOTIFICATION", start(launcher(), "HOME"));
        Settings.START_PAGE.save(StartPage.PROFILE);
        StartPage.signedInForTests = false;
        assertEquals("Profile shows a sign-up page of its own", "USER", start(launcher(), "HOME"));
    }

    @Test public void anAccountServiceThatCannotBeReadKeepsTheChoice() {
        assertTrue(StartPage.signedIn());
    }

    /** TikTok's service manager, as the sign-in check finds it by name. */
    public static final class Services {
        static Object account;

        public static Services get() {
            return new Services();
        }

        public Object getService(Class<?> type) {
            return account;
        }
    }

    /** TikTok's account service, signed in or not. */
    public static final class Account {
        private final boolean login;

        Account(boolean login) {
            this.login = login;
        }

        public boolean isLogin() {
            return login;
        }
    }

    @Test public void theAccountServiceSaysWhetherAnAccountIsSignedIn() {
        StartPage.classes = name -> {
            if ("com.ss.android.ugc.aweme.framework.services.ServiceManager".equals(name)) return Services.class;
            if ("com.ss.android.ugc.aweme.IAccountUserService".equals(name)) return Account.class;
            throw new ClassNotFoundException(name);
        };
        try {
            Services.account = new Account(false);
            assertFalse("signed out", StartPage.signedIn());
            Services.account = new Account(true);
            assertTrue("signed in", StartPage.signedIn());
            Services.account = null;
            assertTrue("no account service keeps the choice", StartPage.signedIn());

            Settings.START_PAGE.save(StartPage.INBOX);
            Services.account = new Account(false);
            assertEquals("signed out, TikTok keeps its tab", "HOME", start(launcher(), "HOME"));
            Services.account = new Account(true);
            assertEquals("signed in, Inbox", "NOTIFICATION", start(launcher(), "HOME"));
        } finally {
            Services.account = null;
        }
    }

    @Test public void aStartMarkedAsTikToksPushKeepsItsTab() {
        Settings.START_PAGE.save(StartPage.INBOX);
        for (String extra : StartPage.PUSH_EXTRAS) {
            assertEquals(extra, "HOME", start(launcher().putExtra(extra, "1"), "HOME"));
            assertFalse(extra, StartPage.isLauncherStart(launcher().putExtra(extra, true)));
        }
        assertEquals("another extra is not a push", "NOTIFICATION", start(launcher().putExtra("something_else", 1), "HOME"));
    }
}
