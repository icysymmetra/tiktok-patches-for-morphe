/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The phone's side of the shared ad base: the daily download, sending the reader's own cuts,
 * votes, and the skip counts behind the statistics.
 *
 * <p>Matching only ever reads the local list. The base adds the segments other readers cut, and
 * a cut sent from this phone learns its server id, which is what makes it votable. Everything
 * here runs off the main thread and gives up quietly when the network is not there; the next
 * sync tries again.
 *
 * <p>The snapshot is little-endian:
 * <pre>
 *   [4] magic MADB   [4] version 2 (1 is still read)   [4] segment count
 *   per segment:
 *     [16] uuid
 *     [4] number in the base, the N of adN   (version 2)
 *     [4] flags, bit 0: hidden by votes      (version 2)
 *     [4] frames                             (version 2)
 *     [4] duration ms   [4] score   [4] hash count
 *     [4 x count] hashes   [4 x count] times
 * </pre>
 * The server names the moment its data is current as of in {@code X-Db-Time}; the next delta asks
 * from there, so nothing sent between a snapshot's build and its download is missed. A delta
 * that filled a whole page says so in {@code X-Db-More}, and the next page is asked for at once.
 */
public final class SharedDb {
    public static final String BASE_URL = "https://metra-adskip.falker600.workers.dev";
    /** Server ids carry this prefix on the phone. */
    public static final String SHARED_PREFIX = "s_";

    private static final int MAGIC = 0x4244414D;
    private static final String KEY_LAST_SYNC = "sync_last";
    private static final String KEY_LAST_REPORT = "skips_reported_last";
    private static final String KEY_LAST_EPOCH = "sync_as_of";
    private static final String KEY_VOTE = "vote_";
    private static final String KEY_STATS_BASE = "stats_base";
    private static final String KEY_STATS_MINE = "stats_mine";
    private static final String KEY_STATS_AT = "stats_at";
    private static final String QUEUE_FILE = "metra_adskip_queue.bin";
    private static final long SYNC_INTERVAL_MS = 24 * 3600 * 1000L;
    /** Background traffic is deliberately limited to one attempt per day. */
    private static final long REPORT_INTERVAL_MS = SYNC_INTERVAL_MS;
    private static final long STATS_INTERVAL_MS = SYNC_INTERVAL_MS;
    private static final String USER_AGENT = "Metra-AdSkip/2";
    /** Delta pages one sync reads at most; the rest arrives with the next sync. */
    private static final int MAX_DELTA_PAGES = 10;
    /** The limits a snapshot is read under, the same as the server's and the core library's. */
    private static final int MAX_DB_BYTES = 64 * 1024 * 1024;
    private static final int MAX_HASHES = 6_000;
    private static final int MIN_DURATION_MS = 1_000;
    private static final int MAX_DURATION_MS = 30_000;
    private static final int TIME_SLACK = 70;
    private static final int MAX_FRAMES = 30 * Landmarks.FRAMES_PER_SECOND + TIME_SLACK;

    private static volatile boolean syncing;
    private static final String KEY_ATTEMPT = "sync_attempt";
    private static final String KEY_STATS_ATTEMPT = "stats_attempt";
    private static final String KEY_PENDING_VOTE = "pending_vote_";
    private static final long RETRY_INTERVAL_MS = SYNC_INTERVAL_MS;
    private static final Object QUEUE_LOCK = new Object();
    private static final Object QUEUE_SEND_LOCK = new Object();
    private static final Object VOTE_SEND_LOCK = new Object();
    private static final Object STATS_LOCK = new Object();

    interface ConnectionFactory {
        HttpURLConnection open(String endpoint) throws IOException;
    }
    // Package-private transport seam: tests never contact the live service.
    static ConnectionFactory connectionFactory = endpoint -> (HttpURLConnection) new URL(endpoint).openConnection();

    private SharedDb() {}

    /** Server-side counters for the statistics card. */
    static final class Stats {
        long segments;
        long contributors;
        long skips;
        long savedMs;
        long mySegments;
        long mySkips;
        long mySavedMs;
        boolean mine;
    }

