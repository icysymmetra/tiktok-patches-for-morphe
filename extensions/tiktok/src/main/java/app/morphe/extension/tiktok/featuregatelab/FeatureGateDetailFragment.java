/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Looper;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.misc.BackgroundPlay;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

@SuppressWarnings({"deprecation", "SetTextI18n"})
public final class FeatureGateDetailFragment extends Fragment {
    private static final String ARG_MANAGER = "manager";
    private static final String ARG_KEY = "key";
    private static final String ARG_TYPE = "type";
    private static final ExecutorService DETAIL_CHANGE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MorpheGateDetail");
                thread.setDaemon(true);
                return thread;
            });

    private final app.morphe.extension.tiktok.settings.SystemBackHandler systemBack =
            new app.morphe.extension.tiktok.settings.SystemBackHandler("FeatureGateDetailBackCallback");

    private FeatureGateCatalog.Entry entry;
    private FeatureGateLabStore.Rule rule;
    private TextView status;
    private TextView statusReason;
    private TextView effectiveValue;
    private Spinner values;
    private Switch force;
    private Switch booleanValue;
    private TextView reset;
    private TextView saveObject;
    private TextView discardObject;
    private LinearLayout disabledNote;
    /** Whether a field has been typed in since the page opened or a save landed. */
    private boolean fieldsDirty;
    /** Raised while the page itself writes into the editors, which is not an edit. */
    private boolean fillingFields;
    private LinearLayout technicalDetails;
    private TextView technicalToggle;
    private List<ValueOption> options;
    private final List<ObjectFieldEditor> objectEditors = new ArrayList<>();
    /** Held so the window can be taken down with the view that opened it. */
    private AlertDialog customValueDialog;
    private boolean suppress;
    private int lastConcreteSelection;
    private long detailChangeGeneration;
    private final Object detailChangeState = new Object();
    private int detailChangesPending;
    private FeatureGateLabUndo.UndoBaseline detailUndoBaseline;

    interface DetailChangeTestHook {
        void before(long generation) throws Exception;
        void after(long generation);
    }

    private static volatile DetailChangeTestHook detailChangeTestHook;

    static void setDetailChangeTestHookForTests(DetailChangeTestHook hook) {
        detailChangeTestHook = hook;
    }

    static void awaitChangesForTests() throws Exception {
        DETAIL_CHANGE_EXECUTOR.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    public static FeatureGateDetailFragment forEntry(String manager, String key, String type) {
        FeatureGateDetailFragment fragment = new FeatureGateDetailFragment();
        Bundle arguments = new Bundle();
        arguments.putString(ARG_MANAGER, manager);
        arguments.putString(ARG_KEY, key);
        arguments.putString(ARG_TYPE, type);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = getActivity();
        FeatureGateLabUi.syncTheme(context);
        FeatureGateCatalog.Snapshot snapshot = FeatureGateCatalog.cachedSnapshot();
        String manager = getArguments().getString(ARG_MANAGER, "");
        String key = getArguments().getString(ARG_KEY, "");
        entry = snapshot == null ? null : snapshot.byIdentity.get(manager + "\n" + key);

        FeatureGateLabUi.BackInterceptLayout screen =
                new FeatureGateLabUi.BackInterceptLayout(context, this::leaveDetail);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(SettingsUi.background());
        screen.requestFocus();
        screen.addView(
                FeatureGateLabUi.header(context, L10n.t(context, "Gate details"), this::leaveDetail),
                FeatureGateLabUi.matchWrap()
        );

        if (entry == null) {
            TextView error = FeatureGateLabUi.body(context, L10n.t(context, "This catalog entry is no longer available. Refresh the Lab and try again."));
            error.setGravity(Gravity.CENTER);

            // The message names the Lab, and the Lab is one screen back, so the way there is
            // offered rather than described. It was a sentence alone on a blank screen telling
            // the reader to go somewhere with no way of going.
            TextView back = FeatureGateLabUi.body(context, L10n.t(context, "Back"));
            back.setGravity(Gravity.CENTER);
            back.setPadding(
                    FeatureGateLabUi.dp(context, 16),
                    FeatureGateLabUi.dp(context, 12),
                    FeatureGateLabUi.dp(context, 16),
                    FeatureGateLabUi.dp(context, 12)
            );
            SettingsUi.styleTextAction(back, true);
            back.setOnClickListener(view -> leaveDetail());

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            column.setPadding(
                    FeatureGateLabUi.dp(context, 24),
                    FeatureGateLabUi.dp(context, 24),
                    FeatureGateLabUi.dp(context, 24),
                    FeatureGateLabUi.dp(context, 24)
            );
            column.addView(error, FeatureGateLabUi.matchWrap());
            column.addView(back, FeatureGateLabUi.wrapWrap());
            screen.addView(column, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return screen;
        }
        rule = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);

        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                FeatureGateLabUi.dp(context, 16),
                FeatureGateLabUi.dp(context, 8),
                FeatureGateLabUi.dp(context, 16),
                FeatureGateLabUi.dp(context, 24)
        );
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        screen.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView title = FeatureGateLabUi.heading(context, entry.title);
        title.setMaxLines(3);
        title.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(title, FeatureGateLabUi.matchWrap());

        TextView rawKey = FeatureGateLabUi.label(context, entry.key);
        rawKey.setTextIsSelectable(true);
        rawKey.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams keyParams = FeatureGateLabUi.matchWrap();
        keyParams.setMargins(0, FeatureGateLabUi.dp(context, 6), 0, 0);
        content.addView(rawKey, keyParams);

        if (!entry.description.isEmpty()) {
            TextView description = FeatureGateLabUi.label(context, entry.description);
            description.setLineSpacing(FeatureGateLabUi.dp(context, 2), 1f);
            LinearLayout.LayoutParams params = FeatureGateLabUi.matchWrap();
            params.setMargins(0, FeatureGateLabUi.dp(context, 12), 0, 0);
            content.addView(description, params);
        }

        addSectionTitle(content, L10n.t(context, "Current state"));
        LinearLayout currentState = new LinearLayout(context);
        currentState.setOrientation(LinearLayout.VERTICAL);
        currentState.setPadding(FeatureGateLabUi.dp(context, 16), 0, FeatureGateLabUi.dp(context, 16), 0);
        currentState.setBackground(SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_CARD, false));
        content.addView(currentState, FeatureGateLabUi.matchWrap());
        addInfo(currentState, L10n.t(context, "Seen this session"),
                L10n.t(context, entry.loaded ? "Yes" : "No"));
        addInfo(currentState, L10n.t(context, "TikTok's own value"), "OBJECT".equals(entry.type)
                ? L10n.t(context, entry.loaded
                        ? "Structured value observed" : "Not requested in this process yet")
                : (entry.loaded
                        ? entry.currentValue + " (" + entry.currentType + ")"
                        : L10n.t(context, "Not seen yet")));
        effectiveValue = addInfo(currentState, L10n.t(context, "What TikTok gets"), effectiveValueText());
        TextView cacheNote = FeatureGateLabUi.label(
                context,
                L10n.t(context, "An override changes the value TikTok reads from this gate. It "
                        + "doesn't change TikTok's saved copy or prove the named feature "
                        + "changed.")
        );
        LinearLayout.LayoutParams cacheNoteParams = FeatureGateLabUi.matchWrap();
        cacheNoteParams.setMargins(0, FeatureGateLabUi.dp(context, 8), 0, 0);
        content.addView(cacheNote, cacheNoteParams);

        if (isSensitiveKey(entry.key)) {
            View sensitive = FeatureGateLabUi.notice(context,
                    L10n.t(context, "This key touches account safety. Forcing it can change login, region, payment or security checks."),
                    SettingsUi.attentionColor());
            LinearLayout.LayoutParams params = FeatureGateLabUi.matchWrap();
            int noticeMargin = FeatureGateLabUi.dp(context, SettingsUi.NOTICE_MARGIN);
            params.setMargins(0, noticeMargin, 0, noticeMargin);
            content.addView(sensitive, params);
        }
        if (BackgroundPlay.decidesGate(entry.key)) {
            View decided = FeatureGateLabUi.notice(context,
                    L10n.t(context, "Keep playing in the background is on in Metra's Playback settings, so TikTok gets 2 for this key and an override here has no effect until that switch is off."),
                    SettingsUi.attentionColor());
            LinearLayout.LayoutParams params = FeatureGateLabUi.matchWrap();
            int noticeMargin = FeatureGateLabUi.dp(context, SettingsUi.NOTICE_MARGIN);
            params.setMargins(0, noticeMargin, 0, noticeMargin);
            content.addView(decided, params);
        }

        addSectionTitle(content, L10n.t(context, "Override"));
        if (!FeatureGateLabStore.masterEnabled()) {
            // It was a loose grey caption after the Reset row, so a reader met greyed controls
            // first and the reason last, with nothing to do about it and the switch a screen
            // back. Above the controls it explains them, and the action turns them on here.
            disabledNote = settingRow(context,
                    L10n.t(context, "Overrides are disabled in the Feature Gate Lab."),
                    L10n.t(context, "Nothing on this page changes what TikTok does until they are on."));
            disabledNote.setTag("feature_gate_disabled_note");
            TextView turnOn = FeatureGateLabUi.text(context, L10n.t(context, "Turn on overrides"),
                    14, SettingsUi.accent(), Typeface.BOLD);
            turnOn.setTag("feature_gate_enable_overrides");
            SettingsUi.styleTextAction(turnOn, true);
            turnOn.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
            turnOn.setPadding(FeatureGateLabUi.dp(context, 12), FeatureGateLabUi.dp(context, 10),
                    0, FeatureGateLabUi.dp(context, 10));
            SettingsUi.markAsButton(turnOn);
            turnOn.setOnClickListener(view -> enableOverrides());
            disabledNote.addView(turnOn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(disabledNote, FeatureGateLabUi.matchWrap());
        }
        status = FeatureGateLabUi.text(context, "", 13, SettingsUi.textSecondary(), Typeface.BOLD);
        status.setAllCaps(true);
        status.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams statusParams = FeatureGateLabUi.matchWrap();
        statusParams.topMargin = FeatureGateLabUi.dp(context, 12);
        content.addView(status, statusParams);

        // Under the status, and only there when something refused the override. The status
        // could say it had not been applied and nothing more; the reason was in logcat.
        statusReason = FeatureGateLabUi.label(context, "");
        statusReason.setTextColor(FeatureGateLabUi.warningColor(context));
        statusReason.setTag("feature_gate_status_reason");
        statusReason.setVisibility(View.GONE);
        content.addView(statusReason, FeatureGateLabUi.matchWrap());

        View statusGap = new View(context);
        content.addView(statusGap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, FeatureGateLabUi.dp(context, 12)));

        boolean editable = FeatureGateLabStore.masterEnabled();
        boolean booleanEntry = "BOOLEAN".equals(entry.type);
        boolean objectEntry = "OBJECT".equals(entry.type);
        if (objectEntry) {
            force = new Switch(context);
            LinearLayout forceRow = FeatureGateLabUi.switchRow(
                    context,
                    L10n.t(context, "Override this configuration"),
                    L10n.t(context, "Return a copy with the fields below changed"),
                    force
            );
            content.addView(forceRow, FeatureGateLabUi.matchWrap());
            addObjectEditors(content, editable);
            saveObject = FeatureGateLabUi.text(
                    context,
                    L10n.t(context, "Save field values"),
                    14,
                    SettingsUi.accent(),
                    Typeface.BOLD
            );
            saveObject.setTag("feature_gate_save_fields");
            SettingsUi.styleTextAction(saveObject, true);
            saveObject.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            saveObject.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
            saveObject.setPadding(0, FeatureGateLabUi.dp(context, 10), 0, FeatureGateLabUi.dp(context, 10));
            saveObject.setEnabled(false);
            content.addView(saveObject, FeatureGateLabUi.matchWrap());
            // Nothing said an edit was pending: Save looked the same before and after typing,
            // and Back dropped every edit without a word. The action says which it is now, and
            // Discard is the way to drop them on purpose.
            discardObject = FeatureGateLabUi.text(context, L10n.t(context, "Discard edits"), 14,
                    SettingsUi.textSecondary(), Typeface.NORMAL);
            discardObject.setTag("feature_gate_discard_fields");
            SettingsUi.styleTextAction(discardObject, false);
            discardObject.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            discardObject.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
            discardObject.setPadding(0, FeatureGateLabUi.dp(context, 10), 0,
                    FeatureGateLabUi.dp(context, 10));
            discardObject.setVisibility(View.GONE);
            content.addView(discardObject, FeatureGateLabUi.matchWrap());
        } else if (booleanEntry) {
            // Two rows, the same as every other type. With one switch doing both jobs, a rule
            // saved with its override off (which is how an import lands) could only be turned
            // on by first forcing the opposite of the value it holds.
            force = new Switch(context);
            LinearLayout forceRow = FeatureGateLabUi.switchRow(
                    context,
                    L10n.t(context, "Override this gate"),
                    L10n.t(context, "When TikTok requests this key, return the selected value below"),
                    force
            );
            forceRow.setBackground(SettingsUi.groupedRow(context, true, false));
            content.addView(forceRow, FeatureGateLabUi.matchWrap());

            booleanValue = new Switch(context);
            booleanValue.setChecked(Boolean.parseBoolean(rule == null ? bestInitialValue(entry) : rule.value));
            booleanValue.setEnabled(editable);
            LinearLayout valueRow = FeatureGateLabUi.switchRow(
                    context,
                    L10n.t(context, "Forced result"),
                    L10n.t(context, "Off forces the value to false, on forces it to true. Reset "
                            + "lets TikTok decide again."),
                    booleanValue
            );
            valueRow.setBackground(SettingsUi.groupedRow(context, false, true));
            content.addView(valueRow, FeatureGateLabUi.matchWrap());
        } else {
            force = new Switch(context);
            LinearLayout forceRow = FeatureGateLabUi.switchRow(
                    context,
                    L10n.t(context, "Override this gate"),
                    L10n.t(context, "When TikTok requests this key, return the selected value below"),
                    force
            );
            content.addView(forceRow, FeatureGateLabUi.matchWrap());

            forceRow.setBackground(SettingsUi.groupedRow(context, true, false));
            LinearLayout valueRow = new LinearLayout(context);
            valueRow.setOrientation(LinearLayout.VERTICAL);
            valueRow.setPadding(FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 14),
                    FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 8));
            valueRow.setBackground(SettingsUi.groupedRow(context, false, true));
            TextView valueLabel = FeatureGateLabUi.body(context,
                    L10n.t(context, "Value to return"));
            valueRow.addView(valueLabel, FeatureGateLabUi.matchWrap());
            options = buildOptions(entry, rule);
            values = new Spinner(context);
            values.setTag("feature_gate_value");
            values.setEnabled(editable);
            applyOptionsAdapter();
            SettingsUi.styleSpinner(values);
            values.setContentDescription(L10n.t(context, "Value to return"));
            LinearLayout.LayoutParams valueParams = FeatureGateLabUi.matchWrap();
            valueParams.setMargins(0, FeatureGateLabUi.dp(context, 4), 0, FeatureGateLabUi.dp(context, 4));
            valueRow.addView(values, valueParams);
            content.addView(valueRow, FeatureGateLabUi.matchWrap());
        }

        reset = FeatureGateLabUi.text(context, L10n.t(context, "Reset override"), 14, SettingsUi.accent(), Typeface.BOLD);
        reset.setTag("feature_gate_reset");
        SettingsUi.styleTextAction(reset, true);
        reset.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        reset.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
        reset.setPadding(0, FeatureGateLabUi.dp(context, 10), 0, FeatureGateLabUi.dp(context, 10));
        reset.setVisibility(rule == null ? View.GONE : View.VISIBLE);
        content.addView(reset, FeatureGateLabUi.matchWrap());

        if (force != null) force.setEnabled(editable);

        suppress = true;
        if (!booleanEntry && !objectEntry) {
            int selected = selectedIndex(options, rule == null ? bestInitialValue(entry) : rule.value);
            values.setSelection(selected);
            lastConcreteSelection = selected;
        }
        if (force != null) force.setChecked(rule != null && rule.enabled);
        suppress = false;
        updateStatus();

        if (values != null) {
            values.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (suppress || !FeatureGateLabStore.masterEnabled()) return;
                    ValueOption option = options.get(position);
                    if (option.custom) {
                        showCustomValue();
                        return;
                    }
                    lastConcreteSelection = position;
                    String storedValue = rule == null ? null : rule.value;
                    if ((storedValue == null && option.value.equals(bestInitialValue(entry)))
                            || option.value.equals(storedValue)) return;
                    persist(option.value, force.isChecked());
                }

                @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
        }

        if (booleanValue != null) {
            booleanValue.setOnCheckedChangeListener((button, checked) -> {
                if (suppress || !FeatureGateLabStore.masterEnabled()) return;
                String value = String.valueOf(checked);
                // Landing on the value TikTok already returns, with nothing saved, is not a
                // change worth writing a rule for. The spinner's listener says the same.
                if (rule == null && value.equals(bestInitialValue(entry))) return;
                persist(value, force.isChecked());
            });
        }

        if (force != null && booleanEntry) {
            force.setOnCheckedChangeListener((button, enabled) -> {
                if (suppress || !FeatureGateLabStore.masterEnabled()) return;
                persist(String.valueOf(booleanValue.isChecked()), enabled);
            });
        }

        if (force != null && !objectEntry && !booleanEntry) {
            force.setOnCheckedChangeListener((button, enabled) -> {
                if (suppress || !FeatureGateLabStore.masterEnabled()) return;
                ValueOption selectedOption = options.get(values.getSelectedItemPosition());
                if (selectedOption.custom) {
                    suppress = true;
                    force.setChecked(false);
                    suppress = false;
                    showCustomValue();
                    return;
                }
                persist(selectedOption.value, enabled);
            });
        }

        if (force != null && objectEntry) {
            force.setOnCheckedChangeListener((button, enabled) -> {
                if (suppress || !FeatureGateLabStore.masterEnabled()) return;
                String value = rule == null ? objectPatchText() : rule.value;
                if (value != null) persist(value, enabled, rule == null);
            });
        }

        if (saveObject != null) {
            saveObject.setOnClickListener(view -> {
                String value = objectPatchText();
                if (value == null) return;
                persist(value, force.isChecked(), true);
            });
        }
        if (discardObject != null) {
            discardObject.setOnClickListener(view -> {
                fillingFields = true;
                try {
                    for (ObjectFieldEditor editor : objectEditors) editor.restore();
                } finally {
                    fillingFields = false;
                }
                setFieldsDirty(false);
                Utils.showToastShort(L10n.t(getContext(), "Field edits were discarded"));
            });
        }
        watchObjectEditors();

        reset.setOnClickListener(view -> resetRule());
        addTechnicalDetails(content);
        SettingsUi.styleSwitches(screen);
        return screen;
    }

    @Override
    public void onResume() {
        super.onResume();
        systemBack.register(getActivity(), this::leaveDetail);
        updateStatus();
    }

    @Override
    public void onPause() {
        systemBack.unregister();
        super.onPause();
    }

    /**
     * Lets go of the view this screen was built from.
     *
     * <p>The screen sits on a back stack, so its view is built again on a rotation, a font scale
     * change, a theme change, or a return from anything deeper. Without this the editors from
     * every earlier view stayed in the list, so the text collected on save came from fields
     * nobody could see, and each of them pinned a destroyed hierarchy. A dialog left open when
     * the activity goes is a leaked window whose buttons reach for those same dead views.
     */
    @Override
    public void onDestroyView() {
        if (customValueDialog != null) {
            if (customValueDialog.isShowing()) customValueDialog.dismiss();
            customValueDialog = null;
        }
        objectEditors.clear();
        status = null;
        statusReason = null;
        effectiveValue = null;
        values = null;
        force = null;
        booleanValue = null;
        reset = null;
        saveObject = null;
        technicalDetails = null;
        technicalToggle = null;
        super.onDestroyView();
    }

    /** How many object field editors the current view is holding, so a test can see them stack. */
    int objectEditorCountForTests() {
        return objectEditors.size();
    }

    boolean customValueDialogShowingForTests() {
        return customValueDialog != null && customValueDialog.isShowing();
    }

    /**
     * Watches every field editor, so the page knows when something is waiting to be saved.
     *
     * <p>Only typing by a reader counts: the page writes into these editors itself when it
     * opens and when Discard puts them back, and neither is a pending edit.
     */
    private void watchObjectEditors() {
        for (ObjectFieldEditor editor : objectEditors) {
            if (editor.input != null) {
                editor.input.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

                    @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                        if (!fillingFields) {
                            editor.changed();
                            setFieldsDirty(true);
                        }
                    }

                    @Override public void afterTextChanged(android.text.Editable text) { }
                });
            } else if (editor.toggle != null) {
                editor.toggle.setOnCheckedChangeListener((button, checked) -> {
                    if (!fillingFields) {
                        editor.changed();
                        setFieldsDirty(true);
                    }
                });
            }
        }
    }

    /** Says whether a save is owed, on the action and to a screen reader. */
    private void setFieldsDirty(boolean dirty) {
        fieldsDirty = dirty;
        if (saveObject == null) return;
        boolean editable = FeatureGateLabStore.masterEnabled();
        saveObject.setEnabled(editable && dirty);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            saveObject.setStateDescription(L10n.t(saveObject.getContext(),
                    dirty ? "Not saved yet" : "Saved"));
        }
        if (discardObject != null) {
            discardObject.setVisibility(dirty && editable ? View.VISIBLE : View.GONE);
        }
    }

    /** Hands the page's controls over, or takes them back, without rebuilding it. */
    private void setControlsEditable(boolean editable) {
        if (values != null) values.setEnabled(editable);
        if (force != null) force.setEnabled(editable);
        if (booleanValue != null) booleanValue.setEnabled(editable);
        for (ObjectFieldEditor editor : objectEditors) editor.setEditable(editable);
        setFieldsDirty(fieldsDirty);
    }

    /**
     * Turns overrides on from here, so a reader who has just been told they are off does not
     * have to go back a screen to act on it.
     */
    private void enableOverrides() {
        runDetailChange(
                undoBaseline -> FeatureGateLabUndo.setMasterEnabled(true),
                () -> {
                    if (disabledNote != null) disabledNote.setVisibility(View.GONE);
                    setControlsEditable(true);
                    updateStatus();
                },
                L10n.t(Utils.getContext(), "Overrides enabled. Restart TikTok to apply this."),
                L10n.t(Utils.getContext(), "Couldn't change the Lab settings. Try again."));
    }

    private void leaveDetail() {
        // Not a confirmation dialog: the house rule is one line of feedback and the action goes
        // through. What was typed is not saved, and now that is said rather than silent.
        if (fieldsDirty) Utils.showToastShort(L10n.t(getContext(), "Field edits weren't saved"));
        if (getFragmentManager() != null) getFragmentManager().popBackStack();
    }

    private LinearLayout settingRow(Context context, String title, String subtitle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 16),
                FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 16));
        row.setBackground(SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_CARD, false));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(FeatureGateLabUi.body(context, title), FeatureGateLabUi.matchWrap());
        labels.addView(FeatureGateLabUi.label(context, subtitle), FeatureGateLabUi.matchWrap());
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void persist(String value, boolean enabled) {
        persist(value, enabled, false);
    }

    private void persist(String value, boolean enabled, boolean fieldsSubmitted) {
        FeatureGateLabStore.ValidationFailure error =
                FeatureGateLabStore.validateValue(entry.type, value);
        if (error != null) {
            Utils.showToastLong(FeatureGateLabText.validation(getContext(), error));
            return;
        }
        // Saving takes the journal lock and two write-and-verify cycles, the same as the Lab
        // screen's own changes, which have run off the main thread since they were written.
        Object[] saved = new Object[1];
        List<FieldSnapshot> submitted = fieldsSubmitted ? snapshotFields() : null;
        runDetailChange(
                undoBaseline -> {
                    FeatureGateLabUndo.saveRule(
                            entry.manager, entry.key, entry.type, value, enabled, undoBaseline);
                    saved[0] = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);
                },
                () -> {
                    rule = (FeatureGateLabStore.Rule) saved[0];
                    updateFieldBaseline(submitted, false);
                    reset.setVisibility(View.VISIBLE);
                    updateStatus();
                },
                L10n.t(Utils.getContext(), "Feature gate override saved"),
                L10n.t(Utils.getContext(), "Couldn't save this override. Try again."));
    }

    private void resetRule() {
        List<FieldSnapshot> requested = snapshotFields();
        runDetailChange(
                undoBaseline -> FeatureGateLabUndo.deleteRule(
                        entry.manager, entry.key, entry.type, undoBaseline),
                () -> {
                    rule = null;
                    suppress = true;
                    if (force != null) force.setChecked(false);
                    if (booleanValue != null) {
                        booleanValue.setChecked(Boolean.parseBoolean(bestInitialValue(entry)));
                    } else if (values != null) {
                        values.setSelection(selectedIndex(options, bestInitialValue(entry)));
                    }
                    suppress = false;
                    updateFieldBaseline(requested, true);
                    reset.setVisibility(View.GONE);
                    updateStatus();
                },
                L10n.t(Utils.getContext(), "Feature gate override reset"),
                L10n.t(Utils.getContext(), "Couldn't reset this override. Try again."));
    }

    /**
     * Puts the controls back on what the store actually holds.
     *
     * <p>A save that fails leaves the switch where the finger left it, so the page goes on
     * claiming a value nothing has written: the toast says it did not save and the screen says
     * it did. The Lab's own master switch has always put itself back; this is the same for the
     * detail page. The rule is re-read rather than remembered, so the controls end up on what
     * survived rather than on what this fragment last thought was there.
     */
    private void revertControlsToStore() {
        if (getActivity() == null) return;
        rule = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);
        suppress = true;
        if (force != null) force.setChecked(rule != null && rule.enabled);
        String value = rule == null ? bestInitialValue(entry) : rule.value;
        if (booleanValue != null) {
            booleanValue.setChecked(Boolean.parseBoolean(value));
        } else if (values != null) {
            int selected = selectedIndex(options, value);
            values.setSelection(selected);
            lastConcreteSelection = selected;
        }
        suppress = false;
        updateFieldBaseline(null, false);
        if (reset != null) reset.setVisibility(rule == null ? View.GONE : View.VISIBLE);
        updateStatus();
    }

    private List<FieldSnapshot> snapshotFields() {
        List<FieldSnapshot> result = new ArrayList<>();
        for (ObjectFieldEditor editor : objectEditors) result.add(new FieldSnapshot(editor));
        return result;
    }

    /** Changes the Discard target only after storage has finished, without replacing newer edits. */
    private void updateFieldBaseline(List<FieldSnapshot> submitted, boolean resetFields) {
        if (objectEditors.isEmpty()) return;
        JSONObject stored = structuredBaseValue();
        boolean dirty = false;
        fillingFields = true;
        try {
            for (ObjectFieldEditor editor : objectEditors) {
                FieldSnapshot snapshot = null;
                if (submitted != null) {
                    for (FieldSnapshot candidate : submitted) {
                        if (candidate.editor == editor) { snapshot = candidate; break; }
                    }
                }
                Object value = stored.opt(editor.name);
                editor.savedValue = value == null ? JSONObject.NULL : value;
                editor.savedText = snapshot != null && !resetFields
                        ? snapshot.text : editorText(value, editor.kind);
                editor.savedChecked = snapshot != null && !resetFields
                        ? snapshot.checked : Boolean.TRUE.equals(value);
                if (snapshot != null && snapshot.revision == editor.revision) {
                    if (resetFields) editor.restore();
                    else editor.edited = false;
                } else if (snapshot != null) {
                    editor.edited |= editor.toggle != null
                            ? editor.toggle.isChecked() != editor.savedChecked
                            : !editor.input.getText().toString().equals(editor.savedText);
                } else if (snapshot == null && !editor.edited) {
                    editor.restore();
                }
                dirty |= editor.edited;
            }
        } finally {
            fillingFields = false;
        }
        setFieldsDirty(dirty);
    }

    /** A change that touches storage, so it does not belong on the thread drawing the screen. */
    private interface DetailChange {
        void run(FeatureGateLabUndo.UndoBaseline undoBaseline) throws Exception;
    }

    /**
     * Runs {@code change} off the main thread, then reports completion on the main thread.
     *
     * <p>Only the newest queued change may report or repaint the screen. This keeps an older
     * success or failure from replacing the state selected by a later tap.
     */
    private void runDetailChange(DetailChange change, Runnable onDone,
                                 String translatedSuccess, String translatedFailurePrefix) {
        final long generation;
        final FeatureGateLabUndo.UndoBaseline undoBaseline;
        synchronized (detailChangeState) {
            generation = ++detailChangeGeneration;
            if (detailChangesPending++ == 0) {
                detailUndoBaseline = new FeatureGateLabUndo.UndoBaseline();
            }
            undoBaseline = detailUndoBaseline;
        }
        try {
            DETAIL_CHANGE_EXECUTOR.execute(() -> {
                String failure = null;
                try {
                    DetailChangeTestHook hook = detailChangeTestHook;
                    if (hook != null) hook.before(generation);
                    change.run(undoBaseline);
                    if (hook != null) hook.after(generation);
                } catch (Exception error) {
                    boolean refused = error instanceof FeatureGateLabStore.RuleLimitRefused;
                    // The rule cap is told to the reader. As a patch error, its debug toast
                    // covered the reason.
                    if (refused) Logger.printInfo(() -> "Feature Gate detail change refused", error);
                    else Logger.printException(() -> "Feature Gate detail change failed", error);
                    failure = refused ? error.getMessage() : translatedFailurePrefix;
                } finally {
                    detailChangeFinished();
                }
                String notice = failure;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (generation != detailChangeGeneration) return;
                    if (notice != null) {
                        Utils.showToastLong(notice);
                        revertControlsToStore();
                        return;
                    }
                    Utils.showToastShort(translatedSuccess);
                    if (getActivity() == null || reset == null) return;
                    onDone.run();
                });
            });
        } catch (RejectedExecutionException error) {
            detailChangeFinished();
            Logger.printException(() -> "Could not schedule Feature Gate detail change", error);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (generation == detailChangeGeneration) {
                    Utils.showToastLong(translatedFailurePrefix);
                    revertControlsToStore();
                }
            });
        }
    }

    private void detailChangeFinished() {
        synchronized (detailChangeState) {
            detailChangesPending--;
            if (detailChangesPending == 0) detailUndoBaseline = null;
        }
    }

    private void showCustomValue() {
        EditText input = new EditText(getActivity());
        input.setSingleLine(!"STRING".equals(entry.type));
        input.setHint(L10n.t(getContext(), "New value"));
        input.setText(rule == null ? "" : rule.value);
        SettingsUi.styleEditText(input);
        LinearLayout dialogBody = new LinearLayout(getActivity());
        dialogBody.setOrientation(LinearLayout.VERTICAL);
        int bodyPad = SettingsUi.dp(getActivity(), 20);
        dialogBody.setPadding(bodyPad, bodyPad, bodyPad, 0);
        TextView notice = new TextView(getActivity());
        notice.setText(L10n.t(getContext(),
                "The Lab can't check this. TikTok gets it exactly as typed."));
        notice.setTextColor(SettingsUi.textSecondary());
        notice.setTextSize(SettingsUi.TEXT_BODY_SMALL);
        dialogBody.addView(notice);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.setMargins(0, SettingsUi.dp(getActivity(), 12), 0, 0);
        dialogBody.addView(input, inputParams);
        AlertDialog dialog = customValueDialog = new AlertDialog.Builder(getActivity())
                .setTitle(L10n.t(getContext(), "Custom value"))
                .setView(dialogBody)
                .setPositiveButton(L10n.t(getContext(), "Use value"), null)
                .setNegativeButton(L10n.t(getContext(), "Cancel"),
                        (ignored, which) -> restoreSelection())
                .setOnCancelListener(ignored -> restoreSelection())
                .create();
        dialog.setOnShowListener(ignored -> {
            FeatureGateLabUi.styleDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString();
                FeatureGateLabStore.ValidationFailure error =
                        FeatureGateLabStore.validateValue(entry.type, value);
                if (error != null) {
                    SettingsUi.reportFieldError(
                            input, FeatureGateLabText.validation(getContext(), error));
                    return;
                }
                addOrSelectCustomValue(value);
                persist(value, force.isChecked());
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void addOrSelectCustomValue(String value) {
        int index = selectedIndex(options, value);
        if (index >= options.size() || options.get(index).custom || !value.equals(options.get(index).value)) {
            int customIndex = options.size() - 1;
            options.add(customIndex, new ValueOption(
                    value,
                    FeatureGateLabText.customOptionLabel(getContext(), value),
                    false
            ));
            applyOptionsAdapter();
            index = customIndex;
        }
        suppress = true;
        values.setSelection(index);
        lastConcreteSelection = index;
        suppress = false;
    }

    private void applyOptionsAdapter() {
        List<String> labels = new ArrayList<>();
        for (ValueOption option : options) labels.add(option.label);
        ArrayAdapter<String> adapter = new ValueAdapter(getActivity(), labels);
        values.setAdapter(adapter);
    }

    private static final class ValueAdapter extends ArrayAdapter<String> {
        ValueAdapter(Context context, List<String> labels) {
            super(context, android.R.layout.simple_spinner_item, labels);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return valueView(position, false, parent.isEnabled());
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return valueView(position, true, parent.isEnabled());
        }

        private TextView valueView(int position, boolean dropdown, boolean enabled) {
            Context context = getContext();
            TextView view = FeatureGateLabUi.body(context, getItem(position));
            view.setEnabled(enabled);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setSingleLine(false);
            view.setEllipsize(null);
            view.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
            view.setTextColor(SettingsUi.enabledTextColors(SettingsUi.accent()));
            if (!dropdown) {
                android.graphics.drawable.Drawable arrow =
                        new app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference
                                .ChevronDrawable(context);
                arrow.setBounds(0, 0, FeatureGateLabUi.dp(context, 18), FeatureGateLabUi.dp(context, 18));
                view.setCompoundDrawablesRelative(null, null, arrow, null);
            }
            view.setPadding(
                    FeatureGateLabUi.dp(context, 12),
                    dropdown ? FeatureGateLabUi.dp(context, 10) : 0,
                    FeatureGateLabUi.dp(context, 12),
                    dropdown ? FeatureGateLabUi.dp(context, 10) : 0
            );
            view.setBackgroundColor(dropdown ? SettingsUi.surface() : android.graphics.Color.TRANSPARENT);
            return view;
        }
    }

    private void restoreSelection() {
        suppress = true;
        values.setSelection(lastConcreteSelection);
        suppress = false;
    }

    private void updateStatus() {
        if (status == null || entry == null) return;
        rule = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);
        if (rule == null) {
            status.setText(L10n.t(getContext(), "Using TikTok's value"));
            status.setTextColor(SettingsUi.textSecondary());
            showFailureReason(null);
            if (effectiveValue != null) updateEffectiveValue();
            return;
        }
        if (!rule.enabled) {
            status.setText(L10n.t(getContext(), "Saved, override off"));
            status.setTextColor(SettingsUi.textSecondary());
            showFailureReason(null);
            if (effectiveValue != null) updateEffectiveValue();
            return;
        }
        boolean triggered = FeatureGateLabRuntime.isTriggered(entry.manager, entry.key, entry.type);
        FeatureGateFailure failure = FeatureGateLabRuntime.structuredFailure(
                entry.manager, entry.key, entry.type);
        status.setText(failure != null
                ? L10n.t(getContext(),
                        "TikTok read it, but the override couldn't be applied. Reset it and save it again.")
                : L10n.t(getContext(), triggered
                        ? "TikTok read it" : "Not read yet"));
        status.setTextColor(triggered ? SettingsUi.accent() : FeatureGateLabUi.warningColor(getActivity()));
        showFailureReason(failure);
        if (effectiveValue != null) updateEffectiveValue();
    }

    /** The reason under the status, which is there only while there is one. */
    private void showFailureReason(FeatureGateFailure failure) {
        if (statusReason == null) return;
        if (failure == null) {
            statusReason.setText("");
            statusReason.setVisibility(View.GONE);
            return;
        }
        statusReason.setText(FeatureGateLabText.structuredFailure(getContext(), failure));
        statusReason.setVisibility(View.VISIBLE);
    }

    private String effectiveValueText() {
        return FeatureGateLabText.effectiveValue(getContext(), entry);
    }

    private void updateEffectiveValue() {
        String text = effectiveValueText();
        effectiveValue.setText(text);
        effectiveValue.setContentDescription(
                L10n.f(getContext(), "%1$s: %2$s",
                        L10n.t(getContext(), "What TikTok gets"), text));
    }

    private void addObjectEditors(LinearLayout root, boolean editable) {
        JSONObject values = structuredBaseValue();
        JSONArray fieldNames = StructuredConfigController.actionableFields(entry.requestedClass);
        if (fieldNames.length() == 0) {
            TextView unavailable = FeatureGateLabUi.label(
                    root.getContext(),
                    L10n.t(root.getContext(), "This configuration has no fields that can be copied and changed safely on this build.")
            );
            unavailable.setTextColor(FeatureGateLabUi.warningColor(root.getContext()));
            root.addView(unavailable, FeatureGateLabUi.matchWrap());
            return;
        }

        TextView explanation = FeatureGateLabUi.label(
                root.getContext(),
                L10n.t(root.getContext(), "Only fields the Lab can check and copy safely are shown.")
        );
        LinearLayout.LayoutParams explanationParams = FeatureGateLabUi.matchWrap();
        explanationParams.setMargins(0, FeatureGateLabUi.dp(root.getContext(), 6), 0, 0);
        root.addView(explanation, explanationParams);

        for (int index = 0; index < fieldNames.length(); index++) {
            String fieldName = fieldNames.optString(index, "");
            String kind = StructuredConfigController.fieldKind(entry.requestedClass, fieldName);
            if (fieldName.isEmpty() || "UNSUPPORTED".equals(kind)) {
                continue;
            }
            Object value = values.opt(fieldName);
            LinearLayout fieldRoot = new LinearLayout(root.getContext());
            fieldRoot.setOrientation(LinearLayout.VERTICAL);
            int fieldPadding = FeatureGateLabUi.dp(root.getContext(), 16);
            fieldRoot.setPadding(fieldPadding, fieldPadding, fieldPadding, fieldPadding);
            fieldRoot.setBackground(SettingsUi.borderedSurface(
                    root.getContext(), SettingsUi.RADIUS_SETTINGS_CARD, false));
            LinearLayout.LayoutParams fieldParams = FeatureGateLabUi.matchWrap();
            fieldParams.setMargins(0, FeatureGateLabUi.dp(root.getContext(), 12), 0, 0);

            TextView title = FeatureGateLabUi.body(root.getContext(), titleForField(fieldName));
            fieldRoot.addView(title, FeatureGateLabUi.matchWrap());
            TextView rawName = FeatureGateLabUi.label(root.getContext(), fieldName + " / " + kind.toLowerCase(Locale.ROOT));
            rawName.setPadding(0, FeatureGateLabUi.dp(root.getContext(), 4), 0, 0);
            fieldRoot.addView(rawName, FeatureGateLabUi.matchWrap());

            if ("BOOLEAN".equals(kind)) {
                Switch toggle = new Switch(root.getContext());
                toggle.setId(View.generateViewId());
                toggle.setChecked(value instanceof Boolean
                        ? (Boolean) value
                        : Boolean.parseBoolean(String.valueOf(value)));
                toggle.setEnabled(editable);
                // Named the same way the text fields beside it are: the readable title owns the
                // relationship and both labels leave traversal, so one field is one stop rather
                // than a title, a raw name and a switch that repeats the raw name back.
                final CharSequence spokenName = title.getText() + " (" + rawName.getText() + ")";
                title.setLabelFor(toggle.getId());
                title.setFocusable(false);
                title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                rawName.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                toggle.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override public void onInitializeAccessibilityNodeInfo(
                            View host, android.view.accessibility.AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        // The name goes in the node's text, not in a content description: a
                        // description replaces what the Switch reports about itself, and the
                        // checked state and the class it announces are the point of using one.
                        info.setText(spokenName);
                    }
                });
                fieldRoot.addView(toggle, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        FeatureGateLabUi.dp(root.getContext(), 48)
                ));
                objectEditors.add(ObjectFieldEditor.toggle(fieldName, kind, toggle, value));
            } else {
                EditText input = new EditText(root.getContext());
                input.setEnabled(editable);
                input.setText(editorText(value, kind));
                // Three calls rather than one with a comparison inside it. "JSON" is a
                // format name, and a literal inside an L10n call is read as a key by the gate
                // that pairs a key with its translation; a hint behind a variable is read by
                // nothing at all.
                String hint;
                if (kind.startsWith("LIST_")) {
                    hint = L10n.t(root.getContext(), "One value per line");
                } else if ("JSON".equals(kind)) {
                    hint = L10n.t(root.getContext(), "Advanced JSON value");
                } else {
                    hint = L10n.t(root.getContext(), "New value");
                }
                input.setHint(hint);
                input.setSingleLine(!kind.startsWith("LIST_") && !"JSON".equals(kind));
                input.setInputType(inputTypeFor(kind));
                input.setMinimumHeight(FeatureGateLabUi.dp(
                        root.getContext(),
                        kind.startsWith("LIST_") || "JSON".equals(kind) ? 96 : 48
                ));
                SettingsUi.styleEditText(input);
                SettingsUi.labelEditor(
                        title,
                        input,
                        title.getText() + " (" + rawName.getText() + ")"
                );
                rawName.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams inputParams = FeatureGateLabUi.matchWrap();
                inputParams.topMargin = FeatureGateLabUi.dp(root.getContext(), 8);
                fieldRoot.addView(input, inputParams);
                objectEditors.add(ObjectFieldEditor.input(fieldName, kind, input, value));
            }
            root.addView(fieldRoot, fieldParams);
        }
    }

    private JSONObject structuredBaseValue() {
        boolean rootArray = StructuredConfigController.isRootArray(entry.requestedClass);
        JSONObject result = parseStructuredValue(
                entry.loaded ? entry.currentValue : null,
                rootArray
        );
        if (result == null && !entry.defaults.isEmpty()) {
            result = parseStructuredValue(entry.defaults.get(0), rootArray);
        }
        if (result == null) {
            result = new JSONObject();
        }
        // A root array is wrapped in one $value field, even when it contains many entries.
        // The small-object fallback is for unavailable configuration metadata, not arrays.
        if (!rootArray && result.length() <= 2) {
            JSONObject generatedDefault = StructuredConfigController.defaultValue(entry.requestedClass);
            if (generatedDefault != null) {
                result = generatedDefault;
            }
        }
        if (rule != null) {
            JSONObject selected = parseObject(rule.value);
            if (selected != null) {
                java.util.Iterator<String> names = selected.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    try {
                        result.put(name, selected.opt(name));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return result;
    }

    private static JSONObject parseStructuredValue(String text, boolean rootArray) {
        JSONObject object = parseObject(text);
        if (object != null || !rootArray || text == null || text.isEmpty()) {
            return object;
        }
        try {
            JSONObject result = new JSONObject();
            result.put("$value", new JSONArray(text));
            return result;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String objectPatchText() {
        JSONObject result = new JSONObject();
        try {
            for (ObjectFieldEditor editor : objectEditors) {
                result.put(editor.name, editor.value());
            }
        } catch (FieldValueException failure) {
            String message = FeatureGateLabText.fieldValidation(
                    getContext(), failure.fieldName, failure.validation);
            for (ObjectFieldEditor editor : objectEditors) {
                if (editor.name.equals(failure.fieldName) && editor.input != null) {
                    SettingsUi.reportFieldError(editor.input, message);
                    return null;
                }
            }
            Utils.showToastLong(message);
            return null;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not collect Feature Gate field values", failure);
            Utils.showToastLong(L10n.t(getContext(),
                    "A field value couldn't be read. Check the values and try again."));
            return null;
        }
        return result.toString();
    }

    private static JSONObject parseObject(String text) {
        if (text == null || text.isEmpty() || "null".equals(text)) {
            return null;
        }
        try {
            return new JSONObject(text);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String editorText(Object value, String kind) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (!kind.startsWith("LIST_") || !(value instanceof JSONArray)) {
            if ("JSON".equals(kind) && value instanceof JSONObject) {
                return value.toString();
            }
            if ("JSON".equals(kind) && value instanceof JSONArray) {
                return value.toString();
            }
            return String.valueOf(value);
        }
        JSONArray array = (JSONArray) value;
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < array.length(); index++) {
            if (result.length() > 0) result.append('\n');
            result.append(String.valueOf(array.opt(index)));
        }
        return result.toString();
    }

    private static int inputTypeFor(String kind) {
        if ("INT".equals(kind) || "LONG".equals(kind)) {
            return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        }
        if ("FLOAT".equals(kind) || "DOUBLE".equals(kind)) {
            return InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_SIGNED
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        if (kind.startsWith("LIST_") || "JSON".equals(kind)) {
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        }
        return InputType.TYPE_CLASS_TEXT;
    }

    private static String titleForField(String fieldName) {
        if ("$value".equals(fieldName)) {
            return "Value";
        }
        StringBuilder result = new StringBuilder();
        String normalized = fieldName.replace('-', '_').replace('.', '_');
        for (String word : normalized.split("_+")) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) result.append(word.substring(1));
        }
        return result.length() == 0 ? fieldName : result.toString();
    }

    private void addTechnicalDetails(LinearLayout root) {
        Context context = root.getContext();
        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = SettingsUi.sectionTitle(context, L10n.t(context, "Technical details"));
        title.setAllCaps(true);
        title.setLetterSpacing(0.1f);
        title.setTextColor(SettingsUi.textSecondary());
        title.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, FeatureGateLabUi.dp(context, 48), 1f));
        technicalToggle = FeatureGateLabUi.text(context, L10n.t(context, "Show"), 14,
                SettingsUi.accent(), Typeface.BOLD);
        technicalToggle.setTag("feature_gate_technical_toggle");
        SettingsUi.styleTextAction(technicalToggle, true);
        technicalToggle.setGravity(Gravity.CENTER);
        technicalToggle.setPadding(
                FeatureGateLabUi.dp(context, 12),
                0,
                FeatureGateLabUi.dp(context, 12),
                0
        );
        technicalToggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(technicalToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                FeatureGateLabUi.dp(context, 48)
        ));
        SettingsUi.markAsButton(heading);
        heading.setFocusable(true);
        // A button with no background showed nothing when pressed or focused.
        heading.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_CONTROL,
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            heading.setContentDescription(L10n.t(context, "Technical details"));
            heading.setStateDescription(L10n.t(context, "collapsed"));
        } else {
            heading.setContentDescription(
                    L10n.f(context, "Technical details, %1$s", L10n.t(context, "collapsed")));
        }
        LinearLayout.LayoutParams headingParams = FeatureGateLabUi.matchWrap();
        headingParams.setMargins(0, FeatureGateLabUi.dp(context, 12), 0, 0);
        root.addView(heading, headingParams);

        technicalDetails = new LinearLayout(context);
        technicalDetails.setOrientation(LinearLayout.VERTICAL);
        technicalDetails.setVisibility(View.GONE);
        technicalDetails.setPadding(FeatureGateLabUi.dp(context, 16), 0,
                FeatureGateLabUi.dp(context, 16), 0);
        technicalDetails.setBackground(SettingsUi.borderedSurface(
                context, SettingsUi.RADIUS_SETTINGS_CARD, false));
        addInfo(technicalDetails, L10n.t(context, "Manager"), entry.manager);
        addInfo(technicalDetails, L10n.t(context, "Type"), entry.type);
        addInfo(technicalDetails, L10n.t(context, "Source"),
                FeatureGateLabText.sourceLabel(context, entry));
        addInfo(technicalDetails, L10n.t(context, "Generated defaults"),
                FeatureGateLabText.rawValues(context, entry.defaults));
        addInfo(technicalDetails, L10n.t(context, "Historical values"),
                FeatureGateLabText.rawValues(context, entry.historical));
        addInfo(technicalDetails, L10n.t(context, "Researched values"),
                FeatureGateLabText.rawValues(context, entry.researched));
        addInfo(technicalDetails, L10n.t(context, "Proof"), entry.proof);
        String original = FeatureGateLabRuntime.originalValue(entry.manager, entry.key, entry.type);
        if (original != null) {
            addInfo(technicalDetails, L10n.t(context, "Last original value"), original);
        }
        String caller = FeatureGateLabRuntime.firstCaller(entry.manager, entry.key, entry.type);
        if (caller != null) {
            addInfo(technicalDetails, L10n.t(context, "First caller"), caller);
        }
        root.addView(technicalDetails, FeatureGateLabUi.matchWrap());

        View.OnClickListener toggle = ignored -> {
            boolean show = technicalDetails.getVisibility() != View.VISIBLE;
            technicalDetails.setVisibility(show ? View.VISIBLE : View.GONE);
            technicalToggle.setText(L10n.t(getContext(), show ? "Hide" : "Show"));
            String state = L10n.t(getContext(), show ? "expanded" : "collapsed");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                heading.setContentDescription(
                        L10n.t(getContext(), "Technical details"));
                heading.setStateDescription(state);
            } else {
                heading.setContentDescription(
                        L10n.f(getContext(), "Technical details, %1$s", state));
            }
        };
        heading.setOnClickListener(toggle);
        technicalToggle.setOnClickListener(toggle);
    }

    private void addSectionTitle(LinearLayout root, String text) {
        TextView title = SettingsUi.sectionTitle(root.getContext(), text);
        title.setAllCaps(true);
        title.setLetterSpacing(0.1f);
        title.setTextColor(SettingsUi.textSecondary());
        LinearLayout.LayoutParams params = FeatureGateLabUi.matchWrap();
        params.setMargins(FeatureGateLabUi.dp(root.getContext(), 8),
                FeatureGateLabUi.dp(root.getContext(), 20), FeatureGateLabUi.dp(root.getContext(), 8),
                FeatureGateLabUi.dp(root.getContext(), 8));
        root.addView(title, params);
    }

    private TextView addInfo(LinearLayout root, String label, String value) {
        Context context = root.getContext();
        if (root.getChildCount() > 0) {
            View divider = new View(context);
            divider.setBackgroundColor(SettingsUi.divider());
            root.addView(divider, new LinearLayout.LayoutParams(-1, FeatureGateLabUi.dp(context, 1)));
        }
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(FeatureGateLabUi.dp(context, 60));
        row.setPadding(0, FeatureGateLabUi.dp(context, 16), 0, FeatureGateLabUi.dp(context, 16));
        TextView labelView = FeatureGateLabUi.body(context, label);
        labelView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(labelView, new LinearLayout.LayoutParams(0, -2, 1));
        String displayValue = value == null || value.isEmpty()
                ? L10n.t(context, "None recorded") : value;
        TextView valueView = FeatureGateLabUi.label(context, displayValue);
        valueView.setTextIsSelectable(true);
        valueView.setGravity(Gravity.END);
        valueView.setContentDescription(L10n.f(context, "%1$s: %2$s", label, displayValue));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, -2, 1);
        valueParams.setMarginStart(FeatureGateLabUi.dp(context, 16));
        row.addView(valueView, valueParams);
        root.addView(row, FeatureGateLabUi.matchWrap());
        return valueView;
    }

    private List<ValueOption> buildOptions(
            FeatureGateCatalog.Entry entry,
            FeatureGateLabStore.Rule rule
    ) {
        LinkedHashMap<String, List<FeatureGateLabText.ValueSource>> values =
                new LinkedHashMap<>();
        if (entry.loaded) addValue(values, entry.currentValue,
                FeatureGateLabText.ValueSource.CURRENT);
        for (String value : entry.defaults) addValue(values, value,
                FeatureGateLabText.ValueSource.DEFAULT);
        for (String value : entry.researched) addValue(values, value,
                FeatureGateLabText.ValueSource.RESEARCHED);
        for (String value : entry.historical) addValue(values, value,
                FeatureGateLabText.ValueSource.HISTORICAL);
        if (rule != null) addValue(values, rule.value,
                FeatureGateLabText.ValueSource.SELECTED);
        List<ValueOption> result = new ArrayList<>();
        for (Map.Entry<String, List<FeatureGateLabText.ValueSource>> item
                : values.entrySet()) {
            result.add(new ValueOption(
                    item.getKey(),
                    FeatureGateLabText.optionLabel(getContext(), item.getKey(), item.getValue()),
                    false
            ));
        }
        result.add(new ValueOption(null, L10n.t(getContext(), "Custom value…"), true));
        return result;
    }

    private static void addValue(
            Map<String, List<FeatureGateLabText.ValueSource>> values,
            String value,
            FeatureGateLabText.ValueSource source
    ) {
        if (value == null || "null".equals(value)) return;
        List<FeatureGateLabText.ValueSource> sources = values.get(value);
        if (sources == null) {
            sources = new ArrayList<>();
            values.put(value, sources);
        }
        if (!sources.contains(source)) sources.add(source);
    }

    private static int selectedIndex(List<ValueOption> options, String value) {
        for (int i = 0; i < options.size(); i++) {
            if (!options.get(i).custom && options.get(i).value.equals(value)) return i;
        }
        return 0;
    }

    private static String bestInitialValue(FeatureGateCatalog.Entry entry) {
        if (entry.loaded && entry.currentValue != null) return entry.currentValue;
        if (!entry.defaults.isEmpty()) return entry.defaults.get(0);
        if (!entry.researched.isEmpty()) return entry.researched.get(0);
        if (!entry.historical.isEmpty()) return entry.historical.get(0);
        if ("BOOLEAN".equals(entry.type)) return "false";
        return "";
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        String[] terms = {"auth", "login", "security", "risk", "compliance", "region", "age", "minor", "payment", "purchase", "wallet", "account", "ban"};
        for (String term : terms) if (lower.contains(term)) return true;
        return false;
    }

    private static final class FieldValueException extends IllegalArgumentException {
        final String fieldName;
        final FeatureGateLabStore.ValidationFailure validation;

        FieldValueException(
                String fieldName,
                FeatureGateLabStore.ValidationFailure validation
        ) {
            this.fieldName = fieldName;
            this.validation = validation;
        }
    }

    private static final class ScalarValueException extends IllegalArgumentException {
        final FeatureGateLabStore.ValidationFailure validation;

        ScalarValueException(FeatureGateLabStore.ValidationFailure validation) {
            this.validation = validation;
        }
    }

    private static final class FieldSnapshot {
        final ObjectFieldEditor editor;
        final long revision;
        final String text;
        final boolean checked;

        FieldSnapshot(ObjectFieldEditor editor) {
            this.editor = editor;
            revision = editor.revision;
            text = editor.input == null ? null : editor.input.getText().toString();
            checked = editor.toggle != null && editor.toggle.isChecked();
        }
    }

    private static final class ObjectFieldEditor {
        final String name;
        final String kind;
        final Switch toggle;
        final EditText input;
        private String savedText;
        private boolean savedChecked;
        private Object savedValue;
        private boolean edited;
        private long revision;

        private ObjectFieldEditor(String name, String kind, Switch toggle, EditText input, Object value) {
            this.name = name;
            this.kind = kind;
            this.toggle = toggle;
            this.input = input;
            this.savedText = input == null ? null : input.getText().toString();
            this.savedChecked = toggle != null && toggle.isChecked();
            this.savedValue = value == null ? JSONObject.NULL : value;
        }

        void changed() {
            edited = true;
            revision++;
        }

        /** Back to the latest committed value, including its original null representation. */
        void restore() {
            if (toggle != null) toggle.setChecked(savedChecked);
            if (input != null) input.setText(savedText);
            edited = false;
            revision++;
        }

        void setEditable(boolean editable) {
            if (toggle != null) toggle.setEnabled(editable);
            if (input != null) input.setEnabled(editable);
        }

        static ObjectFieldEditor toggle(String name, String kind, Switch toggle, Object value) {
            return new ObjectFieldEditor(name, kind, toggle, null, value);
        }

        static ObjectFieldEditor input(String name, String kind, EditText input, Object value) {
            return new ObjectFieldEditor(name, kind, null, input, value);
        }

        Object value() {
            if (!edited) return savedValue;
            try {
                if (toggle != null) {
                    return toggle.isChecked();
                }
                String text = input == null ? "" : input.getText().toString();
                if (kind.startsWith("LIST_")) {
                    String elementKind = kind.substring("LIST_".length());
                    JSONArray result = new JSONArray();
                    for (String line : text.split("\\r?\\n")) {
                        String item = line.trim();
                        if (!item.isEmpty()) {
                            result.put(scalarValue(elementKind, item));
                        }
                    }
                    return result;
                }
                if ("JSON".equals(kind)) {
                    String trimmed = text.trim();
                    try {
                        if (trimmed.startsWith("{")) {
                            return new JSONObject(trimmed);
                        }
                        if (trimmed.startsWith("[")) {
                            return new JSONArray(trimmed);
                        }
                    } catch (Throwable throwable) {
                        throw problem(FeatureGateLabStore.ValidationCode.INVALID_JSON, kind);
                    }
                    throw problem(
                            FeatureGateLabStore.ValidationCode.EXPECTED_JSON_OBJECT_OR_ARRAY,
                            kind
                    );
                }
                return scalarValue(kind, text.trim());
            } catch (ScalarValueException failure) {
                throw new FieldValueException(name, failure.validation);
            }
        }

        private static Object scalarValue(String kind, String text) {
            try {
                switch (kind) {
                    case "BOOLEAN":
                        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                            throw problem(
                                    FeatureGateLabStore.ValidationCode.EXPECTED_TRUE_OR_FALSE,
                                    kind
                            );
                        }
                        return Boolean.valueOf(text);
                    case "INT":
                        return Integer.valueOf(text);
                    case "LONG":
                        return Long.valueOf(text);
                    case "FLOAT": {
                        float value = Float.parseFloat(text);
                        if (!Float.isFinite(value)) {
                            throw problem(
                                    FeatureGateLabStore.ValidationCode.VALUE_MUST_BE_FINITE,
                                    kind
                            );
                        }
                        return value;
                    }
                    case "DOUBLE": {
                        double value = Double.parseDouble(text);
                        if (!Double.isFinite(value)) {
                            throw problem(
                                    FeatureGateLabStore.ValidationCode.VALUE_MUST_BE_FINITE,
                                    kind
                            );
                        }
                        return value;
                    }
                    default:
                        return text;
                }
            } catch (NumberFormatException failure) {
                throw problem(FeatureGateLabStore.ValidationCode.INVALID_NUMBER, kind);
            }
        }

        private static ScalarValueException problem(
                FeatureGateLabStore.ValidationCode code,
                String technicalType
        ) {
            return new ScalarValueException(
                    FeatureGateLabStore.ValidationFailure.of(code, technicalType));
        }
    }

    private static final class ValueOption {
        final String value;
        final String label;
        final boolean custom;

        ValueOption(String value, String label, boolean custom) {
            this.value = value;
            this.label = label;
            this.custom = custom;
        }
    }
}
