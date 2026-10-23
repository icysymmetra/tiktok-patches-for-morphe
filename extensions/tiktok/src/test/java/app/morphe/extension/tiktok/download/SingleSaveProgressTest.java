/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** The old progress tests covered counts from three files up, not stream progress on one save. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SingleSaveProgressTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<String> spoken = new ArrayList<>();
    private ActivityController<PageActivity> owner;
    private ViewGroup root;
    private SaveProgress progress;

    @Before public void setup() {
        owner = Robolectric.buildActivity(PageActivity.class).setup().visible();
        Utils.setActivity(owner.get());
        root = owner.get().findViewById(android.R.id.content);
        SaveNotice.windowRootsForTests = List.of();
        SaveProgress.announcementsForTests = spoken;
    }

    @After public void cleanup() {
        if (progress != null) progress.run(index -> { });
        idle();
        owner.close();
        Utils.setActivity(null);
        SaveNotice.windowRootsForTests = null;
        SaveProgress.announcementsForTests = null;
    }

    @Test public void oneVideoShowsTheCopiedStreamAndStaysBusyUntilPublished() throws Exception {
        progress = SaveProgress.begin(1, true);
        settle();
        ProgressBar bar = find(root, ProgressBar.class);
        assertNotNull(bar);
        assertTrue("The server has not supplied a size yet", bar.isIndeterminate());
        assertEquals(List.of("Saving video"), spoken);
        progress.transfer(500, 1000);
        idle();
        assertFalse(bar.isIndeterminate());
        assertEquals(50, bar.getProgress());
        assertEquals("Saving video: 50%", find(root, TextView.class).getText().toString());
        assertEquals("Repeated byte updates must not interrupt TalkBack", 1, spoken.size());

        View banner = root.findViewWithTag("hushfeed_save_progress");
        banner.measure(View.MeasureSpec.makeMeasureSpec(328, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
        UiCapture.save(banner, "pages/downloads/single-save-progress.png", 328, banner.getMeasuredHeight());

        progress.transfer(1000, 1000);
        idle();
        assertEquals("Receiving the last byte is not a published file", 99, bar.getProgress());
        progress.transfer(0, -1);
        idle();
        assertTrue("Muxing and publication have no byte estimate", bar.isIndeterminate());
        assertTrue(progress.run(index -> { }).complete());
        idle();
        assertNull(root.findViewWithTag("hushfeed_save_progress"));
    }

    /**
     * Writing the caption in says so, and a single video's row, which has no Cancel while it
     * downloads, offers one for it. That Cancel stops it at once, so the row doesn't go on to
     * say "Stopping after this file".
     */
    @Test public void writingTheCaptionSaysSoAndOffersACancelThatStopsIt() {
        progress = SaveProgress.begin(1, true);
        settle();
        ViewGroup banner = root.findViewWithTag("hushfeed_save_progress");
        View cancel = banner.getChildAt(banner.getChildCount() - 1);
        progress.transfer(500, 1000);
        idle();
        assertEquals("a single download keeps no Cancel while it runs", View.GONE, cancel.getVisibility());

        progress.captioning(250_000, 1_000_000);
        idle();
        TextView label = find(root, TextView.class);
        assertEquals("Writing the caption on the video: 25%", label.getText().toString());
        assertEquals(View.VISIBLE, cancel.getVisibility());
        assertFalse(find(root, ProgressBar.class).isIndeterminate());
        assertEquals(25, find(root, ProgressBar.class).getProgress());

        cancel.performClick();
        idle();
        assertTrue(progress.isCancelled());
        assertEquals("Writing the caption on the video: 25%", label.getText().toString());
    }

    /** Once the caption is in, or given up, the tags and the publish are ordinary saving again. */
    @Test public void afterTheCaptionTheRowSavesAgainWithoutCancel() {
        progress = SaveProgress.begin(1, true);
        settle();
        ViewGroup banner = root.findViewWithTag("hushfeed_save_progress");
        View cancel = banner.getChildAt(banner.getChildCount() - 1);
        progress.transfer(500, 1000);
        progress.captioning(990_000, 1_000_000);
        idle();
        assertEquals(View.VISIBLE, cancel.getVisibility());

        progress.captionDone();
        idle();
        assertEquals("Saving video", find(root, TextView.class).getText().toString());
        assertTrue(find(root, ProgressBar.class).isIndeterminate());
        assertEquals("no Cancel once the caption step is over", View.GONE, cancel.getVisibility());
    }

    @Test @Config(qualifiers = "w360dp-h640dp-notnight-mdpi", fontScale = 2f)
    public void largeTextKeepsTheProgressLabelAndBarInsideTheBanner() throws Exception {
        progress = SaveProgress.begin(1, true);
        settle();
        progress.transfer(75, 100);
        idle();
        ViewGroup banner = root.findViewWithTag("hushfeed_save_progress");
        banner.measure(View.MeasureSpec.makeMeasureSpec(328, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        banner.layout(0, 0, 328, banner.getMeasuredHeight());
        TextView label = find(banner, TextView.class);
        ProgressBar bar = find(banner, ProgressBar.class);
        assertTrue(label.getHeight() >= label.getLayout().getHeight());
        assertTrue(bar.getWidth() > 0);
        assertTrue(bar.getBottom() <= ((View) bar.getParent()).getHeight());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, bar.getImportantForAccessibility());
        assertEquals("Saving video: 75%", label.createAccessibilityNodeInfo().getText().toString());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_NONE, label.getAccessibilityLiveRegion());
        UiCapture.save(banner, "pages/downloads/single-save-progress-large-light.png", 328, banner.getMeasuredHeight());
    }

    @Test public void disablingTheRowAndFinishingQuicklyKeepTheFeedClear() {
        progress = SaveProgress.begin(1, false);
        progress.transfer(50, 100);
        settle();
        assertNull(root.findViewWithTag("hushfeed_save_progress"));
        assertTrue(spoken.isEmpty());
        progress.run(index -> { });
        progress = SaveProgress.begin(1, true);
        progress.run(index -> { });
        settle();
        assertNull(root.findViewWithTag("hushfeed_save_progress"));
        assertTrue(spoken.isEmpty());
    }

    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2f)
    public void simultaneousSavesDoNotOverlapAtLargeTextSizes() {
        progress = SaveProgress.begin(1, true);
        settle();
        View first = root.findViewWithTag("hushfeed_save_progress");
        SaveProgress secondRun = SaveProgress.begin(1, true);
        try {
            settle();
            View second = root.getChildAt(root.getChildCount() - 1);
            assertEquals("hushfeed_save_progress", second.getTag());
            for (int pass = 0; pass < 3; pass++) {
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, 360, 640);
                idle();
            }
            assertTrue("The two progress rows overlap", second.getBottom() < first.getTop());
            assertTrue("The upper progress row must stay on screen", second.getTop() >= 0);
        } finally { secondRun.run(index -> { }); }
    }

    /**
     * Saves at once at twice the text size: one running, two waiting in sight and two more out
     * of it. The three rows and the line that counts the other two stack without touching and
     * all stay on screen, with the rows out of sight taking no room.
     */
    @Test @Config(qualifiers = "w360dp-h640dp-night-mdpi", fontScale = 2f)
    public void simultaneousJobsAndTheLineForTheRestFitAtLargeText() throws Exception {
        for (int wait = 0; wait < 500 && MediaJobScheduler.runningJobs() + MediaJobScheduler.queuedJobs() != 0; wait++) {
            Thread.sleep(10);
        }
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        List<MediaJobScheduler.Job> waiting = new ArrayList<>();
        try {
            for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
                assertTrue(MediaJobScheduler.submit("large text hold", () -> {
                    started.countDown();
                    try {
                        hold.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            progress = SaveProgress.begin(1, true);
            settle();
            waiting.add(SaveProgress.queued(1, true).submit("large video", null, () -> { }, null));
            waiting.add(SaveProgress.queued(4, false).submit("large photos", null, () -> { }, null));
            waiting.add(SaveProgress.queued(1, true).submit("out of sight", null, () -> { }, null));
            waiting.add(SaveProgress.queued(5, false).submit("out of sight", null, () -> { }, null));
            settle();
            for (int pass = 0; pass < 3; pass++) {
                root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, 360, 640);
                idle();
            }
            List<View> shown = new ArrayList<>();
            int hidden = 0;
            View line = null;
            for (int index = 0; index < root.getChildCount(); index++) {
                View child = root.getChildAt(index);
                if ("hushfeed_save_waiting_row".equals(child.getTag())) line = child;
                if (!"hushfeed_save_progress".equals(child.getTag())) continue;
                if (child.getVisibility() == View.VISIBLE) shown.add(child);
                else hidden++;
            }
            assertEquals("rows in sight", SaveProgress.MAX_ROWS, shown.size());
            assertEquals("rows out of sight", 2, hidden);
            assertNotNull("nothing counts the rows out of sight", line);
            assertEquals("2 more saves waiting",
                    ((TextView) line.findViewWithTag("hushfeed_save_waiting")).getText().toString());
            shown.sort((a, b) -> Integer.compare(b.getTop(), a.getTop()));
            for (int index = 1; index < shown.size(); index++) {
                assertTrue("row " + index + " overlaps the row below it",
                        shown.get(index).getBottom() <= shown.get(index - 1).getTop());
            }
            View highest = shown.get(shown.size() - 1);
            assertTrue("the waiting line overlaps the highest row", line.getBottom() <= highest.getTop());
            assertTrue("the waiting line went off the top of the screen", line.getTop() >= 0);
            assertTrue("a row went off the top of the screen", highest.getTop() >= 0);
            UiCapture.save(root, "pages/downloads/save-queue-large.png", 360, 640);
        } finally {
            for (MediaJobScheduler.Job job : waiting) if (job != null) job.cancel();
            hold.countDown();
            idle();
        }
    }

    @Test public void realCopyReportsWrittenBytesAndResetsAfterABadMirror() throws Exception {
        byte[] body = new byte[131072];
        System.arraycopy(new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0}, 0, body, 0, 16);
        List<Long> received = new ArrayList<>();
        List<Integer> percentages = new ArrayList<>();
        // The first body is truncated even though its header promises the whole file.
        MediaTransport.Client transport = MediaTransportFixtures.publicClient(url -> response(url,
                url.getPath().contains("broken") ? java.util.Arrays.copyOf(body, 65536) : body, body.length));
        File target = File.createTempFile("single-progress-", ".mp4", RuntimeEnvironment.getApplication().getCacheDir());
        try {
            progress = SaveProgress.begin(1, true);
            settle();
            SaveProgress.Outcome result = progress.run(index -> RemoteMedia.fetch(
                    List.of("https://cdn.example/broken", "https://cdn.example/complete"), target,
                    RemoteMedia.Kind.VIDEO, transport, (copied, expected) -> {
                        received.add(copied);
                        progress.transfer(copied, expected);
                        idle();
                        percentages.add(find(root, ProgressBar.class).getProgress());
                    }));
            idle();
            assertTrue(result.complete());
            assertArrayEquals(body, Files.readAllBytes(target.toPath()));
            assertEquals("Each mirror starts its own measurement", 2, java.util.Collections.frequency(received, 0L));
            assertEquals(Long.valueOf(body.length), received.get(received.size() - 1));
            assertTrue(percentages.stream().anyMatch(value -> value > 0 && value < 99));
            assertEquals(Integer.valueOf(99), percentages.get(percentages.size() - 1));
            assertNull(root.findViewWithTag("hushfeed_save_progress"));
            assertEquals(1, spoken.size());
        } finally { Files.deleteIfExists(target.toPath()); }
    }

    @Test public void anUnknownLengthHasNoInventedPercentageAndAFailureRemovesTheRow() throws Exception {
        File target = File.createTempFile("single-progress-", ".mp4", RuntimeEnvironment.getApplication().getCacheDir());
        byte[] body = new byte[]{0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
        try {
            progress = SaveProgress.begin(1, true);
            settle();
            MediaTransport.Client transport = MediaTransportFixtures.publicClient(url -> response(url, body, -1));
            RemoteMedia.fetch(List.of("https://cdn.example/video"), target, RemoteMedia.Kind.VIDEO, transport,
                    (copied, expected) -> {
                        assertEquals(-1, expected);
                        progress.transfer(copied, expected);
                        idle();
                        assertTrue(find(root, ProgressBar.class).isIndeterminate());
                        assertEquals("Saving video", find(root, TextView.class).getText().toString());
                    });
            SaveProgress.Outcome failed = progress.run(index -> { throw new java.io.IOException("disconnected"); });
            idle();
            assertEquals(0, failed.saved);
            assertEquals(1, failed.skipped);
            assertNull(root.findViewWithTag("hushfeed_save_progress"));
        } finally { Files.deleteIfExists(target.toPath()); }
    }

    private static HttpURLConnection response(URL url, byte[] body, int length) {
        return new FakeHttpsConnection(url) {
            @Override public int getResponseCode() { return 200; }
            @Override public String getHeaderField(String name) {
                return "Content-Length".equalsIgnoreCase(name) && length >= 0 ? String.valueOf(length) : null;
            }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
        };
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
    }
}
