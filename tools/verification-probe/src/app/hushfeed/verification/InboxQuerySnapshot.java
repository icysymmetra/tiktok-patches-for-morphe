package app.hushfeed.verification;

import app.morphe.extension.tiktok.inbox.InboxRenderedModels;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Observes captured Inbox state without initializing native owners or reading model content. */
final class InboxQuerySnapshot {
    private static final String PREFIX = "app.morphe.extension.tiktok.inbox.";
    private static final int MAX_OWNERS = 32, MAX_REQUESTS = 256, MAX_ROWS = 4096;

    private InboxQuerySnapshot() {}

    static String read(ClassLoader loader) {
        JSONObject result = new JSONObject();
        JSONArray errors = new JSONArray();
        try {
            result.put("schema", 1);
            result.put("identityHashLimit", MAX_ROWS);
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new SchemaFailure("main thread required");
            }
            Class<?> ownersType = Class.forName(PREFIX + "InboxQueryOwners", false, loader);
            Class<?> surfacesType = Class.forName(PREFIX + "InboxQueryNative", false, loader);
            Class<?> requestsType = Class.forName(PREFIX + "InboxNativeRequests", false, loader);
            Object lock = required(requestsType, null, "LOCK");
            synchronized (lock) {
                Collection<?> owners = collection(required(ownersType, null, "OWNERS"));
                Map<?, ?> surfaces = map(required(surfacesType, null, "SURFACES"));
                Collection<?> requests = collection(required(requestsType, null, "REQUESTS"));
                result.put("ownerCount", owners.size());
                result.put("surfaceCount", surfaces.size());
                result.put("requestOwnerCount", requests.size());
                JSONArray ownerRows = new JSONArray();
                int index = 0;
                for (Object owner : owners) {
                    if (index == MAX_OWNERS) { errors.put("owners: bound exceeded"); break; }
                    JSONObject row = new JSONObject();
                    ownerRows.put(row);
                    row.put("slot", index++);
                    try { owner(row, owner, surfaces); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        row.put("error", diagnostic(failure));
                        errors.put("owner[" + (index - 1) + "]: " + diagnostic(failure));
                    }
                }
                result.put("owners", ownerRows);
                if (surfaces.size() != owners.size()) errors.put("owners/surfaces: count mismatch");
                JSONArray requestRows = new JSONArray();
                index = 0;
                for (Object request : requests) {
                    if (index == MAX_REQUESTS) { errors.put("requests: bound exceeded"); break; }
                    JSONObject row = new JSONObject();
                    requestRows.put(row);
                    row.put("slot", index++);
                    try {
                        Object target = weak(request, "target");
                        row.put("targetAlive", target != null);
                        row.put("targetClass", type(target));
                        row.put("targetIdentity", identity(target));
                        row.put("entering", integer(request, "entering"));
                        row.put("unreadable", bool(request, "unreadable"));
                        Collection<?> jobs = collection(required(request, "jobs"));
                        if (jobs.size() > MAX_REQUESTS) throw new SchemaFailure("job bound exceeded");
                        int alive = 0;
                        for (Object reference : jobs) {
                            if (!(reference instanceof WeakReference)) throw new SchemaFailure("job is not a weak reference");
                            if (((WeakReference<?>) reference).get() != null) alive++;
                        }
                        row.put("jobWeakReferences", jobs.size());
                        row.put("jobAlive", alive);
                        row.put("jobCollected", jobs.size() - alive);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        row.put("error", diagnostic(failure));
                        errors.put("request[" + (index - 1) + "]: " + diagnostic(failure));
                    }
                }
                result.put("requests", requestRows);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            errors.put(diagnostic(failure));
        } catch (org.json.JSONException failure) {
            return "{\"schema\":1,\"ok\":false,\"errors\":[\"JSON encoding failed\"]}";
        }
        try {
            result.put("ok", errors.length() == 0);
            result.put("errors", errors);
        } catch (org.json.JSONException failure) {
            return "{\"schema\":1,\"ok\":false,\"errors\":[\"JSON encoding failed\"]}";
        }
        return result.toString();
    }

