package app.hushfeed.verification;

import java.io.IOException;
import java.util.List;

/** Drives the same event executor through native-boundary failures without synthesizing view calls. */
public final class EdgeDragPlanContract {
    private static int checks;

    private static final class Fake implements EdgeDragPlan.Driver {
        long time = 1000;
        int injected, canceled, restored, completed, failAt = -1, loseAt = -1;
        int slowGuardAt = -1, slowInjectAt = -1;
        boolean cancelFails, slow;
        @Override public long now() { return time; }
        @Override public void waitUntil(long target) { time = Math.max(time, target) + (slow ? 5000 : 0); }
        @Override public void guard() {
            if (injected == loseAt) throw new IllegalStateException("owned window replaced");
            if (injected == slowGuardAt) time += 5000;
        }
        @Override public void inject(EdgeDragPlan.Event event, long down) throws IOException {
            require(down == 1000 && time >= down + event.offset, "stable down time and scheduled native event");
            int index = injected++;
            if (index == slowInjectAt) time += 5000;
            if (index == failAt) throw new IOException("injected native boundary failure");
        }
        @Override public void completed() { completed++; }
        @Override public void cancel(EdgeDragPlan.Event last, long down) throws IOException {
            canceled++;
            require(last != null && down == 1000, "cancel retains the attempted pointer coordinates and down time");
            if (cancelFails) throw new IOException("injected cancel failure");
        }
        @Override public void restore() { restored++; }
    }

    public static void main(String[] args) throws Exception {
        List<EdgeDragPlan.Event> up = plan(0f), down = plan(1f);
        require(up.size() == 26 && up.get(0).action == 0 && up.get(25).action == 1,
                "balanced native pointer sequence");
        require(up.get(25).y < up.get(0).y && down.get(25).y > down.get(0).y,
                "drag moves away from the current level boundary");
        long last = -1;
        for (EdgeDragPlan.Event event : up) {
            require(event.x == 980f && event.y > 0 && event.y < 2200 && event.offset > last,
                    "strict timing and all coordinates inside fresh bounds");
            last = event.offset;
        }
        reject(() -> EdgeDragPlan.create(0, 0, 1080, 2200, 500, 1000, .5f, 12, 24, 600));
        reject(() -> EdgeDragPlan.create(0, 0, 1080, 2200, 980, 10, .5f, 12, 24, 600));
        reject(() -> EdgeDragPlan.create(0, 0, 1080, 2200, Float.NaN, 1000, .5f, 12, 24, 600));
        reject(() -> EdgeDragPlan.create(0, 0, 1080, 2200, 980, 1000, Float.NaN, 12, 24, 600));
        reject(() -> EdgeDragPlan.create(0, 0, 1080, 2200, 980, 1000, .5f, 12, 11, 600));
        reject(() -> EdgeDragPlan.create(0, 0, 1080, 2200, 980, 1000, .5f, 12, 24, 901));
        Fake ok = new Fake(); EdgeDragPlan.execute(up, ok);
        require(ok.injected == 26 && ok.canceled == 0 && ok.restored == 1 && ok.completed == 1,
                "only timely success marks completion and restores without an extra tap or cancel");
        for (int fail : new int[]{0, 1, 12, 25}) {
            Fake broken = new Fake(); broken.failAt = fail;
            fails(up, broken);
            require(broken.canceled == 1 && broken.restored == 1,
                    "failed down, move or up cancels once and restores");
        }
        Fake replaced = new Fake(); replaced.loseAt = 3; fails(up, replaced);
        require(replaced.injected == 3 && replaced.canceled == 1 && replaced.restored == 1,
                "window ownership loss stops subsequent input");
        Fake beforeDown = new Fake(); beforeDown.loseAt = 0; fails(up, beforeDown);
        require(beforeDown.injected == 0 && beforeDown.canceled == 0 && beforeDown.restored == 1,
                "a rejected initial guard never synthesizes a tap");
        Fake canceled = new Fake(); canceled.failAt = 2; canceled.cancelFails = true; fails(up, canceled);
        require(canceled.canceled == 1 && canceled.restored == 1, "cancel failure still restores");
        Fake timeout = new Fake(); timeout.slow = true; fails(up, timeout);
        require(timeout.injected == 0 && timeout.canceled == 0 && timeout.restored == 1, "deadline stops input before a late down");
        Fake lateGuard = new Fake(); lateGuard.slowGuardAt = 25; fails(up, lateGuard);
        require(lateGuard.injected == 25 && lateGuard.canceled == 1 && lateGuard.restored == 1 && lateGuard.completed == 0,
                "a delayed final guard prevents the UP and cannot mark completion");
        Fake lateUp = new Fake(); lateUp.slowInjectAt = 25; fails(up, lateUp);
        require(lateUp.injected == 26 && lateUp.canceled == 1 && lateUp.restored == 1 && lateUp.completed == 0,
                "a final native UP returning late cancels, restores and cannot mark completion");
        Fake lateDownGuard = new Fake(); lateDownGuard.slowGuardAt = 0; fails(up, lateDownGuard);
        require(lateDownGuard.injected == 0 && lateDownGuard.canceled == 0 && lateDownGuard.restored == 1 && lateDownGuard.completed == 0,
                "a delayed initial guard never starts a late pointer sequence");
        System.out.println("[edge-drag] " + checks + " contracts passed");
    }

    private static List<EdgeDragPlan.Event> plan(float level) {
        return EdgeDragPlan.create(0, 0, 1080, 2200, 980, 1000, level, 12, 24, 600);
    }
    private static void fails(List<EdgeDragPlan.Event> events, Fake fake) throws Exception {
        try { EdgeDragPlan.execute(events, fake); throw new AssertionError("expected boundary failure"); }
        catch (IOException | IllegalStateException expected) { checks++; }
    }
    private static void reject(Runnable action) {
        try { action.run(); throw new AssertionError("invalid plan was accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    private static void require(boolean valid, String message) {
        checks++;
        if (!valid) throw new AssertionError(message);
    }
}
