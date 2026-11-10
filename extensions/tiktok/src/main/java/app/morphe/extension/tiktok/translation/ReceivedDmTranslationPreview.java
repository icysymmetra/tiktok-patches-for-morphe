/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** An explicit preview of one received message. Native text, rows and message data stay native. */
@SuppressWarnings("unused")
public final class ReceivedDmTranslationPreview {
    static final String ITEM_KEY = "ReceivedTranslationPreview";
    /** The menu row's icon, raw/icon_languages on 47.4.3; ReceivedDmTranslationIconTest holds it to that name. */
    static final int LANGUAGES_ICON = 0x7f010844;
    private static final String CONTENT = "com.ss.android.ugc.aweme.im.sdk.chat.ui.text.content.";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Map<Object, Binding> BINDINGS = new WeakHashMap<>();
    private static final AtomicLong ACCOUNT_EPOCH = new AtomicLong(), GATE_EPOCH = new AtomicLong();
    private static SharedPreferences preferences;
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFERENCES = (store, key) -> {
        if (Settings.RECEIVED_DM_TRANSLATION_PREVIEW.key.equals(key) || BaseSettings.PAUSED.key.equals(key)
                || BaseSettings.SAFE_MODE.key.equals(key)) {
            long epoch = GATE_EPOCH.incrementAndGet();
            main(() -> { for (Binding binding : bindings()) binding.cancelBefore(epoch); });
        }
    };

    interface RowReader { Row read(Object assem, Object model) throws ReflectiveOperationException; }
    interface MenuReader { MenuOwner read(Object menu, Object message) throws ReflectiveOperationException; }
    interface ItemFactory { Object create(Context context, Runnable action) throws ReflectiveOperationException; }
    static DmTranslationPreview.Transport transport = new NativeDmTranslation();
    static RowReader rowReader = ReceivedDmTranslationPreview::nativeRow;
    static MenuReader menuReader = ReceivedDmTranslationPreview::nativeMenu;
    static ItemFactory itemFactory = ReceivedDmTranslationPreview::nativeItem;

    static final class MenuOwner {
        final Object assem, model;
        MenuOwner(Object assem, Object model) { this.assem = assem; this.model = model; }
    }

    static final class Row {
        final Object model, message, session;
        final View view;
        final String uuid, conversation, peer, source, content;
        Row(Object model, Object message, Object session, View view, String uuid, String conversation,
                String peer, String source, String content) {
            this.model = model; this.message = message; this.session = session; this.view = view;
            this.uuid = uuid; this.conversation = conversation; this.peer = peer;
            this.source = source; this.content = content;
        }
    }

    /**
     * Called after a successful native received-row bind. While the setting is off it only lets
     * go of the row: reading one costs dozens of reflective calls on the main thread, every bind of
     * every chat. A row bound before the switch went on gets the action at its next bind.
     */
    public static void bound(Object assem, Object model) {
        unbound(assem);
        if (assem == null || model == null || Looper.myLooper() != Looper.getMainLooper() || !enabled()) return;
        try {
            long epoch = ACCOUNT_EPOCH.get();
            String account = SignedInUser.id();
            Row row = rowReader.read(assem, model);
            if (account == null || account.isEmpty() || row == null || row.model != model || row.view == null) return;
            Binding binding = new Binding(assem, model, row.view, account, epoch);
            List<Binding> replaced = new ArrayList<>();
            synchronized (LOCK) {
                if (BINDINGS.size() >= 128 || epoch != ACCOUNT_EPOCH.get() || !account.equals(SignedInUser.id())) return;
                Iterator<Binding> existing = BINDINGS.values().iterator();
                while (existing.hasNext()) {
                    Binding previous = existing.next();
                    if (previous.view.get() == row.view) {
                        previous.retired = true;
                        replaced.add(previous);
                        existing.remove();
                    }
                }
                BINDINGS.put(assem, binding);
            }
            for (Binding previous : replaced) previous.close();
            row.view.addOnAttachStateChangeListener(binding);
            listen();
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // A changed native owner keeps its ordinary menu and rendering.
        }
    }

    /** Retire synchronously before native rebind/cancellation can deliver another result. */
    public static void unbound(Object assem) {
        Binding binding;
        synchronized (LOCK) {
            binding = BINDINGS.remove(assem);
            if (binding != null) binding.retired = true;
        }
        if (binding != null) main(binding::close);
    }

