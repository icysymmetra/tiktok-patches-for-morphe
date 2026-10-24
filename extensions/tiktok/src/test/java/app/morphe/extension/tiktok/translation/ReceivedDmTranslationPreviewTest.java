/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
public class ReceivedDmTranslationPreviewTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Activity activity;
    private FrameLayout root;
    private TextView text;
    private Assem assem;
    private MenuOwner menu;
    private int factories;
    private final List<Call> calls = new ArrayList<>();

    private static final class Message {
        String uuid = "message-a", conversation = "conversation-a", sender = "peer-a";
        String source = "received original", content = "native text payload";
        int type = 7, sends, stores, guides, analytics;
        boolean self;
    }
    private static final class Model {
        Message message = new Message();
        boolean received = true;
    }
    private static final class Session {
        String conversation = "conversation-a", peer = "peer-a";
        boolean single = true, preview, report;
    }
    private static final class Assem {
        Model current = new Model();
        Session session = new Session();
        TextView text;
    }
    private static final class MenuOwner {
        final Assem assem;
        Model model;
        boolean received = true;
        MenuOwner(Assem assem) { this.assem = assem; model = assem.current; }
    }
    private static final class Action {
        final Runnable click;
        Action(Runnable click) { this.click = click; }
    }
    private static final class Call implements DmTranslationPreview.Request {
        final String account, source;
        final DmTranslationPreview.DispatchGuard guard;
        final DmTranslationPreview.Completion callback;
        int canceled;
        Call(String account, String source, DmTranslationPreview.DispatchGuard guard, DmTranslationPreview.Completion callback) {
            this.account = account; this.source = source; this.guard = guard; this.callback = callback;
        }
        @Override public void cancel() { canceled++; }
        void finish(String value) { callback.finished(value); }
    }

    @Before public void setUp() {
        PausedProcess.set(false);
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(true);
        SettingsStatus.commentTranslationEnabled = true;
        SettingsStatus.receivedDmTranslationPreviewEnabled = true;
        SettingsStatus.dmTranslationPreviewEnabled = false;
        SignedInUser.idForTests = "account-a";
        ReceivedDmTranslationPreview.resetForTests();
        ReceivedDmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            Call call = new Call(account, source, guard, callback); calls.add(call); return call;
        };
        ReceivedDmTranslationPreview.rowReader = (owner, model) -> {
            if (!(owner instanceof Assem) || !(model instanceof Model)) return null;
            Assem current = (Assem) owner;
            Model item = (Model) model;
            Message message = item.message;
            Session session = current.session;
            if (current.current != item || !item.received || message.type != 7 || message.self || !session.single
                    || session.preview || session.report || !message.conversation.equals(session.conversation)
                    || !message.sender.equals(session.peer)) return null;
            return new ReceivedDmTranslationPreview.Row(item, message, session, current.text, message.uuid,
                    session.conversation, session.peer, message.source, message.content);
        };
        ReceivedDmTranslationPreview.menuReader = (owner, message) -> {
            if (!(owner instanceof MenuOwner)) return null;
            MenuOwner current = (MenuOwner) owner;
            return current.received && current.model == current.assem.current && current.model.message == message
                    ? new ReceivedDmTranslationPreview.MenuOwner(current.assem, current.model) : null;
        };
        ReceivedDmTranslationPreview.itemFactory = (context, action) -> { factories++; return new Action(action); };
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        text = new TextView(activity);
        text.setText("received original");
        root.addView(text);
        assem = new Assem(); assem.text = text;
        menu = new MenuOwner(assem);
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        idle();
        assertTrue(text.isAttachedToWindow());
    }

    @After public void tearDown() {
        ReceivedDmTranslationPreview.resetForTests();
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(false);
        SettingsStatus.commentTranslationEnabled = false;
        SettingsStatus.receivedDmTranslationPreviewEnabled = false;
        SettingsStatus.dmTranslationPreviewEnabled = false;
        BaseSettings.PAUSED.save(false);
        BaseSettings.SAFE_MODE.save(false);
        PausedProcess.set(false);
        SignedInUser.idForTests = null;
        activity.finish(); idle();
    }

    /** Off, a row's bind reads nothing from TikTok's row; the first bind after it goes on does. */
    @Test public void offABindReadsNothingAndTheNextBindAfterTurningItOnDoes() {
        int[] reads = {0};
        ReceivedDmTranslationPreview.RowReader original = ReceivedDmTranslationPreview.rowReader;
        ReceivedDmTranslationPreview.rowReader = (owner, model) -> { reads[0]++; return original.read(owner, model); };
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(false);
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        assertEquals(0, reads[0]);
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(true);
        assertTrue("a row bound while off has no action yet", append(Collections.emptyList()).isEmpty());
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        assertEquals(1, reads[0]);
        assertNotNull(action());
    }

    @Test public void defaultOffLeavesTheNativeListAndListenersUntouched() {
        assertEquals(Boolean.FALSE, Settings.RECEIVED_DM_TRANSLATION_PREVIEW.defaultValue);
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(false);
        Object nativeItem = new Object();
        List<?> nativeItems = Collections.singletonList(nativeItem);
        assertSame(nativeItems, append(nativeItems));
        assertSame(nativeItem, nativeItems.get(0));
        assertEquals(0, factories);
        assertTrue(calls.isEmpty());
    }

    @Test public void missingReceivedHookAvailabilityCannotBorrowTheDraftOrCommentPatchGate() {
        SettingsStatus.receivedDmTranslationPreviewEnabled = false;
        SettingsStatus.dmTranslationPreviewEnabled = true;
        List<?> nativeItems = Collections.singletonList(new Object());
        assertSame(nativeItems, append(nativeItems));
        assertEquals(0, factories);
        assertTrue(calls.isEmpty());
        SettingsStatus.receivedDmTranslationPreviewEnabled = true;
        action().click.run(); Call old = calls.get(0); AlertDialog dialog = dialog();
        SettingsStatus.receivedDmTranslationPreviewEnabled = false;
        assertFalse(old.guard.current());
        old.finish("unavailable hook translation");
        assertEquals(1, old.canceled);
        assertFalse(dialog.isShowing());
        assertEquals("received original", text.getText().toString());
        assertEquals(0, assem.current.message.sends + assem.current.message.stores + assem.current.message.guides + assem.current.message.analytics);
    }

    @Test @Config(sdk = 23) public void previewShowsTranslationAndOriginalWithoutChangingOrSendingTheMessage() {
        int[] nativeClicks = {0};
        Action nativeCopy = new Action(() -> nativeClicks[0]++);
        Object nativeDelete = new Object();
        List<?> nativeItems = Collections.unmodifiableList(Arrays.asList(nativeCopy, nativeDelete));
        List<?> result = append(nativeItems);
        assertNotSame(nativeItems, result);
        assertEquals(2, nativeItems.size());
        assertEquals(3, result.size());
        assertSame(nativeCopy, result.get(0));
        assertSame(nativeDelete, result.get(1));
        ((Action) result.get(2)).click.run();
        AlertDialog dialog = dialog();
        Call call = calls.get(0);
        assertEquals("account-a", call.account);
        assertEquals("received original", call.source);
        assertTrue(call.guard.current());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertTrue(message(dialog).contains("Original message\n\nreceived original"));
        call.finish("received translated");
        assertEquals("Translated message\n\nreceived translated", message(dialog));
        assertEquals("Show original", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals("Original message\n\nreceived original", message(dialog));
        assertEquals("Show translation", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("Translated message\n\nreceived translated", message(dialog));
        assertEquals("received original", text.getText().toString());
        assertEquals("received original", assem.current.message.source);
        assertEquals("native text payload", assem.current.message.content);
        assertEquals(0, assem.current.message.sends + assem.current.message.stores + assem.current.message.guides + assem.current.message.analytics);
        assertEquals(0, nativeClicks[0]);
        nativeCopy.click.run();
        assertEquals(1, nativeClicks[0]);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertFalse(dialog.isShowing());
        assertFalse(call.guard.current());
        assertEquals(1, call.canceled);
    }

    @Test public void onlyTheExactCurrentReceivedTextRowAndSinglePeerCanOfferTheAction() {
        List<?> nativeItems = Collections.singletonList(new Object());
        menu.received = false; assertSame(nativeItems, append(nativeItems)); menu.received = true;
        assem.current.received = false; assertSame(nativeItems, append(nativeItems)); assem.current.received = true;
        assem.current.message.self = true; assertSame(nativeItems, append(nativeItems)); assem.current.message.self = false;
        assem.current.message.type = 5; assertSame(nativeItems, append(nativeItems)); assem.current.message.type = 7;
        assem.session.single = false; assertSame(nativeItems, append(nativeItems)); assem.session.single = true;
        assem.session.preview = true; assertSame(nativeItems, append(nativeItems)); assem.session.preview = false;
        assem.session.report = true; assertSame(nativeItems, append(nativeItems)); assem.session.report = false;
        assem.session.peer = "account-a"; assem.current.message.sender = "account-a";
        assertSame(nativeItems, append(nativeItems));
        assertEquals(0, factories);
        assertTrue(calls.isEmpty());
    }

    @Test public void menuFromAnotherMessageOrModelAndChangedTextNeverDispatch() {
        List<?> nativeItems = Collections.singletonList(new Object());
        assertSame(nativeItems, ReceivedDmTranslationPreview.appendAction(nativeItems, menu, new Message()));
        Model old = menu.model;
        menu.model = new Model(); assertSame(nativeItems, append(nativeItems)); menu.model = old;
        Action action = action();
        assem.current.message.source = "changed before click";
        action.click.run();
        assertTrue(calls.isEmpty());
    }

    @Test public void sameUuidNewModelAndBindingCannotAcceptOldMenuOrLateResponse() {
        Action oldMenu = action();
        oldMenu.click.run();
        AlertDialog oldDialog = dialog();
        Call old = calls.get(0);
        assem.current = new Model(); // Deliberately has the same UUID/source/conversation/peer.
        menu = new MenuOwner(assem);
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        assertEquals(1, old.canceled);
        assertFalse(oldDialog.isShowing());
        assertFalse(old.guard.current());
        oldMenu.click.run(); old.finish("wrong row");
        assertEquals(1, calls.size());
        action().click.run();
        Call current = calls.get(1);
        current.finish("current row");
        assertEquals("Translated message\n\ncurrent row", message(dialog()));
        old.finish("late again");
        assertEquals("Translated message\n\ncurrent row", message(dialog()));
    }

    @Test public void rebindingTheSameObjectsStillRetiresThePreviousGeneration() {
        Action oldMenu = action(); oldMenu.click.run();
        Call old = calls.get(0);
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        oldMenu.click.run(); old.finish("stale same UUID");
        assertFalse(old.guard.current());
        assertEquals(1, old.canceled);
        assertEquals(1, calls.size());
        action().click.run();
        assertEquals(2, calls.size());
    }

    @Test public void anotherReceivedOwnerCannotKeepTheSameNativeTextViewClaimed() {
        Action previous = action(); previous.click.run(); Call old = calls.get(0); AlertDialog oldDialog = dialog();
        Assem replacement = new Assem(); replacement.text = text;
        ReceivedDmTranslationPreview.bound(replacement, replacement.current);
        assertFalse(oldDialog.isShowing()); assertEquals(1, old.canceled); assertFalse(old.guard.current());
        previous.click.run(); assertEquals(1, calls.size());
        assem = replacement; menu = new MenuOwner(assem);
        action().click.run(); assertEquals(2, calls.size());
    }

    @Test public void aQueuedClickFromTheOldDialogCannotCancelTheNewRequest() {
        action().click.run(); calls.get(0).finish("first translation");
        AlertDialog previous = dialog();
        action().click.run(); Call current = calls.get(1); AlertDialog replacement = dialog();
        previous.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(replacement.isShowing()); assertTrue(current.guard.current()); assertEquals(0, current.canceled);
        current.finish("current translation");
        assertEquals("Translated message\n\ncurrent translation", message(replacement));
    }

    @Test public void changedSessionPeerMessageSourceAndPayloadEachRetirePendingWork() {
        List<Runnable> changes = Arrays.asList(
                () -> assem.session = new Session(),
                () -> { assem.session.peer = "peer-b"; assem.current.message.sender = "peer-b"; },
                () -> { assem.session.conversation = "conversation-b"; assem.current.message.conversation = "conversation-b"; },
                () -> { Message replacement = new Message(); assem.current.message = replacement; },
                () -> assem.current.message.uuid = "message-b",
                () -> assem.current.message.source = "changed text",
                () -> assem.current.message.content = "changed native payload");
        for (Runnable change : changes) {
            assem.current = new Model(); assem.session = new Session(); menu = new MenuOwner(assem);
            ReceivedDmTranslationPreview.bound(assem, assem.current);
            action().click.run(); AlertDialog dialog = dialog(); Call call = calls.get(calls.size() - 1);
            change.run();
            assertFalse(call.guard.current());
            call.finish("stale answer");
            assertFalse(dialog.isShowing());
            assertEquals(1, call.canceled);
            assertEquals("received original", text.getText().toString());
        }
    }

    @Test public void accountEpochRejectsAtoBtoABeforeQueuedMainCleanup() throws Exception {
        Action oldMenu = action(); oldMenu.click.run();
        AlertDialog dialog = dialog(); Call old = calls.get(0);
        Thread logout = new Thread(() -> {
            SignedInUser.idForTests = "account-b";
            ReceivedDmTranslationPreview.accountReset();
            SignedInUser.idForTests = "account-a";
        });
        logout.start(); logout.join();
        assertFalse(old.guard.current());
        oldMenu.click.run();
        assertEquals(1, calls.size());
        idle();
        assertFalse(dialog.isShowing());
        assertEquals(1, old.canceled);
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        action().click.run(); calls.get(1).finish("new account epoch");
        old.finish("old account epoch");
        assertEquals("Translated message\n\nnew account epoch", message(dialog()));
    }

    @Test public void accountEpochChangingDuringIdentityReadRejectsTheNewBinding() {
        ReceivedDmTranslationPreview.RowReader original = ReceivedDmTranslationPreview.rowReader;
        ReceivedDmTranslationPreview.rowReader = (owner, model) -> {
            ReceivedDmTranslationPreview.accountReset();
            return original.read(owner, model);
        };
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        List<?> nativeItems = Collections.singletonList(new Object());
        assertSame(nativeItems, append(nativeItems));
        assertTrue(calls.isEmpty());
    }

    @Test public void queuedLogoutCleanupCannotCloseAFreshBindingOrItsDialog() throws Exception {
        action().click.run(); Call old = calls.get(0); AlertDialog oldDialog = dialog();
        Thread logout = new Thread(ReceivedDmTranslationPreview::accountReset);
        logout.start(); logout.join();
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        action().click.run(); Call current = calls.get(1); AlertDialog currentDialog = dialog();
        idle();
        assertFalse(oldDialog.isShowing()); assertEquals(1, old.canceled);
        assertTrue(currentDialog.isShowing()); assertTrue(current.guard.current()); assertEquals(0, current.canceled);
        current.finish("fresh binding");
        assertEquals("Translated message\n\nfresh binding", message(currentDialog));
    }

    @Test public void identityChangesDuringNativeReadsCannotReturnOneStaleOwnershipCheck() {
        ReceivedDmTranslationPreview.RowReader original = ReceivedDmTranslationPreview.rowReader;
        for (Runnable change : Arrays.<Runnable>asList(
                ReceivedDmTranslationPreview::accountReset,
                () -> { Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(false); Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(true); },
                () -> ReceivedDmTranslationPreview.bound(assem, assem.current))) {
            ReceivedDmTranslationPreview.bound(assem, assem.current);
            action().click.run(); Call old = calls.get(calls.size() - 1); AlertDialog previous = dialog();
            String originalMessage = message(previous);
            ReceivedDmTranslationPreview.rowReader = (owner, model) -> {
                ReceivedDmTranslationPreview.Row row = original.read(owner, model);
                ReceivedDmTranslationPreview.rowReader = original;
                change.run();
                return row;
            };
            assertFalse(old.guard.current());
            old.finish("stale owner after native read");
            assertFalse(previous.isShowing());
            assertEquals(1, old.canceled);
            assertEquals(originalMessage, message(previous));
        }
        assertEquals("received original", text.getText().toString());
    }

    @Test public void queuedGateCleanupCannotCancelWorkCreatedAfterTheGateNotification() throws Exception {
        Action previous = action(); previous.click.run(); Call old = calls.get(0);
        Field listenerField = ReceivedDmTranslationPreview.class.getDeclaredField("PREFERENCES");
        listenerField.setAccessible(true);
        SharedPreferences.OnSharedPreferenceChangeListener listener =
                (SharedPreferences.OnSharedPreferenceChangeListener) listenerField.get(null);
        Thread notifications = new Thread(() -> {
            listener.onSharedPreferenceChanged(null, Settings.RECEIVED_DM_TRANSLATION_PREVIEW.key);
            listener.onSharedPreferenceChanged(null, Settings.RECEIVED_DM_TRANSLATION_PREVIEW.key);
        });
        notifications.start(); notifications.join();
        previous.click.run(); assertEquals(1, calls.size());
        action().click.run(); Call current = calls.get(1); AlertDialog dialog = dialog();
        idle();
        assertEquals(1, old.canceled); assertEquals(0, current.canceled);
        assertTrue(dialog.isShowing()); assertTrue(current.guard.current());
    }

    @Test public void offOnPauseAndSafeModeCancelWithoutRevivingAnOldAction() {
        Action oldMenu = action(); oldMenu.click.run();
        Call old = calls.get(0); AlertDialog dialog = dialog();
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(false);
        Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(true);
        oldMenu.click.run(); old.finish("old gate");
        assertEquals(1, calls.size()); assertFalse(dialog.isShowing()); assertEquals(1, old.canceled);
        action().click.run(); Call paused = calls.get(1);
        BaseSettings.PAUSED.save(true); PausedProcess.set(true); idle();
        assertFalse(paused.guard.current()); assertEquals(1, paused.canceled);
        BaseSettings.PAUSED.save(false); PausedProcess.set(false);
        action().click.run(); Call safe = calls.get(2);
        BaseSettings.SAFE_MODE.save(true); idle();
        assertFalse(safe.guard.current()); assertEquals(1, safe.canceled);
        assertEquals("received original", text.getText().toString());
    }

    @Test public void responseAtTheDeadlineIsRejectedEvenBeforeTheWatchdogRuns() {
        long began = android.os.SystemClock.elapsedRealtime();
        action().click.run(); Call accepted = calls.get(0); AlertDialog first = dialog();
        long remaining = began + DmTranslationPreview.TIMEOUT_MS - 1 - android.os.SystemClock.elapsedRealtime();
        assertTrue(remaining > 0);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(remaining, TimeUnit.MILLISECONDS);
        assertTrue(accepted.guard.current());
        accepted.finish("just before the deadline");
        assertEquals("Translated message\n\njust before the deadline", message(first));
        first.dismiss();

        action().click.run(); Call expired = calls.get(1); AlertDialog second = dialog();
        ShadowSystemClock.advanceBy(DmTranslationPreview.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(expired.guard.current());
        expired.finish("too late");
        assertFalse(second.isShowing());
        assertEquals(1, expired.canceled);
        assertEquals("Couldn't translate this message. Try again in a moment.", ShadowToast.getTextOfLatestToast());
        idle();
        assertEquals(1, expired.canceled);
        assertEquals("received original", text.getText().toString());
    }

    @Test public void slowNativeOwnershipReadsCannotDelayExpiryOrPermitLateDispatch() {
        action().click.run(); Call call = calls.get(0); AlertDialog dialog = dialog();
        ReceivedDmTranslationPreview.RowReader original = ReceivedDmTranslationPreview.rowReader;
        ReceivedDmTranslationPreview.rowReader = (owner, model) -> {
            ShadowSystemClock.advanceBy(37, TimeUnit.MILLISECONDS);
            return original.read(owner, model);
        };
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(DmTranslationPreview.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(1, call.canceled);
        assertFalse(dialog.isShowing());
        assertFalse(call.guard.current());
        assertEquals("received original", text.getText().toString());

        ReceivedDmTranslationPreview.rowReader = original;
        action().click.run(); Call crossed = calls.get(1);
        ReceivedDmTranslationPreview.rowReader = (owner, model) -> {
            ShadowSystemClock.advanceBy(DmTranslationPreview.TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return original.read(owner, model);
        };
        assertFalse(crossed.guard.current());
        crossed.finish("crossed deadline during native read");
        assertEquals(1, crossed.canceled);
        assertEquals("received original", text.getText().toString());
    }

    @Test public void nativeUnbindAndDetachCancelTheActualOwnedRequestAndLateResults() {
        Action beforeDetach = action(); beforeDetach.click.run();
        Call detached = calls.get(0); AlertDialog first = dialog();
        root.removeView(text);
        assertFalse(first.isShowing()); assertEquals(1, detached.canceled); assertFalse(detached.guard.current());
        root.addView(text);
        beforeDetach.click.run(); assertEquals(1, calls.size());
        action().click.run(); Call unbound = calls.get(1); AlertDialog second = dialog();
        ReceivedDmTranslationPreview.unbound(assem);
        assertEquals(1, unbound.canceled); assertFalse(second.isShowing());
        detached.finish("late detached"); unbound.finish("late unbound");
        assertEquals("received original", text.getText().toString());
    }

    @Test public void cancelErrorAndTimeoutAreBoundedAndLeaveNoPrivateErrorText() {
        action().click.run(); AlertDialog canceled = dialog(); Call old = calls.get(0);
        canceled.cancel();
        old.finish("late after Cancel"); assertEquals(1, old.canceled); assertFalse(canceled.isShowing());
        action().click.run(); Call failed = calls.get(1);
        failed.finish(null);
        assertEquals(1, failed.canceled);
        assertEquals("Couldn't translate this message. Try again in a moment.", ShadowToast.getTextOfLatestToast());
        action().click.run(); AlertDialog timeout = dialog(); Call pending = calls.get(2);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(DmTranslationPreview.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertEquals(1, pending.canceled); assertFalse(timeout.isShowing()); assertFalse(pending.guard.current());
        assertEquals("received original", text.getText().toString());
    }

    @Test public void synchronousCompletionAndMalformedResultsCannotLeaveAnUncancelledCall() {
        Call[] synchronous = new Call[1];
        ReceivedDmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            synchronous[0] = new Call(account, source, guard, callback);
            callback.finished("immediate translation"); return synchronous[0];
        };
        action().click.run();
        assertEquals(1, synchronous[0].canceled);
        assertEquals("Translated message\n\nimmediate translation", message(dialog()));
        synchronous[0].finish("second answer");
        assertEquals("Translated message\n\nimmediate translation", message(dialog()));
        dialog().dismiss();
        ReceivedDmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            Call call = new Call(account, source, guard, callback); calls.add(call); return call;
        };
        char[] oversized = new char[DmTranslationPreview.MAX_TEXT + 1];
        Arrays.fill(oversized, 'x');
        for (String invalid : Arrays.asList("", "   ", new String(oversized))) {
            action().click.run(); Call call = calls.get(calls.size() - 1); AlertDialog dialog = dialog();
            call.finish(invalid); assertFalse(dialog.isShowing()); assertEquals(1, call.canceled);
        }
    }

    @Test public void changedOrUnavailableNativeContractsLeaveEveryOriginalItemIntact() {
        List<?> nativeItems = Collections.singletonList(new Object());
        ReceivedDmTranslationPreview.rowReader = (owner, model) -> { throw new NoSuchFieldException("changed native owner"); };
        assertSame(nativeItems, append(nativeItems));
        ReceivedDmTranslationPreview.bound(assem, assem.current);
        assertSame(nativeItems, append(nativeItems));
        assertTrue(calls.isEmpty());
    }

    @Test public void sourceAndMenuBoundsAndFactoryFailureLeaveTheNativeMenuUntouched() {
        List<?> nativeItems = Collections.singletonList(new Object());
        assem.current.message.source = " "; assertSame(nativeItems, append(nativeItems));
        char[] oversized = new char[DmTranslationPreview.MAX_TEXT + 1]; Arrays.fill(oversized, 'x');
        assem.current.message.source = new String(oversized); assertSame(nativeItems, append(nativeItems));
        assem.current.message.source = "received original";
        List<?> oversizedMenu = Collections.nCopies(129, new Object());
        assertSame(oversizedMenu, append(oversizedMenu));
        ReceivedDmTranslationPreview.itemFactory = (context, action) -> { throw new NoSuchMethodException("changed native factory"); };
        assertSame(nativeItems, append(nativeItems));
        assertEquals(0, factories); assertTrue(calls.isEmpty());
    }

    private List<?> append(List<?> items) { return ReceivedDmTranslationPreview.appendAction(items, menu, assem.current.message); }
    private Action action() {
        List<?> result = append(Collections.emptyList());
        assertEquals(1, result.size());
        return (Action) result.get(0);
    }
    private AlertDialog dialog() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog;
    }
    private static String message(AlertDialog dialog) { return ((TextView) dialog.findViewById(android.R.id.message)).getText().toString(); }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}
