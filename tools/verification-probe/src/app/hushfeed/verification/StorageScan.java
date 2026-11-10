package app.hushfeed.verification;

import java.io.File;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Metadata only. No file reads, store initialization, normalization or cleanup. */
final class StorageScan {
    enum Kind { FILE, DIRECTORY, SYMLINK, OTHER }
    enum Problem { MISSING, UNREADABLE, CHANGED, IO }
    enum Bucket {
        MEDIA_TEMP, PUBLICATION_JOURNAL, SAVED_VIDEO_ARCHIVE, SEEN_HISTORY, SAVE_RECORDS,
        SETTINGS, FEATURE_LAB, DIAGNOSTICS, STARTUP_GUARD, REMAINING_PRIVATE
    }

    static final class Failure extends Exception {
        final Problem problem;
        Failure(Problem problem) { this.problem = problem; }
    }

    static final class Stat {
        final Kind kind;
        final long device, inode, size, blocks, modified, changed;
        Stat(Kind kind, long device, long inode, long size, long blocks, long modified, long changed) {
            this.kind = kind;
            this.device = device;
            this.inode = inode;
            this.size = size;
            this.blocks = blocks;
            this.modified = modified;
            this.changed = changed;
        }
        boolean same(Stat other) {
            return kind == other.kind && device == other.device && inode == other.inode
                    && size == other.size && blocks == other.blocks
                    && modified == other.modified && changed == other.changed;
        }
        String identity() { return device + ":" + inode; }
    }

    interface FileSystem {
        Stat stat(File file) throws Failure;
        Directory openDirectory(File directory) throws Failure;
        long elapsedMillis();
    }

    /** Every operation resolves against the held directory, never its replaceable pathname. */
    interface Directory extends AutoCloseable {
        Stat stat() throws Failure;
        Stat stat(String child) throws Failure;
        String[] children() throws Failure;
        Directory openDirectory(String child) throws Failure;
        @Override void close() throws Failure;
    }

    static final class Counts {
        long files, directories, symlinks, other, logicalBytes, allocatedBytes;
        String fields() {
            return " files=" + files + " directories=" + directories + " symlinks=" + symlinks
                    + " other=" + other + " logicalBytes=" + logicalBytes + " allocatedBytes=" + allocatedBytes;
        }
    }

    static final class Result {
        final EnumMap<Bucket, Counts> buckets = new EnumMap<>(Bucket.class);
        long entries, missing, unreadable, ioErrors, changed, aliases, crossBucketAliases, cycles, invalid;
        int roots, absentRoots;
        boolean limited;
        Result() { for (Bucket bucket : Bucket.values()) buckets.put(bucket, new Counts()); }
        boolean complete() {
            return roots > 0 && !limited && missing + unreadable + ioErrors + changed + cycles + invalid == 0;
        }
        String fields() {
            return " privateComplete=" + complete() + " roots=" + roots + " absentRoots=" + absentRoots
                    + " entries=" + entries + " missing=" + missing + " unreadable=" + unreadable
                    + " ioErrors=" + ioErrors + " changed=" + changed + " aliases=" + aliases
                    + " crossBucketAliases=" + crossBucketAliases + " cycles=" + cycles
                    + " invalid=" + invalid + " limited=" + limited;
        }
        Counts total(boolean ownedOnly) {
            Counts total = new Counts();
            for (Map.Entry<Bucket, Counts> entry : buckets.entrySet()) {
                if (ownedOnly && entry.getKey() == Bucket.REMAINING_PRIVATE) continue;
                Counts part = entry.getValue();
                total.files += part.files;
                total.directories += part.directories;
                total.symlinks += part.symlinks;
                total.other += part.other;
                total.logicalBytes = aggregate(total.logicalBytes, part.logicalBytes);
                total.allocatedBytes = aggregate(total.allocatedBytes, part.allocatedBytes);
            }
            return total;
        }
        private long aggregate(long current, long extra) {
            if (current > Long.MAX_VALUE - extra) { invalid++; return Long.MAX_VALUE; }
            return current + extra;
        }
    }

