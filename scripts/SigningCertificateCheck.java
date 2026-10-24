import com.android.apksig.ApkVerifier;
import com.android.apksigner.PasswordRetriever;
import com.android.apksigner.SignerParams;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.Set;
import java.util.TreeSet;

/** Reads only verified signing identities, never password values or private-key bytes. */
public final class SigningCertificateCheck {
    public static void main(String[] args) {
        try {
            if (args.length == 6 && "key".equals(args[0])) {
                SignerParams signer = new SignerParams();
                signer.setKeystoreFile(args[1]);
                signer.setKeystoreKeyAlias(args[2]);
                if (!"default".equals(args[3])) signer.setKeystoreType(args[3]);
                if ("BKS".equalsIgnoreCase(args[3])) {
                    signer.setKeystoreProviderClass("org.bouncycastle.jce.provider.BouncyCastleProvider");
                }
                signer.setKeystorePasswordSpec(args[4]);
                signer.setKeyPasswordSpec(args[5]);
                try (PasswordRetriever passwords = new PasswordRetriever()) {
                    signer.loadPrivateKeyAndCerts(passwords);
                    System.out.println(digest(signer.getCerts().get(0)));
                }
            } else if (args.length == 2 && "apk".equals(args[0])) {
                ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[1])).build().verify();
                if (!result.isVerified() || result.getSignerCertificates().isEmpty()) {
                    throw new IllegalArgumentException("Unverified APK");
                }
                Set<String> identities = new TreeSet<>();
                for (Certificate certificate : result.getSignerCertificates()) identities.add(digest(certificate));
                System.out.println(String.join(",", identities));
            } else {
                throw new IllegalArgumentException("Invalid certificate check");
            }
        } catch (Exception failure) {
            // Library exceptions can echo a malformed password spec. Keep their text private.
            System.err.println("Could not verify the signing certificate.");
            System.exit(1);
        }
    }

    private static String digest(Certificate certificate) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder hex = new StringBuilder(64);
        for (byte value : bytes) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}
