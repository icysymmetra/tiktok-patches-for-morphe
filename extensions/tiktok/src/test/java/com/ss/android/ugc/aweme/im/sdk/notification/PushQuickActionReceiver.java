package com.ss.android.ugc.aweme.im.sdk.notification;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Stands in for TikTok's notification quick reply, keeping what it was handed. */
public class PushQuickActionReceiver extends BroadcastReceiver {
    public static final List<Intent> RECEIVED = new CopyOnWriteArrayList<>();
    public static volatile Consumer<Intent> AFTER_RECEIVE;

    @Override
    public void onReceive(Context context, Intent intent) {
        RECEIVED.add(intent);
        Consumer<Intent> callback = AFTER_RECEIVE;
        if (callback != null) callback.accept(intent);
    }
}
