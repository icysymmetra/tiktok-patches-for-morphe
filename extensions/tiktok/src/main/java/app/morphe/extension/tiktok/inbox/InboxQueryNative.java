/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.os.Looper;
import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/** Projects only the owned ListState publication. Backing models and native diff machinery stay native. */
final class InboxQueryNative {
    private static final Map<InboxQueryOwners.Owner, Surface> SURFACES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Emission> EMITTING = new ThreadLocal<>();
    interface RenderedReader { InboxRenderedModels.Result read(Object adapter) throws ReflectiveOperationException; }
    static RenderedReader renderedReader = InboxRenderedModels::read;
    /** Set when the search threw once; it stays off, with TikTok's own list, until TikTok restarts. */
    private static volatile boolean threw;

    private InboxQueryNative() {}

    static void attach(InboxQueryOwners.Owner owner) {
        if (threw) return;
        Surface surface = new Surface(owner);
        SURFACES.put(owner, surface);
        surface.controls = new InboxQueryControls(owner);
        surface.controls.attach();
    }
    static void detach(InboxQueryOwners.Owner owner) {
        Surface surface = SURFACES.remove(owner);
        if (surface == null) return;
        if (surface.controls != null) surface.controls.detach();
        if (owner.query.phase() != LoadedInboxQuery.Phase.IDLE && surface.publisher != null) {
            // A detached view may keep its ListState for a later reattachment. Restore a fresh
            // unfiltered publication without changing backing models or replaying a callback.
            List<?> basis = backing(owner.state.get());
            if (basis != null) {
                try { surface.publisher.invoke(null, owner.state.get(), basis, false, false, null, 14); }
                catch (ReflectiveOperationException failure) {
                    Logger.printException(() -> "Could not restore the detached Inbox list", failure);
                }
            }
        }
    }
    static boolean ready(InboxQueryOwners.Owner owner) {
        Surface surface = SURFACES.get(owner);
        if (surface == null || surface.publisher == null || !owner.current()) return false;
        for (InboxQueryOwners.Owner other : InboxQueryOwners.snapshot()) {
            if (other != owner && other.current() && (other.state.get() == owner.state.get()
                    || other.adapter.get() == owner.adapter.get())) return false;
        }
        return Reflect.readField(owner.state.get(), "LJFF") instanceof Lock
                && Reflect.readField(owner.state.get(), "LJ") instanceof List
                && Reflect.readField(owner.adapter.get(), "LLLII") != null
                && Reflect.method(owner.fragment.get().getClass(), "hushLoadedInboxIndicesEnabled") != null;
    }

    static void ownershipChanged() {
        app.morphe.extension.shared.Utils.runOnMainThread(() -> {
            for (InboxQueryOwners.Owner owner : InboxQueryOwners.snapshot()) {
                try {
                    if (owner.current() && owner.query.phase() == LoadedInboxQuery.Phase.QUERY
                            && (!owner.pagingIsolated() || !ready(owner))) {
                        query(owner, "");
                        Surface surface = SURFACES.get(owner);
                        if (surface != null && surface.controls != null) surface.controls.cleared();
                    }
                } catch (Throwable failure) { failed(owner, "ownership change", failure); }
            }
        });
    }

    /**
     * Closing an owner republishes TikTok's unfiltered list if a query was showing and drops the
     * bar. Every open list closes, not just the one that threw: another Activity list would
     * otherwise keep searching into the same fault. The closing runs even if reporting throws.
     */
    static void failed(InboxQueryOwners.Owner owner, String hook, Throwable failure) {
        threw = true;
        try {
            HookStatus.threw(InboxQueryOwners.FAMILY, hook, failure);
            Logger.printException(() -> "Loaded Inbox search threw and is off until TikTok restarts", failure);
        } finally {
            List<InboxQueryOwners.Owner> owners = InboxQueryOwners.snapshot();
            if (owner != null && !owners.contains(owner)) owners.add(owner);
            // A delegate can throw on one of TikTok's list threads, and closing hands the list
            // and the bar's views back, which only the main thread may touch. threw is already
            // set, so every delegate passes TikTok's own values through until the close lands.
            app.morphe.extension.shared.Utils.runOnMainThreadNowOrLater(() -> closeAll(owners));
        }
    }

