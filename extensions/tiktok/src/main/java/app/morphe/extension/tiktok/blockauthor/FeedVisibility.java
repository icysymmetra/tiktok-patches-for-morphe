/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Tells whether the video feed is the screen currently on show.
 *
 * The block button is an overlay on the activity content root, and TikTok keeps Inbox,
 * Profile, Friends and Shop inside that same activity. Without this the button follows
 * the user onto every screen.
 *
 * The bottom navigation tabs carry their selected state, so the Home tab being selected
 * is a reliable and cheap signal. On 47.1.4 the bottom navigation ids are oph Home, opg
 * Friends and opi Inbox.
 *
 * <p>Selected is not enough on its own. A creator's profile opened from the feed by the name
 * or the avatar is a page of the same horizontal pager as the feed, and the pager scrolls the
 * feed's page, bottom navigation and all, one screen width to the left, where the Home tab
 * stays VISIBLE and selected. So the tab also has to have pixels on screen, which
 * {@link View#isShown} does not check: it reads visibility flags up the tree and nothing else.
 */
public final class FeedVisibility {
    private static final String[] HOME_TAB_RESOURCE_NAMES = {"47.4.3:onh"};
    private static final String[] INBOX_TAB_RESOURCE_NAMES = {"47.4.3:oni"};
    /**
     * The bottom Friends tab, the id FriendsTabProtocol loads on every declared build (one before
     * Home's). A build with no such view answers null and the tab is simply not covered.
     */
    private static final String[] FRIENDS_TAB_RESOURCE_NAMES = {"47.4.3:ong"};
    private static final String[] COMMENT_SHEET_RESOURCE_NAMES = {"47.4.3:pwm"};
    private static final String[] COMMENT_TITLE_RESOURCE_NAMES = {"47.4.3:wla"};
    // Built once: the sheet check runs on every layout pass of the feed's window.
    private static final String COMMENT_SHEET_DIAGNOSTIC_NAME =
            "sheet (" + joinResourceNames(COMMENT_SHEET_RESOURCE_NAMES) + ")";
    private static final String COMMENT_TITLE_DIAGNOSTIC_NAME =
            "title (" + joinResourceNames(COMMENT_TITLE_RESOURCE_NAMES) + ")";

    /**
     * The story viewer's pager. One id rather than the comment sheet's two: this one is not
     * obfuscated, so it cannot collide with an unrelated layout that happens to reuse a
     * shortened name.
     *
     * <p>Read off 46.2.3 on 2026-09-15 by comparing what {@code dumpsys activity top} shows in
     * all three states: 81 ids are visible in the story viewer and in neither the feed nor a
     * profile, and this is the one that names itself. Not yet checked against 46.7.3 or 46.8.3,
     * so a build that does not have it reports "story viewer: 0 found, 1 missing" and the chips
     * behave as they did before this was added.
     */
    private static final String STORY_PAGER_RESOURCE_NAME = "vp_story_collection";

    private static WeakReference<View> homeTabReference = new WeakReference<>(null);
    private static WeakReference<View> inboxTabReference = new WeakReference<>(null);
    private static WeakReference<View> friendsTabReference = new WeakReference<>(null);
    private static WeakReference<View> commentSheetReference = new WeakReference<>(null);
    private static WeakReference<View> commentTitleReference = new WeakReference<>(null);
    private static WeakReference<View> storyPagerReference = new WeakReference<>(null);

    /**
     * Names resolved once each. The view lookup below has to run again whenever the cached view
     * is gone, and for a tab that is genuinely absent, which is what a reader who hid Inbox in
     * Feed navigation has, that is every call. Resolving the name each time is a string search
     * through TikTok's resource table, and the hold's panel asks once a second for as long as it
     * is up. InboxFilter made the same fix for the same id.
     */
    private static final ResourceIdCache IDS = new ResourceIdCache();
    private static volatile boolean warnedMissing;

    /**
     * The Hook status row these tabs are reported under. A build that renames the ids used to
     * say nothing here: the block button just stopped hiding itself off the feed, and every
     * sibling lookup of this kind already said which id was gone.
     */
    private static final String FAMILY = "bottom navigation";

    // Fragment instances are weak keys, and values never retain the fragment or its view.
    // Lifecycle hooks are injected into TikTok's kept DetailPageFragment methods.
    private static final Map<Object, PageState> DETAIL_PAGES = new WeakHashMap<>();

    private static final class PageState {
        WeakReference<View> view = new WeakReference<>(null);
        boolean resumed;
        boolean visible = true;
    }

    private static PageState detailPage(Object page) {
        PageState state = DETAIL_PAGES.get(page);
        if (state == null) {
            state = new PageState();
            DETAIL_PAGES.put(page, state);
        }
        return state;
    }

    public static void onDetailView(Object page, View view) {
        detailPage(page).view = new WeakReference<>(view);
        // On 47.1.4 a video opened from search is this fragment inside the main activity, not a
        // DetailActivity, so the page's start window the activity opens never opened there and
        // the video showed its controls and its search row for about a second (#84, Login2
        // emulator 2026-10-15). A page in the detail pager has that window from the activity.
        if (view != null && !isDetailPager(activityOf(view))) RememberClearDisplayPatch.onPageOpened(page);
    }

    public static void onDetailResume(Object page) {
        detailPage(page).resumed = true;
    }

    public static void onDetailPause(Object page) {
        PageState state = DETAIL_PAGES.get(page);
        if (state != null) state.resumed = false;
        RememberClearDisplayPatch.onPageLeft(page);
    }

    public static void onDetailVisibility(Object page, boolean visible) {
        detailPage(page).visible = visible;
    }

    public static void onDetailDestroyed(Object page) {
        DETAIL_PAGES.remove(page);
        RememberClearDisplayPatch.onPageLeft(page);
    }

    private static Activity activityOf(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    static boolean isDetailVisible() {
        for (PageState state : DETAIL_PAGES.values()) {
            View view = state.view.get();
            if (state.resumed && state.visible && view != null && view.isShown()) return true;
        }
        return false;
    }

    private FeedVisibility() {
    }

    /**
     * The pager a video opened from a profile, a hashtag, a sound or search plays in. It is an
     * activity of its own, holding the same feed cell with the same right column ids (read off
     * the S22 on 47.0.3, 2026-09-28, and named in both builds' manifests). The overlay hides,
     * Hushfeed's own controls over the video and Mute feed videos follow it there (#47), and so do
     * Hide the status bar and the comment bar's own switch (#50). The tab strip belongs to the
     * main feed and is left as TikTok sets it here.
     */
    static final String DETAIL_PAGER = "com.ss.android.ugc.aweme.detail.ui.DetailActivity";
    private static final String MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity";

    /** The main feed, or a detail pager playing the same kind of cell. */
    public static boolean isFeedWindow(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return MAIN_ACTIVITY.equals(name) || DETAIL_PAGER.equals(name);
    }

    public static boolean isDetailPager(Object activity) {
        return activity instanceof Activity && DETAIL_PAGER.equals(activity.getClass().getName());
    }

    /**
     * @return true when the feed is showing. Unknown states report true so a TikTok build
     *         that renames the tab loses the hiding behaviour rather than the button.
     *
     * <p>Clear display is the feed. On 47.0.3 it sets the tab bar, the Home tab's own parent
     * ({@code omy}), GONE and leaves everything else as it was, so the tab fails
     * {@link View#isShown} while the feed plays under it. Read on the S22 on 2026-09-23 with
     * the probe's feed report: with the controls cleared, that bar was the one hidden ancestor.
     * This answered "not the feed" there, so the daily hold took its panel down and gave the
     * sound back while its budget was spent, and the caption kept for clear display hid itself
     * as soon as it was wanted. The search page, for contrast, hides {@code v4w}, the whole page
     * six levels above the tab, and stays "not the feed".
     */
    public static boolean isOnFeed(Activity activity) {
        View homeTab = homeTab(activity);
        if (homeTab == null) {
            return true;
        }
        if (homeTab.isShown() && !isScrolledAway(homeTab)) return homeTab.isSelected();
        if (cleared(activity, homeTab)) return true;
        return isDetailVisible() && !isStoryVisible(activity);
    }

    /**
     * @return true while the feed's page is scrolled sideways to a creator's profile, a page of
     *         the same pager, with the Home tab still selected on it. {@link #isOnFeed} answers
     *         no there; this is for what rides on the feed's page and has to stay as it was
     *         for the swipe back (#84).
     */
    public static boolean isFeedPageScrolledAway(Activity activity) {
        View homeTab = homeTab(activity);
        return homeTab != null && homeTab.isSelected() && homeTab.getVisibility() == View.VISIBLE
                && isScrolledAway(homeTab) && !isDetailVisible() && !isStoryVisible(activity);
    }

    /**
     * @return true while TikTok's clear display has the feed's controls put away: the Home tab
     *         selected and in place, hidden by nothing but its own bar, with no detail page or
     *         story over it.
     *
     * <p>For Hushfeed's own controls over the feed, which go when TikTok's do. {@link #isOnFeed}
     * answers yes here, and has to for the hold and the kept caption, so a control that should
     * not sit over a cleared screen asks this as well.
     */
    public static boolean isFeedCleared(Activity activity) {
        View homeTab = homeTab(activity);
        return homeTab != null && cleared(activity, homeTab);
    }

    /**
     * Only ever adds a yes, and only for the one shape clear display was seen to have. Anything
     * else (another tab selected, a detail page or a story registered over the feed) answers as
     * it did before: a detail page opened from a grid keeps its chips and its hold either way.
     */
    private static boolean cleared(Activity activity, View homeTab) {
        return homeTab.isSelected() && onlyItsBarIsHidden(homeTab) && !isScrolledAway(homeTab)
                && !isDetailVisible() && !isStoryVisible(activity);
    }

    /**
     * Whether the tab is out of sight only because its bar is: the tab itself VISIBLE, the bar
     * holding it not, and everything above the bar shown. A screen laid over the feed hides
     * more than the bar, and a tab hidden in its own right is not this either.
     */
    private static boolean onlyItsBarIsHidden(View tab) {
        if (tab.getVisibility() != View.VISIBLE) return false;
        ViewParent bar = tab.getParent();
        if (!(bar instanceof View) || ((View) bar).getVisibility() == View.VISIBLE) return false;
        ViewParent page = bar.getParent();
        return page instanceof View && ((View) page).isShown();
    }

    /**
     * @return true while TikTok's story viewer is covering everything else.
     *
     * <p>A story opened from a feed avatar is a detail page like any other as far as the hooks
     * can see: it hides the main content instead of scrolling it, so the Home tab fails
     * {@link View#isShown}, and it registers a resumed, visible {@code DetailPageFragment}, so
     * {@link #isDetailVisible} says yes. The chips were drawn over it and acted on the video
     * underneath, which is not the creator whose story is on screen. The detail-page bargain is
     * for a video opened from a profile grid or a search result, where the button is worth
     * keeping; a story is not that.
     */
    public static boolean isStoryVisible(Activity activity) {
        View pager = namedView(activity, STORY_PAGER_RESOURCE_NAME, storyPagerReference,
                reference -> storyPagerReference = reference, "story viewer");
        // Shown is not enough here either. A viewer that is dismissed by moving off screen with
        // its pager attached and VISIBLE would go on answering yes, and every video detail page
        // opened from a grid or a search would lose the button until it detached.
        return pager != null && pager.isShown() && !isScrolledAway(pager);
    }

    /**
     * Whether the tab is laid out where no pixel of it can reach: scrolled or translated outside
     * one of its ancestors' boxes.
     *
     * <p>On 46.2.3, with a creator's profile opened from the feed covering it, the Home tab
     * answered shown and selected while its position on screen was -1080,2043: the outer pager
     * (id {@code viewpager}) had scrolled one screen width and the feed's page was still laid out
     * beside the profile, flags and all. The three chips stayed drawn over the profile's bio and
     * grid, live, for the video underneath. Where the tab actually sits is what tells that page
     * from the feed, and {@link View#isShown} does not look: it reads visibility flags up the
     * tree and nothing else.
     *
     * <p>Not {@link View#getGlobalVisibleRect}, which answers the same question about the window
     * as well: it clips to the window frame, so a window that has been resized under a laid-out
     * root, or split screen, would read as "not the feed" while the reader is on it. This walks
     * TikTok's own layout up to the activity's content view and stops there, which is as far as
     * the pager that causes this goes.
     *
     * <p>A tab with no size yet is not scrolled away, it is not laid out, and the bargain in
     * {@link #isOnFeed} is that an unknown state answers "the feed": losing the hiding is better
     * than losing the button. A tab whose ancestor is GONE, which is what the story viewer does
     * to the main content, fails {@link View#isShown} first and falls to the detail-page
     * registry as before.
     */
    private static boolean isScrolledAway(View tab) {
        Rect rect = new Rect(0, 0, tab.getWidth(), tab.getHeight());
        if (rect.isEmpty()) return false;
        View child = tab;
        ViewParent parent = tab.getParent();
        while (parent instanceof View) {
            View group = (View) parent;
            rect.offset(child.getLeft() + Math.round(child.getTranslationX()) - group.getScrollX(),
                    child.getTop() + Math.round(child.getTranslationY()) - group.getScrollY());
            // Only an ancestor that has laid out and clips its children can hide anything. A
            // group that draws outside itself is why FLAG_CLIP_CHILDREN exists, and a group
            // that has not laid out has no box to judge against: taking either as proof the tab
            // is gone would answer "not the feed" on the feed, which is the answer that costs
            // the reader the button and lifts the daily hold.
            //
            // Laid out, not sized: a group collapsed to nothing has laid out and does hide its
            // children, which is one of the ways TikTok puts a bar away without GONE, so a size
            // test would have called that the feed as well.
            boolean clips = !(group instanceof ViewGroup) || ((ViewGroup) group).getClipChildren();
            if (clips && group.isLaidOut()
                    && !rect.intersect(0, 0, group.getWidth(), group.getHeight())) {
                return true;
            }
            // The app's own root. Above it are the decor and the window, whose size is the
            // system's business: a window resized under a laid-out root, split screen or an
            // inset would read as "not the feed" while the reader is on it.
            if (group.getId() == android.R.id.content) return false;
            child = group;
            parent = group.getParent();
        }
        return false;
    }

    /**
     * @return true while TikTok's comment sheet is visibly covering the feed.
     *
     * <p>The sheet stays inflated and translated below the screen after it closes, so a shown
     * flag alone is not enough. The root has to remain inside TikTok's own clipped layout.
     * The title only identifies the sheet: Hushfeed's compact-header setting deliberately makes
     * that view {@link View#GONE}, and requiring it to be shown put the block button over the
     * comment list and send action. Using both ids still avoids treating an unrelated layout
     * that happens to reuse one obfuscated id as the comment sheet.
     */
    public static boolean isCommentSheetVisible(Activity activity) {
        View sheet = namedView(activity, COMMENT_SHEET_RESOURCE_NAMES, commentSheetReference,
                reference -> commentSheetReference = reference, "comments sheet",
                COMMENT_SHEET_DIAGNOSTIC_NAME);
        View title = namedView(activity, COMMENT_TITLE_RESOURCE_NAMES, commentTitleReference,
                reference -> commentTitleReference = reference, "comments sheet",
                COMMENT_TITLE_DIAGNOSTIC_NAME);
        return sheet != null && title != null && sheet.isShown() && !isScrolledAway(sheet);
    }

    /**
     * Whether this activity's comment sheet has been found before and is still its own, open or
     * parked below the screen. Only the remembered view is read, so it costs no lookup.
     */
    public static boolean hasCommentSheet(Activity activity) {
        View sheet = commentSheetReference.get();
        return sheet != null && belongsTo(sheet, activity);
    }

    /**
     * @return true only when the recommendation feed is certainly what is on screen.
     *
     * <p>{@link #isOnFeed} answers "assume the feed" when the Home tab id is unknown, and counts
     * a detail page opened from a profile grid or a search result. That is the right bargain for
     * a button the reader has to press, where losing it is worse than seeing it somewhere odd.
     * It is the wrong one for anything that just sits there: a label the reader cannot dismiss
     * should be on the feed or absent, so this asks for the Home tab to exist, be on screen and
     * be selected, and for nothing to be covering it.
     */
    public static boolean onRecommendationFeed(Activity activity) {
        View homeTab = homeTab(activity);
        if (homeTab == null || !homeTab.isShown() || isScrolledAway(homeTab)
                || !homeTab.isSelected()) {
            return false;
        }
        return !isCommentSheetVisible(activity);
    }

    /**
     * Whether one of the feeds the feed lock covers is certainly on screen: the For You and
     * Following pages under the Home tab, the bottom Friends tab's feed, and the Home feed with
     * TikTok's clear display putting its tab bar away. A cleared screen hides the Home tab, so
     * {@link #onRecommendationFeed} alone calls it "not the feed" there, which is the answer that
     * would let the lock's panel and its swipe block go the moment the controls are cleared.
     */
    public static boolean onFeedTab(Activity activity) {
        if (onRecommendationFeed(activity)) return true;
        if (isCommentSheetVisible(activity)) return false;
        View homeTab = homeTab(activity);
        if (homeTab != null && cleared(activity, homeTab)) return true;
        View friends = tab(activity, "Friends", FRIENDS_TAB_RESOURCE_NAMES, friendsTabReference,
                reference -> friendsTabReference = reference);
        return friends != null && friends.isShown() && !isScrolledAway(friends) && friends.isSelected();
    }

    /**
     * The Home tab itself, for anything that has to draw around the navigation rather than
     * over it. Null when this build does not have the id, which is the same case
     * {@link #isOnFeed} treats as "assume the feed".
     */
    public static View homeTabView(Activity activity) {
        return homeTab(activity);
    }

    /**
     * The Inbox tab, for anything that wants to send the reader there.
     *
     * <p>Null when this build renames it and, just as usefully, when the reader has hidden Inbox
     * in Feed navigation: the filter drops the tab from the model, so no view is ever built. A
     * caller can treat null as "there is no Inbox to open" without reasoning about the setting.
     */
    public static View inboxTabView(Activity activity) {
        return tab(activity, "Inbox", INBOX_TAB_RESOURCE_NAMES, inboxTabReference,
                reference -> inboxTabReference = reference);
    }

    private static View homeTab(Activity activity) {
        return tab(activity, "Home", HOME_TAB_RESOURCE_NAMES, homeTabReference,
                reference -> homeTabReference = reference);
    }

    private static View namedView(
            Activity activity,
            String[] resourceNames,
            WeakReference<View> cache,
            Consumer<WeakReference<View>> store,
            String family,
            String diagnosticName
    ) {
        View cached = cache.get();
        if (cached != null && belongsTo(cached, activity)) {
            HookStatus.recoveredViewId(family, diagnosticName);
            return cached;
        }
        try {
            boolean anyNameResolved = false;
            for (String resourceName : resourceNames) {
                int id = IDS.resolve(activity.getResources(), activity.getPackageName(),
                        resourceName, false);
                if (id == 0) continue;
                anyNameResolved = true;

                View view = activity.findViewById(id);
                if (view == null) continue;

                HookStatus.recoveredViewId(family, diagnosticName);
                HookStatus.bound(family, resourceName);
                store.accept(new WeakReference<>(view));
                return view;
            }
            if (!anyNameResolved) {
                HookStatus.missingViewId(family, diagnosticName);
            }
            return null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not resolve " + family + " " + diagnosticName, ex);
            return null;
        }
    }

    private static View namedView(
            Activity activity,
            String resourceName,
            WeakReference<View> cache,
            Consumer<WeakReference<View>> store,
            String family
    ) {
        return namedView(activity, new String[]{resourceName}, cache, store, family, resourceName);
    }

    /** Holds the view weakly and re-resolves it once the old one leaves the window. */
    private static View tab(
            Activity activity,
            String tabName,
            String[] resourceNames,
            WeakReference<View> cache,
            Consumer<WeakReference<View>> store
    ) {
        String diagnosticName = tabName + " tab (" + joinResourceNames(resourceNames) + ")";
        View cached = cache.get();
        if (cached != null && belongsTo(cached, activity)) {
            HookStatus.recoveredViewId(FAMILY, diagnosticName);
            return cached;
        }

        try {
            boolean anyNameResolved = false;
            for (String resourceName : resourceNames) {
                int id = IDS.resolve(activity.getResources(), activity.getPackageName(),
                        resourceName, false);
                if (id == 0) continue;
                anyNameResolved = true;

                View tab = activity.findViewById(id);
                if (tab == null) continue;

                HookStatus.recoveredViewId(FAMILY, diagnosticName);
                HookStatus.bound(FAMILY, resourceName);
                store.accept(new WeakReference<>(tab));
                return tab;
            }
            if (!anyNameResolved) {
                HookStatus.missingViewId(FAMILY, diagnosticName);
                if ("Home".equals(tabName)) warnMissing(resourceNames);
            }
            return null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not resolve the " + tabName + " tab", ex);
            return null;
        }
    }

    private static String joinResourceNames(String[] names) {
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() != 0) joined.append('/');
            joined.append(name);
        }
        return joined.toString();
    }

    /**
     * A cached view answers only for the window it is in. Being attached is not enough: a tab
     * from an earlier window of the same name stays attached until that window is torn down,
     * and until the weak reference clears it would report that window's selection for the new
     * one. In the test suite that made the caption overlay's feed check depend on when garbage
     * collection ran.
     */
    private static boolean belongsTo(View view, Activity activity) {
        return view.isAttachedToWindow() && view.getRootView() == activity.getWindow().getDecorView();
    }

    /** Stands in for this build's resource table in tests. */
    public static void resolveForTests(String packageName, String name, int id) {
        IDS.putForTests(packageName, name, id);
    }

    /** API 24's own is above the payload's floor. */
    private interface Consumer<T> {
        void accept(T value);
    }

    private static void warnMissing(String[] resourceNames) {
        if (warnedMissing) {
            return;
        }
        warnedMissing = true;
        Logger.printInfo(() -> "Bottom navigation Home tab '"
                + joinResourceNames(resourceNames)
                + "' not found. The block button cannot hide itself off the feed.");
    }
}
