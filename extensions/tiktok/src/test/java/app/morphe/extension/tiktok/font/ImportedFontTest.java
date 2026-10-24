/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Use an imported font: a font file the reader picks is copied into TikTok's files, checked,
 * and drawn in by Use system font's hook. It wins over the system font while its switch is on,
 * and with the switch off or the file gone the hook answers exactly as Use system font alone.
 *
 * <p>Robolectric's legacy typefaces don't read the file, so a face made from a file carries the
 * file's name as its family, which is how these tell the imported font from the others. The
 * signature check runs on the bytes either way.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ImportedFontTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final byte[] TRUETYPE = {0, 1, 0, 0, 0, 12, 0, (byte) 0x80};
    private static final byte[] OPENTYPE = {'O', 'T', 'T', 'O', 0, 9, 0, 0x40};

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        HookStatus.clear();
        ImportedFont.moves = ImportedFont.FILES;
        ImportedFont.remove(context);
    }

    @After public void tearDown() {
        ImportedFont.moves = ImportedFont.FILES;
        Utils.setContext(RuntimeEnvironment.getApplication());
        ImportedFont.remove(RuntimeEnvironment.getApplication());
        Settings.IMPORTED_FONT.resetToDefault();
        Settings.SYSTEM_FONT.resetToDefault();
        SettingsStatus.systemFontEnabled = false;
        HookStatus.clear();
    }

    private String importFont(byte[] bytes, String name) throws IOException {
        return ImportedFont.receive(context, new ByteArrayInputStream(bytes), name).install();
    }

    private static String familyOf(Typeface typeface) {
        return Shadows.shadowOf(typeface).getFontDescription().getFamilyName();
    }

    private File[] stored() {
        File[] files = new File(context.getFilesDir(), ImportedFont.DIRECTORY).listFiles();
        return files == null ? new File[0] : files;
    }

    @Test public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals("imported_font", Settings.IMPORTED_FONT.key);
        assertEquals(Boolean.FALSE, Settings.IMPORTED_FONT.defaultValue);
        assertTrue("the engine built TikTok's faces already", Settings.IMPORTED_FONT.rebootApp);
    }

    @Test public void aFontIsKeptUnderItsOwnNameAndANewOneReplacesIt() throws IOException {
        assertNull(ImportedFont.name(context));
        assertEquals("Inter-Regular.ttf", importFont(TRUETYPE, "Inter-Regular.ttf"));
        assertEquals("Inter-Regular.ttf", ImportedFont.name(context));

        assertEquals("the signature decides the extension", "Display Pro.otf",
                importFont(OPENTYPE, "Display Pro.TTF"));
        assertEquals("Display Pro.otf", ImportedFont.name(context));
        assertEquals("one imported font at a time", 1, stored().length);
    }

    @Test public void aFileThatIsNotAFontIsRefusedAndTheOldFontStays() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        for (byte[] notAFont : new byte[][]{
                "hello, this is a text file".getBytes(StandardCharsets.UTF_8),
                {'w', 'O', 'F', 'F', 0, 1, 0, 0},
                {0, 1},
                {}}) {
            try {
                ImportedFont.receive(context, new ByteArrayInputStream(notAFont), "notes.ttf");
                fail("took a file that isn't a font");
            } catch (ImportedFont.Rejected refused) {
                assertEquals(ImportedFont.Reason.NOT_A_FONT, refused.reason);
            }
        }
        assertEquals("Inter.ttf", ImportedFont.name(context));
        assertEquals("nothing is left waiting", 1, stored().length);
    }

    @Test public void aFileOverTheLimitIsRefusedBeforeItIsKept() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        InputStream huge = new InputStream() {
            long left = ImportedFont.MAX_BYTES + 1;

            @Override public int read() {
                if (left-- <= 0) return -1;
                return 0;
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (left <= 0) return -1;
                int count = (int) Math.min(length, left);
                java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0);
                if (left == ImportedFont.MAX_BYTES + 1 && count >= 4) buffer[offset + 1] = 1;
                left -= count;
                return count;
            }
        };
        try {
            ImportedFont.receive(context, huge, "Huge.ttf");
            fail("took a file over the limit");
        } catch (ImportedFont.Rejected refused) {
            assertEquals(ImportedFont.Reason.TOO_LARGE, refused.reason);
        }
        assertEquals("Inter.ttf", ImportedFont.name(context));
        assertEquals(1, stored().length);
    }

    @Test public void aStoppedImportLeavesTheOldFontInPlace() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        ImportedFont.receive(context, new ByteArrayInputStream(OPENTYPE), "Other.otf").discard();
        assertEquals("Inter.ttf", ImportedFont.name(context));
        assertEquals(1, stored().length);
    }

    /** A process killed in the middle of a file move. */
    private static final class Died extends RuntimeException {
    }

    /**
     * The real file moves, except move {@code at} (from 0), which fails or kills the process, and
     * move {@code also}, which fails too when set.
     */
    private static final class FaultyMoves implements ImportedFont.Moves {
        private final int at;
        private final int also;
        private final boolean dies;
        int made;

        FaultyMoves(int at, boolean dies) {
            this.at = at;
            this.also = -1;
            this.dies = dies;
        }

        /** Two moves that fail: the second fault lands while the first is being undone. */
        FaultyMoves(int at, int also) {
            this.at = at;
            this.also = also;
            this.dies = false;
        }

        @Override public boolean rename(File from, File to) {
            return go() && from.renameTo(to);
        }

        @Override public boolean delete(File file) {
            return go() && file.delete();
        }

        private boolean go() {
            int move = made++;
            if (move == also) return false;
            if (move != at) return true;
            if (dies) throw new Died();
            return false;
        }

        boolean struck() {
            return made > at;
        }
    }

    /** A second TrueType file under the same name as {@link #TRUETYPE}'s, told apart by its bytes. */
    private static final byte[] TRUETYPE_AGAIN = {0, 1, 0, 0, 0, 13, 0, (byte) 0x90};

    private byte[] bytesOf(String name) throws IOException {
        return java.nio.file.Files.readAllBytes(new File(new File(context.getFilesDir(), ImportedFont.DIRECTORY), name).toPath());
    }

    /** The folder holds one whole font, the old or the new, and the face and name say which. */
    private void assertOneWholeFont(String oldName, byte[] oldBytes, String newName, byte[] newBytes, String when)
            throws IOException {
        ImportedFont.moves = ImportedFont.FILES;
        ImportedFont.forget();
        String kept = ImportedFont.name(context);
        assertNotNull(when + ": both fonts were lost", kept);
        byte[] bytes = bytesOf(kept);
        assertTrue(when + ": " + kept + " isn't the whole old font or the whole new one",
                kept.equals(oldName) && Arrays.equals(oldBytes, bytes) || kept.equals(newName) && Arrays.equals(newBytes, bytes));
        assertEquals(when + ": the face isn't the font kept", kept, familyOf(ImportedFont.typeface()));
        for (File file : stored()) {
            assertFalse(when + ": a set-aside font was left behind", file.getName().startsWith(ImportedFont.PREVIOUS));
        }
        long fonts = Arrays.stream(stored()).filter(file -> !file.getName().startsWith(".")).count();
        assertEquals(when + ": more than one font is kept", 1, fonts);
    }

    /**
     * A move that fails anywhere in a replacement leaves the earlier font whole and in use, or,
     * past the point the new one is in place, the new one. Same name and a new name both.
     */
    @Test public void aReplacementThatFailsAtAnyMoveKeepsOneWholeFont() throws IOException {
        String[][] cases = {{"Inter.ttf", "Inter.ttf"}, {"Inter.ttf", "Display.otf"}};
        byte[][] news = {TRUETYPE_AGAIN, OPENTYPE};
        for (int c = 0; c < cases.length; c++) {
            int at = 0;
            for (; ; at++) {
                ImportedFont.moves = ImportedFont.FILES;
                ImportedFont.remove(context);
                importFont(TRUETYPE, cases[c][0]);
                ImportedFont.Incoming incoming = ImportedFont.receive(context, new ByteArrayInputStream(news[c]), cases[c][1]);
                FaultyMoves faulty = new FaultyMoves(at, false);
                ImportedFont.moves = faulty;
                boolean saved;
                try {
                    incoming.install();
                    saved = true;
                } catch (IOException failure) {
                    saved = false;
                    incoming.discard();
                }
                if (!faulty.struck()) break;
                String when = cases[c][1] + ", move " + at + " failed";
                assertOneWholeFont(cases[c][0], TRUETYPE, cases[c][1], news[c], when);
                if (!saved) {
                    assertEquals(when + ": a failed replacement didn't keep the earlier font",
                            cases[c][0], ImportedFont.name(context));
                    assertTrue(when, Arrays.equals(TRUETYPE, bytesOf(cases[c][0])));
                }
            }
            assertTrue("the replacement made fewer moves than setting aside, moving in and deleting", at >= 3);
        }
    }

    /** A process killed at any move of a replacement leaves one whole font for the next start. */
    @Test public void aReplacementCutShortAtAnyMoveLeavesOneWholeFont() throws IOException {
        String[][] cases = {{"Inter.ttf", "Inter.ttf"}, {"Inter.ttf", "Display.otf"}};
        byte[][] news = {TRUETYPE_AGAIN, OPENTYPE};
        for (int c = 0; c < cases.length; c++) {
            int at = 0;
            for (; ; at++) {
                ImportedFont.moves = ImportedFont.FILES;
                ImportedFont.remove(context);
                importFont(TRUETYPE, cases[c][0]);
                ImportedFont.Incoming incoming = ImportedFont.receive(context, new ByteArrayInputStream(news[c]), cases[c][1]);
                FaultyMoves faulty = new FaultyMoves(at, true);
                ImportedFont.moves = faulty;
                try {
                    incoming.install();
                } catch (Died killed) {
                    // The process is gone: nothing more runs until the next start.
                }
                if (!faulty.struck()) break;
                assertOneWholeFont(cases[c][0], TRUETYPE, cases[c][1], news[c], cases[c][1] + ", killed at move " + at);
            }
            assertTrue(at >= 3);
        }
    }

    /** A font set aside by a replacement that was cut short doesn't come back after Remove. */
    @Test public void removeAfterACutShortReplacementLeavesNoFont() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        ImportedFont.Incoming incoming = ImportedFont.receive(context, new ByteArrayInputStream(OPENTYPE), "Display.otf");
        ImportedFont.moves = new FaultyMoves(1, true);
        try {
            incoming.install();
            fail("the move that kills the process never came");
        } catch (Died killed) {
            // Set aside, and the new font not moved in yet.
        }
        ImportedFont.moves = ImportedFont.FILES;
        assertTrue(ImportedFont.remove(context));
        ImportedFont.forget();
        assertNull("the removed font came back", ImportedFont.name(context));
        assertNull(ImportedFont.typeface());
    }

    /**
     * Remove fails as a whole when a set-aside font won't go: deleting the font in use anyway
     * would let the next look put the set-aside one back in its place.
     */
    @Test public void removeKeepsTheFontInUseWhenASetAsideCopyWontGo() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        ImportedFont.Incoming incoming = ImportedFont.receive(context, new ByteArrayInputStream(OPENTYPE), "Display.otf");
        ImportedFont.moves = new FaultyMoves(2, true);
        try {
            incoming.install();
            fail("the move that kills the process never came");
        } catch (Died killed) {
            // The new font is in place and the old one is still set aside.
        }
        ImportedFont.moves = new FaultyMoves(0, false);
        assertFalse("Remove said it worked with a set-aside font left", ImportedFont.remove(context));
        assertOneWholeFont("Inter.ttf", TRUETYPE, "Display.otf", OPENTYPE, "after a Remove that failed");
        assertEquals("a failed Remove swapped the font in use for the set-aside one",
                "Display.otf", ImportedFont.name(context));
    }

    /** A failed move whose putting back fails too is settled the next time the font is looked for. */
    @Test public void aFailedMoveWhosePutBackAlsoFailsIsSettledOnTheNextLook() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        ImportedFont.Incoming incoming = ImportedFont.receive(context, new ByteArrayInputStream(OPENTYPE), "Display.otf");
        // Move 0 sets Inter.ttf aside, move 1 fails to move the new font in, move 2 fails to put Inter.ttf back.
        ImportedFont.moves = new FaultyMoves(1, 2);
        try {
            incoming.install();
            fail("a replacement whose new font never moved in said it worked");
        } catch (IOException failure) {
            incoming.discard();
        }
        assertOneWholeFont("Inter.ttf", TRUETYPE, "Display.otf", OPENTYPE, "after a double fault");
        assertEquals("Inter.ttf", ImportedFont.name(context));
    }

    @Test public void theNameAFileAppGivesIsMadeSafe() {
        assertEquals("font.ttf", ImportedFont.storedName(null, ".ttf"));
        assertEquals("font.otf", ImportedFont.storedName("  ", ".otf"));
        assertEquals("_.._evil.ttf", ImportedFont.storedName("/../evil.ttf", ".ttf"));
        assertEquals("Noto Sans JP.ttf", ImportedFont.storedName("Noto Sans JP.ttf", ".ttf"));
        assertEquals("weird.woff.ttf", ImportedFont.storedName("weird.woff", ".ttf"));
        assertFalse(ImportedFont.storedName("a/b\\c:d.ttf", ".ttf").matches(".*[/\\\\:].*"));
        assertFalse("a dot name would be taken for the waiting file",
                ImportedFont.storedName(".hidden.ttf", ".ttf").startsWith("."));
    }

    @Test public void theFaceIsLoadedOnceAndForgottenWhenTheFileChanges() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        Typeface face = ImportedFont.typeface();
        assertNotNull(face);
        assertEquals("Inter.ttf", familyOf(face));

        // Gone behind its back: the engine keeps getting the face it had, until a restart.
        for (File file : stored()) assertTrue(file.delete());
        assertSame(face, ImportedFont.typeface());

        importFont(OPENTYPE, "Other.otf");
        assertEquals("Other.otf", familyOf(ImportedFont.typeface()));
        assertTrue(ImportedFont.remove(context));
        assertNull(ImportedFont.typeface());
    }

    @Test public void withoutAContextThereIsNoFaceAndNothingIsKeptAboutIt() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        ImportedFont.forget();
        Utils.setContext(null);
        try {
            assertNull(ImportedFont.typeface());
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertNotNull("the next ask with a context loads it", ImportedFont.typeface());
    }

    @Test public void theImportedFontWinsOverTheSystemFontAndKeepsTheWeight() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        Settings.IMPORTED_FONT.save(true);
        Settings.SYSTEM_FONT.save(true);
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);

        Typeface drawn = SystemFont.systemize(tiktok);
        assertEquals("the imported font lost to the system font", "Inter.ttf", familyOf(drawn));
        // Legacy typefaces keep the asked weight as their style, so this is the weight carried.
        assertEquals("the weight TikTok built wasn't carried over",
                Typeface.create(ImportedFont.typeface(), tiktok.getWeight(), tiktok.isItalic()).getStyle(),
                drawn.getStyle());
        assertTrue(HookStatus.report().toString().contains("system font"));
        assertNull("null still passes through", SystemFont.systemize(null));
    }

    @Test public void withTheImportSwitchOffTheHookAnswersAsUseSystemFontAlone() throws IOException {
        importFont(TRUETYPE, "Inter.ttf");
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);

        assertSame("both off, TikTok's own", tiktok, SystemFont.systemize(tiktok));
        Settings.SYSTEM_FONT.save(true);
        Typeface system = SystemFont.systemize(tiktok);
        assertNotEquals("Inter.ttf", familyOf(system));
        assertTrue(system != tiktok);
        assertSame(Typeface.DEFAULT, SystemFont.replacementFamily());
    }

    @Test public void aMissingOrUnreadableFileFallsBackToWhatTheSystemSwitchSays() throws IOException {
        Settings.IMPORTED_FONT.save(true);
        Typeface tiktok = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        assertSame("nothing imported and the system font off, TikTok's own", tiktok, SystemFont.systemize(tiktok));
        Settings.SYSTEM_FONT.save(true);
        assertSame("nothing imported, the system font", Typeface.DEFAULT, SystemFont.replacementFamily());

        // A file put there by something else, which isn't a font: it never loads.
        File directory = new File(context.getFilesDir(), ImportedFont.DIRECTORY);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        java.nio.file.Files.write(new File(directory, "broken.ttf").toPath(),
                "not a font".getBytes(StandardCharsets.UTF_8));
        ImportedFont.forget();
        assertNull(ImportedFont.typeface());
        assertSame(Typeface.DEFAULT, SystemFont.replacementFamily());
        Settings.SYSTEM_FONT.save(false);
        assertNull(SystemFont.replacementFamily());
    }

    @Test public void theRowsSitWithUseSystemFontOnlyWhenPatched() throws IOException {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertNull(build(activity).findPreference(Settings.IMPORTED_FONT.key));
            assertNull(build(activity).findPreference("action_import_font"));

            SettingsStatus.systemFontEnabled = true;
            PreferenceScreen screen = build(activity);
            Preference toggle = screen.findPreference(Settings.IMPORTED_FONT.key);
            Preference pick = screen.findPreference("action_import_font");
            Preference remove = screen.findPreference("action_remove_imported_font");
            assertNotNull(toggle);
            assertNotNull(pick);
            assertNotNull(remove);
            assertEquals("Use an imported font", String.valueOf(toggle.getTitle()));
            assertTrue("the restart note is missing", String.valueOf(toggle.getSummary()).contains("Restart TikTok"));
            assertEquals("Pick a .ttf or .otf file of up to 20 MB.", String.valueOf(pick.getSummary()));
            assertFalse("nothing to remove", remove.isEnabled());

            importFont(TRUETYPE, "Inter.ttf");
            screen = build(activity);
            assertEquals("Imported: Inter.ttf. Pick another file to replace it.",
                    String.valueOf(screen.findPreference("action_import_font").getSummary()));
            assertTrue(screen.findPreference("action_remove_imported_font").isEnabled());
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}
