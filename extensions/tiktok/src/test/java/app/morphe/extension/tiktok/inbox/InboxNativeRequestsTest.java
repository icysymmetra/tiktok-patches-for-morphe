package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Test;

public class InboxNativeRequestsTest {
    @After public void tearDown() { InboxNativeRequests.resetForTests(); }

    @Test public void returningFromTheLauncherDoesNotCompleteItsGenuineAsynchronousJob() {
        Object vm = new Object();
        NativeJob job = new NativeJob();
        assertTrue(InboxNativeRequests.begin(vm));
        assertTrue(InboxNativeRequests.busy(vm));
        InboxNativeRequests.launched(vm, job);
        InboxNativeRequests.returned(vm);
        assertTrue(InboxNativeRequests.busy(vm));
        job.completed = true;
        assertFalse(InboxNativeRequests.busy(vm));
    }

    @Test public void nestedEntriesAndEqualJobsKeepTheirIndependentCompletionLifetimes() {
        Object vm = new Object();
        NativeJob first = new NativeJob(), second = new NativeJob();
        assertTrue(InboxNativeRequests.begin(vm));
        assertTrue(InboxNativeRequests.begin(vm));
        InboxNativeRequests.launched(vm, first);
        InboxNativeRequests.launched(vm, second);
        InboxNativeRequests.returned(vm);
        first.completed = true;
        assertTrue(InboxNativeRequests.busy(vm));
        InboxNativeRequests.returned(vm);
        assertTrue(InboxNativeRequests.busy(vm));
        second.completed = true;
        assertFalse(InboxNativeRequests.busy(vm));
        InboxNativeRequests.returned(vm);
        assertFalse(InboxNativeRequests.busy(vm));
    }

    @Test public void unreadableCompletionRefusesActivationWithoutChangingTheNativeJob() {
        Object vm = new Object(), malformed = new Object();
        assertTrue(InboxNativeRequests.begin(vm));
        InboxNativeRequests.launched(vm, malformed);
        InboxNativeRequests.returned(vm);
        assertTrue(InboxNativeRequests.busy(vm));
        assertTrue("another owner keeps its ordinary request path", InboxNativeRequests.allowed(new Object()));
    }

    public static final class NativeJob {
        boolean completed;
        public boolean isCompleted() { return completed; }
        @Override public boolean equals(Object other) { return other instanceof NativeJob; }
        @Override public int hashCode() { return 1; }
    }
}
