/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * The backdrop every control drawn over a video shares, and the radius scale behind it.
 *
 * <p>Five places built this pair of colours by hand and then rounded it three different ways, so
 * the four feed controls, the budget cue and the hold's release control read as three separate
 * add-ons sitting on the same video. These hold the one helper to one answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class OverlayChipTest {

    private Activity activity() {
        return Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test public void theChipIsARectangleAtTheRadiusItWasAskedFor() {
        Activity activity = activity();
        GradientDrawable chip = SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY);

        assertEquals("the overlay backdrop is not a rectangle",
                GradientDrawable.RECTANGLE, chip.getShape());
        assertEquals("the overlay backdrop is not drawn at the radius it was given",
                (float) SettingsUi.dp(activity, SettingsUi.RADIUS_OVERLAY),
                chip.getCornerRadius(), 0.5f);
    }

    @Test public void theChipIsTheSameWhicheverThemeTheSettingsScreenIsIn() {
        Activity activity = activity();

        app.morphe.extension.shared.Utils.setIsDarkModeEnabled(true);
        GradientDrawable dark = SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY);
        app.morphe.extension.shared.Utils.setIsDarkModeEnabled(false);
        GradientDrawable light = SettingsUi.overlayChip(activity, SettingsUi.RADIUS_OVERLAY);

        // A video is dark whatever the phone's theme says, and away from the settings screen the
        // shared theme flag answers for the system rather than for the feed. Both callers relied
        // on that before, each with its own copy of the two colours.
        assertEquals("the overlay backdrop followed the settings theme",
                dark.getCornerRadius(), light.getCornerRadius(), 0f);
        assertEquals("the overlay backdrop followed the settings theme",
                dark.getShape(), light.getShape());
    }

    /**
     * The shared overlay scale has no pill.
     *
     * <p>Native settings panels and tabs use their own larger radii. Read the original shared
     * steps by name so those separate settings tokens do not change the video overlay contract.
     */
    @Test public void everyRadiusOnTheScaleIsAStepAndNoneOfThemIsAPill() throws Exception {
        List<Integer> scale = new ArrayList<>();
        String[] overlaySteps = {"RADIUS_SQUARE", "RADIUS_BADGE", "RADIUS_CONTROL",
                "RADIUS_FIELD", "RADIUS_CARD", "RADIUS_OVERLAY"};
        for (String step : overlaySteps) {
            Field field = SettingsUi.class.getDeclaredField(step);
            assertTrue(field.getName() + " is not a constant", Modifier.isStatic(field.getModifiers()));
            field.setAccessible(true);
            scale.add(field.getInt(null));
        }

        assertEquals("the radius scale lost a step", 6, scale.size());
        for (int radius : scale) {
            assertTrue("a radius on the scale is negative: " + radius, radius >= 0);
            // The shared overlay scale deliberately stays below 16dp. The larger settings
            // panel and pill radii belong to controls outside the video overlay.
            assertTrue("a radius on the scale is large enough to make a pill: " + radius,
                    radius < 16);
        }
        assertTrue("the scale has no square step", scale.contains(SettingsUi.RADIUS_SQUARE));
        assertNotEquals("the overlay radius collapsed onto the square step",
                SettingsUi.RADIUS_SQUARE, SettingsUi.RADIUS_OVERLAY);
    }

    /** The mutation control: a value off the scale has to be able to fail the check above. */
    @Test public void theScaleCheckCanActuallyFail() {
        int pill = 24;
        assertTrue("a 24dp radius on a 48dp control is a pill and the check has to say so",
                pill >= 16);
    }

    /**
     * No source in the runtime tree rounds a corner with a number of its own.
     *
     * <p>Cleaning the radii up once is worth nothing if the next control written picks its own
     * number, which is how the bundle ended up with 5, 10, 12, 24 and 1000 in the first place.
     * A radius has to come from the scale, so this reads the source and says so.
     */
    @Test public void everyCornerRadiusComesFromTheScale() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) {
            root = new java.io.File("extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        }
        assertTrue("could not find the source tree from "
                + new java.io.File(".").getAbsolutePath(), root.isDirectory());

        // setCornerRadius takes pixels, so every caller converts through dp() or density first.
        // A bare number is the thing being looked for: setCornerRadius(24), setCornerRadius(1000).
        java.util.regex.Pattern bare = java.util.regex.Pattern.compile(
                "setCornerRadius\\s*\\(\\s*[0-9]");
        java.util.List<String> offenders = new ArrayList<>();
        int scanned = 0;
        java.nio.file.Path base = root.toPath();
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(base)) {
            for (java.nio.file.Path file : files
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toList())) {
                String text = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                scanned++;
                java.util.regex.Matcher matcher = bare.matcher(text);
                while (matcher.find()) {
                    offenders.add(base.relativize(file).toString());
                }
            }
        }

        assertTrue("the scan found no files to read", scanned > 20);
        assertEquals("a corner radius written as a bare number rather than taken from the scale "
                + "in SettingsUi: " + offenders, 0, offenders.size());
    }

    @Test public void noOverlayClassHandRollsColourLiterals() throws Exception {
        java.io.File root = new java.io.File("src/main/java/app/morphe/extension/tiktok");
        if (!root.isDirectory()) {
            root = new java.io.File("extensions/tiktok/src/main/java/app/morphe/extension/tiktok");
        }
        assertTrue(root.isDirectory());
        String[] overlayFiles = {
                "blockauthor/BlockAuthorOverlay.java",
                "wellbeing/SessionLockOverlay.java"
        };
        List<String> offenders = new ArrayList<>();
        for (String name : overlayFiles) {
            java.io.File file = new java.io.File(root, name);
            // Not a skip. A renamed or moved overlay class would leave this gate passing
            // against nothing at all, which is how the radius scan earned its own file count.
            assertTrue(name + " is not where the scan expects it, so the scan guards nothing",
                    file.exists());
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (OVERLAY_COLOUR_LITERAL.matcher(text).find()) offenders.add(name);
        }
        assertEquals("overlay classes still hand-roll colour literals instead of using tokens: "
                + offenders, 0, offenders.size());
    }

    private static final java.util.regex.Pattern OVERLAY_COLOUR_LITERAL =
            java.util.regex.Pattern.compile("Color\\s*\\.\\s*(argb|WHITE)");

    /** The mutation control: the colour pattern has to catch what the overlays used to hold. */
    @Test public void theColourScanCanActuallyFail() {
        assertTrue("the scan would not have caught the hold panel's own black",
                OVERLAY_COLOUR_LITERAL.matcher(
                        "panel.setBackgroundColor(Color.argb(238, 0, 0, 0));").find());
        assertTrue("the scan would not have caught a bare white title",
                OVERLAY_COLOUR_LITERAL.matcher("title.setTextColor(Color.WHITE);").find());
        assertFalse("the scan flags a colour that does come from the tokens",
                OVERLAY_COLOUR_LITERAL.matcher(
                        "title.setTextColor(SettingsUi.OVERLAY_TEXT);").find());
    }

    /** The mutation control for the scan: the pattern has to catch what it is looking for. */
    @Test public void theSourceScanCanActuallyFail() {
        java.util.regex.Pattern bare = java.util.regex.Pattern.compile(
                "setCornerRadius\\s*\\(\\s*[0-9]");
        assertTrue("the scan would not have caught the 1000 that was in TapConfirmation",
                bare.matcher("ring.setCornerRadius(1000);").find());
        assertTrue("the scan would not have caught a bare 24",
                bare.matcher("pill.setCornerRadius( 24 );").find());
        assertFalse("the scan flags a radius that does come from the scale",
                bare.matcher("chip.setCornerRadius(dp(context, RADIUS_OVERLAY));").find());
    }
}
