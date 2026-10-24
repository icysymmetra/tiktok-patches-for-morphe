/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Motion sensor registrations refused while the switch is on.
 *
 * <p>Six sensor types are refused: the ones an app reads to fingerprint a phone or to track how
 * it is being held, none of which a feed needs. Every other sensor, and every registration while
 * the switch is off, goes through to the real manager. A registration before the settings
 * context exists takes the switch straight from the saved file.
 */
@SuppressWarnings("unused")
public final class ResourceBatteryGovernor {
    /** {@link Settings#BLOCK_MOTION_SENSORS}'s key, for the read before the settings context. */
    static final String SWITCH_KEY = "block_motion_sensors";

    private static boolean blocks(Sensor sensor) {
        if (sensor == null) return false;
        int type = sensor.getType();
        boolean motion = type == Sensor.TYPE_ACCELEROMETER || type == Sensor.TYPE_GYROSCOPE
                || type == Sensor.TYPE_MAGNETIC_FIELD || type == Sensor.TYPE_ROTATION_VECTOR
                || type == Sensor.TYPE_LINEAR_ACCELERATION || type == Sensor.TYPE_GRAVITY;
        if (!motion) return false;
        boolean on = Utils.getContext() != null ? Settings.BLOCK_MOTION_SENSORS.get() : EarlySwitch.isOn(SWITCH_KEY);
        if (!on) return false;
        Logger.printInfo(() -> "Resource governor: blocked sensor registration for type " + type);
        return true;
    }

    public static boolean interceptSensorRegistration(
            SensorManager manager, SensorEventListener listener, Sensor sensor, int delay) {
        if (blocks(sensor)) return false;
        return manager.registerListener(listener, sensor, delay);
    }

    public static boolean interceptSensorRegistration(
            SensorManager manager, SensorEventListener listener, Sensor sensor, int delay, Handler handler) {
        if (blocks(sensor)) return false;
        return manager.registerListener(listener, sensor, delay, handler);
    }

    public static boolean interceptSensorRegistration(
            SensorManager manager, SensorEventListener listener, Sensor sensor, int delay, int maxLatency) {
        if (blocks(sensor)) return false;
        return manager.registerListener(listener, sensor, delay, maxLatency);
    }

    private ResourceBatteryGovernor() {}
}
