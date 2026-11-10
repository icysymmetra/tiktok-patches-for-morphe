/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * An event formats its line once, and says the same thing it always did.
 *
 * <p>Every logged line used to build a {@link java.text.SimpleDateFormat}, look up a time zone
 * and parse the pattern, and it did it more than once per event: appending asks for the line's
 * length, evicting asks again once the buffer is full, and the export, the crash snapshot and
 * the clear and undo paths each ask again. The eviction is the one inside the buffer's lock, so
 * that one is paid by whoever happened to be logging while every other logging thread queues
 * behind it.
 */
public class DiagnosticEventFormatTest {
    private static DiagnosticEvent event(long timestamp, String message) {
        return new DiagnosticEvent(
                DiagnosticCategory.OTHER, timestamp, "main", "Source", "INFO", message);
    }

    /**
     * The same String back every time, which is what says it was built once. Comparing the text
     * would pass just as well against a value rebuilt on every call, which is the thing this
     * change is about.
     */
    @Test
    public void theLineIsBuiltOnceAndHandedBack() {
        DiagnosticEvent event = event(1_700_000_000_123L, "a message");
        String first = event.format();
        assertSame("the line is rebuilt on every call", first, event.format());
        assertSame("the line is rebuilt on every call", first, event.format());
    }

    /** What it reads as, unchanged by the caching. */
    @Test
    public void theLineStillReadsTheWayItAlwaysDid() {
        String line = event(1_700_000_000_123L, "a message").format();
        assertEquals(
                DiagnosticCategory.OTHER.value
                        + " | 2023-11-14T22:13:20.123Z | main | Source | INFO | a message",
                line);
    }

    /** UTC, not whatever the phone is set to, which is the reason for the time zone lookup. */
    @Test
    public void theTimestampIsInUtc() {
        assertTrue(event(0L, "epoch").format().contains("1970-01-01T00:00:00.000Z"));
    }

    /**
     * A formatter held per thread is only safe if each thread has its own. Shared, this produces
     * interleaved garbage rather than an exception, so the assertion is on the lines: every one
     * has to be the line its own timestamp should produce.
     */
    @Test
    public void formattingFromManyThreadsAtOnceProducesTheRightLines() throws Exception {
        int threads = 8;
        int perThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> wrong = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int index = 0; index < threads; index++) {
            final long base = 1_700_000_000_000L + index * 1_000L;
            new Thread(() -> {
                try {
                    start.await();
                    for (int count = 0; count < perThread; count++) {
                        DiagnosticEvent event = event(base + count, "m" + count);
                        String line = event.format();
                        // Same event, same instance: a formatter shared across threads would
                        // still return one string, so the check is on the content.
                        if (!line.endsWith(" | m" + count) || !line.contains("Z |")) {
                            wrong.add(line);
                        }
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue("the threads did not finish", done.await(30, TimeUnit.SECONDS));
        assertEquals("a line came out malformed under concurrent formatting", List.of(), wrong);
    }

    /**
     * The saving is the formatter, not the string.
     *
     * <p>Every other case here would pass just as happily against a fresh
     * {@link java.text.SimpleDateFormat} built inside the constructor, and that allocation, with
     * the time zone lookup and pattern parse behind it, is what this change is about. So this
     * one reads the field: one formatter per thread, kept, and not shared across threads.
     */
    @Test
    public void oneFormatterPerThreadIsKeptAndNotShared() throws Exception {
        Field field = DiagnosticEvent.class.getDeclaredField("TIMESTAMP");
        field.setAccessible(true);
        ThreadLocal<?> timestamps = (ThreadLocal<?>) field.get(null);

        Object mine = timestamps.get();
        assertSame("a formatter is built per call rather than kept per thread", mine, timestamps.get());

        AtomicReference<Object> theirs = new AtomicReference<>();
        Thread other = new Thread(() -> theirs.set(timestamps.get()));
        other.start();
        other.join();
        assertNotSame("two threads share one SimpleDateFormat", mine, theirs.get());
    }

    /** Two events a millisecond apart are two different lines, so nothing is over-shared. */
    @Test
    public void twoEventsAreNotTheSameLine() {
        assertNotEquals(
                event(1_700_000_000_123L, "a message").format(),
                event(1_700_000_000_124L, "a message").format());
    }
}
