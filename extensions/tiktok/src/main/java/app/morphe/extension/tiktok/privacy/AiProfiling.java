/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switch in front of Stop on-device AI profiling.
 *
 * <p>Its three hooks are the Pitaya plugin lookup, the door a real Pitaya core comes in by, and
 * the start of Pitaya Lite. With the switch on the lookup answers "no plugin", which every caller
 * already copes with, and the other two return before they do anything. With it off, and while
 * Hushfeed is paused, all three run as TikTok wrote them. Pitaya starts during launch, so a call
 * before the settings context exists reads the switch from the saved file.
 */
@SuppressWarnings("unused")
public final class AiProfiling {
    /** {@link Settings#STOP_AI_PROFILING}'s key, for the read before the settings context. */
    static final String SWITCH_KEY = "stop_ai_profiling";

    private AiProfiling() {
    }

    public static boolean stopsEngine() {
        return Utils.getContext() != null ? Settings.STOP_AI_PROFILING.get() : EarlySwitch.isOn(SWITCH_KEY);
    }
}
