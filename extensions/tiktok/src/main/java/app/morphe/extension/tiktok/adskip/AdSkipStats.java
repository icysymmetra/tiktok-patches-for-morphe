/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.SharedPreferences;

import app.morphe.extension.tiktok.settings.L10n;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifetime skip counters, the way SponsorBlock keeps them: how many ads were skipped on this
 * phone and how much watching time that saved. They outlive the segments, so deleting an ad
 * does not take back the time it saved.
 *
 * <p>Skips of shared segments are also held until the next sync reports them, which is how the
 * base knows what each reader's cuts saved everyone else.
 */
final class AdSkipStats {
    private static final String KEY_SKIPS = "stats_skips";
    private static final String KEY_SAVED_MS = "stats_saved_ms";
    /** Unreported skips, one "serverId count savedMs" line each. */
    private static final String KEY_PENDING = "stats_pending";
    /** No single report claims more than this many skips of one segment. */
    private static final int MAX_REPORTED = 500;

    private AdSkipStats() {}

    static synchronized void record(String sharedServerId, long savedMs) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        seed(prefs);
        SharedPreferences.Editor edit = prefs.edit()
                .putLong(KEY_SKIPS, prefs.getLong(KEY_SKIPS, 0) + 1)
                .putLong(KEY_SAVED_MS, prefs.getLong(KEY_SAVED_MS, 0) + Math.max(0, savedMs));
        if (sharedServerId != null) {
            Map<String, long[]> pending = read(prefs);
            long[] counts = pending.get(sharedServerId);
            if (counts == null) pending.put(sharedServerId, counts = new long[2]);
            if (counts[0] < MAX_REPORTED) {
                counts[0]++;
                counts[1] += Math.max(0, savedMs);
            }
            edit.putString(KEY_PENDING, write(pending));
        }
        edit.apply();
    }

    static synchronized long skips() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return 0;
        seed(prefs);
        return prefs.getLong(KEY_SKIPS, 0);
    }

    static synchronized long savedMs() {
        SharedPreferences prefs = prefs();
        if (prefs == null) return 0;
        seed(prefs);
        return prefs.getLong(KEY_SAVED_MS, 0);
    }

    static synchronized Map<String, long[]> pendingReport() {
        SharedPreferences prefs = prefs();
        return prefs == null ? new HashMap<>() : read(prefs);
    }

    /** Freeze at most 200 entries durably before sending; later skips remain independent. */
    static synchronized String outgoingReport() throws Exception {
        SharedPreferences prefs = prefs();
        if (prefs == null) return null;
        String kept = prefs.getString("stats_outgoing", null);
        if (kept != null) {
            // Server receipts live 30 days. Never replay an uncertain report beyond that window.
            if (System.currentTimeMillis() - prefs.getLong("stats_outgoing_at", 0) < 7 * 86_400_000L) return kept;
            if (!prefs.edit().remove("stats_outgoing").remove("stats_outgoing_at").commit())
                throw new java.io.IOException("Could not expire skip report");
        }
        Map<String, long[]> pending = read(prefs);
        if (pending.isEmpty()) return null;
        org.json.JSONArray items = new org.json.JSONArray();
        java.util.Iterator<Map.Entry<String, long[]>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext() && items.length() < 200) {
            Map.Entry<String, long[]> entry = iterator.next();
            String hex = entry.getKey().substring(SharedDb.SHARED_PREFIX.length());
            String id = hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
            items.put(new org.json.JSONObject().put("id", id).put("n", entry.getValue()[0]).put("ms", entry.getValue()[1]));
            iterator.remove();
        }
        String body = new org.json.JSONObject().put("report_id", java.util.UUID.randomUUID().toString())
                .put("skips", items).toString();
        if (!prefs.edit().putString(KEY_PENDING, write(pending)).putString("stats_outgoing", body)
                .putLong("stats_outgoing_at", System.currentTimeMillis()).commit())
            throw new java.io.IOException("Could not persist skip report");
        return body;
    }

    static synchronized void acknowledgeReport(String body) throws java.io.IOException {
        SharedPreferences prefs = prefs();
        if (prefs != null && body.equals(prefs.getString("stats_outgoing", null))
                && !prefs.edit().remove("stats_outgoing").remove("stats_outgoing_at").commit())
            throw new java.io.IOException("Could not acknowledge skip report");
    }

    /** The server took this report; skips counted since it was read stay pending. */
    static synchronized void reported(Map<String, long[]> sent) {
        SharedPreferences prefs = prefs();
        if (prefs == null) return;
        Map<String, long[]> pending = read(prefs);
        for (Map.Entry<String, long[]> entry : sent.entrySet()) {
            long[] now = pending.get(entry.getKey());
            if (now == null) continue;
            now[0] -= entry.getValue()[0];
            now[1] -= entry.getValue()[1];
            if (now[0] <= 0) pending.remove(entry.getKey());
        }
        prefs.edit().putString(KEY_PENDING, write(pending)).apply();
    }

    /** "3 h 12 min", "45 min", "40 s". */
    static String duration(long ms) {
        long seconds = Math.max(0, ms / 1000);
        long days = seconds / 86_400, hours = seconds % 86_400 / 3600, minutes = seconds % 3600 / 60;
        if (days > 0) return L10n.f("%1$s d %2$s h", String.valueOf(days), String.valueOf(hours));
        if (hours > 0) return L10n.f("%1$s h %2$s min", String.valueOf(hours), String.valueOf(minutes));
        if (minutes > 0) return L10n.f("%1$s min", String.valueOf(minutes));
        return L10n.f("%1$s s", String.valueOf(seconds));
    }

    /** Counters start from the skips the segments already carry, for phones that had them first. */
    private static void seed(SharedPreferences prefs) {
        if (prefs.contains(KEY_SKIPS)) return;
        long skips = 0, saved = 0;
        List<AdSegment> segments = AdSegmentStore.all();
        for (AdSegment segment : segments) {
            skips += segment.skips;
            saved += segment.skips * segment.durationMs;
        }
        prefs.edit().putLong(KEY_SKIPS, skips).putLong(KEY_SAVED_MS, saved).apply();
    }

    private static Map<String, long[]> read(SharedPreferences prefs) {
        Map<String, long[]> pending = new HashMap<>();
        String text = prefs.getString(KEY_PENDING, "");
        if (text == null || text.isEmpty()) return pending;
        for (String line : text.split("\n")) {
            String[] parts = line.split(" ");
            if (parts.length != 3) continue;
            try {
                pending.put(parts[0], new long[]{Long.parseLong(parts[1]), Long.parseLong(parts[2])});
            } catch (NumberFormatException ignored) {
                // A damaged line is dropped; the rest still count.
            }
        }
        return pending;
    }

    private static String write(Map<String, long[]> pending) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, long[]> entry : pending.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(entry.getKey()).append(' ').append(entry.getValue()[0]).append(' ').append(entry.getValue()[1]);
        }
        return text.toString();
    }

    private static SharedPreferences prefs() {
        return AdSkipPrefs.get();
    }
}
