import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Extracts intact vendor model definitions, never replacement implementations. */
public final class VendorSlice {
    private static final List<String> SEEDS = Arrays.asList(
            "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
            "Lcom/ss/android/ugc/aweme/feed/model/Aweme;");

    public static void main(String[] args) throws Exception {
        if (args.length == 5 && args[0].equals("verify-apk")) {
            verifyApk(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
            return;
        }
        if (args.length == 4 && args[0].equals("payload")) {
            payload(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            return;
        }
        if (!(args.length == 2 && args[0].equals("inspect"))
                && !(args.length == 4 && args[0].equals("slice"))) {
            throw new IllegalArgumentException("inspect <vendor APK> | slice <vendor APK> <new DEX> <new receipt> | payload <TikTok DEX> <shared DEX> <new receipt>");
        }
        Set<String> needed = new LinkedHashSet<>(SEEDS);
        Map<String, ClassDef> found = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(args[1])) {
            List<ZipEntry> entries = new ArrayList<>();
            long total = 0;
            for (java.util.Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements();) {
                ZipEntry entry = all.nextElement();
                if (!entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) continue;
                if (entry.getSize() < 112 || entry.getSize() > 32L * 1024 * 1024) {
                    throw new IllegalArgumentException("vendor DEX size is outside the slice limit");
                }
                entries.add(entry);
                total += entry.getSize();
            }
            if (entries.isEmpty() || entries.size() > 128 || total > 512L * 1024 * 1024) {
                throw new IllegalArgumentException("vendor DEX set exceeds the bounded slice limit");
            }
            for (int pass = 0; pass < 8; pass++) {
                for (ZipEntry entry : entries) {
                    try (InputStream input = new BufferedInputStream(zip.getInputStream(entry))) {
                        byte[] bytes = input.readNBytes((int) entry.getSize() + 1);
                        if (bytes.length != entry.getSize()) throw new IllegalArgumentException("vendor DEX inflated size differs from its ZIP header");
                        DexBackedDexFile dex = new DexBackedDexFile(Opcodes.forApi(23), ByteBuffer.wrap(bytes));
                        for (ClassDef owner : dex.getClasses()) {
                            if (!needed.contains(owner.getType()) || found.containsKey(owner.getType())) continue;
                            found.put(owner.getType(), owner);
                            add(needed, owner.getSuperclass());
                            for (String parent : owner.getInterfaces()) add(needed, parent);
                            if (needed.size() > 64) throw new IllegalArgumentException("vendor hierarchy is too large for this helper");
                        }
                    }
                }
                if (found.size() == needed.size()) break;
                if (pass == 7) throw new IllegalArgumentException("vendor hierarchy could not be resolved: " + needed);
            }
        }
        requireFeedMembers(found.get(SEEDS.get(0)));
        if (args[0].equals("slice")) {
            Path output = Path.of(args[2]), receipt = Path.of(args[3]);
            if (Files.exists(output) || Files.exists(receipt)) throw new IllegalArgumentException("slice output must be fresh");
            byte[] encoded = encoded(found.values());
            if (encoded.length > 4 * 1024 * 1024) throw new IllegalArgumentException("vendor slice is too large");
            DexBackedDexFile written = new DexBackedDexFile(Opcodes.forApi(23), ByteBuffer.wrap(encoded));
            StringBuilder classes = new StringBuilder();
            int count = 0;
            for (ClassDef owner : written.getClasses()) {
                byte[] before = encoded(Arrays.asList(found.get(owner.getType())));
                byte[] after = encoded(Arrays.asList(owner));
                if (!Arrays.equals(before, after)) throw new IllegalStateException("vendor class changed during extraction: " + owner.getType());
                if (count++ > 0) classes.append(',');
                classes.append("{\"type\":\"").append(owner.getType()).append("\",\"canonicalSha256\":\"")
                        .append(hex(MessageDigest.getInstance("SHA-256").digest(after))).append("\"}");
            }
            if (count != found.size()) throw new IllegalStateException("vendor slice class count changed");
            String json = "{\"schema\":1,\"scope\":\"intact vendor models and hierarchy; unused dependencies not closed\","
                    + "\"vendorApkSha256\":\"" + hash(Path.of(args[1])) + "\",\"sliceSha256\":\""
                    + hex(MessageDigest.getInstance("SHA-256").digest(encoded)) + "\",\"classCount\":" + count
                    + ",\"classes\":[" + classes + "]}";
            Files.write(output, encoded, StandardOpenOption.CREATE_NEW);
            Files.writeString(receipt, json, StandardOpenOption.CREATE_NEW);
            System.out.println("intact vendor slice classes=" + count + " bytes=" + encoded.length);
            return;
        }
        for (ClassDef owner : found.values()) {
            System.out.println(owner.getType() + " extends " + owner.getSuperclass() + " implements " + owner.getInterfaces());
            if (owner.getType().contains("FeedItemList;")) for (Field field : owner.getFields()) {
                if (Arrays.asList("items", "dataUserId", "fetchType", "hasMore", "cursor", "requestId").contains(field.getName())) {
                    System.out.println("  field " + field + " access=" + field.getAccessFlags());
                }
            }
            for (Method method : owner.getMethods()) {
                if (!(method.getName().equals("<clinit>") || (method.getName().equals("<init>") && method.getParameters().isEmpty()))) continue;
                System.out.println("  " + method);
                if (method.getImplementation() != null) for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (instruction instanceof ReferenceInstruction) {
                        System.out.println("    " + instruction.getOpcode() + " " + ((ReferenceInstruction) instruction).getReference());
                    }
                }
            }
        }
        System.out.println("intact vendor hierarchy classes=" + found.size());
    }

