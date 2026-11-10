package app.hushfeed.verification;

/** One bounded automatic restoration cycle. Caller schedules RETRY results on its owned handler. */
final class RestorationRetry {
    static final int MAX_ATTEMPTS = 3;
    static final long DELAY_MS = 1_000;
    enum State { PENDING, RETRY, RESTORED, OWNERSHIP_LOST, RECOVERY_REQUIRED }
    interface Work {
        boolean owns() throws Exception;
        void restore() throws Exception;
    }
    static final class OwnershipLost extends Exception {
        OwnershipLost() { super("restoration ownership changed"); }
    }

    static boolean noCompetingHold(boolean switchWanted, boolean lockWanted, boolean feedLockOn, boolean lockedToday) {
        return !switchWanted && !lockWanted && !feedLockOn && !lockedToday;
    }

    /** Recheck inside the caller's write fence before any mutation that could affect a new owner. */
    static void runOwned(Work work) throws Exception {
        if (!work.owns()) throw new OwnershipLost();
        work.restore();
    }

    private int attempts;
    private State state = State.PENDING;
    private Exception failure;

    State attempt(Work work) {
        if (state != State.PENDING && state != State.RETRY) return state;
        attempts++;
        try {
            runOwned(work);
            failure = null;
            return state = State.RESTORED;
        } catch (OwnershipLost lost) {
            failure = lost;
            return state = State.OWNERSHIP_LOST;
        } catch (Exception failed) {
            failure = failed;
            return state = attempts < MAX_ATTEMPTS ? State.RETRY : State.RECOVERY_REQUIRED;
        }
    }

    void schedulingFailed() {
        failure = new IllegalStateException("restoration retry could not be scheduled");
        state = State.RECOVERY_REQUIRED;
    }
    State state() { return state; }
    int attempts() { return attempts; }
    Exception failure() { return failure; }
    boolean recoveryRequired() { return state == State.RECOVERY_REQUIRED || state == State.OWNERSHIP_LOST; }
}
