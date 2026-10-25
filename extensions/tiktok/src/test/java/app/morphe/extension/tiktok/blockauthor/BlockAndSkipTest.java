package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class BlockAndSkipTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final VideoAuthor AUTHOR = new VideoAuthor("one", "sec", "creator", "clip");
    private ActivityController<Activity> owner;
    private ViewGroup root;

    @Before public void setup() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        owner.windowFocusChanged(true);
        Utils.setContext(owner.get());
        Utils.setActivity(owner.get());
        root = owner.get().findViewById(android.R.id.content);
    }

    @After public void cleanup() throws Exception {
        Method dismiss = BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        Utils.setActivity(null);
        owner.close();
    }

    @Test public void confirmedBlockUsesOneCompactTopLeftUnblockAction() {
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View notice = root.getChildAt(root.getChildCount() - 1);
        assertTrue("The whole confirmation should be one small action", notice instanceof TextView);
        assertEquals("Unblock", ((TextView) notice).getText().toString());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) notice.getLayoutParams();
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width);
        assertEquals(Gravity.TOP | Gravity.LEFT, params.gravity);
        assertTrue(notice.getMinimumHeight() >= 48 * root.getResources().getDisplayMetrics().density);
    }

    @Test public void confirmedBlockNoticeIsGoneAtTwoSeconds() {
        int before = root.getChildCount();
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(before + 1, root.getChildCount());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000));
        assertEquals(before, root.getChildCount());
    }

    @Test public void otherUndoNoticesKeepTheirRecoveryWindow() {
        int before = root.getChildCount();
        BlockAuthorOverlay.showUndoBanner(root, "Saved", () -> {});
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_001));
        assertEquals(before + 1, root.getChildCount());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_000));
        assertEquals(before, root.getChildCount());
    }

    @Test public void olderChipTimeoutCannotDismissANewerChip() {
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000));
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_001));
        assertEquals(1, root.getChildCount());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(999));
        assertEquals(0, root.getChildCount());
    }

    @Test public void failedOrUnconfirmedBlockNeverOffersUnblock() {
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.REJECTED);
        assertEquals(0, root.getChildCount());
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.UNCONFIRMED);
        assertEquals(0, root.getChildCount());
    }

    @Test @Config(qualifiers = "ar-rEG")
    public void rtlKeepsTheChipAtTheRequestedPhysicalTopLeft() {
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        View chip = root.getChildAt(root.getChildCount() - 1);
        int gravity = ((FrameLayout.LayoutParams) chip.getLayoutParams()).gravity;
        assertEquals(Gravity.LEFT, Gravity.getAbsoluteGravity(gravity, View.LAYOUT_DIRECTION_RTL)
                & Gravity.HORIZONTAL_GRAVITY_MASK);
        assertEquals(android.widget.Button.class.getName(),
                chip.createAccessibilityNodeInfo().getClassName());
    }

    @Test public void renderCompactUnblockChip() throws Exception {
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        View chip = root.getChildAt(root.getChildCount() - 1);
        chip.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        chip.layout(0, 0, chip.getMeasuredWidth(), chip.getMeasuredHeight());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                chip.getWidth() + 32, chip.getHeight() + 32, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(android.graphics.Color.rgb(24, 24, 27));
        canvas.translate(16, 16);
        chip.draw(canvas);
        String directory = System.getProperty("morphe.screenshotDir");
        if (directory != null) {
            java.io.File file = new java.io.File(directory, "block-unblock-chip.png");
            assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            }
        }
        bitmap.recycle();
    }

    @Test @Config(sdk = 35)
    public void theCompactUnblockChipHonorsTheRecommendedControlTimeout() throws Exception {
        var manager = (android.view.accessibility.AccessibilityManager)
                owner.get().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(60_000);
        Shadows.shadowOf(manager).setNonInteractiveUiTimeout(15_000);
        int before = root.getChildCount();
        BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
        var looper = Shadows.shadowOf(Looper.getMainLooper());
        looper.idleFor(Duration.ofSeconds(3));
        assertEquals("the action ignored the accessible timeout", before + 1, root.getChildCount());
        app.morphe.extension.tiktok.UiCapture.save(root, "audit/accessible-unblock.png");
        looper.idleFor(Duration.ofSeconds(58));
        assertEquals(before, root.getChildCount());
    }

    @Test @Config(sdk = 35)
    public void anOverlayUndoBannerHonorsTheRecommendedControlTimeout() {
        var manager = (android.view.accessibility.AccessibilityManager)
                owner.get().getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(60_000);
        Shadows.shadowOf(manager).setNonInteractiveUiTimeout(45_000);
        int before = root.getChildCount();
        BlockAuthorOverlay.showUndoBanner(root, "Saved", () -> { });
        var looper = Shadows.shadowOf(Looper.getMainLooper());
        looper.idleFor(Duration.ofSeconds(46));
        assertEquals("the undo banner ignored the accessible timeout", before + 1, root.getChildCount());
        looper.idleFor(Duration.ofSeconds(15));
        assertEquals(before, root.getChildCount());
    }
}
