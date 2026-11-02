package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Which sticker the Save button on a reused preview sheet is holding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StickerGallerySaverTest {
    /** Stands in for the source sticker, TikTok's StickerItem, whose members keep their names. */
    private static StickerItemFake sticker(String url) {
        return StickerItemFake.of(StickerGallerySaver.TYPE_STATIC, "png", url);
    }

    /** The preview model TikTok binds to the sheet; the source is registered against it. */
    public static final class PreviewModel {
    }

    /**
     * Below Android 10 a sticker is written straight to external storage rather than handed
     * to MediaStore. That path built a File from the display name and opened it, so the
     * second save of the same sticker replaced the first, and its cleanup deleted whatever
     * was at that name whether or not this save had put it there.
     */
    @Test public void savingTheSameStickerTwiceLeavesTwoFiles() throws Exception {
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        android.graphics.Bitmap sticker =
                android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888);
        sticker.eraseColor(android.graphics.Color.RED);

        Method save = StickerGallerySaver.class.getDeclaredMethod(
                "saveBitmapWithLegacyStorage", android.content.Context.class,
                android.graphics.Bitmap.class, String.class);
        save.setAccessible(true);

        java.io.File first = (java.io.File) save.invoke(
                null, RuntimeEnvironment.getApplication(), sticker, "sticker.png");
        java.io.File second = (java.io.File) save.invoke(
                null, RuntimeEnvironment.getApplication(), sticker, "sticker.png");

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals("the second save replaced the first",
                first.getAbsolutePath(), second.getAbsolutePath());
        assertTrue("the first file went away", first.isFile());
        assertTrue("the second file was not written", second.isFile());
        assertTrue("the first file is empty", first.length() > 0);
        assertTrue("the second file is empty", second.length() > 0);
        assertEquals("sticker.png", first.getName());
        assertEquals("sticker_2.png", second.getName());

        // And a third, so the suffix counts on rather than sticking at _2.
        java.io.File third = (java.io.File) save.invoke(
                null, RuntimeEnvironment.getApplication(), sticker, "sticker.png");
        assertEquals("sticker_3.png", third.getName());
        assertTrue(first.isFile() && second.isFile() && third.isFile());
    }

    /**
     * The path a save reports has to be the file that was written.
     *
     * <p>The display name handed to MediaStore is a request. A duplicate gets a suffix of the
     * provider's choosing, and reporting the requested name points the reader at a file that is
     * not theirs. The provider is stood in for here, because there is no MediaStore under a unit
     * test; what is under test is whether the answer it gives is the one reported.
     */
    @Test public void theReportedPathIsTheNameTheGalleryUsed() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);

        android.net.Uri uri = android.net.Uri.withAppendedPath(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "42");
        org.robolectric.fakes.RoboCursor cursor = new org.robolectric.fakes.RoboCursor();
        cursor.setColumnNames(java.util.List.of(
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME));
        cursor.setResults(new Object[][]{{"sticker_2.png"}});
        org.robolectric.Shadows.shadowOf(context.getContentResolver()).setCursor(uri, cursor);

        String reported = (String) savedPath().invoke(null, context, uri, "sticker.png", false);
        assertTrue("the reported path is the name that was asked for, not the one used: "
                + reported, reported.endsWith("/sticker_2.png"));
    }

    /** A provider that will not say leaves the reader with the best guess rather than nothing. */
    @Test public void anUnreadableRowStillReportsSomewhere() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);

        android.net.Uri missing = android.net.Uri.withAppendedPath(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "404404");
        String reported = (String) savedPath().invoke(null, context, missing, "sticker.png", false);
        assertTrue(reported, reported.endsWith("/sticker.png"));
    }

    private static Method savedPath() throws Exception {
        Method method = StickerGallerySaver.class.getDeclaredMethod(
                "savedPath", android.content.Context.class, android.net.Uri.class,
                String.class, boolean.class);
        method.setAccessible(true);
        return method;
    }

    /** A name with no extension is a name, not a reason to build sticker_2 out of nothing. */
    @Test public void aNameWithNoExtensionStillGetsItsOwnFile() throws Exception {
        java.io.File directory = new java.io.File(
                RuntimeEnvironment.getApplication().getCacheDir(), "claim-test");
        assertTrue(directory.mkdirs() || directory.isDirectory());
        assertEquals("sticker", MediaFileWriter.claim(directory, "sticker").getName());
        assertEquals("sticker_2", MediaFileWriter.claim(directory, "sticker").getName());
        assertEquals("a.b.png", MediaFileWriter.claim(directory, "a.b.png").getName());
        assertEquals("a.b_2.png", MediaFileWriter.claim(directory, "a.b.png").getName());
    }

    @Test public void aReusedSheetHoldsTheStickerItIsShowingNow() throws Exception {
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        StickerSheet sheet = new StickerSheet(RuntimeEnvironment.getApplication());

        PreviewModel first = new PreviewModel();
        StickerGallerySaver.registerStickerSource(first, sticker("https://example.invalid/first.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, first);
        View save = sheet.actions.getChildAt(2);
        assertEquals("https://example.invalid/first.png", url(attached().get(sheet)));

        // The reader closes it and opens a different sticker. TikTok binds the same sheet.
        PreviewModel second = new PreviewModel();
        StickerGallerySaver.registerStickerSource(second, sticker("https://example.invalid/second.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, second);

        // The button reads this when it is pressed, so it has to be the sticker on screen.
        assertEquals("https://example.invalid/second.png", url(attached().get(sheet)));
        assertEquals(3, sheet.actions.getChildCount());
        assertSame(save, sheet.actions.getChildAt(2));
    }

    /**
     * Issue #23: the Save media button vanished on TikTok 47.0.3. The sheet's two action buttons
     * were looked up by their obfuscated class name, which TikTok renames with every build, so
     * the lookup found nothing and gave up quietly. The sheet is recognised by its own shape now:
     * the fields of one shared TextView subclass are its actions, whatever that class is called.
     */
    @Test public void theSaveButtonJoinsTheSheetsOwnActionsWhateverTheirClassIsCalled() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        StickerSheet sheet = new StickerSheet(context);

        PreviewModel preview = new PreviewModel();
        StickerGallerySaver.registerStickerSource(preview, sticker("https://example.invalid/sticker.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertEquals("the Save media button was not added", 3, sheet.actions.getChildCount());
        assertSame(sheet.share, sheet.actions.getChildAt(0));
        assertSame(sheet.favorite, sheet.actions.getChildAt(1));
        View save = sheet.actions.getChildAt(2);
        assertEquals("morphe_save_media", save.getTag());
        assertEquals("the caption was taken for an action", 1, sheet.indexOfChild(sheet.caption));

        // The same sheet bound again for the next sticker keeps one button.
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        assertEquals(3, sheet.actions.getChildCount());
    }

    @Test public void actionsThatArriveAfterTheFirstBindStillGetASaveButton() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        StickerSheet sheet = new StickerSheet(context);
        sheet.actions.removeAllViews();

        PreviewModel first = new PreviewModel();
        StickerGallerySaver.registerStickerSource(first, sticker("https://example.invalid/first.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, first);
        assertEquals(0, sheet.actions.getChildCount());
        String report = app.morphe.extension.shared.diagnostics.HookStatus.report().toString();
        assertTrue(report, report.contains("sticker saves") && report.contains("1 missing"));

        sheet.actions.addView(sheet.share);
        sheet.actions.addView(sheet.favorite);
        PreviewModel second = new PreviewModel();
        StickerGallerySaver.registerStickerSource(second, sticker("https://example.invalid/second.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, second);

        assertEquals(3, sheet.actions.getChildCount());
        assertSame(sheet.share, sheet.actions.getChildAt(0));
        assertSame(sheet.favorite, sheet.actions.getChildAt(1));
        assertEquals("morphe_save_media", sheet.actions.getChildAt(2).getTag());
        assertEquals("https://example.invalid/second.png", url(attached().get(sheet)));
        StickerGallerySaver.attachSaveImageButton(sheet, second);
        assertEquals(3, sheet.actions.getChildCount());
    }

    @Test public void aReplacementActionParentGetsItsOwnSaveButton() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        StickerSheet sheet = new StickerSheet(context);
        PreviewModel preview = new PreviewModel();
        StickerGallerySaver.registerStickerSource(preview, sticker("https://example.invalid/sticker.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        android.widget.LinearLayout previous = sheet.actions;
        View previousSave = previous.getChildAt(2);

        int position = sheet.indexOfChild(previous);
        sheet.removeView(previous);
        previous.removeView(sheet.share);
        previous.removeView(sheet.favorite);
        sheet.actions = new android.widget.LinearLayout(context);
        sheet.actions.addView(sheet.share);
        sheet.actions.addView(sheet.favorite);
        sheet.addView(sheet.actions, position);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertEquals(3, sheet.actions.getChildCount());
        assertSame(sheet.share, sheet.actions.getChildAt(0));
        assertSame(sheet.favorite, sheet.actions.getChildAt(1));
        assertEquals("morphe_save_media", sheet.actions.getChildAt(2).getTag());
        assertEquals(1, previous.getChildCount());
        assertSame(previousSave, previous.getChildAt(0));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        assertEquals(3, sheet.actions.getChildCount());
    }

    @Test public void aRemovedSaveButtonIsRestoredOnTheNextBind() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        StickerSheet sheet = new StickerSheet(context);
        PreviewModel preview = new PreviewModel();
        StickerGallerySaver.registerStickerSource(preview, sticker("https://example.invalid/sticker.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        sheet.actions.removeViewAt(2);

        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertEquals(3, sheet.actions.getChildCount());
        assertSame(sheet.share, sheet.actions.getChildAt(0));
        assertSame(sheet.favorite, sheet.actions.getChildAt(1));
        assertEquals("morphe_save_media", sheet.actions.getChildAt(2).getTag());
    }

    @Test public void anUnreadableRebindForgetsThePreviouslyShownSticker() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        StickerSheet sheet = new StickerSheet(context);
        PreviewModel preview = new PreviewModel();
        StickerGallerySaver.registerStickerSource(preview, sticker("https://example.invalid/sticker.png"));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        View save = sheet.actions.getChildAt(2);

        StickerGallerySaver.attachSaveImageButton(sheet, new PreviewModel());

        assertFalse(attached().containsKey(sheet));
        assertEquals(3, sheet.actions.getChildCount());
        assertSame(save, sheet.actions.getChildAt(2));
    }

    /**
     * A sticker save waiting behind busy workers is said at once with its wait, keeps its button
     * busy, hands the button back exactly once when it is cancelled, and can be asked for again.
     */
    @Test public void aWaitingStickerSaveHandsItsButtonBackOnceAndCanBeAskedAgain() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        StickerSheet sheet = new StickerSheet(context);
        PreviewModel preview = new PreviewModel();
        String url = "https://example.invalid/queued.png";
        StickerGallerySaver.registerStickerSource(preview, sticker(url));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        View save = sheet.actions.getChildAt(2);
        assertTrue(save.isEnabled());

        for (int wait = 0; wait < 500 && MediaJobScheduler.runningJobs() + MediaJobScheduler.queuedJobs() != 0; wait++) {
            Thread.sleep(10);
        }
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        for (int index = 0; index < MediaJobScheduler.MAX_RUNNING_JOBS; index++) {
            assertTrue(MediaJobScheduler.submit("sticker test hold", () -> {
                started.countDown();
                try {
                    hold.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        try {
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            org.robolectric.shadows.ShadowToast.reset();
            save.performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("the button took a second press while its save waited", save.isEnabled());
            assertEquals("Saving the sticker\nStarts after one other save",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());

            MediaJobScheduler.Job waiting = MediaJobScheduler.job("sticker " + url);
            assertNotNull(waiting);
            assertTrue(waiting.cancel());
            assertFalse(waiting.cancel());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("the button stayed busy after its save was cancelled", save.isEnabled());

            save.performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("the second press was not taken", save.isEnabled());
            MediaJobScheduler.Job again = MediaJobScheduler.job("sticker " + url);
            assertNotNull(again);
            assertTrue(again.cancel());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(save.isEnabled());
        } finally {
            hold.countDown();
        }
    }

    /** A sheet with no pair of like-typed actions is left alone and named in the export. */
    @Test public void aSheetWithNoActionPairIsLeftAloneAndReported() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        android.widget.LinearLayout bare = new android.widget.LinearLayout(context);
        bare.addView(new android.widget.TextView(context));

        PreviewModel preview = new PreviewModel();
        StickerGallerySaver.registerStickerSource(preview, sticker("https://example.invalid/sticker.png"));
        StickerGallerySaver.attachSaveImageButton(bare, preview);

        assertEquals(1, bare.getChildCount());
        String report = app.morphe.extension.shared.diagnostics.HookStatus.report().toString();
        assertTrue(report, report.contains("sticker saves") && report.contains("1 missing"));
    }

    /** TikTok's action button: a TextView subclass with a name that changes every build. */
    private static final class RenamedEveryBuild extends android.widget.TextView {
        RenamedEveryBuild(android.content.Context context) { super(context); }
    }

    /** The preview row's shape: a caption, a picture, two icons and two like-typed actions. */
    private static final class StickerSheet extends android.widget.LinearLayout {
        final android.widget.TextView caption;
        final android.widget.ImageView picture;
        final RenamedEveryBuild favorite;
        final RenamedEveryBuild share;
        final android.widget.ImageView close;
        android.widget.LinearLayout actions;

        StickerSheet(android.content.Context context) {
            super(context);
            setOrientation(VERTICAL);
            picture = new android.widget.ImageView(context);
            caption = new android.widget.TextView(context);
            close = new android.widget.ImageView(context);
            share = new RenamedEveryBuild(context);
            favorite = new RenamedEveryBuild(context);
            actions = new android.widget.LinearLayout(context);
            actions.addView(share);
            actions.addView(favorite);
            addView(picture);
            addView(caption);
            addView(actions);
            addView(close);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<View, Object> attached() throws Exception {
        Field f = StickerGallerySaver.class.getDeclaredField("ATTACHED_SHEETS");
        f.setAccessible(true);
        return (Map<View, Object>) f.get(null);
    }

    private static String url(Object asset) throws Exception {
        assertNotNull("nothing recorded for the sheet", asset);
        Field f = asset.getClass().getDeclaredField("url");
        f.setAccessible(true);
        return (String) f.get(asset);
    }

    @Test public void aRunningSaveDoesNotHoldTheSheetThatStartedIt() {
        View button = new View(RuntimeEnvironment.getApplication());
        java.lang.ref.WeakReference<View> anchor = new java.lang.ref.WeakReference<>(button);
        StickerGallerySaver.StickerAsset asset =
                new StickerGallerySaver.StickerAsset("https://cdn.example/sticker.webp", false);

        Runnable work = StickerGallerySaver.stickerSaveWork(
                RuntimeEnvironment.getApplication(), asset);
        Runnable rejected = StickerGallerySaver.handBackLater(anchor);

        // The control: a closure that does capture the button has to be found, or the walk below
        // proves nothing about the two that should not.
        Runnable capturing = () -> button.setEnabled(true);
        assertTrue("the reachability walk cannot even see a captured view",
                reaches(capturing, button));

        assertFalse("a queued sticker save holds the button, and through it the Activity",
                reaches(work, button));
        assertFalse("a rejected sticker save holds the button",
                reaches(rejected, button));
    }

    @Test public void aSaveThatNeverRanStillHandsTheButtonBack() {
        View button = new View(RuntimeEnvironment.getApplication());
        button.setEnabled(false);
        StickerGallerySaver.handBackLater(new java.lang.ref.WeakReference<>(button)).run();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("the Save button was left disabled for good", button.isEnabled());
    }

    /**
     * Whether {@code target} can be reached from {@code root} by strong references only.
     *
     * <p>A {@link java.lang.ref.Reference} is a stop: what it points at is exactly what this is
     * checking is not held. A {@link android.content.Context} is a stop too, because the
     * application context is a process-lifetime object the save is meant to carry, and walking
     * into it would drag in every Activity the test framework knows about.
     */
    private static boolean reaches(Object root, Object target) {
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Object current = pending.poll();
            if (current == null || seen.put(current, Boolean.TRUE) != null) continue;
            if (current == target) return true;
            if (current instanceof java.lang.ref.Reference
                    || current instanceof android.content.Context
                    || current instanceof Class
                    || current instanceof ClassLoader
                    || current instanceof String) {
                continue;
            }
            Class<?> type = current.getClass();
            if (type.isArray()) {
                if (!type.getComponentType().isPrimitive()) {
                    int length = java.lang.reflect.Array.getLength(current);
                    for (int index = 0; index < length; index++) {
                        pending.add(java.lang.reflect.Array.get(current, index));
                    }
                }
                continue;
            }
            for (Class<?> level = type; level != null && level != Object.class;
                    level = level.getSuperclass()) {
                for (Field field : level.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    if (field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        pending.add(field.get(current));
                    } catch (Throwable closed) {
                        // A platform internal that will not open. Nothing of ours is behind one.
                    }
                }
            }
        }
        return false;
    }

    @Config(qualifiers = "de")
    @Test public void theSaveButtonIsTranslatedAndStillOnlyAddedOnce() throws Exception {
        // The button's own label was the check for "this sheet already has one", so translating
        // it would have put a second button on every German sheet.
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.widget.TextView template = new android.widget.TextView(context);
        android.view.ViewGroup parent = new android.widget.LinearLayout(context);
        parent.addView(template);

        Method create = StickerGallerySaver.class.getDeclaredMethod(
                "createActionButton", View.class, View.class);
        create.setAccessible(true);
        android.widget.TextView button =
                (android.widget.TextView) create.invoke(null, template, new View(context));

        assertEquals("the Save button ships in English on a German phone",
                "Medien speichern", button.getText().toString());

        Method has = StickerGallerySaver.class.getDeclaredMethod(
                "hasSaveImageButton", android.view.ViewGroup.class);
        has.setAccessible(true);
        assertFalse("a sheet carrying none of our buttons looks like it has one",
                (Boolean) has.invoke(null, parent));

        parent.addView(button);
        assertTrue("a translated button is not recognised, so a second one gets added",
                (Boolean) has.invoke(null, parent));
    }

    @Test public void aSheetWithoutATextTemplateUsesTheContextThemeColours() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        Method create = StickerGallerySaver.class.getDeclaredMethod(
                "createActionButton", View.class, View.class);
        create.setAccessible(true);

        android.content.Context darkContext = contextWithNightMode(context, true);
        android.widget.TextView dark = (android.widget.TextView) create.invoke(
                null, new View(darkContext), new View(context));
        assertEquals(app.morphe.extension.tiktok.settings.preference.SettingsUi.textPrimaryOn(true),
                dark.getCurrentTextColor());
        dark.setEnabled(false);
        assertEquals(app.morphe.extension.tiktok.settings.preference.SettingsUi.textDisabled(),
                dark.getCurrentTextColor());

        android.content.Context lightContext = contextWithNightMode(context, false);
        android.widget.TextView light = (android.widget.TextView) create.invoke(
                null, new View(lightContext), new View(context));
        assertEquals(app.morphe.extension.tiktok.settings.preference.SettingsUi.textPrimaryOn(false),
                light.getCurrentTextColor());
        assertNotEquals("the fallback colour stayed fixed across themes",
                dark.getTextColors().getColorForState(new int[]{android.R.attr.state_enabled}, 0),
                light.getCurrentTextColor());

        android.graphics.drawable.Drawable background = light.getBackground();
        assertTrue("the fallback Save button has no ripple: " + (background == null ? "null"
                        : background.getClass().getSimpleName()),
                background instanceof android.graphics.drawable.RippleDrawable);
    }

    @Test public void theSaveButtonNeverCopiesATouchTargetSmallerThan48Dp() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.widget.TextView tinyTemplate = new android.widget.TextView(context);
        tinyTemplate.setMinHeight(12);
        tinyTemplate.setMinWidth(18);
        Method create = StickerGallerySaver.class.getDeclaredMethod(
                "createActionButton", View.class, View.class);
        create.setAccessible(true);

        android.widget.TextView button = (android.widget.TextView) create.invoke(
                null, tinyTemplate, new View(context));

        int minimum = app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(context, 48);
        assertTrue("the sticker action is shorter than 48dp", button.getMinHeight() >= minimum);
        assertTrue("the sticker action is narrower than 48dp", button.getMinWidth() >= minimum);
        assertEquals(android.widget.Button.class.getName(),
                button.createAccessibilityNodeInfo().getClassName());
    }

    private static android.content.Context contextWithNightMode(
            android.content.Context base, boolean night) {
        android.content.res.Configuration config = new android.content.res.Configuration(
                base.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | (night ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                         : android.content.res.Configuration.UI_MODE_NIGHT_NO);
        return base.createConfigurationContext(config);
    }

    @Test public void aCleartextStickerMirrorIsNotFetchedFrom() {
        // The bytes behind these addresses reach a native WebP decoder, so an unauthenticated
        // mirror is a body anyone on the network can choose, handed to a parser written in C.
        assertEquals(java.util.List.of("https://cdn.example/sticker.webp"),
                StickerGallerySaver.usableUrlList(java.util.Arrays.asList(
                        "http://cdn.example/sticker.webp",
                        "https://cdn.example/sticker.webp",
                        null,
                        "")));
        assertEquals(java.util.List.of(), StickerGallerySaver.usableUrlList(
                java.util.List.of("http://cdn.example/sticker.webp")));
    }
}
