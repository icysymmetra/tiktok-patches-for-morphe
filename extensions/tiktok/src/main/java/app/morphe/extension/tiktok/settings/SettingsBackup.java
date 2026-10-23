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
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.wellbeing.BudgetChanges;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import app.morphe.extension.shared.settings.SettingsJson;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;
import app.morphe.extension.tiktok.download.DownloadDestination;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** File-backed settings snapshots. Call file and preference writes on a worker thread. */
public final class SettingsBackup {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** The format tag written today, and the one backups made before the rename carry. */
    public static final String FORMAT = "metra-settings";
    /** The newest backup shape this build reads; a file declaring more is refused, absent means 1. */
    static final int SCHEMA = 1;
    public static final String LEGACY_FORMAT = "hushfeed-settings";
    private SettingsBackup() {}

    public enum Failure {
        REJECTED_INPUT,
        ROLLED_BACK,
        RECOVERY_REQUIRED
    }

    /**
     * Why a backup was refused.
     *
     * <p>Every one of these used to reach the user as "The settings backup was rejected", so a
     * truncated download, a file from a newer Hushfeed and a perfectly good backup all read the
     * same and there was nothing to act on.
     */
    public enum Reason {
        SIZE,
        ENCODING,
        /** Readable text that is not JSON at all, which is what a part-finished download is. */
        DAMAGED,
        FORMAT,
        SCHEMA,
        INCOMPLETE,
        VALUE,
        /** A caption or creator list exceeds its persisted byte or entry bound. */
        RULE_LIST,
        /** More Feature Gate Lab rules than the Lab keeps, which only a hand-edited file has. */
        LAB_RULES,
        UNKNOWN
    }

    /** Carries {@link Reason} out of the parse, which throws JSONException and IOException. */
    static final class RejectedBackup extends JSONException {
        final Reason reason;

        RejectedBackup(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }
    }

    /** A restore failure that records whether both stores are back at their prior state. */
    public static final class RestoreException extends Exception {
        private final Failure failure;
        private final Reason reason;
        private final boolean rollbackComplete;
        private final boolean recoveryAvailable;

        private RestoreException(Throwable cause, Failure failure, Reason reason,
                boolean rollbackComplete, boolean recoveryAvailable) {
            super(cause.getMessage(), cause);
            this.failure = failure;
            this.reason = reason;
            this.rollbackComplete = rollbackComplete;
            this.recoveryAvailable = recoveryAvailable;
        }

        private RestoreException(Throwable cause, Failure failure, boolean rollbackComplete,
                boolean recoveryAvailable) {
            this(cause, failure, Reason.UNKNOWN, rollbackComplete, recoveryAvailable);
        }

        public Failure getFailure() { return failure; }
        /** Only meaningful for {@link Failure#REJECTED_INPUT}. */
        public Reason getReason() { return reason; }
        public boolean isRollbackComplete() { return rollbackComplete; }
        public boolean isRecoveryAvailable() { return recoveryAvailable; }

        private static RestoreException rejected(Throwable cause) {
            return new RestoreException(cause, Failure.REJECTED_INPUT, reasonOf(cause), true, false);
        }

        private static Reason reasonOf(Throwable cause) {
            if (cause instanceof RejectedBackup) return ((RejectedBackup) cause).reason;
            if (cause instanceof java.nio.charset.CharacterCodingException) return Reason.ENCODING;
            if (cause instanceof IOException) {
                String message = cause.getMessage();
                if (message != null && message.contains("2 MB")) return Reason.SIZE;
            }
            return Reason.UNKNOWN;
        }
    }

    private static boolean included(Setting<?> setting) {
        return setting.includeWithImportExport || setting == BaseSettings.DEBUG_LOG_FILTERS;
    }

    /** What a picked file's network proxy came to on this phone. */
    public enum ProxyHold {
        /** Restored as the file has it. */
        NONE,
        /** The file had it on, and it was left off; its address came along. */
        LEFT_OFF,
        /** This phone's own proxy address was kept over the file's. */
        KEPT_THIS_PHONES
    }

