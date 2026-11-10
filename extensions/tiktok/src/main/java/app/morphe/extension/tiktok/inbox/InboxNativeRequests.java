/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Tracks real native jobs. It neither starts a request nor invents a completion. */
final class InboxNativeRequests {
    static final Object LOCK = new Object();
    private static final List<RequestOwner> REQUESTS = new ArrayList<>();

    private InboxNativeRequests() {}

    static boolean allowed(Object target) {
        synchronized (LOCK) {
            for (InboxQueryOwners.Owner owner : InboxQueryOwners.forPagingTarget(target)) {
                if (owner.query.phase() != LoadedInboxQuery.Phase.IDLE) return false;
            }
            return true;
        }
    }

    /** Enter before the native method changes loading flags or schedules its coroutine. */
    static boolean begin(Object target) {
        synchronized (LOCK) {
            if (!allowed(target)) return false;
            record(target).entering++;
            return true;
        }
    }

    /** A native method returned normally. Its asynchronous jobs have independent lifetimes. */
    static void returned(Object target) {
        synchronized (LOCK) {
            RequestOwner request = find(target);
            if (request != null && request.entering > 0) request.entering--;
        }
    }

    static void launched(Object target, Object job) {
        if (target == null || job == null) return;
        synchronized (LOCK) {
            RequestOwner request = record(target);
            for (WeakReference<Object> old : request.jobs) if (old.get() == job) return;
            request.jobs.add(new WeakReference<>(job));
            try {
                ClassLoader loader = job.getClass().getClassLoader();
                Class<?> function = Class.forName("kotlin.jvm.functions.Function1", false, loader);
                Object unit = Class.forName("kotlin.Unit", false, loader).getField("LIZ").get(null);
                WeakReference<Object> capturedJob = new WeakReference<>(job);
                Object completion = Proxy.newProxyInstance(loader, new Class<?>[]{function}, (proxy, method, arguments) -> {
                    if (method.getName().equals("invoke")) {
                        synchronized (LOCK) {
                            for (int i = request.jobs.size() - 1; i >= 0; i--) {
                                Object old = request.jobs.get(i).get();
                                if (old == null || old == capturedJob.get()) request.jobs.remove(i);
                            }
                        }
                        return unit;
                    }
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    return "Inbox request completion";
                });
                // JobSupport.LJIJ is the reviewed invokeOnCompletion(handler) entry. It also
                // invokes the handler immediately when the genuine job has already completed.
                Method register = job.getClass().getMethod("LJIJ", function);
                register.invoke(job, completion);
            } catch (ReflectiveOperationException | LinkageError failure) {
                // isCompleted remains an exact native fallback, never a timeout-based success.
                if (!(Reflect.invoke(job, "isCompleted") instanceof Boolean)) request.unreadable = true;
            }
        }
    }

    static boolean busy(Object target) {
        synchronized (LOCK) {
            RequestOwner request = find(target);
            if (request != null) {
                if (request.entering != 0 || request.unreadable) return true;
                for (int i = request.jobs.size() - 1; i >= 0; i--) {
                    Object old = request.jobs.get(i).get();
                    if (old == null || Boolean.TRUE.equals(Reflect.invoke(old, "isCompleted"))) request.jobs.remove(i);
                }
                if (!request.jobs.isEmpty()) return true;
            }
            // onLoadMore's outer coroutine launches a sibling job and can finish first. These
            // are the actual native refresh/load jobs, not a guessed loading timeout.
            String name = target == null ? "" : target.getClass().getName();
            String[] fields;
            if (name.endsWith("NotificationCreatorResultViewModel")) fields = new String[]{"LLJJLIIIJLLLLLLLZ", "LLJL"};
            else if (isRegularVm(target)) fields = new String[]{"LLJLIL", "LLJLILLLLZIIL"};
            else return false;
            for (String field : fields) {
                if (Reflect.field(target.getClass(), field) == null) return true;
                Object job = Reflect.readField(target, field);
                if (job != null && !Boolean.TRUE.equals(Reflect.invoke(job, "isCompleted"))) return true;
            }
            return false;
        }
    }

    private static boolean isRegularVm(Object target) {
        for (Class<?> type = target == null ? null : target.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith("NotificationResultViewModel")) return true;
        }
        return false;
    }

    private static RequestOwner find(Object target) {
        for (int i = REQUESTS.size() - 1; i >= 0; i--) {
            if (REQUESTS.get(i).target.get() == null) REQUESTS.remove(i);
        }
        for (RequestOwner request : REQUESTS) if (request.target.get() == target) return request;
        return null;
    }
    private static RequestOwner record(Object target) {
        RequestOwner request = find(target);
        if (request == null) { request = new RequestOwner(target); REQUESTS.add(request); }
        return request;
    }
    private static final class RequestOwner {
        final WeakReference<Object> target;
        final List<WeakReference<Object>> jobs = new ArrayList<>();
        int entering;
        boolean unreadable;
        RequestOwner(Object target) { this.target = new WeakReference<>(target); }
    }
    static void resetForTests() { synchronized (LOCK) { REQUESTS.clear(); } }
}
