/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Rect;
import android.os.Looper;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.SuggestionSpan;
import android.text.style.UnderlineSpan;
import android.text.style.URLSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
public class DmTranslationPreviewTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private Activity activity;
    private FrameLayout root;
    private EditText editor;
    private Input input;
    private Session session;
    private Composer vm;
    private Assem assem;
    private final List<Call> calls = new ArrayList<>();

    public static final class Assem { public EditText LLJLLL; }
    private static final class Input { EditText editor; int sends; }
    private static final class Session { String conversation = "conversation-a", peer = "peer-a"; boolean single = true, preview, report; }
    private static final class Composer { Input input; Session session; int sends; }
    private static final class Call implements DmTranslationPreview.Request {
        final String account, source;
        final DmTranslationPreview.DispatchGuard guard;
        final DmTranslationPreview.Completion callback;
        int canceled;
        Call(String account, String source, DmTranslationPreview.DispatchGuard guard, DmTranslationPreview.Completion callback) {
            this.account = account; this.source = source; this.guard = guard; this.callback = callback;
        }
        @Override public void cancel() { canceled++; }
        void finish(String translation) { callback.finished(translation); }
    }

    @Before public void setUp() {
        PausedProcess.set(false);
        BaseSettings.SAFE_MODE.save(false);
        Settings.DM_TRANSLATION_PREVIEW.save(true);
        SettingsStatus.commentTranslationEnabled = true;
        SettingsStatus.dmTranslationPreviewEnabled = true;
        SettingsStatus.receivedDmTranslationPreviewEnabled = false;
        SignedInUser.idForTests = "account-a";
        DmTranslationPreview.resetForTests();
        DmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            Call call = new Call(account, source, guard, callback); calls.add(call); return call;
        };
        DmTranslationPreview.identityReader = (actualVm, actualInput, actualSession, actualAssem, actualEditor) -> {
            if (!(actualVm instanceof Composer) || !(actualInput instanceof Input) || !(actualSession instanceof Session) || !(actualAssem instanceof Assem)) return null;
            Composer composer = (Composer) actualVm;
            Session chat = (Session) actualSession;
            return composer.input == actualInput && composer.session == actualSession && ((Input) actualInput).editor == actualEditor
                    && ((Assem) actualAssem).LLJLLL == actualEditor && chat.single && !chat.preview && !chat.report
                    ? new DmTranslationPreview.Identity(chat.conversation, chat.peer) : null;
        };
        activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        editor = new EditText(activity);
        root.addView(editor);
        editor.setText("draft original");
        input = new Input(); input.editor = editor;
        session = new Session();
        vm = new Composer(); vm.input = input; vm.session = session;
        assem = new Assem(); assem.LLJLLL = editor;
        DmTranslationPreview.bindInput(input, editor);
        DmTranslationPreview.inputReady(assem);
        DmTranslationPreview.composerCreated(vm, input, session);
        idle();
        assertTrue(editor.isAttachedToWindow());
    }

    @After public void tearDown() {
        DmTranslationPreview.resetForTests();
        Settings.DM_TRANSLATION_PREVIEW.save(false);
        SettingsStatus.commentTranslationEnabled = false;
        SettingsStatus.dmTranslationPreviewEnabled = false;
        SettingsStatus.receivedDmTranslationPreviewEnabled = false;
        BaseSettings.SAFE_MODE.save(false);
        PausedProcess.set(false);
        SignedInUser.idForTests = null;
        activity.finish();
        idle();
    }

    @Test public void previewKeepsTheDraftAndUseOnlyReplacesThisEditorWithoutSending() {
        assertEquals(Boolean.FALSE, Settings.DM_TRANSLATION_PREVIEW.defaultValue);
        assertFalse(Settings.DM_TRANSLATION_PREVIEW.rebootApp);
        AlertDialog dialog = start();
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        Call call = calls.get(0);
        assertEquals("account-a", call.account);
        assertEquals("draft original", call.source);
        assertTrue(call.guard.current());
        call.finish("draft translated");
        assertEquals("draft original", editor.getText().toString());
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("draft translated", editor.getText().toString());
        assertEquals(editor.length(), editor.getSelectionStart());
        assertFalse(dialog.isShowing());
        assertEquals(0, input.sends + vm.sends);
        assertFalse(call.guard.current());
    }

    @Test @Config(sdk = 23) public void bothEditorMenusWorkOnTheMinimumSdkAndNativeCallbacksArePreserved() {
        NativeActions nativeActions = new NativeActions();
        DmTranslationPreview.composerCleared(vm);
        editor.setCustomSelectionActionModeCallback(nativeActions);
        editor.setCustomInsertionActionModeCallback(nativeActions);
        DmTranslationPreview.composerCreated(vm, input, session);
        ActionMode.Callback selection = editor.getCustomSelectionActionModeCallback();
        ActionMode.Callback insertion = editor.getCustomInsertionActionModeCallback();
        assertNotSame(nativeActions, selection);
        assertNotSame(nativeActions, insertion);
        Mode insertionMode = new Mode(insertion);
        assertTrue(insertion.onCreateActionMode(insertionMode, insertionMode.menu));
        assertNotNull(insertionMode.menu.findItem(DmTranslationPreview.ACTION_ID));
        Mode mode = new Mode(selection);
        assertTrue(selection.onCreateActionMode(mode, mode.menu));
        MenuItem nativeItem = mode.menu.findItem(71);
        assertNotNull(nativeItem);
        assertTrue(selection.onActionItemClicked(mode, nativeItem));
        assertEquals(1, nativeActions.clicks);
        assertTrue(selection.onPrepareActionMode(mode, mode.menu));
        Rect rect = new Rect();
        ((ActionMode.Callback2) selection).onGetContentRect(mode, editor, rect);
        assertEquals(new Rect(1, 2, 3, 4), rect);
        assertNotNull(mode.menu.findItem(DmTranslationPreview.ACTION_ID));
        selection.onDestroyActionMode(mode);
        assertEquals(1, nativeActions.destroyed);
        DmTranslationPreview.composerCleared(vm);
        assertSame(nativeActions, editor.getCustomSelectionActionModeCallback());
        assertSame(nativeActions, editor.getCustomInsertionActionModeCallback());
    }

    @Test public void nativeRejectionAndAnUnownedIdCollisionNeverBecomeOurAction() {
        NativeActions nativeActions = new NativeActions();
        nativeActions.create = false;
        DmTranslationPreview.composerCleared(vm);
        editor.setCustomSelectionActionModeCallback(nativeActions);
        DmTranslationPreview.composerCreated(vm, input, session);
        ActionMode.Callback callback = editor.getCustomSelectionActionModeCallback();
        Mode mode = new Mode(callback);
        assertFalse(callback.onCreateActionMode(mode, mode.menu));
        assertNull(mode.menu.findItem(DmTranslationPreview.ACTION_ID));
        nativeActions.create = true;
        MenuItem collision = mode.menu.add(0, DmTranslationPreview.ACTION_ID, 0, "Native action");
        assertTrue(callback.onCreateActionMode(mode, mode.menu));
        assertSame(collision, mode.menu.findItem(DmTranslationPreview.ACTION_ID));
        callback.onActionItemClicked(mode, collision);
        assertEquals(1, nativeActions.clicks);
        assertTrue(calls.isEmpty());
        Settings.DM_TRANSLATION_PREVIEW.save(false); idle();
        callback.onPrepareActionMode(mode, mode.menu);
        assertSame(collision, mode.menu.findItem(DmTranslationPreview.ACTION_ID));
    }

    @Test public void cancelAndNativeFailureKeepTheDraftAndCancelTheOwnedRequest() {
        AlertDialog canceled = start(); Call old = calls.get(0);
        canceled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();
        assertEquals(1, old.canceled);
        old.finish("late answer");
        assertEquals("draft original", editor.getText().toString());
        AlertDialog failure = start();
        calls.get(1).finish(null);
        assertFalse(failure.isShowing());
        assertEquals("Couldn't translate this draft. Try again in a moment.", ShadowToast.getTextOfLatestToast());
        assertEquals("draft original", editor.getText().toString());
    }

    @Test public void typingAndEvenRestoringTheSameTextRetiresTheOldGeneration() {
        AlertDialog dialog = start(); Call old = calls.get(0);
        editor.setText("different draft"); editor.setText("draft original");
        assertFalse(dialog.isShowing()); assertFalse(old.guard.current());
        old.finish("stale translation");
        AlertDialog next = start();
        old.finish("duplicate stale translation");
        assertFalse(next.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        calls.get(1).finish("fresh translation");
        next.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("fresh translation", editor.getText().toString());
    }

    @Test public void anyCurrentPeerSessionOrInputMismatchRefusesCompletionAndUse() {
        AlertDialog dialog = start(); Call call = calls.get(0);
        session.peer = "peer-b";
        call.finish("wrong peer answer"); assertFalse(dialog.isShowing());
        session.peer = "peer-a";
        dialog = start(); call = calls.get(1); call.finish("ready answer");
        session.conversation = "conversation-b";
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing()); assertEquals("draft original", editor.getText().toString());
        session.conversation = "conversation-a";
        dialog = start(); call = calls.get(2);
        vm.session = new Session();
        assertFalse(call.guard.current()); call.finish("new session answer"); assertFalse(dialog.isShowing());
        vm.session = session;
        dialog = start(); call = calls.get(3); vm.input = new Input();
        assertFalse(call.guard.current()); call.finish("new input answer"); assertFalse(dialog.isShowing());
    }

    @Test public void accountSwitchAndReturnCannotReviveAnOldRequestOrOwner() {
        AlertDialog dialog = start(); Call old = calls.get(0);
        SignedInUser.idForTests = "account-b";
        assertFalse(old.guard.current());
        DmTranslationPreview.accountReset();
        SignedInUser.idForTests = "account-a";
        old.finish("old account answer");
        assertFalse(dialog.isShowing()); assertFalse(old.guard.current());
        assertNull(editor.getCustomSelectionActionModeCallback());
        DmTranslationPreview.composerCreated(vm, input, session);
        AlertDialog next = start(); calls.get(1).finish("new account answer");
        assertTrue(next.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
    }

    @Test public void backgroundLogoutInvalidatesBothAnOldRequestAndAnOldMenuBeforeMainCleanup() throws Exception {
        AlertDialog dialog = start(); Call old = calls.get(0);
        ActionMode.Callback callback = editor.getCustomSelectionActionModeCallback();
        SignedInUser.idForTests = "account-b";
        Thread logout = new Thread(DmTranslationPreview::accountReset);
        logout.start(); logout.join();
        SignedInUser.idForTests = "account-a";
        // No looper idle or Owner polling occurred during the A to B to A switch.
        assertSame(callback, editor.getCustomSelectionActionModeCallback());
        assertFalse(old.guard.current());
        assertNoAction();
        old.finish("old epoch answer");
        assertFalse(dialog.isShowing());
        assertEquals("draft original", editor.getText().toString());
        DmTranslationPreview.composerCreated(vm, input, session);
        idle();
        assertNotNull("queued old-epoch cleanup must preserve a newly captured composer", editor.getCustomSelectionActionModeCallback());
        start(); assertEquals(2, calls.size());
    }

    @Test public void liveOffOnPauseAndSafeModeAllCancelAndNeverUseOldResults() {
        AlertDialog dialog = start(); Call old = calls.get(0);
        Settings.DM_TRANSLATION_PREVIEW.save(false);
        Settings.DM_TRANSLATION_PREVIEW.save(true); idle();
        old.finish("before switch answer"); assertFalse(dialog.isShowing());
        assertFalse(old.guard.current());
        dialog = start(); old = calls.get(1);
        PausedProcess.set(true);
        assertFalse(old.guard.current()); idleFor(100);
        assertFalse(dialog.isShowing());
        assertNoAction();
        PausedProcess.set(false);
        dialog = start(); old = calls.get(2);
        BaseSettings.SAFE_MODE.save(true); idle();
        old.finish("before safe mode answer"); assertFalse(dialog.isShowing());
        assertNoAction();
    }

    @Test public void delayedOffOnCleanupCannotCancelAPreviewCreatedAfterReenable() {
        AlertDialog oldDialog = start(); Call old = calls.get(0);
        Settings.DM_TRANSLATION_PREVIEW.save(false); Settings.DM_TRANSLATION_PREVIEW.save(true);
        AlertDialog current = start();
        idle();
        assertFalse(oldDialog.isShowing()); assertFalse(old.guard.current());
        assertTrue(current.isShowing()); assertTrue(calls.get(1).guard.current());
        calls.get(1).finish("new toggle generation");
        current.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("new toggle generation", editor.getText().toString());
    }

    @Test public void offOnDuringTheNativeIdentityReadCannotPermitOneStaleDispatchOrResponse() {
        AlertDialog previous = start(); Call old = calls.get(0);
        DmTranslationPreview.IdentityReader original = DmTranslationPreview.identityReader;
        DmTranslationPreview.identityReader = (actualVm, actualInput, actualSession, actualAssem, actualEditor) -> {
            DmTranslationPreview.Identity read = original.read(actualVm, actualInput, actualSession, actualAssem, actualEditor);
            DmTranslationPreview.identityReader = original;
            Settings.DM_TRANSLATION_PREVIEW.save(false);
            Settings.DM_TRANSLATION_PREVIEW.save(true);
            return read;
        };
        assertFalse("a gate change during the final owner read must retire this dispatch", old.guard.current());
        old.finish("old gate translation");
        assertFalse(previous.isShowing());
        assertEquals(1, old.canceled);
        assertEquals("draft original", editor.getText().toString());
        AlertDialog fresh = start(); Call current = calls.get(1);
        idle();
        assertTrue("queued old gate cleanup must preserve new work", fresh.isShowing());
        assertTrue(current.guard.current());
        current.finish("fresh gate translation");
        fresh.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("fresh gate translation", editor.getText().toString());
        assertEquals(0, input.sends + vm.sends);
    }

    @Test public void sameObjectRebindDuringTheNativeIdentityReadCannotPermitOneStaleDispatchOrResponse() {
        AlertDialog previous = start(); Call old = calls.get(0);
        DmTranslationPreview.IdentityReader original = DmTranslationPreview.identityReader;
        DmTranslationPreview.identityReader = (actualVm, actualInput, actualSession, actualAssem, actualEditor) -> {
            DmTranslationPreview.Identity read = original.read(actualVm, actualInput, actualSession, actualAssem, actualEditor);
            DmTranslationPreview.identityReader = original;
            DmTranslationPreview.inputReady(assem);
            return read;
        };
        assertFalse("same objects with a new native bind must retire this dispatch", old.guard.current());
        assertFalse(previous.isShowing());
        assertEquals(1, old.canceled);
        old.finish("old binding translation");
        assertFalse(previous.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals("draft original", editor.getText().toString());
        AlertDialog fresh = start(); Call current = calls.get(1);
        old.finish("late old binding translation");
        assertTrue(current.guard.current());
        assertEquals(0, current.canceled);
        assertFalse(fresh.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        current.finish("fresh binding translation");
        fresh.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("fresh binding translation", editor.getText().toString());
        assertEquals(0, input.sends + vm.sends);
    }

    @Test public void groupOwnUnknownPreviewReportAndUnpatchedStatesExposeNoTranslationAction() {
        session.single = false; assertNoAction();
        session.single = true; session.peer = "account-a"; assertNoAction();
        session.peer = ""; assertNoAction();
        session.peer = "0"; assertNoAction();
        session.peer = "peer-a"; session.conversation = ""; assertNoAction();
        session.conversation = "conversation-a"; session.preview = true; assertNoAction();
        session.preview = false; session.report = true; assertNoAction();
        session.report = false; SettingsStatus.commentTranslationEnabled = false; assertNoAction();
        SettingsStatus.commentTranslationEnabled = true; SignedInUser.idForTests = null; assertNoAction();
        assertTrue(calls.isEmpty());
    }

    @Test public void missingDraftHookAvailabilityCannotBorrowTheReceivedOrCommentPatchGate() {
        SettingsStatus.dmTranslationPreviewEnabled = false;
        SettingsStatus.receivedDmTranslationPreviewEnabled = true;
        assertNoAction();
        assertTrue(calls.isEmpty());
        SettingsStatus.dmTranslationPreviewEnabled = true;
        AlertDialog dialog = start(); Call old = calls.get(0);
        SettingsStatus.dmTranslationPreviewEnabled = false;
        assertFalse(old.guard.current());
        old.finish("unavailable hook translation");
        assertEquals(1, old.canceled);
        assertFalse(dialog.isShowing());
        assertEquals("draft original", editor.getText().toString());
        assertEquals(0, input.sends + vm.sends);
    }

    @Test public void deadlineAndDuplicateCallbacksNeverProduceALateOrSecondAnswer() {
        AlertDialog timedOut = start(); Call old = calls.get(0);
        idleFor(DmTranslationPreview.TIMEOUT_MS);
        assertFalse("dispatch must refuse work at the deadline", old.guard.current());
        assertFalse("timeout must dismiss without waiting another poll interval", timedOut.isShowing());
        assertEquals(1, old.canceled);
        old.finish("late translation"); assertEquals("draft original", editor.getText().toString());
        AlertDialog next = start(); Call current = calls.get(1);
        current.finish("first translation"); current.finish("second translation");
        next.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("first translation", editor.getText().toString());
    }

    @Test public void ownerCheckDelayCannotExtendTheTimeoutToTheNextPoll() {
        AlertDialog timedOut = start(); Call old = calls.get(0);
        DmTranslationPreview.IdentityReader original = DmTranslationPreview.identityReader;
        boolean[] delayed = {false};
        DmTranslationPreview.identityReader = (actualVm, actualInput, actualSession, actualAssem, actualEditor) -> {
            if (!delayed[0]) {
                delayed[0] = true;
                ShadowSystemClock.advanceBy(37, TimeUnit.MILLISECONDS);
            }
            return original.read(actualVm, actualInput, actualSession, actualAssem, actualEditor);
        };
        idleFor(DmTranslationPreview.TIMEOUT_MS);
        assertTrue(delayed[0]);
        assertFalse("owner-check time must not postpone timeout cleanup", timedOut.isShowing());
        assertFalse(old.guard.current());
        assertEquals(1, old.canceled);
        assertEquals("draft original", editor.getText().toString());
    }

    @Test public void deadlineReachedDuringFinalOwnerCheckRefusesDispatch() throws ReflectiveOperationException {
        AlertDialog dialog = start(); Call old = calls.get(0);
        // Dialog setup happens after the request's deadline is captured. Use that exact deadline.
        Field owners = DmTranslationPreview.class.getDeclaredField("OWNERS"); owners.setAccessible(true);
        Object owner = ((Map<?, ?>) owners.get(null)).get(vm);
        assertNotNull(owner);
        Field pending = owner.getClass().getDeclaredField("pending"); pending.setAccessible(true);
        Object work = pending.get(owner);
        assertNotNull(work);
        Field deadlineField = work.getClass().getDeclaredField("deadline"); deadlineField.setAccessible(true);
        long deadline = deadlineField.getLong(work);
        long advance = deadline - android.os.SystemClock.elapsedRealtime() - 1;
        assertTrue("dialog setup must leave time to exercise the request boundary", advance >= 0);
        ShadowSystemClock.advanceBy(advance, TimeUnit.MILLISECONDS);
        assertEquals(deadline - 1, android.os.SystemClock.elapsedRealtime());
        assertTrue("dispatch is still allowed one millisecond before timeout", old.guard.current());
        DmTranslationPreview.IdentityReader original = DmTranslationPreview.identityReader;
        int[] reads = {0};
        DmTranslationPreview.identityReader = (actualVm, actualInput, actualSession, actualAssem, actualEditor) -> {
            reads[0]++;
            ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS);
            return original.read(actualVm, actualInput, actualSession, actualAssem, actualEditor);
        };
        assertFalse("native ownership reads cannot carry a dispatch past its deadline", old.guard.current());
        assertEquals(1, reads[0]);
        assertEquals(deadline, android.os.SystemClock.elapsedRealtime());
        old.finish("late translation");
        assertFalse(dialog.isShowing());
        assertEquals(1, old.canceled);
        assertEquals("draft original", editor.getText().toString());
    }

    @Test public void deadlineWithoutMainPollingStillRejectsTheLateResponse() {
        AlertDialog dialog = start(); Call old = calls.get(0);
        ShadowSystemClock.advanceBy(DmTranslationPreview.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertFalse(old.guard.current());
        old.finish("late translation");
        assertFalse("response validation must enforce the deadline before the queued timeout runs", dialog.isShowing());
        assertEquals(1, old.canceled);
        assertEquals("draft original", editor.getText().toString());
        idle();
        assertEquals(1, old.canceled);
    }

    @Test public void detachRebindAndClearCancelOldWorkAndRestoreOnlyOurCallbacks() {
        ActionMode.Callback owned = editor.getCustomSelectionActionModeCallback();
        AlertDialog detached = start(); Call old = calls.get(0);
        root.removeView(editor);
        old.finish("detached answer"); assertFalse(detached.isShowing());
        assertNull(editor.getCustomSelectionActionModeCallback());
        assertNull(editor.getCustomInsertionActionModeCallback());
        root.addView(editor);
        assertNotSame(owned, editor.getCustomSelectionActionModeCallback());
        AlertDialog rebound = start(); Call reboundCall = calls.get(1);
        EditText replacement = new EditText(activity); replacement.setText("replacement draft"); root.addView(replacement);
        input.editor = replacement; assem.LLJLLL = replacement;
        DmTranslationPreview.bindInput(input, replacement); DmTranslationPreview.inputReady(assem);
        reboundCall.finish("old editor answer"); assertFalse(rebound.isShowing());
        assertNull(editor.getCustomSelectionActionModeCallback());
        assertNotNull(replacement.getCustomSelectionActionModeCallback());
        ActionMode.Callback laterNative = new NativeActions();
        replacement.setCustomSelectionActionModeCallback(laterNative);
        DmTranslationPreview.composerCleared(vm);
        assertSame(laterNative, replacement.getCustomSelectionActionModeCallback());
        assertNull(replacement.getCustomInsertionActionModeCallback());
        assertEquals("draft original", editor.getText().toString());
        assertEquals("replacement draft", replacement.getText().toString());
    }

    @Test public void theSameEditorRecycledToAnotherInputRetiresBothOldMenuWrappers() {
        NativeActions nativeActions = new NativeActions();
        DmTranslationPreview.composerCleared(vm);
        editor.setCustomSelectionActionModeCallback(nativeActions);
        editor.setCustomInsertionActionModeCallback(nativeActions);
        DmTranslationPreview.composerCreated(vm, input, session);
        AlertDialog retired = start(); Call old = calls.get(0);
        Input replacementInput = new Input(); replacementInput.editor = editor;
        Session replacementSession = new Session(); replacementSession.peer = "peer-b";
        Composer replacementVm = new Composer(); replacementVm.input = replacementInput; replacementVm.session = replacementSession;
        DmTranslationPreview.bindInput(replacementInput, editor);
        assertSame(nativeActions, editor.getCustomSelectionActionModeCallback());
        assertSame(nativeActions, editor.getCustomInsertionActionModeCallback());
        DmTranslationPreview.inputReady(assem);
        DmTranslationPreview.composerCreated(replacementVm, replacementInput, replacementSession);
        old.finish("old input answer"); assertFalse(old.guard.current()); assertFalse(retired.isShowing());
        assertNotSame(nativeActions, editor.getCustomSelectionActionModeCallback());
        start(); assertEquals(2, calls.size());
        DmTranslationPreview.composerCleared(vm);
        assertTrue(calls.get(1).guard.current());
        DmTranslationPreview.composerCleared(replacementVm);
        assertSame(nativeActions, editor.getCustomSelectionActionModeCallback());
        assertSame(nativeActions, editor.getCustomInsertionActionModeCallback());
    }

    @Test public void aRepeatedNativeViewBindInvalidatesAReadyPreviewEvenForTheSameEditor() {
        AlertDialog retired = start(); Call old = calls.get(0); old.finish("old binding answer");
        Assem replacement = new Assem(); replacement.LLJLLL = editor;
        DmTranslationPreview.inputReady(replacement);
        assertFalse(retired.isShowing()); assertFalse(old.guard.current());
        assem = replacement;
        AlertDialog current = start(); assertEquals(2, calls.size());
        assertTrue(current.isShowing());
    }

    @Test public void constructorBeforeEditorAndNewComposerForSameEditorCannotShareOldPreview() {
        DmTranslationPreview.resetForTests();
        DmTranslationPreview.identityReader = (actualVm, actualInput, actualSession, actualAssem, actualEditor) ->
                actualInput == input && actualSession == session && actualAssem == assem && actualEditor == editor
                        ? new DmTranslationPreview.Identity(session.conversation, session.peer) : null;
        DmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            Call call = new Call(account, source, guard, callback); calls.add(call); return call;
        };
        DmTranslationPreview.composerCreated(vm, input, session);
        assertNull(editor.getCustomSelectionActionModeCallback());
        DmTranslationPreview.bindInput(input, editor); DmTranslationPreview.inputReady(assem);
        AlertDialog oldDialog = start(); Call old = calls.get(0);
        Composer replacement = new Composer(); replacement.input = input; replacement.session = session;
        DmTranslationPreview.composerCreated(replacement, input, session);
        old.finish("retired composer answer"); assertFalse(oldDialog.isShowing()); assertFalse(old.guard.current());
        assertNotNull(editor.getCustomSelectionActionModeCallback());
    }

    @Test public void nativeFiltersCannotSilentlyTruncateAChosenTranslation() {
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        AlertDialog dialog = start(); calls.get(0).finish("a translation longer than twenty characters");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("draft original", editor.getText().toString());
        assertFalse(dialog.isShowing());
        assertEquals("Couldn't translate this draft. Try again in a moment.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void aStatefulNativeFilterIsRolledBackExactlyAndRemainsInstalled() {
        int[] invocations = {0};
        InputFilter stateful = (source, start, end, destination, dstart, dend) -> {
            invocations[0]++;
            assertSame(editor.getText(), destination);
            return source.subSequence(start, Math.min(end, start + 3));
        };
        editor.setFilters(new InputFilter[]{stateful});
        editor.setSelection(2, 7);
        AlertDialog dialog = start(); calls.get(0).finish("translated draft");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("draft original", editor.getText().toString());
        assertEquals(2, editor.getSelectionStart()); assertEquals(7, editor.getSelectionEnd());
        assertSame(stateful, editor.getFilters()[0]);
        assertEquals(1, invocations[0]);
        assertEquals("Couldn't translate this draft. Try again in a moment.", ShadowToast.getTextOfLatestToast());
        assertFalse(dialog.isShowing());
    }

    @Test public void nativeFiltersRunOnlyOnceAgainstTheActualEditable() {
        int[] invocations = {0};
        editor.setFilters(new InputFilter[]{(source, start, end, destination, dstart, dend) -> {
            assertSame(editor.getText(), destination);
            return invocations[0]++ == 0 ? null : source.subSequence(start, Math.min(end, start + 3));
        }});
        AlertDialog dialog = start(); calls.get(0).finish("translated draft");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("translated draft", editor.getText().toString());
        assertEquals(1, invocations[0]);
        assertFalse(dialog.isShowing());
    }

    @Test public void addingAMentionSpanAfterTheResponseCannotDropItThroughUse() {
        AlertDialog dialog = start(); calls.get(0).finish("translated draft");
        Object mention = new StyleSpan(1);
        editor.getText().setSpan(mention, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("draft original", editor.getText().toString());
        assertEquals(0, editor.getText().getSpanStart(mention));
        assertFalse(dialog.isShowing());
    }

    @Test public void aNativeFilterFailureCannotCrashThePreviewOrChangeTheOriginalDraft() {
        editor.setFilters(new InputFilter[]{(source, start, end, destination, dstart, dend) -> {
            throw new IllegalStateException("Native filter unavailable");
        }});
        AlertDialog dialog = start(); calls.get(0).finish("translated draft");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("draft original", editor.getText().toString());
        assertEquals("Couldn't translate this draft. Try again in a moment.", ShadowToast.getTextOfLatestToast());
        assertFalse(dialog.isShowing());
    }

    @Test public void richOrEmptyDraftsAreRefusedInsteadOfDroppingNativeMetadata() {
        editor.setText("  "); assertNoAction();
        SpannableString styled = new SpannableString("draft original");
        styled.setSpan(new StyleSpan(1), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        editor.setText(styled); assertNoAction();
        assertFalse(DmTranslationPreview.plain(styled));
        assertFalse(DmTranslationPreview.plain(null));
        assertFalse(DmTranslationPreview.plain(new String(new char[DmTranslationPreview.MAX_TEXT + 1]).replace('\0', 'x')));
        assertTrue(calls.isEmpty());
    }

    @Test public void composingTextCanBePreviewedAndUsedWithoutSending() {
        editor.requestFocus();
        InputConnection connection = editor.onCreateInputConnection(new EditorInfo());
        assertNotNull(connection);
        editor.selectAll();
        SpannableString composing = new SpannableString("draft original");
        composing.setSpan(new UnderlineSpan(), 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertTrue(connection.setComposingText(composing, 1));
        assertEquals("draft original", editor.getText().toString());
        AlertDialog dialog = start();
        calls.get(0).finish("translated draft");
        idleFor(150);
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("translated draft", editor.getText().toString());
        assertEquals(0, input.sends + vm.sends);
    }

    @Test public void spellingAnnotationsDoNotRetireAnUnchangedPreview() throws ReflectiveOperationException {
        AlertDialog dialog = start();
        editor.getText().setSpan(new SuggestionSpan(activity, new String[]{"draft"}, SuggestionSpan.FLAG_MISSPELLED),
                0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Object checking = Class.forName("android.text.style.SpellCheckSpan").getConstructor().newInstance();
        editor.getText().setSpan(checking, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        calls.get(0).finish("translated draft");
        idleFor(150);
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("translated draft", editor.getText().toString());
        assertEquals(0, input.sends + vm.sends);
    }

    @Test public void composingFlagsCannotMakeLinksOrNativeMetadataPlain() {
        for (Object metadata : new Object[]{new URLSpan("https://example.org"), new Object()}) {
            SpannableString rich = new SpannableString("draft original");
            rich.setSpan(metadata, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
            assertFalse(DmTranslationPreview.plain(rich));
            editor.setText(rich);
            assertNoAction();
        }
        assertTrue(calls.isEmpty());
    }

    @Test public void synchronousFailureAndCancelBeforeHandleReturnDoNotOrphanARequest() {
        int[] cancels = {0};
        DmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            callback.finished(null); return () -> cancels[0]++;
        };
        AlertDialog dialog = start(); assertFalse(dialog.isShowing()); assertEquals(1, cancels[0]);
        DmTranslationPreview.transport = (loader, account, source, guard, callback) -> {
            editor.setText("typed while dispatching"); return () -> cancels[0]++;
        };
        dialog = start(); assertFalse(dialog.isShowing()); assertEquals(2, cancels[0]);
        assertEquals("typed while dispatching", editor.getText().toString());
    }

    private AlertDialog start() {
        ActionMode.Callback callback = editor.getCustomSelectionActionModeCallback();
        assertNotNull(callback);
        Mode mode = new Mode(callback);
        assertTrue(callback.onCreateActionMode(mode, mode.menu));
        MenuItem action = mode.menu.findItem(DmTranslationPreview.ACTION_ID);
        assertNotNull("owned plain draft should offer translation", action);
        assertTrue(callback.onActionItemClicked(mode, action));
        assertEquals(1, mode.finished);
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    private void assertNoAction() {
        ActionMode.Callback callback = editor.getCustomSelectionActionModeCallback();
        assertNotNull(callback);
        Mode mode = new Mode(callback); callback.onCreateActionMode(mode, mode.menu);
        assertNull(mode.menu.findItem(DmTranslationPreview.ACTION_ID));
    }
    private void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void idleFor(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS); }

    private final class Mode extends ActionMode {
        final Menu menu = new PopupMenu(activity, editor).getMenu();
        final ActionMode.Callback callback;
        int finished;
        Mode(ActionMode.Callback callback) { this.callback = callback; }
        @Override public void finish() { finished++; callback.onDestroyActionMode(this); }
        @Override public void invalidate() { callback.onPrepareActionMode(this, menu); }
        @Override public void setTitle(CharSequence title) {}
        @Override public void setTitle(int title) {}
        @Override public void setSubtitle(CharSequence subtitle) {}
        @Override public void setSubtitle(int subtitle) {}
        @Override public void setCustomView(View view) {}
        @Override public Menu getMenu() { return menu; }
        @Override public CharSequence getTitle() { return ""; }
        @Override public CharSequence getSubtitle() { return ""; }
        @Override public View getCustomView() { return null; }
        @Override public MenuInflater getMenuInflater() { return activity.getMenuInflater(); }
    }

    private static final class NativeActions extends ActionMode.Callback2 {
        boolean create = true;
        int clicks, destroyed;
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { if (create) menu.add(0, 71, 0, "Native action"); return create; }
        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return true; }
        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) { clicks++; return true; }
        @Override public void onDestroyActionMode(ActionMode mode) { destroyed++; }
        @Override public void onGetContentRect(ActionMode mode, View view, Rect rect) { rect.set(1, 2, 3, 4); }
    }
}