    // region sync

    /** Whether the patch is in, skipping is on and the shared base is on: nothing goes to the server otherwise. */
    static boolean enabled() {
        return SettingsStatus.adSkipEnabled && Settings.AD_SKIP.get() && Settings.AD_SKIP_SHARED_DB.get();
    }

    /** Syncs in the background once a day, including queued votes and skip reports. Safe to call on every feed open. */
    public static synchronized void syncIfDue() {
        if (!enabled() || syncing) return;
        Context context = Utils.getContext();
        SharedPreferences prefs = AdSkipPrefs.get();
        if (context == null || prefs == null) return;
        long now = System.currentTimeMillis();
        // Every video start lands here: the cheap daily gate comes before any file or prefs scan.
        if (now - prefs.getLong(KEY_ATTEMPT, 0) < RETRY_INTERVAL_MS) return;
        boolean syncDue = now - prefs.getLong(KEY_LAST_SYNC, 0) >= SYNC_INTERVAL_MS;
        boolean reportDue = now - prefs.getLong(KEY_LAST_REPORT, 0) >= REPORT_INTERVAL_MS;
        boolean votesDue = prefs.getAll().keySet().stream().anyMatch(key -> key.startsWith(KEY_PENDING_VOTE));
        File queued = new File(context.getFilesDir(), QUEUE_FILE);
        boolean queueDue = queued.length() > 0 || new File(queued.getPath() + ".bak").exists();
        if (!syncDue && !reportDue && !votesDue && !queueDue) return;
        prefs.edit().putLong(KEY_ATTEMPT, now).apply();
        syncing = true;
        Utils.runOnOwnThread("metra-adskip-sync", () -> {
            try {
                if (syncDue) {
                    try { download(prefs); }
                    catch (IOException failure) { Logger.printException(() -> "Ad skip: download failed", failure); }
                }
                try { flushQueue(context); }
                catch (IOException failure) { Logger.printException(() -> "Ad skip: queue upload failed", failure); }
                try { flushVotes(); }
                catch (Exception failure) { Logger.printException(() -> "Ad skip: vote upload failed", failure); }
                if (reportDue && reportSkips()) {
                    prefs.edit().putLong(KEY_LAST_REPORT, System.currentTimeMillis()).apply();
                }
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: shared base sync failed", failure);
            } finally {
                syncing = false;
            }
        });
    }

