/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit trial only. No settings, UI callbacks, ordinary logger locks or shared executors. */
public final class RecoveryCapture {
    static final long LEASE_MS = 30 * 60_000L, EXPORT_LEASE_MS = 24 * 60 * 60_000L;
    static final int MAX_STARTS = 3, MAX_CHECKS = MAX_STARTS + 1, MAX_INCIDENTS = 3;
    static final int MAX_STORED_BUILD = 8 * 1024, MAX_STORED_ACTIVATION = 12 * 1024;
    static final long TRACE_TIMEOUT_MS = 10_000;
    private static final AtomicBoolean STARTED = new AtomicBoolean(), EXPORTING = new AtomicBoolean();
    private static final AtomicBoolean PROBE_BUSY = new AtomicBoolean();
    private static final AtomicInteger ACTIVATION_MASK = new AtomicInteger();
    private static final AtomicBoolean EXPORT_REQUESTED = new AtomicBoolean();
    private static volatile RecoveryStore store;
    private static volatile String report = "";
    private static final ThreadPoolExecutor PROBE = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread t = new Thread(runnable, "MetraRecoveryProbe"); t.setDaemon(true); return t;
            });

    private RecoveryCapture() {}

    /** Patched by Diagnostic tools only when its explicit recovery-trial option is selected. */
    public static String trialId() { return ""; }
    public static String controlArm() { return ""; }
    private static String controlReceiptKey = "";

    static boolean recordControlReceipt(RecoveryStore data, String trial, int pid, long sessionAt,
            long observedAt, String arm, String profile, String observation) throws Exception {
        synchronized (RecoveryStore.LOCK) {
            JSONObject state = data.read();
            if (!trial.equals(state.optString("trial")) || state.optLong("cleared_through") >= sessionAt) return false;
            JSONArray sessions = state.optJSONArray("sessions");
            JSONObject session = null;
            if (sessions != null) for (int i = 0; i < sessions.length(); i++) {
                JSONObject s = sessions.getJSONObject(i);
                if (s.optInt("pid") == pid && s.optLong("at") == sessionAt) session = s;
            }
            if (session == null) return false;
            // Publish bounded phase changes and the provisional exposure threshold, not a file per sample/minute.
            String key = trial + ":" + pid + ":" + sessionAt + ":" + observation.split(" resumed_s=", 2)[0];
            String seconds = observation.contains(" resumed_s=") ? observation.split(" resumed_s=", 2)[1].split(" ", 2)[0] : "0";
            boolean exposed = false;
            try { exposed = Long.parseLong(seconds) >= 1200; } catch (NumberFormatException unknown) { /* Unknown is not stable. */ }
            key += ":exposure20=" + exposed;
            if (key.equals(controlReceiptKey)) return false;
            String text = "MORPHE DIAGNOSTIC REPORT\nschema: 1\nsource: diagnostic_control_observation"
                    + "\nThis is a startup/observation receipt, not a crash or proof of stability."
                    + "\ntrial: " + trial + "\npid: " + pid + "\nsession_started_ms: " + sessionAt
                    + "\nobserved_ms: " + observedAt + "\ndeclared_arm: " + arm + "\n" + profile
                    + "\n" + observation + "\nNative return codes do not identify a cause or independently prove heap writes."
                    + "\n\n[SESSION BUILD]\n";
            String build = session.optString("build");
            int remaining = Math.max(0, 6000 - text.length());
            if (build.length() > remaining) build = build.substring(0, Math.max(0, remaining - 22)) + "\n[build truncated]\n";
            text += build;
            saveActivation(data, trial + "-control-" + pid + "-" + sessionAt, fitControlActivation(text), state.optInt("generation"));
            controlReceiptKey = key;
            return true;
        }
    }
    private static void saveControlObservation(Context context, int pid, long sessionAt) {
        if (controlArm().isEmpty()) return;
        try {
            RecoveryStore data = store;
            if (data != null && recordControlReceipt(data, trialId(), pid, sessionAt, System.currentTimeMillis(),
                    controlArm(), ControlObservation.profile(), PolicyObservation.state() + " " + ControlObservation.state())) {
                refresh(data); publish(context, data);
            }
        } catch (Throwable unavailable) { /* Retry within the existing sampling lease. */ }
    }

    /** Selected only by the explicit, exact-target patch option. */
    public static boolean softReferenceTrialEnabled() { return false; }
    private static final AtomicInteger SOFT_V1 = new AtomicInteger(), SOFT_V2 = new AtomicInteger();
    public static void softReferenceV1Bypassed() { softReferenceBypassed(SOFT_V1); }
    public static void softReferenceV2Bypassed() { softReferenceBypassed(SOFT_V2); }
    private static void softReferenceBypassed(AtomicInteger counter) {
        // No storage, logger, main-queue work or native calls on the intercepted startup route.
        int value;
        do { value = counter.get(); if (value >= 1_000_000) return; }
        while (!counter.compareAndSet(value, value + 1));
        if (value == 0) RecoveryTimeline.requestReading();
    }
    static String softReferenceState() {
        return softReferenceState(SOFT_V1.get(), SOFT_V2.get());
    }
    private static String softReferenceState(int v1, int v2) {
        return "softref_selected=" + softReferenceTrialEnabled()
                + " softref_v1_bypassed=" + v1 + " softref_v2_bypassed=" + v2;
    }

    /** Called only by the existing sampler, after a reading. Never on the intercepted route. */
    static void saveSoftReferenceActivation(Context context, int pid, long sessionAt) {
        if (!softReferenceTrialEnabled()) return;
        try {
            RecoveryStore data = store;
            if (data == null || !recordSoftReferenceActivation(data, trialId(), pid, sessionAt,
                    System.currentTimeMillis())) return;
            refresh(data); publish(context, data);
        } catch (Throwable unavailable) { /* Retry from a later reading within the existing lease. */ }
    }

    /** At most two successful updates: the first encounter, then the other installer's first hit. */
    static boolean recordSoftReferenceActivation(RecoveryStore data, String trial, int pid,
            long sessionAt, long observedAt) throws Exception {
        // Only sampler work takes this lock. It serializes the mask, receipt replacement and Clear.
        synchronized (RecoveryStore.LOCK) {
            int v1 = SOFT_V1.get(), v2 = SOFT_V2.get();
            int mask = (v1 > 0 ? 1 : 0) | (v2 > 0 ? 2 : 0);
            if ((mask & ~ACTIVATION_MASK.get()) == 0) return false;
            JSONObject state = data.read();
            if (!trial.equals(state.optString("trial")) || state.optLong("cleared_through") >= sessionAt)
                return false;
            JSONArray sessions = state.optJSONArray("sessions");
            JSONObject session = null;
            if (sessions != null) for (int i = 0; i < sessions.length(); i++) {
                JSONObject value = sessions.getJSONObject(i);
                if (value.optInt("pid") == pid && value.optLong("at") == sessionAt) session = value;
            }
            if (session == null) return false;
            String text = "MORPHE DIAGNOSTIC REPORT\nschema: 1\nsource: soft_reference_trial_activation"
                    + "\nThis records an installer bypass, not a crash or proof of a fix."
                    + "\ntrial: " + trial + "\npid: " + pid + "\nsession_started_ms: " + sessionAt
                    + "\nobserved_ms: " + observedAt
                    + "\n" + softReferenceState(v1, v2) + "\n\n[SESSION BUILD]\n" + session.optString("build");
            saveActivation(data, trial + "-" + pid + "-" + sessionAt, text, state.optInt("generation"));
            ACTIVATION_MASK.set(mask);
            return true;
        }
    }

    /** Separate from failures, so an activation receipt cannot evict an unsent failure. */
    static void saveActivation(RecoveryStore data, String id, String text, int generation) throws Exception {
        if (text.length() > 6000 || storedBytes(text) > MAX_STORED_ACTIVATION)
            throw new java.io.IOException("activation too long");
        String hash = RecoveryPublisher.sha(text);
        data.update(state -> {
            if (state.optInt("generation") != generation) return;
            JSONArray previous = state.optJSONArray("activations");
            JSONArray next = new JSONArray();
            next.put(new JSONObject().put("id", id).put("report", text).put("hash", hash));
            if (previous != null) for (int i = 0; i < previous.length() && next.length() < MAX_STARTS; i++) {
                JSONObject item = previous.getJSONObject(i);
                if (!id.equals(item.optString("id"))) next.put(item);
            }
            state.put("activations", next);
        });
    }

    public static void onContext(Context supplied) {
        try {
            String trial = trialId();
            if (trial.isEmpty() || supplied == null || Build.VERSION.SDK_INT < 30) return;
            // Repeated context hooks can supply the Application before first Activity resume,
            // even when attachBaseContext's early ContextImpl did not expose it yet.
            if (!controlArm().isEmpty() && supplied.getPackageName().equals(Application.getProcessName())) {
                Context app = supplied.getApplicationContext();
                if (app instanceof Application) ControlObservation.watch((Application) app);
            }
            start(supplied, trial, new AndroidSource());
        } catch (Throwable unavailable) { /* Never interrupt the host's startup. */ }
    }

    interface OpenTrace { InputStream open() throws Exception; }
    static final class Exit {
        final String process, description; final int pid, reason, importance, status;
        final long at, pssKb, rssKb; final OpenTrace trace;
        Exit(String process, int pid, int reason, long at, OpenTrace trace) {
            this(process, pid, reason, at, trace, null, -1, -1, -1, -1);
        }
        /** What Android recorded about the death beside the trace: its own wording and the memory. */
        Exit(String process, int pid, int reason, long at, OpenTrace trace, String description,
                long pssKb, long rssKb, int importance, int status) {
            this.process = process; this.pid = pid; this.reason = reason; this.at = at; this.trace = trace;
            this.description = description; this.pssKb = pssKb; this.rssKb = rssKb;
            this.importance = importance; this.status = status;
        }
    }
    interface Source {
        long now(); int pid(); boolean main(Context context);
        default long elapsed() { return now(); }
        default long processStartedElapsed() { return -1; }
        default String capturePreference(Context context) { return "unknown"; }
        List<Exit> history(Context context) throws Exception;
        String build(Context context);
        /** Starts the readings for this session, until the lease ends on the elapsed clock. */
        default void timeline(Context context, int pid, long sessionAt, long untilElapsed) {}
    }

    @TargetApi(30)
    private static final class AndroidSource implements Source {
        public long now() { return System.currentTimeMillis(); }
        public long elapsed() { return SystemClock.elapsedRealtime(); }
        public int pid() { return Process.myPid(); }
        public long processStartedElapsed() { return Process.getStartElapsedRealtime(); }
        public String capturePreference(Context context) { return CrashEvidence.capturePreference(context); }
        public boolean main(Context c) { return c.getPackageName().equals(Application.getProcessName()); }
        public String build(Context context) {
            return "android_sdk: " + Build.VERSION.SDK_INT + "\nandroid_release: "
                    + Build.VERSION.RELEASE.replaceAll("[^A-Za-z0-9._-]", "_")
                    + "\nsoft_reference_trial: " + softReferenceTrialEnabled()
                    + (controlArm().isEmpty() ? "" : "\ndeclared_control_arm: " + controlArm() + "\n" + ControlObservation.profile())
                    + "\n" + device(context)
                    + BuildDetails.section(BuildDetails.readMetadata(context));
        }
        public List<Exit> history(Context c) {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return java.util.Collections.emptyList();
            List<ApplicationExitInfo> history = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 16);
            List<Exit> result = new ArrayList<>();
            if (history != null) for (ApplicationExitInfo e : history) result.add(new Exit(
                    e.getProcessName(), e.getPid(), e.getReason(), e.getTimestamp(), e::getTraceInputStream,
                    e.getDescription(), e.getPss(), e.getRss(), e.getImportance(), e.getStatus()));
            return result;
        }
        public void timeline(Context context, int pid, long sessionAt, long untilElapsed) {
            if (!controlArm().isEmpty()) {
                Context app = context.getApplicationContext();
                if (app instanceof Application) ControlObservation.watch((Application) app);
                saveControlObservation(context, pid, sessionAt);
            }
            // The lease's own clock, which keeps counting while the phone sleeps (#215 review).
            RecoveryTimeline.start(context.getFilesDir(), pid, sessionAt, untilElapsed, context.getClassLoader(),
                    () -> { saveSoftReferenceActivation(context, pid, sessionAt); saveControlObservation(context, pid, sessionAt); });
        }
    }

    /**
     * The phone, its ROM and the runtime's memory limits. A ROM sets the heap ceiling a
     * large-heap app such as TikTok gets, and Android's runtime module can differ by ROM, so
     * these say which phone and which limits a report came from (#215).
     */
    @TargetApi(30)
    static String device(Context context) {
        StringBuilder out = new StringBuilder();
        line(out, "device_manufacturer", Build.MANUFACTURER);
        line(out, "device_model", Build.MODEL);
        line(out, "device_name", Build.DEVICE);
        line(out, "rom_display", Build.DISPLAY);
        line(out, "rom_fingerprint", Build.FINGERPRINT);
        line(out, "rom_security_patch", Build.VERSION.SECURITY_PATCH);
        out.append("art_module_version: ").append(artModule(context)).append('\n');
        out.append("heap_max_mb: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append('\n');
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(memory);
                out.append("memory_class_mb: ").append(am.getMemoryClass())
                        .append("\nlarge_memory_class_mb: ").append(am.getLargeMemoryClass())
                        .append("\nlow_ram_device: ").append(am.isLowRamDevice())
                        .append("\ntotal_ram_mb: ").append(memory.totalMem / (1024 * 1024))
                        .append("\nlow_memory_threshold_mb: ").append(memory.threshold / (1024 * 1024)).append('\n');
            }
        } catch (Throwable unavailable) { out.append("memory_class_mb: unavailable\n"); }
        return out.toString();
    }

    private static void line(StringBuilder out, String name, String value) {
        String safe = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._:/,+-]", "_");
        out.append(name).append(": ").append(safe.length() > 160 ? safe.substring(0, 160) : safe).append('\n');
    }

    /** The version of Android's updatable runtime (ART) module, Google's or the ROM's own. */
    @TargetApi(30)
    private static String artModule(Context context) {
        for (String name : new String[] {"com.google.android.art", "com.android.art"}) {
            try {
                android.content.pm.PackageInfo info = context.getPackageManager()
                        .getPackageInfo(name, android.content.pm.PackageManager.MATCH_APEX);
                return name + "@" + info.getLongVersionCode();
            } catch (Throwable absent) { /* Try the other name. */ }
        }
        return "unknown";
    }

    static void start(Context supplied, String trial, Source source) {
        if (!trial.matches("[A-Za-z0-9._-]{1,64}") || !STARTED.compareAndSet(false, true)) return;
        Context app = supplied.getApplicationContext();
        if (app == null) app = supplied;
        final Context context = app;
        Thread coordinator = new Thread(() -> {
            try {
                if (!source.main(context)) return;
                RecoveryStore data = new RecoveryStore(context); store = data;
                try { recover(context, trial, source, data); }
                catch (RecoveryStore.InvalidState invalid) { data.quarantineUnreadable(trial); refresh(data); }
                publish(context, data);
            } catch (Throwable unavailable) {
                // This path must not invoke the ordinary logger or recursively capture itself.
            }
        }, "MetraRecoveryCapture");
        coordinator.setDaemon(true); coordinator.start();
    }

    interface Job<T> { T run() throws Exception; }
    static <T> T probe(Job<T> job, long millis) throws Exception {
        // A timed-out Binder/stream operation may keep running. Never queue more behind it.
        if (!PROBE_BUSY.compareAndSet(false, true)) throw new ProbeBusy();
        Future<T> future;
        try { future = PROBE.submit(() -> { try { return job.run(); } finally { PROBE_BUSY.set(false); } }); }
        catch (RuntimeException rejected) { PROBE_BUSY.set(false); throw rejected; }
        return future.get(millis, TimeUnit.MILLISECONDS);
    }
    static final class ProbeBusy extends Exception {}

    static void recover(Context context, String trial, Source source, RecoveryStore data) throws Exception {
        recover(context, trial, source, data, TRACE_TIMEOUT_MS);
    }

    static void recover(Context context, String trial, Source source, RecoveryStore data, long traceTimeout) throws Exception {
        long now = source.now();
        long elapsed = source.elapsed(), bootEpoch = now - elapsed;
        JSONObject before = data.read();
        if (before.optBoolean("capture_disabled") && (before.optString("trial").isEmpty()
                || trial.equals(before.optString("trial")))) { ControlObservation.stopWatching(); refresh(data); return; }
        if (!trial.equals(before.optString("trial"))) {
            data.update(s -> {
                // A new trial retains earlier unsent incidents but gets its own finite lease.
                s.put("trial", trial).put("started", now).put("started_elapsed", elapsed)
                        .put("boot_epoch", bootEpoch).put("export_until", now + EXPORT_LEASE_MS)
                        .put("starts", 0).put("checks", 0);
                s.put("sessions", new JSONArray());
                s.remove("cleared_through");
                s.remove("capture_disabled");
            });
            before = data.read();
        }
        long age = now - before.optLong("started");
        boolean clockMatches = Math.abs(bootEpoch - before.optLong("boot_epoch")) <= 10_000
                && elapsed >= before.optLong("started_elapsed");
        if (clockMatches) age = elapsed - before.optLong("started_elapsed");
        JSONArray previous = before.optJSONArray("sessions");
        if (previous == null) previous = new JSONArray();
        final int generation = before.optInt("generation");
        if (!clockMatches || age < 0 || age > LEASE_MS) {
            ControlObservation.stopWatching();
            if (previous.length() > 0 && before.optLong("cleared_through") == 0) {
                String status = !clockMatches || age < 0 ? "boot_or_clock_changed" : "collection_window_expired";
                JSONObject last = previous.getJSONObject(previous.length() - 1);
                availability(data, trial + "-" + status, status, "not_queried", now, generation,
                        "previous_pid: " + last.optInt("pid") + "\n[PRIOR SESSION BUILD]\n" + last.optString("build"));
            }
            refresh(data); return;
        }
        // A fourth, read-only check can recover the third tracked start's failure.
        boolean collect = before.optInt("checks", before.optInt("starts")) < MAX_CHECKS;
        if (!collect || before.optInt("starts") >= MAX_STARTS) ControlObservation.stopWatching();
        if (collect) {
            final JSONArray sessions = previous;
            final String collectorBuild = safeBuild(source.build(context));
            final long processStarted=source.processStartedElapsed();
            final String capturePreference=source.capturePreference(context);
            final boolean[] registered = {false};
            data.update(s -> {
                s.put("checks", s.optInt("checks", s.optInt("starts")) + 1);
                if (s.optInt("starts") >= MAX_STARTS) return;
                s.put("starts", s.optInt("starts") + 1);
                JSONObject session = new JSONObject().put("pid", source.pid()).put("at", now)
                        .put("build", collectorBuild).put("process_started_elapsed_ms",processStarted)
                        .put("capture_preference_at_start",capturePreference);
                JSONArray next = new JSONArray();
                for (int i = Math.max(0, sessions.length() - 3); i < sessions.length(); i++) next.put(sessions.get(i));
                next.put(session); s.put("sessions", next);
                registered[0] = true;
            });
            // Only a tracked session is read back later, so only it takes readings, and only for
            // what is left of the lease.
            if (registered[0]) {
                try { source.timeline(context, source.pid(), now, before.optLong("started_elapsed") + LEASE_MS); }
                catch (Throwable unavailable) { ControlObservation.stopWatching(); /* Trace recovery remains available. */ }
            }
            List<Exit> exits = null;
            String historyStatus = "available";
            try { exits = probe(() -> source.history(context), 1500); }
            catch (Exception unavailable) {
                historyStatus = unavailable instanceof ProbeBusy ? "busy" : "unavailable_or_timeout";
                final String status = historyStatus;
                data.update(s -> s.put("history_status", status));
            }
            int visited = 0, found = 0;
            if (exits != null) for (Exit exit : exits) {
                if (!context.getPackageName().equals(exit.process) || exit.at > now || exit.at < before.optLong("started")
                        || exit.at <= before.optLong("cleared_through")) continue;
                JSONObject session = matchingSession(previous, exit);
                if (session == null) continue;
                String id = trial + "-" + exit.at + "-" + exit.pid;
                JSONObject existing = find(data.read(), id);
                if (existing != null && !existing.optBoolean("retryable")) { found++; continue; }
                if (visited++ >= 4) continue;
                RecoveryTrace.Result trace;
                if (exit.reason == 5) trace = new RecoveryTrace.Result("native_binary_not_decoded", "");
                else try {
                    RecoveryTrace.Intake intake = probe(() -> {
                        try (InputStream in = exit.trace.open()) { return RecoveryTrace.readBounded(in); }
                    }, traceTimeout);
                    try { trace = RecoveryTrace.parse(intake, exit.pid); }
                    catch (RuntimeException | StackOverflowError parserFault) {
                        // A parser fault is ours, not Android's: it gets its own name, so it can't
                        // pass for a trace Android withheld, and isn't retried on the same bytes.
                        trace = new RecoveryTrace.Result("parse_error", "", "bytes="
                                + (intake.bytes == null ? 0 : intake.bytes.length) + " input_cut=" + intake.cut
                                + " fault=" + parserFault.getClass().getSimpleName());
                    }
                } catch (Exception unavailable) {
                    trace = new RecoveryTrace.Result(unavailable instanceof ProbeBusy ? "probe_busy"
                            : unavailable instanceof java.util.concurrent.TimeoutException ? "timeout" : "read_error", "");
                }
                boolean retryable = trace.status.equals("timeout") || trace.status.equals("probe_busy") || trace.status.equals("read_error");
                // Ordinary exits with no ANR evidence are not reported as errors.
                boolean confirmed = exit.reason == 6 || exit.reason == 4 || exit.reason == 5 || !trace.text.isEmpty();
                // A parser fault is kept even on an ordinary exit: it is the one record that says
                // the trace existed and the fault was ours.
                if (!confirmed && !retryable && existing == null && !trace.status.equals("parse_error")) continue;
                found++;
                // Readings from before the last clear stay cleared, even if a file outlived it.
                long clearedThrough = data.read().optLong("cleared_through");
                String incident = fitForStore(incident(context, id, exit, trace, session, collectorBuild, confirmed,
                        clearedThrough));
                JSONObject entry = new JSONObject().put("id", id).put("at", exit.at).put("report", incident)
                        .put("hash", RecoveryPublisher.sha(incident)).put("retryable", retryable);
                retain(data, entry, generation);
            }
            JSONArray retained = data.read().optJSONArray("incidents");
            if (found == 0 && previous.length() > 0 && (!controlArm().isEmpty() || retained == null || retained.length() == 0)
                    && previous.getJSONObject(previous.length() - 1).optLong("at") > before.optLong("cleared_through")) {
                JSONObject last = previous.getJSONObject(previous.length() - 1);
                String id = trial + "-status-" + last.optLong("at") + "-" + last.optInt("pid");
                availability(data, id, historyStatus.equals("available") ? "no_matching_failure_trace" : "history_" + historyStatus,
                        historyStatus, now, generation, "previous_pid: " + last.optInt("pid")
                                + "\nprevious_started_ms: " + last.optLong("at") + "\n[TRIAL BUILD]\n" + collectorBuild
                                + (controlArm().isEmpty() ? "" : controlStatusObservation(context, last, now, before.optLong("cleared_through"))));
            }
        }
        refresh(data);
    }

    private static String controlStatusObservation(Context context, JSONObject session, long now, long cleared) {
        String section = RecoveryTimeline.section(context.getFilesDir(), session.optInt("pid"), session.optLong("at"), now, cleared);
        StringBuilder observation = new StringBuilder();
        for (String line : section.split("\n")) if (line.startsWith("control_latest:") || line.startsWith("policy_latest:") || line.startsWith("softref_latest:"))
            observation.append(line).append('\n');
        if (observation.length() > 0) return "\n[PRIOR SESSION OBSERVATION RELATIVE TO STATUS TIME]\n" + observation;
        return "\nprior_control_observation: not_recorded\n";
    }

    static String fitControlActivation(String text) {
        String marker = "\n[PRIOR SESSION OBSERVATION RELATIVE TO STATUS TIME]";
        int at = text.indexOf(marker);
        String head = at < 0 ? text : text.substring(0, at), tail = at < 0 ? "" : text.substring(at);
        String omitted = "\n[build truncated]\n";
        int limit = Math.max(0, 6000 - tail.length() - omitted.length());
        boolean cut = head.length() > limit;
        if (cut) head = head.substring(0, limit);
        while (storedBytes(head + (cut ? omitted : "") + tail) > MAX_STORED_ACTIVATION && !head.isEmpty()) {
            head = head.substring(0, Math.max(0, head.length() - 128)); cut = true;
        }
        return head + (cut ? omitted : "") + tail;
    }

    private static void availability(RecoveryStore data, String id, String status, String history,
            long now, int generation, String details) throws Exception {
        if (find(data.read(), id) != null) return;
        String text = "MORPHE DIAGNOSTIC REPORT\nschema: 1\nsource: recovery_trial_status\nincident: " + id
                + "\ncapture_timestamp_ms: " + now + "\ncapture_status: " + status + "\nhistory_status: " + history
                + "\nThis is capture availability information, not a confirmed crash.\n" + details;
        if (!controlArm().isEmpty()) saveActivation(data, id, fitControlActivation(text), generation);
        else retain(data, new JSONObject().put("id", id).put("at", now).put("report", text)
                .put("hash", RecoveryPublisher.sha(text)), generation);
    }

    private static void retain(RecoveryStore data, JSONObject entry, int generation) throws Exception {
        data.update(s -> {
            if (s.optInt("generation") != generation) return;
            List<JSONObject> sorted = new ArrayList<>(); sorted.add(entry);
            JSONArray old = s.optJSONArray("incidents");
            if (old != null) for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!entry.getString("id").equals(item.optString("id"))) sorted.add(item);
            }
            java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < Math.min(sorted.size(), MAX_INCIDENTS); i++) kept.put(sorted.get(i));
            s.put("incidents", kept);
        });
    }

    /**
     * The most one incident may take in the store once JSON-escaped. MAX_OUTPUT bounds a report
     * in characters, but escaping can double that ("/" is written "\/"), and the store refuses a
     * whole write past its limit, which would lose the incident. Budget three incidents (180 KiB),
     * four escaped session builds (32 KiB), three escaped activation reports (36 KiB)
     * and the export journal under {@link RecoveryStore#MAX_BYTES}. The maximum-size store test
     * covers activations as well as failures. Trial metadata has its own small timeline allowance.
     */
    static final int MAX_STORED_INCIDENT = 60 * 1024;
    private static final String STORE_TRIM = "\n[report trimmed to fit private storage]\n";

    /** Drops whole lines from the end of the report until its escaped form fits. */
    static String fitForStore(String report) {
        if (storedBytes(report) <= MAX_STORED_INCIDENT) return report;
        String text = report;
        while (text.length() > 0 && storedBytes(text + STORE_TRIM) > MAX_STORED_INCIDENT) {
            int end = text.lastIndexOf('\n', Math.max(0, text.length() - 2 - (text.length() / 16)));
            text = end <= 0 ? "" : text.substring(0, end + 1);
        }
        return text + STORE_TRIM;
    }

    static int storedBytes(String value) {
        return JSONObject.quote(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static String safeBuild(String value) {
        String safe = DiagnosticRedactor.redact(value);
        String marker = "\n[build details truncated]";
        if (safe.length() > 4096) safe = safe.substring(0, 4096) + marker;
        if (storedBytes(safe) <= MAX_STORED_BUILD) return safe;
        // A character limit alone does not bound UTF-8 or JSON escaping in the shared store.
        do { safe = safe.substring(0, Math.max(0, safe.length() - 128)); }
        while (storedBytes(safe + marker) > MAX_STORED_BUILD);
        return safe + marker;
    }

    private static JSONObject matchingSession(JSONArray sessions, Exit exit) {
        JSONObject result = null;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject session = sessions.optJSONObject(i);
            JSONObject next = sessions.optJSONObject(i + 1);
            if (session != null && session.optInt("pid") == exit.pid && session.optLong("at") <= exit.at
                    && (next == null || exit.at <= next.optLong("at"))
                    && (result == null || result.optLong("at") < session.optLong("at"))) result = session;
        }
        return result;
    }

    private static String incident(Context context, String id, Exit exit, RecoveryTrace.Result trace,
            JSONObject session, String collectorBuild, boolean confirmed, long clearedThrough) {
        return "MORPHE DIAGNOSTIC REPORT\nschema: 1\nsource: "
                + (confirmed ? "recovery_trial" : "recovery_trial_status") + "\nincident: " + id
                + (confirmed ? "" : "\ncapture_status: trace_probe_" + trace.status
                        + "\nThis is capture availability information, not a confirmed crash.")
                + "\npackage: " + context.getPackageName() + "\npid: " + exit.pid + "\nexit_timestamp_ms: " + exit.at
                + "\nexit_reason: " + exit.reason + exitDetails(exit) + "\ntrace_status: " + trace.status
                + (trace.shape.isEmpty() ? "" : "\ntrace_shape: " + trace.shape)
                + "\n\n[FAILED SESSION BUILD]\n" + session.optString("build")
                // The same build twice is the usual case; the room goes to the stacks instead.
                + "\n[COLLECTOR BUILD]\n" + (collectorBuild.equals(session.optString("build"))
                        ? "same_as_failed_session\n" : collectorBuild)
                + "\n" + RecoveryTimeline.section(context.getFilesDir(), exit.pid, session.optLong("at"), exit.at,
                        clearedThrough)
                + "\n[RECOVERED THREADS]\n" + trace.text;
    }

    /** Android's own account of the death: its wording, and the process's memory when it died. */
    static String exitDetails(Exit exit) {
        StringBuilder out = new StringBuilder();
        if (exit.description != null) {
            String text = DiagnosticRedactor.redact(exit.description).replaceAll("[\\r\\n\\t]+", " ").trim();
            out.append("\nexit_description: ").append(text.length() > 300 ? text.substring(0, 300) + "..." : text);
        }
        if (exit.pssKb >= 0) out.append("\nexit_pss_kb: ").append(exit.pssKb);
        if (exit.rssKb >= 0) out.append("\nexit_rss_kb: ").append(exit.rssKb);
        if (exit.importance >= 0) out.append("\nexit_importance: ").append(exit.importance);
        if (exit.status >= 0) out.append("\nexit_status: ").append(exit.status);
        return out.toString();
    }

    static JSONObject find(JSONObject state, String id) {
        JSONArray items = state.optJSONArray("incidents");
        if (items != null) for (int i = 0; i < Math.min(items.length(), MAX_INCIDENTS); i++) {
            JSONObject e = items.optJSONObject(i); if (e != null && id.equals(e.optString("id"))) return e;
        }
        return null;
    }

    private static void publish(Context context, RecoveryStore data) {
        EXPORT_REQUESTED.set(true);
        if (!EXPORTING.compareAndSet(false, true)) return;
        Thread writer = new Thread(() -> {
            try {
                do {
                    EXPORT_REQUESTED.set(false);
                    RecoveryPublisher.publish(context, data);
                } while (EXPORT_REQUESTED.get());
            }
            catch (Throwable unavailable) { /* Durable incident survives for a later launch/manual export. */ }
            finally {
                EXPORTING.set(false);
                // An activation can arrive while an older report owns the exporter. Drain that
                // request without adding a second concurrent publisher or relying on a relaunch.
                if (EXPORT_REQUESTED.get()) publish(context, data);
            }
        }, "MetraRecoveryExport");
        writer.setDaemon(true); writer.start();
    }

    static String render(JSONObject state) throws Exception {
        JSONArray items = state.optJSONArray("incidents");
        StringBuilder out = new StringBuilder();
        JSONArray activations = state.optJSONArray("activations");
        if (activations != null) for (int i = 0; i < Math.min(activations.length(), MAX_STARTS); i++) {
            JSONObject item = activations.getJSONObject(i);
            String value = item.optString("report");
            if (value.length() > 6000 || !RecoveryPublisher.sha(value).equals(item.optString("hash"))) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(DiagnosticRedactor.redact(value));
        }
        if (items != null) for (int i = 0; i < Math.min(items.length(), MAX_INCIDENTS); i++) {
            JSONObject item = items.getJSONObject(i);
            String value = item.optString("report");
            if (value.length() > 64 * 1024 || !RecoveryPublisher.sha(value).equals(item.optString("hash"))) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(DiagnosticRedactor.redact(value));
        }
        return out.toString();
    }

    static void refresh(RecoveryStore data) throws Exception {
        synchronized (RecoveryStore.LOCK) { report = render(data.read()); }
    }

    /** Cached only: safe for the existing synchronous report/clipboard composer. */
    public static String cachedReport() { return report; }
    /** Called by the existing dedicated manual-file-export worker, never its UI callback. */
    public static void loadForExport(Context context) {
        try { RecoveryStore data = store; if (data == null) store = data = new RecoveryStore(context); refresh(data); }
        catch (Exception unavailable) { /* Existing diagnostics still export. */ }
    }
    public static String clear(Context context) throws Exception {
        RecoveryStore data = store;
        if (data == null && context == null) return "";
        if (data == null) store = data = new RecoveryStore(context);
        // Readings not yet in an incident go too, on disk and in the running sampler. Ones already
        // copied into an incident travel with it, so restoring the incidents restores them.
        RecoveryTimeline.clear(context == null ? null : context.getFilesDir());
        synchronized (RecoveryStore.LOCK) {
            ControlObservation.stopWatching();
            String saved = data.clear(); report = ""; return saved;
        }
    }
    public static void restore(Context context, String saved) throws Exception {
        if (saved == null || saved.isEmpty()) return;
        RecoveryStore data = store; if (data == null) store = data = new RecoveryStore(context);
        data.restoreIfEmpty(saved); refresh(data);
    }

    static void resetForTests() {
        STARTED.set(false); EXPORTING.set(false); store = null; report = "";
        // Process facts survive Clear; only a new process (or this test reset) zeros them.
        SOFT_V1.set(0); SOFT_V2.set(0); RecoveryTimeline.resetReadingRequest();
        ACTIVATION_MASK.set(0);
        EXPORT_REQUESTED.set(false);
        controlReceiptKey = ""; ControlObservation.resetForTests(); PolicyObservation.resetForTests();
    }
}