    private static void owner(JSONObject row, Object owner, Map<?, ?> surfaces)
            throws ReflectiveOperationException, org.json.JSONException {
        Object listObject = live(owner, "list"), state = live(owner, "state"), adapter = live(owner, "adapter");
        Object fragment = live(owner, "fragment"), stub = live(owner, "stub"), container = live(owner, "container");
        if (!(listObject instanceof View)) throw new SchemaFailure("list is not a View");
        View list = (View) listObject;
        Object surface = surfaces.get(owner);
        if (surface == null) throw new SchemaFailure("surface missing");
        Object query = required(owner, "query");
        Object root = required(fragment, "mView");
        if (!(root instanceof View)) throw new SchemaFailure("fragment view missing");
        Object lazy = required(stub, "LLJL");
        if (!"X.02ns".equals(lazy.getClass().getName())) throw new SchemaFailure("native state cache schema changed");
        Object cachedState = required(lazy, "LLJJIJI");
        boolean closed = bool(owner, "closed"), attached = list.isAttachedToWindow();
        boolean fragmentList = required(fragment, "LLJJJ") == list;
        boolean fragmentContainer = required(fragment, "LLJJJIL") == container;
        boolean listAdapter = required(list, "mAdapter") == adapter;
        boolean nativeStub = required(adapter, "LLJJJJLIIL") == stub;
        boolean nativeState = bool(adapter, "LLJJLIIIJLLLLLLLZ") && cachedState == state;
        boolean stubAdapter = required(stub, "LLJJIJIIJIL") == adapter;
        boolean inRoot = inside(list, (View) root);
        row.put("closed", closed);
        row.put("attached", attached);
        row.put("shown", list.isShown());
        row.put("listClass", list.getClass().getName());
        row.put("listIdentity", identity(list));
        row.put("stateIdentity", identity(state));
        row.put("adapterIdentity", identity(adapter));
        row.put("fragmentListMatches", fragmentList);
        row.put("fragmentContainerMatches", fragmentContainer);
        row.put("listAdapterMatches", listAdapter);
        row.put("adapterStubMatches", nativeStub);
        row.put("cachedStateMatches", nativeState);
        row.put("stubAdapterMatches", stubAdapter);
        row.put("insideFragmentRoot", inRoot);
        row.put("currentOwnership", !closed && attached && fragmentList && fragmentContainer
                && listAdapter && nativeStub && nativeState && stubAdapter && inRoot);
        Object phase = required(query, "phase"), refusal = required(query, "refusal");
        if (!(phase instanceof Enum) || !(refusal instanceof Enum)) throw new SchemaFailure("query enum schema changed");
        row.put("phase", ((Enum<?>) phase).name());
        row.put("refusal", ((Enum<?>) refusal).name());
        row.put("generation", number(query, "generation"));
        row.put("inFlight", integer(query, "inFlight"));
        row.put("hasBasis", bool(query, "hasBasis"));
        Object savedPosition = value(query, "savedPosition");
        row.put("savedPositionPresent", savedPosition != null);
        row.put("savedPositionClass", type(savedPosition));
        row.put("savedPositionIdentity", identity(savedPosition));
        row.put("nativeBacking", rows(required(state, "LJ")));
        row.put("queryBasis", rows(required(query, "basis")));
        row.put("queryRendered", rows(required(query, "rendered")));
        Object diff = required(adapter, "LLLII");
        InboxRenderedModels.Result displayed = InboxRenderedModels.read(adapter);
        row.put("nativeRendered", rows(displayed.models));
        row.put("differCached", rows(displayed.differCached));
        row.put("nativeListMode", displayed.mode.name());
        row.put("nativeStubCount", displayed.stubCount);
        row.put("nativeLoadingPhase", displayed.loadingPhase);
        row.put("nativeLoadingRow", displayed.loadingRow);
        row.put("nativeGeneration", integer(diff, "LJIIJ"));
        row.put("acceptedNativeGeneration", integer(surface, "nativeGeneration"));
        row.put("committed", publication(value(query, "committed")));
        row.put("latest", publication(value(surface, "latest")));
        row.put("accepted", publication(value(surface, "accepted")));
        row.put("pendingReceipts", map(required(surface, "pending")).size());
        row.put("metadataReceipts", map(required(surface, "metadata")).size());
        row.put("diffClaims", map(required(surface, "claims")).size());
        row.put("syntheticReceipts", map(required(surface, "synthetic")).size());
        row.put("scrollToStart", bool(surface, "scrollToStart"));
        row.put("needsIndices", bool(surface, "needsIndices"));
        Object controls = required(surface, "controls");
        Object bar = value(controls, "bar"), editor = value(controls, "editor");
        row.put("barPresent", bar != null);
        if (bar != null) {
            if (!(bar instanceof View) || !(editor instanceof TextView)) throw new SchemaFailure("bar/editor schema changed");
            row.put("barParentClass", type(((View) bar).getParent()));
            row.put("barShown", ((View) bar).isShown());
            row.put("editorEnabled", ((TextView) editor).isEnabled());
            row.put("editorTextLength", ((TextView) editor).length());
        } else if (editor != null) throw new SchemaFailure("editor without bar");
        Object spring = list.getParent();
        if (spring == null || required(fragment, "LLJJIJIL") != spring) throw new SchemaFailure("native spring ownership missing");
        row.put("springClass", spring.getClass().getName());
        JSONObject refresh = new JSONObject();
        for (String flag : new String[]{"LLLJIL", "LLLJL", "LLLIIL", "LLJJJIL"}) refresh.put(flag, bool(spring, flag));
        row.put("springFlags", refresh);
    }

