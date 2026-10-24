/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.DialogPreference;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.profile.LocalProfileCounts;
import app.morphe.extension.tiktok.profile.LocalProfileCounts.Metric;
import app.morphe.extension.tiktok.settings.L10n;

import java.text.NumberFormat;
import java.util.EnumMap;
import java.util.Map;

/** The editor captures its account when opened and saves one account's three choices atomically. */
@SuppressWarnings("deprecation")
public final class LocalProfileCountsPreference extends DialogPreference {
    private final Map<Metric, EditText> editors = new EnumMap<>(Metric.class);
    private String owner;
    private Typed restoring;

    public LocalProfileCountsPreference(Context context) {
        super(context);
        setPersistent(false);
        setKey("local_profile_counts_editor");
        setTitle(L10n.t(context, "Own profile counts"));
        setSummary(L10n.t(context, "Choose counts for this signed-in account. Leave a field empty to use TikTok's count. Zero shows zero. Choices stay on this phone.")
                + " " + L10n.t(context, TogglePreference.RESTART_SENTENCE));
        setPositiveButtonText(L10n.t(context, "Save"));
        setNegativeButtonText(L10n.t(context, "Cancel"));
    }

    @Override protected void onClick() {
        if (SignedInUser.id() == null) {
            Utils.showToastShort(L10n.t(getContext(), "Sign in to edit your own profile counts"));
            return;
        }
        super.onClick();
    }

    @Override protected View onCreateDialogView() {
        owner = SignedInUser.id();
        editors.clear();
        Context context = getContext();
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        form.setPadding(padding, padding, padding, padding);
        TextView heading = SettingsUi.text(context, getTitle().toString(), SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(), Typeface.BOLD);
        SettingsUi.markDialogHeading(heading);
        form.addView(heading);
        TextView summary = SettingsUi.text(context, getSummary().toString(), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        summary.setPadding(0, SettingsUi.dp(context, 12), 0, SettingsUi.dp(context, 8));
        form.addView(summary);
        for (Metric metric : Metric.values()) {
            TextView label = SettingsUi.text(context, L10n.t(context, metric.title), SettingsUi.TEXT_BODY,
                    SettingsUi.textPrimary(), Typeface.NORMAL);
            label.setPadding(0, SettingsUi.dp(context, 12), 0, 0);
            form.addView(label);
            EditText editor = new EditText(context);
            editor.setTag("local_profile_count_" + metric.key);
            editor.setInputType(InputType.TYPE_CLASS_NUMBER);
            // Keep one extra digit visible so an oversized paste is rejected instead of becoming a valid truncated count.
            editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
            SettingsUi.styleEditText(editor);
            SettingsUi.labelEditor(label, editor);
            editor.setText(LocalProfileCounts.value(owner, metric));
            form.addView(editor, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            editors.put(metric, editor);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        return scroll;
    }

    @Override protected void showDialog(Bundle state) {
        super.showDialog(state);
        Typed typed = restoring;
        // Generated field ids change with each recreate, so the dialog's own restore can't find
        // them, and after process death a reused id could hand these fields another account's
        // typing. A restore ends with this account's numbers: typed here, or as saved.
        if (state != null && owner != null) {
            boolean same = typed != null && owner.equals(typed.owner);
            for (Metric metric : Metric.values()) {
                editors.get(metric).setText(same ? typed.values[metric.ordinal()] : LocalProfileCounts.value(owner, metric));
            }
        }
        AlertDialog dialog = (AlertDialog) getDialog();
        SettingsUi.styleFramedDialog(dialog);
        SettingsUi.submitOnDone(editors.get(Metric.LIKES), dialog);
        String account = owner;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            Map<Metric, String> values = new EnumMap<>(Metric.class);
            for (Metric metric : Metric.values()) {
                EditText editor = editors.get(metric);
                String text = editor.getText().toString();
                if (!LocalProfileCounts.validValue(metric, text)) {
                    SettingsUi.reportFieldError(editor, L10n.f(getContext(),
                            "Use a whole number from 0 to %1$s, or leave the field empty.",
                            NumberFormat.getIntegerInstance().format(metric.maximum)));
                    return;
                }
                SettingsUi.clearFieldError(editor);
                values.put(metric, text);
            }
            setBusy(dialog, true);
            boolean queued = Utils.runOnBackgroundThread(() -> {
                LocalProfileCounts.SaveResult result = LocalProfileCounts.save(account, values);
                Utils.runOnMainThread(() -> {
                    if (result == LocalProfileCounts.SaveResult.SAVED) {
                        notifyChanged();
                        Utils.showToastShort(L10n.t(getContext(), "Saved. Restart TikTok to apply this."));
                        dialog.dismiss();
                    } else {
                        Utils.showToastShort(L10n.t(getContext(), result == LocalProfileCounts.SaveResult.ACCOUNT_CHANGED
                                ? "Your account changed. Open the count editor again."
                                : "Couldn't save these choices. Try again."));
                        if (getDialog() == dialog && dialog.isShowing()) setBusy(dialog, false);
                    }
                });
            });
            if (!queued) {
                setBusy(dialog, false);
                Utils.showToastShort(L10n.t(getContext(), "Couldn't save these choices. Try again."));
            }
        });
    }

    private void setBusy(AlertDialog dialog, boolean busy) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        for (EditText editor : editors.values()) editor.setEnabled(!busy);
    }

    @Override protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        Dialog dialog = getDialog();
        if (dialog == null || !dialog.isShowing() || editors.size() != Metric.values().length) return superState;
        Typed typed = new Typed(superState);
        typed.owner = owner;
        typed.values = new String[Metric.values().length];
        for (Metric metric : Metric.values()) typed.values[metric.ordinal()] = editors.get(metric).getText().toString();
        return typed;
    }

    @Override protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof Typed)) {
            super.onRestoreInstanceState(state);
            return;
        }
        // DialogPreference reopens the dialog from inside this call; showDialog puts the typed counts back.
        restoring = (Typed) state;
        try { super.onRestoreInstanceState(restoring.getSuperState()); }
        finally { restoring = null; }
    }

    private static final class Typed extends BaseSavedState {
        String owner;
        String[] values;

        Typed(Parcelable superState) {
            super(superState);
        }

        Typed(Parcel source) {
            super(source);
            owner = source.readString();
            values = source.createStringArray();
        }

        @Override public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeString(owner);
            dest.writeStringArray(values);
        }

        public static final Parcelable.Creator<Typed> CREATOR = new Parcelable.Creator<Typed>() {
            @Override public Typed createFromParcel(Parcel source) {
                return new Typed(source);
            }

            @Override public Typed[] newArray(int size) {
                return new Typed[size];
            }
        };
    }
}
