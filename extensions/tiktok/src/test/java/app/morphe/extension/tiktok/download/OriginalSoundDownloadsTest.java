package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The sound a video was made with is a different file from the sound the video makes. Someone
 * saving "that song" wants the first, and reading it means walking a model that is obfuscated,
 * differently shaped between builds, and often simply absent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class OriginalSoundDownloadsTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    /** A sound entry with its own play list, which is the ordinary case. */
    @SuppressWarnings("unused")
    public static final class Music {
        private final Object playUrl;
        private final String title;

        Music(Object playUrl, String title) {
            this.playUrl = playUrl;
            this.title = title;
        }

        public Object getPlayUrl() { return playUrl; }
        public String getTitle() { return title; }
    }

    @SuppressWarnings("unused")
    public static final class PlayUrl {
        private final List<String> urlList;
        private final String uri;

        PlayUrl(List<String> urlList, String uri) {
            this.urlList = urlList;
            this.uri = uri;
        }

        public List<String> getUrlList() { return urlList; }
        public String getUri() { return uri; }
    }

    @SuppressWarnings("unused")
    public static final class Post {
        private final Object music;

        Post(Object music) { this.music = music; }

        public Object getMusic() { return music; }
        public String getAid() { return "7712345"; }
    }

    /** A post from a build that never had the model at all. */
    @SuppressWarnings("unused")
    public static final class BarePost {
        public String getAid() { return "7712345"; }
    }

    @Test public void everyMirrorIsOfferedInTheOrderTheModelGaveThem() {
        Post post = new Post(new Music(
                new PlayUrl(List.of("https://one.example/sound.m4a",
                        "https://two.example/sound.m4a"), null), "A song"));

        assertEquals(List.of("https://one.example/sound.m4a", "https://two.example/sound.m4a"),
                OriginalSoundDownloads.sourceUrls(post));
    }

    @Test public void aPostWithNoSoundModelAsksForNothing() {
        // The commonest case by far: an ordinary video with only its own audio.
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(new BarePost()));
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(new Post(null)));
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(new Post(new Music(null, "A song"))));
    }

    @Test public void aSoundWithNoUrlsAsksForNothing() {
        // A sound entry with an empty list is a sound that cannot be fetched, and asking for it
        // would be a download of nothing followed by a failure nobody can act on.
        Post empty = new Post(new Music(new PlayUrl(List.of(), null), "A song"));
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(empty));

        Post blanks = new Post(new Music(new PlayUrl(java.util.Arrays.asList("", "   "), null), "A song"));
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(blanks));
    }

    @Test public void aBuildWithOnlyASingleUriStillWorks() {
        Post single = new Post(new Music(
                new PlayUrl(List.of(), "https://one.example/sound.m4a"), "A song"));

        assertEquals(List.of("https://one.example/sound.m4a"),
                OriginalSoundDownloads.sourceUrls(single));
    }

    @Test public void aCleartextMirrorIsNotFetchedFrom() {
        // The address comes out of a server response and the bytes are saved to the gallery, so
        // a mirror anyone on the network can rewrite is not one to use. Every other saver here
        // already reads its addresses this way.
        Post mixed = new Post(new Music(new PlayUrl(List.of(
                "http://one.example/sound.m4a", "https://two.example/sound.m4a"), null), "A song"));
        assertEquals(List.of("https://two.example/sound.m4a"),
                OriginalSoundDownloads.sourceUrls(mixed));

        Post cleartextOnly = new Post(new Music(
                new PlayUrl(List.of("http://one.example/sound.m4a"), null), "A song"));
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(cleartextOnly));

        // The single-uri fallback answers to the same rule.
        Post single = new Post(new Music(
                new PlayUrl(List.of(), "http://one.example/sound.m4a"), "A song"));
        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(single));
    }

    @Test public void aUriThatIsNotAnAddressIsNotOffered() {
        // Some builds put an opaque id in that field rather than a URL, and fetching it would
        // be a request to nowhere.
        Post identifier = new Post(new Music(new PlayUrl(List.of(), "v09044g40000abc"), "A song"));

        assertEquals(List.of(), OriginalSoundDownloads.sourceUrls(identifier));
    }

    @Test public void theFileIsNamedAfterTheSound() {
        Post post = new Post(new Music(
                new PlayUrl(List.of("https://one.example/sound.m4a"), null),
                "Sunset Drive (Sped Up)"));

        String name = OriginalSoundDownloads.fileName(post, "m4a");
        assertTrue(name, name.startsWith("Sunset"));
        assertTrue(name, name.endsWith(".m4a"));
        // The same sound saved from two different posts is one file, not two.
        assertEquals(name, OriginalSoundDownloads.fileName(
                new Post(new Music(new PlayUrl(List.of("https://other.example/s.m4a"), null),
                        "Sunset Drive (Sped Up)")), "m4a"));
    }

    @Test public void aSoundWithATitleTooLongToBeAFilenameIsCutDown() {
        Post post = new Post(new Music(
                new PlayUrl(List.of("https://one.example/sound.m4a"), null), "a".repeat(400)));

        String name = OriginalSoundDownloads.fileName(post, "m4a");
        assertTrue(name + " is " + name.length() + " characters", name.length() <= 165);
        assertTrue(name, name.endsWith(".m4a"));
    }

    @Test public void aSoundWithNoTitleFallsBackToThePostsOwnName() {
        Post post = new Post(new Music(
                new PlayUrl(List.of("https://one.example/sound.m4a"), null), ""));

        String name = OriginalSoundDownloads.fileName(post, "m4a");
        assertTrue(name, name.endsWith(".m4a"));
        assertTrue("a nameless sound got no name at all: " + name, name.length() > 4);
    }

    // ------------------------------------------------------------- before anything is fetched

    @Test public void withoutStoragePermissionNothingIsFetchedAndTheReaderIsTold() {
        // API 23 to 28 write a real file. Without the permission the save used to fail after the
        // fetch had already run, and the only thing said was that it could not be saved.
        org.robolectric.shadows.ShadowToast.reset();
        Post post = new Post(new Music(
                new PlayUrl(List.of("https://one.example/sound.m4a"), null), "A song"));

        OriginalSoundDownloads.start(post, RuntimeEnvironment.getApplication());

        assertEquals("Allow storage for TikTok in Android settings to save sounds",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    @Test public void withStoragePermissionTheSaveGetsPastTheCheck() {
        org.robolectric.shadows.ShadowToast.reset();
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);

        // A post with no sound entry, so the next answer proves the permission gate was passed
        // without any network work being started.
        OriginalSoundDownloads.start(new BarePost(), RuntimeEnvironment.getApplication());

        assertEquals("This video has no original sound to save",
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }

    // ------------------------------------------------------------------ what the server sent

    @Test public void anMpegSoundIsRecognisedByItsTagAndByItsFrameSync() throws Exception {
        // TikTok hands back an MP3 for some sound addresses. Before this was read from the
        // bytes, every one of them was refused as an unsupported format and the save failed.
        assertEquals("mp3", fetchServed(padded(new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0}, 32)));
        assertEquals("mp3", fetchServed(padded(new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0}, 32)));
    }

    @Test public void adtsAacIsNotMistakenForMpegAudio() throws Exception {
        // Both start 0xFF and both satisfy the eleven-bit sync. Only the layer bits tell them
        // apart, and calling an AAC body .mp3 gives the gallery a file it will not play.
        assertEquals("aac", fetchServed(padded(new byte[]{(byte) 0xFF, (byte) 0xF1, 0x4C, (byte) 0x80}, 32)));
        assertEquals("aac", fetchServed(padded(new byte[]{(byte) 0xFF, (byte) 0xF9, 0x4C, (byte) 0x80}, 32)));
        assertEquals("audio/aac", OriginalSoundDownloads.mimeFor("aac"));
    }

    @Test public void anMp4SoundIsStillRecognised() throws Exception {
        assertEquals("m4a", fetchServed(padded(
                new byte[]{0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 32)));
    }

    @Test public void aBodyThatIsNotASoundIsStillRefused() {
        // The positive control for the two above: the reader must not have become "accept
        // anything", or a failed request would be saved as a file that plays nothing.
        java.io.IOException failure = org.junit.Assert.assertThrows(java.io.IOException.class,
                () -> fetchServed(padded(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 32)));
        assertTrue(failure.toString(), failure.getSuppressed().length > 0);
    }

    @Test public void theNameAndTheTypeFollowTheContainer() {
        Post post = new Post(new Music(
                new PlayUrl(List.of("https://one.example/sound"), null), "Sunset Drive"));

        assertTrue(OriginalSoundDownloads.fileName(post, "mp3").endsWith(".mp3"));
        assertTrue(OriginalSoundDownloads.fileName(post, "m4a").endsWith(".m4a"));
        assertEquals("audio/mpeg", OriginalSoundDownloads.mimeFor("mp3"));
        assertEquals("audio/mp4", OriginalSoundDownloads.mimeFor("m4a"));
        assertEquals("audio/ogg", OriginalSoundDownloads.mimeFor("ogg"));
    }

    /** A body of {@code length} bytes beginning with {@code head}, so the 16-byte read is fed. */
    private static byte[] padded(byte[] head, int length) {
        byte[] bytes = new byte[length];
        System.arraycopy(head, 0, bytes, 0, head.length);
        return bytes;
    }

    /** Returns what the real fetch and signature detector make of one public HTTPS response. */
    private static String fetchServed(byte[] body) throws Exception {
        MediaTransport.Client transport = MediaTransportFixtures.publicClient(url ->
                MediaTransportFixtures.response(url, 200, body));
        java.io.File temp = java.io.File.createTempFile("original-sound-test", ".tmp");
        try {
            return RemoteMedia.fetch(
                    List.of("https://v16.tiktokcdn.com/sound"),
                    temp, RemoteMedia.Kind.AUDIO, transport);
        } finally {
            temp.delete();
        }
    }
}
