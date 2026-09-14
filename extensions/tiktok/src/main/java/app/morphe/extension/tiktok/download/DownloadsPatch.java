/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/download/DownloadsPatch.java
 */

package app.morphe.extension.tiktok.download;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import com.ss.android.ugc.aweme.feed.model.BitRate;
import com.ss.android.ugc.aweme.feed.model.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("unused")
public class DownloadsPatch {
    private static volatile String lastLoggedPath;
    private static volatile Boolean lastLoggedRemoveWatermark;
    private static final Pattern RESOLUTION_PATTERN = Pattern.compile("(?i)(\\d{3,4})p");
    private static final Pattern EMBEDDED_BITRATE_PATTERN = Pattern.compile(
            "(?i)(?:^|[_-])\\d{3,4}p[_-](\\d{5,9})(?:[_-]|$)"
    );
    private static final Comparator<Candidate> QUALITY_COMPARATOR = (left, right) -> {
        int comparison = Integer.compare(left.resolutionHeight, right.resolutionHeight);
        if (comparison != 0) return comparison;

        comparison = Integer.compare(left.bitrate, right.bitrate);
        if (comparison != 0) return comparison;

        comparison = Long.compare(left.size, right.size);
        if (comparison != 0) return comparison;

        comparison = Integer.compare(left.bytevc1 ? 0 : 1, right.bytevc1 ? 0 : 1);
        if (comparison != 0) return comparison;

        return Integer.compare(left.fallbackPriority, right.fallbackPriority);
    };

    private static volatile String lastLoggedQualitySignature;

    public static String getVideoDownloadPath() {
        return getDownloadPath(Settings.DOWNLOAD_VIDEO_PATH.get(), DownloadDestination.Kind.VIDEO);
    }

    public static String getPhotoDownloadPath() {
        return getDownloadPath(Settings.DOWNLOAD_PHOTO_PATH.get(), DownloadDestination.Kind.PHOTO);
    }

    public static String getMediaDownloadPath(boolean video) {
        return video ? getVideoDownloadPath() : getPhotoDownloadPath();
    }

    private static String getDownloadPath(String configuredPath, DownloadDestination.Kind kind) {
        String path = DownloadDestination.resolve(configuredPath, kind);
        if (BaseSettings.DEBUG.get() && (lastLoggedPath == null || !lastLoggedPath.equals(path))) {
            lastLoggedPath = path;
            Logger.printInfo(() -> "[Morphe Downloads] " + kind.name().toLowerCase()
                    + "_path=\"" + path + "\"");
        }
        return path;
    }

    public static android.net.Uri getVideoCollectionUri() {
        String path = getVideoDownloadPath();
        return DownloadDestination.collectionUri(path, true);
    }

    public static android.net.Uri getPhotoCollectionUri() {
        String path = getPhotoDownloadPath();
        return DownloadDestination.collectionUri(path, false);
    }

    public static boolean shouldRemoveWatermark() {
        boolean removeWatermark = Settings.DOWNLOAD_WATERMARK.get();
        if (BaseSettings.DEBUG.get() && (lastLoggedRemoveWatermark == null || lastLoggedRemoveWatermark != removeWatermark)) {
            lastLoggedRemoveWatermark = removeWatermark;
            Logger.printInfo(() -> "[Morphe Downloads] remove_watermark=" + removeWatermark);
        }
        return removeWatermark;
    }

