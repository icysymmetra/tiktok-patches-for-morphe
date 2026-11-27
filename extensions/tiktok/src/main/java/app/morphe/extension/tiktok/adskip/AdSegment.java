/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

/** Marked ad segment with its audio landmark fingerprint. */
final class AdSegment {
    final long id;
    volatile String name;
    final long createdAt;
    final long durationMs;
    final String sourceAid;
    /** Landmark hashes and frame offsets from segment start. */
    final int[] hashes;
    final int[] times;
    final int frames;
    volatile boolean enabled;
    volatile int skips;
    volatile long lastSkipAt;

    /** Shared database ID, or null for local-only segments. */
    volatile String sharedServerId;

    AdSegment(long id, String name, long createdAt, long durationMs, String sourceAid,
            int[] hashes, int[] times, int frames, boolean enabled, int skips, long lastSkipAt) {
        this.id = id;
        this.name = name;
        this.createdAt = createdAt;
        this.durationMs = durationMs;
        this.sourceAid = sourceAid;
        this.hashes = hashes;
        this.times = times;
        this.frames = frames;
        this.enabled = enabled;
        this.skips = skips;
        this.lastSkipAt = lastSkipAt;
    }

    int hashCount() {
        return hashes.length;
    }
}
