package app.hushfeed.verification;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class NativeMediaProbeContract {
    private static int checks;
    public static void main(String[] args) throws Exception {
        String token = "media-0123456789abcdef01234567";
        require(NativeMediaProbe.Contract.token(token), "fixed token");
        for (String bad : new String[] {null, "", "media-", token + "x", "../" + token,
                "media-0123456789abcdef0123456A", token + "\n"}) {
            require(!NativeMediaProbe.Contract.token(bad), "reject malformed token");
        }
        require(NativeMediaProbe.Contract.hash("a".repeat(64)), "SHA-256");
        for (String bad : new String[] {null, "", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64)}) {
            require(!NativeMediaProbe.Contract.hash(bad), "reject malformed hash");
        }
        Path temp = Files.createTempDirectory("hushfeed-native-media-contract-");
        try {
            File root = temp.toFile().getCanonicalFile();
            require(NativeMediaProbe.Contract.child(root, "silent.mp4").equals(new File(root, "silent.mp4")), "closed component");
            for (String bad : new String[] {null, "", ".", "..", "../video.mp4", "a/b", "a\\b", "/tmp", "a:b", "a\n", ".hidden"}) {
                rejects(() -> NativeMediaProbe.Contract.child(root, bad), IOException.class, "path traversal/authority/control rejected");
            }
            File sub = new File(root, "sub"); require(sub.mkdir(), "owned directory");
            rejects(() -> NativeMediaProbe.Contract.child(new File(sub, ".."), "silent.mp4"), IOException.class,
                    "noncanonical parent rejected");
            require(sub.delete(), "remove only owned empty directory");
        } finally { Files.delete(temp); }
        NativeMediaProbe.Contract.before(99, 100); checks++;
        rejects(() -> NativeMediaProbe.Contract.before(100, 100), IOException.class, "deadline equality rejected");
        rejects(() -> NativeMediaProbe.Contract.before(101, 100), IOException.class, "late native completion rejected");
        require(NativeMediaProbe.Contract.media(1, 0, 90, 2_000_000, 1, 0), "silent rotated video");
        require(NativeMediaProbe.Contract.media(1, 1, 90, 2_021_333, 1, 1), "combined compressed tracks");
        require(NativeMediaProbe.Contract.media(0, 1, 0, 2_000_000, 0, 1), "audio container");
        for (int[] bad : new int[][] {{1,1,90}, {0,0,90}, {2,0,90}, {1,0,0}, {1,0,270}}) {
            require(!NativeMediaProbe.Contract.media(bad[0], bad[1], bad[2], 2_000_000, 1, 0), "wrong silence/rotation/track count rejected");
        }
        require(!NativeMediaProbe.Contract.media(1, 0, 90, 100, 1, 0), "truncated duration rejected");
        require(!NativeMediaProbe.Contract.media(1, 0, 90, 3_000_000, 1, 0), "unexpected duration rejected");
        Method exact = NativeMediaProbe.Contract.method(Bindings.class, "videoOnly", File.class, File.class);
        File one = new File("one"), two = new File("two"); exact.invoke(null, one, two);
        require(Bindings.one == one && Bindings.two == two, "invokes exact private production-shaped signature");
        rejects(() -> NativeMediaProbe.Contract.method(Bindings.class, "videoOnly", String.class, String.class),
                NoSuchMethodException.class, "no string overload guessing");
        rejects(() -> NativeMediaProbe.Contract.method(Bindings.class, "combine", File.class, File.class),
                NoSuchMethodException.class, "combine requires all three File arguments");
        rejects(() -> NativeMediaProbe.Contract.method(Bindings.class, "instance", File.class, File.class),
                NoSuchMethodException.class, "instance substitute rejected");
        rejects(() -> NativeMediaProbe.Contract.method(Bindings.class, "nonVoid", File.class, File.class),
                NoSuchMethodException.class, "nonvoid substitute rejected");
        cleanupContracts();
        receiptContracts();
        System.out.println("[native-media] " + checks + " host boundary contracts passed; ART not exercised");
    }
    private static void receiptContracts() throws Exception {
        Path temp = Files.createTempDirectory("hushfeed-native-media-receipt-").toFile().getCanonicalFile().toPath();
        File file = temp.resolve("receipt.json").toFile();
        byte[] payload = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
        try {
            NativeMediaProbe.Contract.atomic(file, payload, () -> { });
            require(Files.readString(file.toPath()).equals("{\"accepted\":true}"), "atomic receipt finalizes exact bytes");
            rejects(() -> NativeMediaProbe.Contract.atomic(file, payload, () -> { }), IOException.class, "existing receipt preserved");
            require(Files.readString(file.toPath()).equals("{\"accepted\":true}"), "existing receipt unchanged");
            Files.delete(file.toPath());
            for (int late : new int[] {1, 2}) {
                int[] steps = {0};
                rejects(() -> NativeMediaProbe.Contract.atomic(file, payload, () -> {
                    if (++steps[0] == late) NativeMediaProbe.Contract.before(100, 100);
                }), IOException.class, "late close or rename cannot finalize accepted receipt");
                require(!file.exists() && !temp.resolve("receipt.json.tmp").toFile().exists(), "late accepted receipt and owned temporary removed");
            }
            int[] steps = {0};
            rejects(() -> NativeMediaProbe.Contract.atomic(file, payload, () -> {
                if (++steps[0] == 2) {
                    Files.writeString(file.toPath(), "unrelated replacement");
                    throw new IOException("late rename");
                }
            }), IOException.class, "late replacement cannot pass");
            require(Files.readString(file.toPath()).equals("unrelated replacement"), "concurrent replacement preserved");
            Files.delete(file.toPath());
            NativeMediaProbe.Contract.atomic(file, payload, () -> { });
            require(file.isFile(), "retry after late finalization succeeds");
        } finally {
            Files.deleteIfExists(file.toPath()); Files.deleteIfExists(temp.resolve("receipt.json.tmp")); Files.delete(temp);
        }
    }
    private static void cleanupContracts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.cleanup(); require(!Files.exists(fixture.root), "matching owned fixture removed");
        }
        for (String extra : new String[] {"result/unrelated.txt", "unrelated.txt", "result/unrelated-directory"}) {
            try (Fixture fixture = new Fixture()) {
                if (extra.endsWith("directory")) fixture.directory(extra); else fixture.file(extra, "unrelated");
                rejects(fixture::cleanup, IOException.class, "unexpected content blocks cleanup");
                require(fixture.complete() && Files.exists(fixture.root.resolve(extra)), "unexpected and original content preserved");
            }
        }
        for (String changed : new String[] {"manifest.json", "video-90.mp4", "result/silent.mp4"}) {
            try (Fixture fixture = new Fixture()) {
                Files.writeString(fixture.root.resolve(changed), "changed");
                rejects(fixture::cleanup, IOException.class, "changed owned content blocks cleanup");
                require(fixture.complete(), "validation finishes before any deletion");
                require(Files.readString(fixture.root.resolve(changed)).equals("changed"), "changed bytes preserved");
            }
        }
        try (Fixture fixture = new Fixture()) {
            fixture.outputs.remove("silent.mp4");
            rejects(fixture::cleanup, IOException.class, "output without receipt ownership rejected");
            require(fixture.complete(), "unattributed output and inputs preserved");
        }
        try (Fixture fixture = new Fixture()) {
            fixture.outputs.put("unrelated.mp4", "a".repeat(64));
            rejects(fixture::cleanup, IOException.class, "unexpected receipt-owned name rejected");
            require(fixture.complete(), "bad receipt does not delete known content");
        }
        try (Fixture fixture = new Fixture()) {
            fixture.inputs.remove("audio.m4a");
            rejects(fixture::cleanup, IOException.class, "incomplete input ownership rejected");
            require(fixture.complete(), "incomplete ownership does not delete content");
        }
        try (Fixture fixture = new Fixture()) {
            Files.delete(fixture.root.resolve("audio.m4a"));
            Files.delete(fixture.root.resolve("result/silent.mp4"));
            fixture.cleanup(); require(!Files.exists(fixture.root), "partial cleanup resumes without requiring deleted files");
        }
        try (Fixture fixture = new Fixture()) {
            for (String name : NativeMediaProbe.OUTPUTS) Files.delete(fixture.root.resolve("result/" + name));
            Files.delete(fixture.root.resolve("result/owner.json")); Files.delete(fixture.root.resolve("result/receipt.json"));
            Files.delete(fixture.root.resolve("result")); fixture.outputs.clear();
            fixture.cleanup(); require(!Files.exists(fixture.root), "fixture-only cleanup needs no synthetic result marker");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Path base = Files.createTempDirectory("hushfeed-native-media-cleanup-").toFile().getCanonicalFile().toPath();
        final Path root = base.resolve("media-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        final List<Path> owned = new ArrayList<>();
        final Map<String, String> inputs = new HashMap<>(), outputs = new HashMap<>();
        final String manifestHash;
        Fixture() throws Exception {
            Files.createDirectory(root); owned.add(root); directory("result");
            file("manifest.json", "owned manifest"); manifestHash = hash(root.resolve("manifest.json"));
            for (String name : NativeMediaProbe.INPUTS) { file(name, "input:" + name); inputs.put(name, hash(root.resolve(name))); }
            file("result/owner.json", "{}"); file("result/receipt.json", "{}");
            for (String name : NativeMediaProbe.OUTPUTS) { file("result/" + name, "output:" + name); outputs.put(name, hash(root.resolve("result/" + name))); }
        }
        void file(String name, String text) throws Exception { Path file = root.resolve(name); Files.writeString(file, text, StandardCharsets.UTF_8); owned.add(file); }
        void directory(String name) throws Exception { Path dir = root.resolve(name); Files.createDirectory(dir); owned.add(dir); }
        void cleanup() throws Exception { NativeMediaProbe.Contract.cleanup(root.toFile(), manifestHash, inputs, outputs); }
        boolean complete() { for (Path file : owned) if (!Files.exists(file)) return false; return true; }
        public void close() throws Exception {
            // Every path was created here. No traversal or recursive deletion of discovered content.
            for (int i = owned.size() - 1; i >= 0; i--) Files.deleteIfExists(owned.get(i));
            Files.delete(base);
        }
        static String hash(Path file) throws Exception {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            StringBuilder text = new StringBuilder();
            for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return text.toString();
        }
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, Class<? extends Exception> type, String label) throws Exception {
        try { action.run(); throw new AssertionError(label); }
        catch (Exception expected) { require(type.isInstance(expected), label); }
    }
    private static void require(boolean valid, String label) { checks++; if (!valid) throw new AssertionError(label); }
    private static final class Bindings {
        static File one, two;
        private static void videoOnly(File a, File b) { one = a; two = b; }
        @SuppressWarnings("unused") private static void combine(File a, File b, File c) { }
        @SuppressWarnings("unused") private void instance(File a, File b) { }
        @SuppressWarnings("unused") private static int nonVoid(File a, File b) { return 1; }
    }
}
