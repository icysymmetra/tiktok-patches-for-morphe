/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

final class FeatureGateLabUi {
    private FeatureGateLabUi() {
    }

    static void syncTheme(Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        Utils.setIsDarkModeEnabled(nightMode == Configuration.UI_MODE_NIGHT_YES);
    }

    static TextView text(Context context, String text, float size, int color, int style) {
        return SettingsUi.text(context, text, size, color, style);
    }

    static TextView heading(Context context, String text) {
        return text(context, text, 20, SettingsUi.textPrimary(), Typeface.BOLD);
    }

    static FrameLayout header(Context context, String title, Runnable onBack) {
        return header(context, title, onBack, null);
    }

    static FrameLayout header(Context context, String title, Runnable onBack, Runnable onMenu) {
        FrameLayout frame = new FrameLayout(context);
        frame.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 24));
        LinearLayout header = app.morphe.extension.tiktok.settings.preference.SettingsHeaderPreference.createHeader(context, title, onBack);
        if (onMenu != null) {
            // Metra already places the title in the toolbar. Add the Lab action to its free
            // slot; moving children by index aliases the title with the old brand label.
            LinearLayout toolbar = header.findViewWithTag("hushfeed_toolbar");
            frame.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 8));
            View menu = iconButton(context, "icon_ellipsis_horizontal", android.R.drawable.ic_menu_more,
                    L10n.t(context, "More options"), onMenu);
            menu.setTag("feature_gate_menu");
            toolbar.addView(menu,
                    new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        }
        frame.addView(header, new FrameLayout.LayoutParams(-1, -2));
        return frame;
    }

    static View clearSearchButton(Context context, Runnable onClear) {
        return iconButton(
                context,
                "icon_x_mark_small",
                android.R.drawable.ic_menu_close_clear_cancel,
                L10n.t(context, "Clear search"),
                onClear
        );
    }

    private static View iconButton(
            Context context,
            String rawIconName,
            int fallbackDrawable,
            String description,
            Runnable action
    ) {
        View icon = createTuxIcon(context, rawIconName);
        if (icon == null) {
            ImageButton fallback = new ImageButton(context);
            if ("icon_ellipsis_horizontal".equals(rawIconName)) {
                fallback.setImageDrawable(new android.graphics.drawable.Drawable() {
                    private final android.graphics.Paint paint = new android.graphics.Paint(3);
                    @Override public void draw(android.graphics.Canvas canvas) {
                        paint.setColor(SettingsUi.textPrimary());
                        float cx = getBounds().exactCenterX();
                        float cy = getBounds().exactCenterY();
                        for (int i = -1; i <= 1; i++) canvas.drawCircle(cx, cy + dp(context, 6) * i, dp(context, 2), paint);
                    }
                    @Override public int getIntrinsicWidth() { return dp(context, 24); }
                    @Override public int getIntrinsicHeight() { return dp(context, 24); }
                    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
                    @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
                    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
                });
            } else {
                fallback.setImageResource(fallbackDrawable);
            }
            fallback.setColorFilter(SettingsUi.textPrimary());
            fallback.setScaleType(ImageButton.ScaleType.CENTER);
            icon = fallback;
        }
        icon.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        // The settings pages' press and focus, on the control radius. The platform's borderless
        // ripple drew a circle and had no focus state, so a d-pad or keyboard could land on an
        // icon button and leave nothing on screen to say so.
        icon.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_PILL,
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        icon.setContentDescription(description);
        icon.setFocusable(true);
        SettingsUi.markAsButton(icon);
        icon.setOnClickListener(view -> action.run());
        return icon;
    }

    private static View createTuxIcon(Context context, String rawIconName) {
        try {
            int iconId = context.getResources().getIdentifier(rawIconName, "raw", context.getPackageName());
            if (iconId == 0) {
                return null;
            }
            Class<?> iconClass = Class.forName("com.bytedance.tux.icon.TuxIconView");
            Constructor<?> constructor = iconClass.getConstructor(Context.class);
            Object icon = constructor.newInstance(context);
            iconClass.getMethod("setIconRes", int.class).invoke(icon, iconId);
            iconClass.getMethod("setIconWidth", int.class).invoke(icon, dp(context, 24));
            iconClass.getMethod("setIconHeight", int.class).invoke(icon, dp(context, 24));
            iconClass.getMethod("setTintColor", int.class).invoke(icon, SettingsUi.textPrimary());
            return (View) icon;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager input = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) {
            input.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
        view.clearFocus();
    }

    /**
     * A row that is the switch: one screen-reader stop with the Switch role, the whole row a
     * touch target, and the control itself invisible to accessibility so nothing is read twice.
     *
     * <p>The Lab's master row and the detail page's three switch rows only toggled on the 44dp
     * switch, and TalkBack read the title, then the summary, then the switch's copy of the
     * title. Every switch row in the main settings is one stop that toggles on a tap anywhere;
     * these four now match it, through this one builder.
     */
    static LinearLayout switchRow(Context context, String title, String summary, Switch control) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 56));
        row.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        // The row answers the tap and takes focus, so it wears the settings press and focus over
        // its card. A plain card showed nothing for either.
        row.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_SETTINGS_CARD,
                SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_CARD, false)));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(body(context, title), matchWrap());
        if (summary != null && !summary.isEmpty()) {
            TextView detail = label(context, summary);
            detail.setPadding(0, dp(context, 4), 0, 0);
            labels.addView(detail, matchWrap());
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // The control draws the state and nothing else: the row answers the tap and the reader.
        control.setClickable(false);
        control.setFocusable(false);
        control.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 48)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(summary == null || summary.isEmpty()
                ? title : L10n.f(context, "%1$s. %2$s", title, summary));
        row.setOnClickListener(view -> {
            if (control.isEnabled()) control.toggle();
        });
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(control.isChecked());
                info.setEnabled(control.isEnabled());
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        });
        return row;
    }

    static TextView label(Context context, String text) {
        return FeatureGateLabUi.text(context, text, SettingsUi.TEXT_LABEL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
    }

    static TextView body(Context context, String text) {
        return FeatureGateLabUi.text(context, text, SettingsUi.TEXT_TITLE,
                SettingsUi.textPrimary(), Typeface.NORMAL);
    }

    static void styleTab(View tab, boolean selected) {
        Context context = tab.getContext();
        android.graphics.drawable.GradientDrawable surface = SettingsUi.roundedSurface(
                context, SettingsUi.RADIUS_PILL, selected);
        surface.setStroke(Math.max(1, dp(context, 1)),
                selected ? SettingsUi.accent() : SettingsUi.border());
        tab.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_PILL, surface));
    }

    static View notice(Context context, String text, int toneColor) {
        LinearLayout notice = SettingsUi.inlineNotice(context, text, toneColor);
        notice.setBaselineAligned(false);
        android.graphics.drawable.GradientDrawable surface = SettingsUi.borderedSurface(
                context, SettingsUi.RADIUS_SETTINGS_CARD, false);
        surface.setStroke(Math.max(1, dp(context, 1)), toneColor);
        notice.setBackground(surface);
        ((TextView) notice.getChildAt(0)).setTextSize(SettingsUi.TEXT_HEADLINE);
        ((TextView) notice.getChildAt(1)).setTextSize(SettingsUi.TEXT_LABEL);
        return notice;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** For a child that should be no wider than its own text, such as an empty state's action. */
    static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    static int dp(Context context, int value) {
        return SettingsUi.dp(context, value);
    }

    /**
     * Read from the same place as every other colour on the screen. Reading the configuration
     * here instead meant the warning could be painted for one theme while its surface was
     * painted for the other.
     */
    static int warningColor(Context context) {
        return SettingsUi.attentionColor();
    }

    static void styleDialog(AlertDialog dialog) {
        SettingsUi.styleFramedDialog(dialog);
        if (dialog.getWindow() != null) {
            styleDialogTree(dialog.getWindow().getDecorView());
        }
    }

    private static void styleDialogTree(View view) {
        if (view instanceof RadioButton) {
            RadioButton radio = (RadioButton) view;
            radio.setTextColor(SettingsUi.textPrimary());
            radio.setButtonTintList(ColorStateList.valueOf(SettingsUi.accent()));
        } else if (view instanceof Button) {
            // Everything except the dialog's own three actions. styleFramedDialog has already
            // given the positive one the accent and the other two the secondary colour, and
            // this walk runs after it: repainting them all one shade put Use value and Cancel
            // on the same footing, which is the opposite of what a destructive-looking pair of
            // actions needs. It also replaced their state lists with a flat colour, so a
            // disabled action stopped looking disabled.
            int id = view.getId();
            if (id != android.R.id.button1 && id != android.R.id.button2
                    && id != android.R.id.button3) {
                ((Button) view).setTextColor(SettingsUi.accent());
            }
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(SettingsUi.textPrimary());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleDialogTree(group.getChildAt(i));
            }
        }
    }

    static final class BackInterceptLayout extends LinearLayout {
        private final Runnable onBack;

        BackInterceptLayout(Context context, Runnable onBack) {
            super(context);
            this.onBack = onBack;
            setFocusableInTouchMode(true);
            setOnKeyListener((view, keyCode, event) -> consumeBack(event));
        }

        @Override
        public boolean dispatchKeyEventPreIme(KeyEvent event) {
            return consumeBack(event) || super.dispatchKeyEventPreIme(event);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return consumeBack(event) || super.dispatchKeyEvent(event);
        }

        private boolean consumeBack(KeyEvent event) {
            if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBack.run();
            return true;
        }
    }
}