    /** Only the received cell's completed native menu result reaches this method. */
    public static List<?> appendAction(List<?> nativeItems, Object menu, Object message) {
        if (nativeItems == null || nativeItems.size() > 128 || !enabled()
                || Looper.myLooper() != Looper.getMainLooper()) return nativeItems;
        try {
            MenuOwner owner = menuReader.read(menu, message);
            if (owner == null) return nativeItems;
            Binding binding;
            synchronized (LOCK) { binding = BINDINGS.get(owner.assem); }
            if (binding == null || binding.model.get() != owner.model) return nativeItems;
            Snapshot snapshot = binding.snapshot();
            if (snapshot == null || snapshot.message.get() != message) return nativeItems;
            for (Object item : nativeItems) if (item != null && item.getClass().getName().equals("X.18pP")
                    && ITEM_KEY.equals(NativeDmTranslation.field(item.getClass(), "LIZ", String.class).get(item)))
                return nativeItems;
            Object item = itemFactory.create(snapshot.view.get().getContext(), () -> binding.preview(snapshot));
            if (item == null || !binding.current(snapshot)) return nativeItems;
            List<Object> result = new ArrayList<>(nativeItems);
            result.add(item);
            return result;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return nativeItems;
        }
    }

    /** Covers logout/login back to the same account before an old callback arrives. */
    public static void accountReset() {
        retireAccount().run();
    }

    static Runnable retireAccount() {
        List<Binding> retired;
        synchronized (LOCK) {
            ACCOUNT_EPOCH.incrementAndGet();
            retired = new ArrayList<>(BINDINGS.values());
            for (Binding binding : retired) binding.retired = true;
            BINDINGS.clear();
        }
        return () -> main(() -> { for (Binding binding : retired) binding.close(); });
    }

    private static boolean enabled() {
        return Utils.getContext() != null && SettingsStatus.commentTranslationEnabled
                && SettingsStatus.receivedDmTranslationPreviewEnabled
                && Settings.RECEIVED_DM_TRANSLATION_PREVIEW.get() && !Setting.isPaused() && !BaseSettings.SAFE_MODE.get();
    }

