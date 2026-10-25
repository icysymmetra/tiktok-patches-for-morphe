package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ListView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/** Recovery when Android or an injected settings row throws during synchronization. */
@RunWith(RobolectricTestRunner.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Config(sdk = {23, 35}, qualifiers = "de-night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class PreferenceFailureRecoveryTest {
    private static final String PARTIAL = "settings_failure_partial";
    private static final String ERROR = "morphe_settings_error_message";
    private static final String BACK = "morphe_settings_error_back";
    private static final String RETRY = "morphe_settings_error_retry";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private enum Stage { NONE, INITIALIZE, INITIAL_SYNC, CHANGE_SYNC, UPDATE, AVAILABILITY }

    public static final class HarnessFragment extends TikTokPreferenceFragment {
        private static Stage stage = Stage.NONE;
        private static int failures;
        private static boolean failureWasOnMainThread;
        private static boolean failRecoveryVerification;
        private static boolean observeRecoveryGuard;
        private static boolean recoveryGuardStayedRaised;
        private boolean creationFinished;

        static void failOnceAt(Stage next) {
            stage = next;
            failures = 1;
            failureWasOnMainThread = false;
            failRecoveryVerification = false;
            observeRecoveryGuard = false;
            recoveryGuardStayedRaised = false;
        }

        static void observeRecoveryGuard() {
            observeRecoveryGuard = true;
            recoveryGuardStayedRaised = false;
        }

        private static void fail(Stage current) {
            if (stage == current && failures > 0) {
                failures--;
                failureWasOnMainThread = Looper.getMainLooper().isCurrentThread();
                throw new IllegalStateException("injected " + current);
            }
        }

        @Override public void onCreate(Bundle state) {
            super.onCreate(state);
            creationFinished = true;
        }

        @Override protected void initialize() {
            Activity activity = getActivity();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            setPreferenceScreen(screen);

            Preference partial = new Preference(activity);
            partial.setKey(PARTIAL);
            partial.setTitle("Partial settings row");
            screen.addPreference(partial);
            fail(Stage.INITIALIZE);

            SwitchPreference toggle = new SwitchPreference(activity);
            toggle.setKey(BaseSettings.DEBUG.key);
            toggle.setTitle("Debug logging");
            screen.addPreference(toggle);

            SwitchPreference restartToggle = new SwitchPreference(activity);
            restartToggle.setKey(BaseSettings.CAPTURE_JAVA_CRASHES.key);
            restartToggle.setTitle("Capture Java crashes");
            screen.addPreference(restartToggle);
        }

        @Override protected void updateUIToSettingValues() {
            if (!creationFinished) fail(Stage.INITIAL_SYNC);
            super.updateUIToSettingValues();
        }

        @Override protected void syncPreferenceWithStoredValue(
                Preference preference, Setting<?> setting,
                android.content.SharedPreferences preferences) {
            if (creationFinished) fail(Stage.CHANGE_SYNC);
            super.syncPreferenceWithStoredValue(preference, setting, preferences);
        }

        @Override protected void syncSettingWithPreference(
                Preference preference, Setting setting, boolean applySettingToPreference) {
            if (creationFinished && !applySettingToPreference) fail(Stage.UPDATE);
            super.syncSettingWithPreference(preference, setting, applySettingToPreference);
            if (creationFinished && applySettingToPreference && observeRecoveryGuard) {
                recoveryGuardStayedRaised = ReflectionHelpers.getStaticField(
                        AbstractPreferenceFragment.class, "updatingPreference");
                observeRecoveryGuard = false;
            }
        }

        @Override protected void updateUIAvailability() {
            if (creationFinished) fail(Stage.AVAILABILITY);
            super.updateUIAvailability();
        }

        @Override protected boolean preferenceShowsSettingValue(
                Preference preference, Setting<?> setting) {
            if (failRecoveryVerification) {
                failRecoveryVerification = false;
                return false;
            }
            return super.preferenceShowsSettingValue(preference, setting);
        }
    }

    public static final class TestActivity extends Activity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Before public void resetState() {
        HarnessFragment.failOnceAt(Stage.NONE);
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.CAPTURE_JAVA_CRASHES.resetToDefault();
        Setting.preferences.preferences.edit()
                .remove(BaseSettings.DEBUG.key)
                .remove(BaseSettings.CAPTURE_JAVA_CRASHES.key)
                .commit();
        ReflectionHelpers.setStaticField(AbstractPreferenceFragment.class,
                "updatingPreference", false);
        ShadowToast.reset();
    }

    @After public void restoreState() {
        HarnessFragment.failOnceAt(Stage.NONE);
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.CAPTURE_JAVA_CRASHES.resetToDefault();
        Setting.preferences.preferences.edit()
                .remove(BaseSettings.DEBUG.key)
                .remove(BaseSettings.CAPTURE_JAVA_CRASHES.key)
                .commit();
        ReflectionHelpers.setStaticField(AbstractPreferenceFragment.class,
                "updatingPreference", false);
        ShadowToast.reset();
    }

    @Test public void initializeFailureReplacesPartialRowsAndRetryBuildsANewPage() throws Exception {
        HarnessFragment.failOnceAt(Stage.INITIALIZE);
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            HarnessFragment failed = attach(activity);

            assertErrorPage(failed);
            assertNull(failed.findPreference(PARTIAL));
            assertActionSemantics(failed, BACK);
            View retryRow = assertActionSemantics(failed, RETRY);

            assertTrue(retryRow.performClick());
            settle(activity);

            HarnessFragment rebuilt = current(activity);
            assertNotSame(failed, rebuilt);
            assertNull(rebuilt.findPreference(ERROR));
            assertNotNull(rebuilt.findPreference(BaseSettings.DEBUG.key));
        }
    }

    /**
     * The same page, rendered in English, because the README shows this one to everybody.
     *
     * <p>The capture used to be taken inside the case above, and this class runs under a German
     * locale so that the error page is proven translated. That made the one error state the
     * README shows a German screenshot on an English page. The translated render is still
     * asserted above; only the picture moved here.
     */
    @Test
    @Config(sdk = 35, qualifiers = "en-night")
    public void theErrorPageIsCapturedInEnglishForTheReadme() throws Exception {
        HarnessFragment.failOnceAt(Stage.INITIALIZE);
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            HarnessFragment failed = attach(activity);

            // Asserted before the capture, so the published picture cannot quietly become a
            // render of some other locale the way the German one did.
            Preference message = failed.findPreference(ERROR);
            assertNotNull(message);
            assertEquals("Settings couldn't open", String.valueOf(message.getTitle()));
            assertEquals("Try again, or go back to TikTok.",
                    String.valueOf(message.getSummary()));
            assertEquals("Retry", String.valueOf(failed.findPreference(RETRY).getTitle()));

            UiCapture.save(activity.getWindow().getDecorView(),
                    "settings-initialization-error.png");
        }
    }

    @Test public void initialSyncFailureShowsBackAndRetryAndBackLeavesSettings() {
        HarnessFragment.failOnceAt(Stage.INITIAL_SYNC);
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            HarnessFragment failed = attach(activity);

            assertErrorPage(failed);
            View backRow = assertActionSemantics(failed, BACK);
            assertTrue(backRow.performClick());
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void changeSyncFailureRestoresTheSavedValueAndClearsTheGuard() {
        assertListenerRecovery(Stage.CHANGE_SYNC);
    }

    @Test public void updateFailureRestoresTheSavedValueAndClearsTheGuard() {
        assertListenerRecovery(Stage.UPDATE);
    }

    @Test public void availabilityFailureKeepsTheSavedValueAndClearsTheGuard() {
        assertListenerRecovery(Stage.AVAILABILITY);
    }

    @Test public void failedRecoveryVerificationShowsOneLocalizedFailure() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            HarnessFragment page = attach(activity);
            SwitchPreference toggle = (SwitchPreference) page.findPreference(BaseSettings.DEBUG.key);

            HarnessFragment.failOnceAt(Stage.CHANGE_SYNC);
            HarnessFragment.failRecoveryVerification = true;
            ShadowToast.reset();
            toggle.setChecked(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(BaseSettings.DEBUG.get(), toggle.isChecked());
            assertEquals(BaseSettings.DEBUG.get(), Setting.preferences.preferences.getBoolean(
                    BaseSettings.DEBUG.key, BaseSettings.DEBUG.defaultValue));
            assertFalse(ReflectionHelpers.getStaticField(
                    AbstractPreferenceFragment.class, "updatingPreference"));
            assertEquals(1, ShadowToast.shownToastCount());
            assertEquals("Die Einstellungen konnten nicht vollständig aktualisiert werden. "
                            + "Öffne die Einstellungen erneut und versuche es noch einmal.",
                    String.valueOf(ShadowToast.getTextOfLatestToast()));
        }
    }

    @Test public void aNestedRecoveryCallbackCannotReleaseTheOuterUpdateGuard() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            HarnessFragment page = attach(activity);
            SwitchPreference toggle = (SwitchPreference) page.findPreference(BaseSettings.DEBUG.key);

            HarnessFragment.failOnceAt(Stage.CHANGE_SYNC);
            HarnessFragment.observeRecoveryGuard();
            toggle.setChecked(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("a nested callback released the recovery guard",
                    HarnessFragment.recoveryGuardStayedRaised);
            assertFalse(ReflectionHelpers.getStaticField(
                    AbstractPreferenceFragment.class, "updatingPreference"));
            assertEquals(BaseSettings.DEBUG.get(), toggle.isChecked());
            assertFalse(Setting.preferences.preferences.contains(BaseSettings.DEBUG.key));
            assertEquals(1, ShadowToast.shownToastCount());
        }
    }

    @Test public void aFailedRestartSettingShowsOnlyTheRecoveryOutcome() {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            HarnessFragment page = attach(activity);
            SwitchPreference toggle = (SwitchPreference) page.findPreference(
                    BaseSettings.CAPTURE_JAVA_CRASHES.key);

            HarnessFragment.failOnceAt(Stage.UPDATE);
            ShadowToast.reset();
            toggle.setChecked(true);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertFalse(toggle.isChecked());
            assertFalse(BaseSettings.CAPTURE_JAVA_CRASHES.get());
            assertFalse(Setting.preferences.preferences.contains(
                    BaseSettings.CAPTURE_JAVA_CRASHES.key));
            assertEquals(1, ShadowToast.shownToastCount());
            assertEquals("Die Einstellung konnte nicht vollständig aktualisiert werden. "
                            + "Der gespeicherte Wert wird angezeigt.",
                    String.valueOf(ShadowToast.getTextOfLatestToast()));
        }
    }

    private static void assertListenerRecovery(Stage stage) {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            HarnessFragment page = attach(activity);
            // The fragment resolves its store from the activity; the Setting reads a static
            // captured when its class loaded in this sandbox. Robolectric gives every method a
            // fresh preference cache, so the two are one object only while the rule re-points
            // the static one, and a recovery that writes to one and reads the other is the
            // flake this class used to have.
            assertSame("Setting and fragment stores differ", Setting.preferences.preferences,
                    page.getPreferenceManager().getSharedPreferences());
            SwitchPreference toggle = (SwitchPreference) page.findPreference(BaseSettings.DEBUG.key);
            assertNotNull(toggle);
            assertFalse(BaseSettings.DEBUG.get());

            HarnessFragment.failOnceAt(stage);
            ShadowToast.reset();
            // Every write to the key in order, so a failure says which step dropped the value
            // rather than only that it is gone by the end.
            List<String> writes = new ArrayList<>();
            SharedPreferences.OnSharedPreferenceChangeListener watcher = (preferences, key) -> {
                if (!BaseSettings.DEBUG.key.equals(key)) return;
                writes.add(preferences.contains(key)
                        ? String.valueOf(preferences.getBoolean(key, false)) : "absent");
            };
            Setting.preferences.preferences
                    .registerOnSharedPreferenceChangeListener(watcher);
            try {
                toggle.setChecked(true);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            } finally {
                Setting.preferences.preferences
                        .unregisterOnSharedPreferenceChangeListener(watcher);
            }

            boolean settingValue = BaseSettings.DEBUG.get();
            boolean storedValue = Setting.preferences.preferences.getBoolean(
                    BaseSettings.DEBUG.key, BaseSettings.DEBUG.defaultValue);
            String state = state(stage, writes);
            assertEquals("the row does not show the value the Setting holds. " + state,
                    settingValue, toggle.isChecked());
            assertEquals("the saved preference does not hold the value the Setting does. " + state,
                    settingValue, storedValue);
            assertEquals("a value that is not the default is not written down, or a value that"
                            + " is the default was left behind. " + state,
                    !BaseSettings.DEBUG.defaultValue.equals(settingValue),
                    Setting.preferences.preferences.contains(BaseSettings.DEBUG.key));
            assertFalse("the update guard was left raised. " + state,
                    ReflectionHelpers.getStaticField(
                            AbstractPreferenceFragment.class, "updatingPreference"));
            assertTrue("preference recovery did not run on Android's main thread",
                    HarnessFragment.failureWasOnMainThread);
            assertEquals(1, ShadowToast.shownToastCount());
            assertEquals("Die Einstellung konnte nicht vollständig aktualisiert werden. "
                            + "Der gespeicherte Wert wird angezeigt.",
                    String.valueOf(ShadowToast.getTextOfLatestToast()));
        }
    }

    /** What the recovery path left behind, for a failure that only shows up in a full run. */
    private static String state(Stage stage, List<String> writes) {
        return "stage " + stage
                + ", setting " + BaseSettings.DEBUG.get()
                + ", stored " + (Setting.preferences.preferences.contains(BaseSettings.DEBUG.key)
                        ? Setting.preferences.preferences.getBoolean(BaseSettings.DEBUG.key, false)
                        : "absent")
                + ", writes " + writes
                + ", recovery ran on the main thread " + HarnessFragment.failureWasOnMainThread
                + ", import in progress " + AbstractPreferenceFragment.settingImportInProgress
                + ", guard " + ReflectionHelpers.getStaticField(
                        AbstractPreferenceFragment.class, "updatingPreference")
                + ", toasts " + ShadowToast.shownToastCount()
                + " (" + ShadowToast.getTextOfLatestToast() + ")";
    }

    private static void assertErrorPage(HarnessFragment fragment) {
        Preference message = fragment.findPreference(ERROR);
        Preference back = fragment.findPreference(BACK);
        Preference retry = fragment.findPreference(RETRY);
        assertNotNull(message);
        assertNotNull(back);
        assertNotNull(retry);
        assertFalse(message.isSelectable());
        assertTrue(back.isSelectable());
        assertTrue(retry.isSelectable());
        assertEquals("Die Einstellungen konnten nicht geöffnet werden",
                String.valueOf(message.getTitle()));
        assertEquals("Versuche es erneut oder kehre zu TikTok zurück.",
                String.valueOf(message.getSummary()));
        assertEquals("Zurück", String.valueOf(back.getTitle()));
        assertEquals("Erneut versuchen", String.valueOf(retry.getTitle()));
        assertNotNull(back.getOnPreferenceClickListener());
        assertNotNull(retry.getOnPreferenceClickListener());
    }

    private static View assertActionSemantics(HarnessFragment fragment, String key) {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        for (int position = 0; position < list.getAdapter().getCount(); position++) {
            Object item = list.getAdapter().getItem(position);
            if (!(item instanceof Preference) || !key.equals(((Preference) item).getKey())) continue;
            list.setSelection(position);
            settle(fragment.getActivity());
            View row = list.getChildAt(position - list.getFirstVisiblePosition());
            assertNotNull(row);
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            row.onInitializeAccessibilityNodeInfo(info);
            assertEquals(Button.class.getName(), String.valueOf(info.getClassName()));
            assertTrue(info.isClickable());
            assertNull(row.findViewWithTag("hushfeed_chevron"));
            info.recycle();
            return row;
        }
        throw new AssertionError("No rendered action row for " + key);
    }

    private static HarnessFragment attach(Activity activity) {
        HarnessFragment fragment = new HarnessFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        settle(activity);
        return current(activity);
    }

    private static HarnessFragment current(Activity activity) {
        return (HarnessFragment) activity.getFragmentManager()
                .findFragmentById(android.R.id.content);
    }

    private static void settle(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.view.View decor = activity.getWindow().getDecorView();
        decor.measure(android.view.View.MeasureSpec.makeMeasureSpec(480,
                        android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(960,
                        android.view.View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
