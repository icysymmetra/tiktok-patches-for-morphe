package app.hushfeed.verification;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Explicit, temporary native edge drags. No settings persist and no application buttons are clicked. */
final class EdgeDragProbe {
    private static final String TARGET = "com.zhiliaoapp.musically", VERSION = "47.4.3";
    private static final String PREFIX = "app.morphe.extension.tiktok.";
    private static final String UTILS = "app.morphe.extension.shared.Utils";
    private static final String JOURNAL = "hushfeed-edge-drag-restore.json";
    private static final String RECEIPT = "hushfeed-edge-drag-receipt.json";
    private static final int LIMIT = 16384, STREAM = AudioManager.STREAM_MUSIC;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Owned owned;

    private interface MainWork { void run() throws Exception; }

    private static final class Choice {
        final Object setting, before, fixture, raw;
        final String key;
        final boolean present;
        Choice(Object setting, Object fixture, Class<?> base, Map<String, ?> persisted) throws Exception {
            this.setting = setting; this.fixture = fixture;
            before = field(base, "value").get(setting);
            key = (String) field(base, "key").get(setting);
            present = persisted.containsKey(key); raw = persisted.get(key);
        }
    }

    private static final class Owned {
        final Context context;
        final ClassLoader loader;
        final String owner, mode;
        final Activity activity;
        final Window window;
        final View decor, comments, rail, pager;
        final Rect bounds, commentBounds, railBounds;
        final AudioManager audio;
        final int volume, min, max;
        final boolean mute, dimmedPresent;
        final float brightness, initialLevel;
        final Object dimmedBefore;
        final Map<Window, Object> dimmed;
        final String devices;
        final SharedPreferences preferences;
        final Class<?> setting, edges;
        final Map<String, Choice> choices = new LinkedHashMap<>();
        final List<EdgeDragPlan.Event> events;
        final AtomicFile journal;
        final JSONObject receipt = new JSONObject();
        final long began = SystemClock.elapsedRealtime();
        final int[] nativeEvents = new int[4];
        List<Object> listeners;
        Object observer;
        Window.Callback innerCallback, inputGate;
        int injected;
        volatile boolean stopped, workerActive;
        volatile boolean pointerOpen;
        volatile long inputDown;
        volatile EdgeDragPlan.Event lastInput;
        boolean sequenceComplete, cancelSent, cancelRequired, cancelViaWindowCleanup;
        final Runnable watchdog;

