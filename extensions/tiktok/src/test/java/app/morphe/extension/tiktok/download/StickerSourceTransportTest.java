package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import com.ss.android.ugc.aweme.base.model.UrlModel;

import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

/** The save-button entry reads the registered StickerItem before falling back to preview URLs. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class StickerSourceTransportTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        HookStatus.clear();
    }

    @After public void tearDown() {
        HookStatus.clear();
    }

    @Test public void registeredStickerKeepsItsTrimmedHttpsUrl() {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerItemFake sticker = StickerItemFake.of(
                StickerGallerySaver.TYPE_GIPHY, "gif", "  https://cdn.example/sticker.gif  ");
        StickerGallerySaver.registerStickerSource(preview, sticker);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        StickerGallerySaver.StickerAsset asset = resolved(sheet);
        assertNotNull("the HTTPS source did not reach the save-button state", asset);
        assertEquals("https://cdn.example/sticker.gif", asset.url);
        assertEquals(List.of("https://cdn.example/sticker.gif"), asset.urls);
        assertTrue("a GIPHY sticker lost its motion", asset.animated);
    }

    @Test public void cleartextStickerCannotReplaceTheSaveButtonsPreviousAsset() {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerGallerySaver.registerStickerSource(preview,
                StickerItemFake.of(StickerGallerySaver.TYPE_ANIMATED, "webp", "https://cdn.example/previous.webp"));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        assertNotNull("the same source shape must work over HTTPS", resolved(sheet));

        StickerItemFake untrusted = StickerItemFake.of(
                StickerGallerySaver.TYPE_ANIMATED, "webp", "http://cdn.example/next.webp");
        StickerGallerySaver.registerStickerSource(preview, untrusted);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertTrue("the HTTP case never reached the sticker's image", untrusted.stickerBase.image.walks() > 0);
        assertNull("a save button could fetch cleartext media or keep the previous sticker", resolved(sheet));
    }

    /** A sheet with TikTok's two like-typed actions, so the button step has nothing to report. */
    private static final class SheetWithActions extends android.widget.LinearLayout {
        final android.widget.Button share;
        final android.widget.Button favorite;

        SheetWithActions(android.content.Context context) {
            super(context);
            addView(share = new android.widget.Button(context));
            addView(favorite = new android.widget.Button(context));
        }
    }

    @Test public void rejectingTheSourceUrlStillAllowsAnHttpsPreviewFallback() {
        View sheet = new SheetWithActions(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(new PreviewUrls("https://cdn.example/preview.png"));
        StickerItemFake untrusted = StickerItemFake.of(
                StickerGallerySaver.TYPE_ANIMATED, "webp", "http://cdn.example/source.webp");
        StickerGallerySaver.registerStickerSource(preview, untrusted);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertTrue(untrusted.stickerBase.image.walks() > 0);
        StickerGallerySaver.StickerAsset asset = resolved(sheet);
        assertNotNull("rejecting the source address also discarded the usable preview", asset);
        assertEquals("https://cdn.example/preview.png", asset.url);
        assertEquals(List.of("https://cdn.example/preview.png"), asset.urls);
        assertTrue("a working preview fallback was reported as broken",
                HookStatus.missing("sticker saves").isEmpty());
    }

    /** The sheet shows the sticker's own image, so that is what the button saves. */
    @Test public void theSheetsImageComesBeforeTheSendersVariant() {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerItemFake sticker = StickerItemFake.of(
                StickerGallerySaver.TYPE_STATIC, "png", "https://cdn.example/base.png");
        sticker.variant = new StickerItemFake.Image("png", "https://cdn.example/variant.png");
        StickerGallerySaver.registerStickerSource(preview, sticker);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertEquals("https://cdn.example/base.png", resolved(sheet).url);
    }

    @Test public void theVariantAndThenTheThumbnailStandInForAnImageWithNoHttpsAddress() {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerItemFake sticker = StickerItemFake.of(
                StickerGallerySaver.TYPE_ANIMATED, "webp", "http://cdn.example/base.webp");
        sticker.variant = new StickerItemFake.Image("webp", "https://cdn.example/variant.webp");
        StickerGallerySaver.registerStickerSource(preview, sticker);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        StickerGallerySaver.StickerAsset asset = resolved(sheet);
        assertEquals("https://cdn.example/variant.webp", asset.url);
        assertTrue("the variant of an animated sticker moves too", asset.animated);

        StickerItemFake stillOnly = new StickerItemFake(new StickerItemFake.Base(
                StickerGallerySaver.TYPE_ANIMATED,
                new StickerItemFake.Image("webp", "http://cdn.example/base.webp"),
                new StickerItemFake.Image("webp", "https://cdn.example/thumb.webp")));
        StickerGallerySaver.registerStickerSource(preview, stillOnly);
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        asset = resolved(sheet);
        assertEquals("https://cdn.example/thumb.webp", asset.url);
        assertFalse("a thumbnail is a still, whatever the sticker's type", asset.animated);
    }

    /** TikTok's own sticker type says whether a sticker moves; the image type only hints. */
    @Test public void theStickerTypeDecidesMotionAndTheImageTypeOnlyHints() {
        assertFalse(animatedFor(StickerGallerySaver.TYPE_STATIC, "webp"));
        assertFalse(animatedFor(StickerGallerySaver.TYPE_VIDEO_STICKER_STATIC, "webp"));
        assertFalse(animatedFor(StickerGallerySaver.TYPE_AIMOJI_STICKER_STATIC, "webp"));
        assertFalse(animatedFor(StickerGallerySaver.TYPE_PHOTO_COMMENT_STICKER, "webp"));
        assertTrue(animatedFor(StickerGallerySaver.TYPE_ANIMATED, "png"));
        assertTrue(animatedFor(StickerGallerySaver.TYPE_VIDEO_STICKER_ANIMATED, "png"));
        assertTrue(animatedFor(StickerGallerySaver.TYPE_THIRD_PARTY_GIPHY, "png"));
        assertTrue(animatedFor(StickerGallerySaver.TYPE_THIRD_PARTY_TENOR, "png"));
        // A type TikTok's converters don't sort, or none at all: the image type is the hint.
        assertTrue(animatedFor(12, "webp"));
        assertFalse(animatedFor(12, "png"));
        assertTrue(animatedFor(null, "gif"));
    }

    @Test public void aRenamedStickerItemIsVisibleInHookStatus() {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerGallerySaver.registerStickerSource(preview, new RenamedSource());

        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertNull(resolved(sheet));
        List<String> missing = HookStatus.missing("sticker saves");
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("stickerBase"));
        assertTrue(missing.get(0), missing.get(0).contains("currentImage"));
    }

    /** A StickerItem with no HTTPS address anywhere is a sticker, not a renamed model. */
    @Test public void aStickerWithNoHttpsAddressIsNotReportedAsRenamed() {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerGallerySaver.registerStickerSource(preview,
                StickerItemFake.of(StickerGallerySaver.TYPE_STATIC, "png", "http://cdn.example/sticker.png"));

        StickerGallerySaver.attachSaveImageButton(sheet, preview);

        assertNull(resolved(sheet));
        assertTrue(HookStatus.missing("sticker saves").isEmpty());
    }

    private static boolean animatedFor(Integer stickerType, String imageType) {
        View sheet = new View(RuntimeEnvironment.getApplication());
        PreviewModel preview = new PreviewModel(null);
        StickerGallerySaver.registerStickerSource(preview,
                StickerItemFake.of(stickerType, imageType, "https://cdn.example/sticker." + imageType));
        StickerGallerySaver.attachSaveImageButton(sheet, preview);
        StickerGallerySaver.StickerAsset asset = resolved(sheet);
        assertNotNull(asset);
        return asset.animated;
    }

    private static StickerGallerySaver.StickerAsset resolved(View sheet) {
        // Observe the state the real click listener reads. Never seed this map or call the
        // URL helper directly: registration and attachment must choose the asset themselves.
        Map<View, StickerGallerySaver.StickerAsset> sheets = ReflectionHelpers.getStaticField(
                StickerGallerySaver.class, "ATTACHED_SHEETS");
        return sheets.get(sheet);
    }

    public static final class PreviewModel {
        public final UrlModel previewUrl;
        PreviewModel(UrlModel url) { previewUrl = url; }
    }

    /** A newer build renamed StickerItem's members. */
    public static final class RenamedSource {
    }

    public static final class PreviewUrls extends UrlModel {
        private final List<String> urls;
        PreviewUrls(String url) { urls = List.of(url); }
        @Override public List<String> getUrlList() { return urls; }
    }
}
