package app.morphe.extension.shared.diagnostics;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** One atomic state/journal, serialized by a private lock that ordinary logging never takes. */
final class RecoveryStore {
    static final int MAX_BYTES = 256 * 1024;
    interface Change { void apply(JSONObject state) throws Exception; }
    private final AtomicFile file;
    static final Object LOCK = new Object();
    static final class InvalidState extends IOException {
        InvalidState() { super("unreadable recovery state"); }
    }

    RecoveryStore(Context context) { this(new File(context.getFilesDir(), "metra_recovery_v1.json")); }
    RecoveryStore(File path) { file = new AtomicFile(path); }

    JSONObject read() throws Exception {
        synchronized (LOCK) { return readLocked(); }
    }

    private JSONObject readLocked() throws Exception {
        try (FileInputStream in = file.openRead()) {
            long size = file.getBaseFile().length();
            if (size < 1 || size > MAX_BYTES) throw new InvalidState();
            byte[] bytes = new byte[(int) size]; int at = 0;
            while (at < bytes.length) {
                int n = in.read(bytes, at, bytes.length - at);
                if (n <= 0) throw new java.io.IOException("incomplete recovery store");
                at += n;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            try {
                JSONObject state = new JSONObject(text);
                if (state.optInt("schema") != 1) throw new InvalidState();
                return state;
            } catch (org.json.JSONException malformed) { throw new InvalidState(); }
        } catch (java.io.FileNotFoundException missing) {
            if (file.getBaseFile().exists()) throw missing; // Not the same as an absent store.
            if (unreadableFile().exists()) return disabledState("");
            return new JSONObject().put("schema", 1);
        }
    }

    JSONObject update(Change change) throws Exception {
        synchronized (LOCK) {
            JSONObject state = readLocked(); change.apply(state);
            writeLocked(state.toString()); return state;
        }
    }

    private void writeLocked(String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new java.io.IOException("recovery store limit");
        FileOutputStream out = null;
        try {
            out = file.startWrite(); out.write(bytes); out.flush(); out.getFD().sync(); file.finishWrite(out);
        } catch (Exception failure) {
            if (out != null) file.failWrite(out);
            throw failure;
        }
    }

    private File unreadableFile() { return new File(file.getBaseFile().getPath() + ".unreadable"); }

    /** Preserve the original once, then disable collection rather than rearming a corrupt trial. */
    void quarantineUnreadable(String trial) throws Exception {
        synchronized (LOCK) {
            try { readLocked(); return; } catch (InvalidState expected) { }
            File saved = unreadableFile();
            if (saved.exists() || !file.getBaseFile().renameTo(saved)) throw new IOException("Cannot preserve recovery state");
            writeLocked(disabledState(trial).toString());
        }
    }

    private static JSONObject disabledState(String trial) throws Exception {
        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\nsource: recovery_trial_status\n"
                + "trial: " + trial + "\ncapture_status: unreadable_private_state\nCollection disabled for this trial. The original private state was preserved.\n";
        return new JSONObject().put("schema", 1).put("trial", trial).put("capture_disabled", true)
                .put("export_until", System.currentTimeMillis() + RecoveryCapture.EXPORT_LEASE_MS)
                .put("incidents", new org.json.JSONArray().put(new JSONObject().put("id", "storage-status")
                        .put("report", report).put("hash", RecoveryPublisher.sha(report))));
    }
    String clear() throws Exception {
        synchronized (LOCK) {
            JSONObject state = readLocked();
            if (!state.has("trial") && !state.has("incidents")) return "";
            org.json.JSONArray incidents = state.optJSONArray("incidents");
            String before = incidents == null || incidents.length() == 0 ? "" : new JSONObject()
                    .put("incidents", incidents).put("export", state.optJSONObject("export"))
                    .put("cleared_through_before", state.optLong("cleared_through")).toString();
            state.put("cleared_through", Math.max(state.optLong("cleared_through"), System.currentTimeMillis()));
            state.remove("incidents"); state.remove("export"); state.put("generation", state.optInt("generation") + 1);
            // Clearing evidence does not rearm the lease on the next launch.
            writeLocked(state.toString()); return before;
        }
    }
    void restoreIfEmpty(String saved) throws Exception {
        if (saved == null || saved.isEmpty()) return;
        synchronized (LOCK) {
            JSONObject state = readLocked();
            org.json.JSONArray current = state.optJSONArray("incidents");
            if (current == null || current.length() == 0) {
                JSONObject snapshot = new JSONObject(saved);
                org.json.JSONArray incidents = snapshot.getJSONArray("incidents");
                if (incidents.length() > RecoveryCapture.MAX_INCIDENTS) throw new java.io.IOException("incident count");
                state.put("incidents", incidents).put("generation", state.optInt("generation") + 1);
                state.put("cleared_through", snapshot.optLong("cleared_through_before"));
                if (snapshot.has("export")) state.put("export", snapshot.getJSONObject("export"));
                writeLocked(state.toString());
            }
        }
    }
}
