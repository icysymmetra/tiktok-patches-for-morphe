/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Feedback that stays inside the Hushfeed settings window instead of disappearing as a toast.
 *
 * <p>The settings pages replace one another inside a fragment container. The banner sits in the
 * stable surface above that container, with elevation above it, so an Undo offered on one page
 * remains visible if that page is replaced. Only one banner is shown at a time.</p>
 */
public final class SettingsActionBanner {
    private SettingsActionBanner() {
    }

    /** Set on the fragment container by {@code TikTokActivityHook}. */
    public static final String CONTENT_ROOT_TAG = "hushfeed_settings_content";
    static final String BANNER_TAG = "hushfeed_settings_action_banner";
    static final String MESSAGE_TAG = "hushfeed_settings_action_message";
    static final String ACTION_TAG = "hushfeed_settings_action_button";
    static final long VISIBLE_MS = 10_000L;
    /** The longest a banner stays, however long its message. */
    static final long LONGEST_VISIBLE_MS = 30_000L;

    /**
     * How long a banner with {@code message} stays: {@link #VISIBLE_MS} for a short one, longer
     * for a long one, at about a second per fifteen characters. A restore result that listed what
     * it changed was gone after ten seconds, before it could be read.
     */
    static long visibleMs(CharSequence message) {
        int length = message == null ? 0 : message.length();
        return Math.min(LONGEST_VISIBLE_MS, Math.max(VISIBLE_MS, 3_000L + length * 70L));
    }

    private static int generation;
    private static WeakReference<View> current = new WeakReference<>(null);

    public static void showUndo(Context context, String message, Runnable undo) {
        show(context, message, L10n.t(context, "Undo"), undo,
                L10n.t(context, "Couldn't undo that. Try again."));
    }

    public static void showNotice(Context context, String message) {
        show(context, message, null, null, null);
    }

    public static void showRestart(Context context, String message) {
        show(context, message, L10n.t(context, "Restart now"),
                () -> RestartPendingPreference.restart(context),
                L10n.t(context, "Couldn't restart TikTok. Close it and open it again."));
    }

    /** A message with an action of the caller's, such as the Lab's stop for a file app it waits on. */
    public static void showAction(Context context, String message, String actionLabel, Runnable action,
            String failure) {
        show(context, message, actionLabel, action, failure);
    }

    /**
     * Takes down the banner saying {@code message}, if it's still the one shown: an offer whose
     * action no longer applies, while a banner that has replaced it stays.
     */
    public static void dismissShowing(String message) {
        Utils.runOnMainThreadNowOrLater(() -> {
            View banner = current.get();
            View label = banner == null ? null : banner.findViewWithTag(MESSAGE_TAG);
            if (label instanceof TextView && message.contentEquals(((TextView) label).getText())) {
                dismissCurrent();
            }
        });
    }

    /**
     * @param failure what to say when the action throws. It used to be one sentence about
     *                undoing a clear for every action, so a Restart now that failed said the
     *                clear could not be undone.
     */
    private static void show(Context context, String message, String actionLabel, Runnable action,
            String failure) {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                Activity activity = activityFrom(context);
                FrameLayout root = contentRoot(activity);
                if (root == null) {
                    Utils.showToastShort(message);
                    return;
                }

                dismissCurrent();
                LinearLayout banner = new LinearLayout(activity);
                banner.setTag(BANNER_TAG);
                banner.setOrientation(LinearLayout.HORIZONTAL);
                banner.setGravity(Gravity.CENTER_VERTICAL);
                int vertical = SettingsUi.dp(activity, 10);
                int horizontal = SettingsUi.dp(activity, 16);
                banner.setPadding(horizontal, vertical, SettingsUi.dp(activity, 8), vertical);
                banner.setBackground(SettingsUi.borderedSurface(
                        activity, SettingsUi.RADIUS_SETTINGS_CARD, true));
                banner.setElevation(SettingsUi.dp(activity, 8));
                banner.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                // The banner sits over the bottom rows for up to half a minute. It wasn't
                // clickable, so a tap on it went through to the row it covered and could flip a
                // switch out of sight. A tap on it now takes it down instead.
                banner.setOnClickListener(view -> dismissCurrent());
                String close = L10n.t(activity, "Close");
                banner.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK, close));
                    }
                });

                TextView label = SettingsUi.text(activity, message, SettingsUi.TEXT_BODY_SMALL,
                        SettingsUi.textPrimary(), Typeface.NORMAL);
                label.setTag(MESSAGE_TAG);
                label.setLineSpacing(SettingsUi.dp(activity, 2), 1f);
                banner.addView(label, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                if (action != null) addAction(activity, banner, actionLabel, action, failure);

                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                int margin = SettingsUi.dp(activity, 16);
                params.setMargins(margin, margin, margin, margin);
                root.addView(banner, params);
                banner.bringToFront();
                current = new WeakReference<>(banner);

                final int token = ++generation;
                Utils.runOnMainThreadDelayed(() -> {
                    if (token == generation) dismissCurrent();
                }, SettingsUi.feedbackTimeout(activity, (int) visibleMs(message), action != null));
            } catch (Throwable throwable) {
                Logger.printException(() -> "Could not show the settings action banner", throwable);
                Utils.showToastShort(message);
            }
        });
    }

    private static void addAction(Activity activity, LinearLayout banner, String actionLabel,
            Runnable action, String failure) {
        TextView button = SettingsUi.text(activity, actionLabel, SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.accent(), Typeface.BOLD);
        button.setTag(ACTION_TAG);
        button.setContentDescription(actionLabel);
        button.setPadding(SettingsUi.dp(activity, 12), 0,
                SettingsUi.dp(activity, 12), 0);
        SettingsUi.styleTextAction(button, true);
        button.setBackground(SettingsUi.pressAndFocusOver(activity, SettingsUi.RADIUS_SETTINGS_FIELD,
                SettingsUi.borderedSurface(activity, SettingsUi.RADIUS_SETTINGS_FIELD, false)));

        final boolean[] used = {false};
        button.setOnClickListener(view -> {
            if (used[0]) return;
            used[0] = true;
            view.setEnabled(false);
            dismissCurrent();
            try {
                action.run();
            } catch (Throwable throwable) {
                Logger.printException(() -> "Could not run the settings banner action", throwable);
                Utils.showToastShort(failure);
            }
        });
        banner.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static Activity activityFrom(Context context) {
        for (Context at = context; at instanceof ContextWrapper;
                at = ((ContextWrapper) at).getBaseContext()) {
            if (at instanceof Activity) {
                Activity activity = (Activity) at;
                if (!activity.isFinishing() && !activity.isDestroyed()) return activity;
                return null;
            }
            Context next = ((ContextWrapper) at).getBaseContext();
            if (next == at) break;
        }
        Activity activity = Utils.getActivity();
        return activity == null || activity.isFinishing() || activity.isDestroyed()
                ? null : activity;
    }

    private static FrameLayout contentRoot(Activity activity) {
        if (activity == null) return null;
        View root = activity.findViewById(android.R.id.content);
        View content = root == null ? null : root.findViewWithTag(CONTENT_ROOT_TAG);
        return content instanceof FrameLayout ? (FrameLayout) content : null;
    }

    private static void dismissCurrent() {
        generation++;
        View banner = current.get();
        if (banner != null && banner.getParent() instanceof ViewGroup) {
            ((ViewGroup) banner.getParent()).removeView(banner);
        }
        current = new WeakReference<>(null);
    }

    static void dismissForTests() {
        dismissCurrent();
    }
}
