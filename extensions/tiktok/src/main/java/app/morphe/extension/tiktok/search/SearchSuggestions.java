/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.search;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/** Whether the search page's suggested words are asked for and shown. */
@SuppressWarnings("unused")
public final class SearchSuggestions {
    private SearchSuggestions() {
    }

    public static boolean shouldHide() {
        return SettingsStatus.hideSearchSuggestionsEnabled && Settings.HIDE_SEARCH_SUGGESTIONS.get();
    }

    /**
     * Whether the search rewards service is withheld (issue #21): the points banner under the
     * search box and the coin counter over results. TikTok's pages treat a missing service as
     * an account with no rewards.
     */
    public static boolean shouldHideRewards() {
        return SettingsStatus.hideSearchSuggestionsEnabled && Settings.HIDE_SEARCH_REWARDS.get();
    }

    /**
     * TikTok's answer to whether a search rewards feature is downloaded, asked by each rewards
     * service's lazy getter. Answering no hands out the placeholder service, so no coin or
     * banner is built until TikTok restarts with the switch off.
     */
    public static boolean filterRewardsLoaded(boolean loaded) {
        return loaded && !shouldHideRewards();
    }

    public static boolean filterCachedSuggestions(boolean nativeValue) {
        return nativeValue && !shouldHide();
    }

    public static Object filterLynxParameter(String key, Object nativeValue) {
        if (shouldHide()) {
            if ("show_suggest_search_words".equals(key)) return Integer.valueOf(0);
            if ("is_lynx_request_suggest".equals(key)) return Boolean.FALSE;
        }
        return nativeValue;
    }

    public static int filterReentryVisibility(int nativeValue) {
        return shouldHide() ? 0 : nativeValue;
    }
}
