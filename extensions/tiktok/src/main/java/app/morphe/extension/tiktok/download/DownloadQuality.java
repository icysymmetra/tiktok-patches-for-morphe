/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.download;

public enum DownloadQuality {
    HIGH("high", "High", "Best available complete video"),
    MEDIUM("medium", "Medium", "Balanced quality and file size"),
    LOW("low", "Low", "Smallest available complete video");

    public final String key;
    public final String label;
    public final String description;

    DownloadQuality(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    public static DownloadQuality fromSetting(String value) {
        if (value != null) {
            for (DownloadQuality quality : values()) {
                if (quality.key.equalsIgnoreCase(value)) {
                    return quality;
                }
            }
        }
        return HIGH;
    }

    public int selectIndex(int candidateCount) {
        if (candidateCount <= 1) {
            return 0;
        }

        switch (this) {
            case LOW:
                return 0;
            case MEDIUM:
                return candidateCount / 2;
            case HIGH:
            default:
                return candidateCount - 1;
        }
    }
}
