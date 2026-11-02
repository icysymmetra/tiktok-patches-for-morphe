import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Temporary stores holding one fixture key with independent passwords. */
public final class SigningKeyFixtures {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        char[] storePassword = System.getenv("HUSHFEED_FIXTURE_STORE").toCharArray();
        char[] entryPassword = System.getenv("HUSHFEED_FIXTURE_ENTRY").toCharArray();
        KeyStore source = KeyStore.getInstance("JKS");
        try (InputStream input = Files.newInputStream(directory.resolve("source.jks"))) {
            source.load(input, storePassword);
        }
        Key key = source.getKey("sideload", entryPassword);
        Certificate[] chain = source.getCertificateChain("sideload");
        write(directory.resolve("blank.bks"), "BKS", new char[0], entryPassword, key, chain);
        write(directory.resolve("independent.bks"), "BKS", storePassword, entryPassword, key, chain);
        write(directory.resolve("independent.jks"), "JKS", storePassword, entryPassword, key, chain);
        write(directory.resolve("independent.p12"), "PKCS12", storePassword, entryPassword, key, chain);
        write(directory.resolve("defaults.p12"), "PKCS12", "sideload".toCharArray(), "sideload".toCharArray(), key, chain);
    }

    private static void write(Path path, String type, char[] storePassword, char[] entryPassword,
                              Key key, Certificate[] chain) throws Exception {
        KeyStore store = "BKS".equals(type)
                ? KeyStore.getInstance(type, new BouncyCastleProvider()) : KeyStore.getInstance(type);
        store.load(null, storePassword);
        store.setKeyEntry("sideload", key, entryPassword, chain);
        try (OutputStream output = Files.newOutputStream(path)) { store.store(output, storePassword); }
    }
}

/** An unsigned-only stand-in with no provider dependency on its runtime classpath. */
final class UnsignedPatcherFixture {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].startsWith("@")) {
            args = Files.readAllLines(Path.of(args[0].substring(1))).stream()
                    .map(line -> line.substring(1, line.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\"))
                    .toArray(String[]::new);
        }
        if (args.length == 0 || !"patch".equals(args[0])) throw new IllegalArgumentException("Expected patching");
        Path output = null;
        Path report = null;
        List<String> patches = new ArrayList<>();
        boolean unsigned = false;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--unsigned": unsigned = true; break;
                case "-o": output = Path.of(args[++i]); break;
                case "-r": report = Path.of(args[++i]); break;
                case "-e": patches.add(args[++i]); break;
                default:
                    if (args[i].startsWith("--keystore")) throw new IllegalArgumentException("Patcher received a signing key");
            }
        }
        if (!unsigned || output == null || report == null) throw new IllegalArgumentException("Expected unsigned patching");
        Files.writeString(Path.of(System.getenv("HUSHFEED_FIXTURE_PATCH_LOG")), "unsigned\n");
        Files.copy(Path.of(System.getenv("HUSHFEED_FIXTURE_APK")), output, StandardCopyOption.REPLACE_EXISTING);
        if ("1".equals(System.getenv("HUSHFEED_FIXTURE_PATCH_FAIL"))) System.exit(1);
        String names = "\"" + String.join("\",\"", patches) + "\"";
        Files.writeString(report, "{\"packageName\":\"com.zhiliaoapp.musically\",\"packageVersion\":\"1.0.0\","
                + "\"appliedPatches\":[" + names + "],\"failedPatches\":[],\"patchingSteps\":[{\"success\":true}]}");
    }
}
