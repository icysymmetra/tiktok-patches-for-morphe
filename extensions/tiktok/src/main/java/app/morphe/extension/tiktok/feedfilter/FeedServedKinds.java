/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Names the kinds of one native feed element a phone is served, such as the strip above a
 * caption or the banner under a video, before anyone has to guess a key from TikTok's config.
 *
 * <p>A kind is TikTok's component key, with TikTok's type number after it when there is one, and
 * never what the element says or links to. A key that isn't an identifier has no kind. The
 * routes count kinds in the export whatever the logging switch says. With diagnostic logging on,
 * each kind is also logged the first time it is met, which dates it. A kind met while logging is
 * off isn't remembered here, so switching logging on later still logs it.
 */
final class FeedServedKinds {
    private static final int MAX_KINDS = 32;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.-]{1,80}");

    private final String element;
    private final Set<String> named = Collections.newSetFromMap(new ConcurrentHashMap<>());

    FeedServedKinds(String element) {
        this.element = element;
    }

    /** {@code key}, or {@code key/type} when TikTok gives a type number, or null for no key. */
    static String kind(Object key, Object type) {
        if (!(key instanceof String) || !IDENTIFIER.matcher((String) key).matches()) return null;
        return type instanceof Integer ? key + "/" + type : (String) key;
    }

    /** Logs a kind from {@link #kind} the first time it is met with diagnostic logging on. */
    void note(String kind) {
        if (kind == null || !BaseSettings.DEBUG.get() || named.size() >= MAX_KINDS || !named.add(kind)) return;
        Logger.printInfo(() -> "[Morphe TikTok FeedFilter] " + element + " served: " + kind);
    }

    /** Forgets the kinds already logged, between deterministic runtime tests. */
    void resetForTests() {
        named.clear();
    }
}
