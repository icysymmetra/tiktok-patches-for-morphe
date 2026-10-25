/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * TikTok's bedtime wind-down, its breathing exercise and its daily-limit screen, the takeovers the
 * feed's sleep-hour slot puts over a video.
 *
 * <p>Each one comes from a trigger the slot asks whether it has anything to show, as it's built and
 * before it prepares or shows one. With the switch on, the answer is no, so the slot never prepares
 * the screen, never shows it and never comes back to it.
 *
 * <p>Only on an account TikTok itself treats as an adult's: someone is signed in, the compliance
 * settings TikTok saved say the account isn't a minor's, and Family Pairing doesn't link it as the
 * teen's side. Anything that can't be read leaves the screens alone, and so does Pause. The
 * daily-limit screen also stays while Leave when TikTok says time is up is on, since that switch
 * acts on the screen coming up.
 */
public final class WindDownScreens {
    static final String DAILY_LIMIT_TRIGGER =
            "com.ss.android.ugc.aweme.compliance.protection.timelock.ui.assem.STMDailyScreenTimeTrigger";

    private static final String FAMILY_PAIRING_CLASS =
            "com.ss.android.ugc.aweme.compliance.protection.familypairing.FamilyPairingManagerV2";
    private static final String SPI_CLASS = "com.ss.android.ugc.aweme.framework.services.PluggableExtentionKt";
    private static final String PROTECTION_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.compliance.api.services.teenmode.IProtectionService";
    private static final String USER_DETAILS_CLASS = "com.ss.android.ugc.aweme.compliance.api.model.UserDetailsInfoBean";

    /** Family Pairing roles that leave the account its own: not paired, or the parent's side. */
    private static final String ROLE_NOT_PAIRED = "NONE";
    private static final String ROLE_PARENT = "PARENT";

    // Not BooleanSupplier: java.util.function arrived at API 24, past the payload's floor.
    /** Whether TikTok treats the signed-in account as an adult's. A test stands in for TikTok here. */
    interface AdultCheck {
        boolean adult();
    }

    static volatile AdultCheck adultCheck = WindDownScreens::tiktokSaysAdult;

    private static volatile Lookups lookups;
    private static volatile boolean unavailable;

    private WindDownScreens() {
    }

    /**
     * Injection point, before each return of a trigger's "has something to show" check. Returns
     * that answer, or false when the screen is kept back.
     */
    public static boolean eligible(@Nullable Object trigger, boolean eligible) {
        if (!eligible || trigger == null) return eligible;
        try {
            if (!Settings.HIDE_WIND_DOWN_SCREENS.get()) return true;
            if (DAILY_LIMIT_TRIGGER.equals(trigger.getClass().getName())
                    && Settings.LEAVE_ON_REST_REMINDER.get()) return true;
            if (!adultCheck.adult()) return true;
            Logger.printDebug(() -> "Block popups: kept back " + trigger.getClass().getSimpleName());
            return false;
        } catch (Throwable failure) {
            Logger.printException(() -> "Block popups: could not check a wind-down screen", failure);
            return true;
        }
    }

    /**
     * True only for a Family Pairing role TikTok names NONE or PARENT and a saved isMinor of
     * exactly false. A missing role or details, or a role this doesn't know, is a no.
     */
    static boolean adultFrom(@Nullable Object role, @Nullable Object details) throws ReflectiveOperationException {
        if (!(role instanceof Enum)) return false;
        String name = ((Enum<?>) role).name();
        if (!ROLE_NOT_PAIRED.equals(name) && !ROLE_PARENT.equals(name)) return false;
        if (details == null) return false;
        Field field = details.getClass().getField("isMinor");
        return Boolean.FALSE.equals(field.get(details));
    }