    /**
     * A file someone picked can't turn the network proxy on, or change an address this phone
     * already has: a shared backup would otherwise send TikTok's traffic, and the sign-in saved
     * here, through its author's server. It can still turn the proxy off, and on a phone with no
     * address yet its address comes along, so a move to a new phone only needs the switch turned
     * back on. The device's own undo copy isn't held to this.
     */
    private static ProxyHold keepTheProxyWhereTheDeviceHasIt(Map<Setting<?>, Object> updates) {
        boolean onNow = Settings.NETWORK_PROXY.savedValue();
        ProxyHold hold = ProxyHold.NONE;
        if (onNow || !Settings.NETWORK_PROXY_HOST.savedValue().trim().isEmpty()) {
            for (Setting<?> field : new Setting<?>[] {
                    Settings.NETWORK_PROXY_TYPE, Settings.NETWORK_PROXY_HOST, Settings.NETWORK_PROXY_PORT }) {
                Object own = field.savedValue();
                if (own.equals(updates.get(field))) continue;
                updates.put(field, own);
                hold = ProxyHold.KEPT_THIS_PHONES;
            }
        }
        if (!onNow && Boolean.TRUE.equals(updates.get(Settings.NETWORK_PROXY))) {
            updates.put(Settings.NETWORK_PROXY, false);
            if (hold == ProxyHold.NONE) hold = ProxyHold.LEFT_OFF;
        }
        return hold;
    }

    /** What restoring this file does to the network proxy. Ask before the restore changes it. */
    public static ProxyHold proxyHold(String text) {
        try {
            return parse(text).proxyHold;
        } catch (Exception ignored) {
            return ProxyHold.NONE;
        }
    }

