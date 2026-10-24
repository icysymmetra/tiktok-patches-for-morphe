import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.ExceptionHandler;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.TryBlock;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.reference.Reference;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Names every method a patched APK does not share with the clean one it was built from, and prints
 * the register evidence for each: how many registers the method declares, and every instruction the
 * two bodies do not have in common.
 *
 * <p>The point of it is the last line of the report. A patch that writes into a register the method
 * never declared assembles happily and only fails when a device verifies the class, so every
 * instruction that was not in the clean body is checked against the register count of the method it
 * landed in. Methods the patch adds outright, which is where a hand-written bridge lives and where
 * the registers are chosen rather than inherited, are checked the same way.
 *
 * <p>Three things this deliberately does not leave to chance:
 *
 * <ul>
 *   <li>A wide instruction names one register and occupies two, so a destination that sets a wide
 *       register counts as reaching one higher than it names. Missing that would let a
 *       {@code const-wide} one short of the ceiling read as safe.
 *   <li>The highest register travels with the rendered line rather than being read back out of the
 *       text, because this app has string constants that look like register names ({@code v4190},
 *       {@code v20200906}) and parsing the text flags five of them.
 *   <li>Absence of evidence is a failure, not a pass. A run that finds no changed methods, or no
 *       added methods of the extension's own, is comparing the wrong pair of files and says so
 *       instead of reporting nothing wrong.
 * </ul>
 *
 * <p>Branch targets and try-block ranges are part of a body's identity here, so a change that only
 * moves a jump or widens an exception range still shows up as a changed method. Whether the result
 * verifies is a question for a verifier, which is the other half of
 * scripts/verify-injected-registers.ps1.
 *
 *   java -cp &lt;cli jar&gt; DexDiff.java &lt;cleanApk&gt; &lt;patchedApk&gt; &lt;reportFile&gt;
 *       &lt;removalAllowlist&gt;
 */
public class DexDiff {

    /** Anything under here is the bundle's own code rather than the host's. */
    private static final String OWN = "Lapp/morphe/";

    private static final class RemovalAllowlist {
        final Set<String> methods = new TreeSet<>();
        final Set<String> dexEntries = new TreeSet<>();
    }

