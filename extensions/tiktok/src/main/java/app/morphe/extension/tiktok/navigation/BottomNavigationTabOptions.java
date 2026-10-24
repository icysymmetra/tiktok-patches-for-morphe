/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.navigation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The bottom navigation tabs. Home and Profile can't be turned off. */
public final class BottomNavigationTabOptions {
    public static final String HOME = "HOME";
    public static final String FRIENDS = "FRIENDS";
    public static final String PUBLISH = "PUBLISH";
    public static final String INBOX = "INBOX";
    public static final String PROFILE = "PROFILE";
    public static final String MALL = "MALL";

    public static final TabOption[] OPTIONS = {
            new TabOption(HOME, "Home"),
            new TabOption(FRIENDS, "Friends"),
            new TabOption(PUBLISH, "Create"),
            new TabOption(INBOX, "Inbox"),
            new TabOption(PROFILE, "Profile"),
            new TabOption(MALL, "Shop"),
    };

    private static final TabKeys KEYS = new TabKeys(OPTIONS, aliases(), HOME, PROFILE);

    private BottomNavigationTabOptions() {
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new HashMap<>();
        TabKeys.alias(aliases, HOME, "home", "homepage_home");
        TabKeys.alias(aliases, FRIENDS, "friends", "friends_tab", "homepage_friends");
        TabKeys.alias(aliases, PUBLISH, "publish", "create", "plus");
        TabKeys.alias(aliases, INBOX, "notification", "inbox", "message");
        TabKeys.alias(aliases, PROFILE, "user", "profile", "me");
        TabKeys.alias(aliases, MALL, "shop", "mall", "shop_mall");
        return aliases;
    }

    public static String defaultEnabledKeys() {
        return KEYS.defaultEnabledKeys();
    }

    public static Set<String> parseEnabledKeys(String keys) {
        return KEYS.parseEnabledKeys(keys);
    }

    public static Set<String> parseObservedKeys(String keys) {
        return KEYS.parseObservedKeys(keys);
    }

    public static String serializeEnabledKeys(Set<String> keys) {
        return KEYS.serializeEnabledKeys(keys);
    }

    public static List<TabOption> optionsForKeys(Set<String> keys) {
        return KEYS.optionsForKeys(keys);
    }

    public static String normalizeRuntimeTag(String tag) {
        return KEYS.normalizeRuntimeTag(tag);
    }

    public static TabOption findOption(String key) {
        return KEYS.findOption(key);
    }

    public static boolean isRequiredKey(String key) {
        return KEYS.isRequiredKey(key);
    }
}
