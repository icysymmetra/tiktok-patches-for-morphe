package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

/** The old pre-connect DNS checks never tested the actual TCP peer or TLS identity. */
public class PinnedMediaConnectionTest {
    @Test public void tlsUsesTheOriginalHostnameAndCannotBeRetargetedByTheHttpClient() throws Exception {
        InetAddress checked = InetAddress.getByName("8.8.8.8");
        RecordingFactory tls = new RecordingFactory();
        PinnedMediaConnection.PinnedFactory factory =
                new PinnedMediaConnection.PinnedFactory(tls, "cdn.example", checked, 443);
        try (SSLSocket socket = (SSLSocket) factory.createSocket(
                new PeerSocket(checked, 443), "8.8.8.8", 443, true)) {
            assertEquals("cdn.example", tls.host);
            SSLParameters platform = socket.getSSLParameters();
            platform.setServerNames(List.of(new SNIHostName("replacement.example")));
            platform.setApplicationProtocols(new String[]{"h2", "http/1.1"});
            socket.setSSLParameters(platform);
            assertEquals(List.of(new SNIHostName("cdn.example")),
                    tls.created.getSSLParameters().getServerNames());
            assertArrayEquals(new String[0], tls.created.getSSLParameters().getApplicationProtocols());
            socket.setSoTimeout(1250);
            assertEquals(1250, tls.created.getSoTimeout());
        }
        assertTrue(tls.created.isClosed());
    }

    @Test public void aDifferentAddressOrPortIsClosedBeforeTlsStarts() throws Exception {
        InetAddress checked = InetAddress.getByName("8.8.8.8");
        RecordingFactory tls = new RecordingFactory();
        PinnedMediaConnection.PinnedFactory factory =
                new PinnedMediaConnection.PinnedFactory(tls, "cdn.example", checked, 443);
        for (PeerSocket raw : new PeerSocket[]{
                new PeerSocket(InetAddress.getLoopbackAddress(), 443), new PeerSocket(checked, 444)
        }) {
            assertThrows(IOException.class, () -> factory.createSocket(raw, "cdn.example", 443, true));
            assertTrue(raw.closed);
        }
        assertNull(tls.created);
    }

    @Test public void noFactoryEntryPointCanPerformAnotherDnsLookup() throws Exception {
        InetAddress checked = InetAddress.getByName("8.8.8.8");
        PinnedMediaConnection.PinnedFactory factory = new PinnedMediaConnection.PinnedFactory(
                new RecordingFactory(), "cdn.example", checked, 443);
        assertThrows(IOException.class, factory::createSocket);
        assertThrows(IOException.class, () -> factory.createSocket("localhost", 443));
        assertThrows(IOException.class, () -> factory.createSocket("localhost", 443, checked, 0));
        assertThrows(IOException.class, () -> factory.createSocket(checked, 443));
        assertThrows(IOException.class, () -> factory.createSocket(checked, 443, checked, 0));
        assertThrows(IOException.class, () -> factory.createSocket(new Socket(), "localhost", 443, true));
    }

    @Test public void theProductionOpenerRefusesPrivateAddressesAndCleartext() throws Exception {
        assertThrows(IOException.class, () -> PinnedMediaConnection.open(
                new URL("https://cdn.example/media"), InetAddress.getLoopbackAddress()));
        assertThrows(IOException.class, () -> PinnedMediaConnection.open(
                new URL("http://cdn.example/media"), InetAddress.getByName("8.8.8.8")));
    }

    private static final class PeerSocket extends Socket {
        final InetAddress address;
        final int port;
        boolean closed;
        PeerSocket(InetAddress address, int port) { this.address = address; this.port = port; }
        @Override public boolean isConnected() { return true; }
        @Override public InetAddress getInetAddress() { return address; }
        @Override public int getPort() { return port; }
        @Override public void close() { closed = true; }
    }

    private static final class RecordingFactory extends SSLSocketFactory {
        String host;
        SSLSocket created;
        @Override public Socket createSocket(Socket raw, String host, int port, boolean autoClose)
                throws IOException {
            this.host = host;
            created = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            SSLParameters identity = created.getSSLParameters();
            identity.setServerNames(List.of(new SNIHostName(host)));
            created.setSSLParameters(identity);
            return created;
        }
        @Override public String[] getDefaultCipherSuites() { return new String[0]; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public Socket createSocket(String h, int p) throws IOException { throw new IOException(); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { throw new IOException(); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { throw new IOException(); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { throw new IOException(); }
    }
}
