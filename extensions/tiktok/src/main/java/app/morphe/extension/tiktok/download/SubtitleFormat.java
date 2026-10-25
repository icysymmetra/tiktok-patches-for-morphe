/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import app.morphe.extension.shared.settings.SettingsJson;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class SubtitleFormat {
    /**
     * A caption file is a flat list of cues, so anything deeper than a handful of levels is not
     * one. The byte cap matches the 2 MB the transfer already refuses to exceed.
     */
    private static final SettingsJson.Limits CAPTION_LIMITS =
            new SettingsJson.Limits(16, 400_000, 100_000, 20_000, 2 * 1024 * 1024);
    private static final Pattern TIMELINE = Pattern.compile("^((?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{3})\\s+-->\\s+((?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{3})(?:\\s+.*)?$");
    private SubtitleFormat() { }

    static String toSrt(String source, String format) throws IOException {
        if (source.length() > 2 * 1024 * 1024) throw new IOException("Subtitle file is too large");
        String input = source.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder output = new StringBuilder();
        int count = 0;
        if ("creator_caption".equals(format) || "json".equals(format) || input.startsWith("{")) {
            try {
                // The platform parser recurses, so a deeply nested file from a caption host
                // raises StackOverflowError, which is not a JSONException and not a
                // RuntimeException either: it went past every catch and killed the worker.
                JSONArray cues = SettingsJson.parseObject(input, CAPTION_LIMITS)
                        .getJSONArray("utterances");
                for (int i = 0; i < cues.length(); i++) {
                    JSONObject cue = cues.getJSONObject(i);
                    append(output, ++count, cue.getLong("start_time"), cue.getLong("end_time"), cue.getString("text"));
                }
            } catch (JSONException error) { throw new IOException("Invalid caption JSON", error); }
        } else {
            String[] lines = input.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.equals("NOTE") || line.startsWith("NOTE ") || line.equals("STYLE") || line.equals("REGION")) {
                    while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty()) i++;
                    continue;
                }
                Matcher time = TIMELINE.matcher(line);
                if (!time.matches()) continue;
                StringBuilder text = new StringBuilder();
                while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(lines[++i]);
                }
                append(output, ++count, millis(time.group(1)), millis(time.group(2)), text.toString());
            }
        }
        if (count == 0) throw new IOException("No subtitle cues found");
        return output.toString();
    }

    private static long millis(String value) throws IOException {
        String[] parts = value.replace(',', '.').split(":");
        try {
            long hours = parts.length == 3 ? Long.parseLong(parts[0]) : 0;
            long minutes = Long.parseLong(parts[parts.length - 2]);
            String[] seconds = parts[parts.length - 1].split("\\.");
            long sec = Long.parseLong(seconds[0]);
            if (minutes > 59 || sec > 59) throw new NumberFormatException();
            return ((hours * 60 + minutes) * 60 + sec) * 1000 + Long.parseLong(seconds[1]);
        } catch (NumberFormatException error) { throw new IOException("Invalid subtitle time", error); }
    }

    private static void append(StringBuilder output, int index, long start, long end, String text) throws IOException {
        if (index > 20000 || start < 0 || end <= start || end > 7 * 24 * 60 * 60 * 1000L) throw new IOException("Invalid subtitle timing");
        // WebVTT voice/class/timestamp tags have no SRT equivalent. Preserve basic emphasis tags.
        text = text.replaceAll("</?(?:c(?:\\.[^ >]+)?|v|lang|ruby|rt)(?: [^>]*)?>", "")
                .replaceAll("<(?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3}>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").trim().replaceAll("\n\\s*\n", "\n");
        if (text.isEmpty() || text.length() > 32768) throw new IOException("Invalid subtitle text");
        output.append(index).append('\n').append(timestamp(start)).append(" --> ").append(timestamp(end))
                .append('\n').append(text).append("\n\n");
    }

    private static String timestamp(long time) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", time / 3600000, time / 60000 % 60, time / 1000 % 60, time % 1000);
    }
}
