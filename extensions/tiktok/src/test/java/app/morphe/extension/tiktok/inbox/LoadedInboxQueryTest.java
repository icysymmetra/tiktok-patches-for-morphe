package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class LoadedInboxQueryTest {
    private static final class Ownership implements LoadedInboxQuery.Ownership {
        boolean current = true, ready = true, isolated = true;
        @Override public boolean current() { return current; }
        @Override public boolean seamsReady() { return ready; }
        @Override public boolean pagingIsolated() { return isolated; }
    }
    private static final class Notice {
        final String text;
        Notice(String text) { this.text = text; }
        @Override public boolean equals(Object other) { return other instanceof Notice; }
        @Override public int hashCode() { return 1; }
    }
    private static final LoadedInboxQuery.Matcher MATCH = new LoadedInboxQuery.Matcher() {
        @Override public boolean notification(Object model) { return model instanceof Notice; }
        @Override public boolean matches(Object model, String query) {
            return ((Notice) model).text.toLowerCase(java.util.Locale.ROOT).contains(query);
        }
    };

    @Test public void nativePublicationPreservesEveryFlagMaskRunnableAndOriginalModel() {
        for (boolean first : new boolean[]{false, true}) for (boolean second : new boolean[]{false, true}) {
            for (int mask = 0; mask < 16; mask++) {
                LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
                List<?> nativeList = Arrays.asList(new Notice("a"), new Notice("b"));
                int[] callbacks = {0};
                Runnable nativeCompletion = () -> callbacks[0]++;
                LoadedInboxQuery.Publication publication = session.nativePublication(nativeList, first, second, nativeCompletion, mask);
                assertSame(nativeList, publication.models);
                assertSame(nativeCompletion, publication.completion);
                assertEquals(first, publication.firstFlag);
                assertEquals(second, publication.secondFlag);
                assertEquals(mask, publication.mask);
                assertTrue(session.committed(publication, nativeList).accepted);
                assertEquals("bookkeeping must never replay native completion", 0, callbacks[0]);
            }
        }
    }

    @Test public void queryProjectsOnlyLoadedNotificationsAndKeepsStructuralModelsInOrder() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice alice = new Notice("Alice liked your comment"), bob = new Notice("Bob replied");
        Object header = new Object(), loading = new Object(), footer = new Object();
        List<Object> nativeBacking = new ArrayList<>(Arrays.asList(header, alice, bob, null, loading, footer));
        session.nativePublication(nativeBacking, false, true, null, 0);
        int[] syntheticCompletions = {0};
        Runnable afterDiff = () -> syntheticCompletions[0]++;
        LoadedInboxQuery.Publication filtered = session.query("  ALICE  ", MATCH, new Object(), afterDiff);
        assertIdentityList(filtered.models, header, alice, null, loading, footer);
        assertIdentityList(nativeBacking, header, alice, bob, null, loading, footer);
        assertSame(afterDiff, filtered.completion);
        assertEquals(6, filtered.mask);
        assertFalse(filtered.firstFlag);
        assertFalse(filtered.secondFlag);
        assertTrue(session.committed(filtered, filtered.models).accepted);
        assertEquals(0, syntheticCompletions[0]);
        assertNull("rendering a short result must not trigger paging", session.startRequest());
    }

    @Test public void nativeChangesUpdateUnfilteredBasisWithoutReplacingBackingState() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice first = new Notice("first"), second = new Notice("second"), third = new Notice("third");
        List<Object> original = new ArrayList<>(Arrays.asList(first, second));
        session.nativePublication(original, false, false, null, 14);
        session.query("first", MATCH, new Object(), null);
        original.clear();
        LoadedInboxQuery.Publication retained = session.query("second", MATCH, null, null);
        assertIdentityList(retained.models, second);
        List<?> newlyLoaded = Arrays.asList(first, second, third);
        Runnable nativeCompletion = () -> fail("completion belongs to native diff");
        LoadedInboxQuery.Publication updated = session.nativePublication(newlyLoaded, true, true, nativeCompletion, 9);
        assertIdentityList(updated.models, second);
        assertSame(nativeCompletion, updated.completion);
        assertEquals(9, updated.mask);
        assertTrue(updated.firstFlag && updated.secondFlag);
        LoadedInboxQuery.Publication clear = session.query("", MATCH, null, null);
        assertIdentityList(clear.models, first, second, third);
        assertIdentityList(newlyLoaded, first, second, third);
    }

    @Test public void callbackIndicesFollowCommittedListDuringOutOfOrderCompletions() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice a = new Notice("alpha"), b = new Notice("beta");
        session.nativePublication(Arrays.asList(a, b), false, false, null, 14);
        LoadedInboxQuery.Publication alpha = session.query("alpha", MATCH, new Object(), null);
        LoadedInboxQuery.Publication beta = session.query("beta", MATCH, null, null);
        assertTrue(session.committed(alpha, alpha.models).accepted);
        assertIdentityList(session.callback(alpha.generation, 0, 0).models, a);
        assertNull(session.callback(beta.generation, 0, 0));
        assertTrue(session.committed(beta, beta.models).accepted);
        assertFalse(session.committed(alpha, alpha.models).accepted);
        assertNull(session.callback(alpha.generation, 0, 0));
        assertIdentityList(session.callback(beta.generation, 0, 0).models, b);
        assertNull(session.callback(beta.generation, 0, 1));
        assertNull(session.callback(beta.generation, -1, 0));
        LoadedInboxQuery.Alignment emptyViewport = session.callback(beta.generation, -1, -1);
        assertEquals(-1, emptyViewport.first);
        assertEquals(-1, emptyViewport.last);
    }

    @Test public void clearRestoresLatestStockItemsAndPositionOnlyAfterCurrentDiff() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice a = new Notice("alpha"), b = new Notice("beta"), c = new Notice("gamma");
        session.nativePublication(Arrays.asList(a, b), false, false, null, 14);
        Object originalPosition = new Object();
        LoadedInboxQuery.Publication filter = session.query("alpha", MATCH, originalPosition, null);
        session.committed(filter, filter.models);
        LoadedInboxQuery.Publication clear = session.query("", MATCH, new Object(), null);
        assertNull(session.startRequest());
        LoadedInboxQuery.Publication lateNative = session.nativePublication(Arrays.asList(a, b, c), false, false, null, 14);
        assertNull(session.committed(clear, clear.models).restorePosition);
        assertEquals(LoadedInboxQuery.Phase.CLEARING, session.phase());
        LoadedInboxQuery.Commit done = session.committed(lateNative, lateNative.models);
        assertSame(originalPosition, done.restorePosition);
        assertIdentityList(session.callback(lateNative.generation, 0, 2).models, a, b, c);
        assertEquals(LoadedInboxQuery.Phase.IDLE, session.phase());
        assertNull(session.committed(lateNative, lateNative.models).restorePosition);
        assertNotNull(session.startRequest());
    }

    @Test public void currentModelIdentitySurvivesRecyclingWithoutCachedClickDispatch() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice oldItem = new Notice("old"), currentItem = new Notice("current");
        session.nativePublication(Arrays.asList(oldItem, currentItem), false, false, null, 14);
        LoadedInboxQuery.Publication filtered = session.query("current", MATCH, null, null);
        session.committed(filtered, filtered.models);
        Notice[] holderItem = {oldItem};
        List<Notice> navigated = new ArrayList<>();
        Runnable nativeClick = () -> navigated.add(holderItem[0]);
        holderItem[0] = currentItem;
        nativeClick.run();
        assertSame(currentItem, navigated.get(0));
        assertTrue(session.containsCurrentModel(holderItem[0]));
        assertFalse("model equality must not authorize an old holder item", session.containsCurrentModel(oldItem));
        assertFalse(session.containsCurrentModel(new Notice("current")));
    }

    @Test public void missingSeamOrSharedVmRefusesActivationAndSharedTakeoverStillAllowsClear() {
        Ownership ownership = new Ownership();
        LoadedInboxQuery session = new LoadedInboxQuery(ownership);
        session.nativePublication(Collections.singletonList(new Notice("alpha")), false, false, null, 14);
        ownership.ready = false;
        assertNull(session.query("alpha", MATCH, null, null));
        assertEquals(LoadedInboxQuery.Refusal.SEAMS_MISSING, session.refusal());
        ownership.ready = true; ownership.isolated = false;
        assertNull(session.query("alpha", MATCH, null, null));
        assertEquals(LoadedInboxQuery.Refusal.SHARED_VM, session.refusal());
        assertEquals(LoadedInboxQuery.Phase.IDLE, session.phase());
        ownership.isolated = true;
        assertNotNull(session.query("alpha", MATCH, null, null));
        ownership.isolated = false;
        assertNotNull("sharing discovered later must not trap an active query", session.query("", MATCH, null, null));
    }

    @Test public void inFlightAndDeferredEntriesCannotCrossQueryActivationOrClear() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        session.nativePublication(Collections.singletonList(new Notice("alpha")), false, false, null, 14);
        LoadedInboxQuery.Request preQuery = session.startRequest();
        assertNull(session.query("alpha", MATCH, null, null));
        assertEquals(LoadedInboxQuery.Refusal.IN_FLIGHT, session.refusal());
        session.finishRequest(preQuery); session.finishRequest(preQuery);
        assertEquals(0, session.inFlight());
        LoadedInboxQuery.Publication query = session.query("alpha", MATCH, null, null);
        assertNotNull(query);
        assertNull("direct VM, container and delayed dispatch must consult the same gate", session.startRequest());
        LoadedInboxQuery.Publication clear = session.query("", MATCH, null, null);
        assertNull(session.startRequest());
        session.committed(clear, clear.models);
        assertNotNull(session.startRequest());
    }

    @Test public void windowsCannotCommitEachOthersListsOrCompleteEachOthersRequests() {
        LoadedInboxQuery first = new LoadedInboxQuery(new Ownership()), second = new LoadedInboxQuery(new Ownership());
        List<?> firstList = Collections.singletonList(new Notice("first"));
        List<?> secondList = Collections.singletonList(new Notice("second"));
        LoadedInboxQuery.Publication firstPublication = first.nativePublication(firstList, false, false, null, 14);
        LoadedInboxQuery.Publication secondPublication = second.nativePublication(secondList, false, false, null, 14);
        assertFalse(first.committed(secondPublication, secondList).accepted);
        assertFalse(first.committed(firstPublication, new ArrayList<>(firstList)).accepted);
        LoadedInboxQuery.Request firstRequest = first.startRequest(), secondRequest = second.startRequest();
        first.finishRequest(secondRequest);
        assertEquals(1, first.inFlight());
        assertEquals(1, second.inFlight());
        first.finishRequest(firstRequest); second.finishRequest(secondRequest);
        assertEquals(0, first.inFlight());
        assertEquals(0, second.inFlight());
    }

    @Test public void ownershipChangeClosesStateAndRejectsLateCompletion() {
        Ownership owner = new Ownership();
        LoadedInboxQuery session = new LoadedInboxQuery(owner);
        LoadedInboxQuery.Publication original = session.nativePublication(Collections.singletonList(new Notice("alpha")), false, false, null, 14);
        LoadedInboxQuery.Request request = session.startRequest();
        owner.current = false;
        assertNull(session.query("alpha", MATCH, null, null));
        assertEquals(LoadedInboxQuery.Phase.CLOSED, session.phase());
        assertFalse(session.committed(original, original.models).accepted);
        session.finishRequest(request);
        assertEquals(0, session.inFlight());
    }

    @Test public void unreadableModelsCannotLeaveAPartialQueryOrLoseTheLatestStockBasis() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice a = new Notice("alpha"), b = new Notice("beta");
        session.nativePublication(Collections.singletonList(a), false, false, null, 14);
        LoadedInboxQuery.Matcher failed = new LoadedInboxQuery.Matcher() {
            @Override public boolean notification(Object model) { throw new IllegalStateException("injected unreadable model"); }
            @Override public boolean matches(Object model, String query) { return true; }
        };
        assertNull(session.query("alpha", failed, new Object(), null));
        assertEquals(LoadedInboxQuery.Refusal.MATCHER_FAILED, session.refusal());
        assertEquals(LoadedInboxQuery.Phase.IDLE, session.phase());
        boolean[] unreadable = {false};
        LoadedInboxQuery.Matcher laterFailure = new LoadedInboxQuery.Matcher() {
            @Override public boolean notification(Object model) {
                if (unreadable[0]) throw new IllegalStateException("injected payload failure");
                return MATCH.notification(model);
            }
            @Override public boolean matches(Object model, String query) { return MATCH.matches(model, query); }
        };
        Object originalPosition = new Object();
        session.query("alpha", laterFailure, originalPosition, null);
        unreadable[0] = true;
        Runnable nativeCompletion = () -> fail("completion must remain native");
        List<?> latestStock = Arrays.asList(a, b);
        LoadedInboxQuery.Publication restored = session.nativePublication(latestStock, true, false, nativeCompletion, 10);
        assertSame(latestStock, restored.models);
        assertSame(nativeCompletion, restored.completion);
        assertEquals(10, restored.mask);
        assertEquals(LoadedInboxQuery.Phase.CLEARING, session.phase());
        assertNull(session.startRequest());
        assertSame(originalPosition, session.committed(restored, restored.models).restorePosition);
        assertEquals(LoadedInboxQuery.Phase.IDLE, session.phase());
        assertIdentityList(session.callback(restored.generation, 0, 1).models, a, b);
    }

    @Test public void externalAdapterChunksKeepTheirNativeVisibleIndicesAcrossProjectionAndRecomposition() {
        LoadedInboxQuery session = new LoadedInboxQuery(new Ownership());
        Notice alpha = new Notice("alpha"), beta = new Notice("beta");
        Object header = new Object(), footer = new Object(), replacementHeader = new Object();
        session.nativePublication(Arrays.asList(alpha, beta), false, false, null, 14);
        LoadedInboxQuery.Publication projected = session.query("alpha", MATCH, null, null);
        List<Object> aggregate = new ArrayList<>(Arrays.asList(header, alpha, footer));
        assertTrue(session.committed(projected, projected.models, aggregate).accepted);
        aggregate.clear();
        assertIdentityList(session.callback(projected.generation, 1, 1).models, header, alpha, footer);
        assertTrue(session.containsCurrentModel(alpha));
        assertFalse(session.containsCurrentModel(beta));
        assertTrue(session.recomposed(projected.generation, Arrays.asList(replacementHeader, header, alpha, footer)));
        assertIdentityList(session.callback(projected.generation, 2, 2).models, replacementHeader, header, alpha, footer);
        assertFalse(session.recomposed(projected.generation - 1, Collections.singletonList(beta)));
        assertSame(alpha, session.rendered().get(2));
    }

    private static void assertIdentityList(List<?> actual, Object... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) assertSame("model at " + i, expected[i], actual.get(i));
    }
}
