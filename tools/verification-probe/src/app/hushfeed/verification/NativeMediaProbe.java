package app.hushfeed.verification;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Controlled local files only. Calls the installed extension, never a probe copy of its muxer. */
final class NativeMediaProbe {
    static final String TARGET = "com.zhiliaoapp.musically";
    static final String MUXER = "app.morphe.extension.tiktok.download.TrackMuxer";
    static final String BUDGET = "app.morphe.extension.tiktok.download.MediaBudget";
    static final String[] INPUTS = {"video-90.mp4", "audio.m4a", "av-90.mp4"};
    static final String[] OUTPUTS = {"combined.mp4", "silent.mp4", "silent-combined.mp4", "sound.m4a"};
    static final String[] NEGATIVES = {"missingvideo.mp4", "missingaudio.mp4", "combinemissingaudio.mp4"};
    private static final long MAX_MEDIA = 8L * 1024 * 1024, MAX_APK = 768L * 1024 * 1024;
    private static final long RUN_MS = 90_000L;
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    /** Pure boundaries shared with host contracts. No Android calls or mutable app state. */
    static final class Contract {
        interface Check { void run() throws Exception; }
        static boolean token(String value) { return value != null && value.matches("media-[a-f0-9]{24}"); }
        static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }
        static File child(File parent, String name) throws IOException {
            if (name == null || !name.matches("[a-z0-9][a-z0-9.-]{0,63}") || name.equals(".") || name.equals("..")) {
                throw new IOException("invalid component");
            }
            File child = new File(parent, name).getAbsoluteFile();
            if (!parent.getAbsoluteFile().equals(parent.getCanonicalFile())
                    || !child.equals(child.getCanonicalFile())) throw new IOException("redirected path");
            return child;
        }
        static void before(long now, long deadline) throws IOException {
            if (now >= deadline) throw new IOException("diagnostic deadline");
        }
        static boolean media(int video, int audio, int rotation, long durationUs, int wantedVideo, int wantedAudio) {
            return video == wantedVideo && audio == wantedAudio && (video == 0 || rotation == 90)
                    && durationUs >= 1_700_000L && durationUs <= 2_300_000L;
        }
        static Method method(Class<?> owner, String name, Class<?>... parameters) throws Exception {
            Method method = owner.getDeclaredMethod(name, parameters);
            if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != Void.TYPE) {
                throw new NoSuchMethodException("invalid production method shape");
            }
            method.setAccessible(true);
            return method;
        }
        static void atomic(File file, byte[] bytes, Check check) throws Exception {
            if (bytes.length > 32768) throw new IOException("receipt size limit");
            File temp = child(file.getParentFile(), file.getName() + ".tmp");
            if (!temp.createNewFile()) throw new IOException("receipt collision");
            boolean finalized = false;
            try {
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    output.write(bytes); output.getFD().sync();
                }
                check.run();
                if (file.exists() || !temp.renameTo(file)) throw new IOException("receipt finalization failed");
                finalized = true; check.run();
            } catch (Exception failure) {
                if (finalized && file.equals(child(file.getParentFile(), file.getName()))
                        && hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest(file, 32768))
                        && !file.delete()) throw new IOException("late receipt cleanup failed", failure);
                throw failure;
            } finally { if (temp.exists() && !temp.delete()) throw new IOException("receipt temporary cleanup failed"); }
        }
        static void cleanup(File root, String manifestHash, Map<String, String> inputs, Map<String, String> outputs) throws Exception {
            if (!token(root.getName()) || !inputs.keySet().equals(new HashSet<>(Arrays.asList(INPUTS)))) throw new IOException("invalid cleanup contract");
            File result = child(root, "result"), manifest = child(root, "manifest.json");
            if (!manifestHash.equals(digest(manifest, 32768))) throw new IOException("changed manifest preserved");
            Set<String> mediaNames = new HashSet<>(Arrays.asList(OUTPUTS)); mediaNames.addAll(Arrays.asList(NEGATIVES));
            if (!mediaNames.containsAll(outputs.keySet())) throw new IOException("invalid owned output set");
            Set<String> allowed = new HashSet<>(mediaNames); allowed.add("owner.json"); allowed.add("receipt.json");
            String[] names = result.exists() ? result.list() : new String[0];
            if (names == null) throw new IOException("result cannot be listed");
            for (String name : names) if (!allowed.contains(name) || !child(result, name).isFile()) throw new IOException("unrelated result preserved");
            Set<String> rootNames = new HashSet<>(Arrays.asList(INPUTS)); rootNames.add("manifest.json"); rootNames.add("result");
            String[] entries = root.list();
            if (entries == null || !rootNames.containsAll(Arrays.asList(entries))) throw new IOException("unrelated fixture preserved");
            for (String name : names) if (mediaNames.contains(name)
                    && !digest(child(result, name), MAX_MEDIA).equals(outputs.get(name))) throw new IOException("changed output preserved");
            for (String name : INPUTS) {
                File file = child(root, name);
                if (file.exists() && !digest(file, MAX_MEDIA).equals(inputs.get(name))) throw new IOException("changed input preserved");
            }
            // Validate the entire set before deleting anything. Ownership records go last.
            for (String name : names) if (mediaNames.contains(name) && !child(result, name).delete()) throw new IOException("output cleanup failed");
            for (String name : INPUTS) {
                File file = child(root, name);
                if (file.exists() && !file.delete()) throw new IOException("input cleanup failed");
            }
            for (String name : new String[] {"owner.json", "receipt.json"}) {
                File file = child(result, name);
                if (file.exists() && !file.delete()) throw new IOException("ownership cleanup failed");
            }
            if ((result.exists() && !result.delete()) || !manifest.delete() || !root.delete()) throw new IOException("directory cleanup failed");
        }
    }

    static void run(Context app, ClassLoader loader, Intent intent) {
        String token = intent.getStringExtra("token"), manifestHash = intent.getStringExtra("manifest_sha256");
        String apkHash = intent.getStringExtra("apk_sha256"), op = intent.getStringExtra("op");
        if (!Contract.token(token) || !Contract.hash(manifestHash)
                || !("run".equals(op) || "cleanup".equals(op)) || ("run".equals(op) && !Contract.hash(apkHash))) {
            Log.i(Probe.TAG, "native-media status=rejected reason=request complete=false");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            Log.i(Probe.TAG, "native-media token=" + token + " status=busy complete=false");
            return;
        }
        CountDownLatch finished = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                Session session = new Session(app, loader, token, manifestHash, apkHash, "cleanup".equals(op));
                if ("cleanup".equals(op)) session.cleanup(); else session.execute();
            } catch (Exception | LinkageError failure) {
                Log.i(Probe.TAG, "native-media token=" + token + " status=failed complete=false error="
                        + cause(failure).getClass().getSimpleName());
            } finally { finished.countDown(); BUSY.set(false); }
        }, "hushfeed-native-media");
        worker.setDaemon(true);
        worker.start();
        Thread watchdog = new Thread(() -> {
            boolean completed;
            try { completed = finished.await(RUN_MS, TimeUnit.MILLISECONDS); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
            if (!completed) {
                worker.interrupt();
                // A blocked vendor call cannot be killed safely from another Java thread.
                // Keep ownership until it returns; a late completion cannot pass check().
                Log.i(Probe.TAG, "native-media token=" + token
                        + " status=timeout complete=false cleanupPending=true workerActive=true");
            }
        }, "hushfeed-native-media-watchdog");
        watchdog.setDaemon(true); watchdog.start();
    }

    private static final class Session {
        final Context app;
        final ClassLoader loader;
        final String token, manifestHash, apkHash;
        final File root, result;
        final JSONObject manifest, receipt = new JSONObject();
        final List<File> created = new ArrayList<>();
        final long began = SystemClock.elapsedRealtime(), deadline = began + RUN_MS;
        boolean resourcesReleased = true;

        Session(Context app, ClassLoader loader, String token, String manifestHash, String apkHash, boolean cleaning) throws Exception {
            this.app = app; this.loader = loader; this.token = token;
            this.manifestHash = manifestHash; this.apkHash = apkHash;
            if (!TARGET.equals(app.getPackageName()) || app.getApplicationInfo().uid != Process.myUid()
                    || Build.VERSION.SDK_INT < 23 || !"Dalvik".equals(System.getProperty("java.vm.name"))) {
                throw new IOException("target ART context required");
            }
            File external = app.getExternalFilesDir(null);
            if (external == null) throw new IOException("app files unavailable");
            File base = Contract.child(external.getCanonicalFile(), "hushfeed-native-media");
            root = Contract.child(base, token);
            if (!root.isDirectory()) throw new IOException("fixture directory absent");
            File declared = Contract.child(root, "manifest.json");
            if (!manifestHash.equals(digest(declared, 32 * 1024))) throw new IOException("manifest mismatch");
            manifest = new JSONObject(read(declared, 32 * 1024));
            if (manifest.getInt("schema") != 1 || !token.equals(manifest.getString("token"))
                    || !"clockwise".equals(manifest.getString("rotationConvention"))
                    || !"ffmpeg-lavfi-testsrc2+sine".equals(manifest.getString("provenance"))) {
                throw new IOException("fixture provenance required");
            }
            JSONObject sources = manifest.getJSONObject("sourceHashes");
            for (String name : new String[] {"TrackMuxer.java", "MediaBudget.java", "VideoDownloads.java"}) {
                if (!Contract.hash(sources.getString(name))) throw new IOException("source identity absent");
            }
            checkInputs(cleaning);
            result = Contract.child(root, "result");
        }

        void check() throws IOException { Contract.before(SystemClock.elapsedRealtime(), deadline); }

        void checkInputs() throws Exception { checkInputs(false); }

        void checkInputs(boolean allowMissing) throws Exception {
            JSONObject inputs = manifest.getJSONObject("inputs");
            if (inputs.length() != INPUTS.length) throw new IOException("unexpected input set");
            for (String name : INPUTS) {
                check();
                JSONObject wanted = inputs.getJSONObject(name);
                File file = Contract.child(root, name);
                if (allowMissing && !file.exists()) continue;
                if (!file.isFile() || file.length() != wanted.getLong("bytes")
                        || !wanted.getString("sha256").equals(digest(file, MAX_MEDIA))) {
                    throw new IOException("fixture identity mismatch");
                }
            }
        }

        void execute() throws Exception {
            check();
            PackageInfo info = app.getPackageManager().getPackageInfo(TARGET, 0);
            if (!"47.4.3".equals(info.versionName)) throw new IOException("unqualified native host");
            String installed = digest(new File(app.getApplicationInfo().sourceDir), MAX_APK);
            if (!installed.equals(apkHash)) throw new IOException("installed APK identity mismatch");
            check();
            Class<?> owner = loader.loadClass(MUXER), budget = loader.loadClass(BUDGET);
            Method combine = Contract.method(owner, "combine", File.class, File.class, File.class);
            Method videoOnly = Contract.method(owner, "videoOnly", File.class, File.class);
            Method audioOnly = Contract.method(owner, "audioOnly", File.class, File.class);
            Class<?> deadlineType = loader.loadClass(BUDGET + "$Deadline");
            deadlineType.getDeclaredConstructor(long.class);
            Contract.method(budget, "runWithJobDeadline", deadlineType, Runnable.class);
            Field current = budget.getDeclaredField("CURRENT_DEADLINE"); current.setAccessible(true);
            ThreadLocal<?> local = (ThreadLocal<?>) current.get(null);
            Object before = local.get();
            if (before != null) throw new IOException("diagnostic thread already has a media deadline");
            if (!result.mkdir()) throw new IOException("result directory already exists");
            JSONObject marker = new JSONObject().put("token", token).put("manifestSha256", manifestHash);
            try { write(Contract.child(result, "owner.json"), marker, this::check); }
            catch (Exception failure) { result.delete(); throw failure; }
            receipt.put("schema", 1).put("kind", "controlled-trackmuxer-art").put("token", token)
                    .put("manifestSha256", manifestHash).put("installedApkSha256", installed)
                    .put("package", TARGET).put("version", info.versionName).put("sdk", Build.VERSION.SDK_INT)
                    .put("vm", System.getProperty("java.vm.name")).put("actualInstalledTrackMuxer", true)
                    .put("sourceHashes", manifest.getJSONObject("sourceHashes"))
                    .put("compiledSourceQualified", false).put("nativeDashBranchExercised", false)
                    .put("servedDashAcceptance", false).put("deadlineMs", RUN_MS);
            JSONArray operations = new JSONArray();
            receipt.put("operations", operations);
            boolean accepted = false, unchanged = false, deadlineRestored = true;
            try {
                File video = Contract.child(root, INPUTS[0]), audio = Contract.child(root, INPUTS[1]);
                File av = Contract.child(root, INPUTS[2]);
                for (String name : INPUTS) operations.put(inspect(Contract.child(root, name), "input", name));
                File combined = output(OUTPUTS[0]);
                invoke(budget, combine, video, audio, combined);
                operations.put(inspect(combined, "combine", OUTPUTS[0]));
                File silent = output(OUTPUTS[1]);
                invoke(budget, videoOnly, av, silent);
                operations.put(inspect(silent, "videoOnly", OUTPUTS[1]));
                File silentCombined = output(OUTPUTS[2]);
                invoke(budget, videoOnly, combined, silentCombined);
                operations.put(inspect(silentCombined, "videoOnlyCombined", OUTPUTS[2]));
                File sound = output(OUTPUTS[3]);
                invoke(budget, audioOnly, av, sound);
                operations.put(inspect(sound, "audioOnly", OUTPUTS[3]));
                missing(budget, videoOnly, audio, null, "missingVideo", "video/", operations);
                missing(budget, audioOnly, video, null, "missingAudio", "audio/", operations);
                missing(budget, combine, video, video, "combineMissingAudio", "audio/", operations);
                checkInputs(); unchanged = true; check(); accepted = true;
            } catch (Exception | LinkageError failure) {
                receipt.put("failure", cause(failure).getClass().getSimpleName());
            } finally {
                deadlineRestored = local.get() == before;
                accepted = accepted && unchanged && deadlineRestored && resourcesReleased
                        && SystemClock.elapsedRealtime() < deadline;
                boolean partialsRemoved = accepted || removeCreated();
                JSONArray ownedFiles = new JSONArray();
                for (File file : created) if (file.exists()) ownedFiles.put(new JSONObject()
                        .put("file", file.getName()).put("sha256", NativeMediaProbe.digest(file, MAX_MEDIA)));
                if (accepted && SystemClock.elapsedRealtime() >= deadline) {
                    accepted = false; partialsRemoved = removeCreated();
                }
                receipt.put("accepted", accepted).put("ownedFiles", ownedFiles)
                        .put("complete", accepted && partialsRemoved)
                        .put("elapsedMs", SystemClock.elapsedRealtime() - began)
                        .put("restoration", new JSONObject().put("inputsUnchanged", unchanged)
                                .put("productionDeadlineRestored", deadlineRestored)
                                .put("probeResourcesReleased", resourcesReleased).put("settingsTouched", false)
                                .put("audioPlaybackUsed", false).put("networkUsed", false))
                        .put("outputsRetainedForHost", accepted).put("ownedPartialOutputsRemoved", partialsRemoved);
                try { write(Contract.child(result, "receipt.json"), receipt, this::check); }
                catch (Exception failure) {
                    if (!removeCreated()) Log.i(Probe.TAG, "native-media token=" + token
                            + " status=cleanup-pending complete=false ownedPartialOutputsRemoved=false");
                    throw failure;
                }
            }
            Log.i(Probe.TAG, "native-media token=" + token + " status="
                    + (receipt.getBoolean("accepted") ? "passed" : "failed")
                    + " complete=" + receipt.getBoolean("complete") + " nativeDashBranch=false outputsRetained=" + accepted);
        }

        File output(String name) throws Exception {
            check(); File file = Contract.child(result, name);
            if (!file.createNewFile()) throw new IOException("output collision");
            created.add(file); return file;
        }

        void invoke(Class<?> budget, Method method, File... files) throws Exception {
            check();
            Class<?> type = loader.loadClass(BUDGET + "$Deadline");
            Constructor<?> constructor = type.getDeclaredConstructor(long.class); constructor.setAccessible(true);
            Object fresh = constructor.newInstance(System.nanoTime()
                    + Math.max(0L, deadline - SystemClock.elapsedRealtime()) * 1_000_000L);
            Method run = Contract.method(budget, "runWithJobDeadline", type, Runnable.class);
            Throwable[] error = new Throwable[1];
            run.invoke(null, fresh, (Runnable) () -> {
                try { method.invoke(null, (Object[]) files); }
                catch (InvocationTargetException failure) { error[0] = failure.getCause(); }
                catch (Exception failure) { error[0] = failure; }
            });
            check();
            if (error[0] != null) {
                if (error[0] instanceof Exception) throw (Exception) error[0];
                throw new IOException("native operation failed", error[0]);
            }
        }

        void missing(Class<?> budget, Method method, File one, File two, String label, String prefix,
                JSONArray operations) throws Exception {
            File out = output(label.toLowerCase(java.util.Locale.ROOT) + ".mp4");
            boolean rejected = false;
            try { if (two == null) invoke(budget, method, one, out); else invoke(budget, method, one, two, out); }
            catch (IOException expected) { rejected = ("Download is missing its " + prefix + " track").equals(expected.getMessage()); }
            if (!rejected || out.length() != 0) throw new IOException("missing-track control failed");
            if (!out.delete()) throw new IOException("negative output cleanup failed");
            created.remove(out);
            operations.put(new JSONObject().put("operation", label).put("rejected", true).put("partialOutputRemoved", true));
        }

        JSONObject inspect(File file, String operation, String name) throws Exception {
            check(); JSONArray tracks = new JSONArray(); int video = 0, audio = 0, rotation = 0;
            long duration = 0L;
            MediaExtractor headers = new MediaExtractor();
            try {
                headers.setDataSource(file.getAbsolutePath());
                int count = headers.getTrackCount();
                if (count < 1 || count > 2) throw new IOException("unexpected track count");
                for (int index = 0; index < count; index++) {
                    MediaFormat format = headers.getTrackFormat(index);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if ("video/avc".equals(mime)) {
                        video++; rotation = format.containsKey("rotation-degrees") ? format.getInteger("rotation-degrees") : 0;
                    } else if ("audio/mp4a-latm".equals(mime)) audio++; else throw new IOException("unexpected fixture codec");
                    duration = Math.max(duration, format.getLong(MediaFormat.KEY_DURATION));
                    tracks.put(samples(file, index, mime).put("decoded", decode(file, index)));
                }
            } finally { releaseExtractor(headers); }
            int wantedVideo = name.equals("audio.m4a") || name.equals("sound.m4a") ? 0 : 1;
            int wantedAudio = name.equals("audio.m4a") || name.equals("sound.m4a")
                    || name.equals("av-90.mp4") || name.equals("combined.mp4") ? 1 : 0;
            if (!Contract.media(video, audio, rotation, duration, wantedVideo, wantedAudio)) {
                throw new IOException("native track metadata differs");
            }
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            int containerRotation = 0; long containerDuration;
            try {
                metadata.setDataSource(file.getAbsolutePath());
                if (video > 0) containerRotation = Integer.parseInt(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
                containerDuration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } finally {
                try { metadata.release(); }
                catch (Exception failure) { resourcesReleased = false; throw failure; }
            }
            if (containerRotation != rotation || containerDuration < 1700 || containerDuration > 2300) {
                throw new IOException("container metadata differs");
            }
            check();
            return new JSONObject().put("operation", operation).put("file", name).put("bytes", file.length())
                    .put("sha256", digest(file, MAX_MEDIA)).put("videoTracks", video).put("audioTracks", audio)
                    .put("rotation", rotation).put("durationUs", duration).put("containerDurationMs", containerDuration)
                    .put("tracks", tracks);
        }

        JSONObject samples(File file, int index, String mime) throws Exception {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath()); extractor.selectTrack(index);
                ByteBuffer buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024);
                int count = 0; long first = extractor.getSampleTime(), last = first, bytes = 0;
                while (true) {
                    check(); buffer.clear(); int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    if (++count > 1000 || (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                        throw new IOException("unexpected fixture samples");
                    }
                    long time = extractor.getSampleTime();
                    if (time < last) throw new IOException("nonmonotonic fixture samples");
                    last = time; bytes += size;
                    if (!extractor.advance()) break;
                }
                if (count == 0 || bytes == 0) throw new IOException("empty native samples");
                return new JSONObject().put("mime", mime).put("samples", count).put("sampleBytes", bytes)
                        .put("firstPtsUs", first).put("lastPtsUs", last);
            } finally { releaseExtractor(extractor); }
        }

        JSONObject decode(File file, int index) throws Exception {
            MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null; boolean started = false;
            try {
                extractor.setDataSource(file.getAbsolutePath()); extractor.selectTrack(index);
                MediaFormat format = extractor.getTrackFormat(index); String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith("video/")) format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
                codec = MediaCodec.createDecoderByType(mime); codec.configure(format, null, null, 0); codec.start(); started = true;
                boolean inputEnded = false, outputEnded = false; int buffers = 0, polls = 0; long bytes = 0;
                MessageDigest hash = MessageDigest.getInstance("SHA-256"); MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long decodeDeadline = Math.min(deadline, SystemClock.elapsedRealtime() + 8_000L);
                while (!outputEnded) {
                    Contract.before(SystemClock.elapsedRealtime(), decodeDeadline);
                    if (++polls > 4000) throw new IOException("decode iteration limit");
                    if (!inputEnded) {
                        int input = codec.dequeueInputBuffer(5_000L);
                        if (input >= 0) {
                            ByteBuffer data = codec.getInputBuffer(input);
                            if (data == null) throw new IOException("decoder input absent");
                            int size = extractor.readSampleData(data, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true;
                            } else {
                                codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0); extractor.advance();
                            }
                        }
                    }
                    int output = codec.dequeueOutputBuffer(info, 5_000L);
                    if (output >= 0) {
                        try {
                            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                ByteBuffer data = codec.getOutputBuffer(output);
                                if (data == null || info.offset < 0 || info.size > data.capacity() - info.offset) throw new IOException("decoder output absent");
                                data.position(info.offset); data.limit(info.offset + info.size); hash.update(data);
                                bytes += info.size; buffers++;
                                if (bytes > 8 * 1024 * 1024 || buffers > 1000) throw new IOException("decode output limit");
                            }
                            outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        } finally { codec.releaseOutputBuffer(output, false); }
                    }
                }
                Contract.before(SystemClock.elapsedRealtime(), decodeDeadline);
                if (buffers == 0 || bytes == 0) throw new IOException("empty decode");
                return new JSONObject().put("buffers", buffers).put("bytes", bytes).put("sha256", hex(hash.digest())).put("eos", true);
            } finally {
                try { if (codec != null) { try { if (started) codec.stop(); } finally { codec.release(); } } }
                catch (RuntimeException failure) { resourcesReleased = false; throw failure; }
                finally { releaseExtractor(extractor); }
            }
        }

        void releaseExtractor(MediaExtractor extractor) {
            try { extractor.release(); }
            catch (RuntimeException failure) { resourcesReleased = false; throw failure; }
        }

        boolean removeCreated() {
            boolean clean = true;
            for (File file : created) {
                try { if (file.exists() && (!file.equals(Contract.child(result, file.getName())) || !file.delete())) clean = false; }
                catch (IOException failure) { clean = false; }
            }
            return clean;
        }

        void cleanup() throws Exception {
            if (result.exists() && !result.isDirectory()) throw new IOException("result is not an owned directory");
            File marker = Contract.child(result, "owner.json"), receiptFile = Contract.child(result, "receipt.json");
            if (marker.exists()) {
                JSONObject owner = new JSONObject(read(marker, 1024));
                if (!token.equals(owner.getString("token")) || !manifestHash.equals(owner.getString("manifestSha256"))) {
                    throw new IOException("cleanup owner mismatch");
                }
            }
            JSONObject report = null;
            if (receiptFile.exists()) {
                report = new JSONObject(read(receiptFile, 32 * 1024));
                if (!token.equals(report.getString("token")) || !manifestHash.equals(report.getString("manifestSha256"))) {
                    throw new IOException("cleanup receipt mismatch");
                }
            }
            Map<String, String> outputHashes = new HashMap<>(), inputHashes = new HashMap<>();
            JSONArray ownedFiles = report == null ? new JSONArray() : report.getJSONArray("ownedFiles");
            for (int i = 0; i < ownedFiles.length(); i++) {
                JSONObject item = ownedFiles.getJSONObject(i);
                if (outputHashes.put(item.getString("file"), item.getString("sha256")) != null) throw new IOException("duplicate owned file");
            }
            for (String name : INPUTS) inputHashes.put(name, manifest.getJSONObject("inputs").getJSONObject(name).getString("sha256"));
            Contract.cleanup(root, manifestHash, inputHashes, outputHashes);
            Log.i(Probe.TAG, "native-media token=" + token + " status=cleaned complete=true ownedFilesRemoved=true settingsTouched=false");
        }

        String digest(File file, long limit) throws Exception { check(); String value = NativeMediaProbe.digest(file, limit); check(); return value; }
    }

    private static Throwable cause(Throwable failure) {
        return failure instanceof InvocationTargetException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private static String digest(File file, long limit) throws Exception {
        if (!file.isFile() || file.length() > limit) throw new IOException("file size limit");
        MessageDigest hash = MessageDigest.getInstance("SHA-256"); long count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int length;
            while ((length = input.read(buffer)) != -1) {
                count += length; if (count > limit) throw new IOException("file grew beyond limit");
                hash.update(buffer, 0, length);
            }
        }
        if (count != file.length()) throw new IOException("file length changed");
        return hex(hash.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static String read(File file, int limit) throws IOException {
        if (!file.isFile() || file.length() <= 0 || file.length() > limit) throw new IOException("JSON size limit");
        byte[] bytes = new byte[(int) file.length()]; int count = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (count < bytes.length) {
                int next = input.read(bytes, count, bytes.length - count);
                if (next < 0) throw new IOException("JSON truncated"); count += next;
            }
            if (input.read() != -1) throw new IOException("JSON grew");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void write(File file, JSONObject object, Contract.Check check) throws Exception {
        Contract.atomic(file, object.toString(2).getBytes(StandardCharsets.UTF_8), check);
    }
}
