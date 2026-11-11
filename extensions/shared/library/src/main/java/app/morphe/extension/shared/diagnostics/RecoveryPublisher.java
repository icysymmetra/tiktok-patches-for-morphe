package app.morphe.extension.shared.diagnostics;

import android.content.Context;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Durable receipt adapter for the same MediaStore publisher used by manual full reports. */
public final class RecoveryPublisher {
    public interface Journal {
        boolean active() throws Exception;
        String uri() throws Exception;
        void created(String uri) throws Exception;
        void verified(String uri, String actualName, String hash) throws Exception;
    }
    private RecoveryPublisher() {}

    public static String sha(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    static void publish(Context context, RecoveryStore store) throws Exception {
        JSONObject state = store.read();
        if (System.currentTimeMillis() > state.optLong("export_until", Long.MAX_VALUE)) return;
        final String report = RecoveryCapture.render(state);
        if (report.isEmpty()) return;
        final String hash = sha(report);
        final int generation = state.optInt("generation");
        JSONObject existing = state.optJSONObject("export");
        if (existing != null && hash.equals(existing.optString("hash"))
                && (hash.equals(existing.optString("verified_hash")) || existing.optInt("attempts") >= 3)) return;
        store.update(s -> {
            if (s.optInt("generation") != generation) return;
            JSONObject journal = s.optJSONObject("export");
            if (journal == null || !hash.equals(journal.optString("hash"))) journal = new JSONObject().put("hash", hash);
            journal.put("attempts", journal.optInt("attempts") + 1).put("status", "pending"); s.put("export", journal);
        });
        try {
            LogBufferManager.writeVerifiedReport(context, "Metra-issue215-" + hash.substring(0, 24) + ".txt",
                    report, new Journal() {
                        public boolean active() throws Exception {
                            JSONObject s = store.read(); JSONObject j = s.optJSONObject("export");
                            return s.optInt("generation") == generation && j != null && hash.equals(j.optString("hash"))
                                    && hash.equals(sha(RecoveryCapture.render(s)));
                        }
                        public String uri() throws Exception {
                            JSONObject j = store.read().optJSONObject("export");
                            return j == null ? "" : j.optString("uri");
                        }
                        public void created(String uri) throws Exception {
                            store.update(s -> {
                                JSONObject j = s.optJSONObject("export");
                                if (s.optInt("generation") == generation && j != null && hash.equals(j.optString("hash"))) j.put("uri", uri);
                            });
                        }
                        public void verified(String uri, String actualName, String actualHash) throws Exception {
                            if (!hash.equals(actualHash)) throw new java.io.IOException("report hash mismatch");
                            store.update(s -> {
                                JSONObject j = s.optJSONObject("export");
                                if (s.optInt("generation") == generation && j != null && hash.equals(j.optString("hash")))
                                    j.put("uri", uri).put("name", actualName).put("verified_hash", hash).put("status", "verified");
                            });
                        }
                    });
        } catch (Exception unavailable) {
            store.update(s -> {
                JSONObject j = s.optJSONObject("export");
                if (j != null && hash.equals(j.optString("hash"))) j.put("status", "failed_retry_on_relaunch");
            });
        }
    }
}
