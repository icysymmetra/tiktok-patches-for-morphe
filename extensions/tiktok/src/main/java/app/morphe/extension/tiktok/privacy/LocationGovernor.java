/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Location reads answered with nothing while the switch is on.
 *
 * <p>The last known location comes back null and an update request is dropped on the floor, so
 * its listener never hears anything. A read before the settings context exists takes the switch
 * straight from the saved file.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class LocationGovernor {
    /** {@link Settings#BLOCK_LOCATION}'s key, for the read before the settings context. */
    static final String SWITCH_KEY = "block_location";

    private static boolean blocks(String what) {
        boolean on = Utils.getContext() != null ? Settings.BLOCK_LOCATION.get() : EarlySwitch.isOn(SWITCH_KEY);
        if (!on) return false;
        Logger.printInfo(() -> "Location governor: blocked " + what);
        return true;
    }

    public static Location interceptGetLastKnownLocation(LocationManager manager, String provider) {
        if (blocks("getLastKnownLocation for " + provider)) return null;
        return manager.getLastKnownLocation(provider);
    }

    public static void interceptRequestSingleUpdate(LocationManager manager, String provider,
            LocationListener listener, Looper looper) {
        if (blocks("requestSingleUpdate for " + provider)) return;
        manager.requestSingleUpdate(provider, listener, looper);
    }

    public static void interceptRequestLocationUpdates(LocationManager manager, long minTime,
            float minDistance, Criteria criteria, LocationListener listener, Looper looper) {
        if (blocks("requestLocationUpdates by criteria")) return;
        manager.requestLocationUpdates(minTime, minDistance, criteria, listener, looper);
    }

    public static void interceptRequestLocationUpdates(LocationManager manager, String provider,
            long minTime, float minDistance, LocationListener listener) {
        if (blocks("requestLocationUpdates for " + provider)) return;
        manager.requestLocationUpdates(provider, minTime, minDistance, listener);
    }

    public static void interceptRequestLocationUpdates(LocationManager manager, String provider,
            long minTime, float minDistance, LocationListener listener, Looper looper) {
        if (blocks("requestLocationUpdates for " + provider)) return;
        manager.requestLocationUpdates(provider, minTime, minDistance, listener, looper);
    }

    private LocationGovernor() {}
}
