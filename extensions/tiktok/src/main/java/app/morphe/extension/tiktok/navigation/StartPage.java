/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.FeedLock;

/**
 * Picks the tab TikTok opens on when it starts from its icon.
 *
 * <p>TikTok's main activity works that tab out itself as it is created: the tab a notification
 * names, else a tab it saved, else one its own landing rules ask for, else Home. All of those end
 * at one comparison with "HOME", where the patch hands the tag to {@link #coldStartTag}, and the
 * tag that comes back goes through TikTok's own steps for a start on that tab: the splash theme is
 * put back for anything but Home, and Friends, Inbox and Profile each go through the handler
 * TikTok uses when a notification opens them.
 *
 * <p>For You and Following are feed tabs across the top of Home, and Home's pager picks between
 * them twice after the activity. As its view is built it switches to the tab TikTok's home page
 * service names, else For You ({@link #firstTopTab}). Once the first frame is up it moves to its
 * default page, which is Following only when TikTok's own "change follow tab" preference is on
 * for a signed-in account, and otherwise the tab it is on ({@link #followingFirst}). A start that
 * asked for either tab answers both for a short while after it.
 *
 * <p>Only a plain start from the launcher changes. A notification, a link or a shortcut keeps the
 * tab it asked for, and an activity Android restores keeps the tab it was on. Inbox also needs a
 * signed-in account, since TikTok sends a start there without one to its login screen.
 */
public final class StartPage {
    static final String FAMILY = "start page";
    static final String PUSH_TAB = "com.ss.android.ugc.aweme.intent.extra.EXTRA_AWEME_PUSH_TAB";
    /** The extras TikTok's own push check reads, besides a link: a start with any is a notification's. */
    static final String[] PUSH_EXTRAS = {"from_notification", "push_id", "is_from_push"};
    private static final String SERVICE_MANAGER_CLASS = "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String ACCOUNT_USER_SERVICE_CLASS = "com.ss.android.ugc.aweme.IAccountUserService";

    public static final String TIKTOK = "tiktok";
    public static final String FOR_YOU = "for_you";
    public static final String FOLLOWING = "following";
    public static final String FRIENDS = "friends";
    public static final String INBOX = "inbox";
    public static final String PROFILE = "profile";

    /** TikTok's own tags for its tabs, as its tab switch and its notification handlers read them. */
    static final String HOME_TAG = "HOME";
    static final String FRIENDS_TAB_TAG = "FRIENDS_TAB";
    static final String FRIENDS_FEED_TAG = "FRIENDS_FEED";
    static final String INBOX_TAG = "NOTIFICATION";
    static final String PROFILE_TAG = "USER";

    /** Home's own tags for the feed tabs across the top, as its pager switches to them. */
    static final String FOR_YOU_TOP_TAG = "For You";
    static final String FOLLOWING_TOP_TAG = "Following";

    /**
     * How long after a start its top tab answers Home's pager. The pager is built and settles
     * within a second of the activity, so a pick that comes much later belongs to something else,
     * such as the pager built again after TikTok restarts its main page. It's measured on the
     * clock that keeps running while the phone sleeps, so a start the reader put the phone down on
     * doesn't stay open.
     */
    static final long TOP_TAB_WINDOW_MS = 15_000;

    private static String startTopTab;
    private static long startedAt;

    private StartPage() {
    }

    /**
     * The tab a starting TikTok opens on, given the {@code tag} it worked out, the activity whose
     * intent started it and the state Android handed back, if any.
     */
    public static String coldStartTag(Activity activity, String tag, Bundle savedState) {
        try {
            HookStatus.bound(FAMILY, "cold start");
            startTopTab = null;
            if (savedState != null) return tag;
            Intent intent = activity == null ? null : activity.getIntent();
            // A link to one video: the feed lock lets that video through, and Open shared videos
            // alone plays it by itself. It never reaches the start tab below, since a link keeps
            // the tab it asked for.
            if (FeedLock.isVideoLink(intent)) FeedLock.noteLinkEntry();
            String choice = Settings.START_PAGE.get();
            boolean locking = FeedLock.isOn();
            if (TIKTOK.equals(choice) && !(locking && HOME_TAG.equals(tag))) return tag;
            if (!isLauncherStart(intent)) return tag;
            String target = TIKTOK.equals(choice) ? null : tagFor(choice);
            // With the feed lock on, a start that would land on a feed, or on a tab this phone
            // can't show, lands on Inbox, or on Profile for a phone with no Inbox to open, so the
            // app doesn't open on a blank feed.
            if (locking && (target == null || landsOnFeed(target))) {
                choice = tagFor(INBOX) != null ? INBOX : PROFILE;
                target = tagFor(choice);
                HookStatus.bound(FAMILY, "feed lock start");
            }
            if (target == null) return tag;
            String top = topTagFor(choice);
            if (top != null) {
                startTopTab = top;
                startedAt = SystemClock.elapsedRealtime();
            }
            HookStatus.bound(FAMILY, "opened on " + choice);
            return target;
        } catch (Throwable failure) {
            Logger.printException(() -> "Start page failed; TikTok opens on its own tab", failure);
            return tag;
        }
    }

    /**
     * The feed tab across the top Home's pager switches to as its view is built, given the
     * {@code tag} it picked. Right after a start that asked for For You or Following it is that
     * tab, and every other pick is TikTok's.
     */
    public static String firstTopTab(String tag) {
        try {
            HookStatus.bound(FAMILY, "first top tab");
            String top = startTopTab();
            if (top == null) return tag;
            HookStatus.bound(FAMILY, "top tab " + top);
            return top;
        } catch (Throwable failure) {
            Logger.printException(() -> "Start page failed; Home opens on its own feed tab", failure);
            return tag;
        }
    }

