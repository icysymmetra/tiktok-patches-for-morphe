/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featurecontrols;

import android.app.Activity;
import android.content.Intent;

import app.morphe.extension.tiktok.settings.Settings;

public final class FeatureControls {
    private static final int DEFAULT_LONG_PRESS_LOCK_DISTANCE_DP = 140;
    private static final String ACCOUNT_ACTIVITY_PREFIX = "com.ss.android.ugc.aweme.account.";
    private static final String SERVICE_MANAGER_CLASS =
            "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String ACCOUNT_USER_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.IAccountUserService";

    private FeatureControls() {
    }

    public static boolean shouldHideCaptchaPopup() {
        return Settings.HIDE_CAPTCHA_POPUPS.get() && isLoggedIn();
    }

    public static boolean shouldHideCaptchaPopup(Activity activity) {
        return shouldHideCaptchaPopup(activity, null);
    }

    public static boolean shouldHideCaptchaPopup(Activity activity, String riskInfo) {
        if (!Settings.HIDE_CAPTCHA_POPUPS.get() || !isLoggedIn()) return false;
        if (activity == null) return !isAccountRoute(riskInfo);

        // Account flows must be able to present server-required verification.
        if (activity.getClass().getName().startsWith(ACCOUNT_ACTIVITY_PREFIX)) return false;

        Intent intent = activity.getIntent();
        String intentRoute = intent == null ? null : intent.getDataString();
        return !isAccountRoute(intentRoute) && !isAccountRoute(riskInfo);
    }

    public static boolean shouldHideCaptchaPopup(Activity activity, Object verifyRequest) {
        if (!Settings.HIDE_CAPTCHA_POPUPS.get() || !isLoggedIn()) return false;
        if (activity != null && activity.getClass().getName().startsWith(ACCOUNT_ACTIVITY_PREFIX)) {
            return false;
        }

        String scene = readVerificationScene(verifyRequest);
        if (scene == null) return false;

        Intent intent = activity == null ? null : activity.getIntent();
        String intentRoute = intent == null ? null : intent.getDataString();
        return !isAccountRoute(intentRoute) && !isAccountRoute(scene);
    }

    private static String readVerificationScene(Object verifyRequest) {
        if (verifyRequest == null) return null;
        try {
            Object value = verifyRequest.getClass().getMethod("LJIIJ").invoke(verifyRequest);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLoggedIn() {
        try {
            Class<?> serviceManagerClass = Class.forName(SERVICE_MANAGER_CLASS);
            Object serviceManager = serviceManagerClass.getMethod("get").invoke(null);
            Class<?> accountServiceClass = Class.forName(ACCOUNT_USER_SERVICE_CLASS);
            Object accountService = serviceManagerClass
                    .getMethod("getService", Class.class)
                    .invoke(serviceManager, accountServiceClass);
            return accountService != null
                    && Boolean.TRUE.equals(accountServiceClass.getMethod("isLogin").invoke(accountService));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAccountRoute(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("login")
                || normalized.equals("passport")
                || normalized.contains("/passport/")
                || normalized.contains("/login/")
                || normalized.contains("\"passport\"")
                || normalized.contains("\"login\"");
    }

    public static Object filterNormalPendant(Object pendant) {
        return filterPromotionalTouchPoint(pendant);
    }

    public static Object filterPromotionalTouchPoint(Object touchPoint) {
        if (!Settings.HIDE_HOMEPAGE_COIN.get() || touchPoint == null) return touchPoint;

        String className = touchPoint.getClass().getName();
        switch (className) {
            case "com.bytedance.touchpoint.api.model.NormalPendant":
            case "com.bytedance.touchpoint.api.model.TimerPendant":
            case "com.bytedance.touchpoint.api.model.SunshinePendant":
            case "com.bytedance.touchpoint.api.model.CoinBottomTab":
            case "com.bytedance.touchpoint.api.model.BottomTabBubble":
                return null;
            default:
                return touchPoint;
        }
    }

    public static boolean overrideLongPressSpeedUpEnabled(boolean enabled) {
        return Settings.ENABLE_LONG_PRESS_SPEED_LOCK.get() || enabled;
    }

    public static int overrideLongPressSpeedUpLockDistance(int distanceDp) {
        if (!Settings.ENABLE_LONG_PRESS_SPEED_LOCK.get()) return distanceDp;
        return distanceDp > 0 ? distanceDp : DEFAULT_LONG_PRESS_LOCK_DISTANCE_DP;
    }

    public static boolean overrideHideQuickCommentEmoji(boolean original, int followStatus) {
        return Settings.HIDE_COMMENT_QUICK_REACTIONS.get() || original;
    }

    public static int hideFeedFollowButtonVisibility(int originalVisibility) {
        return Settings.HIDE_FEED_FOLLOW_BUTTON.get() ? 8 : originalVisibility;
    }

    public static int hideFeedSaveButtonVisibility(int originalVisibility) {
        return Settings.HIDE_FEED_SAVE_BUTTON.get() ? 8 : originalVisibility;
    }

    public static boolean hideFeedLiveButtonEnabled(boolean originalEnabled) {
        return !Settings.HIDE_FEED_LIVE_BUTTON.get() && originalEnabled;
    }

    public static boolean hideFeedSearchButtonEnabled(boolean originalEnabled) {
        return !Settings.HIDE_FEED_SEARCH_BUTTON.get() && originalEnabled;
    }

    public static int overrideLongPressQuickShare(int originalMode) {
        return Settings.DISABLE_LONG_PRESS_QUICK_SHARE.get() ? 0 : originalMode;
    }

    public static boolean disableLongPressRepost() {
        return Settings.DISABLE_LONG_PRESS_REPOST.get();
    }

    public static boolean enableNonPersonalizedSearch(boolean original) {
        return Settings.ENABLE_NON_PERSONALIZED_SEARCH.get() || original;
    }

    public static int forceNonPersonalizedSearchGate(String key, int value) {
        if (!"enable_non_personalized_search".equals(key)) return value;
        return Settings.ENABLE_NON_PERSONALIZED_SEARCH.get() ? 1 : value;
    }

    public static boolean enableLiveSearch(boolean original) {
        return Settings.ENABLE_LIVE_SEARCH.get() || original;
    }
}
