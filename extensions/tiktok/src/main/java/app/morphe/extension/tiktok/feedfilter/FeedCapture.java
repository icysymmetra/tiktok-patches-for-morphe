/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import android.os.Build;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeBizExtKt;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * A recording of what the feed filters decide while someone reproduces a feed problem, started
 * and stopped from Diagnostics and saved as a text file.
 *
 * <p>The idea comes from icysymmetra's feed debugger, which walks TikTok's model objects by
 * reflection from a bytecode probe of its own. This one listens where Hushfeed already decides:
 * every route in {@link FeedItemsFilter} that keeps or hides a video writes one event per list,
 * with a line per item under it. A route asks {@link #batch} for somewhere to write and gets null
 * while nothing is recording, so the only cost outside a capture is one volatile read per list.
 *
 * <p>The file keeps the diagnostic report's promise. A video or creator id goes in as a short
 * code, a salted hash whose salt is drawn fresh for each capture and never written down, so one
 * video can be followed through a capture and no code means anything outside it. Captions,
 * names, handles and web addresses are never read. The labels that do go in (route names,
 * reasons, TikTok's pool names) pass through {@link DiagnosticRedactor} first.
 *
 * <p>Events sit in a rolling buffer of about 4 MiB of text. When it's full the oldest event
 * goes, and the file says how many went. A list read again unchanged, or read with no rule on,
 * is counted per route rather than written out, which is what keeps TikTok's frequent getter
 * reads from filling the buffer.
 */
public final class FeedCapture {
    /** About 4 MiB of event text, counted as UTF-8, before the oldest events go. */
    static final int BUDGET_BYTES = 4 * 1024 * 1024;
    /** KEEP and PASS lines one list may write. A profile page of two hundred videos is mostly these. */
    static final int MAX_KEPT_LINES = 40;
    /** HIDE, SPARE and BACK lines one list may write, which are what a capture is for. */
    static final int MAX_DECISION_LINES = 200;
    /** Bytes of the salted hash that make a code, written as hex. */
    private static final int CODE_BYTES = 6;
    private static final int MAX_LABEL_CHARS = 64;
    private static final int MAX_REMEMBERED_LABELS = 256;
    private static final int MAX_COUNTED_SOURCES = 64;
    private static final Pattern HANDLE = Pattern.compile("@[A-Za-z0-9_.]+");

    private static volatile Recording active;

    private FeedCapture() {
    }

    /** Whether a capture is running now. */
    public static boolean isRecording() {
        return active != null;
    }

    /** Starts a new capture, dropping one that was running without saving it. */
    public static void start() {
        start(BUDGET_BYTES);
    }

    /** A capture with a smaller buffer, so a test can fill it. */
    static void start(int budgetBytes) {
        active = new Recording(budgetBytes);
    }

    /** Ends the capture and hands it back to be saved, or null when none was running. */
    public static Recording stop() {
        Recording recording = active;
        active = null;
        if (recording != null) recording.stop();
        return recording;
    }

    /** Somewhere for one list's decisions to go, or null while nothing is recording. */
    static Batch batch(String source, int size) {
        Recording recording = active;
        return recording == null ? null : new Batch(recording, source, size);
    }

    /** One video a route decided about on its own, with null for kept. */
    static void single(String source, Aweme item, String reason) {
        Batch batch = batch(source, item == null ? 0 : 1);
        if (batch == null) return;
        if (reason == null) batch.kept(item);
        else batch.hidden(item, reason);
        batch.finish(reason == null ? 1 : 0);
    }

    /** A list the route had already filtered and found unchanged, so it read nothing again. */
    static void unchanged(String source) {
        Recording recording = active;
        if (recording != null) recording.count(recording.unchanged, source);
    }

    /** A list the route was handed with none of its rules switched on. */
    static void noRules(String source) {
        Recording recording = active;
        if (recording != null) recording.count(recording.noRules, source);
    }

    /** A stopped or running capture. Only {@link #text()} is for outside this package. */
    public static final class Recording {
        private final int budget;
        private final byte[] salt = new byte[16];
        private final long startedAtMs = System.currentTimeMillis();
        private final long startedAtNs = System.nanoTime();
        private final AtomicLong batches = new AtomicLong();
        private final ArrayDeque<String> events = new ArrayDeque<>();
        private final Map<String, Long> unchanged = new LinkedHashMap<>();
        private final Map<String, Long> noRules = new LinkedHashMap<>();
        private final Map<String, String> rulesBySource = new HashMap<>();
        private final Map<String, String> labels = new HashMap<>();
        private long used;
        private long droppedEvents;
        private long droppedBytes;
        private long stoppedAtMs;

        private Recording(int budget) {
            this.budget = budget;
            new SecureRandom().nextBytes(salt);
        }

        private synchronized void stop() {
            if (stoppedAtMs == 0) stoppedAtMs = System.currentTimeMillis();
        }

        private synchronized void add(String event) {
            if (stoppedAtMs != 0) return;
            long cost = utf8Length(event) + 1;
            if (cost > budget) {
                droppedEvents++;
                droppedBytes += cost;
                return;
            }
            events.addLast(event);
            used += cost;
            while (used > budget && !events.isEmpty()) {
                long oldest = utf8Length(events.removeFirst()) + 1;
                used -= oldest;
                droppedEvents++;
                droppedBytes += oldest;
            }
        }

        private synchronized void count(Map<String, Long> counts, String source) {
            if (stoppedAtMs != 0) return;
            String label = labelLocked(source);
            Long count = counts.get(label);
            if (count == null && counts.size() >= MAX_COUNTED_SOURCES) return;
            counts.put(label, count == null ? 1L : count + 1L);
        }

        /** The rules a route ran with, when they differ from the last list it filtered. */
        private synchronized String rulesIfChanged(String source, String rules) {
            if (rules == null) return null;
            String last = rulesBySource.get(source);
            if (rules.equals(last)) return null;
            if (last == null && rulesBySource.size() >= MAX_COUNTED_SOURCES) return rules;
            rulesBySource.put(source, rules);
            return rules;
        }

        private synchronized String label(String raw) {
            return labelLocked(raw);
        }

        /**
         * A route name, reason or pool name as it may go into the file: redacted, one word, and
         * short. The same few labels come back on every list, so each is worked out once.
         */
        private String labelLocked(String raw) {
            if (raw == null || raw.isEmpty()) return "-";
            String known = labels.get(raw);
            if (known != null) return known;
            // The redactor leaves a handle glued to the word before it ("FeedInsertion:@name"),
            // which is right for prose and wrong for a label, so those go too.
            String redacted = HANDLE.matcher(DiagnosticRedactor.redact(raw)).replaceAll("[handle omitted]");
            StringBuilder clean = new StringBuilder(Math.min(redacted.length(), MAX_LABEL_CHARS));
            for (int index = 0; index < redacted.length() && clean.length() < MAX_LABEL_CHARS; index++) {
                char c = redacted.charAt(index);
                clean.append(Character.isWhitespace(c) || Character.isISOControl(c)
                        || c == '=' || c == ',' ? '_' : c);
            }
            String label = clean.length() == 0 ? "-" : clean.toString();
            if (labels.size() < MAX_REMEMBERED_LABELS) labels.put(raw, label);
            return label;
        }

        /** The short code for an id, the same within this capture and meaningless outside it. */
        private String code(String value) {
            if (value == null || value.isEmpty()) return "-";
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(salt);
                byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(CODE_BYTES * 2);
                for (int index = 0; index < CODE_BYTES; index++) {
                    hex.append(HEX[(hash[index] >>> 4) & 0xF]).append(HEX[hash[index] & 0xF]);
                }
                return hex.toString();
            } catch (Exception unavailable) {
                // Never the value itself.
                return "?";
            }
        }

        private long elapsedMs() {
            return (System.nanoTime() - startedAtNs) / 1_000_000L;
        }

        /** The whole file. A stopped capture gives the same text every time it's asked. */
        public String text() {
            List<String> copy;
            Map<String, Long> unchangedCopy;
            Map<String, Long> noRulesCopy;
            long usedNow;
            long droppedNow;
            long droppedBytesNow;
            long stoppedNow;
            synchronized (this) {
                copy = new ArrayList<>(events);
                unchangedCopy = new LinkedHashMap<>(unchanged);
                noRulesCopy = new LinkedHashMap<>(noRules);
                usedNow = used;
                droppedNow = droppedEvents;
                droppedBytesNow = droppedBytes;
                stoppedNow = stoppedAtMs;
            }
            long end = stoppedNow == 0 ? System.currentTimeMillis() : stoppedNow;
            StringBuilder text = new StringBuilder((int) Math.min(Integer.MAX_VALUE - 1024L, usedNow + 2048L));
            text.append("Metra feed capture\n")
                    .append("Read this file before you share it. Videos and creators appear only as short codes")
                    .append(" made for this capture, and it carries no captions, names, handles or web addresses.\n")
                    .append("Metra: ").append(orUnknown(patchesVersion())).append('\n')
                    .append("TikTok: ").append(orUnknown(appVersion())).append('\n')
                    .append("Android API: ").append(Build.VERSION.SDK_INT).append('\n')
                    .append("Started: ").append(utc(startedAtMs)).append('\n')
                    .append("Stopped: ").append(stoppedNow == 0 ? "still running" : utc(stoppedNow))
                    .append(" (").append(Math.max(0, (end - startedAtMs) / 1000L)).append(" s)\n")
                    .append("Events kept: ").append(copy.size()).append(" (").append(usedNow)
                    .append(" of ").append(budget).append(" bytes)\n");
            if (droppedNow == 0) {
                text.append("Older events dropped: none\n");
            } else {
                text.append("Older events dropped to stay under the limit: ").append(droppedNow)
                        .append(" (").append(droppedBytesNow).append(" bytes)\n");
            }
            text.append("Read again unchanged, counted only: ").append(counts(unchangedCopy)).append('\n')
                    .append("No rule on, counted only: ").append(counts(noRulesCopy)).append('\n')
                    .append("Verdicts: KEEP stays, HIDE is hidden by the named rule, SPARE matched a rule")
                    .append(" and stays anyway, BACK was hidden and put back, PASS was never checked.\n")
                    .append("Item and creator codes are a salted hash made for this capture only.\n\n")
                    .append("Events\n");
            if (copy.isEmpty()) {
                text.append("Nothing was recorded. Check that the feed filter patch is in this build")
                        .append(" and that a feed was scrolled while the capture ran.\n");
            }
            for (String event : copy) text.append(event).append('\n');
            return text.toString();
        }
    }

    /** One list's decisions, written to the capture as a single event when the route finishes. */
    static final class Batch {
        private final Recording recording;
        private final String source;
        private final int size;
        private final long atMs;
        private final StringBuilder lines = new StringBuilder();
        private final LinkedHashMap<String, Integer> hidden = new LinkedHashMap<>();
        private String rules;
        private String note;
        private int keptLines;
        private int decisionLines;
        private int notListed;
        private int notVideos;
        private int spared;
        private int passed;

        private Batch(Recording recording, String source, int size) {
            this.recording = recording;
            this.source = source;
            this.size = size;
            this.atMs = recording.elapsedMs();
        }

        /** The rules this list ran through, by name. Written only when they changed for the route. */
        void rules(List<IFilter> content, List<IFilter> range) {
            StringBuilder names = new StringBuilder();
            appendNames(names, content);
            appendNames(names, range);
            rules = names.length() == 0 ? "none" : names.toString();
        }

        /** The rules as already-named words, for routes that aren't a list of filters. */
        void rules(String names) {
            rules = names == null || names.isEmpty() ? "none" : names;
        }

        /** One more rule beside the filters, such as a switch that isn't a filter of its own. */
        void addRule(String name) {
            rules = rules == null || rules.equals("none") ? name : rules + ',' + name;
        }

        void kept(Aweme item) {
            write("KEEP", null, item, null, null);
        }

        void hidden(Aweme item, String reason) {
            tally(reason, 1);
            write("HIDE", reason, item, null, null);
        }

        /** Matched a rule and stays, such as the reader's own post or a video a link opened. */
        void spared(Aweme item, String reason, String why) {
            spared++;
            write("SPARE", reason, item, null, why);
        }

        /** Hidden first, then put back so the list isn't left with nothing. */
        void putBack(Aweme item, String reason, String why) {
            tally(reason, -1);
            write("BACK", reason, item, null, why);
        }

        /** Let through without any rule looking at it. */
        void passed(Aweme item, String why) {
            passed++;
            write("PASS", null, item, null, why);
        }

        /** An entry that isn't a bare video, such as a search card or a Friends tab LIVE card. */
        void entry(String kind, Aweme item, String reason) {
            if (reason != null) tally(reason, 1);
            write(reason == null ? "KEEP" : "HIDE", reason, item, kind, null);
        }

        /** An element no rule could read, because it isn't a video. */
        void notVideo() {
            notVideos++;
        }

        /** A word on what the route did with the list as a whole. Several are joined with commas. */
        void note(String text) {
            note = note == null ? text : note + ',' + text;
        }

        /** Writes the list's event, {@code out} being how many entries it handed back. */
        void finish(int out) {
            StringBuilder event = new StringBuilder(96 + lines.length());
            event.append('+').append(atMs).append("ms #").append(recording.batches.incrementAndGet())
                    .append(' ').append(recording.label(source))
                    .append(" in=").append(size).append(" out=").append(out);
            if (!hidden.isEmpty()) {
                event.append(" hidden=");
                boolean first = true;
                for (Map.Entry<String, Integer> reason : hidden.entrySet()) {
                    if (reason.getValue() <= 0) continue;
                    if (!first) event.append(',');
                    event.append(recording.label(reason.getKey())).append(':').append(reason.getValue());
                    first = false;
                }
                if (first) event.append("none");
            }
            if (spared > 0) event.append(" spared=").append(spared);
            if (passed > 0) event.append(" passed=").append(passed);
            if (notVideos > 0) event.append(" notVideos=").append(notVideos);
            if (notListed > 0) event.append(" linesLeftOut=").append(notListed);
            String changed = recording.rulesIfChanged(source, rules);
            if (changed != null) event.append(" rules=").append(changed);
            if (note != null) event.append(" note=").append(note);
            event.append(lines);
            recording.add(event.toString());
        }

        private void tally(String reason, int delta) {
            String key = reason == null ? "-" : reason;
            Integer count = hidden.get(key);
            hidden.put(key, (count == null ? 0 : count) + delta);
        }

        private void write(String verdict, String reason, Aweme item, String kind, String why) {
            boolean decision = !"KEEP".equals(verdict) && !"PASS".equals(verdict);
            if (decision ? decisionLines >= MAX_DECISION_LINES : keptLines >= MAX_KEPT_LINES) {
                notListed++;
                return;
            }
            if (decision) decisionLines++;
            else keptLines++;
            lines.append("\n  ").append(verdict).append(' ')
                    .append(reason == null ? "-" : recording.label(reason));
            if (kind != null) lines.append(" kind=").append(recording.label(kind));
            if (why != null) lines.append(" why=").append(why);
            if (item == null) {
                lines.append(" item=-");
                return;
            }
            describe(item, reason);
        }

        /**
         * What the item is, never what it says. Each fact is read on its own, so a getter TikTok
         * renamed, or one a test fixture leaves out, costs that fact and is named under unread.
         */
        private void describe(Aweme item, String reason) {
            List<String> facts = new ArrayList<>(8);
            List<String> unread = new ArrayList<>(2);
            String aid = null;
            try {
                aid = item.getAid();
            } catch (Throwable failure) {
                unread.add("item");
            }
            String creator = null;
            try {
                creator = CreatorIdentity.uidOf(item);
            } catch (Throwable failure) {
                unread.add("creator");
            }
            lines.append(" item=").append(recording.code(aid))
                    .append(" creator=").append(recording.code(creator));
            for (int which = 0; which < FACTS.length; which++) {
                try {
                    if (fact(item, which)) facts.add(FACTS[which]);
                } catch (Throwable failure) {
                    unread.add(FACTS[which]);
                }
            }
            lines.append(" is=");
            appendWords(lines, facts, "none");
            try {
                lines.append(" plays=").append(plays(item));
            } catch (Throwable failure) {
                unread.add("plays");
            }
            try {
                lines.append(" pool=").append(recording.label(item.getItemDistributeSource()));
            } catch (Throwable failure) {
                unread.add("pool");
            }
            try {
                lines.append(" cache=").append(AwemeBizExtKt.getCacheSourceType(item));
            } catch (Throwable failure) {
                unread.add("cache");
            }
            if (FeedItemsFilter.AI_REASON.equals(reason)) {
                try {
                    lines.append(" ai=").append(recording.label(ContentMarkerFilters.aiSignal(item)));
                } catch (Throwable failure) {
                    unread.add("ai");
                }
            }
            if (!unread.isEmpty()) {
                lines.append(" unread=");
                appendWords(lines, unread, "");
            }
        }
    }

    /** Yes-or-no facts an item line lists when true. Nothing in them is text the creator wrote. */
    private static final String[] FACTS = {"ad", "softAd", "rawAd", "live", "story", "photo",
            "promoMusic", "commission", "reasonGiven"};

    @SuppressWarnings("rawtypes")
    private static boolean fact(Aweme item, int which) {
        switch (which) {
            case 0: return item.isAd();
            case 1: return item.isSoftAd();
            case 2: return item.getAwemeRawAd() != null;
            case 3: return LiveFilter.isLive(item);
            case 4: return item.getIsTikTokStory();
            case 5: {
                List images = item.getImageInfos();
                return (images != null && !images.isEmpty())
                        || item.getPhotoModeImageInfo() != null || item.getPhotoModeTextInfo() != null;
            }
            case 6: return item.isWithPromotionalMusic();
            case 7: return AdsFilter.hasCreatorCommissionDisclosure(item);
            default: return item.getRecReasonsStruct() != null;
        }
    }

    /** A play count as a band, so the number can't single out the video. */
    static String plays(Aweme item) {
        AwemeStatistics statistics = item.getStatistics();
        if (statistics == null) return "-";
        long count = statistics.getPlayCount();
        if (count <= 0) return "0";
        if (count < 1_000L) return "1-999";
        if (count < 10_000L) return "1K+";
        if (count < 100_000L) return "10K+";
        if (count < 1_000_000L) return "100K+";
        if (count < 10_000_000L) return "1M+";
        if (count < 100_000_000L) return "10M+";
        return "100M+";
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static void appendNames(StringBuilder names, List<IFilter> filters) {
        if (filters == null) return;
        for (IFilter filter : filters) {
            if (names.length() > 0) names.append(',');
            names.append(filter.getClass().getSimpleName());
        }
    }

    private static void appendWords(StringBuilder line, List<String> words, String empty) {
        if (words.isEmpty()) {
            line.append(empty);
            return;
        }
        for (int index = 0; index < words.size(); index++) {
            if (index > 0) line.append(',');
            line.append(words.get(index));
        }
    }

    /** Route counts, most first, then by name, so two captures read the same way. */
    private static String counts(Map<String, Long> counts) {
        if (counts.isEmpty()) return "none";
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        Collections.sort(sorted, (a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Long> entry : sorted) {
            if (text.length() > 0) text.append(", ");
            text.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return text.toString();
    }

    /** UTF-8 bytes without building them. */
    static long utf8Length(String text) {
        long bytes = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static String utc(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(ms));
    }

    private static String patchesVersion() {
        try {
            return Utils.getPatchesReleaseVersion();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String appVersion() {
        try {
            return Utils.getAppVersionName();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "unknown" : value;
    }
}
