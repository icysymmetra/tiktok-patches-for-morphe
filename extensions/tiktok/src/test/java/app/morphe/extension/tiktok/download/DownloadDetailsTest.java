/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The details and folder contract was not covered by the filename-only tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DownloadDetailsTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private String oldVideo, oldPhoto, oldVideoPath, oldPhotoPath;

    @Before public void setup() {
        oldVideo = Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get();
        oldPhoto = Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get();
        oldVideoPath = Settings.DOWNLOAD_VIDEO_PATH.get();
        oldPhotoPath = Settings.DOWNLOAD_PHOTO_PATH.get();
        Settings.DOWNLOAD_VIDEO_PATH.save("DCIM/Clips");
        Settings.DOWNLOAD_PHOTO_PATH.save("Pictures/Posts");
    }

    @After public void cleanup() {
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save(oldVideo);
        Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save(oldPhoto);
        Settings.DOWNLOAD_VIDEO_PATH.save(oldVideoPath);
        Settings.DOWNLOAD_PHOTO_PATH.save(oldPhotoPath);
    }

    @Test public void capturesCaptionCreatorSourceAndPublicationTimeBeforeThePostChanges() {
        Post post = new Post("alice", "123");
        post.desc = "First line\nSecond line 🌿";
        DownloadDetails details = new DownloadDetails(post);
        post.desc = "another video";
        post.author.uniqueId = "bob";
        assertEquals("Creator: @alice\nLink: https://www.tiktok.com/@alice/video/123\n"
                + "Published: 2023-11-14T22:13:20Z\n\nCaption:\nFirst line\nSecond line 🌿\n", details.text());
    }

    @Test public void missingMetadataAndOversizedCaptionRemainReadableAndBounded() {
        Post post = new Post("", "123");
        post.createTime = 0;
        post.desc = "x".repeat(65535) + "🌿";
        String text = new DownloadDetails(post).text();
        assertTrue(text.startsWith("Creator: Unknown\n"));
        assertTrue(text.contains("Published: Unknown"));
        assertTrue(text.length() < 65700);
        assertFalse(Character.isHighSurrogate(text.charAt(text.length() - 2)));
    }

    @Test public void theJsonFormCarriesTheSameDetailsWithEveryCharacterEscaped() {
        Post post = new Post("alice", "123");
        post.desc = "Say \"hi\"\\\n\ttab \u0001 🌿";
        DownloadDetails details = new DownloadDetails(post, true);
        assertEquals(".json", details.extension());
        assertEquals("{\n"
                + "  \"id\": \"123\",\n"
                + "  \"creator\": \"@alice\",\n"
                + "  \"link\": \"https://www.tiktok.com/@alice/video/123\",\n"
                + "  \"published\": \"2023-11-14T22:13:20Z\",\n"
                + "  \"caption\": \"Say \\\"hi\\\"\\\\\\n\\ttab \\u0001 🌿\"\n"
                + "}\n", details.json());
    }

    @Test public void whatThePostDidNotHaveIsNullInJson() {
        Post post = new Post("", "123");
        post.createTime = 0;
        String json = new DownloadDetails(post, true).json();
        assertTrue(json.contains("\"creator\": null,"));
        assertTrue(json.contains("\"published\": null,"));
        assertTrue(json.contains("\"caption\": null\n"));
        assertEquals(".txt", new DownloadDetails(post).extension());
    }

    @Test public void theTitleIsTheCaptionsFirstLine() {
        Post post = new Post("alice", "123");
        post.desc = "\n  Morning run  \nwith friends";
        assertEquals("Morning run", new DownloadDetails(post).title());
        post.desc = "x".repeat(300);
        assertEquals(255, new DownloadDetails(post).title().length());
    }

    @Test @Config(sdk = 35)
    public void textAndVideoUseACommonRootOnScopedStorage() {
        assertEquals("Download/Clips/alice", DownloadDetails.pairedPath("DCIM/Clips/alice"));
        assertEquals("Download/Clips/alice", DownloadDetails.pairedPath("Movies/Clips/alice"));
        assertEquals("Documents/Clips/alice", DownloadDetails.pairedPath("Documents/Clips/alice"));
        assertEquals("Download/Clips/alice", DownloadDetails.pairedPath("Download/Clips/alice"));
    }

    @Test public void legacyStorageKeepsTheChosenRoot() {
        assertEquals("DCIM/Clips/alice", DownloadDetails.pairedPath("DCIM/Clips/alice"));
    }

    @Test public void ownedVideoAndPhotoSavesUseTheCreatorSegmentAsAFolder() {
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save("{creator}/{video_id}");
        Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}/{video_id}_{index}");
        Post post = new Post("alice", "123");
        assertEquals("DCIM/Clips/alice", DownloadFilenameFormatter.destinationPath(post, false));
        assertEquals("123.mp4", DownloadFilenameFormatter.formatSelectedVideoName(post));
        assertEquals("Pictures/Posts/alice", DownloadFilenameFormatter.destinationPath(post, true));
        assertEquals("123_2.png", DownloadFilenameFormatter.formatOriginalPhotoName(post, 2, "png"));
    }

    @Test public void creatorCannotEscapeTheChosenFolderOrExceedItsByteLimit() {
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save("{creator}/{video_id}");
        Post post = new Post("../elsewhere\\child", "123");
        assertEquals("DCIM/Clips/_elsewhere_child", DownloadFilenameFormatter.destinationPath(post, false));
        post.author.uniqueId = "界".repeat(400);
        String path = DownloadFilenameFormatter.destinationPath(post, false);
        assertTrue(path.substring("DCIM/Clips/".length()).getBytes(StandardCharsets.UTF_8).length <= 200);
        post.author.uniqueId = "...";
        assertEquals("DCIM/Clips/unknown", DownloadFilenameFormatter.destinationPath(post, false));
    }

    @Test public void nativeSavesWithTheSameFinalNameKeepSeparateCreatorFolders() throws Exception {
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save("{creator}/same");
        File first = File.createTempFile("first-", ".mp4", RuntimeEnvironment.getApplication().getCacheDir());
        File second = File.createTempFile("second-", ".mp4", RuntimeEnvironment.getApplication().getCacheDir());
        try {
            Files.write(first.toPath(), new byte[]{1});
            Files.write(second.toPath(), new byte[]{2});
            DownloadFilenameFormatter.registerDownloadedMediaName(first.getPath(), new Post("alice", "123"));
            DownloadFilenameFormatter.registerDownloadedMediaName(second.getPath(), new Post("bob", "456"));
            String firstPath = DownloadFilenameFormatter.getVideoDestination(first.getName());
            String secondPath = DownloadFilenameFormatter.getVideoDestination(second.getName());
            assertEquals("same.mp4", DownloadFilenameFormatter.consumeDestinationName(second.getName()));
            assertEquals("same.mp4", DownloadFilenameFormatter.consumeDestinationName(first.getName()));
            assertEquals("DCIM/Clips/alice", firstPath);
            assertEquals("DCIM/Clips/bob", secondPath);
            assertEquals(first.getName(), DownloadFilenameFormatter.consumeDestinationName(first.getName()));
        } finally {
            assertTrue(first.delete());
            assertTrue(second.delete());
        }
    }

    public static class Author {
        public String uniqueId;
        Author(String handle) { uniqueId = handle; }
        public String getUniqueId() { return uniqueId; }
    }

    public static class Post {
        public Author author;
        public String aid, desc = "";
        public long createTime = 1700000000L;
        Post(String handle, String id) { author = new Author(handle); aid = id; }
        public Author getAuthor() { return author; }
        public String getAid() { return aid; }
        public String getDesc() { return desc; }
        public long getCreateTime() { return createTime; }
    }
}
