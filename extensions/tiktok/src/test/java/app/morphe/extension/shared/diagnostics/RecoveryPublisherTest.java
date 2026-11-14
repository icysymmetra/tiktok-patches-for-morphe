package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.content.ContentResolver;
import android.provider.MediaStore;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
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
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class RecoveryPublisherTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private Context context; private RecoveryStore data; private Provider media;
    private static final String REPORT = "MORPHE DIAGNOSTIC REPORT\nsource: recovery_trial\nmain: waiting\n";

    @Before public void setup() throws Exception {
        RecoveryCapture.resetForTests(); File directory = temp.newFolder();
        context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
        data = new RecoveryStore(context);
        media = new Provider(temp.newFolder(), context.getPackageName());
        ProviderInfo info = new ProviderInfo(); info.authority = MediaStore.AUTHORITY;
        media.attachInfo(context, info); ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, media);
        data.update(s -> s.put("trial", "unit-v1").put("started", 1000).put("incidents", new JSONArray().put(
                new JSONObject().put("id", "incident-1").put("at", 1500).put("report", REPORT).put("hash", RecoveryPublisher.sha(REPORT)))));
    }
    @After public void reset() { media.release.countDown(); RecoveryCapture.resetForTests(); }

    @Test public void savedMeansPublishedReadableAndMatchingWithOneFileForAllIncidents() throws Exception {
        data.update(s -> s.getJSONArray("incidents").put(new JSONObject().put("id", "incident-2").put("at", 1700)
                .put("report", REPORT + "second\n").put("hash", RecoveryPublisher.sha(REPORT + "second\n"))));
        RecoveryPublisher.publish(context, data);
        assertEquals(1, media.inserts);
        JSONObject receipt = data.read().getJSONObject("export");
        assertEquals("verified", receipt.getString("status"));
        String text = new String(Files.readAllBytes(media.rows.values().iterator().next().file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("second")); assertEquals(RecoveryPublisher.sha(text), receipt.getString("verified_hash"));
        assertEquals(0, media.rows.values().iterator().next().values.getAsInteger(MediaStore.MediaColumns.IS_PENDING).intValue());
    }
    @Test public void restartAfterPublicationBeforeReceiptReusesTheActualFile() throws Exception {
        RecoveryPublisher.publish(context, data);
        data.update(s -> { s.getJSONObject("export").remove("verified_hash"); s.getJSONObject("export").put("status", "pending"); });
        RecoveryPublisher.publish(context, new RecoveryStore(context));
        assertEquals(1, media.inserts); assertEquals("verified", data.read().getJSONObject("export").getString("status"));
    }
    @Test public void providerAssignedNameSurvivesAReceiptRetry() throws Exception {
        media.rename = true; RecoveryPublisher.publish(context, data);
        assertTrue(data.read().getJSONObject("export").getString("name").contains(" (1)"));
        data.update(s -> s.getJSONObject("export").remove("verified_hash"));
        RecoveryPublisher.publish(context, data); assertEquals(1, media.inserts);
    }
    @Test public void failuresAtEachProviderStageKeepEvidenceAndCanResume() throws Exception {
        for (String fault : new String[]{"insert", "open", "publish", "read", "name"}) {
            media.rows.clear(); media.fault = fault; media.inserts = 0;
            data.update(s -> s.remove("export"));
            RecoveryPublisher.publish(context, data);
            JSONObject failed = data.read().getJSONObject("export");
            assertEquals(fault, "failed_retry_on_relaunch", failed.getString("status"));
            assertFalse(fault, failed.has("verified_hash"));
            assertEquals(REPORT, data.read().getJSONArray("incidents").getJSONObject(0).getString("report"));
            RecoveryPublisher.publish(context, new RecoveryStore(context));
            assertEquals(fault, "verified", data.read().getJSONObject("export").getString("status"));
            assertEquals(fault, 1, media.rows.size());
        }
    }
    @Test public void retryRecoversTheInsertReceiptGapWithoutCreatingAnotherRow() throws Exception {
        media.fault = "open"; RecoveryPublisher.publish(context, data);
        data.update(s -> s.getJSONObject("export").remove("uri"));
        RecoveryPublisher.publish(context, data); assertEquals(1, media.inserts);
        assertEquals("verified", data.read().getJSONObject("export").getString("status"));
    }
    @Test public void aStalledProviderDoesNotHoldTheEvidenceStoreOrNormalLoggerLock() throws Exception {
        media.hold = true; Thread writer = new Thread(() -> {
            try { RecoveryPublisher.publish(context, data); } catch (Exception e) { throw new AssertionError(e); }
        }); writer.start();
        try {
            assertTrue(media.entered.await(5, TimeUnit.SECONDS));
            data.update(s -> s.put("capture_progress", "still running"));
            assertEquals("still running", data.read().getString("capture_progress"));
            java.lang.reflect.Field f = LogBufferManager.class.getDeclaredField("CLEAR_UNDO_LOCK"); f.setAccessible(true);
            synchronized (f.get(null)) { media.release.countDown(); writer.join(5000); assertFalse(writer.isAlive()); }
        } finally { media.release.countDown(); writer.join(5000); }
        assertEquals("verified", data.read().getJSONObject("export").getString("status"));
    }
    @Test public void clearedEvidenceIsNotPublishedWhenAProviderFinallyReturns() throws Exception {
        LogBufferManager.persistCrashReport(context,matchingCrash());
        media.hold = true; Thread writer = new Thread(() -> {
            try { RecoveryPublisher.publish(context, data); } catch (Exception e) { throw new AssertionError(e); }
        }); writer.start();
        assertTrue(media.entered.await(5, TimeUnit.SECONDS)); data.clear(); media.release.countDown(); writer.join(5000);
        assertFalse(writer.isAlive()); assertEquals(0, media.rows.size());
    }
    @Test public void badReadbackNeverGetsASuccessReceiptAndRetriesAreBounded() throws Exception {
        media.alwaysBadRead = true;
        for (int i = 0; i < 8; i++) RecoveryPublisher.publish(context, data);
        JSONObject receipt = data.read().getJSONObject("export"); assertEquals(3, receipt.getInt("attempts"));
        assertFalse(receipt.has("verified_hash")); assertEquals(1, media.inserts);
    }
    @Test public void anUpdateCountAloneDoesNotProvePublication() throws Exception {
        media.pretendPublished = true;
        RecoveryPublisher.publish(context, data);
        assertFalse(data.read().getJSONObject("export").has("verified_hash"));
        assertEquals("failed_retry_on_relaunch", data.read().getJSONObject("export").getString("status"));
    }
    @Test public void aPendingOrphanNeedsAnExplicitIncludePendingQuery() throws Exception {
        media.fault = "open"; RecoveryPublisher.publish(context, data);
        try (Cursor c = context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID}, null, null, null)) { assertEquals(0, c.getCount()); }
        data.update(s -> s.getJSONObject("export").remove("uri"));
        RecoveryPublisher.publish(context, data);
        assertEquals(1, media.inserts); assertEquals("verified", data.read().getJSONObject("export").getString("status"));
    }
    @Test public void aTrashedReceiptIsNotCalledPublishedOrUntrashed() throws Exception {
        RecoveryPublisher.publish(context, data);
        Provider.Row original = media.rows.values().iterator().next();
        original.values.put(MediaStore.MediaColumns.IS_TRASHED, 1);
        data.update(s -> s.getJSONObject("export").remove("verified_hash"));
        RecoveryPublisher.publish(context, data);
        assertEquals(2, media.inserts); assertEquals(1, original.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED).intValue());
        assertEquals("verified", data.read().getJSONObject("export").getString("status"));
    }
    @Test public void manualFullReportIsNotRestrictedToTheTrialSizeCap() throws Exception {
        String large = String.join("", java.util.Collections.nCopies(400_000, "\u4e2d"));
        String path = LogBufferManager.writeVerifiedReport(context, "manual-large.txt", large, null);
        assertEquals("Download/Morphe/manual-large.txt", path);
        assertEquals(large, new String(Files.readAllBytes(media.rows.values().iterator().next().file.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void corruptRecoveryDataDoesNotBlockOrdinaryClearAndUndo() throws Exception {
        Utils.setContext(context); LogBufferManager.clearLogBuffer();
        LogBufferManager.appendToLogBuffer("ordinary-clear-canary");
        Files.write(new File(context.getFilesDir(), "metra_recovery_v1.json").toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        assertTrue(LogBufferManager.buildExportText().contains("ordinary-clear-canary"));
        LogBufferManager.clearLogBuffer();
        assertFalse(LogBufferManager.buildExportText().contains("ordinary-clear-canary"));
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        assertTrue(LogBufferManager.buildExportText().contains("ordinary-clear-canary"));
    }
    @Test public void anIncidentAloneMakesAnExportEvenWithOrdinaryLoggingOff() throws Exception {
        Utils.setContext(context); BaseSettings.DEBUG.save(false); LogBufferManager.clearLogBuffer();
        // clear deliberately removed the store; create a newly recovered incident afterwards.
        data.update(s -> s.put("incidents", new JSONArray().put(new JSONObject().put("id", "fresh")
                .put("report", REPORT).put("hash", RecoveryPublisher.sha(REPORT)))));
        RecoveryCapture.refresh(data);
        assertTrue(LogBufferManager.buildExportText().contains("[RECOVERED FAILURES]"));
    }

    private String matchingCrash() throws Exception {
        android.os.SystemClock.sleep(2000);
        long now=System.currentTimeMillis(), elapsed=android.os.SystemClock.elapsedRealtime();
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String crash="schema: 1\ncomplete: true\ntimestamp_utc: "+format.format(new java.util.Date(now-500))
                +"\npackage: "+context.getPackageName()+"\nprocess: "+context.getPackageName()+"\npid: 101"
                +"\nboot_epoch_ms: "+(now-elapsed)+"\nprocess_started_elapsed_ms: "+(elapsed-1500)
                +"\ncaptured_elapsed_ms: "+(elapsed-500)
                +"\nexception: java.lang.IllegalStateException\nmessage: opening-panel-canary"
                +"\n\n[STACK TRACE]\njava.lang.IllegalStateException: opening-panel-canary\n  at app.example.Panel.open(Panel.java:42)\n";
        data.update(s->s.put("started",now-1000).put("boot_epoch",now-elapsed).put("sessions",new JSONArray().put(
                new JSONObject().put("pid",101).put("at",now-1000).put("process_started_elapsed_ms",elapsed-1500))));
        return crash;
    }
    private String exported() throws Exception {
        JSONObject receipt=data.read().getJSONObject("export");
        long id=Long.parseLong(Uri.parse(receipt.getString("uri")).getLastPathSegment());
        String text=new String(Files.readAllBytes(media.rows.get(id).file.toPath()),StandardCharsets.UTF_8);
        assertEquals(RecoveryPublisher.sha(text),receipt.getString("verified_hash"));return text;
    }
    @Test public void automaticReportIncludesMatchingPersistedJavaEvidenceWithoutUsingIncidentBudget() throws Exception {
        LogBufferManager.persistCrashReport(context,matchingCrash());
        String incidents=data.read().getJSONArray("incidents").toString();
        RecoveryPublisher.publish(context,data);
        String exported=exported();
        assertTrue(exported,exported.contains("opening-panel-canary"));
        assertTrue(exported,exported.contains("association=matched_process_session"));
        assertEquals(incidents,data.read().getJSONArray("incidents").toString());
    }
    @Test public void aLatePersistedFileChangesTheExportEvenWhenIncidentsDidNotChange() throws Exception {
        String crash=matchingCrash();RecoveryPublisher.publish(context,data);
        String first=data.read().getJSONObject("export").getString("verified_hash");
        assertTrue(exported().contains("channel=java read_status=absent"));
        LogBufferManager.persistCrashReport(context,crash);RecoveryPublisher.publish(context,data);
        assertNotEquals(first,data.read().getJSONObject("export").getString("verified_hash"));
        assertTrue(exported().contains("opening-panel-canary"));
        assertEquals(1,data.read().getJSONArray("incidents").length());
    }
    @Test public void aCauseBeyondTheOldExcerptBudgetSurvivesTheActualAutomaticExport() throws Exception {
        String crash=matchingCrash()+"  at app.example.Deep.call(Deep.java:1)\n".repeat(800)
                +"Caused by: java.io.IOException: deep-root-cause-canary\n";
        LogBufferManager.persistCrashReport(context,crash);RecoveryPublisher.publish(context,data);
        assertTrue(exported(),exported().contains("deep-root-cause-canary"));
        assertEquals(REPORT,data.read().getJSONArray("incidents").getJSONObject(0).getString("report"));
    }
    @Test public void threeMaximumIncidentsAndBothFullCrashFilesFitTheAutomaticFileLimit() throws Exception {
        String crash=matchingCrash()+"\u4e2d".repeat(19_000);
        LogBufferManager.persistCrashReport(context,crash);
        LogBufferManager.persistNpthCrashReport(context,crash.replace("[STACK TRACE]","[NPTH SUMMARY]")
                .replace("exception: java.lang.IllegalStateException","source: tiktok_npth_callback\ncrash_type: anr"));
        String full=REPORT+"x".repeat(60*1024-REPORT.length());
        JSONArray incidents=new JSONArray();for(int i=0;i<3;i++)incidents.put(new JSONObject().put("id","full-"+i).put("report",full).put("hash",RecoveryPublisher.sha(full)));
        data.update(s->s.put("incidents",incidents));RecoveryPublisher.publish(context,data);
        assertTrue(exported().getBytes(StandardCharsets.UTF_8).length<1024*1024);
        assertEquals(3,data.read().getJSONArray("incidents").length());
        assertTrue(exported().contains("channel=npth read_status=present association=matched_process_session"));
    }
    @Test public void npthAndJavaEvidenceShareTheSameHashVerifiedAutomaticFile() throws Exception {
        String crash=matchingCrash();LogBufferManager.persistCrashReport(context,crash);
        LogBufferManager.persistNpthCrashReport(context,crash.replace("[STACK TRACE]","[NPTH SUMMARY]")
                .replace("exception: java.lang.IllegalStateException","source: tiktok_npth_callback\ncrash_type: anr"));
        RecoveryPublisher.publish(context,data);String text=exported();
        assertTrue(text.contains("channel=java read_status=present association=matched_process_session"));
        assertTrue(text.contains("channel=npth read_status=present association=matched_process_session"));
        assertEquals(1,media.inserts);
    }
    @Test public void aStalledCrashReadIsBoundedAndDoesNotOwnTheStoreOrLoggerGeneration() throws Exception {
        java.lang.reflect.Field fileLock=LogBufferManager.class.getDeclaredField("CRASH_FILE_LOCK");fileLock.setAccessible(true);
        java.lang.reflect.Field clearLock=LogBufferManager.class.getDeclaredField("CLEAR_UNDO_LOCK");clearLock.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer=new Thread(()->{try{RecoveryPublisher.publish(context,data);}catch(Throwable e){error.set(e);}});
        synchronized(fileLock.get(null)){
            writer.start();data.update(s->s.put("reader_lock_canary",true));
            synchronized(clearLock.get(null)){assertTrue(data.read().getBoolean("reader_lock_canary"));}
            writer.join(4000);assertFalse(writer.isAlive());assertNull(error.get());assertTrue(exported().contains("read_status=read_timeout"));
        }
        java.lang.reflect.Field busy=RecoveryCapture.class.getDeclaredField("PROBE_BUSY");busy.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean flag=(java.util.concurrent.atomic.AtomicBoolean)busy.get(null);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(flag.get()&&System.nanoTime()<until)Thread.yield();
        assertFalse("bounded worker did not drain after releasing the file lock",flag.get());
    }
    @Test public void aPreClearProducerCannotPutOldCrashContentIntoANewExport() throws Exception {
        String crash=matchingCrash();data.clear();
        // A handler may finish building its report before Clear, then acquire the write lock afterwards.
        LogBufferManager.persistCrashReport(context,crash);
        data.update(s->s.put("incidents",new JSONArray().put(new JSONObject().put("id","new")
                .put("report",REPORT).put("hash",RecoveryPublisher.sha(REPORT)))));
        RecoveryPublisher.publish(context,data);String text=exported();
        assertTrue(text.contains("association=cleared_record"));assertFalse(text.contains("opening-panel-canary"));
    }
    @Test public void ordinaryClearAndUndoRestoreSupplementalEvidenceWithoutRearmingTheTrial() throws Exception {
        Utils.setContext(context);LogBufferManager.persistCrashReport(context,matchingCrash());
        RecoveryPublisher.publish(context,data);int starts=data.read().optInt("starts");
        LogBufferManager.clearLogBuffer();assertEquals("",RecoveryCapture.render(data.read()));
        assertEquals("absent",LogBufferManager.readCrashReportsForRecovery(context).java.status);
        assertEquals(LogBufferManager.UndoResult.RESTORED,LogBufferManager.undoClear());
        RecoveryPublisher.publish(context,data);assertTrue(exported().contains("opening-panel-canary"));
        assertEquals(starts,data.read().optInt("starts"));
    }
    @Test public void undoRestoresCrashAssociationWhenRecoveryHadNoIncidentOrActivation() throws Exception {
        Utils.setContext(context);LogBufferManager.persistCrashReport(context,matchingCrash());
        data.update(s->{s.remove("incidents");s.remove("activations");});
        int generation=data.read().optInt("generation");
        LogBufferManager.clearLogBuffer();assertEquals(LogBufferManager.UndoResult.RESTORED,LogBufferManager.undoClear());
        assertTrue(data.read().optInt("generation")>generation);
        data.update(s->s.put("incidents",new JSONArray().put(new JSONObject().put("id","later")
                .put("report",REPORT).put("hash",RecoveryPublisher.sha(REPORT)))));
        RecoveryPublisher.publish(context,data);assertTrue(exported(),exported().contains("opening-panel-canary"));
    }
    @Test public void undoRestoresTheClearBoundaryWithoutOverwritingNewerIncidents() throws Exception {
        Utils.setContext(context);LogBufferManager.persistCrashReport(context,matchingCrash());
        data.update(s->{s.remove("incidents");s.remove("activations");});LogBufferManager.clearLogBuffer();
        data.update(s->s.put("incidents",new JSONArray().put(new JSONObject().put("id","newer")
                .put("report",REPORT+"newer-canary").put("hash",RecoveryPublisher.sha(REPORT+"newer-canary")))));
        assertEquals(LogBufferManager.UndoResult.RESTORED,LogBufferManager.undoClear());
        RecoveryPublisher.publish(context,data);assertTrue(exported().contains("opening-panel-canary"));
        assertTrue(exported().contains("newer-canary"));assertEquals(1,data.read().getJSONArray("incidents").length());
    }
    @Test public void expiredExportLeaseDoesNotStartAnotherSupplementalExport() throws Exception {
        LogBufferManager.persistCrashReport(context,matchingCrash());data.update(s->s.put("export_until",System.currentTimeMillis()-1));
        RecoveryPublisher.publish(context,data);assertEquals(0,media.inserts);
    }
    @Test public void aLeaseThatExpiresDuringProviderIoCannotPublishItsSnapshot() throws Exception {
        media.hold=true;java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer=new Thread(()->{try{RecoveryPublisher.publish(context,data);}catch(Throwable e){error.set(e);}});writer.start();
        assertTrue(media.entered.await(5,TimeUnit.SECONDS));
        data.update(s->s.put("export_until",System.currentTimeMillis()-1));media.release.countDown();writer.join(5000);
        assertFalse(writer.isAlive());assertNull(error.get());assertEquals(0,media.rows.size());
        assertEquals(REPORT,data.read().getJSONArray("incidents").getJSONObject(0).getString("report"));
    }

    static final class Provider extends ContentProvider {
        static final class Row { File file; ContentValues values; }
        final File directory; final String owner;
        final Map<Long, Row> rows = new LinkedHashMap<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        int inserts; boolean hold, rename, alwaysBadRead, pretendPublished; String fault = "";
        Provider(File directory, String owner) { this.directory = directory; this.owner = owner; }
        boolean fail(String stage) { if (!stage.equals(fault)) return false; fault = ""; return true; }
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            entered.countDown();
            if (hold) try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("unreleased provider"); }
            catch (InterruptedException e) { throw new IllegalStateException(e); }
            if (fail("insert")) throw new IllegalStateException("provider failure");
            Row row = new Row(); long id = ++inserts; row.file = new File(directory, "row-" + id);
            row.values = new ContentValues(values); row.values.put(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, owner);
            row.values.put(MediaStore.MediaColumns.IS_TRASHED, 0);
            if (rename) row.values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).replace(".txt", " (1).txt"));
            rows.put(id, row); return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, Long.toString(id));
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (mode.contains("w") && fail("open")) throw new FileNotFoundException("provider failure");
            Row row = rows.get(Long.parseLong(uri.getLastPathSegment()));
            if (row == null) throw new FileNotFoundException();
            if (!mode.contains("w") && (alwaysBadRead || fail("read"))) {
                File bad = new File(directory, "bad-read");
                try { Files.write(bad.toPath(), "bad".getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new FileNotFoundException(); }
                return ParcelFileDescriptor.open(bad, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            return ParcelFileDescriptor.open(row.file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public Cursor query(Uri uri, String[] projection, String where, String[] args, String sort) {
            return queryRows(uri, projection, where, args, false, false);
        }
        @Override public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal signal) {
            return queryRows(uri, projection, args.getString(ContentResolver.QUERY_ARG_SQL_SELECTION),
                    args.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS),
                    args.getInt(MediaStore.QUERY_ARG_MATCH_PENDING) == MediaStore.MATCH_INCLUDE,
                    args.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED) == MediaStore.MATCH_INCLUDE);
        }
        private Cursor queryRows(Uri uri, String[] projection, String where, String[] args, boolean pending, boolean trash) {
            if (projection.length >= 2 && MediaStore.MediaColumns.IS_PENDING.equals(projection[1]) && fail("name"))
                throw new IllegalStateException("provider failure");
            MatrixCursor cursor = new MatrixCursor(projection);
            for (Map.Entry<Long, Row> e : rows.entrySet()) {
                if (!uri.equals(MediaStore.Downloads.EXTERNAL_CONTENT_URI) && !Long.toString(e.getKey()).equals(uri.getLastPathSegment())) continue;
                Row row = e.getValue();
                if (uri.equals(MediaStore.Downloads.EXTERNAL_CONTENT_URI) && !pending
                        && row.values.getAsInteger(MediaStore.MediaColumns.IS_PENDING) != 0) continue;
                if (!trash && row.values.getAsInteger(MediaStore.MediaColumns.IS_TRASHED) != 0) continue;
                if (where != null && !row.values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME).startsWith(args[0].replace("%", ""))) continue;
                Object[] result = new Object[projection.length];
                for (int i = 0; i < projection.length; i++) result[i] = MediaStore.MediaColumns._ID.equals(projection[i]) ? e.getKey() : row.values.get(projection[i]);
                cursor.addRow(result);
            }
            return cursor;
        }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            if (fail("publish")) return 0;
            if (pretendPublished) return 1;
            Row row = rows.get(Long.parseLong(uri.getLastPathSegment())); if (row == null) return 0;
            row.values.putAll(values); return 1;
        }
        @Override public int delete(Uri uri, String where, String[] args) {
            Row row = rows.remove(Long.parseLong(uri.getLastPathSegment())); return row == null ? 0 : 1;
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
    }
}
