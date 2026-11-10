package app.hushfeed.verification;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** A bounded, owned aggregate fixture. Real clock, claimNotice, overlay, button and native focus. */
final class BudgetFocusProbe {
    private static final String PREFIX = "app.morphe.extension.tiktok.";
    private static final String SETTING = "app.morphe.extension.shared.settings.Setting";
    private static final String JOURNAL = "hushfeed-budget-focus-restore.json";
    private static final int MAX_JOURNAL_BYTES = 128 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Snapshot owned;

    private static final class Choice {
        final Object setting, live, raw;
        final String key;
        final boolean present;
        Choice(Object setting, String key, Object live, Map<String, ?> persisted) {
            this.setting = setting;
            this.key = key;
            this.live = live;
            present = persisted.containsKey(key);
            raw = persisted.get(key);
        }
    }

    private static final class Snapshot {
        final ClassLoader loader;
        final Class<?> budget, changes, overlay, setting;
        final SharedPreferences preferences;
        final Map<String, Choice> choices = new LinkedHashMap<>();
        final AtomicFile journal;
        final String mode;
        final long startedAt = SystemClock.elapsedRealtime();
        BudgetStateSnapshot budgetState, changeState;
        Object target, clock;
        boolean recovered;
        RestorationRetry restoration = new RestorationRetry();
        CountDownLatch restorationFinished = new CountDownLatch(1);
        final Runnable watchdog = () -> {
            try { restore(this, "watchdog"); }
            catch (Exception failure) { failed("watchdog", failure); }
        };
        final Runnable retry = () -> {
            try { restore(this, "retry"); }
            catch (Exception failure) { failed("retry", failure); }
        };

        Snapshot(Context context, ClassLoader loader, String mode) throws Exception {
            this.loader = loader;
            this.mode = mode;
            budget = loader.loadClass(PREFIX + "wellbeing.SessionBudget");
            changes = loader.loadClass(PREFIX + "wellbeing.BudgetChanges");
            overlay = loader.loadClass(PREFIX + "wellbeing.SessionLockOverlay");
            setting = loader.loadClass(SETTING);
            Object category = field(setting, "preferences").get(null);
            preferences = (SharedPreferences) field(category.getClass(), "preferences").get(category);
            journal = new AtomicFile(new File(context.getNoBackupFilesDir(), JOURNAL));
            Class<?> settings = Class.forName(PREFIX + "settings.Settings", true, loader);
            Map<String, ?> persisted = preferences.getAll();
            for (Field member : settings.getFields()) {
                if (!member.getName().startsWith("SESSION_BUDGET_")) continue;
                Object value = member.get(null);
                String key = (String) field(setting, "key").get(value);
                Object live = field(setting, "value").get(value);
                if (!(live instanceof Integer) && !(live instanceof Boolean) && !(live instanceof String)) {
                    throw new IllegalStateException("unsupported budget setting type");
                }
                choices.put(key, new Choice(value, key, live, persisted));
            }
            if (choices.size() < 13) throw new IllegalStateException("incomplete budget settings snapshot");
        }
    }

    private BudgetFocusProbe() { }

    static boolean active() { return owned != null; }

    static void recoverOnStart(Context context, ClassLoader loader) throws Exception {
        File journal = new File(context.getNoBackupFilesDir(), JOURNAL);
        if (!journal.exists() && !new File(journal + ".bak").exists()) return;
        CountDownLatch completed = new CountDownLatch(1);
        Exception[] failure = new Exception[1];
        Snapshot[] recovering = new Snapshot[1];
        MAIN.post(() -> {
            try { recovering[0] = recover(context, loader); }
            catch (Exception error) { failure[0] = error; failed("startup_recovery", error); }
            finally { completed.countDown(); }
        });
        if (!completed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("budget recovery did not finish");
        if (failure[0] != null) throw failure[0];
        if (recovering[0] != null && (!recovering[0].restorationFinished.await(5, TimeUnit.SECONDS)
                || recovering[0].restoration.state() != RestorationRetry.State.RESTORED)) {
            throw new IllegalStateException("budget startup recovery requires attention");
        }
    }

    static void run(Context context, ClassLoader loader, String op, String mode) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("budget control requires main thread");
        if ("begin".equals(op)) begin(context, loader, mode);
        else if ("release".equals(op)) releaseButton();
        else if ("restore".equals(op)) {
            if (owned != null) {
                // A deliberate command may start another bounded cycle, preserving the original
                // in-memory snapshot. Ownership is checked again before its first write.
                if (owned.restoration.recoveryRequired()) {
                    owned.restoration = new RestorationRetry();
                    owned.restorationFinished = new CountDownLatch(1);
                }
                restore(owned, "requested");
            }
            else recover(context, loader);
        } else if (op == null || "status".equals(op)) status(loader, "status");
        else throw new IllegalArgumentException("expected begin, release, restore or status");
    }

