/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared;

import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;

/** Owns one global-layout listener and detaches it when its host root is replaced. */
public final class GlobalLayoutHook {
    private WeakReference<ViewGroup> root = new WeakReference<>(null);
    private ViewTreeObserver observer;
    private ViewTreeObserver.OnGlobalLayoutListener listener;

    /** Installs the callback once for a root, replacing a callback owned by an older root. */
    public synchronized boolean install(ViewGroup nextRoot, Runnable callback) {
        if (nextRoot == null || callback == null) {
            detach();
            return false;
        }
        if (root.get() == nextRoot && listener != null) {
            ViewTreeObserver current = nextRoot.getViewTreeObserver();
            if (observer == current && current.isAlive()) return false;
            // A reused Dialog root can have a new observer while the old one is still alive.
            // A listener added before attachment can instead have merged into the current
            // observer. Remove it from both before rebinding so either path keeps exactly one.
            remove(observer, listener);
            remove(current, listener);
            current.addOnGlobalLayoutListener(listener);
            observer = current;
            return false;
        }

        detach();
        ViewTreeObserver nextObserver = nextRoot.getViewTreeObserver();
        ViewTreeObserver.OnGlobalLayoutListener nextListener = callback::run;
        nextObserver.addOnGlobalLayoutListener(nextListener);
        root = new WeakReference<>(nextRoot);
        observer = nextObserver;
        listener = nextListener;
        return true;
    }

    /** Removes the listener from its previous root, if that observer is still alive. */
    public synchronized void detach() {
        if (listener != null) {
            remove(observer, listener);
            // A listener added before its root was attached now lives on the window's observer.
            ViewGroup held = root.get();
            if (held != null) remove(held.getViewTreeObserver(), listener);
        }
        root = new WeakReference<>(null);
        observer = null;
        listener = null;
    }

    private static void remove(ViewTreeObserver from, ViewTreeObserver.OnGlobalLayoutListener listener) {
        if (from == null) return;
        try {
            if (from.isAlive()) from.removeOnGlobalLayoutListener(listener);
        } catch (Throwable ignored) {
            // A destroyed window can invalidate its observer between the two calls.
        }
    }
}