    /**
     * Whether Home's pager takes Following as its default page, given TikTok's own "change follow
     * tab" preference. Right after a start that asked for Following it does, right after one that
     * asked for For You it doesn't, and otherwise the preference decides.
     */
    public static boolean followingFirst(boolean tikToksChoice) {
        try {
            HookStatus.bound(FAMILY, "default page");
            String top = startTopTab();
            if (top == null) return tikToksChoice;
            boolean following = FOLLOWING_TOP_TAG.equals(top);
            HookStatus.bound(FAMILY, following ? "default page Following" : "default page For You");
            return following;
        } catch (Throwable failure) {
            Logger.printException(() -> "Start page failed; Home takes its own default page", failure);
            return tikToksChoice;
        }
    }

    /** The top tab the last start asked for, while it is recent enough to still be the start's. */
    private static String startTopTab() {
        String top = startTopTab;
        return top != null && SystemClock.elapsedRealtime() - startedAt <= TOP_TAB_WINDOW_MS ? top : null;
    }

    /** Forgets the last start's top tab, for tests. */
    static void resetForTests() {
        startTopTab = null;
        startedAt = 0;
        signedInForTests = null;
        classes = Class::forName;
    }

    /**
     * A tap on the app's icon: the launcher's own intent, with no page, link or notification tab in
     * it and none of the extras TikTok's own push check reads. Some of TikTok's notifications open
     * the app with the launcher's intent and mark it only with those.
     */
    public static boolean isLauncherStart(Intent intent) {
        if (intent == null
                || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || intent.getData() != null
                || intent.hasExtra(PUSH_TAB)) {
            return false;
        }
        for (String extra : PUSH_EXTRAS) {
            if (intent.hasExtra(extra)) return false;
        }
        return true;
    }

    /**
     * Whether an account is signed in. TikTok sends a start on Inbox without one to its login
     * screen instead, every time. A build where the account service can't be read keeps the
     * choice, as before this was asked.
     */
    static boolean signedIn() {
        if (signedInForTests != null) return signedInForTests;
        try {
            Class<?> managerClass = classes.named(SERVICE_MANAGER_CLASS);
            Object manager = managerClass.getMethod("get").invoke(null);
            Class<?> accountClass = classes.named(ACCOUNT_USER_SERVICE_CLASS);
            Object account = managerClass.getMethod("getService", Class.class).invoke(manager, accountClass);
            return account == null || !Boolean.FALSE.equals(accountClass.getMethod("isLogin").invoke(account));
        } catch (Throwable unreadable) {
            return true;
        }
    }

    /** So a test can stand in for TikTok's account service. */
    static Boolean signedInForTests;

    /** Where {@link #signedIn} finds TikTok's classes by name, so a test can hand it stand-ins. */
    interface Classes {
        Class<?> named(String name) throws ClassNotFoundException;
    }

    static Classes classes = Class::forName;

    /**
     * TikTok's tag for a choice, or nothing when the choice leaves it to TikTok or asks for a tab
     * the Feed tabs page hides. Friends is a bottom tab on some accounts and a feed tab across the
     * top on others, with a tag for each, so the tabs this phone has shown decide which.
     */
    static String tagFor(String choice) {
        if (FOR_YOU.equals(choice)) return HOME_TAG;
        if (FOLLOWING.equals(choice)) return topTabShown(NavigationTabOptions.FOLLOWING) ? HOME_TAG : null;
        if (PROFILE.equals(choice)) return PROFILE_TAG;
        if (INBOX.equals(choice)) {
            return bottomTabShown(BottomNavigationTabOptions.INBOX) && signedIn() ? INBOX_TAG : null;
        }
        if (FRIENDS.equals(choice)) {
            boolean bottom = BottomNavigationTabOptions.parseObservedKeys(
                    Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.get()).contains(BottomNavigationTabOptions.FRIENDS);
            if (bottom) return bottomTabShown(BottomNavigationTabOptions.FRIENDS) ? FRIENDS_TAB_TAG : null;
            return topTabShown(NavigationTabOptions.FRIENDS) ? FRIENDS_FEED_TAG : null;
        }
        return null;
    }

    /**
     * Home's tag for the feed tab across the top a choice opens, or nothing for a choice that isn't
     * one. {@link #tagFor} has already turned down a Following the Feed tabs page hides, and For
     * You can't be hidden there.
     */
    static String topTagFor(String choice) {
        if (FOR_YOU.equals(choice)) return FOR_YOU_TOP_TAG;
        if (FOLLOWING.equals(choice)) return FOLLOWING_TOP_TAG;
        return null;
    }

    /** Whether a tab's tag is one of the feeds the feed lock covers. */
    private static boolean landsOnFeed(String target) {
        return HOME_TAG.equals(target) || FRIENDS_TAB_TAG.equals(target) || FRIENDS_FEED_TAG.equals(target);
    }

    private static boolean bottomTabShown(String key) {
        return !Settings.BOTTOM_NAVIGATION.get()
                || BottomNavigationTabOptions.parseEnabledKeys(Settings.BOTTOM_NAVIGATION_TABS.get()).contains(key);
    }

    private static boolean topTabShown(String key) {
        return !Settings.FEED_NAVIGATION.get()
                || NavigationTabOptions.parseEnabledKeys(Settings.FEED_NAVIGATION_TABS.get()).contains(key);
    }
}
