/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.accountmanagement;

import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.SignedInUser;

/** Register on the same native service that owns the current user. Every event retires this screen. */
final class NativeAccountOwner implements AccountListSession.Accounts {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final ClassLoader loader;
    NativeAccountOwner(ClassLoader loader) { this.loader = loader; }
    @Override public String current() { return SignedInUser.id(); }

    @Override public AccountListSession.Cancellation observe(Runnable changed) throws ReflectiveOperationException {
        Class<?> manager = NativeAccountRows.type(loader, "com.ss.android.ugc.aweme.framework.services.ServiceManager");
        Class<?> api = NativeAccountRows.type(loader, "com.ss.android.ugc.aweme.IAccountUserService");
        Object services = manager.getMethod("get").invoke(null);
        Object service = manager.getMethod("getService", Class.class).invoke(services, api);
        return register(service, api, NativeAccountRows.type(loader, "X.03IM"),
                NativeAccountRows.type(loader, NativeAccountRows.USER), changed);
    }

    static AccountListSession.Cancellation register(Object service, Class<?> api, Class<?> listenerType,
            Class<?> user, Runnable changed) throws ReflectiveOperationException {
        if (service == null || !api.isInstance(service) || !listenerType.isInterface())
            throw new IllegalArgumentException("Unsupported account observer owner");
        Method add = NativeAccountRows.method(api, "LJJIIJ", void.class, listenerType);
        Method remove = NativeAccountRows.method(api, "LJIIZILJ", void.class, listenerType);
        Method event = NativeAccountRows.method(listenerType, "LLLLZI", void.class, int.class, user, user);
        AtomicBoolean active = new AtomicBoolean(true);
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (proxy, method, args) -> {
            if (method.equals(event)) {
                // No Handler hop here: native code can assign the next UID immediately after this call.
                if (active.get()) changed.run();
                return null;
            }
            switch (method.getName()) {
                case "toString": return "Account list observer";
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: throw new IllegalStateException("Changed account observer ABI");
            }
        });
        Runnable unregister = () -> {
            try { remove.invoke(service, listener); }
            catch (ReflectiveOperationException | RuntimeException unavailable) {
                Logger.printInfo(() -> "Account list observer could not be removed");
            }
        };
        try { add.invoke(service, listener); }
        catch (ReflectiveOperationException | RuntimeException failure) {
            active.set(false);
            unregister.run();
            throw failure;
        }
        return () -> {
            if (!active.compareAndSet(true, false)) return;
            // A listener already in the native copied snapshot sees active=false immediately.
            if (Looper.myLooper() == Looper.getMainLooper()) unregister.run();
            else MAIN.post(unregister);
        };
    }
}
