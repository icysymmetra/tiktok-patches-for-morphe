package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * The LIVE Video quality list read white on white in dark mode on the S25. In dark mode, text
 * that can't be read against the color behind it turns white on a dark row and TikTok's dark text
 * on a light one; readable text, light mode and a build without the patch stay TikTok's.
 */
@RunWith(RobolectricTestRunner.class)
public class LiveQualityListColorsTest {
    private Context dark;
    private Context light;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(app);
        SettingsStatus.liveControlsEnabled = true;
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;
        dark = app.createConfigurationContext(night);
        Configuration day = new Configuration(night);
        day.uiMode = Configuration.UI_MODE_NIGHT_NO | Configuration.UI_MODE_TYPE_NORMAL;
        light = app.createConfigurationContext(day);
    }

    @After public void tearDown() {
        SettingsStatus.liveControlsEnabled = false;
    }

    /** The list as the S25 drew it: a dark sheet, a white card, white rows with white text, and one readable row. */
    private static FrameLayout list(Context context, TextView[] rows) {
        FrameLayout sheet = new FrameLayout(context);
        sheet.setBackground(new ColorDrawable(Color.BLACK));
        LinearLayout card = new LinearLayout(context);
        GradientDrawable cardFill = new GradientDrawable();
        cardFill.setColor(Color.WHITE);
        card.setBackground(cardFill);
        sheet.addView(card);
        for (int index = 0; index < rows.length; index++) {
            FrameLayout row = new FrameLayout(context);
            row.setBackground(new ColorDrawable(Color.WHITE));
            rows[index] = new TextView(context);
            rows[index].setTextColor(index == rows.length - 1 ? 0xFF161823 : Color.WHITE);
            row.addView(rows[index]);
            card.addView(row);
        }
        return sheet;
    }

    @Test public void whiteOnWhiteRowsGetDarkTextInDarkModeAndReadableOnesStay() {
        TextView[] rows = new TextView[3];
        FrameLayout sheet = list(dark, rows);
        LiveQualityListColors.readableQualityList(sheet);
        assertEquals(LiveQualityListColors.DARK_TEXT, rows[0].getCurrentTextColor());
        assertEquals(LiveQualityListColors.DARK_TEXT, rows[1].getCurrentTextColor());
        assertEquals(0xFF161823, rows[2].getCurrentTextColor());
        assertEquals("nothing left to fix", 0, LiveQualityListColors.fixContrast(sheet));
    }

    @Test public void darkOnDarkTextTurnsWhite() {
        FrameLayout row = new FrameLayout(dark);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(0xFF000000);
        row.setBackground(fill);
        TextView text = new TextView(dark);
        text.setTextColor(0xFF000000);
        row.addView(text);
        assertEquals(1, LiveQualityListColors.fixContrast(row));
        assertEquals(Color.WHITE, text.getCurrentTextColor());
    }

    @Test public void lightModeAndAMissingPatchLeaveTheListAlone() {
        TextView[] rows = new TextView[2];
        LiveQualityListColors.readableQualityList(list(light, rows));
        assertEquals("light mode", Color.WHITE, rows[0].getCurrentTextColor());

        SettingsStatus.liveControlsEnabled = false;
        LiveQualityListColors.readableQualityList(list(dark, rows));
        assertEquals("without the patch", Color.WHITE, rows[0].getCurrentTextColor());
        LiveQualityListColors.readableQualityList(null);
    }

    @Test public void contrastFollowsWcagAndSeeThroughBackgroundsAreSkipped() {
        assertEquals(1.0, LiveQualityListColors.contrast(Color.WHITE, Color.WHITE), 0.001);
        assertEquals(21.0, LiveQualityListColors.contrast(Color.BLACK, Color.WHITE), 0.001);
        // Half see-through white text on white blends to white.
        assertTrue(LiveQualityListColors.contrast(0x80000000, Color.WHITE) < 6);
        FrameLayout row = new FrameLayout(dark);
        row.setBackground(new ColorDrawable(0x40FFFFFF));
        TextView text = new TextView(dark);
        text.setTextColor(Color.WHITE);
        row.addView(text);
        assertEquals("nothing opaque behind it to judge by", 0, LiveQualityListColors.fixContrast(row));
    }
}