    public static String create(boolean defaults) throws JSONException, IOException {
        Settings.REGION_SPOOF.get(); // Initialize the complete settings registry.
        JSONObject values = new JSONObject();
        JSONArray keys = new JSONArray();
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (!included(setting)) continue;
            Object value = defaults ? setting.defaultValue : setting.savedValue();
            values.put(setting.key, value instanceof Enum<?> ? ((Enum<?>) value).name() : value);
            keys.put(setting.key);
        }
        JSONObject lab = FeatureGateLabStore.exportSettings();
        if (defaults) lab.put("rules", new JSONArray()).put("master", false).put("acknowledged", false);
        String text = new JSONObject().put("format", FORMAT).put("schema", SCHEMA)
                .put("target", FeatureGateLabStore.targetVersion()).put("settings", values)
                .put("setting_keys", keys)
                .put("lab", lab).toString(2);
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("Backup exceeds 2 MB");
        return text;
    }

    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("Could not open backup");
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > MAX_BYTES) throw new IOException("Backup exceeds 2 MB");
                bytes.write(buffer, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
    }

    public static void restore(Context context, String text, boolean saveUndo) throws Exception {
        restore(context, text, saveUndo, true);
    }

    /** With holdRuleLists false for the device's own undo copy, whose lists are already in effect. */
    private static void restore(Context context, String text, boolean saveUndo, boolean holdRuleLists)
            throws Exception {
        Snapshot next;
        try {
            next = decode(text, holdRuleLists);
        } catch (Exception error) {
            throw RestoreException.rejected(error);
        }
        SettingsOperationJournal.Operation operation = SettingsOperationJournal.acquire(context);
        restoreWithOperation(context, text, saveUndo, next, operation);
    }

    public static void reset(Context context) throws Exception {
        SettingsOperationJournal.Operation operation = SettingsOperationJournal.acquire(context);
        try {
            String text = create(true);
            Snapshot next;
            try {
                next = parseDefaults(text);
            } catch (Exception error) {
                throw RestoreException.rejected(error);
            }
            restoreWithOperation(context, text, true, next, operation);
        } catch (Exception error) {
            operation.abort();
            throw error;
        }
        // The proxy's sign-in never goes into a file, so the defaults above leave it out and it
        // outlived every reset. Nothing can bring it back either: Undo reads a file too.
        Settings.NETWORK_PROXY_USER.resetToDefault();
        Settings.NETWORK_PROXY_PASSWORD.resetToDefault();
    }

    private static void restoreWithOperation(Context context, String text, boolean saveUndo,
            Snapshot next, SettingsOperationJournal.Operation operation) throws Exception {
        boolean closed = false;
        try {
            // A budget change already due is applied before anything is read, so it is part of
            // what the restore found: the undo copy carries it, and a restore that fails puts it
            // back rather than the value it replaced, whose record would be gone by then.
            BudgetChanges.applyDue(SessionBudget.now());
            String previousText = create(false);
            String previousJournal = withPendingBudget(previousText,
                    Settings.SESSION_BUDGET_PENDING.savedValue());
            Snapshot previous = parseForJournal(previousJournal);
            Map<String, ?> previousPreferences = new LinkedHashMap<>(
                    Setting.preferences.preferences.getAll());
            if (saveUndo) {
                writeUndo(context, previousText);
                CalmFeedPreset.holdForUndo(context);
            }
            BudgetChanges.Split budget = BudgetChanges.forRestore(next.values, SessionBudget.now());
            Map<Setting<?>, Object> updates = budget.withWaiting();
            operation.recordSettings(previousJournal, withPendingBudget(text,
                    (String) updates.get(Settings.SESSION_BUDGET_PENDING)));
            // Whether the apply below got as far as the Lab. It writes the ordinary settings
            // first, so a failure in those never reaches the Lab at all, and reading
            // labIncluded instead put the Lab back on every same-version failure: the common
            // one, and the only case this guard exists for.
            boolean[] touchedLab = new boolean[1];
            try {
                applySnapshot(next, updates, touchedLab, false);
                // Held to the budget, the restore wrote less than its file carries. What it did
                // write goes on record, or a journal the delete below fails to clear would read as
                // an interrupted restore, and the next start would put the old settings back.
                // The same for a download folder kept because the file's could not hold its kind,
                // and for a network proxy the file wasn't allowed to turn on or move.
                if (budget.heldBack() || !next.keptFolders.isEmpty() || next.proxyHold != ProxyHold.NONE) {
                    operation.recordWritten(withPendingBudget(create(false),
                            Settings.SESSION_BUDGET_PENDING.savedValue()));
                }
                operation.complete();
                closed = true;
                if (next.labIncluded) FeatureGateLabStore.discardLabUndo();
                // The Calm feed card offered "Restore setup" from before a reset or an import,
                // which would have put back values it had just replaced. Its copy went aside
                // with the undo copy above, so the Undo below brings both back.
                if (saveUndo) CalmFeedPreset.clearAfterRestore(context);
            } catch (Exception error) {
                try { Setting.saveAll(previous.values, true); } catch (Exception rollback) { error.addSuppressed(rollback); }
                // Only put the Lab back when the apply above reached it. Writing the same
                // rules again is not free: it raises a restart notice for a store the failed
                // restore never touched.
                if (touchedLab[0]) {
                    try {
                        FeatureGateLabStore.replaceSettings(
                                previous.rules, previous.master, previous.acknowledged, true);
                    }
                    catch (Exception rollback) { error.addSuppressed(rollback); }
                }
                boolean rollbackComplete = ordinarySettingsMatch(previousPreferences)
                        && labSettingsMatch(previous);
                boolean recoveryAvailable = hasVerifiedUndo(context);
                if (rollbackComplete) operation.complete();
                else operation.retainForRecovery();
                closed = true;
                throw new RestoreException(error,
                        rollbackComplete ? Failure.ROLLED_BACK : Failure.RECOVERY_REQUIRED,
                        rollbackComplete, recoveryAvailable);
            }
        } finally {
            if (!closed) operation.abort();
        }
    }

    /** Restores the undo copy and returns its text, so the caller can report what it held. */
    public static String undo(Context context) throws Exception {
        // As a restore does: a budget change already due belongs to what the undo replaces.
        BudgetChanges.applyDue(SessionBudget.now());
        String replaced = create(false);
        String text = restoreFrom(context, readableUndoFile(context).openRead(), false, false);
        CalmFeedPreset.swapWithUndo(context);
        // What the undo replaced becomes the copy, once it has worked, so a second Undo brings
        // back whatever changed since the restore instead of losing it for good. Written after,
        // not before: a failed undo must leave the copy it was asked for in place.
        try {
            writeUndo(context, replaced);
        } catch (IOException error) {
            Logger.printException(() -> "Could not keep the settings the undo replaced", error);
        }
        return text;
    }

    /**
     * Reads and restores in one call, so a file that cannot be read is refused with a reason
     * rather than as a bare {@link IOException} the caller cannot tell apart. Returns the text.
     */
    public static String restoreFrom(Context context, InputStream input, boolean saveUndo)
            throws Exception {
        return restoreFrom(context, input, saveUndo, true);
    }

    private static String restoreFrom(Context context, InputStream input, boolean saveUndo,
            boolean holdRuleLists) throws Exception {
        String text = readForRestore(input);
        restore(context, text, saveUndo, holdRuleLists);
        return text;
    }

    /**
     * Reads a backup the way a restore does, refusing an unreadable or oversized file with a
     * reason, and changes nothing. A restore that has to be stoppable while a file app hands the
     * file over reads with this, then calls {@link #restore} once it is past the point of stopping.
     */
    public static String readForRestore(InputStream input) throws RestoreException {
        try {
            return read(input);
        } catch (Exception error) {
            throw RestoreException.rejected(error);
        }
    }
    /**
     * Whether an undo copy can be read. AtomicFile keeps the last durable copy in .bak while a
     * write is in flight and openRead() restores it, so a process death in that window leaves
     * an undo that works behind a base file that is missing. The row was greyed out over it.
     */
    public static boolean hasUndo(Context context) {
        return hasAtomicFile(readableUndoFile(context));
    }

    private static boolean ordinarySettingsMatch(Map<String, ?> expected) {
        try {
            return expected.equals(Setting.preferences.preferences.getAll());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean labSettingsMatch(Snapshot expected) {
        try {
            // A snapshot that leaves the Lab alone carries no rules to compare against, and its
            // master and acknowledged flags are placeholders rather than anything that was
            // applied. Reading them anyway threw on the null list, the catch below turned that
            // into "does not match", and the journal then reverted a restore that had worked.
            if (!expected.labIncluded) return true;
            if (expected.master != FeatureGateLabStore.masterEnabled()
                    || expected.acknowledged != FeatureGateLabStore.warningAcknowledged()) {
                return false;
            }
            List<FeatureGateLabStore.Rule> actual = FeatureGateLabStore.rules();
            if (actual.size() != expected.rules.size()) return false;
            for (FeatureGateLabStore.Rule wanted : expected.rules) {
                FeatureGateLabStore.Rule found = null;
                for (FeatureGateLabStore.Rule candidate : actual) {
                    if (Objects.equals(wanted.id, candidate.id)) {
                        found = candidate;
                        break;
                    }
                }
                if (found == null || !sameRule(wanted, found)) return false;
            }
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean sameRule(FeatureGateLabStore.Rule left, FeatureGateLabStore.Rule right) {
        return Objects.equals(left.manager, right.manager)
                && Objects.equals(left.key, right.key)
                && Objects.equals(left.type, right.type)
                && Objects.equals(left.value, right.value)
                && left.enabled == right.enabled;
    }

    private static boolean hasVerifiedUndo(Context context) {
        AtomicFile file = readableUndoFile(context);
        if (!hasAtomicFile(file)) return false;
        try (InputStream input = file.openRead()) {
            parseForJournal(read(input));
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    /**
     * Puts back the settings a change was interrupted before it could commit. Its only caller
     * recovers the prior state, unlike the two argument form the restore itself uses to go
     * forward, so the Lab's record of which overrides fired is kept: the recovery reaches the
     * Lab whenever the file carries rules, including when only an ordinary setting had moved.
     */
    static void applyForJournal(Snapshot snapshot) throws IOException {
        applySnapshot(snapshot, snapshot.values, new boolean[1], true);
    }

    /**
     * @param touchedLab set to true the moment the Lab write is about to start, so a caller
     *                   rolling back knows whether there is anything there to put back. Set
     *                   before the write rather than after, because a write that throws part
     *                   way through is exactly the one that needs undoing.
     */
    private static void applySnapshot(Snapshot snapshot, Map<Setting<?>, Object> updates,
            boolean[] touchedLab, boolean puttingBack)
            throws IOException {
        // A restore, a reset or an undo is held to the daily budget like the page is: a locked
        // day keeps its budget, and with loosening set to wait, what loosens it waits. Putting
        // back what an interrupted change found is none of those, so it writes everything.
        Setting.saveAll(updates, snapshot.deviceState || puttingBack);
        // A backup from another TikTok build carries no rules that mean anything here, so the
        // Lab is left as it was rather than emptied.
        if (snapshot.labIncluded) {
            touchedLab[0] = true;
            FeatureGateLabStore.replaceSettings(
                    snapshot.rules, snapshot.master, snapshot.acknowledged, puttingBack);
        }
    }

    /** Only internal journal snapshots carry this device's delayed changes. Exports and Undo
     * keep the public format, and imports never accept a supplied delayed record. */
    private static String withPendingBudget(String text, String pending) throws JSONException {
        return new JSONObject(text).put("local_budget_pending", pending).toString();
    }

    /** How many included settings that file did not carry, which were left as the device had them. */
    public static int settingsNotInFile(String text) {
        try {
            return parseForJournal(text).absent;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** File keys this build cannot restore, separate from current settings absent from the file. */
    public static int settingsSkipped(String text) {
        try {
            return parseForJournal(text).skipped;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** True when the file restored its settings but its Lab rules were for another TikTok build. */
    public static boolean labRulesWereSkipped(String text) {
        try {
            return !parseForJournal(text).labIncluded;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * A file from outside, an import. Its feed rule lists are held to the limits a save enforces,
     * so a hand-edited or foreign file can't load more than the filter can keep, and its network
     * proxy to {@link #keepTheProxyWhereTheDeviceHasIt}.
     */
    private static Snapshot parse(String text) throws JSONException, IOException {
        return decode(text, true);
    }

    /**
     * The defaults a reset writes: held to the rule-list limits like a file, but not to the proxy
     * guard, since putting the proxy back to its default is what a reset was asked to do.
     */
    private static Snapshot parseDefaults(String text) throws JSONException, IOException {
        return decode(text, true, false);
    }

    /**
     * The device's own state: the snapshot a restore takes of what it replaces, the undo copy
     * made from that snapshot, and the journal's records. Those are already in effect, so the
     * rule-list limits aren't held to them. A list kept from before the 2026-09-14 limits made
     * every restore, reset and undo on that phone fail with the generic message, since each one
     * reads the device's own snapshot first.
     */
    static Snapshot parseForJournal(String text) throws JSONException, IOException {
        return decode(text, false);
    }

    /** A feed rule list longer than a backup may carry, named by the setting's title. */
    public static final class RuleListTooLarge extends IOException {
        public final String listTitle;

        RuleListTooLarge(String listTitle) {
            super(listTitle + " exceeds its storage limit");
            this.listTitle = listTitle;
        }
    }

    /**
     * The backup the Save row writes. A list kept from before the 2026-09-14 limits would give
     * a file every restore refuses, so the save is refused instead, naming the list to shorten.
     */
    public static String export() throws JSONException, IOException {
        Settings.REGION_SPOOF.get();
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (included(setting) && feedRuleProblem(setting, setting.savedValue()) != null) {
                throw new RuleListTooLarge(ruleListTitle(setting));
            }
        }
        return create(false);
    }

    /** The settings row each feed rule list is edited on. */
    private static String ruleListTitle(Setting<?> setting) {
        if (setting == Settings.BLOCKED_CAPTION_WORDS) return "Blocked caption words";
        if (setting == Settings.LIVE_HIDDEN_CATEGORIES) return "Hidden LIVE categories";
        if (setting == Settings.BLOCKED_CREATORS) return "Blocked creators";
        if (setting == Settings.LOCAL_HIDDEN_CREATORS) return "Creators hidden on this phone";
        return "Creator exceptions";
    }

    static boolean matchesForJournal(Snapshot expected) {
        try {
            for (Map.Entry<Setting<?>, Object> entry : expected.values.entrySet()) {
                // The snapshots were written from saved values, so they are compared with saved
                // values: paused, get() answers TikTok's own path and matches neither. An after
                // snapshot is the restored file, though, and the save keeps each number in its
                // setting's range, so a file holding 90 for a setting that stops at 60 saved 60.
                Object saved = entry.getKey().savedValue();
                Object recorded = entry.getValue();
                if (!Objects.equals(saved, recorded)
                        && !Objects.equals(saved, entry.getKey().savedFormOf(recorded))) return false;
            }
            return labSettingsMatch(expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static AtomicFile readableUndoFile(Context context) {
        AtomicFile current = currentUndoFile(context);
        if (hasAtomicFile(current)) return current;
        AtomicFile legacy = legacyUndoFile(context);
        return hasAtomicFile(legacy) ? legacy : current;
    }

    private static void writeUndo(Context context, String text) throws IOException {
        AtomicFile file = currentUndoFile(context);
        var output = file.startWrite();
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            file.failWrite(output);
            throw error;
        }
        if (!text.equals(read(file.openRead()))) throw new IOException("Could not verify the undo copy");
        legacyUndoFile(context).delete();
    }

    private static AtomicFile currentUndoFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "metra-settings-undo.json"));
    }

    private static AtomicFile legacyUndoFile(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "hushfeed-settings-undo.json"));
    }

    private static boolean hasAtomicFile(AtomicFile file) {
        File base = file.getBaseFile();
        return base.isFile() || new File(base.getPath() + ".bak").isFile();
    }

    private static Snapshot decode(String text, boolean holdRuleLists) throws JSONException, IOException {
        return decode(text, holdRuleLists, holdRuleLists);
    }

    /** With pickedFile, a file someone chose, whose network proxy is held back. */
    private static Snapshot decode(String text, boolean holdRuleLists, boolean pickedFile)
            throws JSONException, IOException {
        if (text == null || text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new RejectedBackup(Reason.SIZE, "Invalid backup size");
        }
        Settings.REGION_SPOOF.get();
        JSONObject root;
        try {
            String input = text.trim();
            // Metra's original clipboard export omitted the enclosing braces.
            root = SettingsJson.parseObject(input.startsWith("\"") ? "{" + input + "}" : input);
        } catch (JSONException | IOException error) {
            // A download that stopped halfway is valid UTF-8 and valid nothing else. Letting it
            // out as a bare parse failure reported it as the same unexplained rejection as a
            // photograph, which tells the person holding the file nothing about retrying. The
            // reader reports a document that simply ends as an IOException, not a JSONException,
            // so both are caught here. Oversized input was already refused as SIZE above.
            throw new RejectedBackup(Reason.DAMAGED, "Settings backup is not readable as JSON");
        }
        if (!root.has("format")) root = legacyMetraBackup(root);
        String format = root.optString("format");
        if (!(FORMAT.equals(format) || LEGACY_FORMAT.equals(format))) {
            throw new RejectedBackup(Reason.FORMAT, "Not a Metra settings backup");
        }
        // Absent is the first schema: a file written before the key existed still reads. Newer
        // is refused by both numbers, since applying a shape this build never saw field by field
        // would land half a backup with no message. Not a number is not a schema at all.
        Object declared = root.opt("schema");
        int schema = 1;
        if (declared != null) {
            if (!(declared instanceof Number)) {
                schema = -1;
            } else {
                double numeric = ((Number) declared).doubleValue();
                schema = Double.isFinite(numeric)
                        && numeric == Math.rint(numeric)
                        && numeric >= Integer.MIN_VALUE
                        && numeric <= Integer.MAX_VALUE
                        ? (int) numeric
                        : -1;
            }
        }
        if (schema < 1 || schema > SCHEMA) {
            throw new RejectedBackup(Reason.SCHEMA, "Settings backup schema " + declared
                    + " is not one this build reads (it reads up to " + SCHEMA + ")");
        }
        // The target belongs to the Lab rules, which name gates in one TikTok build. It used to
        // refuse the whole file, so the day this project retargets, every backup anyone holds
        // becomes unrestorable, settings included, for a reason that only concerns the Lab. The
        // settings half is version independent and is restored either way. The Lab half applies
        // on its own build, and from another build a catalog was generated from: parseSettings
        // then keeps each rule whose gate both catalogs carry identically and brings the rest
        // back turned off. From a build the catalog no longer has (the bundle declares only the
        // newest, so the one a user moves from is usually gone), the running build's catalog
        // alone decides, as it does when the build changes under the Lab's own store
        // (FeatureGateCatalog.compatibleRuleIds). The Lab half is dropped, and the caller says
        // so, only when the running build has no catalog either or the Lab block names no build.
        String target = root.optString("target");
        JSONObject values = root.getJSONObject("settings"), lab = root.getJSONObject("lab");
        boolean labApplies = FeatureGateLabStore.targetVersion().equals(target)
                || app.morphe.extension.tiktok.featuregatelab.FeatureGateCatalog.hasCatalogFor(target)
                || (app.morphe.extension.tiktok.featuregatelab.FeatureGateCatalog.hasCatalogFor(
                        FeatureGateLabStore.targetVersion())
                && lab.opt("tiktok_version") instanceof String
                && !lab.optString("tiktok_version").isEmpty());
        JSONArray required = root.getJSONArray("setting_keys");
        java.util.Set<String> keys = new java.util.HashSet<>();
        if (required.length() == 0 || required.length() != values.length()) {
            throw new RejectedBackup(Reason.INCOMPLETE, "Incomplete settings backup");
        }
        for (int i = 0; i < required.length(); i++) {
            Object key = required.get(i);
            if (!(key instanceof String) || !keys.add((String) key) || !values.has((String) key)) {
                throw new RejectedBackup(Reason.INCOMPLETE, "Incomplete settings backup");
            }
        }
        // Older structured backups can carry Metra's former key names too.
        Map<String, Object> stored = new LinkedHashMap<>();
        for (java.util.Iterator<String> names = values.keys(); names.hasNext();) {
            String key = names.next();
            stored.put(key, values.get(key));
        }
        try {
            for (Map.Entry<String, Object> migration : MetraLegacySettings.migratedValues(stored).entrySet()) {
                values.put(migration.getKey(), migration.getValue());
            }
        } catch (IllegalArgumentException malformed) {
            throw new RejectedBackup(Reason.VALUE, malformed.getMessage());
        }
        keys.removeAll(MetraLegacySettings.legacyKeys());
        Map<Setting<?>, Object> updates = new LinkedHashMap<>();
        int absent = 0;
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (!included(setting)) continue;
            keys.remove(setting.key);
            if (values.has(setting.key)) {
                Object converted = convert(setting.defaultValue, values.get(setting.key), setting.key);
                if (holdRuleLists && feedRuleProblem(setting, converted) != null) {
                    throw new RejectedBackup(Reason.RULE_LIST,
                            "Feed rule list exceeds its storage limit");
                }
                updates.put(setting, converted);
                continue;
            }
            // A backup is a set of values to apply, not a picture of the whole app. A file
            // written before a setting existed says nothing about that setting, and taking the
            // silence as "put it back to its default" quietly undid whatever the device held.
            updates.put(setting, setting.savedValue());
            absent++;
        }
        ProxyHold proxyHold = pickedFile ? keepTheProxyWhereTheDeviceHasIt(updates) : ProxyHold.NONE;
        if (!holdRuleLists && root.has("local_budget_pending")) {
            Object pending = root.get("local_budget_pending");
            if (!(pending instanceof String)) throw new IOException("Invalid delayed budget journal");
            updates.put(Settings.SESSION_BUDGET_PENDING, pending);
        }
        absent -= migrateDownloadPath(values, updates);
        List<DownloadDestination.Kind> keptFolders = holdRuleLists
                ? keepUsableFolders(updates) : Collections.<DownloadDestination.Kind>emptyList();
        if (!labApplies) {
            // Leaving the Lab exactly as it is, rather than clearing it: the backup says nothing
            // about this TikTok build, so it is not evidence that the user wanted no rules.
            Snapshot snapshot = new Snapshot(updates, null, false, false, false, absent);
            snapshot.deviceState = !holdRuleLists;
            snapshot.skipped = keys.size();
            snapshot.keptFolders = keptFolders;
            snapshot.proxyHold = proxyHold;
            return snapshot;
        }
        // Refused by name before the rules are read, so the reader hears what was wrong with
        // the file rather than the unexplained rejection a parse failure would give.
        JSONArray labRules = lab.optJSONArray("rules");
        if (labRules != null && labRules.length() > FeatureGateLabStore.MAX_RULES) {
            throw new RejectedBackup(Reason.LAB_RULES, "Too many Feature Gate Lab rules");
        }
        Snapshot snapshot = new Snapshot(updates, FeatureGateLabStore.parseSettings(lab),
                lab.getBoolean("master"), lab.getBoolean("acknowledged"), true, absent);
        snapshot.deviceState = !holdRuleLists;
        snapshot.skipped = keys.size();
        snapshot.keptFolders = keptFolders;
        snapshot.proxyHold = proxyHold;
        return snapshot;
    }

    /** Upgrade only recognizable old setting exports; all normal validation still follows. */
    private static JSONObject legacyMetraBackup(JSONObject raw) throws JSONException {
        java.util.Set<String> allowed = MetraLegacySettings.legacyKeys();
        for (Setting<?> setting : Setting.allLoadedSettings()) if (included(setting)) allowed.add(setting.key);
        JSONObject values = new JSONObject();
        for (java.util.Iterator<String> names = raw.keys(); names.hasNext();) {
            String name = names.next();
            String key = allowed.contains(name) ? name : "morphe_" + name;
            if (!allowed.contains(key) || values.has(key)) {
                throw new RejectedBackup(Reason.FORMAT, "Unrecognized legacy Metra settings export");
            }
            values.put(key, raw.get(name));
        }
        if (values.length() == 0) throw new RejectedBackup(Reason.INCOMPLETE, "Empty legacy settings export");
        // An old export contains explicit choices, not a request to reset newly added controls.
        JSONArray keys = new JSONArray();
        for (java.util.Iterator<String> names = values.keys(); names.hasNext();) keys.put(names.next());
        return new JSONObject().put("format", FORMAT).put("schema", SCHEMA).put("target", "")
                .put("settings", values).put("setting_keys", keys).put("lab", new JSONObject());
    }

    /**
     * A download folder that can't hold its kind keeps the one the device has. Saving would have
     * gone to DCIM/TikTok instead without a word (DownloadDestination.resolve), so a file naming
     * Pictures for videos, which a backup from before the folder split does for all three, looked
     * restored while videos went somewhere else. Only a file from outside is checked: the
     * device's own copies (the undo copy, the journal) come back exactly as they were.
     */
    private static List<DownloadDestination.Kind> keepUsableFolders(Map<Setting<?>, Object> updates) {
        List<DownloadDestination.Kind> kept = new ArrayList<>();
        keepUsableFolder(updates, Settings.DOWNLOAD_VIDEO_PATH, DownloadDestination.Kind.VIDEO, kept);
        keepUsableFolder(updates, Settings.DOWNLOAD_PHOTO_PATH, DownloadDestination.Kind.PHOTO, kept);
        keepUsableFolder(updates, Settings.DOWNLOAD_STICKER_PATH, DownloadDestination.Kind.STICKER, kept);
        return kept;
    }

    private static void keepUsableFolder(Map<Setting<?>, Object> updates, Setting<?> setting,
            DownloadDestination.Kind kind, List<DownloadDestination.Kind> kept) {
        Object value = updates.get(setting);
        if (!(value instanceof String)) return;
        try {
            DownloadDestination.validate((String) value, kind);
        } catch (IllegalArgumentException unusable) {
            updates.put(setting, setting.savedValue());
            kept.add(kind);
        }
    }

    /** The download folders restoring this file keeps as the device has them, because the file's can't hold their kind. */
    public static List<DownloadDestination.Kind> foldersKept(String text) {
        try {
            return parse(text).keptFolders;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static String feedRuleProblem(Setting<?> setting, Object value) {
        if (!(value instanceof String)) return null;
        String text = (String) value;
        if (setting == Settings.BLOCKED_CAPTION_WORDS || setting == Settings.LIVE_HIDDEN_CATEGORIES) {
            return FeedRuleLimits.captionProblem(text);
        }
        if (setting == Settings.BLOCKED_CREATORS || setting == Settings.LOCAL_HIDDEN_CREATORS
                || setting == Settings.CREATOR_FILTER_EXCEPTIONS) {
            return FeedRuleLimits.creatorProblem(text);
        }
        return null;
    }

    /**
     * Applies the one download folder a pre-split backup carries to the three that replaced it.
     *
     * <p>A file from before the split holds {@code down_path} and none of the three keys. The
     * one-shot migration in {@link Settings} cannot help: its flag is already true on the device
     * and was never in a backup, so the three would take their default and a custom folder would
     * be lost with nothing said.
     *
     * @return how many of the three this filled in, so they are not also counted as missing.
     */
    private static int migrateDownloadPath(JSONObject values, Map<Setting<?>, Object> updates) {
        if (!values.has(Settings.DOWNLOAD_PATH.key)) return 0;
        Object legacy = updates.get(Settings.DOWNLOAD_PATH);
        if (!(legacy instanceof String)) return 0;
        int filled = 0;
        for (Setting<?> destination : new Setting<?>[] {
                Settings.DOWNLOAD_VIDEO_PATH, Settings.DOWNLOAD_PHOTO_PATH,
                Settings.DOWNLOAD_STICKER_PATH }) {
            if (values.has(destination.key) || !updates.containsKey(destination)) continue;
            updates.put(destination, legacy);
            filled++;
        }
        return filled;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object convert(Object fallback, Object value, String key) throws JSONException {
        try {
            if (fallback instanceof Boolean && value instanceof Boolean) return value;
            if (fallback instanceof String && value instanceof String) return value;
            if (fallback instanceof Enum<?> && value instanceof String) {
                return Enum.valueOf(((Enum) fallback).getDeclaringClass(), (String) value);
            }
            if (value instanceof Number) {
                BigDecimal number = new BigDecimal(value.toString());
                if (fallback instanceof Integer) return number.intValueExact();
                if (fallback instanceof Long) return number.longValueExact();
                if (fallback instanceof Float && Float.isFinite(number.floatValue())) return number.floatValue();
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new RejectedBackup(Reason.VALUE, "Invalid value for " + key);
        }
        throw new RejectedBackup(Reason.VALUE, "Invalid value for " + key);
    }

    static final class Snapshot {
        final Map<Setting<?>, Object> values;
        final List<FeatureGateLabStore.Rule> rules;
        final boolean master, acknowledged;
        /**
         * False when the backup was written against a TikTok build the Lab has no catalog for,
         * so its rules can't be checked against this one and the Lab is left alone.
         */
        final boolean labIncluded;
        /** Included settings the file did not carry, kept at whatever the device already held. */
        final int absent;
        /** Unrecognized or device-local keys carried in the portable settings inventory. */
        int skipped;

        /**
         * Read as the device's own state (an undo copy, the snapshot a restore replaces, a journal
         * record), so writing it back keeps a value a newer bound would refuse.
         */
        boolean deviceState;

        /** Download folders the file named that can't hold their kind, kept as the device had them. */
        List<DownloadDestination.Kind> keptFolders = Collections.emptyList();

        /** Whether a picked file's network proxy was held back, which the restore's record needs. */
        ProxyHold proxyHold = ProxyHold.NONE;

        Snapshot(Map<Setting<?>, Object> values, List<FeatureGateLabStore.Rule> rules,
                boolean master, boolean acknowledged, boolean labIncluded, int absent) {
            this.values = values; this.rules = rules; this.master = master;
            this.acknowledged = acknowledged; this.labIncluded = labIncluded; this.absent = absent;
        }

        Snapshot(Map<Setting<?>, Object> values, List<FeatureGateLabStore.Rule> rules,
                boolean master, boolean acknowledged, boolean labIncluded) {
            this(values, rules, master, acknowledged, labIncluded, 0);
        }

        Snapshot(Map<Setting<?>, Object> values, List<FeatureGateLabStore.Rule> rules, boolean master, boolean acknowledged) {
            this(values, rules, master, acknowledged, true, 0);
        }
    }
}
