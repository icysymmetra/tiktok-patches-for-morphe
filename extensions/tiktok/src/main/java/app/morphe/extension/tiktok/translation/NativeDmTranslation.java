/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses only the shipped DM endpoint and its cancellable native Rx observer. No message is made. */
final class NativeDmTranslation implements DmTranslationPreview.Transport {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String RESPONSE = "com.ss.android.ugc.aweme.translation.model.MultiTranslationResultDM";
    private static final String RESULT = "com.ss.android.ugc.aweme.translation.model.TranslationResultDM";

    @Override public DmTranslationPreview.Request translate(ClassLoader loader, String account, String source,
            DmTranslationPreview.DispatchGuard guard, DmTranslationPreview.Completion completion) {
        Call call = new Call(loader, account, source, guard, completion);
        if (!Utils.runOnBackgroundThread(call::start)) call.finish(null);
        return call;
    }

    private static final class Call implements DmTranslationPreview.Request {
        final ClassLoader loader;
        final String account;
        volatile DmTranslationPreview.DispatchGuard guard;
        final AtomicBoolean terminal = new AtomicBoolean();
        DmTranslationPreview.Completion completion;
        String source, translated;
        int results;
        Object disposable;

        Call(ClassLoader loader, String account, String source, DmTranslationPreview.DispatchGuard guard,
                DmTranslationPreview.Completion completion) {
            this.loader = loader;
            this.account = account;
            this.source = source;
            this.guard = guard;
            this.completion = completion;
        }

