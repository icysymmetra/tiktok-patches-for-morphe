package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.Utils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeatureGateLabRuleLimitTest {
    private static final String LIMIT_MESSAGE =
            "The Lab keeps at most 1,024 rules. This change would make 1,025, so nothing was changed.";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        FeatureGateLabUndo.resetForTests();
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabStore.resetAllLabData();
        deleteUndoFiles();
    }

    @After public void tearDown() {
        FeatureGateLabStore.resetAllLabData();
        SettingsManagerObservationRecorder.clear();
        deleteUndoFiles();
    }

    @Test public void exactly1024RulesCanBeSavedAndUndone() throws Exception {
        FeatureGateLabStore.replaceSettings(rules(FeatureGateLabStore.MAX_RULES), false, false);

        FeatureGateLabUndo.saveRule(
                "abmock", key(FeatureGateLabStore.MAX_RULES - 1), "BOOLEAN", "false", true);
        assertEquals(FeatureGateLabStore.MAX_RULES, FeatureGateLabStore.rules().size());
        assertEquals("false", FeatureGateLabStore.rule(
                "abmock", key(FeatureGateLabStore.MAX_RULES - 1), "BOOLEAN").value);

        FeatureGateLabUndo.undo();
        assertEquals(FeatureGateLabStore.MAX_RULES, FeatureGateLabStore.rules().size());
        assertEquals("true", FeatureGateLabStore.rule(
                "abmock", key(FeatureGateLabStore.MAX_RULES - 1), "BOOLEAN").value);
    }

    @Test public void everyWriteRouteRejects1025BeforeStorageChanges() throws Exception {
        List<FeatureGateLabStore.Rule> full = rules(FeatureGateLabStore.MAX_RULES);
        FeatureGateLabStore.replaceSettings(full, false, false);
        String before = FeatureGateLabStore.exportSettings().toString();

        assertFalse(FeatureGateLabStore.saveRule(
                "abmock", "direct-overflow", "BOOLEAN", "true", true));
        assertEquals(before, FeatureGateLabStore.exportSettings().toString());

        Exception boundary = assertThrows(Exception.class, () -> {
            List<FeatureGateLabStore.Rule> overflow = new ArrayList<>(full);
            overflow.add(rule("boundary-overflow"));
            FeatureGateLabStore.replaceSettings(overflow, false, false);
        });
        assertEquals(LIMIT_MESSAGE, boundary.getMessage());
        assertEquals(before, FeatureGateLabStore.exportSettings().toString());

        Exception single = assertThrows(Exception.class, () -> FeatureGateLabUndo.saveRule(
                "abmock", "single-overflow", "BOOLEAN", "true", true));
        assertEquals(LIMIT_MESSAGE, single.getMessage());
        assertEquals(before, FeatureGateLabStore.exportSettings().toString());
        assertFalse(undoBase().isFile());
        assertFalse(undoBackup().isFile());

        Exception bulk = assertThrows(Exception.class,
                () -> FeatureGateLabUndo.forceBoolean(List.of(entry("bulk-overflow")), true));
        assertEquals(LIMIT_MESSAGE, bulk.getMessage());
        assertEquals(before, FeatureGateLabStore.exportSettings().toString());
        assertFalse(undoBase().isFile());
        assertFalse(undoBackup().isFile());
    }

    @Test public void importing1024RulesOverOneDistinctRuleReportsTheResultingOverflow()
            throws Exception {
        FeatureGateLabStore.replaceSettings(List.of(rule("existing")), false, false);
        String before = FeatureGateLabStore.exportSettings().toString();
        List<FeatureGateLabStore.Rule> incoming = new ArrayList<>();
        for (int index = 0; index < FeatureGateLabStore.MAX_RULES; index++) {
            incoming.add(rule("import-" + index));
        }

        Exception error = assertThrows(Exception.class, () -> FeatureGateLabUndo.importRules(
                new FeatureGateLabStore.ImportReview(incoming, List.of())));

        assertEquals(LIMIT_MESSAGE, error.getMessage());
        assertEquals(before, FeatureGateLabStore.exportSettings().toString());
        assertFalse(undoBase().isFile());
        assertFalse(undoBackup().isFile());
    }

    @Test public void resetAllLabDataRecoversASeeded1025RuleStateWithoutParsingIt()
            throws Exception {
        FeatureGateLabStore.replaceSettings(rules(FeatureGateLabStore.MAX_RULES), true, true);
        FeatureGateLabUndo.saveRule("abmock", key(0), "BOOLEAN", "false", true);
        assertTrue(undoBase().isFile());
        appendRawRule("seeded-overflow");
        SettingsManagerObservationRecorder.observeWithoutDefault(
                "observed-before-recovery", Boolean.class, Boolean.TRUE);
        assertEquals(FeatureGateLabStore.MAX_RULES + 1, FeatureGateLabStore.rules().size());
        assertNotNull(FeatureGateLabStore.rule("abmock", "seeded-overflow", "BOOLEAN"));

        assertFalse(FeatureGateLabUndo.reset(true));

        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertFalse(FeatureGateLabStore.warningAcknowledged());
        assertEquals(0, SettingsManagerObservationRecorder.size());
        assertNull(FeatureGateLabStore.rule("abmock", "seeded-overflow", "BOOLEAN"));
        assertFalse(undoBase().isFile());
        assertFalse(undoBackup().isFile());
    }

    private static List<FeatureGateLabStore.Rule> rules(int count) {
        List<FeatureGateLabStore.Rule> rules = new ArrayList<>(count);
        for (int index = 0; index < count; index++) rules.add(rule(key(index)));
        return rules;
    }

    private static FeatureGateLabStore.Rule rule(String key) {
        String id = FeatureGateLabStore.idFor("abmock", key, "BOOLEAN");
        return new FeatureGateLabStore.Rule(
                id, "abmock", key, "BOOLEAN", "true", true, 1L);
    }

    private static FeatureGateCatalog.Entry entry(String key) {
        return new FeatureGateCatalog.Entry(key, key, "abmock", "BOOLEAN", true, true,
                List.of("false"), List.of(), List.of(), "", "", true, "false", "BOOLEAN");
    }

    private static String key(int index) {
        return String.format(java.util.Locale.ROOT, "rule-%04d", index);
    }

    private void appendRawRule(String key) {
        SharedPreferences prefs = context.getSharedPreferences("morphe_feature_gate_lab", 0);
        String id = FeatureGateLabStore.idFor("abmock", key, "BOOLEAN");
        String ids = prefs.getString("rule_ids", "");
        String prefix = "rule." + id + ".";
        assertTrue(prefs.edit()
                .putString("rule_ids", ids + "\n" + id)
                .putString(prefix + "manager", "abmock")
                .putString(prefix + "key", key)
                .putString(prefix + "type", "BOOLEAN")
                .putString(prefix + "value", "true")
                .putBoolean(prefix + "enabled", true)
                .putLong(prefix + "updated", 1L)
                .commit());
    }

    private void deleteUndoFiles() {
        assertFalse(undoBase().isFile() && !undoBase().delete());
        assertFalse(undoBackup().isFile() && !undoBackup().delete());
    }

    private File undoBase() {
        return new File(context.getFilesDir(), "feature-gate-lab-undo.json");
    }

    private File undoBackup() {
        return new File(undoBase().getPath() + ".bak");
    }
}
