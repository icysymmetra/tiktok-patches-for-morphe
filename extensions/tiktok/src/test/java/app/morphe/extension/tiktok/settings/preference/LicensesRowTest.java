package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * The Licenses row, and the notice behind it.
 *
 * <p>Morphe's Section 7b asks that its notice be carried in derivative works and provided to the
 * person using the software. A file in the repository does neither for somebody holding a patched
 * phone, so the interesting assertions here are that the text the app shows is the text the
 * repository carries, and that a reader can actually reach it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class LicensesRowTest {
    public static class HostActivity extends Activity {}

    /**
     * The one that matters. LicenseNotice is generated from NOTICE, and a generated file that
     * nobody regenerates is a file that lies: the app would go on showing a notice the project no
     * longer carries, which is the failure this whole item exists to prevent.
     */
    @Test
    public void theNoticeTheAppShowsIsTheNoticeTheProjectCarries() throws IOException {
        File notice = repositoryFile("NOTICE");
        String onDisk = new String(Files.readAllBytes(notice.toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").trim();
        assertTrue("the NOTICE file is too short to be the notice", onDisk.length() > 500);
        assertEquals("NOTICE and LicenseNotice have drifted. Run scripts/gen-license-notice.ps1",
                onDisk, LicenseNotice.TEXT.trim());
    }

    /** The clauses this bundle is actually required to pass on, named rather than assumed. */
    @Test
    public void theNoticeCarriesTheClausesItIsRequiredTo() {
        assertTrue("the notice does not carry Morphe's Section 7b",
                LicenseNotice.TEXT.contains("GPLv3 Section 7b: Preservation of User Notices"));
        assertTrue("the notice does not say derivative works must provide it to the user",
                LicenseNotice.TEXT.contains("provide it to the software user"));
        assertTrue("the notice names no upstream project",
                LicenseNotice.TEXT.contains("https://github.com/MorpheApp/morphe-patches"));
        assertTrue("the notice does not name the project it was forked from",
                LicenseNotice.TEXT.contains("icysymmetra/tiktok-patches-for-morphe"));
    }

    @Test
    public void theRowIsTitledAndReachable() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        LicensesPreference row = new LicensesPreference(context);

        assertEquals("a keyed row is what the settings search can open",
                LicensesPreference.KEY, row.getKey());
        assertEquals("Licenses", row.getTitle().toString());
        assertTrue("the row says nothing about what it opens",
                row.getSummary().toString().toLowerCase().contains("notices"));
    }

    /** Pressing it puts the notice on screen, in something that scrolls, with a way out. */
    @Test
    public void pressingItShowsTheNoticeInSomethingThatScrolls() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);

            LicensesPreference.show(activity);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            var dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("nothing was put on screen", dialog);
            assertTrue("the notice was not shown", dialog.isShowing());

            TextView body = firstTextViewContaining(
                    dialog.getWindow().getDecorView(), "Preservation of User Notices");
            assertNotNull("the notice text is not in the dialog", body);
            assertTrue("the notice cannot be scrolled, so most of it cannot be read",
                    hasScrollingAncestor(body));
            assertNotNull("there is no way to close it",
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE));
            assertNotNull("there is no way to reach the full license",
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL));

            dialog.dismiss();
        }
    }

    /** A second press that lands before the notice takes input opens no second one on top. */
    @Test
    public void aSecondPressWhileTheNoticeIsUpOpensNothingMore() {
        try (var owner = Robolectric.buildActivity(HostActivity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            Utils.setActivity(activity);

            LicensesPreference.show(activity);
            var first = ShadowAlertDialog.getLatestAlertDialog();
            LicensesPreference.show(activity);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertSame("a second notice opened on top of the first", first, ShadowAlertDialog.getLatestAlertDialog());

            first.dismiss();
            LicensesPreference.show(activity);
            var again = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame("once it's closed, a press opens it again", first, again);
            assertTrue(again.isShowing());
            again.dismiss();
        }
    }

    private static boolean hasScrollingAncestor(View view) {
        for (Object parent = view.getParent(); parent instanceof View; parent = ((View) parent).getParent()) {
            if (parent instanceof android.widget.ScrollView) return true;
        }
        return false;
    }

    private static TextView firstTextViewContaining(View view, String needle) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().contains(needle)) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = firstTextViewContaining(group.getChildAt(index), needle);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The module runs from its own directory or from the root, depending on the task. */
    private static File repositoryFile(String name) {
        for (String candidate : new String[]{name, "../../" + name, "../../../" + name}) {
            File file = new File(candidate);
            if (file.isFile()) return file;
        }
        throw new AssertionError("could not find " + name + " from " + new File(".").getAbsolutePath());
    }
}