    private static RemovalAllowlist readRemovalAllowlist(File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Removal allowlist not found: " + file);
        RemovalAllowlist allowlist = new RemovalAllowlist();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Set<String> target;
            String value;
            if (line.startsWith("method ")) {
                target = allowlist.methods;
                value = line.substring("method ".length()).trim();
            } else if (line.startsWith("dex ")) {
                target = allowlist.dexEntries;
                value = line.substring("dex ".length()).trim();
            } else {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": expected method or dex");
            }
            if (value.isEmpty() || !target.add(value)) {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": value is empty or duplicated");
            }
        }
        return allowlist;
    }

    private static Set<String> dexEntries(File apk) throws Exception {
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        return new TreeSet<>(container.getDexEntryNames());
    }

    /** Signature -> "registerCount:bodyHash", for every method of an APK. */
    private static Map<String, String> fingerprintAll(File apk) throws Exception {
        Map<String, String> out = new HashMap<>(1 << 20);
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    MethodImplementation impl = m.getImplementation();
                    StringBuilder body = new StringBuilder();
                    int registers = 0;
                    if (impl != null) {
                        registers = impl.getRegisterCount();
                        for (Instruction i : impl.getInstructions()) body.append(render(i)).append('\n');
                        for (String t : tryBlocks(impl)) body.append(t).append('\n');
                    }
                    digest.reset();
                    byte[] hash = digest.digest(body.toString().getBytes("UTF-8"));
                    StringBuilder hex = new StringBuilder();
                    for (int k = 0; k < 8; k++) hex.append(String.format("%02x", hash[k]));
                    out.put(sig(cd, m), registers + ":" + hex);
                }
            }
        }
        return out;
    }

    /** Signature -> rendered body, for the named methods only. */
    private static Map<String, List<String>> bodiesOf(File apk, Set<String> wanted) throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, Opcodes.getDefault());
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    if (!wanted.contains(s)) continue;
                    List<String> body = new ArrayList<>();
                    MethodImplementation impl = m.getImplementation();
                    if (impl != null) {
                        body.add("# registers=" + impl.getRegisterCount());
                        for (Instruction i : impl.getInstructions()) body.add(render(i));
                        body.addAll(tryBlocks(impl));
                    }
                    out.put(s, body);
                }
            }
        }
        return out;
    }

    /** Each try block as a line, so an exception range that moved is a body that changed. */
    private static List<String> tryBlocks(MethodImplementation impl) {
        List<String> out = new ArrayList<>();
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            StringBuilder b = new StringBuilder("# try start=").append(block.getStartCodeAddress())
                    .append(" units=").append(block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                b.append(" catch(").append(handler.getExceptionType())
                        .append(")->").append(handler.getHandlerCodeAddress());
            }
            out.add(b.toString());
        }
        return out;
    }

    private static String sig(ClassDef cd, Method m) {
        StringBuilder b = new StringBuilder(cd.getType()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    /** One instruction as text: opcode, registers, branch offset, literal, reference, max register. */
    private static String render(Instruction i) {
        StringBuilder b = new StringBuilder(i.getOpcode().name);
        List<String> regs = new ArrayList<>();
        int maxReg = -1;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            regs.add("v" + r.getStartRegister() + "..v" + (r.getStartRegister() + r.getRegisterCount() - 1));
            maxReg = r.getStartRegister() + r.getRegisterCount() - 1;
        } else if (i instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction r = (FiveRegisterInstruction) i;
            int n = r.getRegisterCount();
            int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
            for (int k = 0; k < n; k++) { regs.add("v" + all[k]); maxReg = Math.max(maxReg, all[k]); }
        } else if (i instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            regs.add("v" + r.getRegisterC());
            maxReg = Math.max(r.getRegisterA(), Math.max(r.getRegisterB(), r.getRegisterC()));
        } else if (i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            maxReg = Math.max(r.getRegisterA(), r.getRegisterB());
        } else if (i instanceof OneRegisterInstruction) {
            regs.add("v" + ((OneRegisterInstruction) i).getRegisterA());
            maxReg = ((OneRegisterInstruction) i).getRegisterA();
        }
        // A wide destination names its low half and occupies the pair, so it reaches one higher
        // than it says. Only the destination: dexlib2 does not describe which sources are wide,
        // and guessing there would fail a valid narrow destination sitting above a wide source.
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction) {
            maxReg = Math.max(maxReg, ((OneRegisterInstruction) i).getRegisterA() + 1);
        }
        if (!regs.isEmpty()) b.append(' ').append(String.join(", ", regs));
        if (i instanceof OffsetInstruction) {
            b.append(", ").append(String.format("%+d", ((OffsetInstruction) i).getCodeOffset()));
        }
        if (i instanceof WideLiteralInstruction) {
            b.append(", #").append(((WideLiteralInstruction) i).getWideLiteral());
        }
        if (i instanceof ReferenceInstruction) {
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r != null) b.append(", ").append(r);
        }
        return b.append(" |maxreg=").append(maxReg).toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("usage: DexDiff <cleanApk> <patchedApk> <reportFile> <removalAllowlist>");
            System.exit(2);
        }
        File clean = new File(args[0]);
        File patched = new File(args[1]);
        File allowlistFile = new File(args[3]);
        RemovalAllowlist allowlist = readRemovalAllowlist(allowlistFile);

        System.out.println("[diff] fingerprinting clean " + clean.getName());
        Map<String, String> before = fingerprintAll(clean);
        Set<String> beforeDexEntries = dexEntries(clean);
        System.out.println("[diff] " + before.size() + " methods");
        System.out.println("[diff] fingerprinting patched " + patched.getName());
        Map<String, String> after = fingerprintAll(patched);
        Set<String> afterDexEntries = dexEntries(patched);
        System.out.println("[diff] " + after.size() + " methods");

        Set<String> changed = new TreeSet<>();
        Set<String> added = new TreeSet<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String was = before.get(e.getKey());
            if (was == null) added.add(e.getKey());
            else if (!was.equals(e.getValue())) changed.add(e.getKey());
        }
        Set<String> removed = new TreeSet<>();
        for (String k : before.keySet()) if (!after.containsKey(k)) removed.add(k);
        Set<String> removedDexEntries = new TreeSet<>(beforeDexEntries);
        removedDexEntries.removeAll(afterDexEntries);

        Set<String> rejectedRemoved = new TreeSet<>(removed);
        rejectedRemoved.removeAll(allowlist.methods);
        Set<String> rejectedDexEntries = new TreeSet<>(removedDexEntries);
        rejectedDexEntries.removeAll(allowlist.dexEntries);
        Set<String> staleAllowedMethods = new TreeSet<>(allowlist.methods);
        staleAllowedMethods.removeAll(removed);
        Set<String> staleAllowedDexEntries = new TreeSet<>(allowlist.dexEntries);
        staleAllowedDexEntries.removeAll(removedDexEntries);

        Set<String> ownAdded = new TreeSet<>();
        for (String s : added) if (s.startsWith(OWN)) ownAdded.add(s);

        System.out.println("[diff] host methods changed: " + changed.size());
        System.out.println("[diff] methods added: " + added.size()
                + " (" + ownAdded.size() + " under " + OWN + ")");
        System.out.println("[diff] methods removed: " + removed.size()
                + " (rejected " + rejectedRemoved.size() + ")");
        System.out.println("[diff] DEX entries removed: " + removedDexEntries.size()
                + " (rejected " + rejectedDexEntries.size() + ")");

        // A pair of files with nothing between them is not a clean bill of health, it is the wrong
        // pair of files. Both of these were reachable by pointing the run at one APK twice.
        int problems = 0;
        if (changed.isEmpty()) {
            System.out.println("[diff] FAIL: no host method differs, so these two APKs are not a "
                    + "clean build and a patched build of it.");
            problems++;
        }
        if (ownAdded.isEmpty()) {
            System.out.println("[diff] FAIL: the patched APK carries no method under " + OWN
                    + ", so no extension code was added to it.");
            problems++;
        }
        if (!rejectedRemoved.isEmpty()) {
            System.out.println("[diff] FAIL: removed host methods are not allowed:");
            for (String method : rejectedRemoved) System.out.println("[diff]   " + method);
            problems += rejectedRemoved.size();
        }
        if (!rejectedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: removed DEX entries are not allowed:");
            for (String entry : rejectedDexEntries) System.out.println("[diff]   " + entry);
            problems += rejectedDexEntries.size();
        }
        if (!staleAllowedMethods.isEmpty() || !staleAllowedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: the removal allowlist contains entries this pair did not remove:");
            for (String method : staleAllowedMethods) System.out.println("[diff]   method " + method);
            for (String entry : staleAllowedDexEntries) System.out.println("[diff]   dex " + entry);
            problems += staleAllowedMethods.size() + staleAllowedDexEntries.size();
        }

        System.out.println("[diff] reading both bodies for the changed and added methods");
        Set<String> wanted = new TreeSet<>(changed);
        wanted.addAll(ownAdded);
        Map<String, List<String>> beforeBodies = bodiesOf(clean, changed);
        Map<String, List<String>> afterBodies = bodiesOf(patched, wanted);

        PrintWriter report = new PrintWriter(args[2], "UTF-8");
        try {
            report.println("Methods a patched APK does not share with the clean build it came from.");
            report.println("clean:   " + clean.getAbsolutePath());
            report.println("patched: " + patched.getAbsolutePath());
            report.println("removal allowlist: " + allowlistFile.getAbsolutePath());
            report.println("changed=" + changed.size() + " added=" + added.size()
                    + " (own=" + ownAdded.size() + ") removed=" + removed.size()
                    + " removedDex=" + removedDexEntries.size());
            report.println();

            report.println("Removed methods:");
            for (String method : removed) {
                report.println((allowlist.methods.contains(method) ? "  allowed " : "  FAIL    ") + method);
            }
            report.println("Removed DEX entries:");
            for (String entry : removedDexEntries) {
                report.println((allowlist.dexEntries.contains(entry) ? "  allowed " : "  FAIL    ") + entry);
            }
            report.println("Stale removal allowlist entries:");
            for (String method : staleAllowedMethods) report.println("  FAIL    method " + method);
            for (String entry : staleAllowedDexEntries) report.println("  FAIL    dex " + entry);
            report.println();

            int overRegister = 0;
            int unreadable = 0;

            for (String s : changed) {
                List<String> b = beforeBodies.getOrDefault(s, List.of());
                List<String> a = afterBodies.getOrDefault(s, List.of());
                int regsBefore = registersOf(b), regsAfter = registersOf(a);
                report.println("==== " + s);
                report.println("     registers " + regsBefore + " -> " + regsAfter
                        + ", instructions " + Math.max(0, b.size() - 1) + " -> " + Math.max(0, a.size() - 1));
                if (regsAfter < 0 && !a.isEmpty()) {
                    // Nothing to hold the injected lines to. Silently skipping this was a hole:
                    // any method the second pass failed to render passed the check by default.
                    report.println("  !  no register count could be read for this method");
                    unreadable++;
                }
                List<String> onlyAfter = minus(a, b);
                for (String line : minus(b, a)) report.println("  -  " + line);
                for (String line : onlyAfter) {
                    int high = highestRegister(line);
                    boolean bad = high >= 0 && regsAfter >= 0 && high >= regsAfter;
                    if (bad) overRegister++;
                    report.println("  +  " + line + (bad ? "   <<< REGISTER >= registerCount" : ""));
                }
                report.println();
            }

            // The bundle's own methods are where registers are chosen by hand rather than reused
            // from the host, which is exactly where an out-of-range one would come from.
            for (String s : ownAdded) {
                List<String> a = afterBodies.getOrDefault(s, List.of());
                int regsAfter = registersOf(a);
                if (a.isEmpty()) continue;
                List<String> offending = new ArrayList<>();
                for (String line : a) {
                    if (line.startsWith("#")) continue;
                    int high = highestRegister(line);
                    if (high >= 0 && regsAfter >= 0 && high >= regsAfter) offending.add(line);
                }
                if (regsAfter < 0) {
                    report.println("==== added " + s);
                    report.println("  !  no register count could be read for this method");
                    unreadable++;
                }
                if (offending.isEmpty()) continue;
                overRegister += offending.size();
                report.println("==== added " + s);
                report.println("     registers " + regsAfter);
                for (String line : offending) {
                    report.println("  +  " + line + "   <<< REGISTER >= registerCount");
                }
                report.println();
            }

            report.println("Lines naming a register at or above the method's register count: " + overRegister);
            report.println("Methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] injected lines naming an out-of-range register: " + overRegister);
            if (unreadable != 0) problems++;
            if (overRegister != 0) problems++;
        } finally {
            report.close();
        }
        System.out.println("[diff] report written to " + args[2]);
        System.exit(problems == 0 ? 0 : 1);
    }

    /**
     * The register count the body declares, or -1 when the body was not read at all.
     *
     * <p>Zero is a real answer: a static method with no arguments and no locals declares no
     * registers, and R8's synthetic lambda bridges are full of them. Treating zero as "unknown"
     * flagged twenty-one of the bundle's own methods as unreadable when nothing was wrong.
     */
    private static int registersOf(List<String> body) {
        if (body.isEmpty() || !body.get(0).startsWith("# registers=")) return -1;
        return Integer.parseInt(body.get(0).substring("# registers=".length()));
    }

    /** Lines of a that are not in b, counting duplicates. */
    private static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> pool = new HashMap<>();
        for (String s : b) pool.merge(s, 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (String s : a) {
            Integer left = pool.get(s);
            if (left != null && left > 0) pool.put(s, left - 1);
            else if (!s.startsWith("# registers=")) out.add(s);
        }
        return out;
    }

    /** The highest register the instruction touched, read back from the marker render() wrote. */
    private static int highestRegister(String line) {
        int at = line.lastIndexOf("|maxreg=");
        if (at < 0) return -1;
        return Integer.parseInt(line.substring(at + "|maxreg=".length()).trim());
    }
}
