/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.os.Looper;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Captures the notification cell's actual list, state and independently observed VM owners. */
final class InboxQueryOwners {
    static final String FAMILY = "loaded Inbox query";
    private static final List<Owner> OWNERS = new CopyOnWriteArrayList<>();
    private static final List<Vm> VMS = new CopyOnWriteArrayList<>();

    private InboxQueryOwners() {}

    /** Observe only a VM the native getter has already returned. Never initialize a lazy VM. */
    static void observedVm(Object widget, Object vm) {
        if (widget == null || vm == null) return;
        // CopyOnWriteArrayList iterators are snapshots and cannot remove entries themselves.
        for (Vm old : VMS) {
            if (old.widget.get() == null || old.vm.get() == null || old.widget.get() == widget) VMS.remove(old);
        }
        VMS.add(new Vm(widget, vm));
        InboxQueryNative.ownershipChanged();
    }

    private static final class Vm {
        final WeakReference<Object> widget, vm;
        Vm(Object widget, Object vm) { this.widget = new WeakReference<>(widget); this.vm = new WeakReference<>(vm); }
    }

    private static boolean widgetInside(Object widget, Object container) {
        if (container == null) return false;
        // Assem.getParent is an identity-bearing lifecycle owner, not a VM lazy getter.
        for (int depth = 0; widget != null && depth < 16; depth++) {
            if (widget == container) return true;
            Object parent = Reflect.invoke(widget, "getParent");
            if (parent == widget) return false;
            widget = parent;
        }
        return false;
    }

    static void bound(Object holder) {
        Object attached = Reflect.required(holder, "getAttachView$powerlist_release", FAMILY);
        if (!(attached instanceof View)) return;
        View list = (View) attached;
        WeakReference<Object> capturedHolder = new WeakReference<>(holder);
        WeakReference<View> capturedList = new WeakReference<>(list);
        // Native binds can precede attachment. Retry once on this exact list, never another window.
        if (!capture(holder, list)) list.post(() -> {
            Object currentHolder = capturedHolder.get();
            View currentList = capturedList.get();
            if (currentHolder != null && currentList != null) capture(currentHolder, currentList);
        });
    }

    private static boolean capture(Object holder, View list) {
        if (Looper.myLooper() != Looper.getMainLooper()) return false;
        try {
            if (!list.isAttachedToWindow()
                    || Reflect.required(holder, "getAttachView$powerlist_release", FAMILY) != list) return false;
            Object row = Reflect.requiredField(holder, "itemView", FAMILY);
            Object model = Reflect.required(holder, "getItem", FAMILY);
            if (!(row instanceof View) || model == null || !inside((View) row, list)) return false;
            Object fragment = Reflect.required(holder, "getCurFragment", FAMILY);
            Object root = Reflect.required(fragment, "getView", FAMILY);
            Object stub = Reflect.required(holder, "getStub", FAMILY);
            Object state = Reflect.required(list, "getState", FAMILY);
            Object adapter = Reflect.required(list, "getAdapter", FAMILY);
            Object container = Reflect.requiredField(fragment, "LLJJJIL", FAMILY);
            if (!(root instanceof View) || !inside(list, (View) root)
                    || Reflect.required(fragment, "cY", FAMILY) != list || state == null || adapter == null || stub == null
                    || Reflect.required(stub, "LJIIZILJ", FAMILY) != state
                    || Reflect.required(holder, "getAdapterManager$powerlist_release", FAMILY) != adapter
                    || Reflect.requiredField(stub, "LLJJIJIIJIL", FAMILY) != adapter) return false;

            return register(list, state, adapter, fragment, stub, container);
        } catch (Exception failure) {
            Logger.printException(() -> "Could not capture the loaded Inbox list", failure);
            return false;
        }
    }

    /** The exact fragment publication also owns empty pages, where no notification cell binds. */
    static void fragmentBound(Object fragment) {
        Object found = Reflect.invoke(fragment, "cY");
        if (!(found instanceof View)) return;
        View list = (View) found;
        if (!captureFragment(fragment, list)) {
            WeakReference<Object> captured = new WeakReference<>(fragment);
            list.post(() -> { Object current = captured.get(); if (current != null) captureFragment(current, list); });
        }
    }

    private static boolean captureFragment(Object fragment, View list) {
        if (Looper.myLooper() != Looper.getMainLooper() || !list.isAttachedToWindow()
                || Reflect.invoke(fragment, "cY") != list) return false;
        Object root = Reflect.invoke(fragment, "getView");
        Object state = Reflect.invoke(list, "getState"), adapter = Reflect.invoke(list, "getAdapter");
        Object stub = Reflect.invoke(adapter, "LLLLLJIL"), container = Reflect.readField(fragment, "LLJJJIL");
        if (!(root instanceof View) || !inside(list, (View) root) || state == null || adapter == null
                || stub == null || Reflect.invoke(stub, "LJIIZILJ") != state
                || Reflect.readField(stub, "LLJJIJIIJIL") != adapter) return false;
        return register(list, state, adapter, fragment, stub, container);
    }

