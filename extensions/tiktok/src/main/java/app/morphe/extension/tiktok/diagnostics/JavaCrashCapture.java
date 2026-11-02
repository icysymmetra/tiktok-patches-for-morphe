/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.diagnostics;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DebugCapture;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.DiagnosticEvent;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

public final class JavaCrashCapture {
    private static final String PREFS_NAME = "morphe_prefs";
    private static final String CAPTURE_KEY = "morphe_capture_java_crashes";
    private static final String DEBUG_KEY = "morphe_debug";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Object REPORT_LOCK = new Object();
    private static final int RECENT_EVENTS_MAX_CHARS = 12_000;
    private static final int NPTH_DETAIL_MAX_CHARS = 48_000;
    private static Object npthCallback;

    private JavaCrashCapture() {
    }

    public static void initialize(Context context) {
        if (!isEnabled(context, CAPTURE_KEY)) return;
        if (!INSTALLED.compareAndSet(false, true)) return;

        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Context appContext = context.getApplicationContext();
        if (!(original instanceof MorpheCrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new MorpheCrashHandler(appContext, original));
        }
        registerNpthCallback(appContext);
        LogBufferManager.appendEvent(
                DiagnosticCategory.SETTINGS,
                "JavaCrashCapture",
                "INFO",
                "Java crash capture installed after NpthExtent"
        );
        Log.i("morphe:JavaCrashCapture", "Java crash capture installed after NpthExtent");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerNpthCallback(Context context) {
        try {
            Class<?> crashTypeClass = Class.forName("com.bytedance.crash.CrashType");
            Class<?> callbackClass = Class.forName("com.bytedance.crash.ICrashCallback");
            Class<?> npthClass = Class.forName("com.bytedance.crash.Npth");
            Object allCrashTypes = Enum.valueOf((Class<? extends Enum>) crashTypeClass, "ALL");

            npthCallback = Proxy.newProxyInstance(
                    callbackClass.getClassLoader(),
                    new Class<?>[]{callbackClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("toString".equals(method.getName())) return "MorpheNpthCrashCallback";
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return proxy == args[0];
                            return null;
                        }
                        if ("onCrash".equals(method.getName()) && args != null && args.length == 3) {
                            persistNpthSignal(context, args[0], args[1], args[2]);
                        }
                        return null;
                    }
            );

            Method register = npthClass.getDeclaredMethod(
                    "registerCrashCallback", callbackClass, crashTypeClass
            );
            register.invoke(null, npthCallback, allCrashTypes);
            LogBufferManager.appendEvent(
                    DiagnosticCategory.SETTINGS,
                    "NpthCrashCapture",
                    "INFO",
                    "TikTok native, ANR, and launch crash capture registered"
            );
            Log.i(
                    "morphe:NpthCrashCapture",
                    "TikTok native, ANR, and launch crash capture registered"
            );
        } catch (Throwable throwable) {
            npthCallback = null;
            Log.w("morphe:NpthCrashCapture", "Could not register TikTok crash callback\n"
                    + DiagnosticRedactor.redactThrowable(throwable));
        }
    }

    private static void persistNpthSignal(
            Context context,
            Object crashType,
            Object detail,
            Object threadValue
    ) {
        String type = String.valueOf(crashType);
        // The existing uncaught-exception handler records richer Java crash details.
        if ("java".equalsIgnoreCase(type)) return;

        try {
            Thread thread = threadValue instanceof Thread ? (Thread) threadValue : null;
            String report = buildNpthReport(context, type, String.valueOf(detail), thread);
            synchronized (REPORT_LOCK) {
                LogBufferManager.persistNpthCrashReport(context, report);
            }
        } catch (Throwable ignored) {
            // Npth invokes this during failure handling; never interfere with its own pipeline.
        }
    }

    private static String buildNpthReport(
            Context context,
            String crashType,
            String detail,
            Thread thread
    ) {
        String sanitizedDetail = sanitizeNpthDetail(detail);
        StringBuilder report = new StringBuilder();
        report.append("schema: 1\n")
                .append("complete: true\n")
                .append("source: tiktok_npth_callback\n")
                .append("timestamp_utc: ").append(utcNow()).append('\n')
                .append("package: ").append(context.getPackageName()).append('\n')
                .append("tiktok_version: ").append(Utils.getAppVersionName()).append('\n')
                .append("morphe_version: ").append(Utils.getPatchesReleaseVersion()).append('\n')
                .append("crash_type: ").append(safe(crashType)).append('\n')
                .append("process: ").append(processName(context)).append('\n')
                .append("pid: ").append(Process.myPid()).append('\n')
                .append("thread: ").append(thread == null ? "unavailable" : thread.getName())
                .append("\n\n[NPTH SUMMARY]\n")
                .append(sanitizedDetail);

        String recent = LogBufferManager.snapshotForCrash(RECENT_EVENTS_MAX_CHARS);
        if (!recent.isEmpty()) {
            report.append("\n[RECENT MORPHE EVENTS]\n").append(redact(recent));
        }
        return redact(report.toString());
    }

