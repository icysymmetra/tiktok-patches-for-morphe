/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.os.Build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * What a filename template saves as, worked out for a made-up post while the reader types it.
 *
 * <p>Every name goes through the formatter the saves use, and every folder through the same
 * destination and sidecar rules, so the preview can't drift from what a save does. It touches
 * no file, makes no request and starts no save. The two downloaders read the templates a little
 * differently (an empty template keeps TikTok's own name in TikTok's, and only TikTok's knows
 * {@code {original}}), so each gets its own line.
 */
public final class DownloadNamePreview {
    /** The made-up post every preview names. */
    static final String CREATOR = "creator_name";
    static final String VIDEO_ID = "7312345678901234567";
    static final String MEDIA_ID = "7f3a9c21";
    /** TikTok's own name for a save, which the preview can't know, so it stands in for it. */
    static final String TIKTOK_NAME = "tiktok_name";

    /** Anything in braces, so a typo like {video-id}, {video id} or {date2} is named too. */
    private static final Pattern TOKEN = Pattern.compile("\\{[^{}]+\\}");
    /**
     * Every token TikTok's downloader and the comment media saver fill. One with nothing to fill
     * it with still goes ("unknown" for a sticker's creator), so only a word outside this set is
     * kept as typed there. Set.of is API 30.
     */
    static final Set<String> TOKENS = new HashSet<>(Arrays.asList(
            "{creator}", "{date}", "{video_id}", "{media_id}", "{index}", "{original}"));
    /** The ones Hushfeed's downloader fills. It has no TikTok name or media id, so it keeps those two as typed. */
    static final Set<String> SOURCE_TOKENS = new HashSet<>(Arrays.asList(
            "{creator}", "{date}", "{video_id}", "{index}"));

    private DownloadNamePreview() {
    }

    public static String video(String template) {
        return video(template, System.currentTimeMillis());
    }

    public static String photo(String template) {
        return photo(template, System.currentTimeMillis());
    }

    public static String commentMedia(String template) {
        return commentMedia(template, System.currentTimeMillis());
    }

    static String video(String template, long now) {
        List<String> lines = new ArrayList<>();
        String root = DownloadsPatch.getVideoDownloadPath();
        String destination = folder(root, template);
        if (SettingsStatus.advancedDownloadsEnabled) {
            String name = DownloadFilenameFormatter.formatSourceName(template, CREATOR, VIDEO_ID, now, 1, "mp4", false);
            boolean details = Settings.DOWNLOAD_DETAILS.get();
            boolean subtitles = SettingsStatus.subtitleToolsEnabled && Settings.DOWNLOAD_SUBTITLES.get();
            // The same choice VideoDownloads makes: a TXT or SRT beside the video moves the pair.
            String path = details ? DownloadDetails.pairedPath(destination)
                    : subtitles ? SubtitleDownloads.pairedPath(destination) : destination;
            String stem = stem(name);
            // File names and paths are built before they reach L10n, so the only literals in
            // those calls are the sentences the table translates.
            String saved = path + "/" + name;
            lines.add(L10n.f("Metra's downloader: %1$s", saved));
            if (AudioDownloads.enabled()) {
                String sound = AudioDownloads.audioPath(path) + "/" + stem + (SoundFormat.opus() ? ".ogg" : ".m4a");
                lines.add(L10n.f("Sound: %1$s", sound));
            }
            if (details) {
                String extension = Settings.DOWNLOAD_DETAILS_JSON.get() ? ".json" : ".txt";
                String detailsFile = path + "/" + stem + extension;
                lines.add(L10n.f("Details: %1$s", detailsFile));
            }
            // The made-up post has English captions only, which every language choice falls back to.
            if (subtitles) {
                String subtitleFile = path + "/" + stem + ".en.srt";
                lines.add(L10n.f("Subtitles: %1$s", subtitleFile));
            }
        }
        if (SettingsStatus.downloadEnabled) {
            String tiktoks = tiktokRoute(template, root, "mp4", now, 1);
            lines.add(L10n.f("TikTok's downloader: %1$s", tiktoks));
        }
        return finish(lines, template, SettingsStatus.advancedDownloadsEnabled);
    }