    private static boolean register(View list, Object state, Object adapter, Object fragment, Object stub, Object container) {
        for (Owner owner : OWNERS) {
            if (owner.list.get() == null) owner.close();
            else if (owner.list.get() == list) {
                if (owner.same(state, adapter, fragment, stub, container)) return true;
                owner.close();
                break;
            }
        }
        Owner owner = new Owner(list, state, adapter, fragment, stub, container);
        OWNERS.add(owner);
        list.addOnAttachStateChangeListener(owner);
        InboxQueryNative.attach(owner);
        // A newly attached window can share the same state/adapter before its VM getter runs.
        InboxQueryNative.ownershipChanged();
        return true;
    }

    private static boolean inside(View child, View root) {
        for (View current = child; current != null;) {
            if (current == root) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    static final class Owner implements View.OnAttachStateChangeListener, LoadedInboxQuery.Ownership {
        final WeakReference<View> list;
        final WeakReference<Object> state, adapter, fragment, stub, container;
        final LoadedInboxQuery query = new LoadedInboxQuery(this);
        private boolean closed;

        Owner(View list, Object state, Object adapter, Object fragment, Object stub, Object container) {
            this.list = new WeakReference<>(list);
            this.state = new WeakReference<>(state);
            this.adapter = new WeakReference<>(adapter);
            this.fragment = new WeakReference<>(fragment);
            this.stub = new WeakReference<>(stub);
            this.container = new WeakReference<>(container);
        }
        boolean same(Object state, Object adapter, Object fragment, Object stub, Object container) {
            return this.state.get() == state && this.adapter.get() == adapter && this.fragment.get() == fragment
                    && this.stub.get() == stub && this.container.get() == container;
        }
        @Override public boolean current() {
            View view = list.get();
            Object currentFragment = fragment.get(), currentStub = stub.get();
            Object currentState = state.get(), currentAdapter = adapter.get();
            Object root = Reflect.invoke(currentFragment, "getView");
            return !closed && view != null && view.isAttachedToWindow()
                    && root instanceof View && inside(view, (View) root)
                    && currentState != null && currentAdapter != null && currentStub != null
                    && Reflect.invoke(currentFragment, "cY") == view
                    && Reflect.invoke(view, "getState") == currentState
                    && Reflect.invoke(view, "getAdapter") == currentAdapter
                    && Reflect.invoke(currentStub, "LJIIZILJ") == currentState
                    && Reflect.readField(currentStub, "LLJJIJIIJIL") == currentAdapter
                    && Reflect.readField(currentFragment, "LLJJJIL") == container.get();
        }
        @Override public boolean seamsReady() { return InboxQueryNative.ready(this); }
        @Override public boolean pagingIsolated() {
            if (!current() || container.get() == null) return false;
            boolean observed = false;
            for (Vm candidate : VMS) {
                Object vm = candidate.vm.get();
                if (vm == null || !widgetInside(candidate.widget.get(), container.get())) continue;
                observed = true;
                for (Owner other : OWNERS) {
                    if (other == this || !other.current()) continue;
                    for (Vm shared : VMS) {
                        if (shared.vm.get() == vm && widgetInside(shared.widget.get(), other.container.get())) return false;
                    }
                }
            }
            return observed;
        }
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { close(); }
        void close() {
            if (closed) return;
            closed = true;
            InboxQueryNative.detach(this);
            query.close();
            View view = list.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            OWNERS.remove(this);
        }
    }

    static Owner forList(View list) {
        for (Owner owner : OWNERS) if (owner.list.get() == list) return owner;
        return null;
    }
    static Owner ownerForTests(View list) { return forList(list); }
    static List<Owner> snapshot() { return new ArrayList<>(OWNERS); }
    static Owner forState(Object state) {
        for (Owner owner : snapshot()) if (owner.state.get() == state && owner.current()) return owner;
        return null;
    }
    static Owner forStub(Object stub) {
        for (Owner owner : snapshot()) if (owner.stub.get() == stub && owner.current()) return owner;
        return null;
    }
    static Owner forFragment(Object fragment) {
        for (Owner owner : snapshot()) if (owner.fragment.get() == fragment && owner.current()) return owner;
        return null;
    }
    static Owner forDiff(Object diff) {
        for (Owner owner : snapshot()) {
            if (owner.current() && Reflect.readField(owner.adapter.get(), "LLLII") == diff) return owner;
        }
        return null;
    }
    static List<Owner> forPagingTarget(Object target) {
        List<Owner> result = new ArrayList<>();
        for (Owner owner : snapshot()) {
            if (!owner.current()) continue;
            boolean matches = owner.container.get() == target
                    || Reflect.readField(owner.adapter.get(), "LLJLL") == target;
            for (Vm observed : new ArrayList<>(VMS)) {
                if ((observed.vm.get() == target || observed.widget.get() == target)
                        && widgetInside(observed.widget.get(), owner.container.get())) matches = true;
            }
            if (matches) result.add(owner);
        }
        return result;
    }
    static boolean requestsIdle(Owner owner) {
        if (InboxNativeRequests.busy(Reflect.readField(owner.adapter.get(), "LLJLL"))) return false;
        for (Vm observed : new ArrayList<>(VMS)) {
            if (widgetInside(observed.widget.get(), owner.container.get()) && InboxNativeRequests.busy(observed.vm.get())) return false;
        }
        return true;
    }
    static void resetForTests() {
        for (Owner owner : new ArrayList<>(OWNERS)) owner.close();
        VMS.clear();
        InboxNativeRequests.resetForTests();
        InboxQueryNative.resetForTests();
    }
}