    private static void payload(Path input, Path shared, Path receipt) throws Exception {
        Path sharedSlice = receipt.resolveSibling(receipt.getFileName().toString() + ".shared.dex");
        if (Files.exists(receipt) || Files.exists(sharedSlice)) throw new IllegalArgumentException("payload output must be fresh");
        Map<String, ClassDef> required = new LinkedHashMap<>();
        required.put("Lapp/morphe/extension/tiktok/captions/CaptionStyle;", null);
        required.put("Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;", null);
        required.put("Lapp/morphe/extension/tiktok/settings/Settings;", null);
        required.put("Lapp/morphe/extension/shared/Utils;", null);
        required.put("Lapp/morphe/extension/shared/diagnostics/HookStatus;", null);
        required.put("Lapp/morphe/extension/shared/diagnostics/HookStatus$Snapshot;", null);
        required.put("Lapp/morphe/extension/shared/diagnostics/HookStatus$FamilySnapshot;", null);
        int count = 0;
        Map<String, ClassDef> types = new LinkedHashMap<>();
        Map<String, ClassDef> uniqueShared = new LinkedHashMap<>();
        int duplicates = 0;
        for (Path file : Arrays.asList(input, shared)) {
            if (Files.size(file) < 112 || Files.size(file) > 16 * 1024 * 1024) throw new IllegalArgumentException("payload DEX size outside helper limit");
            DexBackedDexFile dex = new DexBackedDexFile(Opcodes.forApi(23), ByteBuffer.wrap(Files.readAllBytes(file)));
            for (ClassDef owner : dex.getClasses()) {
                count++;
                ClassDef previous = types.putIfAbsent(owner.getType(), owner);
                if (previous != null) {
                    if (!Arrays.equals(encoded(Arrays.asList(previous)), encoded(Arrays.asList(owner)))) {
                        throw new IllegalArgumentException("different class definitions across payload DEX files: " + owner.getType());
                    }
                    duplicates++;
                } else if (file.equals(shared)) uniqueShared.put(owner.getType(), owner);
                if (required.containsKey(owner.getType())) required.put(owner.getType(), owner);
                if (SEEDS.contains(owner.getType())) throw new IllegalArgumentException("payload must not contain substitute vendor models");
            }
        }
        for (Map.Entry<String, ClassDef> entry : required.entrySet()) if (entry.getValue() == null) {
            throw new IllegalArgumentException("payload is missing " + entry.getKey());
        }
        requireMethod(required.get("Lapp/morphe/extension/tiktok/captions/CaptionStyle;"), "layout", "Landroid/text/Layout;", "Landroid/text/Layout;");
        boolean apply = false;
        for (Method method : required.get("Lapp/morphe/extension/tiktok/captions/CaptionStyle;").getMethods()) {
            apply |= method.getName().equals("apply") && method.getParameterTypes().equals(Arrays.asList("Landroid/view/View;"))
                    && method.getReturnType().equals("V") && (method.getAccessFlags() & 8) != 0;
        }
        if (!apply) throw new IllegalArgumentException("missing static CaptionStyle.apply(View) payload member");
        ClassDef feed = required.get("Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;");
        requireMethod(feed, "filterCachedFeedList", SEEDS.get(0), SEEDS.get(0));
        requireMethod(feed, "filterOfflineFeedList", SEEDS.get(0), SEEDS.get(0));
        requireMethod(feed, "filterOnRead", SEEDS.get(0), "V");
        requireMethod(required.get("Lapp/morphe/extension/shared/diagnostics/HookStatus;"), "snapshot",
                java.util.Collections.emptyList(), "Lapp/morphe/extension/shared/diagnostics/HookStatus$Snapshot;");
        requireField(required.get("Lapp/morphe/extension/shared/diagnostics/HookStatus$Snapshot;"), "families", "Ljava/util/List;");
        ClassDef family = required.get("Lapp/morphe/extension/shared/diagnostics/HookStatus$FamilySnapshot;");
        requireField(family, "name", "Ljava/lang/String;");
        requireField(family, "bound", "Ljava/util/List;");
        byte[] sharedBytes = encoded(uniqueShared.values());
        DexBackedDexFile sliced = new DexBackedDexFile(Opcodes.forApi(23), ByteBuffer.wrap(sharedBytes));
        int sharedCount = 0;
        for (ClassDef owner : sliced.getClasses()) {
            if (!Arrays.equals(encoded(Arrays.asList(owner)), encoded(Arrays.asList(uniqueShared.get(owner.getType()))))) {
                throw new IllegalStateException("shared payload definition changed during slicing");
            }
            sharedCount++;
        }
        if (sharedCount != uniqueShared.size()) throw new IllegalStateException("shared slice class count changed");
        String json = "{\"schema\":1,\"payloadSha256\":\"" + hash(input) + "\",\"sharedPayloadSha256\":\""
                + hash(shared) + "\",\"sharedSliceSha256\":\"" + hex(MessageDigest.getInstance("SHA-256").digest(sharedBytes))
                + "\",\"sharedSliceClasses\":" + sharedCount + ",\"identicalSharedDuplicatesOmitted\":" + duplicates
                + ",\"classCount\":" + types.size() + "}";
        Files.write(sharedSlice, sharedBytes, StandardOpenOption.CREATE_NEW);
        Files.writeString(receipt, json, StandardOpenOption.CREATE_NEW);
        System.out.println("production payload required members present; raw classes=" + count + " identical duplicates=" + duplicates
                + " intact shared slice classes=" + sharedCount);
    }

