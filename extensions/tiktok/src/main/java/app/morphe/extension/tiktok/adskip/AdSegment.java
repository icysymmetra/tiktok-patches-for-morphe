/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

/** One ad the reader cut out of a video: its name and the landmarks of its sound. */
final class AdSegment {
    final long id;
    volatile String name;
    final long createdAt;
    /** The cut's own length. */
    final long durationMs;
    final String sourceAid;
    /** Hashes and their frames, counted from the cut's start. */
    final int[] hashes;
    final int[] times;
    final int frames;
    volatile boolean enabled;
    volatile int skips;
    volatile long lastSkipAt;

    /** Server-side id if this came from the shared base, null for private. */
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
