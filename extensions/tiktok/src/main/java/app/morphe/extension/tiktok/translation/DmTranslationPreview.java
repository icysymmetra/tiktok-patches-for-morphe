/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Selection;
import android.text.Spanned;
import android.text.SpanWatcher;
import android.text.TextWatcher;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.text.style.SuggestionSpan;
import android.text.style.URLSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** A draft-only action. Nothing here creates a message, presses Send or changes native message data. */
@SuppressWarnings("unused")
public final class DmTranslationPreview {
    static final int MAX_TEXT = 16_384, ACTION_ID = 0x6f331509;
    static final long TIMEOUT_MS = 15_000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, Binding> INPUTS = new WeakHashMap<>();
    private static final Map<Object, Owner> OWNERS = new WeakHashMap<>();
    private static final AtomicLong ACCOUNT_EPOCH = new AtomicLong(), GATE_EPOCH = new AtomicLong();
    private static SharedPreferences preferences;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFERENCES = (store, key) -> {
        if (Settings.DM_TRANSLATION_PREVIEW.key.equals(key) || BaseSettings.PAUSED.key.equals(key)
                || BaseSettings.SAFE_MODE.key.equals(key)) {
            long retired = GATE_EPOCH.incrementAndGet();
            MAIN.post(() -> {
                for (Owner owner : new ArrayList<>(OWNERS.values()))
                    if (owner.pending != null && owner.pending.gateEpoch < retired) owner.cancel();
            });
        }
    };

    interface Request { void cancel(); }
    interface DispatchGuard { boolean current(); }
    interface Completion { void finished(String translation); }
    interface Transport { Request translate(ClassLoader loader, String account, String source,
            DispatchGuard guard, Completion completion); }
    interface IdentityReader { Identity read(Object vm, Object input, Object session, Object assem, EditText editor)
            throws ReflectiveOperationException; }
    static Transport transport = new NativeDmTranslation();
    static IdentityReader identityReader = DmTranslationPreview::nativeIdentity;

    static final class Identity {
        final String conversation, peer;
        Identity(String conversation, String peer) { this.conversation = conversation; this.peer = peer; }
        boolean same(Identity other) { return other != null && conversation.equals(other.conversation) && peer.equals(other.peer); }
    }

    private static final class Binding {
        final WeakReference<Object> input;
        final WeakReference<EditText> editor;
        WeakReference<Object> assem = new WeakReference<>(null);
        Binding(Object input, EditText editor) { this.input = new WeakReference<>(input); this.editor = new WeakReference<>(editor); }
    }

    /** Captured immediately after the real InputFieldVM's cached editor is assigned. */
    public static void bindInput(Object input, View view) {
        if (!(view instanceof EditText) || input == null) return;
        main(() -> {
            for (Owner owner : new ArrayList<>(OWNERS.values())) {
                if (owner.input.get() == input) owner.uninstall();
                else if (owner.editor() == view) owner.close();
            }
            if (INPUTS.size() >= 64 && !INPUTS.containsKey(input)) return;
            INPUTS.put(input, new Binding(input, (EditText) view));
        });
    }

    /** Runs only after InputFieldAssem finished installing its own editor behavior. */
    public static void inputReady(Object assem) {
        main(() -> {
            try {
                Field cached = assem.getClass().getDeclaredField("LLJLLL");
                cached.setAccessible(true);
                Object view = cached.get(assem);
                for (Binding binding : INPUTS.values()) if (binding.editor.get() == view) {
                    for (Owner owner : OWNERS.values()) if (owner.binding == binding) owner.uninstall();
                    binding.assem = new WeakReference<>(assem);
                    for (Owner owner : OWNERS.values()) if (owner.input.get() == binding.input.get()) owner.install(binding);
                }
            } catch (ReflectiveOperationException | RuntimeException missing) {
                // An unrecognized editor is left with its complete native action mode.
            }
        });
    }

