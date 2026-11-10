package app.hushfeed.verification;

import java.io.IOException;

/** Injects partial writes and owner changes without Android or synthetic audio-focus callbacks. */
public final class RestorationRetryContract {
    private static final class Restore implements RestorationRetry.Work {
        final BudgetStateSnapshot original;
        final String persisted = "original_budget_record";
        String record = "fixture_budget_record";
        boolean owner = true, journal = true;
        int calls, failures;

        Restore(int failures) throws Exception {
            State.undoAvailable = true; State.passesUsed = 3;
            original = BudgetStateSnapshot.capture(State.class);
            State.undoAvailable = false; State.passesUsed = 0;
            this.failures = failures;
        }
        @Override public boolean owns() { return owner; }
        @Override public void restore() throws Exception {
            calls++;
            State.passesUsed = 3; // A live value can change before a preference commit fails.
            if (calls <= failures) throw new IOException("injected transient commit failure");
            record = persisted;
            original.restore();
            if (!original.matches() || !persisted.equals(record)) throw new IOException("verification failed");
            journal = false;
        }
    }
    private static final class State {
        static boolean undoAvailable;
        static int passesUsed;
    }
    private static final class FencedRestore implements RestorationRetry.Work {
        boolean targetOwned = true, feedLockOn, ticker = true, journal = true;
        boolean transferTargetDuringDetach, enableFeedLockDuringDetach;
        int detachCalls, writeCalls;
        long generation = 7, queuedGeneration = 7;
        String record = "fixture_record";

        @Override public boolean owns() {
            return targetOwned && RestorationRetry.noCompetingHold(false, false, feedLockOn, false);
        }
        @Override public void restore() throws Exception {
            detachCalls++;
            if (transferTargetDuringDetach) targetOwned = false;
            if (enableFeedLockDuringDetach) feedLockOn = true;
            RestorationRetry.runOwned(new RestorationRetry.Work() {
                @Override public boolean owns() { return FencedRestore.this.owns(); }
                @Override public void restore() {
                    ticker = false;
                    generation++;
                    writeCalls++;
                    record = "original_record";
                    journal = false;
                }
            });
        }
        boolean commitQueuedWrite(String value) {
            if (generation != queuedGeneration) return false;
            record = value;
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        Restore transientFailure = new Restore(1);
        RestorationRetry retry = new RestorationRetry();
        require(retry.attempt(transientFailure) == RestorationRetry.State.RETRY
                && transientFailure.journal && !State.undoAvailable, "partial failure must retain journal and retry");
        require(retry.attempt(transientFailure) == RestorationRetry.State.RESTORED
                && !transientFailure.journal && State.undoAvailable && State.passesUsed == 3,
                "retry must restore original record, passes and Undo");
        retry.attempt(transientFailure);
        require(transientFailure.calls == 2, "a stale callback after success must do nothing");

        Restore permanentFailure = new Restore(Integer.MAX_VALUE);
        retry = new RestorationRetry();
        for (int i = 0; i < 20; i++) retry.attempt(permanentFailure);
        require(permanentFailure.calls == 3 && retry.attempts() == 3 && permanentFailure.journal
                && retry.recoveryRequired() && retry.state() == RestorationRetry.State.RECOVERY_REQUIRED,
                "permanent failure must stop after three attempts and retain recovery");

        Restore changedOwner = new Restore(1);
        retry = new RestorationRetry();
        retry.attempt(changedOwner);
        changedOwner.owner = false;
        require(retry.attempt(changedOwner) == RestorationRetry.State.OWNERSHIP_LOST
                && changedOwner.calls == 1 && changedOwner.journal, "retry must not touch transferred ownership");
        changedOwner.owner = true;
        retry.attempt(changedOwner);
        require(changedOwner.calls == 1, "ownership loss must not restart automatically");

        Restore rejected = new Restore(0); rejected.owner = false;
        retry = new RestorationRetry(); retry.attempt(rejected);
        require(rejected.calls == 0 && retry.recoveryRequired(), "first-attempt ownership rejection must not write");
        retry = new RestorationRetry(); retry.schedulingFailed(); retry.attempt(rejected);
        require(retry.recoveryRequired() && retry.attempts() == 0, "a failed scheduler must expose recovery");

        finalFenceRejectsTargetTransfer();
        feedLockAfterEarlyReleasePreservesTicker();
        feedLockDuringDetachPreservesTicker();
        ownedFenceInvalidatesOnlyFixtureWrites();
        System.out.println("Bounded restoration retry contract passed");
    }

    private static void finalFenceRejectsTargetTransfer() throws Exception {
        FencedRestore changed = new FencedRestore();
        changed.transferTargetDuringDetach = true;
        RestorationRetry retry = new RestorationRetry();
        require(retry.attempt(changed) == RestorationRetry.State.OWNERSHIP_LOST
                && changed.detachCalls == 1 && changed.writeCalls == 0 && changed.generation == 7
                && changed.ticker && changed.journal,
                "a target transfer during detach must stop before ticker or generation mutations");
        require(changed.commitQueuedWrite("new_owner_record") && "new_owner_record".equals(changed.record),
                "rejected restoration must preserve a queued write belonging to the new owner");
        retry.attempt(changed);
        require(changed.detachCalls == 1, "a final ownership rejection must not retry overlay detach");
    }

    private static void feedLockAfterEarlyReleasePreservesTicker() throws Exception {
        FencedRestore changed = new FencedRestore();
        require(changed.owns(), "an early-released fixture must initially remain restorable");
        changed.feedLockOn = true; // The independent switch can own the panel without lockWanted.
        RestorationRetry retry = new RestorationRetry();
        require(retry.attempt(changed) == RestorationRetry.State.OWNERSHIP_LOST
                && changed.detachCalls == 0 && changed.writeCalls == 0 && changed.generation == 7
                && changed.ticker && changed.journal && "fixture_record".equals(changed.record),
                "Feed Lock enabled after early release must keep its panel, ticker and pending journal");
    }

    private static void feedLockDuringDetachPreservesTicker() throws Exception {
        FencedRestore changed = new FencedRestore();
        changed.enableFeedLockDuringDetach = true;
        require(new RestorationRetry().attempt(changed) == RestorationRetry.State.OWNERSHIP_LOST
                && changed.detachCalls == 1 && changed.writeCalls == 0 && changed.generation == 7
                && changed.ticker && changed.journal,
                "Feed Lock taking over during sync must retain its ticker and queued-write generation");
    }

    private static void ownedFenceInvalidatesOnlyFixtureWrites() throws Exception {
        FencedRestore original = new FencedRestore();
        require(new RestorationRetry().attempt(original) == RestorationRetry.State.RESTORED
                && original.generation == 8 && original.writeCalls == 1 && !original.ticker && !original.journal,
                "an owned restoration must invalidate the fixture generation exactly once");
        require(!original.commitQueuedWrite("stale_fixture_record") && "original_record".equals(original.record),
                "restoration must fence queued fixture records while preserving the original record");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