    static String photo(String template, long now) {
        List<String> lines = new ArrayList<>();
        String root = DownloadsPatch.getPhotoDownloadPath();
        if (SettingsStatus.advancedDownloadsEnabled) {
            String path = folder(root, template);
            String first = DownloadFilenameFormatter.formatSourceName(template, CREATOR, VIDEO_ID, now, 1, "jpg", true);
            String second = DownloadFilenameFormatter.formatSourceName(template, CREATOR, VIDEO_ID, now, 2, "jpg", true);
            String saved = path + "/" + first + ", " + second;
            lines.add(L10n.f("Metra's downloader: %1$s", saved));
        }
        if (SettingsStatus.downloadEnabled) {
            String tiktoks = tiktokRoute(template, root, "jpg", now, 1);
            lines.add(L10n.f("TikTok's downloader: %1$s", tiktoks));
        }
        return finish(lines, template, SettingsStatus.advancedDownloadsEnabled);
    }

    static String commentMedia(String template, long now) {
        List<String> lines = new ArrayList<>();
        String sticker = DownloadDestination.resolve(Settings.DOWNLOAD_STICKER_PATH.get(), DownloadDestination.Kind.STICKER);
        String stickerFile = sticker + "/"
                + DownloadFilenameFormatter.formatCommentMediaName(template, "png", MEDIA_ID, now);
        String clipFile = DownloadsPatch.getPhotoDownloadPath() + "/"
                + DownloadFilenameFormatter.formatCommentMediaName(template, "mp4", MEDIA_ID + "-live", now);
        lines.add(L10n.f("Stickers: %1$s", stickerFile));
        lines.add(L10n.f("Live photo clips: %1$s", clipFile));
        return finish(lines, template, false);
    }

    /**
     * Anything in braces the route doesn't fill, which a save keeps exactly as typed. Braces
     * around nothing but spaces aren't a try at a token.
     */
    static List<String> unknownTokens(String template, Set<String> known) {
        Set<String> unknown = new LinkedHashSet<>();
        if (template == null) return new ArrayList<>(unknown);
        Matcher matcher = TOKEN.matcher(template);
        while (matcher.find()) {
            String token = matcher.group();
            if (!known.contains(token) && !token.substring(1, token.length() - 1).trim().isEmpty()) {
                unknown.add(token);
            }
        }
        return new ArrayList<>(unknown);
    }

    private static String tiktokRoute(String template, String root, String extension, long now, int index) {
        if (template == null || template.trim().isEmpty()) {
            return root + "/" + L10n.t("TikTok's own name");
        }
        DownloadFilenameFormatter.Planned planned = DownloadFilenameFormatter.planTikTokSave(
                template, TIKTOK_NAME + "." + extension, extension, CREATOR, VIDEO_ID, now, index);
        return (planned.folder.isEmpty() ? root : root + "/" + planned.folder) + "/" + planned.name;
    }

    private static String folder(String root, String template) {
        return DownloadFilenameFormatter.hasCreatorFolder(template)
                ? root + "/" + DownloadFilenameFormatter.creatorFolder(CREATOR) : root;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * @param hushfeeds whether Hushfeed's downloader has a line, which keeps two more tokens as
     *                  typed than the other routes do.
     */
    private static String finish(List<String> lines, String template, boolean hushfeeds) {
        List<String> unknown = unknownTokens(template, TOKENS);
        if (!unknown.isEmpty()) {
            String typed = String.join(", ", unknown);
            lines.add(L10n.f("Not a part Metra fills in, so it's kept as typed: %1$s", typed));
        }
        if (hushfeeds) {
            List<String> onlyTikToks = unknownTokens(template, SOURCE_TOKENS);
            onlyTikToks.removeAll(unknown);
            if (!onlyTikToks.isEmpty()) {
                String typedHere = String.join(", ", onlyTikToks);
                lines.add(L10n.f("Metra's downloader doesn't fill this in, so it's kept as "
                        + "typed: %1$s", typedHere));
            }
        }
        // On Android 10 and later the media store picks the number, below that the writer does.
        lines.add(L10n.t(Build.VERSION.SDK_INT >= 29
                ? "If that name is taken, Android adds a number to the new file."
                : "If that name is taken, Metra adds a number to the new file."));
        return L10n.t("Preview with a made-up post:") + "\n" + String.join("\n", lines);
    }
}
