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
    private static final Pattern THREAD = Pattern.compile("^\"([^\"]*)\".*?\\btid=(\\d+)\\b.*$");
    private static final Pattern OWNER = Pattern.compile("held by thread (\\d+)");
    private static final Pattern ADDRESS = Pattern.compile("<0x[0-9a-fA-F]+>");
    private static final Pattern FRAME = Pattern.compile("^at ([A-Za-z0-9_.$/<>-]+)\\(([^()]*)\\)$");
    private static final Pattern LIBRARY = Pattern.compile("(?:^|/)(lib[A-Za-z0-9_.+-]+\\.so)\\b");
    private static final Pattern NATIVE = Pattern.compile("^native: #([0-9]+) pc ([0-9a-fA-F]+) .*$");

    static final class Result {
        final String status, text;
        Result(String status, String text) { this.status = status; this.text = text; }
    }

    /** Raw bytes remain bounded and in memory only; decoding/regex work runs off the probe. */
    static final class Intake {
        final byte[] bytes; final boolean cut;
        Intake(byte[] bytes, boolean cut) { this.bytes = bytes; this.cut = cut; }
    }

    static Result read(InputStream input) throws Exception {
        return parse(readBounded(input));
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
        if (intake.bytes == null) return new Result("unavailable", "");
        if (intake.bytes.length == 0) return new Result("empty", "");
        byte[] raw = intake.bytes;
        boolean cut = intake.cut;
        int length = Math.min(raw.length, MAX_INPUT);
        // A byte limit may split the last UTF-8 character; only discard that partial suffix.
        if (cut) while (length > 0 && (raw[length] & 0xc0) == 0x80) length--;
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, 0, length)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            return new Result("invalid_text", "");
        }
        return select(source, cut);
    }

    private static final class ThreadBlock {
        final String tid, state;
        final boolean main;
        final List<String> body = new ArrayList<>();
        ThreadBlock(String tid, boolean main, String header) {
            this.tid = tid; this.main = main;
            Matcher m = Pattern.compile("\\b(Runnable|Waiting|Blocked|TimedWaiting|Native|Sleeping|Suspended)\\s*$")
                    .matcher(header);
            state = m.find() ? m.group(1) : "unknown";
        }
    }

    static Result select(String source, boolean cut) {
        Map<String, ThreadBlock> threads = new LinkedHashMap<>();
        ThreadBlock current = null, main = null;
        for (String line : source.split("\\r?\\n")) {
            Matcher header = THREAD.matcher(line);
            if (header.matches()) {
                current = new ThreadBlock(header.group(2), "main".equals(header.group(1)), line);
                if (threads.size() < 256) threads.put(current.tid, current);
                else { current = null; cut = true; }
                if (current != null && current.main) main = current;
            } else if (current != null) {
                if (current.body.size() < 256) current.body.add(line.trim());
                else cut = true;
            }
        }
        if (main == null) return new Result(cut ? "truncated_no_main" : "no_main_thread", "");
        StringBuilder out = new StringBuilder();
        ArrayDeque<ThreadBlock> queue = new ArrayDeque<>(); queue.add(main);
        Set<String> seen = new HashSet<>();
        Map<String, String> locks = new LinkedHashMap<>();
        boolean outputCut = false;
        while (!queue.isEmpty() && seen.size() < 8) {
            ThreadBlock thread = queue.removeFirst();
            if (!seen.add(thread.tid)) continue;
            out.append("thread tid=").append(thread.tid).append(" role=")
                    .append(thread.main ? "main" : "lock_owner").append(" state=").append(thread.state).append('\n');
            int frames = 0;
            for (String line : thread.body) {
                Matcher owner = OWNER.matcher(line);
                if (owner.find()) {
                    String tid = owner.group(1);
                    if (threads.containsKey(tid) && !seen.contains(tid)) queue.add(threads.get(tid));
                }
                String safe = null;
                Matcher frame = FRAME.matcher(line);
                Matcher nativeFrame = NATIVE.matcher(line);
                if (line.startsWith("at ") || line.startsWith("native:")) {
                    if (frames++ >= 40) { cut = true; continue; }
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
                    if (out.length() + safe.length() + 1 > MAX_OUTPUT - 512) { outputCut = true; break; }
                    out.append(safe).append('\n');
                }
            }
            if (outputCut) break;
        }
        if (!queue.isEmpty()) outputCut = true;
        String status = cut || outputCut ? "truncated" : "captured";
        return new Result(status, DiagnosticRedactor.redact(out.toString()));
    }
}
