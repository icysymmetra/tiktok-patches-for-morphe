/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.UiCapture;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReleaseNotesTest {
    public static class HostActivity extends Activity {}
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void generatedNotesLargerThanOneConstantStillRenderEveryReleaseInOrder() throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir"));
        while (!Files.isRegularFile(cursor.resolve("CHANGELOG.md"))) cursor = cursor.getParent();
        Path root = temporary.getRoot().toPath();
        String notes = "## 0.67.0 (date)\n\n* **TikTok:** Newest starts. " + "content 🚀 \000 ".repeat(7_000)
                + "\n\n* **TikTok:** Newest ends.\n\n## 0.60.0 (date)\n\n* **TikTok:** Oldest kept.\n";
        assertTrue(notes.getBytes(StandardCharsets.UTF_8).length > 65_535);
        Path changelog = root.resolve("CHANGELOG.md");
        Files.write(changelog, ("## Unreleased\nDraft stays out.\n\n" + notes).getBytes(StandardCharsets.UTF_8));
        Path summaries = root.resolve("notes/en.txt");
        Files.createDirectories(summaries.getParent());
        Files.write(summaries, notes.getBytes(StandardCharsets.UTF_8));
        Path source = root.resolve("ReleaseNotesData.java");
        String generate = "import importlib.util,sys; from pathlib import Path; "
                + "s=importlib.util.spec_from_file_location('notes',sys.argv[1]); "
                + "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                + "m.CHANGELOG=Path(sys.argv[2]); m.OUTPUT=Path(sys.argv[3]); "
                + "m.NOTES=Path(sys.argv[3]).parent / 'notes'; m.main()";
        Process generator = new ProcessBuilder("python", "-c", generate,
                cursor.resolve("tools/gen-release-notes.py").toString(), changelog.toString(), source.toString())
                .redirectErrorStream(true).start();
        String generated = new String(generator.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(generated, 0, generator.waitFor());
        Process compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                "-encoding", "UTF-8", "-d", root.toString(), source.toString()).redirectErrorStream(true).start();
        String diagnostics = new String(compiler.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(diagnostics, 0, compiler.waitFor());

        String name = ReleaseNotes.class.getName();
        Path consumer = root.resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(consumer.getParent());
        try (var input = ReleaseNotes.class.getResourceAsStream("ReleaseNotes.class")) {
            Files.copy(input, consumer);
        }
        // Its nested class has to come from the same loader, or the two sit in different
        // runtime packages and can't reach each other's package-private members.
        try (var input = ReleaseNotes.class.getResourceAsStream("ReleaseNotes$Section.class")) {
            Files.copy(input, consumer.resolveSibling("ReleaseNotes$Section.class"));
        }
        try (var loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String target, boolean resolve) throws ClassNotFoundException {
                if (!target.equals(name) && !target.equals(name + "Data") && !target.equals(name + "$Section")) {
                    return super.loadClass(target, resolve);
                }
                synchronized (this) {
                    Class<?> loaded = findLoadedClass(target);
                    if (loaded == null) loaded = findClass(target);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }; var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);
            activity.getSharedPreferences(ReleaseNotes.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("dismissed_version", "0.59.0").commit();
            var show = loader.loadClass(name).getDeclaredMethod("show", Context.class, String.class, Runnable.class);
            show.setAccessible(true);
            show.invoke(null, activity, "0.67.0", (Runnable) () -> {});
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            ViewGroup custom = dialog.findViewById(android.R.id.custom);
            ScrollView scroller = (ScrollView) custom.getChildAt(0);
            // The notes sit last in the dialog's column, under the English label when one shows.
            ViewGroup content = (ViewGroup) scroller.getChildAt(0);
            TextView body = (TextView) content.getChildAt(content.getChildCount() - 1);
            assertEquals(ReleaseNotes.text(notes, "0.67.0", "0.59.0"), body.getText().toString());
            assertTrue(body.getText().toString().endsWith("Oldest kept."));
            assertFalse(body.getText().toString().contains("Draft stays out."));
            dialog.dismiss();
        }
    }

    @Test
    public void bundledTextMatchesCuratedPublishedSummariesAndLeavesDraftsOut() throws Exception {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null && !Files.exists(cursor.resolve("CHANGELOG.md"))) {
            cursor = cursor.getParent();
        }
        assertTrue("repository changelog missing", cursor != null);
        String summaries = new String(Files.readAllBytes(cursor.resolve("extensions/tiktok/src/main/l10n/notes/en.txt")),
                StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        java.util.regex.Matcher heading = java.util.regex.Pattern.compile("(?m)^## \\d+\\.\\d+\\.\\d+ ").matcher(summaries);
        assertTrue("release headings missing", heading.find());
        assertEquals("run tools/gen-release-notes.py after summary edits",
                summaries.substring(heading.start()).trim() + "\n", ReleaseNotesData.TEXT);
        assertFalse(ReleaseNotesData.TEXT.contains("## Unreleased"));
    }

    @Test
    public void aBuildAheadOfAReleaseSortsBelowIt() {
        // Read as the release, Got it on 0.61.0-dev.2 dismissed 0.61.0's own notes before
        // they were published.
        String releases = "## 0.61.0 (date)\nNewer.\n\n## 0.60.0 (date)\nOld.\n";
        assertTrue(ReleaseNotes.text(releases, "0.61.0", "0.61.0-dev.2").contains("Newer."));
        assertEquals("", ReleaseNotes.text(releases, "0.61.0-dev.2", "0.61.0"));
    }

    @Test
    public void theRowNamesTheNewestVersionItShows() {
        // A build with no section of its own shows the releases before it, and the row said
        // "Changes in Hushfeed" with a version the notes never mention.
        String releases = "## 0.60.0 (date)\nOld.\n\n## 0.59.0 (date)\nOlder.\n";
        String shown = ReleaseNotes.text(releases, "0.60.1", "0.59.0");
        assertTrue(shown, shown.contains("Old."));
        assertEquals("0.60.0", ReleaseNotes.newestShown(shown));
        assertEquals(null, ReleaseNotes.newestShown(""));
    }

    @Test
    public void skippedVersionsAppearAndDismissedVersionsStayGone() {
        String releases = "## 0.1000.1000001 (date)\nNewest.\n\n"
                + "## 0.1000.1000000 (date)\nMiddle.\n\n"
                + "## 0.60.0 (date)\nOld.\n";
        String shown = ReleaseNotes.text(releases, "0.1000.1000001", "0.60.0");
        assertTrue(shown.contains("Newest."));
        assertTrue(shown.contains("Middle."));
        assertFalse(shown.contains("Old."));
        assertEquals("", ReleaseNotes.text(releases, "0.1000.1000001", "0.1000.1000001"));
        assertEquals("", ReleaseNotes.text(releases, "0.60.0", "0.1000.1000001"));
        assertTrue(ReleaseNotes.text(releases, "0.1000.1000001", null).contains("Newest."));
        assertFalse(ReleaseNotes.text(releases, "0.1000.1000001", null).contains("Middle."));
    }

    /** What's new draws a sparkle of its own; it used to borrow the Feature Gate Lab's flask. */
    @Test
    public void theWhatsNewRowHasAnIconOfItsOwn() throws Exception {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            int size = SettingsUi.dp(activity, 96);
            int[] news = pixels(SettingsMenuPreference.iconDrawable(activity, SettingsMenuPreference.Icon.NEWS), size);
            int[] lab = pixels(SettingsMenuPreference.iconDrawable(activity, SettingsMenuPreference.Icon.LAB), size);
            assertFalse("the What's new icon draws the Lab's flask", java.util.Arrays.equals(news, lab));
            assertTrue("the What's new icon draws nothing",
                    java.util.Arrays.stream(news).anyMatch(pixel -> android.graphics.Color.alpha(pixel) != 0));

            android.widget.ImageView icon = new android.widget.ImageView(activity);
            icon.setImageDrawable(SettingsMenuPreference.iconDrawable(activity, SettingsMenuPreference.Icon.NEWS));
            icon.setBackgroundColor(SettingsUi.background());
            app.morphe.extension.tiktok.UiCapture.save(icon, "whats-new-icon.png", size, size);
        }
    }

    private static int[] pixels(android.graphics.drawable.Drawable drawable, int size) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                size, size, android.graphics.Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, size, size);
        drawable.draw(new android.graphics.Canvas(bitmap));
        int[] out = new int[size * size];
        bitmap.getPixels(out, 0, size, 0, 0, size, size);
        return out;
    }

    /** The changelog's scope label is for Morphe Manager; the dialog shows a plain bullet. */
    @Test
    public void bulletsDropTheChangelogScopeLabel() {
        String shown = ReleaseNotes.text("## 0.60.0 (date)\n\n* **TikTok:** Comments send again.\n", "0.60.0", null);
        assertEquals("Metra 0.60.0 (date)\n\n• Comments send again.", shown);
    }

    /** A setting name in backticks reads as code on GitHub; the dialog showed the backticks. */
    @Test
    public void settingNamesLoseTheirBackticks() {
        String shown = ReleaseNotes.text(
                "## 0.60.0 (date)\n\n* **TikTok:** Turn `Hide series` back on.\n", "0.60.0", null);
        assertEquals("Metra 0.60.0 (date)\n\n• Turn Hide series back on.", shown);
    }

    /**
     * #91: the title and buttons follow the phone's language and the notes are the English
     * changelog, so an Azerbaijani or Turkish reader is told the notes are English first.
     */
    @Test @Config(manifest = Config.NONE, sdk = 28, qualifiers = "az")
    public void aTranslatedPhoneIsToldTheNotesAreEnglish() {
        assertEnglishLabel("Bu qeydlər ingilis dilindədir.");
    }

    @Test @Config(manifest = Config.NONE, sdk = 28, qualifiers = "tr")
    public void aTurkishPhoneIsToldTheNotesAreEnglish() {
        assertEnglishLabel("Bu notlar İngilizcedir.");
    }

    /** English, and a language with no table, read English everywhere: no label needed. */
    @Test
    public void anEnglishPhoneGetsTheNotesAlone() {
        assertEquals(1, dialogTexts().size());
    }

    @Test @Config(manifest = Config.NONE, sdk = 28, qualifiers = "ja")
    public void aPhoneWithNoTableGetsTheNotesAlone() {
        assertEquals(1, dialogTexts().size());
    }

    private static void assertEnglishLabel(String label) {
        java.util.List<android.widget.TextView> texts = dialogTexts();
        assertEquals("expected the label, then the notes", 2, texts.size());
        assertEquals(label, texts.get(0).getText().toString());
        android.widget.TextView body = texts.get(1);
        assertTrue("the notes are missing: " + body.getText(),
                body.getText().toString().startsWith("Metra 0.1.1"));
        android.text.Spanned notes = (android.text.Spanned) body.getText();
        android.text.style.LocaleSpan[] spans = notes.getSpans(0, notes.length(), android.text.style.LocaleSpan.class);
        assertEquals("the notes are not marked as English for a screen reader", 1, spans.length);
        assertEquals(java.util.Locale.ENGLISH, spans[0].getLocale());
        assertEquals(0, notes.getSpanStart(spans[0]));
        assertEquals(notes.length(), notes.getSpanEnd(spans[0]));
    }

    /** The text views in the What's new dialog's body, top to bottom. */
    private static java.util.List<android.widget.TextView> dialogTexts() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            ReleaseNotes.show(activity, "0.1.1", () -> {});
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            android.view.ViewGroup content = (android.view.ViewGroup)
                    ((android.view.ViewGroup) org.robolectric.Shadows.shadowOf(dialog).getView()).getChildAt(0);
            java.util.List<android.widget.TextView> texts = new java.util.ArrayList<>();
            for (int index = 0; index < content.getChildCount(); index++) {
                texts.add((android.widget.TextView) content.getChildAt(index));
            }
            dialog.dismiss();
            return texts;
        }
    }

    @Test
    public void laterKeepsTheRowPendingAndGotItRemembersTheVersion() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);
            assertTrue(ReleaseNotes.pending(activity, "0.1.1"));

            ReleaseNotes.show(activity, "0.1.1", () -> {});
            AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Later", first.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            first.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(ReleaseNotes.pending(activity, "0.1.1"));

            AtomicBoolean rowRemoved = new AtomicBoolean();
            ReleaseNotes.show(activity, "0.1.1", () -> rowRemoved.set(true));
            AlertDialog second = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Got it", second.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            second.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(rowRemoved.get());
            assertFalse(ReleaseNotes.pending(activity, "0.1.1"));
            assertFalse(ReleaseNotes.pending(activity, "0.1.0"));
        }
    }

    @Test @Config(manifest = Config.NONE, sdk = 28, qualifiers = "de")
    public void fullChangelogOpensTheDetailsWithoutDismissingTheUpdate() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);
            activity.getSharedPreferences(ReleaseNotes.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
            AtomicBoolean rowRemoved = new AtomicBoolean();
            ReleaseNotes.show(activity, "0.8.0", () -> rowRemoved.set(true));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("Alle Änderungen",
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Intent opened = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
                    .getNextStartedActivity();
            assertNotNull(opened);
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals("https://github.com/icysymmetra/tiktok-patches-for-morphe/blob/main/CHANGELOG.md", opened.getDataString());
            assertTrue(ReleaseNotes.pending(activity, "0.8.0"));
            assertFalse(rowRemoved.get());
            assertEquals(null, activity.getSharedPreferences(ReleaseNotes.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("dismissed_version", null));
        }
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-night-mdpi")
    public void actualDialogCapturesEnglishDarkWithReadableActionsAndScrolling() throws Exception {
        captureDialog("dialogs/dark/whats-new.png", true);
    }

    @Test @Config(qualifiers = "en-w390dp-h844dp-notnight-mdpi")
    public void actualDialogCapturesEnglishLightWithReadableActionsAndScrolling() throws Exception {
        captureDialog("dialogs/light/whats-new.png", false);
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi")
    public void actualDialogCapturesNarrowGermanWithReadableActionsAndScrolling() throws Exception {
        captureDialog("dialogs/dark/whats-new-german-narrow.png", true);
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-night-mdpi", fontScale = 2)
    public void actualDialogCapturesLargeGermanWithReadableActionsAndScrolling() throws Exception {
        captureDialog("dialogs/dark/whats-new-german-large.png", true);
    }

    /** Capture the latest published Metra release, even when the local build is a prerelease. */
    private static String sourceVersion() throws Exception {
        java.util.regex.Matcher version = java.util.regex.Pattern.compile("(?m)^## (\\d+\\.\\d+\\.\\d+) ")
                .matcher(ReleaseNotesData.TEXT);
        assertTrue("Metra release history has no published version", version.find());
        return version.group(1);
    }

    private static void captureDialog(String name, boolean dark) throws Exception {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);
            Utils.setIsDarkModeEnabled(dark);
            activity.getSharedPreferences(ReleaseNotes.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("dismissed_version", "0.1.0").commit();
            ReleaseNotes.show(activity, sourceVersion(), () -> {});
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            View decor = dialog.getWindow().getDecorView();
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.AT_MOST));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
            java.util.List<Rect> placed = new java.util.ArrayList<>();
            for (int which : new int[]{AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_POSITIVE}) {
                Button button = dialog.getButton(which);
                assertEquals(View.VISIBLE, button.getVisibility());
                int inset = app.morphe.extension.tiktok.settings.preference.SettingsUi.dp(activity, 8);
                assertTrue("adjacent translated labels need horizontal inset",
                        button.getCompoundPaddingLeft() >= inset && button.getCompoundPaddingRight() >= inset);
                Rect bounds = new Rect();
                button.getDrawingRect(bounds);
                ((ViewGroup) decor).offsetDescendantRectToMyCoords(button, bounds);
                assertTrue(button.getText() + " is clipped", bounds.left >= 0 && bounds.top >= 0
                        && bounds.right <= decor.getWidth() && bounds.bottom <= decor.getHeight());
                for (Rect previous : placed) assertFalse("dialog actions overlap", Rect.intersects(previous, bounds));
                placed.add(bounds);
                android.text.Layout text = button.getLayout();
                assertNotNull(text);
                for (int line = 0; line < text.getLineCount(); line++) {
                    assertEquals("button text was shortened", 0, text.getEllipsisCount(line));
                    assertTrue("button text runs past its edge", text.getLineMax(line)
                            <= button.getWidth() - button.getCompoundPaddingLeft() - button.getCompoundPaddingRight() + 1);
                }
                assertTrue("button text is cut vertically", text.getHeight()
                        <= button.getHeight() - button.getCompoundPaddingTop() - button.getCompoundPaddingBottom());
            }
            ViewGroup custom = dialog.findViewById(android.R.id.custom);
            ScrollView scroller = (ScrollView) custom.getChildAt(0);
            assertTrue("release history must scroll", scroller.canScrollVertically(1));
            UiCapture.save(decor, name, decor.getWidth(), decor.getHeight());
            scroller.scrollTo(0, scroller.getChildAt(0).getHeight());
            assertTrue("older releases must remain reachable", scroller.getScrollY() > 0
                    && scroller.canScrollVertically(-1));
            UiCapture.save(decor, name.replace(".png", "-end.png"), decor.getWidth(), decor.getHeight());
            dialog.dismiss();
        }
    }

    /** #91: a release translated for the phone's table shows in that language, the rest in English. */
    @Test
    public void aTranslatedReleaseReplacesItsEnglishAndOlderOnesStayEnglish() {
        String english = "## 0.2.0 (date)\n\n* **TikTok:** Newer.\n\n## 0.1.1 (date)\n\n* **TikTok:** Older.\n";
        String german = "## 0.2.0 (date)\n\n* **TikTok:** Neuer.\n";
        java.util.List<ReleaseNotes.Section> shown = ReleaseNotes.sections(english, german, "0.2.0", "0.1.0");
        assertEquals(2, shown.size());
        assertEquals("Metra 0.2.0 (date)\n\n• Neuer.\n", shown.get(0).text);
        assertFalse(shown.get(0).english);
        assertTrue(shown.get(1).text, shown.get(1).text.startsWith("Metra 0.1.1 (date)\n\n• Older."));
        assertTrue(shown.get(1).english);
        // The English text the row and the pending check read doesn't change.
        assertTrue(ReleaseNotes.text(english, "0.2.0", "0.1.0").contains("Newer."));
    }

    /** Every table carries the same releases, so no language is a release ahead of another. */
    @Test
    public void everyTableTranslatesTheSameReleases() {
        java.util.regex.Pattern heading = java.util.regex.Pattern.compile("(?m)^## (\\d+\\.\\d+\\.\\d+) ");
        java.util.Set<String> expected = null;
        for (String tag : new String[]{"az", "de", "es", "in", "it", "pt-rbr", "ru", "tr"}) {
            java.util.Set<String> versions = new java.util.TreeSet<>();
            String notes = ReleaseNotesData.translated(tag);
            if (notes != null) {
                java.util.regex.Matcher match = heading.matcher(notes);
                while (match.find()) versions.add(match.group(1));
            }
            if (expected == null) expected = versions;
            assertEquals(tag + " translates different releases", expected, versions);
        }
        assertTrue("Upstream translations must not be labelled as Metra releases", expected.isEmpty());
        assertEquals(ReleaseNotesData.translated("in"), ReleaseNotesData.translated("id"));
        assertEquals(null, ReleaseNotesData.translated("fr"));
        assertEquals(null, ReleaseNotesData.translated(null));
    }

    @Test @Config(manifest = Config.NONE, sdk = 28, qualifiers = "de")
    public void aGermanPhoneLabelsPublishedMetraNotesAsEnglish() {
        java.util.List<TextView> texts = dialogTexts("0.8.0", null);
        assertEquals("expected the label, then the Metra notes", 2, texts.size());
        assertEquals("Diese Hinweise sind auf Englisch.", texts.get(0).getText().toString());
        android.text.Spanned notes = (android.text.Spanned) texts.get(1).getText();
        assertTrue(notes.toString(), notes.toString().startsWith("Metra 0.8.0"));
        assertTrue("Metra notes must use their actual English history",
                notes.toString().equals(ReleaseNotes.text("0.8.0", null)));
        android.text.style.LocaleSpan[] spans = notes.getSpans(0, notes.length(), android.text.style.LocaleSpan.class);
        assertEquals(1, spans.length);
        assertEquals(java.util.Locale.ENGLISH, spans[0].getLocale());
        assertEquals(notes.length(), notes.getSpanEnd(spans[0]));
    }

    @Test @Config(manifest = Config.NONE, sdk = 28, qualifiers = "de")
    public void olderMetraReleasesRemainReachableAndMarkedAsEnglish() {
        java.util.List<TextView> texts = dialogTexts("0.8.0", "0.6.0");
        assertEquals("expected the label, then the notes", 2, texts.size());
        assertEquals("Diese Hinweise sind auf Englisch.", texts.get(0).getText().toString());
        android.text.Spanned notes = (android.text.Spanned) texts.get(1).getText();
        assertTrue(notes.toString().contains("Metra 0.7.0"));
        android.text.style.LocaleSpan[] spans = notes.getSpans(0, notes.length(), android.text.style.LocaleSpan.class);
        assertEquals("one English run covers Metra notes", 1, spans.length);
        java.util.Arrays.sort(spans, java.util.Comparator.comparingInt(notes::getSpanStart));
        assertEquals(java.util.Locale.ENGLISH, spans[0].getLocale());
        assertEquals(0, notes.getSpanStart(spans[0]));
        assertEquals(notes.length(), notes.getSpanEnd(spans[0]));
    }

    /** The What's new dialog's text views for {@code current}, after {@code dismissed} was. */
    private static java.util.List<TextView> dialogTexts(String current, String dismissed) {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            activity.getSharedPreferences(ReleaseNotes.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("dismissed_version", dismissed).commit();
            ReleaseNotes.show(activity, current, () -> {});
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            ViewGroup content = (ViewGroup)
                    ((ViewGroup) org.robolectric.Shadows.shadowOf(dialog).getView()).getChildAt(0);
            java.util.List<TextView> texts = new java.util.ArrayList<>();
            for (int index = 0; index < content.getChildCount(); index++) {
                texts.add((TextView) content.getChildAt(index));
            }
            dialog.dismiss();
            return texts;
        }
    }
}