    /** The actual native constructor provides the input ability and explicit chat session. */
    public static void composerCreated(Object vm, Object input, Object session) {
        main(() -> {
            if (vm == null || input == null || session == null) return;
            Owner previous = OWNERS.remove(vm);
            if (previous != null) previous.close();
            if (OWNERS.size() >= 64) return;
            long epoch = ACCOUNT_EPOCH.get();
            String account = SignedInUser.id();
            if (epoch != ACCOUNT_EPOCH.get()) return;
            Owner owner = new Owner(vm, input, session, account, epoch);
            OWNERS.put(vm, owner);
            Binding binding = INPUTS.get(input);
            if (binding != null && binding.assem.get() != null) owner.install(binding);
        });
    }

    public static void composerCleared(Object vm) {
        main(() -> { Owner owner = OWNERS.remove(vm); if (owner != null) owner.close(); });
    }

    /** Retire this preview even when the native account UID returns to its previous value. */
    public static void accountReset() {
        retireAccount().run();
    }

    static Runnable retireAccount() {
        long retired = ACCOUNT_EPOCH.incrementAndGet();
        return () -> main(() -> {
            for (Map.Entry<Object, Owner> entry : new ArrayList<>(OWNERS.entrySet())) {
                Owner owner = entry.getValue();
                if (owner.accountEpoch < retired) {
                    owner.close();
                    if (OWNERS.get(entry.getKey()) == owner) OWNERS.remove(entry.getKey());
                }
            }
        });
    }

    private static boolean enabled() {
        return Utils.getContext() != null && SettingsStatus.commentTranslationEnabled
                && SettingsStatus.dmTranslationPreviewEnabled
                && Settings.DM_TRANSLATION_PREVIEW.get() && !Setting.isPaused() && !BaseSettings.SAFE_MODE.get();
    }

    private static void listen() {
        if (preferences != null || Utils.getContext() == null) return;
        preferences = Utils.getContext().getSharedPreferences(Setting.preferences.name, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(PREFERENCES);
    }

    private static final class Owner implements TextWatcher, View.OnAttachStateChangeListener {
        final WeakReference<Object> vm, input, session;
        final String account;
        final long accountEpoch;
        Binding binding;
        EditorActions selection, insertion;
        Pending pending;
        long revision;
        boolean closed, applying;

        Owner(Object vm, Object input, Object session, String account, long accountEpoch) {
            this.vm = new WeakReference<>(vm); this.input = new WeakReference<>(input);
            this.session = new WeakReference<>(session); this.account = account;
            this.accountEpoch = accountEpoch;
        }

        void install(Binding found) {
            if (closed || binding == found) return;
            if (binding != null) uninstall();
            EditText editor = found.editor.get();
            if (editor == null) return;
            for (Owner other : new ArrayList<>(OWNERS.values())) if (other != this && other.editor() == editor) other.close();
            binding = found;
            editor.addOnAttachStateChangeListener(this);
            if (editor.isAttachedToWindow()) activate(editor);
            listen();
        }

        void activate(EditText editor) {
            if (closed || selection != null || accountEpoch != ACCOUNT_EPOCH.get()) return;
            selection = new EditorActions(this, editor.getCustomSelectionActionModeCallback());
            insertion = new EditorActions(this, editor.getCustomInsertionActionModeCallback());
            editor.setCustomSelectionActionModeCallback(selection);
            editor.setCustomInsertionActionModeCallback(insertion);
            editor.addTextChangedListener(this);
        }

        void restoreActions(EditText editor) {
            editor.removeTextChangedListener(this);
            if (selection != null && editor.getCustomSelectionActionModeCallback() == selection)
                editor.setCustomSelectionActionModeCallback(selection.nativeCallback);
            if (insertion != null && editor.getCustomInsertionActionModeCallback() == insertion)
                editor.setCustomInsertionActionModeCallback(insertion.nativeCallback);
            selection = insertion = null;
        }

        Identity identity() {
            EditText editor = editor();
            if (closed || accountEpoch != ACCOUNT_EPOCH.get() || account == null || account.isEmpty() || "0".equals(account) || !account.equals(SignedInUser.id())
                    || editor == null || !editor.isAttachedToWindow() || !editor.isShown() || !editor.isEnabled()
                    || binding.assem.get() == null) return null;
            try {
                Identity read = identityReader.read(vm.get(), input.get(), session.get(), binding.assem.get(), editor);
                return accountEpoch != ACCOUNT_EPOCH.get() || read == null || read.peer == null || read.peer.isEmpty() || "0".equals(read.peer) || account.equals(read.peer)
                        || read.conversation == null || read.conversation.isEmpty() ? null : read;
            } catch (ReflectiveOperationException | RuntimeException missing) { return null; }
        }

        EditText editor() { return binding == null ? null : binding.editor.get(); }
        boolean ready() { EditText editor = editor(); return enabled() && identity() != null && editor != null && plain(editor.getText()); }

        void preview() {
            cancel();
            if (!ready()) return;
            EditText editor = editor();
            Identity identity = identity();
            if (editor == null || identity == null) return;
            Pending work = new Pending(this, identity, editor.getText().toString());
            pending = work;
            try {
                work.dialog = new AlertDialog.Builder(editor.getContext())
                        .setTitle(L10n.t(editor.getContext(), "Translation preview"))
                        .setMessage(L10n.t(editor.getContext(), "Translating…"))
                        .setPositiveButton(L10n.t(editor.getContext(), "Use translation"), null)
                        .setNegativeButton(L10n.t(editor.getContext(), "Cancel"), null).create();
                work.dialog.setOnDismissListener(dialog -> { if (pending == work) cancel(); });
                work.dialog.show();
                SettingsUi.styleFramedDialog(work.dialog);
                work.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                work.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    try { work.apply(); }
                    catch (RuntimeException unavailable) { work.fail(); }
                });
                work.schedule();
                Request request = transport.translate(editor.getContext().getClassLoader(), account, work.source,
                        work::dispatchable, work::finished);
                if (pending != work || work.complete) { if (request != null) request.cancel(); }
                else if (request == null) work.fail();
                else work.request = request;
            } catch (RuntimeException unavailable) { work.fail(); }
        }

