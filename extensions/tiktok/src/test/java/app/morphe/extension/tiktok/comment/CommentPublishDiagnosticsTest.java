package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import androidx.fragment.app.Fragment;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 * The report says what a comment send had in hand and where the publish entry went.
 *
 * <p>On a Galaxy S25, tapping Send left the text in the box with no toast and nothing in the
 * log, on every video tried. TikTok's publish entry has a dozen silent returns before the
 * request; these pin the lines that tell them apart.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CommentPublishDiagnosticsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        LogBufferManager.clearLogBuffer();
    }

    @After public void tearDown() {
        CommentPublishDiagnostics.topScreenTestMode = 0;
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        LogBufferManager.clearLogBuffer();
    }

    @Test public void theRequestLineSaysWhatTheEntryHadInHandWithoutTheText() throws Exception {
        String state = CommentPublishDiagnostics.describe(new ViewModel(), new Params());
        assertEquals("text=10 chars, lists=[2,0,1], gift=no, reply=no, context=yes, video=yes", state);

        // The text itself never reaches the report, only its length.
        CommentPublishDiagnostics.onPublishRequested(new ViewModel(), new Params());
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("requested with text=10 chars"));
        assertTrue(report, !report.contains("Nice video"));
    }

    @Test public void aMissingContextOrVideoIsSaidSo() throws Exception {
        ViewModel empty = new ViewModel();
        empty.context = null;
        assertTrue(CommentPublishDiagnostics.describe(empty, new Params()).endsWith("context=no, video=no"));
        ViewModel noVideo = new ViewModel();
        noVideo.context.common.aweme = null;
        assertTrue(CommentPublishDiagnostics.describe(noVideo, new Params()).endsWith("context=yes, video=no"));
        assertTrue(CommentPublishDiagnostics.describe(null, null).startsWith("text=0 chars, lists=[]"));
    }

    @Test public void theExitAndTheHandOffEachLeaveTheirLine() {
        CommentPublishDiagnostics.onPublishExit(37);
        CommentPublishDiagnostics.onPublishHandedOff();
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("returned before the request at exit 37"));
        assertTrue(report, report.contains("handed to the request"));
    }

    /** A screen TikTok's top page did give is what the send check gets, and nothing is logged. */
    @Test public void aScreenTikTokFoundGoesToTheCheckUntouched() {
        Activity top = Robolectric.buildActivity(Activity.class).get();
        CommentPublishDiagnostics.onPublishRequested(new PanelViewModel(Robolectric.buildActivity(Activity.class).get()), new Params());
        assertSame(top, CommentPublishDiagnostics.screenForSendCheck(top));
        assertFalse(LogBufferManager.buildExportText().contains("top page has no screen"));
    }

    /**
     * The S25 case: TikTok's most recently opened page has lost its screen, so the check would
     * stop the send in silence. The check gets the comment panel's screen instead, and the
     * report names the page that had none.
     */
    @Test public void withNoTopScreenTheCheckGetsTheCommentPanelsOwn() {
        Activity panel = Robolectric.buildActivity(Activity.class).get();
        CommentPublishDiagnostics.onPublishRequested(new PanelViewModel(panel), new Params());
        CommentPublishDiagnostics.onTopPage(new DetachedPage());
        assertSame(panel, CommentPublishDiagnostics.screenForSendCheck(null));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("TikTok's top page has no screen (a detached page)"));
        assertTrue(report, report.contains("checking the send against the comment panel's screen, Activity"));
    }

    /** With no panel screen either, the check still gets none, as it would have unpatched. */
    @Test public void aPanelWithNoScreenLeavesTheCheckAsItWas() {
        CommentPublishDiagnostics.onPublishRequested(new PanelViewModel(null), new Params());
        assertNull(CommentPublishDiagnostics.screenForSendCheck(null));
        assertNull("a view model with no fragment", CommentPublishDiagnostics.panelScreen(new ViewModel()));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("the comment panel has none either"));
    }

    /** The phone check's two modes: 1 forces the fallback, 2 forces TikTok's own silent stop. */
    @Test public void theTestModesProduceBothSidesOnDemand() {
        Activity top = Robolectric.buildActivity(Activity.class).get();
        Activity panel = Robolectric.buildActivity(Activity.class).get();
        CommentPublishDiagnostics.onPublishRequested(new PanelViewModel(panel), new Params());
        CommentPublishDiagnostics.topScreenTestMode = 1;
        assertSame(panel, CommentPublishDiagnostics.screenForSendCheck(top));
        CommentPublishDiagnostics.topScreenTestMode = 2;
        assertNull(CommentPublishDiagnostics.screenForSendCheck(top));
        assertTrue(LogBufferManager.buildExportText().contains("test mode 2: TikTok's top page has no screen"));
    }

    /** A publish view model as the fix reads it: unrelated fields and the panel's fragment. */
    public static final class PanelViewModel {
        private final Object unrelated = new Object();
        private final Fragment panel;

        PanelViewModel(Activity screen) {
            panel = new Fragment(screen);
        }
    }

    /** TikTok's top page, as far as the report is concerned: its text. */
    public static final class DetachedPage {
        @Override public String toString() {
            return "a detached page";
        }
    }

    /** The publish parameters as TikTok lays them out: text, three lists, a sticker, a gift, a reply. */
    public static final class Params {
        public CharSequence text = "Nice video";
        public List<Object> images = Arrays.asList(new Object(), new Object());
        public List<Object> mentions = Collections.emptyList();
        public List<Object> audio = Collections.singletonList(new Object());
        public Object sticker = null;
        public GiftStruct gift = null;
        public Comment replyTo = null;
    }

    public static final class GiftStruct {}

    public static final class Comment {}

    public static final class ViewModel {
        CommentContextSource context = new CommentContextSource();

        CommentContextSource contextSource() {
            return context;
        }
    }

    public static final class CommentContextSource {
        CommonModel common = new CommonModel();

        public CommonModel getCommonModel() {
            return common;
        }
    }

    public static final class CommonModel {
        public Object aweme = new Object();
    }
}
