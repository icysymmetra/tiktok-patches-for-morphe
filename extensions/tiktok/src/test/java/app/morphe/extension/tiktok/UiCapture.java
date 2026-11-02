package app.morphe.extension.tiktok;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;

/** Renders test-owned Android views without using the desktop display. */
public final class UiCapture {
    private UiCapture() {}
    public static void save(View view, String name) throws Exception {
        save(view, name, 480, 960);
    }
    public static void save(View view, String name, int width, int height) throws Exception {
        String directory = System.getProperty("morphe.screenshotDir");
        if (directory == null) return;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        requireSomethingDrawn(bitmap, name);
        File output = new File(directory, name);
        if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs()) {
            throw new java.io.IOException("Cannot create screenshot directory");
        }
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new java.io.IOException("Cannot encode screenshot");
            }
        }
        bitmap.recycle();
    }

    /**
     * Refuses a capture with nothing in it.
     *
     * <p>A test class that forgets {@code @GraphicsMode(NATIVE)} still draws, in the sense that
     * nothing throws: the draw is simply a no-op and the bitmap keeps the zeroes it was created
     * with. The published file is then a black rectangle that documents the feature as blank and
     * can never catch a visual regression, and nothing noticed, because the refresh task only
     * compares bytes. session-reminder.png shipped that way.
     *
     * <p>Eight colours rather than two, and counted rather than sampled. Measured over the 49
     * tracked captures on 2026-09-15: the blank one had exactly 1, and the next lowest, an 80 by
     * 80 control, had 40. Anything a person would call a screenshot is far above this.
     */
    private static void requireSomethingDrawn(Bitmap bitmap, String name) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("The capture for " + name + " has no area: "
                    + width + "x" + height);
        }
        java.util.Set<Integer> colours = new java.util.HashSet<>();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int pixel : row) {
                // Opacity out of it: a view drawn on a transparent bitmap is still drawn, and
                // the thing worth refusing is a picture with no shapes in it.
                colours.add(pixel & 0x00FFFFFF);
                if (colours.size() >= MINIMUM_COLOURS) return;
            }
        }
        throw new IllegalStateException("The capture for " + name + " has only " + colours.size()
                + " colour" + (colours.size() == 1 ? "" : "s") + " in it, so nothing was drawn. "
                + "A test that captures a view needs @GraphicsMode(GraphicsMode.Mode.NATIVE); "
                + "without it the draw is a no-op and the bitmap keeps the zeroes it was made "
                + "with.");
    }

    private static final int MINIMUM_COLOURS = 8;
}
