/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.SocketChannel;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Connect to a checked address while TLS and HTTP retain the original hostname. */
final class PinnedMediaConnection {
    private PinnedMediaConnection() { }

    static HttpsURLConnection open(URL original, InetAddress address) throws IOException {
        if (!"https".equalsIgnoreCase(original.getProtocol()) || !MediaTransport.isPublicAddress(address)) {
            throw new IOException("Media connection requires a checked public HTTPS address");
        }
        String host = original.getHost();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int port = original.getPort() < 0 ? 443 : original.getPort();
        URL pinned = new URL("https", address.getHostAddress(), original.getPort(), original.getFile());
        // A system HTTP proxy would choose the peer itself. The IP literal and direct route
        // ensure the platform cannot make another DNS decision before opening the TCP socket.
        URLConnection opened = pinned.openConnection(Proxy.NO_PROXY);
        if (!(opened instanceof HttpsURLConnection)) throw new IOException("Media connection is not HTTPS");
        HttpsURLConnection connection = (HttpsURLConnection) opened;
        String identity = host;
        connection.setSSLSocketFactory(new PinnedFactory(
                connection.getSSLSocketFactory(), identity, address, port));
        javax.net.ssl.HostnameVerifier verifier = connection.getHostnameVerifier();
        connection.setHostnameVerifier((ignored, session) -> verifier.verify(identity, session));
        String authority = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        if (port != 443) authority += ":" + port;
        connection.setRequestProperty("Host", authority);
        return connection;
    }

    /** Android's HTTP client supplies an already-connected raw socket to this factory. */
    static final class PinnedFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String host;
        private final InetAddress address;
        private final int port;

        PinnedFactory(SSLSocketFactory delegate, String host, InetAddress address, int port) {
            this.delegate = delegate;
            this.host = host;
            this.address = address;
            this.port = port;
        }

        @Override public Socket createSocket(Socket raw, String ignoredHost, int ignoredPort,
                boolean autoClose) throws IOException {
            if (raw == null || !raw.isConnected() || !address.equals(raw.getInetAddress())
                    || raw.getPort() != port) {
                if (raw != null) raw.close();
                throw new IOException("Media connection peer differs from its checked address");
            }
            // createSocket's hostname is the logical TLS peer, independent of the TCP peer.
            // The platform trust manager still validates the complete certificate chain.
            Socket tls = delegate.createSocket(raw, host, port, autoClose);
            if (!(tls instanceof SSLSocket)) {
                tls.close();
                throw new IOException("Media connection did not create a TLS socket");
            }
            return new HostSocket((SSLSocket) tls);
        }

