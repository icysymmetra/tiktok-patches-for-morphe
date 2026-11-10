/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import java.util.LinkedHashSet;
import java.util.Set;

import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings({"deprecation", "unused"})
public class LogExportFilterPreference extends Preference {
    private static final String VALUE_ALL = "all";
    private static final String VALUE_FOLLOW = "follow";
    private static final String VALUE_DOWNLOADS = "downloads";
    private static final String VALUE_FEED = "feed";
    private static final String VALUE_FEATURE_GATE = "feature_gate";
    private static final String VALUE_SETTINGS = "settings";
    private static final String VALUE_ERRORS = "errors";
    private static final String VALUE_OTHER = "other";

    private static final String[] VALUES = {
            VALUE_ALL,
            VALUE_FOLLOW,
            VALUE_DOWNLOADS,
            VALUE_FEED,
            VALUE_FEATURE_GATE,
            VALUE_SETTINGS,
            VALUE_ERRORS,
            VALUE_OTHER
    };

    private static final String[] LABELS = {
            "All events",
            "Follow probe",
            "Downloads",
            "Feed and navigation",
            "Feature Gate Lab",
            "Settings",
            "Errors",
            "Other"
    };

    {
        // A key so the settings search can index this row. Nothing in the settings
        // framework treats it as a setting: a key with no Setting behind it is skipped.
        setKey("action_included_diagnostics");
        setOnPreferenceClickListener(pref -> {
            showPicker();
            return true;
        });
    }

    public LogExportFilterPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        updateSummary();
    }

    public LogExportFilterPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        updateSummary();
    }

    public LogExportFilterPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        updateSummary();
    }

    public LogExportFilterPreference(Context context) {
        super(context);
        updateSummary();
    }

    /**
     * The words this row shows, so a bundle that has a translation table can put its own in.
     *
     * <p>This library is shared with bundles that carry no tables at all, so it cannot reach
     * into one. The English here is what those get, and a subclass overrides what it can say
     * better, which is what TintedLogExportFilterPreference already does for the title and the
     * summary of the row itself.
     */
    protected String[] labels() {
        return LABELS;
    }

    protected CharSequence dialogTitle() {
        return "Include diagnostic events";
    }

    protected CharSequence positiveText() {
        return "Apply";
    }

    protected CharSequence negativeText() {
        return "Cancel";
    }

    protected CharSequence allEventsSummary() {
        return "Includes all Morphe diagnostic events.";
    }

    /**
     * The whole sentence, with the kinds already joined into it.
     *
     * <p>One sentence rather than a prefix and a suffix around a list: a table row holds a
     * sentence, and a language that puts the list somewhere else in it has nowhere to go if
     * the pieces are glued together here.
     *
     * <p>The list comes last, after a colon, so each kind can keep the capital its own
     * language gives it. Reading "Includes X events." meant lower-casing every kind, which is
     * right for English and wrong for German, and the labels became translatable while the
     * lower-casing stayed: Einstellungen came out as einstellungen.
     */
    protected CharSequence includesSummary(String kinds) {
        return "Includes these events: " + kinds;
    }

    private void showPicker() {
        boolean[] checked = checkedValues();

        AlertDialog shownDialog = new AlertDialog.Builder(getContext())
                .setTitle(dialogTitle())
                .setMultiChoiceItems(labels(), checked, (dialog, which, isChecked) -> {
                    checked[which] = isChecked;
                    AlertDialog alertDialog = (AlertDialog) dialog;

                    if (which == 0 && isChecked) {
                        for (int i = 1; i < checked.length; i++) {
                            checked[i] = false;
                            alertDialog.getListView().setItemChecked(i, false);
                        }
                    } else if (which > 0 && isChecked) {
                        checked[0] = false;
                        alertDialog.getListView().setItemChecked(0, false);
                    }
                    applyWhenSomethingIsChosen(alertDialog, checked);
                })
                .setPositiveButton(positiveText(), (dialog, which) -> {
                    BaseSettings.DEBUG_LOG_FILTERS.save(serialize(checked));
                    updateSummary();
                })
                .setNegativeButton(negativeText(), null)
                .show();
        onDialogShown(shownDialog);
        applyWhenSomethingIsChosen(shownDialog, checked);
    }

    /**
     * Apply only takes a choice. With every box cleared it used to save "all", and the row then
     * said it included every diagnostic event, the opposite of what was just chosen.
     */
    private static void applyWhenSomethingIsChosen(AlertDialog dialog, boolean[] checked) {
        android.widget.Button apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (apply == null) return;
        boolean any = false;
        for (boolean value : checked) any |= value;
        apply.setEnabled(any);
    }

    protected void onDialogShown(AlertDialog dialog) {
    }

    private boolean[] checkedValues() {
        Set<String> selected = parse(BaseSettings.DEBUG_LOG_FILTERS.savedValue());
        boolean[] checked = new boolean[VALUES.length];

        if (selected.isEmpty() || selected.contains(VALUE_ALL)) {
            checked[0] = true;
            return checked;
        }

        for (int i = 1; i < VALUES.length; i++) {
            checked[i] = selected.contains(VALUES[i]);
        }

        return checked;
    }

    private String serialize(boolean[] checked) {
        if (checked[0]) return VALUE_ALL;

        StringBuilder builder = new StringBuilder();
        for (int i = 1; i < checked.length; i++) {
            if (!checked[i]) continue;

            if (builder.length() > 0) builder.append(',');
            builder.append(VALUES[i]);
        }

        return builder.length() == 0 ? VALUE_ALL : builder.toString();
    }

    private void updateSummary() {
        Set<String> selected = parse(BaseSettings.DEBUG_LOG_FILTERS.savedValue());
        if (selected.isEmpty() || selected.contains(VALUE_ALL)) {
            setSummary(allEventsSummary());
            return;
        }

        StringBuilder builder = new StringBuilder();
        int labelCount = 0;
        for (int i = 1; i < VALUES.length; i++) {
            if (!selected.contains(VALUES[i])) continue;

            if (labelCount > 0) builder.append(", ");
            builder.append(labels()[i]);
            labelCount++;
        }

        setSummary(includesSummary(builder.toString()));
    }

    public static Set<String> parse(String value) {
        Set<String> selected = new LinkedHashSet<>();
        if (value == null || value.trim().isEmpty()) return selected;

        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) selected.add(trimmed);
        }

        return selected;
    }
}
