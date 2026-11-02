/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Field-only 47.1.4 PowerAdapter list selection, shared with the read-only probe. */
public final class InboxRenderedModels {
    static final int MAX_ROWS = 4096;
    private static final int MAX_EXTRA_STUBS = 64;

    private InboxRenderedModels() {}

    public enum Mode { DIFF_CACHE, STUB_AGGREGATE }

    public static final class Result {
        public final Mode mode;
        public final List<?> models, differCached;
        public final int stubCount;
        public final String loadingPhase;
        public final boolean loadingRow;

        private Result(Mode mode, List<?> models, List<?> cached, int stubs, String phase, boolean loading) {
            this.mode = mode;
            this.models = Collections.unmodifiableList(models);
            differCached = Collections.unmodifiableList(cached);
            stubCount = stubs; loadingPhase = phase; loadingRow = loading;
        }
    }

    public static Result read(Object adapter) throws ReflectiveOperationException {
        if (adapter == null) throw invalid("adapter missing");
        // The probe has a different loader. Resolve all native owners from the captured adapter.
        ClassLoader loader = adapter.getClass().getClassLoader();
        return read(adapter, new Schema(type("com.bytedance.ies.powerlist.PowerAdapter", loader),
                type("X.09Np", loader), type("X.09Nn", loader),
                type("com.bytedance.ies.powerlist.PowerStub", loader), type("X.09NO", loader),
                type("X.09NQ", loader), type("X.09NY", loader), type("X.09Ne", loader),
                type("X.0H2C", loader), type("X.09Nd", loader)));
    }

    static Result read(Object adapter, Schema schema) throws ReflectiveOperationException {
        if (!schema.adapter.isInstance(adapter)) throw invalid("adapter type changed");
        Object diff = present(schema.diff.get(adapter), "differ missing");
        List<Object> cached = copy(schema.cached.get(diff));
        Object config = schema.config.get(adapter);
        if (config != null && schema.useCache.getBoolean(config)) {
            return new Result(Mode.DIFF_CACHE, cached, cached, 0, "UNREAD", false);
        }
        List<Object> aggregate = new ArrayList<>();
        append(aggregate, schema.stubModels.get(present(schema.primary.get(adapter), "primary stub missing")));
        List<?> extras = list(schema.extras.get(adapter));
        int count = extras.size();
        if (count > MAX_EXTRA_STUBS) throw invalid("extra stub bound exceeded");
        for (int i = 0; i < count; i++) {
            Object stub = extras.get(i);
            if (!schema.stub.isInstance(stub)) throw invalid("extra stub type changed");
            append(aggregate, schema.stubModels.get(stub));
        }
        if (extras.size() != count) throw invalid("extra stubs changed during read");
        String phase = "DISABLED";
        boolean loading = false;
        if (schema.loadingClass.get(adapter) != null) {
            Object row = present(schema.loading.get(adapter), "loading row missing");
            Object model = present(schema.loadingModel.get(row), "loading model missing");
            Object state = present(schema.loadingState.get(model), "loading state missing");
            Object value = present(schema.phase.get(state), "loading phase missing");
            phase = ((Enum<?>) value).name();
            if (!phase.equals("Refresh") && !phase.equals("Next") && !phase.equals("Prev")) {
                throw invalid("loading phase changed");
            }
            // Native tests the state, not the loading row, for these two subclasses.
            loading = phase.equals("Refresh") ? schema.refreshState.isInstance(state) : !schema.emptyState.isInstance(state);
            if (loading) {
                if (aggregate.size() == MAX_ROWS) throw invalid("model bound exceeded");
                aggregate.add(row);
            }
        }
        return new Result(Mode.STUB_AGGREGATE, aggregate, cached, count + 1, phase, loading);
    }

    /** Exact declaring owners and field types; package scope permits native-shaped host tests. */
    static final class Schema {
        final Class<?> adapter, stub, refreshState, emptyState;
        final Field diff, cached, config, useCache, primary, extras, stubModels, loadingClass,
                loading, loadingModel, loadingState, phase;

        Schema(Class<?> adapter, Class<?> diff, Class<?> config, Class<?> stub, Class<?> loading,
                Class<?> model, Class<?> state, Class<?> phase, Class<?> refresh, Class<?> empty)
                throws ReflectiveOperationException {
            if (!phase.isEnum() || !state.isAssignableFrom(refresh) || !state.isAssignableFrom(empty)) {
                throw invalid("loading type hierarchy changed");
            }
            this.adapter = adapter; this.stub = stub; refreshState = refresh; emptyState = empty;
            this.diff = field(adapter, "LLLII", diff, false);
            cached = field(diff, "LJIIJJI", List.class, false);
            this.config = field(adapter, "LLLIIIIL", config, false);
            useCache = field(config, "LJ", boolean.class, false);
            primary = field(adapter, "LLJJJJLIIL", stub, false);
            extras = field(adapter, "LLJJL", List.class, false);
            stubModels = field(stub, "LLL", List.class, false);
            loadingClass = field(adapter, "LLLIIL", Class.class, false);
            this.loading = field(adapter, "LLJLLL", loading, false);
            loadingModel = field(loading, "LLJJIJIIJIL", model, false);
            loadingState = field(model, "LIZIZ", state, false);
            this.phase = field(state, "LIZIZ", phase, false);
            // Validate the enum schema without reading a static value or initializing a class.
            field(phase, "Refresh", phase, true);
            field(phase, "Next", phase, true);
            field(phase, "Prev", phase, true);
        }
    }

    private static Class<?> type(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic)
            throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic) {
            throw invalid("field schema changed: " + name);
        }
        field.setAccessible(true);
        return field;
    }
    private static Object present(Object object, String failure) {
        if (object == null) throw invalid(failure);
        return object;
    }
    private static List<?> list(Object object) {
        if (!(object instanceof List)) throw invalid("models are not a List");
        return (List<?>) object;
    }
    private static List<Object> copy(Object object) {
        List<Object> result = new ArrayList<>();
        append(result, object);
        return result;
    }
    private static void append(List<Object> target, Object object) {
        List<?> source = list(object);
        int count = source.size();
        if (count > MAX_ROWS - target.size()) throw invalid("model bound exceeded");
        for (int i = 0; i < count; i++) target.add(source.get(i));
        if (source.size() != count) throw invalid("models changed during read");
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
}
