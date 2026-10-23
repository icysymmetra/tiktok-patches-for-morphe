package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;

import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;
import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, qualifiers = "en-w480dp-h960dp-night-mdpi",
        shadows = AutoAdvanceLimitPreferenceTest.NativeBridges.class)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class AutoAdvanceLimitPreferenceTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private final Map<Setting<?>, Object> oldSettings = new LinkedHashMap<>();
    private SharedPreferences oldPreferences;
    private Field preferencesField;
    private boolean oldAutoStatus, oldSpeedStatus;
    private ActivityController<Activity> feedOwner;
    private View feed;
    private NativeComponent nativeComponent;

    @Before public void setUp() throws Exception {
        // The actual PreferenceManager and the static runtime holder must share this app's store.
        oldPreferences = Setting.preferences.preferences;
        preferencesField = SharedPrefCategory.class.getDeclaredField("preferences");
        preferencesField.setAccessible(true);
        preferencesField.set(Setting.preferences, RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.preferences.name, 0));
        for (Setting<?> setting : new Setting<?>[]{Settings.AUTO_ADVANCE,
                Settings.AUTO_ADVANCE_LIMIT, Settings.DEFAULT_SPEED_ENABLED,
                Settings.SESSION_BUDGET_VIDEOS, Settings.SESSION_BUDGET_MINUTES,
                Settings.SESSION_BUDGET_LOCK_MINUTES, Settings.SESSION_BUDGET_STATE}) {
            oldSettings.put(setting, setting.get());
        }
        oldAutoStatus = SettingsStatus.autoAdvanceEnabled;
        oldSpeedStatus = SettingsStatus.playbackSpeedEnabled;
        SettingsStatus.autoAdvanceEnabled = true;
        SettingsStatus.playbackSpeedEnabled = true;
        Setting.saveAll(Map.of(Settings.AUTO_ADVANCE, true, Settings.AUTO_ADVANCE_LIMIT, 2,
                Settings.DEFAULT_SPEED_ENABLED, false, Settings.SESSION_BUDGET_VIDEOS, 0,
                Settings.SESSION_BUDGET_MINUTES, 0, Settings.SESSION_BUDGET_LOCK_MINUTES, 0));
        SessionBudget.clear();

        feedOwner = Robolectric.buildActivity(Activity.class).setup();
        feed = new View(feedOwner.get());
        feedOwner.get().setContentView(feed);
        feedOwner.visible().windowFocusChanged(true);
        settle(feedOwner.get());
        assertTrue(feed.isAttachedToWindow());
        assertTrue(feed.isShown());
        assertTrue(feed.hasWindowFocus());
        nativeComponent = new NativeComponent();
        nativeComponent.onViewCreated(feed);
        idle();
        assertEquals("the real onView callback must call the native start bridge",
                1, nativeComponent.starts);
        assertTrue(control().owned);
    }

    @After public void tearDown() throws Exception {
        try {
            if (nativeComponent != null) nativeComponent.onDestroy();
            if (feedOwner != null) feedOwner.close();
            // Registration cleanup only. Completion, dedup and notice state are never assigned.
            SharedPreferences.OnSharedPreferenceChangeListener listener =
                    ReflectionHelpers.getStaticField(AutoAdvance.class, "PREFERENCES");
            Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
            ReflectionHelpers.setStaticField(AutoAdvance.class, "observing", false);
            SessionBudget.clear();
            Setting.saveAll(oldSettings);
            idle();
        } finally {
            SettingsStatus.autoAdvanceEnabled = oldAutoStatus;
            SettingsStatus.playbackSpeedEnabled = oldSpeedStatus;
            if (preferencesField != null) preferencesField.set(Setting.preferences, oldPreferences);
        }
    }

    @Test public void changingTheSessionLimitFromItsSettingsRowResetsTheRetainedFeedSession() {
        AutoAdvance.Control retained = control();
        complete("first");
        assertEquals(1, retained.completedCount);
        nativeComponent.bind("second");

        withPlaybackSettings(activity -> {
            saveLimit(activity, "2");
            clickPreference(activity, Settings.DEFAULT_SPEED_ENABLED.key);
            assertTrue(Settings.DEFAULT_SPEED_ENABLED.get());
            assertEquals("same-value saves and unrelated settings must preserve this session",
                    1, retained.completedCount);
        });
        assertSame(retained, control());
        assertEquals("returning from settings alone must not reset the count",
                1, retained.completedCount);

        withPlaybackSettings(activity -> {
            saveLimit(activity, "3");
            assertSame("the fixture must retain the existing feed control", retained, control());
            assertEquals("changing the actual limit row must start a fresh session",
                    0, retained.completedCount);
        });
        assertSame(retained, control());
        ShadowToast.reset();
        complete("second");
        nativeComponent.onPlayCompleted("second");
        nativeComponent.onPlayCompleted("first");
        assertEquals("duplicate and stale-source callbacks must not consume extra places",
                1, retained.completedCount);
        complete("third");
        assertEquals(2, retained.completedCount);
        assertTrue(retained.owned);
        assertNull(ShadowToast.getTextOfLatestToast());
        complete("fourth");
        assertEquals(3, retained.completedCount);
        assertFalse(retained.owned);
        assertEquals(1, nativeComponent.stops);
        idle();
        assertLimitNotice("Auto-advance stopped after 3 videos");

        View keepGoing = findBannerAction(
                feedOwner.get().findViewById(android.R.id.content), "Keep going");
        assertNotNull("the banner must carry a Keep going action", keepGoing);
        keepGoing.performClick();
        idle();
        assertEquals("Keep going must reset the count", 0, retained.completedCount);
        assertTrue("Keep going must restart advancing", retained.owned);

        // The stopped video can finish again in the new session. Neither its previous ID nor
        // the previous session's shown-notice flag may survive a real change of the limit.
        withPlaybackSettings(activity -> {
            saveLimit(activity, "2");
            assertSame(retained, control());
            assertEquals(0, retained.completedCount);
        });
        assertTrue(retained.owned);
        ShadowToast.reset();
        nativeComponent.onPlayCompleted("fourth");
        assertEquals("the new session must accept its current video's completion",
                1, retained.completedCount);
        assertNull(ShadowToast.getTextOfLatestToast());
        complete("fifth");
        assertEquals(2, retained.completedCount);
        assertFalse(retained.owned);
        assertEquals(2, nativeComponent.stops);
        idle();
        assertLimitNotice("Auto-advance stopped after 2 videos");
    }

    private void complete(String id) {
        nativeComponent.bind(id);
        nativeComponent.onPlayCompleted(id);
        idle();
    }

    private AutoAdvance.Control control() {
        Map<Object, AutoAdvance.Control> controls =
                ReflectionHelpers.getStaticField(AutoAdvance.class, "CONTROLS");
        AutoAdvance.Control result = controls.get(nativeComponent);
        assertNotNull("the actual onView callback did not retain its control", result);
        return result;
    }

    private void withPlaybackSettings(Consumer<SettingsActivity> edit) {
        nativeComponent.onPause();
        feedOwner.windowFocusChanged(false).pause().stop();
        Intent intent = new Intent("morphe_settings").putExtra("morphe", true)
                .putExtra("morphe_settings_section", "PLAYBACK");
        try (var owner = Robolectric.buildActivity(SettingsActivity.class, intent)
                .setup().visible()) {
            settle(owner.get());
            assertSame("the actual preference page must share the runtime store",
                    Setting.preferences.preferences,
                    page(owner.get()).getPreferenceManager().getSharedPreferences());
            edit.accept(owner.get());
            owner.get().onBackPressed();
            assertTrue(owner.get().isFinishing());
        } finally {
            feedOwner.restart().start().resume().visible().windowFocusChanged(true);
            Utils.setContext(feedOwner.get());
            nativeComponent.onResume();
            settle(feedOwner.get());
        }
    }

    private static void saveLimit(SettingsActivity activity, String value) {
        Preference pref = page(activity).findPreference(Settings.AUTO_ADVANCE_LIMIT.key);
        assertTrue(pref instanceof NumberInputPreference);
        NumberInputPreference row = (NumberInputPreference) pref;
        assertEquals("Auto-advance session limit", row.getTitle().toString());
        clickPreference(activity, row.getKey());
        AlertDialog dialog = (AlertDialog) row.getDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        row.getEditText().setText(value);
        assertEquals("Save", dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        settle(activity);
        assertFalse(dialog.isShowing());
        assertEquals(value, row.getValue());
        assertEquals(Integer.parseInt(value), (int) Settings.AUTO_ADVANCE_LIMIT.get());
        assertEquals(value, Setting.preferences.preferences.getString(Settings.AUTO_ADVANCE_LIMIT.key, "0"));
    }

    private static TikTokPreferenceFragment page(SettingsActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        ViewGroup background = (ViewGroup) content.getChildAt(0);
        ViewGroup container = (ViewGroup) background.getChildAt(0);
        var fragment = activity.getFragmentManager().findFragmentById(container.getId());
        assertTrue(fragment instanceof TikTokPreferenceFragment);
        TikTokPreferenceFragment page = (TikTokPreferenceFragment) fragment;
        assertEquals("PLAYBACK", page.getArguments().getString("morphe_settings_section"));
        return page;
    }

    private static void clickPreference(SettingsActivity activity, String key) {
        ListView list = page(activity).getView().findViewById(android.R.id.list);
        for (int position = 0; position < list.getCount(); position++) {
            Object item = list.getItemAtPosition(position);
            if (!(item instanceof Preference) || !key.equals(((Preference) item).getKey())) continue;
            list.setSelection(position);
            settle(activity);
            View row = list.getChildAt(position - list.getFirstVisiblePosition());
            assertNotNull("the actual preference row was not laid out", row);
            assertTrue(list.performItemClick(row, position, list.getItemIdAtPosition(position)));
            settle(activity);
            return;
        }
        fail("the actual Playback list has no " + key + " row");
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void settle(Activity activity) {
        activity.getFragmentManager().executePendingTransactions();
        idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        idle();
    }

    public static class SettingsActivity extends AdPersonalizationActivity {
        @Override public void onCreate(Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
            assertTrue(TikTokActivityHook.initialize(this));
        }

        @Override public void onBackPressed() {
            if (!TikTokActivityHook.handleBackPressed(this)) super.onBackPressed();
        }
    }

    enum NativeState { AUTO_SCROLL_STATE_START, AUTO_SCROLL_STATE_STOP, AUTO_SCROLL_STATE_PAUSE }

    public static final class FixtureAweme extends Aweme {
        private final String id;
        FixtureAweme(String id) { this.id = id; }
        @Override public String getAid() { return id; }
    }

    /** Only the native state/bridge contract is modeled; every extension callback is real. */
    public static final class NativeComponent {
        NativeState LLJJJIL = NativeState.AUTO_SCROLL_STATE_STOP;
        private Aweme current = new FixtureAweme("first");
        int starts, stops;
        void bind(String id) { current = new FixtureAweme(id); }
        public Aweme Tp() { return current; }
        public void V00(boolean first, boolean second) {
            assertFalse(first);
            assertFalse(second);
            starts++;
            LLJJJIL = NativeState.AUTO_SCROLL_STATE_START;
        }
        public void sq(String reason, boolean forced) {
            assertEquals("", reason);
            assertFalse(forced);
            stops++;
            LLJJJIL = NativeState.AUTO_SCROLL_STATE_STOP;
        }
        void onViewCreated(View view) { AutoAdvance.onView(this, view); }
        void onPlayCompleted(String id) { AutoAdvance.beforeCompletion(this, id); }
        void onPause() {
            if (LLJJJIL == NativeState.AUTO_SCROLL_STATE_START) LLJJJIL = NativeState.AUTO_SCROLL_STATE_PAUSE;
        }
        void onResume() {
            AutoAdvance.onResume(this);
            if (LLJJJIL == NativeState.AUTO_SCROLL_STATE_PAUSE) LLJJJIL = NativeState.AUTO_SCROLL_STATE_START;
        }
        void onDestroy() { AutoAdvance.onDestroy(this); }
    }

    /**
     * AutoAdvancePatch replaces exactly these four stubs with Tp(), LLJJJIL, V00(false,false)
     * and sq("",false). Keep all lifecycle, preference, completion and notice code unshadowed.
     * Unshadowed methods call through by default; Robolectric 4.17 removed the attribute that
     * used to say so.
     */
    @Implements(value = AutoAdvance.class, isInAndroidSdk = false)
    public static class NativeBridges {
        @Implementation protected static Object readAweme(Object component) {
            return ((NativeComponent) component).Tp();
        }
        @Implementation protected static Object readState(Object component) {
            return ((NativeComponent) component).LLJJJIL;
        }
        @Implementation protected static void start(Object component) {
            ((NativeComponent) component).V00(false, false);
        }
        @Implementation protected static void stop(Object component) {
            ((NativeComponent) component).sq("", false);
        }
    }

    private void assertLimitNotice(String expected) {
        String found = ShadowToast.getTextOfLatestToast();
        if (found == null) {
            View root = feedOwner.get().findViewById(android.R.id.content);
            found = findBannerText(root);
        }
        assertEquals(expected, found);
    }

    private static View findBannerAction(View root, String label) {
        if (root instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) root;
            if (label.equals(tv.getText().toString()) && tv.isClickable()) return tv;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findBannerAction(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String findBannerText(View root) {
        if (root instanceof android.widget.TextView) {
            String text = ((android.widget.TextView) root).getText().toString();
            if (text.contains("Auto-advance")) return text;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                String found = findBannerText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
