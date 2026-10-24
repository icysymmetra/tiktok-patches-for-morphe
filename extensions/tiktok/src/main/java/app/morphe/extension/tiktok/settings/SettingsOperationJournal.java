/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings;

import android.content.Context;
import android.util.AtomicFile;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SettingsJson;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.json.JSONException;
import org.json.JSONObject;

/** Serializes persistent settings changes and repairs an interrupted change at startup. */
public final class SettingsOperationJournal {
    static final String FILE_NAME = "hushfeed-settings-operation.json";
    // Each snapshot is bounded to 2 MB, and embedding it as a JSON string can escape every
    // quote and backslash. Leave room for that expansion and the journal envelope.
    private static final int MAX_JOURNAL_BYTES = SettingsBackup.MAX_BYTES * 4 + 16384;
    private static final ReentrantLock LOCK = new ReentrantLock(true);
    private static Recovery pendingNotice = Recovery.NONE;
    private static String noticeFingerprint;

    private SettingsOperationJournal() {
    }

    public enum Recovery {
        NONE,
        RECOVERED_PRIOR,
        ALREADY_COMMITTED,
        MALFORMED,
        FAILED
    }

    @FunctionalInterface
    interface JournalDelete {
        void delete(AtomicFile file) throws IOException;
    }

    /** What clears a committed journal. */
    private static volatile JournalDelete committedDelete = SettingsOperationJournal::delete;

    /**
     * Makes the delete that clears a committed journal fail, the way a full or read-only disk
     * does, or puts the real one back. The failure is swallowed there, and what the next start
     * makes of the journal it leaves is the thing a test of it has to look at.
     */
    public static void failCommittedDeletesForTests(boolean fail) {
        committedDelete = fail
                ? file -> { throw new IOException("A test refused this delete"); }
                : SettingsOperationJournal::delete;
    }

    /** Reads and reconciles the journal. This is safe to call more than once during startup. */
    public static Recovery initialize(Context context) {
        return initialize(context, SettingsOperationJournal::delete);
    }

    /** Package-visible deletion seam for exercising storage failures after reconciliation. */
    static Recovery initialize(Context context, JournalDelete journalDelete) {
        if (context == null || !Utils.isMainProcess()) return Recovery.NONE;
        Context app = applicationContext(context);
        LOCK.lock();
        try {
            return reconcileLocked(app, journalDelete);
        } finally {
            LOCK.unlock();
        }
    }

    /** Shows a single user-facing notice for the most recent startup recovery result. */
    public static void showRecoveryNotice(Context context) {
        Recovery recovery = consumeRecoveryNotice();
        if (recovery == Recovery.NONE) return;
        String message;
        switch (recovery) {
            case RECOVERED_PRIOR:
                message = "Recovered an interrupted settings change. Restart TikTok to apply this.";
                break;
            case ALREADY_COMMITTED:
                message = "Completed an interrupted settings change. Restart TikTok to apply this.";
                break;
            case MALFORMED:
                message = "The record of an interrupted settings change couldn't be read, so it was set aside. Check your settings.";
                break;
            case FAILED:
                message = "An interrupted settings change couldn't be recovered, so its record was set aside. Check your settings or restore a backup.";
                break;
            default:
                return;
        }
        Utils.showToastLong(L10n.t(context, message));
    }

    public static Recovery consumeRecoveryNotice() {
        LOCK.lock();
        try {
            Recovery result = pendingNotice;
            pendingNotice = Recovery.NONE;
            return result;
        } finally {
            LOCK.unlock();
        }
    }

