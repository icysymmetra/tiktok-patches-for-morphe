package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;
import android.content.Context;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MetraLegacySettingsTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Test public void aliasesPreserveExplicitModernChoicesAndInvertVisibleRows() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("hide_ai_content", true);
        before.put("hide_ai_generated", false);
        before.put("share_sheet_send_to", false);
        before.put("share_sheet_channels", true);
        before.put("share_sheet_force_auto_scroll", true);
        Map<String, Object> updates = MetraLegacySettings.migratedValues(before);
        assertFalse(updates.containsKey("hide_ai_generated"));
        assertEquals(true, updates.get("hide_share_contacts"));
        assertEquals(false, updates.get("hide_share_channels"));
        assertFalse("Showing the action must never start automatic advance", updates.containsKey("auto_advance"));
        assertTrue("Rollback keys remain intact", before.containsKey("hide_ai_content"));
    }

    @Test public void disabledKnownIdsStaySeparateAndUnseenIdsStayAllowed() {
        Map<String, Object> updates = MetraLegacySettings.migratedValues(Map.of(
                "share_sheet_channels_observed", "copy,whatsapp,new_app",
                "share_sheet_channels_enabled", "whatsapp,new_app",
                "share_sheet_actions_observed", "copy,save",
                "share_sheet_actions_enabled", "copy"));
        assertEquals("copy", updates.get(MetraLegacySettings.HIDDEN_CHANNELS));
        assertEquals("save", updates.get(MetraLegacySettings.HIDDEN_ACTIONS));
        assertFalse(updates.toString().contains("unseen"));
    }

    @Test public void qualitiesRetainTheirMeaningAndRepeatedMigrationConverges() {
        for (String[] pair : new String[][]{{"high", "highest"}, {"low", "lowest"}, {"medium", "medium"}, {" MEDIUM ", "medium"}}) {
            Map<String, Object> values = new LinkedHashMap<>(Map.of("download_video_quality", pair[0]));
            values.putAll(MetraLegacySettings.migratedValues(values));
            assertEquals(pair[1], values.get("download_video_quality"));
            assertTrue(MetraLegacySettings.migratedValues(values).isEmpty());
        }
    }

    @Test public void allKnownOptionsDisabledIsRepresentedByAnAbsentOldEnabledKey() {
        assertEquals("save,copy", MetraLegacySettings.migratedValues(Map.of(
                "share_sheet_actions_observed", "save,copy")).get(MetraLegacySettings.HIDDEN_ACTIONS));
    }

    @Test public void clipboardAllowListsRecoverKnownChoicesWithoutBlockingFutureOptions() {
        String hidden = (String) MetraLegacySettings.migratedValues(Map.of(
                "share_sheet_channels_enabled", "whatsapp,RAW:new_app")).get(MetraLegacySettings.HIDDEN_CHANNELS);
        java.util.Set<String> ids = new java.util.HashSet<>(java.util.Arrays.asList(hidden.split(",")));
        assertTrue(ids.contains("facebook"));
        assertFalse(ids.contains("whatsapp"));
        assertFalse(ids.contains("raw:new_app"));
        assertFalse(ids.contains("unseen_future_app"));
    }

    @Test public void allRenamedChoicesAndTheirOppositeValuesKeepTheirMeaning() {
        String[][] booleans = {{"comment_sort_force_show", "comment_sort_controls"},
                {"hide_ai_content", "hide_ai_generated"},
                {"hide_alternate_for_you_batches", "hide_unpersonalized_for_you"},
                {"hide_captcha_popups", "hide_browsing_captcha_popups"},
                {"hide_feed_live_button", "hide_live_entrance"},
                {"hide_suggested_accounts", "hide_inbox_suggested_accounts"}};
        for (String[] pair : booleans) for (boolean value : new boolean[]{false, true}) {
            assertEquals(pair[0], value, MetraLegacySettings.migratedValues(Map.of(pair[0], value)).get(pair[1]));
        }
        for (String[] pair : new String[][]{{"share_sheet_send_to", "hide_share_contacts"},
                {"share_sheet_channels", "hide_share_channels"}, {"share_sheet_actions", "hide_share_actions"}}) {
            for (boolean value : new boolean[]{false, true}) assertEquals(!value,
                    MetraLegacySettings.migratedValues(Map.of(pair[0], value)).get(pair[1]));
        }
        assertEquals("es,de", MetraLegacySettings.migratedValues(Map.of(
                "comment_translation_excluded_languages", "es,de")).get("dont_auto_translate_languages"));
    }

    @Test public void commitReloadsAlreadyLoadedSettingsAndDoesNotRepeatOverNewChoices() {
        Context context = RuntimeEnvironment.getApplication();
        Settings.HIDE_AI_GENERATED.save(false);
        var prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("hide_ai_content", true).commit();
        MetraLegacySettings.migrate(context);
        assertTrue(Settings.HIDE_AI_GENERATED.get());
        Settings.HIDE_AI_GENERATED.save(false);
        MetraLegacySettings.migrate(context);
        assertFalse(Settings.HIDE_AI_GENERATED.get());
        assertTrue(prefs.getBoolean("hide_ai_content", false));
    }

    @Test public void malformedAliasCannotPartiallyCommit() {
        Context context = RuntimeEnvironment.getApplication();
        var prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("hide_ai_content", true)
                .putString("share_sheet_send_to", "wrong type").commit();
        MetraLegacySettings.migrate(context);
        assertFalse(prefs.contains("hide_ai_generated"));
        assertFalse(prefs.contains("metra_legacy_settings_v1"));
    }

    @Test public void secondaryStartupLeavesMigrationAndItsCompletionMarkerToTheMainProcess() {
        Context app = RuntimeEnvironment.getApplication();
        var prefs = app.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("hide_ai_content", true).commit();
        Context secondary = new android.content.ContextWrapper(app) {
            @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
                var info = new android.content.pm.ApplicationInfo(super.getApplicationInfo());
                info.processName = app.getPackageName() + ":secondary";
                return info;
            }
        };
        Utils.setContext(secondary);
        try {
            assertFalse(Utils.isMainProcess());
            MetraLegacySettings.migrate(secondary);
            assertFalse("A background process must not write a migrated value", prefs.contains("hide_ai_generated"));
            assertFalse("A background process must not complete the migration", prefs.contains("metra_legacy_settings_v1"));
        } finally {
            Utils.setContext(app);
        }
        MetraLegacySettings.migrate(app);
        assertTrue(prefs.getBoolean("hide_ai_generated", false));
        assertTrue(prefs.getBoolean("metra_legacy_settings_v1", false));
    }

    @Test public void failedCommitDoesNotLeaveAMemoryOnlyCompletionMarkerAndCanRetry() {
        Context context = RuntimeEnvironment.getApplication();
        var prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("hide_ai_content", true).commit();
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        var wrapped = (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                prefs.getClass().getClassLoader(), new Class[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(prefs, args);
                    var editor = prefs.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class[]{android.content.SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                Object result = call.invoke(editor, values);
                                // Android can update memory even when commit reports a disk failure.
                                if (call.getName().equals("commit") && failOnce.getAndSet(false)) return false;
                                return result instanceof android.content.SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        Context failing = new android.content.ContextWrapper(context) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return Setting.PREFERENCES_NAME.equals(name) ? wrapped : super.getSharedPreferences(name, mode);
            }
        };
        MetraLegacySettings.migrate(failing);
        assertFalse("The failed transaction must not appear complete in memory", prefs.contains("metra_legacy_settings_v1"));
        assertFalse("The failed transaction must restore its previous values", prefs.contains("hide_ai_generated"));
        assertTrue(prefs.getBoolean("hide_ai_content", false));
        MetraLegacySettings.migrate(failing);
        assertTrue(prefs.getBoolean("metra_legacy_settings_v1", false));
        assertTrue(prefs.getBoolean("hide_ai_generated", false));
        assertTrue(Settings.HIDE_AI_GENERATED.get());
    }

    @Test public void aConcurrentNewChoiceWinsAfterMigrationTakesItsSnapshot() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Settings.HIDE_SHARE_CONTACTS.save(false);
        var prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("share_sheet_send_to", true).commit();
        var snapshot = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var written = new java.util.concurrent.CountDownLatch(1);
        var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var wrapped = (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                prefs.getClass().getClassLoader(), new Class[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    Object result = method.invoke(prefs, args);
                    if (method.getName().equals("getAll")) {
                        snapshot.countDown();
                        if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Snapshot was not released");
                    }
                    return result;
                });
        Context delayed = new android.content.ContextWrapper(context) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return Setting.PREFERENCES_NAME.equals(name) ? wrapped : super.getSharedPreferences(name, mode);
            }
        };
        Thread migration = new Thread(() -> {
            try { MetraLegacySettings.migrate(delayed); } catch (Throwable error) { errors.set(error); }
        });
        Thread writer = new Thread(() -> {
            try { assertTrue(Settings.HIDE_SHARE_CONTACTS.save(true)); }
            catch (Throwable error) { errors.set(error); }
            finally { written.countDown(); }
        });
        migration.start();
        try {
            assertTrue(snapshot.await(3, java.util.concurrent.TimeUnit.SECONDS));
            writer.start();
            written.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } finally {
            release.countDown();
            migration.join(3000);
            writer.join(3000);
        }
        assertFalse(migration.isAlive());
        assertFalse(writer.isAlive());
        if (errors.get() != null) throw new AssertionError(errors.get());
        assertTrue("Migration overwrote a newer explicit choice", prefs.getBoolean("hide_share_contacts", false));
        assertTrue(Settings.HIDE_SHARE_CONTACTS.get());
    }
}
