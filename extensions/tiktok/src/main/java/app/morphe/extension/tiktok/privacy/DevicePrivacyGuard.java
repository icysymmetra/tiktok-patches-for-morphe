/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.net.NetworkCapabilities;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Method;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The reads TikTok makes about this device, answered without handing it the real value while the
 * matching switch is on: what you copied, whether you're on a VPN, and the advertising id with
 * its limit ad tracking flag, and the reviewed root status and root-tool diagnostics. A new
 * device identity ({@link DeviceIdentity}) also answers the Android ID and the advertising id.
 *
 * <p>One method per intercepted signature, each returning what the call site expects. A single
 * helper returning {@link ClipData} for all three clipboard reads handed a ClipData back where
 * getText's CharSequence and hasPrimaryClip's boolean were expected, and the verifier rejects that
 * class as soon as it loads rather than failing at the call. The VPN and advertising-id intercepts
 * keep to the same rule.
 *
 * <p>Each switch is read at the call and answers off while Hushfeed is paused. The clipboard,
 * VPN and advertising-id switches need no restart. Root collectors can retain an earlier answer,
 * so changing the root switch needs a restart. A new device identity isn't a switch and holds
 * through a pause too: the phone's own ids, handed over once, would tie it to the old identity.
 *
 * <p>TikTok checks its connection and its ad SDKs read the id early in startup. A read before
 * the settings context exists takes the switch straight from the saved file
 * ({@link EarlySwitch}), since reading a setting then would load Settings without its
 * preferences, so a switch that's on covers those reads too.
 */
@SuppressWarnings("unused")
public final class DevicePrivacyGuard {
    /** {@link Settings#BLOCK_CLIPBOARD_READS}'s key, for the read before the settings context. */
    static final String CLIPBOARD_KEY = "block_clipboard_reads";
    /** {@link Settings#HIDE_VPN}'s key. */
    static final String VPN_KEY = "hide_vpn";
    /** {@link Settings#BLOCK_ADVERTISING_ID}'s key. */
    static final String ADVERTISING_ID_KEY = "block_advertising_id";
    /** {@link Settings#HIDE_ROOT}'s key. */
    static final String ROOT_KEY = "hide_root";

    /** What Android hands back for the advertising id once the user has deleted it. */
    private static final String BLANK_ADVERTISING_ID = "00000000-0000-0000-0000-000000000000";

    private static boolean blocks(String what) {
        boolean on = Utils.getContext() != null ? Settings.BLOCK_CLIPBOARD_READS.get() : EarlySwitch.isOn(CLIPBOARD_KEY);
        if (!on) return false;
        Logger.printInfo(() -> "Device privacy guard: " + what);
        return true;
    }

    public static ClipData interceptPrimaryClip(ClipboardManager manager) {
        if (blocks("blocked a getPrimaryClip read")) return null;
        return manager.getPrimaryClip();
    }

    public static CharSequence interceptClipboardText(ClipboardManager manager) {
        if (blocks("blocked a getText read")) return "";
        return manager.getText();
    }

    public static boolean interceptHasPrimaryClip(ClipboardManager manager) {
        if (blocks("answered hasPrimaryClip as false")) return false;
        return manager.hasPrimaryClip();
    }

    // --- VPN ---

    private static boolean hidesVpn(String what) {
        boolean on = Utils.getContext() != null ? Settings.HIDE_VPN.get() : EarlySwitch.isOn(VPN_KEY);
        if (!on) return false;
        Logger.printInfo(() -> "Device privacy guard: " + what);
        return true;
    }

    /**
     * Only the VPN transport is answered false, and only while the switch is on. Every other
     * transport, and the real VPN answer while the switch is off, comes straight from the system.
     */
    public static boolean interceptHasTransport(NetworkCapabilities capabilities, int transport) {
        if (transport == NetworkCapabilities.TRANSPORT_VPN && hidesVpn("answered hasTransport(VPN) as false")) {
            return false;
        }
        return capabilities.hasTransport(transport);
    }

    /**
     * The interface list with the tunnel devices a VPN adds taken out, so code that walks the list
     * looking for one never sees it. Every other interface stays, and with the switch off the real
     * list comes back untouched.
     */
    public static Enumeration<NetworkInterface> interceptNetworkInterfaces() throws SocketException {
        Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
        if (all == null || !hidesVpn("hid the VPN network interfaces")) return all;
        ArrayList<NetworkInterface> kept = new ArrayList<>();
        while (all.hasMoreElements()) {
            NetworkInterface candidate = all.nextElement();
            if (!isVpnInterfaceName(candidate.getName())) kept.add(candidate);
        }
        return Collections.enumeration(kept);
    }

    /** tun, ppp, ipsec and wg are the names a VPN tunnel interface takes. */
    static boolean isVpnInterfaceName(String name) {
        if (name == null) return false;
        return name.startsWith("tun") || name.startsWith("ppp")
                || name.startsWith("ipsec") || name.startsWith("wg");
    }

    // --- Advertising id ---

