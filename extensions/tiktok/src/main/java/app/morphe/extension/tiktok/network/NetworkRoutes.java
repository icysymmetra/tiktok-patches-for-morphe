/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.network;

import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Which of the connections in play go through the network proxy, in the exported report.
 *
 * <p>Four routes behave differently and a reader can't tell them apart from outside: TikTok's
 * own API stack and the JVM's default proxy selector follow the proxy, the native player opens
 * its own sockets, and Hushfeed's saves and short-link lookups deliberately connect direct to an
 * address they checked first. The proxy's host, port and sign-in are never written here.
 */
public final class NetworkRoutes implements LogBufferManager.ReportSection {
    static final String TITLE = "NETWORK ROUTES";
    private static final NetworkRoutes INSTANCE = new NetworkRoutes();

    NetworkRoutes() {}

    /** Registered once, at settings load, so every export from then on carries the section. */
    public static void install() {
        LogBufferManager.registerReportSection(INSTANCE);
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        // A bundle without the proxy patch has no proxy scope to describe.
        if (!SettingsStatus.networkProxyEnabled) return lines;
        NetworkProxy.Config active = NetworkProxy.active();
        String type = active == null ? null : active.socks() ? "SOCKS5" : "HTTP";
        lines.add("proxy: " + (type == null ? "off in this process" : "on, " + type));
        lines.add("tiktok_api_and_java_connections: "
                + (type == null ? "direct" : "through the proxy")
                + " (TikTok's own network stack and the JVM's default proxy selector)");
        lines.add("tiktok_native_player: direct (videos and LIVE streams open their own sockets)");
        lines.add("hushfeed_saves: direct, to a checked public address, TLS to the original host,"
                + " never the proxy selector");
        lines.add("short_link_expansion: direct, same checks, "
                + (Settings.EXPAND_SHORT_SHARE_LINKS.get() ? "switch on" : "switch off"));
        lines.add("proxy_sign_in: Java connections only, never TikTok's native stack");
        return lines;
    }
}
