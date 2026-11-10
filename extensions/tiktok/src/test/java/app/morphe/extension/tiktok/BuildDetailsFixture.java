package app.morphe.extension.tiktok;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.assertTrue;

/** Uses the real Android AssetManager reader without adding a build asset to the test app. */
public final class BuildDetailsFixture implements AutoCloseable {
    public final Context context;
    private final AssetManager assets;

    public BuildDetailsFixture(Context base, File apk, String metadata) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            output.putNextEntry(new ZipEntry("assets/" + BuildDetails.ASSET));
            output.write(metadata.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        assets = AssetManager.class.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
        add.setAccessible(true);
        assertTrue("Android refused the fixture asset APK", (Integer) add.invoke(assets, apk.getAbsolutePath()) > 0);
        context = new ContextWrapper(base) {
            @Override public AssetManager getAssets() { return assets; }
            @Override public Context getApplicationContext() { return this; }
        };
    }

    @Override public void close() { assets.close(); }
}
