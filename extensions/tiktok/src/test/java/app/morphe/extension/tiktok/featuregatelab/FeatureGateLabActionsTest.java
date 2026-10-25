package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import app.morphe.extension.shared.BackgroundPoolSaturation;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.DocumentExportProvider;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.DocumentOperation;
import app.morphe.extension.tiktok.settings.preference.DocumentOperationTest;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureGateLabActionsTest {
    @Before public void setup() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Utils.setIsDarkModeEnabled(true);
        FeatureGateLabFragment.awaitFileIoForTests();
        FeatureGateCatalog.awaitForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateCatalog.resetForTests();
        FeatureGateLabFragment.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabUndo.resetForTests();
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabSession.begin();
        var entry = entry("gate");
        var same = entry("same_gate");
        var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(List.of(entry, same),
                Map.of(entry.identity(), entry, same.identity(), same), 2, 0, true));
    }

    @Test public void disabledMasterCanResetRulesAndUndoWithoutChangingOtherSettings() throws Exception {
        save("gate", "true", true);
        FeatureGateLabStore.acknowledgeWarning();
        SettingsManagerObservationRecorder.observeWithDefault("object", String.class, "", "before");
        String observations = SettingsManagerObservationRecorder.exportJson().toString();
        FeatureGateLabUndo.reset(false);
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertTrue(FeatureGateLabStore.warningAcknowledged());
        Settings.REGION_SPOOF.save(true);
        // The config undo is on disk and does not depend on an in-memory rule snapshot.
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").enabled);
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertTrue(Settings.REGION_SPOOF.get());
        assertEquals(observations, SettingsManagerObservationRecorder.exportJson().toString());
    }

    @Test public void fullResetUndoRestoresSwitchesObservationsAndWrapperOwnership() throws Exception {
        save("gate", "false", true);
        FeatureGateLabStore.setMasterEnabled(true);
        FeatureGateLabStore.acknowledgeWarning();
        SettingsManagerObservationRecorder.observeWithDefault("structured", Sample.class, new Sample(), new Sample());
        String before = SettingsManagerObservationRecorder.exportJson().toString();
        FeatureGateLabUndo.reset(true);
        assertEquals(0, SettingsManagerObservationRecorder.size());
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertFalse(FeatureGateLabStore.warningAcknowledged());
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        FeatureGateLabUndo.undo();
        assertTrue(FeatureGateLabStore.masterEnabled());
        assertTrue(FeatureGateLabStore.warningAcknowledged());
        assertEquals(before, SettingsManagerObservationRecorder.exportJson().toString());
        SettingsManagerObservationRecorder.observeWithoutDefault("structured", Sample.class, new Sample());
        assertEquals(1, SettingsManagerObservationRecorder.size());
    }

    @Test public void importsReplaceAcceptedRulesDisabledAndUndoRetainsUnrelatedRules() throws Exception {
        save("gate", "false", true);
        save("other", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        var incoming = new FeatureGateLabStore.Rule(FeatureGateLabStore.idFor("abmock", "gate", "BOOLEAN"),
                "abmock", "gate", "BOOLEAN", "true", true, 1);
        FeatureGateLabUndo.importRules(new FeatureGateLabStore.ImportReview(List.of(incoming), List.of()));
        assertFalse(FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").enabled);
        assertEquals("true", FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").value);
        assertTrue(FeatureGateLabStore.rule("abmock", "other", "BOOLEAN").enabled);
        assertTrue(FeatureGateLabStore.masterEnabled());
        FeatureGateLabUndo.undo();
        assertEquals("false", FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").value);
        assertTrue(FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").enabled);
    }

    @Test public void failedCheckpointAndInvalidUndoLeaveSettingsUntouched() throws Exception {
        var app = Utils.getContext();
        save("gate", "true", true);
        String before = FeatureGateLabStore.exportSettings().toString();
        File invalidDirectory = File.createTempFile("lab-undo", ".tmp", app.getCacheDir());
        Utils.setContext(new android.content.ContextWrapper(app) {
            @Override public File getFilesDir() { return invalidDirectory; }
        });
        try {
            assertThrows(Exception.class, () -> FeatureGateLabUndo.reset(true));
            assertEquals(before, FeatureGateLabStore.exportSettings().toString());
        } finally {
            Utils.setContext(app);
            assertTrue(invalidDirectory.delete());
        }
        FeatureGateLabUndo.reset(false);
        String empty = FeatureGateLabStore.exportSettings().toString();
        try (var output = new java.io.FileOutputStream(new File(app.getFilesDir(), "feature-gate-lab-undo.json"))) {
            output.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(Exception.class, FeatureGateLabUndo::undo);
        assertEquals(empty, FeatureGateLabStore.exportSettings().toString());
    }

    @Test public void aFailedCommitOnASelectionPutsNoneOfItThrough() throws Exception {
        // The reason a selection goes through one replaceSettings rather than one per gate: a
        // selection half applied is worse than one not applied. Same failing commit the
        // single-gate path is held to below.
        var app = Utils.getContext();
        save("kept", "true", true);
        String before = FeatureGateLabStore.exportSettings().toString();

        // A fresh store each time: the proxy fails only its first commit, which is what leaves a
        // rollback to succeed on the second.
        try {
            var gates = List.of(entry("alpha"), entry("beta"), entry("gamma"));
            useFailingStore(app);
            assertThrows(Exception.class, () -> FeatureGateLabUndo.forceBoolean(gates, false));
            assertEquals("part of the selection was written by a commit that failed",
                    before, FeatureGateLabStore.exportSettings().toString());

            useFailingStore(app);
            assertThrows(Exception.class,
                    () -> FeatureGateLabUndo.resetAll(List.of(entry("kept"))));
            assertEquals("a reset that failed dropped a rule anyway",
                    before, FeatureGateLabStore.exportSettings().toString());
        } finally { Utils.setContext(app); }
    }

    /** Points the Lab's storage at a store whose next commit reports failure. */
    private static void useFailingStore(android.content.Context app) {
        var failing = failingOnceStore(app);
        Utils.setContext(new android.content.ContextWrapper(app) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("morphe_feature_gate_lab")
                        ? failing : super.getSharedPreferences(name, mode);
            }
        });
    }

    /** A settings store whose first commit reports failure, as the single-gate case uses. */
    private static android.content.SharedPreferences failingOnceStore(android.content.Context app) {
        var delegate = app.getSharedPreferences("morphe_feature_gate_lab", 0);
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(),
                new Class[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(delegate, args);
                    var editor = delegate.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class[]{android.content.SharedPreferences.Editor.class},
                            (wrapped, call, values) -> {
                                Object result = call.invoke(editor, values);
                                if (call.getName().equals("commit")) {
                                    return commits.incrementAndGet() != 1 && (Boolean) result;
                                }
                                return result instanceof android.content.SharedPreferences.Editor
                                        ? wrapped : result;
                            });
                });
    }

    @Test public void failedPreferenceCommitRecoversThePreviousConfigAndLeavesObservations() throws Exception {
        var app = Utils.getContext();
        save("gate", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        SettingsManagerObservationRecorder.observeWithDefault("object", String.class, "", "before");
        // A gate that actually fired. The detail screen reads this to say whether an override has
        // been reached, and a commit that did not land must not be able to erase it.
        FeatureGateLabRuntime.reloadRules();
        assertTrue(FeatureGateLabRuntime.overrideBoolean("gate", false));
        assertTrue("the rule never fired, so the test cannot see it being lost",
                FeatureGateLabRuntime.isTriggered("abmock", "gate", "BOOLEAN"));
        String before = FeatureGateLabStore.exportSettings().toString();
        var delegate = app.getSharedPreferences("morphe_feature_gate_lab", 0);
        var commits = new java.util.concurrent.atomic.AtomicInteger();
        var failing = (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                delegate.getClass().getClassLoader(), new Class[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(delegate, args);
                    var editor = delegate.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class[]{android.content.SharedPreferences.Editor.class}, (wrapped, call, values) -> {
                                Object result = call.invoke(editor, values);
                                if (call.getName().equals("commit")) return commits.incrementAndGet() != 1 && (Boolean) result;
                                return result instanceof android.content.SharedPreferences.Editor ? wrapped : result;
                            });
                });
        Utils.setContext(new android.content.ContextWrapper(app) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("morphe_feature_gate_lab") ? failing : super.getSharedPreferences(name, mode);
            }
        });
        try {
            assertThrows(Exception.class, () -> FeatureGateLabUndo.reset(true));
            assertEquals(before, FeatureGateLabStore.exportSettings().toString());
            assertEquals(1, SettingsManagerObservationRecorder.size());
            assertTrue(commits.get() >= 2);
            assertTrue("a failed commit cleared the record of which overrides fired",
                    FeatureGateLabRuntime.isTriggered("abmock", "gate", "BOOLEAN"));
            // Both halves of that are load bearing. The failed write must not clear, because
            // nothing was written; and the rollback must not clear either, because it puts the
            // very rules back that the record describes.
            assertEquals("the original the rule replaced went with it",
                    "false", FeatureGateLabRuntime.originalValue("abmock", "gate", "BOOLEAN"));
        } finally { Utils.setContext(app); }
    }

    @Test public void nulCannotHideTrailingDataInTheLabUndoFile() throws Exception {
        save("gate", "true", true);
        FeatureGateLabUndo.reset(false);
        try (var output = new java.io.FileOutputStream(new File(Utils.getContext().getFilesDir(), "feature-gate-lab-undo.json"), true)) {
            output.write(new byte[]{0, 'b', 'a', 'd'});
        }
        assertThrows(Exception.class, FeatureGateLabUndo::undo);
        assertTrue(FeatureGateLabStore.rules().isEmpty());
    }

    @Test public void controlsApplyMasterResetAndUndoWithoutDialogs() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            Switch master = findSwitch(fragment.getView());
            master.performClick();
            // The switch writes through the journal, which is storage, so it goes the same way
            // as every other Lab change: off this thread, then back to it.
            settle();
            assertTrue(master.isChecked());
            assertTrue(FeatureGateLabStore.masterEnabled());
            assertTrue(FeatureGateLabStore.warningAcknowledged());
            assertNull(ShadowDialog.getLatestDialog());
            master.performClick();
            settle();
            assertFalse(FeatureGateLabStore.masterEnabled());
            save("gate", "true", true);
            action(fragment, 4);
            waitFor("Lab overrides reset.");
            assertTrue(FeatureGateLabStore.rules().isEmpty());
            action(fragment, 6);
            waitFor("Lab settings put back.");
            assertEquals(1, FeatureGateLabStore.rules().size());
            action(fragment, 5);
            waitFor("Lab data reset.");
            assertFalse(master.isChecked());
            assertFalse(FeatureGateLabStore.warningAcknowledged());
            assertNull(ShadowDialog.getLatestDialog());
            var cached = FeatureGateCatalog.cachedSnapshot();
            action(fragment, 1);
            FeatureGateCatalog.awaitForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotSame(cached, FeatureGateCatalog.cachedSnapshot());
            var tabs = FeatureGateLabFragment.class.getDeclaredField("viewTabs");
            tabs.setAccessible(true);
            ((ViewGroup) tabs.get(fragment)).getChildAt(1).performClick();
            ShadowToast.reset();
            FeatureGateLabSession.showRestartDialog(activity);
            FeatureGateLabSession.showAfterHostExit(activity);
            assertEquals("the exit toast is redundant now that every action says restart", 0, ShadowToast.shownToastCount());
            assertNull(ShadowDialog.getLatestDialog());
        }
    }

    @Test public void selectedFileImportsDisabledValuesAndReportsSkippedCountsWithoutConfirmation() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            JSONArray rules = new JSONArray().put(rule("gate", "true")).put(rule("same_gate", "false"))
                    .put(rule("missing", "true")).put(rule("gate", "invalid")).put(42);
            JSONObject root = new JSONObject().put("payload_kind", "loaded_values")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion()).put("rules", rules);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            action(fragment, 3);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.getAction());
            var provider = DocumentExportProvider.register(activity).contents(bytes.toByteArray());
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            // No prompt before the import; the result comes after it, as a dialog with the
            // counts on their own lines.
            waitForImportDialog("Imported 1 values", "1 already matched", "1 unavailable", "2 rejected");
            assertFalse(FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").enabled);
            assertEquals("true", FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").value);
            assertEquals(1, FeatureGateLabStore.rules().size());
            assertFalse("the result dialog was dismissed", ShadowDialog.getLatestDialog().isShowing());
            action(fragment, 6);
            waitFor("Lab settings put back.");
            assertTrue(FeatureGateLabStore.rules().isEmpty());
        }
    }

    @Test public void rawJsonImportMatchesThePickerAndAppliesTheSameReview() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            JSONObject root = new JSONObject().put("payload_kind", "loaded_values")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion())
                    .put("rules", new JSONArray().put(rule("gate", "true")));
            action(fragment, 3);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            var provider = DocumentExportProvider.register(activity)
                    .contents(root.toString().getBytes(StandardCharsets.UTF_8));
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            waitForImportDialog("Imported 1 values", "0 rejected");
            assertFalse(FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").enabled);
            assertEquals("true", FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").value);
        }
    }

    /** The rules a change of TikTok build turned off are counted when the Lab next opens. */
    @Test public void openingTheLabAfterABuildChangeSaysHowManyRulesWereTurnedOff() throws Exception {
        // 47.1.4 is no longer in the catalog; 47.4.3, which replaced it, is.
        app.morphe.extension.shared.BuildNames.setRunningBuildForTests("47.1.4");
        try {
            FeatureGateLabStore.resetAllLabData();
            // A gate 47.4.3 carries, one under a type it doesn't, and one it dropped.
            FeatureGateLabStore.saveRule("abmock", "1005_max_limit_count_daily", "INT", "9", true);
            FeatureGateLabStore.saveRule("abmock", "1005_max_limit_count_daily", "LONG", "9", true);
            FeatureGateLabStore.saveRule("abmock", "comment_cell_badge_dedup", "BOOLEAN", "true", true);
            app.morphe.extension.shared.BuildNames.setRunningBuildForTests("47.4.3");
            try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
                attach(owner.get());
                waitFor("2 overrides were turned off because the Lab couldn't confirm their gates are "
                        + "unchanged in TikTok 47.4.3. Review them before turning them back on.");
                assertTrue(FeatureGateLabStore.rule("abmock", "1005_max_limit_count_daily", "INT").enabled);
            }
        } finally {
            FeatureGateLabStore.resetAllLabData();
            app.morphe.extension.shared.BuildNames.setRunningBuildForTests(null);
        }
    }

    @Test public void aFileFromAnotherTikTokSaysSoInsteadOfCallingItCorrupt() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            JSONObject root = new JSONObject().put("payload_kind", "loaded_values")
                    .put("tiktok_version", "46.8.3")
                    .put("rules", new JSONArray().put(rule("gate", "true")));
            action(fragment, 3);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            var provider = DocumentExportProvider.register(activity)
                    .contents(root.toString().getBytes(StandardCharsets.UTF_8));
            LogBufferManager.clearLogBuffer();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            waitFor("These loaded values are for a different TikTok version.");
            assertTrue(FeatureGateLabStore.rules().isEmpty());
            assertLoggedOnce("INFO", "Loaded-value file import refused", "Loaded-value file import failed");
        }
    }

    /** A file the review can't name stays a patch error: that's how a real export the Lab should take shows up. */
    @Test public void anUnreadableFileIsStillLoggedAsAPatchError() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            action(fragment, 3);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            var provider = DocumentExportProvider.register(activity)
                    .contents("{\"payload_kind\": \"loaded_values\", ".getBytes(StandardCharsets.UTF_8));
            LogBufferManager.clearLogBuffer();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            waitFor("That file isn't a loaded-values export, or it's larger than the Lab accepts");
            assertTrue(FeatureGateLabStore.rules().isEmpty());
            assertLoggedOnce("ERROR", "Loaded-value file import failed", "Loaded-value file import refused");
        }
    }

    @Test public void boundedImportParserRejectsDuplicateDeepInvalidAndOverlargeInput() throws Exception {
        assertLoadedJsonRejected(("{\"payload_kind\":\"loaded_values\","
                + "\"payload_kind\":\"loaded_values\",\"tiktok_version\":\"46.2.3\",\"rules\":[]}")
                .getBytes(StandardCharsets.UTF_8));
        assertLoadedJsonRejected(new byte[]{'{', '"', 'x', '"', ':', '"', (byte) 0xc3, 0x28, '"', '}'});

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 30; i++) deep.append('[');
        deep.append("{}");
        for (int i = 0; i < 30; i++) deep.append(']');
        assertLoadedJsonRejected(deep.toString().getBytes(StandardCharsets.UTF_8));
        assertLoadedJsonRejected(new byte[16 * 1024 * 1024 + 1]);

        // An array longer than any export carries, and more values than one could hold.
        JSONArray many = new JSONArray();
        for (int i = 0; i <= 1 << 16; i++) many.put(0);
        assertLoadedJsonRejected(loadedValues(many).toString().getBytes(StandardCharsets.UTF_8));
        JSONArray wide = new JSONArray();
        for (int row = 0; row < 17; row++) {
            JSONArray part = new JSONArray();
            for (int i = 0; i < 1 << 16; i++) part.put(0);
            wide.put(part);
        }
        assertLoadedJsonRejected(loadedValues(new JSONArray())
                .put("settings_manager_observations", wide).toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A 47.4.3 export carries 14,747 loaded values and up to 2,048 settings-manager observations.
     * The import's parse bounds were the Lab's own rule cap, so it refused every real export,
     * the Lab's own included, as not an export or too large.
     */
    @Test public void aFullSizeExportImportsGzippedOrPlain() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            JSONArray rules = new JSONArray().put(rule("gate", "true").put("force", false));
            for (int i = 1; i < 15_000; i++) rules.put(rule("elsewhere_" + i, "true").put("force", false));
            JSONArray observations = new JSONArray();
            for (int i = 0; i < 2048; i++) observations.put(observation(i));
            byte[] plain = loadedValues(rules).put("settings_manager_observations", observations)
                    .toString().getBytes(StandardCharsets.UTF_8);
            assertTrue("past the old 4 MB file cap", plain.length > 4 * 1024 * 1024);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(plain); }

            action(fragment, 3);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            var provider = DocumentExportProvider.register(activity).contents(bytes.toByteArray());
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            waitForImportDialog("Imported 1 values", "0 already matched", "14999 unavailable", "0 rejected");
            assertEquals("true", FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN").value);

            // The same file, unpacked, as someone reading it in a text editor would leave it.
            action(fragment, 3);
            started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            provider = DocumentExportProvider.register(activity).contents(plain);
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            waitFor("Nothing new was imported. 1 already matched, 14999 unavailable, 0 rejected.");
            assertEquals(1, FeatureGateLabStore.rules().size());
        }
    }

    /** More differing values than the Lab keeps are refused by count, with nothing changed. */
    @Test public void aFileWithMoreChangesThanTheLabKeepsSaysSoAndChangesNothing() throws Exception {
        var entries = new ArrayList<FeatureGateCatalog.Entry>();
        var byIdentity = new java.util.HashMap<String, FeatureGateCatalog.Entry>();
        JSONArray rules = new JSONArray();
        for (int i = 0; i < 1030; i++) {
            var entry = entry("gate_" + i);
            entries.add(entry);
            byIdentity.put(entry.identity(), entry);
            rules.put(rule("gate_" + i, "true"));
        }
        var cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(entries, byIdentity, entries.size(), 0, true));
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            action(fragment, 3);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            var provider = DocumentExportProvider.register(activity)
                    .contents(loadedValues(rules).toString().getBytes(StandardCharsets.UTF_8));
            LogBufferManager.clearLogBuffer();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
            waitFor("The Lab keeps at most 1,024 rules. This change would make 1,030, so nothing was changed.");
            assertTrue(FeatureGateLabStore.rules().isEmpty());
            assertLoggedOnce("INFO", "Lab change refused", "Lab change failed");
        }
    }

    @Test public void loadedValueReviewRejectsNonStringFieldsAndDuplicateRules() throws Exception {
        JSONObject nonString = rule("gate", "true").put("value", true);
        JSONObject duplicate = rule("gate", "false");
        JSONObject duplicateAgain = rule("gate", "true");
        JSONObject root = new JSONObject().put("schema", 1).put("target", "TikTok global")
                .put("tiktok_version", FeatureGateLabStore.targetVersion())
                .put("rules", new JSONArray().put(nonString).put(duplicate).put(duplicateAgain));
        FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                root.toString(), FeatureGateCatalog.cachedSnapshot().byIdentity);
        assertEquals(1, review.accepted.size());
        assertEquals(2, review.rejected.size());
    }

    @Test public void failedExportReportsProviderWriteFailure() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            action(fragment, 2);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            var uri = android.net.Uri.parse("file:///unregistered-export.json.gz");
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK, new Intent().setData(uri));
            waitFor("Loaded-value file export failed");
        }
    }

    @Test public void exportBeforeTheSnapshotIsReadySaysWhy() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var snapshot = FeatureGateLabFragment.class.getDeclaredField("snapshot");
            snapshot.setAccessible(true);
            snapshot.set(fragment, null);

            ShadowToast.reset();
            action(fragment, 2);
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals("Loaded values are still being read. Try again in a moment.",
                    ShadowToast.getTextOfLatestToast());
            assertNull("an empty export still opened a file picker",
                    Shadows.shadowOf(activity).getNextStartedActivityForResult());
        }
    }

    @Test public void aFailedLabSafExportUsesTheDocumentsProviderDeletionContract() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            provider.failOpen = true;
            action(fragment, 2);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK,
                    new Intent().setData(provider.uri));
            FeatureGateLabFragment.awaitFileIoForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("ContentResolver.delete never reaches DocumentsProvider.deleteDocument",
                    1, provider.deleteCalls);
            assertFalse("the failed export was left in the chosen folder", provider.exists);
        }
    }

    /** The file app opened the file and the write then failed, so what's in it now is the Lab's to remove. */
    @Test public void aLabSafExportWhoseWriteFailsAfterTheOpenIsRemoved() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var provider = DocumentExportProvider.register(activity);
            provider.readOnlyWrites = true;
            action(fragment, 2);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK,
                    new Intent().setData(provider.uri));
            waitFor("Loaded-value file export failed");
            assertEquals(1, provider.deleteCalls);
            assertFalse(provider.exists);
        }
    }

    @Test public void failedLabCleanupDoesNotSendTheReaderToAnAssumedDownloadsFolder() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            provider.failOpen = true;
            provider.refuseDeletion = true;
            action(fragment, 2);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            ShadowToast.reset();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK,
                    new Intent().setData(provider.uri));
            FeatureGateLabFragment.awaitFileIoForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String notice = ShadowToast.getTextOfLatestToast();
            assertNotNull(notice);
            assertTrue(notice.contains("partial file") && notice.contains("couldn't be removed"));
            assertFalse("SAF permits cloud and arbitrary local destinations", notice.contains("Downloads"));
            assertEquals(1, provider.deleteCalls);
            assertTrue(provider.exists);
        }
    }

    @Test public void aDetachedLabExportResultCanStillRemoveItsCreatedDocument() throws Exception {
        var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(Utils.getContext());
        var fragment = new FeatureGateLabFragment();
        var write = FeatureGateLabFragment.class.getDeclaredMethod("writeLoadedValuesFile", android.net.Uri.class);
        write.setAccessible(true);
        write.invoke(fragment, provider.uri);
        FeatureGateLabFragment.awaitFileIoForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("losing the view must not abandon its new document", 1, provider.deleteCalls);
        assertFalse(provider.exists);
    }

    @Test public void importBeforeTheSnapshotIsReadySaysWhy() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var snapshot = FeatureGateLabFragment.class.getDeclaredField("snapshot");
            snapshot.setAccessible(true);
            snapshot.set(fragment, null);
            JSONObject root = new JSONObject().put("payload_kind", "loaded_values")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion())
                    .put("rules", new JSONArray().put(rule("gate", "true")));
            var uri = DocumentExportProvider.register(activity)
                    .contents(root.toString().getBytes(StandardCharsets.UTF_8)).uri;

            ShadowToast.reset();
            var read = FeatureGateLabFragment.class.getDeclaredMethod(
                    "readLoadedValuesFile", android.net.Uri.class);
            read.setAccessible(true);
            read.invoke(fragment, uri);
            waitFor("Loaded values are still being read. Try again in a moment.");
            assertTrue(FeatureGateLabStore.rules().isEmpty());
        }
    }

    @Test public void aSelectedImportFinishesAfterTheLabIsClosed() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            JSONObject root = new JSONObject().put("payload_kind", "loaded_values")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion())
                    .put("rules", new JSONArray().put(rule("gate", "true")));
            var provider = DocumentExportProvider.register(activity)
                    .contents(root.toString().getBytes(StandardCharsets.UTF_8));
            var release = provider.holdOpens();
            try {
                readFile(fragment, provider.uri);
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                activity.getFragmentManager().beginTransaction().remove(fragment).commit();
                activity.getFragmentManager().executePendingTransactions();
                assertNull(fragment.getActivity());
            } finally {
                release.countDown();
            }

            // With the Lab gone there is nothing to hang the dialog on, so the one line goes
            // out as a toast, with the way back named.
            waitFor("Imported 1 values. Undo last Lab change is in the menu.");
            assertEquals("true", FeatureGateLabStore.rule(
                    "abmock", "gate", "BOOLEAN").value);
        }
    }

    /** A file app that answers the stop: nothing is imported, and the Lab says so in its own window. */
    @Test public void aStalledImportOffersAStopThatChangesNothing() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var provider = DocumentExportProvider.register(activity).contents(loadedValues(rule("gate", "true")));
            provider.honorCancel = true;
            var release = provider.holdOpens();
            try {
                readFile(fragment, provider.uri);
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNull("the stop was offered before the file app stalled", bannerAction(activity));
                stall();
                assertEquals("Still waiting for the file app.", bannerText(activity));
                View stop = bannerAction(activity);
                assertEquals("Stop waiting", ((TextView) stop).getText().toString());
                assertTrue(stop.performClick());
                assertEquals("Stopped waiting for the file app. Nothing was imported.", settledBanner(activity));
            } finally {
                release.countDown();
            }
            assertEquals("Stopped waiting for the file app. Nothing was imported.", settledBanner(activity));
            assertTrue("the cancellation never reached the file app", provider.cancels.get() > 0);
            assertTrue("a stopped import changed the Lab", FeatureGateLabStore.rules().isEmpty());
            assertFalse(DocumentOperation.busy(DocumentOperation.Kind.LAB_FILE));
        }
    }

    /**
     * A file app that ignores the stop holds only the Lab's slot. Export says so instead of
     * queueing behind it, the settings rows stay free, and once the file app lets go the export is
     * called unsaved and the empty document it never filled is removed.
     */
    @Test public void anExportWhoseFileAppIgnoresTheStopHoldsOnlyTheLabSlot() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var provider = DocumentExportProvider.register(activity);
            var release = provider.holdOpens();
            LogBufferManager.clearLogBuffer();
            try {
                action(fragment, 2);
                var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
                fragment.onActivityResult(started.requestCode, Activity.RESULT_OK,
                        new Intent().setData(provider.uri));
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                stall();
                assertTrue(bannerAction(activity).performClick());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Stopped waiting for the file app. It hasn't said yet whether the export was saved.",
                        bannerText(activity));

                action(fragment, 2);
                assertEquals("The file app still has the last file. Try again once it lets go, or restart TikTok.",
                        bannerText(activity));
                assertNull("a second export queued behind the held one",
                        Shadows.shadowOf(activity).getNextStartedActivityForResult());
                assertFalse("the Lab's file app held the settings rows too",
                        DocumentOperation.busy(DocumentOperation.Kind.SETTINGS_FILE));
            } finally {
                release.countDown();
            }
            assertEquals("The export wasn't saved. Export again when the file app is ready.", settledBanner(activity));
            DocumentOperationTest.assertStopLoggedAsInfo("Loaded-value file export failed");
            assertFalse("the empty document the export never filled was left behind", provider.exists);
            assertFalse(DocumentOperation.busy(DocumentOperation.Kind.LAB_FILE));
        }
    }

    /** The picker can hand back a file the user chose to replace. Stopped before the Lab wrote to it, it stays as it was. */
    @Test public void aStoppedExportLeavesTheFileItWasReplacingAlone() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            byte[] older = "an older export".getBytes(StandardCharsets.UTF_8);
            var provider = DocumentExportProvider.register(activity).contents(older);
            provider.honorCancel = true;
            var release = provider.holdOpens();
            try {
                action(fragment, 2);
                var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
                fragment.onActivityResult(started.requestCode, Activity.RESULT_OK,
                        new Intent().setData(provider.uri));
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                stall();
                assertTrue(bannerAction(activity).performClick());
                // Settled before the open is let go, so the file app has given up rather than
                // opened the file "wt", which would have emptied it.
                assertEquals("The export wasn't saved. Export again when the file app is ready.", settledBanner(activity));
            } finally {
                release.countDown();
            }
            assertEquals("a file the export never wrote was removed", 0, provider.deleteCalls);
            assertTrue(provider.exists);
            assertArrayEquals(older, java.nio.file.Files.readAllBytes(provider.file.toPath()));
        }
    }

    /** "w" doesn't empty the file it opens, so an export over a bigger one kept that file's end and wouldn't read back. */
    @Test public void anExportOverABiggerFileLeavesNothingOfItBehind() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            byte[] older = new byte[64 * 1024];
            java.util.Arrays.fill(older, (byte) 'x');
            var provider = DocumentExportProvider.register(activity).contents(older);
            action(fragment, 2);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            fragment.onActivityResult(started.requestCode, Activity.RESULT_OK,
                    new Intent().setData(provider.uri));
            waitFor("Exported");
            byte[] written = java.nio.file.Files.readAllBytes(provider.file.toPath());
            assertTrue("the export kept the end of the file it replaced", written.length < older.length);
            byte[] json;
            try (var gzip = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(written))) {
                json = gzip.readAllBytes();
            }
            assertEquals("loaded_values",
                    new JSONObject(new String(json, StandardCharsets.UTF_8)).getString("payload_kind"));
        }
    }

    /**
     * What the Lab knows can change while a file app holds the file, as when the catalog finishes
     * reading. The import is reviewed against the values the Lab has once the file arrives.
     */
    @Test public void anImportIsReviewedAgainstTheValuesTheLabHasWhenTheFileArrives() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            var provider = DocumentExportProvider.register(activity)
                    .contents(loadedValues(rule("gate", "true"), rule("late_gate", "true")));
            var release = provider.holdOpens();
            try {
                readFile(fragment, provider.uri);
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                var gate = entry("gate");
                var same = entry("same_gate");
                var late = entry("late_gate");
                var snapshot = FeatureGateLabFragment.class.getDeclaredField("snapshot");
                snapshot.setAccessible(true);
                snapshot.set(fragment, new FeatureGateCatalog.Snapshot(List.of(gate, same, late),
                        Map.of(gate.identity(), gate, same.identity(), same, late.identity(), late), 3, 0, true));
            } finally {
                release.countDown();
            }
            waitForImportDialog("Imported 2 values", "0 unavailable");
            assertEquals("true", FeatureGateLabStore.rule("abmock", "late_gate", "BOOLEAN").value);
        }
    }

    /**
     * Turning the phone while a file app stalls rebuilds the Lab. The stop is offered again in the
     * new window, and the import's result lands there as well.
     */
    @Test public void aLabRebuiltWhileTheFileAppStallsOffersTheStopAndShowsTheResultThere() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            var first = owner.get();
            var fragment = attach(first);
            var provider = DocumentExportProvider.register(first).contents(loadedValues(rule("gate", "true")));
            var release = provider.holdOpens();
            Activity rebuilt;
            try {
                readFile(fragment, provider.uri);
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                stall();
                assertEquals("Still waiting for the file app.", bannerText(first));
                owner.recreate();
                rebuilt = owner.get();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNotSame(first, rebuilt);
                assertEquals("Still waiting for the file app.", bannerText(rebuilt));
                assertNotNull("the rebuilt window offers no stop", bannerAction(rebuilt));
            } finally {
                release.countDown();
            }
            waitForImportDialog("Imported 1 values");
            assertSame("the result went to a window that's gone", rebuilt,
                    activityOf(ShadowDialog.getLatestDialog().getContext()));
        }
    }

    private static void readFile(FeatureGateLabFragment fragment, android.net.Uri uri) throws Exception {
        var read = FeatureGateLabFragment.class.getDeclaredMethod("readLoadedValuesFile", android.net.Uri.class);
        read.setAccessible(true);
        read.invoke(fragment, uri);
    }

    private static byte[] loadedValues(JSONObject... rules) throws Exception {
        JSONArray list = new JSONArray();
        for (JSONObject rule : rules) list.put(rule);
        return new JSONObject().put("payload_kind", "loaded_values")
                .put("tiktok_version", FeatureGateLabStore.targetVersion())
                .put("rules", list).toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Runs the clock past the stall threshold, where the Lab offers to stop waiting. */
    private static void stall() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DocumentOperation.stallMillisForTests()));
    }

    private static String bannerText(Activity activity) {
        View label = activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_settings_action_message");
        return label instanceof TextView ? ((TextView) label).getText().toString() : null;
    }

    private static View bannerAction(Activity activity) {
        return activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_settings_action_button");
    }

    /** The banner once the Lab's file worker has returned and what it posted has run. */
    private static String settledBanner(Activity activity) throws Exception {
        FeatureGateLabFragment.awaitFileIoForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return bannerText(activity);
    }

    private static Activity activityOf(android.content.Context context) {
        while (!(context instanceof Activity) && context instanceof android.content.ContextWrapper) {
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return (Activity) context;
    }

    private static void assertLoadedJsonRejected(byte[] bytes) throws Exception {
        var method = FeatureGateLabFragment.class.getDeclaredMethod("readLoadedJson", byte[].class);
        method.setAccessible(true);
        try {
            method.invoke(null, (Object) bytes);
            fail("import should be rejected");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test public void leavingTheLabWhileAChangeRunsDoesNotWedgeIt() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var fragment = attach(owner.get());
            var reset = FeatureGateLabFragment.class.getDeclaredMethod("reset", boolean.class);
            reset.setAccessible(true);

            // Pressing Back while a reset is still running. The change finishes on the main
            // thread afterwards, and by then the switch it wants to put back is gone.
            reset.invoke(fragment, false);
            fragment.onDestroyView();
            Utils.awaitBackgroundTasksForTests();
            // Robolectric runs a posted runnable on this thread, so the NPE this used to throw
            // would surface right here.
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            var changing = FeatureGateLabFragment.class.getDeclaredField("CHANGING");
            changing.setAccessible(true);
            assertFalse("the Lab stayed marked busy, so every later change would be refused",
                    ((java.util.concurrent.atomic.AtomicBoolean) changing.get(null)).get());

            // And the next change is not turned away.
            ShadowToast.reset();
            reset.invoke(fragment, false);
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotEquals("A Lab change is already running", ShadowToast.getTextOfLatestToast());
        }
    }

    @Test public void aFullWorkerQueueRejectsALabChangeWithoutLeavingItBusy() throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var fragment = attach(owner.get());
            save("gate", "true", true);
            var reset = FeatureGateLabFragment.class.getDeclaredMethod("reset", boolean.class);
            reset.setAccessible(true);
            var changingField = FeatureGateLabFragment.class.getDeclaredField("CHANGING");
            changingField.setAccessible(true);
            var changing = (java.util.concurrent.atomic.AtomicBoolean) changingField.get(null);

            try (BackgroundPoolSaturation saturation = BackgroundPoolSaturation.fill()) {
                ShadowToast.reset();
                reset.invoke(fragment, false);
                Shadows.shadowOf(Looper.getMainLooper()).idle();

                assertEquals("Couldn't start the Lab change. Try again in a moment.",
                        ShadowToast.getTextOfLatestToast());
                assertFalse("the Lab remained busy after rejected scheduling", changing.get());
                assertNotNull("the rejected reset changed Lab storage",
                        FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN"));

                saturation.release();
                ShadowToast.reset();
                reset.invoke(fragment, false);
                settle();
                assertNull("the same reset could not be retried",
                        FeatureGateLabStore.rule("abmock", "gate", "BOOLEAN"));
                assertFalse(changing.get());
                // "Restart TikTok to apply this." is the one sentence the whole settings screen
                // uses now; this message said it a ninth way.
                assertEquals("Lab overrides reset. Undo last Lab change is in the menu."
                                + " Restart TikTok to apply this.",
                        ShadowToast.getTextOfLatestToast());
            }
        }
    }

    @Test public void aRejectedMasterSwitchReturnsToStorageAndRetriesOnTheNextTap()
            throws Exception {
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var fragment = attach(owner.get());
            Switch master = findSwitch(fragment.getView());
            assertFalse(FeatureGateLabStore.masterEnabled());
            assertFalse(master.isChecked());

            try (BackgroundPoolSaturation saturation = BackgroundPoolSaturation.fill()) {
                ShadowToast.reset();
                master.performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();

                assertEquals("Couldn't start the Lab change. Try again in a moment.",
                        ShadowToast.getTextOfLatestToast());
                assertFalse("the rejected change altered storage",
                        FeatureGateLabStore.masterEnabled());
                assertFalse("the rejected switch stayed ahead of storage", master.isChecked());

                saturation.release();
                master.performClick();
                settle();
                assertTrue("the first retry tap was consumed repairing stale UI",
                        FeatureGateLabStore.masterEnabled());
                assertTrue(master.isChecked());
            }
        }
    }

    @Test public void theMasterSwitchDoesItsStorageOffTheMainThread() throws Exception {
        // Turning overrides on takes the journal lock and does two write-and-verify cycles. On
        // the main thread that is a frozen screen for as long as a settings restore holds that
        // lock, which is exactly what the Lab's other changes were written to avoid.
        try (var owner = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = owner.get();
            var fragment = attach(activity);
            Switch master = findSwitch(fragment.getView());
            boolean before = FeatureGateLabStore.masterEnabled();
            // The hook is static and every Lab change writes it, so an earlier test in this
            // class would otherwise answer for this one.
            forgetLastChangeThread();

            master.performClick();
            settle();

            assertEquals("the switch did not take", !before, FeatureGateLabStore.masterEnabled());
            assertEquals("the switch does not show what was stored", !before, master.isChecked());
            String thread = FeatureGateLabFragment.lastChangeThreadForTests();
            assertNotNull("nothing recorded a Lab change at all", thread);
            assertNotEquals("the master switch wrote to storage on the drawing thread",
                    Looper.getMainLooper().getThread().getName(), thread);
        }
    }

    private static void forgetLastChangeThread() throws Exception {
        var field = FeatureGateLabFragment.class.getDeclaredField("lastChangeThreadForTests");
        field.setAccessible(true);
        field.set(null, null);
    }

    /** Lets a Lab change finish: the storage half, then the part that puts the screen back. */
    @Test public void aLongPressGathersASelectionAndTheActionsActOnAllOfIt() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = controller.get();
            var fragment = attach(activity);
            settle();

            android.widget.ListView list = listOf(fragment);
            assertEquals("the fixture catalogue is not on the screen", 2, list.getCount());
            android.view.View bar = selectionBar(fragment);
            assertEquals("the actions were offered before anything was chosen",
                    android.view.View.GONE, bar.getVisibility());

            // A long press gathers, a tap adds, and a tap on a chosen row takes it back out.
            assertTrue(list.performItemClick(null, 0, list.getItemIdAtPosition(0)));
            assertEquals("a plain tap started a selection",
                    android.view.View.GONE, bar.getVisibility());

            assertTrue(list.getOnItemLongClickListener()
                    .onItemLongClick(list, null, 0, list.getItemIdAtPosition(0)));
            assertEquals(android.view.View.VISIBLE, bar.getVisibility());
            assertEquals("1 gate selected", selectionCountText(fragment));
            android.view.View stillActionable = list.getAdapter().getView(1, null, list);
            assertEquals("an unselected row was dimmed below readable contrast",
                    1f, stillActionable.getAlpha(), 0.001f);
            assertTrue(stillActionable.isEnabled());

            // The chosen row has to look chosen. Enable, Disable and Reset act on whatever is in
            // the selection, so a selection nobody can see is a selection acted on by accident.
            android.view.View picked = list.getAdapter().getView(0, null, list);
            assertTrue("the chosen row is not marked as activated",
                    contains(picked.getBackground().getState(), android.R.attr.state_activated));
            assertFalse("a row nobody chose is marked as activated",
                    contains(stillActionable.getBackground().getState(),
                            android.R.attr.state_activated));
            // And says so in a shape as well as a colour.
            assertEquals("the chosen row carries no mark a colour-blind reader can see",
                    android.view.View.VISIBLE, leadingMark(picked).getVisibility());
            assertEquals("an unchosen row is marked as chosen",
                    android.view.View.GONE, leadingMark(stillActionable).getVisibility());

            assertTrue(list.performItemClick(null, 1, list.getItemIdAtPosition(1)));
            assertEquals("2 gates selected", selectionCountText(fragment));
            assertTrue(list.performItemClick(null, 1, list.getItemIdAtPosition(1)));
            assertEquals("1 gate selected", selectionCountText(fragment));
            assertTrue(list.performItemClick(null, 1, list.getItemIdAtPosition(1)));

            // Disable acts on both, in one operation, and leaves the selection behind it.
            selectionAction(fragment, "Disable").performClick();
            settle();
            var rules = FeatureGateLabStore.rules();
            assertEquals("both gates were not written", 2, rules.size());
            for (var rule : rules) assertEquals("false", rule.value);
            assertEquals("the selection outlived the action it ran",
                    android.view.View.GONE, bar.getVisibility());

            // And one Undo takes both back, which is the whole reason for one operation.
            FeatureGateLabUndo.undo();
            assertTrue(FeatureGateLabStore.rules().isEmpty());
        }
    }

    /**
     * A row's own accessibility delegate kept the list from offering its items' click, so a
     * switch or voice user couldn't open a gate, and Select did nothing. The list also ended a
     * fixed 80 dp above a bar taller than that, so the last gate stayed under it.
     */
    @Test public void aRowAnswersItsAccessibilityActionsAndTheListEndsAboveTheBar() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var fragment = attach(controller.get());
            settle();
            android.widget.ListView list = listOf(fragment);
            list.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 1080, 1920);
            android.view.View row = list.getChildAt(0);
            assertNotNull("the list laid out no rows", row);
            var info = row.createAccessibilityNodeInfo();
            assertTrue("a row offers no click: " + info.getActionList(), info.getActionList().contains(
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            assertNotNull("a row lost its place in the list", info.getCollectionItemInfo());

            assertTrue(row.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, null));
            android.view.View bar = selectionBar(fragment);
            assertEquals("Select did not start a selection", android.view.View.VISIBLE, bar.getVisibility());

            android.view.View root = fragment.getView();
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1920, android.view.View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1080, 1920);
            assertTrue("the list ends under the bar", list.getPaddingBottom() >= bar.getHeight());
        }
    }

    /**
     * A selection with nothing to reset says so. The refusal's own sentence used to be swapped
     * for "Try again", which fails the same way every time it is tried.
     */
    @Test public void aRefusedSelectionSaysWhyInsteadOfTryAgain() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var fragment = attach(controller.get());
            settle();
            android.widget.ListView list = listOf(fragment);
            assertTrue(list.getOnItemLongClickListener()
                    .onItemLongClick(list, null, 0, list.getItemIdAtPosition(0)));

            ShadowToast.reset();
            selectionAction(fragment, "Reset").performClick();
            settle();
            assertEquals("None of these gates had an override to reset.",
                    ShadowToast.getTextOfLatestToast());
        }
    }

    /**
     * The selection actions fit the bar they sit in, and Cancel does not read like Enable.
     *
     * <p>Four bold labels with 24dp of side padding in a horizontal row that cannot wrap: at 2x
     * text on a 360dp phone the later ones were squeezed to their 48dp minimum and their words
     * cut. German does it at ordinary size. Cancel was painted in the accent and bold, the same
     * as Enable, so the action that undoes the selection looked like the one that writes to
     * every gate in it.
     */
    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2)
    public void theSelectionActionsFitTheBarAndRankThemselvesInTheDark() throws Exception {
        assertSelectionActionsFitAndRank();
    }

    @Test @Config(qualifiers = "w360dp-h640dp-notnight-mdpi", fontScale = 2)
    public void theSelectionActionsFitTheBarAndRankThemselvesInTheLight() throws Exception {
        assertSelectionActionsFitAndRank();
    }

    private void assertSelectionActionsFitAndRank() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = controller.get();
            var fragment = attach(activity);
            settle();
            // Without this the rest measures a bar at ordinary text size and finds, correctly,
            // that four short English words fit.
            assertEquals("the font scale did not take",
                    2f, activity.getResources().getConfiguration().fontScale, 0.01f);

            android.widget.ListView list = listOf(fragment);
            assertTrue(list.getOnItemLongClickListener()
                    .onItemLongClick(list, null, 0, list.getItemIdAtPosition(0)));
            android.view.ViewGroup bar = (android.view.ViewGroup) selectionBar(fragment);
            assertEquals(android.view.View.VISIBLE, bar.getVisibility());

            int width = FeatureGateLabUi.dp(activity, 360);
            bar.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                            width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                            0, android.view.View.MeasureSpec.UNSPECIFIED));
            bar.layout(0, 0, bar.getMeasuredWidth(), bar.getMeasuredHeight());

            android.view.ViewGroup actions = (android.view.ViewGroup) bar.getChildAt(1);
            int left = actions.getPaddingLeft();
            int right = actions.getWidth() - actions.getPaddingRight();
            assertTrue("the actions row was not laid out", right > left);

            List<android.widget.TextView> laid = new ArrayList<>();
            for (int index = 0; index < actions.getChildCount(); index++) {
                laid.add((android.widget.TextView) actions.getChildAt(index));
            }
            assertEquals("the bar does not carry the four actions", 4, laid.size());

            for (android.widget.TextView action : laid) {
                String label = String.valueOf(action.getContentDescription());
                assertTrue(label + " runs off the start of the bar", action.getLeft() >= left);
                assertTrue(label + " runs off the end of the bar at " + action.getRight()
                        + ", past " + right, action.getRight() <= right);
                // Wide enough for the word, not just for the 48dp box it falls back to.
                double needed = Math.ceil(android.text.Layout.getDesiredWidth(
                        action.getText(), action.getPaint()))
                        + action.getPaddingLeft() + action.getPaddingRight();
                assertTrue(label + " is " + action.getWidth() + " wide and its label needs "
                        + needed, action.getWidth() >= needed);
                assertTrue(label + " has no height", action.getHeight() > 0);
            }

            // Wrapping onto another line is only a fix if the lines do not sit on each other.
            for (int i = 0; i < laid.size(); i++) {
                for (int j = i + 1; j < laid.size(); j++) {
                    android.view.View a = laid.get(i);
                    android.view.View b = laid.get(j);
                    boolean apart = a.getRight() <= b.getLeft() || b.getRight() <= a.getLeft()
                            || a.getBottom() <= b.getTop() || b.getBottom() <= a.getTop();
                    assertTrue(a.getContentDescription() + " overlaps " + b.getContentDescription(),
                            apart);
                }
            }

            android.widget.TextView cancel =
                    (android.widget.TextView) selectionAction(fragment, "Cancel");
            android.widget.TextView enable =
                    (android.widget.TextView) selectionAction(fragment, "Enable");
            assertEquals("Cancel is not painted as the secondary action",
                    SettingsUi.textSecondary(), cancel.getCurrentTextColor());
            assertNotEquals("Cancel and Enable are painted alike",
                    enable.getCurrentTextColor(), cancel.getCurrentTextColor());
            // setTypeface(tf, NORMAL) keeps whatever typeface it is handed, so a button built
            // bold and then styled secondary stays bold.
            assertFalse("Cancel is as heavy as the action that writes to every chosen gate",
                    cancel.getTypeface() != null && cancel.getTypeface().isBold());
            assertTrue("Enable lost the weight that marks it as the primary action",
                    enable.getTypeface() != null && enable.getTypeface().isBold());
        }
    }

    private static boolean contains(int[] states, int wanted) {
        for (int state : states) if (state == wanted) return true;
        return false;
    }

    /** The mark at the start of a Lab row, which is the first child whatever the layout. */
    private static android.view.View leadingMark(android.view.View row) {
        return ((android.view.ViewGroup) row).getChildAt(0);
    }

    private static android.widget.ListView listOf(FeatureGateLabFragment fragment) throws Exception {
        var field = FeatureGateLabFragment.class.getDeclaredField("list");
        field.setAccessible(true);
        return (android.widget.ListView) field.get(fragment);
    }

    private static android.view.View selectionBar(FeatureGateLabFragment fragment) throws Exception {
        var field = FeatureGateLabFragment.class.getDeclaredField("selectionBar");
        field.setAccessible(true);
        return (android.view.View) field.get(fragment);
    }

    private static String selectionCountText(FeatureGateLabFragment fragment) throws Exception {
        var field = FeatureGateLabFragment.class.getDeclaredField("selectionCount");
        field.setAccessible(true);
        return ((android.widget.TextView) field.get(fragment)).getText().toString();
    }

    /** The action with this label, found by the description it gives a screen reader. */
    private static android.view.View selectionAction(FeatureGateLabFragment fragment, String label)
            throws Exception {
        android.view.ViewGroup bar = (android.view.ViewGroup) selectionBar(fragment);
        android.view.ViewGroup actions = (android.view.ViewGroup) bar.getChildAt(1);
        for (int index = 0; index < actions.getChildCount(); index++) {
            android.view.View child = actions.getChildAt(index);
            if (label.contentEquals(String.valueOf(child.getContentDescription()))) return child;
        }
        throw new AssertionError("no selection action called " + label);
    }

    private static void settle() throws Exception {
        FeatureGateLabFragment.awaitFileIoForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static FeatureGateLabFragment attach(Activity activity) {
        var fragment = new FeatureGateLabFragment();
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
    /**
     * The overflow action in position {@code id}, counting from one.
     *
     * <p>It was a platform PopupMenu and is a list in the bundle's own dialog surface now, so
     * this goes through the list. The enabled check is part of the point: Undo used to be
     * offered whether or not there was anything to undo.
     */
    private static void action(FeatureGateLabFragment fragment, int id) {
        assertTrue(fragment.getView().findViewWithTag("feature_gate_menu").performClick());
        var menu = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("the overflow did not open", menu);
        android.widget.ListView list = menu.getListView();
        assertNotNull("the overflow has no items", list);
        // These IDs name actions, not their positions after adding another menu entry.
        String[] labels = {"Refresh values", "Export loaded values", "Import loaded values",
                "Remove all overrides", "Clear all Lab data (overrides, switch, recordings)",
                "Undo last Lab change"};
        int position = -1;
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            if (labels[id - 1].equals(list.getAdapter().getItem(index))) position = index;
        }
        assertTrue("missing action " + labels[id - 1], position >= 0);
        assertTrue("overflow item " + position + " is offered but cannot be taken",
                list.getAdapter().isEnabled(position));
        assertTrue(list.performItemClick(list.getAdapter().getView(position, null, list),
                position, list.getAdapter().getItemId(position)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // The overflow is itself a dialog now. Leave no trace of it, or every assertion about
        // the dialog an action raises would find this one instead.
        ShadowDialog.reset();
    }
    /** The import result dialog, showing, with each expected line somewhere in its view; then dismissed. */
    private static void waitForImportDialog(String... lines) throws Exception {
        FeatureGateLabFragment.awaitFileIoForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        var dialog = ShadowDialog.getLatestDialog();
        assertNotNull("Missing import result dialog; last toast: " + ShadowToast.getTextOfLatestToast(), dialog);
        assertTrue(dialog.isShowing());
        var texts = new java.util.ArrayList<String>();
        collectTexts(Shadows.shadowOf((android.app.AlertDialog) dialog).getView(), texts);
        for (String line : lines) {
            assertTrue(line + " is not among " + texts, texts.contains(line));
        }
        dialog.dismiss();
    }

    private static void collectTexts(View view, java.util.List<String> into) {
        if (view instanceof android.widget.TextView) {
            into.add(((android.widget.TextView) view).getText().toString());
        }
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectTexts(group.getChildAt(i), into);
        }
    }

    private static void waitFor(String prefix) throws Exception {
        FeatureGateLabFragment.awaitFileIoForTests();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        String toast = ShadowToast.getTextOfLatestToast();
        if (toast == null || !toast.startsWith(prefix)) {
            fail("Missing completion: " + toast);
        }
    }

    /**
     * The log holds exactly one line for {@code message}, at {@code level}, and none for
     * {@code absent}. A refusal the reader is told is INFO; filed as a patch error, its debug
     * toast covered the reason.
     */
    static void assertLoggedOnce(String level, String message, String absent) {
        int lines = 0;
        for (String line : LogBufferManager.buildExportText().split("\n")) {
            assertFalse("logged as a failure: " + line, line.contains(absent));
            if (!line.contains(message)) continue;
            lines++;
            assertTrue("logged at the wrong level: " + line, line.contains("| " + level + " | " + message));
        }
        assertEquals("no line for " + message, 1, lines);
    }

    private static Switch findSwitch(View view) {
        if (view instanceof Switch) return (Switch) view;
        if (view instanceof ViewGroup) {
            var group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Switch found = findSwitch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void awholeSelectionIsForcedInOneOperationAndUndoneInOne() throws Exception {
        // Three gates chosen together. The point of doing it in one operation rather than three
        // is that a selection half applied is worse than one not applied, and that Undo puts the
        // whole thing back rather than the last gate of it.
        var gates = List.of(entry("alpha"), entry("beta"), entry("gamma"));

        assertEquals(3, FeatureGateLabUndo.forceBoolean(gates, false));
        var rules = FeatureGateLabStore.rules();
        assertEquals(3, rules.size());
        for (var rule : rules) {
            assertEquals("a gate in the selection was not forced", "false", rule.value);
            assertTrue("a forced gate was left switched off", rule.enabled);
        }

        FeatureGateLabUndo.undo();
        assertTrue("undo left part of the selection behind",
                FeatureGateLabStore.rules().isEmpty());

        // And the other direction, over rules that already exist, so this is a replacement
        // rather than three fresh writes.
        save("alpha", "false", true);
        assertEquals(3, FeatureGateLabUndo.forceBoolean(gates, true));
        assertEquals(3, FeatureGateLabStore.rules().size());
        for (var rule : FeatureGateLabStore.rules()) {
            assertEquals("true", rule.value);
        }

        FeatureGateLabUndo.undo();
        var afterUndo = FeatureGateLabStore.rules();
        assertEquals("undo did not go back to the one rule there was", 1, afterUndo.size());
        assertEquals("alpha", afterUndo.get(0).key);
        assertEquals("false", afterUndo.get(0).value);
    }

    @Test public void resettingASelectionDropsOnlyItsRulesAndUndoBringsThemBack() throws Exception {
        save("alpha", "true", true);
        save("beta", "true", true);
        save("kept", "true", true);

        assertEquals(2, FeatureGateLabUndo.resetAll(List.of(entry("alpha"), entry("beta"))));
        var rules = FeatureGateLabStore.rules();
        assertEquals(1, rules.size());
        assertEquals("a rule outside the selection was dropped", "kept", rules.get(0).key);

        FeatureGateLabUndo.undo();
        assertEquals(3, FeatureGateLabStore.rules().size());
    }

    @Test public void aSelectionWithNothingToDoIsNotAnOperationAtAll() throws Exception {
        // Nothing to force means no journal entry and no undo point, so an Undo after it still
        // means whatever it meant before. A gate whose type the Lab cannot force a boolean on is
        // skipped rather than written with a value its own catalogue disagrees with.
        var text = new FeatureGateCatalog.Entry("worded", "worded", "abmock", "STRING", true, true,
                List.of(""), List.of(), List.of(), "", "", true, "", "STRING");
        assertEquals(0, FeatureGateLabUndo.forceBoolean(List.of(text), false));
        assertTrue(FeatureGateLabStore.rules().isEmpty());

        assertEquals(0, FeatureGateLabUndo.resetAll(List.of(entry("never_saved"))));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
    }

    private static FeatureGateCatalog.Entry entry(String key) {
        return new FeatureGateCatalog.Entry(key, key, "abmock", "BOOLEAN", true, true,
                List.of("false"), List.of(), List.of(), "", "", true, "false", "BOOLEAN");
    }
    private static JSONObject rule(String key, String value) throws Exception {
        return new JSONObject().put("manager", "abmock").put("key", key).put("type", "BOOLEAN").put("value", value);
    }
    private static JSONObject loadedValues(JSONArray rules) throws Exception {
        return new JSONObject().put("payload_kind", "loaded_values")
                .put("tiktok_version", FeatureGateLabStore.targetVersion()).put("rules", rules);
    }
    /** A settings-manager observation shaped like the ones a 47.4.3 export carries. */
    private static JSONObject observation(int index) throws Exception {
        JSONObject value = new JSONObject().put("__class", "com.bytedance.example.Config" + index);
        JSONObject fieldTypes = new JSONObject();
        for (int field = 0; field < 30; field++) {
            value.put("enableField" + field, field % 2 == 0);
            fieldTypes.put("enableField" + field, "boolean");
        }
        value.put("__field_types", fieldTypes);
        return new JSONObject().put("title", "Config " + index).put("key", "config_" + index)
                .put("manager", "live").put("source_type", "runtime_observed_live_settings_call")
                .put("type", "JSON").put("loaded", true).put("runtime_only", true)
                .put("default_value", value).put("current_value", new JSONObject(value.toString()))
                .put("observed_at_ms", 1_760_000_000_000L + index);
    }
    private static void save(String key, String value, boolean enabled) {
        FeatureGateLabStore.saveRule("abmock", key, "BOOLEAN", value, enabled);
    }
    public static final class Sample { public String name = "日本語"; public List<Integer> values = List.of(2, 7); }
    public static class TestActivity extends Activity {
        @Override protected void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    /** The settings window, whose content root carries the tag TikTokActivityHook gives it, so banners show. */
    public static class SettingsWindow extends TestActivity {
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            findViewById(android.R.id.content).setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        }
    }
}
