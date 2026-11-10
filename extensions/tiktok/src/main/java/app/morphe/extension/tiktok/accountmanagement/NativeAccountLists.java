/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.accountmanagement;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Cancellation;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Completion;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Failure;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Guard;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Kind;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Page;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Reason;

/** The existing signed-in native clients, with their real Future/Rx cancellation handles. */
final class NativeAccountLists implements AccountListSession.Transport {
    interface Runner { boolean start(Runnable task); }
    interface Observer {
        void subscribed(Object disposable);
        void next(Object response);
        void error(Throwable error);
        void complete();
    }
    interface Bridge {
        Object saved(long cursor, int count, Guard guard) throws ReflectiveOperationException;
        Future<?> blocked(int index, int count, Guard guard) throws ReflectiveOperationException;
        void subscribe(Object observable, Observer observer) throws ReflectiveOperationException;
        Page read(Kind kind, Object response) throws ReflectiveOperationException;
        Failure failure(Throwable error);
        void dispose(Object disposable);
    }

    private final Bridge bridge;
    private final Runner runner;
    NativeAccountLists(ClassLoader loader) { this(new ReflectBridge(loader), Utils::runOnBackgroundThread); }
    NativeAccountLists(Bridge bridge, Runner runner) { this.bridge = bridge; this.runner = runner; }

    @Override public Cancellation request(Kind kind, long cursor, Guard guard, Completion completion) {
        ReadCall call = new ReadCall(kind, cursor, guard, completion);
        if (!runner.start(call::start)) call.finish(null, new Failure(Reason.NETWORK));
        return call;
    }

    private final class ReadCall implements Cancellation, Observer {
        final Kind kind;
        final long cursor;
        final AtomicBoolean terminal = new AtomicBoolean();
        private Guard guard;
        private Completion completion;
        private Object disposable;
        private Future<?> future;
        private int results;
        private Page answer;
        ReadCall(Kind kind, long cursor, Guard guard, Completion completion) {
            this.kind = kind; this.cursor = cursor; this.guard = guard; this.completion = completion;
        }

        void start() {
            try {
                if (!allowed()) { cancel(); return; }
                if (kind == Kind.SAVED_POSTS) {
                    Object observable = bridge.saved(cursor, 30, this::allowed);
                    if (observable == null) { finish(null, new Failure(Reason.UNSUPPORTED)); return; }
                    if (!allowed()) { cancel(); return; }
                    bridge.subscribe(observable, this);
                } else {
                    if (cursor < 0 || cursor > Integer.MAX_VALUE) {
                        finish(null, new Failure(Reason.UNSUPPORTED)); return;
                    }
                    Future<?> value = bridge.blocked((int) cursor, 10, this::allowed);
                    if (value == null) { finish(null, new Failure(Reason.UNSUPPORTED)); return; }
                    boolean accept;
                    synchronized (this) { accept = !terminal.get(); if (accept) future = value; }
                    if (!accept) { cancelFuture(value); return; }
                    if (!allowed()) { cancel(); return; }
                    Object response = value.get(AccountListSession.TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!allowed()) { cancel(); return; }
                    finish(bridge.read(kind, response), null);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                finish(null, new Failure(Reason.NETWORK));
            } catch (TimeoutException timeout) { finish(null, new Failure(Reason.TIMEOUT)); }
            catch (CancellationException cancelled) { finish(null, new Failure(Reason.NETWORK)); }
            catch (ExecutionException | ReflectiveOperationException | RuntimeException failure) {
                finish(null, bridge.failure(failure));
            }
        }

        boolean allowed() {
            Guard current;
            synchronized (this) { current = guard; }
            return !terminal.get() && current != null && current.current() && !terminal.get();
        }

        @Override public void subscribed(Object value) {
            boolean accept;
            synchronized (this) {
                accept = value != null && !terminal.get() && disposable == null;
                if (accept) disposable = value;
            }
            if (!accept) {
                if (value != null) bridge.dispose(value);
                finish(null, new Failure(Reason.UNSUPPORTED));
                return;
            }
            // Both native adapters provide this actual HTTP owner before execute/enqueue.
            if (!allowed()) cancel();
        }

        @Override public void next(Object response) {
            if (!allowed()) { cancel(); return; }
            boolean duplicate;
            synchronized (this) { duplicate = ++results != 1; }
            if (duplicate) { finish(null, new Failure(Reason.UNSUPPORTED)); return; }
            try {
                Page parsed = bridge.read(kind, response);
                if (!allowed()) { cancel(); return; }
                synchronized (this) { if (!terminal.get()) answer = parsed; }
            } catch (ReflectiveOperationException | RuntimeException failure) { finish(null, bridge.failure(failure)); }
        }

        @Override public void error(Throwable error) { finish(null, bridge.failure(error)); }
        @Override public void complete() {
            if (!allowed()) { cancel(); return; }
            Page page;
            synchronized (this) { page = results == 1 ? answer : null; }
            finish(page, page == null ? new Failure(Reason.UNSUPPORTED) : null);
        }

        void finish(Page page, Failure failure) {
            Completion done;
            Object active;
            Future<?> pending;
            synchronized (this) {
                if (!terminal.compareAndSet(false, true)) return;
                done = completion; completion = null; guard = null; answer = null;
                active = disposable; disposable = null; pending = future; future = null;
            }
            bridge.dispose(active);
            cancelFuture(pending);
            if (done != null) done.finished(page, failure);
        }

        @Override public void cancel() {
            Object active;
            Future<?> pending;
            synchronized (this) {
                if (!terminal.compareAndSet(false, true)) return;
                completion = null; guard = null; answer = null;
                active = disposable; disposable = null; pending = future; future = null;
            }
            bridge.dispose(active);
            cancelFuture(pending);
        }
    }