    private static boolean tiktokSaysAdult() {
        // Signed out, the saved details can be a previous account's.
        String user = SignedInUser.id();
        if (user == null) return false;
        Lookups found = lookups();
        if (found == null) return false;
        try {
            Object service = found.service;
            if (service == null) {
                service = found.spi.invoke(null, found.serviceClass);
                if (service == null) return false;
                found.service = service;
            }
            Object details = found.details.invoke(service);
            return detailsAreFor(user, details) && adultFrom(found.role.invoke(found.pairing), details);
        } catch (Throwable unreadable) {
            // The service may still be starting; the next check asks again.
            return false;
        }
    }

    /** The saved details last seen, and who was signed in when that copy first showed up. */
    private static Object detailsSeen;
    private static String detailsOwner;

    /**
     * Whether {@code details} are {@code user}'s. They carry no account id, but TikTok keeps one
     * copy and swaps it for a new one when its server answers again, as it does after a switch of
     * account. So a copy belongs to whoever was signed in when it first showed up, and after a
     * switch the old copy is no one's until TikTok has the new account's.
     */
    static synchronized boolean detailsAreFor(String user, @Nullable Object details) {
        if (details == null) return false;
        if (details != detailsSeen) {
            detailsSeen = details;
            detailsOwner = user;
        }
        return user.equals(detailsOwner);
    }

    private static final class Lookups {
        final Object pairing;
        final Method role;
        final Method spi;
        final Class<?> serviceClass;
        final Method details;
        volatile Object service;

        Lookups(Object pairing, Method role, Method spi, Class<?> serviceClass, Method details) {
            this.pairing = pairing;
            this.role = role;
            this.spi = spi;
            this.serviceClass = serviceClass;
            this.details = details;
        }
    }

    private static Lookups lookups() {
        Lookups found = lookups;
        if (found != null || unavailable) return found;
        try {
            Class<?> pairingClass = Class.forName(FAMILY_PAIRING_CLASS);
            Class<?> serviceClass = Class.forName(PROTECTION_SERVICE_CLASS);
            Class<?> detailsClass = Class.forName(USER_DETAILS_CLASS);
            Object pairing = ownInstance(pairingClass);
            Method role = noArgumentMethod(pairingClass, null);
            Method details = noArgumentMethod(serviceClass, detailsClass);
            if (pairing == null || role == null || details == null) {
                unavailable = true;
                return null;
            }
            found = new Lookups(pairing, role, Class.forName(SPI_CLASS).getMethod("pluggableSpi", Class.class),
                    serviceClass, details);
            lookups = found;
            return found;
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            // A build where these moved. Nothing will find them later either.
            unavailable = true;
            return null;
        } catch (Throwable early) {
            return null;
        }
    }

    /** The class's one static field holding an instance of itself, or null. */
    @Nullable
    static Object ownInstance(Class<?> type) throws IllegalAccessException {
        Field only = null;
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            if (only != null) return null;
            only = field;
        }
        if (only == null) return null;
        only.setAccessible(true);
        return only.get(null);
    }

    /**
     * The type's one public instance method taking nothing and returning {@code returns}, or, when
     * that is null, an enum with a CHILD constant (Family Pairing's role). Null when there isn't
     * exactly one.
     */
    @Nullable
    static Method noArgumentMethod(Class<?> type, @Nullable Class<?> returns) {
        Method only = null;
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0) continue;
            Class<?> result = method.getReturnType();
            boolean matches = returns != null ? result == returns
                    : Enum.class.isAssignableFrom(result) && hasConstant(result, "CHILD");
            if (!matches) continue;
            if (only != null) return null;
            only = method;
        }
        return only;
    }

    /**
     * By its field, not getEnumConstants(): that calls values(), and neither it nor the class's enum
     * flag has to survive TikTok's shrinking. The constants' own fields and names do.
     */
    private static boolean hasConstant(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            return Modifier.isStatic(field.getModifiers()) && field.getType() == type;
        } catch (NoSuchFieldException absent) {
            return false;
        }
    }

    static synchronized void resetForTests() {
        lookups = null;
        unavailable = false;
        adultCheck = WindDownScreens::tiktokSaysAdult;
        detailsSeen = null;
        detailsOwner = null;
    }
}
