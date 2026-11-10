/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public final class FeatureGateCatalog {
    private static final String TAG = "MorpheFeatureGateLab";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static volatile List<Entry> staticEntries;
    /** The build {@link #staticEntries} was read for. */
    private static volatile String staticEntriesBuild;
    private static volatile Snapshot cachedSnapshot;

    /**
     * AB key to the type the catalogue declares for it, and nothing else.
     *
     * <p>The runtime's AB fallback asks the catalogue one question: does this key's declared type
     * match the type the saved rule carries. Answering it through {@link #cachedSnapshot} meant a
     * phone with one AB rule saved held all 16,052 entries for the life of the process, on a
     * launch where the Lab screen was never opened. This holds one string pair per AB key, and
     * the type strings are shared rather than one per line.
     */
    private static volatile Map<String, String> cachedAbTypes;

    private FeatureGateCatalog() {
    }

    public interface Callback {
        void onLoaded(Snapshot snapshot);

        void onError(String message);
    }

    public interface AbTypesCallback {
        void onLoaded(Map<String, String> abTypes);

        void onError(String message);
    }

    /**
     * Loads {@link #cachedAbTypes} without building a snapshot.
     *
     * <p>It reads the same two places {@link #merge} does for AB entries, in the same order: the
     * static catalogue first, then TikTok's own AB store for keys the catalogue does not carry,
     * so a key answers with the same type either way.
     */
    public static void loadAbTypesAsync(AbTypesCallback callback) {
        Map<String, String> cached = cachedAbTypes;
        if (cached != null) {
            MAIN.post(() -> callback.onLoaded(cached));
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                Map<String, String> types = readAbTypes();
                cachedAbTypes = types;
                MAIN.post(() -> callback.onLoaded(types));
                Log.i(TAG, "catalog ab_types=" + types.size());
            } catch (Throwable throwable) {
                String message = throwable.getClass().getSimpleName()
                        + ": " + String.valueOf(throwable.getMessage());
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    public static Map<String, String> cachedAbTypes() {
        return cachedAbTypes;
    }

    public static void loadAsync(boolean refreshCurrentCache, Callback callback) {
        Snapshot cached = cachedSnapshot;
        if (!refreshCurrentCache && cached != null) {
            MAIN.post(() -> callback.onLoaded(cached));
            return;
        }
        EXECUTOR.execute(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                Map<String, Map<String, Object>> current = readCurrentKevaMaps();
                long currentReadyAt = System.currentTimeMillis();
                String build = catalogBuild();
                List<Entry> base = build.equals(staticEntriesBuild) ? staticEntries : null;
                if (base == null) {
                    Snapshot currentOnly = merge(Collections.emptyList(), current, false);
                    MAIN.post(() -> callback.onLoaded(currentOnly));
                    synchronized (LOCK) {
                        base = build.equals(staticEntriesBuild) ? staticEntries : null;
                        if (base == null) {
                            base = Collections.unmodifiableList(readStaticCatalog(build));
                            staticEntries = base;
                            staticEntriesBuild = build;
                        }
                    }
                }
                long catalogReadyAt = System.currentTimeMillis();
                Snapshot snapshot = merge(base, current, true);
                cachedSnapshot = snapshot;
                cachedAbTypes = abTypesOf(snapshot);
                MAIN.post(() -> callback.onLoaded(snapshot));
                Log.i(TAG, "catalog current_ms=" + (currentReadyAt - startedAt)
                        + " static_ms=" + (catalogReadyAt - currentReadyAt)
                        + " merge_ms=" + (System.currentTimeMillis() - catalogReadyAt)
                        + " loaded=" + snapshot.loadedCount
                        + " total=" + snapshot.entries.size());
            } catch (Throwable throwable) {
                String message = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
                MAIN.post(() -> callback.onError(message));
            }
        });
    }

    public static Snapshot cachedSnapshot() {
        return cachedSnapshot;
    }

    static void awaitForTests() throws Exception {
        // A pre-push run shares the machine with other builds. On 2026-09-30 a catalog refresh
        // there ran past 5 s and failed FeatureGateLabActionsTest, which passes when it has room.
        EXECUTOR.submit(() -> { }).get(30, TimeUnit.SECONDS);
    }

    static void resetForTests() {
        staticEntries = null;
        staticEntriesBuild = null;
        cachedSnapshot = null;
        cachedAbTypes = null;
    }

    /** The TikTok builds a catalog was generated from, oldest first. */
    public static List<String> catalogBuilds() {
        return java.util.Arrays.asList(GeneratedGateCatalogBuilds.BUILDS.clone());
    }

    /** Whether [build] has a catalog generated from it, and so one checked against it. */
    public static boolean hasCatalogFor(String build) {
        return build != null && catalogBuilds().contains(build);
    }

    public static String newestCatalogBuild() {
        String[] builds = GeneratedGateCatalogBuilds.BUILDS;
        return builds[builds.length - 1];
    }

    /**
     * The build whose catalog the Lab shows: the one running, or, on a build no catalog was
     * generated from, the newest one there is. Only the first is checked against the build.
     */
    public static String catalogBuild() {
        String running = app.morphe.extension.shared.BuildNames.runningBuild();
        return hasCatalogFor(running) ? running : newestCatalogBuild();
    }

    /**
     * The rules a build change can leave as they are. When both builds have a catalog, those
     * whose gate both catalogs carry under the same manager, key and type with an identical row,
     * so the same default, provenance and (for a SettingsManager read) model class and default.
     *
     * <p>The bundle declares only the newest TikTok build, so the build a user moves from has
     * usually just been dropped from the catalog. Its rules are then held to the new build's
     * catalog alone: one stays when that catalog carries its gate under the same manager, key
     * and type. A SettingsManager read whose model class R8 renamed is the exception and is
     * turned off, since its field names are that build's own and nothing says the old build's
     * names meant the same fields. None stay when the new build has no catalog. Reads only the
     * tables the rules' managers live in, off the caller's thread or not: it runs once per
     * change of build, when the Lab store is first opened on the new one.
     */
    static java.util.Set<String> compatibleRuleIds(String from, String to,
            java.util.Collection<FeatureGateLabStore.Rule> rules) throws Exception {
        java.util.Set<String> result = new HashSet<>();
        if (rules.isEmpty() || !hasCatalogFor(to)) return result;
        boolean compared = hasCatalogFor(from);
        Set<String> identities = new HashSet<>();
        for (FeatureGateLabStore.Rule rule : rules) identities.add(rule.manager + "\n" + rule.key);
        Map<String, String> after = catalogRows(to, identities);
        Map<String, String> before = !compared ? null : from.equals(to) ? after : catalogRows(from, identities);
        for (FeatureGateLabStore.Rule rule : rules) {
            String identity = rule.manager + "\n" + rule.key;
            String row = after.get(identity);
            if (row == null) continue;
            if (compared ? !row.equals(before.get(identity)) : !carriesWithoutCatalog(rule.manager, row)) continue;
            if (!FeatureGateLabStore.supportsOverride(rule.manager, rule.type)) continue;
            String type = FeatureGateLabStore.MANAGER_SETTINGS_MANAGER.equals(rule.manager)
                    ? "OBJECT" : row.split("\\t", -1)[2];
            if (FeatureGateLabStore.normalizeType(type).equals(FeatureGateLabStore.normalizeType(rule.type))) {
                result.add(rule.id);
            }
        }
        return result;
    }

    /**
     * Whether a rule from a build with no catalog can stand on [row], the new build's: always for
     * a scalar gate, which is its key and type, and for a SettingsManager read only when its model
     * class keeps its real name (the row's second field), and with it its fields'.
     */
    private static boolean carriesWithoutCatalog(String manager, String row) {
        if (!FeatureGateLabStore.MANAGER_SETTINGS_MANAGER.equals(manager)) return true;
        String[] fields = row.split("\\t", -1);
        return fields.length > 1 && !isR8Named(fields[1]);
    }

    /** A class name R8 gave, X.0RSc, or an array of one; TikTok's renamed classes all sit in X. */
    static boolean isR8Named(String className) {
        int start = 0;
        while (start < className.length() && className.charAt(start) == '[') start++;
        if (start > 0 && start < className.length() && className.charAt(start) == 'L') start++;
        return className.startsWith("X.", start);
    }

    /** [build]'s catalog row for each of [identities] (manager, newline, key) it carries. */
    private static Map<String, String> catalogRows(String build, Set<String> identities) throws Exception {
        boolean scalar = false, player = false, ve = false, settings = false;
        for (String identity : identities) {
            String manager = identity.substring(0, identity.indexOf('\n'));
            if (FeatureGateLabStore.MANAGER_PLAYER_CONFIG.equals(manager)) player = true;
            else if (FeatureGateLabStore.MANAGER_VE_CONFIG.equals(manager)) ve = true;
            else if (FeatureGateLabStore.MANAGER_SETTINGS_MANAGER.equals(manager)) settings = true;
            else scalar = true;
        }
        Map<String, String> rows = new HashMap<>();
        RowReader typed = line -> {
            String[] fields = line.split("\\t", -1);
            if (fields.length == 10 && identities.contains(fields[1] + "\n" + fields[0])) {
                rows.put(fields[1] + "\n" + fields[0], line);
            }
        };
        if (scalar) Table.abLive().forEachRow(build, typed);
        if (player) Table.player().forEachRow(build, typed);
        if (ve) Table.ve().forEachRow(build, typed);
        if (settings) {
            String prefix = FeatureGateLabStore.MANAGER_SETTINGS_MANAGER + "\n";
            Table.settings().forEachRow(build, line -> {
                String identity = prefix + line.substring(0, Math.max(0, line.indexOf('\t')));
                if (identities.contains(identity)) rows.put(identity, line);
            });
        }
        return rows;
    }

    /**
     * One generated table: each build's row count, the rows every build has, and each build's
     * own, in {@link GeneratedGateCatalogBuilds#BUILDS} order. Built only when read, since
     * naming a generated class runs its initialiser and that builds every one of its strings.
     */
    private static final class Table {
        final int[] counts;
        final String[] shared;
        final String[][] own;

        Table(int[] counts, String[] shared, String[][] own) {
            this.counts = counts;
            this.shared = shared;
            this.own = own;
        }

        static Table abLive() {
            return new Table(GeneratedFeatureGateCatalog.ENTRY_COUNTS,
                    GeneratedFeatureGateCatalog.SHARED_GZIP_BASE64, GeneratedFeatureGateCatalog.OWN_GZIP_BASE64);
        }

        static Table player() {
            return new Table(GeneratedPlayerFeatureGateCatalog.ENTRY_COUNTS,
                    GeneratedPlayerFeatureGateCatalog.SHARED_GZIP_BASE64,
                    GeneratedPlayerFeatureGateCatalog.OWN_GZIP_BASE64);
        }

        static Table ve() {
            return new Table(GeneratedVeFeatureGateCatalog.ENTRY_COUNTS,
                    GeneratedVeFeatureGateCatalog.SHARED_GZIP_BASE64, GeneratedVeFeatureGateCatalog.OWN_GZIP_BASE64);
        }

        static Table settings() {
            return new Table(GeneratedSettingsManagerCatalog.ENTRY_COUNTS,
                    GeneratedSettingsManagerCatalog.SHARED_GZIP_BASE64,
                    GeneratedSettingsManagerCatalog.OWN_GZIP_BASE64);
        }

        static Table settingsSites() {
            return new Table(GeneratedSettingsManagerSites.ENTRY_COUNTS,
                    GeneratedSettingsManagerSites.SHARED_GZIP_BASE64, GeneratedSettingsManagerSites.OWN_GZIP_BASE64);
        }

        int count(String build) {
            return counts[index(build)];
        }

        /** Each of [build]'s rows, shared ones first. */
        void forEachRow(String build, RowReader reader) throws Exception {
            int index = index(build);
            for (String[] chunks : new String[][]{shared, own[index]}) {
                if (chunks.length == 0) continue;
                try (BufferedReader lines = openCatalog(chunks)) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        reader.row(line);
                    }
                }
            }
        }

        private static int index(String build) {
            int index = java.util.Arrays.asList(GeneratedGateCatalogBuilds.BUILDS).indexOf(build);
            if (index < 0) throw new IllegalArgumentException("No catalog for TikTok " + build);
            return index;
        }
    }

    private interface RowReader {
        void row(String line) throws Exception;
    }

    /** The AB half of a loaded snapshot, so both paths answer a key the same way. */
    private static Map<String, String> abTypesOf(Snapshot snapshot) {
        String prefix = FeatureGateLabStore.MANAGER_ABMOCK + "\n";
        Map<String, String> result = new HashMap<>();
        Map<String, String> shared = new HashMap<>();
        for (Map.Entry<String, Entry> item : snapshot.byIdentity.entrySet()) {
            if (!item.getKey().startsWith(prefix)) {
                continue;
            }
            result.put(item.getValue().key, share(shared, item.getValue().type));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, String> readAbTypes() throws Exception {
        Map<String, String> result = new HashMap<>();
        Map<String, String> shared = new HashMap<>();
        String build = catalogBuild();
        appendStaticAbTypes(result, shared, Table.abLive(), build);
        appendStaticAbTypes(result, shared, Table.player(), build);
        appendStaticAbTypes(result, shared, Table.ve(), build);

        // A key TikTok's AB store carries that the catalogue does not becomes an entry of its own
        // in a snapshot, typed from the value that is there. The static type wins where both have
        // the key, which is what merge does by removing the key as it walks the static entries.
        for (Map.Entry<String, Object> item
                : readKevaMapSafely("libra_config_center_repo").entrySet()) {
            if (result.containsKey(item.getKey())) {
                continue;
            }
            String dynamicType = runtimeType(item.getValue());
            if (!FeatureGateLabStore.supportsOverride(
                    FeatureGateLabStore.MANAGER_ABMOCK, dynamicType)) {
                continue;
            }
            result.put(item.getKey(), share(shared, dynamicType));
        }
        return Collections.unmodifiableMap(result);
    }

    private static void appendStaticAbTypes(
            Map<String, String> result,
            Map<String, String> shared,
            Table table,
            String build
    ) throws Exception {
        table.forEachRow(build, line -> {
            String[] fields = line.split("\t", -1);
            if (fields.length != 10) {
                return;
            }
            // The same three conditions an Entry would have to meet to reach byIdentity:
            // the AB manager, actionable, and a type the Lab can override.
            if (!FeatureGateLabStore.MANAGER_ABMOCK.equals(fields[1])
                    || !"1".equals(fields[3])
                    || !FeatureGateLabStore.supportsOverride(fields[1], fields[2])) {
                return;
            }
            result.put(fields[0], share(shared, fields[2]));
        });
    }

    /** One instance per distinct type, rather than one per line of the catalogue. */
    private static String share(Map<String, String> shared, String type) {
        String existing = shared.get(type);
        if (existing != null) {
            return existing;
        }
        shared.put(type, type);
        return type;
    }

    private static BufferedReader openCatalog(String[] chunks) throws Exception {
        StringBuilder encoded = new StringBuilder();
        for (String chunk : chunks) {
            encoded.append(chunk);
        }
        byte[] compressed = Base64.decode(encoded.toString(), Base64.DEFAULT);
        return new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(compressed)),
                StandardCharsets.UTF_8));
    }

    /** [build]'s catalog, one of {@link #catalogBuilds}. */
    static List<Entry> readStaticCatalog(String build) throws Exception {
        Table abLive = Table.abLive();
        Table player = Table.player();
        Table ve = Table.ve();
        Table settings = Table.settings();
        List<Entry> result = new ArrayList<>(abLive.count(build) + player.count(build)
                + ve.count(build) + settings.count(build));
        appendStaticCatalog(result, abLive, build);
        appendStaticCatalog(result, player, build);
        appendStaticCatalog(result, ve, build);
        appendStructuredStaticCatalog(result, settings, build);
        return result;
    }

    private static void appendStaticCatalog(List<Entry> result, Table table, String build) throws Exception {
        table.forEachRow(build, line -> {
            String[] fields = line.split("\t", -1);
            if (fields.length != 10) {
                return;
            }
            Entry entry = new Entry(
                    fields[0],
                    titleFor(fields[0]),
                    fields[1],
                    fields[2],
                    "1".equals(fields[3]),
                    "1".equals(fields[4]),
                    jsonValues(fields[5]),
                    jsonValues(fields[6]),
                    jsonValues(fields[7]),
                    fields[8],
                    fields[9],
                    false,
                    null,
                    null
            );
            if (entry.userVisible()) {
                result.add(entry);
            }
        });
    }

    /**
     * SettingsManager reads: key, model class, default and provenance, with the call site from a
     * table of its own, since the site is R8's name for it on one build and the rest is not.
     */
    private static void appendStructuredStaticCatalog(
            List<Entry> result,
            Table table,
            String build
    ) throws Exception {
        Map<String, String> sites = new HashMap<>();
        Table.settingsSites().forEachRow(build, line -> {
            String[] fields = line.split("\t", -1);
            if (fields.length == 3) {
                sites.put(fields[0], fields[1] + " in " + fields[2]);
            }
        });
        table.forEachRow(build, line -> {
            String[] fields = line.split("\t", -1);
            if (fields.length != 4) {
                return;
            }
            if (!StructuredConfigController.hasActionableFields(fields[1])) {
                return;
            }
            String site = sites.get(fields[0]);
            result.add(new Entry(
                    fields[0],
                    titleFor(fields[0]),
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER,
                    "OBJECT",
                    true,
                    "generated_registry".equals(fields[3]),
                    Collections.singletonList(fields[2]),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    "",
                    site == null ? fields[3] : site,
                    false,
                    null,
                    null,
                    fields[1]
            ));
        });
    }

    private static Map<String, Map<String, Object>> readCurrentKevaMaps() {
        Map<String, Map<String, Object>> result = new HashMap<>();
        result.put(
                FeatureGateLabStore.MANAGER_ABMOCK,
                readKevaMapSafely("libra_config_center_repo")
        );
        result.put(
                FeatureGateLabStore.MANAGER_PLAYER_CONFIG,
                FeatureGateLabRuntime.playerObservedValues()
        );
        result.put(
                FeatureGateLabStore.MANAGER_LIVE,
                readKevaMapSafely("live_settings_repo")
        );
        result.put(
                FeatureGateLabStore.MANAGER_VE_CONFIG,
                readVeConfigMapSafely()
        );
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readVeConfigMapSafely() {
        try {
            Class<?> centerClass = Class.forName("com.ss.android.vesdk.VEConfigCenter");
            Method getInstance = centerClass.getMethod("getInstance");
            Object center = getInstance.invoke(null);
            Method getConfigs = centerClass.getMethod("getConfigs");
            Object configs = getConfigs.invoke(center);
            if (!(configs instanceof Map)) {
                return Collections.emptyMap();
            }
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) configs).entrySet()) {
                if (item.getKey() == null || item.getValue() == null) {
                    continue;
                }
                Method getValue = item.getValue().getClass().getMethod("getValue");
                result.put(String.valueOf(item.getKey()), getValue.invoke(item.getValue()));
            }
            return result;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readKevaMapSafely(String repoName) {
        try {
            Class<?> kevaClass = Class.forName("com.bytedance.keva.Keva");
            Method getRepo = kevaClass.getMethod("getRepo", String.class);
            Object repo = getRepo.invoke(null, repoName);
            Method getAll = kevaClass.getMethod("getAll");
            Object result = getAll.invoke(repo);
            if (!(result instanceof Map)) {
                return Collections.emptyMap();
            }
            Map<String, Object> copy = new HashMap<>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) result).entrySet()) {
                if (item.getKey() != null) {
                    copy.put(String.valueOf(item.getKey()), item.getValue());
                }
            }
            return copy;
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private static Snapshot merge(
            List<Entry> base,
            Map<String, Map<String, Object>> current,
            boolean catalogComplete
    ) {
        List<Entry> result = new ArrayList<>(base.size() + 1000);
        Map<String, Entry> byIdentity = new HashMap<>();
        Map<String, Map<String, Object>> remaining = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> source : current.entrySet()) {
            remaining.put(source.getKey(), new HashMap<>(source.getValue()));
        }
        int loaded = 0;
        for (Entry entry : base) {
            Map<String, Object> sourceValues = remaining.get(entry.manager);
            boolean isLoaded = sourceValues != null && sourceValues.containsKey(entry.key);
            Object value = sourceValues == null ? null : sourceValues.remove(entry.key);
            Entry merged = entry.withCurrent(isLoaded, value);
            result.add(merged);
            byIdentity.put(merged.identity(), merged);
            if (isLoaded) {
                loaded++;
            }
        }
        for (Map.Entry<String, Map<String, Object>> source : remaining.entrySet()) {
            String manager = source.getKey();
            for (Map.Entry<String, Object> item : source.getValue().entrySet()) {
                if (FeatureGateLabStore.MANAGER_PLAYER_CONFIG.equals(manager)
                        && !FeatureGateLabRuntime.wasPlayerObserved(item.getKey())) {
                    continue;
                }
                String dynamicType = runtimeType(item.getValue());
                boolean actionable = FeatureGateLabStore.supportsOverride(manager, dynamicType);
                if (!actionable) {
                    continue;
                }
                Entry dynamic = new Entry(
                        item.getKey(),
                        titleFor(item.getKey()),
                        manager,
                        dynamicType,
                        true,
                        false,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        "",
                        "unproven",
                        true,
                        valueText(item.getValue()),
                        dynamicType
                );
                result.add(dynamic);
                byIdentity.put(dynamic.identity(), dynamic);
                loaded++;
            }
        }
        loaded += mergeStructuredObservations(result, byIdentity);
        Collections.sort(result, (left, right) ->
                left.title.toLowerCase(Locale.ROOT).compareTo(right.title.toLowerCase(Locale.ROOT)));
        return new Snapshot(
                Collections.unmodifiableList(result),
                Collections.unmodifiableMap(byIdentity),
                loaded,
                System.currentTimeMillis(),
                catalogComplete
        );
    }

    private static int mergeStructuredObservations(
            List<Entry> result,
            Map<String, Entry> byIdentity
    ) {
        JSONArray observations = SettingsManagerObservationRecorder.exportJson();
        Set<String> loadedKeys = new HashSet<>();
        int loaded = 0;
        for (int index = 0; index < observations.length(); index++) {
            org.json.JSONObject observation = observations.optJSONObject(index);
            if (observation == null || !observation.optBoolean("actionable", false)) {
                continue;
            }
            String key = observation.optString("key", "");
            String requestedClass = observation.optString("requested_class", "");
            if (key.isEmpty() || requestedClass.isEmpty() || !loadedKeys.add(key)) {
                continue;
            }
            String manager = observation.optString(
                    "manager",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER
            );
            String identity = manager + "\n" + key;
            Object current = observation.opt("current_value");
            Object defaultValue = observation.opt("default_value");
            List<String> defaults = new ArrayList<>();
            if (defaultValue != null && defaultValue != org.json.JSONObject.NULL) {
                defaults.add(String.valueOf(defaultValue));
            }
            Entry existing = byIdentity.get(identity);
            Entry merged = new Entry(
                    key,
                    existing == null ? titleFor(key) : existing.title,
                    manager,
                    "OBJECT",
                    true,
                    existing != null && existing.registered,
                    defaults.isEmpty() && existing != null ? existing.defaults : defaults,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    "",
                    observation.optString("caller", "runtime getter")
                            + " / "
                            + observation.optString("settings_manager_method_descriptor", ""),
                    true,
                    current == null || current == org.json.JSONObject.NULL
                            ? "null"
                            : String.valueOf(current),
                    "OBJECT",
                    requestedClass
            );
            if (existing == null) {
                result.add(merged);
            } else {
                int existingIndex = result.indexOf(existing);
                if (existingIndex >= 0) {
                    result.set(existingIndex, merged);
                }
            }
            byIdentity.put(identity, merged);
            loaded++;
        }
        return loaded;
    }

    private static List<String> jsonValues(String text) throws Exception {
        List<String> result = new ArrayList<>();
        JSONArray array = new JSONArray(text);
        for (int index = 0; index < array.length(); index++) {
            Object value = array.opt(index);
            String formatted = valueText(value);
            if (!result.contains(formatted)) {
                result.add(formatted);
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static String titleFor(String key) {
        if (key == null || key.isEmpty()) {
            return "Unnamed gate";
        }
        String[] words = key.replace('-', '_').replace('.', '_').split("_+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }
        return result.length() == 0 ? key : result.toString();
    }

    private static String valueText(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.length() <= 2000 ? text : text.substring(0, 1997) + "...";
    }

    private static String runtimeType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) return "INT";
        if (value instanceof Long) return "LONG";
        if (value instanceof Float) return "FLOAT";
        if (value instanceof Double) return "DOUBLE";
        if (value instanceof String) return "STRING";
        return value.getClass().getName();
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final Map<String, Entry> byIdentity;
        public final int loadedCount;
        public final long loadedAtMs;
        public final boolean catalogComplete;

        Snapshot(
                List<Entry> entries,
                Map<String, Entry> byIdentity,
                int loadedCount,
                long loadedAtMs,
                boolean catalogComplete
        ) {
            this.entries = entries;
            this.byIdentity = byIdentity;
            this.loadedCount = loadedCount;
            this.loadedAtMs = loadedAtMs;
            this.catalogComplete = catalogComplete;
        }
    }

    public static final class Entry {
        public final String key;
        public final String title;
        public final String searchText;
        final String normalizedKey;
        final String normalizedTitle;
        final String[] searchTokens;
        public final String manager;
        public final String type;
        public final boolean actionable;
        public final boolean registered;
        public final List<String> defaults;
        public final List<String> historical;
        public final List<String> researched;
        public final String description;
        public final String proof;
        public final boolean loaded;
        public final String currentValue;
        public final String currentType;
        public final String requestedClass;

        Entry(
                String key,
                String title,
                String manager,
                String type,
                boolean actionable,
                boolean registered,
                List<String> defaults,
                List<String> historical,
                List<String> researched,
                String description,
                String proof,
                boolean loaded,
                String currentValue,
                String currentType
        ) {
            this(
                    key, title, manager, type, actionable, registered, defaults, historical, researched,
                    description, proof, loaded, currentValue, currentType, null
            );
        }

        Entry(
                String key,
                String title,
                String manager,
                String type,
                boolean actionable,
                boolean registered,
                List<String> defaults,
                List<String> historical,
                List<String> researched,
                String description,
                String proof,
                boolean loaded,
                String currentValue,
                String currentType,
                String requestedClass
        ) {
            this.key = key;
            this.title = title;
            this.normalizedKey = normalizeSearchText(key);
            this.normalizedTitle = normalizeSearchText(title);
            this.searchText = normalizedKey + "\n" + normalizedTitle;
            String searchable = normalizedKey + " " + normalizedTitle;
            this.searchTokens = searchable.trim().isEmpty()
                    ? new String[0]
                    : searchable.trim().split(" ");
            this.manager = manager;
            this.type = type;
            this.actionable = actionable;
            this.registered = registered;
            this.defaults = defaults;
            this.historical = historical;
            this.researched = researched;
            this.description = description;
            this.proof = proof;
            this.loaded = loaded;
            this.currentValue = currentValue;
            this.currentType = currentType;
            this.requestedClass = requestedClass;
        }

        Entry withCurrent(boolean loaded, Object value) {
            return new Entry(
                    key, title, manager, type, actionable, registered, defaults, historical, researched,
                    description, proof, loaded, loaded ? valueText(value) : null, loaded ? runtimeType(value) : null,
                    requestedClass
            );
        }

        public String identity() {
            return manager + "\n" + key;
        }

        public boolean unknown() {
            return "UNKNOWN".equals(type) || (!registered && !actionable);
        }

        public boolean userVisible() {
            return actionable && FeatureGateLabStore.supportsOverride(manager, type);
        }

        /**
         * The same label the source tab carries, so the row badge matches the tab it sorts
         * into. It was three different names (tab, badge, detail page); now it is two: the
         * badge is the tab label and the detail page spells it out.
         */
        public String shortSourceName() {
            if (FeatureGateLabStore.MANAGER_PIA_ACTIVITY_CENTER.equals(manager)) return "Activity";
            if (FeatureGateLabStore.MANAGER_PLAYER_CONFIG.equals(manager)) return "Player";
            if (FeatureGateLabStore.MANAGER_LIVE.equals(manager)) return "LIVE";
            if (FeatureGateLabStore.MANAGER_VE_CONFIG.equals(manager)) return "Media";
            if (FeatureGateLabStore.MANAGER_SETTINGS_MANAGER.equals(manager)) return "Config";
            if (FeatureGateLabStore.MANAGER_ABMOCK.equals(manager)) return "App AB";
            return manager;
        }

    }

    static String normalizeSearchText(String text) {
        return app.morphe.extension.tiktok.settings.SearchText.normalize(text);
    }

}
