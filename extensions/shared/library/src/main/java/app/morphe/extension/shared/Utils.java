/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.io.FileInputStream;
import java.text.Bidi;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.ui.Dim;

public class Utils {
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    @SuppressLint("StaticFieldLeak")
    static volatile Context context;

    private static String versionName;
    private static String applicationLabel;
    private static volatile String processName;

    @ColorInt
    private static int darkColor = Color.BLACK;
    @ColorInt
    private static int lightColor = Color.WHITE;

    @Nullable
    private static Boolean isDarkModeEnabled;

    private static boolean appIsUsingBoldIcons;

    // Cached Collator instance with its locale.
    @Nullable
    private static Locale cachedCollatorLocale;
    @Nullable
    private static Collator cachedCollator;

    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("\\p{P}+");
    private static final Pattern DIACRITICS_PATTERN = Pattern.compile("\\p{M}");

    private Utils() {
    } // utility class

    /**
     * Injection point.
     *
     * @return The manifest 'Version' entry of the patches.jar used during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static String getPatchesReleaseVersion() {
        return ""; // Value is replaced during patching.
    }

    /** Fixed APK facts, independent of runtime settings and diagnostic events. */
    public static String getBuildMetadata() {
        return app.morphe.extension.shared.diagnostics.BuildDetails.readMetadata(getContext());
    }

    public static boolean isPreReleasePatches() {
        return getPatchesReleaseVersion().contains("dev");
    }

