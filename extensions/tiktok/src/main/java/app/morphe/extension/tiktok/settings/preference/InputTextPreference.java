/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/InputTextPreference.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/InputTextPreference.java
 */

package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("deprecation")
public class InputTextPreference extends EditTextPreference {

    /**
     * Looks at what was typed and says what is wrong with it, or null when nothing is.
     *
     * <p>The message is shown as it is returned, so it is written for the reader and comes
     * from the translation table.
     */
    public interface Check {
        @Nullable
        String problem(String value);
    }

    @Nullable
    private Check check;

    /**
     * Says something about the value that the reader should see without opening the editor,
     * or null when there is nothing to say.
     *
     * <p>Read again each time the row is drawn, not only when its own value changes, because
     * what it says can depend on other rows: an exception the block list also names is a
     * conflict the moment either list changes.
     */
    public interface Note {
        @Nullable
        String line(String value);
    }

    @Nullable
    private Note note;

    /**
     * Shows what the typed value turns into, under the field, while it's typed. Worked out from
     * the text alone, so looking never saves or starts anything. Null hides the line.
     */
    public interface Preview {
        @Nullable
        String text(String typed);
    }

    @Nullable
    private Preview preview;

    /**
     * Like {@link Preview}, with a second box under the field for sample text. Answers from the
     * typed value and the sample together, so the field can be tried on a caption before it is
     * saved. The sample is never saved and never leaves the dialog.
     */
    public interface SamplePreview {
        @Nullable
        String text(String typed, String sample);
    }

    @Nullable
    private SamplePreview samplePreview;
    private String sampleHint = "";
    private int sampleMaxChars = 4_000;

    /** The editor is the same view every time the dialog opens, so its one watcher is kept. */
    @Nullable
    private TextWatcher previewWatcher;

    /** The description, kept so the summary can be rebuilt when the value changes. */
    private final String baseSummary;

    /** The summary as last built, so a line the page added after it can be told apart. */
    @Nullable
    private String built;

    /** How many characters of a value to show before truncating. */
    private static final int VALUE_DISPLAY_LIMIT = 60;

    /** What a hidden value shows as on its row: it says one is saved and nothing about it. */
    static final String HIDDEN_VALUE = "••••••";

    /** Whether the value is a password, shown as dots on the row and while it's typed. */
    private boolean secret;

    public InputTextPreference(Context context, String title, String summary, StringSetting setting) {
        super(context);
        setTitle(title);
        // Already translated by withRestartNote, so not looked up a second time.
        baseSummary = TogglePreference.withRestartNote(context, summary, setting);
        setKey(setting.key);
        setText(setting.savedValue());
        rebuildSummary();
    }

    /**
     * Puts the description and a trailing value line together.
     *
     * <p>The twenty text rows on the settings pages used to show only their description: the
     * only way to learn what was in them was to open the editor. A "Current: Empty" or
     * "Current: us" line underneath is the same thing the numeric rows already carry. A long
     * value (a list of blocked words, say) is cut to roughly sixty characters so it stays on
     * one line and the row stays a row rather than a paragraph.
     */
    private void rebuildSummary() {
        String value = getText();
        String shown;
        if (value == null || value.isEmpty() || (!secret && value.trim().isEmpty())) {
            shown = L10n.t(getContext(), "Empty");
        } else if (secret) {
            shown = HIDDEN_VALUE;
        } else if (value.length() > VALUE_DISPLAY_LIMIT) {
            shown = value.substring(0, VALUE_DISPLAY_LIMIT).trim() + "…";
        } else {
            shown = value;
        }
        String summary = baseSummary + "\n" + L10n.f(getContext(), "Current: %1$s", shown);
        String extra = note == null ? null : note.line(value == null ? "" : value);
        if (extra != null && !extra.isEmpty()) summary += "\n" + extra;
        // What the page added after the last build, its "Turn on X first." line, is kept. A row
        // with a note rebuilds at every draw, which dropped that line from Who to message.
        CharSequence current = getSummary();
        String tail = "";
        if (built != null && current != null && current.toString().startsWith(built)) {
            tail = current.toString().substring(built.length());
        }
        built = summary;
        super.setSummary(summary + tail);
    }

    /**
     * Adds a line under the value that depends on more than the value. The platform only
     * redraws a row whose summary text actually changed, so rebuilding on every draw is cheap
     * and never loops.
     */
    public InputTextPreference withNote(Note note) {
        this.note = note;
        rebuildSummary();
        return this;
    }

    @Override
    public void setText(String text) {
        super.setText(text);
        // baseSummary is null during the super constructor's first setText call, before the
        // field is assigned. The constructor calls rebuildSummary afterwards.
        if (baseSummary != null) rebuildSummary();
    }

    /**
     * Refuses a value the field cannot use, while the dialog is still open and the reader is
     * still looking at it. Without this the value saves and goes wrong somewhere else: at the
     * next feed page, at the next save, or silently and never.
     */
    public InputTextPreference withCheck(Check check) {
        this.check = check;
        return this;
    }

    public InputTextPreference withPreview(Preview preview) {
        this.preview = preview;
        return this;
    }

    /**
     * Adds a sample box under the field and a result line that follows both. The result is a
     * live region, so a screen reader hears it change as either box is typed in.
     *
     * @param hint     what the sample box is for, shown and spoken as its name
     * @param maxChars the most the sample box takes
     */
    public InputTextPreference withSamplePreview(String hint, int maxChars, SamplePreview preview) {
        this.sampleHint = hint;
        this.sampleMaxChars = maxChars;
        this.samplePreview = preview;
        return this;
    }

