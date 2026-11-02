package app.morphe.extension.tiktok.favorites;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Issue #142: the collections popup from holding the bookmark stayed white in dark mode. The
 * panel is built here the way TikTok draws it (white surface, grey icon tile, faint dark divider,
 * dark title and grey subtitle) and handed to the hook as the show would hand it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class QuickFavoritesDarkTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY_TILE = 0xFFF1F1F2;
    private static final int DIVIDER = 0x1F161823;
    private static final int TITLE = 0xFF161823;
    private static final int SUBTITLE = 0xFF8A8B91;

    private LinearLayout panel;
    private View tile;
    private View divider;
    private TextView title;
    private TextView subtitle;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DARK_QUICK_FAVORITES.resetToDefault();
        HookStatus.clear();
    }

    @After public void tearDown() {
        Settings.DARK_QUICK_FAVORITES.resetToDefault();
        HookStatus.clear();
    }

    private Dialog popup() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(new ColorDrawable(WHITE));
        tile = new View(activity);
        GradientDrawable rounded = new GradientDrawable();
        rounded.setColor(GREY_TILE);
        tile.setBackground(rounded);
        divider = new View(activity);
        divider.setBackground(new ColorDrawable(DIVIDER));
        title = new TextView(activity);
        title.setTextColor(TITLE);
        subtitle = new TextView(activity);
        subtitle.setTextColor(SUBTITLE);
        panel.addView(tile);
        panel.addView(title);
        panel.addView(subtitle);
        panel.addView(divider);
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(panel);
        return dialog;
    }

    private static int fill(View view) {
        if (view.getBackground() instanceof ColorDrawable) {
            return ((ColorDrawable) view.getBackground()).getColor();
        }
        return ((GradientDrawable) view.getBackground()).getColor().getDefaultColor();
    }

    @Config(qualifiers = "night")
    @Test public void inDarkModeTheLightPartsGoDarkAndTheReportSaysSo() {
        QuickFavoritesDark.paint(popup());

        assertEquals(QuickFavoritesDark.SURFACE, fill(panel));
        assertEquals(QuickFavoritesDark.TILE, fill(tile));
        assertEquals(Color.argb(Color.alpha(DIVIDER), 0xFF, 0xFF, 0xFF), fill(divider));
        assertEquals(QuickFavoritesDark.TEXT, title.getCurrentTextColor());
        assertEquals(QuickFavoritesDark.TEXT_SECONDARY, subtitle.getCurrentTextColor());
        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("quick favorites: 1 found"));
    }

    @Config(qualifiers = "night")
    @Test public void aSecondPassOverAPaintedPanelChangesNothing() {
        Dialog dialog = popup();
        QuickFavoritesDark.paint(dialog);
        QuickFavoritesDark.paint(dialog);

        assertEquals(QuickFavoritesDark.SURFACE, fill(panel));
        assertEquals(QuickFavoritesDark.TILE, fill(tile));
        assertEquals(Color.argb(Color.alpha(DIVIDER), 0xFF, 0xFF, 0xFF), fill(divider));
        assertEquals(QuickFavoritesDark.TEXT, title.getCurrentTextColor());
        assertEquals(QuickFavoritesDark.TEXT_SECONDARY, subtitle.getCurrentTextColor());
        for (int fill : new int[] {QuickFavoritesDark.SURFACE, QuickFavoritesDark.TILE, fill(divider)}) {
            assertEquals(0, QuickFavoritesDark.darkFill(fill));
        }
        for (int text : new int[] {QuickFavoritesDark.TEXT, QuickFavoritesDark.TEXT_SECONDARY}) {
            assertEquals(0, QuickFavoritesDark.darkText(text));
        }
    }

    @Test public void lightModeIsNeverTouched() {
        QuickFavoritesDark.paint(popup());

        assertEquals(WHITE, fill(panel));
        assertEquals(GREY_TILE, fill(tile));
        assertEquals(DIVIDER, fill(divider));
        assertEquals(TITLE, title.getCurrentTextColor());
        assertEquals(SUBTITLE, subtitle.getCurrentTextColor());
    }

    @Config(qualifiers = "night")
    @Test public void theSwitchOffLeavesTheWhitePopup() {
        Settings.DARK_QUICK_FAVORITES.save(false);

        QuickFavoritesDark.paint(popup());

        assertEquals(WHITE, fill(panel));
        assertEquals(TITLE, title.getCurrentTextColor());
        String hooks = HookStatus.report().toString();
        assertTrue(hooks, hooks.contains("quick favorites: 1 found"));
    }

    @Test public void colourPhotosAndAnythingNotADialogAreLeftAlone() {
        assertEquals(0, QuickFavoritesDark.darkFill(0xFFE8443A));
        assertEquals(0, QuickFavoritesDark.darkFill(0xFF000000));
        assertEquals(0, QuickFavoritesDark.darkText(0xFFFFFFFF));
        assertEquals(0, QuickFavoritesDark.darkText(0x20000000));
        QuickFavoritesDark.paint(new Object());
        QuickFavoritesDark.paint(null);
    }
}