    private static void begin(Context context, ClassLoader loader, String mode) throws Exception {
        if (!"expiry".equals(mode) && !"early".equals(mode)) throw new IllegalArgumentException("mode must be expiry or early");
        if (owned != null) throw new IllegalStateException("budget fixture already running");
        Class<?> budget = loader.loadClass(PREFIX + "wellbeing.SessionBudget");
        Class<?> hold = loader.loadClass(PREFIX + "wellbeing.SessionPlaybackHold");
        Class<?> overlay = loader.loadClass(PREFIX + "wellbeing.SessionLockOverlay");
        if (Boolean.TRUE.equals(call(budget, "isLocked")) || field(hold, "held").get(null) != null
                || field(hold, "waitingForFocus").get(null) != null
                || field(hold, "switchWanted").getBoolean(null) || field(hold, "lockWanted").getBoolean(null)
                || Boolean.TRUE.equals(call(loader.loadClass(SETTING), "isPaused"))
                || Boolean.TRUE.equals(call(loader.loadClass(PREFIX + "playback.FeedMute"), "isHoldingFocus"))
                || Boolean.TRUE.equals(call(loader.loadClass(PREFIX + "wellbeing.FeedLock"), "isOn"))
                || Boolean.TRUE.equals(call(loader.loadClass(PREFIX + "wellbeing.FinishLastVideo"), "pending"))
                || reference(overlay, "overlayReference") != null || field(overlay, "quietened").getBoolean(null)
                || field(overlay, "ticking").getBoolean(null)
                || !Boolean.TRUE.equals(call(hold, "currentPlaying")) || !Boolean.TRUE.equals(call(hold, "hasNativeFocus"))) {
            throw new IllegalStateException("foreground native playback with no other hold is required");
        }
        android.app.Activity activity = (android.app.Activity) call(loader.loadClass("app.morphe.extension.shared.Utils"), "getActivity");
        Class<?> visibility = loader.loadClass(PREFIX + "blockauthor.FeedVisibility");
        if (activity == null || !activity.hasWindowFocus()
                || !Boolean.TRUE.equals(method(visibility, "isOnFeed", android.app.Activity.class).invoke(null, activity))
                || Boolean.TRUE.equals(method(visibility, "isFeedCleared", android.app.Activity.class).invoke(null, activity))) {
            throw new IllegalStateException("native feed must be foreground");
        }
        long now = ((Number) call(budget, "now")).longValue();
        if (Math.abs(now - System.currentTimeMillis()) > 1_000) throw new IllegalStateException("real system clock is required");
        long dayEnd = ((Number) method(budget, "dayEndAfter", long.class).invoke(null, now)).longValue();
        if (dayEnd - now < 120_000) throw new IllegalStateException("budget reset boundary is too near");
        // Bound the wait. Never hold the budget lock while waiting for its writer.
        ((ExecutorService) field(budget, "WRITER").get(null)).submit(() -> null).get(2, TimeUnit.SECONDS);
        Snapshot snapshot = new Snapshot(context, loader, mode);
        if (snapshot.journal.getBaseFile().exists() || new File(snapshot.journal.getBaseFile() + ".bak").exists()
                || new File(snapshot.journal.getBaseFile() + ".new").exists()) {
            throw new IllegalStateException("restore the previous budget journal before beginning");
        }
        synchronized (field(budget, "LOCK").get(null)) {
            synchronized (field(budget, "WRITE_FENCE").get(null)) {
                synchronized (snapshot.setting) {
                    // Native progress may have reached a real budget while the writer drained.
                    if (Boolean.TRUE.equals(call(budget, "isLocked")) || field(hold, "held").get(null) != null
                            || field(hold, "waitingForFocus").get(null) != null
                            || field(hold, "switchWanted").getBoolean(null) || field(hold, "lockWanted").getBoolean(null)) {
                        throw new IllegalStateException("a real hold took ownership before snapshot");
                    }
                    // Read preferences again under the writer fence, after any just-finished save.
                    Map<String, ?> persisted = snapshot.preferences.getAll();
                    for (Map.Entry<String, Choice> each : snapshot.choices.entrySet()) {
                        Choice choice = each.getValue();
                        each.setValue(new Choice(choice.setting, choice.key,
                                field(snapshot.setting, "value").get(choice.setting), persisted));
                    }
                    snapshot.budgetState = BudgetStateSnapshot.capture(budget, "writeGeneration");
                    snapshot.changeState = BudgetStateSnapshot.capture(snapshot.changes);
                    snapshot.clock = field(budget, "clock").get(null);
                    snapshot.target = field(hold, "current").get(null);
                    writeJournal(snapshot);
                    owned = snapshot;
                    MAIN.postDelayed(snapshot.watchdog, 100_000);
                    try {
                        configure(snapshot);
                        call(budget, "resetForTests");
                        call(budget, "videosSeen");
                        field(budget, "videos").setInt(null, 1); // Seed only the aggregate threshold.
                        if (!Boolean.TRUE.equals(call(budget, "claimNotice"))) throw new IllegalStateException("real claimNotice refused");
                    } catch (Exception failure) {
                        restore(snapshot, "begin_failed");
                        throw failure;
                    }
                }
            }
        }
        call(snapshot.overlay, "sync");
        call(snapshot.overlay, "ensureRunning");
        status(loader, "began realClaimNotice=true configuredHoldSeconds=60 watchdogSeconds=100");
    }

