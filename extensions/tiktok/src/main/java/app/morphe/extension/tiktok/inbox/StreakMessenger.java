/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.SystemClock;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;

/**
 * Gets one message into one chat through TikTok's own code, by the names TikTok kept.
 *
 * <p>The message goes through the notification quick reply, {@code PushQuickActionReceiver}:
 * it takes a chat id and a text in one extra and sends it the way a reply typed into a message
 * notification is sent. It only works once TikTok's messaging has started, and a process an
 * alarm started has not started it: a launch does that, and holds it back until its startup
 * phase ends. Such a process sets messaging up the way a launch does and runs TikTok's start
 * task at once.
 */
final class StreakMessenger {
    private static final String SERVICE_MANAGER =
            "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    static final String IM_SERVICE = "com.ss.android.ugc.aweme.im.service.IIMService";
    static final String IM_START_TASK =
            "com.ss.android.ugc.aweme.im.service.provider.IMService$IdleTask";
    /** Hands TikTok's IM service the host proxy at launch. */
    static final String IM_PROXY = "com.ss.android.ugc.aweme.im.IMProxyImpl";
    static final String QUICK_REPLY_RECEIVER =
            "com.ss.android.ugc.aweme.im.sdk.notification.PushQuickActionReceiver";
    /** The quick reply's one extra, a link whose query carries the chat and the text. */
    static final String REPLY_EXTRA = "reply_content_str";

    /** How long the IM service's own start gets before TikTok's start task is run as well. */
    private static final long KICK_AFTER_MILLIS = 8_000L;
    /** The longest wait for the account, of the whole send's budget. */
    private static final long ACCOUNT_WAIT_MILLIS = 15_000L;
    /**
     * How long a process an alarm started is kept alive after the message is handed over. Not
     * final so a test can leave it out.
     */
    static volatile long settleMillis = 8_000L;
    /**
     * How long messaging a process an alarm started has had to sign in and connect before the
     * message is handed over. Not final so a test can leave it out.
     */
    static volatile long warmUpMillis = 10_000L;
    private static final long POLL_MILLIS = 500L;
    /**
     * How long the main thread gets to take the message before the hand-off is called off. Not
     * final so a test can shorten it.
     */
    static volatile long handOffMillis = 10_000L;

    /** So a test can answer {@link #messagingReady} without TikTok. */
    static volatile Boolean readyForTests;

    private StreakMessenger() {}

    static final class Contact {
        final String uid;
        final String name;

        Contact(String uid, String name) {
            this.uid = uid;
            this.name = name;
        }
    }

