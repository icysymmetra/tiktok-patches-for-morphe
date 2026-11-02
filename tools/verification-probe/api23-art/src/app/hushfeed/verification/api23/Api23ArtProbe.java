package app.hushfeed.verification.api23;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Self-targeted member execution only. Does not start or interact with TikTok. */
public final class Api23ArtProbe extends Instrumentation {
    private static final String PACKAGE = "app.hushfeed.verification.api23";
    private final ContractChecks checks = new ContractChecks();
    private final JSONObject receipt = new JSONObject();
    private final JSONArray cases = new JSONArray();
    private boolean art, identity, restored, failed;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Context context = getTargetContext();
        Bundle result = new Bundle();
        try {
            receipt.put("schema", 1).put("scope", "API23 ART payload-member evidence only").put("fullTikTokAcceptance", false)
                    .put("helperPackage", context.getPackageName())
                    .put("helperVersion", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName)
                    .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                    .put("abis", new JSONArray(Build.SUPPORTED_ABIS)).put("vmName", System.getProperty("java.vm.name"))
                    .put("vmVersion", System.getProperty("java.vm.version")).put("cases", cases)
                    .put("vendorContent", "empty list or explicit synthetic Object marker; no served video")
                    .put("captionResources", "helper dm5/dly IDs with explicit 47.4.3 BuildNames test alias; no TikTok renderer");
            if (!PACKAGE.equals(context.getPackageName()) || Build.VERSION.SDK_INT != 23) {
                throw new IllegalStateException("requires this self-targeted helper on API23");
            }
            try (BufferedReader maps = new BufferedReader(new FileReader("/proc/self/maps"))) {
                for (String line; (line = maps.readLine()) != null;) if (line.contains("/libart.so")) { art = true; break; }
            }
            if (!art) throw new IllegalStateException("libart.so was not observed in this helper process");
            JSONObject provenance;
            try (InputStream input = context.getAssets().open("qualification.json")) {
                provenance = new JSONObject(new String(read(input, 65536), StandardCharsets.UTF_8));
            }
            receipt.put("qualification", provenance);
            try (ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir)) {
                identity = hash(apk, "classes2.dex").equals(provenance.getString("payloadSha256"))
                        && hash(apk, "classes3.dex").equals(provenance.getString("sharedSliceSha256"))
                        && hash(apk, "classes4.dex").equals(provenance.getString("vendorSliceSha256"));
            }
            if (!identity) throw new IllegalStateException("installed payload or vendor slice hash differs from the build receipt");
            runOnMainSync(() -> {
                try { PayloadCases.run(context, this::pass); }
                catch (Throwable error) { fail("payload-native-operations", error); }
                finally {
                    try { restored = PayloadCases.restore(); }
                    catch (Throwable error) { fail("helper-settings-restoration", error); }
                }
            });
            negative("map-default-negative", Api24NegativeControl::mapDefault);
            negative("list-sort-negative", Api24NegativeControl::listSort);
        } catch (Throwable error) { fail("runtime-or-artifact-qualification", error); }
        boolean accepted = checks.accepted(Build.VERSION.SDK_INT, art, identity, restored, failed);
        try {
            receipt.put("artMapped", art).put("payloadAndVendorIdentity", identity).put("helperSettingsRestored", restored)
                    .put("accepted", accepted);
            AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "api23-art-receipt.json"));
            FileOutputStream stream = file.startWrite();
            byte[] written = receipt.toString().getBytes(StandardCharsets.UTF_8);
            try { stream.write(written); stream.getFD().sync(); file.finishWrite(stream); }
            catch (Throwable error) { file.failWrite(stream); throw error; }
            try (InputStream input = file.openRead()) {
                if (!java.util.Arrays.equals(written, read(input, 65536))) throw new IllegalStateException("native receipt readback differs");
            }
            result.putString("receipt", receipt.toString());
            result.putString("receiptPath", file.getBaseFile().getAbsolutePath());
            result.putBoolean("accepted", accepted);
        } catch (Throwable error) {
            accepted = false; result.putBoolean("accepted", false);
            try { receipt.put("accepted", false); } catch (Exception ignored) { }
            result.putString("receiptWriteFailure", error.getClass().getName() + ": " + error.getMessage());
            result.putString("receipt", receipt.toString());
        }
        finish(accepted ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void pass(String name, String evidence) throws Exception {
        checks.pass(name); cases.put(new JSONObject().put("name", name).put("passed", true).put("evidence", evidence));
    }
    private void negative(String name, Runnable operation) throws Exception {
        try { operation.run(); throw new AssertionError("API24 negative control unexpectedly resolved on API23"); }
        catch (NoSuchMethodError expected) { pass(name, expected.getClass().getName()); }
    }
    private void fail(String stage, Throwable error) {
        failed = true;
        try {
            JSONObject detail = new JSONObject().put("name", stage).put("passed", false)
                    .put("errorClass", error.getClass().getName()).put("message", String.valueOf(error.getMessage()));
            if (error.getCause() != null) detail.put("causeClass", error.getCause().getClass().getName())
                    .put("causeMessage", String.valueOf(error.getCause().getMessage()));
            cases.put(detail);
        } catch (Exception ignored) { }
    }
    private static byte[] read(InputStream input, int limit) throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] block = new byte[4096];
        for (int count; (count = input.read(block)) != -1;) {
            if (bytes.size() + count > limit) throw new IllegalArgumentException("qualification asset exceeds its limit");
            bytes.write(block, 0, count);
        }
        return bytes.toByteArray();
    }
    private static String hash(ZipFile apk, String entry) throws Exception {
        if (apk.getEntry(entry) == null || apk.getEntry(entry).getSize() > 16 * 1024 * 1024) throw new IllegalArgumentException("missing or oversized APK DEX");
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[65536];
        try (InputStream input = apk.getInputStream(apk.getEntry(entry))) {
            for (int count; (count = input.read(block)) != -1;) digest.update(block, 0, count);
        }
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) output.append(String.format(Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }
}
