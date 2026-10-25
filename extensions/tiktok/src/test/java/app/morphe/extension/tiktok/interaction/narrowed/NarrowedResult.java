package app.morphe.extension.tiktok.interaction.narrowed;

/**
 * A fit result whose copy is not public, in a package of its own. VideoFit is outside this
 * package the way it is outside TikTok's, so the copy is only callable once it has been made
 * accessible, which is the half of the lookup a stand-in in VideoFit's own package cannot show.
 */
public final class NarrowedResult {
    private final int width;
    private final int height;

    public NarrowedResult(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Object getResultOperator() {
        return null;
    }

    NarrowedResult copy(int width, int height, Float translateX, Float translateY, Object operator) {
        return new NarrowedResult(width, height);
    }
}
