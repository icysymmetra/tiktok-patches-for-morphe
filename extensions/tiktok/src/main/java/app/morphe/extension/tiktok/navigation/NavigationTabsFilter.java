/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.navigation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.featurecontrols.LiveButtonReport;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

public final class NavigationTabsFilter {
    private static String lastDebugSignature;
    private static String lastObservedSignature;
    private static String lastBottomDebugSignature;
    private static String lastBottomObservedSignature;
    private static volatile boolean loneForYouModel;
    private static final Set<View> TOP_TAB_STRIPS =
            Collections.newSetFromMap(new WeakHashMap<>());

    private NavigationTabsFilter() {
    }

    public static List<?> filterTopTabs(List<?> tabs) {
        List<?> filtered = filterTopTabs(tabs, false);
        recordFilteredTopTabs(filtered);
        return filtered;
    }

    /** TikTok's own tag for a LIVE tab in the bottom bar, which its tab type table maps to LIVE. */
    static final String LIVE_BOTTOM_TAB_TAG = "Live";

    /**
     * Whether the last bottom pass took TikTok's LIVE tab off the bottom bar.
     *
     * <p>TikTok hides the LIVE button in the feed's corner while LIVE has a bottom tab, since the
     * tab is then the way in. It asks its own list of bottom tabs, which this filter leaves alone,
     * so a LIVE tab taken off here took the corner button with it and left no way into LIVE from
     * the feed (issue #28).
     */
    private static volatile boolean liveBottomTabHidden;
    private static volatile boolean liveTopTabHidden;

    /**
     * TikTok's "LIVE has a bottom tab" check, answered false when the tab it has in mind is one
     * this filter took away. Hide the LIVE button still hides the corner button on its own.
     */
    public static boolean liveHasBottomTab(boolean original) {
        if (original) {
            LiveButtonReport.record(liveBottomTabHidden
                    ? LiveButtonReport.Route.BOTTOM_TAB_TAKEN : LiveButtonReport.Route.BOTTOM_TAB);
        }
        return original && !liveBottomTabHidden;
    }

    /** TikTok's LIVE top-tab modes only hide the corner button while that tab is still visible. */
    public static String liveTopTabMode(String mode) {
        boolean topTab = "live_tab_single".equals(mode) || "live_tab_double".equals(mode);
        if (liveTopTabHidden && Settings.FEED_NAVIGATION.get() && topTab) {
            LiveButtonReport.record(LiveButtonReport.Route.TOP_TAB_TAKEN);
            return "";
        }
        if (topTab) LiveButtonReport.record(LiveButtonReport.Route.TOP_TAB);
        return mode;
    }

    /** The selected label adds no navigation information when the filtered model is only For You. */
    static boolean shouldHideLoneForYouHeader() {
        return Settings.FEED_NAVIGATION.get() && loneForYouModel;
    }

    /**
     * Whether the strip of tab names above the feed should be away: the reader asked for that
     * (issue #32), or the filtered model is only For You, whose lone label says nothing. The
     * strip is the names alone; the search button is a sibling and the pager under them keeps
     * swiping, which is what the switch promises.
     */
    static boolean shouldHideTopTabStrip() {
        return Settings.HIDE_FEED_TAB_STRIP.get() || shouldHideLoneForYouHeader();
    }

