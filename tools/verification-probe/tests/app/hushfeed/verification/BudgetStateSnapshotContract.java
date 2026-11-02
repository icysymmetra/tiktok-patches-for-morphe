package app.hushfeed.verification;

import java.util.LinkedHashMap;
import java.util.Map;

/** No Android or focus callbacks. Verifies the fixture cannot lose a process-local Undo slot. */
public final class BudgetStateSnapshotContract {
    private static final class State {
        private static final Object LOCK = new Object();
        private static boolean undoAvailable = true;
        private static int undoVideos = 27, passesUsed = 3;
        private static long watchedMs = 123456, lastTickMs = 987, writeGeneration = 8;
        private static String lastCountedId = "private_fixture_identity", optional;
    }

    public static void main(String[] args) throws Exception {
        BudgetStateSnapshot snapshot = BudgetStateSnapshot.capture(State.class, "writeGeneration");
        State.undoAvailable = false;
        State.undoVideos = State.passesUsed = 0;
        State.watchedMs = State.lastTickMs = 0;
        State.lastCountedId = State.optional = "temporary";
        State.writeGeneration++;
        require(!snapshot.matches(), "changes must be detected");
        snapshot.restore();
        require(snapshot.matches() && State.undoAvailable && State.undoVideos == 27 && State.passesUsed == 3,
                "original counts, passes and Undo must return");
        require(State.watchedMs == 123456 && State.lastTickMs == 987 && State.optional == null
                && "private_fixture_identity".equals(State.lastCountedId), "exact scalar and private identity state");
        require(State.writeGeneration == 9, "old queued writes must remain invalidated");
        Map<String, Object> journal = snapshot.journalValues("lastCountedId");
        require(!journal.containsKey("lastCountedId") && !journal.containsValue("private_fixture_identity"),
                "private post identity must never enter a journal");
        require(!journal.containsKey("LOCK") && !journal.containsKey("writeGeneration"), "only restorable scalars");
        BudgetStateSnapshot recovered = BudgetStateSnapshot.fromJournal(State.class, journal,
                "writeGeneration", "lastCountedId");
        State.undoAvailable = false; State.undoVideos = 0;
        recovered.restore();
        require(recovered.matches() && State.undoAvailable && State.undoVideos == 27, "recovery retains aggregate Undo");
        for (String corrupt : new String[]{"missing", "unknown", "type", "overflow", "fraction"}) {
            Map<String, Object> invalid = new LinkedHashMap<>(journal);
            if ("missing".equals(corrupt)) invalid.remove("undoVideos");
            if ("unknown".equals(corrupt)) invalid.put("unrelated", 1);
            if ("type".equals(corrupt)) invalid.put("passesUsed", "wrong");
            if ("overflow".equals(corrupt)) invalid.put("passesUsed", Long.MAX_VALUE);
            if ("fraction".equals(corrupt)) invalid.put("watchedMs", 0.5);
            try {
                BudgetStateSnapshot.fromJournal(State.class, invalid, "writeGeneration", "lastCountedId");
                throw new AssertionError("invalid journal accepted");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("Budget scalar restoration contract passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
