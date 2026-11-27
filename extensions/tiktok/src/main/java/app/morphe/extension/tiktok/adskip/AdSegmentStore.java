/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent binary store of marked ad segments ({@code metra_ad_segments.bin}).
 * Rebuilds the immutable {@link AdMatcher.Index} atomically on each change.
 */
final class AdSegmentStore {
    private static final int MAGIC = 0x4D414453; // "MADS"
    private static final int VERSION = 1;
    private static final String FILE = "metra_ad_segments.bin";

    private static final Object LOCK = new Object();
    private static List<AdSegment> segments;
    private static volatile AdMatcher.Index index;
    private static volatile int generation;

    private AdSegmentStore() {}

    static List<AdSegment> all() {
        synchronized (LOCK) {
            ensureLoaded();
            return new ArrayList<>(segments);
        }
    }

    static int size() {
        synchronized (LOCK) {
            ensureLoaded();
            return segments.size();
        }
    }

    /** Segments marked on this phone, leaving out those only the shared base sent. */
    static int ownCount() {
        synchronized (LOCK) {
            ensureLoaded();
            int n = 0;
            for (AdSegment s : segments) if (!isSharedOnly(s)) n++;
            return n;
        }
    }

    static int enabledCount() {
        synchronized (LOCK) {
            ensureLoaded();
            int n = 0;
            for (AdSegment s : segments) if (s.enabled) n++;
            return n;
        }
    }

    static AdMatcher.Index index() {
        AdMatcher.Index current = index;
        if (current != null) return current;
        synchronized (LOCK) {
            ensureLoaded();
            return index;
        }
    }

    static int generation() {
        return generation;
    }

    /** Imports segments not already present by sound fingerprint or shared server ID. */
    static int importAll(List<AdSegment> incoming) {
        synchronized (LOCK) {
            ensureLoaded();
            int added = 0;
            long id = System.currentTimeMillis();
            for (AdSegment s : segments) if (s.id >= id) id = s.id + 1;
            for (AdSegment in : incoming) {
                boolean known = false;
                for (AdSegment s : segments) {
                    if (sameSound(s, in.hashes, in.times)
                            || (in.sharedServerId != null && in.sharedServerId.equals(s.sharedServerId))) {
                        known = true;
                        break;
                    }
                }
                if (known) continue;
                AdSegment copy = new AdSegment(id++, in.name, in.createdAt > 0 ? in.createdAt : System.currentTimeMillis(),
                        in.durationMs, in.sourceAid, in.hashes, in.times, in.frames, in.enabled, in.skips, in.lastSkipAt);
                copy.sharedServerId = in.sharedServerId;
                segments.add(copy);
                added++;
            }
            if (added > 0) changed();
            return added;
        }
    }

    static AdSegment add(String name, long durationMs, String sourceAid, Landmarks.Print print) {
        synchronized (LOCK) {
            ensureLoaded();
            long id = System.currentTimeMillis();
            for (AdSegment s : segments) if (s.id >= id) id = s.id + 1;
            AdSegment segment = new AdSegment(id, name, System.currentTimeMillis(), durationMs, sourceAid,
                    java.util.Arrays.copyOf(print.hashes, print.count),
                    java.util.Arrays.copyOf(print.times, print.count), print.frames, true, 0, 0);
            segments.add(segment);
            changed();
            return segment;
        }
    }

    static void remove(long id) {
        synchronized (LOCK) {
            ensureLoaded();
            for (int i = 0; i < segments.size(); i++) {
                if (segments.get(i).id == id) {
                    segments.remove(i);
                    break;
                }
            }
            changed();
        }
    }

    static void setEnabled(long id, boolean enabled) {
        synchronized (LOCK) {
            ensureLoaded();
            for (AdSegment s : segments) if (s.id == id) s.enabled = enabled;
            changed();
        }
    }

    static void rename(long id, String name) {
        synchronized (LOCK) {
            ensureLoaded();
            for (AdSegment s : segments) if (s.id == id) s.name = name;
            save();
        }
    }

    /** One segment of the shared base as the server sent it. */
    static final class Shared {
        final String serverId;
        final int number;
        final boolean hidden;
        final long durationMs;
        final int frames;
        final int[] hashes;
        final int[] times;
        final int score;

        Shared(String serverId, int number, boolean hidden, long durationMs, int frames, int[] hashes, int[] times,
                int score) {
            this.serverId = serverId;
            this.number = number;
            this.hidden = hidden;
            this.durationMs = durationMs;
            this.frames = frames > 0 ? frames : Landmarks.msToFrame(durationMs);
            this.hashes = hashes;
            this.times = times;
            this.score = score;
        }
    }

    /** Merges shared database segments into the local store. */
    static int mergeShared(List<Shared> incoming) {
        return mergeShared(incoming, -1);
    }

