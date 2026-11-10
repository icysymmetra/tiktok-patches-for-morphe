/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok;

import java.lang.reflect.Method;

/**
 * The signed-in account's uid, read from TikTok's account service by its real names. The
 * lookups are made once; the id is read on every call, since the account can change.
 */
public final class SignedInUser {
    private static final String SERVICE_MANAGER_CLASS =
            "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String ACCOUNT_USER_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.IAccountUserService";

    /** So a test can stand in for the account service, which needs the host running. Empty is signed out. */
    public static volatile String idForTests;
    /** The handle a test stands in with, read while {@link #idForTests} is set. */
    public static volatile String handleForTests;

    private static volatile Lookups lookups;
    private static volatile boolean unavailable;

    private static final class Lookups {
        final Object manager;
        final Class<?> accountClass;
        final Method getService;
        final Method isLogin;
        final Method getCurUserId;

        Lookups(Object manager, Class<?> accountClass, Method getService, Method isLogin, Method getCurUserId) {
            this.manager = manager;
            this.accountClass = accountClass;
            this.getService = getService;
            this.isLogin = isLogin;
            this.getCurUserId = getCurUserId;
        }
    }

    /** The uid, or null when nobody is signed in or the service can't be read on this build. */
    public static String id() {
        String forTests = idForTests;
        if (forTests != null) return forTests.isEmpty() ? null : forTests;
        Lookups found = lookups();
        if (found == null) return null;
        try {
            Object service = found.getService.invoke(found.manager, found.accountClass);
            if (service == null || !Boolean.TRUE.equals(found.isLogin.invoke(service))) return null;
            Object id = found.getCurUserId.invoke(service);
            return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (Throwable ignored) {
            // Not signed in yet, or the service is still starting. Whoever asks treats it as unknown.
            return null;
        }
    }

    /**
     * The signed-in account's handle without the @, or null. For text that names the account,
     * so it looks its members up on each call rather than keeping them.
     */
    public static String handle() {
        if (idForTests != null) {
            String forTests = handleForTests;
            return forTests == null || forTests.isEmpty() ? null : forTests;
        }
        Lookups found = lookups();
        if (found == null) return null;
        try {
            Object service = found.getService.invoke(found.manager, found.accountClass);
            if (service == null || !Boolean.TRUE.equals(found.isLogin.invoke(service))) return null;
            Object user = found.accountClass.getMethod("getCurUser").invoke(service);
            if (user == null) return null;
            Object handle = user.getClass().getMethod("getUniqueId").invoke(user);
            return handle instanceof String && !((String) handle).isEmpty() ? (String) handle : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Lookups lookups() {
        Lookups found = lookups;
        if (found != null || unavailable) return found;
        try {
            Class<?> managerClass = Class.forName(SERVICE_MANAGER_CLASS);
            Class<?> accountClass = Class.forName(ACCOUNT_USER_SERVICE_CLASS);
            found = new Lookups(managerClass.getMethod("get").invoke(null), accountClass,
                    managerClass.getMethod("getService", Class.class),
                    accountClass.getMethod("isLogin"), accountClass.getMethod("getCurUserId"));
            lookups = found;
            return found;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            // A build where the account service moved. Nothing will find it later either.
            unavailable = true;
            return null;
        } catch (Throwable early) {
            // The manager isn't ready yet; the next call asks again.
            return null;
        }
    }

    private SignedInUser() {}
}
