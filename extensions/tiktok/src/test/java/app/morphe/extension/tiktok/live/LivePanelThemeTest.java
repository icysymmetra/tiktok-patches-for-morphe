package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.widget.TextView;

import com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment;
import com.bytedance.android.live.design.view.sheet.LiveSheetFragment;
import com.bytedance.android.live.rank.impl.list.RankListDialog;
import com.bytedance.android.live.rank.impl.list.view.PopularityRankingEducationDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * #159: a LIVE panel's views inflate through its own (dark) LIVE overlay, applied with force,
 * only while Dark LIVE panels is on and its context is in dark mode, and only for themed dialogs
 * and sheet pages, the daily ranking and its guide aside. Everything else gets TikTok's inflater
 * back untouched.
 * Under the same conditions a LIVE room takes TikTok's dark app theme with force over its own light
 * one, so TikTok's generic sheets over a room open dark too, and the profile card's bio view takes
 * the link color, since its link is drawn in the view's own text color.
 */
@RunWith(RobolectricTestRunner.class)
public class LivePanelThemeTest {
    private static final int OVERLAY = android.R.style.Theme_Light;

    /** A room's own fragment: it has an overlay too, but it isn't a panel. */
    public static final class RoomFragment {
        public int getThemeOverlay(Context context) {
            return OVERLAY;
        }
    }

    /** A dialog that extends the themed base, as LiveShareDialog does through LiveDialogFragment. */
    public static final class ShareDialog extends LiveThemeAwareDialogFragment {
    }

