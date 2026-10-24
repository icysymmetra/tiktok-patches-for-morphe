/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.accountmanagement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One transient account-owned list. Only copied display data survives a native callback. */
final class AccountListSession {
    static final int MAX_PAGES = 100, MAX_ROWS = 3000, MAX_PAGE_ROWS = 100;
    static final long TIMEOUT_MS = 15_000;
    enum Kind { SAVED_POSTS, BLOCKED_ACCOUNTS }
    enum State { READY, LOADING, MORE, COMPLETE, PARTIAL, ERROR, CANCELLED, ACCOUNT_CHANGED, CLOSED }
    enum Reason { NONE, SIGNED_OUT, UNSUPPORTED, NETWORK, REFUSED, TIMEOUT, STALLED, EMPTY_MORE,
        DUPLICATE_PAGE, LIMIT, LIST_CHANGED, FILTERED_MORE, COUNT_MISMATCH }
    interface Cancellation { void cancel(); }
    interface Accounts {
        String current();
        Cancellation observe(Runnable changed) throws ReflectiveOperationException;
    }
    interface Guard { boolean current(); }
    interface Completion { void finished(Page page, Failure failure); }
    interface Transport { Cancellation request(Kind kind, long cursor, Guard guard, Completion completion); }
    interface Dispatch {
        long now();
        void post(Runnable task);
        Cancellation after(long delay, Runnable task);
    }
    interface Listener { void changed(Snapshot value); }

    static final class Row {
        final String id, title, detail;
        Row(String id, String title, String detail) {
            if (id == null || id.isEmpty() || id.length() > 128) throw new IllegalArgumentException("Invalid list identifier");
            this.id = id;
            this.title = title == null ? "" : title;
            this.detail = detail == null ? "" : detail;
        }
    }
    static final class BlockSource {
        final String userId;
        final int type;
        BlockSource(String userId, int type) { this.userId = userId; this.type = type; }
    }
    static final class BlockMetadata {
        final int total, filteredCount, filteredMore;
        final String filteredText;
        final List<BlockSource> sources;
        BlockMetadata(int total, int filteredCount, int filteredMore, String filteredText, List<BlockSource> sources) {
            this.total = total; this.filteredCount = filteredCount; this.filteredMore = filteredMore;
            this.filteredText = filteredText == null ? "" : filteredText;
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }
    }
    static final class Page {
        final List<Row> rows;
        final long cursor;
        final boolean more;
        final BlockMetadata blocks;
        Page(List<Row> rows, long cursor, boolean more, BlockMetadata blocks) {
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.cursor = cursor; this.more = more; this.blocks = blocks;
        }
    }
    static final class Failure {
        final Reason reason;
        final Integer code;
        Failure(Reason reason, Integer code) { this.reason = reason; this.code = code; }
        Failure(Reason reason) { this(reason, null); }
    }
    static final class Snapshot {
        final Kind kind;
        final State state;
        final Reason reason;
        final Integer errorCode;
        final List<Row> rows;
        final int pages;
        final long cursor;
        final BlockMetadata blocks;
        Snapshot(Kind kind, State state, Reason reason, Integer errorCode, List<Row> rows,
                int pages, long cursor, BlockMetadata blocks) {
            this.kind = kind; this.state = state; this.reason = reason; this.errorCode = errorCode;
            this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
            this.pages = pages; this.cursor = cursor; this.blocks = blocks;
        }
        boolean canLoad() { return state == State.READY || state == State.MORE; }
    }

    private final Object lock = new Object();
    private final Kind kind;
    private final Accounts accounts;
    private final Transport transport;
    private final Dispatch dispatch;
    private final AtomicLong epoch = new AtomicLong();
    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();
    private final Set<Long> cursors = new HashSet<>();
    private volatile Listener listener;
    private String account;
    private long ownerEpoch, generation, cursor;
    private int pages;
    private boolean opened, retired;
    private State state = State.READY;
    private Reason reason = Reason.NONE;
    private Integer errorCode;
    private BlockMetadata blocks;
    private Cancellation registration;
    private Slot pending;

