/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Takes down the interstitial TikTok lays over a video it has classified, so the video
 * plays instead of asking to be tapped through on every pass.
 *
 * A video carries its masks on the Aweme itself, in fields that keep their real names on
 * 46.2.3: {@code videoMaskInfo} and the {@code videoMaskInfos} list, plus the four typed
 * ones. Each is a {@code VideoMaskInfo} whose {@code showMask} flag decides whether the
 * overlay is drawn, so clearing that flag is enough and leaves the rest of the model alone.
 * The four typed fields are additionally dropped, which is what TikTok's own reset does to
 * them, so a null there is a state the app already handles.
 *
 * This runs as the item is bound, before anything has read the model to build the overlay.
 */
public final class SensitiveWarnings {
    /** Cleared outright; TikTok's own reset nulls exactly these four. */
    private static final String[] DROPPED_MASKS = {
            "generalMaskInfo",
            "photosensitiveMaskInfo",
            "reportMaskInfo",
            "contentClassificationMaskInfo",
    };

    /** Kept, with the overlay flag turned off. */
    private static final String[] FLAGGED_MASKS = {
            "videoMaskInfo",
    };

    private static final String MASK_LIST = "videoMaskInfos";
    private static final String SHOW_MASK = "showMask";

    /**
     * What Hook status calls this. The switch reads on whatever happens here, so without a
     * line of its own a renamed mask model is indistinguishable from a video TikTok never
     * flagged: the warning is simply still there and nothing says why.
     */
    static final String FAMILY = "sensitive warnings";

    private SensitiveWarnings() {
    }

    /**
     * True while a video's risk model should read as absent. Called from Aweme's
     * getAwemeRiskModel, the one way TikTok's readers reach it: the Check sources banner, the
     * warning label, the bottom notice, the share panel's warning flag and the flag sent with
     * a share all treat the video as unflagged, which is how they treat most videos. Paused,
     * the switch answers off.
     */
    public static boolean hideUnverifiedNotices() {
        return SettingsStatus.unverifiedNoticesEnabled && Settings.HIDE_UNVERIFIED_NOTICES.get();
    }

    /**
     * True while TikTok's Know the facts warnings should stay down. Asked in front of the gate the
     * top caution banner ("Check your sources before sharing") and its trigger both call, where a
     * false is what every video without that warning gets, and in front of the prompt TikTok
     * raises when one is shared, where a false lets the share go ahead. This is its own model
     * (KtfInfo on the upload struct), not the risk model the unverified notices switch empties.
     * Paused, the switch answers off.
     */
    public static boolean hideCheckYourSources() {
        return SettingsStatus.checkYourSourcesEnabled && Settings.HIDE_CHECK_YOUR_SOURCES.get();
    }

    /**
     * Called as TikTok binds a feed item.
     *
     * @param videoItemParams a {@code com.ss.android.ugc.aweme.feed.model.VideoItemParams}
     */
    public static void clear(Object videoItemParams) {
        try {
            if (!Settings.HIDE_SENSITIVE_WARNINGS.get()) {
                return;
            }

            Object aweme = Reflect.property(videoItemParams, "getAweme", "aweme");
            if (aweme == null) {
                return;
            }

            for (String name : FLAGGED_MASKS) {
                hideMask(Reflect.readField(aweme, name));
            }

            Object list = Reflect.readField(aweme, MASK_LIST);
            if (list instanceof List) {
                for (Object mask : (List<?>) list) {
                    hideMask(mask);
                }
            }

            for (String name : DROPPED_MASKS) {
                hideMask(Reflect.readField(aweme, name));
                clearField(aweme, name);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not clear the content warning", ex);
        }
    }

    /** Turns off a single mask's overlay flag. */
    private static void hideMask(Object mask) {
        if (mask == null) {
            return;
        }
        Field field = findField(mask.getClass(), SHOW_MASK);
        if (field == null) {
            HookStatus.missingMember(FAMILY, "field", mask.getClass().getName(), SHOW_MASK);
            return;
        }
        try {
            field.set(mask, Boolean.FALSE);
            HookStatus.bound(FAMILY, mask.getClass().getName() + '#' + SHOW_MASK);
        } catch (Exception refused) {
            // Found and would not take the value: the model kept the name and changed the type,
            // or the field is final. Either way the warning stays, and the row now says so.
            HookStatus.missingMember(FAMILY, "writable field", mask.getClass().getName(), SHOW_MASK);
            Logger.printDebug(() -> "Could not turn off " + SHOW_MASK, refused);
        }
    }

    private static void clearField(Object target, String name) {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            // Not every build carries all four, and TikTok's own reset skips the ones it has
            // dropped, so an absent one is ordinary rather than a miss worth reporting.
            return;
        }
        try {
            field.set(target, null);
        } catch (Exception refused) {
            HookStatus.missingMember(FAMILY, "writable field", target.getClass().getName(), name);
            Logger.printDebug(() -> "Could not clear " + name, refused);
        }
    }

    private static Field findField(Class<?> type, String name) {
        return Reflect.field(type, name);
    }
}
