package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SettingsRegistryRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OverlayControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    /**
     * A saved control position outlives its test, and the layout tests here read the defaults.
     * Without this, whichever position test ran first decided what the others saw.
     */
    @org.junit.After public void clearSavedControlPositions() {
        Settings.BLOCK_AUTHOR_BUTTON_POSITION.resetToDefault();
        Settings.LOCAL_HIDE_BUTTON_POSITION.resetToDefault();
        Settings.BLOCK_SOUND_BUTTON_POSITION.resetToDefault();
        Settings.NOT_INTERESTED_BUTTON_POSITION.resetToDefault();
        Settings.FEED_MUTE_BUTTON_POSITION.resetToDefault();
    }

    @Test public void theInstalledBlockButtonDrawsBothTheRingAndTheSlash() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Method factory = BlockAuthorOverlay.class.getDeclaredMethod("createButton", Activity.class);
        factory.setAccessible(true);
        View button = (View) factory.invoke(null, activity);
        // The backdrop, the glyph and the focus ring, under the ripple that carries the press.
        android.graphics.drawable.LayerDrawable layers = contentOf(button.getBackground());
        assertTrue(layers.getDrawable(1) instanceof OverlayGlyphDrawable);
        int size = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);
        button.measure(size, size);
        button.layout(0, 0, 100, 100);
        Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        button.draw(new Canvas(bitmap));
        assertNearWhite("the block symbol lost its diagonal bar", bitmap.getPixel(50, 50));
        assertNearWhite("the block symbol lost its ring", bitmap.getPixel(79, 50));
        assertNotEquals("the symbol became a filled disc", Color.WHITE, bitmap.getPixel(50, 40));
        assertEquals("the round button filled its transparent corner", 0, Color.alpha(bitmap.getPixel(0, 0)));
        bitmap.recycle();
        activity.finish();
    }

    private static void assertNearWhite(String message, int pixel) {
        // Edge coverage is fractional at a diagonal even at the centre of a 2px stroke.
        assertTrue(message + ": " + Integer.toHexString(pixel),
                Color.alpha(pixel) >= 225 && Color.red(pixel) >= 225
                        && Color.green(pixel) >= 225 && Color.blue(pixel) >= 225);
    }

    @Test public void renderAccessibleOverlayControls() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Bitmap bitmap = Bitmap.createBitmap(304, 88, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(24, 24, 27));
        String[] methods = {"createButton", "createLocalHideButton", "createSoundButton", "createNotInterestedButton"};
        for (int i = 0; i < methods.length; i++) {
            Method factory = BlockAuthorOverlay.class.getDeclaredMethod(methods[i], Activity.class);
            factory.setAccessible(true);
            View view = (View) factory.invoke(null, activity);
            assertNotNull(view.getContentDescription());
            assertTrue(view.hasOnClickListeners());
            assertTrue(view.isLongClickable());
            AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
            assertEquals(android.widget.Button.class.getName(), node.getClassName());
            node.recycle();
            int size = View.MeasureSpec.makeMeasureSpec(56, View.MeasureSpec.EXACTLY);
            view.measure(size, size);
            view.layout(0, 0, 56, 56);
            canvas.save();
            canvas.translate(16 + i * 72, 16);
            view.draw(canvas);
            canvas.restore();
        }
        String directory = System.getProperty("morphe.screenshotDir");
        if (directory != null) {
            File output = new File(directory, "overlay-controls.png");
            assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
        }
        activity.finish();
    }

    @Test public void accessibilityLongClickCannotLeaveAControlInPointerDragMode() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Method factory = BlockAuthorOverlay.class.getDeclaredMethod("createButton", Activity.class);
        factory.setAccessible(true);
        View button = (View) factory.invoke(null, activity);
        activity.setContentView(button);
        java.util.concurrent.atomic.AtomicInteger clicks = new java.util.concurrent.atomic.AtomicInteger();
        button.setOnClickListener(view -> clicks.incrementAndGet());

        assertFalse(button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null));
        java.lang.reflect.Field dragging = BlockAuthorOverlay.class.getDeclaredField("dragging");
        dragging.setAccessible(true);
        assertFalse(dragging.getBoolean(null));
        assertTrue(button.performClick());
        assertEquals(1, clicks.get());
        assertFalse(dragging.getBoolean(null));
    }

    @Test public void theFeedButtonsAreLaidOutFromTheLeftInEitherDirection() throws Exception {
        // Every position on these buttons is a pixel worked out from a raw touch and written to
        // leftMargin. A mirrored layout resolves START to RIGHT and then reads rightMargin, which
        // nothing sets, so the saved position was thrown away, a drag moved nothing sideways, and
        // the Not interested button, which differs from the block button only by leftMargin,
        // landed on top of it.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);

        // The real attach path, so the params under test are the ones production writes.
        assertEquals("the overlay looks the activity up for itself", activity, Utils.getActivity());
        Method attach = BlockAuthorOverlay.class.getDeclaredMethod("attach", VideoAuthor.class);
        attach.setAccessible(true);
        int before = root.getChildCount();
        attach.invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));
        assertNotEquals("attach added nothing to the content root", before, root.getChildCount());

        String[] buttons = {"buttonReference", "localHideReference", "soundButtonReference",
                "notInterestedReference"};
        for (String name : buttons) {
            java.lang.reflect.Field held = BlockAuthorOverlay.class.getDeclaredField(name);
            held.setAccessible(true);
            View view = ((java.lang.ref.WeakReference<View>) held.get(null)).get();
            assertNotNull(name + " was never attached", view);

            // Asking the platform what a mirrored layout does with these exact params, rather
            // than laying the root out right-to-left, because a FrameLayout only resolves a
            // direction once it is attached to a real window and off a test that would pass
            // whichever gravity it was given.
            int gravity = ((FrameLayout.LayoutParams) view.getLayoutParams()).gravity;
            int mirrored = Gravity.getAbsoluteGravity(gravity, View.LAYOUT_DIRECTION_RTL)
                    & Gravity.HORIZONTAL_GRAVITY_MASK;
            assertEquals(name + " is mirrored away from the margin that positions it",
                    Gravity.LEFT, mirrored);
        }
    }

    @Test public void allFourFeedButtonsAreOneSizeAndOneShape() throws Exception {
        // They sit in a column on the feed, where a miss is a like or a follow on somebody's
        // video, and 44dp is under Android's own guidance with no TouchDelegate to make up the
        // difference. The shape half of this used to accept any four discs; it now asks for the
        // radius from the scale as well, because the four of them, the budget cue and the hold's
        // release control all draw the same backdrop and had drifted to four radii between them.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        Method attach = BlockAuthorOverlay.class.getDeclaredMethod("attach", VideoAuthor.class);
        attach.setAccessible(true);
        attach.invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));

        int expected = app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(activity, 48);
        String[] buttons = {"buttonReference", "localHideReference", "soundButtonReference",
                "notInterestedReference"};
        for (String name : buttons) {
            java.lang.reflect.Field held = BlockAuthorOverlay.class.getDeclaredField(name);
            held.setAccessible(true);
            View view = ((java.lang.ref.WeakReference<View>) held.get(null)).get();
            assertNotNull(name + " was never attached", view);

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
            assertEquals(name + " is not 48dp wide", expected, params.width);
            assertEquals(name + " is not 48dp tall", expected, params.height);

            // The block button draws its symbol over the disc rather than setting it as text,
            // because the font TikTok is using may not carry it, so its background is a layer
            // list with the disc underneath.
            android.graphics.drawable.GradientDrawable chip =
                    (android.graphics.drawable.GradientDrawable) contentOf(view.getBackground())
                            .getDrawable(0);
            assertEquals(name + " is not the shape the others are",
                    android.graphics.drawable.GradientDrawable.RECTANGLE, chip.getShape());
            assertEquals(name + " is not drawn with the overlay radius the others use",
                    (float) app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(
                            activity,
                            app.morphe.extension.tiktok.settings.preference.SettingsUi
                                    .RADIUS_OVERLAY),
                    chip.getCornerRadius(), 0.5f);
        }
    }

    @Test public void movingAndSavingOneFeedButtonDoesNotMoveTheOthers() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);

        Method attach = BlockAuthorOverlay.class.getDeclaredMethod("attach", VideoAuthor.class);
        attach.setAccessible(true);
        attach.invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        int spec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        root.measure(spec, spec);
        root.layout(0, 0, 1080, 1080);
        View block = held("buttonReference");
        View local = held("localHideReference");
        View sound = held("soundButtonReference");
        View feedback = held("notInterestedReference");
        Method move = BlockAuthorOverlay.class.getDeclaredMethod(
                "moveTo", View.class, android.view.ViewGroup.class, float.class, float.class);
        move.setAccessible(true);
        Method save = BlockAuthorOverlay.class.getDeclaredMethod(
                "savePosition", View.class, android.view.ViewGroup.class);
        save.setAccessible(true);

        int blockLeft = ((FrameLayout.LayoutParams) block.getLayoutParams()).leftMargin;
        int soundLeft = ((FrameLayout.LayoutParams) sound.getLayoutParams()).leftMargin;
        int feedbackLeft = ((FrameLayout.LayoutParams) feedback.getLayoutParams()).leftMargin;
        String oldBlock = Settings.BLOCK_AUTHOR_BUTTON_POSITION.get();
        String oldLocal = Settings.LOCAL_HIDE_BUTTON_POSITION.get();
        String oldSound = Settings.BLOCK_SOUND_BUTTON_POSITION.get();
        String oldFeedback = Settings.NOT_INTERESTED_BUTTON_POSITION.get();
        try {
            move.invoke(null, local, root, 123f, 456f);
            save.invoke(null, local, root);
            FrameLayout.LayoutParams localParams = (FrameLayout.LayoutParams) local.getLayoutParams();
            assertEquals(123, localParams.leftMargin);
            assertEquals(456, localParams.topMargin);
            assertEquals(blockLeft, ((FrameLayout.LayoutParams) block.getLayoutParams()).leftMargin);
            assertEquals(soundLeft, ((FrameLayout.LayoutParams) sound.getLayoutParams()).leftMargin);
            assertEquals(feedbackLeft,
                    ((FrameLayout.LayoutParams) feedback.getLayoutParams()).leftMargin);
            assertNotEquals(oldLocal, Settings.LOCAL_HIDE_BUTTON_POSITION.get());
            assertEquals(oldBlock, Settings.BLOCK_AUTHOR_BUTTON_POSITION.get());
            assertEquals(oldSound, Settings.BLOCK_SOUND_BUTTON_POSITION.get());
            assertEquals(oldFeedback, Settings.NOT_INTERESTED_BUTTON_POSITION.get());
        } finally {
            Settings.BLOCK_AUTHOR_BUTTON_POSITION.save(oldBlock);
            Settings.LOCAL_HIDE_BUTTON_POSITION.save(oldLocal);
            Settings.BLOCK_SOUND_BUTTON_POSITION.save(oldSound);
            Settings.NOT_INTERESTED_BUTTON_POSITION.save(oldFeedback);
        }
    }

    @Test public void everyControlOffersTheMoveAndResetActionsThePointerDragCannot() throws Exception {
        // Pointer long-press parks each control independently. An accessibility long-click has
        // no release, so it is refused, which left a screen reader with no way to move one at
        // all.
        ViewGroup root = attachedRoot();
        int step = app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(root.getContext(), 48);

        for (String name : CONTROLS) {
            View view = held(name);
            AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
            java.util.Map<Integer, String> actions = new java.util.HashMap<>();
            for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) {
                if (action.getLabel() != null) actions.put(action.getId(), action.getLabel().toString());
            }
            assertEquals(name + " is missing a labelled move or reset action",
                    java.util.Set.of("Move up", "Move down", "Move left", "Move right",
                            "Reset position"),
                    new java.util.HashSet<>(actions.values()));
            assertFalse(name + " still advertises a long-click it refuses", node.isLongClickable());
            node.recycle();
        }

        // Each direction, from a control parked where all four have room to move.
        View sound = held("soundButtonReference");
        Method move = declared("moveTo", View.class, ViewGroup.class, float.class, float.class);
        move.invoke(null, sound, root, 500f, 500f);

        assertTrue(sound.performAccessibilityAction(action("ACTION_MOVE_UP"), null));
        assertEquals(500 - step, topOf(sound));
        assertTrue(sound.performAccessibilityAction(action("ACTION_MOVE_DOWN"), null));
        assertEquals(500, topOf(sound));
        assertTrue(sound.performAccessibilityAction(action("ACTION_MOVE_LEFT"), null));
        assertEquals(500 - step, leftOf(sound));
        assertTrue(sound.performAccessibilityAction(action("ACTION_MOVE_RIGHT"), null));
        assertEquals(500, leftOf(sound));
    }

    @Test public void aMoveActionSavesOnlyTheControlItWasPerformedOn() throws Exception {
        ViewGroup root = attachedRoot();
        View local = held("localHideReference");
        declared("moveTo", View.class, ViewGroup.class, float.class, float.class)
                .invoke(null, local, root, 400f, 400f);

        int blockLeft = leftOf(held("buttonReference"));
        int soundTop = topOf(held("soundButtonReference"));
        int feedbackLeft = leftOf(held("notInterestedReference"));
        String block = Settings.BLOCK_AUTHOR_BUTTON_POSITION.get();
        String sound = Settings.BLOCK_SOUND_BUTTON_POSITION.get();
        String feedback = Settings.NOT_INTERESTED_BUTTON_POSITION.get();

        assertTrue(local.performAccessibilityAction(action("ACTION_MOVE_LEFT"), null));

        assertEquals(400 - app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(root.getContext(), 48), leftOf(local));
        assertFalse("the move saved nothing", Settings.LOCAL_HIDE_BUTTON_POSITION.get().isEmpty());
        assertEquals(block, Settings.BLOCK_AUTHOR_BUTTON_POSITION.get());
        assertEquals(sound, Settings.BLOCK_SOUND_BUTTON_POSITION.get());
        assertEquals(feedback, Settings.NOT_INTERESTED_BUTTON_POSITION.get());
        assertEquals(blockLeft, leftOf(held("buttonReference")));
        assertEquals(soundTop, topOf(held("soundButtonReference")));
        assertEquals(feedbackLeft, leftOf(held("notInterestedReference")));
    }

    @Test public void resetReturnsOneControlToItsDefaultAndLeavesTheRestWhereTheyWere()
            throws Exception {
        ViewGroup root = attachedRoot();
        View local = held("localHideReference");
        View sound = held("soundButtonReference");
        int defaultLocalLeft = leftOf(local);
        int defaultLocalTop = topOf(local);

        Method move = declared("moveTo", View.class, ViewGroup.class, float.class, float.class);
        Method save = declared("savePosition", View.class, ViewGroup.class);
        move.invoke(null, local, root, 40f, 40f);
        save.invoke(null, local, root);
        move.invoke(null, sound, root, 700f, 700f);
        save.invoke(null, sound, root);
        assertNotEquals(defaultLocalLeft, leftOf(local));

        assertTrue(local.performAccessibilityAction(action("ACTION_RESET_POSITION"), null));

        assertEquals("", Settings.LOCAL_HIDE_BUTTON_POSITION.get());
        assertEquals(defaultLocalLeft, leftOf(local));
        assertEquals(defaultLocalTop, topOf(local));
        assertEquals("the reset moved a control it was not performed on", 700, leftOf(sound));
        assertEquals(700, topOf(sound));
    }

    @Test public void aMoveActionAtTheEdgeStaysInsideTheScreenAndIsStillPerformed()
            throws Exception {
        ViewGroup root = attachedRoot();
        View feedback = held("notInterestedReference");
        declared("moveTo", View.class, ViewGroup.class, float.class, float.class)
                .invoke(null, feedback, root, 0f, 0f);

        assertTrue("an action at the top edge reported failure",
                feedback.performAccessibilityAction(action("ACTION_MOVE_UP"), null));
        assertEquals(0, topOf(feedback));
        assertTrue(feedback.performAccessibilityAction(action("ACTION_MOVE_LEFT"), null));
        assertEquals(0, leftOf(feedback));
        // The corner is a position like any other, so it is written down rather than refused.
        assertFalse("a clamped move saved nothing",
                Settings.NOT_INTERESTED_BUTTON_POSITION.get().isEmpty());

        int maxLeft = 1080 - app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(root.getContext(), 48);
        declared("moveTo", View.class, ViewGroup.class, float.class, float.class)
                .invoke(null, feedback, root, (float) maxLeft, (float) maxLeft);
        assertTrue(feedback.performAccessibilityAction(action("ACTION_MOVE_DOWN"), null));
        assertTrue(feedback.performAccessibilityAction(action("ACTION_MOVE_RIGHT"), null));
        assertEquals("a control was pushed off the bottom", maxLeft, topOf(feedback));
        assertEquals("a control was pushed off the right", maxLeft, leftOf(feedback));
    }

    @Test @Config(sdk = 35)
    public void draggedControlsStayOutsideSystemBarsAndSideCutouts() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.WindowInsets insets = new android.view.WindowInsets.Builder()
                .setInsets(android.view.WindowInsets.Type.systemBars(),
                        android.graphics.Insets.of(36, 72, 54, 96))
                .build();
        FrameLayout root = new FrameLayout(activity) {
            @Override public android.view.WindowInsets getRootWindowInsets() {
                return insets;
            }
        };
        View feedback = new View(activity);
        int size = SettingsUi.dp(activity, 48);
        root.addView(feedback, new FrameLayout.LayoutParams(size, size));
        int exact = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        root.measure(exact, exact);
        root.layout(0, 0, 1080, 1080);
        Method move = declared("moveTo", View.class, ViewGroup.class, float.class, float.class);

        move.invoke(null, feedback, root, -500f, -500f);
        assertEquals("the control entered the side cutout", 36, leftOf(feedback));
        assertEquals("the control entered the status bar", 72, topOf(feedback));

        move.invoke(null, feedback, root, 2_000f, 2_000f);
        assertEquals("the control entered the right system inset", 1080 - 54 - size,
                leftOf(feedback));
        assertEquals("the control entered the navigation inset", 1080 - 96 - size,
                topOf(feedback));
    }

    @Test public void draggedControlsStayAboveTikToksBottomTabRow() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        ViewGroup root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
        FrameLayout tabBar = new FrameLayout(activity);
        View homeTab = new View(activity);
        View feedback = new View(activity);
        int size = SettingsUi.dp(activity, 48);
        tabBar.addView(homeTab, new FrameLayout.LayoutParams(216, 160));
        root.addView(tabBar, new FrameLayout.LayoutParams(1080, 160));
        root.addView(feedback, new FrameLayout.LayoutParams(size, size));
        int exact = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        root.measure(exact, exact);
        root.layout(0, 0, 1080, 1080);
        tabBar.layout(0, 920, 1080, 1080);
        homeTab.layout(0, 0, 216, 160);
        org.robolectric.util.ReflectionHelpers.setStaticField(
                FeedVisibility.class, "homeTabReference",
                new java.lang.ref.WeakReference<>(homeTab));
        try {
            declared("moveTo", View.class, ViewGroup.class, float.class, float.class)
                    .invoke(null, feedback, root, 2_000f, 2_000f);

            assertEquals("the control covered TikTok's bottom tab row",
                    1080 - 160 - size, topOf(feedback));
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(
                    FeedVisibility.class, "homeTabReference",
                    new java.lang.ref.WeakReference<>(null));
            activity.finish();
        }
    }

    @Test public void anAttachedControlIsReclampedWhenTheSafeAreaChanges() throws Exception {
        ViewGroup root = attachedRoot();
        View feedback = held("notInterestedReference");
        int size = SettingsUi.dp(root.getContext(), 48);
        declared("moveTo", View.class, ViewGroup.class, float.class, float.class)
                .invoke(null, feedback, root, 2_000f, 2_000f);
        assertEquals(1080 - size, topOf(feedback));

        FrameLayout tabBar = new FrameLayout(root.getContext());
        View homeTab = new View(root.getContext());
        tabBar.addView(homeTab, new FrameLayout.LayoutParams(216, 160));
        root.addView(tabBar, new FrameLayout.LayoutParams(1080, 160));
        tabBar.layout(0, 920, 1080, 1080);
        homeTab.layout(0, 0, 216, 160);
        org.robolectric.util.ReflectionHelpers.setStaticField(
                FeedVisibility.class, "homeTabReference",
                new java.lang.ref.WeakReference<>(homeTab));
        try {
            root.getViewTreeObserver().dispatchOnGlobalLayout();

            assertEquals("the existing control stayed under a newly visible tab row",
                    1080 - 160 - size, topOf(feedback));
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(
                    FeedVisibility.class, "homeTabReference",
                    new java.lang.ref.WeakReference<>(null));
        }
    }

    @Test public void aFailedOverlayActionReportsFailureInsteadOfSuccess() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        ShadowToast.reset();
        Setting<String> failing = new Setting<String>(
                "overlay_failure_" + System.nanoTime(), "before", false, false, null, null) {
            @Override protected void load() { value = defaultValue; }
            @Override protected void setValueFromString(String newValue) { value = newValue; }
            @Override protected void saveToPreferences() {
                throw new IllegalStateException("injected commit failure");
            }
        };

        assertFalse(BlockAuthorOverlay.saveAction(failing, "after"));
        assertEquals("before", failing.get());
        assertEquals("This change couldn't be saved. Try again.",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void aMoveActionNeverEntersPointerDragMode() throws Exception {
        ViewGroup root = attachedRoot();
        View block = held("buttonReference");
        java.lang.reflect.Field dragging = BlockAuthorOverlay.class.getDeclaredField("dragging");
        dragging.setAccessible(true);

        assertTrue(block.performAccessibilityAction(action("ACTION_MOVE_DOWN"), null));
        assertFalse("a move action left the control mid-drag", dragging.getBoolean(null));
        java.util.concurrent.atomic.AtomicInteger clicks =
                new java.util.concurrent.atomic.AtomicInteger();
        block.setOnClickListener(view -> clicks.incrementAndGet());
        assertTrue(block.performClick());
        assertEquals("the click after a move was swallowed by drag mode", 1, clicks.get());
    }

    @Test public void detachAndReattachStartOutsidePointerDragMode() throws Exception {
        ViewGroup root = attachedRoot();
        View block = held("buttonReference");
        java.lang.reflect.Field dragging = BlockAuthorOverlay.class.getDeclaredField("dragging");
        dragging.setAccessible(true);

        // Through the real gesture, or the assertion after detach is about a flag that was
        // already false and would stay green with the reset taken out.
        pressDown(block, 10f, 10f);
        assertTrue("the long press was refused", block.performLongClick());
        assertTrue("the pointer gesture did not enter drag mode", dragging.getBoolean(null));

        declared("detach").invoke(null);
        assertFalse("detach left the overlay in drag mode", dragging.getBoolean(null));

        ViewGroup next = attachedRoot();
        View reattached = held("buttonReference");
        assertNotSame("the reattach reused the detached control", block, reattached);
        assertFalse("a reattached overlay started in drag mode", dragging.getBoolean(null));
        java.util.concurrent.atomic.AtomicInteger clicks =
                new java.util.concurrent.atomic.AtomicInteger();
        reattached.setOnClickListener(view -> clicks.incrementAndGet());
        assertTrue(reattached.performClick());
        assertEquals("the reattached control's click was swallowed by stale drag state",
                1, clicks.get());
        assertNotNull(next);
    }

    @Test public void resettingOneControlDoesNotDragTheUnsavedOnesToTheBlockButton()
            throws Exception {
        // The other three default to positions relative to the block button. Re-running the whole
        // positioning pass on a reset therefore moved every control with nothing saved to wherever
        // the block button had since been dragged, from a reset performed on a different control.
        ViewGroup root = attachedRoot();
        View block = held("buttonReference");
        View local = held("localHideReference");
        View sound = held("soundButtonReference");
        View feedback = held("notInterestedReference");

        Method move = declared("moveTo", View.class, ViewGroup.class, float.class, float.class);
        Method save = declared("savePosition", View.class, ViewGroup.class);
        move.invoke(null, block, root, 0f, 0f);
        save.invoke(null, block, root);
        int localLeft = leftOf(local);
        int localTop = topOf(local);
        int feedbackLeft = leftOf(feedback);

        assertTrue(sound.performAccessibilityAction(action("ACTION_RESET_POSITION"), null));

        assertEquals("resetting the sound button moved the local hide button",
                localLeft, leftOf(local));
        assertEquals(localTop, topOf(local));
        assertEquals("resetting the sound button moved the Not interested button",
                feedbackLeft, leftOf(feedback));
        assertEquals("the reset moved the block button it was not performed on", 0, leftOf(block));
        assertEquals("", Settings.BLOCK_SOUND_BUTTON_POSITION.get());

        // The sound button's own default: two steps below the block button, which is now at the
        // top-left corner, so the reset does move the control it was performed on.
        int size = app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(root.getContext(), 48);
        int step = size + app.morphe.extension.tiktok.settings.preference.SettingsUi
                .dp(root.getContext(), 8);
        assertEquals("the control that was reset did not take its own default", 0, leftOf(sound));
        assertEquals(2 * step, topOf(sound));
    }

    /** The pointer half of a long press: the listener refuses a gesture with no press behind it. */
    private static void pressDown(View view, float x, float y) {
        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                0L, 0L, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            view.dispatchTouchEvent(down);
        } finally {
            down.recycle();
        }
    }

    /**
     * Mute feed videos keeps one name and reads out its state: a screen reader hears "Mute feed
     * videos", checked or not, and Muted or Sound on, and a tap flips the saved mute.
     */
    @Test @org.robolectric.annotation.Config(sdk = 34)
    public void theMuteButtonReadsOutWhetherTheFeedIsMuted() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        boolean wasEnabled = app.morphe.extension.tiktok.settings.SettingsStatus.feedMuteEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.feedMuteEnabled = true;
        Settings.FEED_MUTED.save(false);
        try {
            Method factory = BlockAuthorOverlay.class.getDeclaredMethod("createMuteButton", Activity.class);
            factory.setAccessible(true);
            View mute = (View) factory.invoke(null, activity);
            assertEquals("Mute feed videos", mute.getContentDescription().toString());
            AccessibilityNodeInfo node = mute.createAccessibilityNodeInfo();
            assertTrue("the mute button isn't a switch to a reader", node.isCheckable());
            assertFalse(node.isChecked());
            assertEquals("Sound on", String.valueOf(mute.getStateDescription()));
            node.recycle();

            mute.performClick();
            assertTrue("a tap didn't save the mute", Settings.FEED_MUTED.get());
            node = mute.createAccessibilityNodeInfo();
            assertTrue(node.isChecked());
            assertEquals("Muted", String.valueOf(mute.getStateDescription()));
            assertEquals("the name changed with the state", "Mute feed videos", mute.getContentDescription().toString());
            node.recycle();

            mute.performClick();
            assertFalse(Settings.FEED_MUTED.get());
            assertEquals("Sound on", String.valueOf(mute.getStateDescription()));
        } finally {
            Settings.FEED_MUTED.resetToDefault();
            app.morphe.extension.tiktok.settings.SettingsStatus.feedMuteEnabled = wasEnabled;
        }
    }

    private static final String[] CONTROLS = {"buttonReference", "localHideReference",
            "soundButtonReference", "notInterestedReference", "muteReference"};

    /** The content root with all four controls attached, laid out at a known size. */
    /**
     * Out of the box, no chip sits on TikTok's own rail.
     *
     * <p>At 0.91 of the width the column of chips ran straight down the avatar, like, comment
     * and share column. On 46.2.3 at 1080x2316 the avatar is at 932,1019 to 1057,1144 and the
     * hide chip was drawn at 915,1017 to 1050,1152, so a tap on the creator's face hid the
     * creator (S22, 2026-09-15, undone through the editor). A position the reader has dragged
     * to is theirs and stays where they put it.
     */
    @Test public void theDefaultChipsKeepClearOfTikToksOwnRail() throws Exception {
        Settings.BLOCK_AUTHOR_BUTTON_POSITION.resetToDefault();
        Settings.LOCAL_HIDE_BUTTON_POSITION.resetToDefault();
        Settings.BLOCK_SOUND_BUTTON_POSITION.resetToDefault();
        Settings.NOT_INTERESTED_BUTTON_POSITION.resetToDefault();
        Settings.FEED_MUTE_BUTTON_POSITION.resetToDefault();
        ViewGroup root = attachedRoot(1080, 2316);
        // TikTok's rail on 46.2.3 at this size: the avatar as uiautomator reports it, and the
        // column under it, which the like, comment, favourite, share and sound controls share.
        android.graphics.Rect avatar = new android.graphics.Rect(932, 1019, 1057, 1144);
        android.graphics.Rect rail = new android.graphics.Rect(915, 1019, 1080, 2100);
        for (String name : new String[]{"buttonReference", "localHideReference",
                "soundButtonReference", "notInterestedReference", "muteReference"}) {
            View chip = held(name);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) chip.getLayoutParams();
            android.graphics.Rect box = new android.graphics.Rect(params.leftMargin,
                    params.topMargin, params.leftMargin + params.width,
                    params.topMargin + params.height);
            assertTrue(name + " has no size", params.width > 0 && params.height > 0);
            assertFalse(name + " at " + box + " covers the creator's avatar " + avatar,
                    android.graphics.Rect.intersects(box, avatar));
            assertFalse(name + " at " + box + " sits on TikTok's rail " + rail,
                    android.graphics.Rect.intersects(box, rail));
            assertTrue(name + " at " + box + " is off the screen",
                    box.left >= 0 && box.right <= 1080 && box.top >= 0 && box.bottom <= 2316);
        }

        // A reader who dragged the block chip onto the old spot keeps it there.
        Settings.BLOCK_AUTHOR_BUTTON_POSITION.save("0.91,0.40");
        declared("applyPositions", ViewGroup.class).invoke(null, root);
        FrameLayout.LayoutParams moved =
                (FrameLayout.LayoutParams) held("buttonReference").getLayoutParams();
        assertEquals("a saved position was not honoured over the new default",
                Math.round(0.91f * 1080 - moved.width / 2f), moved.leftMargin);
        assertEquals(Math.round(0.40f * 2316 - moved.height / 2f), moved.topMargin);
    }

    private static ViewGroup attachedRoot() throws Exception {
        return attachedRoot(1080, 1080);
    }

    private static ViewGroup attachedRoot(int width, int height) throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        ViewGroup root = activity.findViewById(android.R.id.content);
        declared("attach", VideoAuthor.class)
                .invoke(null, new VideoAuthor("1", "sec", "someone", "7712345"));
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        // attach posts the positioning pass before the root has a size, so the defaults these
        // tests compare against have to be the ones for the size just laid out.
        declared("applyPositions", ViewGroup.class).invoke(null, root);
        return root;
    }

    private static Method declared(String name, Class<?>... parameters) throws Exception {
        Method method = BlockAuthorOverlay.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static int action(String constant) throws Exception {
        java.lang.reflect.Field field = BlockAuthorOverlay.class.getDeclaredField(constant);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static int leftOf(View view) {
        return ((FrameLayout.LayoutParams) view.getLayoutParams()).leftMargin;
    }

    private static int topOf(View view) {
        return ((FrameLayout.LayoutParams) view.getLayoutParams()).topMargin;
    }

    @SuppressWarnings("unchecked")
    private static View held(String fieldName) throws Exception {
        java.lang.reflect.Field field = BlockAuthorOverlay.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        View view = ((java.lang.ref.WeakReference<View>) field.get(null)).get();
        assertNotNull(fieldName + " was never attached", view);
        return view;
    }

    @Test public void theOverlaysFollowTheActivityTheHostRecreated() {
        Activity first = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(first);
        assertSame(first, Utils.getActivity());

        // The host recreates its main activity on a configuration change it does not swallow,
        // and the extension hook runs again for the new one. Keeping the first instance left
        // every overlay attaching to a window nobody was looking at, and isFinishing() reports
        // nothing for a recreated activity because it is destroyed rather than finishing.
        Activity second = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(second);

        assertSame("the overlays would still be drawing on the old window", second, Utils.getActivity());
    }

    @Test public void aSecondUndoBannerKeepsItsOwnSixSeconds() {
        // Block an author and hide one locally inside six seconds: the second banner replaced the
        // first, and the first banner's dismiss was still queued, so it took the second away
        // early and the Undo went with it.
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        android.view.ViewGroup root = activity.findViewById(android.R.id.content);
        var looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper());

        BlockAuthorOverlay.showUndoBanner("first", () -> { });
        looper.idle();
        int withOne = root.getChildCount();
        assertTrue("the first banner was never shown", withOne > 0);

        looper.idleFor(java.time.Duration.ofSeconds(3));
        BlockAuthorOverlay.showUndoBanner("second", () -> { });
        looper.idle();

        looper.idleFor(java.time.Duration.ofSeconds(3));
        assertEquals("the first banner's timer took the second one away", withOne,
                root.getChildCount());

        // And the second banner still goes away on its own time rather than staying forever.
        looper.idleFor(java.time.Duration.ofSeconds(4));
        assertTrue("the banner never went away", root.getChildCount() < withOne);
    }
    /**
     * The part of a control's background that holds the backdrop, any glyph and the focus ring.
     *
     * <p>Every control this bundle draws is a RippleDrawable now, so the press is the ripple and
     * the states live in the layers underneath it. RippleDrawable is itself a LayerDrawable, so
     * this unwraps exactly one level rather than testing for the type.
     */
    private static android.graphics.drawable.LayerDrawable contentOf(
            android.graphics.drawable.Drawable background) {
        assertTrue("the control's background is not a ripple, so a press shows nothing: "
                        + background.getClass().getSimpleName(),
                background instanceof android.graphics.drawable.RippleDrawable);
        return (android.graphics.drawable.LayerDrawable)
                ((android.graphics.drawable.RippleDrawable) background).getDrawable(0);
    }

    /**
     * Every control drawn inside TikTok answers a press and shows where the focus is.
     *
     * <p>They were flat: the same picture before, during and after a press, and nothing at all
     * for a reader moving with a keyboard, a d-pad or switch access. The block button was the one
     * exception and only faded itself to 40 percent while a request was in flight.
     */
    @Test public void everyControlDrawnInsideTikTokAnswersAPressAndShowsItsFocus() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        Settings.BLOCK_AUTHOR_BUTTON.save(true);
        Settings.LOCAL_HIDE_BUTTON.save(true);
        Settings.BLOCK_SOUND_BUTTON.save(true);
        Settings.NOT_INTERESTED_BUTTON.save(true);

        for (String factoryName : new String[]{"createButton", "createSoundButton",
                "createLocalHideButton", "createNotInterestedButton", "createMuteButton"}) {
            Method factory = BlockAuthorOverlay.class.getDeclaredMethod(factoryName, Activity.class);
            factory.setAccessible(true);
            View control = (View) factory.invoke(null, activity);
            android.graphics.drawable.Drawable background = control.getBackground();
            contentOf(background);

            int resting = renderOf(background, new int[0]);
            int focused = renderOf(background, new int[]{android.R.attr.state_focused});
            assertNotEquals(factoryName + " looks the same focused as it does at rest",
                    resting, focused);
            // A clickable view is only focusable by default from API 26, and the ring is dead on
            // a d-pad below that unless it is said outright.
            assertTrue(factoryName + " cannot be reached by a keyboard or d-pad",
                    control.isFocusable());
        }
    }

    /**
     * The banner is one of the overlay family, and it sits where nothing of TikTok's is.
     *
     * <p>It was a 10dp charcoal box with no hairline beside 12dp scrim chips with one, and it
     * was pinned 96dp up from the bottom of whatever root it was given: over the tab bar and
     * caption on one phone, and in the comments sheet right on top of the input row.
     */
    @Test public void theBannerWearsTheOverlayFamilyAndClearsTheTabBar() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        Utils.setActivity(activity);
        ViewGroup root = activity.findViewById(android.R.id.content);
        // A tab bar the height of a real one across the bottom, the way the hold panel's own
        // test builds it, and the Home tab put straight into the lookup's cache.
        FrameLayout bar = new FrameLayout(activity);
        View homeTab = new View(activity);
        bar.addView(homeTab);
        root.addView(bar);
        layoutAt(root, 480, 960);
        bar.layout(0, 860, 480, 960);
        homeTab.layout(0, 0, 96, 100);
        org.robolectric.util.ReflectionHelpers.setStaticField(FeedVisibility.class,
                "homeTabReference", new java.lang.ref.WeakReference<>(homeTab));

        // Measured here rather than after a looper idle: the idle runs the window's own layout
        // pass, which puts the hand-laid bar wherever the window wants it.
        FrameLayout.LayoutParams params = BlockAuthorOverlay.bannerParams(activity, root);
        assertEquals("the banner does not clear the tab bar",
                100 + SettingsUi.dp(activity, 16), params.bottomMargin);
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);

        FrameLayout sheet = new FrameLayout(activity);
        BlockAuthorOverlay.showUndoBanner(sheet, "Blocked someone", () -> { });
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        View banner = sheet.getChildAt(sheet.getChildCount() - 1);
        android.graphics.drawable.Drawable background = banner.getBackground();
        assertTrue("the banner is not drawn on the overlay family's chip: "
                + background.getClass().getSimpleName(),
                background instanceof android.graphics.drawable.GradientDrawable);
        android.graphics.drawable.GradientDrawable chip =
                (android.graphics.drawable.GradientDrawable) background;
        assertEquals(SettingsUi.dp(activity, SettingsUi.RADIUS_OVERLAY), chip.getCornerRadius(), 0.5f);
        assertEquals(SettingsUi.OVERLAY_BANNER_SCRIM, chip.getColor().getDefaultColor());
    }

    @Test public void inASheetTheBannerSitsAboveTheInputRow() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        // The comments sheet's window: its own root, an input row along the bottom.
        FrameLayout sheet = new FrameLayout(activity);
        android.widget.EditText input = new android.widget.EditText(activity);
        sheet.addView(input);
        layoutAt(sheet, 480, 960);
        input.layout(0, 880, 480, 960);

        BlockAuthorOverlay.showUndoBanner(sheet, "Blocked someone", () -> { });
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        View banner = sheet.getChildAt(sheet.getChildCount() - 1);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) banner.getLayoutParams();
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        assertEquals("the banner lands on the input row", 80 + SettingsUi.dp(activity, 16),
                params.bottomMargin);

        // A sheet with nothing to clear along the bottom keeps the banner near the bottom, the
        // fallback offset up. Its top was the old answer, and on a full-screen sheet window the
        // top is the dark video above the visible panel, where the S22 showed the save banner
        // reading as nothing at all.
        FrameLayout bare = new FrameLayout(activity);
        layoutAt(bare, 480, 960);
        BlockAuthorOverlay.showUndoBanner(bare, "Blocked someone", () -> { });
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        params = (FrameLayout.LayoutParams) bare.getChildAt(bare.getChildCount() - 1).getLayoutParams();
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, params.gravity);
        assertEquals(SettingsUi.dp(activity, 96), params.bottomMargin);
    }

    private static void layoutAt(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    /**
     * The Undo on the block banner answers a press and shows its focus, like the four controls
     * it undoes. It sits on the banner's own dark surface, so its ring and ripple are the white
     * the over-video controls use.
     */
    @Test public void theUndoBannerAnswersAPressAndShowsItsFocus() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        FrameLayout root = new FrameLayout(activity);
        BlockAuthorOverlay.showUndoBanner(root, "Blocked someone", () -> { });
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ViewGroup banner = (ViewGroup) root.getChildAt(root.getChildCount() - 1);
        assertNotNull("no banner was drawn", banner);
        View undo = banner.getChildAt(banner.getChildCount() - 1);
        assertTrue("Undo is not reachable by a keyboard or d-pad", undo.isFocusable());
        android.graphics.drawable.Drawable background = undo.getBackground();
        assertTrue("Undo has no ripple: " + (background == null ? "null"
                        : background.getClass().getSimpleName()),
                background instanceof android.graphics.drawable.RippleDrawable);
        assertNotEquals("Undo looks the same focused as it does at rest",
                renderOf(background, new int[0]),
                renderOf(background, new int[]{android.R.attr.state_focused}));
    }

    /**
     * What the background actually paints in the state given, as one number.
     *
     * <p>Every pixel rather than a sample: the difference a focus ring makes is a two pixel
     * stroke at the edge, and which pixel that lands on depends on the radius and the density.
     */
    private static int renderOf(android.graphics.drawable.Drawable background, int[] state) {
        background.setState(state);
        background.setBounds(0, 0, 48, 48);
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        background.draw(new Canvas(bitmap));
        int hash = 17;
        for (int x = 0; x < 48; x++) {
            for (int y = 0; y < 48; y++) hash = hash * 31 + bitmap.getPixel(x, y);
        }
        bitmap.recycle();
        return hash;
    }
}
