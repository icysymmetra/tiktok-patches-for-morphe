package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;

import android.os.Looper;
import app.morphe.extension.shared.BuildNames;
import app.morphe.extension.shared.Utils;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GeneratedCatalogShapeTest {
    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        FeatureGateCatalog.awaitForTests();
        FeatureGateCatalog.resetForTests();
    }

    @After public void tearDown() throws Exception {
        BuildNames.setRunningBuildForTests(null);
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateCatalog.resetForTests();
    }

    @Test public void theGeneratedAbLiveAndPiaRowsRetainTheirTypedSchema() throws Exception {
        for (String build : GeneratedGateCatalogBuilds.BUILDS) {
            typedRows(rows(build, GeneratedFeatureGateCatalog.ENTRY_COUNTS,
                    GeneratedFeatureGateCatalog.SHARED_GZIP_BASE64, GeneratedFeatureGateCatalog.OWN_GZIP_BASE64, 10),
                    Set.of("abmock", "live", "pia_activity_center"));
        }
    }

    @Test public void theGeneratedPlayerRowsRetainTheirTypedSchema() throws Exception {
        for (String build : GeneratedGateCatalogBuilds.BUILDS) {
            typedRows(rows(build, GeneratedPlayerFeatureGateCatalog.ENTRY_COUNTS,
                    GeneratedPlayerFeatureGateCatalog.SHARED_GZIP_BASE64,
                    GeneratedPlayerFeatureGateCatalog.OWN_GZIP_BASE64, 10), Set.of("player_config"));
        }
    }

    @Test public void theGeneratedVeRowsRetainTheirTypedSchema() throws Exception {
        for (String build : GeneratedGateCatalogBuilds.BUILDS) {
            typedRows(rows(build, GeneratedVeFeatureGateCatalog.ENTRY_COUNTS,
                    GeneratedVeFeatureGateCatalog.SHARED_GZIP_BASE64, GeneratedVeFeatureGateCatalog.OWN_GZIP_BASE64, 10),
                    Set.of("ve_config"));
        }
    }

    @Test public void theGeneratedSettingsRowsRetainTheirClassAndDefaultShapes() throws Exception {
        for (String build : GeneratedGateCatalogBuilds.BUILDS) {
            List<String[]> rows = rows(build, GeneratedSettingsManagerCatalog.ENTRY_COUNTS,
                    GeneratedSettingsManagerCatalog.SHARED_GZIP_BASE64,
                    GeneratedSettingsManagerCatalog.OWN_GZIP_BASE64, 4);
            List<String[]> sites = rows(build, GeneratedSettingsManagerSites.ENTRY_COUNTS,
                    GeneratedSettingsManagerSites.SHARED_GZIP_BASE64, GeneratedSettingsManagerSites.OWN_GZIP_BASE64, 3);
            Set<String> keys = new HashSet<>();
            for (String[] row : rows) {
                keys.add(row[0]);
                assertFalse(row[0], row[1].isEmpty());
                assertFalse(row[0], row[2].isEmpty());
                JSONTokener json = new JSONTokener(row[2]);
                assertNotNull(row[0], json.nextValue());
                assertEquals("trailing default data for " + row[0], 0, json.nextClean());
                assertTrue(row[0] + ": " + row[3],
                        Set.of("generated_registry", "call_site_only").contains(row[3]));
            }
            Set<String> sited = new HashSet<>();
            for (String[] site : sites) {
                sited.add(site[0]);
                assertTrue(site[0], site[1].contains("->"));
                // A dynamic feature module's dex is named inside the zip TikTok ships it as.
                assertTrue(site[0] + ": " + site[2],
                        site[2].matches("(libdex_[A-Za-z0-9_]+\\.so!)?classes[0-9]*\\.dex"));
            }
            assertEquals(build + ": every read has one call site and no site is without a read", keys, sited);
        }
    }

    @Test public void theBuildsAreDistinctAndEveryTableCarriesEachOne() {
        String[] builds = GeneratedGateCatalogBuilds.BUILDS;
        assertTrue("no catalog build", builds.length > 0);
        assertEquals(List.of(builds).toString(), builds.length, Set.of(builds).size());
        for (int[] counts : new int[][]{GeneratedFeatureGateCatalog.ENTRY_COUNTS,
                GeneratedPlayerFeatureGateCatalog.ENTRY_COUNTS, GeneratedVeFeatureGateCatalog.ENTRY_COUNTS,
                GeneratedSettingsManagerCatalog.ENTRY_COUNTS, GeneratedSettingsManagerSites.ENTRY_COUNTS}) {
            assertEquals(builds.length, counts.length);
        }
        for (String[][] own : new String[][][]{GeneratedFeatureGateCatalog.OWN_GZIP_BASE64,
                GeneratedPlayerFeatureGateCatalog.OWN_GZIP_BASE64, GeneratedVeFeatureGateCatalog.OWN_GZIP_BASE64,
                GeneratedSettingsManagerCatalog.OWN_GZIP_BASE64, GeneratedSettingsManagerSites.OWN_GZIP_BASE64}) {
            assertEquals(builds.length, own.length);
        }
    }

    @Test public void loadingTheRealCatalogPublishesEntriesFromAllFourBundles() throws Exception {
        for (String build : GeneratedGateCatalogBuilds.BUILDS) {
            BuildNames.setRunningBuildForTests(build);
            FeatureGateCatalog.Snapshot snapshot = load();

            entry(snapshot, "abmock", "1005_max_limit_count_daily", "INT", "5");
            entry(snapshot, "player_config", "AWEDanmakuSupportMask", "BOOLEAN", "false");
            entry(snapshot, "ve_config", "aeabtest_v2api", "BOOLEAN", "false");
            FeatureGateCatalog.Entry array = entry(snapshot, "settings_manager",
                    "lynxview_command_blacklist",
                    "OBJECT", "[\"surl\",\"fallback_url\"]");
            assertEquals(String[].class.getName(), array.requestedClass);
            assertTrue(array.registered);
            assertTrue(array.proof, array.proof.contains(" in classes"));
            int index = List.of(GeneratedGateCatalogBuilds.BUILDS).indexOf(build);
            assertEquals(GeneratedPlayerFeatureGateCatalog.ENTRY_COUNTS[index],
                    snapshot.entries.stream().filter(item -> "player_config".equals(item.manager)).count());
            assertEquals(GeneratedVeFeatureGateCatalog.ENTRY_COUNTS[index],
                    snapshot.entries.stream().filter(item -> "ve_config".equals(item.manager)).count());
            FeatureGateCatalog.resetForTests();
        }
    }

    /**
     * The Lab shows the catalog of the build that is running, the one declared build. These rows
     * are ones older builds had otherwise: a default that moved, a gate added and one dropped.
     */
    @Test public void theRunningBuildsCatalogIsTheOneShown() throws Exception {
        BuildNames.setRunningBuildForTests("47.4.3");
        assertEquals("47.4.3", FeatureGateCatalog.catalogBuild());
        FeatureGateCatalog.Snapshot shown = load();
        entry(shown, "abmock", "low_memory_kill_monitor", "INT", "89");
        entry(shown, "abmock", "4710_lifecycle_job_next_day_fixed", "INT", "0");
        assertNull(shown.byIdentity.get("abmock\ncomment_cell_badge_dedup"));
    }

    @Test public void aBuildWithNoCatalogIsShownTheNewestOne() {
        // 47.1.4 had a catalog until 47.4.3 replaced it.
        BuildNames.setRunningBuildForTests("47.1.4");
        assertFalse(FeatureGateCatalog.hasCatalogFor("47.1.4"));
        assertEquals(FeatureGateCatalog.newestCatalogBuild(), FeatureGateCatalog.catalogBuild());
        BuildNames.setRunningBuildForTests("46.9.3");
        assertFalse(FeatureGateCatalog.hasCatalogFor("46.9.3"));
        assertEquals(FeatureGateCatalog.newestCatalogBuild(), FeatureGateCatalog.catalogBuild());
        assertEquals(List.of(GeneratedGateCatalogBuilds.BUILDS).get(GeneratedGateCatalogBuilds.BUILDS.length - 1),
                FeatureGateCatalog.newestCatalogBuild());
    }

    private static FeatureGateCatalog.Snapshot load() throws Exception {
        List<FeatureGateCatalog.Snapshot> delivered = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        FeatureGateCatalog.loadAsync(true, new FeatureGateCatalog.Callback() {
            @Override public void onLoaded(FeatureGateCatalog.Snapshot snapshot) { delivered.add(snapshot); }
            @Override public void onError(String message) { errors.add(message); }
        });
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(List.of(), errors);
        assertFalse("the loader delivered no catalog", delivered.isEmpty());
        FeatureGateCatalog.Snapshot snapshot = delivered.get(delivered.size() - 1);
        assertTrue("the loader stopped at its current-values preview", snapshot.catalogComplete);
        assertSame(snapshot, FeatureGateCatalog.cachedSnapshot());
        return snapshot;
    }

    private static FeatureGateCatalog.Entry entry(FeatureGateCatalog.Snapshot snapshot,
            String manager, String key, String type, String defaultValue) {
        FeatureGateCatalog.Entry entry = snapshot.byIdentity.get(manager + "\n" + key);
        assertNotNull("the loader omitted " + manager + "/" + key, entry);
        assertEquals(type, entry.type);
        assertEquals(List.of(defaultValue), entry.defaults);
        assertTrue(entry.userVisible());
        return entry;
    }

    private static void typedRows(List<String[]> rows, Set<String> managers) throws Exception {
        Set<String> types = Set.of("BOOLEAN", "INT", "LONG", "FLOAT", "DOUBLE", "STRING");
        for (String[] row : rows) {
            assertTrue(row[0] + ": " + row[1], managers.contains(row[1]));
            assertTrue(row[0] + ": " + row[2], types.contains(row[2]));
            assertTrue(row[0], Set.of("0", "1").contains(row[3]));
            assertTrue(row[0], Set.of("0", "1").contains(row[4]));
            for (int column = 5; column <= 7; column++) new JSONArray(row[column]);
            assertFalse("missing provenance for " + row[0], row[9].isEmpty());
        }
    }

    /** [build]'s rows: the shared ones, then its own. */
    static List<String[]> rows(String build, int[] counts, String[] shared, String[][] own, int width)
            throws Exception {
        int index = List.of(GeneratedGateCatalogBuilds.BUILDS).indexOf(build);
        List<String[]> rows = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (String[] chunks : new String[][]{shared, own[index]}) {
            if (chunks.length == 0) continue;
            byte[] bytes = Base64.getDecoder().decode(String.join("", chunks));
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(new ByteArrayInputStream(bytes)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] row = line.split("\t", -1);
                    assertEquals(build + ": malformed row " + rows.size(), width, row.length);
                    assertFalse("empty gate name", row[0].isEmpty());
                    // Rows are keyed by name and manager; SettingsManager tables by name alone.
                    String identity = width == 10 ? row[0] + "\n" + row[1] : row[0];
                    assertTrue(build + ": duplicate gate " + row[0], identities.add(identity));
                    rows.add(row);
                }
            }
        }
        assertEquals(build + ": declared count does not match the compressed rows", counts[index], rows.size());
        assertTrue("an empty catalog proves no schema", counts[index] > 0);
        return rows;
    }
}
