/*
 * Adapted from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/InputTextPreference.java
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.tiktok.Utils;

@SuppressWarnings("deprecation")
public class NumberInputPreference extends EditTextPreference {
    private final String baseSummary;
    private final IntegerSetting setting;
    private final int minValue;
    private final int maxValue;
    private final String singularUnit;
    private final String pluralUnit;
    /** Non-null means "show this label instead of the value and unit when the value is zero." */
    private String zeroLabel;

    public NumberInputPreference(Context context, String title, String summary,
                                 IntegerSetting setting) {
        this(context, title, summary, setting, "%1$s video", "%1$s videos");
    }

    /**
     * The range comes from the setting, not from here. Passing it separately meant the dialog
     * and the stored value could disagree, and only the dialog was ever enforcing it: a
     * restored backup went straight into the setting without being asked anything.
     */
    public NumberInputPreference(Context context, String title, String summary,
                                 IntegerSetting setting, String unit) {
        this(context, title, summary, setting, unit, unit);
    }

    /**
     * The two unit forms are whole phrases with the number in them ("%1$s day", "%1$s days"),
     * so a translator sees the sentence rather than a bare noun, and the language's plural rule
     * picks the form. A bare unit ("day") was a key of its own, which fixed the word order and
     * the agreement to English.
     */
    public NumberInputPreference(Context context, String title, String summary,
                                 IntegerSetting setting, String singularUnit, String pluralUnit) {
        super(context);
        if (!setting.hasRange()) {
            throw new IllegalArgumentException(setting.key + " has no range to offer");
        }
        this.singularUnit = singularUnit;
        this.pluralUnit = pluralUnit;
        this.baseSummary = summary;
        this.setting = setting;
        this.minValue = setting.minimum();
        this.maxValue = setting.maximum();
        setTitle(title);
        setKey(setting.key);
        setValue(String.valueOf(clamp(setting.savedValue())));
        getEditText().setInputType(inputType());
        // The range as the placeholder: "Enter a number" said nothing the empty field did not.
        getEditText().setHint(L10n.f(context, "%1$s to %2$s", displayValue(minValue), displayValue(maxValue)));
    }

    /**
     * Says that zero means off on this row, so the summary reads "Current: Off".
     *
     * <p>Seven rows document zero that way in their own wording and then showed
     * "Current: 0 videos" underneath, which reads as a limit of none rather than as no limit.
     * Only the rows whose text says it are marked; zero means TikTok's own caption size on one
     * row and no delay on another, and neither of those is off.
     */
    public NumberInputPreference zeroMeansOff() {
        return zeroMeans("Off");
    }

    public NumberInputPreference zeroMeans(String label) {
        zeroLabel = label;
        setValue(getText());
        return this;
    }

    public String getValue() {
        return String.valueOf(parseAndClamp(getText()));
    }

    public void setValue(String value) {
        int clampedValue = parseAndClamp(value);
        setText(String.valueOf(clampedValue));
        showSummary(clampedValue);
    }

    /** Refreshes an already committed value without writing it back through the framework. */
    public void setValueWithoutPersisting(String value) {
        boolean persistent = isPersistent();
        setPersistent(false);
        try {
            setValue(value);
        } finally {
            setPersistent(persistent);
        }
    }

    /** Completes a save consumed by the row's listener, retaining its normal range feedback. */
    public void acceptSavedValue(String typed) {
        int saved = clamp(setting.savedValue());
        setValueWithoutPersisting(String.valueOf(saved));
        sayIfPulledIntoRange(typed, saved);
        if (getDialog() != null) getDialog().dismiss();
    }

    /**
     * Draws the summary again from the stored value without writing anything, for a row whose
     * extra line changed while its value did not.
     */
    public void refreshSummary() {
        showSummary(parseAndClamp(getText()));
    }

    private void showSummary(int clampedValue) {
        // The range is read off the setting, so every one of these rows states it without each
        // of them growing a sentence of its own. Twelve of the fourteen said nothing about it
        // and pulled an out of range number to the nearest end without a word.
        String extra = extraSummaryLine();
        setSummary(L10n.t(getContext(), baseSummary)
                // Formatted like the value under it and carrying the unit, so a limit reads
                // "0 to 86,400 seconds" where it used to be the bare "0 to 86400".
                + (showsRange() ? "\n" + L10n.f(getContext(), "%1$s to %2$s",
                        displayValue(minValue), withUnit(maxValue, displayValue(maxValue))) : "")
                + "\n" + L10n.f(getContext(), "Current: %1$s", shown(clampedValue))
                + (extra == null ? "" : "\n" + extra));
    }

    /** A value as the row shows it: the zero label, or the number inside its unit phrase ("5 seconds"). */
    protected String shown(int value) {
        return zeroLabel != null && value == 0
                ? L10n.t(getContext(), zeroLabel)
                : withUnit(value, displayValue(value));
    }

    /**
     * A fourth line under the current value, or null for the rows that have nothing to add.
     *
     * <p>The two daily budgets use it for how much of today has gone. Called from
     * {@link #setValue}, which the constructor calls, so an override must not read state of its
     * own: the two that exist read the setting and the day's counts, both of them statics.
     */
    protected String extraSummaryLine() {
        return null;
    }

    /** Whether the summary states the range. A row whose picker can't leave it has no need to. */
    protected boolean showsRange() {
        return true;
    }

    /**
     * How the number itself reads in the summary. Most rows are a count and read as one; an hour
     * of the day is not "13 o'clock".
     */
    protected String displayValue(int value) {
        // Grouped the reader's way: 86,400 rather than 86400. Display only; the box the value
        // is typed into takes plain digits.
        android.content.res.Configuration configuration = getContext().getResources().getConfiguration();
        java.util.Locale locale = android.os.Build.VERSION.SDK_INT >= 24
                ? configuration.getLocales().get(0) : configuration.locale;
        return java.text.NumberFormat.getIntegerInstance(locale).format(value);
    }

    /**
     * The number inside its unit phrase, in the reader's language, the form chosen by that
     * language's plural rule for the value.
     */
    private String withUnit(int value, String shown) {
        // A row with no unit (an hour of the day) shows the value on its own.
        if (singularUnit.isEmpty() && pluralUnit.isEmpty()) return shown;
        return L10n.quantity(getContext(), value, singularUnit, pluralUnit, shown);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Utils.setTitleAndSummaryColor(view);
    }

    @Override
    protected View onCreateDialogView() {
        Context context = getContext();
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        dialogView.setPadding(padding, padding, padding, SettingsUi.dp(context, 8));

        TextView title = SettingsUi.text(
                context,
                getTitle() == null ? "" : getTitle().toString(),
                SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(),
                android.graphics.Typeface.BOLD
        );
        SettingsUi.markDialogHeading(title);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (getSummary() != null && getSummary().length() > 0) {
            TextView summary = SettingsUi.text(
                    context,
                    getSummary().toString(),
                    SettingsUi.TEXT_BODY_SMALL,
                    SettingsUi.textSecondary(),
                    android.graphics.Typeface.NORMAL
            );
            android.widget.ScrollView scroller = new android.widget.ScrollView(context);
            scroller.setFillViewport(false);
            scroller.addView(summary, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            );
            summaryParams.setMargins(0, SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 10));
            dialogView.addView(scroller, summaryParams);
        }

        EditText editText = getEditText();
        ViewGroup parent = (ViewGroup) editText.getParent();
        if (parent != null) {
            parent.removeView(editText);
        }
        editText.setInputType(inputType());
        editText.setSingleLine(true);
        editText.setSelectAllOnFocus(true);
        SettingsUi.styleEditText(editText);
        SettingsUi.labelEditor(title, editText);
        dialogView.addView(editText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        return dialogView;
    }

    @Override
    protected void showDialog(Bundle state) {
        super.showDialog(state);
        SettingsUi.styleFramedDialog(getDialog());
        SettingsUi.submitOnDone(getEditText(), getDialog());
        SettingsUi.keepOpenOnInvalidInput(getDialog(), new SettingsUi.DialogCheck() {
            @Override public String problem() {
                return typedProblem(getEditText().getText().toString());
            }

            @Override public void report(String problem) {
                SettingsUi.reportFieldError(getEditText(), problem);
            }

            @Override public boolean accept() {
                return saveTypedValue();
            }
        });
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> this.onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) saveTypedValue();
    }

    /**
     * Saves what is in the box, answering false when something refused it.
     *
     * <p>One method rather than a copy in each place. Save no longer reaches the method above:
     * keeping the dialog open replaces the button's own click listener, which is what used to
     * run it, so the platform now only calls it for the dismiss it does not act on. A second
     * copy of the save would be the one the reader really uses and the one nothing exercises.
     */
    private boolean saveTypedValue() {
        String typed = getEditText().getText().toString();
        String problem = typedProblem(typed);
        if (problem != null) {
            SettingsUi.reportFieldError(getEditText(), problem);
            return false;
        }
        SettingsUi.clearFieldError(getEditText());
        int value = parseAndClamp(typed);
        String text = String.valueOf(value);
        if (!callChangeListener(text)) return false;
        // Only once the row has taken it. Saying "kept to 600" and then refusing the change
        // describes something that did not happen, and with the dialog staying open it said it
        // again on every press.
        sayIfPulledIntoRange(typed, value);
        setValue(text);
        return true;
    }

    private String typedProblem(String typed) {
        // The old message described a flow that no longer exists: Save used to close the
        // dialog and throw away what was typed. It stays open now and shows this under the
        // field, so "the previous value was kept" is about something that didn't happen.
        return parseTyped(typed) == null ? unreadableMessage() : null;
    }

    /** The number in what was typed, or null when there is none. A row may read other forms. */
    protected Integer parseTyped(String typed) {
        try {
            return Integer.parseInt(typed.trim());
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** What the field says under it when nothing in it can be read. */
    protected String unreadableMessage() {
        return L10n.t(getContext(), "Enter a number.");
    }

    /** The keyboard the field asks for. Called from the constructor, so it reads no fields. */
    protected int inputType() {
        return InputType.TYPE_CLASS_NUMBER;
    }

    /**
     * Says so when a number that could be read was outside the range and was moved.
     *
     * <p>Typing 5000 into a row that stops at 600 used to come back as "Current: 600" with no
     * explanation. Nothing is said for an empty or unreadable box: that is already handled by
     * keeping the stored value, and saying "kept at 600" for an empty field would be an answer
     * to a question nobody asked.
     */
    private void sayIfPulledIntoRange(String typed, int stored) {
        Integer asked = parseTyped(typed);
        if (asked == null || asked == stored) return;
        app.morphe.extension.shared.Utils.showToastShort(L10n.f(getContext(),
                "Kept to %1$s, the nearest value this row allows", displayValue(stored)));
    }

    private int parseAndClamp(String value) {
        Integer typed = value == null ? null : parseTyped(value);
        // An empty or unreadable box is not a request for the smallest value. For several of
        // these settings the smallest value means off, so falling to it would quietly turn a
        // feature off because somebody cleared the field.
        return clamp(typed != null ? typed : setting.savedValue());
    }

    protected int clamp(int value) {
        return Math.max(minValue, Math.min(maxValue, value));
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
