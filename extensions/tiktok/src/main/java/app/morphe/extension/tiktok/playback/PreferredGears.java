/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.download.QualitySelector;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one rule Prefer H.264 video and Play SDR instead of HDR narrow a gear list by.
 *
 * <p>A preference never trades a gear the player can open for one it can't. When every preferred
 * gear has a missing, empty or unreadable play address and another gear has a usable one, the
 * list goes back whole, so Playback quality and the player still choose among the gears that
 * play. Whether an address is usable is {@link QualitySelector#hasUsableAddress}, the test the
 * quality picker already applies, which says nothing about the codec. A list where no gear has a
 * usable address tells nothing about which plays, so there the preference stands as it always
 * did. Every outcome is a list the next pass leaves alone, so the setter hooks agree in any order.
 */
final class PreferredGears {
    /** Preferences already described as kept whole, so a feed of such lists costs one line each. */
    private static final Set<String> DESCRIBED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private PreferredGears() {}

    /** {@code preferred} when it narrows {@code original} and a gear in it can play, else {@code original}. */
    static List<?> narrow(List<?> original, List<?> preferred, String preference) {
        if (preferred.isEmpty() || preferred.size() == original.size()) return original;
        if (anyUsable(preferred) || !anyUsable(original)) return preferred;
        if (DESCRIBED.add(preference)) {
            int skipped = preferred.size();
            Logger.printInfo(() -> preference + " kept all " + original.size() + " gears: none of its "
                    + skipped + " preferred gear(s) had a playable address");
        }
        return original;
    }

    private static boolean anyUsable(List<?> gears) {
        for (Object gear : gears) {
            if (QualitySelector.hasUsableAddress(gear)) return true;
        }
        return false;
    }

    static void resetForTests() {
        DESCRIBED.clear();
    }
}
