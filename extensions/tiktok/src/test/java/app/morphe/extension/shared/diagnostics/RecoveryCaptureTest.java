package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class RecoveryCaptureTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private Context context; private RecoveryStore data;
    static final String TRACE = "Cmd line: secret-private-text\n"
            + "\"main\" prio=5 tid=1 Blocked\n"
            + "  at app.example.Player.open(Player.java:42)\n"
            + "  - waiting to lock <0x1234> (a java.lang.Object) held by thread 9\n"
            + "\"account@example.com token=secret\" prio=5 tid=9 Native\n"
            + "  native: #00 pc 001234 /private/user/alice/libbinder.so (unknown)\n"
            + "  at android.os.BinderProxy.transactNative(Native Method)\n"
            + "\"unrelated\" prio=5 tid=10 Runnable\n"
            + "  at private.example.Messages.secret(SourceFile:1)\n";

    @Before public void setup() throws Exception {
        RecoveryCapture.resetForTests();
        File directory = temp.newFolder();
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
        data = new RecoveryStore(context);
    }
    @After public void reset() { RecoveryCapture.resetForTests(); }

    private final class Source implements RecoveryCapture.Source {
        long now = 1000, bootEpoch; int pid = 101; List<RecoveryCapture.Exit> exits = Collections.emptyList();
        public long now() { return now; }
        public long elapsed() { return now - bootEpoch; }
        public int pid() { return pid; }
        public boolean main(Context c) { return true; }
        public String build(Context context) { return "source_commit: test-build\n"; }
        public List<RecoveryCapture.Exit> history(Context c) { return exits; }
    }
    private RecoveryCapture.Exit exit(String process, int reason, long time, RecoveryCapture.OpenTrace stream) {
        return new RecoveryCapture.Exit(process, 101, reason, time, stream);
    }
    private InputStream trace() { return new ByteArrayInputStream(TRACE.getBytes(StandardCharsets.UTF_8)); }
    private Source firstLaunch() throws Exception {
        Source source = new Source(); RecoveryCapture.recover(context, "unit-v1", source, data); return source;
    }

    @Test public void defaultBuildNeverArmsCaptureOrCreatesAStore() {
        RecoveryCapture.onContext(context);
        assertEquals("", RecoveryCapture.trialId());
        assertFalse(new File(context.getFilesDir(), "metra_recovery_v1.json").exists());
    }
    @Test public void firstLaunchDoesNotInventAnIncident() throws Exception {
        firstLaunch(); assertEquals(1, data.read().getInt("starts")); assertEquals("", RecoveryCapture.cachedReport());
    }
    @Test public void anOlderAnrIsNotHiddenByANewerOrdinaryExitOrHelper() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Arrays.asList(exit(context.getPackageName() + ":helper", 6, 1800, this::trace),
                exit(context.getPackageName(), 1, 1700, () -> null),
                exit(context.getPackageName(), 6, 1500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        JSONObject incident = data.read().getJSONArray("incidents").getJSONObject(0);
        assertEquals(1500, incident.getLong("at"));
        assertTrue(incident.getString("report").contains("trace_status: captured"));
        assertTrue(incident.getString("report").contains("Player.open(Player.java:42)"));
        assertEquals(1, data.read().getJSONArray("incidents").length());
    }
    @Test public void recoveredAnrTraceAttachedToSelfExitIsStillCollected() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Collections.singletonList(exit(context.getPackageName(), 1, 1500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertTrue(RecoveryCapture.cachedReport().contains("exit_reason: 1"));
        assertTrue(RecoveryCapture.cachedReport().contains("trace_status: captured"));
    }
    @Test public void missingTraceAndNativeBinaryAreExplicitWithoutDecodingBinary() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Arrays.asList(exit(context.getPackageName(), 6, 1400, () -> null),
                exit(context.getPackageName(), 5, 1500, () -> { throw new AssertionError("native protobuf was opened"); }));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertTrue(RecoveryCapture.cachedReport().contains("trace_status: unavailable"));
        assertTrue(RecoveryCapture.cachedReport().contains("trace_status: native_binary_not_decoded"));
    }
    @Test public void repeatedLaunchesDoNotRenewLeaseOrDuplicateAnIncident() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Collections.singletonList(exit(context.getPackageName(), 6, 1500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        s.now = 3000; s.pid = 103; RecoveryCapture.recover(context, "unit-v1", s, data);
        s.now = 4000; s.pid = 104; RecoveryCapture.recover(context, "unit-v1", s, data);
        assertEquals(1000, data.read().getLong("started")); assertEquals(3, data.read().getInt("starts"));
        assertEquals(1, data.read().getJSONArray("incidents").length());
    }
    @Test public void priorPidAndTrialTimeMustMatchBeforeAttributingARecord() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Arrays.asList(new RecoveryCapture.Exit(context.getPackageName(), 999, 6, 1500, this::trace),
                exit(context.getPackageName(), 6, 500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertTrue(RecoveryCapture.cachedReport().contains("source: recovery_trial_status"));
        assertFalse(RecoveryCapture.cachedReport().contains("exit_reason: 6"));
    }
    @Test public void onlySelectedThreadsAndMetadataLeaveTheTraceParser() throws Exception {
        RecoveryTrace.Result r = RecoveryTrace.read(trace());
        assertEquals("captured", r.status);
        assertTrue(r.text.contains("role=main")); assertTrue(r.text.contains("role=lock_owner"));
        assertTrue(r.text.contains("held_by_tid=9")); assertTrue(r.text.contains("libbinder.so"));
        for (String privateText : new String[]{"account@", "secret", "/private", "alice", "Messages", "0x1234"})
            assertFalse(privateText, r.text.contains(privateText));
    }
    @Test public void traceInputIsBoundedAndInvalidTextIsNotExported() throws Exception {
        byte[] large = new byte[RecoveryTrace.MAX_INPUT + 20]; Arrays.fill(large, (byte) 'a');
        assertEquals("truncated_no_main", RecoveryTrace.read(new ByteArrayInputStream(large)).status);
        assertEquals("invalid_text", RecoveryTrace.read(new ByteArrayInputStream(new byte[]{(byte) 0xff})).status);
    }
    @Test public void completedStateSurvivesANewStoreAndFailedOversizedWrite() throws Exception {
        data.update(s -> s.put("marker", "last-complete"));
        try { data.update(s -> s.put("too_big", String.join("", Collections.nCopies(RecoveryStore.MAX_BYTES, "x")))); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals("last-complete", new RecoveryStore(context).read().getString("marker"));
    }
    @Test public void expiryAndAChangedBootDoNotRenewOrAttributeOldPids() throws Exception {
        Source s = firstLaunch(); s.now = 25_000; s.pid = 102; s.bootEpoch = 20_000;
        s.exits = Collections.singletonList(exit(context.getPackageName(), 6, 1500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertEquals(1, data.read().getInt("starts"));
        assertTrue(RecoveryCapture.cachedReport().contains("capture_status: boot_or_clock_changed"));
        assertFalse(RecoveryCapture.cachedReport().contains("exit_reason: 6"));
        s.bootEpoch = 0; s.now = 1000 + RecoveryCapture.LEASE_MS + 1;
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertEquals(1, data.read().getInt("starts"));
        assertTrue(RecoveryCapture.cachedReport().contains("capture_status: collection_window_expired"));
    }
    @Test public void unreadableStateIsPreservedOnceAndDoesNotRearmCollection() throws Exception {
        File path = new File(context.getFilesDir(), "metra_recovery_v1.json");
        byte[] invalid = "{broken-private-canary".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(path.toPath(), invalid);
        try { data.read(); fail(); } catch (RecoveryStore.InvalidState expected) { }
        data.quarantineUnreadable("unit-v1");
        assertArrayEquals(invalid, java.nio.file.Files.readAllBytes(new File(path + ".unreadable").toPath()));
        RecoveryCapture.recover(context, "unit-v1", new Source(), data);
        assertTrue(data.read().getBoolean("capture_disabled")); assertFalse(data.read().has("sessions"));
        assertTrue(RecoveryCapture.cachedReport().contains("unreadable_private_state"));
        assertFalse(RecoveryCapture.cachedReport().contains("canary"));
        RecoveryCapture.recover(context, "unit-v2", new Source(), data);
        assertFalse(data.read().optBoolean("capture_disabled")); assertEquals(1, data.read().getInt("starts"));
    }
    @Test public void aBlockedHistoryCallCannotQueueMoreProbesOrHoldTheStore() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RecoveryCapture.Source blocked = new RecoveryCapture.Source() {
            public long now() { return 1000; }
            public int pid() { return 101; }
            public boolean main(Context c) { return true; }
            public String build(Context c) { return "source_commit: blocked-test"; }
            public List<RecoveryCapture.Exit> history(Context c) throws Exception {
                calls.incrementAndGet(); release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                return Collections.emptyList();
            }
        };
        try {
            RecoveryCapture.recover(context, "unit-v1", blocked, data);
            assertEquals("unavailable_or_timeout", data.read().getString("history_status"));
            RecoveryCapture.recover(context, "unit-v1", blocked, data);
            assertEquals("busy", data.read().getString("history_status"));
            assertEquals(1, calls.get()); data.update(s -> s.put("marker", "responsive"));
        } finally { release.countDown(); awaitProbeIdle(); }
    }
    private static void awaitProbeIdle() throws Exception {
        java.lang.reflect.Field f = RecoveryCapture.class.getDeclaredField("PROBE_BUSY"); f.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean busy = (java.util.concurrent.atomic.AtomicBoolean) f.get(null);
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (busy.get() && System.nanoTime() < end) Thread.sleep(1);
        assertFalse("probe did not finish", busy.get());
    }
    @Test public void timedOutAnrAndOrdinaryExitTracesAreUpgradedOnTheNextCheck() throws Exception {
        for (int reason : new int[]{6, 10}) {
            data = new RecoveryStore(new File(temp.newFolder(), "state.json"));
            Source s = firstLaunch(); s.now = 2000; s.pid = 102;
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            s.exits = Collections.singletonList(exit(context.getPackageName(), reason, 1500, () -> {
                if (reads.incrementAndGet() == 1) release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                return trace();
            }));
            try {
                RecoveryCapture.recover(context, "unit-v1", s, data, 30);
                assertTrue(data.read().getJSONArray("incidents").getJSONObject(0).getBoolean("retryable"));
                assertTrue(RecoveryCapture.cachedReport().contains("trace_status: timeout"));
                assertFalse(RecoveryCapture.cachedReport().contains("no_matching_failure_trace"));
                if (reason == 10) assertTrue(RecoveryCapture.cachedReport().contains("source: recovery_trial_status"));
            } finally { release.countDown(); awaitProbeIdle(); }
            s.now = 3000; s.pid = 103;
            RecoveryCapture.recover(context, "unit-v1", s, data);
            assertEquals(2, reads.get()); assertEquals(1, data.read().getJSONArray("incidents").length());
            assertFalse(data.read().getJSONArray("incidents").getJSONObject(0).getBoolean("retryable"));
            assertTrue(RecoveryCapture.cachedReport().contains("trace_status: captured"));
        }
    }
    @Test public void aNewTrialRearmsCollectionAndRetainsEarlierEvidence() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Collections.singletonList(exit(context.getPackageName(), 6, 1500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        s.now = RecoveryCapture.LEASE_MS + 3000; s.pid = 103;
        RecoveryCapture.recover(context, "unit-v2", s, data);
        assertEquals("unit-v2", data.read().getString("trial"));
        assertEquals(s.now, data.read().getLong("started")); assertEquals(1, data.read().getInt("starts"));
        assertTrue(RecoveryCapture.cachedReport().contains("unit-v1-1500-101"));
    }
    @Test public void oneFinalCheckRecoversTheThirdTrackedStartWithoutTrackingAnother() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        RecoveryCapture.recover(context, "unit-v1", s, data);
        s.now = 3000; s.pid = 103; RecoveryCapture.recover(context, "unit-v1", s, data);
        s.now = 4000; s.pid = 104;
        s.exits = Collections.singletonList(new RecoveryCapture.Exit(context.getPackageName(), 103, 6, 3500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertTrue(RecoveryCapture.cachedReport().contains("unit-v1-3500-103"));
        assertEquals(3, data.read().getInt("starts")); assertEquals(4, data.read().getInt("checks"));
        assertEquals(103, data.read().getJSONArray("sessions").getJSONObject(2).getInt("pid"));
        s.now = 5000; s.pid = 105; RecoveryCapture.recover(context, "unit-v1", s, data);
        assertEquals(4, data.read().getInt("checks"));
    }
    @Test public void clearSuppressesOldExitReplayAndUndoRestoresTheClearedSnapshot() throws Exception {
        Source s = new Source(); s.now = System.currentTimeMillis() - 2000;
        RecoveryCapture.recover(context, "unit-v1", s, data);
        s.now += 1000; s.pid = 102;
        s.exits = Collections.singletonList(exit(context.getPackageName(), 6, s.now - 500, this::trace));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        String saved = RecoveryCapture.clear(context);
        s.now = System.currentTimeMillis() + 10; s.pid = 103;
        RecoveryCapture.recover(context, "unit-v1", s, data);
        assertEquals("", RecoveryCapture.cachedReport());
        assertFalse(data.read().has("incidents"));
        RecoveryCapture.restore(context, saved);
        assertTrue(RecoveryCapture.cachedReport().contains("trace_status: captured"));
        assertEquals(0, data.read().optLong("cleared_through"));
    }
    @Test public void desugaredKotlinAndNativeFramesRemainUsefulWithoutArbitraryText() {
        RecoveryTrace.Result r = RecoveryTrace.select("\"main\" prio=5 tid=1 Runnable\n"
                + " at example.Iface$-CC.call(Foo.kt:12)\n at example.-$$Nest$invoke.run(Native method)\n"
                + " at example.Worker.run(unavailable)\n at private injected message(secret-text)\n", false);
        assertTrue(r.text.contains("Iface$-CC.call(Foo.kt:12)"));
        assertTrue(r.text.contains("-$$Nest$invoke.run(Native method)"));
        assertTrue(r.text.contains("Worker.run(unavailable)"));
        assertTrue(r.text.contains("at [frame omitted]")); assertFalse(r.text.contains("secret-text"));
    }
    @Test public void aFrameBudgetIsReportedAsTruncationWithoutDroppingTheLockOwner() {
        StringBuilder trace = new StringBuilder("\"main\" prio=5 tid=1 Blocked\n");
        for (int i = 0; i < 45; i++) trace.append(" at example.Worker.call(Worker.java:1)\n");
        trace.append(" - waiting to lock <0x1234> held by thread 9\n\"owner\" prio=5 tid=9 Runnable\n")
                .append(" at example.Owner.hold(Owner.kt:2)\n");
        RecoveryTrace.Result r = RecoveryTrace.select(trace.toString(), false);
        assertEquals("truncated", r.status); assertTrue(r.text.contains("Owner.hold(Owner.kt:2)"));
        assertEquals(40, r.text.split("Worker.call", -1).length - 1);
    }
    @Test public void clearAndUndoCannotOverwriteNewerEvidence() throws Exception {
        data.update(s -> s.put("trial", "unit-v1").put("started", 1000)
                .put("incidents", new org.json.JSONArray().put(new JSONObject().put("id", "old"))));
        String saved = data.clear();
        assertEquals(1000, data.read().getLong("started"));
        data.update(s -> s.put("incidents", new org.json.JSONArray().put(new JSONObject().put("id", "new"))));
        data.restoreIfEmpty(saved);
        assertEquals("new", data.read().getJSONArray("incidents").getJSONObject(0).getString("id"));
    }
}