        void cancel() {
            Pending work = pending;
            pending = null;
            if (work == null) return;
            MAIN.removeCallbacks(work);
            if (work.request != null) work.request.cancel();
            if (work.dialog != null && work.dialog.isShowing()) work.dialog.dismiss();
        }

        void close() {
            if (closed) return;
            closed = true;
            uninstall();
        }

        void uninstall() {
            cancel();
            EditText editor = editor();
            if (editor != null) {
                restoreActions(editor);
                editor.removeOnAttachStateChangeListener(this);
            }
            binding = null;
            selection = insertion = null;
            revision++;
        }

        @Override public void onTextChanged(CharSequence value, int start, int before, int count) { revision++; if (!applying) cancel(); }
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
        @Override public void afterTextChanged(Editable value) {}
        @Override public void onViewAttachedToWindow(View view) { if (editor() == view) activate((EditText) view); }
        @Override public void onViewDetachedFromWindow(View view) {
            if (editor() == view) { revision++; cancel(); restoreActions((EditText) view); }
        }
    }

    private static final class Pending implements Runnable {
        final Owner owner;
        final Identity identity;
        final String source;
        final long revision, accountEpoch = ACCOUNT_EPOCH.get(), gateEpoch = GATE_EPOCH.get();
        final long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        Request request;
        AlertDialog dialog;
        String translation;
        boolean complete;

        Pending(Owner owner, Identity identity, String source) { this.owner = owner; this.identity = identity; this.source = source; revision = owner.revision; }
        boolean current() {
            EditText editor = owner.editor();
            return owner.pending == this && enabled() && owner.revision == revision && accountEpoch == ACCOUNT_EPOCH.get()
                    && gateEpoch == GATE_EPOCH.get() && identity.same(owner.identity()) && editor != null
                    && plain(editor.getText()) && source.equals(editor.getText().toString())
                    && owner.pending == this && editor == owner.editor() && enabled() && owner.revision == revision
                    && accountEpoch == ACCOUNT_EPOCH.get() && gateEpoch == GATE_EPOCH.get();
        }
        boolean dispatchable() { return !complete && current() && SystemClock.elapsedRealtime() < deadline; }
        @Override public void run() {
            if (!current()) { if (owner.pending == this) owner.cancel(); return; }
            if (!complete && SystemClock.elapsedRealtime() >= deadline) { fail(); return; }
            schedule();
        }
        void schedule() {
            MAIN.postDelayed(this, complete ? 100 : Math.min(100, Math.max(0, deadline - SystemClock.elapsedRealtime())));
        }
        void finished(String result) {
            if (!current()) { if (owner.pending == this) owner.cancel(); return; }
            if (complete) return;
            if (SystemClock.elapsedRealtime() >= deadline || result == null || result.trim().isEmpty() || result.length() > MAX_TEXT) { fail(); return; }
            complete = true;
            translation = result;
            dialog.setMessage(result);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        }
        void fail() {
            if (owner.pending != this) return;
            if (current()) Utils.showToastShort(L10n.t(owner.editor().getContext(), "Couldn't translate this draft. Try again in a moment."));
            owner.cancel();
        }
        void apply() {
            if (!complete || translation == null || !current()) { if (owner.pending == this) owner.cancel(); return; }
            EditText editor = owner.editor();
            Editable editable = editor.getText();
            if (!current()) { if (owner.pending == this) owner.cancel(); return; }
            long before = owner.revision;
            int start = editor.getSelectionStart(), end = editor.getSelectionEnd();
            owner.applying = true;
            try {
                editable.replace(0, editable.length(), translation);
                if (!translation.equals(editable.toString())) {
                    // A stateful native filter changed its answer. Never overwrite a separate reentrant edit.
                    if (owner.pending == this && owner.revision == before + 1 && editor.getText() == editable && identity.same(owner.identity())) {
                        InputFilter[] retained = editable.getFilters();
                        try {
                            // Rollback must not pass through the stateful filter that changed its answer.
                            editable.setFilters(new InputFilter[0]);
                            editable.replace(0, editable.length(), source);
                        } finally { editable.setFilters(retained); }
                        Selection.setSelection(editable, Math.max(0, Math.min(start, editable.length())), Math.max(0, Math.min(end, editable.length())));
                        Utils.showToastShort(L10n.t(editor.getContext(), "Couldn't translate this draft. Try again in a moment."));
                    }
                    fail();
                    return;
                }
                editor.setSelection(editable.length());
                owner.cancel();
            } finally { owner.applying = false; }
        }
    }

    private static final class EditorActions extends ActionMode.Callback2 {
        final Owner owner;
        final ActionMode.Callback nativeCallback;
        WeakReference<MenuItem> ownItem = new WeakReference<>(null);
        EditorActions(Owner owner, ActionMode.Callback nativeCallback) { this.owner = owner; this.nativeCallback = nativeCallback; }
        boolean update(Menu menu) {
            MenuItem existing = menu.findItem(ACTION_ID);
            if (!owner.ready()) {
                if (existing != null && existing == ownItem.get()) { menu.removeItem(ACTION_ID); ownItem.clear(); return true; }
                return false;
            }
            if (existing != null) return false;
            MenuItem item = menu.add(0, ACTION_ID, Menu.NONE, L10n.t(owner.editor().getContext(), "Translate draft"));
            ownItem = new WeakReference<>(item);
            return true;
        }
        @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            if (nativeCallback != null && !nativeCallback.onCreateActionMode(mode, menu)) return false;
            update(menu);
            return true;
        }
        @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            boolean changed = nativeCallback != null && nativeCallback.onPrepareActionMode(mode, menu);
            return update(menu) || changed;
        }
        @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item != null && item == ownItem.get()) { owner.preview(); mode.finish(); return true; }
            return nativeCallback != null && nativeCallback.onActionItemClicked(mode, item);
        }
        @Override public void onDestroyActionMode(ActionMode mode) { if (nativeCallback != null) nativeCallback.onDestroyActionMode(mode); ownItem.clear(); }
        @Override public void onGetContentRect(ActionMode mode, View view, Rect rect) {
            if (nativeCallback instanceof ActionMode.Callback2) ((ActionMode.Callback2) nativeCallback).onGetContentRect(mode, view, rect);
            else super.onGetContentRect(mode, view, rect);
        }
    }

    static boolean plain(CharSequence value) {
        if (value == null || value.length() == 0 || value.length() > MAX_TEXT || value.toString().trim().isEmpty()) return false;
        if (value instanceof Spanned) for (Object span : ((Spanned) value).getSpans(0, value.length(), Object.class)) {
            if (span == Selection.SELECTION_START || span == Selection.SELECTION_END
                    || span instanceof TextWatcher || span instanceof SpanWatcher || span instanceof SuggestionSpan) continue;
            String type = span.getClass().getName();
            // SpellCheckSpan and the input connection's composing marker are hidden framework types.
            if (type.equals("android.text.style.SpellCheckSpan")) continue;
            if ((((Spanned) value).getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0
                    && (type.equals("android.view.inputmethod.ComposingText")
                    || span instanceof CharacterStyle && type.startsWith("android.text.style.")
                    && !(span instanceof ReplacementSpan) && !(span instanceof URLSpan))) continue;
            return false;
        }
        return true;
    }

    private static Identity nativeIdentity(Object vm, Object input, Object session, Object assem, EditText editor) throws ReflectiveOperationException {
        if (vm == null || input == null || session == null || assem == null
                || !vm.getClass().getName().equals("com.ss.android.ugc.aweme.im.sdk.chat.viewmodel.SingleChatInputViewModel")
                || !input.getClass().getName().equals("com.ss.android.ugc.aweme.im.chatroom.input.components.inputfield.InputFieldVM")
                || !assem.getClass().getName().equals("com.ss.android.ugc.aweme.im.chatroom.input.components.inputfield.InputFieldAssem")
                || !session.getClass().getName().equals("X.0dli")) return null;
        ClassLoader loader = vm.getClass().getClassLoader();
        if (NativeDmTranslation.field(vm.getClass(), "LLJJJ", NativeDmTranslation.type(loader,
                "com.ss.android.ugc.aweme.im.chatroom.input.components.inputfield.IInputFieldAbility")).get(vm) != input
                || NativeDmTranslation.field(vm.getClass(), "LLJJJJLIIL", NativeDmTranslation.type(loader,
                "com.ss.android.ugc.aweme.im.chatroom.api.model.SessionInfo")).get(vm) != session
                || NativeDmTranslation.field(input.getClass(), "LLJJIII", EditText.class).get(input) != editor
                || NativeDmTranslation.field(assem.getClass(), "LLJLLL", NativeDmTranslation.type(loader, "X.0Ez7")).get(assem) != editor
                || !Boolean.TRUE.equals(session.getClass().getMethod("isSingleChat").invoke(session))
                || Boolean.TRUE.equals(session.getClass().getMethod("isPreviewMode").invoke(session))
                || Boolean.TRUE.equals(session.getClass().getMethod("isReportPage").invoke(session))) return null;
        return new Identity((String) session.getClass().getMethod("getConversationId").invoke(session),
                (String) session.getClass().getMethod("getSingleChatFromUserId").invoke(session));
    }

    private static void main(Runnable action) { if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else MAIN.post(action); }

    static void resetForTests() {
        for (Owner owner : new ArrayList<>(OWNERS.values())) owner.close();
        OWNERS.clear(); INPUTS.clear();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(PREFERENCES);
        preferences = null;
        transport = new NativeDmTranslation(); identityReader = DmTranslationPreview::nativeIdentity;
    }
    private DmTranslationPreview() {}
}