    private static void download(SharedPreferences prefs) throws IOException {
        int added = 0;
        boolean more = true;
        for (int page = 0; more && page < MAX_DELTA_PAGES; page++) {
            long since = prefs.getLong(KEY_LAST_EPOCH, 0);
            String cursor = prefs.getString("sync_cursor", "");
            String endpoint = since > 0 ? BASE_URL + "/v1/db/since?t=" + since
                    + (cursor.isEmpty() ? "" : "&after=" + cursor) : BASE_URL + "/v1/db.bin";
            HttpURLConnection connection = open(endpoint);
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " from " + endpoint);
            long asOf;
            try {
                asOf = Long.parseLong(connection.getHeaderField("X-Db-Time"));
            } catch (Exception missing) {
                asOf = System.currentTimeMillis() / 1000L - 86_400L;
            }
            String nextCursor = connection.getHeaderField("X-Db-Cursor");
            if (nextCursor == null) nextCursor = "";
            if (!nextCursor.isEmpty() && !nextCursor.matches("[0-9a-fA-F-]{36}"))
                throw new IOException("Invalid database cursor");
            more = since > 0 && "1".equals(connection.getHeaderField("X-Db-More"));
            if (more && (asOf < since || (asOf == since && nextCursor.compareTo(cursor) <= 0)))
                throw new IOException("Database cursor did not advance");
            byte[] data;
            try (InputStream in = connection.getInputStream()) {
                data = readAll(in, MAX_DB_BYTES);
            }
            added += merge(data);
            long replay = prefs.getLong("sync_replay", asOf);
            long nextEpoch = !more && since > 0 && (prefs.contains("sync_replay") || !cursor.isEmpty())
                    ? Math.min(asOf, replay - replay % 3600) : asOf;
            SharedPreferences.Editor progress = prefs.edit().putLong(KEY_LAST_EPOCH, nextEpoch)
                    .putString("sync_cursor", more ? nextCursor : "");
            if (more) progress.putLong("sync_replay", replay);
            else progress.remove("sync_replay");
            progress.apply();
        }
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply();
        AdSkipLog.add(null, "shared base: " + added + " new segments");
    }

    private static int merge(byte[] data) throws IOException {
        if (data.length < 12) throw new IOException("Truncated shared database");
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (buf.getInt() != MAGIC) throw new IOException("Invalid shared database magic");
        int version = buf.getInt();
        if (version != 1 && version != 2) throw new IOException("Unsupported shared database version");
        int count = buf.getInt();
        if (count < 0) throw new IOException("Invalid shared database count");
        List<AdSegmentStore.Shared> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (buf.remaining() < (version == 2 ? 40 : 28)) throw new IOException("Truncated shared database row");
            byte[] id = new byte[16];
            buf.get(id);
            int number = version == 2 ? buf.getInt() : 0;
            int flags = version == 2 ? buf.getInt() : 0;
            int frames = version == 2 ? buf.getInt() : 0;
            int durationMs = buf.getInt();
            int score = buf.getInt();
            int hashCount = buf.getInt();
            if (hashCount < 0 || buf.remaining() < hashCount * 8L) throw new IOException("Truncated shared database hashes");
            if (hashCount > MAX_HASHES) {
                buf.position(buf.position() + hashCount * 8);
                continue;
            }
            int[] hashes = new int[hashCount];
            int[] times = new int[hashCount];
            for (int k = 0; k < hashCount; k++) hashes[k] = buf.getInt();
            for (int k = 0; k < hashCount; k++) times[k] = buf.getInt();
            if (frames <= 0) frames = durationMs * Landmarks.FRAMES_PER_SECOND / 1000;
            if (!isValid(number, durationMs, frames, hashes, times)) continue;
            boolean hidden = (flags & 1) != 0 || score < -2;
            entries.add(new AdSegmentStore.Shared(SHARED_PREFIX + hex(id), number, hidden, durationMs, frames,
                    hashes, times, score));
        }
        return AdSegmentStore.mergeShared(entries);
    }

    private static boolean isValid(int number, int durationMs, int frames, int[] hashes, int[] times) {
        if (number < 0 || durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS) return false;
        if (frames < 1 || frames > MAX_FRAMES) return false;
        for (int k = 0; k < hashes.length; k++) {
            if (!isHash(hashes[k]) || times[k] < 0 || times[k] > frames + TIME_SLACK) return false;
        }
        return true;
    }

    /** Two peak bins below the band's width, then a gap of 1 to 63 frames, as Landmarks packs them. */
    private static boolean isHash(int hash) {
        int f1 = hash >>> 15;
        int f2 = (hash >>> 6) & 511;
        int dt = hash & 63;
        return f1 < Landmarks.BINS && f2 < Landmarks.BINS && dt >= 1;
    }

    // endregion

    // region submitting

    /**
     * Sends a new cut. Without a signed-in account, over the daily limit, or offline it waits in a
     * queue on the phone, and works locally meanwhile.
     */
    public static void submit(Context context, AdSegment segment) {
        if (!enabled()) return;
        byte[] body;
        try {
            body = payload(segment);
        } catch (IllegalStateException failure) {
            Logger.printException(() -> "Ad skip: could not encode a segment for the shared base", failure);
            return;
        }
        // Persist before starting a request so process termination cannot lose the submission.
        enqueue(context, body);
        Utils.runOnOwnThread("metra-adskip-submit", () -> {
            synchronized (QUEUE_SEND_LOCK) {
                try {
                    synchronized (QUEUE_LOCK) {
                        if (readQueue(context).stream().noneMatch(entry -> java.util.Arrays.equals(entry, body))) return;
                    }
                    Reply reply = post("/v1/segments", body);
                    if (!retryable(reply.code)) {
                        synchronized (QUEUE_LOCK) {
                            List<byte[]> entries = readQueue(context);
                            for (int i = 0; i < entries.size(); i++) {
                                if (java.util.Arrays.equals(entries.get(i), body)) { entries.remove(i); break; }
                            }
                            writeQueue(context, entries);
                        }
                    }
                    if (reply.code == 200 || reply.code == 201) {
                        String id = reply.json == null ? null : reply.json.optString("id", null);
                        if (id != null) AdSegmentStore.linkShared(segment.id, SHARED_PREFIX + id.replace("-", ""));
                        // Server statistics stay cached until the next daily refresh.
                    }
                    AdSkipLog.add(segment.sourceAid, "shared base: sent, HTTP " + reply.code);
                } catch (Throwable failure) {
                    Logger.printException(() -> "Ad skip: could not send a segment", failure);
                }
            }
        });
    }

    private static void enqueue(Context context, byte[] payload) {
        synchronized (QUEUE_LOCK) {
            try {
                List<byte[]> entries = readQueue(context);
                entries.add(payload);
                writeQueue(context, entries);
            } catch (IOException failure) {
                Logger.printException(() -> "Ad skip: could not queue a segment", failure);
            }
        }
    }

    private static android.util.AtomicFile queueFile(Context context) {
        return new android.util.AtomicFile(new File(context.getFilesDir(), QUEUE_FILE));
    }

    private static List<byte[]> readQueue(Context context) throws IOException {
        List<byte[]> entries = new ArrayList<>();
        byte[] data;
        try (InputStream in = queueFile(context).openRead()) {
            data = readAll(in, MAX_DB_BYTES);
        } catch (java.io.FileNotFoundException absent) {
            return entries;
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            if (buf.remaining() < 4) throw new IOException("Truncated segment queue");
            int length = buf.getInt();
            if (length <= 0 || length > buf.remaining()) throw new IOException("Bad segment queue entry");
            byte[] entry = new byte[length];
            buf.get(entry);
            entries.add(entry);
        }
        return entries;
    }

    private static void writeQueue(Context context, List<byte[]> entries) throws IOException {
        long bytes = 0;
        for (byte[] entry : entries) bytes += 4L + entry.length;
        if (bytes > MAX_DB_BYTES) throw new IOException("Segment queue is full; segment remains local");
        android.util.AtomicFile file = queueFile(context);
        FileOutputStream stream = file.startWrite();
        try {
            DataOutputStream out = new DataOutputStream(stream);
            for (byte[] entry : entries) {
                out.writeInt(entry.length);
                out.write(entry);
            }
            out.flush();
            file.finishWrite(stream);
        } catch (IOException failure) {
            file.failWrite(stream);
            throw failure;
        }
    }

    private static boolean retryable(int code) {
        return code == 202 || code == 401 || code == 429 || code >= 500;
    }

    // Only the sync worker drains the queue. The entry stays durable until acknowledged;
    // an interrupted request may be replayed, which the server deduplicates by fingerprint.
    private static void flushQueue(Context context) throws IOException {
        synchronized (QUEUE_SEND_LOCK) {
            flushQueueLocked(context);
        }
    }

    private static void flushQueueLocked(Context context) throws IOException {
        for (int sent = 0; sent < 20; sent++) {
            byte[] entry;
            synchronized (QUEUE_LOCK) {
                List<byte[]> entries = readQueue(context);
                if (entries.isEmpty()) return;
                entry = entries.get(0);
            }
            Reply reply = post("/v1/segments", entry);
            if (retryable(reply.code)) return; // Do not hammer a daily or burst limit.
            synchronized (QUEUE_LOCK) {
                List<byte[]> entries = readQueue(context);
                entries.remove(0); // Enqueues can only append while this request is in flight.
                writeQueue(context, entries);
            }
        }
    }

    private static byte[] payload(AdSegment segment) {
        try {
            int n = segment.hashes.length;
            ByteBuffer hashes = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer times = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < n; i++) {
                hashes.putInt(segment.hashes[i]);
                times.putInt(segment.times[i]);
            }
            JSONObject json = new JSONObject()
                    .put("name", segment.name)
                    .put("duration_ms", segment.durationMs)
                    .put("source_aid", segment.sourceAid == null ? JSONObject.NULL : segment.sourceAid)
                    .put("frames", segment.frames)
                    .put("hashes_b64", Base64.encodeToString(hashes.array(), Base64.NO_WRAP))
                    .put("times_b64", Base64.encodeToString(times.array(), Base64.NO_WRAP));
            return json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encode a segment", failure);
        }
    }

    // endregion

    // region votes

    /** The reader's vote on this segment: 1, -1, or 0 when none. */
    static int myVote(String serverId) {
        SharedPreferences prefs = AdSkipPrefs.get();
        return prefs == null || serverId == null ? 0 : prefs.getInt(KEY_VOTE + serverId, 0);
    }

    /** Only someone else's segment from the base can be voted on. */
    static boolean canVote(String serverId) {
        return serverId != null && serverId.startsWith(SHARED_PREFIX) && AdSegmentStore.isOthers(serverId);
    }

    /**
     * Votes once per segment. The same vote again does nothing; the other one replaces it, on the
     * server too, so a segment never carries two votes from one account. 0 takes the vote back.
     * A segment voted down is no longer skipped for this reader until the vote is taken back.
     */
    static boolean vote(String serverId, int vote) {
        if (vote < -1 || vote > 1 || !canVote(serverId)) return false;
        SharedPreferences prefs = AdSkipPrefs.get();
        if (prefs == null) return false;
        synchronized (prefs) {
            int previous = myVote(serverId);
            if (previous == vote) return false;
            // Keep the local effect even offline or with sharing off. Zero is also queued:
            // taking back a down-vote must eventually reach the server.
            prefs.edit().putInt(KEY_VOTE + serverId, vote)
                    .putInt(KEY_PENDING_VOTE + serverId, vote).apply();
            if (vote < 0 || previous < 0) AdSegmentStore.votesChanged();
        }
        // Only the daily sync sends the latest choice; local down-votes apply immediately.
        return true;
    }

    private static void flushVotes() throws Exception {
        synchronized (VOTE_SEND_LOCK) {
            SharedPreferences prefs = AdSkipPrefs.get();
            if (prefs == null || !enabled()) return;
            for (String key : prefs.getAll().keySet()) {
                if (!key.startsWith(KEY_PENDING_VOTE)) continue;
                int value;
                synchronized (prefs) {
                    if (!prefs.contains(key)) continue;
                    value = prefs.getInt(key, 0);
                }
                String id = key.substring(KEY_PENDING_VOTE.length());
                String remoteId = uuid(id.substring(SHARED_PREFIX.length()));
                Reply reply = post("/v1/segments/" + remoteId + "/vote",
                        new JSONObject().put("vote", value).toString().getBytes(StandardCharsets.UTF_8));
                if (retryable(reply.code)) return;
                synchronized (prefs) {
                    // Do not acknowledge a newer choice made while the request was in flight.
                    if (prefs.getInt(key, 2) == value) prefs.edit().remove(key).apply();
                }
            }
        }
    }

    // endregion

    // region statistics

    /** Sends how often each shared segment was skipped here since the last report. */
    private static boolean reportSkips() throws Exception {
        String body = AdSkipStats.outgoingReport();
        if (body == null) return true;
        Reply reply = post("/v1/skips", body.getBytes(StandardCharsets.UTF_8));
        if (reply.code == 200) AdSkipStats.acknowledgeReport(body);
        return reply.code == 200;
    }

    /**
     * The base's totals and what the reader's own cuts saved others. An answer is kept for a day,
     * so opening the list again costs no request. /v1/me carries the totals too; a server that
     * predates that is asked /v1/stats as well.
     */
    @Nullable
    static Stats fetchStats() {
        synchronized (STATS_LOCK) {
            return fetchStatsLocked();
        }
    }

    @Nullable
    private static Stats fetchStatsLocked() {
        if (!enabled()) return null;
        SharedPreferences prefs = AdSkipPrefs.get();
        try {
            if (prefs != null && System.currentTimeMillis() - prefs.getLong(KEY_STATS_AT, 0) < STATS_INTERVAL_MS) {
                String kept = prefs.getString(KEY_STATS_BASE, null);
                String keptMine = prefs.getString(KEY_STATS_MINE, null);
                if (kept != null) return stats(new JSONObject(kept), keptMine == null ? null : new JSONObject(keptMine));
            }
            long now = System.currentTimeMillis();
            if (prefs != null) {
                if (now - prefs.getLong(KEY_STATS_ATTEMPT, 0) < RETRY_INTERVAL_MS) return cachedStats(prefs);
                prefs.edit().putLong(KEY_STATS_ATTEMPT, now).apply();
            }
            JSONObject mine = null;
            JSONObject base = null;
            HttpURLConnection me = open(BASE_URL + "/v1/me");
            if (me.getResponseCode() == 200) {
                mine = readJson(me);
                base = mine.optJSONObject("base");
            }
            if (base == null) {
                HttpURLConnection global = open(BASE_URL + "/v1/stats");
                if (global.getResponseCode() != 200) return cachedStats(prefs);
                base = readJson(global);
            }
            if (prefs != null) {
                prefs.edit()
                        .putString(KEY_STATS_BASE, base.toString())
                        .putString(KEY_STATS_MINE, mine == null ? null : mine.toString())
                        .putLong(KEY_STATS_AT, System.currentTimeMillis())
                        .apply();
            }
            return stats(base, mine);
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Ad skip: no shared base statistics: " + failure);
            return cachedStats(prefs);
        }
    }

    @Nullable
    private static Stats cachedStats(@Nullable SharedPreferences prefs) {
        if (prefs == null) return null;
        try {
            String base = prefs.getString(KEY_STATS_BASE, null);
            String mine = prefs.getString(KEY_STATS_MINE, null);
            return base == null ? null : stats(new JSONObject(base), mine == null ? null : new JSONObject(mine));
        } catch (Exception invalid) {
            return null;
        }
    }

    private static Stats stats(JSONObject base, @Nullable JSONObject mine) {
        Stats stats = new Stats();
        stats.segments = base.optLong("active");
        stats.contributors = base.optLong("contributors");
        stats.skips = base.optLong("skips");
        stats.savedMs = base.optLong("saved_ms");
        if (mine != null) {
            stats.mine = true;
            stats.mySegments = mine.optLong("segments");
            stats.mySkips = mine.optLong("skips");
            stats.mySavedMs = mine.optLong("saved_ms");
        }
        return stats;
    }

    // endregion

    // region http

    private static final class Reply {
        final int code;
        @Nullable
        final JSONObject json;

        Reply(int code, @Nullable JSONObject json) {
            this.code = code;
            this.json = json;
        }
    }

    private static HttpURLConnection open(String endpoint) throws IOException {
        if (!enabled()) throw new IOException("Shared base is disabled");
        String token = AdSkipPrefs.token();
        if (token == null) throw new IOException("No sync token yet");
        HttpURLConnection connection = connectionFactory.open(endpoint);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("X-SB-Token", token);
        return connection;
    }

    private static Reply post(String path, byte[] body) throws IOException {
        HttpURLConnection connection = open(BASE_URL + path);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.getOutputStream().write(body);
        int code = connection.getResponseCode();
        JSONObject json = null;
        if (code < 400) {
            try (InputStream in = connection.getInputStream()) {
                json = new JSONObject(new String(readAll(in, 16 * 1024), StandardCharsets.UTF_8));
            } catch (Exception notJson) {
                json = null;
            }
        }
        return new Reply(code, json);
    }

    private static JSONObject readJson(HttpURLConnection connection) throws Exception {
        try (InputStream in = connection.getInputStream()) {
            return new JSONObject(new String(readAll(in, 64 * 1024), StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            if (out.size() > limit) throw new IOException("Reply larger than " + limit + " bytes");
        }
        return out.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b));
        return text.toString();
    }

    /** 32 hex digits as a hyphenated uuid. */
    private static String uuid(String hex) {
        if (hex.length() != 32) return hex;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    // endregion
}