    private static void cancelFuture(Future<?> future) {
        if (future == null) return;
        try { future.cancel(true); }
        catch (RuntimeException unavailable) { Logger.printInfo(() -> "Account list request cancellation failed"); }
    }

    private static final class ReflectBridge implements Bridge {
        private final ClassLoader loader;
        private NativeAccountRows rows;
        ReflectBridge(ClassLoader loader) { this.loader = loader; }

        @Override public Object saved(long cursor, int count, Guard guard) throws ReflectiveOperationException {
            Class<?> wrapper = NativeAccountRows.type(loader, "X.1TVY");
            Class<?> api = NativeAccountRows.type(loader, "X.1TVZ");
            Object singleton = NativeAccountRows.field(wrapper, "LIZIZ", wrapper, true).get(null);
            Object service = NativeAccountRows.field(wrapper, "LIZ", api).get(singleton);
            if (!guard.current()) return null;
            return NativeAccountRows.method(api, "LIZ", NativeAccountRows.type(loader, "X.02tX"), long.class, int.class)
                    .invoke(service, cursor, count);
        }

        @Override public Future<?> blocked(int index, int count, Guard guard) throws ReflectiveOperationException {
            Class<?> api = NativeAccountRows.type(loader, "X.18r1");
            Object service = NativeAccountRows.field(NativeAccountRows.type(loader, "X.18r0"), "LIZ", api, true).get(null);
            if (!guard.current()) return null;
            Object value = NativeAccountRows.method(api, "LIZ", NativeAccountRows.type(loader,
                    "com.google.common.util.concurrent.ListenableFuture"), int.class, int.class).invoke(service, index, count);
            if (!(value instanceof Future)) throw new IllegalArgumentException("Changed native future owner");
            return (Future<?>) value;
        }

        @Override public void subscribe(Object observable, Observer observer) throws ReflectiveOperationException {
            Class<?> observerType = NativeAccountRows.type(loader, "X.02tY");
            Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{observerType}, (self, method, args) -> {
                switch (method.getName()) {
                    case "onSubscribe": observer.subscribed(args[0]); return null;
                    case "onNext": observer.next(args[0]); return null;
                    case "onError": observer.error((Throwable) args[0]); return null;
                    case "onComplete": observer.complete(); return null;
                    case "toString": return "Saved posts observer";
                    case "hashCode": return System.identityHashCode(self);
                    case "equals": return self == args[0];
                    default: throw new IllegalStateException("Changed saved-post observer ABI");
                }
            });
            NativeAccountRows.method(NativeAccountRows.type(loader, "X.02tX"), "LIZ", void.class, observerType)
                    .invoke(observable, proxy);
        }

        @Override public Page read(Kind kind, Object response) throws ReflectiveOperationException {
            if (rows == null) rows = new NativeAccountRows(loader);
            return kind == Kind.SAVED_POSTS ? rows.saved(response) : rows.blocked(response);
        }

        @Override public Failure failure(Throwable error) {
            while (error instanceof InvocationTargetException || error instanceof ExecutionException) {
                Throwable cause = error.getCause();
                if (cause == null || cause == error) break;
                error = cause;
            }
            if (error instanceof NativeAccountRows.Refused) return ((NativeAccountRows.Refused) error).failure;
            try {
                Class<?> nativeError = NativeAccountRows.type(loader, "X.0E1o");
                if (nativeError.isInstance(error)) return new Failure(Reason.REFUSED,
                        (Integer) NativeAccountRows.method(nativeError, "getErrorCode", int.class).invoke(error));
            } catch (ReflectiveOperationException | RuntimeException unavailable) {
                // Neither exception messages nor native response bodies belong in diagnostics.
            }
            return new Failure(error instanceof ReflectiveOperationException || error instanceof IllegalArgumentException
                    ? Reason.UNSUPPORTED : Reason.NETWORK);
        }

        @Override public void dispose(Object value) {
            if (value == null) return;
            try { NativeAccountRows.method(NativeAccountRows.type(loader, "X.02tk"), "dispose", void.class).invoke(value); }
            catch (ReflectiveOperationException | RuntimeException unavailable) {
                Logger.printInfo(() -> "Saved posts request cancellation failed");
            }
        }
    }
}
