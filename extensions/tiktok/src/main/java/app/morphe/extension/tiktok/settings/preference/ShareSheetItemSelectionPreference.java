package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Checkbox-dialog multi-select preference generalized over an item catalog via plain
 * functions, so one class serves both channels and actions. Mirrors {@link TabSelectionPreference}.
 */
@SuppressWarnings("deprecation")
public class ShareSheetItemSelectionPreference extends Preference {
    private final StringSetting setting;
    private final String dialogTitle;
    private final String helperText;
    private final Function<String, Set<String>> parseEnabledKeys;
    private final Function<Set<String>, String> serializeEnabledKeys;
    private final Function<Set<String>, List<Row>> optionsForKeys;
    private final Set<String> knownKeys;
    private String value;
    private boolean valueSet;

    public ShareSheetItemSelectionPreference(
            Context context,
            String title,
            String dialogTitle,
            String helperText,
            StringSetting setting,
            Function<String, Set<String>> parseEnabledKeys,
            Function<Set<String>, String> serializeEnabledKeys,
            Function<Set<String>, List<Row>> optionsForKeys,
            Set<String> knownKeys
    ) {
        super(context);
        this.dialogTitle = dialogTitle;
        this.helperText = helperText;
        this.setting = setting;
        this.parseEnabledKeys = parseEnabledKeys;
        this.serializeEnabledKeys = serializeEnabledKeys;
        this.optionsForKeys = optionsForKeys;
        this.knownKeys = knownKeys;
        setTitle(title);
        setKey(setting.key);
        setValue(setting.get());
    }

    public String getValue() {
        return value;
    }

    public boolean setValue(String value) {
        String sanitizedValue = serializeEnabledKeys.apply(parseEnabledKeys.apply(value));
        boolean changed = !TextUtils.equals(this.value, sanitizedValue);
        if (changed || !valueSet) {
            this.value = sanitizedValue;
            valueSet = true;
            setting.save(sanitizedValue);
            refreshSummary();
            if (changed) {
                notifyDependencyChange(shouldDisableDependents());
                notifyChanged();
            }
        }
        return changed;
    }

    @Override
    protected void onClick() {
        showSelectionDialog();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    private void refreshSummary() {
        Set<String> selected = parseEnabledKeys.apply(value);
        List<Row> allRows = optionsForKeys.apply(knownKeys);
        if (allRows.isEmpty()) {
            setSummary("All");
            return;
        }

        int selectedCount = 0;
        StringBuilder builder = new StringBuilder();
        for (Row row : allRows) {
            if (!selected.contains(row.key)) {
                continue;
            }
            selectedCount++;
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(row.label);
        }

        if (selectedCount == allRows.size()) {
            setSummary("All");
            return;
        }
        if (builder.length() == 0) {
            setSummary("None");
            return;
        }
        setSummary(builder.toString());
    }

    private void showSelectionDialog() {
        Context context = getContext();
        Set<String> selected = new LinkedHashSet<>(parseEnabledKeys.apply(value));
        List<Row> rows = optionsForKeys.apply(knownKeys);

        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        dialogView.setBackground(createDialogBackground());
        int padding = dpToPx(22);
        dialogView.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(context);
        title.setText(dialogTitle);
        title.setTextColor(getTitleTextColor());
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView helper = new TextView(context);
        helper.setText(helperText);
        helper.setTextColor(getSummaryTextColor());
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        helperParams.setMargins(0, dpToPx(16), 0, dpToPx(12));
        dialogView.addView(helper, helperParams);

        LinearLayout optionsContainer = new LinearLayout(context);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        optionsContainer.setBackground(createListBackground());
        int optionInset = Math.max(1, dpToPx(1));
        optionsContainer.setPadding(optionInset, optionInset, optionInset, optionInset);

        for (Row row : rows) {
            optionsContainer.addView(createOptionRow(context, selected, row));
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.addView(optionsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsUi.dialogListHeight(context, 380)
        );
        scrollParams.setMargins(0, 0, 0, dpToPx(16));
        dialogView.addView(scrollView, scrollParams);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView selectAllButton = createActionButton(context, "Select all", false);
        TextView cancelButton = createActionButton(context, "Cancel", false);
        TextView saveButton = createActionButton(context, "Save", true);

        actions.addView(selectAllButton, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));
        actions.addView(cancelButton);
        actions.addView(saveButton);
        dialogView.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();

        selectAllButton.setOnClickListener(view -> {
            selected.clear();
            for (Row row : rows) {
                selected.add(row.key);
            }
            boolean changed = setValue(serializeEnabledKeys.apply(selected));
            dialog.dismiss();
            if (changed && setting.rebootApp) {
                AbstractPreferenceFragment.showRestartDialog(context);
            }
        });
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        saveButton.setOnClickListener(view -> {
            boolean changed = setValue(serializeEnabledKeys.apply(selected));
            dialog.dismiss();
            if (changed && setting.rebootApp) {
                AbstractPreferenceFragment.showRestartDialog(context);
            }
        });

        dialog.show();
        SettingsUi.styleDialog(dialog);
    }

    private View createOptionRow(Context context, Set<String> selected, Row row) {
        LinearLayout rowView = new LinearLayout(context);
        rowView.setOrientation(LinearLayout.HORIZONTAL);
        rowView.setGravity(Gravity.CENTER_VERTICAL);
        rowView.setBackgroundColor(getDialogBackgroundColor());
        rowView.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));

        CheckBox checkBox = new CheckBox(context);
        checkBox.setChecked(selected.contains(row.key));
        checkBox.setClickable(false);
        SettingsUi.styleCheckBox(checkBox);
        rowView.addView(checkBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView label = new TextView(context);
        label.setText(row.label);
        label.setTextColor(getTitleTextColor());
        label.setTextSize(16);
        rowView.addView(label, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        ));

        rowView.setOnClickListener(view -> {
            if (selected.contains(row.key)) {
                selected.remove(row.key);
                checkBox.setChecked(false);
            } else {
                selected.add(row.key);
                checkBox.setChecked(true);
            }
        });

        View divider = new View(context);
        divider.setBackgroundColor(getDialogDividerColor());

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(rowView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        wrapper.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dpToPx(1))
        ));
        return wrapper;
    }

    private TextView createActionButton(Context context, String text, boolean primary) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(6));
        SettingsUi.styleTextAction(button, primary);
        return button;
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }

    private GradientDrawable createDialogBackground() {
        return SettingsUi.borderedSurface(getContext(), 6, true);
    }

    private GradientDrawable createListBackground() {
        return SettingsUi.borderedSurface(getContext(), 4, false);
    }

    private static int getDialogBackgroundColor() {
        return SettingsUi.surface();
    }

    private static int getDialogDividerColor() {
        return SettingsUi.divider();
    }

    private static int getTitleTextColor() {
        return SettingsUi.textPrimary();
    }

    private static int getSummaryTextColor() {
        return SettingsUi.textSecondary();
    }

    public static final class Row {
        public final String key;
        public final String label;

        public Row(String key, String label) {
            this.key = key;
            this.label = label;
        }
    }
}