    private static final class Counted {
        final Stat stat;
        Bucket bucket;
        Counted(Stat stat, Bucket bucket) { this.stat = stat; this.bucket = bucket; }
    }

    private final FileSystem fs;
    private final long deadline, maxEntries;
    private final Result result = new Result();
    private final Map<String, Counted> counted = new HashMap<>();

    StorageScan(FileSystem fs, long maxEntries, long maxMillis) {
        this.fs = fs;
        this.maxEntries = maxEntries;
        deadline = fs.elapsedMillis() + maxMillis;
    }

    Result scan(File... roots) {
        for (File root : roots) {
            if (root == null || exhausted()) continue;
            Stat stat = stat(root, true);
            if (stat == null) continue;
            if (stat.kind != Kind.DIRECTORY) { result.invalid++; continue; }
            // CE/DE or platform aliases must not count a root twice.
            if (counted.containsKey(stat.identity())) continue;
            result.roots++;
            visit(root, null, null, "", stat, 0);
        }
        return result;
    }

    private boolean exhausted() {
        if (result.entries >= maxEntries || fs.elapsedMillis() >= deadline) result.limited = true;
        return result.limited;
    }

    private Stat stat(File file, boolean root) {
        try { return fs.stat(file); }
        catch (Failure failure) {
            if (failure.problem == Problem.MISSING) {
                if (root) result.absentRoots++; else result.missing++;
            } else if (failure.problem == Problem.UNREADABLE) result.unreadable++;
            else if (failure.problem == Problem.CHANGED) result.changed++;
            else result.ioErrors++;
        } catch (RuntimeException failure) {
            result.ioErrors++;
        }
        return null;
    }

    private Stat stat(Directory directory, String child) {
        try { return child == null ? directory.stat() : directory.stat(child); }
        catch (Failure failure) { problem(failure); }
        catch (RuntimeException failure) { result.ioErrors++; }
        return null;
    }

    private void problem(Failure failure) {
        if (failure.problem == Problem.MISSING) result.missing++;
        else if (failure.problem == Problem.UNREADABLE) result.unreadable++;
        else if (failure.problem == Problem.CHANGED) result.changed++;
        else result.ioErrors++;
    }

    private void visit(File root, Directory parent, String name, String relative, Stat before, int depth) {
        if (exhausted()) return;
        if (depth > 128 || before.size < 0 || before.blocks < 0 || before.blocks > Long.MAX_VALUE / 512) {
            result.invalid++;
            return;
        }
        result.entries++;
        Bucket bucket = bucket(relative, before.kind);
        Counted previous = counted.get(before.identity());
        if (previous != null) {
            result.aliases++;
            if (!previous.stat.same(before)) result.changed++;
            if (before.kind == Kind.DIRECTORY) { result.cycles++; return; }
            // An inode shared with another store cannot be assigned to just the named store.
            if (previous.bucket != bucket) {
                if (previous.bucket != Bucket.REMAINING_PRIVATE) {
                    add(result.buckets.get(previous.bucket), previous.stat, -1);
                    previous.bucket = Bucket.REMAINING_PRIVATE;
                    add(result.buckets.get(previous.bucket), previous.stat, 1);
                }
                result.crossBucketAliases++;
            }
            return;
        }
        counted.put(before.identity(), new Counted(before, bucket));
        add(result.buckets.get(bucket), before, 1);
        if (before.kind == Kind.DIRECTORY) {
            try (Directory anchor = parent == null ? fs.openDirectory(root) : parent.openDirectory(name)) {
                // Opening refuses links. Verify the held inode before any enumeration.
                Stat opened = stat(anchor, null);
                if (opened == null) return;
                if (!before.same(opened)) { result.changed++; return; }
                String[] children = anchor.children();
                if (children == null) { result.unreadable++; return; }
                Stat listed = stat(anchor, null);
                if (listed == null) return;
                if (!before.same(listed)) { result.changed++; return; }
                Arrays.sort(children);
                for (String childName : children) {
                    if (exhausted()) break;
                    if (!validChild(childName)) {
                        result.invalid++;
                        continue;
                    }
                    Stat parentStat = stat(anchor, null);
                    if (parentStat == null) break;
                    if (!before.same(parentStat)) { result.changed++; break; }
                    Stat childStat = stat(anchor, childName);
                    if (childStat != null) visit(null, anchor, childName,
                            relative.isEmpty() ? childName : relative + "/" + childName, childStat, depth + 1);
                }
                Stat after = stat(anchor, null);
                if (after != null && !before.same(after)) result.changed++;
            } catch (Failure failure) { problem(failure); }
            catch (RuntimeException failure) { result.ioErrors++; }
        } else {
            Stat after = stat(parent, name);
            if (after != null && !before.same(after)) result.changed++;
        }
    }