    /** The strips hidden here, so a rule that stops applying puts them back and TikTok's own GONE is left alone. */
    private static final Set<View> HIDDEN_HERE = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Keeps the For You page model intact and hides only TikTok's tab-strip view after it has
     * finished adding children. The search button lives in a separate sibling container.
     */
    public static void installLoneForYouHeaderHider(View tabStrip) {
        if (tabStrip == null) return;
        synchronized (TOP_TAB_STRIPS) {
            TOP_TAB_STRIPS.add(tabStrip);
        }
        View.OnLayoutChangeListener listener = (view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateLoneForYouHeader(view);
        tabStrip.addOnLayoutChangeListener(listener);
        tabStrip.post(() -> updateLoneForYouHeader(tabStrip));
    }

    /**
     * Applies the strip rule to every strip registered so far. The overlay hider calls this on
     * each layout pass, so the switch lands on the way back from settings without a restart:
     * the strip itself lays out again only when TikTok changes it.
     */
    public static void refreshTopTabStrips() {
        View[] strips;
        synchronized (TOP_TAB_STRIPS) {
            strips = TOP_TAB_STRIPS.toArray(new View[0]);
        }
        for (View strip : strips) {
            if (strip != null) updateLoneForYouHeader(strip);
        }
    }

    private static void updateLoneForYouHeader(View tabStrip) {
        if (shouldHideTopTabStrip()) {
            if (tabStrip.getVisibility() != View.GONE) {
                tabStrip.setVisibility(View.GONE);
                HIDDEN_HERE.add(tabStrip);
            }
        } else if (HIDDEN_HERE.remove(tabStrip)) {
            tabStrip.setVisibility(View.VISIBLE);
        }
    }

    private static void recordFilteredTopTabs(List<?> tabs) {
        boolean loneForYou = false;
        if (tabs != null && tabs.size() == 1) {
            loneForYou = NavigationTabOptions.HOT.equals(
                    NavigationTabOptions.normalizeRuntimeTag(getTag(tabs.get(0))));
        }
        loneForYouModel = loneForYou;

        View[] strips;
        synchronized (TOP_TAB_STRIPS) {
            strips = TOP_TAB_STRIPS.toArray(new View[0]);
        }
        for (View strip : strips) {
            if (strip != null) strip.post(() -> updateLoneForYouHeader(strip));
        }
    }

    @SuppressWarnings({"unused", "rawtypes", "unchecked"})
    public static List<?> filterTopTabs(List<?> tabs, boolean includeChildren) {
        try {
            if (tabs == null) {
                liveTopTabHidden = false;
                return tabs;
            }
            if (includeChildren) {
                return tabs;
            }

            Set<String> previousObservedKeys = NavigationTabOptions.parseObservedKeys(
                    Settings.FEED_NAVIGATION_OBSERVED_TABS.get()
            );
            observeTabs(tabs, previousObservedKeys);

            if (!Settings.FEED_NAVIGATION.get()) {
                liveTopTabHidden = false;
                debugTabs("observed", tabs, tabs);
                return tabs;
            }

            Set<String> enabledKeys = NavigationTabOptions.parseEnabledKeys(Settings.FEED_NAVIGATION_TABS.get());
            Set<String> observedKeys = NavigationTabOptions.parseObservedKeys(
                    Settings.FEED_NAVIGATION_OBSERVED_TABS.get()
            );
            boolean blockNewTabs = Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.get();
            CopyOnWriteArrayList filtered = new CopyOnWriteArrayList();
            Object hotTab = null;
            boolean sawLiveTopTab = false;
            boolean keptLiveTopTab = false;

            for (Object tab : tabs) {
                String key = NavigationTabOptions.normalizeRuntimeTag(getTag(tab));
                if (NavigationTabOptions.HOT.equals(key)) {
                    hotTab = tab;
                }
                if (NavigationTabOptions.LIVE.equals(key)) {
                    sawLiveTopTab = true;
                }

                if (shouldKeepTab(key, enabledKeys, previousObservedKeys, observedKeys, blockNewTabs)) {
                    filtered.add(tab);
                    if (NavigationTabOptions.LIVE.equals(key)) {
                        keptLiveTopTab = true;
                    }
                }
            }

            if (filtered.isEmpty()) {
                if (hotTab != null) {
                    filtered.add(hotTab);
                } else {
                    liveTopTabHidden = false;
                    debugTabs("fallback-original", tabs, tabs);
                    return tabs;
                }
            }

            liveTopTabHidden = sawLiveTopTab && !keptLiveTopTab;
            debugTabs("filtered", tabs, filtered);
            return filtered;
        } catch (Throwable throwable) {
            liveTopTabHidden = false;
            Logger.printException(() -> "Feed tab navigation failed; returning original tabs", throwable);
            return tabs;
        }
    }

    @SuppressWarnings({"unused", "rawtypes", "unchecked"})
    public static List<?> filterBottomTabs(List<?> tabs) {
        try {
            if (tabs == null) {
                return tabs;
            }

            Set<String> previousObservedKeys = BottomNavigationTabOptions.parseObservedKeys(
                    Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.get()
            );
            observeBottomTabs(tabs, previousObservedKeys);

            if (!Settings.BOTTOM_NAVIGATION.get()) {
                liveBottomTabHidden = false;
                debugBottomTabs("observed", tabs, tabs);
                return tabs;
            }

            Set<String> enabledKeys = BottomNavigationTabOptions.parseEnabledKeys(Settings.BOTTOM_NAVIGATION_TABS.get());
            Set<String> observedKeys = BottomNavigationTabOptions.parseObservedKeys(
                    Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.get()
            );
            boolean blockNewTabs = Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.get();
            CopyOnWriteArrayList filtered = new CopyOnWriteArrayList();
            Object homeTab = null;

            for (Object tab : tabs) {
                String key = BottomNavigationTabOptions.normalizeRuntimeTag(getTag(tab));
                if (BottomNavigationTabOptions.HOME.equals(key)) {
                    homeTab = tab;
                }

                if (shouldKeepBottomTab(key, enabledKeys, previousObservedKeys, observedKeys, blockNewTabs)) {
                    filtered.add(tab);
                }
            }

            if (filtered.isEmpty()) {
                if (homeTab != null) {
                    filtered.add(homeTab);
                } else {
                    liveBottomTabHidden = false;
                    debugBottomTabs("fallback-original", tabs, tabs);
                    return tabs;
                }
            }

            liveBottomTabHidden = hasLiveTab(tabs) && !hasLiveTab(filtered);
            debugBottomTabs("filtered", tabs, filtered);
            return filtered;
        } catch (Throwable throwable) {
            liveBottomTabHidden = false;
            Logger.printException(() -> "Bottom navigation filter failed; returning original tabs", throwable);
            return tabs;
        }
    }

    private static boolean shouldKeepTab(
            String key,
            Set<String> enabledKeys,
            Set<String> previousObservedKeys,
            Set<String> observedKeys,
            boolean blockNewTabs
    ) {
        if (key == null) {
            return !blockNewTabs;
        }

        if (NavigationTabOptions.HOT.equals(key) || enabledKeys.contains(key)) {
            return true;
        }

        boolean wasAlreadyObserved = previousObservedKeys.contains(key);
        boolean isNewlyObserved = observedKeys.contains(key) && !wasAlreadyObserved;
        return isNewlyObserved && !blockNewTabs;
    }

    private static boolean shouldKeepBottomTab(
            String key,
            Set<String> enabledKeys,
            Set<String> previousObservedKeys,
            Set<String> observedKeys,
            boolean blockNewTabs
    ) {
        if (key == null) {
            return !blockNewTabs;
        }

        if (BottomNavigationTabOptions.isRequiredKey(key) || enabledKeys.contains(key)) {
            return true;
        }

        boolean wasAlreadyObserved = previousObservedKeys.contains(key);
        boolean isNewlyObserved = observedKeys.contains(key) && !wasAlreadyObserved;
        return isNewlyObserved && !blockNewTabs;
    }

    private static void observeTabs(List<?> tabs, Set<String> previousObservedKeys) {
        LinkedHashSet<String> observedKeys = new LinkedHashSet<>(previousObservedKeys);
        LinkedHashSet<String> newlyObservedKeys = new LinkedHashSet<>();
        for (Object tab : tabs) {
            String key = NavigationTabOptions.normalizeRuntimeTag(getTag(tab));
            if (key != null && observedKeys.add(key)) {
                newlyObservedKeys.add(key);
            }
        }

        String signature = NavigationTabOptions.serializeEnabledKeys(observedKeys);
        if (!signature.equals(lastObservedSignature)
                && !signature.equals(Settings.FEED_NAVIGATION_OBSERVED_TABS.get())) {
            Settings.FEED_NAVIGATION_OBSERVED_TABS.save(signature);
            lastObservedSignature = signature;
        }

        // Bookkeeping on the reader's own list, paused or not: paused, get() would answer the
        // default list and an open door, and a tab Hushfeed has no name for would be saved over
        // the reader's list together with every tab it knows.
        if (!newlyObservedKeys.isEmpty() && !Settings.FEED_NAVIGATION_BLOCK_NEW_TABS.savedValue()) {
            Set<String> enabledKeys = NavigationTabOptions.parseEnabledKeys(Settings.FEED_NAVIGATION_TABS.savedValue());
            if (enabledKeys.addAll(newlyObservedKeys)) {
                Settings.FEED_NAVIGATION_TABS.save(NavigationTabOptions.serializeEnabledKeys(enabledKeys));
            }
        }
    }

    private static void observeBottomTabs(List<?> tabs, Set<String> previousObservedKeys) {
        LinkedHashSet<String> observedKeys = new LinkedHashSet<>(previousObservedKeys);
        LinkedHashSet<String> newlyObservedKeys = new LinkedHashSet<>();
        for (Object tab : tabs) {
            String key = BottomNavigationTabOptions.normalizeRuntimeTag(getTag(tab));
            if (key != null && observedKeys.add(key)) {
                newlyObservedKeys.add(key);
            }
        }

        String signature = BottomNavigationTabOptions.serializeEnabledKeys(observedKeys);
        if (!signature.equals(lastBottomObservedSignature)
                && !signature.equals(Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.get())) {
            Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.save(signature);
            lastBottomObservedSignature = signature;
        }

        if (!newlyObservedKeys.isEmpty() && !Settings.BOTTOM_NAVIGATION_BLOCK_NEW_TABS.savedValue()) {
            Set<String> enabledKeys = BottomNavigationTabOptions.parseEnabledKeys(Settings.BOTTOM_NAVIGATION_TABS.savedValue());
            if (enabledKeys.addAll(newlyObservedKeys)) {
                Settings.BOTTOM_NAVIGATION_TABS.save(BottomNavigationTabOptions.serializeEnabledKeys(enabledKeys));
            }
        }
    }

    private static boolean hasLiveTab(List<?> tabs) {
        for (Object tab : tabs) {
            if (LIVE_BOTTOM_TAB_TAG.equals(getTag(tab))) return true;
        }
        return false;
    }

    static void resetLiveBottomTabForTests() {
        liveBottomTabHidden = false;
    }

    private static String getTag(Object tab) {
        if (tab == null) {
            return null;
        }

        String tag = invokeStringMethod(tab, "tag");
        return tag != null ? tag : invokeStringMethod(tab, "getTag");
    }

    private static String invokeStringMethod(Object target, String methodName) {
        Object value = Reflect.invoke(target, methodName);
        return value instanceof String ? (String) value : null;
    }

    private static void debugTabs(String state, List<?> original, List<?> filtered) {
        if (!BaseSettings.DEBUG.get()) {
            return;
        }

        String signature = state + " original=" + describe(original) + " filtered=" + describe(filtered);
        if (signature.equals(lastDebugSignature)) {
            return;
        }

        lastDebugSignature = signature;
        Logger.printDebug(() -> "Feed tab navigation: " + signature);
    }

    private static void debugBottomTabs(String state, List<?> original, List<?> filtered) {
        if (!BaseSettings.DEBUG.get()) {
            return;
        }

        String signature = state + " original=" + describeBottom(original) + " filtered=" + describeBottom(filtered);
        if (signature.equals(lastBottomDebugSignature)) {
            return;
        }

        lastBottomDebugSignature = signature;
        Logger.printDebug(() -> "Bottom navigation: " + signature);
    }

    private static String describe(List<?> tabs) {
        if (tabs == null) {
            return "null";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(tabs.size()).append('[');
        for (int i = 0; i < tabs.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }

            String tag = getTag(tabs.get(i));
            String key = NavigationTabOptions.normalizeRuntimeTag(tag);
            builder.append(tag == null ? "unknown" : tag);
            if (key != null) {
                builder.append('/').append(key);
            }
        }
        return builder.append(']').toString();
    }

    private static String describeBottom(List<?> tabs) {
        if (tabs == null) {
            return "null";
        }

        StringBuilder builder = new StringBuilder();
        builder.append(tabs.size()).append('[');
        for (int i = 0; i < tabs.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }

            String tag = getTag(tabs.get(i));
            String key = BottomNavigationTabOptions.normalizeRuntimeTag(tag);
            builder.append(tag == null ? "unknown" : tag);
            if (key != null) {
                builder.append('/').append(key);
            }
        }
        return builder.append(']').toString();
    }
}
