package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Long translated page titles stay whole and inside the title's measured box at a 320 dp window
 * and twice the text size, including when the host is narrower than the configuration width.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w320dp-h800dp-mdpi", fontScale = 2.0f)
public class SettingsHeadingFitTest {
    private static final String[] TITLES = {
            "Comments",
            "Kommentare und Übersetzung",
            "Sicherung und Wiederherstellung",
            "Benachrichtigungseinstellungen",
            "Datenschutzeinstellungen und Berechtigungen",
    };

    @Test public void atTheConfigurationWidth() {
        for (String title : TITLES) assertFits(title, 320, View.LAYOUT_DIRECTION_LTR);
    }

    @Test public void inRightToLeft() {
        for (String title : TITLES) assertFits(title, 320, View.LAYOUT_DIRECTION_RTL);
    }

    @Test public void inAHostNarrowerThanItsConfiguration() {
        for (String title : TITLES) assertFits(title, 288, View.LAYOUT_DIRECTION_LTR);
    }

    private static void assertFits(String title, int hostDp, int direction) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FrameLayout page = new FrameLayout(activity);
            int gutter = SettingsUi.dp(activity, 16);
            page.setPadding(gutter, 0, gutter, 0);
            LinearLayout header = SettingsHeaderPreference.createHeader(activity, title, () -> { });
            page.addView(header, new FrameLayout.LayoutParams(-1, -2));
            setDirection(page, direction);
            activity.setContentView(page);
            View root = activity.findViewById(android.R.id.content);
            root.measure(View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, hostDp), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SettingsUi.dp(activity, 800), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());

            TextView view = header.findViewWithTag("hushfeed_page_title");
            assertNotNull(view);
            String where = title + " at " + hostDp + "dp";
            assertEquals(where + " lost text", title, view.getText().toString());
            Layout layout = view.getLayout();
            assertNotNull(layout);
            int textWidth = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
            assertTrue(where + " ends outside the header", view.getRight() <= header.getWidth());
            assertTrue(where + " starts outside the header", view.getLeft() >= 0);
            assertTrue(where + " is clipped vertically", view.getHeight()
                    >= layout.getHeight() + view.getCompoundPaddingTop() + view.getCompoundPaddingBottom());
            for (int line = 0; line < layout.getLineCount(); line++) {
                assertEquals(where + " line " + line + " is ellipsized", 0, layout.getEllipsisCount(line));
                assertTrue(where + " line " + line + " is " + layout.getLineWidth(line) + " px in " + textWidth,
                        layout.getLineWidth(line) <= textWidth);
            }
            int start = 0;
            for (String word : title.split(" ")) {
                int end = start + word.length() - 1;
                // Oversized single words must wrap at the user's text size. Ordinary words
                // still stay whole; shrinking a compact toolbar below 17sp hides text instead.
                if (view.getPaint().measureText(word) <= textWidth) {
                    assertEquals(where + " broke the word " + word,
                            layout.getLineForOffset(start), layout.getLineForOffset(end));
                }
                start += word.length() + 1;
            }
            float minimum = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                    17, activity.getResources().getDisplayMetrics());
            assertTrue(where + " shrank below the readable minimum", view.getTextSize() >= minimum - 0.01f);
        }
    }

    private static void setDirection(View view, int direction) {
        view.setLayoutDirection(direction);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setDirection(group.getChildAt(i), direction);
        }
    }
}
