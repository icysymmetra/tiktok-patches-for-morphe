package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import android.content.ContextWrapper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.json.JSONArray;
import org.json.JSONObject;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class SoftReferenceTrialEvidenceTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Before public void before() { RecoveryCapture.resetForTests(); }
    @After public void after() { RecoveryCapture.resetForTests(); }

    @Test public void ordinaryBuildDoesNotClaimASelectedTrial() {
        assertFalse(RecoveryCapture.softReferenceTrialEnabled());
        assertEquals("softref_selected=false softref_v1_bypassed=0 softref_v2_bypassed=0",
                RecoveryCapture.softReferenceState());
    }

    @Test public void concurrentRepeatedEntriesRemainSeparatelyCounted() throws Exception {
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> {
                for (int n = 0; n < 100; n++) {
                    RecoveryCapture.softReferenceV1Bypassed(); RecoveryCapture.softReferenceV2Bypassed();
                }
            });
            workers.add(thread); thread.start();
        }
        for (Thread worker : workers) worker.join(5000);
        for (Thread worker : workers) assertFalse(worker.isAlive());
        assertEquals("softref_selected=false softref_v1_bypassed=800 softref_v2_bypassed=800",
                RecoveryCapture.softReferenceState());
        assertFalse(RecoveryTimeline.reading(0).contains("softref_"));
    }

    @Test public void clearingTimelinePreservesProcessBypassFacts() {
        RecoveryCapture.softReferenceV1Bypassed(); RecoveryCapture.softReferenceV2Bypassed();
        RecoveryTimeline.clear(null);
        assertTrue(RecoveryCapture.softReferenceState().endsWith("softref_v1_bypassed=1 softref_v2_bypassed=1"));
    }

    private RecoveryStore data() throws Exception {
        File files = temp.newFolder();
        RecoveryStore data = new RecoveryStore(new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return files; }
        });
        data.update(s -> s.put("trial", "test-trial"));
        return data;
    }

    @Test public void aLaterInstallerEncounterUpdatesTheSameDatedActivationOnlyOnce() throws Exception {
        RecoveryStore data = data();
        data.update(s -> s.put("sessions", new JSONArray().put(new JSONObject().put("pid", 101)
                .put("at", 1000).put("build", "test build"))));
        RecoveryCapture.softReferenceV1Bypassed();
        assertTrue(RecoveryCapture.recordSoftReferenceActivation(data, "test-trial", 101, 1000, 2000));
        String first = RecoveryCapture.render(data.read());
        assertTrue(first, first.contains("softref_v1_bypassed=1 softref_v2_bypassed=0"));
        assertTrue(first, first.contains("observed_ms: 2000\n"));
        RecoveryCapture.softReferenceV2Bypassed();
        assertTrue(RecoveryCapture.recordSoftReferenceActivation(data, "test-trial", 101, 1000, 3000));
        assertEquals(1, data.read().getJSONArray("activations").length());
        String both = RecoveryCapture.render(data.read());
        assertTrue(both, both.contains("softref_v1_bypassed=1 softref_v2_bypassed=1"));
        assertTrue(both, both.contains("observed_ms: 3000\n"));
        RecoveryCapture.softReferenceV2Bypassed();
        assertFalse(RecoveryCapture.recordSoftReferenceActivation(data, "test-trial", 101, 1000, 4000));
        assertEquals(both, RecoveryCapture.render(data.read()));
    }

    @Test public void laterInstallerAfterClearDoesNotRecreateTheClearedActivation() throws Exception {
        RecoveryStore data = data();
        data.update(s -> s.put("sessions", new JSONArray().put(new JSONObject().put("pid", 101)
                .put("at", 1000).put("build", "test build"))));
        RecoveryCapture.softReferenceV1Bypassed();
        assertTrue(RecoveryCapture.recordSoftReferenceActivation(data, "test-trial", 101, 1000, 2000));
        data.clear();
        RecoveryCapture.softReferenceV2Bypassed();
        assertFalse(RecoveryCapture.recordSoftReferenceActivation(data, "test-trial", 101, 1000, 3000));
        assertEquals("", RecoveryCapture.render(data.read()));
    }

    @Test public void activationAloneIsExportableAndClearUndoPreservesIt() throws Exception {
        RecoveryStore data = data();
        RecoveryCapture.saveActivation(data, "session1", "activation, not a crash", 0);
        assertEquals("activation, not a crash", RecoveryCapture.render(data.read()));
        String saved = data.clear();
        assertEquals("", RecoveryCapture.render(data.read()));
        data.restoreIfEmpty(saved);
        assertEquals("activation, not a crash", RecoveryCapture.render(data.read()));
    }

    @Test public void activationIsBoundedDeduplicatedAndCannotEvictFailures() throws Exception {
        RecoveryStore data = data();
        String failure = "retained failure";
        data.update(s -> s.put("incidents", new JSONArray().put(new JSONObject().put("id", "failure")
                .put("report", failure).put("hash", RecoveryPublisher.sha(failure)))));
        for (int i = 0; i < 8; i++) RecoveryCapture.saveActivation(data, "session" + i, "activation" + i, 0);
        RecoveryCapture.saveActivation(data, "session7", "activation7", 0);
        assertEquals(3, data.read().getJSONArray("activations").length());
        assertEquals(1, data.read().getJSONArray("incidents").length());
        assertTrue(RecoveryCapture.render(data.read()).contains(failure));
    }

    @Test public void preClearWriterCannotRestoreAnActivation() throws Exception {
        RecoveryStore data = data(); data.clear();
        RecoveryCapture.saveActivation(data, "old", "stale activation", 0);
        assertEquals("", RecoveryCapture.render(data.read()));
    }
}