    public static void patchVideoObject(Video video) {
        if (video == null) return;

        try {
            boolean debug = BaseSettings.DEBUG.get();
            UrlModel original = video.downloadNoWatermarkAddr;
            DownloadQuality quality = DownloadQuality.fromSetting(Settings.DOWNLOAD_VIDEO_QUALITY.get());
            Selection selection = selectPreferredVideo(video, quality, debug);
            if (selection == null) {
                return;
            }

            video.downloadNoWatermarkAddr = selection.candidate.model;

            if (debug) {
                String originalSummary = describeUrlModel(original);
                String selectedSummary = describeUrlModel(selection.candidate.model);
                String source = selection.candidate.name;
                String signature = selection.quality.key + '|' + source + '|'
                        + selection.candidate.resolutionHeight + '|'
                        + selection.candidate.bitrate + '|'
                        + originalSummary + '|' + selectedSummary + '|'
                        + selection.candidatesSummary + '|' + selection.rawBitRatesSummary;
                if (!signature.equals(lastLoggedQualitySignature)) {
                    lastLoggedQualitySignature = signature;
                    String decisionId = Integer.toHexString(signature.hashCode());
                    Logger.printInfo(() -> "[Morphe Downloads] quality decision"
                            + " id=" + decisionId
                            + " preference=" + selection.quality.key
                            + " candidates=" + selection.candidateCount
                            + " original=" + originalSummary
                            + " source=" + source
                            + " resolution=" + selection.candidate.resolutionHeight + "p"
                            + " bitrate=" + selection.candidate.bitrate
                            + " bytevc1=" + selection.candidate.bytevc1
                            + " replacement=" + selectedSummary);
                    Logger.printInfo(() -> "[Morphe Downloads] quality candidates"
                            + " id=" + decisionId
                            + " order=ascending"
                            + " entries=" + selection.candidatesSummary);
                    Logger.printInfo(() -> "[Morphe Downloads] raw bitrates"
                            + " id=" + decisionId
                            + " entries=" + selection.rawBitRatesSummary);
                }
            }
        } catch (Throwable ex) {
            if (BaseSettings.DEBUG.get()) {
                Logger.printException(() -> "[Morphe Downloads] patchVideoObject failure", ex);
            }
        }
    }

    private static Selection selectPreferredVideo(
            Video video,
            DownloadQuality quality,
            boolean captureDiagnostics
    ) {
        List<Candidate> candidates = new ArrayList<>();
        StringBuilder rawBitRates = captureDiagnostics ? new StringBuilder("[") : null;
        addCandidate(candidates, new Candidate(
                "downloadNoWatermarkAddr",
                video.downloadNoWatermarkAddr,
                0,
                null,
                false,
                4
        ));

        List<BitRate> bitRates = getRawBitRatesSafe(video);
        if (bitRates != null) {
            for (int index = 0; index < bitRates.size(); index++) {
                BitRate bitRate = bitRates.get(index);
                if (bitRate == null) {
                    appendRawBitRateDiagnostic(rawBitRates, index, null, false, null);
                    continue;
                }

                boolean progressive = isProgressiveSafe(bitRate);
                UrlModel playAddr = getPlayAddrSafe(bitRate);
                appendRawBitRateDiagnostic(rawBitRates, index, bitRate, progressive, playAddr);
                if (!progressive) {
                    continue;
                }

                addCandidate(candidates, new Candidate(
                        "bitRate[" + index + "]",
                        playAddr,
                        getBitRateSafe(bitRate),
                        getGearNameSafe(bitRate),
                        isBytevc1Safe(bitRate),
                        3
                ));
            }
        }

        addCandidate(candidates, new Candidate("h264PlayAddr", video.h264PlayAddr, 0, null, false, 2));
        addCandidate(candidates, new Candidate("playAddr", video.playAddr, 0, null, false, 0));

        if (candidates.isEmpty()) {
            return null;
        }

        Collections.sort(candidates, QUALITY_COMPARATOR);
        Candidate selected = candidates.get(quality.selectIndex(candidates.size()));
        if (rawBitRates != null) {
            rawBitRates.append(']');
        }
        return new Selection(
                quality,
                selected,
                candidates.size(),
                captureDiagnostics ? describeCandidates(candidates) : null,
                rawBitRates == null ? null : rawBitRates.toString()
        );
    }

    private static void addCandidate(List<Candidate> candidates, Candidate candidate) {
        if (!candidate.usable) {
            return;
        }

        for (int index = 0; index < candidates.size(); index++) {
            Candidate existing = candidates.get(index);
            if (candidate.isSameMedia(existing)) {
                if (QUALITY_COMPARATOR.compare(candidate, existing) > 0) {
                    candidates.set(index, candidate);
                }
                return;
            }
        }

        candidates.add(candidate);
    }

