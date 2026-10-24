/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsReturn;

/**
 * The row pinned to the top of every settings page while a restart is owed.
 *
 * <p>Flip three restart-gated switches and you got three identical toasts, then nothing; come
 * back later and nothing said a restart was still owed, and there was no way to do it although
 * the app can relaunch itself. This row counts the changes waiting and restarts TikTok when
 * pressed. It sits between the page header and its caption, on every page, and goes away with
 * the process, which is the only thing that settles the debt.
 */
@SuppressWarnings("deprecation")
public final class RestartPendingPreference extends Preference {
    public static final String KEY = "hushfeed_restart_pending";
    static final String ROW_TAG = "hushfeed_restart_pending_row";

    /** What the press does with the context it is given. */
    public interface Restarter {
        void restart(Context context);
    }

    /**
     * What the press does. Tests put a recorder here; the real thing ends the process. Its own
     * interface rather than java.util.function: that package arrives at API 24 and this runs
     * from 23.
     */
    static Restarter restarter = Utils::restartApp;

    public RestartPendingPreference(Context context) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        // The row is the button, so the list must not answer the press a second time.
        setSelectable(false);
        // Between the page header (-1000) and its caption (-900).
        setOrder(-950);
    }

    /** What the row says: one change, or how many. */
    public static String label(Context context) {
        int count = AbstractPreferenceFragment.restartPending.size();
        return L10n.quantity(context, count,
                "Restart TikTok to apply this change", "Restart TikTok to apply %1$d changes");
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        TextView row = SettingsUi.text(context, label(context), SettingsUi.TEXT_BODY, SettingsUi.accent(),
                Typeface.BOLD);
        row.setTag(ROW_TAG);
        int side = SettingsUi.dp(context, 16);
        int ends = SettingsUi.dp(context, 14);
        row.setPadding(side, ends, side, ends);
        row.setMinimumHeight(SettingsUi.dp(context, 48));
        row.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_SETTINGS_CARD,
                SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_CARD, false)));
        row.setFocusable(true);
        // A TextView with a click listener is read as text; the role has to be said.
        SettingsUi.markAsButton(row);
        row.setOnClickListener(view -> restart(view.getContext()));

        FrameLayout holder = new FrameLayout(context);
        holder.setBackgroundColor(SettingsUi.background());
        int gutter = SettingsUi.dp(context, 8);
        holder.setPadding(gutter, gutter, gutter, 0);
        holder.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        TextView row = view.findViewWithTag(ROW_TAG);
        if (row != null) row.setText(label(getContext()));
    }

    /** Test seam: the press would otherwise end the test process. */
    public static void setRestarterForTests(Restarter replacement) {
        restarter = replacement == null ? Utils::restartApp : replacement;
    }

    /**
     * Notes the page open now and how far down it was, so the next start lands back there, then
     * relaunches. The row and the banner's Restart both come through here, and both are pressed on
     * a settings page.
     */
    static void restart(Context context) {
        TikTokPreferenceFragment open = TikTokPreferenceFragment.active();
        SettingsReturn.rememberBeforeRestart(open == null
                ? TikTokPreferenceFragment.PAGE_HOME : open.returnMarker());
        restarter.restart(context);
    }
}
