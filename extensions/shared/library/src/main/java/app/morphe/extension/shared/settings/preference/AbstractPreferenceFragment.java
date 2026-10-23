/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.util.Pair;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("deprecation")
public abstract class AbstractPreferenceFragment extends PreferenceFragment {

    /**
     * The recovery page's rows, public so an app that draws its own rows over the platform's can
     * tell the message and the two actions apart from an ordinary setting after it restyles them.
     */
    public static final String INITIALIZATION_ERROR_KEY = "morphe_settings_error_message";
    public static final String INITIALIZATION_BACK_KEY = "morphe_settings_error_back";
    public static final String INITIALIZATION_RETRY_KEY = "morphe_settings_error_retry";

    /** A tap acts immediately, and assistive technology should hear the same Button role. */
    private static final class ErrorActionPreference extends Preference implements ImmediateAction {
        /** The one the reader is meant to take, so the page can draw it as the way forward. */
        private final boolean primary;
        private final ErrorActionStyler styler;

        ErrorActionPreference(Context context, boolean primary, ErrorActionStyler styler) {
            super(context);
            this.primary = primary;
            this.styler = styler;
        }

        @Override public boolean actsOnTap() {
            return true;
        }

        @Override protected void onBindView(View view) {
            super.onBindView(view);
            view.setOnClickListener(ignored -> {
                OnPreferenceClickListener click = getOnPreferenceClickListener();
                if (click != null) click.onPreferenceClick(this);
            });
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(
                        View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    // The class name alone leaves a screen reader with a button it will not
                    // offer to press, which on the one screen whose whole purpose is a way out
                    // is the worst place for it.
                    info.setEnabled(host.isEnabled());
                    info.setClickable(true);
                    if (host.isEnabled()) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    }
                }
            });
            if (styler != null) styler.style(view, primary);
        }
    }

    /**
     * Lets the app decide how the recovery page's two actions look.
     *
     * <p>The shared library has no palette of its own, and both actions were drawn identically,
     * so Try again and Go back read as two rows of a list rather than as a way forward and a way
     * out.
     */
    public interface ErrorActionStyler {
        void style(View row, boolean primary);
    }

    /** Overridden by an app that wants its accent on the recovery action. Plain by default. */
    protected ErrorActionStyler errorActionStyler() {
        return null;
    }

    /**
     * Indicates that if a preference changes,
     * to apply the change from the Setting to the UI component.
     */
    public static volatile boolean settingImportInProgress;

    /**
     * Prevents recursive calls during preference <-> UI syncing from showing extra dialogs.
     */
    private static boolean updatingPreference;

    /**
     * The keys of settings changed in this process that only take effect after a restart.
     *
     * <p>A toast said so once and vanished, and nothing afterwards said a restart was still
     * owed, so a switch that "did nothing" was reported as broken. Process-wide, and cleared
     * by the process ending, which is the only thing that clears the debt.
     */
    public static final Set<String> restartPending =
            Collections.synchronizedSet(new LinkedHashSet<>());

    /**
     * The value each restart-gated setting had when this process last read it: what the app is
     * actually running with, whatever the store says now.
     */
    private static final Map<String, Object> runningValues =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * Records a change that waits on a restart, or clears one, then gives the page a chance to
     * say so.
     *
     * <p>A switch flipped on and back off owes nothing: the process already runs the value the
     * store holds. The value before the first change to a key is the running one, and a later
     * change that lands back on it takes the key out of the debt.
     *
     * @param valueBefore the setting's value before this change was applied.
     */
    protected boolean noteRestartPending(Setting<?> setting, Object valueBefore) {
        if (setting == null || !setting.rebootApp) return false;
        Object running;
        synchronized (runningValues) {
            if (!runningValues.containsKey(setting.key)) runningValues.put(setting.key, valueBefore);
            running = runningValues.get(setting.key);
        }
        boolean pending = !Objects.equals(running, setting.savedValue());
        if (pending) restartPending.add(setting.key);
        else restartPending.remove(setting.key);
        onRestartPendingChanged();
        return pending;
    }

    /** The page's chance to show that a restart is owed. */
    protected void onRestartPendingChanged() {
    }

    /**
     * Used to prevent showing reboot dialog, if user cancels a setting user dialog.
     */
    private static boolean showingUserDialogMessage;

    /**
     * Confirmation dialog title.
     * Set by subclasses if Strings cannot be added as a resource.
     */
    @Nullable
    protected static CharSequence confirmDialogTitle;

    private boolean listenerRegistered;
    private boolean destroyed;
    private boolean retryScheduled;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, key) ->
            Utils.runOnMainThreadNowOrLater(() -> onPreferenceChanged(sharedPreferences, key));

    private void onPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        // A DialogPreference's positive-button click and its dialog close are two posted
        // Handler messages, and the close (which persists the value) can run after this
        // fragment is detached but before onDestroy's own posted teardown unregisters this
        // listener. That persisted value must still reach the Setting here, so only the
        // destroyed flag gates this sync; isAdded() guards just the branches below that draw
        // UI on top of an activity this fragment may no longer be attached to.
        if (destroyed) return;
        if (updatingPreference) {
            Logger.printDebug(() -> "Ignoring preference change as sync is in progress");
            return;
        }

        Setting<?> setting = null;
        Preference pref = null;
        try {
            setting = Setting.getSettingFromPath(Objects.requireNonNull(key));
            if (setting == null) {
                return;
            }
            pref = findPreference(key);
            if (pref == null) {
                return;
            }
            Logger.printDebug(() -> "Preference changed: " + key);
            // Read before the Setting takes the new value: this is what the process runs with.
            Object valueBefore = setting.savedValue();

            updatingPreference = true;
            if (!settingImportInProgress) {
                // Another live page may own the change. Its persisted value is authoritative;
                // reading this page's older control would overwrite it, including removing defaults.
                syncPreferenceWithStoredValue(pref, setting, sharedPreferences);
            }

            boolean showRestartAfterUpdate = false;
            if (!settingImportInProgress && !showingUserDialogMessage) {
                // Confirmation is a dialog on this fragment's activity: skip it, rather than
                // crash reaching for a Context this fragment no longer has, when detached.
                if (isAdded() && setting.userDialogMessage != null && !prefIsSetToDefault(pref, setting)) {
                    // Do not change the setting yet, to allow preserving whatever
                    // list/text value was previously set if it needs to be reverted.
                    showSettingUserDialogConfirmation(pref, setting);
                    return;
                }
                showRestartAfterUpdate = setting.rebootApp;
            }

            // Apply 'Setting <- Preference', unless importing already updated the Setting.
            updatePreference(pref, setting, true, settingImportInProgress);
            // Update any other preference availability that may now be different.
            updateUIAvailability();
            // Report success only after every operation that can still enter recovery succeeded.
            if (showRestartAfterUpdate) {
                // The debt itself is recorded either way; only showing it is UI that needs
                // an attached activity.
                if (noteRestartPending(setting, valueBefore) && isAdded()) {
                    showRestartDialog(getContext());
                }
            }
        } catch (Exception ex) {
            // This path owns a localized outcome below, so logging must not add a second toast.
            Logger.printInfo(() -> "OnSharedPreferenceChangeListener failure", ex);
            boolean restored = pref != null && setting != null
                    && restorePreferenceFromSetting(pref, setting, sharedPreferences);
            Context context = getActivity();
            Utils.showToastLong(String.valueOf(restored
                    ? preferenceChangeRecoveredMessage(context)
                    : preferenceChangeRecoveryFailedMessage(context)));
        } finally {
            updatingPreference = false;
        }
    }

    /**
     * Restores both the visible row and its persisted preference from the typed Setting value.
     * The listener guard stays raised while a Preference setter writes the saved value back.
     */
    private boolean restorePreferenceFromSetting(@NonNull Preference pref,
                                                 @NonNull Setting<?> setting,
                                                 @NonNull SharedPreferences preferences) {
        updatingPreference = true;
        try {
            syncSettingWithPreference(pref, setting, true);
            if (!persistSettingValue(preferences, setting)) return false;
            updateUIAvailability();
            return preferenceShowsSettingValue(pref, setting)
                    && storedPreferenceMatchesSetting(preferences, setting);
        } catch (Exception restoreFailure) {
            Logger.printInfo(() -> "Preference recovery failure", restoreFailure);
            return false;
        }
    }

    /** Keeps the Setting contract that default values are represented by an absent preference. */
    private static boolean persistSettingValue(@NonNull SharedPreferences preferences,
                                               @NonNull Setting<?> setting) {
        Object value = setting.savedValue();
        SharedPreferences.Editor editor = preferences.edit();
        if (setting.defaultValue.equals(value)) {
            editor.remove(setting.key);
        } else if (value instanceof Boolean) {
            editor.putBoolean(setting.key, (Boolean) value);
        } else {
            editor.putString(setting.key, settingStringValue(value));
        }
        return editor.commit();
    }

    /** Lets app-specific Preference classes verify that recovery put the saved value on screen. */
    protected boolean preferenceShowsSettingValue(@NonNull Preference pref,
                                                  @NonNull Setting<?> setting) {
        Object value = setting.savedValue();
        if (pref instanceof SwitchPreference switchPref) {
            return value instanceof Boolean && switchPref.isChecked() == (Boolean) value;
        }
        String expected = settingStringValue(value);
        if (pref instanceof EditTextPreference editPreference) {
            return Objects.equals(expected, editPreference.getText());
        }
        if (pref instanceof ListPreference listPreference) {
            return Objects.equals(expected, listPreference.getValue());
        }
        return true;
    }

    private static boolean storedPreferenceMatchesSetting(@NonNull SharedPreferences preferences,
                                                          @NonNull Setting<?> setting) {
        Object expected = setting.savedValue();
        Object stored = preferences.getAll().get(setting.key);
        if (stored == null && !preferences.contains(setting.key)) stored = setting.defaultValue;
        if (expected instanceof Boolean) return Objects.equals(expected, stored);
        return Objects.equals(settingStringValue(expected), settingStringValue(stored));
    }

    private static String settingStringValue(Object value) {
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : String.valueOf(value);
    }

    /**
     * Initialize this instance, and do any custom behavior.
     * <p>
     * To ensure all {@link Setting} instances are correctly synced to the UI,
     * it is important that subclasses make a call or otherwise reference their Settings class bundle
     * so all app specific {@link Setting} instances are loaded before this method returns.
     */
    protected void initialize() {
        String preferenceResourceName;
        if (BaseSettings.SHOW_MENU_ICONS.savedValue()) {
            preferenceResourceName = Utils.appIsUsingBoldIcons()
                    ? "morphe_prefs_icons_bold"
                    : "morphe_prefs_icons";
        } else {
            preferenceResourceName = "morphe_prefs";
        }

        final var identifier = ResourceUtils.getIdentifier(ResourceType.XML, preferenceResourceName);
        if (identifier == 0) return;
        addPreferencesFromResource(identifier);

        PreferenceScreen screen = getPreferenceScreen();
        Utils.sortPreferenceGroups(screen);
        Utils.setPreferenceTitlesToMultiLineIfNeeded(screen);
    }

    private void showSettingUserDialogConfirmation(Preference pref, Setting<?> setting) {
        Utils.verifyOnMainThread();

        final var context = getContext();
        if (confirmDialogTitle == null) {
            confirmDialogTitle = str("morphe_settings_confirm_user_dialog_title");
        }

        showingUserDialogMessage = true;

        CharSequence message = BulletPointPreference.formatIntoBulletPoints(
                Objects.requireNonNull(setting.userDialogMessage).toString());

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                confirmDialogTitle, // Title.
                message,
                null, // No EditText.
                null, // OK button text.
                () -> {
                    // OK button action. User confirmed, save to the Setting.
                    Object valueBefore = setting.savedValue();
                    updatePreference(pref, setting, true, false);

                    // Update availability of other preferences that may be changed.
                    updateUIAvailability();

                    if (setting.rebootApp) {
                        if (noteRestartPending(setting, valueBefore)) {
                            showRestartDialog(context);
                        }
                    }
                },
                () -> {
                    // Cancel button action. Restore whatever the setting was before the change.
                    updatePreference(pref, setting, true, true);
                },
                null, // No Neutral button.
                null, // No Neutral button action.
                true  // Dismiss dialog when onNeutralClick.
        );

        dialogPair.first.setOnDismissListener(d -> showingUserDialogMessage = false);
        dialogPair.first.setCancelable(false);

        // Show the dialog.
        dialogPair.first.show();
    }

    /**
     * Updates all Preferences values and their availability using the current values in {@link Setting}.
     */
    protected void updateUIToSettingValues() {
        updatePreferenceScreen(getPreferenceScreen(), true, true);
    }

    /**
     * Updates Preferences availability only using the status of {@link Setting}.
     */
    protected void updateUIAvailability() {
        updatePreferenceScreen(getPreferenceScreen(), false, false);
    }

    /**
     * @return If the preference is currently set to the default value of the Setting.
     */
    protected boolean prefIsSetToDefault(Preference pref, Setting<?> setting) {
        Object defaultValue = setting.defaultValue;
        if (pref instanceof SwitchPreference switchPref) {
            return switchPref.isChecked() == (Boolean) defaultValue;
        }
        String defaultValueString = defaultValue.toString();
        if (pref instanceof EditTextPreference editPreference) {
            return editPreference.getText().equals(defaultValueString);
        }
        if (pref instanceof ListPreference listPref) {
            return listPref.getValue().equals(defaultValueString);
        }

        throw new IllegalStateException("Must override method to handle "
                + "preference type: " + pref.getClass());
    }

    /**
     * Syncs all UI Preferences to any {@link Setting} they represent.
     */
    private void updatePreferenceScreen(@NonNull PreferenceGroup group,
                                        boolean syncSettingValue,
                                        boolean applySettingToPreference) {
        // Alternatively this could iterate thru all Settings and check for any matching Preferences,
        // but there are many more Settings than UI preferences so it's more efficient to only check
        // the Preferences.
        for (int i = 0, prefCount = group.getPreferenceCount(); i < prefCount; i++) {
            Preference pref = group.getPreference(i);
            if (pref instanceof PreferenceGroup subGroup) {
                updatePreferenceScreen(subGroup, syncSettingValue, applySettingToPreference);
            } else if (pref.hasKey()) {
                String key = pref.getKey();
                Setting<?> setting = Setting.getSettingFromPath(key);

                if (setting != null) {
                    updatePreference(pref, setting, syncSettingValue, applySettingToPreference);
                } else if (BaseSettings.DEBUG.savedValue() && (pref instanceof SwitchPreference
                        || pref instanceof EditTextPreference || pref instanceof ListPreference)) {
                    // Probably a typo in the patches preference declaration.
                    Logger.printException(() -> "Preference key has no setting: " + key);
                }
            }
        }
    }

    /** Refreshes a receiving page before its control is used to update the Setting. */
    protected void syncPreferenceWithStoredValue(@NonNull Preference pref,
                                                @NonNull Setting<?> setting,
                                                @NonNull SharedPreferences preferences) {
        if (pref instanceof SwitchPreference switchPref) {
            switchPref.setChecked(preferences.getBoolean(setting.key, (Boolean) setting.defaultValue));
        } else if (pref instanceof EditTextPreference editPreference) {
            editPreference.setText(preferences.getString(setting.key, setting.defaultValue.toString()));
        } else if (pref instanceof ListPreference listPref) {
            listPref.setValue(preferences.getString(setting.key, setting.defaultValue.toString()));
        }
    }

    /**
     * Handles syncing a UI Preference with the {@link Setting} that backs it.
     * If needed, subclasses can override this to handle additional UI Preference types.
     *
     * @param applySettingToPreference If true, then apply {@link Setting} -> Preference.
     *                                 If false, then apply {@link Setting} <- Preference.
     */
    protected void syncSettingWithPreference(@NonNull Preference pref,
                                             @NonNull Setting<?> setting,
                                             boolean applySettingToPreference) {
        if (pref instanceof SwitchPreference switchPref) {
            BooleanSetting boolSetting = (BooleanSetting) setting;
            if (applySettingToPreference) {
                switchPref.setChecked(boolSetting.savedValue());
            } else {
                BooleanSetting.privateSetValue(boolSetting, switchPref.isChecked());
            }
        } else if (pref instanceof EditTextPreference editPreference) {
            if (applySettingToPreference) {
                editPreference.setText(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, editPreference.getText());
            }
        } else if (pref instanceof ListPreference listPref) {
            if (applySettingToPreference) {
                listPref.setValue(setting.savedValue().toString());
            } else {
                Setting.privateSetValueFromString(setting, listPref.getValue());
            }
            updateListPreferenceSummary(listPref, setting);
        } else if (!pref.getClass().equals(Preference.class)) {
            // Ignore root preference class because there is no data to sync.
            Logger.printException(() -> "Setting cannot be handled: " + pref.getClass() + ": " + pref);
        }
    }

    /**
     * Updates a UI Preference with the {@link Setting} that backs it.
     *
     * @param syncSetting If the UI should be synced {@link Setting} <-> Preference
     * @param applySettingToPreference If true, then apply {@link Setting} -> Preference.
     *                                 If false, then apply {@link Setting} <- Preference.
     */
    private void updatePreference(@NonNull Preference pref, @NonNull Setting<?> setting,
                                  boolean syncSetting, boolean applySettingToPreference) {
        if (!syncSetting && applySettingToPreference) {
            throw new IllegalArgumentException();
        }

        if (syncSetting) {
            syncSettingWithPreference(pref, setting, applySettingToPreference);
        }

        updatePreferenceAvailability(pref, setting);
    }

    protected void updatePreferenceAvailability(@NonNull Preference pref, @NonNull Setting<?> setting) {
        pref.setEnabled(setting.isAvailable());
    }

    protected void updateListPreferenceSummary(ListPreference listPreference, Setting<?> setting) {
        String objectStringValue = setting.savedValue().toString();
        final int entryIndex = listPreference.findIndexOfValue(objectStringValue);
        if (entryIndex >= 0) {
            listPreference.setSummary(listPreference.getEntries()[entryIndex]);
        } else {
            // Value is not an available option.
            // User manually edited import data, or options changed and current selection is no longer available.
            // Still show the value in the summary, so it's clear that something is selected.
            listPreference.setSummary(objectStringValue);
        }
    }

    /**
     * What a saved setting says, so a bundle with a translation table can set its own.
     *
     * <p>Static because the caller is: this is reached from the settings framework rather than
     * from an instance. Null means the English below, which is what a bundle that carries no
     * table gets.
     */
    protected static CharSequence savedMessage;

    /** Lets an app keep restart feedback inside its settings shell. */
    public interface RestartFeedbackPresenter {
        void present(Context context, CharSequence message);
    }

    private static volatile RestartFeedbackPresenter restartFeedbackPresenter;

    public static void setRestartFeedbackPresenter(RestartFeedbackPresenter presenter) {
        restartFeedbackPresenter = presenter;
    }

    protected CharSequence initializationErrorTitle(@Nullable Context context) {
        return "Settings couldn't open";
    }

    protected CharSequence initializationErrorSummary(@Nullable Context context) {
        return "Try again, or go back to TikTok.";
    }

    protected CharSequence initializationBackLabel(@Nullable Context context) {
        return "Back";
    }

    protected CharSequence initializationRetryLabel(@Nullable Context context) {
        return "Retry";
    }

    protected CharSequence preferenceChangeRecoveredMessage(@Nullable Context context) {
        return "The setting couldn't finish updating. Its saved value is shown.";
    }

    protected CharSequence preferenceChangeRecoveryFailedMessage(@Nullable Context context) {
        return "Settings couldn't refresh completely. Reopen settings and try again.";
    }

    public static void showRestartDialog(Context context) {
        Utils.verifyOnMainThread();
        CharSequence message = savedMessage == null
                ? "Saved. Restart TikTok to apply this change."
                : savedMessage;
        RestartFeedbackPresenter presenter = restartFeedbackPresenter;
        if (presenter != null) {
            presenter.present(context, message);
        } else {
            Utils.showToastLong(message.toString());
        }
    }

    @SuppressLint("ResourceType")
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        initializePreferencePage();
    }

    private void initializePreferencePage() {
        try {
            PreferenceManager preferenceManager = getPreferenceManager();
            preferenceManager.setSharedPreferencesName(Setting.preferences.name);

            // Must initialize before adding change listener,
            // otherwise the syncing of Setting -> UI
            // causes a callback to the listener even though nothing changed.
            initialize();
            updateUIToSettingValues();

            preferenceManager.getSharedPreferences().registerOnSharedPreferenceChangeListener(listener);
            listenerRegistered = true;
        } catch (Exception ex) {
            // The error page is the user-facing outcome; keep the diagnostic logger toast quiet.
            Logger.printInfo(() -> "onCreate() failure", ex);
            unregisterPreferenceListener();
            renderInitializationError();
        }
    }

    private void renderInitializationError() {
        Activity activity = getActivity();
        if (activity == null) {
            Logger.printException(() -> "Cannot render settings initialization error without an Activity");
            return;
        }

        try {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            setPreferenceScreen(screen);

            Preference message = new Preference(activity);
            message.setKey(INITIALIZATION_ERROR_KEY);
            message.setTitle(initializationErrorTitle(activity));
            message.setSummary(initializationErrorSummary(activity));
            message.setPersistent(false);
            message.setSelectable(false);
            screen.addPreference(message);

            ErrorActionStyler styler = errorActionStyler();

            // Try again leads. It is the one that can actually fix this, and it used to sit
            // underneath Go back, so the first thing offered to a reader whose settings would
            // not open was the way out rather than the way through.
            Preference retry = new ErrorActionPreference(activity, true, styler);
            retry.setKey(INITIALIZATION_RETRY_KEY);
            retry.setTitle(initializationRetryLabel(activity));
            retry.setPersistent(false);
            retry.setOnPreferenceClickListener(ignored -> {
                replaceFailedPage();
                return true;
            });
            screen.addPreference(retry);

            Preference back = new ErrorActionPreference(activity, false, styler);
            back.setKey(INITIALIZATION_BACK_KEY);
            back.setTitle(initializationBackLabel(activity));
            back.setPersistent(false);
            back.setOnPreferenceClickListener(ignored -> {
                leaveFailedPage();
                return true;
            });
            screen.addPreference(back);
        } catch (Exception renderFailure) {
            Logger.printException(() -> "Settings initialization error UI failure", renderFailure);
        }
    }

    private void leaveFailedPage() {
        Activity activity = getActivity();
        if (activity == null) return;
        if (activity.getFragmentManager().getBackStackEntryCount() > 0) {
            activity.getFragmentManager().popBackStack();
        } else {
            activity.finish();
        }
    }

    /** A new Fragment also rebuilds app-specific adapters and clears every partial subclass field. */
    private void replaceFailedPage() {
        if (retryScheduled) return;
        Activity activity = getActivity();
        int containerId = getId();
        if (activity == null || containerId == 0 || containerId == android.view.View.NO_ID) return;

        retryScheduled = true;
        try {
            Bundle arguments = getArguments() == null ? null : new Bundle(getArguments());
            Fragment replacement = Fragment.instantiate(activity, getClass().getName(), arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(containerId, replacement)
                    .commit();
        } catch (Exception retryFailure) {
            retryScheduled = false;
            Logger.printInfo(() -> "Settings retry failure", retryFailure);
            initializePreferencePage();
        }
    }

    private void unregisterPreferenceListener() {
        if (!listenerRegistered) return;
        try {
            // Setting.preferences.preferences, not getPreferenceManager().getSharedPreferences():
            // this can run after the fragment is torn down, by which point its own
            // PreferenceManager is no longer a safe thing to ask. It is the same
            // SharedPreferences instance the listener was registered on either way.
            Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
        } finally {
            listenerRegistered = false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Posted, not run here directly: a DialogPreference closing posts the message that
        // persists its value, and unregistering synchronously could win the race against it,
        // leaving the store holding a value the running Setting never learns about until the
        // app restarts. Posting queues this teardown behind whatever is already pending. It
        // comes after super, because super dismisses a dialog still showing
        // (PreferenceManager.dispatchActivityDestroy), and that dismiss is one of the messages
        // it has to queue behind.
        Utils.runOnMainThread(() -> {
            destroyed = true;
            unregisterPreferenceListener();
        });
    }
}
