/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Which of TikTok's first-launch setup steps are passed over. TikTok's setup asks each step
 * whether it should show, by its id, and moves on to the next one when the answer is no, the
 * way it already does for a step its own rules leave out.
 */
@SuppressWarnings("unused")
public final class FirstLaunchSetup {
    /**
     * The steps that only ask about taste or teach a gesture. Every other step keeps TikTok's own
     * answer: consent and the Hungarian consent box, the age gate, ad choice and the subscription
     * offers, the teen privacy pages and the private account tip, every sign-in step, deep links,
     * Android's own notification prompt (push_page_advance and push_popup_background both show
     * it, over a background of TikTok's), and the slogan page, since one of its layouts carries
     * the consent box. So does a step this list doesn't know, a new one in a later build included.
     */
    static final Set<String> SKIPPED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "interest_list",
            "interest_sub_tag",
            "content_language",
            "gender_selection",
            "follow_trending_creators",
            "swipe_up",
            "push_auth_preposition_page"
    )));

    private FirstLaunchSetup() {
    }

    /** True when the setup step with this id should be passed over. Paused, every step shows. */
    public static boolean skipStep(@Nullable String id) {
        if (id == null || !SKIPPED.contains(id)) return false;
        if (!SettingsStatus.firstLaunchSetupEnabled || !Settings.SKIP_FIRST_LAUNCH_SETUP.get()) return false;
        Logger.printDebug(() -> "Skip first-launch setup: passed over " + id);
        return true;
    }
}
