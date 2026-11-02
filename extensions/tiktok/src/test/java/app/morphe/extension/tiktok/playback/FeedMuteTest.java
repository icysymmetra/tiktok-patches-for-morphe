package app.morphe.extension.tiktok.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Mute feed videos: a feed video's engine is silenced as it plays and given its sound back on
 * unmute; a video no feed controller asked for (DMs), one on another screen, a story or a LIVE
 * keeps its sound; an engine reused for such a video gets its sound back; and the feed's audio
 * focus is turned down, and given up, only while muted with a feed video in front.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FeedMuteTest {
    private final Map<Object, String> ids = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private ActivityController<Activity> feed;
    private boolean wasEnabled;
    private CountDownLatch muteEntered;
    private CountDownLatch releaseMute;
    private final AtomicBoolean engineMuted = new AtomicBoolean();

    /** Stands in for a PlayerController; FeedMute reads its host from this field. */
    static final class Controller {
        final Activity activity;

        Controller(Activity activity) {
            this.activity = activity;
        }
    }

    /** A screen that isn't the feed, like the detail page a DM's post opens in. */
    public static final class OtherScreen extends Activity {
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        FeedMute.resetForTests();
        wasEnabled = SettingsStatus.feedMuteEnabled;
        SettingsStatus.feedMuteEnabled = true;
        Settings.FEED_MUTED.save(false);
        FeedMute.nativeForTests = new FeedMute.Native() {
            @Override public String sourceId(Object engine) { return ids.get(engine); }
            @Override public boolean isMute(Object engine) { return false; }
            @Override public void setMute(Object engine, boolean mute) {
                if (mute && muteEntered != null) {
                    muteEntered.countDown();
                    try {
                        if (!releaseMute.await(5, TimeUnit.SECONDS)) throw new AssertionError("mute stalled");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                engineMuted.set(mute);
                calls.add(name(engine) + (mute ? " mute" : " sound"));
            }
            @Override public void abandonSession(Object helper) { calls.add("abandon session " + helper); }
            @Override public void requestSession(Object helper) { calls.add("request session " + helper); }
            @Override public void abandonPage(Object helper, Context context) { calls.add("abandon page " + helper); }
            @Override public void requestPage(Object helper, Context context) { calls.add("request page " + helper); }
        };
        // Installed from the feed's onCreate, as the patch does, so its start is seen.
        feed = Robolectric.buildActivity(Activity.class).create();
        FeedMute.install(feed.get());
        feed.start().postCreate(null).resume().visible();
    }

    @After public void tearDown() {
        FeedMute.resetForTests();
        Settings.FEED_MUTED.resetToDefault();
        Settings.FEED_MUTE_BUTTON.resetToDefault();
        SettingsStatus.feedMuteEnabled = wasEnabled;
        feed.pause().stop().destroy();
    }

    /**
     * Pausing the feed for the comments, or for a return, takes the sound from TikTok by asking
     * for focus. A muted feed has none to take, so the ask would only stop the music another
     * app is playing under it.
     */
    @Test public void pausingAMutedFeedLeavesOtherAppsSoundAlone() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onControllerPlay(new Controller(feed.get()), video("111"));
        FeedMute.onEnginePlay(engine("A", "111"));
        assertTrue(FeedMute.isHoldingFocus());
        var audio = org.robolectric.Shadows.shadowOf((android.media.AudioManager)
                RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE));
        try {
            PausePlayback.quietenForTests();
            assertNull("a muted feed asked for the focus", audio.getLastAudioFocusRequest());
            assertFalse(PausePlayback.quietenedForTests());

            FeedMute.setMuted(false);
            PausePlayback.quietenForTests();
            assertNotNull("with sound the feed must still be quietened",
                    audio.getLastAudioFocusRequest());
        } finally {
            PausePlayback.resetForTests();
        }
    }

    @Test public void aFeedVideoPlaysSilentWhileMutedAndGetsItsSoundBack() {
        Settings.FEED_MUTED.save(true);
        Object engine = engine("A", "101");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("101"));
        FeedMute.onEnginePlay(engine);
        assertEquals(List.of("A mute"), calls);

        // The next video comes on a fresh engine, which starts with sound.
        Object next = engine("B", "102");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("102"));
        FeedMute.onEnginePlay(next);
        assertEquals(List.of("A mute", "B mute"), calls);

        calls.clear();
        FeedMute.setMuted(false);
        assertEquals("unmute gives both engines TikTok's own state back", 2, calls.size());
        assertTrue(calls.contains("A sound"));
        assertTrue(calls.contains("B sound"));
        calls.clear();
        FeedMute.onEnginePlay(engine);
        assertEquals("an unmuted feed engine is left alone", List.of(), calls);
    }

    /** A report of a silent feed says whether the mute was on and what it did (#58). */
    @Test public void theReportSaysWhetherTheFeedIsMutedAndWhatItSilenced() {
        assertEquals("FEED MUTE", FeedMute.Report.INSTANCE.title());
        // The button is opt-in: a fresh install draws nothing on the video.
        assertEquals(List.of("Mute feed videos: off", "Mute button on the feed: turned off",
                "Feed players muted since TikTok started: 0", "Audio focus requests turned down: 0"),
                FeedMute.Report.INSTANCE.lines());

        Settings.FEED_MUTED.save(true);
        Settings.FEED_MUTE_BUTTON.save(true);
        FeedMute.onControllerPlay(new Controller(feed.get()), video("501"));
        Object engine = engine("A", "501");
        FeedMute.onEnginePlay(engine);
        FeedMute.onEnginePlay(engine);
        assertTrue(FeedMute.holdSessionFocus("helper"));

        assertEquals(List.of("Mute feed videos: on", "Mute button on the feed: shown",
                "Feed players muted since TikTok started: 1", "Audio focus requests turned down: 1"),
                FeedMute.Report.INSTANCE.lines());
    }

    /**
     * Under Pause every switch reads off. The report keeps the reader's choices and says the
     * pause is why the feed has its sound, rather than calling the button turned off.
     */
    @Test public void aPausedReportKeepsTheChoicesAndSaysItIsPaused() {
        Settings.FEED_MUTED.save(true);
        Settings.FEED_MUTE_BUTTON.save(true);
        PausedProcess.set(true);
        try {
            assertEquals(List.of("Mute feed videos: on",
                    "Metra is paused, so the feed plays with its sound",
                    "Mute button on the feed: shown",
                    "Feed players muted since TikTok started: 0", "Audio focus requests turned down: 0"),
                    FeedMute.Report.INSTANCE.lines());
        } finally {
            PausedProcess.set(false);
        }
    }

    /**
     * Mute switched on from the settings page, with the feed behind it, gives the focus back when
     * the feed returns (S22, 2026-09-28: another app's music stayed paused on the muted feed).
     */
    @Test public void muteFromSettingsGivesTheFocusBackOnReturn() {
        FeedMute.onControllerPlay(new Controller(feed.get()), video("401"));
        FeedMute.onEnginePlay(engine("A", "401"));
        FeedMute.holdSessionFocus("session");
        FeedMute.holdPageFocus("page");
        calls.clear();

        feed.pause();
        FeedMute.setMuted(true);
        assertFalse("focus was touched with the feed away", calls.contains("abandon session session"));

        feed.resume();
        assertTrue(calls.toString(), calls.contains("abandon session session"));
        assertTrue(calls.toString(), calls.contains("abandon page page"));
    }

    /**
     * Sound turned back on while the video is paused asks for the focus when the feed next plays,
     * the resumed video or the next one (S22, 2026-09-28: it played over another app's music).
     */
    @Test public void soundOnWhilePausedAsksForTheFocusWhenTheFeedPlays() {
        Settings.FEED_MUTED.save(true);
        Object engine = engine("A", "501");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("501"));
        FeedMute.onEnginePlay(engine);
        FeedMute.holdSessionFocus("session");
        FeedMute.holdPageFocus("page");
        calls.clear();

        // Nothing is playing (the test has no player to ask), so nothing is asked for yet.
        FeedMute.setMuted(false);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(calls.toString(), calls.contains("request session session"));

        FeedMute.onEnginePlay(engine);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(calls.toString(), calls.contains("request session session"));
        assertTrue(calls.toString(), calls.contains("request page page"));

        // Once: the next play asks for nothing more.
        calls.clear();
        FeedMute.onEnginePlay(engine);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(calls.toString(), calls.contains("request session session"));
    }

    /** The button stays off a story or a LIVE replay, which keep their sound whatever it says. */
    @Test public void theButtonAppliesOnlyToVideosTheMuteGoverns() {
        FeedMute.onControllerPlay(new Controller(feed.get()), video("601"));
        FeedMute.onControllerPlay(new Controller(feed.get()), aweme("602", 0, true, 0));
        assertTrue(FeedMute.appliesTo("601"));
        assertFalse("a story kept the mute button", FeedMute.appliesTo("602"));
        assertTrue("an unknown video lost the button", FeedMute.appliesTo("never-played"));
    }

    /**
     * A video opened from a profile, a hashtag or a sound plays in the detail pager, which draws
     * the mute button too. What the button says has to hold there.
     */
    @Test public void aDetailPagerVideoFollowsTheMuteButton() {
        Settings.FEED_MUTED.save(true);
        ActivityController<com.ss.android.ugc.aweme.detail.ui.DetailActivity> pager =
                Robolectric.buildActivity(com.ss.android.ugc.aweme.detail.ui.DetailActivity.class).setup();
        try {
            Object engine = engine("pager", "301");
            FeedMute.onControllerPlay(new Controller(pager.get()), video("301"));
            FeedMute.onEnginePlay(engine);
            assertEquals(List.of("pager mute"), calls);
            assertTrue(FeedMute.isHoldingFocus());

            calls.clear();
            FeedMute.setMuted(false);
            assertEquals(List.of("pager sound"), calls);
        } finally {
            pager.pause().stop().destroy();
        }
    }

    @Test public void videosTheFeedDidNotAskForKeepTheirSound() {
        Settings.FEED_MUTED.save(true);
        // A DM chat's video: no feed controller asked for it.
        FeedMute.onEnginePlay(engine("dm", "201"));
        // A post opened from a DM plays on another screen.
        ActivityController<OtherScreen> other = Robolectric.buildActivity(OtherScreen.class).setup();
        FeedMute.onControllerPlay(new Controller(other.get()), video("202"));
        FeedMute.onEnginePlay(engine("detail", "202"));
        // Stories and a LIVE on the feed's own screen. A story is told by its flag or, shared
        // into the feed, by its type alone.
        FeedMute.onControllerPlay(new Controller(feed.get()), aweme("203", 0, true, 0));
        FeedMute.onEnginePlay(engine("story", "203"));
        FeedMute.onControllerPlay(new Controller(feed.get()), aweme("205", 45, false, 0));
        FeedMute.onEnginePlay(engine("shared story", "205"));
        FeedMute.onControllerPlay(new Controller(feed.get()), live("204"));
        FeedMute.onEnginePlay(engine("live", "204"));
        assertEquals(List.of(), calls);
        other.pause().stop().destroy();
    }

    @Test public void anEngineReusedForAnotherVideoGetsItsSoundBack() {
        Settings.FEED_MUTED.save(true);
        Object engine = engine("A", "301");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("301"));
        FeedMute.onEnginePlay(engine);
        ids.put(engine, "302");
        FeedMute.onEnginePlay(engine);
        assertEquals(List.of("A mute", "A sound"), calls);
    }

    /**
     * TikTok prepares the next videos ahead, and an engine's play() runs while it prepares,
     * before the controller asks for that video; when the video comes on, the engine often
     * starts without play() again. Every test above had the ask first, and on both phones no
     * feed engine was ever muted (2026-09-29: a feed video on 47.1.3 and a photo post on 47.0.3
     * kept their sound with the mute on).
     */
    @Test public void anEnginePreparedBeforeTheFeedAskedForItIsStillMuted() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onEnginePlay(engine("A", "701"));
        assertEquals("nothing is known about the video yet", List.of(), calls);
        FeedMute.onControllerPlay(new Controller(feed.get()), video("701"));
        assertEquals(List.of("A mute"), calls);

        // A one-photo post with a sound plays its music on the same engines.
        FeedMute.onEnginePlay(engine("photo", "702"));
        FeedMute.onControllerPlay(new Controller(feed.get()), aweme("702", 150, false, 0));
        assertEquals(List.of("A mute", "photo mute"), calls);

        calls.clear();
        FeedMute.setMuted(false);
        assertTrue(calls.toString(), calls.contains("A sound"));
        assertTrue(calls.toString(), calls.contains("photo sound"));
    }

    @Test public void anEnginePreparedAheadForAStoryALiveOrAnotherScreenKeepsItsSound() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onEnginePlay(engine("story", "711"));
        FeedMute.onControllerPlay(new Controller(feed.get()), story("711"));
        FeedMute.onEnginePlay(engine("live", "712"));
        FeedMute.onControllerPlay(new Controller(feed.get()), live("712"));
        ActivityController<OtherScreen> other = Robolectric.buildActivity(OtherScreen.class).setup();
        FeedMute.onEnginePlay(engine("detail", "713"));
        FeedMute.onControllerPlay(new Controller(other.get()), video("713"));
        assertEquals(List.of(), calls);
        other.pause().stop().destroy();
    }

    /**
     * Most feed videos start by routes the PlayerController play doesn't hear (S25, 47.1.3,
     * 2026-09-29: three swipes in four left no note). The feed's current video, as the block
     * button tracks it, notes each one instead, in either order with the engine.
     */
    @Test public void aFeedVideoStartedWithoutTheControllersPlayIsMutedAsTheCurrentVideo() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onEnginePlay(engine("A", "801"));
        FeedMute.onCurrentVideo(video("801"));
        assertEquals(List.of("A mute"), calls);

        FeedMute.onCurrentVideo(aweme("802", 150, false, 0));
        FeedMute.onEnginePlay(engine("photo", "802"));
        assertEquals(List.of("A mute", "photo mute"), calls);

        // A story or a LIVE in the feed keeps its sound, and the focus goes to it.
        calls.clear();
        FeedMute.onEnginePlay(engine("story", "803"));
        FeedMute.onCurrentVideo(story("803"));
        FeedMute.onCurrentVideo(live("804"));
        FeedMute.onEnginePlay(engine("live", "804"));
        assertEquals(List.of(), calls);
        assertFalse("a LIVE in front had its focus turned down", FeedMute.holdPageFocus("P"));

        // With another screen over the feed, the current video is nothing to mute.
        feed.pause();
        FeedMute.onEnginePlay(engine("other", "805"));
        FeedMute.onCurrentVideo(video("805"));
        assertEquals(List.of(), calls);
    }

    /**
     * The feed binds an item ahead of the reader, as TikTok prepares its engine ahead. Noted only
     * as the current video, each swipe let about half a second of the next video's sound through
     * first (S25, 2026-09-29). A bind of the next item leaves the focus to what plays now.
     */
    @Test public void aBoundFeedItemIsMutedBeforeItBecomesCurrent() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onFeedBind(video("811"));
        FeedMute.onEnginePlay(engine("next", "811"));
        FeedMute.onEnginePlay(engine("after", "812"));
        FeedMute.onFeedBind(video("812"));
        assertEquals(List.of("next mute", "after mute"), calls);

        // A story is playing; binding the video after it doesn't take the story's focus away.
        FeedMute.onCurrentVideo(story("813"));
        FeedMute.onFeedBind(video("814"));
        assertFalse("a bind of the next item turned the playing story's focus down",
                FeedMute.holdPageFocus("P"));
    }

    @Test public void anEnginePreparedAheadWhileUnmutedIsMutedByTheButtonLater() {
        FeedMute.onEnginePlay(engine("A", "721"));
        FeedMute.onControllerPlay(new Controller(feed.get()), video("721"));
        assertEquals(List.of(), calls);
        FeedMute.setMuted(true);
        assertTrue(calls.toString(), calls.contains("A mute"));
    }

    @Test public void aVideoSeenInTheFeedKeepsSoundWhenOpenedFromAnotherScreen() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onControllerPlay(new Controller(feed.get()), video("305"));
        feed.pause();
        FeedMute.onEnginePlay(engine("dm", "305"));
        assertEquals("a remembered video id alone cannot mute another screen", List.of(), calls);
    }

    @Test public void unmuteWaitsForAnEarlierPlayerMuteToFinish() throws Exception {
        Settings.FEED_MUTED.save(true);
        Object engine = engine("A", "310");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("310"));
        muteEntered = new CountDownLatch(1);
        releaseMute = new CountDownLatch(1);
        CountDownLatch unmuteDone = new CountDownLatch(1);
        Thread player = new Thread(() -> FeedMute.onEnginePlay(engine));
        player.start();
        assertTrue(muteEntered.await(5, TimeUnit.SECONDS));
        Thread toggle = new Thread(() -> {
            Settings.FEED_MUTED.save(false);
            FeedMute.refresh();
            unmuteDone.countDown();
        });
        toggle.start();
        try {
            assertFalse("an older player mute must finish before unmute", unmuteDone.await(200, TimeUnit.MILLISECONDS));
        } finally {
            releaseMute.countDown();
        }
        player.join(5000);
        toggle.join(5000);
        assertFalse(player.isAlive());
        assertFalse(toggle.isAlive());
        assertFalse("the engine stays audible after the toggle", engineMuted.get());
    }

    @Test public void focusIsTurnedDownOnlyForAMutedFeedInFront() throws Exception {
        Object session = "S";
        FeedMute.onControllerPlay(new Controller(feed.get()), video("401"));
        assertFalse("unmuted, the request goes through", FeedMute.holdSessionFocus(session));
        Settings.FEED_MUTED.save(true);
        assertTrue(FeedMute.holdSessionFocus(session));
        assertTrue(FeedMute.holdPageFocus("P"));
        assertTrue("the daily hold stops waiting for a grant", FeedMute.isHoldingFocus());

        // TikTok asks from its own threads.
        AtomicBoolean offMain = new AtomicBoolean();
        Thread worker = new Thread(() -> offMain.set(FeedMute.holdSessionFocus(session)));
        worker.start();
        worker.join();
        assertTrue(offMain.get());

        FeedMute.onControllerPlay(new Controller(feed.get()), story("402"));
        assertFalse("a story on the feed's screen takes the focus", FeedMute.holdSessionFocus(session));
        FeedMute.onControllerPlay(new Controller(feed.get()), video("403"));
        feed.pause();
        assertFalse("another screen in front takes the focus", FeedMute.holdPageFocus("P"));
        feed.resume();
        assertTrue(FeedMute.holdPageFocus("P"));
    }

    /**
     * TikTok's launcher entry is an alias of the main activity, and a theme change recreates the
     * buried copy too. It resumes and pauses for a moment while the feed stays resumed in front,
     * and that pause took the feed out of front, so the next video played with sound.
     */
    @Test public void aBuriedMainActivityPausingLeavesTheFeedMuted() {
        Settings.FEED_MUTED.save(true);
        var buried = Robolectric.buildActivity(Activity.class).create().start().resume();
        buried.pause().stop();
        FeedMute.onControllerPlay(new Controller(feed.get()), video("701"));
        FeedMute.onEnginePlay(engine("B", "701"));
        assertEquals("the feed's next video", "B mute", lastFor("B"));
        assertTrue(FeedMute.holdPageFocus("P"));
        buried.destroy();

        feed.pause();
        assertFalse("with no feed window resumed, the focus goes through", FeedMute.holdPageFocus("P"));
        feed.resume();
    }

    @Test public void mutingMidVideoGivesUpTheFocusTheFeedHeld() {
        Object engine = engine("A", "501");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("501"));
        FeedMute.onEnginePlay(engine);
        assertFalse(FeedMute.holdSessionFocus("S"));
        assertFalse(FeedMute.holdPageFocus("P"));
        assertEquals("nothing touched before the mute", List.of(), calls);

        FeedMute.setMuted(true);
        assertEquals(List.of("A mute", "abandon session S", "abandon page P"), calls);
        assertTrue(Settings.FEED_MUTED.get());
    }

    @Test public void withoutThePatchNothingIsTouched() {
        SettingsStatus.feedMuteEnabled = false;
        Settings.FEED_MUTED.save(true);
        FeedMute.onControllerPlay(new Controller(feed.get()), video("601"));
        FeedMute.onEnginePlay(engine("A", "601"));
        assertFalse(FeedMute.holdSessionFocus("S"));
        assertEquals(List.of(), calls);
    }

    /** The last call made on the named engine. */
    private String lastFor(String name) {
        String last = null;
        for (String call : calls) if (call.startsWith(name + " ")) last = call;
        return last;
    }

    @Test public void aFeedVideoOpenedOnAnotherScreenStaysMutedInTheFeedAfterwards() {
        Settings.FEED_MUTED.save(true);
        Object a = engine("A", "901");
        FeedMute.onFeedBind(video("901"));
        FeedMute.onEnginePlay(a);
        assertEquals(List.of("A mute"), calls);
        feed.pause();
        // The same video, shared to a DM and opened there, plays under the same id.
        ActivityController<OtherScreen> other = Robolectric.buildActivity(OtherScreen.class).setup();
        FeedMute.onControllerPlay(new Controller(other.get()), video("901"));
        FeedMute.onEnginePlay(engine("dm", "901"));
        other.pause().stop().destroy();
        feed.resume();
        // The feed's engine resumes without a play(), so what it was left with is what plays.
        assertEquals("A mute", lastFor("A"));
    }

    @Test public void aFeedVideoOpenedOnAnotherScreenIsMutedWhenTheFeedPlaysItAgain() {
        Settings.FEED_MUTED.save(true);
        Object a = engine("A", "902");
        FeedMute.onFeedBind(video("902"));
        FeedMute.onEnginePlay(a);
        feed.pause();
        ActivityController<OtherScreen> other = Robolectric.buildActivity(OtherScreen.class).setup();
        FeedMute.onControllerPlay(new Controller(other.get()), video("902"));
        other.pause().stop().destroy();
        feed.resume();
        FeedMute.onEnginePlay(a);
        assertEquals("A mute", lastFor("A"));
    }

    @Test public void theCurrentFeedVideoStaysMutedAfterAGridBindsMoreItemsThanAreRemembered() {
        Settings.FEED_MUTED.save(true);
        Object a = engine("A", "950");
        FeedMute.onFeedBind(video("950"));
        FeedMute.onCurrentVideo(video("950"));
        FeedMute.onEnginePlay(a);
        assertEquals(List.of("A mute"), calls);
        // A profile grid or search results, in the feed's own activity, bind item after item.
        for (int i = 0; i < 70; i++) FeedMute.onFeedBind(video("grid" + i));
        FeedMute.onEnginePlay(a);
        assertEquals("A mute", lastFor("A"));
    }

    @Test public void anotherScreensEngineForAFeedVideoGetsItsSoundWhenThatScreenAsksForIt() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onFeedBind(video("960"));
        ActivityController<OtherScreen> other = Robolectric.buildActivity(OtherScreen.class).setup();
        // Prepared while the feed was in front, so its play() muted it as a feed engine.
        FeedMute.onEnginePlay(engine("detail", "960"));
        FeedMute.onControllerPlay(new Controller(other.get()), video("960"));
        String last = lastFor("detail");
        other.pause().stop().destroy();
        assertTrue("another screen's engine left muted: " + calls, last == null || last.equals("detail sound"));
    }

    @Test public void thePreparedNextFeedVideoStaysMutedAfterAGridBindsMoreItemsThanAreRemembered() {
        Settings.FEED_MUTED.save(true);
        FeedMute.onCurrentVideo(video("970"));
        FeedMute.onEnginePlay(engine("A", "970"));
        FeedMute.onFeedBind(video("971"));
        Object b = engine("B", "971");
        FeedMute.onEnginePlay(b);
        assertEquals("B mute", lastFor("B"));
        for (int i = 0; i < 70; i++) FeedMute.onFeedBind(video("grid" + i));
        // Back on the feed, the next video comes on with a play() before its current-video change.
        FeedMute.onEnginePlay(b);
        assertEquals("B mute", lastFor("B"));
    }

    /**
     * Background play: with TikTok in the background, its media notification resumes the feed
     * video with a play() on the same engine, and the mute has to hold through it.
     */
    @Test public void aMutedFeedVideoResumedInTheBackgroundStaysMuted() {
        Settings.FEED_MUTED.save(true);
        Object a = engine("A", "980");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("980"));
        FeedMute.onEnginePlay(a);
        assertEquals(List.of("A mute"), calls);

        feed.pause().stop();
        FeedMute.onEnginePlay(a);
        assertEquals("A mute", lastFor("A"));
        // An engine that wasn't the feed's is left alone, and one taken for another video gets
        // its sound back, as in front.
        FeedMute.onEnginePlay(engine("B", "981"));
        assertNull(lastFor("B"));
        ids.put(a, "982");
        FeedMute.onEnginePlay(a);
        assertEquals("A sound", lastFor("A"));
        feed.start().resume();
    }

    /** Another TikTok screen in front isn't the background: the feed's engine plays with sound there. */
    @Test public void aFeedEnginePlayedWhileAnotherScreenIsInFrontStillGetsItsSound() {
        Settings.FEED_MUTED.save(true);
        Object a = engine("A", "985");
        FeedMute.onControllerPlay(new Controller(feed.get()), video("985"));
        FeedMute.onEnginePlay(a);
        feed.pause();
        ActivityController<OtherScreen> other = Robolectric.buildActivity(OtherScreen.class).setup();
        feed.stop();
        FeedMute.onEnginePlay(a);
        assertEquals("A sound", lastFor("A"));
        other.pause().stop().destroy();
        feed.start().resume();
    }

    private Object engine(String name, String id) {
        Object engine = new Object() {
            @Override public String toString() {
                return name;
            }
        };
        ids.put(engine, id);
        return engine;
    }

    private static String name(Object engine) {
        return engine.toString();
    }

    private static Aweme video(String id) {
        return aweme(id, 0, false, 0);
    }

    private static Aweme story(String id) {
        return aweme(id, 40, true, 0);
    }

    private static Aweme live(String id) {
        return aweme(id, 0, false, 7);
    }

    private static Aweme aweme(String id, int type, boolean story, long liveId) {
        return new Aweme() {
            @Override public String getAid() { return id; }
            @Override public int getAwemeType() { return type; }
            @Override public boolean getIsTikTokStory() { return story; }
            @Override public long getLiveId() { return liveId; }
            @Override public boolean isLiveReplay() { return false; }
            @Override public String getLiveType() { return null; }
        };
    }
}
