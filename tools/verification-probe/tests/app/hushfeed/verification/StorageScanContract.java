package app.hushfeed.verification;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** JVM metadata fixtures. These do not claim Android filesystem or native size equivalence. */
public final class StorageScanContract {
    private static final String ROOT = "/private-account-secret";
    private static int cases;

    public static void main(String[] args) {
        if (args.length == 1 && "parent-replacement".equals(args[0])) {
            parentReplacementAfterCheckMustNotAttributeOutsideBytes();
            System.out.println("[storage] 1 parent-replacement contract passed");
            return;
        }
        if (args.length == 1 && "parent-replacement-restored".equals(args[0])) {
            restoredParentMustNotTurnOutsideCountsIntoACompletePrivateReport();
            System.out.println("[storage] 1 restored-parent contract passed");
            return;
        }
        exactAllowlistAndSidecars();
        ownedAndRemainingAreDisjoint();
        sparseFilesUseAllocatedBlocks();
        symlinksNeverTraverse();
        sameStoreHardlinksCountOnce();
        crossStoreHardlinksStayUnclassified();
        rootAliasesAndAbsentDeviceStore();
        unreadableDirectoryIsIncomplete();
        disappearingEntryIsIncomplete();
        inconsistentAnchorMetadataStopsDescent();
        parentReplacementAfterCheckMustNotAttributeOutsideBytes();
        restoredParentMustNotTurnOutsideCountsIntoACompletePrivateReport();
        directoryReplacementBeforeOpeningRefusesDescent();
        directoryOpenAndCloseFailuresAreIncomplete();
        growingFileIsIncomplete();
        scanLimitsAreExplicit();
        directoryHandlesCloseWhenBudgetExpires();
        invalidMetadataAndNamesAreIncomplete();
        filenamesAndExceptionSecretsStayInternal();
        tokenCannotInjectOutput();
        System.out.println("[storage] " + cases + " scanner contracts passed");
    }

