package app.hushfeed.verification;

import android.app.Application;
import android.app.Instrumentation;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Context-only entry, also usable on a matched unpatched host with the existing signer. */
public final class StorageProbe extends Instrumentation {
    private static final String ACTION = "app.hushfeed.verification.PROBE";
    private static final String TARGET = "com.zhiliaoapp.musically";
    private static final AtomicBoolean LISTENING = new AtomicBoolean();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final long MAX_ENTRIES = 100_000L;
    private static final long MAX_SCAN_MS = 5_000L;

    @Override
    public Application newApplication(ClassLoader loader, String className, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        Application application = super.newApplication(loader, className, context);
        listen(application);
        return application;
    }

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() { }

    private static void listen(Context app) {
        if (!LISTENING.compareAndSet(false, true)) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    // This entry exposes only storage measurement, never settings or reports.
                    try {
                        if ("storage".equals(intent.getStringExtra("action"))) request(app, intent, this);
                    } catch (RuntimeException failure) {
                        Log.i(Probe.TAG, "storage status=rejected reason=invalid_request complete=false");
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter, "android.permission.DUMP", null);
            }
            Log.i(Probe.TAG, "storage ready contextOnly=true");
        } catch (RuntimeException failure) {
            LISTENING.set(false);
            Log.i(Probe.TAG, "storage status=unavailable complete=false");
        }
    }

    /** Only validated tokens and closed labels reach the log. Filesystem errors stay internal. */
    static void request(Context context, Intent intent, BroadcastReceiver receiver) {
        final String token;
        try {
            token = intent.getStringExtra("token");
            if (!StorageScan.validToken(token)) {
                Log.i(Probe.TAG, "storage status=rejected reason=invalid_token complete=false");
                return;
            }
        } catch (RuntimeException failure) {
            Log.i(Probe.TAG, "storage status=rejected reason=invalid_request complete=false");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            Log.i(Probe.TAG, "storage token=" + token + " status=busy complete=false");
            return;
        }
        BroadcastReceiver.PendingResult pending = null;
        try {
            pending = receiver.goAsync();
            final BroadcastReceiver.PendingResult completion = pending;
            Thread worker = new Thread(() -> {
                try { measure(context, token); }
                // A LinkageError is an API the device doesn't have; it must not take TikTok down.
                catch (RuntimeException | LinkageError failure) {
                    Log.i(Probe.TAG, "storage token=" + token + " status=unavailable complete=false");
                } finally {
                    BUSY.set(false);
                    if (completion != null) completion.finish();
                }
            }, "hushfeed-storage-probe");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException failure) {
            BUSY.set(false);
            if (pending != null) pending.finish();
            Log.i(Probe.TAG, "storage token=" + token + " status=unavailable complete=false");
        }
    }

    private static void measure(Context context, String token) {
        ApplicationInfo info = context.getApplicationInfo();
        if (!TARGET.equals(context.getPackageName()) || info.uid != Process.myUid() || info.dataDir == null) {
            Log.i(Probe.TAG, "storage token=" + token + " status=rejected reason=target_context complete=false");
            return;
        }
        if (Build.VERSION.SDK_INT >= 24 && context.isDeviceProtectedStorage()) {
            Log.i(Probe.TAG, "storage token=" + token + " status=rejected reason=device_context complete=false");
            return;
        }
        long started = System.currentTimeMillis();
        long elapsed = SystemClock.elapsedRealtime();
        // Store-specific directory accessors create paths. Use only existing root metadata.
        File[] roots = Build.VERSION.SDK_INT >= 24
                ? Api24.roots(context, info) : new File[] {new File(info.dataDir)};
        StorageScan.Result result = new StorageScan(new AndroidFiles(), MAX_ENTRIES, MAX_SCAN_MS).scan(roots);
        UidStats stats = Build.VERSION.SDK_INT >= 26 ? Api26.stats(context, info) : new UidStats("unsupported");
        StorageScan.Counts privateTotal = result.total(false);
        StorageScan.Counts ownedTotal = result.total(true);
        String prefix = "storage token=" + token;
        Log.i(Probe.TAG, prefix + " schema=1 status=ok utcStart=" + utc(started)
                + " utcEnd=" + utc(System.currentTimeMillis())
                + " elapsedMs=" + (SystemClock.elapsedRealtime() - elapsed)
                + " complete=" + (result.complete() && "ok".equals(stats.status))
                + " atomic=false scope=existing_internal_private traversal=held_directory_handles"
                + " externalPrivateScanned=false symlinkTargetsExcluded=true"
                + " logical=regular_files allocated=st_blocks_x_512 counts=unique_inodes"
                + " maxEntries=" + MAX_ENTRIES + " maxScanMs=" + MAX_SCAN_MS + result.fields());
        // One bounded line per bucket. Neither names, paths, UID nor file content are emitted.
        for (StorageScan.Bucket bucket : StorageScan.Bucket.values()) {
            Log.i(Probe.TAG, prefix + " bucket=" + bucket.name().toLowerCase(Locale.ROOT)
                    + result.buckets.get(bucket).fields());
        }
        Log.i(Probe.TAG, prefix + " bucket=owned_total" + ownedTotal.fields());
        Log.i(Probe.TAG, prefix + " bucket=private_total" + privateTotal.fields());
        // Android data includes cache and may include app-scoped external data on this volume.
        Log.i(Probe.TAG, prefix + " uidStats=" + stats.status + " appBytes=" + stats.app
                + " dataBytes=" + stats.data + " cacheBytes=" + stats.cache
                + " cacheIncludedInData=true uidScopeMayIncludeExternal=true");
        Log.i(Probe.TAG, prefix + " end=true");
    }

    private static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static final class Api24 {
        static File[] roots(Context context, ApplicationInfo info) {
            // getDataDir returns a path without ensurePrivateDirExists (ContextImpl, SDK 36).
            return new File[] {context.getDataDir(),
                    info.deviceProtectedDataDir == null ? null : new File(info.deviceProtectedDataDir)};
        }
    }

    private static final class UidStats {
        final String status;
        long app = -1, data = -1, cache = -1;
        UidStats(String status) { this.status = status; }
    }

    private static final class Api26 {
        static UidStats stats(Context context, ApplicationInfo info) {
            try {
                StorageStatsManager manager = context.getSystemService(StorageStatsManager.class);
                if (manager == null || info.storageUuid == null) return new UidStats("unavailable");
                StorageStats nativeStats = manager.queryStatsForUid(info.storageUuid, Process.myUid());
                UidStats stats = new UidStats("ok");
                stats.app = nativeStats.getAppBytes();
                stats.data = nativeStats.getDataBytes();
                stats.cache = nativeStats.getCacheBytes();
                if (stats.app < 0 || stats.data < 0 || stats.cache < 0) return new UidStats("unavailable");
                return stats;
            } catch (Exception failure) { return new UidStats("unavailable"); }
        }
    }

    private static final class AndroidFiles implements StorageScan.FileSystem {
        @Override public StorageScan.Stat stat(File file) throws StorageScan.Failure {
            return statPath(file.getPath());
        }
        private static StorageScan.Stat statPath(String path) throws StorageScan.Failure {
            try {
                return metadata(Os.lstat(path));
            } catch (ErrnoException failure) { throw failure(failure); }
        }
        @Override public StorageScan.Directory openDirectory(File directory) throws StorageScan.Failure {
            return openPath(directory.getPath());
        }
        @Override public long elapsedMillis() { return SystemClock.elapsedRealtime(); }

        private static StorageScan.Stat metadata(StructStat stat) {
            StorageScan.Kind kind = OsConstants.S_ISREG(stat.st_mode) ? StorageScan.Kind.FILE
                    : OsConstants.S_ISDIR(stat.st_mode) ? StorageScan.Kind.DIRECTORY
                    : OsConstants.S_ISLNK(stat.st_mode) ? StorageScan.Kind.SYMLINK : StorageScan.Kind.OTHER;
            return new StorageScan.Stat(kind, stat.st_dev, stat.st_ino, stat.st_size, stat.st_blocks,
                    stat.st_mtime, stat.st_ctime);
        }

        private static StorageScan.Failure failure(ErrnoException failure) {
            StorageScan.Problem problem = failure.errno == OsConstants.ENOENT ? StorageScan.Problem.MISSING
                    : failure.errno == OsConstants.EACCES || failure.errno == OsConstants.EPERM
                    ? StorageScan.Problem.UNREADABLE : failure.errno == OsConstants.ELOOP
                    ? StorageScan.Problem.CHANGED : StorageScan.Problem.IO;
            return new StorageScan.Failure(problem);
        }

        private static StorageScan.Directory openPath(String path) throws StorageScan.Failure {
            ParcelFileDescriptor held = null;
            try {
                // O_DIRECTORY is not a public SDK constant. Refuse non-directories by fstat.
                // NONBLOCK prevents a replacement FIFO from blocking before that check.
                // O_CLOEXEC is public from API 27; reading it earlier throws NoSuchFieldError.
                int flags = OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK;
                if (Build.VERSION.SDK_INT >= 27) flags |= OsConstants.O_CLOEXEC;
                FileDescriptor raw = Os.open(path, flags, 0);
                try {
                    if (!OsConstants.S_ISDIR(Os.fstat(raw).st_mode))
                        throw new StorageScan.Failure(StorageScan.Problem.CHANGED);
                    held = ParcelFileDescriptor.dup(raw);
                } finally { Os.close(raw); }
                AndroidDirectory anchor = new AndroidDirectory(held);
                held = null;
                return anchor;
            } catch (ErrnoException failure) { throw failure(failure); }
            catch (IOException failure) { throw new StorageScan.Failure(StorageScan.Problem.IO); }
            finally {
                if (held != null) {
                    try { held.close(); }
                    catch (IOException failure) { throw new StorageScan.Failure(StorageScan.Problem.IO); }
                }
            }
        }
    }

    private static final class AndroidDirectory implements StorageScan.Directory {
        private final ParcelFileDescriptor held;
        private final File anchor;
        AndroidDirectory(ParcelFileDescriptor held) {
            this.held = held;
            anchor = new File("/proc/self/fd/" + held.getFd());
        }
        @Override public StorageScan.Stat stat() throws StorageScan.Failure {
            try { return AndroidFiles.metadata(Os.fstat(held.getFileDescriptor())); }
            catch (ErrnoException failure) { throw AndroidFiles.failure(failure); }
        }
        private String childPath(String name) throws StorageScan.Failure {
            if (!StorageScan.validChild(name)) throw new StorageScan.Failure(StorageScan.Problem.IO);
            return new File(anchor, name).getPath();
        }
        @Override public StorageScan.Stat stat(String child) throws StorageScan.Failure {
            return AndroidFiles.statPath(childPath(child));
        }
        @Override public String[] children() throws StorageScan.Failure {
            String[] names = anchor.list();
            if (names == null) throw new StorageScan.Failure(StorageScan.Problem.UNREADABLE);
            return names;
        }
        @Override public StorageScan.Directory openDirectory(String child) throws StorageScan.Failure {
            return AndroidFiles.openPath(childPath(child));
        }
        @Override public void close() throws StorageScan.Failure {
            try { held.close(); }
            catch (IOException failure) { throw new StorageScan.Failure(StorageScan.Problem.IO); }
        }
    }
}