    static boolean validChild(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private void add(Counts counts, Stat stat, int sign) {
        if (stat.kind == Kind.FILE) {
            counts.files += sign;
            counts.logicalBytes = sum(counts.logicalBytes, sign * stat.size);
        } else if (stat.kind == Kind.DIRECTORY) counts.directories += sign;
        else if (stat.kind == Kind.SYMLINK) counts.symlinks += sign;
        else counts.other += sign;
        counts.allocatedBytes = sum(counts.allocatedBytes, sign * stat.blocks * 512);
    }

    private long sum(long current, long delta) {
        if (delta > 0 && current > Long.MAX_VALUE - delta) { result.invalid++; return Long.MAX_VALUE; }
        return current + delta;
    }

    /** Exact source-owned paths and standard AtomicFile/SQLite/SharedPreferences sidecars only. */
    static Bucket bucket(String path, Kind kind) {
        if (path.startsWith("cache/hushfeed-media/")
                || (path.equals("cache/hushfeed-media") && kind == Kind.DIRECTORY)) return Bucket.MEDIA_TEMP;
        if (path.startsWith("no_backup/hushfeed-media/")
                || (path.equals("no_backup/hushfeed-media") && kind == Kind.DIRECTORY)) return Bucket.PUBLICATION_JOURNAL;
        if (kind != Kind.FILE) return Bucket.REMAINING_PRIVATE;
        // SavedVideoArchive.DATABASE_NAME and SeenVideoHistory.DATABASE_NAME.
        if (database(path, "hushfeed-saved-videos.db")) return Bucket.SAVED_VIDEO_ARCHIVE;
        if (database(path, "seen_videos.db")) return Bucket.SEEN_HISTORY;
        if (atomic(path, "hushfeed-save-records.json")) return Bucket.SAVE_RECORDS;
        if (atomic(path, "hushfeed-settings-undo.json") || atomic(path, "metra-settings-undo.json")
                || atomic(path, "hushfeed-settings-operation.json")
                || path.equals("files/hushfeed-settings-operation.json.damaged")) return Bucket.SETTINGS;
        if (atomic(path, "feature-gate-lab-undo.json")) return Bucket.FEATURE_LAB;
        if (atomic(path, "morphe_java_crash_report_v1.txt") || atomic(path, "morphe_npth_crash_report_v1.txt"))
            return Bucket.DIAGNOSTICS;
        if (path.equals("files/hushfeed-start") || path.equals("files/hushfeed-start-crashes"))
            return Bucket.STARTUP_GUARD;
        if (preferences(path, "morphe_prefs") || preferences(path, "hushfeed-calm-feed-preset")
                || preferences(path, "hushfeed_release_notes")
                || preferences(path, "hushfeed_device_identity")) return Bucket.SETTINGS;
        if (preferences(path, "morphe_feature_gate_lab")) return Bucket.FEATURE_LAB;
        return Bucket.REMAINING_PRIVATE;
    }

    private static boolean atomic(String path, String name) {
        String base = "files/" + name;
        return path.equals(base) || path.equals(base + ".bak") || path.equals(base + ".new");
    }
    private static boolean database(String path, String name) {
        String base = "databases/" + name;
        return path.equals(base) || path.equals(base + "-wal") || path.equals(base + "-shm")
                || path.equals(base + "-journal");
    }
    private static boolean preferences(String path, String name) {
        String base = "shared_prefs/" + name + ".xml";
        return path.equals(base) || path.equals(base + ".bak");
    }

    static boolean validToken(String token) {
        return token != null && token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
