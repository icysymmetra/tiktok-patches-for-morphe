/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
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
 * The marked ads, kept on the phone in one small binary file. A 15 second ad is about 2,500
 * hashes, 20 KB, so a few hundred ads stay under a few megabytes and load in milliseconds.
 *
 * <p>Every change writes a new file beside the old one and renames it over, so a crash mid
 * write leaves the previous list rather than half of one. The match index is rebuilt on each
 * change and swapped in whole, so a scan running on another thread always sees one list.
 */
final class AdSegmentStore {
    private static final int MAGIC = 0x4D414453; // "MADS"
    private static final int VERSION = 1;
    private static final String FILE = "metra_ad_segments.bin";

    private static final Object LOCK = new Object();
    private static List<AdSegment> segments;
    private static volatile AdMatcher.Index index;
    /** Bumped on every change, so a cached scan result made against an older list is redone. */
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

    /**
     * Brings the shared base's segments into the list, in one write.
     *
     * <p>A segment this phone sent itself comes back with the very same hashes, so it is matched
     * by its sound, not added twice: the local one keeps its name and only learns its server id,
     * which is what lets it be voted on. A segment only the base has is named by its number in the
     * base, ad12, which is the same on every phone. One the votes hid is taken off again.
     */
    static int mergeShared(List<Shared> incoming) {
        synchronized (LOCK) {
            ensureLoaded();
            int added = 0;
            boolean dirty = false;
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
                    // Earlier versions stored the hash count as the length in frames, which made
                    // a skip run far past the ad's end.
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
                        entry.hashes, entry.times, entry.frames, entry.score > -2, 0, 0);
                segment.sharedServerId = entry.serverId;
                segments.add(segment);
                added++;
                dirty = true;
            }
            if (dirty) changed();
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

    /**
     * Earlier versions added a phone's own segment a second time when the base sent it back.
     * The copy goes, and its server id moves to the original, which keeps its name.
     */
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
            }
        }
        if (dropSharedCopies()) save();
        index = AdMatcher.Index.build(segments);
    }

    private static void save() {
        File file = file();
        if (file == null) return;
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
            if (!temp.renameTo(file)) Logger.printDebug(() -> "Ad skip: could not replace the ad list");
        }
    }

    private static File file() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE);
    }

    static List<AdSegment> unmodifiable(List<AdSegment> list) {
        return Collections.unmodifiableList(list);
    }
}
