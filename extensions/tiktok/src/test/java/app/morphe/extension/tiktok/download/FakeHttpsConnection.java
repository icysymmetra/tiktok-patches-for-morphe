package app.morphe.extension.tiktok.download;

import java.net.URL;
import java.security.cert.Certificate;

import javax.net.ssl.HttpsURLConnection;

/**
 * An HTTPS connection for the tests' https handler, with no socket behind it.
 *
 * <p>Media downloads connect to the checked address through PinnedMediaConnection, which sets the
 * TLS factory and hostname check on an {@link HttpsURLConnection} and refuses anything else. A
 * handler that answered with a plain HttpURLConnection was refused there, so every fixture that
 * fakes a download extends this instead and overrides what it serves.
 */
abstract class FakeHttpsConnection extends HttpsURLConnection {
    FakeHttpsConnection(URL url) {
        super(url);
    }

    @Override public String getCipherSuite() { return "TLS_FAKE_FOR_TESTS"; }
    @Override public Certificate[] getLocalCertificates() { return null; }
    @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    @Override public void connect() { }
    @Override public void disconnect() { }
    @Override public boolean usingProxy() { return false; }
}
