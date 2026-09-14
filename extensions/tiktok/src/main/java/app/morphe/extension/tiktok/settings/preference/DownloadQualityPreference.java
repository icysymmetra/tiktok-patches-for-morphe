/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.download.DownloadQuality;

@SuppressWarnings("deprecation")
public final class DownloadQualityPreference extends Preference {
    private final StringSetting setting;

    public DownloadQualityPreference(Context context, StringSetting setting) {
        super(context);
        this.setting = setting;
        setPersistent(false);
        setTitle("Video quality");
        refreshSummary();
    }

    @Override
    protected void onClick() {
        DownloadQuality[] qualities = DownloadQuality.values();
        String[] labels = new String[qualities.length];
        DownloadQuality current = DownloadQuality.fromSetting(setting.get());
        int checkedItem = 0;

        for (int index = 0; index < qualities.length; index++) {
            DownloadQuality quality = qualities[index];
            labels[index] = quality.label + " · " + quality.description;
            if (quality == current) {
                checkedItem = index;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setTitle("Video quality")
                .setSingleChoiceItems(labels, checkedItem, (dialogInterface, which) -> {
                    if (which >= 0 && which < qualities.length) {
                        setting.save(qualities[which].key);
                        refreshSummary();
                    }
                    dialogInterface.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        SettingsUi.styleStandardAlertDialog(dialog);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    private void refreshSummary() {
        DownloadQuality quality = DownloadQuality.fromSetting(setting.get());
        setSummary(quality.label + " · " + quality.description);
    }
}
