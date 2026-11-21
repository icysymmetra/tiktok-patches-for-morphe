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
        String build = "source_commit: test-build\n";
        public String build(Context context) { return build; }
        public List<RecoveryCapture.Exit> history(Context c) { return exits; }
    }
    private RecoveryCapture.Exit exit(String process, int reason, long time, RecoveryCapture.OpenTrace stream) {
        return new RecoveryCapture.Exit(process, 101, reason, time, stream);
    }
    private InputStream trace() { return new ByteArrayInputStream(TRACE.getBytes(StandardCharsets.UTF_8)); }
    private Source firstLaunch() throws Exception {
        Source source = new Source(); RecoveryCapture.recover(context, "unit-v1", source, data); return source;
    }

    @Test public void staleUndoCannotRollBackALaterClear() throws Exception {
        data.update(s->s.put("trial","unit"));String older=data.clear();data.clear();
        int generation=data.read().getInt("generation");long cleared=data.read().getLong("cleared_through");
        data.restoreIfEmpty(older);assertEquals(generation,data.read().getInt("generation"));
        assertEquals(cleared,data.read().getLong("cleared_through"));
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
    /**
     * What Android 15 stores when the Java dump fails (#215): StackTracesDumpHelper's uptime
     * header, then debuggerd's native backtrace, whose headers say sysTid= and never tid=.
     */
    static final String NATIVE_TRACE = "----- dumping pid: 101 at 3141592\n"
            + "\n\n----- pid 101 at 2026-11-12 05:21:41.640 +0400 -----\n"
            + "Cmd line: com.zhiliaoapp.musically\nABI: 'arm64'\n"
            + "\n\"zhiliaoapp.musi\" sysTid=101\n"
            + "    #00 pc 000000000008a2c8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24) (BuildId: 0f1e2d)\n"
            + "    #01 pc 00000000004f1a3c  /apex/com.android.art/lib64/libart.so (art::ConditionVariable::WaitHoldingLocks(art::Thread*)+140) (BuildId: 9a8b7c)\n"
            + "    #02 pc 0000000000123456  /data/app/~~Zx9q==/com.zhiliaoapp.musically-Ab3==/lib/arm64/libsscronet.so (BuildId: 77aa)\n"
            + "    #03 pc 0000000000003f10  /memfd:jit-cache (deleted) (offset 0x2000000) (com.ss.android.ugc.aweme.Player.open+212)\n"
            + "    #04 pc 0000000000a1b2c3  /data/app/~~Zx9q==/com.zhiliaoapp.musically-Ab3==/base.apk!libmetasec_ov.so (offset 0x1b4000) (BuildId: 1234)\n"
            + "\n\"Signal Catcher\" sysTid=104\n"
            + "    #00 pc 000000000008a2c8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24) (BuildId: 0f1e2d)\n"
            + "    #01 pc 00000000006c0d10  /apex/com.android.art/lib64/libart.so (art::ThreadList::SuspendAllInternal(art::Thread*, art::Thread*, art::Thread*, bool)+1020) (BuildId: 9a8b7c)\n"
            + "\n\"tp-io-4 alice@example.com\" sysTid=130\n"
            + "    #00 pc 000000000008a2c8  /apex/com.android.runtime/lib64/bionic/libc.so (__epoll_pwait+8) (BuildId: 0f1e2d)\n"
            + "\n\"HeapTaskDaemon\" sysTid=110\n"
            + "    #00 pc 000000000008a2c8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24) (BuildId: 0f1e2d)\n"
            + "\n\"secret-worker\" sysTid=140\n"
            + "    #00 pc 0000000000055500  /data/app/~~Zx9q==/com.zhiliaoapp.musically-Ab3==/lib/arm64/libttheif.so (BuildId: 5150)\n"
            + "\n----- end 101 -----\n";

    @Test public void aNativeOnlyDumpYieldsTheMainThreadAndTheRuntimesOwnThreads() throws Exception {
        RecoveryTrace.Result r = RecoveryTrace.read(
                new ByteArrayInputStream(NATIVE_TRACE.getBytes(StandardCharsets.UTF_8)), 101);
        assertEquals(r.text, "captured", r.status);
        assertTrue(r.shape, r.shape.contains("format=native java_threads=0 native_threads=5"));
        assertTrue(r.text, r.text.startsWith("thread tid=101 role=main state=native_dump\n"));
        assertTrue(r.text, r.text.contains("thread tid=104 role=signal_catcher"));
        assertTrue(r.text, r.text.contains("thread tid=110 role=gc_daemon"));
        // A worker with an unrecognised top frame gets detail as a candidate; the parked one keeps
        // only its top frame, in the summary.
        assertTrue(r.text, r.text.contains("thread tid=140 role=candidate"));
        assertFalse(r.text, r.text.contains("thread tid=130"));
        assertTrue(r.text, r.text.contains("[OTHER NATIVE THREADS, FIRST FRAMES]\n  tid=130\n"
                + "    #00 pc 000000000008a2c8 libc.so (__epoll_pwait+8)\n"));
        // An unnamed stripped frame keeps its pc, which can be symbolised against the APK later.
        assertTrue(r.text, r.text.contains("  tid=140\n    #00 pc 0000000000055500 libttheif.so\n"));
        assertTrue(r.text, r.text.indexOf("[CANDIDATE STACKS]\nthread tid=140 role=candidate")
                > r.text.indexOf("[OTHER NATIVE THREADS"));
        assertTrue(r.shape, r.shape.contains(
                "detailed=4 detail_slots_skipped=0 summarized=2 unsummarized=0 summary_symbolized=1 candidates=1 main_frames=5"));
        assertTrue(r.text, r.text.contains("libart.so (art::ThreadList::SuspendAllInternal(art::Thread*, art::Thread*, art::Thread*, bool)+1020)"));
        assertTrue(r.text, r.text.contains("native: #02 pc 0000000000123456 libsscronet.so"));
        assertTrue(r.text, r.text.contains("jit-cache (com.ss.android.ugc.aweme.Player.open+212)"));
        assertTrue(r.text, r.text.contains("native: #04 pc 0000000000a1b2c3 libmetasec_ov.so"));
        for (String privateText : new String[]{"/data/app", "~~", "Zx9q", "Ab3", "alice", "secret", "zhiliaoapp.musi", "BuildId"})
            assertFalse(privateText, r.text.contains(privateText));
    }

    @Test public void withoutAPidTheFirstNativeThreadIsTheMainThread() {
        RecoveryTrace.Result r = RecoveryTrace.select(NATIVE_TRACE, false);
        assertTrue(r.text, r.text.startsWith("thread tid=101 role=main"));
    }

    @Test public void aRecoveredNativeOnlyAnrReportsItsThreadsAndShape() throws Exception {
        Source s = firstLaunch(); s.now = 2000; s.pid = 102;
        s.exits = Collections.singletonList(exit(context.getPackageName(), 6, 1500,
                () -> new ByteArrayInputStream(NATIVE_TRACE.getBytes(StandardCharsets.UTF_8))));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        String report = RecoveryCapture.cachedReport();
        assertTrue(report, report.contains("trace_status: captured"));
        assertTrue(report, report.contains("trace_shape: bytes="));
        assertTrue(report, report.contains("format=native"));
        assertTrue(report, report.contains("thread tid=101 role=main state=native_dump"));
    }

    @Test public void aZeroOffsetSymbolKeepsItsName() {
        // FormatFrame leaves out "+0", so the symbol slot has no offset.
        assertEquals("native: #00 pc 0000000000012340 libart.so (art_quick_generic_jni_trampoline)",
                RecoveryTrace.nativeFrame("#00 pc 0000000000012340  /apex/com.android.art/lib64/libart.so "
                        + "(art_quick_generic_jni_trampoline) (BuildId: 9a8b7c)"));
        assertEquals("native: #01 pc 00000000000a0000 libc.so",
                RecoveryTrace.nativeFrame("#01 pc 00000000000a0000  /apex/com.android.runtime/lib64/bionic/libc.so"));
        assertEquals("native: #02 pc 0000000000003f10 jit-cache (com.example.Player.open)",
                RecoveryTrace.nativeFrame("#02 pc 0000000000003f10  /memfd:jit-cache (deleted) (offset 0x2000000) (com.example.Player.open)"));
    }

    @Test public void aJavaDumpCutAfterItsHeaderGivesWayToTheNativeFallback() throws Exception {
        String mixed = "----- pid 101 at 2026-11-12 05:21:41 -----\n\"main\" prio=5 tid=1 Native\n"
                + NATIVE_TRACE.substring(NATIVE_TRACE.indexOf("\n\n----- pid"));
        RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(mixed.getBytes(StandardCharsets.UTF_8)), 101);
        assertEquals(r.text, "captured", r.status);
        assertTrue(r.shape, r.shape.contains("format=mixed java_threads=1 native_threads=5"));
        assertTrue(r.text, r.text.startsWith("thread tid=101 role=main state=native_dump\n"));
        assertTrue(r.text, r.text.contains("role=signal_catcher"));
    }

    @Test public void aJavaMainWithFramesKeepsTheNativeMainAlongside() throws Exception {
        String mixed = "\"main\" prio=5 tid=1 Blocked\n  | group=\"main\" sCount=1 sysTid=101 nice=-10\n"
                + "  at app.example.Player.open(Player.java:42)\n"
                + NATIVE_TRACE.substring(NATIVE_TRACE.indexOf("\n\n----- pid"));
        RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(mixed.getBytes(StandardCharsets.UTF_8)), 101);
        assertTrue(r.text, r.text.startsWith("thread tid=1 role=main state=Blocked\n"));
        assertTrue(r.text, r.text.contains("thread tid=101 role=main_native state=native_dump"));
    }

    /** A native thread whose top frame is in a stripped library, so it qualifies as a candidate. */
    private static String busyNativeThread(int tid) {
        return "\n\"worker\" sysTid=" + tid + "\n"
                + "    #00 pc 0000000000055500  /data/app/~~Zx9q==/x-Ab3==/lib/arm64/libbusy.so (BuildId: 5150)\n";
    }

    @Test public void provenLockOwnersComeBeforeNativeGuesswork() throws Exception {
        StringBuilder trace = new StringBuilder("\"main\" prio=5 tid=1 Blocked\n  | group=\"main\" sysTid=101\n"
                + "  at app.example.Player.open(Player.java:42)\n"
                + "  - waiting to lock <0x1234> (a java.lang.Object) held by thread 9\n"
                + "\"owner\" prio=5 tid=9 Runnable\n  at app.example.Cache.fill(Cache.java:7)\n");
        trace.append(NATIVE_TRACE, NATIVE_TRACE.indexOf("\n\n----- pid"), NATIVE_TRACE.indexOf("\n----- end"));
        for (int tid = 200; tid < 210; tid++) trace.append(busyNativeThread(tid));
        RecoveryTrace.Result r = RecoveryTrace.read(
                new ByteArrayInputStream(trace.toString().getBytes(StandardCharsets.UTF_8)), 101);
        assertTrue(r.text, r.text.contains("thread tid=9 role=lock_owner state=Runnable\n  at app.example.Cache.fill"));
        assertTrue(r.text, r.text.indexOf("tid=9 role=lock_owner") < r.text.indexOf("role=signal_catcher"));
        // Running out of detailed slots never costs the summary.
        assertTrue(r.text, r.text.contains("[OTHER NATIVE THREADS, FIRST FRAMES]\n"));
        assertTrue(r.text, r.text.contains("  tid=209\n    #00 pc 0000000000055500 libbusy.so\n"));
        assertTrue(r.shape, r.shape.contains("unsummarized=0"));
        assertTrue(r.shape, r.shape.contains("candidates=4"));
    }

    @Test public void aLongJavaSectionCannotCrowdOutTheNativeFallback() throws Exception {
        StringBuilder trace = new StringBuilder("\"main\" prio=5 tid=1 Native\n");
        for (int tid = 2; tid <= RecoveryTrace.MAX_THREADS; tid++) {
            trace.append("\"w\" prio=5 tid=").append(tid).append(" Waiting\n");
        }
        trace.append(NATIVE_TRACE, NATIVE_TRACE.indexOf("\n\n----- pid"), NATIVE_TRACE.length());
        RecoveryTrace.Result r = RecoveryTrace.read(
                new ByteArrayInputStream(trace.toString().getBytes(StandardCharsets.UTF_8)), 101);
        assertEquals(r.text, "captured", r.status);
        assertTrue(r.text, r.text.startsWith("thread tid=101 role=main state=native_dump\n"));
        assertTrue(r.text, r.text.contains("role=signal_catcher"));
    }

    @Test public void aJavaOnlyLockChainKeepsTheWholeLimit() throws Exception {
        // Eight threads each waiting on the next, forty ordinary frames apiece: about 30 KiB.
        StringBuilder trace = new StringBuilder();
        for (int t = 1; t <= 8; t++) {
            trace.append('"').append(t == 1 ? "main" : "w" + t).append("\" prio=5 tid=").append(t).append(" Blocked\n");
            for (int f = 0; f < 40; f++) {
                trace.append("  at com.ss.android.ugc.aweme.feed.controller.PlayerController.step").append(f)
                        .append("(SourceFile:16778093)\n");
            }
            if (t < 8) trace.append("  - waiting to lock <0x").append(1000 + t).append("> (a java.lang.Object) held by thread ")
                    .append(t + 1).append('\n');
        }
        RecoveryTrace.Result r = RecoveryTrace.read(
                new ByteArrayInputStream(trace.toString().getBytes(StandardCharsets.UTF_8)), 101);
        assertEquals(r.shape, "captured", r.status);
        assertTrue(r.shape, r.shape.contains("detailed=8"));
        assertTrue(String.valueOf(r.text.length()), r.text.length() > 28 * 1024);
    }

    /** A native dump shaped like TikTok's on an older phone: long runtime stacks, short workers. */
    private static String realisticNativeDump(int threads) { return realisticNativeDump(threads, 101); }

    private static String realisticNativeDump(int threads, int pid) {
        StringBuilder trace = new StringBuilder("\n\n----- pid 101 at 2026-11-12 05:21:41.640 +0400 -----\n"
                + "Cmd line: com.zhiliaoapp.musically\nABI: 'arm64'\n");
        String[] names = {"zhiliaoapp.musi", "Signal Catcher", "HeapTaskDaemon"};
        for (int i = 0; i < threads; i++) {
            trace.append("\n\"").append(i < names.length ? names[i] : "tp-io-" + i).append("\" sysTid=").append(pid + i).append('\n');
            int frames = i < names.length ? 30 : 8;
            for (int f = 0; f < frames; f++) {
                if (f == 0) trace.append("    #00 pc 000000000008a2c8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24) (BuildId: 0f1e2d3c4b5a69788796a5b4c3d2e1f0)\n");
                else if (f % 3 == 1) trace.append(String.format("    #%02d pc 00000000004f1a3c  /apex/com.android.art/lib64/libart.so "
                        + "(art::ConditionVariable::WaitHoldingLocks(art::Thread*)+140) (BuildId: 9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d)\n", f));
                else if (f % 3 == 2) trace.append(String.format("    #%02d pc 0000000000123456  /data/app/~~Zx9qAbCdEf==/com.zhiliaoapp.musically-Ab3DeFgH==/lib/arm64/libsscronet.so "
                        + "(BuildId: 77aa66bb55cc44dd33ee22ff11009988)\n", f));
                else trace.append(String.format("    #%02d pc 0000000000003f10  /memfd:jit-cache (deleted) (offset 0x2000000) "
                        + "(com.ss.android.ugc.aweme.feed.controller.PlayerController.onRender+212)\n", f));
            }
        }
        return trace.append("\n----- end 101 -----\n").toString();
    }

    @Test public void aRealisticNativeDumpSummarisesEveryThread() throws Exception {
        for (int threads : new int[]{150, 200, 250}) {
            RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(
                    realisticNativeDump(threads).getBytes(StandardCharsets.UTF_8)), 101);
            assertTrue(threads + " " + r.shape, r.shape.contains("unsummarized=0"));
            assertTrue(threads + " " + r.shape, r.shape.contains("summarized=" + (threads - 3)));
            assertTrue(threads + " " + r.text.length(), r.text.length() <= RecoveryTrace.MAX_OUTPUT);
            assertTrue(r.text, r.text.startsWith("thread tid=101 role=main state=native_dump\n"));
            assertTrue(r.text, r.text.contains("role=signal_catcher"));
        }
    }

    @Test public void threeFullSizedReportsFitTheStoreAndTheExport() throws Exception {
        // The store refuses anything over its limit, and that refusal would drop every incident,
        // so the largest reports the parser can now produce must still fit three at a time.
        Source s = new Source();
        s.build = "source_commit: " + String.join("", Collections.nCopies(6000, "b")) + "\n";
        RecoveryCapture.recover(context, "unit-v1", s, data);
        for (int launch = 2; launch <= 4; launch++) {
            s.now = launch * 1000L; s.pid = 100 + launch;
            int failed = 99 + launch;
            String dump = realisticNativeDump(250, failed);
            s.exits = Arrays.asList(new RecoveryCapture.Exit(context.getPackageName(), failed, 6,
                    launch * 1000L - 500, () -> new ByteArrayInputStream(dump.getBytes(StandardCharsets.UTF_8))));
            RecoveryCapture.recover(context, "unit-v1", s, data);
        }
        org.json.JSONArray kept = data.read().getJSONArray("incidents");
        assertEquals(3, kept.length());
        for (int i = 0; i < kept.length(); i++) {
            // Each must also pass render's own per-report limit, or the export skips it.
            assertTrue(kept.getJSONObject(i).getString("report").length() <= 64 * 1024);
        }
        String report = RecoveryCapture.cachedReport();
        assertEquals(3, report.split("MORPHE DIAGNOSTIC REPORT", -1).length - 1);
        assertEquals(3, report.split("role=signal_catcher", -1).length - 1);
        String activation = String.join("", Collections.nCopies(6000, "/"));
        for (int i = 0; i < 3; i++) RecoveryCapture.saveActivation(data, "activation" + i, activation, 0);
        assertEquals(3, data.read().getJSONArray("activations").length());
        assertEquals(3, data.read().getJSONArray("incidents").length());
        assertEquals(3, RecoveryCapture.render(data.read()).split("role=signal_catcher", -1).length - 1);
        assertTrue(new File(context.getFilesDir(), "metra_recovery_v1.json").length() <= RecoveryStore.MAX_BYTES);
    }

    @Test public void maximumEscapedIncidentsBuildsActivationsAndJournalFitTogether() throws Exception {
        String incident = String.join("", Collections.nCopies(RecoveryCapture.MAX_STORED_INCIDENT / 2 - 1, "/"));
        assertEquals(RecoveryCapture.MAX_STORED_INCIDENT, RecoveryCapture.storedBytes(incident));
        org.json.JSONArray incidents = new org.json.JSONArray(), sessions = new org.json.JSONArray();
        for (int i = 0; i < 3; i++) incidents.put(new JSONObject().put("id", "incident" + i)
                .put("at", Long.MAX_VALUE).put("report", incident).put("hash", RecoveryPublisher.sha(incident)));
        String build = String.join("", Collections.nCopies(RecoveryCapture.MAX_STORED_BUILD / 2 - 1, "/"));
        for (int i = 0; i < 4; i++) sessions.put(new JSONObject().put("pid", Integer.MAX_VALUE)
                .put("at", Long.MAX_VALUE).put("build", build).put("process_started_elapsed_ms",Long.MAX_VALUE)
                .put("capture_preference_at_start","unknown"));
        String journalField = String.join("", Collections.nCopies(RecoveryPublisher.MAX_JOURNAL_FIELD, "/"));
        String hash = RecoveryPublisher.sha(incident);
        data.update(state -> state.put("trial", "issue215-softref-v1-00000000-0000-0000-0000-000000000000")
                .put("started", Long.MAX_VALUE).put("started_elapsed", Long.MAX_VALUE)
                .put("export_until", Long.MAX_VALUE).put("boot_epoch", Long.MAX_VALUE)
                .put("sessions", sessions).put("incidents", incidents)
                .put("export", new JSONObject().put("uri", journalField).put("name", journalField)
                        .put("hash", hash).put("verified_hash", hash).put("attempts", 3).put("status", "verified")));
        String activation = String.join("", Collections.nCopies(5929, "/"))
                + String.join("", Collections.nCopies(71, "\u0001"));
        assertEquals(RecoveryCapture.MAX_STORED_ACTIVATION - 2, RecoveryCapture.storedBytes(activation));
        for (int i = 0; i < 3; i++) RecoveryCapture.saveActivation(data,
                "issue215-softref-v1-00000000-0000-0000-0000-000000000000-2147483647-9223372036854775807-" + i,
                activation, 0);
        assertEquals(3, data.read().getJSONArray("incidents").length());
        assertEquals(3, data.read().getJSONArray("activations").length());
        assertTrue(new File(context.getFilesDir(), "metra_recovery_v1.json").length() <= RecoveryStore.MAX_BYTES);
        assertFalse(RecoveryCapture.render(data.read()).isEmpty());
    }

    @Test public void multibyteBuildsAreBoundedByEscapedBytesAsWellAsCharacters() throws Exception {
        Source s = new Source();
        s.build = String.join("", Collections.nCopies(6000, "\u4e2d"));
        RecoveryCapture.recover(context, "unit-v1", s, data);
        String build = data.read().getJSONArray("sessions").getJSONObject(0).getString("build");
        assertTrue(RecoveryCapture.storedBytes(build) <= RecoveryCapture.MAX_STORED_BUILD);
        assertTrue(build.endsWith("[build details truncated]"));
    }

    @Test public void reportsThatEscapeLargeAreTrimmedNotLost() throws Exception {
        // Escaping writes "/" as "\/", so a report within MAX_OUTPUT characters could double in
        // the store; three of them once overflowed it and the third incident was lost.
        String name = String.join("", Collections.nCopies(1000, "a/")) + "X.m";
        StringBuilder java = new StringBuilder("\"main\" prio=5 tid=1 Blocked\n");
        for (int f = 0; f < 40; f++) java.append("  at ").append(name).append(f).append("(SourceFile:1)\n");
        String trace = java.toString();
        Source s = new Source();
        RecoveryCapture.recover(context, "unit-v1", s, data);
        for (int launch = 2; launch <= 4; launch++) {
            s.now = launch * 1000L; s.pid = 100 + launch;
            s.exits = Arrays.asList(new RecoveryCapture.Exit(context.getPackageName(), 99 + launch, 6,
                    launch * 1000L - 500, () -> new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8))));
            RecoveryCapture.recover(context, "unit-v1", s, data);
        }
        org.json.JSONArray kept = data.read().getJSONArray("incidents");
        assertEquals(3, kept.length());
        for (int i = 0; i < kept.length(); i++) {
            String report = kept.getJSONObject(i).getString("report");
            assertTrue(RecoveryCapture.storedBytes(report) <= RecoveryCapture.MAX_STORED_INCIDENT);
            assertTrue(report, report.endsWith("[report trimmed to fit private storage]\n"));
            assertTrue(report, report.contains("thread tid=1 role=main"));
        }
        assertEquals(3, RecoveryCapture.cachedReport().split("MORPHE DIAGNOSTIC REPORT", -1).length - 1);
    }

    @Test public void anOrdinaryReportIsStoredUntouched() {
        String report = "MORPHE DIAGNOSTIC REPORT\nthread tid=1 role=main\n";
        assertSame(report, RecoveryCapture.fitForStore(report));
    }

    @Test public void theOutputLimitHoldsAfterRedaction() throws Exception {
        StringBuilder trace = new StringBuilder();
        for (int tid = 101; tid < 101 + RecoveryTrace.MAX_THREADS; tid++) {
            trace.append("\n\"t\" sysTid=").append(tid).append('\n');
            for (int f = 0; f < 50; f++) {
                trace.append("    #").append(f).append(" pc 00000000004f1a3c  /apex/com.android.art/lib64/libart.so ")
                        .append("(fe80::1:2:3::Thread").append(f).append("(art::Thread*, art::Thread*, art::Thread*)+1020)\n");
            }
        }
        RecoveryTrace.Result r = RecoveryTrace.read(
                new ByteArrayInputStream(trace.toString().getBytes(StandardCharsets.UTF_8)), 101);
        assertTrue(String.valueOf(r.text.length()), r.text.length() <= RecoveryTrace.MAX_OUTPUT);
        assertTrue(r.text.endsWith("\n"));
        assertEquals("truncated", r.status);
    }

    @Test public void aRenamedJavaMainIsFoundByItsKernelThread() throws Exception {
        String trace = "\"renamed-ui\" prio=5 tid=1 Runnable\n  | group=\"main\" sCount=0 sysTid=101 nice=-10\n"
                + "  at app.example.Player.open(Player.java:42)\n";
        RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)), 101);
        assertEquals("captured", r.status);
        assertTrue(r.text, r.text.startsWith("thread tid=1 role=main state=Runnable\n"));
        assertFalse(r.text, r.text.contains("renamed"));
    }

    @Test public void aMainWithNoFramesIsNotCalledCaptured() throws Exception {
        RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(
                "\"main\" prio=5 tid=1 Native\n".getBytes(StandardCharsets.UTF_8)), 101);
        assertEquals("main_without_frames", r.status);
    }

    @Test public void aSplitCharacterInAThreadNameKeepsTheStacks() throws Exception {
        byte[] head = "\n\"zhiliaoapp.mus".getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\" sysTid=101\n    #00 pc 000000000008a2c8  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+24)\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] trace = new byte[head.length + 1 + tail.length];
        System.arraycopy(head, 0, trace, 0, head.length);
        trace[head.length] = (byte) 0xe2; // the first byte of a three-byte character, cut off
        System.arraycopy(tail, 0, trace, head.length + 1, tail.length);
        RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(trace), 101);
        assertEquals(r.text, "captured", r.status);
        assertTrue(r.shape, r.shape.contains("decode_replaced=true"));
        assertTrue(r.text, r.text.contains("libc.so (syscall+24)"));
    }

    @Test public void commonRuntimeSymbolsSurviveRedaction() throws Exception {
        String[] symbols = {
                "art::ThreadList::SuspendAllInternal(art::Thread*, art::Thread*, art::Thread*, bool)+1020",
                "art::gc::Heap::WaitForGcToCompleteLocked(art::gc::GcCause, art::Thread*)+80",
                "art::Monitor::Lock<(art::LockReason)1>(art::Thread*)+404",
                "art::ConditionVariable::WaitHoldingLocks(art::Thread*)+140",
                "android::IPCThreadState::talkWithDriver(bool)+256",
        };
        StringBuilder trace = new StringBuilder("\"x\" sysTid=101\n");
        for (int i = 0; i < symbols.length; i++) {
            trace.append("    #0").append(i).append(" pc 00000000004f1a3c  /apex/com.android.art/lib64/libart.so (")
                    .append(symbols[i]).append(") (BuildId: 9a8b7c)\n");
        }
        RecoveryTrace.Result r = RecoveryTrace.read(new ByteArrayInputStream(trace.toString().getBytes(StandardCharsets.UTF_8)), 101);
        for (String symbol : symbols) assertTrue(symbol + "\n" + r.text, r.text.contains("(" + symbol + ")"));
    }

    @Test public void aJavaDumpStillReportsItsShape() throws Exception {
        RecoveryTrace.Result r = RecoveryTrace.read(trace());
        assertTrue(r.shape, r.shape.contains("format=java java_threads=3 native_threads=0"));
    }

    @Test public void traceInputIsBoundedAndInvalidTextIsNotExported() throws Exception {
        byte[] large = new byte[RecoveryTrace.MAX_INPUT + 20]; Arrays.fill(large, (byte) 'a');
        assertEquals("truncated_no_main", RecoveryTrace.read(new ByteArrayInputStream(large)).status);
        RecoveryTrace.Result invalid = RecoveryTrace.read(new ByteArrayInputStream(new byte[]{(byte) 0xff}));
        assertEquals("no_main_thread", invalid.status);
        assertTrue(invalid.shape, invalid.shape.contains("decode_replaced=true"));
        assertEquals("", invalid.text);
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
