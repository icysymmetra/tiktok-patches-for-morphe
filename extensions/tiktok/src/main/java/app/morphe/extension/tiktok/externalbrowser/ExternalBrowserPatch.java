/*
 * Thanks to lyyako for the original implementation and help with this patch.
 *
 * Originally adapted for TikTok 43.8.3; ported to TikTok 46.2.3:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.externalbrowser;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

public final class ExternalBrowserPatch {
    private static final String HOOK_FAMILY = "external browser";
    private static final String MAIN_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.services.IMainService";
    private static final String SERVICE_MANAGER_CLASS =
            "com.ss.android.ugc.aweme.framework.services.ServiceManager";

    private ExternalBrowserPatch() {
    }

    public static boolean openSparkThirdContext(Context context, Object sparkThirdContext) {
        return isEnabled()
                && isAllowedScreen(sparkThirdContext)
                && open(context, stringField(sparkThirdContext, "url"));
    }

    public static boolean openStoryLink(Object owner, Object sticker) {
        if (!isEnabled() || !Integer.valueOf(106).equals(call(sticker, "getType"))) {
            return false;
        }

        Object link = call(sticker, "getUrlLinkSticker");
        Object source = call(link, "getFullURL");
        return source instanceof String && open(contextField(owner), (String) source);
    }

    public static boolean openSparkActivity(Activity activity) {
        if (!isEnabled()) {
            return false;
        }

        Object sparkContext = typedField(activity, "com.bytedance.hybrid.spark.SparkContext");
        String source = stringField(sparkContext, "url");
        boolean opened = isAllowedScreen(sparkContext) && open(activity, source);
        if (opened) {
            activity.finish();
        }
        return opened;
    }

    private static boolean isEnabled() {
        return Settings.OPEN_EXTERNAL_LINKS.get();
    }

    private static boolean isAllowedScreen(Object target) {
        if (target == null) {
            return false;
        }

        if ("bio_url".equals(call(field(target, "seclinkConfig"), "getScene"))) {
            return true;
        }

        Object params = field(target, "defaultParams");
        return params instanceof Map
                && "bio_url".equals(((Map<?, ?>) params).get("sec_link_scene"));
    }

    private static boolean open(Context context, String source) {
        Uri uri = resolveTarget(source);
        if (context == null || uri == null) {
            return false;
        }

        Class<?> serviceClass = Reflect.requiredClass(MAIN_SERVICE_CLASS, HOOK_FAMILY);
        Class<?> managerClass = Reflect.requiredClass(SERVICE_MANAGER_CLASS, HOOK_FAMILY);
        Method getManager = Reflect.requiredMethod(managerClass, "get", HOOK_FAMILY);
        Object manager = Reflect.invokeRequired(getManager, null, HOOK_FAMILY);
        if (serviceClass == null || manager == null) return false;

        Method getService = Reflect.requiredMethod(
                manager.getClass(), "getService", HOOK_FAMILY, Class.class);
        Object service = Reflect.invokeRequired(
                getService, manager, HOOK_FAMILY, serviceClass);
        if (service == null) return false;

        Method openBrowser = Reflect.requiredMethod(
                serviceClass,
                "openSystemBrowser",
                HOOK_FAMILY,
                Context.class,
                String.class);
        return Boolean.TRUE.equals(Reflect.invokeRequired(
                openBrowser, service, HOOK_FAMILY, context, uri.toString()));
    }

    static Uri resolveTarget(String source) {
        String value = source == null ? null : source.trim();
        for (int i = 0; value != null && i < 4; i++) {
            Uri uri;
            try {
                uri = Uri.parse(value);
            } catch (RuntimeException ignored) {
                return null;
            }
            if (uri.getScheme() == null) {
                value = "https://" + value;
                continue;
            }
            if (asciiEqualsIgnoreCase("aweme", uri.getScheme())) {
                try {
                    value = uri.getQueryParameter("url");
                } catch (RuntimeException ignored) {
                    return null;
                }
                continue;
            }
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!(asciiEqualsIgnoreCase("http", scheme) || asciiEqualsIgnoreCase("https", scheme))
                    || host == null || host.trim().isEmpty()) {
                return null;
            }

            if (isLinkSafetyRedirect(uri)) {
                String target;
                try {
                    target = uri.getQueryParameter("target");
                } catch (RuntimeException ignored) {
                    return null;
                }
                if (target != null) {
                    value = target;
                    continue;
                }
            }

            return uri;
        }
        return null;
    }

    private static boolean isLinkSafetyRedirect(Uri uri) {
        if (!asciiEqualsIgnoreCase("https", uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String path = uri.getPath();
        if (path == null || !path.startsWith("/link/")) return false;
        String host = uri.getHost();
        return asciiEqualsIgnoreCase("www.tiktok.com", host)
                || asciiEqualsIgnoreCase("www.tiktoklinksafety.us", host)
                || asciiEqualsIgnoreCase("www.tiktoklinksafety.eu", host)
                || asciiEqualsIgnoreCase("www.tiktoklinksafety.com", host);
    }

    private static boolean asciiEqualsIgnoreCase(String expected, String actual) {
        if (actual == null || actual.length() != expected.length()) return false;
        for (int i = 0; i < actual.length(); i++) {
            char value = actual.charAt(i);
            if (value >= 'A' && value <= 'Z') value += 'a' - 'A';
            if (value != expected.charAt(i)) return false;
        }
        return true;
    }

    private static String stringField(Object target, String name) {
        Object value = field(target, name);
        return value instanceof String ? (String) value : null;
    }

    private static Context contextField(Object target) {
        if (target == null) {
            return null;
        }

        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(candidate.getType())) {
                    continue;
                }
                Object value = readField(candidate, target);
                if (value instanceof Context) {
                    return (Context) value;
                }
            }
        }
        return null;
    }

    private static Object typedField(Object target, String fieldType) {
        if (target == null) {
            return null;
        }

        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Field candidate : type.getDeclaredFields()) {
                if (fieldType.equals(candidate.getType().getName())) {
                    return readField(candidate, target);
                }
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }

        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                return readField(type.getDeclaredField(name), target);
            } catch (NoSuchFieldException ignored) {
                // Continue through the class hierarchy.
            }
        }
        return null;
    }

    private static Object readField(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object call(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
