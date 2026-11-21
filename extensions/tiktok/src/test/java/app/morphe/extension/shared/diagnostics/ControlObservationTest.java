package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.ContextWrapper;
import android.os.SystemClock;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ControlObservationTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    @Before public void before() { RecoveryCapture.resetForTests(); }
    @After public void after() { RecoveryCapture.resetForTests(); }
    @Test public void defaultsDoNotEnableAControlOrClaimConfigurationAbsence() {
        assertEquals("", RecoveryCapture.controlArm()); assertEquals("", ControlObservation.profile());
        assertTrue(ControlObservation.state().startsWith("first_config=unobserved entries=0"));
    }
    @Test public void concurrentNativeReturnsStayPairedWithTheirArguments() throws Exception {
        Thread[] workers = new Thread[4];
        for (int i = 0; i < 4; i++) {
            final int n = i;
            workers[i] = new Thread(() -> {
                int token = ControlObservation.begin(.5, n, n + 10);
                ControlObservation.nativeStarting(token);
                ControlObservation.returned(token, n + 100);
            }); workers[i].start();
        }
        for (Thread t : workers) { t.join(5000); assertFalse(t.isAlive()); }
        String state = ControlObservation.state();
        for (int n = 0; n < 4; n++) assertTrue(state, state.contains("min_hex:" + ControlObservation.bits(n) + ",max_hex:" + ControlObservation.bits(n + 10) + ",phase:returned,result:" + (n + 100)));
        for (int n = 0; n < 100; n++) assertEquals(0, ControlObservation.begin(.5, n, n));
        assertTrue(ControlObservation.state().contains("entries=over_4"));
        assertTrue(ControlObservation.state().length() <= RecoveryTimeline.MAX_CONTROL);
    }
    @Test public void nullConfigurationAndUnavailableLibraryDifferFromUnobservedAndPendingNative() {
        ControlObservation.configObserved(null);
        int a = ControlObservation.begin(0, -1, -1); ControlObservation.unavailable(a);
        int b = ControlObservation.begin(.7, 12, 24); ControlObservation.nativeStarting(b);
        String state = ControlObservation.state();
        assertTrue(state.contains("config=null")); assertTrue(state.contains("phase:library_unavailable"));
        assertTrue(state.contains("phase:native_pending")); assertFalse(state.contains("result:"));
    }
    @Test public void lifecycleCountsResumedTimeWithoutDoubleCountingOverlappingActivities() {
        Activity a = Robolectric.buildActivity(Activity.class).get(), b = Robolectric.buildActivity(Activity.class).get();
        ControlObservation.activity(a, true); SystemClock.sleep(1000);
        ControlObservation.activity(b, true); ControlObservation.activity(a, true); SystemClock.sleep(1000);
        ControlObservation.activity(a, false); SystemClock.sleep(1000);
        ControlObservation.activity(b, false); SystemClock.sleep(1000);
        assertTrue(ControlObservation.state(), ControlObservation.state().contains("resumed_s=3 resumes=2"));
    }
    @Test public void exactArgumentsSurvivePublicReportRedactionAndFitTheirOwnBudget() {
        for (int i = 0; i < 4; i++) {
            int token = ControlObservation.begin(Double.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE);
            ControlObservation.returned(token, -1);
        }
        String state = ControlObservation.state();
        assertEquals(state, DiagnosticRedactor.redact(state));
        assertTrue(state.length() <= RecoveryTimeline.MAX_CONTROL);
        assertTrue(state.contains("7fff_ffff_ffff_ffff"));
    }
    private RecoveryStore store() throws Exception {
        File dir = temp.newFolder();
        RecoveryStore data = new RecoveryStore(new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return dir; }
        });
        data.update(s -> s.put("trial", "control").put("sessions", new JSONArray()
                .put(new JSONObject().put("pid", 10).put("at", 1000).put("build", "test build"))));
        return data;
    }
    @Test public void startupReceiptExistsWithoutFailureAndUpdatesInPlaceWithoutEvictingAnIncident() throws Exception {
        RecoveryStore data = store();
        data.update(s -> s.put("incidents", new JSONArray().put(new JSONObject().put("id", "old").put("report", "retained"))));
        assertTrue(RecoveryCapture.recordControlReceipt(data, "control", 10, 1000, 1001, "minimal", "observed_hash=x", ControlObservation.state()));
        assertFalse(RecoveryCapture.recordControlReceipt(data, "control", 10, 1000, 1002, "minimal", "observed_hash=x", ControlObservation.state()));
        ControlObservation.configObserved(null);
        assertTrue(RecoveryCapture.recordControlReceipt(data, "control", 10, 1000, 1003, "minimal", "observed_hash=x", ControlObservation.state()));
        assertEquals(1, data.read().getJSONArray("activations").length());
        assertEquals(1, data.read().getJSONArray("incidents").length());
        assertTrue(RecoveryCapture.render(data.read()).contains("not a crash or proof of stability"));
        String saved = data.clear();
        assertFalse(RecoveryCapture.recordControlReceipt(data, "control", 10, 1000, 1004, "minimal", "observed_hash=x", ControlObservation.state()));
        assertEquals("", RecoveryCapture.render(data.read()));
        data.restoreIfEmpty(saved); assertTrue(RecoveryCapture.render(data.read()).contains("config=null"));
    }
}
