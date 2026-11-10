/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.app.Activity;
import android.os.Build;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Back handling that survives the predictive back gesture.
 *
 * <p>An activity's {@code onBackPressed} stops being called once an app targets 36 and drops
 * {@code android:enableOnBackInvokedCallback="false"} from its manifest, which TikTok still
 * carries today and will not forever. A screen that only hooks that method loses Back the day it
 * goes. Registering a callback with the dispatcher works on API 33 and above and is ignored
 * below it, so both paths can be in place at once.
 *
 * <p>Everything is reflective because the extension compiles against an older API than the one
 * that has {@code OnBackInvokedDispatcher}.
 */
public final class SystemBackHandler {
    /** Above the host's own callbacks, so this screen answers Back while it is up. */
    private static final int PRIORITY_OVERLAY = 1_000_000;

    private Object dispatcher;
    private Object callback;
    private final String name;

    public SystemBackHandler(String name) {
        this.name = name;
    }

    /** Registers {@code action} as this screen's Back, or does nothing below API 33. */
    public void register(Activity activity, Runnable action) {
        if (Build.VERSION.SDK_INT < 33 || activity == null || callback != null) return;
        try {
            Class<?> callbackClass = Class.forName("android.window.OnBackInvokedCallback");
            Class<?> dispatcherClass = Class.forName("android.window.OnBackInvokedDispatcher");
            Method getDispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher");
            dispatcher = getDispatcher.invoke(activity);
            callback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        String called = method.getName();
                        if ("onBackInvoked".equals(called)) {
                            action.run();
                            return null;
                        }
                        if ("hashCode".equals(called)) return System.identityHashCode(proxy);
                        if ("equals".equals(called)) return proxy == args[0];
                        if ("toString".equals(called)) return name;
                        return null;
                    }
            );
            dispatcherClass.getMethod("registerOnBackInvokedCallback", int.class, callbackClass)
                    .invoke(dispatcher, PRIORITY_OVERLAY, callback);
        } catch (Throwable ignored) {
            dispatcher = null;
            callback = null;
        }
    }

    /**
     * Registers and takes the callback away again when {@code owner} leaves the window.
     *
     * <p>For a screen with no lifecycle of its own to hang the unregister on. A detached view is
     * the same moment, and it needs no callbacks registered against the application.
     */
    public void registerUntilDetached(Activity activity, View owner, Runnable action) {
        register(activity, action);
        if (owner == null || callback == null) return;
        owner.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
            }

            @Override public void onViewDetachedFromWindow(View view) {
                view.removeOnAttachStateChangeListener(this);
                unregister();
            }
        });
    }

    public void unregister() {
        if (dispatcher == null || callback == null) return;
        try {
            Class<?> callbackClass = Class.forName("android.window.OnBackInvokedCallback");
            Class<?> dispatcherClass = Class.forName("android.window.OnBackInvokedDispatcher");
            dispatcherClass.getMethod("unregisterOnBackInvokedCallback", callbackClass)
                    .invoke(dispatcher, callback);
        } catch (Throwable ignored) {
            // Activity teardown also removes callbacks.
        } finally {
            dispatcher = null;
            callback = null;
        }
    }

    /** The registered callback, so a test can invoke it the way the system would. */
    Object callbackForTests() {
        return callback;
    }
}
