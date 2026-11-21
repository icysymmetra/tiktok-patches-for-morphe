package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
public class RecoveryTimelineTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private File files;

    @Before public void setup() throws Exception { RecoveryCapture.resetForTests(); files = temp.newFolder(); }
    @After public void reset() { RecoveryCapture.resetForTests(); }

    /** A clock the test moves, and readings that say which sample they were. */
    private static class Fake implements RecoveryTimeline.Clock, RecoveryTimeline.Probe {
        long wall = 100_000, elapsed = 0; int samples, jatoReads; String jato = "jato JatoXL.isInited=true";
        /** Added to the elapsed clock after the given sample, as if the phone slept that long. */
        int sleepAfter = -1; long sleptMs;
        public long wall() { return wall; }
        public long elapsed() { return elapsed; }
        public String sample() {
            samples++; wall += RecoveryTimeline.PERIOD_MS; elapsed += RecoveryTimeline.PERIOD_MS;
            if (samples == sleepAfter) { elapsed += sleptMs; wall += sleptMs; }
            return "heap=" + samples + "/512M";
        }
        public String jato() { jatoReads++; return jato; }
    }

    private String file(int pid, long at) throws Exception {
        return new String(Files.readAllBytes(RecoveryTimeline.file(files, pid, at).toPath()), StandardCharsets.UTF_8);
    }

    @Test public void earlyWakeReadingsDoNotAdvanceTheFirstOrSubsequentJatoSnapshots() throws Exception {
        List<Long> observed = new ArrayList<>();
        Fake fake = new Fake() {
            @Override public String sample() {
                String value = super.sample();
                if (samples <= 2) { elapsed -= RecoveryTimeline.PERIOD_MS; wall -= RecoveryTimeline.PERIOD_MS; }
                return value;
            }
            @Override public String jato() { observed.add(elapsed); return super.jato(); }
        };
        RecoveryTimeline.run(files, 101, 7, 160_000, fake, fake, false);
        assertEquals(Arrays.asList(20_000L, 140_000L), observed);
    }

    @Test public void readingsStopAtTheEndOfTheLeaseAndKeepOnlyTheNewest() throws Exception {
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 200 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertEquals(200, fake.samples);
        String text = file(101, 7);
        assertTrue(text, text.startsWith("pid=101 session_at=7\n"));
        assertTrue(text, text.contains(" heap=200/512M\n"));
        assertFalse(text, text.contains(" heap=100/512M\n"));
        assertTrue(text.split("\n").length <= RecoveryTimeline.MAX_SAMPLES + 2);
    }

    @Test public void aPhoneAsleepPastTheLeaseTakesNoFurtherReading() throws Exception {
        Fake fake = new Fake(); fake.sleepAfter = 2; fake.sleptMs = 60 * 60_000L;
        RecoveryTimeline.run(files, 101, 7, 30 * 60_000L, fake, fake, false);
        assertEquals("the lease is kept on the clock that counts sleep", 2, fake.samples);
    }

    @Test public void tiktoksTuningIsReadAfterStartupAndKeptThroughALongSession() throws Exception {
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 3 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertEquals("not read before its startup task has run", 0, fake.jatoReads);
        fake = new Fake();
        // Far longer than the readings kept: the snapshot must outlive them.
        RecoveryTimeline.run(files, 102, 7, 250 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertTrue(fake.jatoReads > 5);
        String text = file(102, 7);
        assertTrue(text, text.split("\n")[1].startsWith("J "));
        assertEquals(text, 1, text.split("jato JatoXL", -1).length - 1);
        assertFalse(text, text.contains("jato_changed"));
        String section = RecoveryTimeline.section(files, 102, 7, fake.wall);
        assertTrue(section, section.contains("\njato_latest: t-"));
        assertTrue(section, section.contains(" jato JatoXL.isInited=true\n"));
    }

    @Test public void aChangeInTiktoksTuningIsMarkedWhereItHappened() throws Exception {
        Fake fake = new Fake() {
            @Override public String jato() { jatoReads++; return jatoReads == 1 ? "jato a=1" : "jato a=2"; }
        };
        RecoveryTimeline.run(files, 101, 7, (RecoveryTimeline.JATO_FIRST + RecoveryTimeline.JATO_EVERY + 1)
                * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        String section = RecoveryTimeline.section(files, 101, 7, fake.wall);
        assertTrue(section, section.contains(" jato_changed\n"));
        assertTrue(section, section.contains(" jato a=2\n"));
        assertFalse(section, section.contains("a=1"));
    }

    @Test public void aSectionDatesEachReadingFromTheExitAndSaysWhenTheyStopped() throws Exception {
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 6 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        // Each reading is dated when it starts, so the sixth is one period before the clock's end.
        long lastReading = fake.wall - RecoveryTimeline.PERIOD_MS;
        String section = RecoveryTimeline.section(files, 101, 7, lastReading + 42_000);
        assertTrue(section, section.startsWith(RecoveryTimeline.HEADING
                + "samples=6 period_s=5 last_saved_reading_before_exit_s=42\n"
                + "jato_latest: t-47s jato JatoXL.isInited=true\n"));
        assertTrue(section, section.contains("t-67s heap=1/512M\n"));
        assertTrue(section, section.endsWith("t-42s heap=6/512M\n"));
    }

    @Test public void anotherSessionsReadingsAreNeverLent() throws Exception {
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 2 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertEquals(RecoveryTimeline.HEADING + "not_recorded\n", RecoveryTimeline.section(files, 101, 8, fake.wall));
        assertEquals(RecoveryTimeline.HEADING + "not_recorded\n", RecoveryTimeline.section(files, 555, 7, fake.wall));
    }

    @Test public void aReusedPidCannotOverwriteTheFailedSessionsReadings() throws Exception {
        Fake failed = new Fake();
        RecoveryTimeline.run(files, 101, 7, 3 * RecoveryTimeline.PERIOD_MS, failed, failed, false);
        Fake current = new Fake();
        RecoveryTimeline.run(files, 101, 9, 5 * RecoveryTimeline.PERIOD_MS, current, current, false);
        assertTrue(RecoveryTimeline.section(files, 101, 7, failed.wall).contains("samples=3 "));
        assertTrue(RecoveryTimeline.section(files, 101, 9, current.wall).contains("samples=5 "));
    }

    @Test public void aLongTimelineKeepsItsNewestReadingsWithinItsShare() throws Exception {
        for (int width : new int[] {60, 81, 150}) {
            File dir = RecoveryTimeline.dir(files); assertTrue(dir.isDirectory() || dir.mkdirs());
            StringBuilder text = new StringBuilder("pid=101 session_at=7\n");
            text.append("J 5000 jato ").append(String.join("", Collections.nCopies(1500, "j"))).append('\n');
            for (int i = 0; i < 76; i++) text.append(1000L * i).append(' ')
                    .append(String.join("", Collections.nCopies(width, "x"))).append(i).append('\n');
            Files.write(RecoveryTimeline.file(files, 101, 7).toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            String section = RecoveryTimeline.section(files, 101, 7, 80_000);
            assertTrue(width + ": " + section.length(), section.length() <= RecoveryTimeline.MAX_SECTION);
            assertTrue(section, section.contains("x75\n"));
            assertTrue(section, section.contains("jato_latest: t-75s jato jjj"));
            if (section.contains("x0\n")) assertFalse(section, section.contains("older readings omitted"));
            else assertTrue(section, section.contains("older readings omitted]"));
        }
    }

    @Test public void reporterSizedReadingsKeepTheirHistoryWithTrialStateSeparate() throws Exception {
        Fake fake = new Fake() {
            @Override public String sample() {
                super.sample();
                // A rendered line is 108 characters including its t-... prefix and newline.
                return "heap=64/512M " + String.join("", Collections.nCopies(87, "x"));
            }
            @Override public String softReference() {
                return "softref_selected=true softref_v1_bypassed=0 softref_v2_bypassed=1";
            }
        };
        fake.jato = String.join("", Collections.nCopies(646, "j"));
        RecoveryTimeline.run(files, 101, 7, 72 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        long exit = fake.wall - RecoveryTimeline.PERIOD_MS + 135_000;
        String section = RecoveryTimeline.section(files, 101, 7, exit);
        int readings = 0;
        for (String line : section.split("\n")) if (line.startsWith("t-") && line.contains(" heap=")) {
            readings++; assertEquals(line, 108, line.length() + 1);
            assertFalse(line, line.contains("softref_"));
        }
        assertTrue("retained " + readings + " readings", readings >= 49);
        assertTrue(section, section.contains("last_saved_reading_before_exit_s=135\n"));
        assertEquals(1, section.split("softref_selected=true", -1).length - 1);
        assertTrue(section.length() <= RecoveryTimeline.MAX_SECTION + RecoveryTimeline.MAX_SOFT_REFERENCE);
        assertTrue(Files.size(RecoveryTimeline.file(files, 101, 7).toPath()) <= RecoveryTimeline.MAX_FILE);
    }

    @Test public void ordinaryRecoveryAddsNoSoftReferenceRecord() throws Exception {
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 10 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertFalse(file(101, 7).contains("\nS "));
        assertFalse(RecoveryTimeline.section(files, 101, 7, fake.wall).contains("softref_"));
    }
    @Test public void controlMetadataKeepsReporterHistoryAndDoesNotBecomeAReading() throws Exception {
        Fake fake = new Fake() {
            @Override public String sample() {
                super.sample(); return "heap=64/512M " + String.join("", Collections.nCopies(87, "x"));
            }
            @Override public String control() { return String.join("", Collections.nCopies(RecoveryTimeline.MAX_CONTROL, "c")); }
            @Override public String policy() { return String.join("", Collections.nCopies(RecoveryTimeline.MAX_POLICY, "p")); }
        };
        fake.jato = String.join("", Collections.nCopies(646, "j"));
        RecoveryTimeline.run(files, 101, 7, 72 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        String section = RecoveryTimeline.section(files, 101, 7, fake.wall - RecoveryTimeline.PERIOD_MS + 135_000);
        int count = 0;
        for (String line : section.split("\n")) if (line.startsWith("t-") && line.contains(" heap=")) count++;
        assertTrue("retained " + count, count >= 49);
        assertEquals(1, section.split("control_latest:", -1).length - 1);
        assertTrue(section.contains("last_saved_reading_before_exit_s=135\n"));
        assertTrue(section.length() <= RecoveryTimeline.MAX_SECTION + RecoveryTimeline.MAX_CONTROL + RecoveryTimeline.MAX_POLICY + 96);
        assertEquals(1, section.split("policy_latest:", -1).length - 1);
        assertFalse(RecoveryTimeline.section(files, 101, 7, fake.wall, fake.wall).contains("policy_latest:"));
        assertFalse(RecoveryTimeline.section(files, 101, 7, fake.wall, fake.wall).contains("control_latest:"));
    }

    @Test public void wideReadingsKeepUpdatingRatherThanSilentlyStoppingAtTheFileLimit() throws Exception {
        Fake fake = new Fake() {
            @Override public String sample() {
                return super.sample() + " " + String.join("", Collections.nCopies(300, "x"));
            }
            @Override public String softReference() { return "softref_selected=true softref_v2_bypassed=1"; }
        };
        fake.jato = String.join("", Collections.nCopies(RecoveryTimeline.MAX_JATO, "j"));
        RecoveryTimeline.run(files, 101, 7, 200 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertTrue(file(101, 7).contains(" heap=200/512M "));
        assertTrue(Files.size(RecoveryTimeline.file(files, 101, 7).toPath()) <= RecoveryTimeline.MAX_FILE);
    }

    @Test public void clearDropsSavedObservationsButNewReadingsRetainProcessBypassFacts() throws Exception {
        RecoveryCapture.softReferenceV2Bypassed();
        Fake fake = new Fake() {
            @Override public String sample() {
                String value = super.sample();
                if (samples == 10) RecoveryTimeline.clear(files);
                return value;
            }
            @Override public String softReference() { return RecoveryCapture.softReferenceState(); }
        };
        RecoveryTimeline.run(files, 101, 7, 20 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        String section = RecoveryTimeline.section(files, 101, 7, fake.wall);
        assertFalse(section, section.contains("heap=9/") || section.contains("heap=10/"));
        assertTrue(section, section.contains("softref_v2_bypassed=1"));
        assertEquals(1, section.split("softref_v2_bypassed=1", -1).length - 1);
        String filtered = RecoveryTimeline.section(files, 101, 7, fake.wall, fake.wall);
        assertFalse(filtered, filtered.contains("softref_"));
    }

    @Test public void oldFilesArePrunedButThisSessionKeepsItsOwn() throws Exception {
        File dir = RecoveryTimeline.dir(files); assertTrue(dir.mkdirs());
        for (int pid = 1; pid <= 9; pid++) {
            File f = new File(dir, pid + "-7.txt"); Files.write(f.toPath(), new byte[] {1});
            assertTrue(f.setLastModified(1_000_000L * pid));
        }
        RecoveryTimeline.prune(files, 1, 7);
        List<String> left = new ArrayList<>(Arrays.asList(dir.list())); Collections.sort(left);
        assertEquals(Arrays.asList("1-7.txt", "6-7.txt", "7-7.txt", "8-7.txt", "9-7.txt"), left);
    }

    @Test public void clearingDuringSamplingDropsEverythingObservedBefore() throws Exception {
        Fake fake = new Fake() {
            @Override public String sample() {
                String reading = super.sample();
                if (samples == 10) RecoveryTimeline.clear(files);
                return reading;
            }
        };
        RecoveryTimeline.run(files, 101, 7, 20 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        String text = file(101, 7);
        assertFalse(text, text.contains("heap=9/"));
        assertFalse("the reading taken as the clear happened goes too", text.contains("heap=10/"));
        assertTrue(text, text.contains("heap=11/") && text.contains("heap=20/"));
        assertFalse("the tuning snapshot read before the clear goes too", text.contains("\nJ "));
    }

    @Test public void aReportLeavesOutReadingsFromBeforeTheLastClear() throws Exception {
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 10 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        // Readings start at 100000 and come every 5 s; the snapshot was read at 120000.
        String section = RecoveryTimeline.section(files, 101, 7, fake.wall, 120_000);
        assertTrue(section, section.contains("samples=5 "));
        assertTrue(section, section.contains("jato_latest: not_read\n"));
        assertFalse(section, section.contains("heap=5/"));
        assertTrue(section, section.contains("heap=6/"));
    }

    @Test public void clearingDiagnosticsRemovesUnreportedReadings() throws Exception {
        Context context = context(files);
        Fake fake = new Fake();
        RecoveryTimeline.run(files, 101, 7, 2 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        assertTrue(RecoveryTimeline.file(files, 101, 7).isFile());
        RecoveryCapture.clear(context);
        assertFalse(RecoveryTimeline.file(files, 101, 7).exists());
    }

    @Test public void aReadingHasEveryFieldTheDiagnosisNeeds() {
        String reading = RecoveryTimeline.reading(0);
        for (String field : new String[] {"heap=", "M gc=", "ms bgc=", "ms alloc=", "M freed=", " thr=", " rss=", " nat=", " main=ok"})
            assertTrue(reading, reading.contains(field));
        assertTrue(RecoveryTimeline.reading(12_000).endsWith(" main=late_12s"));
        assertFalse(reading, reading.contains("softref_"));
    }

    // Stand-ins for TikTok's tuning classes: static flags and numbers, and a config object.
    public static final class FakeJato {
        public static volatile boolean isInited = true;
        public static boolean sDisableGcBlocker = false;
        public static Object sConfig = new FakeConfig();
        public static Object sUnrelated = new Object();
    }
    public static final class FakeConfig {
        public boolean mIsEnabledArtGcBlocker = true; public int mGcHeapLimit = 384; public long mBlockInterval = 7;
        public Context mContext = null;
    }
    public static final class FakeBlocker { public static long LIZIZ = Long.MAX_VALUE; }

    @Test public void tiktoksTuningStateIsReadAsItIsAndAbsentClassesSayAbsent() {
        String prefix = RecoveryTimelineTest.class.getName() + "$";
        String state = RecoveryTimeline.jatoState(getClass().getClassLoader(),
                Arrays.asList(prefix + "FakeJato", prefix + "FakeBlocker", "com.bytedance.common.jato.Missing"));
        assertTrue(state, state.startsWith("jato "));
        assertTrue(state, state.contains("RecoveryTimelineTest_FakeJato.isInited=true"));
        assertTrue(state, state.contains("RecoveryTimelineTest_FakeJato.sDisableGcBlocker=false"));
        assertTrue(state, state.contains("config.mIsEnabledArtGcBlocker=true"));
        assertTrue(state, state.contains("config.mGcHeapLimit=384"));
        assertTrue(state, state.contains("config.mBlockInterval=7"));
        assertTrue(state, state.contains("RecoveryTimelineTest_FakeBlocker.LIZIZ=long_max"));
        // Seen on a device: the redactor took Long.MAX_VALUE for an ID. The state must survive it.
        assertEquals(state, DiagnosticRedactor.redact(state));
        assertTrue(state, state.contains("Missing=absent"));
        assertFalse(state, state.contains("mContext"));
        assertFalse(state, state.contains("sUnrelated"));
    }

    @Test public void longTuningNumbersStayReadableThroughRedaction() {
        assertEquals("long_max", RecoveryTimeline.value(Long.MAX_VALUE));
        assertEquals("int_max", RecoveryTimeline.value(Integer.MAX_VALUE));
        assertEquals("3000", RecoveryTimeline.value(3000));
        assertEquals("true", RecoveryTimeline.value(true));
        String time = "jato GcBlocker.LIZIZ=" + RecoveryTimeline.value(1794513836206L);
        assertEquals("jato GcBlocker.LIZIZ=1.795e12", time);
        assertEquals(time, DiagnosticRedactor.redact(time));
    }

    @Test public void theRealClassNamesAreAbsentOutsideTikTok() {
        assertEquals("jato JatoXL=absent GcBlocker=absent RemoveWaitingForGCOpt=absent",
                RecoveryTimeline.jatoState(getClass().getClassLoader()));
    }

    @Test public void onlyRuntimeThreadNamesAreShownAndCollectorNamesOnlyAsAKind() {
        for (String name : new String[] {"HeapTaskDaemon", "ReferenceQueueD", "ReferenceQueueDaemon", "Signal Catcher",
                "binder:4861_2", "Jit thread pool", "RenderThread", "hwuiTask1", "FinalizerWatchd"})
            assertEquals(name, " name=\"" + name + "\"", RecoveryTrace.nameLabel(name));
        for (String name : new String[] {"jato-gc", "gc-alice", "jato-user123", "MyGcWorker"})
            assertEquals(name, " name_kind=gc_or_jato", RecoveryTrace.nameLabel(name));
        for (String name : new String[] {"secret-worker", "tp-io-4 alice@example.com", "zhiliaoapp.musi", "main",
                "OkHttp https://example.com/x", "", null})
            assertEquals(String.valueOf(name), "", RecoveryTrace.nameLabel(name));
    }

    @Test public void aNativeDumpNamesTheCollectorThreadsButNotTheAppsOwn() throws Exception {
        RecoveryTrace.Result r = RecoveryTrace.read(new java.io.ByteArrayInputStream(
                RecoveryCaptureTest.NATIVE_TRACE.getBytes(StandardCharsets.UTF_8)), 101);
        assertTrue(r.text, r.text.contains("thread tid=110 role=gc_daemon state=native_dump name=\"HeapTaskDaemon\"\n"));
        assertTrue(r.text, r.text.contains("  tid=130\n"));
        assertFalse(r.text, r.text.contains("alice"));
    }

    private Context context(File dir) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return dir; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    /** The whole path: a tracked session takes readings and its report carries them with Android's own account. */
    @Test public void aRecoveredIncidentCarriesItsTimelineExitDetailsAndOneBuild() throws Exception {
        Context context = context(files);
        RecoveryStore data = new RecoveryStore(context);
        final List<long[]> started = new ArrayList<>();
        final long[] clock = {1000};
        final List<RecoveryCapture.Exit> exits = new ArrayList<>();
        final int[] pid = {101};
        RecoveryCapture.Source source = new RecoveryCapture.Source() {
            public long now() { return clock[0]; }
            public int pid() { return pid[0]; }
            public boolean main(Context c) { return true; }
            public List<RecoveryCapture.Exit> history(Context c) { return exits; }
            public String build(Context c) { return "source_commit: same\n"; }
            public void timeline(Context c, int p, long at, long until) { started.add(new long[] {p, at, until}); }
        };
        RecoveryCapture.recover(context, "unit-v1", source, data);
        assertEquals(1, started.size());
        assertEquals(101, started.get(0)[0]); assertEquals(1000, started.get(0)[1]);
        assertEquals(RecoveryCapture.LEASE_MS + 1000, started.get(0)[2]);

        Fake fake = new Fake(); fake.wall = 1100;
        RecoveryTimeline.run(files, 101, 1000, 3 * RecoveryTimeline.PERIOD_MS, fake, fake, false);
        exits.add(new RecoveryCapture.Exit(context.getPackageName(), 101, 6, 30_000,
                () -> new java.io.ByteArrayInputStream(RecoveryCaptureTest.NATIVE_TRACE.getBytes(StandardCharsets.UTF_8)),
                "user request after error: Input dispatching timed out\nsecond line", 812_000, 901_000, 100, 0));
        clock[0] = 31_000; pid[0] = 101;
        // The new launch has the failed one's pid: its readings must not cost the failed session its own.
        RecoveryCapture.recover(context, "unit-v1", source, data);
        String report = RecoveryCapture.cachedReport();
        assertTrue(report, report.contains("exit_reason: 6\nexit_description: user request after error: Input dispatching timed out second line\n"
                + "exit_pss_kb: 812000\nexit_rss_kb: 901000\nexit_importance: 100\nexit_status: 0\n"));
        assertTrue(report, report.contains("[COLLECTOR BUILD]\nsame_as_failed_session\n"));
        assertTrue(report, report.contains(RecoveryTimeline.HEADING + "samples=3 period_s=5 last_saved_reading_before_exit_s=18\n"));
        assertTrue(report, report.indexOf(RecoveryTimeline.HEADING) < report.indexOf("[RECOVERED THREADS]"));
        assertEquals("the second launch is tracked too", 2, started.size());
        assertEquals(31_000, started.get(1)[1]);
    }

    @Test public void anUntrackedLaunchTakesNoReadings() throws Exception {
        Context context = context(files);
        RecoveryStore data = new RecoveryStore(context);
        final int[] started = {0}; final long[] clock = {1000};
        RecoveryCapture.Source source = new RecoveryCapture.Source() {
            public long now() { return clock[0]; }
            public int pid() { return (int) clock[0]; }
            public boolean main(Context c) { return true; }
            public List<RecoveryCapture.Exit> history(Context c) { return Collections.emptyList(); }
            public String build(Context c) { return "source_commit: same\n"; }
            public void timeline(Context c, int p, long at, long until) { started[0]++; }
        };
        for (int launch = 0; launch < RecoveryCapture.MAX_CHECKS + 1; launch++) {
            RecoveryCapture.recover(context, "unit-v1", source, data); clock[0] += 1000;
        }
        assertEquals(RecoveryCapture.MAX_STARTS, started[0]);
    }
}