        @SuppressWarnings("unchecked")
        Owned(Context context, ClassLoader loader, Intent intent) throws Exception {
            this.context = context; this.loader = loader;
            owner = required(intent, "owner"); mode = required(intent, "mode");
            validateOwner(owner);
            if (!"brightness".equals(mode) && !"volume".equals(mode)) throw new IllegalArgumentException("mode must be brightness or volume");
            checkHost(context);
            activity = current(loader);
            if (activity == null) throw new IllegalStateException("no visible native activity");
            window = activity.getWindow(); decor = window.getDecorView();
            checkForeground(this);
            if (BudgetFocusProbe.active() || Boolean.TRUE.equals(call(loader, PREFIX + "wellbeing.SessionBudget", "isLocked"))
                    || Boolean.TRUE.equals(call(loader, PREFIX + "wellbeing.FeedLock", "isOn"))
                    || Boolean.TRUE.equals(call(loader, "app.morphe.extension.shared.settings.Setting", "isPaused"))) {
                throw new IllegalStateException("another hold or Pause owns the feed");
            }
            if (!Boolean.TRUE.equals(method(loader.loadClass(PREFIX + "feed.VideoOverlayHider"), "tapsGoThroughNow").invoke(null))) {
                throw new IllegalStateException("native faded Clear display must already be active");
            }
            bounds = visibleBounds(decor);
            if (bounds.width() != decor.getWidth() || bounds.height() != decor.getHeight()) {
                throw new IllegalStateException("native window is clipped; coordinate mapping refused");
            }
            comments = unique(decor, activity.getResources().getIdentifier("eq2", "id", TARGET));
            rail = unique(decor, activity.getResources().getIdentifier("mud", "id", TARGET));
            commentBounds = visibleBounds(comments); railBounds = visibleBounds(rail);
            if (!railBounds.contains(commentBounds)) throw new IllegalStateException("comments are outside the observed native rail");
            pager = pagerAbove(rail);
            audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audio == null || audio.isVolumeFixed()) throw new IllegalStateException("mutable native media volume is required");
            volume = audio.getStreamVolume(STREAM); mute = audio.isStreamMute(STREAM);
            min = audio.getStreamMinVolume(STREAM); max = audio.getStreamMaxVolume(STREAM);
            if (max <= min || volume < min || volume > max) throw new IllegalStateException("invalid native music volume range");
            if ("volume".equals(mode) && mute) throw new IllegalStateException("pre-muted stream hides its last audible index; volume fixture refused");
            devices = devices(audio);
            if (boot(context) < 0) throw new IllegalStateException("native boot identity is unavailable");
            brightness = window.getAttributes().screenBrightness;
            if (!Float.isFinite(brightness) || (brightness != -1f && (brightness < 0 || brightness > 1))) {
                throw new IllegalStateException("invalid native per-window brightness");
            }
            edges = loader.loadClass(PREFIX + "feed.EdgeSwipeLevels");
            initialLevel = "brightness".equals(mode)
                    ? ((Number) method(edges, "currentBrightness", Activity.class).invoke(null, activity)).floatValue()
                    : volume / (float) max;
            dimmed = (Map<Window, Object>) field(edges, "DIMMED").get(null);
            dimmedPresent = dimmed.containsKey(window); dimmedBefore = dimmed.get(window);
            setting = loader.loadClass("app.morphe.extension.shared.settings.Setting");
            Object category = field(setting, "preferences").get(null);
            preferences = (SharedPreferences) field(category.getClass(), "preferences").get(category);
            Map<String, ?> persisted = preferences.getAll();
            Class<?> settings = loader.loadClass(PREFIX + "settings.Settings");
            choices.put("SWIPE_LEVELS", new Choice(settings.getField("SWIPE_LEVELS").get(null), true, setting, persisted));
            choices.put("SWIPE_LEVELS_RIGHT", new Choice(settings.getField("SWIPE_LEVELS_RIGHT").get(null), mode, setting, persisted));
            choices.put("SWIPE_LEVELS_STRIP_PERCENT", new Choice(settings.getField("SWIPE_LEVELS_STRIP_PERCENT").get(null), 15, setting, persisted));
            events = EdgeDragPlan.create(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    commentBounds.exactCenterX(), commentBounds.exactCenterY(), initialLevel,
                    ViewConfiguration.get(activity).getScaledTouchSlop(), integer(intent, "steps", 24), integer(intent, "duration", 600));
            journal = file(context, JOURNAL);
            receipt.put("owner", owner).put("mode", mode).put("version", VERSION).put("display", 0)
                    .put("capturedUptime", SystemClock.uptimeMillis())
                    .put("window", bounds.toShortString()).put("comments", commentBounds.toShortString())
                    .put("rail", railBounds.toShortString()).put("brightnessBefore", brightness)
                    .put("dimmedBefore", dimmedPresent).put("volumeBefore", volume).put("muteBefore", mute);
            watchdog = () -> {
                stopped = true;
                Log.e(Probe.TAG, "edge-drag watchdog owner=" + owner + " nativeInjectionPending=" + workerActive);
                try {
                    try { cancelOwnedWindow(this); }
                    finally { restore(this, "watchdog"); }
                }
                catch (Exception error) { failure(owner, "watchdog", error); }
            };
        }
    }

    static void run(Context context, ClassLoader loader, Intent intent) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("edge fixture requires main thread");
        String op = intent.getStringExtra("op");
        if (op == null || "status".equals(op)) {
            Log.i(Probe.TAG, "edge-drag status owned=" + (owned != null) + " journal=" + exists(file(context, JOURNAL))
                    + " receipt=" + (exists(file(context, RECEIPT)) ? read(file(context, RECEIPT)) : "none"));
            return;
        }
        if ("restore".equals(op)) {
            String owner = required(intent, "owner"); validateOwner(owner);
            if (owned == null) throw new IllegalStateException("no live fixture; use explicit recover after a process restart");
            if (!owned.owner.equals(owner)) throw new IllegalStateException("fixture owner mismatch");
            owned.stopped = true;
            try { cancelOwnedWindow(owned); }
            finally { restore(owned, "requested"); }
            return;
        }
        if ("recover".equals(op)) { recover(context, required(intent, "owner")); return; }
        if (!"run".equals(op)) throw new IllegalArgumentException("expected status, run, restore or recover");
        for (String key : intent.getExtras().keySet()) {
            if (!"action".equals(key) && !"op".equals(key) && !"owner".equals(key) && !"mode".equals(key)
                    && !"steps".equals(key) && !"duration".equals(key)) throw new IllegalArgumentException("unknown edge fixture argument");
        }
        if (owned != null || exists(file(context, JOURNAL))) throw new IllegalStateException("previous edge fixture must be restored first");
        Owned pending = new Owned(context, loader, intent);
        JSONObject journal = new JSONObject().put("schema", 1).put("owner", pending.owner).put("mode", pending.mode)
                .put("pid", android.os.Process.myPid()).put("boot", boot(context)).put("version", VERSION)
                .put("volume", pending.volume).put("mute", pending.mute).put("min", pending.min).put("max", pending.max)
                .put("devices", pending.devices).put("brightnessBits", Float.floatToIntBits(pending.brightness));
        write(pending.journal, journal);
        owned = pending;
        try {
            addObserver(pending);
            for (Choice choice : pending.choices.values()) field(pending.setting, "value").set(choice.setting, choice.fixture);
            method(pending.edges, "sync", Activity.class).invoke(null, pending.activity);
            method(loader.loadClass(PREFIX + "feed.VideoOverlayHider"), "applyTo", Activity.class).invoke(null, pending.activity);
            guard(pending);
            installInputGate(pending);
            pending.workerActive = true;
            if (!MAIN.postDelayed(pending.watchdog, 7000)) throw new IllegalStateException("edge watchdog scheduling failed");
            Thread worker = new Thread(() -> drive(pending), "hushfeed-edge-drag");
            worker.setDaemon(true); worker.start();
            Log.i(Probe.TAG, "edge-drag began " + pending.receipt);
        } catch (Exception | Error error) {
            pending.workerActive = false;
            try { restore(pending, "begin_failed"); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static void drive(Owned snapshot) {
        try {
            EdgeDragPlan.execute(snapshot.events, new EdgeDragPlan.Driver() {
                final Instrumentation input = new Instrumentation();
                @Override public long now() { return SystemClock.uptimeMillis(); }
                @Override public void waitUntil(long time) { SystemClock.sleep(Math.max(0, time - now())); }
                @Override public void guard() throws Exception { onMain(() -> EdgeDragProbe.guard(snapshot)); }
                @Override public void inject(EdgeDragPlan.Event event, long down) throws Exception {
                    snapshot.inputDown = down; snapshot.lastInput = event;
                    if (event.action == EdgeDragPlan.DOWN) snapshot.pointerOpen = true;
                    send(input, event.action, event.x, event.y, down);
                    snapshot.injected++;
                    if (event.action == EdgeDragPlan.UP) {
                        snapshot.pointerOpen = false;
                    }
                }
                @Override public void completed() { snapshot.sequenceComplete = !snapshot.stopped; }
                @Override public void cancel(EdgeDragPlan.Event last, long down) throws Exception {
                    snapshot.cancelRequired = true;
                    try {
                        onMain(() -> checkForeground(snapshot));
                        send(input, MotionEvent.ACTION_CANCEL, last.x, last.y, down);
                        snapshot.cancelSent = true; snapshot.pointerOpen = false;
                    } catch (Exception nativeCancelFailed) {
                        onMain(() -> cancelOwnedWindow(snapshot));
                    }
                }
                @Override public void restore() throws Exception {
                    snapshot.workerActive = false;
                    onMain(() -> EdgeDragProbe.restore(snapshot, snapshot.sequenceComplete ? "completed" : "interrupted"));
                }
            });
        } catch (Exception error) { failure(snapshot.owner, "native_drag", error); }
    }

    private static void send(Instrumentation input, int action, float x, float y, long down) {
        MotionEvent event = event(action, x, y, down);
        try { input.sendPointerSync(event); } finally { event.recycle(); }
    }

    private static MotionEvent event(int action, float x, float y, long down) {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0; property.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords point = new MotionEvent.PointerCoords();
        point.x = x; point.y = y; point.pressure = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : 1;
        point.size = 1;
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{property}, new MotionEvent.PointerCoords[]{point},
                0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0, 0, MotionEvent.CLASSIFICATION_NONE);
    }

    /** Blocks only late events carrying our down time. Other native input and callbacks pass through. */
    private static void installInputGate(Owned snapshot) {
        snapshot.innerCallback = snapshot.window.getCallback();
        if (snapshot.innerCallback == null) throw new IllegalStateException("native window callback absent");
        snapshot.inputGate = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class}, (self, member, args) -> {
                    if ("dispatchTouchEvent".equals(member.getName()) && args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                        MotionEvent event = (MotionEvent) args[0];
                        if (snapshot.stopped && snapshot.inputDown != 0 && event.getDownTime() == snapshot.inputDown
                                && event.getSource() == InputDevice.SOURCE_TOUCHSCREEN
                                && event.getDeviceId() == 0) return true;
                    }
                    try { return member.invoke(snapshot.innerCallback, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        snapshot.window.setCallback(snapshot.inputGate);
    }

    /** Cleanup only. Never counted as successful native gesture injection. */
    private static void cancelOwnedWindow(Owned snapshot) {
        if (!snapshot.pointerOpen || snapshot.innerCallback == null || snapshot.inputDown == 0) return;
        EdgeDragPlan.Event last = snapshot.lastInput;
        MotionEvent cancel = event(MotionEvent.ACTION_CANCEL, last.x, last.y, snapshot.inputDown);
        snapshot.cancelRequired = true;
        try {
            snapshot.innerCallback.dispatchTouchEvent(cancel);
            snapshot.cancelViaWindowCleanup = true; snapshot.pointerOpen = false;
        } finally { cancel.recycle(); }
    }

    private static void guard(Owned snapshot) throws Exception {
        if (owned != snapshot || snapshot.stopped) throw new IllegalStateException("edge fixture no longer owns input");
        checkForeground(snapshot);
        if (snapshot.inputGate != null && snapshot.window.getCallback() != snapshot.inputGate) {
            throw new IllegalStateException("native window callback owner changed");
        }
        if (!visibleBounds(snapshot.comments).equals(snapshot.commentBounds)
                || !visibleBounds(snapshot.rail).equals(snapshot.railBounds)
                || !visibleBounds(snapshot.decor).equals(snapshot.bounds)) throw new IllegalStateException("native bounds changed");
        if (!settingsMatch(snapshot, false) || !devices(snapshot.audio).equals(snapshot.devices)
                || snapshot.audio.getStreamMaxVolume(STREAM) != snapshot.max
                || snapshot.audio.getStreamMinVolume(STREAM) != snapshot.min) throw new IllegalStateException("fixture settings or audio route owner changed");
        for (Choice choice : snapshot.choices.values()) {
            if (!Objects.equals(field(snapshot.setting, "value").get(choice.setting), choice.fixture)) {
                throw new IllegalStateException("fixture live setting no longer owns the drag");
            }
        }
        if (BudgetFocusProbe.active() || Boolean.TRUE.equals(call(snapshot.loader, PREFIX + "wellbeing.SessionBudget", "isLocked"))
                || Boolean.TRUE.equals(call(snapshot.loader, PREFIX + "wellbeing.FeedLock", "isOn"))
                || !Boolean.TRUE.equals(method(snapshot.loader.loadClass(PREFIX + "feed.VideoOverlayHider"), "tapsGoThroughNow").invoke(null))) {
            throw new IllegalStateException("faded native feed ownership changed");
        }
    }

    private static void restore(Owned snapshot, String reason) throws Exception {
        if (owned != snapshot) return;
        snapshot.stopped = true; MAIN.removeCallbacks(snapshot.watchdog);
        JSONObject out = snapshot.receipt;
        out.put("reason", reason).put("elapsedMs", SystemClock.elapsedRealtime() - snapshot.began)
                .put("inputDownUptime", snapshot.inputDown)
                .put("nativeInputCount", snapshot.injected).put("sequenceComplete", snapshot.sequenceComplete)
                .put("cancelRequired", snapshot.cancelRequired).put("cancelSent", snapshot.cancelSent)
                .put("cancelViaWindowCleanup", snapshot.cancelViaWindowCleanup).put("nativeInjectionPending", snapshot.workerActive)
                .put("nativePagerDown", snapshot.nativeEvents[0]).put("nativePagerUp", snapshot.nativeEvents[1]).put("nativePagerMove", snapshot.nativeEvents[2])
                .put("nativePagerCancel", snapshot.nativeEvents[3]);
        float after = snapshot.window.getAttributes().screenBrightness;
        int afterVolume = snapshot.audio.getStreamVolume(STREAM); boolean afterMute = snapshot.audio.isStreamMute(STREAM);
        View indicator = snapshot.decor.findViewWithTag("hushfeed_swipe_level");
        out.put("brightnessAfter", after).put("volumeAfter", afterVolume).put("muteAfter", afterMute)
                .put("levelIndicatorShown", indicator != null && indicator.isShown());
        boolean levelChanged = "volume".equals(snapshot.mode) ? afterVolume != snapshot.volume
                : after >= 0 && Math.abs(after - snapshot.initialLevel) > 0.00001f;
        out.put("nativeLevelChanged", levelChanged).put("nativePathObserved", snapshot.nativeEvents[0] > 0 && snapshot.nativeEvents[3] > 0);
        Exception failure = null;
        boolean settingsOwned = settingsMatch(snapshot, false);
        try {
            if (!settingsOwned) throw new IllegalStateException("edge setting ownership changed; original journal retained");
            for (Choice choice : snapshot.choices.values()) field(snapshot.setting, "value").set(choice.setting, choice.before);
            method(snapshot.edges, "sync", Activity.class).invoke(null, snapshot.activity);
        } catch (Exception error) { failure = error; }
        if (settingsOwned) try {
            EdgeDragRestoration.brightness(new EdgeDragRestoration.Brightness() {
                @Override public float value() { return snapshot.window.getAttributes().screenBrightness; }
                @Override public boolean dimmedPresent() { return snapshot.dimmed.containsKey(snapshot.window); }
                @Override public Object dimmed() { return snapshot.dimmed.get(snapshot.window); }
                @Override public void value(float value) {
                    WindowManager.LayoutParams attrs = snapshot.window.getAttributes(); attrs.screenBrightness = value;
                    snapshot.window.setAttributes(attrs);
                }
                @Override public void dimmed(boolean present, Object value) {
                    if (present) snapshot.dimmed.put(snapshot.window, value); else snapshot.dimmed.remove(snapshot.window);
                }
            }, snapshot.brightness, snapshot.dimmedPresent, snapshot.dimmedBefore);
        } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        try { restoreAudio(snapshot.audio, read(snapshot.journal), snapshot.context, snapshot.mode); }
        catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        boolean callbackExact = snapshot.inputGate == null;
        if (!snapshot.workerActive) {
            try {
                if (snapshot.listeners != null && snapshot.observer != null && snapshot.listeners.contains(snapshot.observer)) snapshot.listeners.remove(snapshot.observer);
            } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            try {
                if (snapshot.inputGate != null && snapshot.window.getCallback() == snapshot.inputGate) snapshot.window.setCallback(snapshot.innerCallback);
            } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            callbackExact = snapshot.inputGate == null || snapshot.window.getCallback() == snapshot.innerCallback;
        }
        boolean brightnessExact = Float.floatToIntBits(snapshot.window.getAttributes().screenBrightness) == Float.floatToIntBits(snapshot.brightness)
                && snapshot.dimmed.containsKey(snapshot.window) == snapshot.dimmedPresent
                && Objects.equals(snapshot.dimmed.get(snapshot.window), snapshot.dimmedBefore);
        boolean settingsExact = settingsMatch(snapshot, true);
        boolean audioExact = snapshot.audio.getStreamVolume(STREAM) == snapshot.volume && snapshot.audio.isStreamMute(STREAM) == snapshot.mute;
        boolean observerRemoved = snapshot.listeners == null || !snapshot.listeners.contains(snapshot.observer);
        boolean exact = failure == null && brightnessExact && settingsExact && audioExact && observerRemoved && callbackExact && !snapshot.workerActive;
        out.put("brightnessRestored", snapshot.window.getAttributes().screenBrightness)
                .put("volumeRestored", snapshot.audio.getStreamVolume(STREAM)).put("muteRestored", snapshot.audio.isStreamMute(STREAM))
                .put("brightnessExact", brightnessExact).put("settingsLiveAndPersistedExact", settingsExact)
                .put("audioExact", audioExact).put("observerRemoved", observerRemoved).put("callbackExact", callbackExact).put("restoredExact", exact)
                .put("accepted", exact && snapshot.sequenceComplete && levelChanged && snapshot.nativeEvents[0] > 0 && snapshot.nativeEvents[3] > 0);
        write(file(snapshot.context, RECEIPT), out);
        Log.i(Probe.TAG, "edge-drag receipt " + out);
        if (!exact) throw new IllegalStateException("edge restoration incomplete; private journal retained", failure);
        snapshot.journal.delete();
        if (exists(snapshot.journal)) throw new IllegalStateException("restored edge journal could not be removed");
        owned = null;
    }

    private static boolean settingsMatch(Owned snapshot, boolean restored) throws Exception {
        Map<String, ?> persisted = snapshot.preferences.getAll();
        for (Choice choice : snapshot.choices.values()) {
            Object live = field(snapshot.setting, "value").get(choice.setting);
            if ((!Objects.equals(live, choice.before) && (restored || !Objects.equals(live, choice.fixture)))
                    || persisted.containsKey(choice.key) != choice.present || !Objects.equals(persisted.get(choice.key), choice.raw)) return false;
        }
        return true;
    }

    private static void recover(Context context, String owner) throws Exception {
        validateOwner(owner); checkHost(context);
        if (owned != null) throw new IllegalStateException("live fixture requires restore");
        AtomicFile journal = file(context, JOURNAL); JSONObject saved = read(journal);
        if (!owner.equals(saved.getString("owner"))) throw new IllegalStateException("journal owner mismatch");
        if (saved.getInt("pid") == android.os.Process.myPid()) throw new IllegalStateException("same-process journal needs its live window snapshot");
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        restoreAudio(audio, saved, context, saved.getString("mode"));
        JSONObject receipt = new JSONObject().put("owner", owner).put("reason", "explicit_process_recovery")
                .put("oldWindowExpired", true).put("persistedSettingsNeverChanged", true).put("audioExact", true)
                .put("volumeRestored", audio.getStreamVolume(STREAM)).put("muteRestored", audio.isStreamMute(STREAM)).put("accepted", false);
        write(file(context, RECEIPT), receipt); journal.delete();
        if (exists(journal)) throw new IllegalStateException("recovered edge journal could not be removed");
        Log.i(Probe.TAG, "edge-drag receipt " + receipt);
    }

    private static void restoreAudio(AudioManager audio, JSONObject saved, Context context, String mode) throws Exception {
        if (audio == null || saved.getInt("schema") != 1 || !VERSION.equals(saved.getString("version"))
                || saved.getInt("boot") < 0 || saved.getInt("boot") != boot(context)) {
            throw new IllegalStateException("audio restoration route or boot owner changed");
        }
        EdgeDragRestoration.audio(new EdgeDragRestoration.Audio() {
            @Override public int index() { return audio.getStreamVolume(STREAM); }
            @Override public boolean muted() { return audio.isStreamMute(STREAM); }
            @Override public int min() { return audio.getStreamMinVolume(STREAM); }
            @Override public int max() { return audio.getStreamMaxVolume(STREAM); }
            @Override public String route() { return devices(audio); }
            @Override public void index(int value) { audio.setStreamVolume(STREAM, value, 0); }
            @Override public void muted(boolean value) { audio.adjustStreamVolume(STREAM, value ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0); }
        }, saved.getInt("volume"), saved.getBoolean("mute"), saved.getInt("min"), saved.getInt("max"), saved.getString("devices"), "volume".equals(mode));
    }

    @SuppressWarnings("unchecked")
    private static void addObserver(Owned snapshot) throws Exception {
        snapshot.listeners = (List<Object>) method(snapshot.pager.getClass(), "getOnInterceptTouchEventListeners").invoke(snapshot.pager);
        Class<?> listenerType = null;
        for (Object existing : snapshot.listeners) for (Class<?> type : existing.getClass().getInterfaces()) {
            for (Method member : type.getMethods()) if (member.getName().equals("dispatchTouchEvent")
                    && member.getParameterCount() == 1 && member.getParameterTypes()[0] == MotionEvent.class) listenerType = type;
        }
        if (listenerType == null) throw new IllegalStateException("native pager touch listener contract absent");
        snapshot.observer = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (self, member, args) -> {
            if (member.getDeclaringClass() == Object.class) {
                if ("equals".equals(member.getName())) return self == args[0];
                if ("hashCode".equals(member.getName())) return System.identityHashCode(self);
                return "edge-drag-observer";
            }
            if ("dispatchTouchEvent".equals(member.getName()) && args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                int action = ((MotionEvent) args[0]).getActionMasked();
                if (action >= 0 && action < snapshot.nativeEvents.length) snapshot.nativeEvents[action]++;
            }
            return member.getReturnType() == boolean.class ? Boolean.FALSE : null;
        });
        snapshot.listeners.add(snapshot.observer);
    }

    private static View pagerAbove(View start) throws Exception {
        for (View view = start; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            try { method(view.getClass(), "getOnInterceptTouchEventListeners"); return view; }
            catch (NoSuchMethodException absent) { }
        }
        throw new IllegalStateException("observed rail has no native pager ancestor");
    }
    private static View unique(View root, int id) {
        if (id == 0) throw new IllegalStateException("native rail/comments resource absent");
        View found = null; int visited = 0;
        ArrayDeque<View> pending = new ArrayDeque<>(); pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (++visited > 5000) throw new IllegalStateException("native hierarchy exceeds fixture bound");
            Rect rect = new Rect();
            if (view.getId() == id && view.isShown() && view.isEnabled() && view.isAttachedToWindow() && view.getGlobalVisibleRect(rect) && !rect.isEmpty()) {
                if (found != null) throw new IllegalStateException("native anchor is ambiguous");
                found = view;
            }
            if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) pending.add(((ViewGroup) view).getChildAt(i));
        }
        if (found == null) throw new IllegalStateException("native anchor is not currently visible");
        return found;
    }
    private static Rect visibleBounds(View view) {
        Rect bounds = new Rect();
        if (!view.isAttachedToWindow() || !view.isShown() || !view.getGlobalVisibleRect(bounds) || bounds.isEmpty()) throw new IllegalStateException("native view is not visible");
        int[] rootAt = new int[2]; view.getRootView().getLocationOnScreen(rootAt); bounds.offset(rootAt[0], rootAt[1]);
        return bounds;
    }
    private static void checkForeground(Owned snapshot) throws Exception {
        if (current(snapshot.loader) != snapshot.activity || snapshot.activity.isFinishing() || snapshot.activity.isDestroyed()
                || !snapshot.activity.hasWindowFocus() || snapshot.activity.getWindow() != snapshot.window
                || snapshot.window.getDecorView() != snapshot.decor || !snapshot.decor.isAttachedToWindow()
                || snapshot.decor.getDisplay() == null || snapshot.decor.getDisplay().getDisplayId() != 0
                || !TARGET.equals(snapshot.activity.getPackageName())) throw new IllegalStateException("target window is no longer foreground on display zero");
    }
    private static Activity current(ClassLoader loader) throws Exception { return (Activity) call(loader, UTILS, "getVisibleActivity"); }
    private static void checkHost(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < 34 || !TARGET.equals(context.getPackageName()) || !TARGET.equals(Application.getProcessName())
                || !VERSION.equals(context.getPackageManager().getPackageInfo(TARGET, 0).versionName)) throw new IllegalStateException("fixture requires main TikTok 47.4.3 process on Android 14 or later");
    }
    private static String devices(AudioManager audio) {
        List<String> devices = new ArrayList<>();
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) devices.add(device.getId() + ":" + device.getType());
        Collections.sort(devices);
        List<String> media = new ArrayList<>();
        for (AudioDeviceInfo device : audio.getAudioDevicesForAttributes(new AudioAttributes.Builder().setLegacyStreamType(STREAM).build())) {
            media.add(device.getId() + ":" + device.getType());
        }
        if (media.isEmpty()) throw new IllegalStateException("native media route is unavailable");
        Collections.sort(media); return "media=" + media + " outputs=" + devices;
    }
    private static int boot(Context context) { return android.provider.Settings.Global.getInt(context.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1); }
    private static void onMain(MainWork work) throws Exception {
        CountDownLatch done = new CountDownLatch(1); Exception[] failure = new Exception[1];
        Runnable task = () -> { try { work.run(); } catch (Exception error) { failure[0] = error; } finally { done.countDown(); } };
        if (!MAIN.post(task)) throw new IllegalStateException("main-thread fixture dispatch failed");
        if (!done.await(2, TimeUnit.SECONDS)) { MAIN.removeCallbacks(task); throw new IllegalStateException("main-thread fixture deadline exceeded"); }
        if (failure[0] != null) throw failure[0];
    }
    private static AtomicFile file(Context context, String name) { return new AtomicFile(new File(context.getNoBackupFilesDir(), name)); }
    private static boolean exists(AtomicFile file) { return file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists() || new File(file.getBaseFile() + ".new").exists(); }
    private static JSONObject read(AtomicFile file) throws Exception { return new JSONObject(new String(VerifiedJournal.read(file.openRead(), LIMIT), StandardCharsets.UTF_8)); }
    private static void write(AtomicFile file, JSONObject json) throws Exception {
        FileOutputStream output = file.startWrite();
        try { VerifiedJournal.write(output, file.getBaseFile(), json.toString().getBytes(StandardCharsets.UTF_8), LIMIT, file::finishWrite); }
        catch (Exception error) { file.failWrite(output); throw error; }
    }
    private static String required(Intent intent, String key) { String value = intent.getStringExtra(key); if (value == null) throw new IllegalArgumentException("missing " + key); return value; }
    private static int integer(Intent intent, String key, int fallback) { String value = intent.getStringExtra(key); return value == null ? fallback : Integer.parseInt(value); }
    private static void validateOwner(String value) { if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")) throw new IllegalArgumentException("owner must be an explicit 8 through 64 character fixture token"); }
    private static Object call(ClassLoader loader, String type, String name) throws Exception { return method(loader.loadClass(type), name).invoke(null); }
    private static Method method(Class<?> type, String name, Class<?>... params) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) try { Method method = owner.getDeclaredMethod(name, params); method.setAccessible(true); return method; } catch (NoSuchMethodException inherited) { }
        throw new NoSuchMethodException(name);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; } catch (NoSuchFieldException inherited) { }
        throw new NoSuchFieldException(name);
    }
    private static void failure(String owner, String phase, Exception error) {
        Log.e(Probe.TAG, "edge-drag failed owner=" + owner + " phase=" + phase + " kind=" + error.getClass().getSimpleName() + " privateJournalRetained=true");
    }
    private EdgeDragProbe() { }
}