    private static PackageInfo getPackageInfo() throws PackageManager.NameNotFoundException {
        final var packageName = Objects.requireNonNull(getContext()).getPackageName();

        PackageManager packageManager = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
            );
        }

        return packageManager.getPackageInfo(
                packageName,
                0
        );
    }

    /**
     * @return The version name of the app, such as 20.13.41
     */
    public static String getAppVersionName() {
        if (versionName == null) {
            try {
                versionName = getPackageInfo().versionName;
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to get package info", ex);
                versionName = "Unknown";
            }
        }

        return versionName;
    }

    public static String getApplicationName() {
        if (applicationLabel == null) {
            try {
                ApplicationInfo applicationInfo = getPackageInfo().applicationInfo;
                applicationLabel = (String) applicationInfo.loadLabel(context.getPackageManager());
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to get application name", ex);
                applicationLabel = "Unknown";
            }
        }

        return applicationLabel;
    }

    /**
     * Hide a view by setting its layout height and width to 1dp.
     *
     * @param setting   The setting to check for hiding the view.
     * @param view      The view to hide.
     */
    public static void hideViewBy0dpUnderCondition(BooleanSetting setting, View view) {
        if (hideViewBy0dpUnderCondition(setting.get(), view)) {
            Logger.printDebug(() -> "View hidden by setting: " + setting);
        }
    }

    /**
     * Hide a view by setting its layout height and width to 0dp.
     *
     * @param condition The setting to check for hiding the view.
     * @param view      The view to hide.
     */
    public static boolean hideViewBy0dpUnderCondition(boolean condition, View view) {
        if (condition) {
            hideViewByLayoutParams(view);
            return true;
        }

        return false;
    }

    /**
     * Hide a view by setting its visibility to GONE.
     *
     * @param setting The setting to check for hiding the view.
     * @param view      The view to hide.
     */
    public static void hideViewUnderCondition(BooleanSetting setting, View view) {
        if (hideViewUnderCondition(setting.get(), view)) {
            Logger.printDebug(() -> "View hidden by setting: " + setting);
        }
    }

    /**
     * Hide a view by setting its visibility to GONE.
     *
     * @param condition The setting to check for hiding the view.
     * @param view      The view to hide.
     */
    public static boolean hideViewUnderCondition(boolean condition, View view) {
        if (condition) {
            view.setVisibility(View.GONE);
            return true;
        }

        return false;
    }

    public static void hideViewByRemovingFromParentUnderCondition(BooleanSetting setting, View view) {
        if (hideViewByRemovingFromParentUnderCondition(setting.get(), view)) {
            Logger.printDebug(() -> "View hidden by setting: " + setting);
        }
    }

    public static boolean hideViewByRemovingFromParentUnderCondition(boolean condition, View view) {
        if (condition) {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup parentGroup) {
                parentGroup.removeView(view);
                return true;
            }
        }

        return false;
    }

    /**
     * General purpose pool for network calls and other background tasks.
     * All tasks run at max thread priority.
     */
    private static final ThreadPoolExecutor backgroundThreadPool = new ThreadPoolExecutor(
            3,
            6,
            10,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            r -> { // ThreadFactory
                Thread t = new Thread(r);
                t.setPriority(Thread.NORM_PRIORITY);
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final java.util.concurrent.atomic.AtomicInteger backgroundTasksInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * @return true when the task was accepted, or false when the bounded worker queue was full.
     *         Stateful callers must unwind any busy state when this returns false.
     */
    public static boolean runOnBackgroundThread(Runnable task) {
        backgroundTasksInFlight.incrementAndGet();
        try {
            backgroundThreadPool.execute(() -> {
                try {
                    runAndLogFailure(task);
                } finally {
                    backgroundTasksInFlight.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException error) {
            backgroundTasksInFlight.decrementAndGet();
            Logger.printException(() -> "Background task queue is full", error);
            return false;
        }
    }

    public static <T> Future<T> submitOnBackgroundThread(Callable<T> call) {
        backgroundTasksInFlight.incrementAndGet();
        try {
            return backgroundThreadPool.submit(() -> {
                try {
                    return call.call();
                } finally {
                    backgroundTasksInFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException error) {
            backgroundTasksInFlight.decrementAndGet();
            throw error;
        }
    }

    /**
     * Runs work that can block inside another app's code for as long as that app likes, such as
     * a file app opening a document, on a thread of its own so it never holds one of the shared
     * pool's few threads. It counts as background work, so a test waiting for that waits for
     * this too. False when no thread could be started.
     */
    public static boolean runOnOwnThread(String name, Runnable task) {
        backgroundTasksInFlight.incrementAndGet();
        Thread thread = new Thread(() -> {
            try {
                runAndLogFailure(task);
            } finally {
                backgroundTasksInFlight.decrementAndGet();
            }
        }, name);
        thread.setDaemon(true);
        try {
            thread.start();
            return true;
        } catch (OutOfMemoryError noThread) {
            // Android reports a thread it couldn't create as an OutOfMemoryError.
            backgroundTasksInFlight.decrementAndGet();
            Logger.printException(() -> "Could not start " + name, noThread);
            return false;
        }
    }

    /** Waits until background work submitted before this call has finished. */
    public static void awaitBackgroundTasksForTests() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Background tasks did not finish");
            Future<?> marker;
            try {
                marker = backgroundThreadPool.submit(() -> { });
            } catch (RejectedExecutionException full) {
                // A full queue is work still in flight, so wait for it to drain. A burst bigger
                // than the queue (twenty quick switch flips) used to fail the wait itself.
                Thread.sleep(5);
                continue;
            }
            marker.get(remaining, TimeUnit.NANOSECONDS);
            if (backgroundTasksInFlight.get() == 0 && backgroundThreadPool.getQueue().isEmpty()) {
                return;
            }
        }
    }

    /**
     * Simulates a delay by doing meaningless calculations.
     * Used for debugging to verify UI timeout logic.
     */
    @SuppressWarnings("UnusedReturnValue")
    public static long doNothingForDuration(long amountOfTimeToWaste) {
        final long timeCalculationStarted = System.currentTimeMillis();
        Logger.printDebug(() -> "Artificially creating delay of: " + amountOfTimeToWaste + "ms");

        long meaninglessValue = 0;
        while (System.currentTimeMillis() - timeCalculationStarted < amountOfTimeToWaste) {
            // Could do a thread sleep, but that will trigger an exception if the thread is interrupted.
            meaninglessValue += Long.numberOfLeadingZeros((long) Math.exp(Math.random()));
        }
        // Return the value, otherwise the compiler or VM might optimize and remove the meaningless time wasting work,
        // leaving an empty loop that hammers on the System.currentTimeMillis native call.
        return meaninglessValue;
    }

    public static boolean containsAny(String value, String... targets) {
        return indexOfFirstFound(value, targets) >= 0;
    }

    public static int indexOfFirstFound(String value, String... targets) {
        if (isNotEmpty(value)) {
            for (String string : targets) {
                if (!string.isEmpty()) {
                    final int indexOf = value.indexOf(string);
                    if (indexOf >= 0) return indexOf;
                }
            }
        }
        return -1;
    }

    public static boolean equalsAny(String value, String...targets) {
        if (isNotEmpty(value)) {
            for (String string : targets) {
                if (value.equals(string)) {
                    return true;
                }
            }
        }
        return false;
    }

    public interface MatchFilter<T> {
        boolean matches(T object);
    }

    /**
     * Includes sub children.
     */
    public static <R extends View> R getChildViewByResourceName(View view, String str) {
        var child = view.findViewById(ResourceUtils.getIdentifierOrThrow(ResourceType.ID, str));
        //noinspection unchecked
        return (R) child;
    }

    /**
     * @param searchRecursively If children ViewGroups should also be
     *                          recursively searched using depth first search.
     * @return The first child view that matches the filter.
     */
    @Nullable
    public static <T extends View> T getChildView(ViewGroup viewGroup, boolean searchRecursively,
                                                  MatchFilter<View> filter) {
        for (int i = 0, childCount = viewGroup.getChildCount(); i < childCount; i++) {
            View childAt = viewGroup.getChildAt(i);

            if (filter.matches(childAt)) {
                //noinspection unchecked
                return (T) childAt;
            }
            // Must do recursive after filter check, in case the filter is looking for a ViewGroup.
            if (searchRecursively && childAt instanceof ViewGroup) {
                T match = getChildView((ViewGroup) childAt, true, filter);
                if (match != null) return match;
            }
        }

        return null;
    }

    @Nullable
    public static ViewParent getParentView(View view, int nthParent) {
        ViewParent parent = view.getParent();

        int currentDepth = 0;
        while (++currentDepth < nthParent && parent != null) {
            parent = parent.getParent();
        }

        if (currentDepth == nthParent) {
            return parent;
        }

        final int currentDepthLog = currentDepth;
        Logger.printDebug(() -> "Could not find parent view of depth: " + nthParent
                + " and instead found at: " + currentDepthLog + " view: " + view);
        return null;
    }

    public static void restartApp(Context context) {
        String packageName = context.getPackageName();
        Intent intent = Objects.requireNonNull(context.getPackageManager().getLaunchIntentForPackage(packageName));
        Intent mainIntent = Intent.makeRestartActivityTask(intent.getComponent());
        // Required for API 34 and later
        // Ref: https://developer.android.com/about/versions/14/behavior-changes-14#safer-intents
        mainIntent.setPackage(packageName);
        context.startActivity(mainIntent);
        System.exit(0);
    }

    public static Resources getResources() {
        return getResources(true);
    }

    public static Resources getResources(boolean useContext) {
        if (useContext) {
            if (context != null) {
                return context.getResources();
            }
            Activity mActivity = activityRef.get();
            if (mActivity != null) {
                return mActivity.getResources();
            }
        }

        return Resources.getSystem();
    }

    public static Activity getActivity() {
        return activityRef.get();
    }

    public static void setActivity(Activity mainActivity) {
        Logger.printInfo(() -> "Set activity: " + mainActivity);
        activityRef = new WeakReference<>(mainActivity);
        followResumed(mainActivity);
    }

    private static volatile WeakReference<Activity> resumedRef = new WeakReference<>(null);
    private static WeakReference<android.app.Application> followedApplication = new WeakReference<>(null);

    /**
     * The host activity in front: the last one resumed while it is still alive, else the main
     * activity. A video opened from a profile or search plays in an activity of its own, and a
     * dialog built on the main activity behind it opens on a stopped window nobody can see.
     */
    public static Activity getVisibleActivity() {
        Activity resumed = resumedRef.get();
        if (resumed != null && !resumed.isFinishing() && !resumed.isDestroyed()) return resumed;
        return getActivity();
    }

    private static synchronized void followResumed(Activity activity) {
        android.app.Application application = activity == null ? null : activity.getApplication();
        if (application == null || followedApplication.get() == application) return;
        followedApplication = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) { resumedRef = new WeakReference<>(resumed); }
            @Override public void onActivityDestroyed(Activity destroyed) {
                if (resumedRef.get() == destroyed) resumedRef = new WeakReference<>(null);
            }
            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityPaused(Activity paused) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    /**
     * The activity when there is one, else the application: for sizes that follow the window
     * rather than the display, such as a side by side split view's half of the screen.
     */
    public static Context getWindowContext() {
        Activity activity = getActivity();
        return activity != null ? activity : getContext();
    }

    public static Context getContext() {
        if (context == null) {
            Logger.printException(() -> "Context is not set by extension hook, returning null");
        }
        return context;
    }

    /** Persistent preference writes are owned by the package's main process. */
    public static boolean isMainProcess() {
        Context appContext = context;
        if (appContext == null) return true;
        String packageName = appContext.getPackageName();
        String declared = appContext.getApplicationInfo().processName;
        if (declared != null && declared.startsWith(packageName + ":")) return false;
        String actual = currentProcessName();
        if (actual == null || actual.isEmpty() || packageName.equals(actual)) return true;
        return !actual.startsWith(packageName + ":");
    }

    private static String currentProcessName() {
        String cached = processName;
        if (cached != null) return cached;
        StringBuilder name = new StringBuilder();
        try (FileInputStream input = new FileInputStream("/proc/" + Process.myPid() + "/cmdline")) {
            int value;
            while ((value = input.read()) > 0) name.append((char) value);
        } catch (Exception ignored) {
            return null;
        }
        cached = name.toString();
        if (!cached.isEmpty()) processName = cached;
        return cached;
    }

    public static void setContext(Context appContext) {
        // Intentionally use logger before context is set,
        // to expose any bugs in the 'no context available' logger code.
        Logger.printInfo(() -> "Set context: " + appContext);
        // An activity is kept weakly, in getActivity(), for the callers that need one; the field
        // holds its application, so a finished MainActivity isn't reachable from here until the
        // next one is created. Any other context (a test's wrapper) is kept as given.
        Context stored = appContext;
        if (appContext instanceof Activity activity) {
            Context application = activity.getApplicationContext();
            if (application != null) stored = application;
        }
        // Must initially set context to check the app language.
        context = stored;

        // Before any hook reads a setting: whether this process runs with Hushfeed paused, and
        // the record that lets three crashed starts in a row turn safe mode on.
        HushfeedPause.onProcessStart(appContext);
        // A timed capture keeps the end it was given, or is cleared when that passed while
        // TikTok was closed.
        app.morphe.extension.shared.diagnostics.DebugCapture.load();

        // Follow the activity rather than keeping the first one. The host recreates its main
        // activity on a configuration change it does not swallow, and this hook runs again for
        // the new one; holding the old instance left every overlay attaching to a window nobody
        // is looking at, which isFinishing() does not report because a recreated activity is
        // destroyed rather than finishing.
        if (appContext instanceof Activity activity && getActivity() != activity) {
            setActivity(activity);
        }

        AppLanguage language = BaseSettings.MORPHE_LANGUAGE.get();
        if (language != AppLanguage.DEFAULT) {
            // Create a new context with the desired language.
            Logger.printDebug(() -> "Using app language: " + language);
            Configuration config = new Configuration(stored.getResources().getConfiguration());
            config.setLocale(language.getLocale());
            context = stored.createConfigurationContext(config);
        }

        setThemeLightColor(getThemeColor(getThemeLightColorResourceName(), Color.WHITE));
        setThemeDarkColor(getThemeColor(getThemeDarkColorResourceName(), Color.BLACK));
    }

    public static void setClipboard(CharSequence text) {
        setClipboard(context, "Morphe", text);
    }

    /** Copies a labeled clip through a caller-owned Context with the shared sensitive-data policy. */
    public static void setClipboard(Context clipboardContext, CharSequence label, CharSequence text) {
        ClipboardManager clipboard = (ClipboardManager) clipboardContext
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        if (Build.VERSION.SDK_INT >= 24) {
            PersistableBundle extras = new PersistableBundle();
            // Use the literal so API 24 through 32 can carry the flag before the constant
            // was added to the SDK. Android 13 and newer recognize the same key.
            extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
    }

    public static boolean isNotEmpty(@Nullable String str) {
        return str != null && !str.isEmpty();
    }

    public static boolean isTablet() {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    @Nullable
    private static Boolean isRightToLeftTextLayout;

    /**
     * @return If the device language uses right to left text layout (Hebrew, Arabic, etc).
     *         If this should match any Morphe language override then instead use
     *         {@link #isRightToLeftLocale(Locale)} with {@link BaseSettings#MORPHE_LANGUAGE}.
     *         This is the default locale of the device, which may differ if
     *         {@link BaseSettings#MORPHE_LANGUAGE} is set to a different language.
     */
    public static boolean isRightToLeftLocale() {
        if (isRightToLeftTextLayout == null) {
            isRightToLeftTextLayout = isRightToLeftLocale(Locale.getDefault());
        }
        return isRightToLeftTextLayout;
    }

    /**
     * @return If the locale uses right to left text layout (Hebrew, Arabic, etc).
     */
    public static boolean isRightToLeftLocale(Locale locale) {
        String displayLanguage = locale.getDisplayLanguage();
        return new Bidi(displayLanguage, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).isRightToLeft();
    }

    /**
     * @return A UTF8 string containing a left-to-right or right-to-left
     *         character of the device locale. If this should match any Morphe language
     *         override then instead use {@link #getTextDirectionString(Locale)} with
     *         {@link BaseSettings#MORPHE_LANGUAGE}.
     */
    public static String getTextDirectionString() {
        return  getTextDirectionString(isRightToLeftLocale());
    }

    public static String getTextDirectionString(Locale locale) {
        return getTextDirectionString(isRightToLeftLocale(locale));
    }

    private static String getTextDirectionString(boolean isRightToLeft) {
        return isRightToLeft
                ? "\u200F"  // u200F = right to left character.
                : "\u200E"; // u200E = left to right character.
    }

    /**
     * @return if the text contains at least 1 number character,
     *         including any unicode numbers such as Arabic.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean containsNumber(CharSequence text) {
        for (int index = 0, length = text.length(); index < length;) {
            final int codePoint = Character.codePointAt(text, index);
            if (Character.isDigit(codePoint)) {
                return true;
            }
            index += Character.charCount(codePoint);
        }

        return false;
    }

    /**
     * Ignore this class. It must be public to satisfy Android requirements.
     */
    @SuppressWarnings("deprecation")
    public static final class DialogFragmentWrapper extends DialogFragment {

        private Dialog dialog;
        @Nullable
        private DialogFragmentOnStartAction onStartAction;

        @Override
        public void onSaveInstanceState(Bundle outState) {
            // Do not call super method to prevent state saving.
        }

        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            return dialog;
        }

        @Override
        public void onStart() {
            try {
                super.onStart();

                if (onStartAction != null) {
                    onStartAction.onStart(dialog);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onStart failure: " + dialog.getClass().getSimpleName(), ex);
            }
        }
    }

    /**
     * Interface for {@link #showDialog(Activity, Dialog, boolean, DialogFragmentOnStartAction)}.
     */
    @FunctionalInterface
    public interface DialogFragmentOnStartAction {
        void onStart(Dialog dialog);
    }

    public static void showDialog(Activity activity, Dialog dialog) {
        showDialog(activity, dialog, true, null);
    }

    /**
     * Utility method to allow showing a Dialog on top of other dialogs.
     * Calling this will always display the dialog on top of all other dialogs
     * previously called using this method.
     * <p>
     * Be aware the on start action can be called multiple times for some situations,
     * such as the user switching apps without dismissing the dialog then switching back to this app.
     * <p>
     * This method is only useful during app startup and multiple patches may show their own dialog,
     * and the most important dialog can be called last (using a delay) so it's always on top.
     * <p>
     * For all other situations it's better to not use this method and
     * call {@link Dialog#show()} on the dialog.
     */
    @SuppressWarnings("deprecation")
    public static void showDialog(Activity activity,
                                  Dialog dialog,
                                  boolean isCancelable,
                                  @Nullable DialogFragmentOnStartAction onStartAction) {
        verifyOnMainThread();

        DialogFragmentWrapper fragment = new DialogFragmentWrapper();
        fragment.dialog = dialog;
        fragment.onStartAction = onStartAction;
        fragment.setCancelable(isCancelable);

        fragment.show(activity.getFragmentManager(), null);
    }

    /**
     * Safe to call from any thread.
     */
    public static void showToastShort(String messageToToast) {
        showToast(messageToToast, Toast.LENGTH_SHORT);
    }

    /**
     * Safe to call from any thread.
     */
    public static void showToastLong(String messageToToast) {
        showToast(messageToToast, Toast.LENGTH_LONG);
    }

    /**
     * Safe to call from any thread.
     *
     * @param messageToToast Message to show.
     * @param toastDuration Either {@link Toast#LENGTH_SHORT} or {@link Toast#LENGTH_LONG}.
     */
    public static void showToast(String messageToToast, int toastDuration) {
        Objects.requireNonNull(messageToToast);
        runOnMainThreadNowOrLater(() -> {
            Context currentContext = context;

            if (currentContext == null) {
                Logger.printException(() -> "Cannot show toast (context is null): " + messageToToast);
            } else {
                Logger.printDebug(() -> "Showing toast: " + messageToToast);
                Toast.makeText(currentContext, messageToToast, toastDuration).show();
            }
        });
    }

    /**
     * @return The current dark mode as set by any patch.
     *         Or if none is set, then the system dark mode status is returned.
     */
    public static boolean isDarkModeEnabled() {
        Boolean isDarkMode = isDarkModeEnabled;
        if (isDarkMode != null) {
            return isDarkMode;
        }

        Configuration config = Resources.getSystem().getConfiguration();
        final int currentNightMode = config.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Overrides dark mode status as returned by {@link #isDarkModeEnabled()}.
     */
    public static void setIsDarkModeEnabled(boolean isDarkMode) {
        isDarkModeEnabled = isDarkMode;
        Logger.printDebug(() -> "Dark mode status: " + isDarkMode);
    }

    public static boolean isLandscapeOrientation() {
        final int orientation = Resources.getSystem().getConfiguration().orientation;
        return orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Automatically logs any exceptions the runnable throws.
     *
     * @see #runOnMainThreadNowOrLater(Runnable)
     */
    public static void runOnMainThread(Runnable runnable) {
        runOnMainThreadDelayed(runnable, 0);
    }

    /**
     * Automatically logs any exceptions the runnable throws.
     */
    public static void runOnMainThreadDelayed(Runnable runnable, long delayMillis) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> runAndLogFailure(runnable), delayMillis);
    }

    /**
     * If called from the main thread, the code is run immediately.
     * If called off the main thread, this is the same as {@link #runOnMainThread(Runnable)}.
     */
    public static void runOnMainThreadNowOrLater(Runnable runnable) {
        if (isCurrentlyOnMainThread()) {
            runAndLogFailure(runnable);
        } else {
            runOnMainThread(runnable);
        }
    }

    /** Keeps a fire-and-forget callback from escaping into TikTok's process. */
    private static void runAndLogFailure(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable failure) {
            Logger.printException(
                    () -> runnable.getClass().getSimpleName() + ": " + failure.getMessage(),
                    failure
            );
        }
    }

    /**
     * @return if the calling thread is on the main thread.
     */
    public static boolean isCurrentlyOnMainThread() {
        return Looper.getMainLooper().isCurrentThread();
    }

    /**
     * @throws IllegalStateException if the calling thread is _off_ the main thread.
     */
    public static void verifyOnMainThread() throws IllegalStateException {
        if (!isCurrentlyOnMainThread()) {
            throw new IllegalStateException("Must call _on_ the main thread");
        }
    }

    /**
     * @throws IllegalStateException if the calling thread is _on_ the main thread.
     */
    public static void verifyOffMainThread() throws IllegalStateException {
        if (isCurrentlyOnMainThread()) {
            throw new IllegalStateException("Must call _off_ the main thread");
        }
    }

    public static void openLink(String url) {
        try {
            Intent intent = new Intent("android.intent.action.VIEW", Uri.parse(url));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            Logger.printInfo(() -> "Opening link with external browser: " + intent);
            getContext().startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "openLink failure", ex);
        }
    }

    public enum NetworkType {
        NONE,
        MOBILE,
        OTHER,
    }

    /**
     * Calling extension code must ensure the un-patched app has the permission
     * <code>android.permission.ACCESS_NETWORK_STATE</code>,
     * otherwise the app will crash if this method is used.
     */
    public static boolean isNetworkConnected() {
        NetworkType networkType = getNetworkType();
        return networkType == NetworkType.MOBILE
                || networkType == NetworkType.OTHER;
    }

    /**
     * Calling extension code must ensure the un-patched app has the permission
     * <code>android.permission.ACCESS_NETWORK_STATE</code>,
     * otherwise the app will crash if this method is used.
     */
    @SuppressWarnings({"MissingPermission", "deprecation"})
    public static NetworkType getNetworkType() {
        Context networkContext = getContext();
        if (networkContext == null) {
            return NetworkType.NONE;
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        var networkInfo = cm.getActiveNetworkInfo();

        if (networkInfo == null || !networkInfo.isConnected()) {
            return NetworkType.NONE;
        }
        var type = networkInfo.getType();
        return (type == ConnectivityManager.TYPE_MOBILE)
                || (type == ConnectivityManager.TYPE_BLUETOOTH) ? NetworkType.MOBILE : NetworkType.OTHER;
    }

    /**
     * Hides a view by setting its layout width and height to 0dp.
     * Handles null layout params safely.
     *
     * @param view The view to hide. If null, does nothing.
     */
    public static void hideViewByLayoutParams(@Nullable View view) {
        if (view == null) return;

        ViewGroup.LayoutParams params = view.getLayoutParams();

        if (params == null) {
            // Create generic 0x0 layout params accepted by all ViewGroups.
            params = new ViewGroup.LayoutParams(0, 0);
        } else {
            params.width = 0;
            params.height = 0;
        }

        view.setLayoutParams(params);
    }

    /**
     * Configures the parameters of a dialog window, including its width, gravity, vertical offset and background dimming.
     * The width is calculated as a percentage of the screen's portrait width and the vertical offset is specified in DIP.
     * The default dialog background is removed to allow for custom styling.
     *
     * @param window The {@link Window} object to configure.
     * @param gravity The gravity for positioning the dialog (e.g., {@link Gravity#BOTTOM}).
     * @param yOffsetDip The vertical offset from the gravity position in DIP.
     * @param widthPercentage The width of the dialog as a percentage of the screen's portrait width (0-100).
     * @param dimAmount If true, sets the background dim amount to 0 (no dimming); if false, leaves the default dim amount.
     */
    public static void setDialogWindowParameters(Window window, int gravity, int yOffsetDip, int widthPercentage, boolean dimAmount) {
        WindowManager.LayoutParams params = window.getAttributes();

        params.width = Dim.pctPortraitWidth(widthPercentage);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = gravity;
        params.y = yOffsetDip > 0 ? Dim.dp(yOffsetDip) : 0;
        if (dimAmount) {
            params.dimAmount = 0f;
        }

        window.setAttributes(params); // Apply window attributes.
        window.setBackgroundDrawable(null); // Remove default dialog background
    }

    /**
     * The recommended app version of this app to patch. Set during patching.
     * Returns an empty string if not set.
     */
    public static String getRecommendedAppVersion() {
        return "";
    }

    /**
     * @return If the unpatched app is currently using bold icons.
     */
    public static boolean appIsUsingBoldIcons() {
        return appIsUsingBoldIcons;
    }

    /**
     * Controls if Morphe bold icons are shown in various places.
     * @param boldIcons If the app is currently using bold icons.
     */
    public static void setAppIsUsingBoldIcons(boolean boldIcons) {
        appIsUsingBoldIcons = boldIcons;
    }

    /**
     * Sets the theme light color used by the app.
     */
    public static void setThemeLightColor(@ColorInt int color) {
        Logger.printDebug(() -> "Setting theme light color: " + getColorHexString(color));
        lightColor = color;
    }

    /**
     * Sets the theme dark used by the app.
     */
    public static void setThemeDarkColor(@ColorInt int color) {
        Logger.printDebug(() -> "Setting theme dark color: " + getColorHexString(color));
        darkColor = color;
    }

    /**
     * Returns the themed light color, or {@link Color#WHITE} if no theme was set using
     * {@link #setThemeLightColor(int).
     */
    @ColorInt
    public static int getThemeLightColor() {
        return lightColor;
    }

    /**
     * Returns the themed dark color, or {@link Color#BLACK} if no theme was set using
     * {@link #setThemeDarkColor(int)}.
     */
    @ColorInt
    public static int getThemeDarkColor() {
        return darkColor;
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("SameReturnValue")
    private static String getThemeLightColorResourceName() {
        // Value is changed by Settings patch.
        return "#FFFFFFFF";
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("SameReturnValue")
    private static String getThemeDarkColorResourceName() {
        // Value is changed by Settings patch.
        return "#FF000000";
    }

    @ColorInt
    private static int getThemeColor(String resourceName, int defaultColor) {
        try {
            return getColorFromString(resourceName);
        } catch (Exception ex) {
            // This code can never be reached since a bad custom color will
            // fail during resource compilation. So no localized strings are needed here.
            Logger.printException(() -> "Invalid custom theme color: " + resourceName, ex);
            return defaultColor;
        }
    }


    @ColorInt
    public static int getDialogBackgroundColor() {
        if (isDarkModeEnabled()) {
            final int darkColor = getThemeDarkColor();
            return darkColor == Color.BLACK
                    // Lighten the background a little if using AMOLED dark theme
                    // as the dialogs are almost invisible.
                    ? 0xFF080808 // 3%
                    : darkColor;
        }
        return getThemeLightColor();
    }

    /**
     * @return The current app background color.
     */
    @ColorInt
    public static int getAppBackgroundColor() {
        return isDarkModeEnabled() ? getThemeDarkColor() : getThemeLightColor();
    }

    /**
     * @return The current app foreground color.
     */
    @ColorInt
    public static int getAppForegroundColor() {
        return isDarkModeEnabled()
                ? getThemeLightColor()
                : getThemeDarkColor();
    }

    @ColorInt
    public static int getOkButtonBackgroundColor() {
        return isDarkModeEnabled()
                // Must be inverted color.
                ? Color.WHITE
                : Color.BLACK;
    }

    @ColorInt
    public static int getCancelOrNeutralButtonBackgroundColor() {
        return isDarkModeEnabled()
                ? adjustColorBrightness(getDialogBackgroundColor(), 1.10f)
                : adjustColorBrightness(getThemeLightColor(), 0.95f);
    }

    @ColorInt
    public static int getEditTextBackground() {
        return isDarkModeEnabled()
                ? adjustColorBrightness(getDialogBackgroundColor(), 1.05f)
                : adjustColorBrightness(getThemeLightColor(), 0.97f);
    }

    public static String getColorHexString(@ColorInt int color) {
        return String.format("#%06X", (0x00FFFFFF & color));
    }

    /**
     * {@link PreferenceScreen} and {@link PreferenceGroup} sorting styles.
     */
    private enum Sort {
        /**
         * Sort by the localized preference title.
         */
        BY_TITLE("_sort_by_title"),

        /**
         * Sort by the preference keys.
         */
        BY_KEY("_sort_by_key"),

        /**
         * Unspecified sorting.
         */
        UNSORTED("_sort_by_unsorted");

        final String keySuffix;

        Sort(String keySuffix) {
            this.keySuffix = keySuffix;
        }

        static Sort fromKey(@Nullable String key, Sort defaultSort) {
            if (key != null) {
                for (Sort sort : values()) {
                    if (key.endsWith(sort.keySuffix)) {
                        return sort;
                    }
                }
            }
            return defaultSort;
        }
    }

    /**
     * Removes punctuation and converts text to lowercase. Returns an empty string if input is null.
     */
    public static String removePunctuationToLowercase(@Nullable CharSequence original) {
        if (original == null) return "";
        return PUNCTUATION_PATTERN.matcher(original).replaceAll("")
                .toLowerCase(BaseSettings.MORPHE_LANGUAGE.get().getLocale());
    }

    /**
     * Normalizes text for search: applies NFD, removes diacritics, and lowercases (locale-neutral).
     * Returns an empty string if input is null.
     */
    public static String normalizeTextToLowercase(@Nullable CharSequence original) {
        if (original == null) return "";
        return DIACRITICS_PATTERN.matcher(Normalizer.normalize(original, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * Returns a cached Collator for the current locale, or creates a new one if locale changed.
     */
    private static Collator getCollator() {
        Locale currentLocale = BaseSettings.MORPHE_LANGUAGE.get().getLocale();

        if (cachedCollator == null || !currentLocale.equals(cachedCollatorLocale)) {
            cachedCollatorLocale = currentLocale;
            cachedCollator = Collator.getInstance(currentLocale);
            cachedCollator.setStrength(Collator.SECONDARY); // Case-insensitive, diacritic-insensitive.
        }

        return cachedCollator;
    }

    /**
     * Sorts a {@link PreferenceGroup} and all nested subgroups by title or key.
     * <p>
     * The sort order is controlled by the {@link Sort} suffix present in the preference key.
     * Preferences without a key or without a {@link Sort} suffix remain in their original order.
     * <p>
     * Sorting is performed using {@link Collator} with the current user locale,
     * ensuring correct alphabetical ordering for all supported languages
     * (e.g., Ukrainian "і", German "ß", French accented characters, etc.).
     *
     * @param group the {@link PreferenceGroup} to sort
     */
    @SuppressWarnings("deprecation")
    public static void sortPreferenceGroups(PreferenceGroup group) {
        Sort groupSort = Sort.fromKey(group.getKey(), Sort.UNSORTED);
        List<Pair<String, Preference>> preferences = new ArrayList<>();

        // Get cached Collator for locale-aware string comparison.
        Collator collator = getCollator();

        for (int i = 0, prefCount = group.getPreferenceCount(); i < prefCount; i++) {
            Preference preference = group.getPreference(i);

            final Sort preferenceSort;
            if (preference instanceof PreferenceGroup subGroup) {
                sortPreferenceGroups(subGroup);
                preferenceSort = groupSort; // Sort value for groups is for it's content, not itself.
            } else {
                // Allow individual preferences to set a key sorting.
                // Used to force a preference to the top or bottom of a group.
                preferenceSort = Sort.fromKey(preference.getKey(), groupSort);
            }

            final String sortValue;
            switch (preferenceSort) {
                case BY_TITLE:
                    sortValue = removePunctuationToLowercase(preference.getTitle());
                    break;
                case BY_KEY:
                    sortValue = preference.getKey();
                    break;
                case UNSORTED:
                    continue; // Keep original sorting.
                default:
                    throw new IllegalStateException();
            }

            preferences.add(new Pair<>(sortValue, preference));
        }

        // Sort the list using locale-specific collation rules.
        Collections.sort(preferences, (pair1, pair2)
                -> collator.compare(pair1.first, pair2.first));

        // Reassign order values to reflect the new sorted sequence
        int index = 0;
        for (Pair<String, Preference> pair : preferences) {
            int order = index++;
            Preference pref = pair.second;

            // Move any screens and intents to the top. The About preference this also named was
            // never built here and its class is gone.
            if (pref instanceof PreferenceScreen || pref.getIntent() != null) {
                // Any arbitrary large number.
                order -= 1000;
            }

            pref.setOrder(order);
        }
    }

    /**
     * Set all preferences to multiline titles if the device is not using an English variant.
     * The English strings are heavily scrutinized and all titles fit on screen
     * except 2 or 3 preference strings and those do not affect readability.
     * <p>
     * Allowing multiline for those 2 or 3 English preferences looks weird and out of place,
     * and visually it looks better to clip the text and keep all titles 1 line.
     */
    @SuppressWarnings("deprecation")
    public static void setPreferenceTitlesToMultiLineIfNeeded(PreferenceGroup group) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        String morpheLocale = Utils.getContext().getResources().getConfiguration().locale.getLanguage();
        if (morpheLocale.equals(Locale.ENGLISH.getLanguage())) {
            return;
        }

        for (int i = 0, prefCount = group.getPreferenceCount(); i < prefCount; i++) {
            Preference pref = group.getPreference(i);
            pref.setSingleLineTitle(false);

            if (pref instanceof PreferenceGroup subGroup) {
                setPreferenceTitlesToMultiLineIfNeeded(subGroup);
            }
        }
    }

    /**
     * Parse a color resource or hex code to an int representation of the color.
     */
    @ColorInt
    public static int getColorFromString(String colorString) throws IllegalArgumentException, Resources.NotFoundException {
        if (colorString.startsWith("#")) {
            return Color.parseColor(colorString);
        }
        return ResourceUtils.getColor(colorString);
    }

    /**
     * Uses {@link #adjustColorBrightness(int, float)} depending if light or dark mode is active.
     */
    @ColorInt
    public static int adjustColorBrightness(@ColorInt int baseColor, float lightThemeFactor, float darkThemeFactor) {
        return isDarkModeEnabled()
                ? adjustColorBrightness(baseColor, darkThemeFactor)
                : adjustColorBrightness(baseColor, lightThemeFactor);
    }

    /**
     * Adjusts the brightness of a color by lightening or darkening it based on the given factor.
     * <p>
     * If the factor is greater than 1, the color is lightened by interpolating toward white (#FFFFFF).
     * If the factor is less than or equal to 1, the color is darkened by scaling its RGB components toward black (#000000).
     * The alpha channel remains unchanged.
     *
     * @param color  The input color to adjust, in ARGB format.
     * @param factor The adjustment factor. Use values > 1.0f to lighten (e.g., 1.11f for slight lightening)
     *               or values <= 1.0f to darken (e.g., 0.95f for slight darkening).
     * @return The adjusted color in ARGB format.
     */
    @ColorInt
    public static int adjustColorBrightness(@ColorInt int color, float factor) {
        final int alpha = Color.alpha(color);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);

        if (factor > 1.0f) {
            // Lighten: Interpolate toward white (255).
            final float t = 1.0f - (1.0f / factor); // Interpolation parameter.
            red = Math.round(red + (255 - red) * t);
            green = Math.round(green + (255 - green) * t);
            blue = Math.round(blue + (255 - blue) * t);
        } else {
            // Darken or no change: Scale toward black.
            red = Math.round(red * factor);
            green = Math.round(green * factor);
            blue = Math.round(blue * factor);
        }

        // Ensure values are within [0, 255].
        red = clamp(red, 0, 255);
        green = clamp(green, 0, 255);
        blue = clamp(blue, 0, 255);

        return Color.argb(alpha, red, green, blue);
    }

    public static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    public static float clamp(float value, float lower, float upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    /**
     * @param maxSize The maximum number of elements to keep in the map.
     * @return A {@link LinkedHashMap} that automatically evicts the oldest entry
     *        when the size exceeds {@code maxSize}.
     */
    public static <T, V> Map<T, V> createSizeRestrictedMap(int maxSize) {
        return new LinkedHashMap<>(2 * maxSize) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > maxSize;
            }
        };
    }

    /**
     * @return whether the device's API level is higher than a specific SDK version.
     */
    @ChecksSdkIntAtLeast(parameter = 0)
    public static boolean isSDKAbove(int sdk) {
        return Build.VERSION.SDK_INT >= sdk;
    }
}
