/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;
import app.morphe.extension.tiktok.privacy.DeviceIdentity;
import app.morphe.extension.tiktok.privacy.BenchmarkRuns;
import app.morphe.extension.tiktok.settings.preference.ReleaseNotes;

import java.util.Arrays;

/**
 * TikTok's own recovery from crashes at launch. On the third launch crash in a row it deletes
 * every preferences file whose name isn't on its keep-list, and Hushfeed's weren't on it: a crash
 * loop erased every setting, the Lab's rules and the Calm feed undo one start before Hushfeed's
 * safe mode could pause anything.
 *
 * <p>This runs where TikTok builds that list, which can be inside its crash handler and before
 * Hushfeed has a context, so it touches nothing but the array it is given. The names are
 * compile-time constants, and reading them loads none of the classes they come from.
 */
public final class LaunchCrashCleanup {
    /** Every preferences file Hushfeed writes. A new one belongs here as well. */
    static final String[] HUSHFEED_FILES = {
            Setting.PREFERENCES_NAME,
            FeatureGateLabStore.PREFS_NAME,
            CalmFeedPreset.PREFERENCES,
            ReleaseNotes.PREFS_NAME,
            DeviceIdentity.PREFERENCES,
            BenchmarkRuns.STATE_FILE,
    };

    private LaunchCrashCleanup() {
    }

    /** TikTok's keep-list with Hushfeed's files after it. */
    public static String[] keepHushfeedFiles(String[] kept) {
        if (kept == null) return HUSHFEED_FILES.clone();
        String[] joined = Arrays.copyOf(kept, kept.length + HUSHFEED_FILES.length);
        System.arraycopy(HUSHFEED_FILES, 0, joined, kept.length, HUSHFEED_FILES.length);
        return joined;
    }
}
