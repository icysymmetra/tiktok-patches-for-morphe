package app.morphe.extension.tiktok.download;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * TikTok's StickerItem as the saver reads it. The member names are TikTok's own, which R8
 * leaves alone; StickerSourceFixturesTest holds each declared build to them.
 */
public final class StickerItemFake {
    public final Base stickerBase;
    /** The variant a sender picked, which TikTok's currentImage() prefers. */
    public Image variant;

    StickerItemFake(Base stickerBase) {
        this.stickerBase = stickerBase;
    }

    /** A sticker of this type whose image has these addresses. */
    static StickerItemFake of(Integer stickerType, String imageType, String... urls) {
        return new StickerItemFake(new Base(stickerType, new Image(imageType, urls), null));
    }

    /** TikTok's currentImage(): the picked variant's image, else the sticker's own. */
    public Image currentImage() {
        if (variant != null) return variant;
        return stickerBase == null ? null : stickerBase.image;
    }

    public static final class Base {
        public final Integer stickerType;
        public final Image image;
        public final Image thumbnail;

        Base(Integer stickerType, Image image, Image thumbnail) {
            this.stickerType = stickerType;
            this.image = image;
            this.thumbnail = thumbnail;
        }
    }

    public static final class Image {
        public final List<String> urlList;
        public final String imageType;

        Image(String imageType, String... urls) {
            this.imageType = imageType;
            this.urlList = new CountingList(Arrays.asList(urls));
        }

        /** How many times the saver walked this image's addresses. */
        int walks() {
            return ((CountingList) urlList).walks;
        }
    }

    private static final class CountingList extends AbstractList<String> {
        private final List<String> items;
        int walks;

        CountingList(List<String> items) {
            this.items = items;
        }

        @Override public Iterator<String> iterator() {
            walks++;
            return super.iterator();
        }

        @Override public String get(int index) {
            return items.get(index);
        }

        @Override public int size() {
            return items.size();
        }
    }
}
