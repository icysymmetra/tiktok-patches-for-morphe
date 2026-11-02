package app.morphe.extension.tiktok.inbox;

import java.lang.reflect.Proxy;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Runs the actual shared reader without Android, native methods or native type stubs. */
public final class InboxRenderedModelsContract {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Object header = new Opaque(), body = new Opaque(), footer = new Opaque(), extra = new Opaque();
        Adapter adapter = new Adapter();
        adapter.LLLII.LJIIJJI = Arrays.asList(header, body, footer);
        adapter.LLJJJJLIIL = null; adapter.LLJJL = null;
        adapter.LLLIIL = Object.class;
        InboxRenderedModels.Result cached = read(adapter);
        require(cached.mode == InboxRenderedModels.Mode.DIFF_CACHE, "enabled cache mode");
        identities(cached.models, header, body, footer);
        identities(cached.differCached, header, body, footer);
        require(cached.stubCount == 0 && cached.loadingPhase.equals("UNREAD") && !cached.loadingRow,
                "unused fallback is not inspected in cache mode");
        rejects(() -> cached.models.clear(), UnsupportedOperationException.class, "returned models are read-only");

        adapter = new Adapter();
        adapter.LLLIIIIL.LJ = false;
        adapter.LLLII.LJIIJJI = Collections.singletonList(extra);
        adapter.LLJJJJLIIL.LLL = Arrays.asList(header, body);
        adapter.LLJJL.add(new Stub(Collections.singletonList(footer)));
        adapter.LLJJL.add(new Stub(Collections.singletonList(extra)));
        InboxRenderedModels.Result fallback = read(adapter);
        require(fallback.mode == InboxRenderedModels.Mode.STUB_AGGREGATE, "disabled cache selects native stubs");
        identities(fallback.models, header, body, footer, extra);
        identities(fallback.differCached, extra);
        require(fallback.stubCount == 3 && fallback.loadingPhase.equals("DISABLED") && !fallback.loadingRow,
                "ordered primary plus two extra stubs without a loading tail");
        identities(adapter.LLJJJJLIIL.LLL, header, body);
        require(adapter.LLJJL.size() == 2, "native extra list is unchanged");
        adapter.LLLIIIIL = null;
        identities(read(adapter).models, header, body, footer, extra);
        adapter.LLLII.LJIIJJI = Collections.emptyList();
        identities(read(adapter).models, header, body, footer, extra);