    private static void exactAllowlistAndSidecars() {
        for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
            equal(StorageScan.Bucket.SAVED_VIDEO_ARCHIVE,
                    StorageScan.bucket("databases/hushfeed-saved-videos.db" + suffix, StorageScan.Kind.FILE));
            equal(StorageScan.Bucket.SEEN_HISTORY,
                    StorageScan.bucket("databases/seen_videos.db" + suffix, StorageScan.Kind.FILE));
        }
        for (String suffix : new String[] {"", ".bak", ".new"}) {
            equal(StorageScan.Bucket.SAVE_RECORDS,
                    StorageScan.bucket("files/hushfeed-save-records.json" + suffix, StorageScan.Kind.FILE));
            for (String name : new String[] {"hushfeed-settings-undo.json", "metra-settings-undo.json",
                    "hushfeed-settings-operation.json"}) {
                equal(StorageScan.Bucket.SETTINGS, StorageScan.bucket("files/" + name + suffix, StorageScan.Kind.FILE));
            }
            equal(StorageScan.Bucket.FEATURE_LAB,
                    StorageScan.bucket("files/feature-gate-lab-undo.json" + suffix, StorageScan.Kind.FILE));
            for (String name : new String[] {"morphe_java_crash_report_v1.txt", "morphe_npth_crash_report_v1.txt"})
                equal(StorageScan.Bucket.DIAGNOSTICS, StorageScan.bucket("files/" + name + suffix, StorageScan.Kind.FILE));
        }
        for (String suffix : new String[] {"", ".bak"}) {
            for (String name : new String[] {"morphe_prefs", "hushfeed-calm-feed-preset", "hushfeed_release_notes",
                    "hushfeed_device_identity"})
                equal(StorageScan.Bucket.SETTINGS,
                        StorageScan.bucket("shared_prefs/" + name + ".xml" + suffix, StorageScan.Kind.FILE));
            equal(StorageScan.Bucket.FEATURE_LAB,
                    StorageScan.bucket("shared_prefs/morphe_feature_gate_lab.xml" + suffix, StorageScan.Kind.FILE));
        }
        equal(StorageScan.Bucket.SETTINGS,
                StorageScan.bucket("files/hushfeed-settings-operation.json.damaged", StorageScan.Kind.FILE));
        equal(StorageScan.Bucket.STARTUP_GUARD, StorageScan.bucket("files/hushfeed-start", StorageScan.Kind.FILE));
        equal(StorageScan.Bucket.STARTUP_GUARD, StorageScan.bucket("files/hushfeed-start-crashes", StorageScan.Kind.FILE));
        for (String path : new String[] {"files/seen_videos.db", "files/hushfeed-save-records.json.secret",
                "databases/seen_videos.db-wal-secret", "cache/hushfeed-media-secret/clip",
                "no_backup/hushfeed-media-secret/journal", "shared_prefs/morphe_prefs.xml.new",
                "files/hushfeed-start.bak", "shared_prefs/account-preferences.xml"})
            equal(StorageScan.Bucket.REMAINING_PRIVATE, StorageScan.bucket(path, StorageScan.Kind.FILE));
        equal(StorageScan.Bucket.REMAINING_PRIVATE,
                StorageScan.bucket("files/hushfeed-save-records.json", StorageScan.Kind.DIRECTORY));
        cases++;
    }

    private static void ownedAndRemainingAreDisjoint() {
        Files fs = new Files();
        fs.file("cache/hushfeed-media/live-temp.bin", 5, 1);
        fs.file("no_backup/hushfeed-media/pending-uris.tsv.bak", 7, 2);
        fs.file("databases/hushfeed-saved-videos.db-wal", 11, 3);
        fs.file("files/hushfeed-save-records.json.new", 13, 4);
        fs.file("files/account-secret.db", 17, 5);
        StorageScan.Result result = scan(fs);
        yes(result.complete(), "complete metadata tree was incomplete");
        equal(5L, result.buckets.get(StorageScan.Bucket.MEDIA_TEMP).logicalBytes);
        equal(7L, result.buckets.get(StorageScan.Bucket.PUBLICATION_JOURNAL).logicalBytes);
        equal(11L, result.buckets.get(StorageScan.Bucket.SAVED_VIDEO_ARCHIVE).logicalBytes);
        equal(13L, result.buckets.get(StorageScan.Bucket.SAVE_RECORDS).logicalBytes);
        equal(17L, result.buckets.get(StorageScan.Bucket.REMAINING_PRIVATE).logicalBytes);
        equal(53L, logical(result));
        equal(15L * 512L, allocated(result));
        equal(53L, result.total(false).logicalBytes);
        equal(36L, result.total(true).logicalBytes);
        equal(result.total(false).logicalBytes,
                result.total(true).logicalBytes + result.buckets.get(StorageScan.Bucket.REMAINING_PRIVATE).logicalBytes);
        cases++;
    }

    private static void sparseFilesUseAllocatedBlocks() {
        Files fs = new Files();
        fs.file("files/sparse-secret", 2L * 1024 * 1024 * 1024, 8);
        StorageScan.Result result = scan(fs);
        equal(2L * 1024 * 1024 * 1024, logical(result));
        equal(4096L, allocated(result));
        cases++;
    }

    private static void symlinksNeverTraverse() {
        Files fs = new Files();
        fs.put("cache/hushfeed-media/link-secret", StorageScan.Kind.SYMLINK, 9999, 0);
        fs.node("cache/hushfeed-media/link-secret").children = new String[] {"outside-secret"};
        fs.put("cache/hushfeed-media/ordinary", StorageScan.Kind.FILE, 23, 1);
        StorageScan.Result result = scan(fs);
        equal(23L, logical(result));
        equal(1L, result.buckets.get(StorageScan.Bucket.MEDIA_TEMP).symlinks);
        equal(0, fs.node("cache/hushfeed-media/link-secret").listCalls);
        yes(result.complete(), "a link target was incorrectly required for an internal metadata scan");
        cases++;
    }

    private static void sameStoreHardlinksCountOnce() {
        Files fs = new Files();
        Node first = fs.file("cache/hushfeed-media/a", 19, 2);
        fs.file("cache/hushfeed-media/b", 19, 2).stat = first.stat;
        StorageScan.Result result = scan(fs);
        equal(1L, result.buckets.get(StorageScan.Bucket.MEDIA_TEMP).files);
        equal(19L, logical(result));
        equal(1024L, allocated(result));
        equal(1L, result.aliases);
        cases++;
    }

    private static void crossStoreHardlinksStayUnclassified() {
        for (boolean ownedFirst : new boolean[] {true, false}) {
            Files fs = new Files();
            Node owned = fs.file("databases/hushfeed-saved-videos.db", 31, 3);
            fs.file(ownedFirst ? "files/private-secret" : "a-private-secret", 31, 3).stat = owned.stat;
            StorageScan.Result result = scan(fs);
            equal(0L, result.buckets.get(StorageScan.Bucket.SAVED_VIDEO_ARCHIVE).logicalBytes);
            equal(31L, result.buckets.get(StorageScan.Bucket.REMAINING_PRIVATE).logicalBytes);
            equal(31L, logical(result));
            equal(1536L, allocated(result));
            equal(1L, result.crossBucketAliases);
        }
        cases++;
    }

    private static void rootAliasesAndAbsentDeviceStore() {
        Files fs = new Files();
        fs.file("files/private-secret", 7, 1);
        StorageScan.Result result = new StorageScan(fs, 1000, 1000)
                .scan(new File(ROOT), new File(ROOT), new File("/absent-device-secret"));
        equal(1, result.roots);
        equal(1, result.absentRoots);
        equal(7L, logical(result));
        yes(result.complete(), "an absent optional DE root made an observed CE tree incomplete");
        fs.assertClosed();
        yes(!new StorageScan(new Files(), 1000, 1000).scan(new File("/no-root")).complete(),
                "no observed root was marked complete");
        cases++;
    }

    private static void unreadableDirectoryIsIncomplete() {
        Files fs = new Files();
        fs.put("files/private-secret", StorageScan.Kind.DIRECTORY, 0, 0).listFailure = StorageScan.Problem.UNREADABLE;
        StorageScan.Result result = scan(fs);
        equal(1L, result.unreadable);
        yes(!result.complete(), "unreadable directory was marked complete");
        cases++;
    }

    private static void disappearingEntryIsIncomplete() {
        Files fs = new Files();
        fs.file("files/disappearing-secret", 100, 1).statFailure = StorageScan.Problem.MISSING;
        StorageScan.Result result = scan(fs);
        equal(1L, result.missing);
        equal(0L, logical(result));
        yes(!result.complete(), "disappearing file was marked complete");
        cases++;
    }

    private static void inconsistentAnchorMetadataStopsDescent() {
        Files fs = new Files();
        fs.file("files/private-secret", 100, 1);
        Node directory = fs.node("files");
        directory.afterList = new StorageScan.Stat(StorageScan.Kind.SYMLINK, 1, 888, 10, 0, 0, 0);
        StorageScan.Result result = scan(fs);
        equal(1L, result.changed);
        equal(0, fs.node("files/private-secret").statCalls);
        yes(!result.complete(), "replaced directory was followed or marked complete");
        cases++;
    }

    private static void parentReplacementAfterCheckMustNotAttributeOutsideBytes() {
        Files fs = new Files();
        Node child = fs.file("cache/hushfeed-media/private-child", 5, 1);
        Node parent = fs.node("cache/hushfeed-media");
        Node outside = fs.outsideDirectory();
        int[] replaced = {0};
        // Replace the namespace entry after the last parent check before child lstat.
        // A held parent remains the original directory even when its pathname is a link.
        parent.afterStatCall = 5;
        parent.afterStat = () -> {
            fs.node("cache").entries.put("hushfeed-media", fs.link(outside));
            replaced[0]++;
        };
        StorageScan.Result result = scan(fs);
        equal(1, replaced[0]);
        equal(0, fs.outsideLookups);
        equal(0, outside.listCalls);
        equal(2, child.statCalls);
        equal(5L, result.buckets.get(StorageScan.Bucket.MEDIA_TEMP).logicalBytes);
        cases++;
    }

    private static void restoredParentMustNotTurnOutsideCountsIntoACompletePrivateReport() {
        Files fs = new Files();
        Node child = fs.file("cache/hushfeed-media/private-child", 5, 1);
        Node parent = fs.node("cache/hushfeed-media");
        Node outside = fs.outsideDirectory();
        int[] replaced = {0}, restored = {0};
        parent.afterStatCall = 5;
        parent.afterStat = () -> {
            fs.node("cache").entries.put("hushfeed-media", fs.link(outside));
            replaced[0]++;
        };
        // Restore after the second child lookup. The directory metadata is unchanged,
        // so a pathname recheck would miss the race and could certify outside bytes.
        Runnable restore = () -> {
            fs.node("cache").entries.put("hushfeed-media", parent);
            restored[0]++;
        };
        child.afterStatCall = 2;
        child.afterStat = restore;
        Node outsideChild = outside.entries.get("private-child");
        outsideChild.afterStatCall = 2;
        outsideChild.afterStat = restore;
        StorageScan.Result result = scan(fs);
        long countedBytes = result.buckets.get(StorageScan.Bucket.MEDIA_TEMP).logicalBytes;
        equal(1, replaced[0]);
        equal(1, restored[0]);
        equal(0, fs.outsideLookups);
        equal(5L, countedBytes);
        yes(!result.complete() || countedBytes < 65536,
                "restored parent concealed outside counts: bytes=" + countedBytes
                        + " changed=" + result.changed + " complete=" + result.complete());
        cases++;
    }

    private static void directoryReplacementBeforeOpeningRefusesDescent() {
        for (StorageScan.Kind replacement : new StorageScan.Kind[] {
                StorageScan.Kind.DIRECTORY, StorageScan.Kind.SYMLINK, StorageScan.Kind.FILE}) {
            Files fs = new Files();
            fs.file("files/private-secret", 100, 1);
            Node original = fs.node("files");
            Node replaced = new Node(new StorageScan.Stat(replacement, 2, 777, 0, 0, 0, 0));
            original.afterStatCall = 1;
            original.afterStat = () -> fs.node("").entries.put("files", replaced);
            StorageScan.Result result = scan(fs);
            equal(1L, result.changed);
            equal(0, replaced.listCalls);
            equal(0, fs.node("files/private-secret").statCalls);
            yes(!result.complete(), "a replacement directory was enumerated before its identity was checked");
        }
        cases++;
    }

    private static void directoryOpenAndCloseFailuresAreIncomplete() {
        for (boolean closeFailure : new boolean[] {false, true}) {
            Files fs = new Files();
            fs.file("files/private-secret", 100, 1);
            Node directory = fs.node("files");
            if (closeFailure) directory.closeFailure = StorageScan.Problem.IO;
            else directory.openFailure = StorageScan.Problem.UNREADABLE;
            StorageScan.Result result = scan(fs);
            if (closeFailure) equal(1L, result.ioErrors);
            else {
                equal(1L, result.unreadable);
                equal(0, directory.listCalls);
            }
            yes(!result.complete(), "directory handle failure was marked complete");
        }
        cases++;
    }

    private static void growingFileIsIncomplete() {
        Files fs = new Files();
        Node file = fs.file("files/growing-secret", 10, 1);
        file.afterFirstStat = new StorageScan.Stat(StorageScan.Kind.FILE, file.stat.device, file.stat.inode, 12, 2, 1, 1);
        StorageScan.Result result = scan(fs);
        equal(1L, result.changed);
        yes(!result.complete(), "changing file was marked complete");
        cases++;
    }

    private static void scanLimitsAreExplicit() {
        Files fs = new Files();
        fs.file("a", 1, 1);
        fs.file("b", 2, 2);
        StorageScan.Result result = new StorageScan(fs, 2, 1000).scan(new File(ROOT));
        yes(result.limited && !result.complete(), "entry bound silently produced a complete report");
        fs.assertClosed();
        fs = new Files();
        fs.clockStep = 2;
        result = new StorageScan(fs, 1000, 1).scan(new File(ROOT));
        yes(result.limited && !result.complete(), "time bound silently produced a complete report");
        fs.assertClosed();
        cases++;
    }

    private static void directoryHandlesCloseWhenBudgetExpires() {
        Files fs = new Files();
        Node child = fs.file("files/private-secret", 100, 1);
        child.afterStatCall = 1;
        child.afterStat = () -> fs.clock = 1001;
        StorageScan.Result result = new StorageScan(fs, 1000, 1000).scan(new File(ROOT));
        yes(fs.opened > 0, "time limit did not exercise a held directory");
        equal(1, child.statCalls);
        equal(0L, logical(result));
        yes(result.limited && !result.complete(), "expired directory scan was marked complete");
        fs.assertClosed();
        cases++;
    }

    private static void invalidMetadataAndNamesAreIncomplete() {
        Files fs = new Files();
        fs.file("negative-secret", 5, -1);
        StorageScan.Result result = scan(fs);
        yes(result.invalid == 1 && !result.complete(), "negative allocation was accepted");
        fs = new Files();
        fs.file("huge-secret", Long.MAX_VALUE, 0);
        fs.file("positive-secret", 1, 0);
        result = scan(fs);
        yes(result.invalid > 0 && !result.complete() && logical(result) >= 0, "byte sum overflow was accepted");
        fs = new Files();
        fs.node("").children = new String[] {"../outside-secret", "sub/secret", ".."};
        result = scan(fs);
        equal(3L, result.invalid);
        yes(!result.complete(), "escaping child names were accepted");
        cases++;
    }

    private static void filenamesAndExceptionSecretsStayInternal() {
        Files fs = new Files();
        fs.file("files/username-person-token-secret", 37, 2).runtimeFailure = true;
        StorageScan.Result result = scan(fs);
        StringBuilder output = new StringBuilder(result.fields());
        for (StorageScan.Bucket bucket : StorageScan.Bucket.values())
            output.append(bucket.name()).append(result.buckets.get(bucket).fields());
        for (String secret : new String[] {ROOT, "username", "person", "token-secret", "https://", "999888777666", "RuntimeException"})
            yes(!output.toString().contains(secret), "private value reached aggregate output");
        equal(1L, result.ioErrors);
        yes(!result.complete(), "runtime metadata failure was marked complete");
        cases++;
    }

    private static void tokenCannotInjectOutput() {
        yes(StorageScan.validToken("a1e27665-649a-4eee-9ce0-72f73f0962ac"), "safe ownership token refused");
        for (String token : new String[] {null, "", "person@example.invalid", "../secret",
                "a1e27665-649a-4eee-9ce0-72f73f0962ac\nsecret=true"})
            yes(!StorageScan.validToken(token), "unsafe ownership token accepted");
        cases++;
    }

    private static StorageScan.Result scan(Files fs) {
        StorageScan.Result result = new StorageScan(fs, 1000, 1000).scan(new File(ROOT));
        fs.assertClosed();
        return result;
    }
    private static long logical(StorageScan.Result result) {
        long bytes = 0; for (StorageScan.Counts counts : result.buckets.values()) bytes += counts.logicalBytes; return bytes;
    }
    private static long allocated(StorageScan.Result result) {
        long bytes = 0; for (StorageScan.Counts counts : result.buckets.values()) bytes += counts.allocatedBytes; return bytes;
    }
    private static void yes(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) {
        yes(expected.equals(actual), "aggregate mismatch expected=" + expected + " actual=" + actual);
    }

    private static final class Node {
        StorageScan.Stat stat, afterList, afterFirstStat;
        final Map<String, Node> entries = new LinkedHashMap<>();
        Node linkTarget;
        String[] children;
        StorageScan.Problem statFailure, listFailure, openFailure, closeFailure;
        boolean runtimeFailure, outside;
        int statCalls, listCalls;
        int afterStatCall;
        Runnable afterStat;
        Node(StorageScan.Stat stat) { this.stat = stat; }
    }

    private static final class Files implements StorageScan.FileSystem {
        final Map<String, Node> nodes = new LinkedHashMap<>();
        int opened, closed, active, outsideLookups;
        long inode = 999888777666L, clock, clockStep;
        Files() { put("", StorageScan.Kind.DIRECTORY, 0, 0); }
        Node file(String relative, long size, long blocks) { return put(relative, StorageScan.Kind.FILE, size, blocks); }
        Node put(String relative, StorageScan.Kind kind, long size, long blocks) {
            int slash = relative.lastIndexOf('/');
            if (slash > 0 && !nodes.containsKey(ROOT + "/" + relative.substring(0, slash)))
                put(relative.substring(0, slash), StorageScan.Kind.DIRECTORY, 0, 0);
            Node node = new Node(new StorageScan.Stat(kind, 1, inode++, size, blocks, 0, 0));
            nodes.put(ROOT + (relative.isEmpty() ? "" : "/" + relative), node);
            if (!relative.isEmpty()) node(slash < 0 ? "" : relative.substring(0, slash))
                    .entries.put(slash < 0 ? relative : relative.substring(slash + 1), node);
            return node;
        }
        Node node(String relative) { return nodes.get(ROOT + (relative.isEmpty() ? "" : "/" + relative)); }
        private static String path(File file) { return file.getPath().replace('\\', '/'); }
        @Override public StorageScan.Stat stat(File file) throws StorageScan.Failure {
            return observe(resolve(file));
        }
        private Node resolve(File file) throws StorageScan.Failure {
            String path = path(file);
            if (!path.equals(ROOT) && !path.startsWith(ROOT + "/"))
                throw new StorageScan.Failure(StorageScan.Problem.MISSING);
            Node node = node("");
            if (path.equals(ROOT)) return node;
            for (String child : path.substring(ROOT.length() + 1).split("/")) {
                if (node != null && node.stat.kind == StorageScan.Kind.SYMLINK) node = node.linkTarget;
                if (node == null || node.stat.kind != StorageScan.Kind.DIRECTORY)
                    throw new StorageScan.Failure(StorageScan.Problem.MISSING);
                node = node.entries.get(child);
            }
            if (node == null) throw new StorageScan.Failure(StorageScan.Problem.MISSING);
            return node;
        }
        private StorageScan.Stat observe(Node node) throws StorageScan.Failure {
            if (node == null) throw new StorageScan.Failure(StorageScan.Problem.MISSING);
            node.statCalls++;
            if (node.outside) outsideLookups++;
            if (node.runtimeFailure) throw new RuntimeException("https://account-secret/username-person-token-secret");
            if (node.statFailure != null) throw new StorageScan.Failure(node.statFailure);
            StorageScan.Stat observed = node.afterFirstStat != null && node.statCalls > 1 ? node.afterFirstStat : node.stat;
            if (node.afterStat != null && node.statCalls == node.afterStatCall) node.afterStat.run();
            return observed;
        }
        @Override public StorageScan.Directory openDirectory(File file) throws StorageScan.Failure {
            return open(resolve(file));
        }
        private StorageScan.Directory open(Node node) throws StorageScan.Failure {
            if (node == null) throw new StorageScan.Failure(StorageScan.Problem.MISSING);
            if (node.openFailure != null) throw new StorageScan.Failure(node.openFailure);
            if (node.stat.kind == StorageScan.Kind.SYMLINK)
                throw new StorageScan.Failure(StorageScan.Problem.CHANGED);
            if (observe(node).kind != StorageScan.Kind.DIRECTORY)
                throw new StorageScan.Failure(StorageScan.Problem.CHANGED);
            opened++;
            active++;
            return new StorageScan.Directory() {
                private boolean released;
                @Override public StorageScan.Stat stat() throws StorageScan.Failure { return observe(node); }
                @Override public StorageScan.Stat stat(String child) throws StorageScan.Failure {
                    return observe(node.entries.get(child));
                }
                @Override public String[] children() throws StorageScan.Failure {
                    node.listCalls++;
                    if (node.listFailure != null) throw new StorageScan.Failure(node.listFailure);
                    yes(node.stat.kind == StorageScan.Kind.DIRECTORY, "a symlink or ordinary file was listed");
                    String[] result = node.children == null ? node.entries.keySet().toArray(new String[0]) : node.children;
                    if (node.afterList != null) node.stat = node.afterList;
                    return result;
                }
                @Override public StorageScan.Directory openDirectory(String child) throws StorageScan.Failure {
                    return open(node.entries.get(child));
                }
                @Override public void close() throws StorageScan.Failure {
                    yes(!released, "a directory handle was closed twice");
                    released = true;
                    active--;
                    closed++;
                    if (node.closeFailure != null) throw new StorageScan.Failure(node.closeFailure);
                }
            };
        }
        Node outsideDirectory() {
            Node directory = new Node(new StorageScan.Stat(StorageScan.Kind.DIRECTORY, 2, 776, 0, 0, 0, 0));
            Node child = new Node(new StorageScan.Stat(StorageScan.Kind.FILE, 2, 777, 65536, 128, 0, 0));
            directory.outside = child.outside = true;
            directory.entries.put("private-child", child);
            return directory;
        }
        Node link(Node target) {
            Node link = new Node(new StorageScan.Stat(StorageScan.Kind.SYMLINK, 1, 888, 10, 0, 0, 0));
            link.linkTarget = target;
            return link;
        }
        void assertClosed() {
            equal(0, active);
            equal(opened, closed);
        }
        @Override public long elapsedMillis() { long value = clock; clock += clockStep; return value; }
    }
}
