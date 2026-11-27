/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** JSON backup and import of marked ad segments. */
final class AdBackup {
    static final String FORMAT = "metra-adskip-backup";
    static final int VERSION = 1;
    private static final String MIME = "application/json";
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final int MAX_HASHES = 20_000;
    private static final String FRAGMENT_TAG = "metra-adskip-backup";
    private static final int REQUEST = 0x4D41;

    interface Done {
        void imported();
    }

    @Nullable private static Done pendingDone;

    private AdBackup() {}

    static void export(Activity activity) {
        if (AdSegmentStore.size() == 0) {
            Utils.showToastShort(L10n.t("There are no ads to export yet"));
            return;
        }
        ask(activity, true, null);
    }

    static void importFile(Activity activity, Done done) {
        ask(activity, false, done);
    }

    private static void ask(Activity activity, boolean export, @Nullable Done done) {
        if (activity == null || activity.isFinishing()) return;
        pendingDone = done;
        try {
            Request request = new Request();
            Bundle arguments = new Bundle();
            arguments.putBoolean("export", export);
            request.setArguments(arguments);
            FragmentManager fragments = activity.getFragmentManager();
            fragments.beginTransaction().add(request, FRAGMENT_TAG).commitAllowingStateLoss();
        } catch (Throwable failure) {
            Logger.printException(() -> "Ad skip: could not open the file picker", failure);
            Utils.showToastLong(L10n.t("Couldn't open the file picker. Try again."));
        }
    }

    static String fileName() {
        return "metra-ads-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json";
    }

    // region format

    static String toJson(List<AdSegment> segments) throws Exception {
        JSONArray ads = new JSONArray();
        for (AdSegment s : segments) {
            ads.put(new JSONObject()
                    .put("name", s.name == null ? "" : s.name)
                    .put("created", s.createdAt)
                    .put("duration_ms", s.durationMs)
                    .put("source", s.sourceAid == null ? "" : s.sourceAid)
                    .put("shared", s.sharedServerId == null ? "" : s.sharedServerId)
                    .put("enabled", s.enabled)
                    .put("skips", s.skips)
                    .put("last_skip", s.lastSkipAt)
                    .put("frames", s.frames)
                    .put("hashes", pack(s.hashes))
                    .put("times", pack(s.times)));
        }
        return new JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("exported", System.currentTimeMillis())
                .put("ads", ads)
                .toString();
    }

    /** Parses valid segments from backup JSON, skipping malformed entries. */
    static List<AdSegment> fromJson(String text) throws Exception {
        JSONObject root = new JSONObject(text);
        if (!FORMAT.equals(root.optString("format"))) throw new IOException("Not a Metra ad backup");
        if (root.optInt("version") > VERSION) throw new IOException("Made by a newer Metra");
        JSONArray ads = root.getJSONArray("ads");
        List<AdSegment> out = new ArrayList<>();
        for (int i = 0; i < ads.length(); i++) {
            try {
                JSONObject ad = ads.getJSONObject(i);
                int[] hashes = unpack(ad.getString("hashes"));
                int[] times = unpack(ad.getString("times"));
                int frames = ad.getInt("frames");
                long duration = ad.getLong("duration_ms");
                if (hashes.length == 0 || hashes.length != times.length || hashes.length > MAX_HASHES) continue;
                if (frames <= 0 || duration <= 0 || duration > 60_000) continue;
                boolean timesValid = true;
                for (int t : times) if (t < 0 || t > frames + 200) timesValid = false;
                if (!timesValid) continue;
                String name = ad.optString("name").trim();
                if (name.length() > 200) name = name.substring(0, 200);
                String source = ad.optString("source");
                String shared = ad.optString("shared");
                AdSegment segment = new AdSegment(0, name.isEmpty() ? L10n.t("Ad") : name, ad.optLong("created"),
                        duration, source.isEmpty() ? null : source, hashes, times, frames,
                        ad.optBoolean("enabled", true), Math.max(0, ad.optInt("skips")), ad.optLong("last_skip"));
                if (!shared.isEmpty()) segment.sharedServerId = shared;
                out.add(segment);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static String pack(int[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) buffer.putInt(v);
        return Base64.encodeToString(buffer.array(), Base64.NO_WRAP);
    }

    private static int[] unpack(String text) {
        byte[] bytes = Base64.decode(text, Base64.DEFAULT);
        if (bytes.length % 4 != 0) throw new IllegalArgumentException("Bad array");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] out = new int[bytes.length / 4];
        for (int i = 0; i < out.length; i++) out[i] = buffer.getInt();
        return out;
    }

    // endregion

    // region files

    private static void write(Activity activity, Uri uri) {
        ContentResolver resolver = activity.getContentResolver();
        Utils.runOnBackgroundThread(() -> {
            try {
                byte[] data = toJson(AdSegmentStore.all()).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("No output");
                    out.write(data);
                }
                int count = AdSegmentStore.size();
                Utils.runOnMainThread(() -> Utils.showToastLong(L10n.f("Exported %1$s ads", String.valueOf(count))));
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: export failed", failure);
                Utils.runOnMainThread(() -> Utils.showToastLong(L10n.t("Couldn't save the backup. Try another folder.")));
            }
        });
    }

    private static void read(Activity activity, Uri uri, @Nullable Done done) {
        ContentResolver resolver = activity.getContentResolver();
        Utils.runOnBackgroundThread(() -> {
            try {
                String text;
                try (InputStream in = resolver.openInputStream(uri)) {
                    if (in == null) throw new IOException("No input");
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        buffer.write(chunk, 0, read);
                        if (buffer.size() > MAX_BYTES) throw new IOException("Too large");
                    }
                    text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                }
                List<AdSegment> ads = fromJson(text);
                int added = AdSegmentStore.importAll(ads);
                int skipped = ads.size() - added;
                AdScanner.clearAll();
                Utils.runOnMainThread(() -> {
                    Utils.showToastLong(skipped > 0
                            ? L10n.f("Imported %1$s ads, %2$s were already in the list", String.valueOf(added), String.valueOf(skipped))
                            : L10n.f("Imported %1$s ads", String.valueOf(added)));
                    if (done != null) done.imported();
                    AdSkipBar.refresh();
                });
            } catch (Throwable failure) {
                Logger.printException(() -> "Ad skip: import failed", failure);
                Utils.runOnMainThread(() -> Utils.showToastLong(L10n.t("This file is not a Metra ad backup")));
            }
        });
    }

    // endregion

    /** Headless fragment that launches the system document picker and receives its result. */
    public static final class Request extends Fragment {
        public Request() {}

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            if (state != null) return;
            boolean export = getArguments() != null && getArguments().getBoolean("export");
            try {
                Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(export ? MIME : "*/*");
                if (export) intent.putExtra(Intent.EXTRA_TITLE, fileName());
                startActivityForResult(intent, REQUEST);
            } catch (RuntimeException unavailable) {
                leave();
                Utils.showToastLong(L10n.t("Couldn't open the file picker. Try again."));
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request != REQUEST) return;
            Activity activity = getActivity();
            boolean export = getArguments() != null && getArguments().getBoolean("export");
            leave();
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            Done done = pendingDone;
            pendingDone = null;
            if (uri == null || activity == null) return;
            if (export) write(activity, uri);
            else read(activity, uri, done);
        }

        private void leave() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }
}