    private static void closeAll(List<InboxQueryOwners.Owner> owners) {
        for (InboxQueryOwners.Owner open : owners) {
            try { open.close(); }
            catch (Throwable cleanup) { Logger.printException(() -> "Could not hand the Inbox list back to TikTok", cleanup); }
        }
    }

    static void resetForTests() { threw = false; fault = null; }

    /** A seam for tests: lets one throw come from Hushfeed's own part of a native delegate. */
    interface Fault { void at(String hook); }
    static volatile Fault fault;

    /**
     * Ends the search after Hushfeed's own code threw inside a delegate TikTok's frames call. The
     * owner is not needed: every open list is handed back to TikTok. Reporting must never throw
     * into the native frame either, or the guard would only move the crash.
     */
    static void contain(String hook, Throwable failure) {
        try { failed(null, hook, failure); }
        catch (Throwable ignored) { /* the native frame must get its default back */ }
    }

    static void probe(String hook) {
        Fault current = fault;
        if (current != null) current.at(hook);
    }

    /** Called at ListState.LIZ entry. The other five arguments pass through untouched. */
    static List<?> publication(Object state, List<?> models, boolean first, boolean second, Runnable completion, int mask) {
        try { probe("loaded publication"); return publicationBody(state, models, first, second, completion, mask); }
        catch (Throwable failure) { contain("loaded publication", failure); return models; }
    }

    private static List<?> publicationBody(Object state, List<?> models, boolean first, boolean second, Runnable completion, int mask) {
        InboxQueryOwners.Owner owner = InboxQueryOwners.forState(state);
        if (owner == null) return models;
        Surface surface = SURFACES.get(owner);
        if (surface == null || threw
                || (!Settings.INBOX_LOADED_SEARCH.get() && owner.query.phase() == LoadedInboxQuery.Phase.IDLE)) return models;
        synchronized (InboxNativeRequests.LOCK) {
            Emission emission = EMITTING.get();
            LoadedInboxQuery.Publication publication;
            if (emission != null && emission.owner == owner && emission.publication.models == models) {
                publication = emission.publication;
                surface.synthetic.put(publication, Boolean.TRUE);
            }
            else {
                if (!Settings.INBOX_LOADED_SEARCH.get() && owner.query.phase() == LoadedInboxQuery.Phase.QUERY) {
                    owner.query.query("", null, null, null);
                }
                publication = owner.query.nativePublication(models, first, second, completion, mask);
                if (owner.query.refusal() == LoadedInboxQuery.Refusal.MATCHER_FAILED && surface.controls != null) {
                    app.morphe.extension.shared.Utils.runOnMainThread(surface.controls::failed);
                }
            }
            surface.pending.put(publication.models, publication);
            surface.latest = publication;
            // A coalesced native LiveData post can omit an old publication. Bound the receipts,
            // while keeping the latest plan and all already submitted native diff claims.
            if (surface.pending.size() > 64) {
                surface.pending.clear();
                surface.pending.put(publication.models, publication);
            }
            return publication.models;
        }
    }

    /** The native wrapper and its newly constructed metadata are observed, never rewritten. */
    static void stubPublication(Object stub, Object wrapper, Object metadata) {
        try { probe("loaded stub"); stubPublicationBody(stub, wrapper, metadata); }
        catch (Throwable failure) { contain("loaded stub", failure); }
    }

    private static void stubPublicationBody(Object stub, Object wrapper, Object metadata) {
        InboxQueryOwners.Owner owner = InboxQueryOwners.forStub(stub);
        Surface surface = owner == null ? null : SURFACES.get(owner);
        if (surface == null) return;
        synchronized (InboxNativeRequests.LOCK) {
            Object models = Reflect.readField(wrapper, "LIZ");
            LoadedInboxQuery.Publication publication = surface.pending.get(models);
            if (publication != null) surface.metadata.put(metadata, publication);
        }
    }

