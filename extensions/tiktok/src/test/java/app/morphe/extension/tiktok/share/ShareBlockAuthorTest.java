/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorService;
import app.morphe.extension.tiktok.blockauthor.BlockGlyphDrawable;
import app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor;
import app.morphe.extension.tiktok.blockauthor.VideoAuthor;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ShareBlockAuthorTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Author {
        public String uid;
        public String secUid;
        public String uniqueId;

        Author(String uid) { this.uid = uid; secUid = "sec-" + uid; uniqueId = "creator" + uid; }
        public String getUid() { return uid; }
        public String getSecUid() { return secUid; }
        public String getUniqueId() { return uniqueId; }
    }

    public static final class Post extends Aweme {
        public final Author author;
        private final String id;

        Post(String uid, String id) { author = new Author(uid); this.id = id; }
        public Author getAuthor() { return author; }
        @Override public String getAid() { return id; }
    }

    public static final class Params {
        private final Post post;
        Params(Post post) { this.post = post; }
        public Post getAweme() { return post; }
    }

    public static final class Action {
        private final String originalKey;
        Action(String key) { originalKey = key; }
        public String key() {
            String owned = ShareBlockAuthor.key(this);
            return owned == null ? originalKey : owned;
        }
    }

    private static final class Request {
        final VideoAuthor author;
        final String account;
        final boolean undo;
        final BlockAuthorService.Callback callback;

        Request(VideoAuthor author, String account, boolean undo, BlockAuthorService.Callback callback) {
            this.author = author; this.account = account; this.undo = undo; this.callback = callback;
        }
    }

    private final List<Request> requests = new ArrayList<>();
    private final List<Runnable> undos = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private Activity activity;
    private int constructed;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        SettingsStatus.shareSheetEnabled = true;
        pause(false);
        Settings.SHARE_BLOCK_AUTHOR.save(true);
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        SignedInUser.idForTests = "100";
        ShareBlockAuthor.nativeForTests = (from, post) -> {
            assertSame(activity, from);
            assertTrue(post instanceof Post);
            constructed++;
            return new Action("report");
        };
        ShareBlockAuthor.transportForTests = (author, account, undo, callback) ->
                requests.add(new Request(author, account, undo, callback));
        ShareBlockAuthor.feedbackForTests = new ShareBlockAuthor.Feedback() {
            @Override public void undo(String message, Runnable undo) {
                assertTrue(message.startsWith("Blocked "));
                undos.add(undo);
            }
            @Override public void notice(String message) { notices.add(message); }
        };
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
    }

    @After public void tearDown() {
        ShareBlockAuthor.nativeForTests = null;
        ShareBlockAuthor.transportForTests = null;
        ShareBlockAuthor.feedbackForTests = null;
        ((List<?>) ReflectionHelpers.getStaticField(ShareBlockAuthor.class, "BUILT")).clear();
        ((Map<?, ?>) ReflectionHelpers.getStaticField(ShareBlockAuthor.class, "IN_FLIGHT")).clear();
        ((Map<?, ?>) ReflectionHelpers.getStaticField(ShareBlockAuthor.class, "CREATORS")).clear();
        SignedInUser.idForTests = null;
        Settings.SHARE_BLOCK_AUTHOR.save(false);
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        SettingsStatus.shareSheetEnabled = false;
        pause(false);
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "resetForTests");
        activity.finish();
    }

    private static void pause(boolean paused) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ReflectionHelpers.ClassParameter.from(boolean.class, paused));
    }

    private static List<Object> row() {
        return new ArrayList<>(Arrays.asList(new Action("save"), new Action("report"), new Action("not_interested")));
    }

    private static AwemeSharePackage sheet(String uid) { return new AwemeSharePackage(new Post(uid, "video" + uid)); }
    private Object action(AwemeSharePackage sheet) { return ShareBlockAuthor.actions(sheet, row()).get(2); }

    @Test public void insertsBesideReportWithoutMutatingOrDuplicatingTheNativeRow() {
        AwemeSharePackage sheet = sheet("200");
        List<Object> nativeRow = row();
        List<?> finished = ShareBlockAuthor.actions(sheet, nativeRow);
        assertEquals(3, nativeRow.size());
        assertEquals(4, finished.size());
        assertSame(nativeRow.get(1), finished.get(1));
        assertEquals(ShareBlockAuthor.KEY, ((Action) finished.get(2)).key());
        assertSame(finished, ShareBlockAuthor.actions(sheet, finished));
        assertEquals(1, constructed);
        assertEquals("Block this creator", ShareBlockAuthor.label(finished.get(2)));
        assertTrue(ShareActionCatalog.entries(ShareSurface.VIDEO).stream().anyMatch(entry ->
                entry.key.equals("hushfeed block author") && entry.label.equals("Block this creator")));
    }

    @Test public void blocksThePackageAuthorAndUndoKeepsThatIdentityWithoutAdvancingTheFeed() {
        Post behind = new Post("300", "behind");
        ReflectionHelpers.callStaticMethod(CurrentVideoAuthor.class, "update",
                ReflectionHelpers.ClassParameter.from(Object.class, new Params(behind)));
        assertEquals("300", CurrentVideoAuthor.get().uid);
        AwemeSharePackage sheet = sheet("200");
        Object action = action(sheet);
        assertTrue(ShareBlockAuthor.click(action, activity, sheet));
        Request block = requests.get(0);
        assertEquals("200", block.author.uid);
        assertEquals("sec-200", block.author.secUid);
        assertEquals("video200", block.author.awemeId);
        assertEquals("100", block.account);
        assertFalse(block.undo);
        assertTrue(undos.isEmpty());
        block.callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertEquals(1, undos.size());
        undos.get(0).run();
        Request undo = requests.get(1);
        assertSame(block.author, undo.author);
        assertEquals(block.account, undo.account);
        assertTrue(undo.undo);
        undo.callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertTrue(notices.get(0).startsWith("Unblocked "));
        assertEquals("300", CurrentVideoAuthor.get().uid);
    }

    @Test public void ownUnknownSignedOutAndUnreviewedSurfacesGetNoAction() {
        List<Object> row = row();
        assertSame(row, ShareBlockAuthor.actions(sheet("100"), row));
        for (String invalid : new String[] {null, "", "0", "-1", "bad", "2 00"}) {
            assertSame(row, ShareBlockAuthor.actions(sheet(invalid), row));
        }
        SignedInUser.idForTests = "";
        assertSame(row, ShareBlockAuthor.actions(sheet("200"), row));
        SignedInUser.idForTests = "100";
        AwemeSharePackage profile = sheet("200"); profile.itemType = "user";
        assertSame(row, ShareBlockAuthor.actions(profile, row));
        profile.itemType = "live";
        assertSame(row, ShareBlockAuthor.actions(profile, row));
        assertSame(row, ShareBlockAuthor.actions(new Params(new Post("200", "unknown")), row));
        assertSame(row, ShareBlockAuthor.actions(new AwemeSharePackage(new Object()), row));
        assertSame(row, ShareBlockAuthor.actions(null, row));
        assertEquals(0, constructed);
    }

    @Test public void disabledPausedHiddenAndMissingReportNeverResurrectTheAction() {
        assertFalse(Settings.SHARE_BLOCK_AUTHOR.defaultValue);
        AwemeSharePackage sheet = sheet("200");
        List<Object> nativeRow = row();
        Settings.SHARE_BLOCK_AUTHOR.save(false);
        assertSame(nativeRow, ShareBlockAuthor.actions(sheet, nativeRow));
        Settings.SHARE_BLOCK_AUTHOR.save(true);
        pause(true);
        assertSame(nativeRow, ShareBlockAuthor.actions(sheet, nativeRow));
        pause(false);
        Settings.HIDE_SHARE_ACTIONS.save(true);
        assertSame(nativeRow, ShareBlockAuthor.actions(sheet, nativeRow));
        Settings.HIDE_SHARE_ACTIONS.save(false);
        for (String hidden : new String[] {ShareBlockAuthor.KEY, "Block this creator"}) {
            Settings.SHARE_HIDDEN_ITEMS.save(hidden);
            assertSame(nativeRow, ShareBlockAuthor.actions(sheet, nativeRow));
        }
        Settings.SHARE_HIDDEN_ITEMS.save("");
        List<Object> noReport = new ArrayList<>(Arrays.asList(new Action("save")));
        assertSame(noReport, ShareBlockAuthor.actions(sheet, noReport));
        SettingsStatus.shareSheetEnabled = false;
        assertSame(nativeRow, ShareBlockAuthor.actions(sheet, nativeRow));
        assertEquals(0, constructed);
    }

    @Test public void recycledPackageReplacesAndRetiresItsOldAction() {
        AwemeSharePackage sheet = sheet("200");
        List<?> first = ShareBlockAuthor.actions(sheet, row());
        Object stale = first.get(2);
        sheet.aweme = new Post("300", "video300");
        List<?> second = ShareBlockAuthor.actions(sheet, first);
        Object current = second.get(2);
        assertEquals(4, second.size());
        assertNotSame(stale, current);
        assertTrue(ShareBlockAuthor.click(stale, activity, sheet));
        assertTrue(requests.isEmpty());
        assertTrue(ShareBlockAuthor.click(current, activity, sheet));
        assertEquals("300", requests.get(0).author.uid);
        assertTrue(ShareBlockAuthor.click(current, activity, sheet("400")));
        assertEquals(1, requests.size());
    }

    @Test public void disablingOrChangingSurfaceStripsOwnedActionsAndStillConsumesTheirClicks() {
        AwemeSharePackage sheet = sheet("200");
        List<?> first = ShareBlockAuthor.actions(sheet, row());
        Object old = first.get(2);
        Settings.SHARE_BLOCK_AUTHOR.save(false);
        assertEquals(3, ShareBlockAuthor.actions(sheet, first).size());
        assertTrue(ShareBlockAuthor.click(old, activity, sheet));
        Settings.SHARE_BLOCK_AUTHOR.save(true);
        assertTrue(ShareBlockAuthor.click(old, activity, sheet));
        assertEquals(3, ShareBlockAuthor.actions(new Object(), first).size());
        assertTrue(requests.isEmpty());
    }

    @Test public void ordinaryReportAndRecycledIconKeepTheirNativeBehavior() {
        Object nativeReport = new Action("report");
        assertFalse(ShareBlockAuthor.owns(nativeReport));
        assertNull(ShareBlockAuthor.key(nativeReport));
        assertNull(ShareBlockAuthor.label(nativeReport));
        assertFalse(ShareBlockAuthor.click(nativeReport, activity, sheet("200")));
        ImageView image = new ImageView(activity);
        ColorDrawable original = new ColorDrawable(0xff123456);
        image.setImageDrawable(original);
        ShareBlockAuthor.bindIcon(nativeReport, image);
        assertSame(original, image.getDrawable());
        ShareBlockAuthor.bindIcon(action(sheet("200")), image);
        assertTrue(image.getDrawable() instanceof BlockGlyphDrawable);
        image.setImageDrawable(original); // Native bind resets the recycled ImageView first.
        ShareBlockAuthor.bindIcon(nativeReport, image);
        assertSame(original, image.getDrawable());
        assertTrue(requests.isEmpty());
    }

    @Test public void duplicateTargetsShareOneFlightAndEachCompletionIsDeliveredOnce() {
        AwemeSharePackage a = sheet("200"), b = sheet("200");
        Object first = action(a), second = action(b);
        ShareBlockAuthor.click(first, activity, a);
        ShareBlockAuthor.click(first, activity, a);
        ShareBlockAuthor.click(second, activity, b);
        assertEquals(1, requests.size());
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertEquals(1, undos.size());
        ShareBlockAuthor.click(first, activity, a);
        assertEquals(1, requests.size());
        undos.get(0).run();
        undos.get(0).run();
        assertEquals(2, requests.size());
    }

    @Test public void accountSwitchRefusesOldClicksCallbacksAndUndo() {
        AwemeSharePackage sheet = sheet("200");
        Object action = action(sheet);
        SignedInUser.idForTests = "101";
        ShareBlockAuthor.click(action, activity, sheet);
        assertTrue(requests.isEmpty());
        SignedInUser.idForTests = "100";
        ShareBlockAuthor.click(action, activity, sheet);
        SignedInUser.idForTests = "101";
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertTrue(undos.isEmpty());
        SignedInUser.idForTests = "100";
        AwemeSharePackage another = sheet("300");
        ShareBlockAuthor.click(action(another), activity, another);
        requests.get(1).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        SignedInUser.idForTests = "101";
        undos.get(0).run();
        assertEquals(2, requests.size());
    }

    @Test public void confirmedStateFollowsEverySheetForTheAccountAndCreator() {
        AwemeSharePackage a = sheet("200"), b = sheet("200");
        Object first = action(a), second = action(b);
        ShareBlockAuthor.click(first, activity, a);
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        ShareBlockAuthor.click(second, activity, b);
        AwemeSharePackage c = sheet("200"); Object third = action(c);
        ShareBlockAuthor.click(third, activity, c);
        assertEquals("Existing and newly opened sheets share the confirmed block", 1, requests.size());

        undos.get(0).run();
        requests.get(1).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        ShareBlockAuthor.click(second, activity, b);
        ShareBlockAuthor.click(third, activity, c);
        assertEquals(3, requests.size());
        requests.get(2).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        ShareBlockAuthor.click(third, activity, c);
        assertEquals(3, requests.size());
    }

    @Test public void anOlderUndoWaitsForAnAlreadyAcceptedBlockAndRunsAfterEveryReply() throws Exception {
        for (BlockAuthorService.Result result : BlockAuthorService.Result.values()) {
            int before = requests.size();
            AwemeSharePackage a = sheet("200"), b = sheet("200");
            Object first = action(a), second = action(b);
            ShareBlockAuthor.click(first, activity, a);
            requests.get(before).callback.onResult(BlockAuthorService.Result.CONFIRMED);
            Runnable olderUndo = undos.get(undos.size() - 1);
            // Model a repeat operation already accepted before shared confirmation was observed.
            // The public click gate prevents fresh repeats. A pending request still needs Undo.
            acceptedBlock(second);
            assertEquals(before + 2, requests.size());
            olderUndo.run();
            olderUndo.run();
            assertEquals("Undo waits until the pending block replies", before + 2, requests.size());
            Request pending = requests.get(before + 1);
            pending.callback.onResult(result);
            assertEquals(before + 3, requests.size());
            Request undo = requests.get(before + 2);
            assertTrue(undo.undo);
            assertEquals("200", undo.author.uid);
            assertEquals("video200", undo.author.awemeId);
            assertEquals("100", undo.account);
            pending.callback.onResult(result);
            olderUndo.run();
            assertEquals(before + 3, requests.size());
            undo.callback.onResult(BlockAuthorService.Result.CONFIRMED);
        }
    }

    @Test public void confirmedCreatorStateIsSeparateForEachSignedInAccount() {
        AwemeSharePackage a = sheet("200"); Object first = action(a);
        ShareBlockAuthor.click(first, activity, a);
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        SignedInUser.idForTests = "101";
        AwemeSharePackage b = sheet("200"); Object second = action(b);
        ShareBlockAuthor.click(second, activity, b);
        assertEquals(2, requests.size());
        assertEquals("101", requests.get(1).account);
        SignedInUser.idForTests = "100";
        ShareBlockAuthor.click(first, activity, a);
        assertEquals(2, requests.size());
        requests.get(1).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        SignedInUser.idForTests = "101";
        ShareBlockAuthor.click(second, activity, b);
        assertEquals(2, requests.size());
    }

    @Test public void aQueuedUndoDoesNotSendAfterAnAccountSwitch() throws Exception {
        AwemeSharePackage a = sheet("200"), b = sheet("200");
        Object first = action(a), second = action(b);
        ShareBlockAuthor.click(first, activity, a);
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        acceptedBlock(second);
        undos.get(0).run();
        SignedInUser.idForTests = "101";
        requests.get(1).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertEquals(2, requests.size());
        assertTrue(((Map<?, ?>) ReflectionHelpers.getStaticField(ShareBlockAuthor.class, "IN_FLIGHT")).isEmpty());
    }

    @Test public void retainedNormalReportNeverBecomesAnOwnedBlockAction() {
        AwemeSharePackage sheet = sheet("200");
        List<Object> nativeRow = row(); Object report = nativeRow.get(1);
        List<?> finished = ShareBlockAuthor.actions(sheet, nativeRow);
        ShareBlockAuthor.click(finished.get(2), activity, sheet);
        requests.get(0).callback.onResult(BlockAuthorService.Result.CONFIRMED);
        assertSame(report, finished.get(1));
        assertEquals("report", ((Action) report).key());
        assertFalse(ShareBlockAuthor.owns(report));
        assertNull(ShareBlockAuthor.label(report));
        assertNull(ShareBlockAuthor.icon(report));
        assertFalse(ShareBlockAuthor.click(report, activity, sheet));
        sheet.aweme = new Post("300", "video300");
        List<?> rebound = ShareBlockAuthor.actions(sheet, finished);
        assertSame(report, rebound.get(1));
        assertEquals("report", ((Action) report).key());
        assertFalse(ShareBlockAuthor.click(report, activity, sheet));
        assertEquals(1, requests.size());
    }

    @SuppressWarnings("unchecked")
    private void acceptedBlock(Object action) throws Exception {
        Object built = ReflectionHelpers.callStaticMethod(ShareBlockAuthor.class, "find",
                ReflectionHelpers.ClassParameter.from(Object.class, action));
        Class<?> pendingType = Class.forName(ShareBlockAuthor.class.getName() + "$Pending");
        Constructor<?> constructor = pendingType.getDeclaredConstructor(built.getClass(), boolean.class,
                BlockAuthorService.Callback.class);
        constructor.setAccessible(true);
        Object pending = constructor.newInstance(built, false, (BlockAuthorService.Callback) result -> {});
        Map<String, Object> flights = ReflectionHelpers.getStaticField(ShareBlockAuthor.class, "IN_FLIGHT");
        assertNull(flights.put("100:200", pending));
        Method dispatch = ShareBlockAuthor.class.getDeclaredMethod("dispatch", pendingType);
        dispatch.setAccessible(true);
        dispatch.invoke(null, pending);
    }

    @Test public void rejectedAndUnconfirmedRepliesNeverOfferUndoAndReleaseTheFlight() {
        AwemeSharePackage sheet = sheet("200"); Object action = action(sheet);
        for (BlockAuthorService.Result result : new BlockAuthorService.Result[] {
                BlockAuthorService.Result.REJECTED, BlockAuthorService.Result.UNCONFIRMED}) {
            ShareBlockAuthor.click(action, activity, sheet);
            requests.get(requests.size() - 1).callback.onResult(result);
            assertTrue(undos.isEmpty());
        }
        assertEquals(2, requests.size());
        assertTrue(notices.get(0).startsWith("TikTok didn't block"));
        assertTrue(notices.get(1).startsWith("Couldn't confirm the block"));
        ShareBlockAuthor.transportForTests = (author, account, undo, callback) -> { throw new IllegalStateException("queue"); };
        ShareBlockAuthor.click(action, activity, sheet);
        assertEquals(3, notices.size());
        ShareBlockAuthor.transportForTests = (author, account, undo, callback) ->
                requests.add(new Request(author, account, undo, callback));
        ShareBlockAuthor.click(action, activity, sheet);
        assertEquals(3, requests.size());
    }

    @Test public void aFailedFactoryAndDuplicateOwnedRowsKeepOnlySafeNativeActions() {
        AwemeSharePackage sheet = sheet("200");
        List<?> first = ShareBlockAuthor.actions(sheet, row());
        List<Object> duplicate = new ArrayList<>(first);
        duplicate.add(first.get(2));
        List<?> deduped = ShareBlockAuthor.actions(sheet, duplicate);
        assertEquals(4, deduped.size());
        ShareBlockAuthor.click(deduped.get(2), activity, sheet);
        assertEquals(1, requests.size());
        requests.get(0).callback.onResult(BlockAuthorService.Result.UNCONFIRMED);
        requests.clear();
        sheet.aweme = new Post("300", "video300");
        ShareBlockAuthor.nativeForTests = (activity, post) -> { throw new IllegalStateException("factory"); };
        List<?> safe = ShareBlockAuthor.actions(sheet, first);
        assertEquals(3, safe.size());
        assertEquals("report", ((Action) safe.get(1)).key());
        assertTrue(ShareBlockAuthor.click(first.get(2), activity, sheet));
        assertTrue(requests.isEmpty());
    }
}
