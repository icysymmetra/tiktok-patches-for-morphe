/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.atDeath;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.await;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.records;
import static app.morphe.extension.tiktok.download.SaveRecordsFixtures.startAgain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The one notice a start gets: what it says in each language the bundle carries, that it waits
 * for the main screen and is said once, that it's consumed only once its banner has run its time,
 * and that a save made again afterwards is its own save, followed from scratch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en")
public class UnfinishedSavesTest {
    /** Longer than the notice banner's six seconds, so it has run its time. */
    private static final long BANNER_TIME_MS = 10_000;

    private Context context;
    private final List<String> shown = new CopyOnWriteArrayList<>();
    private final List<ActivityController<Activity>> screens = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        SaveRecordsFixtures.reset(context);
        UnfinishedSaves.shownForTests = shown;
    }

    @After public void tearDown() throws Exception {
        UnfinishedSaves.shownForTests = null;
        java.lang.reflect.Method dismiss = BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        for (ActivityController<Activity> screen : screens) screen.pause().stop().destroy();
        Utils.setActivity(null);
        SaveRecordsFixtures.reset(context);
    }

    // -----------------------------------------------------------------------------------------
    // The words, in all six languages.
    // -----------------------------------------------------------------------------------------

    /**
     * A video that never landed, a photo save with three of five files missing and one it can't
     * confirm, and a story it can't confirm; then three more than the banner has lines for.
     */
    private static SaveRecords.Report report() {
        List<SaveRecords.Unfinished> saves = new ArrayList<>();
        saves.add(new SaveRecords.Unfinished("a", "video", 1, 0, 1, 0));
        saves.add(new SaveRecords.Unfinished("b", "original photos", 5, 1, 3, 1));
        saves.add(new SaveRecords.Unfinished("c", "story", 1, 0, 0, 1));
        saves.add(new SaveRecords.Unfinished("d", "sound", 1, 0, 1, 0));
        saves.add(new SaveRecords.Unfinished("e", "sticker", 1, 0, 1, 0));
        saves.add(new SaveRecords.Unfinished("f", "profile picture", 1, 0, 1, 0));
        return new SaveRecords.Report(saves);
    }

    private void assertSays(String... lines) {
        assertEquals(String.join("\n", lines), UnfinishedSaves.message(context, report()));
    }

    /** One file of five missing, so the verb agrees with one, not with five. */
    private void assertOneOfFiveSays(String line) {
        List<SaveRecords.Unfinished> saves = new ArrayList<>();
        saves.add(new SaveRecords.Unfinished("a", "original photos", 5, 4, 1, 0));
        String message = UnfinishedSaves.message(context, new SaveRecords.Report(saves));
        assertEquals(line, message.substring(message.indexOf('\n') + 1));
    }

    @Test public void englishNamesWhatDidNotFinishAndPromisesNothing() {
        assertSays("TikTok closed during these saves",
                "Video: didn't finish",
                "Original photos: 3 of 5 files didn't finish",
                "Original photos: one of 5 files might not have finished",
                "Story: might not have finished",
                "And 3 more saves");
        assertOneOfFiveSays("Original photos: one of 5 files didn't finish");
        String text = UnfinishedSaves.message(context, report()).toLowerCase(Locale.ROOT);
        for (String promise : new String[]{"resum", "automatic", "will ", "continue", "failed"}) {
            assertFalse("the notice says " + promise, text.contains(promise));
        }
    }

    @Test @Config(qualifiers = "de")
    public void german() {
        assertSays("TikTok wurde während dieser Speicherungen geschlossen",
                "Video: nicht fertig geworden",
                "Originalfotos: 3 von 5 Dateien nicht fertig geworden",
                "Originalfotos: eine von 5 Dateien möglicherweise nicht fertig geworden",
                "Story: möglicherweise nicht fertig geworden",
                "Und 3 weitere Speicherungen");
        assertOneOfFiveSays("Originalfotos: eine von 5 Dateien nicht fertig geworden");
    }

    @Test @Config(qualifiers = "es")
    public void spanish() {
        assertSays("TikTok se cerró durante estos guardados",
                "Vídeo: no terminó",
                "Fotos originales: 3 de 5 archivos no terminaron",
                "Fotos originales: puede que uno de 5 archivos no haya terminado",
                "Historia: puede que no haya terminado",
                "Y otros 3 guardados");
        assertOneOfFiveSays("Fotos originales: uno de 5 archivos no terminó");
    }

    @Test @Config(qualifiers = "in-rID")
    public void indonesian() {
        assertSays("TikTok tertutup saat penyimpanan ini berjalan",
                "Video: tidak selesai",
                "Foto asli: 3 dari 5 berkas tidak selesai",
                "Foto asli: 1 dari 5 berkas mungkin tidak selesai",
                "Story: mungkin tidak selesai",
                "Dan 3 penyimpanan lain");
        // Indonesian has no one form: the other form reads right for every count.
        assertOneOfFiveSays("Foto asli: 1 dari 5 berkas tidak selesai");
    }

    @Test @Config(qualifiers = "pt-rBR")
    public void brazilianPortuguese() {
        assertSays("O TikTok fechou durante estes salvamentos",
                "Vídeo: não terminou",
                "Fotos originais: 3 de 5 arquivos não terminaram",
                "Fotos originais: um de 5 arquivos pode não ter terminado",
                "Story: pode não ter terminado",
                "E mais 3 salvamentos");
        assertOneOfFiveSays("Fotos originais: um de 5 arquivos não terminou");
    }

    @Test @Config(qualifiers = "tr")
    public void turkish() {
        assertSays("TikTok bu kayıtlar sırasında kapandı",
                "Video: tamamlanmadı",
                "Orijinal fotoğraflar: 5 dosyadan 3 tanesi tamamlanmadı",
                "Orijinal fotoğraflar: 5 dosyadan biri tamamlanmamış olabilir",
                "Hikaye: tamamlanmamış olabilir",
                "Ve 3 kayıt daha");
        assertOneOfFiveSays("Orijinal fotoğraflar: 5 dosyadan biri tamamlanmadı");
    }

    /** One left over takes the one form, and every kind has a name of its own. */
    @Test public void oneMoreSaveAndEveryKindIsNamed() {
        List<SaveRecords.Unfinished> saves = new ArrayList<>();
        String[] kinds = {"video", "original photos", "story", "sound", "original-sound"};
        for (String kind : kinds) saves.add(new SaveRecords.Unfinished(kind, kind, 1, 0, 1, 0));
        assertEquals("TikTok closed during these saves\nVideo: didn't finish\n"
                        + "Original photos: didn't finish\nStory: didn't finish\nSound: didn't finish\n"
                        + "And one more save",
                UnfinishedSaves.message(context, new SaveRecords.Report(saves)));
        assertEquals("Original sound", UnfinishedSaves.kindLabel(context, "original-sound"));
        assertEquals("Profile picture", UnfinishedSaves.kindLabel(context, "profile picture"));
        assertEquals("Sticker", UnfinishedSaves.kindLabel(context, "sticker"));
        assertEquals("Live photo clip", UnfinishedSaves.kindLabel(context, "comment live photo"));
        assertEquals("Video of photos", UnfinishedSaves.kindLabel(context, "photo video"));
        assertEquals("Video frame", UnfinishedSaves.kindLabel(context, FrameSaver.LABEL));
        assertEquals("Media save", UnfinishedSaves.kindLabel(context, SaveRecords.OTHER));
    }

    // -----------------------------------------------------------------------------------------
    // When it's said, and what it consumes.
    // -----------------------------------------------------------------------------------------

    private static void idleFor(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    /** A screen that isn't the main one, then the main one, as TikTok's main activity hook sets it. */
    private Activity screen(boolean main) {
        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).create();
        screens.add(controller);
        if (main) Utils.setContext(controller.get());
        controller.start().resume().visible();
        return controller.get();
    }

    /** An earlier process that died with a video save open and nothing published. */
    private void anEarlierProcessDiedSavingAVideo() throws Exception {
        SaveRecords.accepted(SaveRecords.open("video", 1));
        startAgain(context, atDeath(context));
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
                TextView found = findText(((ViewGroup) view).getChildAt(index), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The start comes up before any screen. Nothing is said until the main screen has been in
     * front a moment, then it's said once, on that screen, and the record is gone.
     */
    @Test public void theNoticeWaitsForTheMainScreenAndIsSaidOnce() throws Exception {
        anEarlierProcessDiedSavingAVideo();

        UnfinishedSaves.atStart(context);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertTrue("said with no screen up", shown.isEmpty());

        screen(false);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertTrue("said on a screen that isn't the main one", shown.isEmpty());
        assertEquals("consumed before it was said", 1, records(context).length());

        Activity main = screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS / 2);
        assertTrue("said before the screen settled", shown.isEmpty());
        idleFor(UnfinishedSaves.SETTLE_MS);
        String expected = "TikTok closed during these saves\nVideo: didn't finish";
        assertEquals(List.of(expected), shown);
        assertNotNull("the banner isn't on the main screen",
                findText(main.findViewById(android.R.id.content), expected));
        assertEquals("consumed before the banner ran its time", 1, records(context).length());
        idleFor(BANNER_TIME_MS);
        assertEquals("the notice's record wasn't consumed", 0, records(context).length());

        // Coming back to the screen, or a second call in the same process, says nothing more.
        UnfinishedSaves.atStart(context);
        screens.get(screens.size() - 1).pause().resume();
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertEquals(1, shown.size());

        // Nor does the next start: it was consumed.
        startAgain(context, atDeath(context));
        UnfinishedSaves.atStart(context);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertEquals(1, shown.size());
    }

    /**
     * The main screen recreated inside the settle, as a rotation or a theme change does it: the
     * new one is the screen in front, so it's the one told, and only once.
     */
    @Test public void aMainScreenRecreatedInsideTheSettleIsStillTold() throws Exception {
        anEarlierProcessDiedSavingAVideo();
        UnfinishedSaves.atStart(context);
        idleFor(10);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS / 2);

        ActivityController<Activity> old = screens.remove(screens.size() - 1);
        old.pause().stop().destroy();
        Activity recreated = screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS + 100);

        String expected = "TikTok closed during these saves\nVideo: didn't finish";
        assertEquals("the recreated main screen wasn't told", List.of(expected), shown);
        // The banner is up for six seconds, so it's looked for while it is.
        assertNotNull("the banner isn't on the recreated screen",
                findText(recreated.findViewById(android.R.id.content), expected));
        idleFor(10_000);
        assertEquals("the check armed for the old screen said it again", 1, shown.size());
    }

    /**
     * Another banner inside the notice's six seconds takes it down early. Nothing says it was
     * read, so the record stays and the next start says it again, then forgets it once it has
     * been up for its whole time.
     */
    @Test public void aNoticeAnotherBannerReplacedIsSaidAgainOnTheNextStart() throws Exception {
        anEarlierProcessDiedSavingAVideo();
        UnfinishedSaves.atStart(context);
        Activity main = screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS + 100);
        String expected = "TikTok closed during these saves\nVideo: didn't finish";
        assertEquals(List.of(expected), shown);

        ViewGroup content = main.findViewById(android.R.id.content);
        BlockAuthorOverlay.showNoticeBanner(content, "Something else to say");
        idleFor(10);
        assertNull("the notice is still up beside the new banner", findText(content, expected));
        idleFor(BANNER_TIME_MS);
        assertEquals("a notice cut short was consumed", 1, records(context).length());

        startAgain(context, atDeath(context));
        UnfinishedSaves.atStart(context);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS + 100);
        assertEquals("the next start didn't say it again", List.of(expected, expected), shown);
        idleFor(BANNER_TIME_MS);
        assertEquals("the notice that ran its time wasn't consumed", 0, records(context).length());

        startAgain(context, atDeath(context));
        UnfinishedSaves.atStart(context);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertEquals("said a third time", 2, shown.size());
    }

    /**
     * The reader backs out of TikTok a second after the notice goes up. Its timeout still comes,
     * over a screen that's gone, and that isn't a notice read out in full: the next start says it
     * again.
     */
    @Test public void aNoticeWhoseScreenWentAwayInsideItsTimeIsSaidAgain() throws Exception {
        anEarlierProcessDiedSavingAVideo();
        UnfinishedSaves.atStart(context);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS + 100);
        String expected = "TikTok closed during these saves\nVideo: didn't finish";
        assertEquals(List.of(expected), shown);

        idleFor(1000);
        screens.remove(screens.size() - 1).pause().stop().destroy();
        idleFor(BANNER_TIME_MS);
        assertEquals("a notice whose screen went away one second in was consumed", 1,
                records(context).length());

        startAgain(context, atDeath(context));
        UnfinishedSaves.atStart(context);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS + 100);
        assertEquals("the next start didn't say it again", List.of(expected, expected), shown);
        idleFor(BANNER_TIME_MS);
        assertEquals("the notice that ran its time wasn't consumed", 0, records(context).length());
    }

    /** A start that never shows the main screen (a push, a background job) keeps it for one that does. */
    @Test public void aStartWithNoScreenKeepsTheRecordsForTheNext() throws Exception {
        anEarlierProcessDiedSavingAVideo();
        UnfinishedSaves.atStart(context);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertTrue(shown.isEmpty());

        startAgain(context, atDeath(context));
        UnfinishedSaves.atStart(context);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertEquals(List.of("TikTok closed during these saves\nVideo: didn't finish"), shown);
    }

    /**
     * The retry. After the notice the reader saves the video again: that save is followed from
     * scratch, closes when it's over, and the next start has nothing to say about either. Cut off
     * in its turn, it's named in its turn, and only it.
     */
    @Test public void aSaveMadeAgainAfterTheNoticeIsItsOwnSave() throws Exception {
        anEarlierProcessDiedSavingAVideo();
        UnfinishedSaves.atStart(context);
        screen(true);
        idleFor(UnfinishedSaves.SETTLE_MS * 2);
        assertEquals(1, shown.size());
        idleFor(BANNER_TIME_MS);

        CountDownLatch finished = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit("video", "video again", 1, () -> { }, finished::countDown));
        await(finished);
        SaveRecordsFixtures.drain();
        assertEquals("the retry that finished left a record", 0, records(context).length());
        startAgain(context, atDeath(context));
        assertTrue(SaveRecords.reconcile(context).saves.isEmpty());

        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        assertNotNull(MediaJobScheduler.submit("video", "video again", 1, () -> {
            started.countDown();
            await(hold);
        }, null));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        byte[] died = atDeath(context);
        hold.countDown();
        startAgain(context, died);
        SaveRecords.Report report = SaveRecords.reconcile(context);
        assertEquals(1, report.saves.size());
        assertEquals("video", report.saves.get(0).kind);
    }
}