        for (Phase phase : Phase.values()) {
            for (int kind = 0; kind < 3; kind++) {
                adapter = new Adapter();
                adapter.LLLIIIIL.LJ = false;
                adapter.LLJJJJLIIL.LLL = Arrays.asList(header, body);
                adapter.LLJJL.add(new Stub(Collections.singletonList(footer)));
                adapter.LLLIIL = Object.class;
                State state = kind == 0 ? new State(phase) : kind == 1 ? new RefreshState(phase) : new EmptyState(phase);
                Loading row = new Loading(new Model(state));
                adapter.LLJLLL = row;
                InboxRenderedModels.Result result = read(adapter);
                boolean expected = phase == Phase.Refresh ? kind == 1 : kind != 2;
                require(result.loadingRow == expected && result.loadingPhase.equals(phase.name()), "phase/state loading selection");
                if (expected) identities(result.models, header, body, footer, row);
                else identities(result.models, header, body, footer);
                require(adapter.LLJLLL.LLJJIJIIJIL.LIZIZ == state, "loading state is unchanged");
            }
        }
        malformedContracts();
        boundedContracts();
        loaderContract();
        System.out.println("[inbox-rendered-models] " + checks + " field-only host contracts passed; native rendering not exercised");
    }

    private static void malformedContracts() throws Exception {
        for (int kind = 0; kind < 7; kind++) {
            Adapter adapter = new Adapter(); adapter.LLLIIIIL.LJ = false;
            if (kind == 0) adapter.LLJJJJLIIL = null;
            if (kind == 1) adapter.LLJJL = null;
            if (kind == 2) adapter.LLJJL.add(new Object());
            if (kind == 3) adapter.LLJJJJLIIL.LLL = null;
            if (kind >= 4) adapter.LLLIIL = Object.class;
            if (kind == 5) adapter.LLJLLL = new Loading(null);
            if (kind == 6) adapter.LLJLLL = new Loading(new Model(null));
            rejects(() -> read(adapter), IllegalStateException.class, "incomplete aggregate refuses explicitly");
        }
        Adapter adapter = new Adapter(); adapter.LLLII.LJIIJJI = null;
        rejects(() -> read(adapter), IllegalStateException.class, "null differ cache is not an empty list");
        rejects(() -> InboxRenderedModels.read(new Object(), schema()), IllegalStateException.class, "unowned adapter type refused");
        rejects(() -> new InboxRenderedModels.Schema(WrongAdapter.class, Diff.class, Config.class, Stub.class,
                Loading.class, Model.class, State.class, Phase.class, RefreshState.class, EmptyState.class),
                IllegalStateException.class, "wrong declared field type refused");
        rejects(() -> new InboxRenderedModels.Schema(InheritedAdapter.class, Diff.class, Config.class, Stub.class,
                Loading.class, Model.class, State.class, Phase.class, RefreshState.class, EmptyState.class),
                NoSuchFieldException.class, "declaring owner is exact instead of a recursive name search");
    }

    private static void boundedContracts() throws Exception {
        Adapter adapter = new Adapter();
        adapter.LLLII.LJIIJJI = new AbstractList<Object>() {
            @Override public int size() { return 4097; }
            @Override public Object get(int index) { throw new AssertionError("oversized cache must not be traversed"); }
        };
        Adapter oversizedCache = adapter;
        rejects(() -> read(oversizedCache), IllegalStateException.class, "cache identity traversal is bounded");
        adapter = new Adapter(); adapter.LLLIIIIL.LJ = false;
        adapter.LLJJJJLIIL.LLL = Collections.nCopies(4096, null);
        adapter.LLJJL.add(new Stub(Collections.singletonList(null)));
        Adapter combined = adapter;
        rejects(() -> read(combined), IllegalStateException.class, "combined stub rows share one bound");
        adapter = new Adapter(); adapter.LLLIIIIL.LJ = false;
        adapter.LLJJJJLIIL.LLL = Collections.nCopies(4096, null);
        identitiesCount(read(adapter).models, 4096);
        adapter.LLLIIL = Object.class;
        adapter.LLJLLL = new Loading(new Model(new RefreshState(Phase.Refresh)));
        Adapter loading = adapter;
        rejects(() -> read(loading), IllegalStateException.class, "loading tail participates in row bound");
        adapter = new Adapter(); adapter.LLLIIIIL.LJ = false;
        adapter.LLJJL = new AbstractList<Object>() {
            @Override public int size() { return 65; }
            @Override public Object get(int index) { throw new AssertionError("oversized stubs must not be traversed"); }
        };
        Adapter extras = adapter;
        rejects(() -> read(extras), IllegalStateException.class, "extra stub traversal is bounded independently");
        adapter = new Adapter();
        adapter.LLLII.LJIIJJI = new AbstractList<Object>() {
            int reads;
            @Override public int size() { return ++reads == 1 ? 1 : 2; }
            @Override public Object get(int index) { return null; }
        };
        Adapter changed = adapter;
        rejects(() -> read(changed), IllegalStateException.class, "size changes cannot produce a successful partial snapshot");
    }

    private static void loaderContract() throws Exception {
        List<String> requested = new ArrayList<>();
        ClassLoader nativeLoader = new ClassLoader(InboxRenderedModelsContract.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("com.bytedance.ies.powerlist.PowerAdapter")) { requested.add(name); throw new ClassNotFoundException(name); }
                return super.loadClass(name, resolve);
            }
        };
        Object captured = Proxy.newProxyInstance(nativeLoader, new Class<?>[]{Marker.class}, (proxy, method, args) -> null);
        rejects(() -> InboxRenderedModels.read(captured), ClassNotFoundException.class, "unavailable native schema refuses");
        require(requested.equals(Collections.singletonList("com.bytedance.ies.powerlist.PowerAdapter")),
                "production reader resolves exact native owners through the captured adapter loader");
    }

    public interface Marker {}
    public static class Adapter {
        public Diff LLLII = new Diff();
        public Config LLLIIIIL = new Config();
        public Stub LLJJJJLIIL = new Stub(Collections.emptyList());
        public List<Object> LLJJL = new ArrayList<>();
        public Class<?> LLLIIL;
        public Loading LLJLLL;
        public int getItemCount() { throw new AssertionError("no native getters"); }
        public List<?> LLLLJ() { throw new AssertionError("no native aggregate getter"); }
    }
    public static final class WrongAdapter { public Object LLLII; }
    public static final class InheritedAdapter extends Adapter {}
    public static final class Diff { public List<?> LJIIJJI = Collections.emptyList(); }
    public static final class Config { public boolean LJ = true; }
    public static final class Stub { public List<?> LLL; Stub(List<?> rows) { LLL = rows; } }
    public enum Phase { Refresh, Next, Prev }
    public static class State { public Phase LIZIZ; State(Phase phase) { LIZIZ = phase; } }
    public static final class RefreshState extends State { RefreshState(Phase phase) { super(phase); } }
    public static final class EmptyState extends State { EmptyState(Phase phase) { super(phase); } }
    public static final class Model { public State LIZIZ; Model(State state) { LIZIZ = state; } }
    public static final class Loading { public Model LLJJIJIIJIL; Loading(Model model) { LLJJIJIIJIL = model; } }
    private static final class Opaque {
        @Override public boolean equals(Object other) { throw new AssertionError("no model equality"); }
        @Override public int hashCode() { throw new AssertionError("no model hash"); }
        @Override public String toString() { throw new AssertionError("no model text"); }
    }
    private static InboxRenderedModels.Schema schema() throws ReflectiveOperationException {
        return new InboxRenderedModels.Schema(Adapter.class, Diff.class, Config.class, Stub.class, Loading.class,
                Model.class, State.class, Phase.class, RefreshState.class, EmptyState.class);
    }
    private static InboxRenderedModels.Result read(Adapter adapter) throws ReflectiveOperationException {
        return InboxRenderedModels.read(adapter, schema());
    }
    private static void identities(List<?> actual, Object... expected) {
        require(actual.size() == expected.length, "model count");
        for (int i = 0; i < expected.length; i++) require(actual.get(i) == expected[i], "ordered model identity");
    }
    private static void identitiesCount(List<?> actual, int count) { require(actual.size() == count, "inclusive model bound"); }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action, Class<? extends Throwable> type, String label) throws Exception {
        try { action.run(); } catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError(label, failure);
            checks++; return;
        }
        throw new AssertionError(label);
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