    private static void listen() {
        if (preferences != null || Utils.getContext() == null) return;
        preferences = Utils.getContext().getSharedPreferences(Setting.preferences.name, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(PREFERENCES);
    }

    private static List<Binding> bindings() { synchronized (LOCK) { return new ArrayList<>(BINDINGS.values()); } }
    private static void main(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else MAIN.post(action);
    }

    private static final class Snapshot {
        final WeakReference<Object> model, message, session;
        final WeakReference<View> view;
        final String uuid, conversation, peer, source, content;
        final long revision, accountEpoch, gateEpoch = GATE_EPOCH.get();
        Snapshot(Row row, Binding binding) {
            model = new WeakReference<>(row.model); message = new WeakReference<>(row.message);
            session = new WeakReference<>(row.session); view = new WeakReference<>(row.view);
            uuid = row.uuid; conversation = row.conversation; peer = row.peer;
            source = row.source; content = row.content;
            revision = binding.revision; accountEpoch = binding.accountEpoch;
        }
        boolean same(Row row) {
            return row != null && model.get() == row.model && message.get() == row.message && session.get() == row.session
                    && view.get() == row.view && uuid.equals(row.uuid) && conversation.equals(row.conversation)
                    && peer.equals(row.peer) && source.equals(row.source)
                    && (content == null ? row.content == null : content.equals(row.content));
        }
    }

    private static final class Binding implements View.OnAttachStateChangeListener {
        final WeakReference<Object> assem, model;
        final WeakReference<View> view;
        final String account;
        final long accountEpoch;
        volatile boolean retired;
        long revision;
        Pending pending;
        Binding(Object assem, Object model, View view, String account, long accountEpoch) {
            this.assem = new WeakReference<>(assem); this.model = new WeakReference<>(model);
            this.view = new WeakReference<>(view); this.account = account; this.accountEpoch = accountEpoch;
        }
        Row row() {
            View anchor = view.get();
            Object owner = assem.get(), item = model.get();
            if (retired || owner == null || item == null || anchor == null || !anchor.isAttachedToWindow()
                    || !anchor.isShown() || accountEpoch != ACCOUNT_EPOCH.get() || !account.equals(SignedInUser.id())) return null;
            synchronized (LOCK) { if (BINDINGS.get(owner) != this) return null; }
            try {
                Row row = rowReader.read(owner, item);
                return row != null && row.model == item && row.view == anchor && row.message != null && row.session != null
                        && valid(row.uuid) && valid(row.conversation) && valid(row.peer) && !account.equals(row.peer)
                        && valid(row.source) && row.source.length() <= DmTranslationPreview.MAX_TEXT
                        && !row.source.trim().isEmpty() && (row.content == null
                        || row.content.length() <= DmTranslationPreview.MAX_TEXT * 4) ? row : null;
            } catch (ReflectiveOperationException | RuntimeException unavailable) { return null; }
        }
        Snapshot snapshot() { Row row = enabled() ? row() : null; return row == null ? null : new Snapshot(row, this); }
        boolean current(Snapshot snapshot) {
            if (!enabled() || retired || snapshot.revision != revision || snapshot.accountEpoch != ACCOUNT_EPOCH.get()
                    || snapshot.gateEpoch != GATE_EPOCH.get()) return false;
            Row observed = row();
            return snapshot.same(observed) && enabled() && !retired && snapshot.revision == revision
                    && snapshot.accountEpoch == ACCOUNT_EPOCH.get() && snapshot.gateEpoch == GATE_EPOCH.get()
                    && account.equals(SignedInUser.id());
        }
        void preview(Snapshot snapshot) {
            if (Looper.myLooper() != Looper.getMainLooper() || !current(snapshot)) return;
            cancel();
            Pending work = new Pending(this, snapshot);
            pending = work;
            Context context = snapshot.view.get().getContext();
            try {
                work.dialog = new AlertDialog.Builder(context).setTitle(L10n.t(context, "Translation preview"))
                        .setMessage(L10n.t(context, "Original message") + "\n\n" + snapshot.source + "\n\n" + L10n.t(context, "Translating…"))
                        .setPositiveButton(L10n.t(context, "Show original"), null)
                        .setNegativeButton(L10n.t(context, "Close"), null).create();
                work.dialog.setOnDismissListener(dialog -> { if (pending == work) cancel(); });
                work.dialog.show();
                SettingsUi.styleFramedDialog(work.dialog);
                work.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                work.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> work.toggle());
                work.schedule();
                DmTranslationPreview.Request request = transport.translate(context.getClassLoader(), account, snapshot.source,
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
            work.translation = null;
            if (work.dialog != null && work.dialog.isShowing()) work.dialog.dismiss();
        }
        void cancelBefore(long gateEpoch) {
            Pending work = pending;
            if (work != null && work.snapshot.gateEpoch < gateEpoch) cancel();
        }
        void close() {
            cancel();
            View anchor = view.get();
            if (anchor != null) anchor.removeOnAttachStateChangeListener(this);
        }
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { revision++; cancel(); }
    }

    private static final class Pending implements Runnable {
        final Binding owner;
        final Snapshot snapshot;
        final long deadline = SystemClock.elapsedRealtime() + DmTranslationPreview.TIMEOUT_MS;
        AlertDialog dialog;
        DmTranslationPreview.Request request;
        String translation;
        boolean complete, original;
        Pending(Binding owner, Snapshot snapshot) { this.owner = owner; this.snapshot = snapshot; }
        boolean current() { return owner.pending == this && owner.current(snapshot) && owner.pending == this; }
        boolean dispatchable() { return !complete && current() && SystemClock.elapsedRealtime() < deadline; }
        void finished(String translated) {
            if (!current()) { if (owner.pending == this) owner.cancel(); return; }
            if (complete) return;
            if (SystemClock.elapsedRealtime() >= deadline || !valid(translated) || translated.trim().isEmpty()
                    || translated.length() > DmTranslationPreview.MAX_TEXT) { fail(); return; }
            complete = true;
            translation = translated;
            if (request != null) { request.cancel(); request = null; }
            render();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        }
        void toggle() {
            if (!current()) { if (owner.pending == this) owner.cancel(); return; }
            if (!complete) return;
            original = !original;
            render();
        }
        void render() {
            Context context = snapshot.view.get().getContext();
            dialog.setMessage(L10n.t(context, original ? "Original message" : "Translated message") + "\n\n"
                    + (original ? snapshot.source : translation));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(L10n.t(context, original ? "Show translation" : "Show original"));
        }
        void fail() {
            boolean visible = current();
            View view = snapshot.view.get();
            if (owner.pending == this) owner.cancel();
            if (visible && view != null) Utils.showToastShort(L10n.t(view.getContext(), "Couldn't translate this message. Try again in a moment."));
        }
        @Override public void run() {
            if (!current()) { if (owner.pending == this) owner.cancel(); return; }
            if (!complete && SystemClock.elapsedRealtime() >= deadline) { fail(); return; }
            schedule();
        }
        void schedule() {
            MAIN.postDelayed(this, complete ? 100 : Math.min(100, Math.max(0, deadline - SystemClock.elapsedRealtime())));
        }
    }

    private static boolean valid(String value) { return value != null && !value.isEmpty(); }

    private static MenuOwner nativeMenu(Object menu, Object message) throws ReflectiveOperationException {
        if (menu == null || !menu.getClass().getName().equals(CONTENT + "TextTranslateReceiveCell$createAssemAttached2Cell$1")) return null;
        ClassLoader loader = menu.getClass().getClassLoader();
        Class<?> receive = NativeDmTranslation.type(loader, CONTENT + "TextTranslateReceiveAssem");
        Object child = null;
        Object children = NativeDmTranslation.type(loader, "X.03Sz").getMethod("getChildren").invoke(menu);
        if (!(children instanceof List) || ((List<?>) children).size() > 128) return null;
        for (Object candidate : (List<?>) children) if (candidate != null && candidate.getClass() == receive) {
            if (child != null) return null;
            child = candidate;
        }
        Object cell = NativeDmTranslation.field(menu.getClass(), "LLLILZ",
                NativeDmTranslation.type(loader, CONTENT + "TextTranslateReceiveCell")).get(menu);
        if (child == null || cell == null) return null;
        Object model = NativeDmTranslation.type(loader, "com.bytedance.ies.powerlist.PowerCell").getMethod("getItem").invoke(cell);
        Class<?> received = NativeDmTranslation.type(loader, "X.0dfZ");
        if (model == null || model.getClass() != received) return null;
        Object actual = NativeDmTranslation.type(loader, "X.0cxv").getMethod("LIZ", NativeDmTranslation.type(loader, "X.0dSc")).invoke(null, model);
        return actual == message ? new MenuOwner(child, model) : null;
    }

    private static Row nativeRow(Object assem, Object model) throws ReflectiveOperationException {
        if (assem == null || model == null || !assem.getClass().getName().equals(CONTENT + "TextTranslateReceiveAssem")
                || !model.getClass().getName().equals("X.0dfZ")) return null;
        ClassLoader loader = assem.getClass().getClassLoader();
        Object current = NativeDmTranslation.type(loader, "X.08oa").getMethod("LIZ",
                NativeDmTranslation.type(loader, "com.bytedance.assem.arch.reused.ReusedAssem")).invoke(null, assem);
        if (current != model) return null;
        Object message = NativeDmTranslation.type(loader, "X.0cxv").getMethod("LIZ", NativeDmTranslation.type(loader, "X.0dSc")).invoke(null, model);
        Class<?> messageType = NativeDmTranslation.type(loader, "X.0BUG");
        if (!messageType.isInstance(message) || !Integer.valueOf(7).equals(messageType.getMethod("getMsgType").invoke(message))
                || Boolean.TRUE.equals(NativeDmTranslation.type(loader, "X.0CVs").getMethod("LJI", messageType).invoke(null, message))) return null;
        Class<?> action = NativeDmTranslation.type(loader, "X.0dRY");
        if (!Boolean.TRUE.equals(NativeDmTranslation.type(loader, "X.0CWU").getMethod("LJIIIZ", messageType, action)
                .invoke(null, message, action.getField("TRANSLATE").get(null)))) return null;
        Class<?> service = NativeDmTranslation.type(loader, "X.0CHK");
        if (!Boolean.TRUE.equals(service.getMethod("LJIIZILJ").invoke(service.getField("LIZIZ").get(null)))) return null;
        Class<?> owner = NativeDmTranslation.type(loader, CONTENT + "TextTranslateAssem");
        Object lazy = NativeDmTranslation.field(owner, "LLJJL", NativeDmTranslation.type(loader, "X.04QJ")).get(assem);
        Object holder = NativeDmTranslation.type(loader, "X.04QJ").getMethod("getValue").invoke(lazy);
        Class<?> holderType = NativeDmTranslation.type(loader, "X.0YZ7");
        if (!holderType.isInstance(holder)) return null;
        Class<?> sessionType = NativeDmTranslation.type(loader, "com.ss.android.ugc.aweme.im.chatroom.api.model.SessionInfo");
        Object session = NativeDmTranslation.field(holderType, "LL", sessionType).get(holder);
        Class<?> single = NativeDmTranslation.type(loader, "X.0dli");
        if (!single.isInstance(session) || !Boolean.TRUE.equals(sessionType.getMethod("isSingleChat").invoke(session))
                || Boolean.TRUE.equals(sessionType.getMethod("isPreviewMode").invoke(session))
                || Boolean.TRUE.equals(sessionType.getMethod("isReportPage").invoke(session))) return null;
        String conversation = (String) sessionType.getMethod("getConversationId").invoke(session);
        String peer = (String) single.getMethod("getSingleChatFromUserId").invoke(session);
        Object sender = messageType.getMethod("getSender").invoke(message);
        if (!valid(conversation) || !conversation.equals(messageType.getMethod("getConversationId").invoke(message))
                || !(sender instanceof Long) || ((Long) sender) <= 0 || !Long.toString((Long) sender).equals(peer)) return null;
        Object body = owner.getMethod("mq").invoke(assem);
        Class<?> bodyType = NativeDmTranslation.type(loader, "X.0dfB");
        if (!bodyType.isInstance(body)) return null;
        Object view = NativeDmTranslation.field(bodyType, "LL", View.class).get(body);
        return !(view instanceof View) ? null : new Row(model, message, session, (View) view,
                (String) messageType.getMethod("getUuid").invoke(message), conversation, peer,
                (String) NativeDmTranslation.type(loader, "X.0dfc").getMethod("LLIIJLIL").invoke(model),
                (String) messageType.getMethod("getContent").invoke(message));
    }

    private static Object nativeItem(Context context, Runnable action) throws ReflectiveOperationException {
        ClassLoader loader = context.getClassLoader();
        Class<?> function = NativeDmTranslation.type(loader, "kotlin.jvm.functions.Function1");
        Object unit = NativeDmTranslation.type(loader, "kotlin.Unit").getField("LIZ").get(null);
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "invoke": if (args != null && args.length == 1 && args[0] instanceof View) action.run(); return unit;
                case "toString": return "Received translation preview";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return args != null && args.length == 1 && proxy == args[0];
                default: throw new IllegalStateException("Unexpected received translation callback");
            }
        });
        Class<?> icon = NativeDmTranslation.type(loader, "X.18h3"), text = NativeDmTranslation.type(loader, "X.18h1");
        Class<?> style = NativeDmTranslation.type(loader, "X.18kj"), size = NativeDmTranslation.type(loader, "X.19Hs");
        return NativeDmTranslation.type(loader, "X.18pP").getConstructor(String.class, Enum.class, icon, text, style, size, boolean.class, function)
                .newInstance(ITEM_KEY, NativeDmTranslation.type(loader, "X.0dJL").getField("TRANSLATE").get(null),
                        NativeDmTranslation.type(loader, "X.18h2").getConstructor(int.class).newInstance(LANGUAGES_ICON),
                        NativeDmTranslation.type(loader, "X.18gz").getConstructor(String.class).newInstance(L10n.t(context, "Preview translation")),
                        style.getField("PRIMARY").get(null), size.getConstructor().newInstance(), false, callback);
    }

    static void resetForTests() {
        accountReset();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(PREFERENCES);
        preferences = null;
        ACCOUNT_EPOCH.set(0); GATE_EPOCH.set(0);
        transport = new NativeDmTranslation();
        rowReader = ReceivedDmTranslationPreview::nativeRow;
        menuReader = ReceivedDmTranslationPreview::nativeMenu;
        itemFactory = ReceivedDmTranslationPreview::nativeItem;
    }
}
