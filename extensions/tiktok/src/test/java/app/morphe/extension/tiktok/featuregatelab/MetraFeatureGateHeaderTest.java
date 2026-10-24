package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercises the shared Metra toolbar through its Lab consumer, including fresh view creation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w390dp-h844dp-night-mdpi")
public class MetraFeatureGateHeaderTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @Test public void openingAndRecreatingTheLabKeepsItsTitleAndActions() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabUi.syncTheme(activity);
            for (int creation = 0; creation < 2; creation++) {
                boolean[] clicked = new boolean[2];
                FrameLayout frame = FeatureGateLabUi.header(activity, "Feature Gate Lab",
                        () -> clicked[0] = true, () -> clicked[1] = true);
                activity.setContentView(frame);
                frame.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.AT_MOST));
                frame.layout(0, 0, 390, frame.getMeasuredHeight());
                TextView title = frame.findViewWithTag("hushfeed_page_title");
                LinearLayout toolbar = frame.findViewWithTag("hushfeed_toolbar");
                assertEquals("Feature Gate Lab", title.getText().toString());
                assertSame("the Lab must retain Metra's toolbar title", toolbar, title.getParent());
                assertEquals(17f, title.getTextSize()
                        / activity.getResources().getDisplayMetrics().scaledDensity, .01f);
                assertTrue(toolbar.getChildAt(0).performClick());
                View menu = frame.findViewWithTag("feature_gate_menu");
                assertNotNull(menu);
                assertTrue(menu.performClick());
                assertTrue(clicked[0] && clicked[1]);
                assertTrue(menu.getWidth() >= 48 && menu.getHeight() >= 48);
            }
        }
    }

    @Test @Config(qualifiers = "w390dp-h844dp-notnight-mdpi")
    public void lightLabKeepsTheSameToolbarContract() {
        openingAndRecreatingTheLabKeepsItsTitleAndActions();
    }

    @Test public void aGateDetailKeepsMetraTypographyWithoutAnOverflowMenu() {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FrameLayout frame = FeatureGateLabUi.header(activity, "Gate detail", () -> { });
            TextView title = frame.findViewWithTag("hushfeed_page_title");
            assertEquals(17f, title.getTextSize()
                    / activity.getResources().getDisplayMetrics().scaledDensity, .01f);
            assertNull(frame.findViewWithTag("feature_gate_menu"));
        }
    }
}