    /**
     * The handle in what was typed: "@name", "name" and a profile link all give "name". TikTok
     * handles are letters, digits, dots and underscores, so anything after those is dropped.
     */
    static String handleOf(String typed) {
        if (typed == null) return "";
        String text = typed.trim();
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            text = text.substring(at + 1);
        } else if (text.indexOf('/') >= 0) {
            // A link with no @ in it, like a vm.tiktok.com short link, doesn't name anyone.
            return "";
        }
        int end = 0;
        while (end < text.length()) {
            char c = text.charAt(end);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '_') break;
            end++;
        }
        return text.substring(0, end);
    }

    /** Each comma or line-separated entry names one chat; case variants are the same handle. */
    static List<String> handlesOf(String typed) {
        LinkedHashSet<String> handles = new LinkedHashSet<>();
        if (typed != null) {
            for (String entry : typed.split("[,\\r\\n]+")) {
                String handle = handleOf(entry).toLowerCase(Locale.ROOT);
                if (!handle.isEmpty()) handles.add(handle);
            }
        }
        return new ArrayList<>(handles);
    }

    /**
     * A one to one chat's id: {@code 0:1:} and the two uids, the smaller first. Uids are
     * decimal numbers, so a shorter one is smaller and two of a length compare as text.
     */
    static String conversationId(String self, String peer) {
        boolean selfFirst = self.length() != peer.length()
                ? self.length() < peer.length() : self.compareTo(peer) < 0;
        return "0:1:" + (selfFirst ? self + ":" + peer : peer + ":" + self);
    }

    /**
     * The person with this handle in the signed-in account's message contacts, or null. The
     * contacts are a plain SQLite file of TikTok's, one per account, holding everyone the
     * account has a chat with.
     */
    static Contact findContact(Context context, String selfUid, String handle) {
        File file = context.getDatabasePath("db_im_contact-" + selfUid);
        if (!file.isFile()) return null;
        SQLiteDatabase database = null;
        try {
            database = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            try (Cursor row = database.rawQuery("SELECT UID, NICK_NAME FROM IM_USER_BASE_INFO "
                    + "WHERE UNIQUE_ID = ? COLLATE NOCASE LIMIT 1", new String[]{handle})) {
                if (!row.moveToFirst()) return null;
                String uid = row.getString(0);
                return uid == null || uid.isEmpty() ? null : new Contact(uid, row.getString(1));
            }
        } catch (RuntimeException unreadable) {
            Logger.printInfo(() -> "Auto streak: contacts unreadable", unreadable);
            return null;
        } finally {
            if (database != null) database.close();
        }
    }

    /** TikTok's IM service, created on the first request for it, or null. */
    static Object startMessaging() {
        try {
            Class<?> manager = Class.forName(SERVICE_MANAGER);
            Object services = manager.getMethod("get").invoke(null);
            return manager.getMethod("getService", Class.class).invoke(services, Class.forName(IM_SERVICE));
        } catch (Exception ex) {
            Logger.printInfo(() -> "Auto streak: no IM service", ex);
            return null;
        }
    }

    /**
     * Sets TikTok's messaging up the way a launch does, for a process an alarm started. A launch
     * hands the IM service a host proxy through IMProxyImpl, and the service's initIM puts it in
     * place and starts the IM SDK. The service holds initIM back until the launch's startup
     * phase ends, which a process with no screen never reaches, and until then the quick reply
     * drops a message without a trace: the sender it checks for exists from the start. The proxy
     * is caught as IMProxyImpl hands it over, so its obfuscated class is never named.
     */
    static void initializeMessaging(Object service) {
        if (service == null) return;
        try {
            Class<?> serviceType = Class.forName(IM_SERVICE);
            AtomicReference<Object> proxy = new AtomicReference<>();
            Object catcher = Proxy.newProxyInstance(serviceType.getClassLoader(), new Class<?>[]{serviceType},
                    (self, method, args) -> {
                        if ("initialize".equals(method.getName()) && args != null && args.length == 1) {
                            proxy.set(args[0]);
                        }
                        return defaultOf(method.getReturnType());
                    });
            Object handOver = Class.forName(IM_PROXY).getDeclaredConstructor().newInstance();
            for (Method method : handOver.getClass().getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == serviceType && !Modifier.isStatic(method.getModifiers())) {
                    method.setAccessible(true);
                    method.invoke(handOver, catcher);
                    break;
                }
            }
            Object host = proxy.get();
            if (host == null) {
                Logger.printInfo(() -> "Auto streak: IMProxyImpl handed over no proxy");
                return;
            }
            for (Method method : service.getClass().getMethods()) {
                if ("initIM".equals(method.getName()) && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isInstance(host)) {
                    method.invoke(service, host);
                    Logger.printInfo(() -> "Auto streak: set messaging up");
                    return;
                }
            }
            Logger.printInfo(() -> "Auto streak: no initIM");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Auto streak: messaging setup failed", ex);
        } catch (LinkageError missing) {
            Logger.printInfo(() -> "Auto streak: messaging setup failed: " + missing);
        }
    }

    private static Object defaultOf(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }

    /**
     * Runs TikTok's messaging start task at once. Launching TikTok queues it behind the first
     * screen, which a process an alarm started never draws.
     */
    static void kickMessaging(Context context) {
        try {
            Class<?> task = Class.forName(IM_START_TASK);
            Object start = task.getConstructor(long.class).newInstance(0L);
            task.getMethod("run", Context.class).invoke(start, context);
            Logger.printInfo(() -> "Auto streak: ran the messaging start task");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Auto streak: no messaging start task", ex);
        }
    }

    /** The signed-in uid, waiting a little for an account a new process is still loading. */
    static String awaitSignedIn(long deadline) {
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + ACCOUNT_WAIT_MILLIS);
        while (true) {
            String id = SignedInUser.id();
            if (id != null || SystemClock.elapsedRealtime() >= until) return id;
            SystemClock.sleep(POLL_MILLIS);
        }
    }

    /** Waits until messaging can take a message, starting it harder if it is slow to. */
    static boolean awaitReady(Context context, long deadline) {
        long started = SystemClock.elapsedRealtime();
        boolean kicked = false;
        while (true) {
            if (isReady()) {
                long took = SystemClock.elapsedRealtime() - started;
                boolean afterKick = kicked;
                Logger.printInfo(() -> "Auto streak: messaging ready after " + took + " ms"
                        + (afterKick ? ", with the start task" : ""));
                return true;
            }
            long now = SystemClock.elapsedRealtime();
            if (now >= deadline) return false;
            if (!kicked && now - started >= KICK_AFTER_MILLIS) {
                kicked = true;
                kickMessaging(context);
            }
            SystemClock.sleep(POLL_MILLIS);
        }
    }

    private static boolean isReady() {
        try {
            return messagingReady(null, null);
        } catch (RuntimeException | LinkageError early) {
            // The lookup can throw while TikTok's services are still being built.
            return false;
        }
    }

    /**
     * Whether TikTok's messaging can take a message now: the chat sender the quick reply asks
     * for exists, which is the same test the quick reply makes before it sends anything. The
     * patch replaces this body with the quick reply's own lookup. The two parameters are
     * unused here and give that code registers of its own.
     */
    @SuppressWarnings("unused")
    static boolean messagingReady(Object core, Object chat) {
        Boolean forTests = readyForTests;
        return forTests != null && forTests;
    }

    /** The quick reply's extra for this chat and text. */
    static String replyLink(String conversationId, String text) {
        return new Uri.Builder().scheme("sslocal").authority("reply")
                .appendQueryParameter("conv_id", conversationId)
                .appendQueryParameter("reply_text", text)
                .build().toString();
    }

    /**
     * Hands the message to the quick reply, called directly on the main thread the way Android
     * would call it. A broadcast would queue behind the alarm this is still answering.
     *
     * <p>When the main thread doesn't get to it in time the hand-off is called off, not left
     * queued: a reply that ran late, after this attempt was counted as failed, would go out and
     * then the retry would send a second one.
     */
    static void deliver(Context context, String conversationId, String text) throws Exception {
        deliver(context, conversationId, text, () -> {}, handOffMillis);
    }

    interface BeforeDispatch {
        void check() throws Exception;
    }

    /** A native receiver can throw after it queued the message; retrying would risk a duplicate. */
    static final class UnconfirmedDispatch extends Exception {
        UnconfirmedDispatch(Throwable cause) {
            super("TikTok's receiver ran, but its dispatch outcome is unknown", cause);
        }
    }

    static void deliver(Context context, String conversationId, String text,
                        BeforeDispatch before, long waitMillis) throws Exception {
        if (waitMillis <= 0) throw new IllegalStateException("No time left for the hand-off");
        Class<?> type = Class.forName(QUICK_REPLY_RECEIVER);
        BroadcastReceiver receiver = (BroadcastReceiver) type.getDeclaredConstructor().newInstance();
        Intent intent = new Intent().setClassName(context.getPackageName(), QUICK_REPLY_RECEIVER)
                .putExtra(REPLY_EXTRA, replyLink(conversationId, text));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean claimed = new AtomicBoolean();
        AtomicBoolean invoked = new AtomicBoolean();
        Utils.runOnMainThread(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                // The worker may have waited behind account switching on this very thread.
                before.check();
                invoked.set(true);
                receiver.onReceive(context, intent);
            } catch (Throwable thrown) {
                failure.set(thrown);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(waitMillis, TimeUnit.MILLISECONDS)) {
            if (claimed.compareAndSet(false, true)) {
                throw new IllegalStateException("The main thread didn't take the message");
            }
            // It started just as time ran out. The message is on its way, so wait it out.
            done.await();
        }
        Throwable thrown = failure.get();
        if (thrown != null && invoked.get()) throw new UnconfirmedDispatch(thrown);
        if (thrown instanceof Exception) throw (Exception) thrown;
        if (thrown != null) throw new IllegalStateException(thrown);
    }
}