    private static List<BitRate> getRawBitRatesSafe(Video video) {
        try {
            return video.getRawBitRate();
        } catch (Throwable ignored) {
            try {
                return video.bitRate;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static boolean isProgressiveSafe(BitRate bitRate) {
        try {
            if ("dash".equalsIgnoreCase(bitRate.getFormat())) {
                return false;
            }
            return !bitRate.isDash();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static UrlModel getPlayAddrSafe(BitRate bitRate) {
        try {
            return bitRate.getPlayAddr();
        } catch (Throwable ignored) {
            try {
                return bitRate.playAddr;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static int getBitRateSafe(BitRate bitRate) {
        try {
            int value = bitRate.getBitRate();
            if (value > 0) return value;
        } catch (Throwable ignored) {
        }

        try {
            return Math.max(0, bitRate.bitRate);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String getGearNameSafe(BitRate bitRate) {
        try {
            return bitRate.getGearName();
        } catch (Throwable ignored) {
            try {
                return bitRate.gearName;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static boolean isBytevc1Safe(BitRate bitRate) {
        try {
            return bitRate.isBytevc1() != 0;
        } catch (Throwable ignored) {
            try {
                return bitRate.isBytevc1 != 0;
            } catch (Throwable ignoredAgain) {
                return false;
            }
        }
    }

    private static String getFormatSafe(BitRate bitRate) {
        try {
            return bitRate.getFormat();
        } catch (Throwable ignored) {
            try {
                return bitRate.format;
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }

    private static int getQualityTypeSafe(BitRate bitRate) {
        try {
            return bitRate.getQualityType();
        } catch (Throwable ignored) {
            try {
                return bitRate.qualityType;
            } catch (Throwable ignoredAgain) {
                return 0;
            }
        }
    }

    private static boolean hasUsableUrl(UrlModel model) {
        List<String> urls = getUrlListSafe(model);
        if (urls == null || urls.isEmpty()) {
            return false;
        }

        for (String url : urls) {
            if (url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim())) {
                return true;
            }
        }

        return false;
    }

    private static String describeUrlModel(UrlModel model) {
        if (model == null) {
            return "null";
        }

        List<String> urls = getUrlListSafe(model);
        int urlCount = urls == null ? -1 : urls.size();
        return "{class=" + model.getClass().getName()
                + ",uri=" + getUriSafe(model)
                + ",urlKey=" + getUrlKeySafe(model)
                + ",size=" + getSizeSafe(model)
                + ",urlCount=" + urlCount
                + ",firstUrl=" + redactUrl(firstUrl(urls)) + "}";
    }

    private static List<String> getUrlListSafe(UrlModel model) {
        try {
            return model.getUrlList();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getUriSafe(UrlModel model) {
        try {
            return model.getUri();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String getUrlKeySafe(UrlModel model) {
        try {
            return model.getUrlKey();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static long getSizeSafe(UrlModel model) {
        try {
            return model.getSize();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String firstUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }

        return urls.get(0);
    }

    private static String firstUsableUrl(List<String> urls) {
        if (urls == null) {
            return null;
        }

        for (String url : urls) {
            if (url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim())) {
                return url;
            }
        }
        return null;
    }

    private static int inferResolution(String gearName, UrlModel model) {
        return firstPositiveMatch(RESOLUTION_PATTERN,
                gearName,
                getUriSafe(model),
                getUrlKeySafe(model),
                firstUsableUrl(getUrlListSafe(model)));
    }

    private static int inferEmbeddedBitrate(UrlModel model) {
        return firstPositiveMatch(EMBEDDED_BITRATE_PATTERN,
                getUriSafe(model),
                getUrlKeySafe(model),
                firstUsableUrl(getUrlListSafe(model)));
    }

    private static int firstPositiveMatch(Pattern pattern, String... values) {
        for (String value : values) {
            if (value == null) continue;

            Matcher matcher = pattern.matcher(value);
            if (!matcher.find()) continue;

            try {
                int match = Integer.parseInt(matcher.group(1));
                if (match > 0) return match;
            } catch (RuntimeException ignored) {
            }
        }
        return 0;
    }

    private static String mediaIdentity(UrlModel model) {
        // TikTok commonly reuses the same URI for every bitrate variant while
        // urlKey identifies the actual encoding and resolution. Prefer urlKey
        // so low, medium, and high variants are not collapsed into one entry.
        String identity = getUrlKeySafe(model);
        if (identity == null || identity.trim().isEmpty()) {
            identity = getUriSafe(model);
        }
        if (identity == null || identity.trim().isEmpty()) {
            identity = firstUsableUrl(getUrlListSafe(model));
        }
        if (identity == null) {
            return null;
        }

        int queryIndex = identity.indexOf('?');
        return queryIndex >= 0 ? identity.substring(0, queryIndex) : identity;
    }

    private static void appendRawBitRateDiagnostic(
            StringBuilder output,
            int index,
            BitRate bitRate,
            boolean progressive,
            UrlModel playAddr
    ) {
        if (output == null) {
            return;
        }
        if (output.length() > 1) {
            output.append(';');
        }
        if (bitRate == null) {
            output.append("{index=").append(index).append(",null=true}");
            return;
        }

        output.append("{index=").append(index)
                .append(",progressive=").append(progressive)
                .append(",usable=").append(hasUsableUrl(playAddr))
                .append(",format=").append(compactValue(getFormatSafe(bitRate)))
                .append(",gear=").append(compactValue(getGearNameSafe(bitRate)))
                .append(",qualityType=").append(getQualityTypeSafe(bitRate))
                .append(",bitrate=").append(getBitRateSafe(bitRate))
                .append(",size=").append(Math.max(0, getSizeSafe(playAddr)))
                .append(",bytevc1=").append(isBytevc1Safe(bitRate))
                .append(",addr=").append(describeCompactUrlModel(playAddr))
                .append('}');
    }

    private static String describeCandidates(List<Candidate> candidates) {
        StringBuilder output = new StringBuilder("[");
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) {
                output.append(';');
            }
            Candidate candidate = candidates.get(index);
            output.append("{rank=").append(index)
                    .append(",source=").append(candidate.name)
                    .append(",resolution=").append(candidate.resolutionHeight)
                    .append(",bitrate=").append(candidate.bitrate)
                    .append(",size=").append(candidate.size)
                    .append(",bytevc1=").append(candidate.bytevc1)
                    .append(",addr=").append(describeCompactUrlModel(candidate.model))
                    .append('}');
        }
        return output.append(']').toString();
    }

    private static String describeCompactUrlModel(UrlModel model) {
        if (model == null) {
            return "null";
        }
        List<String> urls = getUrlListSafe(model);
        return "{uri=" + compactValue(getUriSafe(model))
                + ",urlKey=" + compactValue(getUrlKeySafe(model))
                + ",urlCount=" + (urls == null ? -1 : urls.size()) + '}';
    }

    private static String compactValue(String value) {
        if (value == null) {
            return "null";
        }
        int queryIndex = value.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? value.substring(0, queryIndex) : value;
        return withoutQuery.length() <= 96 ? withoutQuery : withoutQuery.substring(0, 96) + "...";
    }

    private static String redactUrl(String url) {
        if (url == null) {
            return null;
        }

        int queryIndex = url.indexOf('?');
        String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        return withoutQuery.length() <= 96 ? withoutQuery : withoutQuery.substring(0, 96) + "...";
    }

    private static final class Candidate {
        final String name;
        final UrlModel model;
        final boolean usable;
        final int resolutionHeight;
        final int bitrate;
        final long size;
        final boolean bytevc1;
        final int fallbackPriority;
        final String identity;

        Candidate(
                String name,
                UrlModel model,
                int bitrate,
                String gearName,
                boolean bytevc1,
                int fallbackPriority
        ) {
            this.name = name;
            this.model = model;
            this.usable = hasUsableUrl(model);
            this.resolutionHeight = usable ? inferResolution(gearName, model) : 0;
            int inferredBitrate = usable ? inferEmbeddedBitrate(model) : 0;
            this.bitrate = bitrate > 0 ? bitrate : inferredBitrate;
            this.size = usable ? Math.max(0, getSizeSafe(model)) : 0;
            this.bytevc1 = bytevc1;
            this.fallbackPriority = fallbackPriority;
            this.identity = usable ? mediaIdentity(model) : null;
        }

        boolean isSameMedia(Candidate other) {
            if (model == other.model) {
                return true;
            }
            return identity != null && identity.equals(other.identity);
        }
    }

    private static final class Selection {
        final DownloadQuality quality;
        final Candidate candidate;
        final int candidateCount;
        final String candidatesSummary;
        final String rawBitRatesSummary;

        Selection(
                DownloadQuality quality,
                Candidate candidate,
                int candidateCount,
                String candidatesSummary,
                String rawBitRatesSummary
        ) {
            this.quality = quality;
            this.candidate = candidate;
            this.candidateCount = candidateCount;
            this.candidatesSummary = candidatesSummary;
            this.rawBitRatesSummary = rawBitRatesSummary;
        }
    }
}
