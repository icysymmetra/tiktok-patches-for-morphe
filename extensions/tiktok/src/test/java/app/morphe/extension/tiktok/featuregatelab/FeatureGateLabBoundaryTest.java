package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** State, import, coercion and concurrency boundaries for the Feature Gate Lab. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeatureGateLabBoundaryTest {
    private boolean recorderEnabled;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        recorderEnabled = SettingsStatus.featureGateRecorderEnabled;
        SettingsStatus.featureGateRecorderEnabled = true;
        FeatureGateLearnMode.cancel();
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.clearCatalogRequestForTests();
        FeatureGateLabRuntime.reloadRules();
    }

    @After
    public void tearDown() throws Exception {
        FeatureGateLearnMode.cancel();
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabStore.resetAllLabData();
        // A load one of these asked for has to be finished with before the cache is cleared, or
        // it lands in the middle of the next test and the catalogue appears from nowhere.
        FeatureGateCatalog.awaitForTests();
        FeatureGateCatalog.resetForTests();
        SettingsStatus.featureGateRecorderEnabled = recorderEnabled;
    }

    /**
     * Publishes a catalogue the runtime can consult without the Lab's screen loading one.
     *
     * <p>Both caches, because that is what a real load leaves behind: the Lab screen fills the
     * snapshot and the AB type map together, and the runtime's check reads only the map.
     */
    private static void publishCatalog(FeatureGateCatalog.Entry... entries) throws Exception {
        java.util.Map<String, FeatureGateCatalog.Entry> byIdentity = new java.util.HashMap<>();
        java.util.Map<String, String> abTypes = new java.util.HashMap<>();
        for (FeatureGateCatalog.Entry entry : entries) {
            byIdentity.put(entry.identity(), entry);
            if (FeatureGateLabStore.MANAGER_ABMOCK.equals(entry.manager)) {
                abTypes.put(entry.key, entry.type);
            }
        }
        var field = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        field.setAccessible(true);
        field.set(null, new FeatureGateCatalog.Snapshot(
                List.of(entries), byIdentity, entries.length, 0L, true));
        var types = FeatureGateCatalog.class.getDeclaredField("cachedAbTypes");
        types.setAccessible(true);
        types.set(null, java.util.Collections.unmodifiableMap(abTypes));
    }

    private static FeatureGateCatalog.Entry abEntry(String key, String type) {
        return new FeatureGateCatalog.Entry(key, key, FeatureGateLabStore.MANAGER_ABMOCK, type,
                true, true, List.of(), List.of(), List.of(), "", "", false, null, null);
    }

    @Test
    public void malformedScalarRulesKeepNativeValuesAndDoNotTrigger() {
        FeatureGateLabStore.saveRule("abmock", "bad_boolean", "BOOLEAN", "maybe", true);
        FeatureGateLabStore.saveRule("abmock", "bad_float", "FLOAT", "NaN", true);
        FeatureGateLabStore.saveRule("abmock", "bad_double", "DOUBLE", "Infinity", true);
        FeatureGateLabStore.setMasterEnabled(true);

        assertTrue(FeatureGateLabRuntime.overrideBoolean("bad_boolean", true));
        assertFalse(FeatureGateLabRuntime.overrideBoolean("bad_boolean", false));
        assertEquals(1.25f, FeatureGateLabRuntime.overrideFloat("bad_float", 1.25f), 0f);
        assertEquals(2.5d, FeatureGateLabRuntime.overrideDouble("bad_double", 2.5d), 0d);
        assertFalse(FeatureGateLabRuntime.isTriggered("abmock", "bad_boolean", "BOOLEAN"));
        assertFalse(FeatureGateLabRuntime.isTriggered("abmock", "bad_float", "FLOAT"));
        assertFalse(FeatureGateLabRuntime.isTriggered("abmock", "bad_double", "DOUBLE"));

        FeatureGateLabStore.saveRule(
                FeatureGateLabStore.MANAGER_PIA_ACTIVITY_CENTER,
                "bad_center_boolean", "BOOLEAN", "maybe", true);
        FeatureGateLabStore.saveRule(
                FeatureGateLabStore.MANAGER_PIA_ACTIVITY_CENTER,
                "bad_center_double", "DOUBLE", "Infinity", true);
        FeatureGateLabStore.setMasterEnabled(true);
        String inner = new Uri.Builder().scheme("https").authority("api.example")
                .appendPath("activity_center").appendQueryParameter("dev_fg_json", "{}").build().toString();
        String schema = new Uri.Builder().scheme("https").authority("wrapper.example")
                .appendQueryParameter("url", inner).build().toString();
        assertEquals(schema, FeatureGateLabRuntime.transformActivityCenterSchema(schema));
    }

    @Test
    public void masterAndResetCyclesClearRuntimeStateBeforeReenablingRules() {
        FeatureGateLabStore.saveRule("abmock", "cycle_gate", "BOOLEAN", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        assertTrue(FeatureGateLabRuntime.overrideBoolean("cycle_gate", false));
        assertTrue(FeatureGateLabRuntime.isTriggered("abmock", "cycle_gate", "BOOLEAN"));

        FeatureGateLabRuntime.overridePlayerValue("cycle_player", Float.class, 1.0f);
        assertTrue(FeatureGateLabRuntime.wasPlayerObserved("cycle_player"));

        FeatureGateLabStore.setMasterEnabled(false);
        assertFalse(FeatureGateLabRuntime.overrideBoolean("cycle_gate", false));
        assertFalse(FeatureGateLabRuntime.isTriggered("abmock", "cycle_gate", "BOOLEAN"));
        assertFalse(FeatureGateLabRuntime.wasPlayerObserved("cycle_player"));

        FeatureGateLabStore.setMasterEnabled(true);
        assertFalse(FeatureGateLabRuntime.isTriggered("abmock", "cycle_gate", "BOOLEAN"));
        assertTrue(FeatureGateLabRuntime.overrideBoolean("cycle_gate", false));

        FeatureGateLabStore.resetAllLabData();
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertFalse(FeatureGateLabRuntime.overrideBoolean("cycle_gate", false));
        assertFalse(FeatureGateLabRuntime.isTriggered("abmock", "cycle_gate", "BOOLEAN"));
        assertFalse(FeatureGateLabRuntime.wasPlayerObserved("cycle_player"));
    }

    @Test
    public void profileImportKeepsAcceptedRulesDisabledUntilExplicitlyEnabled() throws Exception {
        FeatureGateCatalog.Entry entry = new FeatureGateCatalog.Entry(
                "import_gate", "Import gate", FeatureGateLabStore.MANAGER_ABMOCK, "BOOLEAN",
                true, true, List.of(), List.of(), List.of(), "", "", false, null, null);
        JSONObject root = new JSONObject()
                .put("schema", 1)
                .put("target", "TikTok global")
                .put("tiktok_version", FeatureGateLabStore.targetVersion())
                .put("rules", new org.json.JSONArray().put(new JSONObject()
                        .put("manager", FeatureGateLabStore.MANAGER_ABMOCK)
                        .put("key", "import_gate")
                        .put("type", "BOOLEAN")
                        .put("value", "true")));

        FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                root.toString(), Map.of(entry.identity(), entry));
        assertEquals(1, review.accepted.size());
        assertFalse(review.accepted.get(0).enabled);

        FeatureGateLabUndo.importRules(review);
        assertFalse(FeatureGateLabStore.rule(
                FeatureGateLabStore.MANAGER_ABMOCK, "import_gate", "BOOLEAN").enabled);
        FeatureGateLabStore.setMasterEnabled(true);
        assertFalse(FeatureGateLabRuntime.overrideBoolean("import_gate", false));
    }

    @Test
    public void aRuleTheCatalogueDisagreesWithIsNotHandedToTheHost() throws Exception {
        // The one path with no runtime type to check against: TikTok's cached value is null, so
        // the rule is found by key alone. A STRING rule on a key the host reads as a number used
        // to be handed straight back, and the ClassCastException landed in TikTok's own frame.
        publishCatalog(abEntry("mistyped_gate", "INT"), abEntry("agreed_gate", "INT"));
        FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK, "mistyped_gate",
                "STRING", "not a number", true);
        FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK, "agreed_gate",
                "INT", "7", true);
        FeatureGateLabStore.setMasterEnabled(true);

        assertNull("a String was handed back for a key the catalogue calls INT",
                FeatureGateLabRuntime.overrideRawAbValue("mistyped_gate", null, false));
        assertFalse(FeatureGateLabRuntime.isTriggered(
                FeatureGateLabStore.MANAGER_ABMOCK, "mistyped_gate", "STRING"));
        FeatureGateFailure mistyped = FeatureGateLabRuntime.structuredFailure(
                FeatureGateLabStore.MANAGER_ABMOCK, "mistyped_gate", "STRING");
        assertNotNull("the refusal was not reported anywhere", mistyped);
        assertEquals("the refusal lost the reason it carried",
                FeatureGateFailure.Reason.TYPE_MISMATCH, mistyped.reason);
        assertEquals("the refusal lost the types that disagreed",
                "Catalogue says INT, this rule is STRING", mistyped.text());

        // The positive control: a rule the catalogue agrees with still reaches the host, so the
        // check is refusing the mistyped rule rather than the whole path.
        assertEquals(7, FeatureGateLabRuntime.overrideRawAbValue("agreed_gate", null, false));
        assertTrue(FeatureGateLabRuntime.isTriggered(
                FeatureGateLabStore.MANAGER_ABMOCK, "agreed_gate", "INT"));
    }

    @Test
    public void aKeyTheCatalogueHasNeverHeardOfKeepsTikTokValue() throws Exception {
        // With a loaded catalogue that does not carry the key there is nothing to check the type
        // against, so the rule is refused and the host keeps the value it had. Until the Lab's
        // screen loads a catalogue there is nothing to consult and the rule applies, which is
        // what withNoCatalogueLoadedTheRuleStillApplies pins.
        publishCatalog(abEntry("known_gate", "INT"));
        FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK, "stranger_gate",
                "INT", "3", true);
        FeatureGateLabStore.setMasterEnabled(true);

        assertNull(FeatureGateLabRuntime.overrideRawAbValue("stranger_gate", null, false));
        assertFalse(FeatureGateLabRuntime.isTriggered(
                FeatureGateLabStore.MANAGER_ABMOCK, "stranger_gate", "INT"));
    }

    @Test
    public void withNoCatalogueLoadedTheRuleStillApplies() {
        // The Lab's screen is what loads the catalogue, so on a process where it has never been
        // opened there is nothing to consult and the fallback works as it always did.
        FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK, "early_gate",
                "INT", "5", true);
        FeatureGateLabStore.setMasterEnabled(true);

        assertNull("the test started with a catalogue loaded", FeatureGateCatalog.cachedSnapshot());
        assertEquals(5, FeatureGateLabRuntime.overrideRawAbValue("early_gate", null, false));
    }

    @Test
    public void withNoCatalogueLoadedTheFallbackAsksForOneSoTheNextReadIsChecked() throws Exception {
        // The check could only refuse a rule while the catalogue happened to be loaded, and
        // nothing loaded it until the Lab's own screen was opened in that process, so on a fresh
        // launch the fallback behaved exactly as it had before the check existed.
        FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK, "stranger_gate",
                "STRING", "not a number", true);
        FeatureGateLabStore.setMasterEnabled(true);
        assertNull("the test started with a catalogue loaded", FeatureGateCatalog.cachedSnapshot());

        // This one read still has nothing to check against, and applies the rule as it always did.
        assertEquals("not a number",
                FeatureGateLabRuntime.overrideRawAbValue("stranger_gate", null, false));

        FeatureGateCatalog.awaitForTests();
        Map<String, String> loaded = FeatureGateCatalog.cachedAbTypes();
        assertNotNull("the fallback did not ask for the catalogue", loaded);

        // What it asked for is the AB key to type map and not the whole catalogue. The snapshot
        // is 16,052 entries and this launch never opened the Lab screen, so holding it for the
        // life of the process buys nothing the check uses.
        assertNull("the fallback loaded the whole snapshot, not just the types it reads",
                FeatureGateCatalog.cachedSnapshot());
        assertTrue("the AB type map came back empty", loaded.size() > 1000);

        // The branch this whole check exists for, reached without the Lab screen ever opening:
        // a key the catalogue does carry, with a rule whose type it disagrees with. Taken from
        // the map that was just loaded rather than named here, so this keeps meaning the same
        // thing when the catalogue is regenerated.
        Map.Entry<String, String> known = anAbEntry(loaded);
        String wrongType = "BOOLEAN".equals(FeatureGateLabStore.normalizeType(known.getValue()))
                ? "STRING" : "BOOLEAN";
        FeatureGateLabStore.saveRule(
                FeatureGateLabStore.MANAGER_ABMOCK, known.getKey(), wrongType, "true", true);
        assertNull("a rule the catalogue disagrees with was handed to the host",
                FeatureGateLabRuntime.overrideRawAbValue(known.getKey(), null, false));
        FeatureGateFailure disagreed = FeatureGateLabRuntime.structuredFailure(
                FeatureGateLabStore.MANAGER_ABMOCK, known.getKey(), wrongType);
        assertNotNull("no refusal was recorded at all", disagreed);
        assertEquals("the refusal did not say the catalogue disagreed: " + disagreed.text(),
                FeatureGateFailure.Reason.TYPE_MISMATCH, disagreed.reason);

        // And a key it has never heard of is refused for the other reason.
        assertNull(FeatureGateLabRuntime.overrideRawAbValue("stranger_gate", null, false));
    }

    @Test
    public void theTypeMapAgreesWithTheCatalogueTheLabScreenLoads() throws Exception {
        // Two ways in, one answer. The Lab screen builds a whole snapshot; the runtime's check
        // reads a map built from the same two places, in the same order. A key that answered
        // differently depending on which had run would refuse a rule on one launch and apply it
        // on the next.
        FeatureGateCatalog.loadAbTypesAsync(new FeatureGateCatalog.AbTypesCallback() {
            @Override public void onLoaded(Map<String, String> abTypes) { }

            @Override public void onError(String message) { }
        });
        FeatureGateCatalog.awaitForTests();
        Map<String, String> lean = new HashMap<>(FeatureGateCatalog.cachedAbTypes());

        FeatureGateCatalog.resetForTests();
        FeatureGateCatalog.loadAsync(false, new FeatureGateCatalog.Callback() {
            @Override public void onLoaded(FeatureGateCatalog.Snapshot snapshot) { }

            @Override public void onError(String message) { }
        });
        FeatureGateCatalog.awaitForTests();
        FeatureGateCatalog.Snapshot snapshot = FeatureGateCatalog.cachedSnapshot();
        assertNotNull("the whole catalogue did not load", snapshot);

        Map<String, String> fromSnapshot = new HashMap<>();
        for (FeatureGateCatalog.Entry entry : snapshot.entries) {
            if (FeatureGateLabStore.MANAGER_ABMOCK.equals(entry.manager)) {
                fromSnapshot.put(entry.key, entry.type);
            }
        }
        assertEquals("the two ways of reading the catalogue disagree", fromSnapshot, lean);

        // And the map the Lab screen's load leaves behind is that same map, so a check after it
        // reads what a check before it would have.
        assertEquals(fromSnapshot, FeatureGateCatalog.cachedAbTypes());
    }

    /** Any AB entry the loaded map carries, so the type check has something real to read. */
    private static Map.Entry<String, String> anAbEntry(Map<String, String> loaded) {
        for (Map.Entry<String, String> entry : loaded.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                return entry;
            }
        }
        throw new AssertionError("the loaded catalogue carries no AB entry to check against");
    }

    @Test
    public void aLowercaseTypeStillMatchesOnATurkishPhone() throws Exception {
        // The Lab's own exports write the type in capitals, so this needs a profile from
        // somewhere else. Turkish capitalises a dotless i to a dotted one, so "int" folded under
        // the phone's locale is a different string from the "INT" the catalog folds to, and the
        // rule is dropped as a type mismatch on that phone alone.
        Locale previous = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            FeatureGateCatalog.Entry entry = new FeatureGateCatalog.Entry(
                    "turkish_gate", "Turkish gate", FeatureGateLabStore.MANAGER_ABMOCK, "INT",
                    true, true, List.of(), List.of(), List.of(), "", "", false, null, null);
            JSONObject root = new JSONObject()
                    .put("schema", 1)
                    .put("target", "TikTok global")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion())
                    .put("rules", new org.json.JSONArray().put(new JSONObject()
                            .put("manager", FeatureGateLabStore.MANAGER_ABMOCK)
                            .put("key", "turkish_gate")
                            .put("type", "int")
                            .put("value", "7")));

            FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                    root.toString(), Map.of(entry.identity(), entry));
            assertEquals("a lowercase type was rejected: " + review.rejected,
                    1, review.accepted.size());

            FeatureGateLabUndo.importRules(review);
            FeatureGateLabStore.saveRule(FeatureGateLabStore.MANAGER_ABMOCK, "turkish_gate",
                    "int", "7", true);
            FeatureGateLabStore.setMasterEnabled(true);
            assertEquals("the imported rule never reached the boundary",
                    7, FeatureGateLabRuntime.overrideInt("turkish_gate", 1));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void recorderStopsWhenDisabledAndStartsWithAFreshReadSet() throws Exception {
        String key = "toggle_" + System.nanoTime();
        FeatureGateLearnMode.cancel();
        SettingsStatus.featureGateRecorderEnabled = false;
        FeatureGateLearnMode.begin();
        assertFalse(FeatureGateLearnMode.isRecording());
        FeatureGateLearnMode.observe("test", key, "INT", 1);
        assertFalse(FeatureGateLearnMode.isRecording());

        SettingsStatus.featureGateRecorderEnabled = true;
        FeatureGateLearnMode.begin();
        FeatureGateLearnMode.observe("test", key, "INT", 1);
        assertTrue(FeatureGateLearnMode.isRecording());

        SettingsStatus.featureGateRecorderEnabled = false;
        FeatureGateLearnMode.observe("test", key, "INT", 2);
        assertFalse(FeatureGateLearnMode.isRecording());

        SettingsStatus.featureGateRecorderEnabled = true;
        FeatureGateLearnMode.begin();
        FeatureGateLearnMode.observe("test", key, "INT", 2);
        JSONObject report = new JSONObject(FeatureGateLearnMode.stopAndBuildReport());
        assertEquals(1, report.getInt("gate_count"));
        assertEquals("new", report.getJSONArray("gates").getJSONObject(0).getString("state"));
    }

    @Test
    public void observationLimitRemainsBoundedWhenCallsArriveTogether() throws Exception {
        SettingsManagerObservationRecorder.clear();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                final int offset = worker * 512;
                tasks.add(executor.submit(() -> {
                    for (int index = 0; index < 512; index++) {
                        SettingsManagerObservationRecorder.observeWithoutDefault(
                                "parallel_" + (offset + index), String.class, "value");
                    }
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(2048, SettingsManagerObservationRecorder.size());
    }

    @Test public void aDeeplyNestedStructuredValueIsRefusedRatherThanKillingTheWorker() {
        // A rule's value is a string inside the backup, so the depth check on the document
        // around it says nothing about what the string holds. The platform parser recurses once
        // per level and raises StackOverflowError, which is an Error: it walked past the catch
        // in the restore and the catch in the preference, and took the process with it.
        int depth = 30_000;
        StringBuilder nested = new StringBuilder(2 * depth + 8).append("{\"a\":");
        for (int level = 0; level < depth; level++) nested.append('[');
        for (int level = 0; level < depth; level++) nested.append(']');
        String value = nested.append('}').toString();
        assertTrue("the probe must stay under the length check it is not testing",
                value.length() < 64 * 1024);

        assertEquals(FeatureGateLabStore.ValidationCode.INVALID_STRUCTURED_VALUE,
                FeatureGateLabStore.validateValue("OBJECT", value).code);
        // The positive control: an ordinary structured value still goes through.
        assertNull(FeatureGateLabStore.validateValue("OBJECT", "{\"enable\":true}"));
        assertEquals(FeatureGateLabStore.ValidationCode.SELECT_AT_LEAST_ONE_FIELD,
                FeatureGateLabStore.validateValue("OBJECT", "{}").code);
    }

    @Test
    public void aKeyReadWithoutADefaultIsOnlyWalkedOnce() {
        // A key TikTok only ever reads through the no-default getter never lands in the
        // default-wrapper set, so this path used to capture a full stack trace on every read of
        // it, forever, on whatever thread the host reads settings from.
        SettingsManagerObservationRecorder.clear();
        SettingsManagerObservationRecorder.wrapperWalks = 0;

        for (int read = 0; read < 500; read++) {
            SettingsManagerObservationRecorder.observeWithoutDefault(
                    "gate_read_without_default", Boolean.class, Boolean.TRUE);
        }
        assertEquals("the stack was walked on every read of one key",
                1, SettingsManagerObservationRecorder.wrapperWalks);

        // A second key is its own answer, so the cache is per key rather than a latch.
        SettingsManagerObservationRecorder.observeWithoutDefault(
                "another_gate_read", Boolean.class, Boolean.TRUE);
        assertEquals("a different key reused the first key's answer",
                2, SettingsManagerObservationRecorder.wrapperWalks);
    }

    @Test
    public void aRuleSavedWhileTheSnapshotIsBuildingIsNotLost() {
        // A gate thread reads the rules to build its snapshot; the UI thread saves a rule and
        // clears the snapshot; the gate thread then publishes the rules it read before the save,
        // and the new rule stays invisible until something else happens to reload. The hook
        // creates that overlap from inside the build.
        FeatureGateLabStore.setMasterEnabled(true);
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabRuntime.rulesReadHook = () -> {
            FeatureGateLabRuntime.rulesReadHook = null;
            FeatureGateLabStore.saveRule("abmock", "saved_mid_build", "BOOLEAN", "true", true);
        };
        try {
            FeatureGateLabRuntime.overrideBoolean("saved_mid_build", false);
        } finally {
            FeatureGateLabRuntime.rulesReadHook = null;
        }

        assertTrue("the rule saved while the snapshot was building never took effect",
                FeatureGateLabRuntime.overrideBoolean("saved_mid_build", false));
    }

    @Test
    public void gateReadsBeforeTikTokHandsOverAContextDoNotKeepTakingTheMonitor() throws Exception {
        // Before Utils has a context the snapshot could not be built, and a null was rebuilt on
        // every read, so every gate read on every thread went through the class monitor at the
        // point in start-up where the host reads gates hardest.
        java.lang.reflect.Field held = Utils.class.getDeclaredField("context");
        held.setAccessible(true);
        Object previous = held.get(null);
        try {
            held.set(null, null);
            FeatureGateLabRuntime.reloadRules();
            FeatureGateLabRuntime.snapshotMonitorEntries = 0;
            for (int read = 0; read < 1000; read++) {
                FeatureGateLabRuntime.overrideBoolean("read_before_context", true);
            }
            assertTrue("1,000 gate reads with no context took the monitor more than once",
                    FeatureGateLabRuntime.snapshotMonitorEntries <= 1);
        } finally {
            held.set(null, previous);
        }

        // And the snapshot built without a context does not outlive it, which would leave every
        // rule dead for the rest of the process.
        FeatureGateLabRuntime.snapshotMonitorEntries = 0;
        FeatureGateLabRuntime.overrideBoolean("read_before_context", true);
        assertTrue("the snapshot from before the context arrived was never rebuilt",
                FeatureGateLabRuntime.snapshotMonitorEntries > 0);
    }

    @Test
    public void aSwitchedOffRecorderTakesNoLockOnTheGateReadPath() throws Exception {
        // The Lab's boundaries run for every AB, live settings and player config read TikTok
        // makes, on whatever thread makes it, and the Recorder patch that gives them something
        // to do ships off. Taking the class monitor before checking that put all of it behind
        // one lock.
        SettingsStatus.featureGateRecorderEnabled = false;
        FeatureGateLearnMode.cancel();
        FeatureGateLearnMode.monitorEntries = 0;

        int threads = 8;
        int callsPerThread = 1250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                running.add(pool.submit(() -> {
                    for (int call = 0; call < callsPerThread; call++) {
                        FeatureGateLabRuntime.overrideBoolean("gate_read_under_load", true);
                    }
                }));
            }
            for (java.util.concurrent.Future<?> task : running) {
                task.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("10,000 gate reads with the Recorder off still serialised on the monitor",
                0, FeatureGateLearnMode.monitorEntries);

        // The counter is live, so the zero above is a measurement rather than a dead field.
        SettingsStatus.featureGateRecorderEnabled = true;
        FeatureGateLabRuntime.overrideBoolean("gate_read_under_load", true);
        assertTrue("the counter never moves, so it cannot show the monitor being taken",
                FeatureGateLearnMode.monitorEntries > 0);
    }
}
