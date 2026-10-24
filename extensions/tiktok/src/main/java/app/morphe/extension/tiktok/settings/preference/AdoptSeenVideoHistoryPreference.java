/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import java.text.NumberFormat;

import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Adds the seen videos recorded before each account kept its own record to the signed-in
 * account. Nothing on the phone says whose they were, so they hide nothing until someone asks,
 * and the row only appears while there are any.
 */
@SuppressWarnings("deprecation")
public final class AdoptSeenVideoHistoryPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String TITLE = "Add older seen videos to this account";

    public AdoptSeenVideoHistoryPreference(Context context, int unowned) {
        super(context);
        setKey("action_adopt_seen_video_history");
        setTitle(L10n.t(context, TITLE));
        setSummary(L10n.quantity(context, unowned,
                "One video was recorded before each account kept its own list. It hides nothing until you add it here.",
                "%1$s videos were recorded before each account kept its own list. They hide nothing until you add them here.",
                NumberFormat.getInstance().format(unowned)));
        setOnPreferenceClickListener(preference -> {
            setEnabled(false);
            SeenVideoHistory.adoptUnowned(added -> {
                if (added < 0) {
                    setEnabled(true);
                    SettingsActionBanner.showNotice(context,
                            L10n.t(context, "Couldn't add the older seen videos. Try again."));
                    return;
                }
                setSummary(L10n.t(context, "Added to this account."));
                SettingsActionBanner.showNotice(context, L10n.quantity(context, added,
                        "Added one older seen video to this account",
                        "Added %1$s older seen videos to this account", NumberFormat.getInstance().format(added)));
            });
            return true;
        });
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
