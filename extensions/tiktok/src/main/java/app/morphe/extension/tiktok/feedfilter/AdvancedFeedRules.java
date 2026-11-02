/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class AdvancedFeedRules {
    private AdvancedFeedRules() {}

    /** Test-only observer for proving the feed path does not rebuild unchanged creator lists. */
    private static volatile Runnable creatorParseTestHook;
    private static final CreatorRuleCache BLOCKED_CREATOR_CACHE = new CreatorRuleCache();
    private static final CreatorRuleCache LOCAL_CREATOR_CACHE = new CreatorRuleCache();

    public static final class KeywordFilter implements IFilter {
        public boolean getEnabled() { return !Settings.BLOCKED_CAPTION_WORDS.get().trim().isEmpty(); }
        public boolean getFiltered(Aweme item) {
            String caption = Reflect.string(item, "getDesc", "desc");
            if (caption == null) return false;
            // Plain phrases still mean what they always did. The list also takes
            // "a" & "b" and "a" !& "b", which a phrase on its own cannot say.
            return KeywordRules.anyMatches(
                    KeywordRules.cached(Settings.BLOCKED_CAPTION_WORDS.get()), caption);
        }
    }

    /**
     * Blocked caption words matched against the words of the post's text stickers, the text a
     * creator types over the video in TikTok's editor. Text burned into the video's pictures
     * isn't in the post and can't be read.
     */
    public static final class StickerTextFilter implements IFilter {
        public boolean getEnabled() {
            return Settings.BLOCKED_WORDS_IN_STICKERS.get()
                    && !Settings.BLOCKED_CAPTION_WORDS.get().trim().isEmpty();
        }
        public boolean getFiltered(Aweme item) {
            List<String> texts = stickerTexts(item);
            if (texts.isEmpty()) return false;
            List<KeywordRules.Rule> rules = KeywordRules.cached(Settings.BLOCKED_CAPTION_WORDS.get());
            for (String text : texts) {
                if (KeywordRules.anyMatches(rules, text)) return true;
            }
            return false;
        }
    }

    /**
     * TikTok's type for a text sticker. Its textStruct holds the sticker's words as plain text,
     * the string TikTok's own translation service sends; a few other types keep JSON there.
     */
    static final int TEXT_STICKER_TYPE = 18;
    /**
     * A text sticker that also carries a caption model. TikTok's editor writes the same typed
     * text into its textStruct (TextStickerModel.getInteractStickerStruct picks 20 or 18).
     */
    static final int CAPTIONED_TEXT_STICKER_TYPE = 20;

    /** The words of each text sticker on {@code item}, in order, or an empty list. */
    static List<String> stickerTexts(Object item) {
        Object stickers = Reflect.property(item, "getInteractStickerStructs", "interactStickerStructs");
        if (!(stickers instanceof List)) return Collections.emptyList();
        List<String> texts = new ArrayList<>();
        for (Object sticker : (List<?>) stickers) {
            if (sticker == null) continue;
            Object type = Reflect.property(sticker, "getType", "type");
            if (!(type instanceof Integer)) continue;
            int kind = (Integer) type;
            if (kind != TEXT_STICKER_TYPE && kind != CAPTIONED_TEXT_STICKER_TYPE) continue;
            String text = Reflect.string(sticker, "getTextStruct", "textStruct");
            if (text != null) texts.add(text);
        }
        return texts;
    }

    public static final class CreatorFilter implements IFilter {
        public boolean getEnabled() {
            return !Settings.BLOCKED_CREATORS.get().trim().isEmpty()
                    || !Settings.LOCAL_HIDDEN_CREATORS.get().trim().isEmpty();
        }
        public boolean getFiltered(Aweme item) {
            return blocks(CreatorIdentity.of(item));
        }
    }

    /** Whether Blocked creators or Creators hidden on this phone names {@code who}. */
    static boolean blocks(CreatorIdentity who) {
        return BLOCKED_CREATOR_CACHE.get(Settings.BLOCKED_CREATORS.get()).matches(
                who.normalizedUid, who.normalizedSecUid, who.normalizedHandle, who.handle, who.nickname)
                || LOCAL_CREATOR_CACHE.get(Settings.LOCAL_HIDDEN_CREATORS.get()).matches(
                who.normalizedUid, who.normalizedSecUid, who.normalizedHandle, who.handle, who.nickname);
    }

    /**
     * Whether the block lists name one creator, given only the handle or id typed for it.
     * The entry is tried as each of the three ids and, for a pattern, as the handle.
     */
    static boolean blockListsName(String entry) {
        String normalized = normalizedCreator(entry);
        if (normalized.isEmpty()) return false;
        return BLOCKED_CREATOR_CACHE.get(Settings.BLOCKED_CREATORS.get()).matches(
                normalized, normalized, normalized, normalized, null)
                || LOCAL_CREATOR_CACHE.get(Settings.LOCAL_HIDDEN_CREATORS.get()).matches(
                normalized, normalized, normalized, normalized, null);
    }

    /** One setting's exact-source snapshot. Blocked and local lists own separate instances. */
    private static final class CreatorRuleCache {
        private final Object lock = new Object();
        private volatile CreatorRules current;

        CreatorRules get(String source) {
            CreatorRules found = current;
            if (found != null && Objects.equals(found.source, source)) return found;
            synchronized (lock) {
                found = current;
                if (found == null || !Objects.equals(found.source, source)) {
                    found = parseCreatorRules(source);
                    current = found;
                }
                return found;
            }
        }

        void clear() {
            synchronized (lock) {
                current = null;
            }
        }
    }

    /** Immutable exact names and patterns for one creator-list string. */
    private static final class CreatorRules {
        final String source;
        final Set<String> exactNames;
        final List<Pattern> patterns;

        CreatorRules(String source, Set<String> exactNames, List<Pattern> patterns) {
            this.source = source;
            this.exactNames = exactNames;
            this.patterns = patterns;
        }

        boolean matches(String uid, String secUid, String normalizedHandle,
                String handle, String nickname) {
            if ((!uid.isEmpty() && exactNames.contains(uid))
                    || (!secUid.isEmpty() && exactNames.contains(secUid))
                    || (!normalizedHandle.isEmpty() && exactNames.contains(normalizedHandle))) {
                return true;
            }
            for (Pattern pattern : patterns) {
                if (matchesPattern(pattern, handle) || matchesPattern(pattern, nickname)) return true;
            }
            return false;
        }
    }

    private static CreatorRules parseCreatorRules(String source) {
        Runnable hook = creatorParseTestHook;
        if (hook != null) hook.run();
        Set<String> exact = new LinkedHashSet<>();
        List<Pattern> patterns = new ArrayList<>();
        for (String entry : rawTerms(source)) {
            if (isPattern(entry)) {
                Pattern pattern = compiled(entry);
                if (pattern != null) patterns.add(pattern);
                continue;
            }
            String normalized = normalizedCreator(entry);
            if (!normalized.isEmpty()) exact.add(normalized);
        }
        return new CreatorRules(source, Collections.unmodifiableSet(exact),
                Collections.unmodifiableList(patterns));
    }

    /**
     * A handle or id the way every list and every item is compared: trimmed, one leading @
     * dropped, lower case. The editor's duplicate check and the feed's match share it, so an
     * entry the editor refuses as already present is one the feed would have matched.
     */
    static String normalizedCreator(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.startsWith("@")) normalized = normalized.substring(1);
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesPattern(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).find();
    }

    /** Filters posts whose known publication time is older than the user's age limit. */
    public static final class PublicationAgeFilter implements IFilter {
        private static final long DAY_MS = 86_400_000L;

        @Override
        public boolean getEnabled() {
            return Settings.MAX_PUBLICATION_AGE_DAYS.get() > 0;
        }

        @Override
        public boolean getFiltered(Aweme item) {
            Object raw = Reflect.property(item, "getCreateTime", "createTime");
            return olderThan(publicationTimeMillis(raw), System.currentTimeMillis(),
                    Settings.MAX_PUBLICATION_AGE_DAYS.get());
        }

        /** Converts TikTok seconds or milliseconds to milliseconds without overflowing. */
        static long publicationTimeMillis(Object raw) {
            if (!(raw instanceof Number)) return 0;
            long timestamp = ((Number) raw).longValue();
            if (timestamp <= 0) return 0;
            if (timestamp < 100_000_000_000L) {
                if (timestamp > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
                return timestamp * 1000L;
            }
            return timestamp;
        }

        /** Uses a strict cutoff so a post exactly at the chosen age remains visible. */
        static boolean olderThan(long timestampMillis, long nowMillis, long ageDays) {
            if (timestampMillis <= 0 || ageDays <= 0 || timestampMillis > nowMillis) return false;
            long ageMillis = ageDays > Long.MAX_VALUE / DAY_MS
                    ? Long.MAX_VALUE : ageDays * DAY_MS;
            long cutoff = nowMillis < ageMillis ? Long.MIN_VALUE : nowMillis - ageMillis;
            return timestampMillis < cutoff;
        }
    }

    /**
     * What is wrong with a blocked-creator list as typed, or null when nothing is. Named so
     * the reader can see which entry to fix, since the list is one long comma separated line
     * and a pattern that will not compile is otherwise only reported at the next feed page.
     */
    @Nullable
    public static String creatorEntryProblem(String list) {
        if (list == null) return null;
        String limitProblem = FeedRuleLimits.creatorProblem(list);
        if (limitProblem != null) return limitProblem;
        for (String entry : rawTerms(list)) {
            if (!isPattern(entry)) continue;
            String source = entry.substring(1, entry.length() - 1);
            if (source.length() > MAX_PATTERN_LENGTH) {
                return L10n.f(
                        "That creator pattern is too long, so it wasn't added: %1$s",
                        entry);
            }
            try {
                Pattern.compile(source, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException invalid) {
                return L10n.f("Metra can't read the creator pattern %1$s", L10n.isolate(entry));
            }
            if (couldStall(source)) {
                return L10n.f("That creator pattern could stall the feed, so it wasn't added: %1$s",
                        L10n.isolate(entry));
            }
        }
        return null;
    }

    /** Returns non-empty creator entries in their stored order. */
    public static List<String> creatorEntries(String value) {
        List<String> entries = new ArrayList<>();
        if (value == null) return entries;
        for (String entry : rawTerms(value)) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) entries.add(trimmed);
        }
        return entries;
    }

    /** Joins creator entries in the format used by the settings editor. */
    public static String joinCreatorEntries(List<String> entries) {
        StringBuilder joined = new StringBuilder();
        if (entries == null) return "";
        for (String entry : entries) {
            if (entry == null || entry.trim().isEmpty()) continue;
            if (joined.length() > 0) joined.append(", ");
            joined.append(entry.trim());
        }
        return joined.toString();
    }

    /** Adds one exact creator entry unless it is already present, ignoring case and @. */
    public static String addCreatorEntry(String value, String entry) {
        String candidate = entry == null ? "" : entry.trim();
        List<String> entries = creatorEntries(value);
        if (candidate.isEmpty()) return joinCreatorEntries(entries);
        if (!hasCreatorEntry(entries, candidate)) entries.add(candidate);
        return joinCreatorEntries(entries);
    }

    /** Removes one exact creator entry, ignoring case and a leading @. */
    public static String removeCreatorEntry(String value, String entry) {
        String candidate = entry == null ? "" : entry.trim();
        List<String> entries = creatorEntries(value);
        if (!candidate.isEmpty()) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                if (sameCreatorEntry(entries.get(index), candidate)) entries.remove(index);
            }
        }
        return joinCreatorEntries(entries);
    }

    /** Whether the exact creator entry is already present, ignoring case and a leading @. */
    public static boolean hasCreatorEntry(String value, String entry) {
        return hasCreatorEntry(creatorEntries(value), entry);
    }

    private static boolean hasCreatorEntry(List<String> entries, String entry) {
        for (String existing : entries) {
            if (sameCreatorEntry(existing, entry)) return true;
        }
        return false;
    }

    private static boolean sameCreatorEntry(String left, String right) {
        String a = normalizedCreator(left);
        return !a.isEmpty() && a.equals(normalizedCreator(right));
    }

    /** An entry between slashes is a pattern rather than a name to match exactly. */
    static boolean isPattern(String entry) {
        return entry.length() > 2 && entry.startsWith("/") && entry.endsWith("/");
    }

    /** One entry that will not compile, remembered so it is only ever said once. */
    private static final Pattern INVALID = Pattern.compile("");
    /**
     * Every entry seen so far, compiled. Each item on a feed page runs through every entry,
     * so a cache of one would never hit; and a map keeps the answer and the entry it belongs
     * to together, which two fields written in sequence do not.
     */
    private static final Map<String, Pattern> COMPILED = new ConcurrentHashMap<>();

    /**
     * How long a creator pattern may be. It is user input and it runs against every name in
     * every feed page. Anything a person types to match a handle fits well inside this, and
     * the list rides along in a settings backup, which is the way somebody else's pattern
     * could arrive. The shape that makes a pattern slow is refused on its own, by
     * {@link #couldStall}, since ten characters are enough for that.
     */
    private static final int MAX_PATTERN_LENGTH = 200;

    /**
     * The pattern for one entry, compiled once. A pattern that will not compile is dropped
     * and said once, because the entry otherwise looks like it is working.
     */
    static Pattern compiled(String entry) {
        Pattern cached = COMPILED.get(entry);
        if (cached != null) {
            return cached == INVALID ? null : cached;
        }

        String source = entry.substring(1, entry.length() - 1);
        if (source.length() > MAX_PATTERN_LENGTH) {
            COMPILED.put(entry, INVALID);
            Utils.showToastLong(L10n.f(
                    "That creator pattern is too long to use, so it was skipped: %1$s", entry));
            return null;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(source, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException invalid) {
            COMPILED.put(entry, INVALID);
            Utils.showToastLong(L10n.f("Metra can't read the creator pattern %1$s", L10n.isolate(entry)));
            return null;
        }
        if (couldStall(source)) {
            COMPILED.put(entry, INVALID);
            Utils.showToastLong(L10n.f(
                    "That creator pattern could stall the feed, so it was skipped: %1$s",
                    L10n.isolate(entry)));
            return null;
        }
        COMPILED.put(entry, pattern);
        return pattern;
    }

    /** How much work a creator pattern may promise, counted in doublings: about a million steps. */
    private static final int STALL_LIMIT_DOUBLINGS = 20;

    /** One open-ended repeat over a name, taken as 32 characters long. */
    private static final int DOUBLINGS_PER_REPEAT = 5;

    /**
     * Whether a creator pattern could keep the feed thread matching for seconds or longer.
     *
     * <p>The regex engine has no time limit and nothing can interrupt it. On a phone it copies
     * the name to a String and matches natively in ICU, so the guard this replaced, a wrapper
     * that counted the engine's reads, never saw a single read there: it only ever worked on
     * the desktop JVM the tests run on. What can be bounded is the pattern's shape, before it
     * runs. Refused are a back-reference; a repeated group holding a repeat or a choice, as in
     * {@code (a+)+} or {@code (a|ab)*}, whose ways of splitting a name double with every
     * character; comment mode, whose spaces and # comments this reading does not skip; and a
     * pattern whose work adds up to more than {@link #STALL_LIMIT_DOUBLINGS} doublings. Each
     * open-ended repeat multiplies the work by the name's length, and so does find() trying
     * every start unless the pattern is held to the start with ^; an optional part doubles it,
     * and a choice of k adds log2 k to the costliest of its alternatives, which are counted
     * apart since only one of them runs at a time. That leaves three wildcards, four words
     * held to the start, or two wildcards beside a dozen alternatives, for anything written
     * to match a handle. Only runs on a pattern that compiled, so the syntax is already known
     * to be sound.
     */
    static boolean couldStall(String source) {
        java.util.ArrayDeque<int[]> outer = new java.util.ArrayDeque<>();
        int[] group = new int[FRAME];
        int[] closed = null;
        // What a quantifier here would repeat: 0 nothing, 1 a single atom, 2 the group just closed.
        int last = 0;
        int length = source.length();
        int index = 0;
        while (index < length) {
            char c = source.charAt(index);
            if (c == '\\') {
                if (index + 1 >= length) return true;
                char escaped = source.charAt(index + 1);
                if (escaped >= '1' && escaped <= '9') return true;
                if (escaped == 'k' && index + 2 < length && source.charAt(index + 2) == '<') return true;
                index = afterEscape(source, index);
                last = 1;
            } else if (c == '[') {
                index = afterClass(source, index);
                if (index < 0) return true;
                last = 1;
            } else if (c == '(') {
                index++;
                if (index < length && source.charAt(index) == '?') {
                    index++;
                    if (index + 1 < length && source.charAt(index) == '<'
                            && source.charAt(index + 1) != '=' && source.charAt(index + 1) != '!') {
                        int end = source.indexOf('>', index);
                        index = end < 0 ? length : end + 1;
                    } else {
                        // Comment mode turned on, not off: (?-x) is the one that turns it off.
                        boolean turningOff = false;
                        while (index < length && ":=!<>)".indexOf(source.charAt(index)) < 0) {
                            char flag = source.charAt(index);
                            if (flag == '-') turningOff = true;
                            else if (flag == 'x' && !turningOff) return true;
                            index++;
                        }
                        if (index < length && source.charAt(index) == ')') {
                            // Flags alone, as in (?i): nothing opened and nothing to repeat.
                            index++;
                            last = 0;
                            continue;
                        }
                        if (index < length && source.charAt(index) == '<') index++;
                        index++;
                    }
                }
                outer.push(group);
                group = new int[FRAME];
                last = 0;
            } else if (c == ')') {
                // A close with nothing open is the same disagreement as a group left open.
                if (outer.isEmpty()) return true;
                closed = group;
                group = outer.pop();
                // The group runs as one step of the alternative around it, and costs what its
                // costliest alternative does plus the choice between them.
                group[CUR_REPEATS] += Math.max(closed[MAX_REPEATS], closed[CUR_REPEATS]);
                group[CUR_DOUBLINGS] += Math.max(closed[MAX_DOUBLINGS], closed[CUR_DOUBLINGS])
                        + choiceDoublings(closed[BARS]);
                if (closed[VARIES] != 0 || closed[BARS] != 0 || closed[NESTED] != 0) group[NESTED] = 1;
                index++;
                last = 2;
            } else if (c == '|') {
                group[BARS]++;
                group[MAX_REPEATS] = Math.max(group[MAX_REPEATS], group[CUR_REPEATS]);
                group[MAX_DOUBLINGS] = Math.max(group[MAX_DOUBLINGS], group[CUR_DOUBLINGS]);
                group[CUR_REPEATS] = 0;
                group[CUR_DOUBLINGS] = 0;
                index++;
                last = 0;
            } else if (c == '*' || c == '+' || c == '?' || c == '{') {
                int min;
                int max; // -1 for no upper bound
                if (c == '{') {
                    int end = source.indexOf('}', index);
                    if (end < 0) return true;
                    String bounds = source.substring(index + 1, end);
                    int comma = bounds.indexOf(',');
                    try {
                        min = Integer.parseInt((comma < 0 ? bounds : bounds.substring(0, comma)).trim());
                        String upper = comma < 0 ? bounds : bounds.substring(comma + 1);
                        max = upper.trim().isEmpty() ? -1 : Integer.parseInt(upper.trim());
                    } catch (NumberFormatException unread) {
                        return true;
                    }
                    index = end + 1;
                } else {
                    min = c == '+' ? 1 : 0;
                    max = c == '?' ? 1 : -1;
                    index++;
                }
                // Lazy and possessive forms promise no less work in the worst case.
                if (index < length && (source.charAt(index) == '?' || source.charAt(index) == '+')) index++;
                boolean repeated = max < 0 || max > 1;
                if (last == 2 && repeated && closed != null
                        && (closed[VARIES] != 0 || closed[BARS] != 0 || closed[NESTED] != 0)) {
                    return true;
                }
                if (max < 0 || max != min) {
                    group[VARIES] = 1;
                    if (max == 1) group[CUR_DOUBLINGS]++;
                    else group[CUR_REPEATS]++;
                }
                last = 0;
            } else {
                index++;
                last = 1;
            }
        }
        // A group left open means this reading and the engine's disagree, and the engine is the
        // one that compiled it. Refused, rather than trusting a count that may have missed a part.
        if (!outer.isEmpty()) return true;
        int repeats = Math.max(group[MAX_REPEATS], group[CUR_REPEATS]);
        int doublings = Math.max(group[MAX_DOUBLINGS], group[CUR_DOUBLINGS]) + choiceDoublings(group[BARS]);
        // find() tries every start of the name, which is one more factor of its length, unless
        // the whole pattern is held to the start.
        int starts = group[BARS] == 0 && anchoredAtStart(source) ? 0 : 1;
        return DOUBLINGS_PER_REPEAT * (repeats + starts) + doublings > STALL_LIMIT_DOUBLINGS;
    }

    /** couldStall's per-group counts: what makes a repeat of the group dangerous, and its cost. */
    private static final int VARIES = 0;
    private static final int BARS = 1;
    private static final int NESTED = 2;
    private static final int CUR_REPEATS = 3;
    private static final int MAX_REPEATS = 4;
    private static final int CUR_DOUBLINGS = 5;
    private static final int MAX_DOUBLINGS = 6;
    private static final int FRAME = 7;

    /**
     * Whether a match can only begin at the start of the name: ^ or \A first, after any flags,
     * and no multiline mode, where ^ also matches after every line break.
     */
    private static boolean anchoredAtStart(String source) {
        int index = 0;
        while (source.startsWith("(?", index)) {
            int close = source.indexOf(')', index);
            if (close < 0) return false;
            String flags = source.substring(index + 2, close);
            for (int at = 0; at < flags.length(); at++) {
                char flag = flags.charAt(at);
                if (flag == 'm') return false;
                if (!Character.isLetter(flag) && flag != '-') return false;
            }
            index = close + 1;
        }
        return source.startsWith("^", index) || source.startsWith("\\A", index);
    }

    /**
     * Where the escape at {@code start} ends. Most are two characters; {@code \cX} is three, the
     * braced forms run to their brace, and {@code \Q} quotes everything up to {@code \E}. Read
     * the same inside a class as outside: a quoted or control bracket read as a real one moved
     * the end of the class, and a repeat after it was taken for part of the class.
     */
    private static int afterEscape(String source, int start) {
        int length = source.length();
        if (start + 1 >= length) return length;
        char escaped = source.charAt(start + 1);
        if (escaped == 'Q') {
            int end = source.indexOf("\\E", start + 2);
            return end < 0 ? length : end + 2;
        }
        if (escaped == 'c') return Math.min(length, start + 3);
        int index = start + 2;
        if ((escaped == 'p' || escaped == 'P' || escaped == 'x' || escaped == 'N')
                && index < length && source.charAt(index) == '{') {
            int end = source.indexOf('}', index);
            return end < 0 ? length : end + 1;
        }
        return index;
    }

    /** log2 of the ways through a group with this many bars, rounded up. */
    private static int choiceDoublings(int bars) {
        return bars <= 0 ? 0 : 32 - Integer.numberOfLeadingZeros(bars);
    }

    /**
     * Where a character class that opens at {@code start} ends, nested classes included, or -1
     * when it never closes here, which can only mean this reading has gone wrong.
     */
    private static int afterClass(String source, int start) {
        int depth = 0;
        int index = start;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (c == '\\') {
                index = afterEscape(source, index);
                continue;
            }
            index++;
            if (c == '[') {
                depth++;
                // A ] straight after the opening bracket, or after [^, is a literal member.
                if (index < source.length() && source.charAt(index) == '^') index++;
                if (index < source.length() && source.charAt(index) == ']') index++;
            } else if (c == ']' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    public static final class PromotionalMusicFilter implements IFilter {
        public boolean getEnabled() { return Settings.HIDE_PROMOTIONAL_MUSIC.get(); }
        public boolean getFiltered(Aweme item) { return item.isWithPromotionalMusic(); }
    }

    public static final class LiveReplayFilter implements IFilter {
        public boolean getEnabled() { return Settings.HIDE_LIVE_REPLAYS.get(); }
        public boolean getFiltered(Aweme item) { return item.isLiveReplay(); }
    }

    /**
     * For You videos TikTok sends without choosing them for this reader. TikTok marks the fill-in
     * batches it serves when it has nothing personal ready with the distribute source
     * {@code for_you_page_999} and no recommendation reason; a picked video carries one. This is
     * a hard rejection on For You responses and cache deliveries, including an entire batch.
     */
    public static final class UnpersonalizedForYouFilter implements IFilter {
        static final String FILL_IN_SOURCE = "for_you_page_999";

        public boolean getEnabled() { return Settings.HIDE_UNPERSONALIZED_FOR_YOU.get(); }
        public boolean getFiltered(Aweme item) {
            return FILL_IN_SOURCE.equals(item.getItemDistributeSource()) && item.getRecReasonsStruct() == null;
        }
    }

    /** Last content rule, so fallback candidates have passed every hard block. */
    public static final class QualityFilter implements IFilter {
        public boolean getEnabled() {
            return Settings.MAX_VIDEO_SECONDS.get() > 0 || Settings.MIN_VIDEO_SECONDS.get() > 0
                    || Settings.MAX_VIEWS_PER_LIKE.get() > 0 || Settings.MAX_VIEWS_PER_COMMENT.get() > 0;
        }
        public boolean getFiltered(Aweme item) { return distance(item) > 0; }

        static double distance(Aweme item) {
            double distance = 0;
            int seconds = Settings.MAX_VIDEO_SECONDS.get();
            int least = Settings.MIN_VIDEO_SECONDS.get();
            if (seconds > 0 || least > 0) {
                Object video = Reflect.property(item, "getVideo", "video");
                long ms = positive(Reflect.property(video, "getDuration", "videoLength"));
                if (ms <= 0) ms = positive(Reflect.property(video, "getPilotLength", "pilotLength"));
                if (ms > 0 && seconds > 0) distance = Math.max(0, ms / (seconds * 1000.0) - 1);
                // A photo post's length is its sound's, and an unknown length is no reason to hide
                // a video: neither is held to the minimum (#154).
                if (ms > 0 && least > 0 && !ImageVideoFilter.isPhotoPost(item)) {
                    distance = Math.max(distance, least * 1000.0 / ms - 1);
                }
            }
            int maxPerLike = Settings.MAX_VIEWS_PER_LIKE.get();
            int maxPerComment = Settings.MAX_VIEWS_PER_COMMENT.get();
            AwemeStatistics stats = (maxPerLike > 0 || maxPerComment > 0) ? item.getStatistics() : null;
            if (stats != null) {
                long views = stats.getPlayCount();
                if (views > 0 && maxPerLike > 0) {
                    long likes = stats.getDiggCount();
                    if (likes >= 0) {
                        double ratio = likes == 0 ? Double.MAX_VALUE : views / (double) likes;
                        distance = Math.max(distance, ratio / maxPerLike - 1);
                    }
                }
                if (views > 0 && maxPerComment > 0) {
                    long comments = stats.getCommentCount();
                    if (comments >= 0) {
                        double ratio = comments == 0 ? Double.MAX_VALUE : views / (double) comments;
                        distance = Math.max(distance, ratio / maxPerComment - 1);
                    }
                }
            }
            return distance;
        }
    }

    private static long positive(Object value) {
        return value instanceof Number ? Math.max(0, ((Number) value).longValue()) : 0;
    }

    /**
     * The same entries with their case intact, which a pattern needs: lower casing turns
     * \D into \d.
     *
     * A comma inside a pattern is not a separator, so a fragment that opens a pattern takes
     * the ones after it until one closes it. A line is as far as that reaches, and a pattern
     * nothing closes gives its fragments back as they were: a stray slash must not swallow
     * the names after it.
     */
    static String[] rawTerms(String value) {
        List<String> entries = new ArrayList<>();
        if (value == null || value.trim().isEmpty()) return new String[0];
        for (String line : value.trim().split("\\s*\\n\\s*")) {
            List<String> pending = null;
            for (String fragment : line.split("\\s*,\\s*")) {
                if (pending != null) {
                    pending.add(fragment);
                    if (fragment.endsWith("/")) {
                        entries.add(String.join(",", pending));
                        pending = null;
                    }
                    continue;
                }
                if (fragment.startsWith("/") && !isPattern(fragment)) {
                    pending = new ArrayList<>();
                    pending.add(fragment);
                    continue;
                }
                entries.add(fragment);
            }
            if (pending != null) entries.addAll(pending);
        }
        return entries.toArray(new String[0]);
    }

    static void setCreatorParseTestHookForTests(Runnable hook) {
        creatorParseTestHook = hook;
        BLOCKED_CREATOR_CACHE.clear();
        LOCAL_CREATOR_CACHE.clear();
    }
}
