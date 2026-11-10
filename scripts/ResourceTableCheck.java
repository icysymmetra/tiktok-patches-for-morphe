import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResConfig;
import com.reandroid.arsc.value.ResTableMapEntry;
import com.reandroid.arsc.value.ResValueMap;
import com.reandroid.arsc.value.TableEntry;
import com.reandroid.arsc.value.ValueItem;
import com.reandroid.arsc.value.ValueType;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Holds a patched APK's resource table to the stock table it was built from, so a resource the
 * rebuild lost is named before a phone finds it.
 *
 * <p>A resource patch makes Morphe decode TikTok's whole resource table and build it again. The
 * table holds twelve packages on TikTok 46.2.3 to 47.0.3, one per dynamic feature, and a layout
 * that a rebuild drops from any of them only fails when TikTok inflates it, as
 * {@code Resources$NotFoundException: Resource ID #0x7e03004d} (layout/bj of df_search_biz,
 * upstream #84). Every resource of every stock package has to resolve in the patched table by its
 * id, with the same type and a value in every configuration the stock table gives it; every file
 * a patched value names has to be in the patched archive; and every value the patches changed has
 * to point at something the patched table has. A style keeps its parent, and a file keeps the bytes
 * the stock archive has for it, since no patch writes either, with one exception: Custom launcher
 * icon restyles the adaptive icon XML that the manifest's icon and round icon name, so a changed
 * XML file of one of those ids is reported, not failed. A failure names the id and exits 1.
 *
 * <p>What the patches changed is reported, not failed: each rewritten value with its old and new
 * value (the AMOLED dark theme's palette and sheet colors), each entry the rebuild renamed (it
 * still resolves by id; a lookup by its old name finds the other entry of that name, or nothing),
 * and each added one.
 *
 * <p>Usage: {@code java -cp <morphe desktop jar> ResourceTableCheck.java <stock.apk> <patched.apk>
 * [report.txt]}. The report file gets every line; the console gets the counts and the first lines
 * of each list.
 */
public final class ResourceTableCheck {
    private static final int SHOWN = 40;

    private final Map<Integer, String> stockNames = new HashMap<>();
    /**
     * Every resource of each table by id, built by walking the table. ARSCLib's own
     * getResource(int) answers null for an entry id from 0x8000 up (0x7f0a8000 on 47.0.3), which
     * would read as thousands of lost ids.
     */
    private final Map<Integer, ResourceEntry> stockById = new HashMap<>();
    private final Map<Integer, ResourceEntry> patchedById = new HashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final List<String> rewritten = new ArrayList<>();
    private final List<String> renamed = new ArrayList<>();
    private final List<String> added = new ArrayList<>();
    private final List<String> moved = new ArrayList<>();
    private final List<String> absentInStock = new ArrayList<>();
    /** The ids the stock manifest's application, activities and aliases name as icon or round icon. */
    private final Set<Integer> launcherIcons = new HashSet<>();
    private final List<String> restyledIcons = new ArrayList<>();
    /** The id the stock manifest's application names as its network security config. */
    private final Set<Integer> networkConfigs = new HashSet<>();
    private final List<String> trustedConfigs = new ArrayList<>();
    private int stockResources;
    private int stockValues;
    private int patchedResources;
    private int files;

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("usage: ResourceTableCheck <stock.apk> <patched.apk> [report.txt]");
            System.exit(2);
        }
        File stockFile = new File(args[0]);
        File patchedFile = new File(args[1]);
        ResourceTableCheck check = new ResourceTableCheck();
        try (ZipFile stockZip = new ZipFile(stockFile); ZipFile patchedZip = new ZipFile(patchedFile)) {
            check.launcherIcons.addAll(launcherIcons(stockZip));
            check.networkConfigs.addAll(networkSecurityConfigs(stockZip));
            check.run(table(stockZip), stockZip, table(patchedZip), patchedZip);
        }
        List<String> lines = check.lines(stockFile.getName(), patchedFile.getName());
        if (args.length == 3) {
            Files.write(new File(args[2]).toPath(), lines, StandardCharsets.UTF_8);
        }
        PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        for (String line : check.console(lines)) out.println(line);
        System.exit(check.failures.isEmpty() ? 0 : 1);
    }

    private static TableBlock table(ZipFile zip) throws IOException {
        ZipEntry arsc = zip.getEntry(TableBlock.FILE_NAME);
        if (arsc == null) throw new IOException(zip.getName() + " has no " + TableBlock.FILE_NAME);
        try (InputStream in = new BufferedInputStream(zip.getInputStream(arsc))) {
            return TableBlock.load(in);
        }
    }

    private static final int ICON = 0x01010002;
    private static final int ROUND_ICON = 0x0101052c;
    private static final int NETWORK_SECURITY_CONFIG = 0x01010527;

    private static Set<Integer> launcherIcons(ZipFile zip) throws IOException {
        return manifestReferences(zip, Set.of("application", "activity", "activity-alias"), ICON, ROUND_ICON);
    }

    private static Set<Integer> networkSecurityConfigs(ZipFile zip) throws IOException {
        return manifestReferences(zip, Set.of("application"), NETWORK_SECURITY_CONFIG);
    }

    /** The ids the manifest's elements with one of these names give in any of these attributes. */
    private static Set<Integer> manifestReferences(ZipFile zip, Set<String> elementNames, int... attributes) throws IOException {
        Set<Integer> ids = new HashSet<>();
        ZipEntry manifest = zip.getEntry("AndroidManifest.xml");
        if (manifest == null) return ids;
        AndroidManifestBlock block;
        try (InputStream in = new BufferedInputStream(zip.getInputStream(manifest))) {
            block = AndroidManifestBlock.load(in);
        }
        ResXmlElement root = block.getManifestElement();
        if (root == null) return ids;
        Iterator<?> elements = root.recursiveElements();
        while (elements.hasNext()) {
            ResXmlElement element = (ResXmlElement) elements.next();
            // Set.of refuses a null lookup, and an element can come back without a name.
            String name = element.getName();
            if (name == null || !elementNames.contains(name)) continue;
            for (int attribute : attributes) {
                ResXmlAttribute value = element.searchAttributeByResourceId(attribute);
                if (value != null && value.getValueType() == ValueType.REFERENCE) ids.add(value.getData());
            }
        }
        return ids;
    }

    private void run(TableBlock stock, ZipFile stockZip, TableBlock patched, ZipFile patchedZip) {
        Map<Integer, PackageBlock> patchedPackages = new LinkedHashMap<>();
        for (PackageBlock block : patched.listPackages()) {
            patchedPackages.put(block.getId(), block);
            for (ResourceEntry resource : resources(block)) patchedById.put(resource.getResourceId(), resource);
        }
        for (PackageBlock block : stock.listPackages()) {
            for (ResourceEntry resource : resources(block)) {
                stockNames.put(resource.getResourceId(), label(resource));
                stockById.put(resource.getResourceId(), resource);
            }
        }

        Set<Integer> seen = new HashSet<>();
        for (PackageBlock stockPackage : stock.listPackages()) {
            PackageBlock patchedPackage = patchedPackages.get(stockPackage.getId());
            if (patchedPackage == null) {
                failures.add(String.format(Locale.ROOT, "package 0x%02x %s: not in the patched table",
                        stockPackage.getId(), stockPackage.getName()));
                continue;
            }
            for (ResourceEntry resource : resources(stockPackage)) {
                stockResources++;
                int id = resource.getResourceId();
                seen.add(id);
                ResourceEntry match = patchedById.get(id);
                if (match == null) {
                    failures.add(hex(id) + " " + label(resource) + ": not in the patched table");
                    continue;
                }
                if (!resource.getType().equals(match.getType())) {
                    failures.add(hex(id) + " " + label(resource) + ": is " + label(match) + " in the patched table");
                    continue;
                }
                if (!resource.getName().equals(match.getName())) {
                    renamed.add(hex(id) + " " + label(resource) + " is " + label(match));
                }
                compareValues(resource, match, patched, patchedPackages.keySet());
            }
        }
        for (PackageBlock block : patched.listPackages()) {
            for (ResourceEntry resource : resources(block)) {
                patchedResources++;
                if (seen.contains(resource.getResourceId())) continue;
                added.add(hex(resource.getResourceId()) + " " + label(resource));
                for (Entry entry : values(resource)) {
                    checkReferences(resource, entry, null, patched, patchedPackages.keySet());
                }
            }
        }
        checkFiles(stock, stockZip, patched, patchedZip);
    }

    private void compareValues(ResourceEntry resource, ResourceEntry match, TableBlock patched, Set<Integer> packages) {
        Set<ResConfig> matched = new HashSet<>();
        for (Entry entry : values(resource)) {
            stockValues++;
            ResConfig config = entry.getResConfig();
            Entry other = match.get(config);
            String where = hex(resource.getResourceId()) + " " + label(resource) + " [" + config(config) + "]";
            if (other == null || other.isNull()) {
                failures.add(where + ": the value is gone");
                continue;
            }
            matched.add(other.getResConfig());
            if (entry.isComplex() != other.isComplex()) {
                failures.add(where + ": was " + (entry.isComplex() ? "a bag" : "a value") + ", is "
                        + (other.isComplex() ? "a bag" : "a value"));
                continue;
            }
            if (!entry.isComplex()) {
                String before = value(entry.getResValue(), resource.getType());
                String after = value(other.getResValue(), resource.getType());
                if (!before.equals(after) && !(isFile(before) && isFile(after))) {
                    rewritten.add(where + " " + before + " -> " + after);
                    checkReference(where, other.getResValue(), patched, packages);
                }
                continue;
            }
            compareBag(where, entry, other, patched, packages);
        }
        for (Entry other : values(match)) {
            if (matched.contains(other.getResConfig())) continue;
            rewritten.add(hex(match.getResourceId()) + " " + label(match) + " [" + config(other.getResConfig())
                    + "] added " + (other.isComplex() ? "bag" : value(other.getResValue(), match.getType())));
            checkReferences(match, other, null, patched, packages);
        }
    }

    private void compareBag(String where, Entry entry, Entry other, TableBlock patched, Set<Integer> packages) {
        ResTableMapEntry before = (ResTableMapEntry) entry.getTableEntry();
        ResTableMapEntry after = (ResTableMapEntry) other.getTableEntry();
        if (before.getParentId() != after.getParentId()) {
            // A style inherits every item its parent sets, so a parent that goes or changes drops
            // or swaps all of them at once. No patch moves one, so it fails like a lost item.
            failures.add(where + ": its parent " + reference(before.getParentId()) + " became "
                    + reference(after.getParentId()) + ", which drops or swaps every item it inherits");
        }
        // Per attr, in order: a bag can set one attr more than once (style/aed sets
        // windowBackground twice on 47.0.3), and a map keyed by attr keeps only the last.
        Map<Integer, List<ResValueMap>> beforeItems = items(before);
        Map<Integer, List<ResValueMap>> afterItems = items(after);
        for (Map.Entry<Integer, List<ResValueMap>> named : beforeItems.entrySet()) {
            int name = named.getKey();
            List<ResValueMap> was = named.getValue();
            List<ResValueMap> is = afterItems.getOrDefault(name, List.of());
            String itemWhere = where + " item " + name(name);
            for (int index = 0; index < was.size(); index++) {
                if (index >= is.size()) {
                    failures.add(itemWhere + ": the item is gone");
                    continue;
                }
                String old = value(was.get(index), null);
                String now = value(is.get(index), null);
                if (!old.equals(now)) {
                    rewritten.add(itemWhere + " " + old + " -> " + now);
                    checkReference(itemWhere, is.get(index), patched, packages);
                }
            }
            for (int index = was.size(); index < is.size(); index++) {
                rewritten.add(itemWhere + " added " + value(is.get(index), null));
                checkReference(itemWhere, is.get(index), patched, packages);
            }
        }
        for (Map.Entry<Integer, List<ResValueMap>> named : afterItems.entrySet()) {
            if (beforeItems.containsKey(named.getKey())) continue;
            String itemWhere = where + " item " + name(named.getKey());
            for (ResValueMap item : named.getValue()) {
                rewritten.add(itemWhere + " added " + value(item, null));
                checkReference(itemWhere, item, patched, packages);
            }
        }
    }

    /** Every file a patched value names is in the patched archive, unless the stock one lacked it too. */
    private void checkFiles(TableBlock stock, ZipFile stockZip, TableBlock patched, ZipFile patchedZip) {
        for (PackageBlock block : patched.listPackages()) {
            for (ResourceEntry resource : resources(block)) {
                ResourceEntry original = stockById.get(resource.getResourceId());
                for (Entry entry : values(resource)) {
                    if (entry.isComplex()) continue;
                    String path = filePath(entry.getResValue(), resource.getType());
                    if (path == null) continue;
                    files++;
                    String where = hex(resource.getResourceId()) + " " + label(resource) + " [" + config(entry.getResConfig()) + "]";
                    String stockPath = null;
                    if (original != null) {
                        Entry stockEntry = original.get(entry.getResConfig());
                        if (stockEntry != null && !stockEntry.isNull() && !stockEntry.isComplex()) {
                            stockPath = filePath(stockEntry.getResValue(), original.getType());
                        }
                    }
                    ZipEntry file = patchedZip.getEntry(path);
                    ZipEntry stockFile = stockPath == null ? null : stockZip.getEntry(stockPath);
                    if (file == null) {
                        if (stockPath != null && stockFile == null) {
                            absentInStock.add(where + " " + path);
                        } else {
                            failures.add(where + ": names " + path + ", which is not in the patched archive");
                        }
                        continue;
                    }
                    if (stockPath == null || stockFile == null) continue;
                    if (!stockPath.equals(path)) moved.add(where + " " + stockPath + " -> " + path);
                    // No patch writes a resource file but Custom launcher icon, which restyles the
                    // launcher's adaptive icon XML, and Trust user certificates, which adds the
                    // user's certificates to the network security config. Any other file whose
                    // bytes changed is one the rebuild swapped or damaged (a path clash loses
                    // content as well as paths).
                    if (stockFile.getCrc() != file.getCrc() || stockFile.getSize() != file.getSize()) {
                        if (launcherIcons.contains(resource.getResourceId()) && path.endsWith(".xml")) {
                            restyledIcons.add(where + " " + path);
                        } else if (networkConfigs.contains(resource.getResourceId()) && path.endsWith(".xml")) {
                            trustedConfigs.add(where + " " + path);
                        } else {
                            failures.add(where + ": " + path + " is not the file the stock archive holds for it");
                        }
                    }
                }
            }
        }
    }

    private void checkReferences(ResourceEntry resource, Entry entry, String where, TableBlock patched, Set<Integer> packages) {
        String at = where != null ? where
                : hex(resource.getResourceId()) + " " + label(resource) + " [" + config(entry.getResConfig()) + "]";
        if (!entry.isComplex()) {
            checkReference(at, entry.getResValue(), patched, packages);
            return;
        }
        ResTableMapEntry bag = (ResTableMapEntry) entry.getTableEntry();
        checkId(at + " parent", bag.getParentId(), patched, packages);
        for (ResValueMap item : bag) checkReference(at + " item " + name(item.getNameId()), item, patched, packages);
    }

    private void checkReference(String where, ValueItem value, TableBlock patched, Set<Integer> packages) {
        if (value == null) return;
        ValueType type = value.getValueType();
        if (type == ValueType.REFERENCE || type == ValueType.DYNAMIC_REFERENCE
                || type == ValueType.ATTRIBUTE || type == ValueType.DYNAMIC_ATTRIBUTE) {
            checkId(where, value.getData(), patched, packages);
        }
    }

    /** An id in one of the table's own packages has to be in it; the framework's are Android's to answer. */
    private void checkId(String where, int id, TableBlock patched, Set<Integer> packages) {
        if (id == 0 || !packages.contains(id >>> 24)) return;
        if (!patchedById.containsKey(id)) failures.add(where + ": points at " + hex(id) + ", which is not in the patched table");
    }

    private static Map<Integer, List<ResValueMap>> items(ResTableMapEntry bag) {
        Map<Integer, List<ResValueMap>> items = new LinkedHashMap<>();
        for (ResValueMap item : bag) items.computeIfAbsent(item.getNameId(), name -> new ArrayList<>()).add(item);
        return items;
    }

    private static List<ResourceEntry> resources(PackageBlock block) {
        List<ResourceEntry> list = new ArrayList<>();
        for (SpecTypePair pair : block.listSpecTypePairs()) {
            Iterator<ResourceEntry> iterator = pair.getResources();
            while (iterator.hasNext()) {
                ResourceEntry resource = iterator.next();
                if (resource != null && !resource.isEmpty()) list.add(resource);
            }
        }
        return list;
    }

    private static List<Entry> values(ResourceEntry resource) {
        List<Entry> list = new ArrayList<>();
        Iterator<Entry> iterator = resource.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry != null && !entry.isNull()) list.add(entry);
        }
        return list;
    }

    /** A value as text that does not depend on where the rebuild put it in a string pool. */
    private static String value(ValueItem value, String resourceType) {
        if (value == null) return "(none)";
        ValueType type = value.getValueType();
        int data = value.getData();
        if (type == null) return String.format(Locale.ROOT, "type 0x%02x 0x%08x", value.getType() & 0xff, data);
        switch (type) {
            case STRING:
                String text = value.getValueAsString();
                if (text == null) return "string (unreadable)";
                if (resourceType != null && !"string".equals(resourceType) && text.startsWith("res/")) return "file " + text;
                return "\"" + text.replace("\n", "\\n") + "\"";
            case REFERENCE:
            case DYNAMIC_REFERENCE:
                return reference(data);
            case ATTRIBUTE:
            case DYNAMIC_ATTRIBUTE:
                return String.format(Locale.ROOT, "?0x%08x", data);
            case COLOR_ARGB8:
            case COLOR_RGB8:
            case COLOR_ARGB4:
            case COLOR_RGB4:
                return String.format(Locale.ROOT, "#%08x", data);
            default:
                return type.name().toLowerCase(Locale.ROOT) + String.format(Locale.ROOT, " 0x%08x", data);
        }
    }

    private static String filePath(ValueItem value, String resourceType) {
        String text = value(value, resourceType);
        return isFile(text) ? text.substring("file ".length()) : null;
    }

    private static boolean isFile(String value) {
        return value.startsWith("file ");
    }

    private static String reference(int id) {
        return id == 0 ? "@null" : String.format(Locale.ROOT, "@0x%08x", id);
    }

    private String name(int id) {
        String known = stockNames.get(id);
        return known == null ? hex(id) : hex(id) + " " + known;
    }

    private static String label(ResourceEntry resource) {
        return resource.getType() + "/" + resource.getName();
    }

    private static String config(ResConfig config) {
        if (config == null) return "default";
        String qualifiers = config.getQualifiers();
        if (qualifiers == null || qualifiers.isEmpty()) return "default";
        return qualifiers.startsWith("-") ? qualifiers.substring(1) : qualifiers;
    }

    private static String hex(int id) {
        return String.format(Locale.ROOT, "0x%08x", id);
    }

    private List<String> lines(String stockName, String patchedName) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "[resources] %s: %d resources with %d values; %s: %d resources",
                stockName, stockResources, stockValues, patchedName, patchedResources));
        section(lines, "rewritten values", byType(rewritten));
        section(lines, "renamed by the rebuild (still resolved by id; a lookup by the old name no longer finds this id)", renamed);
        section(lines, "added resources", added);
        lines.add(String.format(Locale.ROOT, "[resources] files named by the patched table: %d, moved %d, absent from the stock archive too %d",
                files, moved.size(), absentInStock.size()));
        for (String line : moved) lines.add("  moved " + line);
        for (String line : absentInStock) lines.add("  absent in both " + line);
        section(lines, "launcher icon files restyled (Custom launcher icon)", restyledIcons);
        section(lines, "network security config files rewritten (Trust user certificates)", trustedConfigs);
        if (failures.isEmpty()) {
            lines.add(String.format(Locale.ROOT, "[resources] every one of the stock table's %d resources resolves in the patched table, with its type and a value in each of its configurations, and every file and reference the patched values name is there",
                    stockResources));
        } else {
            for (String failure : failures) lines.add("[resources] FAIL " + failure);
            lines.add("[resources] " + failures.size() + " failure(s)");
        }
        return lines;
    }

    /** Colors, then styles, then every other type by name, strings last: the lines start "0x... type/name". */
    private static List<String> byType(List<String> items) {
        List<String> sorted = new ArrayList<>(items);
        sorted.sort(java.util.Comparator.comparingInt((String line) -> typeRank(line)).thenComparing(line -> typeOf(line)));
        return sorted;
    }

    private static String typeOf(String line) {
        int space = line.indexOf(' ');
        int slash = line.indexOf('/', space + 1);
        return space < 0 || slash < 0 ? "" : line.substring(space + 1, slash);
    }

    private static int typeRank(String line) {
        switch (typeOf(line)) {
            case "color": return 0;
            case "style": return 1;
            case "string": return 3;
            default: return 2;
        }
    }

    private static void section(List<String> lines, String title, List<String> items) {
        lines.add("[resources] " + title + ": " + items.size());
        for (String item : items) lines.add("  " + item);
    }

    /** The console keeps every heading and failure, and the first lines under each heading. */
    private List<String> console(List<String> lines) {
        List<String> shown = new ArrayList<>();
        int under = 0;
        int hidden = 0;
        for (String line : lines) {
            if (!line.startsWith("  ")) {
                if (hidden > 0) shown.add("  ... " + hidden + " more in the report");
                under = 0;
                hidden = 0;
                shown.add(line);
                continue;
            }
            if (under++ < SHOWN) shown.add(line);
            else hidden++;
        }
        if (hidden > 0) shown.add("  ... " + hidden + " more in the report");
        return shown;
    }
}