    private static void requireMethod(ClassDef owner, String name, String parameter, String result) {
        requireMethod(owner, name, Arrays.asList(parameter), result);
    }

    private static void requireMethod(ClassDef owner, String name, List<String> parameters, String result) {
        for (Method method : owner.getMethods()) if (method.getName().equals(name)
                && method.getParameterTypes().equals(parameters) && method.getReturnType().equals(result)
                && (method.getAccessFlags() & 9) == 9) return;
        throw new IllegalArgumentException("missing public static payload member " + owner.getType() + "->" + name);
    }

    private static void requireField(ClassDef owner, String name, String type) {
        for (Field field : owner.getFields()) if (field.getName().equals(name) && field.getType().equals(type)
                && (field.getAccessFlags() & 8) == 0) return;
        throw new IllegalArgumentException("missing instance payload field " + owner.getType() + "->" + name);
    }

    private static void requireFeedMembers(ClassDef owner) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("items", "Ljava/util/List;"); expected.put("dataUserId", "Ljava/lang/String;");
        expected.put("fetchType", "I"); expected.put("hasMore", "I"); expected.put("cursor", "J"); expected.put("requestId", "Ljava/lang/String;");
        for (Field field : owner.getFields()) if (field.getType().equals(expected.get(field.getName()))
                && (field.getAccessFlags() & 1) != 0 && (field.getAccessFlags() & 8) == 0) expected.remove(field.getName());
        if (!expected.isEmpty()) throw new IllegalArgumentException("vendor FeedItemList required public fields missing: " + expected);
    }

    private static byte[] encoded(Iterable<? extends ClassDef> classes) throws Exception {
        MemoryDataStore data = new MemoryDataStore();
        try {
            DexPool pool = new DexPool(Opcodes.forApi(23));
            for (ClassDef owner : classes) pool.internClass(owner);
            pool.writeTo(data);
            return data.getData();
        } finally { data.close(); }
    }

    private static void verifyApk(Path apk, Path payload, Path shared, Path slice) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Set<String> names = new LinkedHashSet<>();
            for (java.util.Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements();) {
                String name = all.nextElement().getName();
                if (!names.add(name)) throw new IllegalArgumentException("duplicate APK entry");
                if (name.endsWith(".dex") && !Arrays.asList("classes.dex", "classes2.dex", "classes3.dex", "classes4.dex").contains(name)) {
                    throw new IllegalArgumentException("unexpected APK DEX entry");
                }
            }
            if (!Arrays.equals(read(zip, "classes2.dex"), Files.readAllBytes(payload))
                    || !Arrays.equals(read(zip, "classes3.dex"), Files.readAllBytes(shared))
                    || !Arrays.equals(read(zip, "classes4.dex"), Files.readAllBytes(slice))) {
                throw new IllegalArgumentException("APK payload/vendor slice bytes changed");
            }
            DexBackedDexFile helper = new DexBackedDexFile(Opcodes.forApi(23), ByteBuffer.wrap(read(zip, "classes.dex")));
            boolean map = false, sort = false, caption = false, feed = false;
            for (ClassDef owner : helper.getClasses()) {
                if (!owner.getType().startsWith("Lapp/hushfeed/verification/api23/")) {
                    throw new IllegalArgumentException("helper DEX contains a production/model substitute: " + owner.getType());
                }
                for (Method method : owner.getMethods()) if (method.getImplementation() != null) {
                    for (Instruction instruction : method.getImplementation().getInstructions()) if (instruction instanceof ReferenceInstruction) {
                        Object reference = ((ReferenceInstruction) instruction).getReference();
                        if (!(reference instanceof MethodReference)) continue;
                        MethodReference call = (MethodReference) reference;
                        if (owner.getType().equals("Lapp/hushfeed/verification/api23/Api24NegativeControl;")
                                && method.getParameterTypes().isEmpty() && method.getReturnType().equals("V")
                                && (instruction.getOpcode() == Opcode.INVOKE_INTERFACE || instruction.getOpcode() == Opcode.INVOKE_INTERFACE_RANGE)) {
                            map |= method.getName().equals("mapDefault") && call.getDefiningClass().equals("Ljava/util/Map;")
                                    && call.getName().equals("remove") && call.getReturnType().equals("Z")
                                    && call.getParameterTypes().equals(Arrays.asList("Ljava/lang/Object;", "Ljava/lang/Object;"));
                            sort |= method.getName().equals("listSort") && call.getDefiningClass().equals("Ljava/util/List;")
                                    && call.getName().equals("sort") && call.getReturnType().equals("V")
                                    && call.getParameterTypes().equals(Arrays.asList("Ljava/util/Comparator;"));
                        }
                        caption |= instruction.getOpcode() == Opcode.INVOKE_STATIC
                                && call.getDefiningClass().equals("Lapp/morphe/extension/tiktok/captions/CaptionStyle;") && call.getName().equals("layout")
                                && call.getReturnType().equals("Landroid/text/Layout;") && call.getParameterTypes().equals(Arrays.asList("Landroid/text/Layout;"));
                        feed |= instruction.getOpcode() == Opcode.INVOKE_STATIC
                                && call.getDefiningClass().equals("Lapp/morphe/extension/tiktok/feedfilter/FeedItemsFilter;") && call.getName().equals("filterOfflineFeedList")
                                && call.getReturnType().equals(SEEDS.get(0)) && call.getParameterTypes().equals(Arrays.asList(SEEDS.get(0)));
                    }
                }
            }
            if (!map || !sort || !caption || !feed) throw new IllegalArgumentException("actual payload calls or API24 native negative controls were rewritten/missing");
            System.out.println("APK preserves byte-identical payload/slice, actual production calls and API24 negative controls");
        }
    }

    private static byte[] read(ZipFile zip, String name) throws Exception {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.getSize() < 112 || entry.getSize() > 16L * 1024 * 1024) {
            throw new IllegalArgumentException("missing or oversized APK DEX: " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) { return input.readNBytes(16 * 1024 * 1024 + 1); }
    }

    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] block = new byte[65536];
        try (InputStream input = Files.newInputStream(path)) {
            for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }

    private static void add(Set<String> needed, String type) {
        if (type != null && !type.startsWith("Ljava/") && !type.startsWith("Landroid/")) needed.add(type);
    }

    private VendorSlice() { }
}
