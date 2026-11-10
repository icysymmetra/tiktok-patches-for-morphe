package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Page, section and recovery titles are accessibility headings from API 23, not only from 28.
 *
 * <p>Before API 28 a heading is the bit carried by a CollectionItemInfo, the same fallback the
 * dialogs use. Body text stays an ordinary view.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h800dp-mdpi")
public class SettingsHeadingSemanticsTest {
    @Test @Config(sdk = 23)
    public void titlesAreHeadingsOnApi23() {
        assertTitlesAreHeadings();
    }

    @Test @Config(sdk = 27)
    public void titlesAreHeadingsOnApi27() {
        assertTitlesAreHeadings();
    }

    @Test @Config(sdk = 28)
    public void titlesAreHeadingsOnApi28() {
        assertTitlesAreHeadings();
    }

    private static boolean isHeading(View view) {
        if (Build.VERSION.SDK_INT >= 28) return view.isAccessibilityHeading();
        AccessibilityNodeInfo info = view.createAccessibilityNodeInfo();
        AccessibilityNodeInfo.CollectionItemInfo item = info.getCollectionItemInfo();
        return item != null && item.isHeading();
    }

    private static void assertTitlesAreHeadings() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);

            boolean[] back = {false};
            LinearLayout header = SettingsHeaderPreference.createHeader(activity, "Comments", () -> back[0] = true);
            TextView page = header.findViewWithTag("hushfeed_page_title");
            assertEquals("Comments", page.getText().toString());
            assertTrue("the page title is not a heading", isHeading(page));
            View backButton = ((LinearLayout) header.findViewWithTag("hushfeed_toolbar")).getChildAt(0);
            assertFalse("the Back control became a heading", isHeading(backButton));
            assertTrue("the Back control lost its action", backButton.createAccessibilityNodeInfo().isClickable());
            assertTrue(backButton.performClick());
            assertTrue(back[0]);

            for (boolean home : new boolean[]{false, true}) {
                SectionHeadingPreference section = home
                        ? SectionHeadingPreference.compact(activity, "Playback")
                        : new SectionHeadingPreference(activity, "Playback");
                TextView sectionTitle = section.getView(null, null).findViewById(android.R.id.title);
                assertNotNull(sectionTitle);
                assertEquals("Playback", sectionTitle.getText().toString());
                assertTrue("the section title is not a heading (home=" + home + ")", isHeading(sectionTitle));
            }

            LinearLayout recovery = new LinearLayout(activity);
            TextView recoveryTitle = new TextView(activity);
            recoveryTitle.setId(android.R.id.title);
            recoveryTitle.setText("Couldn't open this page");
            recovery.addView(recoveryTitle);
            SettingsUi.styleErrorHeading(recovery);
            assertEquals("Couldn't open this page", recoveryTitle.getText().toString());
            assertTrue("the recovery title is not a heading", isHeading(recoveryTitle));

            TextView body = SettingsUi.text(activity, "Body text", SettingsUi.TEXT_BODY, SettingsUi.textSecondary(), 0);
            assertFalse("body text became a heading", isHeading(body));
            assertEquals("Body text", body.createAccessibilityNodeInfo().getText().toString());
        }
    }
}