    private static String sanitizeNpthDetail(String detail) {
        if (detail == null || "null".equals(detail)) return "";
        boolean truncated = detail.length() > NPTH_DETAIL_MAX_CHARS;
        String boundedDetail = truncated ? detail.substring(0, NPTH_DETAIL_MAX_CHARS) : detail;
        String sanitized = redact(boundedDetail);
        return truncated ? sanitized + "\n[summary truncated]" : sanitized;
    }

    static String redact(String text) {
        return DiagnosticRedactor.redact(text);
    }

    private static final class MorpheCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler delegate;
        private final ThreadLocal<Boolean> handling = new ThreadLocal<>();

        MorpheCrashHandler(Context context, Thread.UncaughtExceptionHandler delegate) {
            this.context = context;
            this.delegate = delegate;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            if (Boolean.TRUE.equals(handling.get())) {
                if (delegate != null && delegate != this) {
                    delegate.uncaughtException(thread, throwable);
                }
                return;
            }

            handling.set(Boolean.TRUE);
            try {
                synchronized (REPORT_LOCK) {
                    LogBufferManager.persistCrashReport(
                            context,
                            buildReport(context, thread, throwable, delegate)
                    );
                }
            } catch (Throwable ignored) {
                // Crash-time code must never interfere with TikTok's handler.
            } finally {
                try {
                    if (delegate != null && delegate != this) {
                        delegate.uncaughtException(thread, throwable);
                    }
                } finally {
                    handling.remove();
                }
            }
        }
    }

    private static String buildReport(
            Context context,
            Thread thread,
            Throwable throwable,
            Thread.UncaughtExceptionHandler delegate
    ) {
        String sanitizedStack = DiagnosticRedactor.redactThrowable(throwable);

        StringBuilder report = new StringBuilder();
        report.append("schema: 1\n")
                .append("complete: true\n")
                .append("timestamp_utc: ").append(utcNow()).append('\n')
                .append("package: ").append(context.getPackageName()).append('\n')
                .append("tiktok_version: ").append(Utils.getAppVersionName()).append('\n')
                .append("morphe_version: ").append(Utils.getPatchesReleaseVersion()).append('\n')
                .append("diagnostic_logging: ").append(isEnabled(context, DEBUG_KEY) || DebugCapture.isRunning()).append('\n')
                .append("java_crash_capture: ").append(isEnabled(context, CAPTURE_KEY)).append('\n')
                .append("android_api: ").append(Build.VERSION.SDK_INT).append('\n')
                .append("abis: ").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n')
                .append("process: ").append(processName(context)).append('\n')
                .append("pid: ").append(Process.myPid()).append('\n')
                .append("thread: ").append(thread.getName()).append('\n')
                .append("thread_id: ").append(thread.getId()).append('\n')
                .append("exception: ").append(throwable.getClass().getName()).append('\n')
                .append("message: ").append(throwableMessage(throwable)).append('\n')
                .append("delegate: ").append(delegate == null ? "none" : delegate.getClass().getName())
                .append("\n\n[STACK TRACE]\n")
                .append(sanitizedStack);

        String recent = LogBufferManager.snapshotForCrash(RECENT_EVENTS_MAX_CHARS);
        if (!recent.isEmpty()) {
            // Log lines carry addresses too: a download names the URL it could not reach.
            report.append("\n[RECENT MORPHE EVENTS]\n").append(redact(recent));
        }
        return redact(report.toString());
    }

    private static String throwableMessage(Throwable throwable) {
        try {
            return safe(redact(throwable.getMessage()));
        } catch (Throwable unavailable) {
            return "[message unavailable]";
        }
    }

    private static String processName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName();
        }
        return context.getPackageName();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static boolean isEnabled(Context context, String key) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, false);
    }

    private static String utcNow() {
        return DiagnosticEvent.utc(System.currentTimeMillis());
    }
}
