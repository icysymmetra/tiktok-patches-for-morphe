package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=30)
public class CrashEvidenceTest {
    @Rule public final TemporaryFolder temp=new TemporaryFolder();
    private Context context;
    private static final long NOW=1_800_000_000_000L,ELAPSED=10_000,BOOT=NOW-ELAPSED;
    @Before public void before()throws Exception{
        RecoveryCapture.resetForTests();File dir=temp.newFolder();
        context=new ContextWrapper(RuntimeEnvironment.getApplication()){@Override public File getFilesDir(){return dir;}};
    }
    @After public void after(){RecoveryCapture.resetForTests();}
    private JSONObject state()throws Exception{return new JSONObject().put("trial","unit").put("started",NOW-2000)
        .put("boot_epoch",BOOT).put("sessions",new JSONArray().put(new JSONObject().put("pid",101).put("at",NOW-2000)
            .put("process_started_elapsed_ms",1000).put("capture_preference_at_start","off")));}
    private String record(boolean npth){return "schema: 1\ncomplete: true\ntimestamp_utc: "+DiagnosticEvent.utc(NOW-1000)
        +"\npackage: "+context.getPackageName()+"\nprocess: "+context.getPackageName()+"\npid: 101\nrecovery_trial_id: unit"
        +"\nboot_epoch_ms: "+BOOT+"\ncaptured_elapsed_ms: 9000\nprocess_started_elapsed_ms: 1000"
        +(npth?"\nsource: tiktok_npth_callback\ncrash_type: anr\n\n[NPTH SUMMARY]\n":"\nexception: java.lang.IllegalStateException\nmessage: initiating-canary\n\n[STACK TRACE]\n")
        +"java.lang.IllegalStateException: initiating-canary\nCaused by: java.io.IOException: causal-canary\n  at app.example.Player.start(Player.java:42)\n";}
    private String render(String java,String npth,JSONObject state){return CrashEvidence.render(state,context.getPackageName(),"off",
        new LogBufferManager.CrashReportSnapshot(new LogBufferManager.PersistedCrashReport(java==null?"absent":"present",java==null?"":java),
            new LogBufferManager.PersistedCrashReport(npth==null?"absent":"present",npth==null?"":npth)),NOW,ELAPSED);}
    @Test public void bothChannelsPreserveCauseChainWithoutClaimingAnEarlierErrorWasAbsent()throws Exception{
        String result=render(record(false),record(true),state());
        assertEquals(2,result.split("association=matched_process_session",-1).length-1);
        assertTrue(result.contains("Caused by: java.io.IOException: causal-canary"));
        assertTrue(result.contains("capture_preference_at_start=off"));assertTrue(result.contains("not exhaustive error history"));
        assertTrue(result.contains("not necessarily the initiating exception time"));
    }
    @Test public void absentFilesStillCarryTheTrackedCapturePreferenceWithoutClaimingInstallation()throws Exception{
        String result=render(null,null,state());
        assertTrue(result.contains("tracked_session pid=101"));assertTrue(result.contains("capture_preference_at_start=off handler_installation=not_observed"));
        assertTrue(result.contains("channel=java read_status=absent"));assertTrue(result.contains("channel=npth read_status=absent"));
    }
    @Test public void anEarlyErrorCanMatchTheActualProcessBeforeSamplerRegistration()throws Exception{
        JSONObject state=state();state.getJSONArray("sessions").getJSONObject(0).put("at",NOW-500);
        String result=render(record(false),null,state);
        assertTrue(result,result.contains("association=matched_process_session"));assertTrue(result.contains("initiating-canary"));
    }
    @Test public void reusedPidDoesNotBecomeAMatchedSession()throws Exception{
        String result=render(record(false).replace("process_started_elapsed_ms: 1000","process_started_elapsed_ms: 2000"),null,state());
        assertFalse(result,result.contains("association=matched_process_session"));
        assertTrue(result.contains("same_trial_untracked_process"));assertTrue(result.contains("do not attribute this record to a recovered ANR"));
    }
    @Test public void secondaryProcessEvidenceIsKeptSeparatelyFromTheMainProcess()throws Exception{
        String raw=record(true).replace("process: "+context.getPackageName(),"process: "+context.getPackageName()+":worker");
        String result=render(null,raw,state());assertTrue(result.contains("association=same_trial_secondary_process"));
        assertTrue(result.contains("causal-canary"));assertFalse(result.contains("association=matched_process_session"));
    }
    @Test public void legacyWallTimeMatchIsExplicitlyWeaker()throws Exception{
        String raw=record(false).replaceAll("(?m)^(recovery_trial_id|boot_epoch_ms|captured_elapsed_ms|process_started_elapsed_ms):.*\\n","");
        String result=render(raw,null,state());assertTrue(result.contains("association=pid_and_wall_time_only"));
        assertFalse(result.contains("association=matched_process_session"));
        assertFalse(render(raw.replace("pid: 101","pid: 999"),null,state()).contains("initiating-canary"));
    }
    @Test public void anotherTrialPackageBootOrFutureTimestampCannotSupplyAnInitiatingError()throws Exception{
        String raw=record(false);
        String[] bad={raw.replace("recovery_trial_id: unit","recovery_trial_id: other"),raw.replace("package: "+context.getPackageName(),"package: other.app"),
            raw.replace("boot_epoch_ms: "+BOOT,"boot_epoch_ms: "+(BOOT-60_000)),raw.replace(DiagnosticEvent.utc(NOW-1000),DiagnosticEvent.utc(NOW+1000)),
            raw.replace("captured_elapsed_ms: 9000","captured_elapsed_ms: 11000"),raw.replace("process_started_elapsed_ms: 1000","process_started_elapsed_ms: 9500")};
        for(String value:bad)assertFalse(render(value,null,state()),render(value,null,state()).contains("initiating-canary"));
    }
    @Test public void clockChangeAndClearExcludeOldBodiesAndUndoCanRestoreThem()throws Exception{
        JSONObject state=state();state.put("boot_epoch",BOOT+60_000);
        assertTrue(render(record(false),null,state).contains("association=boot_or_clock_unverified"));
        state.put("boot_epoch",BOOT).put("cleared_through",NOW-500);
        String cleared=render(record(false),null,state);assertTrue(cleared.contains("association=cleared_record"));assertFalse(cleared.contains("initiating-canary"));
        state.remove("cleared_through");assertTrue(render(record(false),null,state).contains("initiating-canary"));
    }
    @Test public void malformedOrDuplicateHeadersCannotCreateAFalseIdentity()throws Exception{
        for(String raw:new String[]{record(false).replace("schema: 1","schema: 1\npid: 202"),record(false).replace("timestamp_utc:","missing_date:"),"schema: 1\nnot a report"})
            assertFalse(render(raw,null,state()).contains("initiating-canary"));
    }
    @Test public void bodyLimitsAreUtf8SafeRedactedAndDoNotKeepACompleteClaimAfterCutting()throws Exception{
        String raw=record(false)+"https://secret.example/path?sessionid=PRIVATE_CANARY\n"+"\u4e2d".repeat(50_000);
        String result=render(raw,record(true)+"\u4e2d".repeat(50_000),state());
        assertTrue(result.getBytes(StandardCharsets.UTF_8).length<=CrashEvidence.MAX_SECTION_BYTES);
        assertTrue(result.contains("export_body_truncated=true"));assertTrue(result.contains("complete: false"));
        assertFalse(result.contains("complete: true"));assertFalse(result.contains("\ufffd"));assertFalse(result.contains("PRIVATE_CANARY"));
    }
    @Test public void readStatusDistinguishesMissingEmptyCorruptOversizedAndUnreadable()throws Exception{
        File file=new File(context.getFilesDir(),"morphe_java_crash_report_v1.txt");
        assertEquals("absent",LogBufferManager.readCrashReportsForRecovery(context).java.status);
        Files.write(file.toPath(),new byte[0]);assertEquals("empty",LogBufferManager.readCrashReportsForRecovery(context).java.status);
        Files.write(file.toPath(),new byte[]{(byte)0xc3});assertEquals("malformed_utf8",LogBufferManager.readCrashReportsForRecovery(context).java.status);
        Files.write(file.toPath(),new byte[64_001]);assertEquals("oversized",LogBufferManager.readCrashReportsForRecovery(context).java.status);
        File parent=temp.newFolder();Context broken=new ContextWrapper(context){@Override public File getFilesDir(){return parent;}};
        assertTrue(new File(parent,"morphe_java_crash_report_v1.txt").mkdir());assertEquals("read_error",LogBufferManager.readCrashReportsForRecovery(broken).java.status);
    }
    @Test public void existingAtomicBackupAndPersistedMetadataAreActuallyRead()throws Exception{
        LogBufferManager.persistCrashReport(context,record(false));
        File file=new File(context.getFilesDir(),"morphe_java_crash_report_v1.txt"),backup=new File(file.getPath()+".bak");
        assertTrue(file.renameTo(backup));Files.write(file.toPath(),"half".getBytes(StandardCharsets.UTF_8));
        LogBufferManager.CrashReportSnapshot snapshot=LogBufferManager.readCrashReportsForRecovery(context);
        assertEquals("present",snapshot.java.status);
        assertTrue("reading must not consume a legacy backup",backup.exists());
        assertEquals("half",new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        String result=CrashEvidence.render(state(),context.getPackageName(),"off",snapshot,NOW,ELAPSED);
        assertTrue(result,result.contains("association=matched_process_session"));assertTrue(result.contains("causal-canary"));
    }
    @Test public void readingCommittedDataDoesNotDeleteAnotherWritersStagingFile()throws Exception{
        File file=new File(context.getFilesDir(),"morphe_java_crash_report_v1.txt"),staging=new File(file.getPath()+".new");
        Files.write(file.toPath(),record(false).getBytes(StandardCharsets.UTF_8));Files.write(staging.toPath(),"replacement-in-progress".getBytes(StandardCharsets.UTF_8));
        assertEquals("present",LogBufferManager.readCrashReportsForRecovery(context).java.status);
        assertEquals("replacement-in-progress",new String(Files.readAllBytes(staging.toPath()),StandardCharsets.UTF_8));
        assertEquals(record(false),LogBufferManager.readCrashReport(context));
        assertTrue(staging.exists());
    }
    @Test public void aFirstWriteStillInStagingIsNotReadOrDeleted()throws Exception{
        File staging=new File(context.getFilesDir(),"morphe_java_crash_report_v1.txt.new");
        Files.write(staging.toPath(),record(false).getBytes(StandardCharsets.UTF_8));
        var result=LogBufferManager.readCrashReportsForRecovery(context).java;
        assertEquals("staging_only",result.status);assertEquals("",result.text);assertTrue(staging.exists());
    }
    @Test public void anErrorAfterCollectionExpiresIsRetainedButExplicitlyLabelled()throws Exception{
        JSONObject state=state().put("started",NOW-RecoveryCapture.LEASE_MS-2000);
        assertTrue(render(record(false),null,state).contains("association=matched_process_after_collection_window"));
    }
}
