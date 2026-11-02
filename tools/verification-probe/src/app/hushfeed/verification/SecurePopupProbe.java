package app.hushfeed.verification;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

/** Shows a controlled instance of TikTok's own Compose popup for FLAG_SECURE checks. */
final class SecurePopupProbe {
    private static final int SECURE = WindowManager.LayoutParams.FLAG_SECURE;
    private static WindowManager manager;
    private static View popup;

    private SecurePopupProbe() {}

    static synchronized String show(ClassLoader loader, Activity activity) throws Exception {
        if (activity == null) throw new IllegalStateException("TikTok has no foreground activity");
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("secure-popup must run on TikTok's main thread");
        }
        close();

        Window host = activity.getWindow();
        int originalHostFlags = host.getAttributes().flags;
        boolean hostWasSecure = (originalHostFlags & SECURE) != 0;
        Object nativePopup;
        WindowManager.LayoutParams attributes;
        try {
            // Compose's PopupLayout inherits security from its anchor window. Set the host only
            // while its constructor runs, then restore it before the controlled popup is shown.
            host.addFlags(SECURE);
            View anchor = host.getDecorView();
            WindowManager.LayoutParams rootAttributes =
                    (WindowManager.LayoutParams) anchor.getRootView().getLayoutParams();
            if ((rootAttributes.flags & SECURE) == 0) {
                throw new IllegalStateException("host decor did not receive FLAG_SECURE");
            }

            Class<?> propertiesClass = loader.loadClass("X.011R");
            Constructor<?> propertiesConstructor = propertiesClass.getConstructor(
                    boolean.class, boolean.class, boolean.class, int.class);
            Object properties = propertiesConstructor.newInstance(false, true, true, 0);

            Class<?> densityClass = loader.loadClass("X.01oO");
            Object density = Proxy.newProxyInstance(loader, new Class<?>[]{densityClass},
                    (proxy, method, arguments) -> densityResult(
                            activity.getResources().getDisplayMetrics().density,
                            proxy, method, arguments));
            Class<?> positionClass = loader.loadClass("X.011P");
            Object position = Proxy.newProxyInstance(loader, new Class<?>[]{positionClass},
                    (proxy, method, arguments) -> method.getReturnType() == long.class ? 0L : null);
            Class<?> functionClass = loader.loadClass("kotlin.jvm.functions.Function0");
            Class<?> popupClass = loader.loadClass("X.01tC");
            Constructor<?> popupConstructor = popupClass.getConstructor(
                    functionClass, propertiesClass, String.class, View.class,
                    densityClass, positionClass, UUID.class);
            nativePopup = popupConstructor.newInstance(
                    null, properties, "hushfeed-secure-popup", anchor,
                    density, position, UUID.randomUUID());
            attributes = (WindowManager.LayoutParams) popupClass
                    .getMethod("getParams$ui_release").invoke(nativePopup);
        } finally {
            if (!hostWasSecure) host.clearFlags(SECURE);
        }

        android.widget.TextView view = new android.widget.TextView(activity);
        view.setBackgroundColor(0xffff006e);
        view.setTextColor(0xffffffff);
        view.setTextSize(24f);
        view.setGravity(Gravity.CENTER);
        view.setText("CONTROLLED TIKTOK POPUP\nFLAG_SECURE TEST");
        view.setContentDescription("Hushfeed controlled secure popup");
        attributes.width = dp(activity, 300);
        attributes.height = dp(activity, 260);
        attributes.gravity = Gravity.CENTER;
        attributes.x = 0;
        attributes.y = 0;
        attributes.setTitle("Hushfeed controlled secure popup");

        WindowManager windowManager = (WindowManager)
                activity.getSystemService(Context.WINDOW_SERVICE);
        windowManager.addView(view, attributes);
        manager = windowManager;
        popup = view;
        return "caller=" + nativePopup.getClass().getName()
                + " view=" + view.getClass().getName()
                + " flags=0x" + Integer.toHexString(attributes.flags)
                + " secure=" + ((attributes.flags & SECURE) != 0)
                + " hostWasSecure=" + hostWasSecure
                + " size=" + attributes.width + "x" + attributes.height;
    }

    static synchronized String close() {
        if (manager == null || popup == null) return "already closed";
        try {
            manager.removeViewImmediate(popup);
            return "closed";
        } finally {
            manager = null;
            popup = null;
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static Object densityResult(float density, Object proxy, Method method, Object[] args) {
        String name = method.getName();
        if ("toString".equals(name)) return "HushfeedDensityProxy";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == args[0];
        Class<?> type = method.getReturnType();
        if (type == float.class) {
            float value = args != null && args.length > 0 && args[0] instanceof Number
                    ? ((Number) args[0]).floatValue() : 0f;
            if ("LJJJJL".equals(name)) return value * density;
            if ("LLIIIJ".equals(name)) return value / density;
            if ("getDensity".equals(name)) return density;
            return value;
        }
        if (type == int.class) {
            float value = args != null && args.length > 0 && args[0] instanceof Number
                    ? ((Number) args[0]).floatValue() : 0f;
            return Math.round(value * density);
        }
        if (type == long.class) {
            return args != null && args.length > 0 && args[0] instanceof Long ? args[0] : 0L;
        }
        if (type == boolean.class) return false;
        return null;
    }
}
