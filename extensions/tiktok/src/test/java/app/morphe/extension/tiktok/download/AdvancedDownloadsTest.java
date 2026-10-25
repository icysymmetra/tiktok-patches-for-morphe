package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;
import android.os.Environment;
import android.os.Looper;
import android.preference.PreferenceActivity;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DownloadsPreferenceCategory;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AdvancedDownloadsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void tearDown() {
        SettingsStatus.advancedDownloadsEnabled = false;
        // A saved value stays in the setting for the whole test JVM, so a template set here
        // renamed the frames FrameSaverTest saves later.
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.resetToDefault();
        Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.resetToDefault();
    }
    public static final class Address extends UrlModel {
        private final String url;
        private final long size;
        private int width, height;
        private List<String> encodings;
        Address(String url, long size) { this.url = url; this.size = size; }
        /** The frame size 47.0.3's UrlModel carries (getWidth, getHeight); 0 is unknown. */
        Address frame(int w, int h) { width = w; height = h; return this; }
        /** One photo in several encodings, listed the way TikTok lists them. */
        Address encodings(String... urls) { encodings = List.of(urls); return this; }
        @Override public List<String> getUrlList() {
            return encodings != null ? encodings : url == null ? List.of() : List.of(url);
        }
        @Override public long getSize() { return size; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }
    public static final class Gear {
        public final String gearName;
        public final int bitRate;
        public final UrlModel playAddr;
        public String videoExtra;
        /** TikTok's codec code: 0 H.264, 1 ByteVC1 (HEVC), 2 ByteVC2. */
        public int isBytevc1;
        public Gear(String name, int rate, String url) { gearName = name; bitRate = rate; playAddr = new Address(url, rate * 10L); }
        Gear codec(int code) { isBytevc1 = code; return this; }
    }
    public static final class VideoData {
        public final List<Gear> bitRate;
        public List<Audio> bitRateAudio = List.of();
        public boolean dash;
        public Address downloadNoWatermarkAddr, downloadAddr, playAddr;
        VideoData(List<Gear> gears) { bitRate = gears; }
        public List<Gear> getBitRate() { throw new AssertionError("Must not recurse into playback getter"); }
        public boolean hasDashBitrate() { return dash; }
    }
    /**
     * TikTok 47.0.3's Video: the list moved to the field bitRateList, and getRawBitRate returns
     * it whole. The playback getter must still never be asked.
     */
    public static final class VideoData47 {
        public final List<Gear> bitRateList;
        public Address downloadNoWatermarkAddr, downloadAddr;
        VideoData47(List<Gear> gears) { bitRateList = gears; }
        public List<Gear> getRawBitRate() { return bitRateList; }
        public Address getDownloadNoWatermarkAddr() { return downloadNoWatermarkAddr; }
        public Address getDownloadAddr() { return downloadAddr; }
        public List<Gear> getBitRate() { throw new AssertionError("Must not recurse into playback getter"); }
        public boolean hasDashBitrate() { return false; }
    }
    /** The renamed field with no raw getter: the field is read under its new name. */
    public static final class VideoDataRenamedField {
        public final List<Gear> bitRateList;
        VideoDataRenamedField(List<Gear> gears) { bitRateList = gears; }
        public List<Gear> getBitRate() { throw new AssertionError("Must not recurse into playback getter"); }
        public boolean hasDashBitrate() { return false; }
    }
    public static final class Audio {
        public final AudioMeta audioMeta;
        Audio(String id, int rate) { audioMeta = new AudioMeta(id, rate); }
    }
    public static final class AudioMeta {
        public final String fileId;
        public final long bitrate;
        public final AudioUrls urlList;
        AudioMeta(String id, int rate) { fileId = id; bitrate = rate; urlList = new AudioUrls(id); }
    }
    public static final class AudioUrls {
        public final String mainUrl, backupUrl, fallbackUrl;
        AudioUrls(String id) { mainUrl = "https://example.com/" + id; backupUrl = mainUrl + "/backup"; fallbackUrl = mainUrl + "/fallback"; }
    }
    public static final class Photo {
        public final Address displayImageNoWatermark;
        public final Address thumbnail = new Address("https://example.com/thumb", 50);
        /** 47.0.3's live photo: the struct whose videoModel is what the flag saves as video. */
        public LivePhoto livePhotoStruct;
        Photo(String url) { displayImageNoWatermark = new Address(url, 100); }
        Photo(Address source) { displayImageNoWatermark = source; }
        Photo live() { livePhotoStruct = new LivePhoto(); return this; }
    }
    public static final class LivePhoto {
        public final Object videoModel = new Object();
    }
    public static final class Info {
        public List<Photo> imageList;
        Info(List<Photo> photos) { imageList = photos; }
    }
    public static final class Author {
        private final String uniqueId;
        Author(String uniqueId) { this.uniqueId = uniqueId; }
        public String getUniqueId() { return uniqueId; }
        public String getNickname() { return "nickname"; }
    }
    /** A post with the fields the filename tokens are read from. */
    public static final class Item {
        private final Author author;
        private final String aid;
        private final List<Object> imageInfos;
        Item(String creator, String aid) { this(creator, aid, 0); }
        Item(String creator, String aid, int photoCount) {
            this.author = new Author(creator);
            this.aid = aid;
            this.imageInfos = java.util.Collections.nCopies(photoCount, new Object());
        }
        public Author getAuthor() { return author; }
        public String getAid() { return aid; }
        public List<Object> getImageInfos() { return imageInfos; }
    }
    /** A profile, carrying only the avatar sizes a given test wants to offer. */
    public static final class Account {
        public Address avatar300, avatarLarger, avatarMedium, avatar168, avatarThumb;
        private final String uniqueId, nickname;
        Account(String uniqueId, String nickname) { this.uniqueId = uniqueId; this.nickname = nickname; }
        public String getUniqueId() { return uniqueId; }
        public String getNickname() { return nickname; }
    }
    /**
     * The profile fetch response, shaped like the patched one: the patch prepends the record
     * call to getUser itself, so a double with a plain getter would hide a reader that goes
     * back through the getter and never stops.
     */
    public static final class Profile {
        public final Account user;
        static int getUserCalls;
        Profile(Account user) { this.user = user; }
        public Account getUser() {
            getUserCalls++;
            ProfileAvatarSaver.recordProfileResponse(this);
            return user;
        }
    }
    public static final class Post {
        public final Info photoModeImageInfo;
        Post(List<Photo> photos) { photoModeImageInfo = new Info(photos); }
    }
    /** A photo post with an id, which is what a save is keyed on. */
    public static final class PhotoPost {
        public final Info photoModeImageInfo;
        public VideoData video;
        private final String aid;
        PhotoPost(String aid, List<Photo> photos) { this.aid = aid; photoModeImageInfo = new Info(photos); }
        public String getAid() { return aid; }
    }
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void qualityUsesValidVideoGearsAndKeepsDownloadSeparateFromPlayback() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Gear low = new Gear("normal_360_0", 100, "https://example.com/low");
        Gear medium = new Gear("normal_720_0", 200, "https://example.com/mid");
        Gear high = new Gear("normal_1080_0", 400, "https://example.com/high");
        Gear missing = new Gear("normal_2160_0", 900, null);
        List<Gear> gears = List.of(medium, missing, high, low);
        assertSame(high, QualitySelector.choose(gears, "highest"));
        assertSame(low, QualitySelector.choose(gears, "lowest"));
        assertSame(medium, QualitySelector.choose(gears, "720"));
        assertSame(low, QualitySelector.choose(gears, "540"));
        assertSame(medium, QualitySelector.choose(List.of(high, medium), "360"));
        assertNull(QualitySelector.choose(gears, "auto"));
        assertNull(QualitySelector.choose(gears, "typo"));
        Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
        assertSame(high.playAddr, QualitySelector.download(new VideoData(gears)));
        assertEquals(4, gears.size());
    }

    /**
     * Each build's rendition list is found. 47.0.3 renamed the field to bitRateList; reading
     * bitRate alone found nothing there and every chosen quality fell back to TikTok's own save
     * (S22, 2026-09-23). The download's own choice and the address TikTok's save is handed both
     * read it.
     */
    @Test public void everyBuildsRenditionListIsRead() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Gear low = new Gear("normal_360_0", 100, "https://example.com/low");
        Gear medium = new Gear("normal_720_0", 200, "https://example.com/mid");
        Gear high = new Gear("normal_1080_0", 400, "https://example.com/high");
        List<Gear> gears = List.of(medium, high, low);
        for (Object video : new Object[]{new VideoData(gears), new VideoData47(gears), new VideoDataRenamedField(gears)}) {
            String shape = video.getClass().getSimpleName();
            assertSame(shape + ": the chosen quality", medium, VideoDownloads.selectedGear(video, "720", false));
            assertSame(shape + ": Automatic with captions takes the highest", high, VideoDownloads.selectedGear(video, "auto", true));
            assertNull(shape + ": Automatic alone is TikTok's own save", VideoDownloads.selectedGear(video, "auto", false));
            Settings.DOWNLOAD_VIDEO_QUALITY.save("720");
            assertSame(shape + ": the address TikTok's save is handed", medium.playAddr, QualitySelector.download(video));
        }
    }

    /**
     * 47.0.3 serves some heights only as ByteVC2, which nothing but TikTok's own player decodes
     * (a saved file's video track came out as codec tag bvc2 on the S22). Those are never chosen;
     * at the same height H.264 is taken over HEVC; with nothing playable left the save is
     * TikTok's own.
     */
    @Test public void onlyRenditionsOtherPlayersOpenAreChosen() {
        Gear bvc2High = new Gear("adapt_lower_720_2", 500, "https://example.com/720-bvc2").codec(2);
        Gear hevcHigh = new Gear("adapt_lowest_1080_1", 450, "https://example.com/1080-hevc").codec(1);
        Gear bvc2Mid = new Gear("adapt_540_2", 300, "https://example.com/540-bvc2").codec(2);
        Gear hevcMid = new Gear("lower_540_1", 200, "https://example.com/540-hevc").codec(1);
        Gear h264Mid = new Gear("normal_540_0", 150, "https://example.com/540-h264").codec(0);
        List<Gear> gears = List.of(bvc2High, hevcHigh, bvc2Mid, hevcMid, h264Mid);
        assertSame("720: the ByteVC2 720 is skipped for the playable 540", h264Mid, QualitySelector.chooseForFile(gears, "720"));
        assertSame("highest: the playable 1080", hevcHigh, QualitySelector.chooseForFile(gears, "highest"));
        assertSame("540: H.264 over HEVC and ByteVC2 at the same height", h264Mid, QualitySelector.chooseForFile(gears, "540"));
        assertSame("lowest: still H.264 at the same height", h264Mid, QualitySelector.chooseForFile(gears, "lowest"));
        assertSame("without H.264, HEVC", hevcMid, QualitySelector.chooseForFile(List.of(bvc2Mid, hevcMid), "540"));
        assertNull("nothing playable: TikTok's own save", QualitySelector.chooseForFile(List.of(bvc2High, bvc2Mid), "720"));
        assertNull("and for the download's own choice too",
                VideoDownloads.selectedGear(new VideoData47(List.of(bvc2High, bvc2Mid)), "720", false));
        Settings.DOWNLOAD_VIDEO_QUALITY.save("720");
        assertSame("and for the address TikTok's save is handed", h264Mid.playAddr, QualitySelector.download(new VideoData47(gears)));
        // TikTok's own player decodes ByteVC2, so the choice for playback keeps it.
        assertSame("playback still takes the ByteVC2 720", bvc2High, QualitySelector.choose(gears, "720"));
    }

    /**
     * When the asked height is served only as ByteVC2 the playable choice is a shorter one, while
     * TikTok's own download can be that height in H.264. TikTok's file wins then, but only when its
     * address says so and it is no taller than asked (refutation review of bd52abf1).
     */
    @Test public void aTallerOwnDownloadWinsOverAShorterPlayableChoice() {
        Gear bvc2High = new Gear("adapt_lower_720_2", 500, "https://example.com/720-bvc2").codec(2);
        Gear h264Mid = new Gear("normal_540_0", 150, "https://example.com/540-h264").codec(0);
        VideoData47 video = new VideoData47(List.of(bvc2High, h264Mid));
        assertSame("an address that says nothing keeps the playable 540",
                h264Mid, VideoDownloads.selectedGear(video, "720", false));

        // Only the watermarked address says 720: a deferred save would fall to it and put the
        // watermark on a save the rendition path kept clean, so the playable 540 stays.
        video.downloadAddr = new Address("https://example.com/marked.mp4", 900).frame(720, 1280);
        assertSame("a watermarked-only 720 took the save", h264Mid, VideoDownloads.selectedGear(video, "720", false));

        video.downloadNoWatermarkAddr = new Address("https://example.com/clean.mp4", 900).frame(720, 1280);
        assertNull("TikTok's own clean 720 lost to the playable 540", VideoDownloads.selectedGear(video, "720", false));
        assertNull("for Highest too", VideoDownloads.selectedGear(video, "highest", false));
        assertNull("and for Automatic with captions", VideoDownloads.selectedGear(video, "auto", true));
        Settings.DOWNLOAD_VIDEO_QUALITY.save("720");
        assertNull("TikTok's own save was handed the 540", QualitySelector.download(video));
        assertSame("asked for 540, the 540", h264Mid, VideoDownloads.selectedGear(video, "540", false));
        assertSame("Lowest is left alone", h264Mid, VideoDownloads.selectedGear(video, "lowest", false));

        video.downloadNoWatermarkAddr = new Address("https://example.com/clean.mp4", 900).frame(1080, 1920);
        assertSame("taller than asked: the playable 540 stays", h264Mid, VideoDownloads.selectedGear(video, "720", false));
        video.downloadNoWatermarkAddr = new Address("https://example.com/clean.mp4", 900).frame(540, 960);
        assertSame("no taller than the choice: the playable 540 stays", h264Mid, VideoDownloads.selectedGear(video, "720", false));
        video.downloadNoWatermarkAddr = new Address("https://example.com/clean.mp4", 900).frame(486, 864);
        assertSame("shorter than the choice: the playable 540 stays", h264Mid, VideoDownloads.selectedGear(video, "720", false));
        assertSame("and in Highest", h264Mid, VideoDownloads.selectedGear(video, "highest", false));
    }

    @Test public void photosUseOrderedSourceImagesAndNeverThumbnails() {
        Post post = new Post(List.of(new Photo("https://example.com/one"), new Photo("https://example.com/two")));
        assertEquals(List.of(List.of("https://example.com/one"), List.of("https://example.com/two")), OriginalPhotos.sources(post));
        post.photoModeImageInfo.imageList = List.of(new Photo((String) null));
        assertTrue(OriginalPhotos.sources(post).isEmpty());
        assertTrue(OriginalPhotos.sources(new Object()).isEmpty());
    }

    /**
     * TikTok leads a photo's list with a HEIF encoding, and that was what got saved: a .heif
     * that plenty of galleries can't open (#105). The JPEG copy goes first, and a JPEG named only
     * in the query doesn't count. The WebP comes before the HEIF, since a Samsung can't decode
     * TikTok's HEIF to make a JPEG of it (S22, 47.0.3, every photo listed as "heic, webp").
     */
    @Test public void photosTryJpegThenWebpBeforeHeif() {
        String heic = "https://p16-sign.example.com/obj/abc~tplv-photomode-image.heic?x-expires=1&name=a.jpeg";
        String webp = "https://p16-sign.example.com/obj/abc~tplv-photomode-image.webp?x-expires=1";
        String jpeg = "https://p16-sign.example.com/obj/abc~tplv-photomode-image.jpeg?x-expires=1";
        String upper = "https://p19-sign.example.com/obj/def~tplv-photomode-image.JPG";
        String avif = "https://p16-sign.example.com/obj/ghi~tplv-photomode-image.avif?x-expires=1";
        String png = "https://p16-sign.example.com/obj/ghi~tplv-photomode-image.png";
        Post post = new Post(List.of(
                new Photo(new Address(null, 100).encodings(heic, webp, jpeg)),
                new Photo(new Address(null, 100).encodings(webp, upper)),
                new Photo(new Address(null, 100).encodings(heic, webp)),
                new Photo(new Address(null, 100).encodings(avif, heic, png, webp))));
        assertEquals(List.of(List.of(jpeg, webp, heic), List.of(upper, webp), List.of(webp, heic),
                        List.of(png, webp, avif, heic)),
                OriginalPhotos.sources(post));
        // What a debug report says of each list: the path's ending only, never the signed query.
        assertEquals(List.of("heic", "webp", "jpeg", "jpg", "?"), OriginalPhotos.encodings(
                List.of(heic, webp, jpeg, upper, "https://p16-sign.example.com/obj/abc")));
        // The log redacts before it prints, and "encodings: [heic" lost its heic to it.
        String listing = OriginalPhotos.listing(1, List.of(heic, webp));
        assertEquals("Original photo 1 is listed as heic, webp", listing);
        assertEquals(listing, app.morphe.extension.shared.diagnostics.DiagnosticRedactor.redact(listing));
    }

    /**
     * 47.0.3's photo save job says which photos it was asked for, counted from 0: the one photo
     * for "Download image", the picked ones from TikTok's selection sheet. The older entry, the
     * video download start, asks for all of them.
     */
    @Test public void aPhotoSaveTakesOnlyThePhotosItWasAskedFor() {
        assertEquals(List.of(0, 1, 2), OriginalPhotos.positions(null, 3));
        assertEquals(List.of(0), OriginalPhotos.positions(java.util.Set.of(0), 3));
        assertEquals("in the post's order", List.of(0, 2), OriginalPhotos.positions(java.util.Set.of(2, 0), 3));
        assertEquals("a Long index reads the same", List.of(1), OriginalPhotos.positions(java.util.Set.of(1L), 3));
        assertEquals("a photo the post doesn't have", List.of(), OriginalPhotos.positions(java.util.Set.of(5), 3));
        assertEquals(List.of(), OriginalPhotos.positions(java.util.Set.of(), 3));
    }

    /**
     * The job's entry takes a save only with the switch on, and only for photos the post has.
     * The flag is not "this is a video save": the picker sets it for every save, stills
     * included (the job reads it per item, picking livePhotoStruct.videoModel where one
     * exists), so the entry stands aside only when a chosen photo really is a live photo. The
     * old assertion here, "flag set means TikTok's", was that wrong premise: it made every
     * picker save fall to TikTok's own .webp on the S22. A save already running for the post
     * answers "taken" before anything is fetched, which is what lets this reach the decision
     * without the network.
     */
    @Test public void thePhotoSaveJobTakesOnlyTheSavesItShould() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        PhotoPost post = new PhotoPost("busy-post", List.of(new Photo("https://example.com/one"), new Photo("https://example.com/two")));
        java.util.Set<String> active = org.robolectric.util.ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");
        active.add("busy-post");
        try {
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
            assertTrue("the second photo, asked for", OriginalPhotos.startPhotos(post, java.util.Set.of(1), false));
            assertTrue("the picker's flag on plain stills is not a video save",
                    OriginalPhotos.startPhotos(post, java.util.Set.of(0, 1), true));
            assertFalse("a photo the post doesn't have", OriginalPhotos.startPhotos(post, java.util.Set.of(2), false));
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(false);
            assertFalse("the switch off", OriginalPhotos.startPhotos(post, java.util.Set.of(0), false));
        } finally {
            active.remove("busy-post");
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.resetToDefault();
        }
    }

    @Test public void videoQualityNeverTakesAPhotoPostBeforeItsPhotoSave() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        PhotoPost post = new PhotoPost("photo-with-video", List.of(new Photo("https://example.com/original")));
        post.video = new VideoData(List.of(new Gear("normal_720_0", 700, "https://example.com/video")));
        post.video.downloadAddr = new Address("https://example.com/watermarked-video", 100);
        Set<String> videos = org.robolectric.util.ReflectionHelpers.getStaticField(VideoDownloads.class, "ACTIVE");
        Set<String> photos = org.robolectric.util.ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");
        String previousQuality = Settings.DOWNLOAD_VIDEO_QUALITY.savedValue();
        boolean previousOriginals = Settings.DOWNLOAD_ORIGINAL_PHOTOS.savedValue();
        boolean previousMute = Settings.DOWNLOAD_WITHOUT_SOUND.savedValue();
        boolean previousDetails = Settings.DOWNLOAD_DETAILS.savedValue();
        boolean previousCheckSaved = Settings.CHECK_SAVED_VIDEOS.savedValue();
        boolean previousProgress = Settings.DOWNLOAD_PROGRESS.savedValue();
        videos.add(post.getAid());
        photos.add(post.getAid());
        try {
            Settings.DOWNLOAD_DETAILS.save(false);
            Settings.CHECK_SAVED_VIDEOS.save(false);
            Settings.DOWNLOAD_PROGRESS.save(false);
            for (String quality : List.of("auto", "highest", "720")) {
                Settings.DOWNLOAD_VIDEO_QUALITY.save(quality);
                for (boolean original : List.of(false, true)) {
                    Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(original);
                    for (boolean mute : List.of(false, true)) {
                        Settings.DOWNLOAD_WITHOUT_SOUND.save(mute);
                        assertFalse("Photo dispatch was swallowed by quality=" + quality
                                + ", originals=" + original + ", mute=" + mute,
                                VideoDownloads.start(post, RuntimeEnvironment.getApplication()));
                    }
                }
            }
            Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(false);
            assertFalse("With originals off the generic entry must leave the photo save to TikTok",
                    OriginalPhotos.start(post, RuntimeEnvironment.getApplication()));
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
            assertTrue(OriginalPhotos.start(post, RuntimeEnvironment.getApplication()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("The generic download entry must reach the image job",
                    MediaJobScheduler.busyMessage("photos " + post.getAid()),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            post.photoModeImageInfo.imageList = List.of(new Photo((String) null));
            assertFalse("Missing original URLs must leave the generic photo save to TikTok",
                    OriginalPhotos.start(post, RuntimeEnvironment.getApplication()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("A native fallback must explain why the originals were not saved",
                    "The original photos aren't available, so TikTok's own save runs instead",
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        } finally {
            videos.remove(post.getAid());
            photos.remove(post.getAid());
            Settings.DOWNLOAD_VIDEO_QUALITY.save(previousQuality);
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(previousOriginals);
            Settings.DOWNLOAD_WITHOUT_SOUND.save(previousMute);
            Settings.DOWNLOAD_DETAILS.save(previousDetails);
            Settings.CHECK_SAVED_VIDEOS.save(previousCheckSaved);
            Settings.DOWNLOAD_PROGRESS.save(previousProgress);
        }
    }

    /** A chosen live photo would come down as a video with the flag set: that save is TikTok's. */
    @Test public void aChosenLivePhotoLeavesTheSaveToTikTok() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        Photo still = new Photo("https://example.com/one");
        Photo live = new Photo("https://example.com/two").live();
        PhotoPost post = new PhotoPost("live-post", List.of(still, live));
        java.util.Set<String> active = org.robolectric.util.ReflectionHelpers.getStaticField(OriginalPhotos.class, "ACTIVE");
        active.add("live-post");
        try {
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.save(true);
            assertFalse("the chosen live photo was taken from TikTok",
                    OriginalPhotos.startPhotos(post, java.util.Set.of(0, 1), true));
            assertTrue("the still alone is not a video save", OriginalPhotos.startPhotos(post, java.util.Set.of(0), true));
            assertTrue("without the flag the live photo saves as its still",
                    OriginalPhotos.startPhotos(post, java.util.Set.of(0, 1), false));
            assertFalse("an unreadable post with the flag set stays TikTok's",
                    OriginalPhotos.startPhotos(new Object(), java.util.Set.of(0), true));
        } finally {
            active.remove("live-post");
            Settings.DOWNLOAD_ORIGINAL_PHOTOS.resetToDefault();
        }
    }

    @Test public void adaptiveDownloadsPairTheRequestedAudioAndNeverReturnSilentVideoUrl() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DOWNLOAD_VIDEO_QUALITY.save("highest");
        Gear gear = new Gear("1080p", 400, "https://example.com/video");
        gear.videoExtra = "{\"audio_file_id\":\"matching\"}";
        VideoData video = new VideoData(List.of(gear));
        video.dash = true;
        video.bitRateAudio = List.of(new Audio("unrelated", 200), new Audio("matching", 100));
        assertEquals(List.of("https://example.com/matching", "https://example.com/matching/backup", "https://example.com/matching/fallback"), VideoDownloads.audioUrls(video, gear));
        assertNull(QualitySelector.download(video));
        gear.videoExtra = "{\"audio_file_id\":\"missing\"}";
        assertTrue(VideoDownloads.audioUrls(video, gear).isEmpty());
        gear.videoExtra = null;
        assertEquals("https://example.com/unrelated", VideoDownloads.audioUrls(video, gear).get(0));
    }

    @Test public void muxerCopiesBothCompressedTracksAndRejectsMissingAudio() throws Exception {
        File video = File.createTempFile("mux-video", ".mp4"), audio = File.createTempFile("mux-audio", ".mp4"), output = File.createTempFile("mux-result", ".mp4");
        try {
            var videoSource = org.robolectric.shadows.util.DataSource.toDataSource(video.getAbsolutePath());
            var audioSource = org.robolectric.shadows.util.DataSource.toDataSource(audio.getAbsolutePath());
            org.robolectric.shadows.ShadowMediaExtractor.addTrack(videoSource,
                    android.media.MediaFormat.createVideoFormat("video/avc", 1080, 1920), new byte[]{1, 2, 3});
            assertThrows(java.io.IOException.class, () -> TrackMuxer.combine(video, audio, output));
            org.robolectric.shadows.ShadowMediaExtractor.addTrack(audioSource,
                    android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), new byte[]{4, 5, 6});
            TrackMuxer.combine(video, audio, output);
            // Robolectric's muxer writes sample payloads directly; real container playback remains a device check.
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, Files.readAllBytes(output.toPath()));
        } finally { assertTrue(video.delete()); assertTrue(audio.delete()); assertTrue(output.delete()); }
    }

    @Test public void soundIsCopiedIntoItsOwnContainerAndSilentVideoIsRejected() throws Exception {
        File media = File.createTempFile("sound-source", ".mp4"), output = File.createTempFile("sound-result", ".m4a");
        try {
            var source = org.robolectric.shadows.util.DataSource.toDataSource(media.getAbsolutePath());
            org.robolectric.shadows.ShadowMediaExtractor.addTrack(source,
                    android.media.MediaFormat.createVideoFormat("video/avc", 1080, 1920), new byte[]{1, 2, 3});
            // A video with no sound has nothing to save, and that has to say so rather than
            // leaving an empty file behind.
            assertThrows(java.io.IOException.class, () -> TrackMuxer.audioOnly(media, output));
            org.robolectric.shadows.ShadowMediaExtractor.addTrack(source,
                    android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), new byte[]{7, 8, 9});
            TrackMuxer.audioOnly(media, output);
            // Only the sound is written: the picture track stays behind.
            assertArrayEquals(new byte[]{7, 8, 9}, Files.readAllBytes(output.toPath()));
        } finally { assertTrue(media.delete()); assertTrue(output.delete()); }
    }

    @Test public void thePictureIsCopiedOnItsOwnAndAPictureIsRequired() throws Exception {
        File media = File.createTempFile("muted-source", ".mp4"), output = File.createTempFile("muted-result", ".mp4");
        try {
            var source = org.robolectric.shadows.util.DataSource.toDataSource(media.getAbsolutePath());
            org.robolectric.shadows.ShadowMediaExtractor.addTrack(source,
                    android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), new byte[]{7, 8, 9});
            // Sound and no picture is not a video, and that has to say so rather than leaving
            // an empty file behind.
            assertThrows(java.io.IOException.class, () -> TrackMuxer.videoOnly(media, output));
            org.robolectric.shadows.ShadowMediaExtractor.addTrack(source,
                    android.media.MediaFormat.createVideoFormat("video/avc", 1080, 1920), new byte[]{1, 2, 3});
            TrackMuxer.videoOnly(media, output);
            // Only the picture is written: the sound stays behind.
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(output.toPath()));
        } finally { assertTrue(media.delete()); assertTrue(output.delete()); }
    }

    @Test public void theSoundTakesTheVideoNameWithAnAudioExtension() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save("{creator}-{video_id}");
        Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("photo-{video_id}");
        // Distinct values, so swapping the two tokens fails rather than reading the same.
        Item item = new Item("dancer", "7712345");
        assertEquals("dancer-7712345.mp4", DownloadFilenameFormatter.formatSelectedVideoName(item));
        assertEquals("dancer-7712345.m4a", DownloadFilenameFormatter.formatSelectedAudioName(item));
    }

    /**
     * The saver names each image of a slideshow in turn, so the number it is given has to survive
     * the length cap. A creator name long enough to reach that cap used to take the number with
     * it and every photo of the post came out with one name.
     */
    @Test public void everySlideshowPhotoKeepsItsOwnNumberWhenTheCreatorNameIsLong() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String photoTemplate = Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get();
        try {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}_{index}");
            Item post = new Item("a".repeat(200), "7712345");

            // Past ten, so the number changing width is covered too.
            Set<String> names = new LinkedHashSet<>();
            for (int index = 1; index <= 12; index++) {
                names.add(DownloadFilenameFormatter.formatOriginalPhotoName(post, index, "jpg"));
            }

            assertEquals("Photos of one slideshow shared a name: " + names, 12, names.size());
            for (String name : names) {
                assertTrue(name, name.startsWith("aaaa") && name.endsWith(".jpg"));
            }
        } finally {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save(photoTemplate);
        }
    }

    /** An ordinary name is short enough to keep the template's own shape. */
    @Test public void anOrdinaryCreatorNameKeepsTheTemplateShape() throws IOException {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String photoTemplate = Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get();
        try {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}_{index}");
            File folder = Files.createTempDirectory("hushfeed-slideshow-short").toFile();
            Item post = new Item("dancer", "7712345");

            assertEquals("dancer_1.jpg", resolveSavedName(folder, "source_1.jpg", post));
        } finally {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save(photoTemplate);
        }
    }

    @Test public void tiktokRegistrationNumbersEveryPhotoAndResetsAfterTheBatch() throws IOException {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String photoTemplate = Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get();
        try {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}_{index}");
            File folder = Files.createTempDirectory("hushfeed-native-slideshow").toFile();
            Item post = new Item("dancer", "7712345-native", 3);

            assertEquals("dancer_1.jpg", resolveSavedName(folder, "stage-a.jpg", post));
            assertEquals("dancer_2.jpg", resolveSavedName(folder, "stage-b.jpg", post));
            assertEquals("dancer_3.jpg", resolveSavedName(folder, "stage-c.jpg", post));
            assertEquals("the next save of the same post did not restart its numbering",
                    "dancer_1.jpg", resolveSavedName(folder, "stage-d.jpg", post));
        } finally {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save(photoTemplate);
        }
    }

    /** A filename has to fit the filesystem whatever the host called the file it handed over. */
    @Test public void aLongNameAndALongExtensionStayInsideTheFilesystemLimit() throws IOException {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String videoTemplate = Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get();
        try {
            Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save("{creator}");
            File folder = Files.createTempDirectory("hushfeed-long-extension").toFile();
            // Multi-byte, so a character count that is inside the limit is not a byte count that
            // is: 200 of these are 400 bytes.
            Item post = new Item("\u00e9".repeat(200), "7712345");

            String name = resolveSavedName(folder, "source." + "x".repeat(40), post);

            assertTrue(name + " is " + name.getBytes(StandardCharsets.UTF_8).length + " bytes",
                    name.getBytes(StandardCharsets.UTF_8).length <= 255);
        } finally {
            Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.save(videoTemplate);
        }
    }

    /**
     * A creator name past the cap, through the path TikTok's own download takes.
     *
     * <p>That path fills the template beside the staging file, so it has its own trimming to do
     * and its own counter to keep. Losing that counter to the cut is what once left every photo
     * of a slideshow resolving to one name, and the tests that covered it went with the
     * collision probe they were written around.
     */
    @Test public void aTemplateWithACounterStaysInsideTheLimitFromTheRegistrationPath()
            throws IOException {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String photoTemplate = Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get();
        try {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}_{index}");
            File folder = Files.createTempDirectory("hushfeed-registration-cap").toFile();
            // Multi-byte, so 200 characters are 400 bytes and both budgets have to bite.
            Item post = new Item("\u00e9".repeat(200), "7712345");

            String name = resolveSavedName(folder, "source_1.jpg", post);

            assertTrue(name + " is " + name.getBytes(StandardCharsets.UTF_8).length + " bytes",
                    name.getBytes(StandardCharsets.UTF_8).length <= 255);
            assertTrue(name + " is " + name.length() + " characters", name.length() <= 165);
            // The counter survived the cut, which is the whole point of the bounded name.
            assertTrue("the counter was trimmed away: " + name, name.contains("_1."));
            assertTrue(name, name.startsWith("\u00e9"));
        } finally {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save(photoTemplate);
        }
    }

    /**
     * Where a taken name is actually settled, on the versions that write real files.
     *
     * <p>The formatter works out its name beside TikTok's private staging file, which is in the
     * app's own cache and tells it nothing about the folder the download lands in. Two saves
     * asking for one name is the destination's problem, and it answers by creating the file:
     * whoever loses the race gets the next number rather than a second chance at the same one.
     */
    @Test public void theDestinationIsWhatKeepsTwoDownloadsApart() throws Exception {
        File folder = Files.createTempDirectory("hushfeed-claim").toFile();

        File first = MediaFileWriter.claim(folder, "dancer.jpg");
        File second = MediaFileWriter.claim(folder, "dancer.jpg");
        File third = MediaFileWriter.claim(folder, "dancer.jpg");

        assertEquals("dancer.jpg", first.getName());
        assertEquals("dancer_2.jpg", second.getName());
        assertEquals("dancer_3.jpg", third.getName());
        assertTrue("the name was handed out without taking it", first.isFile() && second.isFile());
    }

    /** Two photos of one post reach distinct names without anything probing a folder. */
    @Test public void twoPhotosOfOnePostStillReachDistinctPublishedNames() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        String photoTemplate = Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.get();
        try {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save("{creator}_{index}");
            File folder = Files.createTempDirectory("hushfeed-two-photos").toFile();
            Item post = new Item("dancer", "7712345");

            String first = DownloadFilenameFormatter.formatOriginalPhotoName(post, 1, "jpg");
            String second = DownloadFilenameFormatter.formatOriginalPhotoName(post, 2, "jpg");
            assertNotEquals("The second photo would overwrite the first", first, second);

            // And a template with no number of its own still comes out as two files, because
            // the folder is what settles it.
            assertEquals("dancer_1.jpg", MediaFileWriter.claim(folder, "dancer_1.jpg").getName());
            assertEquals("dancer_1_2.jpg", MediaFileWriter.claim(folder, "dancer_1.jpg").getName());
        } finally {
            Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.save(photoTemplate);
        }
    }

    private static String resolveSavedName(File folder, String sourceName, Item post) throws IOException {
        File source = new File(folder, sourceName);
        Files.write(source.toPath(), new byte[]{1});
        DownloadFilenameFormatter.registerDownloadedMediaName(source.getPath(), post);
        return DownloadFilenameFormatter.consumeDestinationName(source.getName());
    }

    @Test public void soundGoesToTheAudioTreeOnlyWhereTheGalleryDemandsIt() {
        // Android 10 and later refuse an audio file in the video collection, so the folder
        // name is mirrored under Music. Older versions write real files side by side.
        int sdk = android.os.Build.VERSION.SDK_INT;
        try {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 28);
            assertEquals("Movies/TikTok", AudioDownloads.audioPath("Movies/TikTok"));
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", 29);
            assertEquals("Music/TikTok", AudioDownloads.audioPath("Movies/TikTok"));
            assertEquals("Music/TikTok/Sounds", AudioDownloads.audioPath("Download/TikTok/Sounds"));
            // A destination with no folder of its own still gets one.
            assertEquals("Music/TikTok", AudioDownloads.audioPath("Movies"));
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION.class, "SDK_INT", sdk);
        }
    }

    @Test public void failedMirrorFallsBackAndGalleryGetsExactOriginalBytes() throws Exception {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=");
        MediaTransport.Client transport = MediaTransportFixtures.publicClient(url ->
                MediaTransportFixtures.response(url,
                        url.getPath().contains("bad") ? 403 : 200,
                        url.getPath().contains("bad") ? new byte[0] : png));
        File temp = File.createTempFile("photo-test", ".tmp");
        try {
            assertEquals("png", RemoteMedia.fetch(List.of(
                    "https://[bad",
                    "https://first.tiktokcdn.com/bad",
                    "https://second.tiktokcdn.com/photo"),
                    temp, RemoteMedia.Kind.IMAGE, transport));
            assertArrayEquals(png, Files.readAllBytes(temp.toPath()));
            MediaFileWriter.publish(RuntimeEnvironment.getApplication(), temp, "source.png", "image/png", "DCIM/OriginalPhotosTest", false);
            File saved = new File(Environment.getExternalStorageDirectory(), "DCIM/OriginalPhotosTest/source.png");
            assertArrayEquals(png, Files.readAllBytes(saved.toPath()));
            assertTrue(saved.delete());
        } finally { assertTrue(temp.delete()); }
    }

    @Test public void allMalformedMediaMirrorsReturnOneRedactedFailure() throws Exception {
        File temp = File.createTempFile("media-failure", ".tmp");
        try {
            IOException failure = assertThrows(IOException.class,
                    () -> RemoteMedia.fetch(List.of("https://[bad?token=secret"), temp, RemoteMedia.Kind.IMAGE));
            assertFalse(failure.toString().contains("token=secret"));
            assertFalse(failure.toString().contains("https://[bad"));
            assertFalse("an all-invalid fetch must not leave a partial target", temp.exists());
        } finally {
            temp.delete();
        }
    }

    @Test public void theStickerFormatChoicePicksTheContainerAndFallsBackToTheSource() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        // The default is what shipped before the choice existed.
        assertTrue(StickerGallerySaver.MediaFormat.animated("mp4").convertToMp4);
        assertEquals("mp4", StickerGallerySaver.MediaFormat.animated("mp4").extension);

        var gif = StickerGallerySaver.MediaFormat.animated("gif");
        assertTrue(gif.convertToGif);
        assertFalse(gif.convertToMp4);
        assertFalse("a GIF is not a video to the gallery", gif.video);
        assertEquals("image/gif", gif.mimeType);

        // WebP is the sticker exactly as it arrived, so nothing converts it.
        var webp = StickerGallerySaver.MediaFormat.animated("webp");
        assertFalse(webp.convertToGif);
        assertFalse(webp.convertToMp4);
        assertFalse(webp.convertToPng);
        assertEquals("image/webp", webp.mimeType);
        assertEquals("webp", webp.extension);

        // An unknown or missing choice keeps the old behaviour rather than refusing to save.
        assertTrue(StickerGallerySaver.MediaFormat.animated("avif").convertToMp4);
        assertTrue(StickerGallerySaver.MediaFormat.animated(null).convertToMp4);

        // The fallback a failed conversion writes is that same untouched WebP.
        assertEquals("WebP", StickerGallerySaver.MediaFormat.webp().label);
        assertEquals("image/webp", StickerGallerySaver.MediaFormat.webp().mimeType);
    }

    @Test public void aStoryIsFetchedFromTheCleanestAddressItHas() {
        // A story has no save button, so it is saved from whatever address the video carries,
        // preferring the one without a watermark burnt into it.
        VideoData video = new VideoData(List.of());
        video.downloadNoWatermarkAddr = new Address("https://example.com/clean.mp4", 900);
        video.downloadAddr = new Address("https://example.com/watermarked.mp4", 900);
        video.playAddr = new Address("https://example.com/play.mp4", 900);
        assertEquals(List.of("https://example.com/clean.mp4"), VideoDownloads.sourceUrls(video));
        video.downloadNoWatermarkAddr = null;
        assertEquals(List.of("https://example.com/watermarked.mp4"), VideoDownloads.sourceUrls(video));
        video.downloadAddr = new Address(null, 0);
        assertEquals(List.of("https://example.com/play.mp4"), VideoDownloads.sourceUrls(video));
        video.playAddr = null;
        assertTrue(VideoDownloads.sourceUrls(video).isEmpty());
        assertTrue(VideoDownloads.sourceUrls(new Object()).isEmpty());
    }

    @Test public void eachStoryStaysWithThePlayAreaThatBoundIt() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.advancedDownloadsEnabled = true;
            Settings.SAVE_STORY.save(true);

            // The viewer binds the pages either side of the one on screen, so two play areas are
            // live at once and each has to keep its own story.
            Object showing = new Object(), neighbour = new Object();
            android.view.View onScreen = new android.view.View(activity);
            android.view.View offScreen = new android.view.View(activity);
            StoryDownloads.attachPlayArea(showing, onScreen);
            StoryDownloads.attachPlayArea(neighbour, offScreen);

            Post first = new Post(List.of(new Photo("https://example.com/one")));
            Post second = new Post(List.of(new Photo("https://example.com/two")));
            StoryDownloads.recordStory(showing, 3, first);
            StoryDownloads.recordStory(neighbour, 4, second);
            // Binding the neighbour last must not change what the story on screen is.
            assertSame(first, StoryDownloads.storyFor(onScreen));
            assertSame(second, StoryDownloads.storyFor(offScreen));

            // A bind with nothing in it leaves the story that play area is showing alone.
            StoryDownloads.recordStory(showing, 3, null);
            StoryDownloads.recordStory(null, 3, second);
            assertSame(first, StoryDownloads.storyFor(onScreen));
            // A view no play area claimed has no story, rather than somebody else's.
            assertNull(StoryDownloads.storyFor(new android.view.View(activity)));
        } finally {
            Settings.SAVE_STORY.save(false);
        }
    }

    @Test public void theStoryGestureIsOnlyTakenWhenItIsAskedFor() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.advancedDownloadsEnabled = true;

            // Off: TikTok's own press and hold is left exactly as it was.
            Settings.SAVE_STORY.save(false);
            android.view.View view = new android.view.View(activity);
            boolean[] tikTokSawIt = {false};
            view.setOnLongClickListener(anchor -> tikTokSawIt[0] = true);
            StoryDownloads.attachPlayArea(new Object(), view);
            assertTrue(view.performLongClick());
            assertTrue("TikTok's own listener still runs", tikTokSawIt[0]);

            // On: the press is ours, and with no story bound it says so rather than saving.
            Settings.SAVE_STORY.save(true);
            Object component = new Object();
            android.view.View ours = new android.view.View(activity);
            StoryDownloads.attachPlayArea(component, ours);
            org.robolectric.shadows.ShadowToast.reset();
            assertTrue(ours.performLongClick());
            assertEquals(1, org.robolectric.shadows.ShadowToast.shownToastCount());
        } finally {
            Settings.SAVE_STORY.save(false);
        }
    }

    @Test public void theProfilePictureTakesTheLargestSizeThatIsThere() {
        Account account = new Account("dancer", "Dancer");
        account.avatarThumb = new Address("https://example.com/thumb.jpg", 10);
        account.avatar168 = new Address("https://example.com/168.jpg", 20);
        account.avatarMedium = new Address("https://example.com/medium.jpg", 30);
        account.avatarLarger = new Address("https://example.com/larger.jpg", 40);
        account.avatar300 = new Address("https://example.com/300.jpg", 50);
        // Native S22 fields carry Larger=1080, Medium=720 and Avatar300=300 pixels.
        // The former expectation picked 300 first and incorrectly called that full size.
        assertEquals(List.of("https://example.com/larger.jpg"), ProfileAvatarSaver.avatarUrls(account));

        // Each size steps down only when the one above it has no usable URL.
        account.avatarLarger = new Address(null, 0);
        assertEquals(List.of("https://example.com/medium.jpg"), ProfileAvatarSaver.avatarUrls(account));
        account.avatarMedium = null;
        assertEquals(List.of("https://example.com/300.jpg"), ProfileAvatarSaver.avatarUrls(account));
        account.avatar300 = null;
        assertEquals(List.of("https://example.com/168.jpg"), ProfileAvatarSaver.avatarUrls(account));
        account.avatar168 = null;
        assertEquals(List.of("https://example.com/thumb.jpg"), ProfileAvatarSaver.avatarUrls(account));
        account.avatarThumb = null;
        assertTrue(ProfileAvatarSaver.avatarUrls(account).isEmpty());
        assertTrue(ProfileAvatarSaver.avatarUrls(new Object()).isEmpty());
    }

    @Test public void theProfilePictureIsNamedAfterTheAccount() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        assertEquals("dancer_profile.jpg", ProfileAvatarSaver.avatarName(new Account("dancer", "Dancer")));
        // No handle falls back to the display name.
        assertEquals("Dancer_profile.jpg", ProfileAvatarSaver.avatarName(new Account("  ", "Dancer")));
        // Path separators are replaced rather than dropped, so the name stays legal and unique.
        assertEquals("____profile.jpg", ProfileAvatarSaver.avatarName(new Account(null, "///")));
        // Nothing to go on at all is the only case that falls back to a fixed name.
        assertEquals("profile_profile.jpg", ProfileAvatarSaver.avatarName(new Object()));
    }

    @Test public void theRecordedProfileIsTheOneTheResponseCarries() {
        Account account = new Account("dancer", "Dancer");
        account.avatar300 = new Address("https://example.com/300.jpg", 50);
        Profile.getUserCalls = 0;
        ProfileAvatarSaver.recordProfileResponse(new Profile(account));
        assertSame(account, ProfileAvatarSaver.recordedProfileUser());
        // Reading the field, not the getter: the patch put this call inside getUser, so one
        // trip through the getter would be a stack overflow that the reflection helper eats.
        assertEquals("getUser calls", 0, Profile.getUserCalls);

        // A response with no user leaves the last profile alone rather than blanking it.
        ProfileAvatarSaver.recordProfileResponse(new Profile(null));
        ProfileAvatarSaver.recordProfileResponse(new Object());
        ProfileAvatarSaver.recordProfileResponse(null);
        assertSame(account, ProfileAvatarSaver.recordedProfileUser());
        assertEquals("getUser calls", 0, Profile.getUserCalls);
    }

    @Test public void thePictureIsRefusedWhenTheHandleOnScreenIsSomebodyElse() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            android.widget.LinearLayout header = new android.widget.LinearLayout(activity);
            android.widget.TextView handle = new android.widget.TextView(activity);
            android.view.View avatar = new android.view.View(activity);
            header.addView(handle);
            header.addView(avatar);

            Account onScreen = new Account("dancer", "Dancer");
            Account somebodyElse = new Account("singer", "Singer");
            handle.setText("@dancer");
            assertTrue(ProfileAvatarSaver.matchesProfileOnScreen(avatar, onScreen));
            assertFalse(ProfileAvatarSaver.matchesProfileOnScreen(avatar, somebodyElse));

            // A header with no handle on it cannot tell either way, so it does not refuse.
            handle.setText("Dancer");
            assertTrue(ProfileAvatarSaver.matchesProfileOnScreen(avatar, somebodyElse));
            // Neither can a profile with no handle of its own.
            handle.setText("@dancer");
            assertTrue(ProfileAvatarSaver.matchesProfileOnScreen(avatar, new Account(" ", null)));
        }
    }

    @Test public void aVeryLongCreatorNameStaysInsideWhatTheFilesystemTakes() {
        // 160 Japanese characters is 480 bytes of UTF-8, well past the 255 a filesystem takes,
        // and cutting at a fixed index can land between the halves of a surrogate pair.
        String longHandle = "\u3042".repeat(200);
        String withEmoji = "\uD83C\uDF0D".repeat(120);

        for (String handle : new String[]{longHandle, withEmoji}) {
            String name = DownloadFilenameFormatter.formatProfilePictureName(handle);
            assertTrue("the name must fit a filesystem: " + name.length() + " bytes",
                    name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255);
            for (int index = 0; index < name.length(); index++) {
                assertFalse("a surrogate was cut in half",
                        Character.isHighSurrogate(name.charAt(index))
                                && (index + 1 == name.length()
                                    || !Character.isLowSurrogate(name.charAt(index + 1))));
            }
        }

        // An ordinary handle is untouched.
        assertEquals("dancer_profile.jpg",
                DownloadFilenameFormatter.formatProfilePictureName("dancer"));
    }

    @Test public void theGestureGoesBackWhenTheSwitchDoes() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.advancedDownloadsEnabled = true;

            // In the activity's own tree: a long press with no listener left asks the parent
            // for a context menu, and a view with no parent has nothing to ask.
            android.widget.LinearLayout root = new android.widget.LinearLayout(activity);
            activity.setContentView(root);

            for (boolean story : new boolean[]{false, true}) {
                android.view.View view = new android.view.View(activity);
                root.addView(view);
                // TikTok's own gesture. Holding a story is how the app pauses it, so a switch
                // that is off must leave this exactly where it is.
                boolean[] theirsRan = {false};
                view.setOnLongClickListener(anchor -> {
                    theirsRan[0] = true;
                    return true;
                });

                Settings.SAVE_PROFILE_PICTURE.save(false);
                Settings.SAVE_STORY.save(false);
                attach(story, view);
                assertTrue("a view we never took still answers to TikTok", view.performLongClick());
                assertTrue(theirsRan[0]);

                // Switched on, the press becomes ours.
                Settings.SAVE_PROFILE_PICTURE.save(true);
                Settings.SAVE_STORY.save(true);
                attach(story, view);

                // Switched off again, the next bind hands the view back empty. Answering false
                // from a listener that is still attached would not: TikTok's own is long gone
                // by then, and only removing ours lets the app install its own again.
                Settings.SAVE_PROFILE_PICTURE.save(false);
                Settings.SAVE_STORY.save(false);
                attach(story, view);
                theirsRan[0] = false;
                assertFalse("ours was taken away rather than left answering false",
                        view.performLongClick());
                assertFalse(theirsRan[0]);
            }
        } finally {
            Settings.SAVE_PROFILE_PICTURE.save(false);
            Settings.SAVE_STORY.save(false);
        }
    }

    private static void attach(boolean story, android.view.View view) {
        if (story) StoryDownloads.attachPlayArea(new Object(), view);
        else ProfileAvatarSaver.attachAvatar(view);
    }

    @Test public void advancedPatchAloneShowsItsOwnOptions() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.advancedDownloadsEnabled = true;
            Settings.DOWNLOAD_VIDEO_QUALITY.save("auto");
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new DownloadsPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("download_video_quality"));
            assertNotNull(screen.findPreference("download_original_photos"));
            assertNotNull(screen.findPreference("download_audio_track"));
            assertNull(screen.findPreference("down_watermark"));
            assertEquals(Settings.DOWNLOAD_PHOTO_PATH.get(), screen.findPreference("download_photo_path").getSummary());
            activity.setPreferenceScreen(screen);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            app.morphe.extension.tiktok.UiCapture.save(activity.getWindow().getDecorView(), "download-settings.png");
        }
    }
}
