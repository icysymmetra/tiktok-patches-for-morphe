package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import app.morphe.extension.shared.Utils;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DmStickerDecryptionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final byte[] key = StickerGallerySaver.dmStickerKey("00112233445566778899aabbccddeeff");

    @Before public void context() { Utils.setContext(RuntimeEnvironment.getApplication()); }

    @Test public void fullStaticAndAnimatedSourcesSurviveAuthenticatedDecryptionByteForByte() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6WQAAAAASUVORK5CYII=");
        byte[] animated = new byte[512];
        System.arraycopy("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, animated, 0, 4);
        System.arraycopy("WEBPVP8X".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, animated, 8, 8);
        animated[20] = 2;
        for (byte[] media : new byte[][]{png, animated}) {
            File source = temporary.newFile(), decoded = temporary.newFile();
            Files.write(source.toPath(), encrypted(media));
            StickerGallerySaver.decryptDmSticker(source, decoded, key, MediaBudget.deadline());
            assertArrayEquals(media, Files.readAllBytes(decoded.toPath()));
        }
    }

    @Test public void wrongMissingAndMalformedKeysNeverLeavePlaintext() throws Exception {
        byte[] body = encrypted(new byte[64]);
        byte[] wrong = key.clone();
        wrong[0] ^= 1;
        for (byte[] candidate : new byte[][]{wrong, null, new byte[15], new byte[17]}) rejected(body, candidate);
        for (String invalid : new String[]{"", "abc", "0".repeat(31), "0".repeat(33), "g".repeat(32), "ａ".repeat(32), " " + "0".repeat(32)}) {
            assertNull(StickerGallerySaver.dmStickerKey(invalid));
        }
        assertNull(StickerGallerySaver.dmStickerKey(null));
        assertEquals(16, StickerGallerySaver.dmStickerKey("aA".repeat(16)).length);
        assertEquals(24, StickerGallerySaver.dmStickerKey("01".repeat(24)).length);
        assertEquals(32, StickerGallerySaver.dmStickerKey("01".repeat(32)).length);
    }

    @Test public void modifiedNonceBodyOrAuthenticationTagIsRejected() throws Exception {
        byte[] body = encrypted(new byte[128]);
        for (int position : new int[]{0, 12, body.length - 1}) {
            byte[] altered = body.clone();
            altered[position] ^= 1;
            rejected(altered, key);
        }
    }

    @Test public void readableMediaRequiresSuccessfulFinalAuthenticationBeforePublication() throws Exception {
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6WQAAAAASUVORK5CYII=");
        byte[] body = encrypted(Arrays.copyOf(png, 131072));
        body[body.length - 1] ^= 1;
        File source = temporary.newFile(), decoded = temporary.newFile();
        Files.write(source.toPath(), body);
        try {
            StickerGallerySaver.decryptDmSticker(source, decoded, key, MediaBudget.deadline());
            fail("readable media was accepted without an authenticated final tag");
        } catch (AEADBadTagException expected) {
            // The Cipher API must report the final tag failure directly. Stream wrappers
            // are permitted to swallow it, even if their update returned readable bytes.
            assertFalse("unauthenticated media reached the publication source", decoded.exists());
            assertArrayEquals(body, Files.readAllBytes(source.toPath()));
        }
    }

    @Test public void truncatedAndOversizedEncryptedInputIsRejectedBeforePublication() throws Exception {
        byte[] body = encrypted(new byte[64]);
        for (int size : new int[]{0, 11, 12, 27, 28, body.length - 1}) rejected(Arrays.copyOf(body, size), key);
        File source = temporary.newFile(), decoded = temporary.newFile();
        try (RandomAccessFile file = new RandomAccessFile(source, "rw")) { file.setLength(24L * 1024 * 1024 + 1); }
        try {
            StickerGallerySaver.decryptDmSticker(source, decoded, key, MediaBudget.deadline());
            fail("oversized encrypted sticker was accepted");
        } catch (java.io.IOException expected) {
            assertEquals("Invalid encrypted sticker", expected.getMessage());
            assertFalse(decoded.exists());
        }
    }

    @Test public void aMissingKeyCannotPublishCiphertextJustBecauseTheUrlAndServerCallItAnImage() throws Exception {
        File source = temporary.newFile();
        Files.write(source.toPath(), encrypted(new byte[64]));
        StickerGallerySaver.StickerAsset asset = new StickerGallerySaver.StickerAsset(
                java.util.List.of("https://example.invalid/private-message.gif"), false, true, null);
        Method publish = StickerGallerySaver.class.getDeclaredMethod("saveDownloadedSticker",
                android.content.Context.class, StickerGallerySaver.StickerAsset.class,
                String.class, File.class, String.class);
        publish.setAccessible(true);
        try {
            publish.invoke(null, RuntimeEnvironment.getApplication(), asset, asset.url, source, "image/gif");
            fail("ciphertext was published from image metadata");
        } catch (InvocationTargetException expected) {
            assertEquals("Unsupported sticker media type", expected.getCause().getMessage());
        }
    }

    @Test public void privateFailuresDiscardSensitiveTransportMessagesAndCauses() {
        java.io.IOException transport = new java.io.IOException(
                "https://example.invalid/private-message?token=value", new IllegalArgumentException("00112233445566778899aabbccddeeff"));
        java.io.IOException redacted = StickerGallerySaver.privateStickerFailure(transport);
        assertEquals("Private sticker mirror failed (IOException)", redacted.getMessage());
        assertNull(redacted.getCause());
        assertEquals(0, redacted.getSuppressed().length);
    }

    private void rejected(byte[] body, byte[] candidate) throws Exception {
        File source = temporary.newFile(), decoded = temporary.newFile();
        Files.write(source.toPath(), body);
        Files.write(decoded.toPath(), new byte[]{1, 2, 3});
        try {
            StickerGallerySaver.decryptDmSticker(source, decoded, candidate, MediaBudget.deadline());
            fail("unauthenticated sticker was accepted");
        } catch (java.io.IOException | java.security.GeneralSecurityException expected) {
            assertFalse("partial plaintext remained", decoded.exists());
            assertFalse(expected.toString().contains("00112233445566778899aabbccddeeff"));
        }
    }

    private byte[] encrypted(byte[] media) throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) nonce[i] = (byte) (i + 1);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(media);
        byte[] body = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
        System.arraycopy(ciphertext, 0, body, nonce.length, ciphertext.length);
        return body;
    }
}