    /** Whether the advertising id switch is on, read at the call, and off while Hushfeed is paused. */
    private static boolean blocksAdvertisingId(String what) {
        boolean on = Utils.getContext() != null
                ? Settings.BLOCK_ADVERTISING_ID.get() : EarlySwitch.isOn(ADVERTISING_ID_KEY);
        if (!on) return false;
        Logger.printInfo(() -> "Device privacy guard: " + what);
        return true;
    }

    /**
     * The advertising id lookup, answered with the blank id Android gives once the user has deleted
     * it while the switch is on. The call site hands its {@code AdvertisingIdClient.Info} in as an
     * Object, since the extension does not compile against Play Services; the real id is read back
     * off it by reflection only when the switch is off.
     */
    public static String interceptAdvertisingId(Object info) {
        if (blocksAdvertisingId("answered the advertising id as blank")) return BLANK_ADVERTISING_ID;
        String identity = identityAdvertisingId();
        return identity != null ? identity : realAdvertisingId(info);
    }

    /**
     * An id read without Info.getId: off the reply of Google's advertising id service, which two
     * SDKs inside TikTok ask directly, or off Info's id field. The blank id while the switch is on,
     * a new identity's own id while there is one, the id the read produced otherwise.
     */
    public static String interceptAdvertisingIdRead(String id) {
        if (blocksAdvertisingId("answered a direct advertising id read as blank")) return BLANK_ADVERTISING_ID;
        String identity = identityAdvertisingId();
        return identity != null ? identity : id;
    }

    /**
     * A new device identity's advertising id. TikTok's server hands an old device id back for the
     * phone's real advertising id, so with the switch off an identity still never shows it.
     */
    private static String identityAdvertisingId() {
        String identity = DeviceIdentity.advertisingId();
        if (identity != null) {
            Logger.printDebug(() -> "Device privacy guard: answered the advertising id with the new identity's");
        }
        return identity;
    }

    // --- Android ID ---

    /**
     * TikTok's Settings.Secure reads, the Android ID among them, which every part of TikTok makes
     * through one wrapper. A new device identity's Android ID while there is one; every other
     * name, and the Android ID without an identity, comes straight from the system.
     */
    public static String interceptSecureString(ContentResolver resolver, String name) {
        String identity = identityAndroidId(name);
        return identity != null ? identity : android.provider.Settings.Secure.getString(resolver, name);
    }

    /** The same for Settings.System, where old Android versions also answered the Android ID. */
    @SuppressWarnings("deprecation")
    public static String interceptSystemString(ContentResolver resolver, String name) {
        String identity = identityAndroidId(name);
        return identity != null ? identity : android.provider.Settings.System.getString(resolver, name);
    }

    private static String identityAndroidId(String name) {
        if (!android.provider.Settings.Secure.ANDROID_ID.equals(name)) return null;
        String identity = DeviceIdentity.androidId();
        if (identity != null) {
            Logger.printDebug(() -> "Device privacy guard: answered the Android ID with the new identity's");
        }
        return identity;
    }

    /**
     * Whether ad tracking is limited, as the service's reply carries it: an int, nonzero for
     * limited. 1 while the switch is on, which is what Android answers once the user has deleted
     * the id or opted out of ads personalization.
     */
    public static int interceptLimitAdTrackingReply(int limited) {
        if (blocksAdvertisingId("answered a direct limit ad tracking read as limited")) return 1;
        return limited;
    }

    /**
     * The same flag read off Info's field, where R8 inlined isLimitAdTrackingEnabled: limited
     * while the switch is on, the real answer otherwise.
     */
    public static boolean interceptLimitAdTracking(boolean limited) {
        if (blocksAdvertisingId("answered limit ad tracking as limited")) return true;
        return limited;
    }

    /** Only the five reviewed root methods ask this before running their original probes. */
    public static boolean hideRoot() {
        boolean on = Utils.getContext() != null ? Settings.HIDE_ROOT.get() : EarlySwitch.isOn(ROOT_KEY);
        if (on) Logger.printInfo(() -> "Device privacy guard: hid a root check");
        return on;
    }

    /** Native JSONObject(Map) consumers require the original value types and nested schema. */
    public static Map<String, Object> hiddenRootPackages() {
        return rootSummary("rootPackage");
    }

    public static Map<String, Object> hiddenRootPaths() {
        return rootSummary("rootPath");
    }

    private static Map<String, Object> rootSummary(String kind) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hit", 0);
        summary.put("unknown", 0);
        summary.put(kind, false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("items", new ArrayList<>());
        return result;
    }

    public static Map<String, Object> hiddenWhichSu() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", "not_detected");
        result.put("path", "");
        return result;
    }

    public static Map<String, Object> hiddenSuCommand() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", "not_detected");
        // TikTok serializes String.valueOf(boolean), not a Boolean, for this field.
        result.put("uidZero", "false");
        return result;
    }

    private static String realAdvertisingId(Object info) {
        if (info == null) return null;
        try {
            Method getId = info.getClass().getMethod("getId");
            Object value = getId.invoke(info);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private DevicePrivacyGuard() {}
}
