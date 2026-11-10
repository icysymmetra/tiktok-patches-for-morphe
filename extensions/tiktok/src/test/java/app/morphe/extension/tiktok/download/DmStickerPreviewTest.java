package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.L10n;
import com.bytedance.lighten.loader.SmartImageView;
import com.ss.android.ugc.aweme.views.RoundingCornerLayout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DmStickerPreviewTest {
    private Activity activity;
    private final List<AlertDialog> dialogs = new ArrayList<>();

    public static final class Template {
        final ImageComponent imageComponent;
        final Runnable send, favorite;
        Template(ImageComponent image, Runnable send, Runnable favorite) {
            imageComponent = image;
            this.send = send;
            this.favorite = favorite;
        }
    }

    public static final class ImageComponent {
        List<String> urls;
        String decryptKey;
        ImageComponent(String url, String key) {
            urls = new ArrayList<>(List.of(url));
            decryptKey = key;
        }
    }

    public static final class MissingKeyComponent {
        final List<String> urls = List.of("https://example.invalid/private.gif");
    }

    public static final class MissingKeyTemplate {
        final MissingKeyComponent imageComponent = new MissingKeyComponent();
    }

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @After public void cleanup() {
        for (AlertDialog dialog : dialogs) dialog.dismiss();
        shadowOf(Looper.getMainLooper()).idle();
        activity.finish();
    }

    @Test public void onlyTheSmallNativeStickerBubbleCanSupplyThePoster() {
        RoundingCornerLayout bubble = bubble();
        View image = bubble.getChildAt(0);
        assertSame(image, StickerGallerySaver.dmStickerImage(bubble));

        FrameLayout chat = new FrameLayout(activity);
        chat.addView(bubble);
        chat.layout(0, 0, 160, 160);
        assertNull(StickerGallerySaver.dmStickerImage(chat));
        assertNull(StickerGallerySaver.dmStickerImage(image));
        bubble.layout(0, 0, 2048, 2048);
        assertNull(StickerGallerySaver.dmStickerImage(bubble));
        bubble.layout(0, 0, 160, 160);
        image.layout(0, 0, 161, 160);
        assertNull(StickerGallerySaver.dmStickerImage(bubble));
        image.layout(0, 0, 160, 160);
        SmartImageView second = new SmartImageView(activity);
        bubble.addView(second);
        second.layout(0, 0, 80, 80);
        assertNull(StickerGallerySaver.dmStickerImage(bubble));
        assertNull(StickerGallerySaver.dmStickerImage(new RoundingCornerLayout(activity)));
    }

    @Test public void anIncompleteOrTooLargeViewTreeIsRefusedRatherThanPartlyScanned() {
        RoundingCornerLayout bubble = bubble();
        for (int i = 0; i < 33; i++) bubble.addView(new View(activity));
        assertNull(StickerGallerySaver.dmStickerImage(bubble));
        bubble.removeAllViews();
        FrameLayout parent = bubble;
        for (int i = 0; i < 5; i++) {
            FrameLayout child = new FrameLayout(activity);
            parent.addView(child);
            parent = child;
        }
        assertNull(StickerGallerySaver.dmStickerImage(bubble));
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void thePosterShowsOnlyTheImageAndOpeningItDispatchesNoNativeAction() {
        AtomicInteger actions = new AtomicInteger();
        RoundingCornerLayout bubble = bubble();
        bubble.setBackgroundColor(Color.BLUE);
        bubble.getChildAt(0).setBackgroundColor(Color.RED);
        bubble.setOnClickListener(ignored -> actions.incrementAndGet());
        Template template = template("first", null, actions);
        StickerGallerySaver.openDmStickerPreview(bubble, template);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialogs.add(dialog);
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        ImageView image = findImage(dialog.getWindow().getDecorView());
        Bitmap poster = ((android.graphics.drawable.BitmapDrawable) image.getDrawable()).getBitmap();
        assertEquals(160, poster.getWidth());
        assertEquals(160, poster.getHeight());
        assertTrue(poster.getByteCount() <= 4L * 1024 * 1024);
        assertEquals(Color.RED, poster.getPixel(80, 80));
        assertEquals(0, actions.get());
        assertEquals(1, bubble.getChildCount());
        dialog.dismiss();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(poster.isRecycled());
        assertNull(image.getDrawable());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w390dp-h844dp-night-mdpi")
    public void actualOwnedPreviewCapturesDarkWithReadableActions() throws Exception {
        capturePreview("dialogs/dark/dm-sticker-preview.png", true);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w390dp-h844dp-notnight-mdpi")
    public void actualOwnedPreviewCapturesLightWithReadableActions() throws Exception {
        capturePreview("dialogs/light/dm-sticker-preview.png", false);
    }

    private void capturePreview(String name, boolean dark) throws Exception {
        Utils.setContext(activity);
        Utils.setActivity(activity);
        Utils.setIsDarkModeEnabled(dark);
        RoundingCornerLayout bubble = bubble();
        Bitmap sticker = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(sticker);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(0xFFFFA6C1);
            canvas.drawCircle(80, 80, 68, ink);
            ink.setColor(0xFF332431);
            canvas.drawCircle(57, 66, 7, ink);
            canvas.drawCircle(103, 66, 7, ink);
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(6);
            ink.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(49, 72, 111, 117, 15, 150, false, ink);
            ((ImageView) bubble.getChildAt(0)).setImageBitmap(sticker);
            StickerGallerySaver.openDmStickerPreview(bubble, template("capture", null, null));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            dialogs.add(dialog);
            shadowOf(Looper.getMainLooper()).idle();
            View decor = dialog.getWindow().getDecorView();
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
            TextView save = (TextView) save(dialog);
            TextView close = (TextView) ((ViewGroup) save.getParent()).getChildAt(0);
            assertEquals(L10n.t(activity, "Close"), close.getText().toString());
            assertEquals(L10n.t(activity, "Save media"), save.getText().toString());
            List<Rect> placed = new ArrayList<>();
            for (TextView action : new TextView[]{close, save}) {
                Rect bounds = new Rect();
                action.getDrawingRect(bounds);
                ((ViewGroup) decor).offsetDescendantRectToMyCoords(action, bounds);
                assertTrue("preview action is clipped", bounds.width() > 0 && bounds.height() > 0
                        && bounds.left >= 0 && bounds.top >= 0
                        && bounds.right <= decor.getWidth() && bounds.bottom <= decor.getHeight());
                for (Rect prior : placed) assertFalse("preview actions overlap", Rect.intersects(prior, bounds));
                placed.add(bounds);
                android.text.Layout text = action.getLayout();
                assertNotNull(text);
                for (int line = 0; line < text.getLineCount(); line++) {
                    assertEquals("preview action was shortened", 0, text.getEllipsisCount(line));
                    assertTrue("preview action runs past its edge", text.getLineMax(line)
                            <= action.getWidth() - action.getCompoundPaddingLeft() - action.getCompoundPaddingRight() + 1);
                }
                assertTrue("preview action is cut vertically", text.getHeight()
                        <= action.getHeight() - action.getCompoundPaddingTop() - action.getCompoundPaddingBottom());
            }
            ImageView preview = findImage(decor);
            assertEquals(L10n.t(activity, "Sticker"), preview.getContentDescription().toString());
            Bitmap poster = ((android.graphics.drawable.BitmapDrawable) preview.getDrawable()).getBitmap();
            assertFalse(poster.isRecycled());
            UiCapture.save(decor, name, decor.getWidth(), decor.getHeight());
            close.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(dialog.isShowing());
            assertTrue(poster.isRecycled());
        } finally {
            sticker.recycle();
        }
    }

    @Test public void aReboundTemplateCannotChangeTheSourceAlreadyShown() {
        ImageComponent image = new ImageComponent("https://example.invalid/first.gif", "00112233445566778899aabbccddeeff");
        StickerGallerySaver.StickerAsset first = StickerGallerySaver.findDmStickerAsset(new Template(image, null, null));
        image.urls.set(0, "https://example.invalid/second.gif");
        image.decryptKey = "ffeeddccbbaa99887766554433221100";
        StickerGallerySaver.StickerAsset second = StickerGallerySaver.findDmStickerAsset(new Template(image, null, null));
        assertEquals(List.of("https://example.invalid/first.gif"), first.urls);
        assertArrayEquals(StickerGallerySaver.dmStickerKey("00112233445566778899aabbccddeeff"), first.decryptKey);
        assertEquals(List.of("https://example.invalid/second.gif"), second.urls);
        assertNotEquals(first.url, second.url);
        assertTrue(first.privateMedia);
        assertEquals("private sticker", StickerGallerySaver.diagnosticSource(first));
        assertNull(StickerGallerySaver.stickerJobKey(first));
    }

    @Test public void missingMalformedOrUnsafeSourceMetadataNeverOffersSave() {
        assertNull(StickerGallerySaver.findDmStickerAsset(new MissingKeyTemplate()));
        assertNull(StickerGallerySaver.findDmStickerAsset(template("bad", "not-a-key", null)));
        assertNull(StickerGallerySaver.findDmStickerAsset(new Template(new ImageComponent("http://example.invalid/private.gif", null), null, null)));
        assertNull(StickerGallerySaver.findDmStickerAsset(new Object()));
        assertNotNull(StickerGallerySaver.findDmStickerAsset(template("plain", null, null)));
        assertNotNull(StickerGallerySaver.findDmStickerAsset(template("plain", "", null)));
    }

    @Test public void oldDismissalAndLateCleanupCannotReplaceTheNextDialogsAsset() throws Exception {
        StickerGallerySaver.StickerAsset first = StickerGallerySaver.findDmStickerAsset(template("first", null, null));
        AlertDialog old = show(first);
        View oldSave = save(old);
        StickerGallerySaver.StickerAsset second = StickerGallerySaver.findDmStickerAsset(template("second", null, null));
        AlertDialog current = show(second);
        View currentSave = save(current);
        old.dismiss();
        StickerGallerySaver.handBackLater(new java.lang.ref.WeakReference<>(oldSave)).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(old.isShowing());
        assertTrue(current.isShowing());
        assertTrue(currentSave.isEnabled());
        assertTrue(attached().containsValue(second));
        assertFalse(attached().containsValue(first));
        int queued = MediaJobScheduler.queuedJobs();
        oldSave.performClick();
        assertEquals(queued, MediaJobScheduler.queuedJobs());
    }

    @Test public void saveQueuesOnlyTheFullSourceAndNeverCallsSendFavoriteOrTheNativeView() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        RoundingCornerLayout bubble = bubble();
        bubble.setOnClickListener(ignored -> actions.incrementAndGet());
        StickerGallerySaver.openDmStickerPreview(bubble, template("full-original", null, actions));
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialogs.add(dialog);
        CountDownLatch hold = new CountDownLatch(1), started = new CountDownLatch(MediaJobScheduler.MAX_RUNNING_JOBS);
        List<MediaJobScheduler.Job> waiting = new ArrayList<>();
        try {
            for (int i = 0; i < MediaJobScheduler.MAX_RUNNING_JOBS; i++) {
                assertTrue(MediaJobScheduler.submit("DM test hold", () -> {
                    started.countDown();
                    try { hold.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }));
            }
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(save(dialog).performClick());
            assertFalse(save(dialog).isEnabled());
            Field field = MediaJobScheduler.class.getDeclaredField("EXECUTOR");
            field.setAccessible(true);
            for (Runnable runnable : ((ThreadPoolExecutor) field.get(null)).getQueue()) {
                if (runnable instanceof MediaJobScheduler.Job) waiting.add((MediaJobScheduler.Job) runnable);
            }
            assertEquals(1, waiting.size());
            assertEquals("sticker", waiting.get(0).label);
            assertNull(waiting.get(0).key);
            assertEquals(0, actions.get());
        } finally {
            for (MediaJobScheduler.Job job : waiting) job.cancel();
            hold.countDown();
            for (int i = 0; i < 500 && !MediaJobScheduler.idle(); i++) Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue(MediaJobScheduler.idle());
        assertTrue(save(dialog).isEnabled());
        assertEquals(0, actions.get());
    }

    private RoundingCornerLayout bubble() {
        RoundingCornerLayout bubble = new RoundingCornerLayout(activity);
        SmartImageView image = new SmartImageView(activity);
        bubble.addView(image);
        bubble.layout(0, 0, 160, 160);
        image.layout(0, 0, 160, 160);
        return bubble;
    }

    private Template template(String name, String key, AtomicInteger actions) {
        Runnable action = actions == null ? null : actions::incrementAndGet;
        return new Template(new ImageComponent("https://example.invalid/" + name + ".gif", key), action, action);
    }

    private AlertDialog show(StickerGallerySaver.StickerAsset asset) {
        AlertDialog dialog = StickerGallerySaver.showDmStickerPreview(activity,
                Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888), asset);
        dialogs.add(dialog);
        return dialog;
    }

    private static View save(AlertDialog dialog) {
        View found = dialog.getWindow().getDecorView().findViewWithTag("morphe_save_media");
        assertNotNull(found);
        return found;
    }

    private static ImageView findImage(View view) {
        if (view instanceof ImageView && ((ImageView) view).getDrawable() instanceof android.graphics.drawable.BitmapDrawable) return (ImageView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ImageView found = findImage(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked") private static Map<View, StickerGallerySaver.StickerAsset> attached() throws Exception {
        Field field = StickerGallerySaver.class.getDeclaredField("ATTACHED_SHEETS");
        field.setAccessible(true);
        return (Map<View, StickerGallerySaver.StickerAsset>) field.get(null);
    }
}
