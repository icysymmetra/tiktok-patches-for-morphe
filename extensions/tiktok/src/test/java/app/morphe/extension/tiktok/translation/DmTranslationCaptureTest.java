/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.text.Editable;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;

import java.util.Collections;
import java.util.List;

/** Real preview UI with synthetic ownership and transport. This does not qualify native DM service access. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 29, qualifiers = "en-rUS-w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DmTranslationCaptureTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String SOURCE = "Could we meet for coffee tomorrow?";
    private static final String TRANSLATED = "¿Podemos tomar un café mañana?";
    private boolean comments, outgoing, incoming;
    private Call call;

    @Before public void setUp() {
        comments = SettingsStatus.commentTranslationEnabled;
        outgoing = SettingsStatus.dmTranslationPreviewEnabled;
        incoming = SettingsStatus.receivedDmTranslationPreviewEnabled;
        SettingsStatus.commentTranslationEnabled = true;
        SettingsStatus.dmTranslationPreviewEnabled = true;
        SettingsStatus.receivedDmTranslationPreviewEnabled = true;
        BaseSettings.PAUSED.save(false); BaseSettings.SAFE_MODE.save(false); PausedProcess.set(false);
        SignedInUser.idForTests = "capture-account";
        DmTranslationPreview.resetForTests(); ReceivedDmTranslationPreview.resetForTests();
        Settings.DM_TRANSLATION_PREVIEW.save(true); Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(true);
        DmTranslationPreview.Transport ownedTransport = (loader, account, source, guard, completion) -> {
            assertNull("opening a preview dispatched twice", call);
            assertEquals("capture-account", account); assertEquals(SOURCE, source); assertTrue(guard.current());
            call = new Call(guard, completion); return call;
        };
        DmTranslationPreview.transport = ownedTransport;
        ReceivedDmTranslationPreview.transport = ownedTransport;
    }

    @After public void tearDown() {
        DmTranslationPreview.resetForTests(); ReceivedDmTranslationPreview.resetForTests();
        Settings.DM_TRANSLATION_PREVIEW.save(false); Settings.RECEIVED_DM_TRANSLATION_PREVIEW.save(false);
        SettingsStatus.commentTranslationEnabled = comments;
        SettingsStatus.dmTranslationPreviewEnabled = outgoing;
        SettingsStatus.receivedDmTranslationPreviewEnabled = incoming;
        SignedInUser.idForTests = null; PausedProcess.set(false);
        Utils.setIsDarkModeEnabled(true); idle();
    }

    @Test public void outgoingPreviewRendersAndCancelPreservesTheDraftInDarkMode() throws Exception { outgoing(true); }
    @Test @Config(qualifiers = "en-rUS-w480dp-h960dp-notnight-mdpi")
    public void outgoingPreviewRendersAndCancelPreservesTheDraftInLightMode() throws Exception { outgoing(false); }
    @Test public void receivedPreviewTogglesAndClosePreservesTheRowInDarkMode() throws Exception { incoming(true); }
    @Test @Config(qualifiers = "en-rUS-w480dp-h960dp-notnight-mdpi")
    public void receivedPreviewTogglesAndClosePreservesTheRowInLightMode() throws Exception { incoming(false); }

    private void outgoing(boolean dark) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get(); FrameLayout root = root(activity, dark);
            EditText editor = new EditText(activity); editor.setText(SOURCE); root.addView(editor);
            Editable original = editor.getText();
            Input input = new Input(); input.editor = editor;
            Composer composer = new Composer(); composer.input = input;
            InputAssem assem = new InputAssem(); assem.LLJLLL = editor;
            DmTranslationPreview.identityReader = (vm, ability, session, render, currentEditor) ->
                    vm == composer && ability == input && session == composer.session && render == assem
                            && input.editor == currentEditor && assem.LLJLLL == currentEditor
                            ? new DmTranslationPreview.Identity(composer.session.conversation, composer.session.peer) : null;
            DmTranslationPreview.bindInput(input, editor); DmTranslationPreview.inputReady(assem);
            DmTranslationPreview.composerCreated(composer, input, composer.session);
            ActionMode.Callback callback = editor.getCustomSelectionActionModeCallback(); assertNotNull(callback);
            Mode mode = new Mode(activity, editor, callback);
            assertTrue(callback.onCreateActionMode(mode, mode.menu));
            assertNotNull(mode.menu.findItem(DmTranslationPreview.ACTION_ID));
            assertTrue(callback.onActionItemClicked(mode, mode.menu.findItem(DmTranslationPreview.ACTION_ID)));
            assertEquals(1, mode.finished);
            AlertDialog dialog = latest();
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertSame(original, editor.getText()); assertEquals(SOURCE, editor.getText().toString());
            capture(dialog, dark, "dm-draft-loading", "Translating…");
            call.completion.finished(TRANSLATED); idle();
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertEquals("Use translation", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertEquals("Cancel", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            assertSame(original, editor.getText()); assertEquals(SOURCE, editor.getText().toString());
            capture(dialog, dark, "dm-draft-preview", TRANSLATED);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertFalse(dialog.isShowing()); assertFalse(call.guard.current()); assertEquals(1, call.canceled);
            call.completion.finished("Late translation after cancel"); idle();
            assertSame(original, editor.getText()); assertEquals(SOURCE, editor.getText().toString());
        }
    }

    private void incoming(boolean dark) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get(); FrameLayout root = root(activity, dark);
            TextView text = new TextView(activity); text.setText(SOURCE); root.addView(text);
            CharSequence original = text.getText();
            ReceiveAssem assem = new ReceiveAssem(); assem.text = text;
            ReceivedDmTranslationPreview.rowReader = (render, model) -> render == assem && model == assem.model
                    ? new ReceivedDmTranslationPreview.Row(assem.model, assem.model.message, assem.session,
                            assem.text, "capture-message", assem.session.conversation, assem.session.peer,
                            assem.model.message.source, assem.model.message.content) : null;
            Object menuOwner = new Object();
            ReceivedDmTranslationPreview.menuReader = (menu, message) -> menu == menuOwner && message == assem.model.message
                    ? new ReceivedDmTranslationPreview.MenuOwner(assem, assem.model) : null;
            ReceivedDmTranslationPreview.itemFactory = (context, action) -> new ReceivedAction(action);
            ReceivedDmTranslationPreview.bound(assem, assem.model);
            Object stock = new Object(); List<?> nativeItems = Collections.singletonList(stock);
            List<?> menu = ReceivedDmTranslationPreview.appendAction(nativeItems, menuOwner, assem.model.message);
            assertEquals(1, nativeItems.size()); assertEquals(2, menu.size()); assertSame(stock, menu.get(0));
            ((ReceivedAction) menu.get(1)).click.run();
            AlertDialog dialog = latest();
            assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            call.completion.finished(TRANSLATED); idle();
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            assertEquals("Close", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            capture(dialog, dark, "dm-received-translation", "Translated message\n\n" + TRANSLATED);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals("Show translation", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            capture(dialog, dark, "dm-received-original", "Original message\n\n" + SOURCE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertEquals("Show original", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertNotNull(findText(dialog.getWindow().getDecorView(), "Translated message\n\n" + TRANSLATED));
            assertSame(original, text.getText()); assertEquals(SOURCE, text.getText().toString());
            assertEquals(SOURCE, assem.model.message.source); assertEquals("owned native payload", assem.model.message.content);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertFalse(dialog.isShowing()); assertFalse(call.guard.current()); assertEquals(1, call.canceled);
            call.completion.finished("Late translation after close"); idle();
            assertSame(original, text.getText()); assertEquals(SOURCE, text.getText().toString());
            assertEquals(SOURCE, assem.model.message.source); assertEquals("owned native payload", assem.model.message.content);
        }
    }

    private static FrameLayout root(Activity activity, boolean dark) {
        Utils.setContext(activity); SettingsUi.syncDarkMode(activity); assertEquals(dark, SettingsUi.isDarkMode());
        FrameLayout root = new FrameLayout(activity); activity.setContentView(root); idle(); return root;
    }
    private static AlertDialog latest() {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertNotNull(dialog); assertTrue(dialog.isShowing()); return dialog;
    }
    private static void capture(AlertDialog dialog, boolean dark, String name, String message) throws Exception {
        View decor = dialog.getWindow().getDecorView();
        assertNotNull(findText(decor, "Translation preview")); assertNotNull(findText(decor, message));
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST));
        int height = decor.getMeasuredHeight(); assertTrue(height > 0); assertTrue(height <= 960);
        decor.layout(0, 0, 480, height); idle();
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getHeight() > 0);
        UiCapture.save(decor, "dialogs/" + (dark ? "dark" : "light") + "/" + name + ".png", 480, height);
    }
    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private static final class Session { final String conversation = "capture-conversation", peer = "capture-peer"; }
    private static final class Input { EditText editor; }
    private static final class Composer { Input input; final Session session = new Session(); }
    private static final class InputAssem { EditText LLJLLL; }
    private static final class Message { final String source = SOURCE, content = "owned native payload"; }
    private static final class Model { final Message message = new Message(); }
    private static final class ReceiveAssem { final Model model = new Model(); final Session session = new Session(); TextView text; }
    private static final class ReceivedAction { final Runnable click; ReceivedAction(Runnable click) { this.click = click; } }
    private static final class Call implements DmTranslationPreview.Request {
        final DmTranslationPreview.DispatchGuard guard; final DmTranslationPreview.Completion completion; int canceled;
        Call(DmTranslationPreview.DispatchGuard guard, DmTranslationPreview.Completion completion) { this.guard = guard; this.completion = completion; }
        @Override public void cancel() { canceled++; }
    }
    private static final class Mode extends ActionMode {
        final Activity activity; final Menu menu; final ActionMode.Callback callback; int finished;
        Mode(Activity activity, View anchor, ActionMode.Callback callback) { this.activity = activity; menu = new PopupMenu(activity, anchor).getMenu(); this.callback = callback; }
        @Override public void finish() { finished++; callback.onDestroyActionMode(this); }
        @Override public void invalidate() { callback.onPrepareActionMode(this, menu); }
        @Override public void setTitle(CharSequence text) {}
        @Override public void setTitle(int id) {}
        @Override public void setSubtitle(CharSequence text) {}
        @Override public void setSubtitle(int id) {}
        @Override public void setCustomView(View view) {}
        @Override public Menu getMenu() { return menu; }
        @Override public CharSequence getTitle() { return ""; }
        @Override public CharSequence getSubtitle() { return ""; }
        @Override public View getCustomView() { return null; }
        @Override public MenuInflater getMenuInflater() { return activity.getMenuInflater(); }
    }
}
