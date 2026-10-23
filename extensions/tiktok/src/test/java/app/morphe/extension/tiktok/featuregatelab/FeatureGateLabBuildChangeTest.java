package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;

import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsBackup;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The Lab is for the TikTok build that is installed, and a rule moves between builds only where
 * the catalogs say its gate is still the same gate.
 *
 * <p>The bundle declares only the newest build, so the build a user moves from is no longer in
 * the catalog: 47.1.4, which 47.4.3 replaced, stands for it here. The gates below are 47.4.3's:
 * 1005_max_limit_count_daily, low_memory_kill_monitor, AWEDanmakuSupportMask and the
 * lynxview_command_blacklist read (a String[] model) are in its catalog; comment_cell_badge_dedup
 * isn't; and the SettingsManager read drama_innerfeed_lynxcard_delete_card_on_error_config has a
 * model class R8 renamed (X.0RaO on 47.4.3).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeatureGateLabBuildChangeTest {
    private static final String DROPPED = "47.1.4";
    private static final String DECLARED = "47.4.3";

    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Before
    public void setUp() {
        BuildNames.setRunningBuildForTests(DROPPED);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.consumeMigrationNotice();
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
    }

    @After
    public void tearDown() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BuildNames.setRunningBuildForTests(DROPPED);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.consumeMigrationNotice();
        FeatureGateLabRuntime.reloadRules();
        BuildNames.setRunningBuildForTests(null);
    }

    @Test
    public void theTargetEveryExportNamesIsTheInstalledBuild() throws Exception {
        for (String build : new String[]{DECLARED, "47.2.1"}) {
            BuildNames.setRunningBuildForTests(build);
            assertEquals(build, FeatureGateLabStore.targetVersion());
            assertEquals(build, new JSONObject(FeatureGateLabStore.exportProfile()).getString("tiktok_version"));
            JSONObject backup = new JSONObject(SettingsBackup.create(false));
            assertEquals(build, backup.getString("target"));
            assertEquals(build, backup.getJSONObject("lab").getString("tiktok_version"));
        }
    }

    @Test
    public void anUpgradeFromABuildTheCatalogDroppedKeepsTheRulesWhoseGatesTheNewBuildCarries() {
        assertFalse("the build moved from still has a catalog", FeatureGateCatalog.hasCatalogFor(DROPPED));
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        save("abmock", "low_memory_kill_monitor", "INT", "50", true);
        save("player_config", "AWEDanmakuSupportMask", "BOOLEAN", "true", true);
        save("settings_manager", "lynxview_command_blacklist", "OBJECT", "{\"LIZ\":true}", true);
        save("abmock", "comment_cell_badge_dedup", "BOOLEAN", "true", true);
        save("settings_manager", "drama_innerfeed_lynxcard_delete_card_on_error_config", "OBJECT",
                "{\"LIZ\":true}", true);
        // The same gate under a type its catalog row doesn't carry, and one no catalog has.
        save("abmock", "1005_max_limit_count_daily", "LONG", "9", true);
        save("abmock", "only_seen_at_runtime", "BOOLEAN", "true", true);
        // Already off: left off, and not counted as turned off.
        save("abmock", "comment_cell_bind_dedup", "BOOLEAN", "true", false);
        assertEquals(9, FeatureGateLabRuntime.overrideInt("1005_max_limit_count_daily", 5));

        upgradeTo(DECLARED);

        assertTrue(enabled("abmock", "1005_max_limit_count_daily", "INT"));
        assertTrue(enabled("abmock", "low_memory_kill_monitor", "INT"));
        assertTrue(enabled("player_config", "AWEDanmakuSupportMask", "BOOLEAN"));
        assertTrue("a SettingsManager read whose model keeps its name",
                enabled("settings_manager", "lynxview_command_blacklist", "OBJECT"));
        assertFalse("a gate 47.4.3 doesn't have", enabled("abmock", "comment_cell_badge_dedup", "BOOLEAN"));
        assertFalse("a SettingsManager model R8 renamed",
                enabled("settings_manager", "drama_innerfeed_lynxcard_delete_card_on_error_config", "OBJECT"));
        assertFalse("a type the catalog doesn't carry", enabled("abmock", "1005_max_limit_count_daily", "LONG"));
        assertFalse("a gate no catalog has", enabled("abmock", "only_seen_at_runtime", "BOOLEAN"));
        assertFalse(enabled("abmock", "comment_cell_bind_dedup", "BOOLEAN"));
        // Every rule is still there to review, with the value it had.
        assertEquals("true", FeatureGateLabStore.rule("abmock", "comment_cell_badge_dedup", "BOOLEAN").value);
        assertTrue("the master switch keeps what carried over working", FeatureGateLabStore.masterEnabled());

        assertEquals(9, FeatureGateLabRuntime.overrideInt("1005_max_limit_count_daily", 5));
        assertEquals(50, FeatureGateLabRuntime.overrideInt("low_memory_kill_monitor", 89));

        assertEquals(4, FeatureGateLabStore.consumeMigrationNotice());
        assertEquals("the notice is shown once", 0, FeatureGateLabStore.consumeMigrationNotice());
    }

    @Test
    public void aBuildWithNoCatalogTurnsEveryRuleOff() {
        BuildNames.setRunningBuildForTests(DECLARED);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        save("player_config", "AWEDanmakuSupportMask", "BOOLEAN", "true", true);

        upgradeTo("47.2.1");

        assertEquals("47.2.1", FeatureGateLabStore.targetVersion());
        assertFalse(enabled("abmock", "1005_max_limit_count_daily", "INT"));
        assertFalse(enabled("player_config", "AWEDanmakuSupportMask", "BOOLEAN"));
        assertEquals(5, FeatureGateLabRuntime.overrideInt("1005_max_limit_count_daily", 5));
        assertEquals(2, FeatureGateLabStore.consumeMigrationNotice());
    }

    @Test
    public void aSecondaryProcessAppliesNoRuleBeforeTheMainProcessMovesThem() {
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        save("abmock", "comment_cell_badge_dedup", "BOOLEAN", "true", true);

        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(new ContextWrapper(app) {
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.processName = app.getPackageName() + ":push";
                return info;
            }
        });
        BuildNames.setRunningBuildForTests(DECLARED);
        FeatureGateLabRuntime.reloadRules();
        assertFalse(Utils.isMainProcess());
        assertEquals("a rule stored for 47.1.4 applied in a 47.4.3 process that hadn't checked it",
                5, FeatureGateLabRuntime.overrideInt("1005_max_limit_count_daily", 5));
        assertFalse(FeatureGateLabRuntime.overrideBoolean("comment_cell_badge_dedup", false));

        Utils.setContext(app);
        FeatureGateLabRuntime.reloadRules();
        assertEquals("the main process moved the rules and applies what carried over",
                9, FeatureGateLabRuntime.overrideInt("1005_max_limit_count_daily", 5));
        assertFalse(FeatureGateLabRuntime.overrideBoolean("comment_cell_badge_dedup", false));
    }

    @Test
    public void aBackupFromTheDeclaredBuildRestoresItsRulesAsTheyWere() throws Exception {
        BuildNames.setRunningBuildForTests(DECLARED);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        save("abmock", "low_memory_kill_monitor", "INT", "50", true);
        String backup = SettingsBackup.create(false);
        assertEquals(DECLARED, new JSONObject(backup).getString("target"));

        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.consumeMigrationNotice();
        SettingsBackup.restore(Utils.getContext(), backup, true);

        assertFalse("the Lab half was left out", SettingsBackup.labRulesWereSkipped(backup));
        assertTrue(enabled("abmock", "1005_max_limit_count_daily", "INT"));
        assertTrue(enabled("abmock", "low_memory_kill_monitor", "INT"));
        assertTrue(FeatureGateLabStore.masterEnabled());
        assertEquals(0, FeatureGateLabStore.consumeMigrationNotice());
    }

    @Test
    public void aBackupFromABuildTheCatalogDroppedKeepsTheRulesWhoseGatesTheRunningBuildCarries() throws Exception {
        assertFalse("the build the backup was made on still has a catalog", FeatureGateCatalog.hasCatalogFor(DROPPED));
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        save("player_config", "AWEDanmakuSupportMask", "BOOLEAN", "true", true);
        save("settings_manager", "lynxview_command_blacklist", "OBJECT", "{\"LIZ\":true}", true);
        save("abmock", "comment_cell_badge_dedup", "BOOLEAN", "true", true);
        save("settings_manager", "drama_innerfeed_lynxcard_delete_card_on_error_config", "OBJECT",
                "{\"LIZ\":true}", true);
        save("abmock", "comment_cell_bind_dedup", "BOOLEAN", "true", false);
        String backup = SettingsBackup.create(false);
        assertEquals(DROPPED, new JSONObject(backup).getString("target"));
        assertEquals(DROPPED, new JSONObject(backup).getJSONObject("lab").getString("tiktok_version"));

        BuildNames.setRunningBuildForTests(DECLARED);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.consumeMigrationNotice();
        FeatureGateLabRuntime.reloadRules();
        SettingsBackup.restore(Utils.getContext(), backup, true);

        assertFalse("the Lab half was left out", SettingsBackup.labRulesWereSkipped(backup));
        assertTrue(enabled("abmock", "1005_max_limit_count_daily", "INT"));
        assertTrue(enabled("player_config", "AWEDanmakuSupportMask", "BOOLEAN"));
        assertTrue("a SettingsManager read whose model keeps its name",
                enabled("settings_manager", "lynxview_command_blacklist", "OBJECT"));
        assertFalse("a gate 47.4.3 doesn't have", enabled("abmock", "comment_cell_badge_dedup", "BOOLEAN"));
        assertFalse("a SettingsManager model R8 renamed",
                enabled("settings_manager", "drama_innerfeed_lynxcard_delete_card_on_error_config", "OBJECT"));
        assertFalse(enabled("abmock", "comment_cell_bind_dedup", "BOOLEAN"));
        assertEquals("true", FeatureGateLabStore.rule("abmock", "comment_cell_badge_dedup", "BOOLEAN").value);
        assertTrue(FeatureGateLabStore.masterEnabled());
        assertEquals(9, FeatureGateLabRuntime.overrideInt("1005_max_limit_count_daily", 5));
    }

    @Test
    public void aBackupFromADroppedBuildIsLeftOutWhenTheRunningBuildHasNoCatalog() throws Exception {
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        String backup = SettingsBackup.create(false);

        BuildNames.setRunningBuildForTests("47.2.1");
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.setMasterEnabled(false);
        SettingsBackup.restore(Utils.getContext(), backup, true);

        assertTrue(SettingsBackup.labRulesWereSkipped(backup));
        assertFalse("the Lab was changed by a backup nothing could check",
                FeatureGateLabStore.masterEnabled());
        assertEquals(0, FeatureGateLabStore.rules().size());
    }

    @Test
    public void anUndoCopyFromABuildTheCatalogDroppedKeepsTheRulesWhoseGatesTheNewBuildCarries() throws Exception {
        FeatureGateLabStore.setMasterEnabled(true);
        save("abmock", "1005_max_limit_count_daily", "INT", "9", true);
        save("abmock", "comment_cell_badge_dedup", "BOOLEAN", "true", true);
        JSONObject saved = FeatureGateLabStore.exportSettings();
        assertEquals(DROPPED, saved.getString("tiktok_version"));

        BuildNames.setRunningBuildForTests(DECLARED);
        java.util.List<FeatureGateLabStore.Rule> rules = FeatureGateLabStore.parseSettings(saved);
        assertEquals(2, rules.size());
        for (FeatureGateLabStore.Rule rule : rules) {
            assertEquals(rule.key, "1005_max_limit_count_daily".equals(rule.key), rule.enabled);
        }
    }

    @Test
    public void onlyTheClassesR8RenamedCountAsRenamed() {
        assertTrue(FeatureGateCatalog.isR8Named("X.0RSc"));
        assertTrue(FeatureGateCatalog.isR8Named("[LX.0RSc;"));
        assertTrue(FeatureGateCatalog.isR8Named("[[LX.0RSc;"));
        assertFalse(FeatureGateCatalog.isR8Named("[Ljava.lang.String;"));
        assertFalse(FeatureGateCatalog.isR8Named("[I"));
        assertFalse(FeatureGateCatalog.isR8Named("com.ss.android.ugc.aweme.settings.HybridLogReportModel"));
        assertFalse(FeatureGateCatalog.isR8Named(""));
    }

    private static void upgradeTo(String build) {
        BuildNames.setRunningBuildForTests(build);
        FeatureGateLabRuntime.reloadRules();
        // The first open of the store on the new build moves the rules.
        FeatureGateLabStore.rules();
    }

    private static void save(String manager, String key, String type, String value, boolean enabled) {
        assertTrue(key, FeatureGateLabStore.saveRule(manager, key, type, value, enabled));
    }

    private static boolean enabled(String manager, String key, String type) {
        FeatureGateLabStore.Rule rule = FeatureGateLabStore.rule(manager, key, type);
        assertNotNull(manager + "/" + key + ":" + type + " was dropped", rule);
        return rule.enabled;
    }
}
