/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A rendered-list transaction, independent of native backing lists and network state. Native
 * adapters must finish wiring every seam before allowing query activation. This class never
 * dispatches a click, rewrites a model or runs an original publication's completion callback.
 */
final class LoadedInboxQuery {
    interface Ownership {
        boolean current();
        boolean seamsReady();
        boolean pagingIsolated();
    }
    interface Matcher {
        boolean notification(Object model);
        boolean matches(Object model, String query);
    }
    enum Phase { IDLE, QUERY, CLEARING, CLOSED }
    enum Refusal { NONE, OWNER_CHANGED, SEAMS_MISSING, SHARED_VM, IN_FLIGHT, MATCHER_FAILED }

    static final class Publication {
        final List<?> models;
        final boolean firstFlag, secondFlag;
        final Runnable completion;
        final int mask;
        final long generation;
        private final LoadedInboxQuery owner;

        private Publication(LoadedInboxQuery owner, List<?> models, boolean firstFlag,
                boolean secondFlag, Runnable completion, int mask, long generation) {
            this.owner = owner;
            this.models = models;
            this.firstFlag = firstFlag;
            this.secondFlag = secondFlag;
            this.completion = completion;
            this.mask = mask;
            this.generation = generation;
        }
    }
    static final class Alignment {
        final List<?> models;
        final int first, last;

        Alignment(List<?> models, int first, int last) {
            this.models = models;
            this.first = first;
            this.last = last;
        }
    }
    static final class Request {
        private final LoadedInboxQuery owner;
        private boolean finished;

        Request(LoadedInboxQuery owner) { this.owner = owner; }
    }
    static final class Commit {
        final boolean accepted;
        final Object restorePosition;

        Commit(boolean accepted, Object restorePosition) {
            this.accepted = accepted;
            this.restorePosition = restorePosition;
        }
    }

    private final Ownership ownership;
    private Phase phase = Phase.IDLE;
    private Refusal refusal = Refusal.NONE;
    private List<?> basis = Collections.emptyList();
    private boolean hasBasis;
    private Matcher matcher;
    private String query = "";
    private Object savedPosition;
    private Publication committed;
    private List<?> rendered = Collections.emptyList();
    private long generation;
    private int inFlight;

    LoadedInboxQuery(Ownership ownership) { this.ownership = ownership; }

    /** Only the List argument changes. Both flags, mask and native Runnable remain identical. */
    synchronized Publication nativePublication(List<?> models, boolean firstFlag, boolean secondFlag,
            Runnable completion, int mask) {
        if (models == null) throw new IllegalArgumentException("models required");
        if (!current()) return new Publication(this, models, firstFlag, secondFlag, completion, mask, generation);
        List<?> nextBasis = new ArrayList<>(models);
        List<?> visible = models;
        if (phase == Phase.QUERY) {
            try { visible = project(nextBasis, matcher, query); }
            catch (RuntimeException failed) {
                // Publish native stock on an unreadable model. A later clear must still have
                // the newest unfiltered basis, and paging waits for this stock diff to finish.
                phase = Phase.CLEARING;
                matcher = null;
                query = "";
                refusal = Refusal.MATCHER_FAILED;
            }
        }
        basis = nextBasis;
        hasBasis = true;
        return new Publication(this, visible,
                firstFlag, secondFlag, completion, mask, ++generation);
    }

    /** Returns a plan to emit through ListState.LIZ on the owned view's main thread. */
    synchronized Publication query(String value, Matcher nextMatcher, Object position, Runnable afterDiff) {
        if (!current()) { refusal = Refusal.OWNER_CHANGED; return null; }
        String next = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (next.isEmpty() && phase == Phase.IDLE) return null;
        if (!hasBasis || (!next.isEmpty() && !ownership.seamsReady())) {
            refusal = Refusal.SEAMS_MISSING; return null;
        }
        if (!next.isEmpty() && !ownership.pagingIsolated()) { refusal = Refusal.SHARED_VM; return null; }
        if (!next.isEmpty() && inFlight != 0) { refusal = Refusal.IN_FLIGHT; return null; }
        if (!next.isEmpty() && nextMatcher == null) throw new IllegalArgumentException("matcher required");
        List<?> visible;
        try { visible = next.isEmpty() ? new ArrayList<>(basis) : project(basis, nextMatcher, next); }
        catch (RuntimeException failed) { refusal = Refusal.MATCHER_FAILED; return null; }
        if (phase == Phase.IDLE) savedPosition = position;
        query = next;
        matcher = nextMatcher;
        phase = next.isEmpty() ? Phase.CLEARING : Phase.QUERY;
        refusal = Refusal.NONE;
        // Mask 6 defaults only the two booleans. It keeps this new diff completion, unlike 14.
        return new Publication(this, visible,
                false, false, afterDiff, 6, ++generation);
    }