    AccountListSession(Kind kind, Accounts accounts, Transport transport, Dispatch dispatch, Listener listener) {
        this.kind = kind; this.accounts = accounts; this.transport = transport;
        this.dispatch = dispatch; this.listener = listener;
    }

    void open() {
        synchronized (lock) { if (opened || retired) return; opened = true; }
        String uid = accounts.current();
        if (uid == null || uid.isEmpty() || "0".equals(uid)) { failOpening(Reason.SIGNED_OUT); return; }
        synchronized (lock) { account = uid; ownerEpoch = epoch.get(); cursors.add(0L); }
        Cancellation observed;
        try { observed = accounts.observe(this::accountChanged); }
        catch (ReflectiveOperationException | RuntimeException unavailable) { failOpening(Reason.UNSUPPORTED); return; }
        boolean dispose;
        synchronized (lock) { dispose = retired; if (!dispose) registration = observed; }
        if (dispose) { if (observed != null) observed.cancel(); return; }
        if (observed == null) { failOpening(Reason.UNSUPPORTED); return; }
        if (!ownerCurrent()) { accountChanged(); return; }
        publish();
        loadMore();
    }

    void loadMore() {
        if (!ownerCurrent()) { accountChanged(); return; }
        Slot slot;
        synchronized (lock) {
            if (retired || pending != null || !(state == State.READY || state == State.MORE)) return;
            if (pages >= MAX_PAGES || rows.size() >= MAX_ROWS) {
                state = State.PARTIAL; reason = Reason.LIMIT; slot = null;
            } else {
                state = State.LOADING; reason = Reason.NONE; errorCode = null;
                slot = new Slot(++generation, dispatch.now() + TIMEOUT_MS);
                pending = slot;
            }
        }
        publish();
        if (slot == null) return;
        slot.installTimer(dispatch.after(TIMEOUT_MS, () -> finished(slot, null, new Failure(Reason.TIMEOUT))));
        try {
            slot.installRequest(transport.request(kind, snapshot().cursor, () -> dispatchable(slot),
                    (page, failure) -> finished(slot, page, failure)));
        } catch (RuntimeException unavailable) { finished(slot, null, new Failure(Reason.NETWORK)); }
    }

    private boolean ownerCurrent() {
        long captured;
        String uid;
        synchronized (lock) {
            if (retired || account == null || epoch.get() != ownerEpoch) return false;
            captured = ownerEpoch; uid = account;
        }
        String fresh = accounts.current();
        synchronized (lock) { return !retired && captured == epoch.get() && uid.equals(fresh); }
    }

    private boolean dispatchable(Slot slot) {
        if (!ownerCurrent()) { accountChanged(); return false; }
        synchronized (lock) {
            return !retired && epoch.get() == ownerEpoch && pending == slot && generation == slot.generation
                    && !slot.done.get() && dispatch.now() < slot.deadline;
        }
    }

    private void finished(Slot slot, Page page, Failure failure) {
        if (!slot.done.compareAndSet(false, true)) return;
        slot.dispose();
        if (!ownerCurrent()) { accountChanged(); return; }
        synchronized (lock) {
            if (retired || epoch.get() != ownerEpoch || pending != slot || generation != slot.generation) return;
            pending = null;
            if (dispatch.now() >= slot.deadline) failure = new Failure(Reason.TIMEOUT);
            if (failure != null || page == null) {
                state = rows.isEmpty() ? State.ERROR : State.PARTIAL;
                reason = failure == null ? Reason.UNSUPPORTED : failure.reason;
                errorCode = failure == null ? null : failure.code;
            } else accept(page);
        }
        publish();
    }