    /** Acquires the process-wide mutation lock before a caller captures its prior snapshot. */
    public static Operation acquire(Context context) throws IOException {
        if (context == null) throw new IOException("Settings storage unavailable");
        if (!Utils.isMainProcess()) {
            throw new IOException("Persistent settings are writable only from the main process");
        }
        Context app = applicationContext(context);
        LOCK.lock();
        // The lock is handed to the Operation on success and released on every other way out,
        // an Error included: reconciling reads a journal of up to 8 MB and builds two trees
        // from it, and a lock left held by a thread that has gone parks every later caller.
        boolean handedOver = false;
        try {
            reconcileLocked(app, SettingsOperationJournal::delete);
            // A record that could not be read or applied has been set aside by then. The one
            // reason left to refuse is a journal still in place, which is a record that has
            // not been reconciled and must not be written over.
            if (hasJournalFile(journalFile(app))) {
                throw new IOException("Settings recovery needs attention");
            }
            Operation operation = new Operation(app);
            handedOver = true;
            return operation;
        } finally {
            if (!handedOver) LOCK.unlock();
        }
    }

    public static final class Operation {
        private final Context context;
        private boolean recorded;
        private boolean finished;
        private String recordedKind;
        private String recordedBefore;

        private Operation(Context context) {
            this.context = context;
        }

        public void recordSettings(String before, String after) throws IOException {
            record("settings", before, after);
        }

        public void recordLab(String before, String after) throws IOException {
            record("lab", before, after);
        }

        /**
         * Puts what the change actually wrote in place of the after snapshot it set out with. A
         * restore the budget held back writes less than its file carries, and a journal whose
         * after snapshot is the whole file reads as interrupted if its delete then fails.
         */
        public void recordWritten(String after) throws IOException {
            if (finished) throw new IOException("Settings operation is already closed");
            if (!recorded) throw new IOException("Settings operation has no intent to correct");
            writeIntent(recordedKind, recordedBefore, after);
        }

        private void record(String kind, String before, String after) throws IOException {
            ensureOpen();
            writeIntent(kind, before, after);
            recordedKind = kind;
            recordedBefore = before;
            recorded = true;
        }

        private void writeIntent(String kind, String before, String after) throws IOException {
            validateSnapshot(before);
            validateSnapshot(after);
            JSONObject root;
            try {
                root = new JSONObject().put("schema", 1).put("kind", kind)
                        .put("before", before).put("after", after);
            } catch (JSONException error) {
                throw new IOException("Could not create settings journal", error);
            }
            write(journalFile(context), root.toString());
        }

        /** Clears a committed journal and releases the process lock. */
        public void complete() {
            if (finished) return;
            try {
                if (recorded) {
                    try {
                        committedDelete.delete(journalFile(context));
                    } catch (IOException ignored) {
                        // A committed operation is still recoverable from its after snapshot.
                    }
                }
            } finally {
                finished = true;
                LOCK.unlock();
            }
        }

        /** Leaves the journal for the next process to reconcile, then releases the lock. */
        public void retainForRecovery() {
            if (finished) return;
            finished = true;
            LOCK.unlock();
        }

        /** Discards an intent that was never applied and releases the process lock. */
        public void abort() {
            if (finished) return;
            try {
                if (recorded) {
                    try {
                        delete(journalFile(context));
                    } catch (IOException ignored) {
                        // The next startup can clear an intent that was never applied.
                    }
                }
            } finally {
                finished = true;
                LOCK.unlock();
            }
        }

        private void ensureOpen() throws IOException {
            if (finished) throw new IOException("Settings operation is already closed");
            if (recorded) throw new IOException("Settings operation already has an intent");
        }
    }

