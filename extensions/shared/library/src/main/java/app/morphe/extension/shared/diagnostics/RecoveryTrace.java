/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects stack metadata, never arbitrary system-dump text, before anything reaches disk. */
final class RecoveryTrace {
    static final int MAX_INPUT = 512 * 1024, MAX_OUTPUT = 48 * 1024;
    static final int MAX_THREADS = 256, MAX_LINES = 256, MAX_FRAMES = 40, MAX_DETAILED = 8;
    private static final Pattern THREAD = Pattern.compile("^\"([^\"]*)\".*?\\btid=(\\d+)\\b.*$");
    private static final Pattern OWNER = Pattern.compile("held by thread (\\d+)");
    private static final Pattern ADDRESS = Pattern.compile("<0x[0-9a-fA-F]+>");
    private static final Pattern FRAME = Pattern.compile("^at ([A-Za-z0-9_.$/<>-]+)\\(([^()]*)\\)$");
    private static final Pattern LIBRARY = Pattern.compile("(?:^|[/!])(lib[A-Za-z0-9_.+-]+\\.so)\\b");
    private static final Pattern NATIVE = Pattern.compile("^native: #([0-9]+) pc ([0-9a-fA-F]+) .*$");
    /**
     * The form Android writes when the Java dump fails: AMS falls back to debuggerd's native
     * stacks (StackTracesDumpHelper.dumpJavaTracesTombstoned). Its headers say sysTid= and never
     * tid=, so THREAD matched nothing and #215's three reports came back with no threads.
     */
    private static final Pattern NATIVE_THREAD = Pattern.compile("^\"([^\"]*)\" sysTid=(\\d+)\\s*$");
    /**
     * libunwindstack's FormatFrame: "#NN pc PC  MAP[ (deleted)][ (offset 0x..)][ (SYMBOL[+OFF])]
     * [ (BuildId: ..)]". The symbol has no "+OFF" when the offset is zero, and a demangled C++
     * symbol nests brackets, so the tail is taken apart in {@link #nativeFrame} rather than here.
     */
    private static final Pattern NATIVE_DUMP_FRAME = Pattern.compile("^#([0-9]+) pc ([0-9a-fA-F]+)\\s+(\\S+)(.*)$");
    private static final Pattern BUILD_ID = Pattern.compile("\\s*\\(BuildId: [^()]*\\)\\s*$");
    private static final Pattern MAP_NOTE = Pattern.compile("^\\s*\\((?:deleted|offset 0x[0-9a-fA-F]+)\\)");
    private static final Pattern SYS_TID = Pattern.compile("\\bsysTid=(\\d+)\\b");
    /**
     * Java methods and demangled C/C++ symbols, whose argument lists bring spaces and brackets;
     * anything with a path or address separator in it is dropped.
     */
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9_.$:<>~,+*&()\\[\\] -]{1,300}");

    static final class Result {
        final String status, text;
        /** Counts only, so a report with no threads still says what kind of trace Android gave. */
        final String shape;
        Result(String status, String text) { this(status, text, ""); }
        Result(String status, String text, String shape) {
            this.status = status; this.text = text; this.shape = shape;
        }
    }

    /** Raw bytes remain bounded and in memory only; decoding/regex work runs off the probe. */
    static final class Intake {
        final byte[] bytes; final boolean cut;
        Intake(byte[] bytes, boolean cut) { this.bytes = bytes; this.cut = cut; }
    }

    static Result read(InputStream input) throws Exception {
        return parse(readBounded(input));
    }

    static Result read(InputStream input, int pid) throws Exception {
        return parse(readBounded(input), pid);
    }

    static Intake readBounded(InputStream input) throws Exception {
        if (input == null) return new Intake(null, false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        boolean cut = false;
        while (bytes.size() <= MAX_INPUT) {
            int n = input.read(chunk, 0, Math.min(chunk.length, MAX_INPUT + 1 - bytes.size()));
            if (n < 0) break;
            if (n == 0) throw new java.io.IOException("trace made no progress");
            bytes.write(chunk, 0, n);
            if (bytes.size() > MAX_INPUT) { cut = true; break; }
        }
        return new Intake(bytes.toByteArray(), cut);
    }

    static Result parse(Intake intake) {
        return parse(intake, -1);
    }

    /** @param pid the failed process, whose own thread leads a native dump; -1 when unknown */
    static Result parse(Intake intake, int pid) {
        if (intake.bytes == null) return new Result("unavailable", "");
        if (intake.bytes.length == 0) return new Result("empty", "");
        byte[] raw = intake.bytes;
        boolean cut = intake.cut;
        int length = Math.min(raw.length, MAX_INPUT);
        // A byte limit may split the last UTF-8 character; only discard that partial suffix.
        if (cut) while (length > 0 && (raw[length] & 0xc0) == 0x80) length--;
        String source;
        boolean replaced;
        try {
            // Thread names come from /proc/<pid>/task/<tid>/comm, which the kernel and ART cut
            // by bytes, so one split character in a name the report never prints must not cost
            // every stack. Nothing decoded leaves here except through the structural filters.
            source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .decode(ByteBuffer.wrap(raw, 0, length)).toString();
            replaced = source.indexOf('�') >= 0;
        } catch (java.nio.charset.CharacterCodingException invalid) {
            return new Result("invalid_text", "", "bytes=" + length + " input_cut=" + cut);
        }
        Result result = select(source, cut, pid);
        return new Result(result.status, result.text, "bytes=" + length + " input_cut=" + cut
                + (replaced ? " decode_replaced=true" : "") + " " + result.shape);
    }

    private static final class ThreadBlock {
        final String tid, state, name;
        final boolean nativeDump;
        /** Why the thread is in the report; never its name, which can be anything an app set. */
        String role = "lock_owner";
        final List<String> body = new ArrayList<>();
        ThreadBlock(String tid, String name, String header, boolean nativeDump) {
            this.tid = tid; this.name = name; this.nativeDump = nativeDump;
            if (nativeDump) { state = "native_dump"; return; }
            Matcher m = Pattern.compile("\\b(Runnable|Waiting|Blocked|TimedWaiting|Native|Sleeping|Suspended)\\s*$")
                    .matcher(header);
            state = m.find() ? m.group(1) : "unknown";
        }

        /** ART and the kernel number threads differently, so the two kinds never share a key. */
        String key() { return (nativeDump ? "n" : "j") + tid; }

        int frames() {
            int frames = 0;
            for (String line : body) if (line.startsWith("at ") || line.startsWith("native:")
                    || NATIVE_DUMP_FRAME.matcher(line).matches()) frames++;
            return frames;
        }

        /** The kernel thread id of a Java thread, from ART's "| group=.. sysTid=N" line. */
        String sysTid() {
            for (String line : body) {
                if (!line.startsWith("|")) continue;
                Matcher m = SYS_TID.matcher(line);
                if (m.find()) return m.group(1);
            }
            return null;
        }
    }

    static Result select(String source, boolean cut) {
        return select(source, cut, -1);
    }

    static Result select(String source, boolean cut, int pid) {
        Map<String, ThreadBlock> threads = new LinkedHashMap<>();
        ThreadBlock current = null;
        int javaHeaders = 0, nativeHeaders = 0, javaKept = 0, nativeKept = 0, omitted = 0;
        for (String line : source.split("\\r?\\n")) {
            Matcher header = THREAD.matcher(line);
            Matcher nativeHeader = NATIVE_THREAD.matcher(line);
            boolean java = header.matches(), isNative = !java && nativeHeader.matches();
            if (java || isNative) {
                if (java) javaHeaders++; else nativeHeaders++;
                current = java ? new ThreadBlock(header.group(2), header.group(1), line, false)
                        : new ThreadBlock(nativeHeader.group(2), nativeHeader.group(1), line, true);
                // Each kind has its own allowance, so a long Java section can't crowd out the
                // native fallback written after it (#215 review). The input is already bounded.
                boolean room = java ? javaKept < MAX_THREADS : nativeKept < MAX_THREADS;
                if (room && !threads.containsKey(current.key())) {
                    threads.put(current.key(), current);
                    if (java) javaKept++; else nativeKept++;
                } else { current = null; omitted++; cut = true; }
            } else if (current != null) {
                if (current.body.size() < MAX_LINES) current.body.add(line.trim());
                else cut = true;
            }
        }
        String format = javaHeaders > 0 && nativeHeaders > 0 ? "mixed"
                : javaHeaders > 0 ? "java" : nativeHeaders > 0 ? "native" : "none";
        StringBuilder shape = new StringBuilder("format=").append(format)
                .append(" java_threads=").append(javaHeaders).append(" native_threads=").append(nativeHeaders)
                .append(" threads_omitted=").append(omitted);

        ThreadBlock javaMain = javaMain(threads, pid), nativeMain = nativeMain(threads, pid);
        // A Java main with stack frames is the better record: it has lock lines to follow. When
        // the Java dump broke off after the header, the native fallback that follows it is the
        // only stack there is, so it leads instead (#215 review).
        ThreadBlock main = javaMain != null && (javaMain.frames() > 0 || nativeMain == null) ? javaMain : nativeMain;
        if (main == null) {
            return new Result(cut ? "truncated_no_main" : "no_main_thread", "", shape.toString());
        }
        main.role = "main";
        Detail detail = new Detail(threads);
        // The native overview's compact form is the one part sized to the whole thread set, so
        // the detailed stacks leave room for exactly that, and only when there is an overview to
        // write. A Java-only dump keeps the whole limit for its lock chain (#215 review).
        int reserve = 0;
        if (nativeMain != null) {
            reserve = SUMMARY_HEADING.length();
            for (ThreadBlock thread : threads.values()) {
                if (thread.nativeDump && thread != nativeMain) reserve += summaryRow(thread, false).length();
            }
            reserve = Math.min(reserve, MAX_OUTPUT - 512 - MIN_DETAIL);
        }
        int detailLimit = MAX_OUTPUT - 512 - reserve;

        // 1. The main thread, its native twin, then every Java thread it is proven to wait on.
        //    Proven dependencies come before anything chosen by guesswork.
        ArrayDeque<ThreadBlock> queue = new ArrayDeque<>(); queue.add(main);
        if (main == javaMain && nativeMain != null) { nativeMain.role = "main_native"; queue.add(nativeMain); }
        detail.drain(queue, MAX_DETAILED, detailLimit);
        int mainFrames = detail.framesOf(main);

        // 2. The runtime's own threads, which show why a Java dump failed.
        if (nativeMain != null) {
            for (ThreadBlock thread : threads.values()) {
                if (!thread.nativeDump || detail.has(thread)) continue;
                if ("Signal Catcher".equals(thread.name)) { thread.role = "signal_catcher"; queue.add(thread); }
                else if ("HeapTaskDaemon".equals(thread.name)) { thread.role = "gc_daemon"; queue.add(thread); }
            }
            detail.drain(queue, MAX_DETAILED, detailLimit);
        }
        int slotsSkipped = queue.size();
        queue.clear();

        // 3. A native dump has no lock lines, so who holds the others up is read from the whole
        //    set: every other native thread gets its first frames before any thread picked by
        //    guesswork gets more. Three frames, because a top frame of "syscall" says nothing
        //    about who made the call. Coverage comes first: every row is written with pcs and
        //    module names, which the APK can symbolise later; symbols are then added row by row
        //    while room is left beyond what the candidates keep.
        int summarized = 0, unsummarized = 0, symbolized = 0;
        if (nativeMain != null) {
            List<ThreadBlock> rest = new ArrayList<>();
            for (ThreadBlock thread : threads.values()) if (thread.nativeDump && !detail.has(thread)) rest.add(thread);
            int room = MAX_OUTPUT - 512 - detail.out.length() - SUMMARY_HEADING.length();
            List<String> rows = new ArrayList<>();
            for (ThreadBlock thread : rest) {
                String row = summaryRow(thread, false);
                if (row.length() > room) break;
                rows.add(row); room -= row.length();
            }
            summarized = rows.size(); unsummarized = rest.size() - summarized;
            int spare = room - CANDIDATE_RESERVE;
            for (int i = 0; i < rows.size(); i++) {
                String full = summaryRow(rest.get(i), true);
                int more = full.length() - rows.get(i).length();
                if (more == 0) continue;
                if (more > spare) continue;
                rows.set(i, full); spare -= more; symbolized++;
            }
            if (!rows.isEmpty()) {
                detail.out.append(SUMMARY_HEADING);
                for (String row : rows) detail.out.append(row);
            }
        }

        // 4. With what room is left, full stacks for up to four threads whose top frame isn't
        //    a recognised wait. Candidates, not proven holders: a parked thread can hold a lock.
        int candidates = 0;
        if (nativeMain != null && unsummarized == 0) {
            for (ThreadBlock thread : threads.values()) {
                if (candidates >= 4) break;
                if (!thread.nativeDump || detail.has(thread) || !notParked(thread)) continue;
                thread.role = "candidate"; queue.add(thread); candidates++;
            }
            if (!queue.isEmpty() && detail.fits(64, MAX_OUTPUT - 512)) {
                detail.out.append("[CANDIDATE STACKS]\n");
                detail.drain(queue, detail.count() + queue.size(), MAX_OUTPUT - 512);
            }
            candidates -= queue.size();
        }

        shape.append(" detailed=").append(detail.count()).append(" detail_slots_skipped=").append(slotsSkipped)
                .append(" summarized=").append(summarized).append(" unsummarized=").append(unsummarized)
                .append(" summary_symbolized=").append(symbolized)
                .append(" candidates=").append(candidates).append(" main_frames=").append(mainFrames);
        String text = DiagnosticRedactor.redact(detail.out.toString());
        // Redaction can lengthen what it rewrites, so the bound is applied to what leaves.
        boolean clamped = false;
        if (text.length() > MAX_OUTPUT) {
            int end = text.lastIndexOf('\n', MAX_OUTPUT - 1);
            text = text.substring(0, end < 0 ? MAX_OUTPUT : end + 1);
            clamped = true;
        }
        String status = mainFrames == 0 ? "main_without_frames"
                : cut || detail.bytesCut || detail.framesCut || slotsSkipped > 0 || unsummarized > 0 || clamped ? "truncated" : "captured";
        return new Result(status, text, shape.toString());
    }

    static final int SUMMARY_FRAMES = 3;
    static final String SUMMARY_HEADING = "[OTHER NATIVE THREADS, FIRST FRAMES]\n";
    /**
     * The least the detailed stacks keep however large the overview, and what symbols in the
     * overview leave for the candidate stacks.
     */
    static final int MIN_DETAIL = 12 * 1024, CANDIDATE_RESERVE = 4 * 1024;

    /** A thread's overview row: its first frames, with or without their symbols. */
    private static String summaryRow(ThreadBlock thread, boolean symbols) {
        StringBuilder row = new StringBuilder("  tid=").append(thread.tid).append('\n');
        int frames = 0;
        for (String line : thread.body) {
            if (frames >= SUMMARY_FRAMES) break;
            if (line.length() <= 2048 && NATIVE_DUMP_FRAME.matcher(line).matches()) {
                String frame = nativeFrame(line, symbols);
                row.append("    ").append(frame, "native: ".length(), frame.length()).append('\n');
                frames++;
            } else if (line.startsWith("Unwind failed")) { row.append("    unwind failed\n"); return row.toString(); }
        }
        if (frames == 0) row.append("    no frames\n");
        return row.toString();
    }

    /** The selected stacks, rendered under one output limit. */
    private static final class Detail {
        final Map<String, ThreadBlock> threads;
        final StringBuilder out = new StringBuilder();
        final Map<String, Integer> frames = new LinkedHashMap<>();
        final Map<String, String> locks = new LinkedHashMap<>();
        /** The output limit stopped a phase; or a stack was cut at MAX_FRAMES. */
        boolean bytesCut, framesCut;

        Detail(Map<String, ThreadBlock> threads) { this.threads = threads; }

        boolean has(ThreadBlock thread) { return frames.containsKey(thread.key()); }
        int count() { return frames.size(); }
        int framesOf(ThreadBlock thread) { Integer n = frames.get(thread.key()); return n == null ? 0 : n; }
        boolean fits(int more, int limit) { return out.length() + more <= limit; }

        /**
         * Renders queued threads until {@code slots} threads are rendered in all or the output
         * reaches {@code limit}. A Java thread's lock owners join the queue as they are found.
         * What a full slot count leaves queued stays queued for the caller to count.
         */
        void drain(ArrayDeque<ThreadBlock> queue, int slots, int limit) {
            while (!queue.isEmpty() && frames.size() < slots) {
                ThreadBlock thread = queue.removeFirst();
                if (has(thread)) continue;
                if (!render(thread, queue, limit)) { bytesCut = true; break; }
            }
            queue.removeIf(this::has);
        }

        /** @return false when the limit stopped it; a thread with a header counts as rendered */
        private boolean render(ThreadBlock thread, ArrayDeque<ThreadBlock> queue, int limit) {
            String head = "thread tid=" + thread.tid + " role=" + thread.role + " state=" + thread.state + "\n";
            if (!fits(head.length(), limit)) return false;
            out.append(head);
            int count = 0;
            for (String line : thread.body) {
                Matcher owner = OWNER.matcher(line);
                if (owner.find() && !thread.nativeDump) {
                    ThreadBlock holder = threads.get("j" + owner.group(1));
                    if (holder != null && !has(holder) && !queue.contains(holder)) queue.add(holder);
                }
                String safe = null;
                Matcher frame = FRAME.matcher(line);
                Matcher nativeFrame = NATIVE.matcher(line);
                if (line.startsWith("#") && line.length() <= 2048 && NATIVE_DUMP_FRAME.matcher(line).matches()) {
                    if (count >= MAX_FRAMES) { framesCut = true; continue; }
                    count++;
                    safe = "  " + nativeFrame(line);
                } else if (line.startsWith("Unwind failed")) {
                    safe = "  unwind failed";
                } else if (line.startsWith("at ") || line.startsWith("native:")) {
                    if (count >= MAX_FRAMES) { framesCut = true; continue; }
                    count++;
                    if (line.length() <= 2048 && frame.matches()) {
                        String location = frame.group(2);
                        if (!location.matches("(?:[A-Za-z0-9_$.-]+\\.(?:java|kt)(?::[0-9]+)?|SourceFile(?::[0-9]+)?|Native [Mm]ethod|Unknown Source|unavailable)"))
                            location = "source omitted";
                        safe = "  at " + frame.group(1) + "(" + location + ")";
                    } else if (line.length() <= 2048 && nativeFrame.matches()) {
                        Matcher library = LIBRARY.matcher(line);
                        safe = "  native: #" + nativeFrame.group(1) + " pc " + nativeFrame.group(2)
                                + " " + (library.find() ? library.group(1) : "library omitted");
                    } else safe = "  at [frame omitted]";
                } else if (line.startsWith("- ") && line.length() < 512) {
                    Matcher address = ADDRESS.matcher(line);
                    if (address.find()) {
                        String key = address.group();
                        String label = locks.get(key);
                        if (label == null) { label = "L" + (locks.size() + 1); locks.put(key, label); }
                        safe = "  lock=" + label;
                        Matcher held = OWNER.matcher(line);
                        if (held.find()) safe += " held_by_tid=" + held.group(1);
                        if (line.startsWith("- waiting")) safe += " operation=waiting";
                        else if (line.startsWith("- locked")) safe += " operation=held";
                    }
                }
                if (safe != null) {
                    if (!fits(safe.length() + 1, limit)) { frames.put(thread.key(), count); return false; }
                    out.append(safe).append('\n');
                }
            }
            frames.put(thread.key(), count);
            return true;
        }
    }

    /** ART names its main thread "main"; failing that, the Java thread on the pid's kernel thread. */
    private static ThreadBlock javaMain(Map<String, ThreadBlock> threads, int pid) {
        for (ThreadBlock thread : threads.values()) if (!thread.nativeDump && "main".equals(thread.name)) return thread;
        if (pid <= 0) return null;
        for (ThreadBlock thread : threads.values()) {
            if (!thread.nativeDump && String.valueOf(pid).equals(thread.sysTid())) return thread;
        }
        return null;
    }

    /**
     * A native dump names the main thread after the process, not "main"; its kernel thread id
     * is the pid. debuggerd writes the target thread first, so the first native thread stands
     * in when the pid is unknown or absent.
     */
    private static ThreadBlock nativeMain(Map<String, ThreadBlock> threads, int pid) {
        ThreadBlock first = null;
        for (ThreadBlock thread : threads.values()) {
            if (!thread.nativeDump) continue;
            if (pid > 0 && thread.tid.equals(String.valueOf(pid))) return thread;
            if (first == null) first = thread;
        }
        return first;
    }

    /** Top frames of a parked thread: a futex, poll, sleep or blocking read. */
    private static final Pattern PARKED = Pattern.compile(
            "\\((?:__futex_wait\\w*|syscall|__epoll_pwait\\w*|epoll_wait|__ppoll|ppoll|poll|__pselect6|nanosleep|"
            + "clock_nanosleep|__rt_sigtimedwait|sigsuspend|__ioctl|ioctl|read|__read_chk|recvfrom|recvmsg|accept4?|"
            + "__openat|pread64|futex_wait|pthread_cond_\\w*wait\\w*)(?:\\+[0-9]+)?\\)");

    /**
     * A native thread whose top frame isn't one of the waits above. A parked thread's top frame
     * is the wait itself in libc, which always carries its symbol; an unnamed top frame is a
     * stripped library, usually the app's own, doing work.
     */
    private static boolean notParked(ThreadBlock thread) {
        for (String line : thread.body) {
            if (!NATIVE_DUMP_FRAME.matcher(line).matches()) continue;
            return !PARKED.matcher(nativeFrame(line)).find();
        }
        return false;
    }

    /** One debuggerd frame as "native: #NN pc PC mapping[ (symbol)]", without its path or BuildId. */
    static String nativeFrame(String line) {
        return nativeFrame(line, true);
    }

    static String nativeFrame(String line, boolean withSymbol) {
        Matcher m = NATIVE_DUMP_FRAME.matcher(line);
        if (!m.matches()) return "native: [frame omitted]";
        String rest = BUILD_ID.matcher(m.group(4)).replaceFirst("");
        for (Matcher note = MAP_NOTE.matcher(rest); note.find(); note = MAP_NOTE.matcher(rest)) {
            rest = rest.substring(note.end());
        }
        rest = rest.trim();
        String symbol = rest.startsWith("(") && rest.endsWith(")") ? rest.substring(1, rest.length() - 1) : null;
        return "native: #" + m.group(1) + " pc " + m.group(2) + " " + mapping(m.group(3))
                + (withSymbol && symbol != null && SYMBOL.matcher(symbol).matches() ? " (" + symbol + ")" : "");
    }

    /**
     * A frame's mapping without its path, which can carry the app's install directory: the
     * library's file name, or the kind of mapping when it isn't a named file.
     */
    static String mapping(String path) {
        Matcher library = LIBRARY.matcher(path);
        if (library.find()) return library.group(1);
        if (path.startsWith("/memfd:jit")) return "jit-cache";
        if (path.startsWith("[") || path.startsWith("/memfd:") || path.startsWith("<")) return "anonymous";
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.matches("[A-Za-z0-9_.@+-]{1,80}\\.(?:oat|odex|vdex|art|jar|apk|so)")) {
            return path.startsWith("/data/") ? "app:" + name : name;
        }
        return "mapping omitted";
    }
}
