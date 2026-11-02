/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/DownloadPathPreference.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/DownloadPathPreference.java
 */

package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.download.DownloadDestination;

@SuppressWarnings("deprecation")
public class DownloadPathPreference extends DialogPreference {
    private String mValue;

    private boolean mValueSet;
    private String downloadPathValue;
    /** The box the dialog is showing, so a refused path can be said under it. */
    private EditText pathField;
    private final DownloadDestination.Kind kind;

    public DownloadPathPreference(
            Context context,
            String title,
            StringSetting setting,
            DownloadDestination.Kind kind
    ) {
        super(context);
        this.kind = kind;
        setTitle(title);
        setSummary(setting.savedValue());
        setKey(setting.key);
        setValue(setting.savedValue());
    }

    public String getValue() {
        return this.mValue;
    }

    public void setValue(String value) {
        String normalizedValue = normalizePath(value);
        setSummary(normalizedValue);
        final boolean changed = !TextUtils.equals(mValue, normalizedValue);
        if (changed || !mValueSet) {
            mValue = normalizedValue;
            mValueSet = true;
            persistString(normalizedValue);
            if (changed) {
                notifyDependencyChange(shouldDisableDependents());
                notifyChanged();
            }
        }
    }

    public void applyPickedPath(String path) {
        if (path == null) {
            String error = app.morphe.extension.tiktok.settings.L10n.t(
                    "Choose a folder on internal storage. SD cards aren't supported.");
            if (pathField != null && getDialog() != null && getDialog().isShowing()) {
                SettingsUi.reportFieldError(pathField, error);
            } else {
                app.morphe.extension.shared.Utils.showToastLong(error);
            }
            return;
        }
        try {
            String validated = DownloadDestination.validate(path, kind);
            if (pathField != null && getDialog() != null && getDialog().isShowing()) {
                pathField.setText(validated);
                SettingsUi.clearFieldError(pathField);
            } else {
                setValue(validated);
                app.morphe.extension.shared.Utils.showToastShort(
                app.morphe.extension.tiktok.settings.L10n.t("Destination updated"));
            }
        } catch (IllegalArgumentException ex) {
            if (pathField != null && getDialog() != null && getDialog().isShowing()) {
                SettingsUi.reportFieldError(pathField, ex.getMessage());
            } else {
                app.morphe.extension.shared.Utils.showToastLong(ex.getMessage());
            }
        }
    }

    @Override
    protected View onCreateDialogView() {
        downloadPathValue = normalizePath(getValue());

        Context context = getContext();
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        dialogView.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        dialogView.setPadding(padding, padding, padding, SettingsUi.dp(context, 8));

        // The row's own title, not a literal. All three destinations opened a dialog headed
        // "Download path", so nothing on the screen said which of the three was being edited.
        TextView title = SettingsUi.text(
                context,
                getTitle() == null ? "" : getTitle().toString(),
                SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(),
                Typeface.BOLD
        );
        SettingsUi.markDialogHeading(title);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView helper = SettingsUi.text(
                context,
                L10n.f(context, "Choose a subfolder under %1$s.",
                        DownloadDestination.allowedRoots(kind)),
                SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(),
                Typeface.NORMAL
        );
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        helperParams.setMargins(0, SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 10));
        dialogView.addView(helper, helperParams);

        EditText downloadPath = new EditText(context);
        downloadPath.setInputType(InputType.TYPE_CLASS_TEXT);
        downloadPath.setSingleLine(true);
        // Left as it is on purpose. This is an example of what to type, and every part of it
        // is a folder name Android fixes: "DCIM/TikTok" is the same on a German phone.
        downloadPath.setHint("DCIM/TikTok");
        downloadPath.setText(downloadPathValue);
        SettingsUi.styleEditText(downloadPath);
        SettingsUi.labelEditor(title, downloadPath);
        pathField = downloadPath;
        downloadPath.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                downloadPathValue = editable.toString();
            }
        });
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pathParams.setMargins(0, SettingsUi.dp(context, 10), 0, 0);
        dialogView.addView(downloadPath, pathParams);
        return dialogView;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        Utils.setTitleAndSummaryColor(view);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which) -> this.onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
        builder.setNeutralButton(L10n.t(getContext(), "Browse"), null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) saveTypedPath();
    }

    /** Null when the path saved, otherwise what is wrong with it. */
    private String saveTypedPath() {
        try {
            setValue(DownloadDestination.validate(downloadPathValue, kind));
            return null;
        } catch (IllegalArgumentException refused) {
            return refused.getMessage();
        }
    }

    @Override
    protected void showDialog(Bundle state) {
        super.showDialog(state);
        SettingsUi.styleFramedDialog(getDialog());
        // The fourth row of the same shape as the three the item named: it validated after the
        // dialog had gone and then said what was wrong over whatever was behind it.
        SettingsUi.keepOpenOnInvalidInput(getDialog(), new SettingsUi.DialogCheck() {
            @Override public String problem() {
                try {
                    DownloadDestination.validate(downloadPathValue, kind);
                    return null;
                } catch (IllegalArgumentException refused) {
                    return refused.getMessage();
                }
            }

            @Override public void report(String problem) {
                SettingsUi.reportFieldError(pathField, problem);
            }

            @Override public boolean accept() {
                return saveTypedPath() == null;
            }
        });
        AlertDialog dialog = (AlertDialog) getDialog();
        TextView browseButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (browseButton != null) {
            browseButton.setOnClickListener(view -> {
                TikTokPreferenceFragment.openDownloadPathFolderPicker(this);
            });
        }
    }

    private String normalizePath(String path) {
        return DownloadDestination.resolve(path, kind);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }
}
