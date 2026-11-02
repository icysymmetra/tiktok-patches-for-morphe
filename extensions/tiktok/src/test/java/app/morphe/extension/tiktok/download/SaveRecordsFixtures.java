/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Process death, as the tests play it. What a dead process leaves is the records file as it
 * stood at that moment and nothing in memory, so a death is a copy of the file taken then, and
 * the next start is a fresh {@link SaveRecords} handed that copy back. The dying process's jobs
 * are let go and drained in between, since a live pool would keep writing after its "death".
 */
final class SaveRecordsFixtures {
    private SaveRecordsFixtures() {
    }

    /** The file as it is now, after any queued write lands; null when there is none. */
    static byte[] atDeath(Context context) throws Exception {
        SaveRecords.flushForTests();
        File base = base(context);
        if (base.isFile()) return Files.readAllBytes(base.toPath());
        File backup = new File(base.getPath() + ".bak");
        return backup.isFile() ? Files.readAllBytes(backup.toPath()) : null;
    }

    /** The start after a death that left {@code atDeath} on disk. */
    static void startAgain(Context context, byte[] atDeath) throws Exception {
        drain();
        SaveRecords.flushForTests();
        SaveRecords.freshStartForTests();
        write(context, atDeath);
    }

    /** A clean slate for a test: no file, no memory of any earlier process. */
    static void reset(Context context) throws Exception {
        drain();
        SaveRecords.flushForTests();
        SaveRecords.freshStartForTests();
        write(context, null);
    }

    static void write(Context context, byte[] bytes) throws Exception {
        File base = base(context);
        File backup = new File(base.getPath() + ".bak");
        if (backup.exists() && !backup.delete()) throw new IllegalStateException("backup stuck");
        if (bytes == null) {
            if (base.exists() && !base.delete()) throw new IllegalStateException("records stuck");
        } else {
            Files.write(base.toPath(), bytes);
        }
    }

    /** The records on disk right now, parsed; an empty array when there is no file. */
    static JSONArray records(Context context) throws Exception {
        byte[] bytes = atDeath(context);
        if (bytes == null) return new JSONArray();
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8)).getJSONArray("records");
    }

    static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    static File base(Context context) {
        return new File(context.getFilesDir(), SaveRecords.FILE_NAME);
    }

    static void drain() throws Exception {
        for (int wait = 0; wait < 250; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("the media pool never emptied");
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
