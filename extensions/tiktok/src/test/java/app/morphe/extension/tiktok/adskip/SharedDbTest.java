/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SharedDbTest {
    @Rule public final SettingsContextRule contextRule = new SettingsContextRule();
    private SharedDb.ConnectionFactory original;
    private SharedPreferences prefs;
    private int requests;
    private int code;
    private boolean fail;
    private Runnable duringRequest;
    private final List<String> bodies = new ArrayList<>();
    private static final String ID = "s_123456781234423482341234567890ab";

    @Before public void before() {
        original = SharedDb.connectionFactory;
        prefs = AdSkipPrefs.get();
        prefs.edit().clear().commit();
        SettingsStatus.adSkipEnabled = true;
        Settings.AD_SKIP.save(true);
        Settings.AD_SKIP_SHARED_DB.save(true);
        code = 200;
        SharedDb.connectionFactory = endpoint -> {
            requests++;
            if (fail) throw new IOException("offline");
            return new HttpURLConnection(new URL(endpoint)) {
                final ByteArrayOutputStream body = new ByteArrayOutputStream();
                public void connect() {}
                public void disconnect() {}
                public boolean usingProxy() { return false; }
                public OutputStream getOutputStream() { return body; }
                public int getResponseCode() {
                    bodies.add(body.toString());
                    if (duringRequest != null) { Runnable r = duringRequest; duringRequest = null; r.run(); }
                    return code;
                }
                public InputStream getInputStream() {
                    return new ByteArrayInputStream("{\"segments\":2,\"base\":{\"active\":3}}".getBytes(StandardCharsets.UTF_8));
                }
            };
        };
        new android.util.AtomicFile(new File(Utils.getContext().getFilesDir(), "metra_adskip_queue.bin")).delete();
    }

    @After public void after() {
        SharedDb.connectionFactory = original;
        Settings.AD_SKIP.save(false);
        Settings.AD_SKIP_SHARED_DB.save(true);
        SettingsStatus.adSkipEnabled = false;
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = SharedDb.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try { return m.invoke(null, args); }
        catch (InvocationTargetException e) { throw (Exception) e.getCause(); }
    }
    private void enqueue(String s) throws Exception {
        invoke("enqueue", new Class<?>[]{Context.class, byte[].class}, Utils.getContext(), s.getBytes(StandardCharsets.UTF_8));
    }
    private void drain() throws Exception { invoke("flushQueue", new Class<?>[]{Context.class}, Utils.getContext()); }
    private List<?> queue() throws Exception { return (List<?>) invoke("readQueue", new Class<?>[]{Context.class}, Utils.getContext()); }
    private void votes() throws Exception { invoke("flushVotes", new Class<?>[]{}); }

    @Test public void disabledBlocksEveryConnectionBeforeTransport() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            SettingsStatus.adSkipEnabled = mode != 0;
            Settings.AD_SKIP.save(mode != 1);
            Settings.AD_SKIP_SHARED_DB.save(mode != 2);
            try { invoke("open", new Class<?>[]{String.class}, SharedDb.BASE_URL + "/v1/me"); fail("opened"); }
            catch (IOException expected) {}
            assertNull(SharedDb.fetchStats());
        }
        assertEquals(0, requests);
    }

    @Test public void queueSurvivesConnectionFailureAndRetries() throws Exception {
        enqueue("first"); enqueue("second");
        fail = true;
        try { drain(); fail("must fail"); } catch (IOException expected) {}
        assertEquals(2, queue().size());
        fail = false;
        drain();
        assertTrue(queue().isEmpty());
        assertEquals(Arrays.asList("first", "second"), bodies);
    }

    @Test public void queuePreservesConcurrentAppend() throws Exception {
        enqueue("first");
        duringRequest = () -> { try { enqueue("second"); } catch (Exception e) { throw new RuntimeException(e); } };
        drain();
        assertEquals(Arrays.asList("first", "second"), bodies);
        assertTrue(queue().isEmpty());
    }

    @Test public void queueStopsAtRateLimitWithoutLosingTail() throws Exception {
        enqueue("first"); enqueue("second"); code = 429;
        drain();
        assertEquals(1, requests);
        assertEquals(2, queue().size());
    }

    @Test public void failedVoteAndUndoAreRetriedDurably() throws Exception {
        String key = "pending_vote_" + ID;
        prefs.edit().putInt(key, -1).commit();
        fail = true;
        try { votes(); fail("must fail"); } catch (IOException expected) {}
        assertEquals(-1, prefs.getInt(key, 9));
        fail = false;
        votes();
        assertFalse(prefs.contains(key));
        prefs.edit().putInt(key, 0).commit();
        votes();
        assertFalse(prefs.contains(key));
        assertEquals(Arrays.asList("{\"vote\":-1}", "{\"vote\":0}"), bodies);
    }

    @Test public void voteAckDoesNotEraseNewerChoice() throws Exception {
        String key = "pending_vote_" + ID;
        prefs.edit().putInt(key, -1).commit();
        duringRequest = () -> prefs.edit().putInt(key, 0).commit();
        votes();
        assertEquals(0, prefs.getInt(key, 9));
        votes();
        assertFalse(prefs.contains(key));
    }

    @Test public void malformedDownloadIsNotAcknowledged() throws Exception {
        for (byte[] bytes : new byte[][] {new byte[0], new byte[12],
                java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putInt(0x4244414D).putInt(2).putInt(1).array()}) {
            try { invoke("merge", new Class<?>[]{byte[].class}, bytes); fail("accepted corrupt database"); }
            catch (IOException expected) {}
        }
    }

    @Test public void reportRetryKeepsExactBodyAndPreservesNewSkips() throws Exception {
        AdSkipStats.record(ID, 1000);
        String first = AdSkipStats.outgoingReport();
        AdSkipStats.record(ID, 2000);
        assertEquals(first, AdSkipStats.outgoingReport());
        AdSkipStats.acknowledgeReport(first);
        org.json.JSONObject next = new org.json.JSONObject(AdSkipStats.outgoingReport());
        assertNotEquals(new org.json.JSONObject(first).getString("report_id"), next.getString("report_id"));
        assertEquals(1, next.getJSONArray("skips").getJSONObject(0).getLong("n"));
        assertEquals(2000, next.getJSONArray("skips").getJSONObject(0).getLong("ms"));
    }

    @Test public void uncertainReportExpiresWithoutDoubleCounting() throws Exception {
        AdSkipStats.record(ID, 1000);
        String first = AdSkipStats.outgoingReport();
        prefs.edit().putLong("stats_outgoing_at", System.currentTimeMillis() - 8 * 86_400_000L).commit();
        assertNull(AdSkipStats.outgoingReport());
        assertEquals(1, AdSkipStats.skips());
    }

    @Test public void statsCacheAvoidsRepeatedRequests() {
        assertEquals(3, SharedDb.fetchStats().segments);
        assertEquals(3, SharedDb.fetchStats().segments);
        assertEquals(1, requests);
    }

    @Test public void statsFailureHasCooldownAndRetainsStaleData() {
        prefs.edit().putString("stats_base", "{\"active\":8}").commit();
        fail = true;
        assertEquals(8, SharedDb.fetchStats().segments);
        assertEquals(8, SharedDb.fetchStats().segments);
        assertEquals(1, requests);
    }

    @Test public void statsCacheLastsADayAndThenExpires() {
        long now = System.currentTimeMillis();
        prefs.edit().putString("stats_base", "{\"active\":8}")
                .putLong("stats_at", now - 23 * 3600_000L).commit();
        assertEquals(8, SharedDb.fetchStats().segments);
        assertEquals(0, requests);
        prefs.edit().putLong("stats_at", now - 25 * 3600_000L).commit();
        assertEquals(3, SharedDb.fetchStats().segments);
        assertEquals(1, requests);
    }

    @Test public void failedStatsWaitADayBeforeRetry() {
        prefs.edit().putString("stats_base", "{\"active\":8}")
                .putLong("stats_attempt", System.currentTimeMillis() - 23 * 3600_000L).commit();
        assertEquals(8, SharedDb.fetchStats().segments);
        assertEquals(0, requests);
    }

    @Test public void syncCooldownSurvivesRepeatedFeedCalls() {
        prefs.edit().putLong("sync_attempt", System.currentTimeMillis() - 23 * 3600_000L).commit();
        SharedDb.syncIfDue(); SharedDb.syncIfDue();
        assertEquals(0, requests);
    }

    @Test public void playHookRecognizesSelectedPatchWithoutSettingsScreen() {
        Settings.AD_SKIP.save(false);
        SettingsStatus.adSkipEnabled = false;
        AdSkip.onPlay(null, null);
        assertTrue(SettingsStatus.adSkipEnabled);
        assertEquals(0, requests);
    }
}