    /**
     * Merges shared segments; when {@code snapshotAtMs >= 0}, removes shared-only segments older
     * than {@code snapshotAtMs} that are absent from the snapshot.
     */
    static int mergeShared(List<Shared> incoming, long snapshotAtMs) {
        synchronized (LOCK) {
            ensureLoaded();
            int added = 0;
            boolean dirty = false;
            if (snapshotAtMs >= 0) {
                java.util.Set<String> present = new java.util.HashSet<>();
                for (Shared entry : incoming) if (!entry.hidden) present.add(entry.serverId);
                for (java.util.Iterator<AdSegment> it = segments.iterator(); it.hasNext(); ) {
                    AdSegment s = it.next();
                    if (isSharedOnly(s) && s.sharedServerId != null && s.createdAt < snapshotAtMs
                            && !present.contains(s.sharedServerId)) {
                        it.remove();
                        dirty = true;
                    }
                }
            }
            for (Shared entry : incoming) {
                AdSegment known = null;
                for (AdSegment s : segments) {
                    if (entry.serverId.equals(s.sharedServerId) || entry.serverId.equals(s.sourceAid)) {
                        known = s;
                        break;
                    }
                }
                if (known == null) {
                    for (AdSegment s : segments) {
                        if (sameSound(s, entry.hashes, entry.times)) {
                            known = s;
                            break;
                        }
                    }
                }
                if (entry.hidden) {
                    if (known == null) continue;
                    if (isSharedOnly(known)) segments.remove(known);
                    else if (entry.serverId.equals(known.sharedServerId)) known.sharedServerId = null;
                    dirty = true;
                    continue;
                }
                if (known != null && isSharedOnly(known) && known.frames != entry.frames) {
                    AdSegment fixed = new AdSegment(known.id, known.name, known.createdAt, entry.durationMs, null,
                            entry.hashes, entry.times, entry.frames, known.enabled, known.skips, known.lastSkipAt);
                    fixed.sharedServerId = entry.serverId;
                    segments.set(segments.indexOf(known), fixed);
                    known = fixed;
                    dirty = true;
                }
                if (known != null) {
                    if (known.sharedServerId == null) {
                        known.sharedServerId = entry.serverId;
                        dirty = true;
                    }
                    if (isSharedOnly(known) && entry.number > 0 && !sharedName(entry.number).equals(known.name)
                            && isDefaultSharedName(known.name)) {
                        known.name = sharedName(entry.number);
                        dirty = true;
                    }
                    continue;
                }
                long id = System.currentTimeMillis();
                for (AdSegment s : segments) if (s.id >= id) id = s.id + 1;
                String name = entry.number > 0 ? sharedName(entry.number) : sharedName(sharedCount() + 1);
                AdSegment segment = new AdSegment(id, name, System.currentTimeMillis(), entry.durationMs, null,
                        entry.hashes, entry.times, entry.frames, true, 0, 0);
                segment.sharedServerId = entry.serverId;
                segments.add(segment);
                added++;
                dirty = true;
            }
            if (dirty) changed();
            else if (saveFailed) save(); // an earlier merge is still only in memory
            return added;
        }
    }

    /** The server took this segment; it can be voted on from now. */
    static void linkShared(long id, String serverId) {
        synchronized (LOCK) {
            ensureLoaded();
            for (AdSegment s : segments) {
                if (s.id == id && s.sharedServerId == null) {
                    s.sharedServerId = serverId;
                    save();
                }
            }
        }
    }

    /** Whether this base segment was cut by someone else, so the reader may vote on it. */
    static boolean isOthers(String serverId) {
        synchronized (LOCK) {
            ensureLoaded();
            for (AdSegment s : segments) if (serverId.equals(s.sharedServerId)) return isSharedOnly(s);
            return false;
        }
    }

    /** A vote down takes a segment out of matching and undoing it puts it back. */
    static void votesChanged() {
        synchronized (LOCK) {
            ensureLoaded();
            generation++;
            index = AdMatcher.Index.build(segments);
        }
    }

    /** Came from the shared base and was never marked on this phone. */
    static boolean isSharedOnly(AdSegment s) {
        return s.sharedServerId != null && (s.sourceAid == null || s.sourceAid.equals(s.sharedServerId));
    }

    static String sharedName(int number) {
        return "ad" + number;
    }

    private static boolean isDefaultSharedName(String name) {
        return name == null || name.isEmpty() || name.equals("Ad (shared)") || name.matches("ad\\d+");
    }

    private static int sharedCount() {
        int n = 0;
        for (AdSegment s : segments) if (isSharedOnly(s)) n++;
        return n;
    }

    private static boolean sameSound(AdSegment s, int[] hashes, int[] times) {
        return java.util.Arrays.equals(s.hashes, hashes) && java.util.Arrays.equals(s.times, times);
    }

    /** Deduplicates shared copies of locally created segments by sound fingerprint. */
    private static boolean dropSharedCopies() {
        boolean dirty = false;
        for (int i = segments.size() - 1; i >= 0; i--) {
            AdSegment copy = segments.get(i);
            if (!isSharedOnly(copy)) continue;
            for (AdSegment own : segments) {
                if (own == copy || isSharedOnly(own) || !sameSound(own, copy.hashes, copy.times)) continue;
                if (own.sharedServerId == null) own.sharedServerId = copy.sharedServerId;
                own.skips += copy.skips;
                own.lastSkipAt = Math.max(own.lastSkipAt, copy.lastSkipAt);
                segments.remove(i);
                dirty = true;
                break;
            }
        }
        return dirty;
    }

