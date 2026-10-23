package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
public class LiveControlsTest {
    private Locale savedLocale;

    @Before public void setUp() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        Utils.setContext(RuntimeEnvironment.getApplication());
        SettingsStatus.liveControlsEnabled = true;
    }

    @After public void tearDown() {
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        Settings.STOP_LIVE_AUTO_ENTER.save(Settings.STOP_LIVE_AUTO_ENTER.defaultValue);
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(Settings.SHOW_EXACT_LIVE_VIEWERS.defaultValue);
        Settings.OFFER_LIVE_QUALITY.save(Settings.OFFER_LIVE_QUALITY.defaultValue);
        Settings.REMEMBER_LIVE_QUALITY.save(Settings.REMEMBER_LIVE_QUALITY.defaultValue);
        Settings.REMEMBERED_LIVE_QUALITY.save(Settings.REMEMBERED_LIVE_QUALITY.defaultValue);
        Settings.OFFER_LIVE_CLEAR_SCREEN.save(Settings.OFFER_LIVE_CLEAR_SCREEN.defaultValue);
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
        Settings.RECORD_LIVE.save(Settings.RECORD_LIVE.defaultValue);
        coldStart();
        Locale.setDefault(savedLocale);
    }

    /** A new TikTok process: nothing picked this run. */
    private static void coldStart() {
        ReflectionHelpers.setStaticField(LiveControls.class, "pickThisRun", null);
    }

    /** #37: the Record button is off until switched on, needs the patch, and Pause turns it off. */
    @Test public void recordButtonIsOffByDefaultAndFollowsItsSwitchAndPause() {
        assertFalse(Settings.RECORD_LIVE.defaultValue);
        assertFalse(LiveRecordChip.enabled());
        Settings.RECORD_LIVE.save(true);
        assertTrue(LiveRecordChip.enabled());
        setPaused(true);
        assertFalse(LiveRecordChip.enabled());
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveRecordChip.enabled());
    }

    /** #57: off, TikTok's server setting decides which rooms offer clear screen. */
    @Test public void clearScreenIsOffByDefaultAndFollowsItsSwitch() {
        assertFalse(Settings.OFFER_LIVE_CLEAR_SCREEN.defaultValue);
        assertFalse(LiveControls.offerClearScreen());
        Settings.OFFER_LIVE_CLEAR_SCREEN.save(true);
        assertTrue(LiveControls.offerClearScreen());
        assertFalse("clear screen turned the quality picker on", LiveControls.offerQualityEverywhere());
        Settings.OFFER_LIVE_CLEAR_SCREEN.save(false);
        assertFalse("read at the call, so the next menu follows", LiveControls.offerClearScreen());
    }

    /**
     * #159: TikTok keeps LIVE panels light in rooms even in dark mode. With the switch on a panel
     * whose context is in dark mode takes the dark LIVE theme; light mode, the switch off, Pause and
     * a build without the patch leave TikTok's answer.
     */
    @Test public void livePanelsGoDarkOnlyInDarkModeWithTheirSwitch() {
        android.content.Context app = RuntimeEnvironment.getApplication();
        android.content.res.Configuration night = new android.content.res.Configuration(app.getResources().getConfiguration());
        night.uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES | android.content.res.Configuration.UI_MODE_TYPE_NORMAL;
        android.content.Context dark = app.createConfigurationContext(night);
        android.content.res.Configuration day = new android.content.res.Configuration(night);
        day.uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO | android.content.res.Configuration.UI_MODE_TYPE_NORMAL;
        android.content.Context light = app.createConfigurationContext(day);

        assertFalse(Settings.DARK_LIVE_PANELS.defaultValue);
        assertFalse(LiveControls.darkLivePanels(dark));
        Settings.DARK_LIVE_PANELS.save(true);
        assertTrue(LiveControls.darkLivePanels(dark));
        assertFalse("light mode went dark", LiveControls.darkLivePanels(light));
        assertFalse(LiveControls.darkLivePanels(null));
        setPaused(true);
        assertFalse(LiveControls.darkLivePanels(dark));
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveControls.darkLivePanels(dark));
    }

    @Test public void pausedOrWithoutThePatchClearScreenIsTikToks() {
        Settings.OFFER_LIVE_CLEAR_SCREEN.save(true);
        setPaused(true);
        assertFalse(LiveControls.offerClearScreen());
        setPaused(false);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveControls.offerClearScreen());
    }

    /** #57: off by default, a cold start leaves the first rooms at TikTok's choice. */
    @Test public void keepingTheQualityIsOffByDefault() {
        assertFalse(Settings.REMEMBER_LIVE_QUALITY.defaultValue);
        Settings.OFFER_LIVE_QUALITY.save(true);
        LiveControls.noteLiveQualityPick("hd");
        assertEquals("a pick was saved with the switch off", "", Settings.REMEMBERED_LIVE_QUALITY.get());
        Settings.REMEMBERED_LIVE_QUALITY.save("hd");
        coldStart();
        assertEquals(null, LiveControls.liveQualityPick(null));
    }

    /** On, a pick is saved and a cold start's empty pick reads it back; TikTok's own pick wins. */
    @Test public void aKeptPickStartsTheRoomsAfterARestart() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        Settings.REMEMBER_LIVE_QUALITY.save(true);
        LiveControls.noteLiveQualityPick("origin");
        assertEquals("origin", Settings.REMEMBERED_LIVE_QUALITY.get());
        coldStart();
        assertEquals("after a cold start TikTok's pick is null", "origin", LiveControls.liveQualityPick(null));
        assertEquals("a pick from this session was replaced", "sd", LiveControls.liveQualityPick("sd"));
        LiveControls.noteLiveQualityPick("sd");
        assertEquals("the next pick replaces the saved one", "sd", LiveControls.liveQualityPick(null));
        LiveControls.noteLiveQualityPick(null);
        LiveControls.noteLiveQualityPick("");
        assertEquals("an empty pick overwrote the saved one", "sd", Settings.REMEMBERED_LIVE_QUALITY.get());
    }

    /**
     * #57: a game room's start never reads the pick, so the patch asks at its end. It gets the
     * saved pick after a restart and TikTok's own pick within a run, only when the stream lists it
     * and the room isn't on it already; off, TikTok's choice stays.
     */
    @Test public void aGameRoomStartsAtThePickWhenItsStreamHasIt() {
        String stream = "{\"data\":{\"ld\":{},\"hd\":{},\"origin\":{}}}";
        Settings.REMEMBERED_LIVE_QUALITY.save("ld");
        Settings.REMEMBER_LIVE_QUALITY.save(true);
        assertEquals("Offer LIVE quality is off", null, LiveControls.gameRoomQuality("hd", stream, "origin"));

        Settings.OFFER_LIVE_QUALITY.save(true);
        assertEquals("after a restart", "ld", LiveControls.gameRoomQuality(null, stream, "origin"));
        assertEquals("a pick from this run", "hd", LiveControls.gameRoomQuality("hd", stream, "origin"));
        assertEquals("already there", null, LiveControls.gameRoomQuality("origin", stream, "origin"));
        assertEquals("the stream lacks it", null, LiveControls.gameRoomQuality("sd", stream, "origin"));
        assertEquals("no stream data", null, LiveControls.gameRoomQuality("hd", null, "origin"));
        assertEquals("broken stream data", null, LiveControls.gameRoomQuality("hd", "{\"data\":", "origin"));

        Settings.REMEMBER_LIVE_QUALITY.save(false);
        assertEquals("nothing kept and nothing picked", null, LiveControls.gameRoomQuality(null, stream, "origin"));
        setPaused(true);
        assertEquals("paused", null, LiveControls.gameRoomQuality("hd", stream, "origin"));
    }

    /**
     * #57: TikTok's player start leaves LIVE Studio rooms out of the pick by comparing the room's
     * mode with LIVE_STUDIO. With Offer LIVE quality on the hook hands it null, which no room
     * matches; off or paused it hands back the mode and TikTok's check is unchanged.
     */
    @Test public void liveStudioRoomsTakeThePickOnlyWhileOfferLiveQualityIsOn() {
        Object liveStudio = new Object();
        assertSame("Offer LIVE quality is off", liveStudio, LiveControls.liveStudioPickSkip(liveStudio));
        Settings.OFFER_LIVE_QUALITY.save(true);
        assertNull("Offer LIVE quality is on", LiveControls.liveStudioPickSkip(liveStudio));
        setPaused(true);
        assertSame("paused", liveStudio, LiveControls.liveStudioPickSkip(liveStudio));
    }

    /**
     * #57: the older picker never writes TikTok's pick, so with Offer LIVE quality on the last pick
     * from either picker carries to the next room this run, ahead of an older one TikTok still
     * holds. Off or paused, TikTok's own pick stays, and a pick made while off isn't carried later.
     */
    @Test public void thisRunsLastPickCarriesToTheNextRoom() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        LiveControls.noteLiveQualityPick("ld");
        assertEquals("a pick from the older picker", "ld", LiveControls.liveQualityPick(null));
        assertEquals("ahead of TikTok's older pick", "ld", LiveControls.liveQualityPick("hd"));
        assertEquals("nothing saved without the keep switch", "", Settings.REMEMBERED_LIVE_QUALITY.get());

        setPaused(true);
        assertEquals("paused", "hd", LiveControls.liveQualityPick("hd"));
        setPaused(false);
        Settings.OFFER_LIVE_QUALITY.save(false);
        assertEquals("Offer LIVE quality is off", null, LiveControls.liveQualityPick(null));
        LiveControls.noteLiveQualityPick("origin");
        Settings.OFFER_LIVE_QUALITY.save(true);
        assertEquals("a pick made while it was off", "ld", LiveControls.liveQualityPick(null));
    }

    /** Nothing saved yet, the start keeps TikTok's null and so the stream's default. */
    @Test public void withNothingSavedTheStreamsDefaultStays() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        Settings.REMEMBER_LIVE_QUALITY.save(true);
        assertEquals(null, LiveControls.liveQualityPick(null));
    }

    /** It rides on Offer LIVE quality, the only thing that makes TikTok carry a pick at all. */
    @Test public void keepingTheQualityNeedsOfferQualityAndThePatch() {
        Settings.REMEMBER_LIVE_QUALITY.save(true);
        Settings.REMEMBERED_LIVE_QUALITY.save("hd");
        assertFalse(LiveControls.rememberQuality());
        assertEquals(null, LiveControls.liveQualityPick(null));
        LiveControls.noteLiveQualityPick("origin");
        assertEquals("hd", Settings.REMEMBERED_LIVE_QUALITY.get());

        Settings.OFFER_LIVE_QUALITY.save(true);
        assertTrue(LiveControls.rememberQuality());
        SettingsStatus.liveControlsEnabled = false;
        assertFalse("a saved switch did something without its patch", LiveControls.rememberQuality());
        assertEquals(null, LiveControls.liveQualityPick(null));
    }

    /** Paused, the rooms start at TikTok's choice and a pick isn't saved over the kept one. */
    @Test public void pausedTheKeptQualityIsLeftAlone() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        Settings.REMEMBER_LIVE_QUALITY.save(true);
        Settings.REMEMBERED_LIVE_QUALITY.save("hd");
        setPaused(true);
        assertEquals(null, LiveControls.liveQualityPick(null));
        LiveControls.noteLiveQualityPick("ld");
        setPaused(false);
        assertEquals("hd", Settings.REMEMBERED_LIVE_QUALITY.get());
        assertEquals("hd", LiveControls.liveQualityPick(null));
    }

    /** Off, TikTok's own setting decides which rooms offer a quality. */
    @Test public void qualityOffByDefaultLeavesTikToksSetting() {
        assertFalse(Settings.OFFER_LIVE_QUALITY.defaultValue);
        assertFalse(LiveControls.offerQualityEverywhere());
    }

    @Test public void qualityOnOffersItEverywhere() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        assertTrue(LiveControls.offerQualityEverywhere());
        Settings.OFFER_LIVE_QUALITY.save(false);
        assertFalse("read at the call, so the next room follows", LiveControls.offerQualityEverywhere());
    }

    @Test public void pausedQualityLeavesTikToksSetting() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        setPaused(true);
        assertFalse(LiveControls.offerQualityEverywhere());
    }

    @Test public void qualityDoesNothingWithoutThePatch() {
        Settings.OFFER_LIVE_QUALITY.save(true);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveControls.offerQualityEverywhere());
    }

    @Test public void qualityIsIndependentOfTheOtherSwitches() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        assertFalse(LiveControls.offerQualityEverywhere());
        Settings.OFFER_LIVE_QUALITY.save(true);
        Settings.STOP_LIVE_AUTO_ENTER.save(false);
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(false);
        assertTrue(LiveControls.offerQualityEverywhere());
        assertFalse(LiveControls.skipAutoEnter());
    }

    @Test public void offByDefaultPreviewsCountDownAsBefore() {
        assertFalse(Settings.STOP_LIVE_AUTO_ENTER.defaultValue);
        assertFalse(LiveControls.skipAutoEnter());
    }

    @Test public void onTheCountdownNeverStarts() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        assertTrue(LiveControls.skipAutoEnter());
    }

    @Test public void pausedPreviewsCountDownAsBefore() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        setPaused(true);
        assertFalse(LiveControls.skipAutoEnter());
    }

    /** A switch saved on by an earlier build does nothing after a repatch without the patch. */
    @Test public void aSavedSwitchDoesNothingWithoutItsPatch() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        SettingsStatus.liveControlsEnabled = false;
        assertFalse(LiveControls.skipAutoEnter());
    }

    /** Read at the call, so turning it off mid-session lets the next preview count down again. */
    @Test public void turningItOffTakesEffectAtTheNextPreview() {
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        assertTrue(LiveControls.skipAutoEnter());
        Settings.STOP_LIVE_AUTO_ENTER.save(false);
        assertFalse(LiveControls.skipAutoEnter());
    }

    @Test public void exactViewersOffByDefaultKeepsTikToksText() {
        assertFalse(Settings.SHOW_EXACT_LIVE_VIEWERS.defaultValue);
        assertEquals("1.2K+", shown(1234, "1.2K+"));
    }

    @Test public void exactViewersOnShowsTheGroupedCount() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        assertEquals("1,234", shown(1234, "1.2K+"));
        assertEquals("1,234,567", shown(1234567, "1.2M+"));
        assertEquals("987", shown(987, "987"));
        assertEquals("0", shown(0, "0"));
    }

    @Test public void exactViewersGroupsTheWayTheLocaleDoes() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        Locale.setDefault(Locale.GERMANY);
        assertEquals("1.234", shown(1234, "1.2K+"));
    }

    @Test public void pausedExactViewersKeepTikToksText() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        setPaused(true);
        assertEquals("1.2K+", shown(1234, "1.2K+"));
    }

    @Test public void exactViewersDoNothingWithoutThePatch() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        SettingsStatus.liveControlsEnabled = false;
        assertEquals("1.2K+", shown(1234, "1.2K+"));
    }

    @Test public void exactViewersIsIndependentOfTheAutoEnterSwitch() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        assertFalse(LiveControls.skipAutoEnter());
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(false);
        Settings.STOP_LIVE_AUTO_ENTER.save(true);
        assertEquals("1.2K+", shown(1234, "1.2K+"));
    }

    /** A text with no count noted before it (or a negative one) is TikTok's, untouched. */
    @Test public void aTextWithoutACountIsLeftAlone() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        assertEquals("1.2K+", LiveControls.viewerCountText("1.2K+"));
        assertEquals("--", shown(-1, "--"));
        LiveControls.noteViewerCount(1234);
        assertEquals(null, LiveControls.viewerCountText(null));
    }

    /** A count is used once: a later text can't pick up an older one. */
    @Test public void aNotedCountIsUsedOnce() {
        Settings.SHOW_EXACT_LIVE_VIEWERS.save(true);
        LiveControls.noteViewerCount(5000);
        assertEquals("5,000", LiveControls.viewerCountText("5K+"));
        assertEquals("5K+", LiveControls.viewerCountText("5K+"));
    }

    private static String shown(long count, String tiktokText) {
        LiveControls.noteViewerCount(count);
        return LiveControls.viewerCountText(tiktokText);
    }

    /**
     * #94: the For You LIVE preview's Service+ row showed under the next LIVE's name. A row still
     * showing as it's bound to a LIVE without a Service+ item is hidden first; a row TikTok has
     * hidden, or a LIVE that has its own Service+ item, is left to TikTok, and so is everything
     * while Hushfeed is paused.
     */
    @Test public void aServicePlusRowLeftFromTheLastLiveIsHidden() {
        Row showing = new Row(true);
        assertTrue("a LIVE with no Service+ preview kept the last row", LiveControls.hideStaleServicePlus(showing, null));
        assertTrue("a LIVE with no Service+ items kept the last row",
                LiveControls.hideStaleServicePlus(showing, new Preview(null)));
        assertTrue(LiveControls.hideStaleServicePlus(showing, new Preview(new ArrayList<>())));
        assertFalse("a LIVE with a Service+ item of its own lost it",
                LiveControls.hideStaleServicePlus(showing, new Preview(Collections.singletonList(new Object()))));
        assertFalse("a hidden row was hidden again", LiveControls.hideStaleServicePlus(new Row(false), null));
        assertFalse(LiveControls.hideStaleServicePlus(null, null));
        assertFalse("a row without isShowing was hidden", LiveControls.hideStaleServicePlus(new Object(), null));

        setPaused(true);
        assertFalse("paused, the row was hidden", LiveControls.hideStaleServicePlus(showing, null));
    }

    /** Stands in for TikTok's Widget: only {@code isShowing} is read. */
    @SuppressWarnings("unused")
    private static final class Row {
        private final boolean showing;

        Row(boolean showing) {
            this.showing = showing;
        }

        public boolean isShowing() {
            return showing;
        }
    }

    /** Stands in for SMBLivePreview: only {@code previewItem} is read. */
    @SuppressWarnings("unused")
    private static final class Preview {
        private final List<Object> previewItem;

        Preview(List<Object> items) {
            this.previewItem = items;
        }
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }
}
