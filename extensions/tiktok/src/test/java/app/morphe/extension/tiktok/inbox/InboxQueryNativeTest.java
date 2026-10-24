package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import kotlin.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class InboxQueryNativeTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private final List<Dialog> dialogs = new ArrayList<>();
    private static final LoadedInboxQuery.Matcher MATCH = new LoadedInboxQuery.Matcher() {
        @Override public boolean notification(Object model) { return model instanceof Notice; }
        @Override public boolean matches(Object model, String query) { return ((Notice) model).text.contains(query); }
    };
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        InboxQueryOwners.resetForTests();
        InboxQueryNative.renderedReader = adapter -> InboxRenderedModels.read(adapter, renderedSchema());
        Settings.INBOX_LOADED_SEARCH.save(true);
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
    }
    @After public void tearDown() {
        InboxQueryOwners.resetForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        InboxQueryNative.renderedReader = InboxRenderedModels::read;
        for (Dialog dialog : dialogs) dialog.dismiss();
        Settings.INBOX_LOADED_SEARCH.resetToDefault();
        controller.close();
    }

    @Test public void defaultOffPreservesNativePublicationFlagsCompletionAndBackingModels() {
        Settings.INBOX_LOADED_SEARCH.save(false);
        Surface surface = new Surface(false);
        int[] completed = {0};
        Runnable nativeCompletion = () -> completed[0]++;
        surface.diff.autoAccept = true;
        surface.state.nativeSet(Arrays.asList(surface.alpha, surface.beta), true, true, nativeCompletion, 0);
        assertSame(surface.state.LJ, surface.state.published);
        assertTrue(surface.state.first);
        assertTrue(surface.state.second);
        assertSame(nativeCompletion, surface.state.completion);
        assertEquals(0, surface.state.mask);
        assertEquals(1, completed[0]);
        assertFalse(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        assertEquals(LoadedInboxQuery.Phase.IDLE, surface.owner().query.phase());
        assertEquals(1, surface.root.getChildCount());
        assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
    }

    @Test public void initialFallbackSeedUsesPrimaryAndOrderedExtraStubsDespiteEmptyDifferCache() {
        Surface surface = new Surface(false);
        surface.adapter.LLLIIIIL.LJ = false;
        surface.diff.LJIIJJI = Collections.emptyList();
        surface.stub.LLL = Arrays.asList(surface.header, surface.alpha, surface.beta);
        Object extraFooter = new Object();
        surface.adapter.LLJJL.add(new NativeStub(surface.state, surface.adapter, Collections.singletonList(surface.footer)));
        surface.adapter.LLJJL.add(new NativeStub(surface.state, surface.adapter, Collections.singletonList(extraFooter)));

        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        assertIdentity(surface.owner().query.rendered(), surface.header, surface.alpha, surface.beta, surface.footer, extraFooter);
        assertIdentity(surface.state.published, surface.alpha);
        assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        assertTrue("seeding must not write a native differ cache", surface.diff.LJIIJJI.isEmpty());
        assertEquals(1, surface.diff.pending.size());
    }

    @Test public void nullConfigSelectsStubAggregateWhileCacheModeIgnoresUnavailableFallbackValues() {
        Surface fallback = new Surface(false);
        fallback.adapter.LLLIIIIL = null;
        fallback.diff.LJIIJJI = Collections.emptyList();
        assertTrue(InboxQueryNative.query(fallback.owner(), "alpha", MATCH));
        assertIdentity(fallback.owner().query.rendered(), fallback.header, fallback.alpha, fallback.beta, fallback.footer);

        Surface cached = new Surface(false);
        cached.adapter.LLJJJJLIIL = null;
        cached.adapter.LLJJL = null;
        cached.adapter.LLLIIL = Object.class;
        cached.adapter.LLJLLL = null;
        assertTrue(InboxQueryNative.query(cached.owner(), "alpha", MATCH));
        assertIdentity(cached.owner().query.rendered(), cached.header, cached.alpha, cached.beta, cached.footer);
    }

    @Test public void fallbackSeedPreservesNativeLoadingTailForEveryPhaseAndStateBranch() {
        NativeLoadingState[] states = {
            new NativeRefreshState(NativePhase.Refresh), new NativeLoadingState(NativePhase.Refresh),
            new NativeEmptyState(NativePhase.Next), new NativeLoadingState(NativePhase.Next),
            new NativeEmptyState(NativePhase.Prev), new NativeLoadingState(NativePhase.Prev)
        };
        boolean[] appended = {true, false, false, true, false, true};
        for (int i = 0; i < states.length; i++) {
            Surface surface = new Surface(false);
            surface.adapter.LLLIIIIL.LJ = false;
            surface.diff.LJIIJJI = Collections.emptyList();
            surface.adapter.LLLIIL = Object.class;
            NativeLoading loading = new NativeLoading(states[i]);
            surface.adapter.LLJLLL = loading;
            assertTrue("native branch " + i, InboxQueryNative.query(surface.owner(), "alpha", MATCH));
            if (appended[i]) assertIdentity(surface.owner().query.rendered(), surface.header,
                    surface.alpha, surface.beta, surface.footer, loading);
            else assertIdentity(surface.owner().query.rendered(), surface.header, surface.alpha, surface.beta, surface.footer);
            assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        }
    }

    @Test public void unsupportedFallbackSchemaRefusesBeforeAnyPublicationOrEmptyBasisAcceptance() {
        for (int malformed = 0; malformed < 3; malformed++) {
            Surface surface = new Surface(false);
            surface.adapter.LLLIIIIL.LJ = false;
            surface.diff.LJIIJJI = Collections.emptyList();
            if (malformed == 0) surface.adapter.LLJJL = null;
            if (malformed == 1) surface.adapter.LLJJL.add(new Object());
            if (malformed == 2) { surface.adapter.LLLIIL = Object.class; surface.adapter.LLJLLL = null; }
            assertFalse(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
            assertNull(surface.state.published);
            assertTrue(surface.diff.pending.isEmpty());
            assertEquals(0, surface.owner().query.committedGeneration());
            assertEquals(LoadedInboxQuery.Phase.IDLE, surface.owner().query.phase());
            assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        }
    }

    @Test public void seedRequiresTheBackingBodyAsAnIdentityContiguousSegment() {
        for (boolean equalReplacement : new boolean[]{false, true}) {
            Surface surface = new Surface(false);
            surface.adapter.LLLIIIIL.LJ = false;
            surface.diff.LJIIJJI = Collections.emptyList();
            surface.stub.LLL = equalReplacement
                    ? Arrays.asList(surface.header, new Notice("alpha"), surface.beta, surface.footer)
                    : Arrays.asList(surface.header, surface.alpha, surface.footer, surface.beta);
            assertFalse(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
            assertNull(surface.state.published);
            assertEquals(0, surface.owner().query.committedGeneration());
            assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        }
    }

    @Test public void emptyBackingDoesNotDiscardNonemptyFallbackHeadersAndSeedIsBounded() {
        Surface surface = new Surface(false);
        surface.adapter.LLLIIIIL.LJ = false;
        surface.diff.LJIIJJI = Collections.emptyList();
        surface.state.LJ.clear();
        surface.stub.LLL = Arrays.asList(surface.header, surface.footer);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        assertIdentity(surface.owner().query.rendered(), surface.header, surface.footer);
        assertTrue(surface.state.published.isEmpty());

        Surface oversized = new Surface(false);
        oversized.state.LJ.addAll(Collections.nCopies(4097, new Object()));
        assertFalse(InboxQueryNative.query(oversized.owner(), "alpha", MATCH));
        assertNull(oversized.state.published);
        assertEquals(0, oversized.owner().query.committedGeneration());
    }

    @Test public void queryWaitsForRealDiffAndAlignsNativeIndicesIncludingExternalChunks() {
        Surface surface = new Surface(false);
        Parcelable originalPosition = surface.layout.saved;
        assertTrue(surface.owner().seamsReady());
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        assertIdentity(surface.state.published, surface.alpha);
        assertEquals(6, surface.state.mask);
        assertNull(surface.state.completion);
        assertFalse(InboxFilter.allowLoadedRequest(surface.vm));
        assertFalse(InboxFilter.allowLoadedRequest(surface.fragment.LLJJJIL));
        assertFalse(InboxFilter.allowLoadedRequest(surface.adapter.LLJLL));
        InboxFilter.onLoadedIndexedCallback(new Closure(surface.fragment), surface.fragment.helper,
                surface.state.LJ, new Pair<>(1, 2));
        assertEquals("the callback waits for the actual accepted diff", 0, surface.fragment.helper.calls);
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertIdentity(surface.fragment.helper.models, surface.header, surface.alpha, surface.footer);
        assertEquals(1, surface.fragment.helper.first);
        assertEquals(1, surface.fragment.helper.last);
        assertTrue(InboxFilter.allowLoadedNotificationClick(surface.holder));
        surface.holder.item = surface.beta;
        assertFalse("a recycled holder cannot dispatch a removed model", InboxFilter.allowLoadedNotificationClick(surface.holder));
        assertSame(surface.beta, surface.holder.getItem());
        assertTrue(InboxQueryNative.query(surface.owner(), "", MATCH));
        assertFalse(InboxFilter.beginLoadedRequest(surface.vm));
        assertNull(surface.layout.restored);
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(originalPosition, surface.layout.restored);
        assertIdentity(surface.owner().query.rendered(), surface.header, surface.alpha, surface.beta, surface.footer);
        assertTrue(InboxFilter.beginLoadedRequest(surface.vm));
        InboxFilter.returnedLoadedRequest(surface.vm);
    }

    @Test public void searchBarUsesOnlyTheFrameworkParentOutsideTheTwoChildNativeSpring() {
        Surface surface = new Surface(false);
        assertSame(surface.spring, surface.list.getParent());
        assertSame(surface.outer, surface.spring.getParent());
        assertEquals(2, surface.spring.getChildCount());
        assertEquals("the search bar is a sibling of SpringLayout", 2, surface.outer.getChildCount());
        int width = View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY);
        surface.root.measure(width, height);
        surface.root.layout(0, 0, 600, 900);
        assertSame(surface.list, surface.spring.nativeNestedChild());

        Settings.INBOX_LOADED_SEARCH.save(false);
        surface.list.getViewTreeObserver().dispatchOnGlobalLayout();
        assertEquals(1, surface.outer.getChildCount());
        assertEquals(2, surface.spring.getChildCount());
        assertSame(surface.list, surface.spring.nativeNestedChild());
    }

    @Test public void queryRefusesPullAndManualRefreshBeforeFlagsOrAnimationsAndClearRestoresStockRefresh() {
        Surface surface = new Surface(false);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        surface.spring.setRefreshing(true);
        surface.spring.LJ();
        assertFalse(surface.spring.refreshing);
        assertFalse(surface.spring.listenerPending);
        assertEquals(0, surface.spring.listenerCalls);
        assertEquals(0, surface.spring.animations);
        assertEquals(0, surface.spring.requests);
        assertEquals(0, surface.spring.completions);

        NativeSpring unrelated = new NativeSpring(activity);
        unrelated.LJ();
        assertTrue(unrelated.refreshing);
        assertEquals(1, unrelated.animations);

        assertTrue(InboxQueryNative.query(surface.owner(), "", MATCH));
        surface.spring.setRefreshing(true);
        surface.spring.LJ();
        assertFalse("clear must wait for genuine diff acceptance", surface.spring.refreshing);
        assertEquals(0, surface.spring.completions);
        surface.diff.accept(0);
        surface.spring.LJ();
        assertTrue(surface.spring.refreshing);
        assertTrue(surface.spring.listenerPending);
        assertEquals(1, surface.spring.listenerCalls);
        assertEquals(1, surface.spring.animations);
        assertEquals(1, surface.spring.requests);
        surface.spring.nativeComplete();
        assertFalse(surface.spring.refreshing);
        assertFalse(surface.spring.listenerPending);
        assertEquals(1, surface.spring.completions);

        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.spring.refreshing = surface.spring.listenerPending = true;
        surface.spring.setRefreshing(false);
        assertFalse("native stop is never guarded", surface.spring.refreshing);
        assertFalse(surface.spring.listenerPending);
    }

    @Test public void syntheticIndicesKeepTheLiveNativeGateAndNeverInitializeItsLazyHelper() {
        Surface surface = new Surface(false);
        surface.fragment.indicesEnabled = false;
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, surface.fragment.helperAccesses);
        assertEquals(0, surface.fragment.helper.calls);
        assertTrue(InboxQueryNative.query(surface.owner(), "", MATCH));
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, surface.fragment.helperAccesses);

        surface.fragment.indicesEnabled = true;
        surface.fragment.nativeIndexed(surface.state.LJ, new Pair<>(0, 1));
        assertEquals(1, surface.fragment.helperAccesses);
        assertEquals(1, surface.fragment.helper.calls);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, surface.fragment.helperAccesses);
        assertEquals(2, surface.fragment.helper.calls);
        assertIdentity(surface.fragment.helper.models, surface.header, surface.alpha, surface.footer);
        assertEquals(1, surface.fragment.helper.first);
        assertEquals(1, surface.fragment.helper.last);

        surface.fragment.indicesEnabled = false;
        assertTrue(InboxQueryNative.query(surface.owner(), "beta", MATCH));
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a previously captured helper does not bypass a later disabled gate", 2, surface.fragment.helper.calls);
        assertEquals(1, surface.fragment.helperAccesses);
        assertTrue(surface.fragment.gateReads >= 2);
    }

    @Test public void queryRefusesAnAlreadyRunningNativeJobAndDoesNotAffectAnotherRequestOwner() {
        Surface surface = new Surface(false);
        InboxNativeRequestsTest.NativeJob job = new InboxNativeRequestsTest.NativeJob();
        assertTrue(InboxFilter.beginLoadedRequest(surface.vm));
        InboxFilter.onLoadedRequestJob(surface.vm, job);
        InboxFilter.returnedLoadedRequest(surface.vm);
        assertFalse(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        assertIdentity(surface.diff.LJIIJJI, surface.header, surface.alpha, surface.beta, surface.footer);
        job.completed = true;
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        Object unrelatedVm = new Object();
        assertTrue(InboxFilter.beginLoadedRequest(unrelatedVm));
        InboxFilter.returnedLoadedRequest(unrelatedVm);
        assertFalse(InboxFilter.beginLoadedRequest(surface.vm));
    }

    @Test public void clearUsesLatestUnfilteredNativePublicationWithoutReplayingItsCompletion() {
        Surface surface = new Surface(false);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        int[] completed = {0};
        Runnable nativeCompletion = () -> completed[0]++;
        Notice newest = new Notice("alpha newest");
        surface.state.nativeSet(Arrays.asList(surface.beta, newest), true, false, nativeCompletion, 2);
        assertIdentity(surface.state.published, newest);
        assertIdentity(surface.state.LJ, surface.beta, newest);
        assertSame(nativeCompletion, surface.state.completion);
        assertTrue(surface.state.first);
        assertFalse(surface.state.second);
        assertEquals(2, surface.state.mask);
        surface.diff.accept(0);
        assertEquals(1, completed[0]);
        assertTrue(InboxQueryNative.query(surface.owner(), "", MATCH));
        assertIdentity(surface.state.published, surface.beta, newest);
        assertNull(surface.state.completion);
        surface.diff.accept(0);
        assertEquals(1, completed[0]);
        assertIdentity(surface.state.LJ, surface.beta, newest);
    }

    @Test public void lateVmSharingRestoresOnlyTheQueryWindowAndRetainsBothNativeBackingLists() {
        Surface first = new Surface(false);
        assertTrue(InboxQueryNative.query(first.owner(), "alpha", MATCH));
        first.diff.accept(0);
        Surface second = new Surface(true);
        InboxFilter.onNotificationVmObserved(second.widget, first.vm);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LoadedInboxQuery.Phase.CLEARING, first.owner().query.phase());
        assertIdentity(first.state.published, first.alpha, first.beta);
        assertEquals(LoadedInboxQuery.Phase.IDLE, second.owner().query.phase());
        assertIdentity(second.state.LJ, second.alpha, second.beta);
        first.diff.accept(0);
        assertEquals(LoadedInboxQuery.Phase.IDLE, first.owner().query.phase());
        assertFalse(first.owner().pagingIsolated());
        assertFalse(InboxQueryNative.query(first.owner(), "alpha", MATCH));
    }

    @Test public void detachRestoresAFreshStockPublicationAndClosesLateDiffOwnership() {
        Surface surface = new Surface(false);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        InboxQueryOwners.Owner owner = surface.owner();
        surface.spring.removeView(surface.list);
        assertEquals(LoadedInboxQuery.Phase.CLOSED, owner.query.phase());
        assertIdentity(surface.state.published, surface.alpha, surface.beta);
        assertEquals(14, surface.state.mask);
        assertNull(surface.state.completion);
        surface.diff.accept(0);
        assertEquals(0, owner.query.committedGeneration());
        assertNull(InboxQueryOwners.ownerForTests(surface.list));
    }

    @Test public void aReaderThatThrowsWhileTypingHandsTheListBackAndRemovesTheBarUntilRestart() {
        HookStatus.clear();
        Surface surface = new Surface(false);
        int nativePadding = surface.list.getPaddingTop();
        View bar = surface.outer.getChildAt(1);
        measureSearchBar(surface, bar);
        assertTrue(surface.list.getPaddingTop() > nativePadding);
        InboxQueryNative.renderedReader = adapter -> { throw new ClassCastException("a drifted native row"); };

        ((EditText) ((ViewGroup) bar).getChildAt(0)).setText("alpha");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull("the bar is gone", bar.getParent());
        assertEquals(1, surface.outer.getChildCount());
        assertEquals(nativePadding, surface.list.getPaddingTop());
        assertNull("nothing was published over TikTok's list", surface.state.published);
        assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        assertTrue(HookStatus.missing(InboxQueryOwners.FAMILY).toString(),
                HookStatus.missing(InboxQueryOwners.FAMILY).contains(
                        "a working 'search query' hook (it threw java.lang.ClassCastException)"));

        // A later bind of the same list keeps TikTok's list and adds no bar until TikTok restarts.
        InboxFilter.onNotificationRowBound(surface.holder);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, surface.outer.getChildCount());
        assertFalse(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        assertNull(surface.state.published);
        HookStatus.clear();
    }

    @Test public void aLayoutPassThatThrowsDuringAQueryRepublishesTikToksUnfilteredList() {
        HookStatus.clear();
        Surface surface = new Surface(false);
        int nativePadding = surface.list.getPaddingTop();
        View bar = surface.outer.getChildAt(1);
        measureSearchBar(surface, bar);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        assertIdentity(surface.state.published, surface.alpha);

        surface.list.locationFailure = new IllegalStateException("a drifted window");
        surface.list.getViewTreeObserver().dispatchOnGlobalLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertIdentity(surface.state.published, surface.alpha, surface.beta);
        assertEquals(14, surface.state.mask);
        assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
        assertNull(bar.getParent());
        assertEquals(nativePadding, surface.list.getPaddingTop());
        assertNull(InboxQueryOwners.ownerForTests(surface.list));
        assertTrue(HookStatus.missing(InboxQueryOwners.FAMILY).contains(
                "a working 'search bar layout' hook (it threw java.lang.IllegalStateException)"));
        HookStatus.clear();
    }

    @Test public void aThrowInOneListEndsTheSearchInEveryOpenList() {
        HookStatus.clear();
        Surface querying = new Surface(false);
        View queryingBar = querying.outer.getChildAt(1);
        assertTrue(InboxQueryNative.query(querying.owner(), "alpha", MATCH));
        querying.diff.accept(0);
        assertIdentity(querying.state.published, querying.alpha);

        Surface failing = new Surface(true);
        View failingBar = failing.outer.getChildAt(1);
        InboxQueryNative.renderedReader = adapter -> { throw new ClassCastException("a drifted native row"); };
        ((EditText) ((ViewGroup) failingBar).getChildAt(0)).setText("alpha");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(failingBar.getParent());
        assertNull("the list that was searching lost its bar too", queryingBar.getParent());
        assertIdentity(querying.state.published, querying.alpha, querying.beta);
        assertEquals(14, querying.state.mask);
        assertNull(InboxQueryOwners.ownerForTests(querying.list));
        assertNull(InboxQueryOwners.ownerForTests(failing.list));
        HookStatus.clear();
    }

    /** The aligned indices are posted to the list's queue, outside any native frame. */
    @Test public void aNativeIndexHelperThatThrowsAfterAQueryHandsTheListBack() {
        HookStatus.clear();
        Surface surface = new Surface(false);
        View bar = surface.outer.getChildAt(1);
        surface.fragment.indicesEnabled = true;
        surface.fragment.nativeIndexed(surface.state.LJ, new Pair<>(0, 1));
        assertEquals(1, surface.fragment.helper.calls);
        surface.fragment.helper.failure = new IndexOutOfBoundsException("a drifted adapter");

        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the posted alignment reached TikTok's helper", 2, surface.fragment.helper.calls);
        assertIdentity(surface.state.published, surface.alpha, surface.beta);
        assertNull(bar.getParent());
        assertNull(InboxQueryOwners.ownerForTests(surface.list));
        assertTrue(HookStatus.missing(InboxQueryOwners.FAMILY).toString(), HookStatus.missing(InboxQueryOwners.FAMILY)
                .contains("a working 'visible rows' hook (it threw java.lang.IndexOutOfBoundsException)"));
        HookStatus.clear();
    }

    @Test public void wholeHostDetachLeavesSiblingChildrenStableUntilTraversalFinishes() {
        Surface surface = new Surface(false);
        int nativePadding = surface.list.getPaddingTop();
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        assertTrue(InboxQueryNative.query(surface.owner(), "", MATCH));
        surface.diff.accept(0);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        InboxQueryOwners.Owner owner = surface.owner();
        assertEquals(LoadedInboxQuery.Phase.IDLE, owner.query.phase());
        assertEquals(2, surface.outer.getChildCount());
        View retiredBar = surface.outer.getChildAt(1);
        measureSearchBar(surface, retiredBar);
        assertTrue(surface.list.getPaddingTop() > nativePadding);
        boolean[] attachedDuringDetach = {false};
        surface.list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                attachedDuringDetach[0] = view.isAttachedToWindow();
                assertNull("ownership must retire during the native detach callback", surface.owner());
            }
        });

        // FrameLayout caches its child array/count while detaching spring then its sibling bar.
        // Removing the bar from the list's callback leaves that traversal reading a null child.
        surface.root.removeView(surface.outer);

        assertTrue("the framework clears attachment after notifying listeners", attachedDuringDetach[0]);
        assertEquals(LoadedInboxQuery.Phase.CLOSED, owner.query.phase());
        assertSame("structural cleanup waits until after the ancestor traversal", surface.outer, retiredBar.getParent());
        assertEquals(2, surface.outer.getChildCount());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(retiredBar.getParent());
        assertEquals(1, surface.outer.getChildCount());
        assertEquals(nativePadding, surface.list.getPaddingTop());
    }

    @Test public void queuedOldBarCleanupPreservesReattachedOwnersNewBarQueryAndPadding() {
        Surface surface = new Surface(false);
        InboxQueryOwners.Owner retiredOwner = surface.owner();
        View retiredBar = surface.outer.getChildAt(1);
        View retiredEditor = ((ViewGroup) retiredBar).getChildAt(0);
        View retiredClear = ((ViewGroup) retiredBar).getChildAt(1);
        int nativePadding = surface.list.getPaddingTop();
        measureSearchBar(surface, retiredBar);
        int retiredPadding = surface.list.getPaddingTop() - nativePadding;
        assertTrue(retiredPadding > 0);
        surface.root.removeView(surface.outer);
        assertEquals(LoadedInboxQuery.Phase.CLOSED, retiredOwner.query.phase());
        assertNull(surface.owner());
        assertFalse(retiredEditor.isEnabled());
        assertFalse("a retained old Clear button cannot act during queued cleanup", retiredClear.performClick());

        surface.root.addView(surface.outer, new FrameLayout.LayoutParams(-1, -1));
        InboxFilter.onNotificationRowBound(surface.holder);
        InboxQueryOwners.Owner replacement = surface.owner();
        assertNotNull(replacement);
        assertNotSame(retiredOwner, replacement);
        assertEquals(3, surface.outer.getChildCount());
        View replacementBar = surface.outer.getChildAt(2);
        assertNotSame(retiredBar, replacementBar);
        measureSearchBar(surface, replacementBar);
        int replacementPadding = surface.list.getPaddingTop() - nativePadding - retiredPadding;
        assertTrue(replacementPadding > 0);
        assertTrue(InboxQueryNative.query(replacement, "beta", MATCH));

        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertNull(retiredBar.getParent());
        assertSame(surface.outer, replacementBar.getParent());
        assertEquals(2, surface.outer.getChildCount());
        assertEquals(nativePadding + replacementPadding, surface.list.getPaddingTop());
        assertSame(replacement, surface.owner());
        assertTrue(replacement.current());
        assertEquals(LoadedInboxQuery.Phase.QUERY, replacement.query.phase());
        assertFalse(InboxFilter.allowLoadedRequest(surface.vm));
        assertIdentity(surface.state.published, surface.beta);
        assertIdentity(surface.state.LJ, surface.alpha, surface.beta);
    }

    private void measureSearchBar(Surface surface, View bar) {
        bar.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        surface.list.getViewTreeObserver().dispatchOnGlobalLayout();
    }

    @Test public void lateSharedStateRestoresTheQueryBeforeTheNewWindowsVmGetterRuns() {
        Surface first = new Surface(false);
        assertTrue(InboxQueryNative.query(first.owner(), "alpha", MATCH));
        first.diff.accept(0);
        Surface second = new Surface(true);
        second.list.state = first.state;
        second.list.adapter = first.adapter;
        second.holder.stub = first.stub;
        InboxFilter.onNotificationRowBound(second.holder);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(LoadedInboxQuery.Phase.CLEARING, first.owner().query.phase());
        assertIdentity(first.state.published, first.alpha, first.beta);
        assertFalse(first.owner().seamsReady());
        assertFalse(second.owner().seamsReady());
        first.diff.accept(0);
        assertEquals(LoadedInboxQuery.Phase.IDLE, first.owner().query.phase());
    }

    @Test public void originalNativeIndexedCallbackFailureKeepsItsExceptionIdentity() {
        RuntimeException failure = new IllegalStateException("native callback failed");
        NativeIndices helper = new NativeIndices();
        helper.failure = failure;
        try {
            InboxFilter.onLoadedIndexedCallback(new Closure(new Object()), helper,
                    Collections.singletonList(new Object()), new Pair<>(0, 0));
            fail("the native callback failure must propagate");
        } catch (RuntimeException expected) { assertSame(failure, expected); }
        assertEquals(1, helper.calls);
    }

    /** Starts a query so a guarded failure has a filtered list to hand back, then throws at the hook. */
    private Surface searchingThenFaultAt(String hook) {
        HookStatus.clear();
        Surface surface = new Surface(false);
        assertTrue(InboxQueryNative.query(surface.owner(), "alpha", MATCH));
        surface.diff.accept(0);
        assertIdentity(surface.state.published, surface.alpha);
        InboxQueryNative.fault = at -> { if (at.equals(hook)) throw new IllegalStateException("a drifted " + hook); };
        return surface;
    }

    private void assertSearchEnded(Surface surface, String hook) {
        InboxQueryNative.fault = null;
        assertNull(InboxQueryOwners.ownerForTests(surface.list));
        assertIdentity(surface.state.published, surface.alpha, surface.beta);
        assertTrue(HookStatus.missing(InboxQueryOwners.FAMILY).toString(), HookStatus.missing(InboxQueryOwners.FAMILY)
                .contains("a working '" + hook + "' hook (it threw java.lang.IllegalStateException)"));
        HookStatus.clear();
    }

    @Test public void aThrowInThePublicationDelegateReturnsTheNativeModelsAndHandsTheListBack() {
        Surface surface = searchingThenFaultAt("loaded publication");
        List<Object> models = new ArrayList<>(Arrays.asList(surface.alpha, surface.beta));
        assertSame(models, InboxFilter.onLoadedPublication(surface.state, models, false, false, null, 14));
        assertSearchEnded(surface, "loaded publication");
    }

    /** TikTok publishes from its list threads, and handing the list back touches the bar's views. */
    @Test public void aThrowOnTikToksListThreadHandsTheListBackOnTheMainThread() throws Exception {
        Surface surface = searchingThenFaultAt("loaded publication");
        List<Object> models = new ArrayList<>(Arrays.asList(surface.alpha, surface.beta));
        List<Object> returned = new ArrayList<>();
        Thread listThread = new Thread(() -> returned.add(
                InboxFilter.onLoadedPublication(surface.state, models, false, false, null, 14)));
        listThread.start();
        listThread.join(5_000);
        assertEquals("the delegate did not return", 1, returned.size());
        assertSame("the native models did not come back", models, returned.get(0));
        assertNotNull("the list was handed back off the main thread", InboxQueryOwners.ownerForTests(surface.list));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSearchEnded(surface, "loaded publication");
    }

    @Test public void aThrowInTheStubAndDiffDelegatesReturnsNormallyAndHandsTheListBack() {
        for (String hook : new String[] {"loaded stub", "loaded diff", "loaded diff accepted"}) {
            Surface surface = searchingThenFaultAt(hook);
            Object metadata = new Object();
            if (hook.equals("loaded stub")) InboxFilter.onLoadedStubPublication(surface.stub, new Wrapper(surface.state.LJ), metadata);
            else if (hook.equals("loaded diff")) InboxFilter.onLoadedDiffSubmitted(surface.diff, Collections.emptyList(),
                    surface.diff.LJIIJJI, surface.aggregate(surface.state.LJ), metadata);
            else InboxFilter.onLoadedDiffAccepted(surface.diff, metadata);
            assertSearchEnded(surface, hook);
            InboxQueryOwners.resetForTests();
        }
    }

    @Test public void aThrowInTheIndexedCallbackStillMakesTheOriginalNativeCall() {
        Surface surface = searchingThenFaultAt("loaded indices");
        List<?> original = surface.state.LJ;
        InboxFilter.onLoadedIndexedCallback(new Closure(surface.fragment), surface.fragment.helper, original, new Pair<>(0, 1));
        assertEquals(1, surface.fragment.helper.calls);
        assertSame(original, surface.fragment.helper.models);
        assertSearchEnded(surface, "loaded indices");
    }

    @Test public void aThrowInTheIndexedListGateReturnsTheOriginalList() {
        Surface surface = searchingThenFaultAt("loaded list");
        List<?> original = surface.state.LJ;
        assertSame(original, InboxFilter.onLoadedIndexedList(surface.fragment, original));
        assertSearchEnded(surface, "loaded list");
    }

    @Test public void aThrowInTheVisibleIndicesGateAllowsTheNativeCall() {
        Surface surface = searchingThenFaultAt("loaded visible indices");
        assertTrue(InboxFilter.allowLoadedVisibleIndices(surface.fragment, true));
        assertSearchEnded(surface, "loaded visible indices");
    }

    @Test public void aThrowInTheClickGateAllowsTheNativeClick() {
        Surface surface = searchingThenFaultAt("loaded click");
        assertTrue(InboxFilter.allowLoadedNotificationClick(surface.holder));
        assertSearchEnded(surface, "loaded click");
    }

    @Test public void aThrowInTheRefreshGateAllowsTheNativeRefresh() {
        Surface surface = searchingThenFaultAt("loaded refresh");
        assertTrue(InboxFilter.allowLoadedRefresh(surface.spring, true));
        assertSearchEnded(surface, "loaded refresh");
    }

    @Test public void aThrowInARequestGateLetsTikToksRequestGoAhead() {
        Surface allowed = searchingThenFaultAt("loaded request");
        assertTrue("a paused list's request goes ahead once the search ends", InboxFilter.allowLoadedRequest(allowed.vm));
        assertSearchEnded(allowed, "loaded request");
        InboxQueryOwners.resetForTests();
        for (boolean viaClosure : new boolean[] {false, true}) {
            Surface started = searchingThenFaultAt("loaded request start");
            assertTrue(viaClosure ? InboxFilter.beginLoadedClosureRequest(new Closure(started.vm))
                    : InboxFilter.beginLoadedRequest(started.vm));
            assertSearchEnded(started, "loaded request start");
            InboxQueryOwners.resetForTests();
        }
    }

    @Test public void aThrowWhileARequestReturnsOrLaunchesItsJobReturnsNormallyAndHandsTheListBack() {
        for (String hook : new String[] {"loaded request return", "loaded request job"}) {
            for (boolean viaClosure : new boolean[] {false, true}) {
                Surface surface = searchingThenFaultAt(hook);
                Object job = new InboxNativeRequestsTest.NativeJob();
                if (hook.equals("loaded request return")) {
                    if (viaClosure) InboxFilter.returnedLoadedClosureRequest(new Closure(surface.vm));
                    else InboxFilter.returnedLoadedRequest(surface.vm);
                } else if (viaClosure) InboxFilter.onLoadedClosureRequestJob(new Closure(surface.vm), job);
                else InboxFilter.onLoadedRequestJob(surface.vm, job);
                assertSearchEnded(surface, hook);
                InboxQueryOwners.resetForTests();
            }
        }
    }

    @Test public void aNativeIndexedFailureDoesNotEndTheSearch() {
        HookStatus.clear();
        Surface surface = new Surface(false);
        surface.fragment.helper.failure = new IllegalStateException("native callback failed");
        try {
            InboxFilter.onLoadedIndexedCallback(new Closure(surface.fragment), surface.fragment.helper,
                    surface.state.LJ, new Pair<>(0, 1));
            fail("the native failure must propagate");
        } catch (IllegalStateException expected) { assertSame(surface.fragment.helper.failure, expected); }
        assertNotNull("TikTok's own failure is not a Metra fault", InboxQueryOwners.ownerForTests(surface.list));
        assertTrue(HookStatus.missing(InboxQueryOwners.FAMILY).isEmpty());
        HookStatus.clear();
    }

    private final class Surface {
        final FrameLayout root = new FrameLayout(activity);
        final FrameLayout outer = new FrameLayout(activity);
        final NativeSpring spring = new NativeSpring(activity);
        final NativeState state = new NativeState();
        final NativeLayout layout = new NativeLayout();
        final NativeDiff diff = new NativeDiff();
        final NativeAdapter adapter = new NativeAdapter(diff);
        final NativeList list = new NativeList(activity, state, adapter, layout);
        final NativeStub stub = new NativeStub(state, adapter);
        final NativeFragment fragment = new NativeFragment(root, list, spring);
        final Notice alpha = new Notice("alpha"), beta = new Notice("beta");
        final Object header = new Object(), footer = new Object(), vm = new Object();
        final NativeWidget widget = new NativeWidget(fragment.LLJJJIL);
        final TextView row = new TextView(activity);
        final NativeHolder holder = new NativeHolder(this);
        Surface(boolean secondWindow) {
            state.surface = this; adapter.stub = adapter.LLJJJJLIIL = stub;
            state.LJ.addAll(Arrays.asList(alpha, beta));
            diff.LJIIJJI = aggregate(state.LJ);
            stub.LLL = aggregate(state.LJ);
            list.addView(row);
            spring.requestTarget = vm;
            spring.addView(new View(activity));
            spring.addView(list, new FrameLayout.LayoutParams(-1, -1));
            outer.addView(spring, new FrameLayout.LayoutParams(-1, -1));
            root.addView(outer, new FrameLayout.LayoutParams(-1, -1));
            if (secondWindow) {
                Dialog dialog = new Dialog(activity);
                dialog.setContentView(root); dialog.show(); dialogs.add(dialog);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue(list.isAttachedToWindow());
            } else activity.setContentView(root);
            InboxFilter.onNotificationRowBound(holder);
            InboxFilter.onNotificationVmObserved(widget, vm);
        }
        InboxQueryOwners.Owner owner() { return InboxQueryOwners.ownerForTests(list); }
        List<?> aggregate(List<?> models) {
            List<Object> all = new ArrayList<>();
            all.add(header); all.addAll(models); all.add(footer); return all;
        }
    }
    private static final class Notice {
        final String text;
        Notice(String text) { this.text = text; }
        @Override public boolean equals(Object other) { return other instanceof Notice; }
        @Override public int hashCode() { return 1; }
    }
    public static final class NativeState {
        public final List<Object> LJ = new ArrayList<>();
        public final ReentrantLock LJFF = new ReentrantLock();
        Surface surface;
        List<?> published;
        boolean first, second;
        Runnable completion;
        int mask;
        public static void LIZ(NativeState state, List<?> models, boolean first, boolean second, Runnable completion, int mask) {
            List<?> projected = InboxFilter.onLoadedPublication(state, models, first, second, completion, mask);
            state.published = projected; state.first = first; state.second = second; state.completion = completion; state.mask = mask;
            Object metadata = new Object();
            InboxFilter.onLoadedStubPublication(state.surface.stub, new Wrapper(projected), metadata);
            state.surface.diff.submit(metadata, state.surface.aggregate(projected), (mask & 8) == 0 ? completion : null);
        }
        void nativeSet(List<?> models, boolean first, boolean second, Runnable completion, int mask) {
            LJFF.lock();
            try { LJ.clear(); LJ.addAll(models); }
            finally { LJFF.unlock(); }
            LIZ(this, LJ, first, second, completion, mask);
        }
    }
    public static final class Wrapper {
        public final List<?> LIZ;
        Wrapper(List<?> models) { LIZ = models; }
    }
    public static final class NativeDiff {
        public int LJIIJ;
        public List<?> LJIIJJI = Collections.emptyList();
        final List<Pending> pending = new ArrayList<>();
        boolean autoAccept;
        void submit(Object metadata, List<?> models, Runnable completion) {
            LJIIJ++;
            InboxFilter.onLoadedDiffSubmitted(this, Collections.emptyList(), LJIIJJI, models, metadata);
            pending.add(new Pending(metadata, models, completion));
            if (autoAccept) accept(pending.size() - 1);
        }
        void accept(int index) {
            Pending accepted = pending.remove(index);
            LJIIJJI = accepted.models;
            InboxFilter.onLoadedDiffAccepted(this, accepted.metadata);
            if (accepted.completion != null) accepted.completion.run();
        }
    }
    private static final class Pending {
        final Object metadata;
        final List<?> models;
        final Runnable completion;
        Pending(Object metadata, List<?> models, Runnable completion) { this.metadata = metadata; this.models = models; this.completion = completion; }
    }
    public static final class NativeAdapter {
        public final NativeDiff LLLII;
        public final Object LLJLL = new Object();
        public NativeConfig LLLIIIIL = new NativeConfig();
        public NativeStub LLJJJJLIIL;
        public List<Object> LLJJL = new ArrayList<>();
        public Class<?> LLLIIL;
        public NativeLoading LLJLLL;
        NativeStub stub;
        NativeAdapter(NativeDiff diff) { LLLII = diff; }
        public NativeStub LLLLLJIL() { return stub; }
    }
    public static final class NativeList extends FrameLayout {
        NativeState state;
        NativeAdapter adapter;
        final NativeLayout layout;
        RuntimeException locationFailure;
        NativeList(Activity activity, NativeState state, NativeAdapter adapter, NativeLayout layout) {
            super(activity); this.state = state; this.adapter = adapter; this.layout = layout;
        }
        @Override public void getLocationInWindow(int[] location) {
            if (locationFailure != null) throw locationFailure;
            super.getLocationInWindow(location);
        }
        public NativeState getState() { return state; }
        public NativeAdapter getAdapter() { return adapter; }
        public NativeLayout getLayoutManager() { return layout; }
        public void scrollToPosition(int position) {}
    }
    public static final class NativeLayout {
        public final Parcelable saved = new Bundle();
        Parcelable restored;
        public Parcelable onSaveInstanceState() { return saved; }
        public void onRestoreInstanceState(Parcelable state) { restored = state; }
        public int findFirstCompletelyVisibleItemPosition() { return 1; }
        public int findLastCompletelyVisibleItemPosition() { return 1; }
        public int findLastVisibleItemPosition() { return 1; }
    }
    public static final class NativeStub {
        public final NativeAdapter LLJJIJIIJIL;
        public List<?> LLL = Collections.emptyList();
        final NativeState state;
        NativeStub(NativeState state, NativeAdapter adapter) { this.state = state; LLJJIJIIJIL = adapter; }
        NativeStub(NativeState state, NativeAdapter adapter, List<?> models) { this(state, adapter); LLL = models; }
        public NativeState LJIIZILJ() { return state; }
    }
    public static final class NativeConfig { public boolean LJ = true; }
    public enum NativePhase { Refresh, Next, Prev }
    public static class NativeLoadingState {
        public final NativePhase LIZIZ;
        NativeLoadingState(NativePhase phase) { LIZIZ = phase; }
    }
    public static final class NativeRefreshState extends NativeLoadingState {
        NativeRefreshState(NativePhase phase) { super(phase); }
    }
    public static final class NativeEmptyState extends NativeLoadingState {
        NativeEmptyState(NativePhase phase) { super(phase); }
    }
    public static final class NativeLoadingModel {
        public final NativeLoadingState LIZIZ;
        NativeLoadingModel(NativeLoadingState state) { LIZIZ = state; }
    }
    public static final class NativeLoading {
        public final NativeLoadingModel LLJJIJIIJIL;
        NativeLoading(NativeLoadingState state) { LLJJIJIIJIL = new NativeLoadingModel(state); }
    }
    private static InboxRenderedModels.Schema renderedSchema() throws ReflectiveOperationException {
        return new InboxRenderedModels.Schema(NativeAdapter.class, NativeDiff.class, NativeConfig.class,
                NativeStub.class, NativeLoading.class, NativeLoadingModel.class, NativeLoadingState.class,
                NativePhase.class, NativeRefreshState.class, NativeEmptyState.class);
    }
    public static final class NativeFragment {
        final View root;
        final NativeList list;
        final NativeSpring spring;
        public final Object LLJJJIL = new Object();
        final NativeIndices helper = new NativeIndices();
        boolean indicesEnabled = true;
        int helperAccesses, gateReads;
        NativeFragment(View root, NativeList list, NativeSpring spring) { this.root = root; this.list = list; this.spring = spring; }
        public View getView() { return root; }
        public NativeList cY() { return list; }
        public NativeSpring bY() { return spring; }
        public NativeIndices ZX() { helperAccesses++; return helper; }
        public boolean hushLoadedInboxIndicesEnabled() { gateReads++; return indicesEnabled; }
        void nativeIndexed(List<?> models, Pair<Integer, Integer> pair) {
            if (indicesEnabled) InboxFilter.onLoadedIndexedCallback(new Closure(this), ZX(), models, pair);
        }
    }
    public static final class NativeSpring extends FrameLayout {
        boolean refreshing, listenerPending;
        int listenerCalls, animations, requests, completions;
        Object requestTarget;
        NativeSpring(Activity activity) { super(activity); }
        View nativeNestedChild() {
            if (getChildCount() > 2) throw new IllegalArgumentException("SpringLayout has more than two children");
            return getChildAt(getChildCount() - 1);
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            nativeNestedChild();
            super.onLayout(changed, left, top, right, bottom);
        }
        public void setRefreshing(boolean value) {
            if (!InboxFilter.allowLoadedRefresh(this, value) || refreshing == value) return;
            refreshing = value;
            if (value && !listenerPending) {
                listenerPending = true; listenerCalls++;
                if (requestTarget != null && InboxFilter.beginLoadedRequest(requestTarget)) {
                    requests++; InboxFilter.returnedLoadedRequest(requestTarget);
                }
            } else if (!value) listenerPending = false;
        }
        public void LJ() {
            if (!InboxFilter.allowLoadedManualRefresh(this)) return;
            setRefreshing(true);
            animations++;
        }
        void nativeComplete() { completions++; setRefreshing(false); }
    }
    public static final class NativeIndices {
        int calls, first, last;
        RuntimeException failure;
        List<?> models;
        public void LJFF(List<?> models, Pair<Integer, Integer> positions) {
            calls++;
            if (failure != null) throw failure;
            this.models = models; first = positions.getFirst(); last = positions.getSecond();
            assertTrue(first >= -1 && last < models.size());
        }
    }
    public static final class NativeHolder {
        final Surface surface;
        public final View itemView;
        NativeStub stub;
        Object item;
        NativeHolder(Surface surface) { this.surface = surface; itemView = surface.row; item = surface.alpha; stub = surface.stub; }
        public View getAttachView$powerlist_release() { return surface.list; }
        public NativeAdapter getAdapterManager$powerlist_release() { return surface.list.adapter; }
        public NativeFragment getCurFragment() { return surface.fragment; }
        public NativeStub getStub() { return stub; }
        public Object getItem() { return item; }
    }
    public static final class NativeWidget {
        final Object parent;
        NativeWidget(Object parent) { this.parent = parent; }
        public Object getParent() { return parent; }
    }
    public static final class Closure {
        public final Object l0;
        Closure(Object fragment) { l0 = fragment; }
    }
    private static void assertIdentity(List<?> actual, Object... expected) {
        assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) assertSame("model at " + i, expected[i], actual.get(i));
    }
}