    /** Runs after native generation allocation, with the native aggregate passed to the differ. */
    static void submitted(Object diff, List<?> oldFiltered, List<?> old, List<?> aggregate, Object metadata) {
        try { probe("loaded diff"); submittedBody(diff, aggregate, metadata); }
        catch (Throwable failure) { contain("loaded diff", failure); }
    }

    private static void submittedBody(Object diff, List<?> aggregate, Object metadata) {
        InboxQueryOwners.Owner owner = InboxQueryOwners.forDiff(diff);
        Surface surface = owner == null ? null : SURFACES.get(owner);
        if (surface == null) return;
        synchronized (InboxNativeRequests.LOCK) {
            LoadedInboxQuery.Publication publication = surface.metadata.remove(metadata);
            if (publication == null) publication = surface.accepted;
            if (publication == null || !containsBody(aggregate, publication.models)) return;
            Object generation = Reflect.readField(diff, "LJIIJ");
            if (!(generation instanceof Integer)) return;
            surface.claims.put(metadata, new Claim(publication, aggregate, (Integer) generation));
        }
    }

    /** Runs after genuine dispatchUpdatesTo and listeners, before the original Runnable loop. */
    static void accepted(Object diff, Object metadata) {
        try { probe("loaded diff accepted"); acceptedBody(diff, metadata); }
        catch (Throwable failure) { contain("loaded diff accepted", failure); }
    }

    private static void acceptedBody(Object diff, Object metadata) {
        InboxQueryOwners.Owner owner = InboxQueryOwners.forDiff(diff);
        Surface surface = owner == null ? null : SURFACES.get(owner);
        if (surface == null) return;
        synchronized (InboxNativeRequests.LOCK) {
            Claim claim = surface.claims.remove(metadata);
            if (claim == null || claim.nativeGeneration < surface.nativeGeneration || !owner.current()) return;
            LoadedInboxQuery.Commit commit = owner.query.committed(claim.publication, claim.publication.models, claim.aggregate);
            if (!commit.accepted && surface.accepted == claim.publication) {
                owner.query.recomposed(claim.publication.generation, claim.aggregate);
            } else if (!commit.accepted) return;
            surface.accepted = claim.publication;
            surface.nativeGeneration = claim.nativeGeneration;
            surface.pending.remove(claim.publication.models);
            Iterator<Claim> claims = surface.claims.values().iterator();
            while (claims.hasNext()) if (claims.next().nativeGeneration < claim.nativeGeneration) claims.remove();
            Iterator<LoadedInboxQuery.Publication> metadataEntries = surface.metadata.values().iterator();
            while (metadataEntries.hasNext()) if (metadataEntries.next().generation <= claim.publication.generation) metadataEntries.remove();
            if (commit.restorePosition != null) call(Reflect.invoke(owner.list.get(), "getLayoutManager"),
                    "onRestoreInstanceState", commit.restorePosition);
            if (surface.scrollToStart && owner.query.phase() == LoadedInboxQuery.Phase.QUERY) {
                surface.scrollToStart = false;
                call(owner.list.get(), "scrollToPosition", 0);
            }
            boolean synthetic = surface.synthetic.remove(claim.publication) != null;
            if (synthetic || (surface.needsIndices && owner.query.settled())) {
                surface.needsIndices = false;
                postIndices(owner, owner.query.committedGeneration());
            }
        }
    }

    /**
     * Both the list and native visible pair belong to the accepted aggregate, not a queued diff.
     * Hushfeed decides inside the guard what the native helper should receive; the helper call
     * itself runs outside it, so a throw from TikTok's own replaced call keeps its identity.
     */
    static void indexed(Object closure, Object helper, List<?> original, Object pair) {
        List<?> models;
        try { probe("loaded indices"); models = indexedModels(closure, helper, original, pair); }
        catch (Throwable failure) { contain("loaded indices", failure); models = original; }
        if (models != null) call(helper, "LJFF", models, pair);
    }