    private static JSONObject publication(Object publication) throws ReflectiveOperationException, org.json.JSONException {
        JSONObject result = new JSONObject();
        result.put("present", publication != null);
        if (publication != null) {
            result.put("generation", number(publication, "generation"));
            result.put("models", rows(required(publication, "models")));
        }
        return result;
    }

    private static JSONObject rows(Object object) throws org.json.JSONException {
        IdentityRows rows = identityRows(object);
        return new JSONObject().put("count", rows.count).put("orderedIdentityHash", rows.hash);
    }

    static final class IdentityRows {
        final int count;
        final String hash;
        IdentityRows(int count, String hash) { this.count = count; this.hash = hash; }
    }

    static IdentityRows identityRows(Object object) {
        if (!(object instanceof List)) throw new SchemaFailure("models are not a List");
        List<?> list = (List<?>) object;
        int count = list.size();
        if (count > MAX_ROWS) throw new SchemaFailure("model identity bound exceeded");
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            update(hash, count);
            for (int i = 0; i < count; i++) update(hash, System.identityHashCode(list.get(i)));
            if (list.size() != count) throw new SchemaFailure("models changed during snapshot");
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash.digest()) { hex.append(Character.forDigit((b >>> 4) & 15, 16)); hex.append(Character.forDigit(b & 15, 16)); }
            return new IdentityRows(count, hex.toString());
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new SchemaFailure("SHA-256 unavailable");
        }
    }

    private static void update(MessageDigest hash, int value) {
        hash.update((byte) (value >>> 24)); hash.update((byte) (value >>> 16));
        hash.update((byte) (value >>> 8)); hash.update((byte) value);
    }
    private static boolean inside(View child, View root) {
        for (int depth = 0; child != null && depth < 128; depth++) {
            if (child == root) return true;
            child = child.getParent() instanceof View ? (View) child.getParent() : null;
        }
        return false;
    }
    private static Object live(Object owner, String name) throws ReflectiveOperationException {
        Object result = weak(owner, name);
        if (result == null) throw new SchemaFailure(name + " reference cleared");
        return result;
    }
    private static Object weak(Object owner, String name) throws ReflectiveOperationException {
        Object result = required(owner, name);
        if (!(result instanceof WeakReference)) throw new SchemaFailure(name + " is not a weak reference");
        return ((WeakReference<?>) result).get();
    }
    private static boolean bool(Object owner, String name) throws ReflectiveOperationException {
        Object result = required(owner, name);
        if (!(result instanceof Boolean)) throw new SchemaFailure(name + " is not boolean");
        return (Boolean) result;
    }
    private static int integer(Object owner, String name) throws ReflectiveOperationException {
        Object result = required(owner, name);
        if (!(result instanceof Integer)) throw new SchemaFailure(name + " is not int");
        return (Integer) result;
    }
    private static long number(Object owner, String name) throws ReflectiveOperationException {
        Object result = required(owner, name);
        if (!(result instanceof Long)) throw new SchemaFailure(name + " is not long");
        return (Long) result;
    }
    private static Collection<?> collection(Object result) {
        if (!(result instanceof Collection)) throw new SchemaFailure("registry is not a Collection");
        return (Collection<?>) result;
    }
    private static Map<?, ?> map(Object result) {
        if (!(result instanceof Map)) throw new SchemaFailure("registry is not a Map");
        return (Map<?, ?>) result;
    }
    private static Object required(Object owner, String name) throws ReflectiveOperationException {
        return required(owner.getClass(), owner, name);
    }
    private static Object required(Class<?> type, Object owner, String name) throws ReflectiveOperationException {
        Object result = field(type, name).get(owner);
        if (result == null) throw new SchemaFailure(name + " is null");
        return result;
    }
    private static Object value(Object owner, String name) throws ReflectiveOperationException { return field(owner.getClass(), name).get(owner); }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException missing) { /* Continue through the exact owner's parents. */ }
        }
        throw new NoSuchFieldException(name);
    }
    private static String type(Object object) { return object == null ? "none" : object.getClass().getName(); }
    private static String identity(Object object) { return object == null ? "none" : Integer.toHexString(System.identityHashCode(object)); }
    private static final class SchemaFailure extends IllegalStateException {
        SchemaFailure(String message) { super(message); }
    }
    private static String diagnostic(Throwable failure) {
        // Exception messages from the observed code could contain private content.
        if (failure instanceof SchemaFailure || failure instanceof NoSuchFieldException) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        return failure.getClass().getSimpleName();
    }
}
