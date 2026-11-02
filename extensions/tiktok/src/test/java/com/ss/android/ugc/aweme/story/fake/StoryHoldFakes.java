package com.ss.android.ugc.aweme.story.fake;

import android.content.Context;
import android.view.View;

import com.ss.android.ugc.aweme.feed.collection.sub.ability.LongPressMonitorAbility;

/**
 * The story hold's objects as TikTok lays them out, with R8-style names that move between builds.
 * They live under a TikTok package because the saver only opens TikTok's own objects.
 */
public final class StoryHoldFakes {
    private StoryHoldFakes() {
    }

    /** The native story child: its monitor sits in one of its fields. */
    public static final class StoryView extends View {
        public Object LLJJJJLIIL;

        public StoryView(Context context, Object monitor) {
            super(context);
            this.LLJJJJLIIL = monitor;
        }
    }

    /** The Assem base keeps its state holder; the monitor inherits it. */
    public static class Assem {
        public Object LLJJJJLIIL;
    }

    /** TikTok's LongPressMonitorComponent. */
    public static final class Monitor extends Assem implements LongPressMonitorAbility {
        public Object LLLFF;

        public Monitor(Object state) {
            this.LLJJJJLIIL = state;
        }
    }

    /** The state holder's base, which keeps the cell's bound item as an Object. */
    public static class StateBase {
        public Object LL;
    }

    /** The Assem state holder, with the platform objects it also keeps. */
    public static final class State extends StateBase {
        public final java.util.List<Object> LLJZ = new java.util.ArrayList<>();

        public State(Object item) {
            this.LL = item;
        }
    }

    /** Any other of TikTok's objects a monitor happens to hold. */
    public static final class Holder {
        public Object LIZ;

        public Holder(Object value) {
            this.LIZ = value;
        }
    }
}
