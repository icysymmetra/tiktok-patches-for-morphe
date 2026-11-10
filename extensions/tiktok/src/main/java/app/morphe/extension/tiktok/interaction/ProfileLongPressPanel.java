/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full long-press menu on a creator's videos and in search results (#120). TikTok picks the
 * long-press panel's shape by the page it opened on, looked up in its server's long-press panel
 * config: For You gets the full menu, a video opened from someone's profile or from search only
 * the small share sheet. Every lookup in that config passes the page's name through one
 * normalizer first, and this answers For You's name there for a profile's or a search page's, so
 * those get For You's menu. The share itself still says where it came from.
 */
public final class ProfileLongPressPanel {
    static final String PROFILE = "others_homepage";
    static final String FOR_YOU = "homepage_hot";
    /** Search's Top results and its Videos tab. */
    static final String SEARCH_TOP = "general_search";
    static final String SEARCH_VIDEOS = "search_result";

    /**
     * Page names already logged, so a debug log names each page once. One long press looks up
     * the page it opened on and For You in turn, so remembering only the last name logged both
     * of them again on every lookup (S22, 47.1.4).
     */
    private static final Set<String> LOGGED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ProfileLongPressPanel() {
    }

    /** Called first thing in TikTok's long-press scene normalizer, with the page's name. */
    @Nullable
    public static String scene(@Nullable String scene) {
        try {
            if (scene != null && LOGGED.add(scene)) {
                Logger.printDebug(() -> "Long-press panel page: " + scene);
            }
            // With a Long press action of Hushfeed's the menu never opens, and the page's Share
            // button sheet, which reads the same config, is left as TikTok draws it.
            if (!SettingsStatus.longPressEnabled
                    || GestureActions.takesLongPress(Settings.LONG_PRESS_ACTION.get())) {
                return scene;
            }
            if (PROFILE.equals(scene) && Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.get()) {
                return FOR_YOU;
            }
            if ((SEARCH_TOP.equals(scene) || SEARCH_VIDEOS.equals(scene))
                    && Settings.LONG_PRESS_FULL_MENU_IN_SEARCH.get()) {
                return FOR_YOU;
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not choose the long-press menu", error);
        }
        return scene;
    }
}
