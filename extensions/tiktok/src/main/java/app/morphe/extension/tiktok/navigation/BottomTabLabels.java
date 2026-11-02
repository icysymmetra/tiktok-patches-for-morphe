/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The names under TikTok's bottom tab icons.
 *
 * <p>Each tab's icon view is built from icon data that carries the tab's tag, and its name view
 * is made later by the tab's own logic and set on the icon in one place. Each kind of tab then
 * writes its name its own way, some through the icon's setters and some straight into the view,
 * so the switches are held in a check that runs before each frame: a name hidden or renamed here
 * stays that way whatever TikTok writes next, and turning the switch off puts back what TikTok
 * last showed.
 */
public final class BottomTabLabels {
    static final String FAMILY = "bottom tab names";

    /** Each icon view's tab tag, kept only as long as TikTok keeps the view. */
    private static final Map<View, String> TAGS = new WeakHashMap<>();
    /** The name views already held, so a second report adds no second check. */
    private static final Map<TextView, Boolean> HELD = new WeakHashMap<>();

    private BottomTabLabels() { }

    /** A bottom tab's icon view was built for the tab with this tag. */
    public static void tabCreated(@Nullable View icon, @Nullable String tag) {
        HookStatus.bound(FAMILY, "tab icon");
        if (icon == null || tag == null) return;
        synchronized (TAGS) {
            TAGS.put(icon, tag);
        }
    }

    /** A bottom tab's name view was set on its icon view. */
    public static void labelCreated(@Nullable View icon, @Nullable TextView label) {
        HookStatus.bound(FAMILY, "tab name");
        if (label == null) return;
        synchronized (HELD) {
            if (HELD.put(label, Boolean.TRUE) != null) return;
        }
        String tag;
        synchronized (TAGS) {
            tag = icon == null ? null : TAGS.get(icon);
        }
        Keeper keeper = new Keeper(label, BottomNavigationTabOptions.normalizeRuntimeTag(tag));
        label.addOnAttachStateChangeListener(keeper);
        if (label.isAttachedToWindow()) keeper.onViewAttachedToWindow(label);
        keeper.apply();
    }

    /** The name a tab should show, or an empty string to keep TikTok's. */
    static String nameFor(@Nullable String key) {
        StringSetting setting = settingFor(key);
        if (setting == null) return "";
        String name = setting.get();
        return name == null ? "" : name.trim();
    }

    @Nullable
    static StringSetting settingFor(@Nullable String key) {
        if (key == null) return null;
        switch (key) {
            case BottomNavigationTabOptions.HOME:
                return Settings.BOTTOM_TAB_NAME_HOME;
            case BottomNavigationTabOptions.FRIENDS:
                return Settings.BOTTOM_TAB_NAME_FRIENDS;
            case BottomNavigationTabOptions.INBOX:
                return Settings.BOTTOM_TAB_NAME_INBOX;
            case BottomNavigationTabOptions.PROFILE:
                return Settings.BOTTOM_TAB_NAME_PROFILE;
            case BottomNavigationTabOptions.MALL:
                return Settings.BOTTOM_TAB_NAME_SHOP;
            default:
                return null;
        }
    }

    /** Holds one name view to the switches, checked before each frame while it is on screen. */
    static final class Keeper implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final TextView label;
        @Nullable
        private final String key;
        /** Whether the view is hidden because of the switch, so turning it off shows it again. */
        private boolean hidden;
        /** The name of the user's own that is showing, and what TikTok showed before it. */
        @Nullable
        private String renamed;
        @Nullable
        private CharSequence own;

        Keeper(TextView label, @Nullable String key) {
            this.label = label;
            this.key = key;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            view.getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            view.getViewTreeObserver().removeOnPreDrawListener(this);
        }

        /**
         * Always lets the frame draw: a change made here shows on the next one, and a tab that
         * rewrote its name on every frame could never stall the screen.
         */
        @Override
        public boolean onPreDraw() {
            apply();
            return true;
        }

        /** Brings the view in line with the switches, and says whether anything changed. */
        boolean apply() {
            boolean changed = false;
            if (Settings.HIDE_BOTTOM_TAB_LABELS.get()) {
                if (label.getVisibility() != View.GONE) {
                    label.setVisibility(View.GONE);
                    hidden = true;
                    changed = true;
                }
            } else if (hidden) {
                hidden = false;
                if (label.getVisibility() == View.GONE) {
                    label.setVisibility(View.VISIBLE);
                    changed = true;
                }
            }

            String name = nameFor(key);
            CharSequence shown = label.getText();
            if (!name.isEmpty()) {
                if (!TextUtils.equals(shown, name)) {
                    if (renamed == null || !TextUtils.equals(shown, renamed)) own = shown;
                    renamed = name;
                    label.setText(name);
                    changed = true;
                }
            } else if (renamed != null) {
                if (TextUtils.equals(shown, renamed)) {
                    label.setText(own);
                    changed = true;
                }
                renamed = null;
                own = null;
            }
            return changed;
        }
    }
}
