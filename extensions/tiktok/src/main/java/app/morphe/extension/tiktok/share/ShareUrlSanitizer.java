package app.morphe.extension.tiktok.share;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings("unused")
public final class ShareUrlSanitizer {
    private static final String LOG_PREFIX = "[Morphe TikTok Share]";

    private ShareUrlSanitizer() {}

    public static String stripAllQueryParams(String url) {
        if (url == null || !BaseSettings.SANITIZE_SHARING_LINKS.get()) return url;

        try {
            int queryIndex = url.indexOf('?');
            if (queryIndex <= 0) return url;

            String sanitizedUrl = url.substring(0, queryIndex);
            if (BaseSettings.DEBUG.get()) {
                Logger.printDebug(() ->
                    LOG_PREFIX + " Sanitized share URL " + describeUrl(url) + " -> " + describeUrl(sanitizedUrl)
                );
            }
            return sanitizedUrl;
        } catch (Exception ex) {
            Logger.printInfo(() -> LOG_PREFIX + " Failed to sanitize share URL", ex);
            return url;
        }
    }

    private static String describeUrl(String url) {
        if (url == null) return "null";

        String sanitized = url.replace('\n', ' ').replace('\r', ' ');
        int queryIndex = sanitized.indexOf('?');
        if (queryIndex >= 0) {
            sanitized = sanitized.substring(0, queryIndex) + "?<redacted>";
        }
        if (sanitized.length() > 120) {
            sanitized = sanitized.substring(0, 120) + "...";
        }

        return "\"" + sanitized + "\"";
    }
}