    private void accept(Page page) {
        if (page.rows.size() > MAX_PAGE_ROWS || page.cursor < 0
                || kind == Kind.BLOCKED_ACCOUNTS && (page.cursor > Integer.MAX_VALUE || page.blocks == null)
                || kind == Kind.SAVED_POSTS && page.blocks != null || page.rows.contains(null)) {
            state = rows.isEmpty() ? State.ERROR : State.PARTIAL; reason = Reason.UNSUPPORTED; return;
        }
        int before = rows.size();
        boolean limited = false;
        boolean countChanged = blocks != null && page.blocks != null && blocks.total != page.blocks.total;
        pages++;
        for (Row row : page.rows) {
            if (rows.size() >= MAX_ROWS && !rows.containsKey(row.id)) { limited = true; break; }
            if (!rows.containsKey(row.id)) rows.put(row.id, row);
        }
        blocks = page.blocks;
        Reason partial = Reason.NONE;
        if (limited) partial = Reason.LIMIT;
        else if (countChanged || blocks != null && blocks.total < rows.size()) partial = Reason.LIST_CHANGED;
        else if (page.more && (page.cursor == cursor || cursors.contains(page.cursor))) partial = Reason.STALLED;
        else if (page.more && page.rows.isEmpty()) partial = Reason.EMPTY_MORE;
        else if (page.more && before == rows.size()) partial = Reason.DUPLICATE_PAGE;
        else if (page.more && (pages >= MAX_PAGES || rows.size() >= MAX_ROWS)) partial = Reason.LIMIT;
        else if (!page.more && blocks != null && blocks.filteredMore != 0) partial = Reason.FILTERED_MORE;
        else if (!page.more && blocks != null && (long) blocks.total > (long) rows.size() + blocks.filteredCount)
            partial = Reason.COUNT_MISMATCH;
        cursor = page.cursor;
        cursors.add(cursor);
        state = partial != Reason.NONE ? State.PARTIAL : page.more ? State.MORE : State.COMPLETE;
        reason = partial;
    }

    void cancelLoading() {
        Slot active;
        synchronized (lock) {
            if (retired || state == State.COMPLETE || state == State.CANCELLED) return;
            active = pending; pending = null; generation++;
            state = State.CANCELLED; reason = Reason.NONE;
        }
        if (active != null) active.cancel();
        // The account listener stays until dismissal, so cancelled rows cannot survive a switch.
        publish();
    }

    private void accountChanged() {
        epoch.incrementAndGet();
        retire(State.ACCOUNT_CHANGED, true);
    }

    void close() { listener = null; retire(State.CLOSED, true); }

    private void failOpening(Reason failure) {
        synchronized (lock) {
            if (retired) return;
            state = State.ERROR; reason = failure;
        }
        publish();
    }

    private void retire(State target, boolean clear) {
        Slot active;
        Cancellation observed;
        synchronized (lock) {
            if (retired) return;
            retired = true; state = target; reason = Reason.NONE; generation++;
            active = pending; pending = null; observed = registration; registration = null;
            if (clear) { rows.clear(); blocks = null; }
        }
        if (active != null) active.cancel();
        if (observed != null) observed.cancel();
        publish();
    }

    Snapshot snapshot() {
        synchronized (lock) { return new Snapshot(kind, state, reason, errorCode,
                new ArrayList<>(rows.values()), pages, cursor, blocks); }
    }

    private void publish() {
        dispatch.post(() -> {
            // Read the latest state at delivery, never a pre-switch snapshot queued on main.
            Listener sink = listener;
            if (sink != null) sink.changed(snapshot());
        });
    }

    private static final class Slot implements Cancellation {
        final long generation, deadline;
        final AtomicBoolean done = new AtomicBoolean();
        private Cancellation request, timer;
        Slot(long generation, long deadline) { this.generation = generation; this.deadline = deadline; }
        void installRequest(Cancellation value) { install(value, false); }
        void installTimer(Cancellation value) { install(value, true); }
        private void install(Cancellation value, boolean delayed) {
            boolean cancel;
            synchronized (this) {
                cancel = done.get();
                if (!cancel) { if (delayed) timer = value; else request = value; }
            }
            if (cancel && value != null) value.cancel();
        }
        @Override public void cancel() { done.set(true); dispose(); }
        void dispose() {
            Cancellation active, timeout;
            synchronized (this) { active = request; request = null; timeout = timer; timer = null; }
            if (active != null) active.cancel();
            if (timeout != null) timeout.cancel();
        }
    }
}