    /** The list to hand TikTok's helper now, or null when the callback is withheld or posted. */
    private static List<?> indexedModels(Object closure, Object helper, List<?> original, Object pair) {
        Object fragment = Reflect.readField(closure, "l0");
        InboxQueryOwners.Owner owner = InboxQueryOwners.forFragment(fragment);
        Surface surface = owner == null ? null : SURFACES.get(owner);
        // This bridge is reached only after the original invoke$21 enable gate and ZX call.
        // Remember that actual helper instead of initializing one for a synthetic query diff.
        if (surface != null && helper != null) surface.indices = new WeakReference<>(helper);
        if (owner == null || owner.query.phase() == LoadedInboxQuery.Phase.IDLE) return original;
        if (!owner.query.settled()) {
            if (surface != null) surface.needsIndices = true;
            return null;
        }
        Object first = Reflect.invoke(pair, "getFirst"), last = Reflect.invoke(pair, "getSecond");
        if (first instanceof Integer && last instanceof Integer) {
            LoadedInboxQuery.Alignment alignment = owner.query.callback(owner.query.committedGeneration(), (Integer) first, (Integer) last);
            if (alignment != null) return alignment.models;
        }
        // A layout pass can temporarily have no valid visible range. Deliver the real callback
        // after layout with fresh native positions; don't send mismatched list/index data.
        postIndices(owner, owner.query.committedGeneration());
        return null;
    }

    static List<?> indexedList(Object fragment, List<?> original) {
        try { probe("loaded list"); return indexedListBody(fragment, original); }
        catch (Throwable failure) { contain("loaded list", failure); return original; }
    }

    private static List<?> indexedListBody(Object fragment, List<?> original) {
        InboxQueryOwners.Owner owner = InboxQueryOwners.forFragment(fragment);
        return owner != null && owner.query.phase() != LoadedInboxQuery.Phase.IDLE
                && owner.query.committedGeneration() != 0 ? owner.query.rendered() : original;
    }

    static boolean click(Object holder) {
        try { probe("loaded click"); return clickBody(holder); }
        catch (Throwable failure) { contain("loaded click", failure); return true; }
    }

    private static boolean clickBody(Object holder) {
        Object attached = Reflect.invoke(holder, "getAttachView$powerlist_release");
        InboxQueryOwners.Owner owner = attached instanceof View ? InboxQueryOwners.forList((View) attached) : null;
        if (owner == null || !owner.current() || owner.query.phase() == LoadedInboxQuery.Phase.IDLE) return true;
        return owner.query.containsCurrentModel(Reflect.invoke(holder, "getItem"));
    }

    static boolean visibleIndices(Object fragment, boolean loadingRow) {
        try { probe("loaded visible indices"); return visibleIndicesBody(fragment, loadingRow); }
        catch (Throwable failure) { contain("loaded visible indices", failure); return true; }
    }

    private static boolean visibleIndicesBody(Object fragment, boolean loadingRow) {
        InboxQueryOwners.Owner owner = InboxQueryOwners.forFragment(fragment);
        if (owner == null || owner.query.phase() == LoadedInboxQuery.Phase.IDLE || !loadingRow) return true;
        Object layout = Reflect.invoke(owner.list.get(), "getLayoutManager");
        Object last = Reflect.invoke(layout, "findLastVisibleItemPosition");
        return last instanceof Integer && (Integer) last >= 0 && (Integer) last < owner.query.rendered().size();
    }

    static boolean refresh(Object spring, boolean starting) {
        if (!starting) return true; // Native completion/settling must always be able to stop it.
        try { probe("loaded refresh"); return refreshBody(spring); }
        catch (Throwable failure) { contain("loaded refresh", failure); return true; }
    }

    private static boolean refreshBody(Object spring) {
        synchronized (InboxNativeRequests.LOCK) {
            for (InboxQueryOwners.Owner owner : InboxQueryOwners.snapshot()) {
                View list = owner.list.get();
                if (owner.current() && list != null && list.getParent() == spring
                        && Reflect.invoke(owner.fragment.get(), "bY") == spring
                        && owner.query.phase() != LoadedInboxQuery.Phase.IDLE) return false;
            }
            return true;
        }
    }