    private static Recovery reconcileLocked(Context context, JournalDelete journalDelete) {
        AtomicFile file = journalFile(context);
        // AtomicFile may leave the last durable copy in its .bak file when the process
        // dies between startWrite and finishWrite. Let openRead restore that copy instead
        // of treating the interrupted operation as if no journal existed.
        if (!hasJournalFile(file)) return Recovery.NONE;

        // A record that cannot be read, or cannot be applied, is set aside rather than left in
        // place. Left there, every later acquire() refused to start, and the notice told the
        // reader to use Undo or restore a backup, both of which start with acquire(). Nothing
        // short of clearing the app's data got out of that. The copy set aside keeps what the
        // record said for anyone reading the diagnostics; the notice says what happened.
        String text;
        String fingerprint;
        try {
            text = read(file);
            fingerprint = fingerprint(text);
        } catch (Exception error) {
            String unreadableFingerprint = unreadableFingerprint(file);
            setAside(file);
            publish(Recovery.MALFORMED, unreadableFingerprint);
            return Recovery.MALFORMED;
        }

        JournalEntry entry;
        try {
            entry = parseEntry(text);
            validateEntry(entry);
        } catch (Exception error) {
            setAside(file);
            publish(Recovery.MALFORMED, fingerprint);
            return Recovery.MALFORMED;
        }

        Recovery result;
        try {
            result = entry.settings ? reconcileSettings(entry) : reconcileLab(entry);
        } catch (Exception error) {
            Logger.printException(() -> "Could not reconcile the settings journal", error);
            setAside(file);
            publish(Recovery.FAILED, fingerprint);
            return Recovery.FAILED;
        }

        // The state result belongs to reconciliation, not to cleanup. Publish it first. If the
        // durable record cannot be deleted, retain it as diagnostics without relabelling an
        // already recovered or already committed settings change as a recovery failure.
        publish(result, fingerprint);
        try {
            journalDelete.delete(file);
        } catch (Exception error) {
            Logger.printException(() -> "Could not clear the reconciled settings journal", error);
            setAside(file);
        }
        return result;
    }

    /** The name a journal that could not be used is kept under, beside where it was. */
    static final String DAMAGED_SUFFIX = ".damaged";

    /**
     * Moves the journal out of the way, keeping its bytes under {@link #DAMAGED_SUFFIX}. An
     * older damaged copy is replaced. If the move itself fails the file is deleted, because
     * what matters is that the next acquire() can start; the diagnostics already carry the
     * failure that brought this about.
     */
    private static void setAside(AtomicFile file) {
        File base = file.getBaseFile();
        File damaged = new File(base.getPath() + DAMAGED_SUFFIX);
        File backup = new File(base.getPath() + ".bak");
        // AtomicFile keeps the last durable copy in .bak while a write is in flight, and
        // openRead() prefers it, so it is what an unreadable base was read from. Both go.
        File source = base.isFile() ? base : backup;
        if (damaged.exists() && !damaged.delete()) {
            Logger.printInfo(() -> "Could not replace the previous damaged settings journal");
        }
        if (source.isFile() && !source.renameTo(damaged)) {
            Logger.printInfo(() -> "Could not set the settings journal aside, deleting it");
        }
        file.delete();
        if (hasJournalFile(file)) {
            Logger.printException(() -> "The settings journal could not be cleared");
        }
    }

    private static Recovery reconcileSettings(JournalEntry entry) throws Exception {
        SettingsBackup.Snapshot before = SettingsBackup.parseForJournal(entry.before);
        SettingsBackup.Snapshot after = SettingsBackup.parseForJournal(entry.after);
        boolean beforeMatches = SettingsBackup.matchesForJournal(before);
        boolean afterMatches = SettingsBackup.matchesForJournal(after);
        if (!beforeMatches && !afterMatches) {
            SettingsBackup.applyForJournal(before);
            if (!SettingsBackup.matchesForJournal(before)) {
                throw new IOException("Could not verify settings recovery");
            }
            return Recovery.RECOVERED_PRIOR;
        }
        return afterMatches ? Recovery.ALREADY_COMMITTED : Recovery.RECOVERED_PRIOR;
    }

    private static Recovery reconcileLab(JournalEntry entry) throws Exception {
        JSONObject before = SettingsJson.parseObject(entry.before);
        JSONObject after = SettingsJson.parseObject(entry.after);
        validateLab(before);
        validateLab(after);
        boolean beforeMatches = labMatches(before);
        boolean afterMatches = labMatches(after);
        if (!beforeMatches && !afterMatches) {
            applyLab(before);
            if (!labMatches(before)) throw new IOException("Could not verify Lab recovery");
            return Recovery.RECOVERED_PRIOR;
        }
        return afterMatches ? Recovery.ALREADY_COMMITTED : Recovery.RECOVERED_PRIOR;
    }

