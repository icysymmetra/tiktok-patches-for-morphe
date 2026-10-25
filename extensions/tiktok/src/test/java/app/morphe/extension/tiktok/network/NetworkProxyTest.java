package app.morphe.extension.tiktok.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.text.InputType;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsBackup;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Network proxy: what the settings accept, the rule TTNet's engine builder gets, the selector and
 * sign-in the JVM gets, the start-up check, and that it all stays out of the way while the switch
 * is off, while Hushfeed is paused, before the extension has a context and on a bundle without
 * the patch. The user name and password never appear in anything this writes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class NetworkProxyTest {
    public static final class TestActivity extends PreferenceActivity {}

    private static final String USER = "alice";
    private static final String PASSWORD = "s3cret-pass";
    private static final int TIMEOUT_MS = 2000;

    /** Built on call: touching Settings in a static initializer runs before setUp gives it a context. */
    private static StringSetting[] fields() {
        return new StringSetting[] {
                Settings.NETWORK_PROXY_TYPE, Settings.NETWORK_PROXY_HOST, Settings.NETWORK_PROXY_PORT,
                Settings.NETWORK_PROXY_USER, Settings.NETWORK_PROXY_PASSWORD,
        };
    }

    private Context context;
    private ProxySelector originalSelector;
    private boolean simSpoof;
    private boolean regionSpoof;
    private final List<ServerSocket> servers = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        originalSelector = ProxySelector.getDefault();
        simSpoof = SettingsStatus.simSpoofEnabled;
        regionSpoof = SettingsStatus.regionSpoofEnabled;
        SettingsStatus.networkProxyEnabled = true;
        NetworkProxy.resetForTests();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        for (ServerSocket server : servers) server.close();
        Utils.setContext(context);
        setPaused(false);
        ProxySelector.setDefault(originalSelector);
        Authenticator.setDefault(null);
        SettingsStatus.networkProxyEnabled = false;
        SettingsStatus.simSpoofEnabled = simSpoof;
        SettingsStatus.regionSpoofEnabled = regionSpoof;
        Settings.NETWORK_PROXY.resetToDefault();
        for (StringSetting field : fields()) field.resetToDefault();
        NetworkProxy.resetForTests();
    }

    // -- The settings -----------------------------------------------------------------------------

    @Test public void offByDefaultAndEveryFieldWaitsForARestart() {
        assertFalse(Settings.NETWORK_PROXY.defaultValue);
        assertEquals(NetworkProxy.TYPE_HTTP, Settings.NETWORK_PROXY_TYPE.defaultValue);
        assertTrue(Settings.NETWORK_PROXY.rebootApp);
        for (StringSetting field : fields()) {
            assertTrue(field.key + " restarts", field.rebootApp);
            assertEquals(field.key + " starts empty", field == Settings.NETWORK_PROXY_TYPE ? "http" : "",
                    field.defaultValue);
        }
    }

    @Test public void theUserNameAndPasswordStayOutOfBackups() {
        assertTrue(Settings.NETWORK_PROXY.includeWithImportExport);
        assertTrue(Settings.NETWORK_PROXY_TYPE.includeWithImportExport);
        assertTrue(Settings.NETWORK_PROXY_HOST.includeWithImportExport);
        assertTrue(Settings.NETWORK_PROXY_PORT.includeWithImportExport);
        assertFalse(Settings.NETWORK_PROXY_USER.includeWithImportExport);
        assertFalse(Settings.NETWORK_PROXY_PASSWORD.includeWithImportExport);
    }

    @Test public void hostsAreNamesOrAddressesAndNothingElse() {
        assertEquals("proxy.example.com", NetworkProxy.normalizeHost("proxy.example.com"));
        assertEquals("proxy.example.com", NetworkProxy.normalizeHost("  proxy.example.com. "));
        assertEquals("192.168.1.20", NetworkProxy.normalizeHost("192.168.1.20"));
        assertEquals("localhost", NetworkProxy.normalizeHost("localhost"));
        assertEquals("::1", NetworkProxy.normalizeHost("[::1]"));
        assertEquals("fe80::1%wlan0", NetworkProxy.normalizeHost("fe80::1%wlan0"));
        assertEquals("2001:db8::7", NetworkProxy.normalizeHost("2001:db8::7"));

        for (String refused : new String[]{null, "", "  ", "http://proxy.example.com", "proxy.example.com:8080",
                "alice@proxy.example.com", "a;b", "a=b", "proxy example", "-proxy.com", "proxy-.com", "a..b",
                "256.1.1.1", "1.2.3.999", ".", "[]", "proxy/path"}) {
            assertNull(refused, NetworkProxy.normalizeHost(refused));
        }
        assertNull(NetworkProxy.hostProblem("proxy.example.com"));
        assertNotNull(NetworkProxy.hostProblem("http://proxy.example.com"));
        assertNotNull(NetworkProxy.hostProblem(" a;b "));
    }

    @Test public void clearingTheHostOrPortTurnsTheProxyOff() {
        for (String empty : new String[]{"", "   ", null}) {
            assertNull("an empty host is taken", NetworkProxy.hostProblem(empty));
            assertNull("an empty port is taken", NetworkProxy.portProblem(empty));
        }

        turnOn("http", "proxy.example.com", "8080", "", "");
        assertNotNull(NetworkProxy.configured());
        Settings.NETWORK_PROXY_HOST.save("");
        assertNull("no host, no proxy", NetworkProxy.configured());
        assertSame("", NetworkProxy.ttnetConfig(""));
        Settings.NETWORK_PROXY_HOST.save("proxy.example.com");
        Settings.NETWORK_PROXY_PORT.save(" ");
        assertNull("no port, no proxy", NetworkProxy.configured());
        NetworkProxy.install(context);
        assertNull(NetworkProxy.active());
        assertSame(originalSelector, ProxySelector.getDefault());

        // The rows' editors save an empty field and still refuse a bad one.
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Context activity = controller.get();
            InputTextPreference host = new InputTextPreference(activity, "Proxy host",
                    "The proxy's address, like 192.168.1.20 or proxy.example.com.",
                    Settings.NETWORK_PROXY_HOST).withCheck(NetworkProxy::hostProblem);
            InputTextPreference port = new InputTextPreference(activity, "Proxy port",
                    "The port the proxy listens on, like 8080 or 1080.",
                    Settings.NETWORK_PROXY_PORT).withCheck(NetworkProxy::portProblem);
            assertTrue(host.callChangeListener(""));
            assertTrue(port.callChangeListener(""));
            assertFalse(host.callChangeListener("http://proxy.example.com"));
            assertFalse(port.callChangeListener("0"));
        }
    }

    @Test public void portsAreWholeNumbersInRange() {
        assertEquals(1, NetworkProxy.parsePort("1"));
        assertEquals(8080, NetworkProxy.parsePort(" 8080 "));
        assertEquals(65535, NetworkProxy.parsePort("65535"));
        for (String refused : new String[]{null, "", "0", "65536", "-1", "80a", "8 0", "+80", "123456", "1.5"}) {
            assertEquals(String.valueOf(refused), -1, NetworkProxy.parsePort(refused));
        }
        assertNull(NetworkProxy.portProblem("1080"));
        assertNotNull(NetworkProxy.portProblem("70000"));
        assertNotNull(NetworkProxy.portProblem(" 8 0 "));
    }

    @Test public void parseNeedsATypeAHostAndAPort() {
        NetworkProxy.Config http = NetworkProxy.parse("http", "proxy.example.com", "8080", "", "");
        assertNotNull(http);
        assertFalse(http.socks());
        assertFalse(http.hasCredentials());
        NetworkProxy.Config socks = NetworkProxy.parse(" SOCKS5 ", "10.0.0.2", "1080", null, null);
        assertNotNull(socks);
        assertTrue(socks.socks());

        assertNull(NetworkProxy.parse("https", "proxy.example.com", "8080", "", ""));
        assertNull(NetworkProxy.parse(null, "proxy.example.com", "8080", "", ""));
        assertNull(NetworkProxy.parse("http", "", "8080", "", ""));
        assertNull(NetworkProxy.parse("http", "proxy.example.com", "", "", ""));

        // A password with no user name has nothing to sign in as.
        NetworkProxy.Config passwordOnly = NetworkProxy.parse("http", "proxy.example.com", "8080", " ", PASSWORD);
        assertNotNull(passwordOnly);
        assertFalse(passwordOnly.hasCredentials());
        assertEquals("", passwordOnly.password);
    }

    @Test public void theRulesAreChromiumsShape() {
        assertEquals("http=proxy.example.com:8080;https=proxy.example.com:8080",
                NetworkProxy.parse("http", "proxy.example.com", "8080", "", "").ttnetRules());
        assertEquals("socks5://10.0.0.2:1080",
                NetworkProxy.parse("socks5", "10.0.0.2", "1080", "", "").ttnetRules());
        assertEquals("http=[::1]:8080;https=[::1]:8080",
                NetworkProxy.parse("http", "[::1]", "8080", "", "").ttnetRules());
    }

    @Test public void credentialsAreNeverWrittenOut() throws Exception {
        NetworkProxy.Config config = NetworkProxy.parse("socks5", "10.0.0.2", "1080", USER, PASSWORD);
        assertNotNull(config);
        assertTrue(config.hasCredentials());
        for (String written : new String[]{config.ttnetRules(), config.describe(), config.hostPort(),
                config.javaProxy().toString()}) {
            assertFalse(written, written.contains(USER));
            assertFalse(written, written.contains(PASSWORD));
        }

        turnOn("socks5", "10.0.0.2", "1080", USER, PASSWORD);
        String handed = NetworkProxy.ttnetConfig("");
        assertFalse(handed.contains(USER));
        assertFalse(handed.contains(PASSWORD));
    }

    // -- TTNet ----------------------------------------------------------------------------------------

    @Test public void offTtnetGetsItsOwnConfigBack() {
        String original = "";
        assertSame(original, NetworkProxy.ttnetConfig(original));
        assertNull(NetworkProxy.ttnetConfig(null));
        // Filled in but off is still off.
        Settings.NETWORK_PROXY_HOST.save("proxy.example.com");
        Settings.NETWORK_PROXY_PORT.save("8080");
        String config = "{\"other\":1}";
        assertSame(config, NetworkProxy.ttnetConfig(config));
    }

    @Test public void onAnEmptyConfigBecomesOneCarryingTheProxy() throws Exception {
        turnOn("http", "proxy.example.com", "8080", "", "");
        assertEquals("http=proxy.example.com:8080;https=proxy.example.com:8080",
                new JSONObject(NetworkProxy.ttnetConfig("")).getString("ttnet_proxy"));
        assertEquals("http=proxy.example.com:8080;https=proxy.example.com:8080",
                new JSONObject(NetworkProxy.ttnetConfig(null)).getString("ttnet_proxy"));
        assertEquals(1, new JSONObject(NetworkProxy.ttnetConfig("  ")).length());
    }

    @Test public void onAConfigKeepsEverythingElseInIt() throws Exception {
        turnOn("socks5", "10.0.0.2", "1080", "", "");
        JSONObject handed = new JSONObject(NetworkProxy.ttnetConfig(
                "{\"boe_proxy_enabled\":false,\"ttnet_proxy\":\"http=old:1;https=old:1\",\"other\":\"x\"}"));
        assertEquals("socks5://10.0.0.2:1080", handed.getString("ttnet_proxy"));
        assertFalse(handed.getBoolean("boe_proxy_enabled"));
        assertEquals("x", handed.getString("other"));
    }

    @Test public void aConfigThatDoesntParseGoesBackUntouched() {
        turnOn("http", "proxy.example.com", "8080", "", "");
        String broken = "{not json";
        assertSame(broken, NetworkProxy.ttnetConfig(broken));
    }

    @Test public void pausedTtnetGetsItsOwnConfigBack() {
        turnOn("http", "proxy.example.com", "8080", "", "");
        setPaused(true);
        assertSame("", NetworkProxy.ttnetConfig(""));
    }

    @Test public void withoutAContextTtnetGetsItsOwnConfigBack() {
        turnOn("http", "proxy.example.com", "8080", "", "");
        Utils.setContext(null);
        try {
            assertSame("", NetworkProxy.ttnetConfig(""));
        } finally {
            Utils.setContext(context);
        }
    }

    @Test public void withoutThePatchTtnetGetsItsOwnConfigBack() {
        turnOn("http", "proxy.example.com", "8080", "", "");
        SettingsStatus.networkProxyEnabled = false;
        assertSame("", NetworkProxy.ttnetConfig(""));
        assertNull(NetworkProxy.configured());
    }

    @Test public void anUnusableProxyLeavesTtnetAlone() {
        turnOn("http", "http://proxy.example.com", "8080", "", "");
        assertSame("", NetworkProxy.ttnetConfig(""));
        turnOn("http", "proxy.example.com", "0", "", "");
        assertSame("", NetworkProxy.ttnetConfig(""));
    }

    // -- The JVM --------------------------------------------------------------------------------------

    @Test public void offInstallLeavesTheJvmAlone() throws Exception {
        NetworkProxy.install(context);
        assertTrue(NetworkProxy.isInstalled());
        assertNull(NetworkProxy.active());
        assertSame(originalSelector, ProxySelector.getDefault());
        assertNull(Authenticator.requestPasswordAuthentication("10.0.0.2", null, 1080, "SOCKS5",
                "", "", null, Authenticator.RequestorType.SERVER));

        // Once a process: turning it on later waits for the restart.
        turnOn("http", "proxy.example.com", "8080", "", "");
        NetworkProxy.install(context);
        assertNull(NetworkProxy.active());
        assertSame(originalSelector, ProxySelector.getDefault());
    }

    @Test public void theReportNamesFourRoutesAndLeavesTheProxyDetailsOut() throws Exception {
        String joined = String.join(" | ", new NetworkRoutes().lines());
        assertTrue("off in this process says so", joined.contains("proxy: off in this process"));
        assertTrue(joined.contains("tiktok_api_and_java_connections: direct"));

        ServerSocket server = serve(socket -> {
            readFully(socket.getInputStream(), 3);
            socket.getOutputStream().write(new byte[]{5, 0});
        });
        turnOn("socks5", "127.0.0.1", String.valueOf(server.getLocalPort()), USER, PASSWORD);
        NetworkProxy.install(context);
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();

        joined = String.join(" | ", new NetworkRoutes().lines());
        assertTrue(joined.contains("proxy: on, SOCKS5"));
        assertTrue(joined.contains("tiktok_api_and_java_connections: through the proxy"));
        assertTrue(joined.contains("tiktok_native_player: direct"));
        assertTrue(joined.contains("hushfeed_saves: direct, to a checked public address"));
        assertTrue(joined.contains("never the proxy selector"));
        assertTrue(joined.contains("short_link_expansion: direct, same checks, switch off"));
        assertTrue(joined.contains("proxy_sign_in: Java connections only"));
        assertFalse(joined.contains("127.0.0.1"));
        assertFalse(joined.contains(USER));
        assertFalse(joined.contains(PASSWORD));
        assertEquals(NetworkRoutes.TITLE, new NetworkRoutes().title());
    }

    @Test public void aBundleWithoutTheProxyPatchHasNoRoutesToReport() {
        SettingsStatus.networkProxyEnabled = false;
        assertTrue(new NetworkRoutes().lines().isEmpty());
    }

    @Test public void pausedInstallLeavesTheJvmAlone() {
        turnOn("http", "proxy.example.com", "8080", "", "");
        setPaused(true);
        NetworkProxy.install(context);
        assertNull(NetworkProxy.active());
        assertSame(originalSelector, ProxySelector.getDefault());
    }

    @Test public void onInstallRoutesJavaConnectionsAndSignsInToTheProxyOnly() throws Exception {
        ServerSocket server = serve(socket -> {
            readFully(socket.getInputStream(), 3);
            socket.getOutputStream().write(new byte[]{5, 0});
        });
        String port = String.valueOf(server.getLocalPort());
        turnOn("socks5", "127.0.0.1", port, USER, PASSWORD);

        NetworkProxy.install(context);
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();

        assertNotNull(NetworkProxy.active());
        ProxySelector selector = ProxySelector.getDefault();
        assertTrue(selector instanceof NetworkProxy.Selector);
        Proxy proxy = selector.select(new URI("https://api16-normal-useast5.tiktokv.us/aweme/v1/feed/")).get(0);
        assertEquals(Proxy.Type.SOCKS, proxy.type());
        assertEquals(InetSocketAddress.createUnresolved("127.0.0.1", server.getLocalPort()), proxy.address());

        PasswordAuthentication signIn = Authenticator.requestPasswordAuthentication("127.0.0.1",
                InetAddress.getByName("127.0.0.1"), server.getLocalPort(), "SOCKS5", "", "", null,
                Authenticator.RequestorType.SERVER);
        assertNotNull(signIn);
        assertEquals(USER, signIn.getUserName());
        assertEquals(PASSWORD, new String(signIn.getPassword()));
        // A website asking for its own password never gets the proxy's.
        assertNull(Authenticator.requestPasswordAuthentication("www.tiktok.com", null, 443, "https",
                "", "basic", null, Authenticator.RequestorType.SERVER));
        // The proxy answered, so nothing was said.
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void theSelectorCarriesWebTrafficAndLeavesTheLocalNetworkDirect() throws Exception {
        NetworkProxy.Config http = NetworkProxy.parse("http", "proxy.example.com", "8080", "", "");
        NetworkProxy.Selector selector = new NetworkProxy.Selector(http, null);
        Proxy expected = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.com", 8080));
        for (String carried : new String[]{"https://www.tiktok.com/", "http://api.tiktokv.com/x",
                "wss://webcast.tiktok.com/ws", "ws://webcast.tiktok.com/ws"}) {
            assertEquals(carried, Collections.singletonList(expected), selector.select(new URI(carried)));
        }
        for (String direct : new String[]{"http://localhost:8080/", "http://127.0.0.1/", "http://192.168.1.5/",
                "http://10.1.2.3/", "http://172.16.0.9/", "http://169.254.1.1/", "http://[::1]:9000/",
                "http://[fd00::1]/", "http://printer.local/", "https://proxy.example.com/",
                "socket://api.tiktokv.com:443", "ftp://files.example.com/"}) {
            assertEquals(direct, Collections.singletonList(Proxy.NO_PROXY), selector.select(new URI(direct)));
        }

        NetworkProxy.Config socks = NetworkProxy.parse("socks5", "10.0.0.2", "1080", "", "");
        Proxy socksProxy = new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("10.0.0.2", 1080));
        assertEquals(Collections.singletonList(socksProxy),
                new NetworkProxy.Selector(socks, null).select(new URI("socket://api.tiktokv.com:443")));
    }

    @Test public void whatTheProxyDoesntCarryGoesWhereItWentBefore() throws Exception {
        Proxy before = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("corporate", 3128));
        List<URI> failed = new ArrayList<>();
        ProxySelector previous = new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                return Collections.singletonList(before);
            }

            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                failed.add(uri);
            }
        };
        NetworkProxy.Selector selector = new NetworkProxy.Selector(
                NetworkProxy.parse("http", "proxy.example.com", "8080", "", ""), previous);
        assertEquals(Collections.singletonList(before), selector.select(new URI("http://localhost/")));

        URI elsewhere = new URI("http://localhost/");
        selector.connectFailed(elsewhere, InetSocketAddress.createUnresolved("localhost", 80), new IOException());
        assertEquals(Collections.singletonList(elsewhere), failed);
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test public void aFailedConnectionToTheProxySaysSoOnce() throws Exception {
        NetworkProxy.Config config = NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD);
        NetworkProxy.Selector selector = new NetworkProxy.Selector(config, null);
        URI uri = new URI("https://www.tiktok.com/");
        selector.connectFailed(uri, InetSocketAddress.createUnresolved("proxy.example.com", 8080), new IOException());
        selector.connectFailed(uri, InetSocketAddress.createUnresolved("proxy.example.com", 8080), new IOException());
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(1, ShadowToast.shownToastCount());
        String said = ShadowToast.getTextOfLatestToast();
        assertTrue(said, said.contains("proxy.example.com:8080"));
        assertFalse(said, said.contains(USER));
        assertFalse(said, said.contains(PASSWORD));
    }

    @Test public void signInGoesToTheProxyAndNobodyElse() throws Exception {
        NetworkProxy.Config http = NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD);
        PasswordAuthentication answer = NetworkProxy.credentialsFor(http, true, "http", "proxy.example.com", null, 8080);
        assertNotNull(answer);
        assertEquals(USER, answer.getUserName());
        assertEquals(PASSWORD, new String(answer.getPassword()));
        assertNull("a website", NetworkProxy.credentialsFor(http, false, "https", "proxy.example.com", null, 8080));
        assertNull("another host", NetworkProxy.credentialsFor(http, true, "http", "evil.example.com", null, 8080));
        assertNull("another port", NetworkProxy.credentialsFor(http, true, "http", "proxy.example.com", null, 8081));

        NetworkProxy.Config socks = NetworkProxy.parse("socks5", "127.0.0.1", "1080", USER, PASSWORD);
        assertNotNull(NetworkProxy.credentialsFor(socks, false, "SOCKS5", null,
                InetAddress.getByName("127.0.0.1"), 1080));
        assertNull("not the SOCKS handshake", NetworkProxy.credentialsFor(socks, false, "http", "127.0.0.1", null, 1080));

        NetworkProxy.Config anonymous = NetworkProxy.parse("http", "proxy.example.com", "8080", "", "");
        assertNull(NetworkProxy.credentialsFor(anonymous, true, "http", "proxy.example.com", null, 8080));
    }

    // -- The start-up check ---------------------------------------------------------------------------

    @Test public void aSocksProxyWithoutASignInIsReached() throws Exception {
        byte[] greeting = new byte[3];
        ServerSocket server = serve(socket -> {
            readFully(socket.getInputStream(), 3, greeting);
            socket.getOutputStream().write(new byte[]{5, 0});
        });
        assertEquals(NetworkProxy.Probe.REACHED, NetworkProxy.probe(local("socks5", server), TIMEOUT_MS));
        assertEquals("version 5, one method, no sign-in", "[5, 1, 0]", java.util.Arrays.toString(greeting));
    }

    @Test public void aSocksProxyThatWantsASignInIsToldApart() throws Exception {
        ServerSocket server = serve(socket -> {
            readFully(socket.getInputStream(), 3);
            socket.getOutputStream().write(new byte[]{5, (byte) 0xFF});
        });
        assertEquals(NetworkProxy.Probe.NEEDS_PASSWORD, NetworkProxy.probe(local("socks5", server), TIMEOUT_MS));
    }

    @Test public void anHttpProxyThatOpensATunnelIsReached() throws Exception {
        StringBuilder request = new StringBuilder();
        ServerSocket server = serve(socket -> {
            request.append(readHead(socket.getInputStream()));
            socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        });
        assertEquals(NetworkProxy.Probe.REACHED, NetworkProxy.probe(local("http", server), TIMEOUT_MS));
        assertTrue(request.toString(), request.toString().startsWith("CONNECT " + NetworkProxy.PROBE_TARGET + " HTTP/1.1\r\n"));
        assertFalse("the check never signs in", request.toString().contains("Proxy-Authorization"));
    }

    @Test public void anHttpProxyThatWantsASignInIsToldApart() throws Exception {
        ServerSocket server = serve(socket -> {
            readHead(socket.getInputStream());
            socket.getOutputStream().write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
        });
        assertEquals(NetworkProxy.Probe.NEEDS_PASSWORD, NetworkProxy.probe(local("http", server), TIMEOUT_MS));
    }

    @Test public void somethingElseOnThePortIsUnreachable() throws Exception {
        ServerSocket server = serve(socket -> {
            readHead(socket.getInputStream());
            socket.getOutputStream().write("SSH-2.0-OpenSSH_9.6\r\n".getBytes(StandardCharsets.US_ASCII));
        });
        assertEquals(NetworkProxy.Probe.UNREACHABLE, NetworkProxy.probe(local("http", server), TIMEOUT_MS));

        ServerSocket silent = serve(socket -> readFully(socket.getInputStream(), 3));
        assertEquals(NetworkProxy.Probe.UNREACHABLE, NetworkProxy.probe(local("socks5", silent), TIMEOUT_MS));
    }

    @Test public void aClosedPortIsUnreachable() throws Exception {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = closed.getLocalPort();
        }
        NetworkProxy.Config config = NetworkProxy.parse("socks5", "127.0.0.1", String.valueOf(port), "", "");
        assertEquals(NetworkProxy.Probe.UNREACHABLE, NetworkProxy.probe(config, TIMEOUT_MS));
    }

    @Test public void theReportSaysWhichProblemOnceAndNeverTheSignIn() {
        NetworkProxy.Config config = NetworkProxy.parse("http", "proxy.example.com", "8080", USER, PASSWORD);
        NetworkProxy.report(config, NetworkProxy.Probe.REACHED);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, ShadowToast.shownToastCount());

        NetworkProxy.report(config, NetworkProxy.Probe.NEEDS_PASSWORD);
        NetworkProxy.report(config, NetworkProxy.Probe.UNREACHABLE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, ShadowToast.shownToastCount());
        String said = ShadowToast.getTextOfLatestToast();
        assertTrue(said, said.contains("proxy.example.com:8080") && said.contains("password"));
        assertFalse(said, said.contains(USER));
        assertFalse(said, said.contains(PASSWORD));
    }

    @Test public void aProxyThatAnsweredInBetweenIsToldAboutAgain() {
        NetworkProxy.Config config = NetworkProxy.parse("http", "proxy.example.com", "8080", "", "");
        NetworkProxy.report(config, NetworkProxy.Probe.UNREACHABLE);
        NetworkProxy.report(config, NetworkProxy.Probe.UNREACHABLE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, ShadowToast.shownToastCount());

        NetworkProxy.report(config, NetworkProxy.Probe.REACHED);
        NetworkProxy.report(config, NetworkProxy.Probe.UNREACHABLE);
        NetworkProxy.report(config, NetworkProxy.Probe.UNREACHABLE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, ShadowToast.shownToastCount());
    }

    @Test public void aProxyThatStopsAnsweringIsToldOnceAtTheNextCheck() throws Exception {
        ServerSocket server = serve(socket -> {
            readFully(socket.getInputStream(), 3);
            socket.getOutputStream().write(new byte[]{5, 0});
        });
        int port = server.getLocalPort();
        turnOn("socks5", "127.0.0.1", String.valueOf(port), "", "");
        // Counted, so "one toast" can't pass because the second come-back never checked at all.
        int[] checks = {0};
        NetworkProxy.checker = task -> {
            checks[0]++;
            task.run();
        };
        NetworkProxy.install(context);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("the proxy answered at start-up", 0, ShadowToast.shownToastCount());
        assertEquals("the start-up check", 1, checks[0]);

        server.close();
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("too soon after the start-up check to ask again", 0, ShadowToast.shownToastCount());
            assertEquals("too soon after the start-up check to check again", 1, checks[0]);

            for (int comeBack = 0; comeBack < 2; comeBack++) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NetworkProxy.RECHECK_AFTER_MS));
                controller.pause().resume();
                shadowOf(Looper.getMainLooper()).idle();
                assertEquals("come-back " + comeBack + " checks the proxy", 2 + comeBack, checks[0]);
            }
        }
        assertEquals("one outage, one toast", 1, ShadowToast.shownToastCount());
        String said = ShadowToast.getTextOfLatestToast();
        assertTrue(said, said.contains("127.0.0.1:" + port));
    }

    @Test public void resetSettingsClearsTheSignInAndARestoreLeavesIt() throws Exception {
        turnOn("http", "proxy.example.com", "8080", USER, PASSWORD);
        SettingsBackup.restore(context, SettingsBackup.create(false), false);
        assertEquals("a backup never carried this phone's sign-in", USER, Settings.NETWORK_PROXY_USER.get());
        assertEquals(PASSWORD, Settings.NETWORK_PROXY_PASSWORD.get());

        SettingsBackup.reset(context);
        assertFalse(Settings.NETWORK_PROXY.get());
        assertEquals("", Settings.NETWORK_PROXY_HOST.get());
        assertEquals("", Settings.NETWORK_PROXY_USER.get());
        assertEquals("", Settings.NETWORK_PROXY_PASSWORD.get());
    }

    @Test public void aRestoredFileNeverTurnsTheProxyOnOrMovesIt() throws Exception {
        turnOn(NetworkProxy.TYPE_SOCKS5, "theirs.example.com", "1080", "", "");
        String theirs = SettingsBackup.create(false);

        // A phone with no proxy yet, the move to a new phone.
        Settings.NETWORK_PROXY.save(false);
        Settings.NETWORK_PROXY_HOST.save("");
        assertEquals(SettingsBackup.ProxyHold.LEFT_OFF, SettingsBackup.proxyHold(theirs));
        SettingsBackup.restore(context, theirs, false);
        assertFalse("a file can't turn the proxy on", Settings.NETWORK_PROXY.get());
        assertEquals("its address comes along for the user to switch on", "theirs.example.com",
                Settings.NETWORK_PROXY_HOST.get());

        // A phone with its own proxy, on and then off: the file's address never replaces it,
        // so turning the switch on later can't send the sign-in saved here to the file's server.
        turnOn(NetworkProxy.TYPE_HTTP, "mine.example.com", "8080", USER, PASSWORD);
        for (boolean on : new boolean[] {true, false}) {
            Settings.NETWORK_PROXY.save(on);
            assertEquals(SettingsBackup.ProxyHold.KEPT_THIS_PHONES, SettingsBackup.proxyHold(theirs));
            SettingsBackup.restore(context, theirs, false);
            assertEquals(on, Settings.NETWORK_PROXY.get());
            assertEquals("a file can't move this phone's proxy", "mine.example.com", Settings.NETWORK_PROXY_HOST.get());
            assertEquals("8080", Settings.NETWORK_PROXY_PORT.get());
            assertEquals(NetworkProxy.TYPE_HTTP, Settings.NETWORK_PROXY_TYPE.get());
        }

        Settings.NETWORK_PROXY.save(false);
        String off = SettingsBackup.create(false);
        Settings.NETWORK_PROXY.save(true);
        assertEquals(SettingsBackup.ProxyHold.NONE, SettingsBackup.proxyHold(off));
        SettingsBackup.restore(context, off, false);
        assertFalse("a file can still turn it off", Settings.NETWORK_PROXY.get());
    }

    /** Undo reads the phone's own copy, which the guard doesn't hold back. */
    @Test public void undoPutsTheProxyBackExactly() throws Exception {
        turnOn(NetworkProxy.TYPE_HTTP, "mine.example.com", "8080", USER, PASSWORD);
        Settings.NETWORK_PROXY.save(false);
        String off = SettingsBackup.create(false);
        Settings.NETWORK_PROXY.save(true);

        SettingsBackup.restore(context, off, true);
        assertFalse(Settings.NETWORK_PROXY.get());
        SettingsBackup.undo(context);
        assertTrue("undo left the proxy off", Settings.NETWORK_PROXY.get());
        assertEquals("mine.example.com", Settings.NETWORK_PROXY_HOST.get());
    }

    // -- The rows -------------------------------------------------------------------------------------

    @Test public void thePasswordRowNeverShowsThePassword() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Context activity = controller.get();
            InputTextPreference password = new InputTextPreference(activity, "Proxy password",
                    "Optional, used with the user name. It isn't shown, logged or put in a backup.",
                    Settings.NETWORK_PROXY_PASSWORD).withSecret();
            assertTrue(password.getSummary().toString().contains("Empty"));
            password.setText(PASSWORD);
            String summary = password.getSummary().toString();
            assertFalse(summary, summary.contains(PASSWORD));
            assertTrue(summary, summary.contains("••••••"));
            assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    password.getEditText().getInputType() & InputType.TYPE_MASK_VARIATION);

            InputTextPreference host = new InputTextPreference(activity, "Proxy host",
                    "The proxy's address, like 192.168.1.20 or proxy.example.com.", Settings.NETWORK_PROXY_HOST);
            host.setText("proxy.example.com");
            assertTrue(host.getSummary().toString().contains("proxy.example.com"));
        }
    }

    @Test public void aBundleWithOnlyTheProxyShowsOnlyTheProxyRows() {
        SettingsStatus.simSpoofEnabled = false;
        SettingsStatus.regionSpoofEnabled = false;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            PreferenceActivity activity = controller.get();
            Utils.setContext(activity);
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            assertTrue(SimSpoofPreferenceCategory.isAvailable());
            new SimSpoofPreferenceCategory(activity, screen);
            List<String> keys = new ArrayList<>();
            collectKeys(screen, keys);
            assertEquals(java.util.Arrays.asList("network_proxy", "network_proxy_type", "network_proxy_host",
                    "network_proxy_port", "network_proxy_user", "network_proxy_password"), keys);
        } finally {
            Utils.setContext(context);
        }

        SettingsStatus.networkProxyEnabled = false;
        assertFalse(SimSpoofPreferenceCategory.isAvailable());
    }

    // -- Helpers --------------------------------------------------------------------------------------

    private interface Handler {
        void handle(Socket socket) throws IOException;
    }

    /** A one-connection server on this machine that answers the way [handler] says. */
    private ServerSocket serve(Handler handler) throws IOException {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        servers.add(server);
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(TIMEOUT_MS);
                handler.handle(socket);
                socket.getOutputStream().flush();
            } catch (IOException ignored) {
                // The probe under test reports what it saw; the server has nothing to add.
            }
        }, "network-proxy-test-server");
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    private static NetworkProxy.Config local(String type, ServerSocket server) {
        return NetworkProxy.parse(type, "127.0.0.1", String.valueOf(server.getLocalPort()), "", "");
    }

    private static void readFully(InputStream in, int count) throws IOException {
        readFully(in, count, new byte[count]);
    }

    private static void readFully(InputStream in, int count, byte[] into) throws IOException {
        for (int read = 0; read < count; ) {
            int got = in.read(into, read, count - read);
            if (got < 0) throw new IOException("closed early");
            read += got;
        }
    }

    /** An HTTP request's head, up to and including the blank line. */
    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n") && head.length() < 4096) {
            int next = in.read();
            if (next < 0) break;
            head.append((char) next);
        }
        return head.toString();
    }

    private static void collectKeys(Preference preference, List<String> into) {
        if (preference.hasKey()) into.add(preference.getKey());
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup group = (PreferenceGroup) preference;
            for (int index = 0; index < group.getPreferenceCount(); index++) {
                collectKeys(group.getPreference(index), into);
            }
        }
    }

    private static void turnOn(String type, String host, String port, String user, String password) {
        Settings.NETWORK_PROXY.save(true);
        Settings.NETWORK_PROXY_TYPE.save(type);
        Settings.NETWORK_PROXY_HOST.save(host);
        Settings.NETWORK_PROXY_PORT.save(port);
        Settings.NETWORK_PROXY_USER.save(user);
        Settings.NETWORK_PROXY_PASSWORD.save(password);
    }

    private static void setPaused(boolean paused) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, paused));
    }
}