    private static List<?> project(List<?> basis, Matcher matcher, String query) {
        List<Object> visible = new ArrayList<>();
        for (Object model : basis) {
            // Headers, loading rows and unknown models stay native, in their original order.
            if (!matcher.notification(model) || matcher.matches(model, query)) visible.add(model);
        }
        return visible;
    }

    /** Call only after the native diff accepted this exact list. Never replay a native Runnable. */
    synchronized Commit committed(Publication publication, List<?> displayedModels) {
        return committed(publication, displayedModels, displayedModels);
    }

    /** The native adapter may add header/footer chunks around the owned state's models. */
    synchronized Commit committed(Publication publication, List<?> displayedModels, List<?> aggregate) {
        if (!current() || publication == null || publication.owner != this
                || publication.models != displayedModels || publication.generation > generation
                || aggregate == null || (committed != null && publication.generation <= committed.generation)) {
            return new Commit(false, null);
        }
        committed = publication;
        rendered = Collections.unmodifiableList(new ArrayList<>(aggregate));
        Object restore = null;
        if (phase == Phase.CLEARING && publication.generation == generation) {
            phase = Phase.IDLE;
            restore = savedPosition;
            savedPosition = null;
            matcher = null;
        }
        return new Commit(true, restore);
    }

    /** The callback's list and positions come from the same committed rendered generation. */
    synchronized Alignment callback(long displayedGeneration, int first, int last) {
        if (!current() || committed == null || displayedGeneration != committed.generation) return null;
        if (first == -1 && last == -1) return new Alignment(rendered, first, last);
        if (first < 0 || last < first || last >= rendered.size()) return null;
        return new Alignment(rendered, first, last);
    }

    synchronized boolean containsCurrentModel(Object currentModel) {
        if (!current() || committed == null) return false;
        for (Object visible : rendered) if (visible == currentModel) return true;
        return false;
    }

    /** A header/footer-only native diff can recompose the same owned publication. */
    synchronized boolean recomposed(long displayedGeneration, List<?> aggregate) {
        if (!current() || committed == null || committed.generation != displayedGeneration || aggregate == null) return false;
        rendered = Collections.unmodifiableList(new ArrayList<>(aggregate));
        return true;
    }

    /** All concrete VM/container/deferred entry points must consult this before any side effect. */
    synchronized Request startRequest() {
        if (!current() || phase != Phase.IDLE) return null;
        inFlight++;
        return new Request(this);
    }

    synchronized void finishRequest(Request request) {
        if (request == null || request.owner != this || request.finished) return;
        request.finished = true;
        if (phase != Phase.CLOSED) inFlight--;
    }

    private boolean current() {
        if (phase == Phase.CLOSED) return false;
        if (ownership.current()) return true;
        close();
        return false;
    }

    synchronized void close() {
        phase = Phase.CLOSED;
        basis = Collections.emptyList();
        hasBasis = false;
        committed = null;
        rendered = Collections.emptyList();
        matcher = null;
        savedPosition = null;
        query = "";
        inFlight = 0;
        generation++;
    }

    synchronized Phase phase() { return phase; }
    synchronized Refusal refusal() { return refusal; }
    synchronized long generation() { return generation; }
    synchronized int inFlight() { return inFlight; }
    synchronized long committedGeneration() { return committed == null ? 0 : committed.generation; }
    synchronized List<?> rendered() { return rendered; }
    synchronized boolean settled() { return committed != null && committed.generation == generation; }
}
