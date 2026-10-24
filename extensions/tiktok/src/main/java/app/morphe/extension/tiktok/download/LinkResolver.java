/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;

/**
 * Where a link ends up, through the media transport and its checks on every redirect: HTTPS
 * only, no address on the reader's own network, five redirects at most. The page it lands on
 * is never read; the connection closes once its status is in.
 */
public final class LinkResolver {
    private static final int TIMEOUT_MS = 5000;

    private LinkResolver() {
    }

    public static String finalUrl(String url) throws IOException {
        try (MediaTransport.Response response = MediaTransport.open(url, null, TIMEOUT_MS, TIMEOUT_MS, null, true)) {
            return response.url.toString();
        }
    }
}