    private static void configure(Snapshot snapshot) throws Exception {
        for (Map.Entry<String, Object> next : fixtureValues().entrySet()) {
            Choice choice = snapshot.choices.get(next.getKey());
            if (choice == null || !choice.live.getClass().isInstance(next.getValue())) throw new IllegalStateException("missing fixture setting");
            field(snapshot.setting, "value").set(choice.setting, next.getValue());
        }
    }

    private static Map<String, Object> fixtureValues() {
        Object[][] values = {
                {"session_budget_videos", 1}, {"session_budget_minutes", 0}, {"session_budget_lock_minutes", 1},
                {"session_budget_lock", false}, {"session_budget_passes_per_day", 2},
                {"session_budget_wait_to_loosen", false}, {"session_budget_pending", ""},
                {"session_budget_ramp", false}, {"session_budget_finish_video", false},
                {"session_budget_cue", false}, {"session_budget_notice_minutes", 0}, {"session_budget_state", ""}
        };
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object[] next : values) {
            out.put((String) next[0], next[1]);
        }
        return out;
    }

    private static void releaseButton() throws Exception {
        Snapshot snapshot = owned;
        if (snapshot == null || !"early".equals(snapshot.mode)) throw new IllegalStateException("no early-release fixture");
        Class<?> hold = snapshot.loader.loadClass(PREFIX + "wellbeing.SessionPlaybackHold");
        View button = (View) reference(snapshot.overlay, "releaseReference");
        int before = field(snapshot.budget, "passesUsed").getInt(null);
        if (field(hold, "current").get(null) != snapshot.target || field(hold, "held").get(null) != snapshot.target
                || button == null || !button.isAttachedToWindow() || !button.isShown() || !button.isEnabled()
                || !Boolean.TRUE.equals(call(snapshot.budget, "isLocked")) || !button.performClick()) {
            throw new IllegalStateException("owned native hold release button unavailable");
        }
        if (Boolean.TRUE.equals(call(snapshot.budget, "isLocked"))
                || field(snapshot.budget, "passesUsed").getInt(null) != before + 1
                || reference(snapshot.overlay, "overlayReference") != null) {
            throw new IllegalStateException("real release action did not open the hold");
        }
        status(snapshot.loader, "released actualButton=true passIncremented=true");
    }

    private static void restore(Snapshot snapshot, String reason) throws Exception {
        if (owned != snapshot) return;
        MAIN.removeCallbacks(snapshot.watchdog);
        MAIN.removeCallbacks(snapshot.retry);
        RestorationRetry.State outcome = snapshot.restoration.attempt(new RestorationRetry.Work() {
            @Override public boolean owns() throws Exception { return ownsRestoration(snapshot); }
            @Override public void restore() throws Exception { restoreOnce(snapshot); }
        });
        if (outcome == RestorationRetry.State.RESTORED) {
            owned = null;
            snapshot.restorationFinished.countDown();
            Log.i(Probe.TAG, "budget-focus restored reason=" + reason + " attempts=" + snapshot.restoration.attempts()
                    + " persistedExact=true liveExact=true memoryExact=" + !snapshot.recovered + " aggregateMemoryExact=true"
                    + " privateIdentityRestored=" + !snapshot.recovered + " overlayClear=true writerFenced=true");
            // Indicator failures cannot invalidate an already-verified restoration or lose its snapshot.
            try {
                call(snapshot.loader.loadClass(PREFIX + "wellbeing.HoldRamp"), "sync");
                call(snapshot.loader.loadClass(PREFIX + "wellbeing.BudgetCue"), "sync");
            } catch (Exception indicatorFailure) { failed("restored_indicators", indicatorFailure); }
            return;
        }
        if (outcome == RestorationRetry.State.RETRY
                && !MAIN.postDelayed(snapshot.retry, RestorationRetry.DELAY_MS)) {
            snapshot.restoration.schedulingFailed();
        }
        if (snapshot.restoration.recoveryRequired()) snapshot.restorationFinished.countDown();
        Exception failure = snapshot.restoration.failure();
        Log.e(Probe.TAG, "budget-focus restoration_failed reason=" + reason + " attempt=" + snapshot.restoration.attempts()
                + " state=" + snapshot.restoration.state() + " recoveryRequired=" + snapshot.restoration.recoveryRequired()
                + " privateJournalRetained=true kind=" + (failure == null ? "none" : failure.getClass().getSimpleName()));
    }

    private static boolean ownsRestoration(Snapshot snapshot) throws Exception {
        if (owned != snapshot) return false;
        Class<?> hold = snapshot.loader.loadClass(PREFIX + "wellbeing.SessionPlaybackHold");
        if (!RestorationRetry.noCompetingHold(field(hold, "switchWanted").getBoolean(null),
                field(hold, "lockWanted").getBoolean(null),
                Boolean.TRUE.equals(call(snapshot.loader.loadClass(PREFIX + "wellbeing.FeedLock"), "isOn")),
                field(snapshot.budget, "lockedToday").getBoolean(null))) return false;
        if (!snapshot.recovered) {
            Object held = field(hold, "held").get(null), waiting = field(hold, "waitingForFocus").get(null);
            if (field(hold, "current").get(null) != snapshot.target
                    || (held != null && held != snapshot.target) || (waiting != null && waiting != snapshot.target)
                    || field(snapshot.budget, "clock").get(null) != snapshot.clock) return false;
        }
        Map<String, Object> fixture = fixtureValues();
        Map<String, ?> persisted = snapshot.preferences.getAll();
        for (Choice choice : snapshot.choices.values()) {
            if ("session_budget_state".equals(choice.key)) continue; // The real writer owns this evolving record.
            Object live = field(snapshot.setting, "value").get(choice.setting);
            if ((!Objects.equals(live, choice.live) && !Objects.equals(live, fixture.get(choice.key)))
                    || persisted.containsKey(choice.key) != choice.present || !Objects.equals(persisted.get(choice.key), choice.raw)) {
                return false;
            }
        }
        return true;
    }

    private static void restoreOnce(Snapshot snapshot) throws Exception {
        // Remove only our budget panel through its own focus-aware detach path, before restoring
        // counts. Never force native focus or playback if the external fixture still owns it.
        synchronized (field(snapshot.budget, "LOCK").get(null)) {
            if (!ownsRestoration(snapshot)) throw new RestorationRetry.OwnershipLost();
            field(snapshot.budget, "lockUntilMs").setLong(null, 0);
            field(snapshot.budget, "lockedToday").setBoolean(null, false);
        }
        call(snapshot.overlay, "sync");
        synchronized (field(snapshot.budget, "LOCK").get(null)) {
            synchronized (field(snapshot.budget, "WRITE_FENCE").get(null)) {
                synchronized (snapshot.setting) {
                    RestorationRetry.runOwned(new RestorationRetry.Work() {
                        @Override public boolean owns() throws Exception { return ownsRestoration(snapshot); }
                        @Override public void restore() throws Exception {
                            Handler overlayMain = (Handler) field(snapshot.overlay, "MAIN").get(null);
                            overlayMain.removeCallbacks((Runnable) field(snapshot.overlay, "TICK").get(null));
                            field(snapshot.overlay, "ticking").setBoolean(null, false);
                            // Only invalidate fixture writes after the final owner check. The fence
                            // has already waited for any entered write belonging to this generation.
                            Field generation = field(snapshot.budget, "writeGeneration");
                            generation.setLong(null, generation.getLong(null) + 1);
                            SharedPreferences.Editor editor = snapshot.preferences.edit();
                            for (Choice choice : snapshot.choices.values()) {
                                field(snapshot.setting, "value").set(choice.setting, choice.live);
                                if (!choice.present) editor.remove(choice.key);
                                else if (choice.raw instanceof Boolean) editor.putBoolean(choice.key, (Boolean) choice.raw);
                                else if (choice.raw instanceof String) editor.putString(choice.key, (String) choice.raw);
                                else throw new IllegalStateException("unsupported original budget preference type");
                            }
                            if (!editor.commit()) throw new IllegalStateException("budget preference restoration commit failed");
                            snapshot.budgetState.restore();
                            snapshot.changeState.restore();
                            Map<String, ?> persisted = snapshot.preferences.getAll();
                            boolean rawMatches = true, liveMatches = true;
                            for (Choice choice : snapshot.choices.values()) {
                                rawMatches &= persisted.containsKey(choice.key) == choice.present
                                        && Objects.equals(persisted.get(choice.key), choice.raw);
                                liveMatches &= Objects.equals(field(snapshot.setting, "value").get(choice.setting), choice.live);
                            }
                            boolean memoryMatches = snapshot.budgetState.matches() && snapshot.changeState.matches();
                            if (!snapshot.recovered) memoryMatches &= snapshot.clock == field(snapshot.budget, "clock").get(null);
                            if (!rawMatches || !liveMatches || !memoryMatches) {
                                throw new IllegalStateException("budget restoration verification failed; private journal retained");
                            }
                        }
                    });
                }
            }
        }
        boolean overlayClear = reference(snapshot.overlay, "overlayReference") == null
                && !field(snapshot.overlay, "quietened").getBoolean(null)
                && !field(snapshot.overlay, "focusGranted").getBoolean(null);
        if (!overlayClear) {
            throw new IllegalStateException("budget restoration verification failed; private journal retained");
        }
        snapshot.journal.delete();
        if (snapshot.journal.getBaseFile().exists() || new File(snapshot.journal.getBaseFile() + ".bak").exists()) {
            throw new IllegalStateException("restored budget journal could not be removed");
        }
    }

    private static Snapshot recover(Context context, ClassLoader loader) throws Exception {
        Snapshot snapshot = new Snapshot(context, loader, "recovery");
        File file = snapshot.journal.getBaseFile();
        if (!file.exists() && !new File(file + ".bak").exists()) return null;
        byte[] bytes = VerifiedJournal.read(snapshot.journal.openRead(), MAX_JOURNAL_BYTES);
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        if (root.getInt("schema") != 1) throw new IllegalStateException("unsupported budget journal");
        JSONObject choices = root.getJSONObject("choices");
        if (choices.length() != snapshot.choices.size()) throw new IllegalStateException("incomplete choice journal");
        for (Map.Entry<String, Choice> each : snapshot.choices.entrySet()) {
            Choice current = each.getValue();
            JSONObject saved = choices.getJSONObject(each.getKey());
            Object live = typed(saved.get("live"), current.live);
            Map<String, Object> raw = new LinkedHashMap<>();
            if (saved.getBoolean("present")) {
                Object value = saved.get("raw");
                if (!(value instanceof String) && !(value instanceof Boolean)) throw new IllegalArgumentException("wrong raw journal type");
                raw.put(current.key, value);
            }
            each.setValue(new Choice(current.setting, current.key, live, raw));
        }
        snapshot.budgetState = BudgetStateSnapshot.fromJournal(snapshot.budget,
                scalarMap(root.getJSONObject("budget")), "writeGeneration", "lastCountedId");
        snapshot.changeState = BudgetStateSnapshot.fromJournal(snapshot.changes, scalarMap(root.getJSONObject("changes")));
        snapshot.recovered = true;
        owned = snapshot;
        restore(snapshot, "process_recovery");
        return snapshot;
    }

    private static void writeJournal(Snapshot snapshot) throws Exception {
        JSONObject choices = new JSONObject();
        for (Choice choice : snapshot.choices.values()) {
            if (choice.present && !(choice.raw instanceof String) && !(choice.raw instanceof Boolean)) {
                throw new IllegalStateException("unsupported original budget preference type");
            }
            JSONObject value = new JSONObject().put("live", choice.live).put("present", choice.present);
            if (choice.present) value.put("raw", choice.raw);
            choices.put(choice.key, value);
        }
        JSONObject root = new JSONObject().put("schema", 1).put("choices", choices)
                .put("budget", json(snapshot.budgetState.journalValues("lastCountedId")))
                .put("changes", json(snapshot.changeState.journalValues()));
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_JOURNAL_BYTES) throw new IllegalStateException("budget snapshot is oversized");
        FileOutputStream output = snapshot.journal.startWrite();
        try {
            VerifiedJournal.write(output, snapshot.journal.getBaseFile(), bytes, MAX_JOURNAL_BYTES,
                    snapshot.journal::finishWrite);
        } catch (Exception failure) {
            // No fixture mutation has happened yet. This is a fresh journal, so an unverified
            // base or .new is ours to discard rather than treating it as a committed rollback.
            snapshot.journal.failWrite(output);
            snapshot.journal.delete();
            throw failure;
        }
    }

    private static Object typed(Object value, Object expected) {
        if (expected instanceof Integer && (value instanceof Integer || value instanceof Long)) {
            long number = ((Number) value).longValue();
            if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) return (int) number;
        }
        if (expected.getClass().isInstance(value)) return value;
        throw new IllegalArgumentException("wrong live choice journal type");
    }

    private static JSONObject json(Map<String, Object> values) throws Exception {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Object> value : values.entrySet()) out.put(value.getKey(),
                value.getValue() == null ? JSONObject.NULL : value.getValue());
        return out;
    }

    private static Map<String, Object> scalarMap(JSONObject json) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = json.keys(); keys.hasNext();) {
            String key = keys.next();
            out.put(key, json.isNull(key) ? null : json.get(key));
        }
        return out;
    }

    private static void status(ClassLoader loader, String reason) throws Exception {
        Class<?> budget = loader.loadClass(PREFIX + "wellbeing.SessionBudget");
        Class<?> overlay = loader.loadClass(PREFIX + "wellbeing.SessionLockOverlay");
        JSONObject out = new JSONObject().put("scenario", "budget_overlay_seeded_count").put("reason", reason)
                .put("owned", owned != null).put("mode", owned == null ? "none" : owned.mode)
                .put("restoreAttempts", owned == null ? 0 : owned.restoration.attempts())
                .put("restoreState", owned == null ? "NONE" : owned.restoration.state().name())
                .put("recoveryRequired", owned != null && owned.restoration.recoveryRequired())
                .put("elapsedMs", owned == null ? 0 : SystemClock.elapsedRealtime() - owned.startedAt)
                .put("budgetLocked", call(budget, "isLocked")).put("remainingMs", call(budget, "lockRemainingMs"))
                .put("overlayVisible", reference(overlay, "overlayReference") instanceof View
                        && ((View) reference(overlay, "overlayReference")).isShown())
                .put("quietened", field(overlay, "quietened").getBoolean(null))
                .put("focusGranted", field(overlay, "focusGranted").getBoolean(null));
        Log.i(Probe.TAG, "budget-focus " + out);
        SessionHoldProbe.run(loader, "status", 1);
    }

    private static Object reference(Class<?> type, String name) throws Exception {
        return ((WeakReference<?>) field(type, name).get(null)).get();
    }
    private static Object call(Class<?> type, String name) throws Exception { return method(type, name).invoke(null); }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException inherited) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void failed(String op, Exception failure) {
        Log.e(Probe.TAG, "budget-focus failed op=" + op + " kind=" + failure.getClass().getSimpleName()
                + " privateJournalRetained=true");
    }
}