    private static boolean labMatches(JSONObject expected) {
        return FeatureGateLabStore.settingsMatch(expected);
    }

    /**
     * Puts back the rules a change was interrupted before it could commit. Its only caller
     * recovers the prior state, so the record of which overrides fired is kept: it was made
     * against rules that either are these or are the half written state this is undoing, and a
     * marker left over for a rule that no longer exists shows nowhere, because the detail screen
     * needs the rule before it reads the marker.
     */
    private static void applyLab(JSONObject saved) throws Exception {
        List<FeatureGateLabStore.Rule> rules = FeatureGateLabStore.parseSettings(saved);
        FeatureGateLabStore.replaceSettings(rules, saved.getBoolean("master"),
                saved.getBoolean("acknowledged"), true);
    }

    private static void validateLab(JSONObject value) throws JSONException {
        FeatureGateLabStore.parseSettings(value);
    }

    private static JournalEntry parseEntry(String text) throws IOException, JSONException {
        JSONObject root = SettingsJson.parseObject(text);
        if (!Integer.valueOf(1).equals(root.get("schema"))) {
            throw new IOException("Unsupported settings journal schema");
        }
        String kind = root.getString("kind");
        if (!("settings".equals(kind) || "lab".equals(kind))) {
            throw new IOException("Unsupported settings journal kind");
        }
        Object before = root.get("before"), after = root.get("after");
        if (!(before instanceof String) || !(after instanceof String)) {
            throw new IOException("Invalid settings journal snapshots");
        }
        return new JournalEntry("settings".equals(kind), (String) before, (String) after);
    }

    private static void validateEntry(JournalEntry entry) throws Exception {
        validateSnapshot(entry.before);
        validateSnapshot(entry.after);
        if (entry.settings) {
            SettingsBackup.parseForJournal(entry.before);
            SettingsBackup.parseForJournal(entry.after);
        } else {
            validateLab(SettingsJson.parseObject(entry.before));
            validateLab(SettingsJson.parseObject(entry.after));
        }
    }

    private static void validateSnapshot(String text) throws IOException {
        if (text == null || text.isEmpty()
                || text.getBytes(StandardCharsets.UTF_8).length > SettingsBackup.MAX_BYTES) {
            throw new IOException("Invalid settings journal snapshot");
        }
    }

    private static Context applicationContext(Context context) {
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    private static AtomicFile journalFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
    }

    private static boolean hasJournalFile(AtomicFile file) {
        File base = file.getBaseFile();
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    private static void write(AtomicFile file, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JOURNAL_BYTES) throw new IOException("Settings journal is too large");
        java.io.FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
        if (!text.equals(read(file))) throw new IOException("Could not verify settings journal");
    }

    private static String read(AtomicFile file) throws IOException {
        try (InputStream input = file.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_JOURNAL_BYTES) {
                    throw new IOException("Settings journal is too large");
                }
                bytes.write(buffer, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
    }

    private static void delete(AtomicFile file) throws IOException {
        file.delete();
        if (hasJournalFile(file)) throw new IOException("Could not clear settings journal");
    }

    private static void publish(Recovery result, String fingerprint) {
        if (result == Recovery.NONE || fingerprint == null) return;
        if (!fingerprint.equals(noticeFingerprint)) {
            noticeFingerprint = fingerprint;
            pendingNotice = result;
        }
    }

    private static String fingerprint(String text) {
        return text.length() + ":" + text.hashCode();
    }

    /** Identity for bytes that could not be decoded and therefore have no content fingerprint. */
    private static String unreadableFingerprint(AtomicFile file) {
        File base = file.getBaseFile();
        File source = base.isFile() ? base : new File(base.getPath() + ".bak");
        return source.getAbsolutePath() + ":" + source.length() + ":" + source.lastModified();
    }

    private static final class JournalEntry {
        final boolean settings;
        final String before;
        final String after;

        JournalEntry(boolean settings, String before, String after) {
            this.settings = settings;
            this.before = before;
            this.after = after;
        }
    }
}
