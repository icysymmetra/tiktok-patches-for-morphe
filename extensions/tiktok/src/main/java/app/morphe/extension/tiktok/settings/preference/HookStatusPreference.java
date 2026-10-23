/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.util.List;

/**
 * Says whether the hooks found the app they attach to.
 *
 * <p>The patcher only knows what it wrote into the APK. Whether a hook then found its anchor at
 * runtime is a separate question, and when it does not the switch above still reads on while
 * nothing happens. This row answers that question before someone files a report about a feature
 * that was never running.
 *
 * <p>It speaks for the surfaces that report: the comments, the inbox, the share sheet, the feed
 * overlay, the feed models and the families listed in the README's Hook status paragraph. A
 * patch that reports nothing is not covered by it, so "everything found what it needed" means
 * everything that is watched, not every patch in the bundle. No count here: the last one this
 * said was eleven patches out of date.
 */
@SuppressWarnings("deprecation")
public class HookStatusPreference extends Preference {
    public HookStatusPreference(Context context) {
        super(context);
        // A key so the settings search can index this row. A key with no Setting behind
        // it is skipped by the settings framework, so nothing tries to persist it.
        setKey("action_hook_status");
        // Still "Hook status". The item asked for a plainer name, and the search test says why
        // not: people reach this row after being told to look at the hook status by name, and
        // searching "hook" has to find it. The jargon worth removing was inside, not here.
        setTitle(L10n.t(context, "Hook status"));
        setOnPreferenceClickListener(preference -> {
            showReport();
            return true;
        });
    }

    /**
     * Read whenever the row is drawn, which is when the list binds it. A surface first reports
     * as the app reaches it, so leaving this screen and coming back is what refreshes the
     * answer; nothing here pushes an update while the screen is open.
     */
    private Boolean lastOk;

    @Override
    public CharSequence getSummary() {
        Context context = getContext();
        List<String> report = HookStatus.report();
        if (report.isEmpty()) {
            lastOk = null;
            return L10n.t(context,
                    "Nothing has been looked up yet. Use the app for a moment, then come back.");
        }

        List<String> broken = HookStatus.familiesMissingSomething();
        if (broken.isEmpty()) {
            lastOk = Boolean.TRUE;
            String text = L10n.quantity(context, report.size(),
                    "One area was checked and everything it needs is here.",
                    "%1$d areas were checked and everything they need is here.");
            return SettingsUi.tonedSummary("✓ ", SettingsUi.okColor(), text);
        }
        lastOk = Boolean.FALSE;
        String surfaces = String.join(", ", broken);
        String text = L10n.f(context, "Something is missing from %1$s. Tap for the whole report.",
                surfaces);
        return SettingsUi.tonedSummary(
                SettingsUi.ATTENTION_GLYPH + " ", SettingsUi.attentionColor(), text);
    }

    private void showReport() {
        if (SettingsUi.dialogOpen(getKey(), getContext())) return;
        Context context = getContext();
        List<String> report = HookStatus.report();
        StringBuilder message = new StringBuilder();
        if (report.isEmpty()) {
            message.append(L10n.t(context,
                    "Nothing has been checked yet, so there's nothing to report."));
        } else {
            message.append(L10n.t(context,
                    "Each area shows how many of the things Metra looks for were found in "
                            + "this TikTok and how many are missing."));
            for (String line : report) message.append("\n\n").append(line);
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "Hook status"))
                .setMessage(message.toString())
                .setPositiveButton(L10n.t(context, "Close"), null)
                .show();
        SettingsUi.dialogOpened(getKey(), context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Utils.setTitleAndSummaryColor(view);
        if (Build.VERSION.SDK_INT >= 30 && lastOk != null) {
            view.setStateDescription(lastOk
                    ? L10n.t(getContext(), "OK") : L10n.t(getContext(), "Attention"));
        }
    }
}
