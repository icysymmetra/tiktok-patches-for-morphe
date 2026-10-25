/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * The row that shows the notices this bundle is required to pass on.
 *
 * <p>Morphe's Section 7b asks that a derivative work carry its NOTICE text and provide it to the
 * person using the software. Carrying it in the repository satisfies the first half for anyone
 * reading the source and none of it for the person who installed a patched app, which is who the
 * clause is about. This row is that second half.
 *
 * <p>The text is shown rather than linked, because a link is not providing the notice to somebody
 * with no network. The full license is a button, since the GPL runs to some tens of kilobytes and
 * putting it in every patched APK to be scrolled past is not what the clause asks for.
 */
@SuppressWarnings("deprecation")
public final class LicensesPreference extends Preference {
    /** A stable key, so the settings search can find the row and open it. */
    public static final String KEY = "action_licenses";

    public LicensesPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(title(context));
        setSummary(summary(context));
        setOnPreferenceClickListener(preference -> {
            show(context);
            return true;
        });
    }

    public static String title(Context context) {
        return L10n.t(context, "Licenses");
    }

    public static String summary(Context context) {
        return L10n.t(context, "Notices from this project and the ones it's built on");
    }

    /** Opens the notice, and is public so the settings search can open it from a result. */
    public static void show(Context context) {
        if (SettingsUi.dialogOpen(KEY, context)) return;
        TextView body = new TextView(context);
        body.setText(LicenseNotice.TEXT);
        body.setTextIsSelectable(true);
        int padding = SettingsUi.dp(context, 20);
        body.setPadding(padding, padding, padding, padding);
        // Set here rather than left to the dialog styling pass, which walks the decor view and
        // would reach this as an anonymous TextView. A notice nobody can read against the
        // background is not provided to anybody.
        body.setTextColor(SettingsUi.textPrimary());
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_LABEL);

        ScrollView scroller = new ScrollView(context);
        scroller.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsUi.dialogListHeight(context, 420)));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title(context))
                .setView(scroller)
                .setPositiveButton(L10n.t(context, "Close"), (target, which) -> target.dismiss())
                .setNeutralButton(L10n.t(context, "Full license"), (target, which) ->
                        Utils.openLink(LicenseNotice.LICENSE_URL))
                .create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);

        TextView heading = dialog.findViewById(android.R.id.title);
        if (heading != null) {
            heading.setGravity(Gravity.START);
            SettingsUi.markDialogHeading(heading);
        }
    }
}