        void start() {
            try {
                if (!allowed()) { finish(null); return; }
                Class<?> service = type(loader, "X.0CHK");
                Object nativeService = service.getField("LIZIZ").get(null);
                if (!Boolean.TRUE.equals(service.getMethod("LJIIZILJ").invoke(nativeService))) {
                    finish(null);
                    return;
                }
                Class<?> language = type(loader, "X.03j7");
                Object languages = language.getField("LIZIZ").get(null);
                String target = (String) language.getMethod("LLILLIZIL").invoke(languages);
                String[] excluded = (String[]) language.getMethod("LLFII").invoke(languages);
                if (target == null || target.isEmpty() || !allowed()) { finish(null); return; }
                String original;
                synchronized (this) { original = source; }
                if (original == null) return;
                // Gson's native content object omits a null source_lang and empty rich-text data.
                String payload = new JSONArray().put(new JSONObject().put("original_text", original)).toString();
                String dnt = new JSONArray(excluded == null ? new String[0] : excluded).toString();
                Class<?> api = type(loader, "X.1O0f");
                Object nativeApi = type(loader, "X.1O0d").getMethod("LIZ").invoke(null);
                Object observable = api.getMethod("LJ", String.class, String.class, String.class)
                        .invoke(nativeApi, payload, target, dnt);
                if (observable == null || !allowed()) { finish(null); return; }
                Class<?> observerType = type(loader, "X.02tm");
                Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{observerType}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onSubscribe": subscribed(args[0]); return null;
                        case "onNext": next(args[0]); return null;
                        case "onError": finish(null); return null;
                        case "onComplete": complete(); return null;
                        case "toString": return "DM translation observer";
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: throw new IllegalStateException("Unexpected DM observer method");
                    }
                });
                if (!allowed()) { finish(null); return; }
                type(loader, "X.02tl").getMethod("LIZ", observerType).invoke(observable, observer);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                finish(null);
            } catch (ReflectiveOperationException | JSONException | RuntimeException failure) {
                // Native errors may contain private text. Only the caller's generic failure UI is used.
                finish(null);
            }
        }

        boolean allowed() throws InterruptedException {
            DmTranslationPreview.DispatchGuard guard = this.guard;
            if (guard == null || terminal.get() || !account.equals(SignedInUser.id())) return false;
            if (Looper.myLooper() == Looper.getMainLooper()) return guard.current() && !terminal.get();
            CountDownLatch checked = new CountDownLatch(1);
            AtomicBoolean accepted = new AtomicBoolean();
            Runnable check = () -> {
                try { accepted.set(!terminal.get() && guard.current()); }
                catch (RuntimeException stale) { accepted.set(false); }
                finally { checked.countDown(); }
            };
            MAIN.post(check);
            try {
                return checked.await(2, TimeUnit.SECONDS) && accepted.get() && !terminal.get()
                        && account.equals(SignedInUser.id());
            } finally { MAIN.removeCallbacks(check); }
        }

        void subscribed(Object value) {
            boolean accepted;
            synchronized (this) {
                accepted = !terminal.get() && disposable == null;
                if (accepted) disposable = value;
            }
            if (!accepted) { dispose(value); return; }
            // Both native HTTP adapters call onSubscribe before execute/enqueue. Cancel the
            // actual call here too if the editor retired while subscription was being made.
            try { if (!allowed()) finish(null); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); finish(null); }
            catch (RuntimeException stale) { finish(null); }
        }

        void next(Object value) {
            String original;
            boolean duplicate;
            synchronized (this) {
                if (terminal.get()) return;
                duplicate = ++results != 1;
                original = source;
            }
            if (duplicate) { finish(null); return; }
            try {
                String answer = readResponse(value, original);
                synchronized (this) { if (!terminal.get()) translated = answer; }
            } catch (ReflectiveOperationException | RuntimeException invalid) {
                finish(null);
            }
        }

        void complete() {
            String answer;
            synchronized (this) { answer = results == 1 ? translated : null; }
            finish(answer);
        }

        void finish(String answer) {
            DmTranslationPreview.Completion done;
            Object active;
            synchronized (this) {
                if (!terminal.compareAndSet(false, true)) return;
                done = completion;
                completion = null;
                guard = null;
                source = translated = null;
                active = disposable;
                disposable = null;
            }
            dispose(active);
            if (done != null) MAIN.post(() -> done.finished(answer));
        }

        @Override public void cancel() {
            Object active;
            synchronized (this) {
                if (!terminal.compareAndSet(false, true)) return;
                completion = null;
                guard = null;
                source = translated = null;
                active = disposable;
                disposable = null;
            }
            dispose(active);
        }

        void dispose(Object value) {
            if (value == null) return;
            try { type(loader, "X.02ty").getMethod("dispose").invoke(value); }
            catch (ReflectiveOperationException | RuntimeException ignored) {
                // Ownership is already retired. A late native event cannot present or replace a draft.
            }
        }
    }

    static String readResponse(Object response, String original) throws ReflectiveOperationException {
        if (response == null || !response.getClass().getName().equals(RESPONSE) || original == null)
            throw new IllegalArgumentException("Unknown DM response");
        Class<?> owner = response.getClass();
        Object status = field(owner, "statusCode", Integer.class).get(response);
        Object message = field(owner, "statusMsg", String.class).get(response);
        Object rows = field(owner, "results", List.class).get(response);
        if (!Integer.valueOf(0).equals(status) || message != null && !"".equals(message)
                || !(rows instanceof List) || ((List<?>) rows).size() != 1)
            throw new IllegalArgumentException("Unconfirmed DM response");
        Object row = ((List<?>) rows).get(0);
        if (row == null || !row.getClass().getName().equals(RESULT))
            throw new IllegalArgumentException("Unknown DM result");
        owner = row.getClass();
        Object code = field(owner, "translationStatusCode", Integer.class).get(row);
        Object echo = field(owner, "originalText", String.class).get(row);
        Object text = field(owner, "translatedText", String.class).get(row);
        Object extra = field(owner, "textExtraList", List.class).get(row);
        if (!Integer.valueOf(0).equals(code) || !original.equals(echo) || !(text instanceof String)
                || ((String) text).trim().isEmpty() || ((String) text).length() > DmTranslationPreview.MAX_TEXT
                || extra != null && (!(extra instanceof List) || !((List<?>) extra).isEmpty()))
            throw new IllegalArgumentException("Unconfirmed plain DM translation");
        return (String) text;
    }

    static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    static Field field(Class<?> owner, String name, Class<?> expected) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected) throw new NoSuchFieldException("Changed DM field schema");
        field.setAccessible(true);
        return field;
    }
}