    private Context dark;
    private Context light;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(app);
        SettingsStatus.liveControlsEnabled = true;
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;
        dark = new ContextThemeWrapper(app.createConfigurationContext(night), android.R.style.Theme_Black);
        Configuration day = new Configuration(night);
        day.uiMode = Configuration.UI_MODE_NIGHT_NO | Configuration.UI_MODE_TYPE_NORMAL;
        light = new ContextThemeWrapper(app.createConfigurationContext(day), android.R.style.Theme_Black);
    }

    @After public void tearDown() {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
    }

    private static int textColor(Context context) {
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true));
        return value.resourceId != 0 ? value.resourceId : value.data;
    }

    private static ShareDialog dialog() {
        ShareDialog dialog = new ShareDialog();
        dialog.overlay = OVERLAY;
        return dialog;
    }

    @Test public void aPanelInflatesThroughItsOverlayWithForceInDarkModeWithTheSwitch() {
        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("the switch starts off", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));

        Settings.DARK_LIVE_PANELS.save(true);
        LayoutInflater wrapped = LivePanelTheme.darkLivePanel(dialog(), tiktok);
        assertNotSame(tiktok, wrapped);
        // The overlay wins over the context's own theme, the way TikTok's light wrap does.
        assertEquals(textColor(new ContextThemeWrapper(dark, OVERLAY)), textColor(wrapped.getContext()));
        assertTrue(textColor(dark) != textColor(wrapped.getContext()));

        LiveSheetFragment sheet = new LiveSheetFragment();
        sheet.overlay = OVERLAY;
        assertNotSame("a sheet page is a panel", tiktok, LivePanelTheme.darkLivePanel(sheet, tiktok));
    }

    @Test public void lightModeRoomFragmentsPauseAndMissingPiecesKeepTikToksInflater() {
        Settings.DARK_LIVE_PANELS.save(true);
        LayoutInflater day = LayoutInflater.from(light);
        assertSame("light mode", day, LivePanelTheme.darkLivePanel(dialog(), day));

        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("a room's own fragment", tiktok, LivePanelTheme.darkLivePanel(new RoomFragment(), tiktok));
        assertSame("no panel", tiktok, LivePanelTheme.darkLivePanel(null, tiktok));
        assertSame(null, LivePanelTheme.darkLivePanel(dialog(), null));
        ShareDialog none = new ShareDialog();
        assertSame("no overlay", tiktok, LivePanelTheme.darkLivePanel(none, tiktok));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
    }

    /** A LIVE room with a light theme of its own, the way LivePlayActivity builds one. */
    private static Activity room() {
        Activity room = Robolectric.buildActivity(Activity.class).create().get();
        room.setTheme(android.R.style.Theme_Light);
        return room;
    }

    @Test public void aRoomTakesTikToksDarkAppThemeInDarkModeWithTheSwitch() {
        RuntimeEnvironment.setQualifiers("+night");
        Activity room = room();
        int stock = textColor(room);
        LivePanelTheme.darkLiveRoom(room, android.R.style.Theme_Black);
        assertEquals("the switch starts off", stock, textColor(room));

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.darkLiveRoom(room, android.R.style.Theme_Black);
        // Applied with force, so it wins over the room's own light theme.
        assertEquals(textColor(new ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Black)), textColor(room));
        assertTrue(stock != textColor(room));
    }

    @Test public void lightModeAPauseAndNoKnownThemeLeaveTheRoomAlone() {
        Settings.DARK_LIVE_PANELS.save(true);
        RuntimeEnvironment.setQualifiers("+notnight");
        Activity day = room();
        int stock = textColor(day);
        LivePanelTheme.darkLiveRoom(day, android.R.style.Theme_Black);
        assertEquals("light mode", stock, textColor(day));

        RuntimeEnvironment.setQualifiers("+night");
        Activity night = room();
        stock = textColor(night);
        LivePanelTheme.darkLiveRoom(night);
        assertEquals("without the patch the dark app theme is unknown", stock, textColor(night));
        LivePanelTheme.darkLiveRoom(night, 0);
        assertEquals("no theme", stock, textColor(night));
        LivePanelTheme.darkLiveRoom(null);
        LivePanelTheme.darkLiveRoom(null, android.R.style.Theme_Black);

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.darkLiveRoom(night, android.R.style.Theme_Black);
        assertEquals("paused", stock, textColor(night));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.darkLiveRoom(night, android.R.style.Theme_Black);
        assertEquals("without the patch", stock, textColor(night));
    }

    @Test public void theDailyRankingStaysStock() {
        Settings.DARK_LIVE_PANELS.save(true);
        RankListDialog ranking = new RankListDialog();
        ranking.overlay = OVERLAY;
        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame(tiktok, LivePanelTheme.darkLivePanel(ranking, tiktok));
        PopularityRankingEducationDialog guide = new PopularityRankingEducationDialog();
        guide.overlay = OVERLAY;
        assertSame("its first-time guide", tiktok, LivePanelTheme.darkLivePanel(guide, tiktok));
        // The same overlay on an ordinary themed dialog does wrap, so it's the ranking that's left out.
        assertNotSame(tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
    }

    @Test public void aBiosViewTakesTheLinkColorInDarkModeWithTheSwitch() {
        int link = 0xe6ffffff;
        TextView bio = new TextView(dark);
        bio.setTextColor(Color.BLACK);
        LivePanelTheme.bioLinkColor(link, bio);
        assertEquals("the switch starts off", Color.BLACK, bio.getCurrentTextColor());

        Settings.DARK_LIVE_PANELS.save(true);
        LivePanelTheme.bioLinkColor(link, bio);
        assertEquals(link, bio.getCurrentTextColor());

        TextView lightBio = new TextView(light);
        lightBio.setTextColor(Color.BLACK);
        LivePanelTheme.bioLinkColor(link, lightBio);
        assertEquals("light mode", Color.BLACK, lightBio.getCurrentTextColor());
        LivePanelTheme.bioLinkColor(link, "bio");
        LivePanelTheme.bioLinkColor(link, null);

        TextView paused = new TextView(dark);
        paused.setTextColor(Color.BLACK);
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        LivePanelTheme.bioLinkColor(link, paused);
        assertEquals("paused", Color.BLACK, paused.getCurrentTextColor());
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        LivePanelTheme.bioLinkColor(link, paused);
        assertEquals("without the patch", Color.BLACK, paused.getCurrentTextColor());
    }

    @Test public void panelsAreFoundByTikToksClassNames() {
        assertEquals(LiveThemeAwareDialogFragment.class.getName(), LivePanelTheme.THEMED_DIALOG);
        assertEquals(LiveSheetFragment.class.getName(), LivePanelTheme.SHEET);
        assertEquals(RankListDialog.class.getName(), LivePanelTheme.RANK_LIST);
        assertEquals(PopularityRankingEducationDialog.class.getName(), LivePanelTheme.RANK_GUIDE);
        assertTrue(LivePanelTheme.isPanel(ShareDialog.class));
        assertTrue(!LivePanelTheme.isPanel(RoomFragment.class));
        assertTrue(!LivePanelTheme.isPanel(RankListDialog.class));
        assertTrue(!LivePanelTheme.isPanel(PopularityRankingEducationDialog.class));
        assertTrue("a ranking subclass stays stock too", !LivePanelTheme.isPanel(HourlyRanking.class));
    }

    /** A ranking built on the daily one, the way TikTok splits its list dialogs into variants. */
    static class HourlyRanking extends RankListDialog { }
}
