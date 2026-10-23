/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.featuregatelab;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Records calls at the Lab's existing return boundaries, without changing their values. */
public final class FeatureGateLearnMode {
    private static final int LIMIT = 2048;
    private static final Map<String, String> latest = new LinkedHashMap<>();
    private static final Map<String, Read> reads = new LinkedHashMap<>();
    private static Map<String, String> baseline = new LinkedHashMap<>();
    private static volatile boolean recording;
    private static long startedAt;

    /**
     * So the screenshot of the report is the same twice.
     *
     * <p>The dialog renders the report JSON, and two of its fields are the times the recording
     * started and stopped, so a capture of an unchanged tree differed on every run and the
     * refreshScreenshots task could not hold that one picture. Same shape as
     * {@link app.morphe.extension.tiktok.wellbeing.SessionBudget}'s clock.
     */
    private static Clock clock = System::currentTimeMillis;

    interface Clock {
        long now();
    }

    static synchronized void setClockForTests(Clock replacement) {
        clock = replacement == null ? System::currentTimeMillis : replacement;
    }

    /**
     * Empties what the recorder remembers between runs.
     *
     * <p>{@link #latest} is what a recording takes its baseline from, and nothing in a test's
     * lifecycle empties it, so a gate another test read first turns up in this one's baseline
     * and the report says "read, was false" where a fresh process would say "new". The
     * screenshot of that report is a published file, so a class-order change or running one
     * case on its own moved it. It also saturates at {@value #LIMIT}, after which a new key is
     * refused, so seeding a baseline without clearing first is not enough.
     */
    static synchronized void resetForTests() {
        recording = false;
        latest.clear();
        baseline.clear();
        reads.clear();
        dropped = 0;
        lastReport = "";
        lastCount = 0;
        sessionState = false;
    }
    private static int dropped;
    private static String lastReport = "";
    private static int lastCount;

    /**
     * Whether a session has left anything for {@link #discardActiveSession} to clean up. Only
     * {@link #begin} creates that state, so with the Recorder off this is false and the monitor
     * below is never taken.
     */
    private static volatile boolean sessionState;

    /** Monitor entries, so the boundary test can assert a disabled Recorder takes no lock. */
    static volatile int monitorEntries;

    private FeatureGateLearnMode() {}

    /**
     * The Lab's boundaries call this on every gate read TikTok makes, on whatever thread makes
     * it, and the Recorder patch that gives it something to do ships off. Entering the class
     * monitor first put every AB, live settings and player config lookup in the app behind one
     * lock. The flag is read before the monitor now, and the monitor is taken only when there is
     * something to record, or once when a session is left over from the Recorder being switched
     * off. It is re-checked inside, because the switch can move between the two.
     */
    static void observe(String manager, String key, String type, Object value) {
        if (!SettingsStatus.featureGateRecorderEnabled) {
            if (sessionState) discardSession();
            return;
        }
        if (key == null || key.isEmpty()) return;
        record(manager, key, type, value);
    }

    private static synchronized void discardSession() {
        monitorEntries++;
        discardActiveSession();
    }

    private static synchronized void record(String manager, String key, String type, Object value) {
        monitorEntries++;
        if (!SettingsStatus.featureGateRecorderEnabled) {
            discardActiveSession();
            return;
        }
        String identity = manager + "\n" + key + "\n" + type;
        if (!latest.containsKey(identity) && latest.size() >= LIMIT) {
            // A full baseline must not prevent a new gate appearing in the current recording.
            if (!recording) return;
        }
        if (recording && !reads.containsKey(identity) && reads.size() >= LIMIT) {
            dropped++;
            return;
        }
        String text = SettingsManagerObservationRecorder.serializeText(value);
        if (text.length() > 8192) text = text.substring(0, 8192) + " [truncated]";
        if (recording) {
            Read read = reads.get(identity);
            if (read == null) {
                read = new Read(manager, key, type, baseline.get(identity), baseline.containsKey(identity));
                reads.put(identity, read);
            }
            read.after = text;
            read.calls++;
        }
        if (latest.containsKey(identity) || latest.size() < LIMIT) latest.put(identity, text);
    }

    public static synchronized void begin() {
        if (!SettingsStatus.featureGateRecorderEnabled) {
            discardActiveSession();
            return;
        }
        baseline = new LinkedHashMap<>(latest);
        reads.clear();
        dropped = 0;
        startedAt = clock.now();
        recording = true;
        sessionState = true;
    }

    public static boolean isRecording() { return recording; }
    public static synchronized int lastCandidateCount() { return lastCount; }
    public static synchronized String lastReport() { return lastReport; }

    public static synchronized void cancel() {
        discardActiveSession();
    }

    private static void discardActiveSession() {
        if (recording) {
            latest.clear();
            latest.putAll(baseline);
        }
        recording = false;
        baseline.clear();
        reads.clear();
        dropped = 0;
        sessionState = false;
    }

    public static synchronized String stopAndBuildReport() {
        if (!recording) return lastReport;
        recording = false;
        try {
            JSONArray gates = new JSONArray();
            int changed = 0, added = 0;
            for (Read read : reads.values()) {
                String state = !read.known ? "new" : Objects.equals(read.before, read.after) ? "read" : "changed";
                if ("new".equals(state)) added++;
                if ("changed".equals(state)) changed++;
                // Redacted here rather than at the read, so the Lab keeps showing real values
                // while the thing people attach to an issue does not. These are server config:
                // CDN hosts, endpoint addresses and per-install identifiers turn up in them, and
                // every other export this project produces goes through the same reader.
                gates.put(new JSONObject().put("manager", read.manager).put("key", read.key)
                        .put("type", read.type).put("state", state).put("calls", read.calls)
                        .put("before", read.known ? DiagnosticRedactor.redact(read.before) : JSONObject.NULL)
                        .put("after", DiagnosticRedactor.redact(read.after)));
            }
            lastCount = gates.length();
            lastReport = new JSONObject().put("target", "TikTok " + FeatureGateLabStore.targetVersion())
                    .put("note", "Addresses, hostnames, credential values and id-shaped "
                            + "tokens are replaced, so a gate marked changed can show the same "
                            + "text twice. Everything else is the value as it was read, so read "
                            + "this through before attaching it to anything.")
                    .put("started_at_ms", startedAt).put("stopped_at_ms", clock.now())
                    .put("gate_count", gates.length()).put("new_count", added).put("changed_count", changed)
                    .put("dropped_reads", dropped).put("gates", gates).toString(2);
        } catch (JSONException error) {
            Logger.printException(() -> "Could not create feature gate report", error);
            lastReport = "{\"error\":\"Could not create report\"}";
        } finally {
            baseline.clear();
            reads.clear();
            sessionState = false;
        }
        return lastReport;
    }

    private static final class Read {
        final String manager, key, type, before;
        final boolean known;
        String after;
        int calls;
        Read(String manager, String key, String type, String before, boolean known) {
            this.manager = manager; this.key = key; this.type = type; this.before = before; this.known = known;
        }
    }
}
