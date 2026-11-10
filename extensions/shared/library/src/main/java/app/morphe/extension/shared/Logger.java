/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared;

import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_STACKTRACE;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_TOAST_ON_ERROR;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Morphe specific logger.  Logging is done to standard device log (accessible thru ADB),
 * and additionally accessible thru {@link LogBufferManager}.
 *
 * All methods are thread safe, and are safe to call even
 * if {@link Utils#getContext()} is not available.
 */
public class Logger {

    /**
     * Log messages using lambdas.
     */
    @FunctionalInterface
    public interface LogMessage {
        /**
         * @return Logger string message. This method is only called if logging is enabled.
         */
        @NonNull
        String buildMessageString();
    }

    private enum LogLevel {
        DEBUG,
        INFO,
        ERROR
    }

    /**
     * Log tag prefix. Only used for system logging.
     */
    private static final String MORPHE_LOG_TAG_PREFIX = "morphe: ";

    private static final String LOGGER_CLASS_NAME = Logger.class.getName();

    /**
     * Error toasts already shown in this process, so one broken hook cannot repeat itself.
     *
     * <p>An error on a path the feed runs fires once per video. The queue then holds a minute of
     * identical toasts, each covering the video underneath it, long after the reader has read the
     * first one. Bounded so a stream of genuinely different failures cannot grow it without end;
     * every occurrence still reaches the log and the diagnostic report either way.
     */
    private static final java.util.Set<String> TOASTED_ERRORS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private static final int MAX_TOASTED_ERRORS = 24;

    /**
     * @return For outer classes, this returns {@link Class#getSimpleName()}.
     * For static, inner, or anonymous classes, this returns the simple name of the enclosing class.
     * <br>
     * For example, each of these classes returns 'SomethingView':
     * <code>
     * com.company.SomethingView
     * com.company.SomethingView$StaticClass
     * com.company.SomethingView$1
     * </code>
     */
    private static String getOuterClassSimpleName(Object obj) {
        Class<?> logClass = obj.getClass();
        String fullClassName = logClass.getName();
        final int dollarSignIndex = fullClassName.indexOf('$');
        if (dollarSignIndex < 0) {
            return logClass.getSimpleName(); // Already an outer class.
        }

        // Class is inner, static, or anonymous.
        // Parse the simple name full name.
        // A class with no package returns index of -1, but incrementing gives index zero which is correct.
        final int simpleClassNameStartIndex = fullClassName.lastIndexOf('.') + 1;
        return fullClassName.substring(simpleClassNameStartIndex, dollarSignIndex);
    }

    /**
     * Internal method to handle logging to Android Log and {@link LogBufferManager}.
     * Appends the log message, stack trace (if enabled), and exception (if present) to logBuffer
     * with class name but without 'morphe:' prefix.
     *
     * @param logLevel          The log level.
     * @param message           Log message object.
     * @param ex                Optional exception.
     * @param includeStackTrace If the current stack should be included.
     * @param showToast         If a toast is to be shown.
     */
    private static void logInternal(
            LogLevel logLevel,
            @Nullable DiagnosticCategory category,
            @Nullable String explicitSource,
            LogMessage message,
            @Nullable Throwable ex,
            boolean includeStackTrace,
            boolean showToast
    ) {
        // It's very important that no Settings are used in this method,
        // as this code is used when a context is not set and thus referencing
        // a setting will crash the app.
        //
        // The message is built by the caller's lambda, and that lambda reads whatever the caller
        // was in the middle of: a host object's fields, a list's size, a nullable name. Every
        // hook in the extension logs on its way through, so a message that cannot be built must
        // not be the thing that throws out of the hook into the host app.
        //
        // So is everything after the message: the stack trim, the buffer and the toast all run
        // inside a hook too. An Error counts as much as an exception here, since a toString that
        // recurses or a class that fails to load is exactly what a host object hands over.
        String messageString;
        try {
            messageString = message.buildMessageString();
        } catch (Throwable failure) {
            try {
                messageString = "Could not build the log message: " + failure;
            } catch (Throwable ignored) {
                // Throwable.toString() calls getMessage(), and host throwables can override both.
                messageString = "Could not build the log message.";
            }
        }
        try {
            logBuilt(logLevel, category, explicitSource, message, messageString, ex, includeStackTrace, showToast);
        } catch (Throwable failure) {
            try {
                Log.e(MORPHE_LOG_TAG_PREFIX + "Logger", DiagnosticRedactor.redact(
                        "Could not log a message: " + DiagnosticRedactor.redact(messageString)
                                + "\n" + DiagnosticRedactor.redactThrowable(failure)));
            } catch (Throwable ignored) {
                // Nothing is left to report through.
            }
        }
    }

    private static void logBuilt(
            LogLevel logLevel,
            @Nullable DiagnosticCategory category,
            @Nullable String explicitSource,
            LogMessage message,
            String messageString,
            @Nullable Throwable ex,
            boolean includeStackTrace,
            boolean showToast
    ) {
        String className = DiagnosticRedactor.redact(
                explicitSource == null ? getOuterClassSimpleName(message) : explicitSource);
        if (category == null) category = legacyCategory(className, logLevel);

        String logText = DiagnosticRedactor.redact(messageString);

        // Android's Throwable overload prints raw messages, causes and suppressed exceptions.
        // Render a bounded sanitized copy, while leaving the original host exception untouched.
        if (ex != null) {
            logText += "\n" + DiagnosticRedactor.redactThrowable(ex);
        }

        if (includeStackTrace) {
            String stackTrace = DiagnosticRedactor.redactThrowable(new Throwable());
            // Remove the stacktrace elements of this class.
            final int loggerIndex = stackTrace.lastIndexOf(LOGGER_CLASS_NAME);
            final int loggerBegins = stackTrace.indexOf('\n', loggerIndex);
            // With no line after the logger's last frame there is nothing of the caller's to
            // keep past it, and substring(-1) would throw.
            logText += loggerBegins >= 0 ? stackTrace.substring(loggerBegins) : "\n" + stackTrace;
        }
        logText = DiagnosticRedactor.redact(logText);

        // Do not include "morphe:" prefix in clipboard logs.
        String managerToastString = className + ": " + logText;
        LogBufferManager.appendEvent(category, className, logLevel.name(), logText);

        String logTag = MORPHE_LOG_TAG_PREFIX + className;
        switch (logLevel) {
            case DEBUG:
                Log.d(logTag, logText);
                break;
            case INFO:
                Log.i(logTag, logText);
                break;
            case ERROR:
                Log.e(logTag, logText);
                break;
        }

        if (showToast && TOASTED_ERRORS.size() < MAX_TOASTED_ERRORS
                && TOASTED_ERRORS.add(managerToastString)) {
            Utils.showToastLong(managerToastString);
        }
    }

    private static boolean shouldLogDebug() {
        // If the app is still starting up and the context is not yet set,
        // then allow debug logging regardless what the debug setting actually is.
        return Utils.context == null || DEBUG.get();
    }

    /**
     * Whether a failure inside the bundle is put in front of the reader as a toast.
     *
     * <p>Only while diagnostic logging is on. These messages are written for whoever is fixing
     * the code: they carry the class that failed and the exception's own text, in English, past
     * every translation this bundle ships. A reader who has not turned diagnostic logging on
     * cannot act on "PlaybackQuality: Could not read the playback quality model", and on a path
     * the feed runs they were getting it once per video.
     *
     * <p>Nothing is lost by keeping it out of their way. Every one of these still reaches
     * logcat, the diagnostic buffer and the exported report, which is what the bug report form
     * asks for, and a hook that stopped binding still says so in Hook status.
     */
    private static boolean shouldShowErrorToast() {
        return Utils.context != null && DEBUG.get() && DEBUG_TOAST_ON_ERROR.get();
    }

    private static boolean includeStackTrace() {
        return Utils.context != null && DEBUG_STACKTRACE.get();
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if {@link BaseSettings#DEBUG} is enabled.
     */
    public static void printDebug(LogMessage message) {
        printDebug(message, null);
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if {@link BaseSettings#DEBUG} is enabled.
     */
    public static void printDebug(LogMessage message, @Nullable Exception ex) {
        if (shouldLogDebug()) {
            logInternal(LogLevel.DEBUG, null, null, message, ex, includeStackTrace(), false);
        }
    }

    /**
     * Logs information messages using the outer class name of the code calling this method.
     */
    public static void printInfo(LogMessage message) {
        printInfo(message, null);
    }

    /**
     * Logs information messages using the outer class name of the code calling this method.
     */
    public static void printInfo(LogMessage message, @Nullable Exception ex) {
        logInternal(LogLevel.INFO, null, null, message, ex, includeStackTrace(), false);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method.
     * Appends the log message, exception (if present), and toast message (if enabled) to logBuffer.
     */
    public static void printException(LogMessage message) {
        printException(message, null);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method.
     * <p>
     * If the calling code is showing it's own error toast,
     * instead use {@link #printInfo(LogMessage, Exception)}
     *
     * @param message          log message
     * @param ex               exception (optional)
     */
    public static void printException(LogMessage message, @Nullable Throwable ex) {
        logInternal(LogLevel.ERROR, DiagnosticCategory.PATCH_ERRORS, null, message, ex,
                includeStackTrace(), shouldShowErrorToast());
    }

    public static void diagnosticDebug(
            DiagnosticCategory category,
            String source,
            LogMessage message
    ) {
        if (shouldLogDebug()) {
            logInternal(LogLevel.DEBUG, category, source, message, null, includeStackTrace(), false);
        }
    }

    public static void diagnosticInfo(
            DiagnosticCategory category,
            String source,
            LogMessage message
    ) {
        logInternal(LogLevel.INFO, category, source, message, null, includeStackTrace(), false);
    }

    public static void diagnosticError(
            DiagnosticCategory category,
            String source,
            LogMessage message,
            @Nullable Throwable throwable
    ) {
        logInternal(LogLevel.ERROR, category, source, message, throwable,
                includeStackTrace(), shouldShowErrorToast());
    }

    private static DiagnosticCategory legacyCategory(String source, LogLevel level) {
        if (level == LogLevel.ERROR) return DiagnosticCategory.PATCH_ERRORS;
        if (source.startsWith("FollowDiagnostics")) return DiagnosticCategory.FOLLOW;
        if (source.startsWith("Downloads") || source.startsWith("Sticker")) {
            return DiagnosticCategory.DOWNLOADS;
        }
        if (source.startsWith("FeatureGateLab")) return DiagnosticCategory.FEATURE_GATE_LAB;
        if (source.startsWith("Feed") || source.startsWith("Navigation")
                || source.startsWith("BottomNavigation")) {
            return DiagnosticCategory.FEED_AND_NAVIGATION;
        }
        if (source.startsWith("TikTokActivityHook") || source.contains("Preference")
                || source.startsWith("Settings")) {
            return DiagnosticCategory.SETTINGS;
        }
        return DiagnosticCategory.OTHER;
    }
}