    static void clear() {
        synchronized (LOCK) {
            ensureLoaded();
            segments.clear();
            changed();
        }
    }

    /** Counts a skip and the time it saved. Saved later with the next change or by {@link #flushStats}. */
    static void recordSkip(AdSegment segment, long savedMs) {
        // The lifetime counters start from the segments' counts; they must be read before this
        // skip is added to the segment, or the first skip counts twice.
        AdSkipStats.seedOnce();
        synchronized (LOCK) {
            segment.skips++;
            segment.lastSkipAt = System.currentTimeMillis();
            statsDirty = true;
        }
        AdSkipStats.record(segment.sharedServerId, savedMs);
        Utils.runOnMainThreadDelayed(AdSegmentStore::flushStats, 5_000);
    }

    private static boolean statsDirty;

    static void flushStats() {
        synchronized (LOCK) {
            if (!statsDirty) return;
            statsDirty = false;
        }
        Utils.runOnBackgroundThread(() -> {
            synchronized (LOCK) {
                save();
            }
        });
    }

    private static void changed() {
        generation++;
        index = AdMatcher.Index.build(segments);
        save();
    }

    private static void ensureLoaded() {
        if (segments != null) return;
        segments = new ArrayList<>();
        File file = file();
        if (file != null && file.isFile()) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                if (in.readInt() != MAGIC) throw new IOException("Not an ad list");
                int version = in.readInt();
                if (version != VERSION) throw new IOException("Ad list version " + version);
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    long id = in.readLong();
                    String name = in.readUTF();
                    long created = in.readLong();
                    long duration = in.readLong();
                    String aid = in.readUTF();
                    boolean enabled = in.readBoolean();
                    int skips = in.readInt();
                    long lastSkip = in.readLong();
                    int frames = in.readInt();
                    int n = in.readInt();
                    if (n < 0 || n > 2_000_000) throw new IOException("Bad hash count");
                    int[] hashes = new int[n];
                    int[] times = new int[n];
                    for (int k = 0; k < n; k++) hashes[k] = in.readInt();
                    for (int k = 0; k < n; k++) times[k] = in.readInt();
                    AdSegment seg = new AdSegment(id, name, created, duration, aid.isEmpty() ? null : aid,
                            hashes, times, frames, enabled, skips, lastSkip);
                    if (version >= VERSION) {
                        try { String srvId = in.readUTF(); if (!srvId.isEmpty()) seg.sharedServerId = srvId; }
                        catch (java.io.EOFException ignored) { }
                    }
                    segments.add(seg);
                }
            } catch (Exception failure) {
                Logger.printException(() -> "Ad skip: could not read the ad list", failure);
                File broken = new File(file.getParentFile(), FILE + ".broken");
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(broken);
                segments.clear();
                SharedDb.forgetSyncProgress();
            }
        } else if (file != null) {
            SharedDb.forgetSyncProgress();
            save();
        }
        if (dropSharedCopies()) save();
        index = AdMatcher.Index.build(segments);
    }

    private static volatile boolean saveFailed;

    /** Reads the list now if it isn't yet, recovering a lost one before a sync relies on it. */
    static void load() {
        synchronized (LOCK) {
            ensureLoaded();
        }
    }

    /** Whether the last write of the list failed, so a sync must not count what it merged as kept. */
    static boolean lastSaveFailed() {
        return saveFailed;
    }

    private static void save() {
        File file = file();
        if (file == null) return;
        saveFailed = true;
        File temp = new File(file.getParentFile(), FILE + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(segments.size());
            for (AdSegment s : segments) {
                out.writeLong(s.id);
                out.writeUTF(s.name == null ? "" : s.name);
                out.writeLong(s.createdAt);
                out.writeLong(s.durationMs);
                out.writeUTF(s.sourceAid == null ? "" : s.sourceAid);
                out.writeBoolean(s.enabled);
                out.writeInt(s.skips);
                out.writeLong(s.lastSkipAt);
                out.writeInt(s.frames);
                out.writeInt(s.hashes.length);
                for (int h : s.hashes) out.writeInt(h);
                for (int t : s.times) out.writeInt(t);
                out.writeUTF(s.sharedServerId == null ? "" : s.sharedServerId);
            }
        } catch (IOException failure) {
            Logger.printException(() -> "Ad skip: could not save the ad list", failure);
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
            return;
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) {
                Logger.printDebug(() -> "Ad skip: could not replace the ad list");
                return;
            }
        }
        saveFailed = false;
    }

    private static File file() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE);
    }

    static List<AdSegment> unmodifiable(List<AdSegment> list) {
        return Collections.unmodifiableList(list);
    }
}
