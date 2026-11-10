/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/**
 * Retry on a save of original photos that half landed: it queues the photos that failed and
 * nothing else, in the post's order and under the names they first had, as often as some still
 * fail. A cancelled photo isn't a failed one, and a Retry from another account, one too old for
 * TikTok's addresses or one already used is refused with a word.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class OriginalPhotosRetryTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private final List<Integer> tried = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> names = new ConcurrentHashMap<>();
    private final Set<Integer> failing = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final List<Integer> renamed = new CopyOnWriteArrayList<>();
    private final Set<String> ownIds = new HashSet<>();
    private boolean previousOriginals, previousAdvanced;
    private ActivityController<SaveNoticeTest.HostActivity> owner;
    private ViewGroup root;

    @Before public void setUp() throws Exception {
        drainPool();
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        SignedInUser.idForTests = "account-a";
        SaveNotice.windowRootsForTests = List.of();
        previousAdvanced = SettingsStatus.advancedDownloadsEnabled;
        SettingsStatus.advancedDownloadsEnabled = false;
        OriginalPhotos.photoStepForTests = (position, name) -> {
            tried.add(position);
            String first = names.putIfAbsent(position, name);
            if (first != null && !first.equals(name)) renamed.add(position);
            if (failing.contains(position)) throw new IOException("photo " + position + " didn't come");
            return new MediaFileWriter.Saved(name, Uri.parse("content://media/external/images/media/" + position));
        };
        owner = Robolectric.buildActivity(SaveNoticeTest.HostActivity.class).setup().visible();
        Utils.setActivity(owner.get());
        root = owner.get().findViewById(android.R.id.content);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            drainPool();
            idle();
        } finally {
            OriginalPhotos.photoStepForTests = null;
            SignedInUser.idForTests = null;
            SaveNotice.windowRootsForTests = null;
            Utils.setActivity(null);
            owner.close();
            Set<String> active = ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");
            active.removeAll(ownIds);
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            SettingsStatus.advancedDownloadsEnabled = previousAdvanced;
        }
    }

    @Test public void retryTakesOnlyTheFailedPhotosInOrderUntilEveryOneLands() throws Exception {
        var post = post("retry-mixed", 5);
        failing.addAll(Set.of(1, 4));
        // Photo 2 isn't picked, so nothing ever tries it.
        assertTrue(OriginalPhotos.startPhotos(post, Set.of(4, 0, 3, 1), false));
        finish();
        assertEquals(List.of(0, 1, 3, 4), tried);
        assertNotNull(find(root, "Saved 2 of 4, 2 skipped"));
        assertNotNull("the saved photos can still be opened", find(root, "Open"));

        tried.clear();
        failing.remove(1);
        tap("Retry");
        finish();
        assertEquals("only the failed photos, in the post's order", List.of(1, 4), tried);
        assertNotNull(find(root, "Saved 1 of 2, 1 skipped"));

        tried.clear();
        failing.clear();
        tap("Retry");
        finish();
        assertEquals("the photo that landed on the last try isn't saved again", List.of(4), tried);
        assertNotNull(find(root, "Saved one original photo"));
        assertNull("nothing is left to retry", find(root, "Retry"));
        assertEquals("a retried photo keeps the name it first had", List.of(), renamed);
    }

    @Test public void aRetryUnderAnotherAccountIsRefusedAndQueuesNothing() throws Exception {
        var post = post("retry-account", 3);
        failing.add(2);
        assertTrue(OriginalPhotos.startPhotos(post, null, false));
        finish();
        assertEquals(List.of(0, 1, 2), tried);

        tried.clear();
        SignedInUser.idForTests = "account-b";
        tap("Retry");
        finish();
        assertEquals(List.of(), tried);
        assertNull(MediaJobScheduler.job("photos " + post.getAid()));
        assertEquals("Those photos can't be tried again now. Save the post again to get the rest.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void aRetryRunsOnceAndNotPastItsWindow() {
        long now = SystemClock.elapsedRealtime();
        OriginalPhotos.Retry fresh = retry(List.of(1), now);
        assertTrue(fresh.stillUsable());
        assertFalse("a Retry runs once", fresh.stillUsable());

        OriginalPhotos.Retry old = retry(List.of(1), now - OriginalPhotos.RETRY_WINDOW_MS - 1);
        assertFalse("TikTok's addresses are too old by then", old.stillUsable());

        SignedInUser.idForTests = "";
        assertFalse("signed out since a signed-in save", retry(List.of(1), now).stillUsable());
    }

    @Test public void aRetryWhileThePostIsStillSavingSaysSoAndStaysUsable() throws Exception {
        OriginalPhotos.Retry failed = retry(List.of(1), SystemClock.elapsedRealtime());
        Set<String> active = ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");
        ownIds.add(failed.id);
        active.add(failed.id);
        OriginalPhotos.retry(failed);
        finish();
        assertEquals("the save still running is the one that goes on", List.of(), tried);
        assertEquals(MediaJobScheduler.busyMessage("photos " + failed.id), ShadowToast.getTextOfLatestToast());

        active.remove(failed.id);
        OriginalPhotos.retry(failed);
        finish();
        assertEquals("the Retry wasn't used up by the busy tap", List.of(1), tried);
    }

    @Test public void cancelledPhotosAreNotFailedOnes() {
        // The first file fails, Cancel comes during the second, and the last three are never tried.
        SaveProgress progress = SaveProgress.begin(5);
        SaveProgress.Outcome outcome = progress.run(index -> {
            if (index == 0) throw new IOException("this one didn't come");
            progress.cancel();
        });
        assertEquals(1, outcome.saved);
        assertEquals(3, outcome.cancelled);
        assertEquals(List.of(0), outcome.unsaved);

        OriginalPhotos.Retry failed = retry(List.of(2, 5, 6, 7, 8), SystemClock.elapsedRealtime()).failed(outcome);
        assertEquals("only the failed photo, by its place in the post", List.of(2), failed.positions);

        SaveProgress.Outcome allCancelled = new SaveProgress.Outcome(3, 1, 0, 2);
        assertNull("nothing failed, so there's no Retry", retry(List.of(0, 1, 2), 0).failed(allCancelled));
    }

    @Test public void aRefusalFromTheDiskLeavesTheRefusedFileAndTheRestToRetry() {
        SaveProgress.Outcome outcome = SaveProgress.begin(5).run(index -> {
            if (index == 1) throw new IOException("this one didn't come");
            if (index == 3) throw new MediaBudget.StopException("no room", true);
        });
        assertEquals(SaveProgress.Stop.NO_SPACE, outcome.stop);
        assertEquals(List.of(1, 3, 4), outcome.unsaved);
    }

    private OriginalPhotos.Retry retry(List<Integer> positions, long madeAt) {
        List<List<String>> photos = new ArrayList<>();
        List<String> photoNames = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            photos.add(List.of("https://example.com/" + i));
            photoNames.add("photo-" + i + ".tmp");
        }
        return new OriginalPhotos.Retry("retry-unit", "account-a", photos, photoNames, "Pictures/TikTok",
                positions, madeAt);
    }

    private AdvancedDownloadsTest.PhotoPost post(String id, int count) {
        ownIds.add(id);
        List<AdvancedDownloadsTest.Photo> images = new ArrayList<>();
        for (int i = 0; i < count; i++) images.add(new AdvancedDownloadsTest.Photo("https://example.com/" + id + "/" + i));
        return new AdvancedDownloadsTest.PhotoPost(id, images);
    }

    /** Lets the save run, then the banner come up after the sheet's settle. */
    private void finish() throws Exception {
        drainPool();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(SaveNotice.SHEET_SETTLE_MS + 1, TimeUnit.MILLISECONDS);
        idle();
    }

    private void tap(String label) {
        TextView action = find(root, label);
        assertNotNull("no " + label + " on the banner", action);
        assertTrue(action.performClick());
        idle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void drainPool() throws Exception {
        for (int wait = 0; wait < 500; wait++) {
            if (MediaJobScheduler.idle()) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("The media pool never emptied");
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
