/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Date;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DebugCapture;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Starts a {@link DebugCapture} on a tap and stops it on the next one. While it runs the row
 * says when it ends, so the reader can see it's on without the Log diagnostics switch moving.
 */
@SuppressWarnings("deprecation")
public final class TimedDiagnosticsPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String TITLE = "Log diagnostics for 15 minutes";

    public TimedDiagnosticsPreference(Context context) {
        super(context);
        setKey("action_timed_diagnostics");
        setTitle(TITLE);
        applyState();
        setOnPreferenceClickListener(preference -> {
            if (DebugCapture.isRunning()) {
                DebugCapture.stop();
                Utils.showToastShort(L10n.t(getContext(), "Timed logging stopped"));
            } else {
                long end = DebugCapture.start();
                Utils.showToastShort(L10n.f(getContext(), "Logging diagnostics until %1$s", time(end)));
            }
            applyState();
            return true;
        });
    }

    private String time(long wallClock) {
        return DateFormat.getTimeFormat(getContext()).format(new Date(wallClock));
    }

    private void applyState() {
        long end = DebugCapture.endsAtWallClock();
        if (end != 0) {
            setSummary(L10n.f(getContext(), "Logging until %1$s. Tap to stop now.", time(end)));
        } else {
            setSummary(L10n.t(getContext(),
                    "Logs for 15 minutes, then stops on its own. Restarting TikTok doesn't move the end, and the switch above stays as you set it."));
        }
    }

    @Override
    protected void onBindView(View view) {
        applyState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }
}
