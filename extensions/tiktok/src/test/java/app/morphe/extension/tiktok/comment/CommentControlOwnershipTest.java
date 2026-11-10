package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockGlyphDrawable;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/** Native-like comment controls reached through the listener-install and cell-bind hooks. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentControlOwnershipTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.BLOCK_FROM_COMMENT.save(false);
        Settings.COMMENT_SEARCH.save(false);
        Settings.PAUSE_ON_COMMENTS.save(false);
        ReflectionHelpers.<Map<View, ?>>getStaticField(CommentTools.class, "CELL_COMMENTS").clear();
        ReflectionHelpers.<Set<String>>getStaticField(CommentTools.class, "BLOCKED_UIDS").clear();
        ShadowToast.reset();
        Object cache = ReflectionHelpers.getStaticField(CommentTools.class, "RESOURCE_IDS");
        Map<String, Integer> ids = ReflectionHelpers.getField(cache, "ids");
        ids.put(RuntimeEnvironment.getApplication().getPackageName() + ":47.4.3:k11", 0x7f000201);
        ids.put(RuntimeEnvironment.getApplication().getPackageName() + ":47.4.3:mne", 0x7f000202);
        ids.put(RuntimeEnvironment.getApplication().getPackageName() + ":jlk", 0x7f000101);
        ids.put(RuntimeEnvironment.getApplication().getPackageName() + ":m3b", 0x7f000102);
    }

    @After public void tearDown() {
        Settings.BLOCK_FROM_COMMENT.resetToDefault();
        Settings.COMMENT_SEARCH.resetToDefault();
        Settings.PAUSE_ON_COMMENTS.resetToDefault();
        ReflectionHelpers.<Set<String>>getStaticField(CommentTools.class, "BLOCKED_UIDS").clear();
    }

    @Test public void disablingRestoresNativeTouchAndTheActualBoundCellsAppearance() {
        Cell cell = new Cell();
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);
        tap(cell.nativeRow.button);
        assertEquals("the native control must work before the first takeover", 1, cell.nativeRow.taps);

        cell.itemView.setAlpha(0.82f);
        cell.nativeRow.actionRow.setAlpha(0.65f);
        cell.nativeRow.button.setContentDescription("Dislike");
        CommentTools.markBlockedForTests("uid-bound");
        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        assertEquals(0.55f, cell.itemView.getAlpha(), 0.001f);
        assertBlockTouch(cell.nativeRow.button);
        assertEquals(1, cell.nativeRow.taps);

        Settings.BLOCK_FROM_COMMENT.save(false);
        bind(cell);
        assertEquals("restore the itemView captured at bind, not the nearest row parent",
                0.82f, cell.itemView.getAlpha(), 0.001f);
        assertEquals(0.65f, cell.nativeRow.actionRow.getAlpha(), 0.001f);
        assertEquals("Dislike", String.valueOf(cell.nativeRow.button.getContentDescription()));
        assertFalse("the takeover's accessibility click survived handback",
                cell.nativeRow.button.hasOnClickListeners());
        tap(cell.nativeRow.button);
        assertEquals("the once-installed native listener was lost", 2, cell.nativeRow.taps);

        // Constructor-only native initialization does not run again on this pooled cell.
        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        assertBlockTouch(cell.nativeRow.button);
        Settings.BLOCK_FROM_COMMENT.save(false);
        bind(cell);
        tap(cell.nativeRow.button);
        assertEquals(3, cell.nativeRow.taps);
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void theBlockControlDrawsABlockSymbolAndGivesTikToksIconBack() {
        // With the switch on, a tap on what still looked exactly like TikTok's thumbs down
        // blocked an account. The only thing that told a reader otherwise was the tint, which
        // arrives after the block. The shape says it beforehand now.
        Cell cell = new Cell();
        ColorDrawable nativeIcon = new ColorDrawable(Color.GREEN);
        cell.nativeRow.icon.setImageDrawable(nativeIcon);
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);

        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        assertTrue("a control that blocks still looked like a thumbs down",
                cell.nativeRow.icon.getDrawable() instanceof BlockGlyphDrawable);

        Settings.BLOCK_FROM_COMMENT.save(false);
        bind(cell);
        assertSame("TikTok's own icon did not come back with the control",
                nativeIcon, cell.nativeRow.icon.getDrawable());
    }

    /**
     * The symbol takes the grey TikTok gives most of the row's text, whatever the Android theme
     * says: TikTok's dark mode is its own, and the theme's secondary colour put a near black
     * symbol on the S25's near black sheet (2026-09-26).
     */
    @Test public void theBlockSymbolTakesTheColourOfTheRowsOwnSecondaryText() {
        int grey = 0xFF8A8B91;
        Cell cell = new Cell();
        cell.itemView.addView(text("poster", grey));
        cell.itemView.addView(text("a comment in the primary colour", Color.WHITE));
        cell.itemView.addView(text("2h", grey));
        cell.itemView.addView(text("Reply", grey));
        cell.itemView.addView(text("12", Color.RED));
        // Empty and hidden text says nothing about the sheet. Either kind counted, red would win.
        for (int i = 0; i < 3; i++) {
            cell.itemView.addView(text("", Color.RED));
            TextView hidden = text("hidden", Color.RED);
            hidden.setVisibility(View.GONE);
            cell.itemView.addView(hidden);
        }
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);

        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        assertTrue(cell.nativeRow.icon.getDrawable() instanceof BlockGlyphDrawable);
        android.graphics.Paint paint = ReflectionHelpers.getField(cell.nativeRow.icon.getDrawable(), "paint");
        assertEquals("the symbol wasn't drawn in the row's own secondary grey",
                Integer.toHexString(grey), Integer.toHexString(paint.getColor()));
    }

    private static TextView text(String value, int colour) {
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        view.setText(value);
        view.setTextColor(colour);
        return view;
    }

    @Test public void olderBuildNamesAreNeverTakenForTheThumbsDown() {
        // jlk and m3b were the thumbs down and its icon on 46.x. On 47.0.3 they name other
        // views, so a row carrying them is left to TikTok even with the switch on.
        Cell cell = new Cell(0x7f000101, 0x7f000102);
        ColorDrawable nativeIcon = new ColorDrawable(Color.GREEN);
        cell.nativeRow.icon.setImageDrawable(nativeIcon);
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);

        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);

        assertSame("a 46.x id had its icon swapped for the block symbol",
                nativeIcon, cell.nativeRow.icon.getDrawable());
        tap(cell.nativeRow.button);
        assertEquals("a 46.x id lost its own touch to the block gesture", 1, cell.nativeRow.taps);
    }

    @Test public void aNativeRebindRefreshesTheListenerThatWillBeHandedBack() {
        Cell cell = new Cell();
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);
        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        int[] replacements = {0};
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, (view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) replacements[0]++;
            return true;
        });
        bind(cell);
        assertBlockTouch(cell.nativeRow.button);
        Settings.BLOCK_FROM_COMMENT.save(false);
        bind(cell);
        tap(cell.nativeRow.button);
        assertEquals(0, cell.nativeRow.taps);
        assertEquals(1, replacements[0]);
    }

    @Test public void disablingDuringAPressDoesNotReleaseThatPressOntoTheNativeDislike() {
        Cell cell = new Cell();
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);
        // TikTok's onTouch$9 leaves its pressed flag set after CANCEL.
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_DOWN);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_CANCEL);
        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_DOWN);
        Settings.BLOCK_FROM_COMMENT.save(false);
        bind(cell);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_UP);
        assertEquals("the press belonged to the block control", 0, cell.nativeRow.taps);
        tap(cell.nativeRow.button);
        assertEquals("a later native gesture must still work", 1, cell.nativeRow.taps);
    }

    @Test public void rebindingDuringAPressKeepsThatReleaseAwayFromAStaleNativePress() {
        Cell cell = new Cell();
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_DOWN);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_CANCEL);
        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_DOWN);
        // A fresh manager carries another native Comment through the real cell-bind hook.
        bind(cell);
        Settings.BLOCK_FROM_COMMENT.save(false);
        bind(cell);
        dispatch(cell.nativeRow.button, MotionEvent.ACTION_UP);
        assertEquals("rebinding forgot who owned the block press", 0, cell.nativeRow.taps);
        tap(cell.nativeRow.button);
        assertEquals("a later native gesture must still work", 1, cell.nativeRow.taps);
    }

    @Test public void aDiscardedCellCanBeCollectedWithItsOriginalNativeListener() {
        WeakReference<View> cell = abandonTakenOverCell();
        for (int attempt = 0; attempt < 20 && cell.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
        }
        assertNull("a static control index retained the discarded native view/listener cycle", cell.get());
    }

    private static WeakReference<View> abandonTakenOverCell() {
        Cell cell = new Cell();
        // The real native listener owns its row, whose children include the indexed control.
        CommentTools.setDislikeTouchListener(cell.nativeRow.button, cell.nativeRow);
        Settings.BLOCK_FROM_COMMENT.save(true);
        bind(cell);
        assertBlockTouch(cell.nativeRow.button);
        return new WeakReference<>(cell.itemView);
    }

    private static void bind(Cell cell) {
        CommentTools.registerCommentCell(cell.itemView, new CommentDislikeGestureTest.Manager());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void assertBlockTouch(View button) {
        Object touch = ReflectionHelpers.getStaticField(CommentTools.class, "DISLIKE_TOUCH");
        Map<View, ?> gestures = ReflectionHelpers.getField(touch, "gestures");
        dispatch(button, MotionEvent.ACTION_DOWN);
        assertTrue("the bound control did not reach the block gesture", gestures.containsKey(button));
        dispatch(button, MotionEvent.ACTION_CANCEL);
    }

    private static void tap(View button) {
        dispatch(button, MotionEvent.ACTION_DOWN);
        dispatch(button, MotionEvent.ACTION_UP);
    }

    private static void dispatch(View view, int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 5, 5, 0);
        try {
            view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static final class Cell {
        final FrameLayout itemView = new FrameLayout(RuntimeEnvironment.getApplication()) {
            // Run the real bind's posted takeover without a window retaining the GC fixture.
            @Override public boolean post(Runnable action) { action.run(); return true; }
        };
        final NativeRow nativeRow = new NativeRow(itemView.getContext());

        Cell() { this(0x7f000201, 0x7f000202); }

        Cell(int buttonId, int iconId) {
            nativeRow.button.setId(buttonId);
            nativeRow.icon.setId(iconId);
            itemView.addView(nativeRow);
        }
    }

    private static final class NativeRow extends FrameLayout implements View.OnTouchListener {
        final FrameLayout actionRow;
        final RelativeLayout button;
        final ImageView icon;
        int taps;
        boolean pressed;

        NativeRow(Context context) {
            super(context);
            actionRow = new FrameLayout(context);
            button = new RelativeLayout(context);
            button.setId(0x7f000201);
            icon = new ImageView(context);
            icon.setId(0x7f000202);
            button.addView(icon);
            actionRow.addView(button);
            addView(actionRow);
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            // ATListenerS437S0100000_22.onTouch$9: DOWN sets the owner's pressed flag,
            // UP consumes it, and CANCEL only resets the animation without clearing it.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) pressed = true;
            if (event.getActionMasked() == MotionEvent.ACTION_UP && pressed) {
                pressed = false;
                taps++;
            }
            return true;
        }
    }
}
