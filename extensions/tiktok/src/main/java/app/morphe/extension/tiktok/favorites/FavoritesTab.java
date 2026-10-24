/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.favorites;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers the two server gates that empty the profile's Favorites tab.
 *
 * <p>Issue #4: two people found the Favorites tab on their profile showing nothing after
 * patching, with the tab itself still there. On 46.2.3 the tab's pages are built in
 * {@code FavoritesFragmentV2.onCreate}, and two app AB gates skip that: {@code favorite_reverse}
 * read as 1, or {@code new_user_favorite_full_reversal_exp} read as true. Forcing either on the
 * S22 through the Feature Gate Lab produced the reporters' page exactly, saved videos gone and
 * the "Posts / Collections" row with them. Both values come from TikTok's server, so nothing
 * here can say why an account is in that experiment; what it can do is answer the two reads
 * with the values that keep the tab, and say in the report when it did.
 */
public final class FavoritesTab {
    /** The Hook status family this reports under. */
    static final String FAMILY = "favorites tab";
    static final String INSTALLED = "installed";
    /** An int gate: 1 skips building the tab's pages. */
    static final String REVERSE_GATE = "favorite_reverse";
    /** A boolean gate: true does the same for accounts the server counts as new. */
    static final String NEW_USER_GATE = "new_user_favorite_full_reversal_exp";

    /** Gates already written about, so a profile opened all afternoon costs one line each. */
    private static final Set<String> REFUSED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private FavoritesTab() {}

    /** Called at settings load, so the family is in every export whether or not a gate fired. */
    public static void installed() {
        HookStatus.bound(FAMILY, INSTALLED);
    }

    /** Every app AB int read goes through here; only the one gate, read as 1, is answered. */
    public static int keepInt(String key, int value) {
        if (value == 0 || !REVERSE_GATE.equals(key) || !Settings.KEEP_FAVORITES_TAB.get()) return value;
        refused(key, String.valueOf(value));
        return 0;
    }

    /** Every app AB boolean read goes through here; only the one gate, read as true, is answered. */
    public static boolean keepBoolean(String key, boolean value) {
        if (!value || !NEW_USER_GATE.equals(key) || !Settings.KEEP_FAVORITES_TAB.get()) return value;
        refused(key, "true");
        return false;
    }

    private static void refused(String key, String value) {
        HookStatus.bound(FAMILY, "refused " + key);
        if (!REFUSED.add(key)) return;
        Logger.printInfo(() -> "Kept the Favorites tab: " + key + " came back " + value);
    }

    /** Forgets which gates have been written about, so a test can watch the first one again. */
    static void resetForTests() {
        REFUSED.clear();
    }
}
