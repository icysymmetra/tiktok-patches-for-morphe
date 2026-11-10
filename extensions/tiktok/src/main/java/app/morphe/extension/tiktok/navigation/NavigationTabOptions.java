/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.navigation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The tabs above the feed. For You can't be turned off. */
public final class NavigationTabOptions {
    public static final String HOT = "HOT";
    public static final String EXPLORE = "EXPLORE";
    public static final String FOLLOWING = "FOLLOWING";
    public static final String MALL = "MALL";
    public static final String NEARBY = "NEARBY";
    public static final String FRIENDS = "FRIENDS";
    public static final String LIVE = "LIVE";
    public static final String POPULAR = "POPULAR";
    public static final String STEM = "STEM";
    public static final String SERIES = "SERIES";

    public static final TabOption[] OPTIONS = {
            new TabOption(HOT, "For You"),
            // TikTok renames Explore to Community on some accounts; the tag stays the same (#62).
            new TabOption(EXPLORE, "Explore or Community"),
            new TabOption(FOLLOWING, "Following"),
            new TabOption(MALL, "Shop"),
            new TabOption(NEARBY, "Nearby"),
            new TabOption(FRIENDS, "Friends"),
            new TabOption(LIVE, "LIVE"),
            new TabOption(POPULAR, "Popular"),
            new TabOption(STEM, "STEM"),
            new TabOption(SERIES, "Drama and Series"),
    };

    private static final TabKeys KEYS = new TabKeys(OPTIONS, aliases(), HOT);

    private NavigationTabOptions() {
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new HashMap<>();
        TabKeys.alias(aliases, HOT, "for you", "hot", "homepage_hot");
        TabKeys.alias(aliases, EXPLORE, "explore", "homepage_explore");
        TabKeys.alias(aliases, FOLLOWING, "following", "homepage_follow", "homepage_following");
        TabKeys.alias(aliases, MALL, "shop", "mall", "homepage_shop", "homepage_shop_mall");
        TabKeys.alias(aliases, NEARBY, "nearby", "local", "homepage_nearby");
        TabKeys.alias(aliases, FRIENDS, "friends", "friends_feed", "homepage_friends");
        TabKeys.alias(aliases, LIVE, "live", "homepage_live");
        TabKeys.alias(aliases, POPULAR, "popular", "homepage_popular");
        TabKeys.alias(aliases, STEM, "stem", "homepage_stem", "homepage_topic_stem");
        TabKeys.alias(aliases, SERIES, "drama", "series", "homepage_series");
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

    public static boolean isKnownKey(String key) {
        return KEYS.findOption(key) != null;
    }

    public static TabOption findOption(String key) {
        return KEYS.findOption(key);
    }
}