    static boolean query(InboxQueryOwners.Owner owner, String value) {
        return query(owner, value, InboxQueryText.MATCHER);
    }

    static boolean query(InboxQueryOwners.Owner owner, String value, LoadedInboxQuery.Matcher matcher) {
        if (threw || Looper.myLooper() != Looper.getMainLooper() || !owner.current()) return false;
        Surface surface = SURFACES.get(owner);
        if (surface == null) return false;
        synchronized (InboxNativeRequests.LOCK) {
            boolean clearing = value == null || value.trim().isEmpty();
            if (!clearing && (!ready(owner) || !Settings.INBOX_LOADED_SEARCH.get()
                    || !InboxQueryOwners.requestsIdle(owner))) return false;
            if (!owner.query.settled() && owner.query.phase() == LoadedInboxQuery.Phase.IDLE && !seed(surface)) return false;
            Object layout = Reflect.invoke(owner.list.get(), "getLayoutManager");
            Object position = Reflect.invoke(layout, "onSaveInstanceState");
            LoadedInboxQuery.Publication publication = owner.query.query(value, matcher, position, null);
            if (publication == null) return clearing && owner.query.phase() == LoadedInboxQuery.Phase.IDLE;
            surface.scrollToStart = !clearing;
            try {
                EMITTING.set(new Emission(owner, publication));
                surface.publisher.invoke(null, owner.state.get(), publication.models,
                        publication.firstFlag, publication.secondFlag, publication.completion, publication.mask);
                return true;
            } catch (ReflectiveOperationException failure) {
                Logger.printException(() -> "Could not publish the loaded Inbox query", failure);
                return false;
            } finally { EMITTING.remove(); }
        }
    }

    private static boolean seed(Surface surface) {
        InboxQueryOwners.Owner owner = surface.owner;
        List<?> basis = backing(owner.state.get(), InboxRenderedModels.MAX_ROWS);
        if (basis == null) return false;
        List<?> aggregate;
        try { aggregate = renderedReader.read(owner.adapter.get()).models; }
        catch (ReflectiveOperationException | IllegalStateException | LinkageError unsupported) {
            return false; // Refuse unsupported native schemas before creating any publication.
        }
        if (!containsBody(aggregate, basis)) return false;
        LoadedInboxQuery.Publication publication = owner.query.nativePublication(basis, false, false, null, 14);
        if (!owner.query.committed(publication, publication.models, aggregate).accepted) return false;
        surface.latest = surface.accepted = publication;
        return true;
    }

    private static List<?> backing(Object state) {
        return backing(state, Integer.MAX_VALUE);
    }

    private static List<?> backing(Object state, int maximum) {
        Object lock = Reflect.readField(state, "LJFF"), backing = Reflect.readField(state, "LJ");
        if (!(lock instanceof Lock) || !(backing instanceof List)) return null;
        ((Lock) lock).lock();
        try { return ((List<?>) backing).size() > maximum ? null : new ArrayList<>((List<?>) backing); }
        finally { ((Lock) lock).unlock(); }
    }

    static boolean containsBody(List<?> aggregate, List<?> body) {
        if (aggregate == null || body == null || aggregate.size() < body.size()) return false;
        if (body.isEmpty()) return true;
        for (int offset = 0; offset <= aggregate.size() - body.size(); offset++) {
            int i = 0;
            while (i < body.size() && aggregate.get(offset + i) == body.get(i)) i++;
            if (i == body.size()) return true;
        }
        return false;
    }

    /**
     * The aligned callback runs from the list's queue on TikTok's main thread, outside any native
     * frame, so a throw from it, TikTok's helper included, ends the search like a bar hook's.
     */
    private static void postIndices(InboxQueryOwners.Owner owner, long generation) {
        View list = owner.list.get();
        if (list == null) return;
        list.post(() -> {
            try { alignIndices(owner, list, generation); }
            catch (Throwable failure) { failed(owner, "visible rows", failure); }
        });
    }

