package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import java.util.Collections;
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
public class InboxQueryOwnersTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private Dialog dialog;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        InboxQueryOwners.resetForTests();
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = controller.get();
    }
    @After public void tearDown() {
        InboxQueryOwners.resetForTests();
        if (dialog != null) dialog.dismiss();
        controller.close();
    }

    @Test public void normalAndPayloadBindsKeepCurrentNativeItemAndClickListener() {
        Surface surface = surface(false);
        Object initial = new Object(), rebound = new Object();
        surface.holder.item = initial;
        Object[] clicked = {null};
        surface.row.setOnClickListener(view -> clicked[0] = surface.holder.getItem());
        InboxFilter.onNotificationRowBound(surface.holder);
        InboxQueryOwners.Owner owner = InboxQueryOwners.ownerForTests(surface.list);
        assertNotNull(owner);
        surface.holder.item = rebound;
        InboxFilter.onNotificationRowBound(surface.holder);
        assertSame(owner, InboxQueryOwners.ownerForTests(surface.list));
        assertTrue(surface.row.performClick());
        assertSame(rebound, clicked[0]);
        assertSame(rebound, surface.holder.getItem());
        assertSame(surface.state, surface.list.getState());
        assertTrue("all active filtering seams must be wired before activation", owner.current());
        assertFalse(owner.seamsReady());
        owner.query.nativePublication(Collections.singletonList(rebound), true, true, null, 0);
        assertNull(owner.query.query("rebound", null, null, null));
        assertEquals(LoadedInboxQuery.Refusal.SEAMS_MISSING, owner.query.refusal());
        assertEquals(LoadedInboxQuery.Phase.IDLE, owner.query.phase());
    }

    @Test public void secondWindowUsesItsOwnRecyclerAndFragmentInsteadOfMainActivity() {
        Surface main = surface(false), second = surface(true);
        assertFalse(inside(second.list, activity.findViewById(android.R.id.content)));
        InboxFilter.onNotificationRowBound(second.holder);
        InboxFilter.onNotificationRowBound(main.holder);
        InboxQueryOwners.Owner firstOwner = InboxQueryOwners.ownerForTests(main.list);
        InboxQueryOwners.Owner secondOwner = InboxQueryOwners.ownerForTests(second.list);
        assertNotNull(firstOwner);
        assertNotNull(secondOwner);
        assertNotSame(firstOwner, secondOwner);
        assertSame(second.list, secondOwner.list.get());
        assertSame(second.fragment, secondOwner.fragment.get());
        assertSame(second.state, secondOwner.state.get());
        assertSame(main.state, firstOwner.state.get());
    }

    @Test public void stateOrAdapterReconfigurationInvalidatesTheOldOwner() {
        Surface surface = surface(false);
        InboxFilter.onNotificationRowBound(surface.holder);
        InboxQueryOwners.Owner old = InboxQueryOwners.ownerForTests(surface.list);
        surface.list.state = new Object();
        surface.stub.state = surface.list.state;
        assertFalse(old.current());
        InboxFilter.onNotificationRowBound(surface.holder);
        InboxQueryOwners.Owner replacement = InboxQueryOwners.ownerForTests(surface.list);
        assertNotSame(old, replacement);
        assertEquals(LoadedInboxQuery.Phase.CLOSED, old.query.phase());
        assertTrue(replacement.current());
        surface.list.adapter = new Object();
        assertFalse(replacement.current());
    }

    @Test public void aMismatchedStubStateOrFragmentNeverCapturesAnotherList() {
        Surface main = surface(false), second = surface(true);
        main.stub.state = new Object();
        InboxFilter.onNotificationRowBound(main.holder);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(InboxQueryOwners.ownerForTests(main.list));
        main.stub.state = main.state;
        main.holder.fragment = second.fragment;
        InboxFilter.onNotificationRowBound(main.holder);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(InboxQueryOwners.ownerForTests(main.list));
        assertNull(InboxQueryOwners.ownerForTests(second.list));
    }

    @Test public void detachClosesTheExactWindowAndLeavesTheOtherOwnerAlive() {
        Surface main = surface(false), second = surface(true);
        InboxFilter.onNotificationRowBound(main.holder);
        InboxFilter.onNotificationRowBound(second.holder);
        InboxQueryOwners.Owner closed = InboxQueryOwners.ownerForTests(second.list);
        dialog.dismiss();
        assertNull(InboxQueryOwners.ownerForTests(second.list));
        assertEquals(LoadedInboxQuery.Phase.CLOSED, closed.query.phase());
        assertTrue(InboxQueryOwners.ownerForTests(main.list).current());
    }

    @Test public void aPostedBindCannotCaptureTheHoldersPreviousWindowAfterRecycling() {
        Surface main = surface(false), second = surface(true);
        main.root.removeView(main.list);
        InboxFilter.onNotificationRowBound(main.holder);
        main.holder.list = second.list;
        main.holder.fragment = second.fragment;
        main.holder.stub = second.stub;
        main.holder.itemView = second.row;
        main.root.addView(main.list);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(InboxQueryOwners.ownerForTests(main.list));
        assertNull(InboxQueryOwners.ownerForTests(second.list));
        InboxFilter.onNotificationRowBound(main.holder);
        assertNotNull(InboxQueryOwners.ownerForTests(second.list));
    }

    @Test public void observedVmSharingAcrossWindowsRefusesIsolationWithoutInitializingDormantWidgets() {
        Surface main = surface(false), second = surface(true);
        Object sharedVm = new Object();
        NativeWidget firstWidget = new NativeWidget(main.fragment.LLJJJIL);
        NativeWidget secondWidget = new NativeWidget(second.fragment.LLJJJIL);
        firstWidget.cachedVm = sharedVm;
        secondWidget.cachedVm = sharedVm;
        // A native getter can return before either window binds its first notification.
        InboxFilter.onNotificationVmObserved(firstWidget, sharedVm);
        InboxFilter.onNotificationVmObserved(secondWidget, sharedVm);
        InboxFilter.onNotificationRowBound(main.holder);
        InboxFilter.onNotificationRowBound(second.holder);
        InboxQueryOwners.Owner firstOwner = InboxQueryOwners.ownerForTests(main.list);
        InboxQueryOwners.Owner secondOwner = InboxQueryOwners.ownerForTests(second.list);
        assertFalse(firstOwner.pagingIsolated());
        assertFalse(secondOwner.pagingIsolated());
        secondWidget.cachedVm = new Object();
        InboxFilter.onNotificationVmObserved(secondWidget, secondWidget.cachedVm);
        assertTrue(firstOwner.pagingIsolated());
        assertTrue(secondOwner.pagingIsolated());
        assertEquals(0, firstWidget.lazyReads);
        assertEquals(0, secondWidget.lazyReads);
        secondWidget.parent = main.fragment.LLJJJIL;
        assertFalse("a reparented widget must lose its former owner's mapping", secondOwner.pagingIsolated());
    }

    @Test public void aDormantVmOrUnrelatedWidgetDoesNotProvePagingIsolation() {
        Surface main = surface(false);
        InboxFilter.onNotificationRowBound(main.holder);
        InboxQueryOwners.Owner owner = InboxQueryOwners.ownerForTests(main.list);
        assertFalse(owner.pagingIsolated());
        NativeWidget unrelated = new NativeWidget(new Object());
        InboxFilter.onNotificationVmObserved(unrelated, new Object());
        assertFalse(owner.pagingIsolated());
        assertEquals(0, unrelated.lazyReads);
    }

    private Surface surface(boolean otherWindow) {
        FrameLayout root = new FrameLayout(activity);
        NativeList list = new NativeList(activity);
        TextView row = new TextView(activity);
        list.addView(row);
        root.addView(list);
        if (otherWindow) {
            dialog = new Dialog(activity);
            dialog.setContentView(root);
            dialog.show();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the second window must complete its native attachment before binding", list.isAttachedToWindow());
        } else activity.setContentView(root);
        NativeFragment fragment = new NativeFragment(root, list);
        NativeStub stub = new NativeStub(list);
        NativeHolder holder = new NativeHolder(row, list, fragment, stub);
        return new Surface(root, list, row, fragment, stub, holder);
    }
    private static boolean inside(View child, View root) {
        for (View view = child; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view == root) return true;
        }
        return false;
    }
    private static final class Surface {
        final FrameLayout root;
        final NativeList list;
        final View row;
        final Object state;
        final NativeFragment fragment;
        final NativeStub stub;
        final NativeHolder holder;
        Surface(FrameLayout root, NativeList list, View row, NativeFragment fragment, NativeStub stub, NativeHolder holder) {
            this.root = root; this.list = list; this.row = row; this.state = list.state;
            this.fragment = fragment; this.stub = stub; this.holder = holder;
        }
    }
    public static final class NativeList extends FrameLayout {
        Object state = new Object(), adapter = new Object();
        NativeList(Activity activity) { super(activity); }
        public Object getState() { return state; }
        public Object getAdapter() { return adapter; }
    }
    public static final class NativeFragment {
        final View root;
        final NativeList list;
        public Object LLJJJIL = new Object();
        NativeFragment(View root, NativeList list) { this.root = root; this.list = list; }
        public View getView() { return root; }
        public NativeList cY() { return list; }
    }
    public static final class NativeStub {
        Object state;
        public Object LLJJIJIIJIL;
        NativeStub(NativeList list) { state = list.state; LLJJIJIIJIL = list.adapter; }
        public Object LJIIZILJ() { return state; }
    }
    public static final class NativeHolder {
        public View itemView;
        NativeList list;
        NativeFragment fragment;
        NativeStub stub;
        Object item = new Object();
        NativeHolder(View row, NativeList list, NativeFragment fragment, NativeStub stub) {
            itemView = row; this.list = list; this.fragment = fragment; this.stub = stub;
        }
        public NativeList getAttachView$powerlist_release() { return list; }
        public Object getAdapterManager$powerlist_release() { return list.adapter; }
        public NativeFragment getCurFragment() { return fragment; }
        public NativeStub getStub() { return stub; }
        public Object getItem() { return item; }
    }
    public static final class NativeWidget {
        Object parent;
        Object cachedVm;
        int lazyReads;
        NativeWidget(Object parent) { this.parent = parent; }
        public Object getParent() { return parent; }
        public Object Zq() { lazyReads++; return cachedVm; }
    }
}
