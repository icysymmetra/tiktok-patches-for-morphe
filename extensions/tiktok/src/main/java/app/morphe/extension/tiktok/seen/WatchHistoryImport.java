/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seen;

import app.morphe.extension.shared.settings.SettingsJson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Reads reviewed watch-history fields only. No link is opened or resolved over the network. */
public final class WatchHistoryImport {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final SettingsJson.Limits JSON_LIMITS =
            new SettingsJson.Limits(24, 100_000, 64 * 1024, 100_000, MAX_BYTES);
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final Pattern VIDEO_PATH = Pattern.compile(
            "/(?:share/video/|@[^/]+/video/)([0-9]{1,20})/?");

    public enum Reason { TOO_LARGE, TOO_MANY, UNSUPPORTED, DAMAGED, UNREADABLE }

    public static final class Rejected extends IOException {
        public final Reason reason;

        private Rejected(Reason reason) {
            this(reason, null);
        }

        private Rejected(Reason reason, Throwable cause) {
            super("Watch-history import rejected: " + reason, cause);
            this.reason = reason;
        }
    }

    public static final class Records {
        public final Map<String, Long> videos;
        public final int skipped;
        public final int total;

        Records(Map<String, Long> videos, int skipped, int total) {
            this.videos = Collections.unmodifiableMap(new HashMap<>(videos));
            this.skipped = skipped;
            this.total = total;
        }
    }

    private WatchHistoryImport() {}

    /** The caller captures the phone's time zone before opening the picker, rather than guessing UTC. */
    public static Records read(InputStream input, TimeZone timeZone, long nowMs) throws IOException {
        if (input == null) throw new Rejected(Reason.UNREADABLE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream owned = input) {
            byte[] buffer = new byte[8192];
            for (;;) {
                int count = owned.read(buffer);
                if (count < 0) break;
                if (count == 0) {
                    int value = owned.read();
                    if (value < 0) break;
                    if (bytes.size() == MAX_BYTES) throw new Rejected(Reason.TOO_LARGE);
                    bytes.write(value);
                    continue;
                }
                if (count > MAX_BYTES - bytes.size()) throw new Rejected(Reason.TOO_LARGE);
                bytes.write(buffer, 0, count);
            }
        } catch (Rejected refused) {
            throw refused;
        } catch (IOException failure) {
            throw new Rejected(Reason.UNREADABLE, failure);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException damaged) {
            throw new Rejected(Reason.DAMAGED, damaged);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        JSONObject root;
        try {
            root = SettingsJson.parseObject(text, JSON_LIMITS);
        } catch (JSONException | IOException damaged) {
            throw new Rejected(Reason.DAMAGED, damaged);
        }
        // Reviewed export, data dated 2024-12-15, file updated 2025-04-18.
        // https://github.com/Sbell-ppd/TikTokMiniGamesPrivacy/blob/
        // a7a2898475986804b694e9a9a981e5ebf08ba2b4/user_data_tiktok.json
        // Its Date has no zone. The UI and README explicitly use the picker-opening phone zone.
        JSONObject activity = root.optJSONObject("Your Activity");
        JSONObject history = activity == null ? null : activity.optJSONObject("Watch History");
        JSONArray list = history == null ? null : history.optJSONArray("VideoList");
        if (list == null) throw new Rejected(Reason.UNSUPPORTED);
        if (list.length() > SeenVideoHistory.MAX_RECORDS) throw new Rejected(Reason.TOO_MANY);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setLenient(false);
        format.setTimeZone((TimeZone) timeZone.clone());
        Map<String, Long> videos = new HashMap<>();
        int skipped = 0;
        for (int index = 0; index < list.length(); index++) {
            JSONObject row = list.optJSONObject(index);
            Object date = row == null ? null : row.opt("Date");
            Object link = row == null ? null : row.opt("Link");
            String id = link instanceof String ? videoId((String) link) : null;
            Long watched = date instanceof String ? timestamp((String) date, format, nowMs) : null;
            if (id == null || watched == null) {
                skipped++;
                continue;
            }
            Long previous = videos.get(id);
            if (previous != null) skipped++;
            if (previous == null || previous < watched) videos.put(id, watched);
        }
        return new Records(videos, skipped, list.length());
    }

    private static Long timestamp(String value, SimpleDateFormat format, long nowMs) {
        if (!DATE.matcher(value).matches()) return null;
        ParsePosition position = new ParsePosition(0);
        Date parsed = format.parse(value, position);
        if (parsed == null || position.getIndex() != value.length()
                || !value.equals(format.format(parsed))) return null;
        long time = parsed.getTime();
        return time > 0 && time <= nowMs ? time : null;
    }

    private static String videoId(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getHost() == null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.equals("tiktok.com") && !host.equals("www.tiktok.com")
                    && !host.equals("tiktokv.com") && !host.equals("www.tiktokv.com")) return null;
            Matcher path = VIDEO_PATH.matcher(uri.getRawPath() == null ? "" : uri.getRawPath());
            return path.matches() ? path.group(1) : null;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }
}