    private static void alignIndices(InboxQueryOwners.Owner owner, View list, long generation) {
        if (threw || !owner.current() || owner.query.committedGeneration() != generation) return;
        Surface surface = SURFACES.get(owner);
        Object helper = surface == null ? null : surface.indices.get();
        if (helper == null || !Boolean.TRUE.equals(Reflect.invoke(owner.fragment.get(), "hushLoadedInboxIndicesEnabled"))) return;
        Object layout = Reflect.invoke(list, "getLayoutManager");
        Object first = Reflect.invoke(layout, "findFirstCompletelyVisibleItemPosition");
        Object last = Reflect.invoke(layout, "findLastCompletelyVisibleItemPosition");
        if (!(first instanceof Integer) || !(last instanceof Integer)) return;
        LoadedInboxQuery.Alignment alignment = owner.query.callback(generation, (Integer) first, (Integer) last);
        if (alignment == null) return;
        try {
            Class<?> pair = Class.forName("kotlin.Pair", false, list.getClass().getClassLoader());
            Object positions = pair.getConstructor(Object.class, Object.class).newInstance(first, last);
            call(helper, "LJFF", alignment.models, positions);
        } catch (ReflectiveOperationException failure) {
            Logger.printException(() -> "Could not align loaded Inbox positions", failure);
        }
    }

    static Object call(Object target, String name, Object... arguments) {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != arguments.length) continue;
            try { method.setAccessible(true); return method.invoke(target, arguments); }
            catch (IllegalArgumentException ignored) { /* Try the correctly typed overload. */ }
            catch (InvocationTargetException failure) {
                // This replaces an existing native call. Preserve its original failure rather
                // than treating a thrown callback as a missing reflection member.
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IllegalStateException("Native Inbox operation failed", cause);
            }
            catch (ReflectiveOperationException failure) {
                Logger.printException(() -> "Could not call the native Inbox list operation", failure);
                return null;
            }
        }
        return null;
    }

    private static Method publisher(Object state) {
        if (state == null) return null;
        for (Method method : state.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals("LIZ") && Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class && parameters.length == 6
                    && parameters[0].isInstance(state) && parameters[1] == List.class
                    && parameters[2] == boolean.class && parameters[3] == boolean.class
                    && parameters[4] == Runnable.class && parameters[5] == int.class) {
                method.setAccessible(true); return method;
            }
        }
        return null;
    }
    private static final class Surface {
        final InboxQueryOwners.Owner owner;
        final Method publisher;
        final Map<Object, LoadedInboxQuery.Publication> pending = new IdentityHashMap<>(), metadata = new IdentityHashMap<>();
        final Map<Object, Claim> claims = new IdentityHashMap<>();
        final Map<LoadedInboxQuery.Publication, Boolean> synthetic = new IdentityHashMap<>();
        LoadedInboxQuery.Publication latest, accepted;
        InboxQueryControls controls;
        WeakReference<Object> indices = new WeakReference<>(null);
        int nativeGeneration;
        boolean scrollToStart, needsIndices;
        Surface(InboxQueryOwners.Owner owner) { this.owner = owner; this.publisher = publisher(owner.state.get()); }
    }
    private static final class Claim {
        final LoadedInboxQuery.Publication publication;
        final List<?> aggregate;
        final int nativeGeneration;
        Claim(LoadedInboxQuery.Publication publication, List<?> aggregate, int generation) {
            this.publication = publication;
            this.aggregate = Collections.unmodifiableList(new ArrayList<>(aggregate));
            this.nativeGeneration = generation;
        }
    }
    private static final class Emission {
        final InboxQueryOwners.Owner owner;
        final LoadedInboxQuery.Publication publication;
        Emission(InboxQueryOwners.Owner owner, LoadedInboxQuery.Publication publication) { this.owner = owner; this.publication = publication; }
    }
}
