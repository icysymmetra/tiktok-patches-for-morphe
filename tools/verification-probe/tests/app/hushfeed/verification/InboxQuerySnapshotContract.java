package app.hushfeed.verification;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;

/** Exercises the identity hashing used by every native and query list in the actual snapshot. */
public final class InboxQuerySnapshotContract {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Object one = new Opaque(), two = new Opaque();
        InboxQuerySnapshot.IdentityRows rows = InboxQuerySnapshot.identityRows(Arrays.asList(one, two, one, null));
        require(rows.count == 4 && rows.hash.matches("[0-9a-f]{64}"), "bounded identity report without private model methods");
        require(rows.hash.equals(InboxQuerySnapshot.identityRows(Arrays.asList(one, two, one, null)).hash), "same object order has the same hash");
        require(!rows.hash.equals(InboxQuerySnapshot.identityRows(Arrays.asList(two, one, one, null)).hash), "order changes the hash");
        require(!rows.hash.equals(InboxQuerySnapshot.identityRows(Arrays.asList(one, two, one)).hash), "count changes the hash");
        require(InboxQuerySnapshot.identityRows(Collections.emptyList()).count == 0, "actual empty list remains explicit");
        require(InboxQuerySnapshot.identityRows(Collections.nCopies(4096, one)).count == 4096, "inclusive row bound");
        rejects(() -> InboxQuerySnapshot.identityRows(Collections.nCopies(4097, one)), "oversized identity snapshot refused");
        rejects(() -> InboxQuerySnapshot.identityRows(null), "missing models refused");
        rejects(() -> InboxQuerySnapshot.identityRows(new Object()), "wrong model schema refused");
        rejects(() -> InboxQuerySnapshot.identityRows(new AbstractList<Object>() {
            int reads;
            @Override public int size() { return ++reads == 1 ? 1 : 2; }
            @Override public Object get(int index) { return one; }
        }), "list size change cannot report a successful partial hash");
        System.out.println("[inbox-query-snapshot] " + checks + " read-only identity contracts passed; device state not exercised");
    }
    private static final class Opaque {
        @Override public boolean equals(Object other) { throw new AssertionError("no private equality"); }
        @Override public int hashCode() { throw new AssertionError("no private hash"); }
        @Override public String toString() { throw new AssertionError("no private text"); }
    }
    private interface Action { void run(); }
    private static void rejects(Action action, String label) {
        try { action.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
