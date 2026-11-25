/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.share;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.adskip.AdSkipActions;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * "Mark ad" in a video's share sheet: opens the editor that cuts the ad out of this video, so
 * the same ad is skipped wherever it plays again. Built on the native Report carrier, like
 * Save cover and Block this creator, and placed first in the row so it's one tap away.
 */
public final class ShareMarkAd {
    static final String KEY = "metra_mark_ad";
    private static final String FAMILY = "ad skip";
    private static final String POST_PACKAGE = "com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage";
    private static final List<Built> BUILT = new ArrayList<>();

    private ShareMarkAd() {}

    private static final class Built {
        final WeakReference<Object> action;
        final WeakReference<Object> sharePackage;
        final WeakReference<Object> aweme;
        volatile boolean retired;

        Built(Object action, Object sharePackage, Object aweme) {
            this.action = new WeakReference<>(action);
            this.sharePackage = new WeakReference<>(sharePackage);
            this.aweme = new WeakReference<>(aweme);
        }

        boolean matches(Object from, Object post) {
            return !retired && post != null && sharePackage.get() == from && aweme.get() == post;
        }
    }

    private static boolean enabled() {
        return SettingsStatus.adSkipEnabled && Settings.AD_SKIP.get() && Settings.AD_SKIP_SHARE_BUTTON.get();
    }

    public static List<?> actions(Object sharePackage, List<?> actions) {
        if (actions == null) return null;
        List<Object> row = new ArrayList<>(actions.size() + 1);
        Built reusable = null;
        try {
            Object post = enabled() ? post(sharePackage) : null;
            boolean eligible = post != null && !isPhoto(post);
            for (Object item : actions) {
                Built built = find(item);
                if (built == null) row.add(item);
                else if (eligible && reusable == null && built.matches(sharePackage, post)) reusable = built;
                else if (built != reusable) built.retired = true;
            }
            if (eligible) {
                Object action = reusable == null ? null : reusable.action.get();
                if (action == null) {
                    Activity activity = Utils.getVisibleActivity();
                    if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                        action = OwnedShareActions.newAction(activity, post);
                        if (action != null) remember(new Built(action, sharePackage, post));
                    }
                }
                if (action != null) {
                    row.add(0, action);
                    HookStatus.bound(FAMILY, "share sheet button");
                }
            } else if (reusable != null) reusable.retired = true;
            return same(actions, row) ? actions : row;
        } catch (Throwable failure) {
            for (Object item : actions) {
                Built built = find(item);
                if (built != null) built.retired = true;
            }
            row.clear();
            for (Object item : actions) if (!owns(item)) row.add(item);
            HookStatus.threw(FAMILY, "share sheet button", failure);
            Logger.printException(() -> "Could not add Mark ad to the share sheet", failure);
            return same(actions, row) ? actions : row;
        }
    }

    private static boolean isPhoto(Object aweme) {
        Object images = Reflect.invoke(aweme, "getImageInfos");
        return images instanceof List<?> && !((List<?>) images).isEmpty();
    }

    private static boolean same(List<?> before, List<?> after) {
        if (before.size() != after.size()) return false;
        for (int i = 0; i < before.size(); i++) if (before.get(i) != after.get(i)) return false;
        return true;
    }

    private static Object post(Object sharePackage) {
        if (sharePackage == null || !POST_PACKAGE.equals(sharePackage.getClass().getName())
                || !"aweme".equals(Reflect.string(sharePackage, "getItemType", "itemType"))) return null;
        Object aweme = Reflect.readField(sharePackage, "aweme");
        return aweme instanceof Aweme ? aweme : null;
    }

    private static synchronized Built find(Object action) {
        Built found = null;
        for (Iterator<Built> each = BUILT.iterator(); each.hasNext();) {
            Built built = each.next();
            Object held = built.action.get();
            if (held == null) each.remove();
            else if (held == action) found = built;
        }
        return found;
    }

    private static synchronized void remember(Built built) {
        find(null);
        BUILT.add(built);
    }

    public static boolean owns(Object action) { return action != null && find(action) != null; }
    public static String key(Object action) { return owns(action) ? KEY : null; }
    public static String label(Object action) { return owns(action) ? L10n.t("Mark ad") : null; }

    public static Drawable icon(Object action) {
        if (!owns(action)) return null;
        Context context = Utils.getVisibleActivity();
        if (context == null) context = Utils.getContext();
        return new AdGlyphDrawable(SettingsUi.textPrimaryOn(SettingsUi.isDarkContext(context)),
                SettingsUi.dp(context, 2));
    }

    public static void bindIcon(Object action, ImageView image) {
        if (!owns(action)) return;
        Drawable icon = NativeShareIcon.restyle(image.getDrawable(), AdGlyphDrawable::new);
        if (icon == null) icon = icon(action);
        if (icon != null) image.setImageDrawable(icon);
    }

    public static boolean click(Object action, Context context, Object sharePackage) {
        Built built = find(action);
        if (built == null) return false;
        try {
            Object post = post(sharePackage);
            if (!enabled() || !built.matches(sharePackage, post)) return true;
            Activity from = context instanceof Activity ? (Activity) context : Utils.getVisibleActivity();
            // After the sheet has gone, so the editor opens over the feed rather than under it.
            Utils.runOnMainThreadDelayed(() -> AdSkipActions.mark(from, post), 250);
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "share sheet button", failure);
            Logger.printException(() -> "Could not open the ad editor from the share sheet", failure);
        }
        return true;
    }
}