    /**
     * Tells the keyboard the field holds names, handles or codes, not prose: no capitals, no
     * space after a dot and no autocorrect. Left as prose, SwiftKey on the S25 turned
     * com.deniscerri.ytdl into "Com. Deniscerri. Ytdl", which the field's check then refused.
     *
     * <p>Raw, so only the keyboard hears it: setInputType would also make the field single-line,
     * and on Android 14 a single-line field is capped at 5,000 characters, text it loads
     * included. A long Blocked creators list was cut on opening and the cut copy saved.
     */
    public InputTextPreference withNameKeyboard() {
        getEditText().setRawInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return this;
    }

    /**
     * Hides the value: the row says one is saved without showing it, and the editor shows dots
     * as it's typed. Raw, for the same reason as {@link #withNameKeyboard()}.
     */
    public InputTextPreference withSecret() {
        secret = true;
        getEditText().setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        getEditText().setTransformationMethod(PasswordTransformationMethod.getInstance());
        rebuildSummary();
        return this;
    }

    @Override
    public boolean callChangeListener(Object newValue) {
        if (check != null) {
            String problem = check.problem(newValue == null ? "" : newValue.toString());
            if (problem != null) {
                Utils.showToastLong(problem);
                return false;
            }
        }
        return super.callChangeListener(newValue);
    }

    @Override
    protected void onBindView(View view) {
        if (note != null) rebuildSummary();
        super.onBindView(view);

        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
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

        // The dialog shows only the description, not the "Current:" line that the row carries.
        // The editor below already shows the value, and repeating it above it would be two of
        // the same thing on one surface.
        if (baseSummary != null && !baseSummary.isEmpty()) {
            TextView summary = SettingsUi.text(
                    context,
                    baseSummary,
                    SettingsUi.TEXT_BODY_SMALL,
                    SettingsUi.textSecondary(),
                    android.graphics.Typeface.NORMAL
            );
            // The summary scrolls and gives way, the box does not. A long summary at twice the
            // system text size used to take the whole dialog and squeeze the field it explains
            // down to nothing, which is the one part nobody can do without.
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
        SettingsUi.styleEditText(editText);
        SettingsUi.labelEditor(title, editText);
        dialogView.addView(editText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (previewWatcher != null) editText.removeTextChangedListener(previewWatcher);
        previewWatcher = null;
        if (preview != null || samplePreview != null) {
            // The sample box is made fresh with the dialog and starts empty: nothing typed in it
            // is read back from, or written to, a setting.
            EditText sampleBox = null;
            if (samplePreview != null) {
                sampleBox = new EditText(context);
                sampleBox.setHint(sampleHint);
                sampleBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                sampleBox.setMinLines(2);
                sampleBox.setMaxLines(5);
                sampleBox.setFilters(new InputFilter[]{new InputFilter.LengthFilter(sampleMaxChars)});
                SettingsUi.styleEditText(sampleBox);
                SettingsUi.labelEditor(sampleBox, sampleHint);
                LinearLayout.LayoutParams sampleParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                sampleParams.setMargins(0, SettingsUi.dp(context, 10), 0, 0);
                dialogView.addView(sampleBox, sampleParams);
            }
            final EditText sample = sampleBox;
            TextView shown = SettingsUi.text(
                    context,
                    "",
                    SettingsUi.TEXT_BODY_SMALL,
                    SettingsUi.textSecondary(),
                    android.graphics.Typeface.NORMAL
            );
            if (sample != null) shown.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            previewParams.setMargins(0, SettingsUi.dp(context, 10), 0, 0);
            dialogView.addView(shown, previewParams);
            previewWatcher = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                }

                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                }

                @Override public void afterTextChanged(Editable text) {
                    showPreview(shown, String.valueOf(editText.getText()),
                            sample == null ? null : String.valueOf(sample.getText()));
                }
            };
            editText.addTextChangedListener(previewWatcher);
            if (sample != null) sample.addTextChangedListener(previewWatcher);
            showPreview(shown, String.valueOf(editText.getText()),
                    sample == null ? null : String.valueOf(sample.getText()));
        }

        return dialogView;
    }

    private void showPreview(TextView shown, String typed, @Nullable String sample) {
        String text;
        try {
            text = samplePreview != null && sample != null
                    ? samplePreview.text(typed, sample)
                    : preview == null ? null : preview.text(typed);
        } catch (RuntimeException failure) {
            // A preview that can't be worked out is left out; the field still saves.
            Logger.printException(() -> "Could not preview the typed value", failure);
            text = null;
        }
        // Unchanged text is not written again, so a live region doesn't repeat itself.
        SettingsUi.setTextIfChanged(shown, text == null ? "" : text);
        shown.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> this.onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
    }

    @Override
    protected void showDialog(Bundle state) {
        super.showDialog(state);
        SettingsUi.styleFramedDialog(getDialog());
        SettingsUi.submitOnDone(getEditText(), getDialog());
        SettingsUi.keepOpenOnInvalidInput(getDialog(), new SettingsUi.DialogCheck() {
            @Override public String problem() {
                return check == null
                        ? null
                        : check.problem(String.valueOf(getEditText().getText()));
            }

            @Override public void report(String problem) {
                SettingsUi.reportFieldError(getEditText(), problem);
            }

            @Override public boolean accept() {
                return saveTypedValue();
            }
        });
    }

    /**
     * What EditTextPreference.onDialogClosed(true) does, with the listener's answer kept rather
     * than thrown away, and reached from both the Save button and the platform's own dismiss.
     */
    private boolean saveTypedValue() {
        String typed = String.valueOf(getEditText().getText());
        if (!callChangeListener(typed)) return false;
        setText(typed);
        return true;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult) saveTypedValue();
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
