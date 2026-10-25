package app.hushfeed.verification.gestures;

import java.util.ArrayList;
import java.util.List;

/** Runs the used input driver with controlled native-call delays and rejection paths. */
public final class GestureDriverContract {
    private static int checks;

    public static void main(String[] args) {
        List<GestureSequence.Event> events = GestureSequence.pinch(0, 0, 1000, 2000,
                940, 1000, 500, 1000, 0.35f, 16, 400);
        Host normal = new Host();
        GestureDriver.Outcome completed = new GestureDriver.Outcome();
        GestureDriver.inject(events, normal, completed);
        check(normal.sent.size() == events.size() && normal.time == 1424 && completed.cancel == null,
                "The bounded pinch completes with its original event timing");
        check(normal.sameDown, "All native events retain the original down time");

        Host foregroundLate = new Host();
        foregroundLate.lateForeground = events.size();
        GestureDriver.Outcome beforeFinal = new GestureDriver.Outcome();
        RuntimeException before = reject(() -> GestureDriver.inject(events, foregroundLate, beforeFinal));
        check(before.getMessage().contains("five seconds"), "Late final foreground lookup fails the deadline");
        check(foregroundLate.sent.size() == events.size() && !hasAction(foregroundLate, GestureSequence.UP),
                "A delayed final foreground lookup cannot inject the final up or report completion");
        check(last(foregroundLate).action == GestureSequence.CANCEL && last(foregroundLate).xs.length == 1
                        && "sent".equals(beforeFinal.cancel),
                "Cancellation after the second pointer lifted contains only the remaining pointer");

        Host injectionLate = new Host();
        injectionLate.lateSend = events.size();
        GestureDriver.Outcome afterFinal = new GestureDriver.Outcome();
        RuntimeException after = reject(() -> GestureDriver.inject(events, injectionLate, afterFinal));
        check(after.getMessage().contains("five seconds") && last(injectionLate).action == GestureSequence.UP,
                "A delayed final injection is a failed run even when native up was accepted");
        check(injectionLate.sent.size() == events.size() && afterFinal.cancel == null,
                "An accepted final up does not receive a spurious cancel");

        Host firstLate = new Host();
        firstLate.lateSend = 1;
        GestureDriver.Outcome afterDown = new GestureDriver.Outcome();
        reject(() -> GestureDriver.inject(events, firstLate, afterDown));
        check(firstLate.sent.size() == 2 && last(firstLate).action == GestureSequence.CANCEL
                        && "sent".equals(afterDown.cancel),
                "Deadline expiry inside initial injection still cancels the accepted down");

        Host sleepLate = new Host();
        sleepLate.oversleep = true;
        GestureDriver.Outcome afterSleep = new GestureDriver.Outcome();
        reject(() -> GestureDriver.inject(events, sleepLate, afterSleep));
        check(sleepLate.sent.size() == 2 && last(sleepLate).action == GestureSequence.CANCEL,
                "A delayed timed wait cannot inject another gesture event");

        Host rejected = new Host();
        rejected.rejectSend = 3;
        rejected.inputFailure = new IllegalStateException("Native move rejected");
        rejected.cancelFailure = new IllegalArgumentException("Native cancel rejected");
        GestureDriver.Outcome cancelled = new GestureDriver.Outcome();
        check(reject(() -> GestureDriver.inject(events, rejected, cancelled)) == rejected.inputFailure,
                "Cancellation rejection cannot replace the original input failure");
        check("failed_IllegalArgumentException".equals(cancelled.cancel) && rejected.cancelAttempts == 1,
                "Cancellation failure is recorded once for the failed run");

        Host cancelLookup = new Host();
        cancelLookup.rejectSend = 2;
        cancelLookup.inputFailure = new IllegalStateException("Native pointer down rejected");
        cancelLookup.rejectForeground = 3;
        GestureDriver.Outcome lookupFailed = new GestureDriver.Outcome();
        check(reject(() -> GestureDriver.inject(events, cancelLookup, lookupFailed)) == cancelLookup.inputFailure,
                "A failed cancellation window lookup preserves the original input rejection");
        check("failed_IllegalArgumentException".equals(lookupFailed.cancel) && cancelLookup.cancelAttempts == 0,
                "A failed ownership lookup cannot inject cancellation into an unknown window");

        Host changedWindow = new Host();
        changedWindow.loseFocus = 2;
        GestureDriver.Outcome lost = new GestureDriver.Outcome();
        reject(() -> GestureDriver.inject(events, changedWindow, lost));
        check(changedWindow.sent.size() == 1 && changedWindow.cancelAttempts == 0
                        && "focus_changed_no_further_input".equals(lost.cancel),
                "The driver sends no further input to an unowned window");
        System.out.println("[gesture-driver] " + checks + " contracts passed");
    }

    private static final class Host implements GestureDriver.Host {
        long time = 1000;
        int foregroundCalls, sendCalls, lateForeground, lateSend, rejectSend, rejectForeground, loseFocus, cancelAttempts;
        boolean oversleep, sameDown = true;
        RuntimeException inputFailure, cancelFailure;
        final List<GestureSequence.Event> sent = new ArrayList<>();

        @Override public long now() { return time; }
        @Override public void sleep(long delay) { time += delay + (oversleep ? 5001 : 0); }
        @Override public boolean ownsWindow() {
            foregroundCalls++;
            if (foregroundCalls == rejectForeground) throw new IllegalArgumentException("Native window lookup rejected");
            if (foregroundCalls == lateForeground) time = 6001;
            return loseFocus == 0 || foregroundCalls < loseFocus;
        }
        @Override public void send(GestureSequence.Event event, long down) {
            sameDown &= down == 1000;
            if (event.action == GestureSequence.CANCEL) {
                cancelAttempts++;
                if (cancelFailure != null) throw cancelFailure;
            } else if (++sendCalls == rejectSend) {
                throw inputFailure;
            }
            sent.add(event);
            if (sendCalls == lateSend) time = 6001;
        }
    }

    private static GestureSequence.Event last(Host host) { return host.sent.get(host.sent.size() - 1); }

    private static boolean hasAction(Host host, int action) {
        return host.sent.stream().anyMatch(event -> event.action == action);
    }

    private static RuntimeException reject(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return expected; }
        throw new AssertionError("Unsafe input completed");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
        checks++;
    }
}
