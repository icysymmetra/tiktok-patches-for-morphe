package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35})
@LooperMode(LooperMode.Mode.PAUSED)
public class CompactCommentHeaderTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private boolean original;

    @Before public void remember() { original = Settings.COMPACT_COMMENT_HEADER.get(); }
    @After public void restore() { Settings.COMPACT_COMMENT_HEADER.save(original); }

    @Test public void disabledBindDoesNotChangeGeometryOrAccessibility() {
        Settings.COMPACT_COMMENT_HEADER.save(false);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View root = header(owner.get());
            FrameLayout parent = new FrameLayout(owner.get());
            parent.addView(root);
            owner.get().setContentView(parent);
            CompactCommentHeader.bindCountBar(root);
            idle();
            assertEquals(View.VISIBLE, root.getVisibility());
            assertEquals(44, root.getLayoutParams().height);
            assertEquals(44, root.getMinimumHeight());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, root.getImportantForAccessibility());
        }
    }

    @Test public void allThreeRootsCollapseWithoutTouchingListComposerOrNativeListeners() {
        Settings.COMPACT_COMMENT_HEADER.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            View count = header(activity), legacy = header(activity), common = header(activity);
            View list = new View(activity);
            EditText composer = new EditText(activity);
            column.addView(legacy);
            column.addView(common);
            column.addView(count);
            column.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            column.addView(composer, new LinearLayout.LayoutParams(-1, 48));
            activity.setContentView(column);
            count.setOnClickListener(view -> composer.setTag("native listener retained"));
            layout(column);
            int originalListTop = list.getTop(), originalListHeight = list.getHeight();
            CompactCommentHeader.bindLegacyBanner(legacy);
            CompactCommentHeader.bindCommonBanner(common);
            CompactCommentHeader.bindCountBar(count);
            idle();
            layout(column);
            for (View root : new View[]{count, legacy, common}) {
                assertEquals(View.GONE, root.getVisibility());
                assertEquals(0, root.getLayoutParams().height);
                assertEquals(0, root.getMinimumHeight());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        root.getImportantForAccessibility());
            }
            assertEquals(0, list.getTop());
            assertEquals(originalListHeight + originalListTop, list.getHeight());
            assertEquals(View.VISIBLE, composer.getVisibility());
            assertTrue(composer.isEnabled());
            count.performClick();
            assertEquals("native listener retained", composer.getTag());
        }
    }

    @Test public void nativeRebindCannotLeaveInvisibleSpaceAndDisablingRestoresOriginalMetrics() {
        Settings.COMPACT_COMMENT_HEADER.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            FrameLayout parent = new FrameLayout(owner.get());
            View root = header(owner.get());
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, 44);
            params.topMargin = 5;
            params.bottomMargin = 7;
            parent.addView(root, params);
            owner.get().setContentView(parent);
            CompactCommentHeader.bindCountBar(root);
            idle();
            CompactCommentHeader.bindCountBar(root); // Must not snapshot the already hidden state.
            root.setVisibility(View.VISIBLE);
            root.getLayoutParams().height = 60;
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            idle();
            assertEquals(View.GONE, root.getVisibility());
            assertEquals(0, root.getLayoutParams().height);
            assertEquals(0, params.topMargin);
            assertEquals(0, params.bottomMargin);
            Settings.COMPACT_COMMENT_HEADER.save(false);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(View.VISIBLE, root.getVisibility());
            assertEquals(44, root.getLayoutParams().height);
            assertEquals(5, params.topMargin);
            assertEquals(7, params.bottomMargin);
            assertEquals(44, root.getMinimumHeight());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, root.getImportantForAccessibility());
        }
    }

    @Test public void cachedRootReattachesWithoutAnotherBind() {
        Settings.COMPACT_COMMENT_HEADER.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            FrameLayout parent = new FrameLayout(owner.get());
            View root = header(owner.get());
            parent.addView(root);
            owner.get().setContentView(parent);
            CompactCommentHeader.bindCommonBanner(root);
            idle();
            parent.removeView(root);
            assertEquals(View.VISIBLE, root.getVisibility());
            parent.addView(root);
            idle();
            assertEquals(View.GONE, root.getVisibility());
            assertEquals(0, root.getLayoutParams().height);
        }
    }

    @Test public void missingRootsFailOpenAndSettingIsOptionalAndRestartGated() {
        Settings.COMPACT_COMMENT_HEADER.save(true);
        CompactCommentHeader.bindCountBar(null);
        CompactCommentHeader.bindLegacyBanner(null);
        CompactCommentHeader.bindCommonBanner(null);
        assertEquals(Boolean.FALSE, Settings.COMPACT_COMMENT_HEADER.defaultValue);
        assertTrue(Settings.COMPACT_COMMENT_HEADER.rebootApp);
    }

    @Test public void aNativeListTabAppearingLaterRestoresTheBarSoNavigationIsNotTrapped() {
        Settings.COMPACT_COMMENT_HEADER.save(true);
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            FrameLayout parent = new FrameLayout(owner.get());
            FrameLayout count = new FrameLayout(owner.get());
            HorizontalScrollView tabs = new HorizontalScrollView(owner.get());
            tabs.setVisibility(View.GONE);
            FrameLayout nativeInclude = new FrameLayout(owner.get());
            nativeInclude.addView(tabs);
            count.addView(nativeInclude);
            parent.addView(count, new FrameLayout.LayoutParams(-1, 44));
            owner.get().setContentView(parent);
            CompactCommentHeader.bindCountBar(count);
            idle();
            assertEquals(View.GONE, count.getVisibility());
            tabs.setVisibility(View.VISIBLE);
            count.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(View.VISIBLE, count.getVisibility());
            assertEquals(44, count.getLayoutParams().height);
            tabs.setVisibility(View.GONE);
            count.getViewTreeObserver().dispatchOnGlobalLayout();
            assertEquals(View.GONE, count.getVisibility());
        }
    }

    private static View header(Activity activity) {
        View root = new View(activity);
        root.setMinimumHeight(44);
        root.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, 44));
        return root;
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 480, 900);
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
}
