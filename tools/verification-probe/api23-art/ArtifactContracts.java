import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.rewriter.DexRewriter;
import com.android.tools.smali.dexlib2.rewriter.Rewriter;
import com.android.tools.smali.dexlib2.rewriter.RewriterModule;
import com.android.tools.smali.dexlib2.rewriter.Rewriters;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Corrupt actual helper inputs and call the same artifact verifier used by the build. */
public final class ArtifactContracts {
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("<APK> <payload DEX> <shared DEX> <vendor DEX> <new test directory>");
        Path apk = Path.of(args[0]), payload = Path.of(args[1]), shared = Path.of(args[2]), slice = Path.of(args[3]), output = Path.of(args[4]);
        Files.createDirectory(output);
        VendorSlice.main(new String[]{"verify-apk", apk.toString(), payload.toString(), shared.toString(), slice.toString()}); checks++;
        for (String entry : Arrays.asList("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex")) {
            Path missing = rewrite(apk, output.resolve("missing-" + entry + ".apk"), entry, null, false);
            reject(() -> VendorSlice.main(new String[]{"verify-apk", missing.toString(), payload.toString(), shared.toString(), slice.toString()}));
        }
        Path wrongPayload = rewrite(apk, output.resolve("wrong-payload.apk"), "classes2.dex", Files.readAllBytes(slice), false);
        reject(() -> VendorSlice.main(new String[]{"verify-apk", wrongPayload.toString(), payload.toString(), shared.toString(), slice.toString()}));
        Path wrongShared = rewrite(apk, output.resolve("wrong-shared.apk"), "classes3.dex", Files.readAllBytes(payload), false);
        reject(() -> VendorSlice.main(new String[]{"verify-apk", wrongShared.toString(), payload.toString(), shared.toString(), slice.toString()}));
        Path wrongSlice = rewrite(apk, output.resolve("wrong-vendor.apk"), "classes4.dex", Files.readAllBytes(payload), false);
        reject(() -> VendorSlice.main(new String[]{"verify-apk", wrongSlice.toString(), payload.toString(), shared.toString(), slice.toString()}));
        Path helperSubstitute = rewrite(apk, output.resolve("helper-substitute.apk"), "classes.dex", Files.readAllBytes(payload), false);
        reject(() -> VendorSlice.main(new String[]{"verify-apk", helperSubstitute.toString(), payload.toString(), shared.toString(), slice.toString()}));
        Path extra = rewrite(apk, output.resolve("extra-dex.apk"), "classes5.dex", Files.readAllBytes(slice), true);
        reject(() -> VendorSlice.main(new String[]{"verify-apk", extra.toString(), payload.toString(), shared.toString(), slice.toString()}));
        MethodReference[] invalidControls = {
                // This concrete Map implementation already supports the operation on API23.
                new ImmutableMethodReference("Ljava/util/concurrent/ConcurrentHashMap;", "remove", Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;"), "Z"),
                new ImmutableMethodReference("Ljava/util/Map;", "remove", Arrays.asList("Ljava/lang/Object;", "Ljava/lang/String;"), "Z"),
                new ImmutableMethodReference("Ljava/util/ArrayList;", "sort", Arrays.asList("Ljava/util/Comparator;"), "V"),
                new ImmutableMethodReference("Ljava/util/List;", "sort", Arrays.asList("Ljava/util/Comparator;"), "Z"),
                // The observed Android6 implementation resolves this old negative control.
                new ImmutableMethodReference("Ljava/util/concurrent/ConcurrentHashMap;", "newKeySet", java.util.Collections.emptyList(), "Ljava/util/concurrent/ConcurrentHashMap$KeySetView;")};
        for (int i = 0; i < invalidControls.length; i++) {
            byte[] mutated = mutateControl(apk, i < 2 ? "Ljava/util/Map;" : "Ljava/util/List;", i < 2 ? "remove" : "sort", invalidControls[i]);
            Path invalid = rewrite(apk, output.resolve("rewritten-control-" + i + ".apk"), "classes.dex", mutated, false);
            reject(() -> VendorSlice.main(new String[]{"verify-apk", invalid.toString(), payload.toString(), shared.toString(), slice.toString()}));
        }
        Path shortPayload = output.resolve("truncated.dex"); Files.write(shortPayload, Arrays.copyOf(Files.readAllBytes(payload), 80));
        reject(() -> VendorSlice.main(new String[]{"payload", shortPayload.toString(), shared.toString(), output.resolve("short.json").toString()}));
        reject(() -> VendorSlice.main(new String[]{"payload", slice.toString(), shared.toString(), output.resolve("vendor-as-payload.json").toString()}));
        Path existing = output.resolve("existing.json"); Files.writeString(existing, "preserve this receipt");
        reject(() -> VendorSlice.main(new String[]{"payload", payload.toString(), shared.toString(), existing.toString()}));
        if (!Files.readString(existing).equals("preserve this receipt")) throw new AssertionError("pre-existing output changed"); checks++;
        System.out.println("[api23-art] " + checks + " artifact contracts passed");
    }
    private static byte[] mutateControl(Path apk, String owner, String name, MethodReference replacement) throws Exception {
        DexBackedDexFile input;
        try (ZipFile zip = new ZipFile(apk.toFile()); InputStream stream = zip.getInputStream(zip.getEntry("classes.dex"))) {
            input = new DexBackedDexFile(Opcodes.forApi(23), ByteBuffer.wrap(stream.readNBytes(16 * 1024 * 1024 + 1)));
        }
        int[] changed = {0};
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<MethodReference> getMethodReferenceRewriter(Rewriters rewriters) {
                return reference -> {
                    if (!reference.getDefiningClass().equals(owner) || !reference.getName().equals(name)) return reference;
                    changed[0]++;
                    return replacement;
                };
            }
        });
        DexFile rewritten = rewriter.getDexFileRewriter().rewrite(input);
        MemoryDataStore output = new MemoryDataStore();
        try {
            DexPool pool = new DexPool(Opcodes.forApi(23));
            for (ClassDef type : rewritten.getClasses()) pool.internClass(type);
            pool.writeTo(output);
            if (changed[0] == 0) throw new AssertionError("actual helper control was not mutated");
            return output.getData();
        } finally { output.close(); }
    }
    private static Path rewrite(Path source, Path output, String replace, byte[] bytes, boolean append) throws Exception {
        try (ZipFile zip = new ZipFile(source.toFile()); ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            for (java.util.Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements();) {
                ZipEntry entry = all.nextElement();
                if (entry.getName().equals(replace)) {
                    if (bytes != null) add(target, replace, bytes);
                    continue;
                }
                if (entry.getSize() > 16 * 1024 * 1024) throw new IllegalArgumentException("test input entry too large");
                try (InputStream input = zip.getInputStream(entry)) { add(target, entry.getName(), input.readNBytes(16 * 1024 * 1024)); }
            }
            if (append) add(target, replace, bytes);
        }
        return output;
    }
    private static void add(ZipOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new ZipEntry(name)); output.write(bytes); output.closeEntry();
    }
    private interface Work { void run() throws Exception; }
    private static void reject(Work work) throws Exception {
        try { work.run(); throw new AssertionError("invalid actual artifact was accepted"); }
        catch (Exception expected) { checks++; }
    }
}