        private IOException unpinned() {
            return new IOException("Media TLS requires an already-connected checked socket");
        }
        @Override public Socket createSocket() throws IOException { throw unpinned(); }
        @Override public Socket createSocket(String host, int port) throws IOException { throw unpinned(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort)
                throws IOException { throw unpinned(); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { throw unpinned(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
                throws IOException { throw unpinned(); }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    }

    /**
     * Android's HTTP client tries to set SNI from the IP URL after the factory returns. Keep the
     * factory's original peer name and HTTP/1.1, while passing its cipher/protocol selections on.
     * No hidden setHostname/setAlpnProtocols methods are exposed for older Android releases.
     */
    static final class HostSocket extends SSLSocket {
        private final SSLSocket socket;

        HostSocket(SSLSocket socket) { this.socket = socket; }

        @Override public SSLParameters getSSLParameters() { return socket.getSSLParameters(); }
        @Override public void setSSLParameters(SSLParameters parameters) {
            if (parameters.getCipherSuites() != null) socket.setEnabledCipherSuites(parameters.getCipherSuites());
            if (parameters.getProtocols() != null) socket.setEnabledProtocols(parameters.getProtocols());
            if (parameters.getNeedClientAuth()) socket.setNeedClientAuth(true);
            else socket.setWantClientAuth(parameters.getWantClientAuth());
        }
        @Override public String[] getSupportedCipherSuites() { return socket.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return socket.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] values) { socket.setEnabledCipherSuites(values); }
        @Override public String[] getSupportedProtocols() { return socket.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return socket.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] values) { socket.setEnabledProtocols(values); }
        @Override public SSLSession getSession() { return socket.getSession(); }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
            socket.addHandshakeCompletedListener(listener);
        }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
            socket.removeHandshakeCompletedListener(listener);
        }
        @Override public void startHandshake() throws IOException { socket.startHandshake(); }
        @Override public void setUseClientMode(boolean value) { socket.setUseClientMode(value); }
        @Override public boolean getUseClientMode() { return socket.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean value) { socket.setNeedClientAuth(value); }
        @Override public boolean getNeedClientAuth() { return socket.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean value) { socket.setWantClientAuth(value); }
        @Override public boolean getWantClientAuth() { return socket.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean value) { socket.setEnableSessionCreation(value); }
        @Override public boolean getEnableSessionCreation() { return socket.getEnableSessionCreation(); }
        @Override public void bind(SocketAddress local) throws IOException { socket.bind(local); }
        @Override public void connect(SocketAddress remote) throws IOException { socket.connect(remote); }
        @Override public void connect(SocketAddress remote, int timeout) throws IOException { socket.connect(remote, timeout); }
        @Override public InetAddress getInetAddress() { return socket.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return socket.getLocalAddress(); }
        @Override public int getPort() { return socket.getPort(); }
        @Override public int getLocalPort() { return socket.getLocalPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return socket.getRemoteSocketAddress(); }
        @Override public SocketAddress getLocalSocketAddress() { return socket.getLocalSocketAddress(); }
        @Override public SocketChannel getChannel() { return socket.getChannel(); }
        @Override public InputStream getInputStream() throws IOException { return socket.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { return socket.getOutputStream(); }
        @Override public void setTcpNoDelay(boolean value) throws SocketException { socket.setTcpNoDelay(value); }
        @Override public boolean getTcpNoDelay() throws SocketException { return socket.getTcpNoDelay(); }
        @Override public void setSoLinger(boolean on, int linger) throws SocketException { socket.setSoLinger(on, linger); }
        @Override public int getSoLinger() throws SocketException { return socket.getSoLinger(); }
        @Override public void sendUrgentData(int data) throws IOException { socket.sendUrgentData(data); }
        @Override public void setOOBInline(boolean value) throws SocketException { socket.setOOBInline(value); }
        @Override public boolean getOOBInline() throws SocketException { return socket.getOOBInline(); }
        @Override public void setSoTimeout(int timeout) throws SocketException { socket.setSoTimeout(timeout); }
        @Override public int getSoTimeout() throws SocketException { return socket.getSoTimeout(); }
        @Override public void setSendBufferSize(int size) throws SocketException { socket.setSendBufferSize(size); }
        @Override public int getSendBufferSize() throws SocketException { return socket.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int size) throws SocketException { socket.setReceiveBufferSize(size); }
        @Override public int getReceiveBufferSize() throws SocketException { return socket.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean value) throws SocketException { socket.setKeepAlive(value); }
        @Override public boolean getKeepAlive() throws SocketException { return socket.getKeepAlive(); }
        @Override public void setTrafficClass(int value) throws SocketException { socket.setTrafficClass(value); }
        @Override public int getTrafficClass() throws SocketException { return socket.getTrafficClass(); }
        @Override public void setReuseAddress(boolean value) throws SocketException { socket.setReuseAddress(value); }
        @Override public boolean getReuseAddress() throws SocketException { return socket.getReuseAddress(); }
        @Override public void close() throws IOException { socket.close(); }
        @Override public void shutdownInput() throws IOException { socket.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { socket.shutdownOutput(); }
        @Override public boolean isConnected() { return socket.isConnected(); }
        @Override public boolean isBound() { return socket.isBound(); }
        @Override public boolean isClosed() { return socket.isClosed(); }
        @Override public boolean isInputShutdown() { return socket.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return socket.isOutputShutdown(); }
        @Override public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
            socket.setPerformancePreferences(connectionTime, latency, bandwidth);
        }
    }
}
