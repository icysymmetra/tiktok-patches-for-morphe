package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.common.widget.VerticalViewPager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BlockFeedAdvanceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<Activity> owner;
    private VerticalViewPager pager;
    private final FakeService service = new FakeService();

    @Before public void setup() throws Exception {
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", service);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod",
                FakeService.class.getMethod("block", String.class, String.class, int.class, int.class));
        ReflectionHelpers.setStaticField(BlockAuthorOverlay.class, "requestInFlight", false);
        Settings.BLOCK_AUTHOR_BUTTON.save(false);
        Settings.NOT_INTERESTED_BUTTON.save(false);
        CurrentVideoAuthor.resetForTests();
        owner = Robolectric.buildActivity(Activity.class).setup().visible().windowFocusChanged(true);
        Utils.setContext(owner.get());
        Utils.setActivity(owner.get());
        pager = new VerticalViewPager(owner.get());
        owner.get().setContentView(pager);
        select("clip", "creator");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View root = pager.getRootView();
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 400, 800);
    }

    private void select(String clip, String creator) {
        CurrentVideoAuthor.update(new CurrentVideoAuthorTest.Params(clip, creator));
        CurrentVideoAuthor.onPlaying(clip);
    }

    private BlockFeedAdvance capture() {
        BlockFeedAdvance pending = BlockFeedAdvance.capture(CurrentVideoAuthor.get());
        assertNotNull("A visible idle native pager should be captured", pending);
        return pending;
    }

    @After public void cleanup() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedService", null);
        ReflectionHelpers.setStaticField(BlockAuthorService.class, "cachedBlockMethod", null);
        ReflectionHelpers.setStaticField(BlockAuthorOverlay.class, "requestInFlight", false);
        java.lang.reflect.Method dismiss = BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        CurrentVideoAuthor.resetForTests();
        Utils.setActivity(null);
        owner.close();
    }

    @Test public void confirmedCurrentVideoAdvancesExactlyOnceWithoutGesture() {
        BlockFeedAdvance pending = capture();
        assertTrue(pending.advance());
        assertEquals(4, pager.current);
        assertFalse(pending.advance());
        assertEquals(1, pager.advances);
    }

    @Test public void anotherVideoFromTheSameAuthorIsNotSkippedByALateReply() {
        BlockFeedAdvance pending = capture();
        select("another", "creator");
        assertFalse(pending.advance());
        assertEquals(0, pager.advances);
    }

    @Test public void pagerMovingBeforePlayerReportsNewVideoIsNotSkippedAgain() {
        BlockFeedAdvance pending = capture();
        pager.current++;
        assertFalse(pending.advance());
        assertEquals(0, pager.advances);
    }

    @Test public void scrollingAndEndOfFeedAreLeftToTikTok() {
        BlockFeedAdvance pending = capture();
        pager.scrollState = 1;
        assertFalse(pending.advance());
        assertNull(BlockFeedAdvance.capture(CurrentVideoAuthor.get()));
        pager.scrollState = 0;
        pending = capture();
        pager.hasNext = false;
        assertFalse(pending.advance());
        assertEquals(0, pager.advances);
    }

    @Test public void detachedHiddenOrBackgroundFeedNeverAdvances() {
        BlockFeedAdvance pending = capture();
        owner.windowFocusChanged(false);
        assertFalse(pending.advance());
        owner.windowFocusChanged(true);
        pending = capture();
        pager.setVisibility(View.GONE);
        assertFalse(pending.advance());
        pager.setVisibility(View.VISIBLE);
        pending = capture();
        owner.get().setContentView(new View(owner.get()));
        assertFalse(pending.advance());
        assertEquals(0, pager.advances);
    }

    @Test public void realTapPathBlocksOnceAdvancesAndUnblocksTheOriginalAuthor() throws Exception {
        tapBlock();
        tapBlock();
        settleRequest();
        assertEquals(java.util.List.of("creator:1"), service.calls);
        assertEquals(1, pager.advances);
        ViewGroup root = owner.get().findViewById(android.R.id.content);
        TextView chip = (TextView) root.getChildAt(root.getChildCount() - 1);
        assertEquals("Unblock", chip.getText().toString());
        select("next", "different");
        chip.performClick();
        tapBlock(); // Can't re-block while the original account is being unblocked.
        settleRequest();
        assertEquals(java.util.List.of("creator:1", "creator:0"), service.calls);
        assertEquals(1, pager.advances);
    }

    @Test public void rejectedAndUnreadableRepliesNeverAdvance() throws Exception {
        service.status = 7;
        tapBlock();
        settleRequest();
        assertEquals(0, pager.advances);
        service.status = null;
        tapBlock();
        settleRequest();
        assertEquals(0, pager.advances);
        ViewGroup root = owner.get().findViewById(android.R.id.content);
        assertEquals(1, root.getChildCount()); // Only the native pager, no success chip.
    }

    @Test public void confirmedReplyAfterASwipeDoesNotAdvanceAgain() throws Exception {
        tapBlock();
        pager.current++;
        select("new", "other");
        settleRequest();
        assertEquals(java.util.List.of("creator:1"), service.calls);
        assertEquals(0, pager.advances);
    }

    private static void tapBlock() throws Exception {
        java.lang.reflect.Method tap = BlockAuthorOverlay.class.getDeclaredMethod("onBlockTapped");
        tap.setAccessible(true);
        tap.invoke(null);
    }

    private static void settleRequest() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    public static final class FakeService {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        Integer status = 0;
        public FakeCall block(String uid, String secUid, int type, int source) {
            calls.add(uid + ":" + type);
            return new FakeCall(status);
        }
    }

    public static final class FakeCall {
        final Integer status;
        FakeCall(Integer status) { this.status = status; }
        public FakeReply execute() { return new FakeReply(status); }
    }

    public static final class FakeReply {
        public final Integer statusCode;
        FakeReply(Integer status) { statusCode = status; }
        public FakeReply body() { return this; }
    }
}
