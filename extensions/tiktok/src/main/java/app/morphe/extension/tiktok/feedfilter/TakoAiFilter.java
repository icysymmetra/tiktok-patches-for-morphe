/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.feedfilter;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.banner.BannerCommonKey;
import com.ss.android.ugc.aweme.feed.model.banner.BannerCommonStruct;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class TakoAiFilter {
    private static final int MAX_LOGS = 20;
    private static final AtomicInteger floatingButtonRouteLogCount = new AtomicInteger();
    private static final AtomicInteger boundViewHideLogCount = new AtomicInteger();

    private TakoAiFilter() {}

    public static boolean shouldHideFeedButton() {
        boolean enabled = Settings.HIDE_TAKO_AI.get();
        HookStatus.bound(HOOK_FAMILY,
                "floating button " + (enabled ? "hidden" : "left"));
        logFloatingButtonRoute("feed-tako-state", enabled);
        return enabled;
    }

    public static void hideBoundFeedButtonView(View view) {
        boolean enabled = Settings.HIDE_TAKO_AI.get();
        HookStatus.bound(HOOK_FAMILY,
                "bound button " + (enabled ? "hidden" : "left"));
        if (!enabled || view == null) return;

        view.setVisibility(View.GONE);
        logBoundViewHide();
    }

    private static final String HOOK_FAMILY = "tako AI";
    private static final AtomicInteger askBarHideLogCount = new AtomicInteger();

    /**
     * The "Ask" strip under a video's caption (issue #6), a second Tako surface the floating
     * button switch never covered. It is a slot component bound per video; asked at the top of
     * that bind, so the strip is neither filled nor shown.
     */
    public static boolean shouldHideAskBar() {
        boolean enabled = Settings.HIDE_TAKO_AI.get();
        HookStatus.bound(HOOK_FAMILY, "ask bar " + (enabled ? "hidden" : "left"));
        return enabled;
    }

    /** The slot's content view, hidden in place of the bind the guard skipped. */
    public static void hideAskBar(View view) {
        if (view == null) return;
        view.setVisibility(View.GONE);
        int count = askBarHideLogCount.getAndIncrement();
        if (count < MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Ask bar hidden");
        } else if (count == MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Ask bar hidden (further logs suppressed)");
        }
    }

    /**
     * The Tako bubble at the bottom right of a feed video (discussion #115), an entrance of its
     * own. Its bind only tells the entrance's view model whether to show, and is asked first, so
     * nothing ever says show.
     */
    public static boolean shouldHideRightBottomEntrance() {
        boolean enabled = Settings.HIDE_TAKO_AI.get();
        HookStatus.bound(HOOK_FAMILY, "corner bubble " + (enabled ? "hidden" : "left"));
        return enabled;
    }

    /**
     * The same entrance's view, just laid out by its base class. With the switch on it goes GONE
     * and the true answer skips the coroutine that would show it again.
     */
    public static boolean hideRightBottomEntrance(View view) {
        if (!shouldHideRightBottomEntrance()) return false;
        if (view != null) view.setVisibility(View.GONE);
        return true;
    }

    /**
     * The Tako entrance floating over the search page (issue #22), either a lone Ask Tako bubble
     * or a Voice and Ask Tako pill. Asked before TikTok inflates it, so nothing is built.
     */
    public static boolean shouldHideSearchEntrance() {
        boolean enabled = Settings.HIDE_TAKO_AI.get();
        HookStatus.bound(HOOK_FAMILY, "search entrance " + (enabled ? "hidden" : "left"));
        return enabled;
    }

    private static final String SEARCH_TABS_READ = "search tabs read";
    private static final String SEARCH_TAB_HIDDEN = "search tab hidden";
    private static final String SEARCH_TAB_LEFT = "search tab left";
    /**
     * The keys an Ask Tako tab would be served under, with any tab whose name says Tako. On
     * 47.0.3 the pill at the head of the strip is not a served tab at all (the strip's keys are
     * general, user, video, shop, live, music, place, photos and hashtag) but a view of its own,
     * hidden by {@link #hideSearchTabEntrance}; this filter is the guard for a build that serves
     * it as data, and it logs the keys it meets so an export says what the strip held.
     */
    private static final Set<String> TAKO_TAB_KEYS = Set.of("tako", "ask_tako", "tako_tab", "asktako");

    /**
     * The search results tab strip (Ask Tako, Top, Users, Videos, ...) arrives as a list of
     * dynamic tab infos whose only consumer, the strip's view model, reads it through two getters
     * the patch filters here. With Hide the Tako bubble on the Ask Tako tab is dropped, so the
     * strip is built without it and the other tabs keep their order; with it off, and when the
     * tab is not in the list, the list goes back as it came. Fails open, the way the banners do.
     */
    public static List filterSearchTabs(List tabs) {
        HookStatus.bound(HOOK_FAMILY, SEARCH_TABS_READ);
        if (tabs == null || tabs.isEmpty()) return tabs;
        try {
            noteSearchTabKeys(tabs);
            boolean hide = Settings.HIDE_TAKO_AI.get();
            List kept = null;
            boolean seen = false;
            for (int i = 0; i < tabs.size(); i++) {
                Object tab = tabs.get(i);
                boolean tako = isTakoTab(tab);
                if (tako) seen = true;
                if (tako && hide) {
                    if (kept == null) kept = new ArrayList(tabs.subList(0, i));
                } else if (kept != null) {
                    kept.add(tab);
                }
            }
            if (seen) HookStatus.bound(HOOK_FAMILY, hide ? SEARCH_TAB_HIDDEN : SEARCH_TAB_LEFT);
            return kept == null ? tabs : kept;
        } catch (Throwable failure) {
            // A build whose tab model moved: leave the strip as TikTok sent it and say so.
            HookStatus.threw(HOOK_FAMILY, "search tabs", failure);
            return tabs;
        }
    }

    static boolean isTakoTab(Object tab) {
        String key = Reflect.string(tab, "getTabKey", "tabKey");
        if (key != null && TAKO_TAB_KEYS.contains(key.trim().toLowerCase(java.util.Locale.ROOT))) return true;
        String name = Reflect.string(tab, "getTabName", "tabName");
        return name != null && name.toLowerCase(java.util.Locale.ROOT).contains("tako");
    }

    /**
     * The keys served: one marker per distinct set (HookStatus keeps one of a name, and honours a
     * clear) and, with diagnostic logging on, a line the export carries.
     */
    private static void noteSearchTabKeys(List tabs) {
        StringBuilder keys = new StringBuilder();
        for (Object tab : tabs) {
            String key = Reflect.string(tab, "getTabKey", "tabKey");
            if (key == null) continue;
            if (keys.length() > 0) keys.append(',');
            keys.append(key);
        }
        String set = keys.toString();
        if (set.isEmpty()) return;
        HookStatus.bound(HOOK_FAMILY, "search tab keys " + set);
        if (BaseSettings.DEBUG.get()) {
            Logger.printInfo(() -> "[Morphe TikTok TakoAI] Search tabs served: " + set);
        }
    }

    private static final String SEARCH_TAB_ENTRANCE_ID = "tv_tab_tako_entrance";
    private static final int SEARCH_TAB_ENTRANCE_WRAPPERS = 4;

    /**
     * The Ask Tako pill at the head of the search results tab strip (issue #21's screenshots),
     * the one Tako surface the switch left standing. It is not a served tab but a view TikTok
     * inflates with the strip when its own gate allows a Tako entrance, then styles: a text view
     * that keeps the real id name tv_tab_tako_entrance, inside a clickable pill, inside a frame,
     * in a column beside the tab row, the wrappers R8-named. Asked with the fragment's view once
     * it is created: with the switch on the pill's column goes GONE, so the strip lays out
     * without it and the tabs keep their order and selection; a page inflated without the pill
     * is left alone; a build without the id name is named in the export.
     */
    public static void hideSearchTabEntrance(View root) {
        try {
            if (!Settings.HIDE_TAKO_AI.get()) {
                HookStatus.bound(HOOK_FAMILY, "search tab entrance left");
                return;
            }
            if (root == null) return;
            int id = root.getResources().getIdentifier(
                    SEARCH_TAB_ENTRANCE_ID, "id", root.getContext().getPackageName());
            if (id == 0) {
                HookStatus.missingViewId(HOOK_FAMILY, SEARCH_TAB_ENTRANCE_ID);
                return;
            }
            HookStatus.recoveredViewId(HOOK_FAMILY, SEARCH_TAB_ENTRANCE_ID);
            hideSearchTabEntrance(root, root.findViewById(id));
        } catch (Throwable failure) {
            // A build whose strip moved: leave it as TikTok inflated it and say so.
            HookStatus.threw(HOOK_FAMILY, "search tab entrance", failure);
        }
    }

    /** The pill's text found under the root, or null on a page whose strip has no pill. */
    static void hideSearchTabEntrance(View root, View text) {
        if (text == null) {
            HookStatus.bound(HOOK_FAMILY, "search tab entrance absent");
            return;
        }
        searchTabEntranceColumn(root, text).setVisibility(View.GONE);
        HookStatus.bound(HOOK_FAMILY, "search tab entrance hidden");
    }

    /**
     * The pill's own subtree: the text's parent (the pill, which may hold a GONE icon beside the
     * text), then up through wrappers holding nothing else visible, to the first view with a
     * visible sibling (the column beside the tab row on 47.0.3). Never the root, and never more
     * than a few levels, so a build that nests it deeper keeps its strip.
     */
    static View searchTabEntranceColumn(View root, View text) {
        View view = text;
        for (int level = 0; level < SEARCH_TAB_ENTRANCE_WRAPPERS; level++) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof ViewGroup) || parent == root) break;
            ViewGroup group = (ViewGroup) parent;
            if (level > 0 && !holdsOnly(group, view)) break;
            view = group;
        }
        return view;
    }

    private static boolean holdsOnly(ViewGroup group, View child) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View other = group.getChildAt(i);
            if (other != child && other.getVisibility() != View.GONE) return false;
        }
        return true;
    }

    private static final String SEARCH_QUESTIONS_CARD = "search questions card";
    private static final int SEARCH_QUESTIONS_CARD_WRAPPERS = 4;

    /**
     * The "People also ask Tako" card in the search results (47.4.3, an Italian account's "Le
     * persone chiedono anche a Tako" on a landmark search): a Tako header over a list of questions
     * that each open Tako, in a full-width row of its own between the video rows. It is a native
     * card, not a Lynx one, laid out by SearchTakoSugCardAssem, whose view is handed here once it
     * is created. With the switch on the card goes, and so does its row once the row is in the
     * results list. The switch takes effect on restart and a row of this kind holds only these
     * cards, so nothing has to give the row back later.
     */
    public static void hideSearchSugCard(View view) {
        try {
            boolean enabled = Settings.HIDE_TAKO_AI.get();
            HookStatus.bound(HOOK_FAMILY, SEARCH_QUESTIONS_CARD + (enabled ? " hidden" : " left"));
            if (!enabled || view == null) return;
            collapse(view);
            // The view is created before its row joins the list, and a post on a detached view
            // runs when it is attached, which is when the row can be found.
            view.post(() -> {
                try {
                    collapseSearchSugCardRow(view);
                } catch (Throwable failure) {
                    HookStatus.threw(HOOK_FAMILY, SEARCH_QUESTIONS_CARD, failure);
                }
            });
        } catch (Throwable failure) {
            // A build whose card moved: leave it as TikTok laid it out and say so.
            HookStatus.threw(HOOK_FAMILY, SEARCH_QUESTIONS_CARD, failure);
        }
    }

    /**
     * The card's row in the results list: a RecyclerView lays a row out at its own height whatever
     * its visibility, so the row goes to no height as well.
     */
    static void collapseSearchSugCardRow(View view) {
        View row = searchSugCardRow(view);
        if (row == null) {
            HookStatus.bound(HOOK_FAMILY, SEARCH_QUESTIONS_CARD + " row not found");
            return;
        }
        collapse(row);
        HookStatus.bound(HOOK_FAMILY, SEARCH_QUESTIONS_CARD + " row collapsed");
    }

    /**
     * The view, or the wrapper holding it, whose parent is a RecyclerView (TikTok's subclasses
     * included), within a few levels. Null when the card sits in no list, or when a wrapper on the
     * way up holds something else visible, since that row is more than the card (the card itself
     * is already gone at no height, which leaves the rest of such a row standing).
     */
    static View searchSugCardRow(View view) {
        View current = view;
        for (int level = 0; level <= SEARCH_QUESTIONS_CARD_WRAPPERS; level++) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            if (isRecyclerView(parent.getClass())) return current;
            if (!holdsOnly((ViewGroup) parent, current)) return null;
            current = (View) parent;
        }
        return null;
    }

    private static boolean isRecyclerView(Class<?> type) {
        for (Class<?> current = type; current != null && current != View.class; current = current.getSuperclass()) {
            if ("RecyclerView".equals(current.getSimpleName())) return true;
        }
        return false;
    }

    private static void collapse(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = 0;
            view.setLayoutParams(params);
        }
        view.setVisibility(View.GONE);
    }

    /**
     * The commentv2 bridge that hands the Tako service the comments sheet's top bar questions.
     * Its base class serves nine bridges, ads and shop among them, so the guard on that base
     * asks here with the service in hand and only this one is answered. The patch pins the class
     * name against each build, so a rename fails the patch rather than the comparison.
     */
    static final String COMMENT_TOP_BAR_BRIDGE = "BgTakoTopBarServiceImpl";

    /**
     * The Tako bar inside the comments sheet, the "related words" strip above the comment list.
     * Asked from the Tako service's own canShow: a false answer is the business condition the
     * sheet's resolver already treats as "no component".
     */
    public static boolean shouldHideCommentTopBar() {
        boolean enabled = Settings.HIDE_TAKO_AI.get();
        HookStatus.bound(HOOK_FAMILY, "comment bar " + (enabled ? "hidden" : "left"));
        return enabled;
    }

    /** The same question from the shared bridge base, answered only for the Tako bridge. */
    public static boolean shouldHideBridgedCommentTopBar(Object service) {
        if (service == null || !COMMENT_TOP_BAR_BRIDGE.equals(service.getClass().getSimpleName())) {
            return false;
        }
        return shouldHideCommentTopBar();
    }

    static final String CAPTION_PANEL_ASKED = "caption panel asked";
    static final String CAPTION_PANEL_SKIPPED = "caption panel skipped";

    /**
     * Issue #162. On some For You videos a tap on the caption, or its "more", opens TikTok's
     * search panel instead of expanding the caption: the caption again, then "Discover more with
     * Tako" and an "Ask Tako" box. The caption handler asks one gate before it hands the tap to
     * that panel, and on a no it expands the caption in place, the same path a video with nothing
     * to search takes. This is the gate's answer on its way to that branch: with the switch on the
     * panel is never asked for; with it off, or while Hushfeed is paused, TikTok's answer stands.
     */
    public static boolean allowCaptionSearchPanel(boolean tikTok) {
        HookStatus.bound(HOOK_FAMILY, CAPTION_PANEL_ASKED);
        if (!tikTok || !Settings.HIDE_TAKO_AI.get()) return tikTok;
        HookStatus.bound(HOOK_FAMILY, CAPTION_PANEL_SKIPPED);
        return false;
    }

    static final String SMART_SEARCH_CAPTION_ASKED = "smart search caption asked";
    static final String SMART_SEARCH_CAPTION_DROPPED = "smart search caption dropped";

    /**
     * Issue #162. On a video with smart search info the caption always shows a "more" that leads
     * into the search panel, even when the whole caption fits, so with the panel skipped (see
     * {@link #allowCaptionSearchPanel}) it led nowhere. TikTok's helper decides that from a flag
     * that says the video has smart search info; this is that flag on its way in. With the switch
     * on it says no, which gives the caption the layout of a video without Tako content: "more"
     * only where the text is cut, and an expand in place. With it off, or while Hushfeed is
     * paused, the flag is TikTok's own.
     */
    public static boolean allowSmartSearchCaption(boolean hasSmartSearchInfo) {
        HookStatus.bound(HOOK_FAMILY, SMART_SEARCH_CAPTION_ASKED);
        if (!hasSmartSearchInfo || !Settings.HIDE_TAKO_AI.get()) return hasSmartSearchInfo;
        HookStatus.bound(HOOK_FAMILY, SMART_SEARCH_CAPTION_DROPPED);
        return false;
    }

    /** The component key TikTok gives the "Ask · topic" banner under a feed video. */
    static final String ASK_BANNER_KEY = "bottom_banner_tako";
    private static final String SEARCH_BANNER_KEY = "bottom_banner_search_rs";
    private static final String BANNERS_READ = "bottom banners read";
    private static final String ASK_BANNER_HIDDEN = "ask banner hidden";
    private static final String ASK_BANNER_LEFT = "ask banner left";
    private static final AtomicInteger askBannerLogCount = new AtomicInteger();

    /**
     * The "Ask · topic" bar under a For You video, issue #6 as its reporter still saw it on
     * 0.40.0. It is not a Tako component. It is one of TikTok's common bottom banners, keyed
     * {@code bottom_banner_tako} and drawn by the same banner view as the "Search · topic" bar,
     * which is why the four Tako components hooked in 0.39.0 and 0.40.0 never fired for it.
     * Every banner reaches a feed cell through {@code Aweme.getBanners()}, and this is that
     * getter's answer on its way out.
     *
     * <p>Each switch removes only its own banner kind. The search strip is removed before native
     * banner priority and layout reserve its height, so neighboring controls can use that space.
     * The video's banner list is replaced by a copy without the selected kinds,
     * so the next read finds nothing to remove and nothing else reading the old list is
     * disturbed. The video's other banners stay where they were.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List filterBanners(Aweme video, List banners) {
        HookStatus.bound(HOOK_FAMILY, BANNERS_READ);
        if (banners == null) return banners;
        try {
            countBanners(banners);
            if (banners.isEmpty()) return banners;
            boolean hideAsk = Settings.HIDE_TAKO_AI.get();
            boolean hideSearch = Settings.HIDE_BOTTOM_SEARCH_BAR.get();
            if (containsAskBanner(banners)) {
                HookStatus.bound(HOOK_FAMILY, hideAsk ? ASK_BANNER_HIDDEN : ASK_BANNER_LEFT);
            }
            if (!hideAsk && !hideSearch) return banners;

            List kept = null;
            int removedAsk = 0, removedSearch = 0;
            for (int i = 0; i < banners.size(); i++) {
                Object banner = banners.get(i);
                boolean ask = hideAsk && isAskBanner(banner);
                boolean search = hideSearch && isSearchBanner(banner);
                if (ask || search) {
                    if (kept == null) kept = new ArrayList(banners.subList(0, i));
                    if (ask) removedAsk++;
                    else removedSearch++;
                } else if (kept != null) kept.add(banner);
            }
            if (kept == null) return banners;
            if (video != null) video.setBanners(kept);
            if (removedAsk > 0) {
                logAskBannerHidden();
                FeedFilterCounters.removed(BANNER_SOURCE, removedAsk, ASK_BANNER_REASON);
            }
            if (removedSearch > 0) {
                HookStatus.bound("feed bottom search", "search banner removed");
                FeedFilterCounters.removed(BANNER_SOURCE, removedSearch, SEARCH_BANNER_REASON);
            }
            return kept;
        } catch (Throwable failure) {
            // A build whose banner model moved: leave the banners as TikTok sent them and say so.
            HookStatus.threw(HOOK_FAMILY, "bottom banners", failure);
            return banners;
        }
    }

    /** The export's route for the banners under a video, one list per read of the getter. */
    static final String BANNER_SOURCE = "BottomBanner";
    static final String ASK_BANNER_REASON = "takoAskBanner";
    static final String SEARCH_BANNER_REASON = "searchBanner";
    private static final FeedServedKinds BANNER_KINDS = new FeedServedKinds("Bottom banner");

    /**
     * Counts the banners each read hands out, by kind, whatever the logging switch says, so an
     * export says which banners a phone is served. Issue #6 took two releases to reach this route
     * because nothing could say what the bar under the video was; the next export names it.
     */
    private static void countBanners(List<?> banners) {
        FeedFilterCounters.sawList(BANNER_SOURCE, banners.size());
        for (Object banner : banners) {
            if (!(banner instanceof BannerCommonStruct)) continue;
            BannerCommonKey key = ((BannerCommonStruct) banner).bannerKey;
            String kind = FeedServedKinds.kind(key == null ? null : key.componentKey, null);
            if (kind == null) continue;
            FeedFilterCounters.sawKind(BANNER_SOURCE, kind);
            BANNER_KINDS.note(kind);
        }
    }

    /** Forgets the banner kinds already named, between deterministic runtime tests. */
    static void resetBannerKindsForTests() {
        BANNER_KINDS.resetForTests();
    }

    private static boolean containsAskBanner(List<?> banners) {
        for (Object banner : banners) {
            if (isAskBanner(banner)) return true;
        }
        return false;
    }

    static boolean isAskBanner(Object banner) {
        if (!(banner instanceof BannerCommonStruct)) return false;
        BannerCommonKey key = ((BannerCommonStruct) banner).bannerKey;
        return key != null && ASK_BANNER_KEY.equals(key.componentKey);
    }

    private static boolean isSearchBanner(Object banner) {
        if (!(banner instanceof BannerCommonStruct)) return false;
        BannerCommonKey key = ((BannerCommonStruct) banner).bannerKey;
        return key != null && SEARCH_BANNER_KEY.equals(key.componentKey);
    }

    private static void logAskBannerHidden() {
        int count = askBannerLogCount.getAndIncrement();
        if (count < MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Ask banner removed from a video");
        } else if (count == MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Ask banner removed (further logs suppressed)");
        }
    }

    private static void logFloatingButtonRoute(String source, boolean enabled) {
        int count = floatingButtonRouteLogCount.getAndIncrement();
        if (count < MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Floating button route hit"
                    + " source=" + source
                    + " enabled=" + enabled);
        } else if (count == MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Floating button route hit (further logs suppressed)");
        }
    }

    private static void logBoundViewHide() {
        int count = boundViewHideLogCount.getAndIncrement();
        if (count < MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Bound feed button view hidden");
        } else if (count == MAX_LOGS) {
            Logger.printDebug(() -> "[Morphe TikTok TakoAI] Bound feed button view hidden (further logs suppressed)");
        }
    }
}
