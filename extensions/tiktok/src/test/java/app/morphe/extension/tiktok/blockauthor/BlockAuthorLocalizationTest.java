package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BlockAuthorLocalizationTest {
    private static final VideoAuthor AUTHOR = new VideoAuthor(
            "raw_uid_23", "raw_sec_uid_23", "raw_creator_23", "raw_aweme_23");

    /**
     * The creator's name as every one of these messages carries it: between Unicode's
     * first-strong isolate and its pop.
     *
     * <p>A name is the one part of these sentences that is not ours and not translated, and in a
     * right-to-left script it used to reorder the words around it. The pair is also how the
     * exported diagnostic report finds a name to leave out, in a language it cannot read. It is
     * spelled out here rather than hidden behind a helper so that a change to either mark shows
     * up as a change to what these six messages say.
     */
    private static final String NAME = "⁨raw_creator_23⁩";

    @Before public void resetState() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        CurrentVideoAuthor.resetForTests();
        ShadowToast.reset();
    }

    @After public void clearActivity() throws Exception {
        java.lang.reflect.Method dismiss =
                BlockAuthorOverlay.class.getDeclaredMethod("dismissUndo");
        dismiss.setAccessible(true);
        dismiss.invoke(null);
        Utils.setActivity(null);
        ShadowToast.reset();
    }

    @Test @Config(sdk = 28, qualifiers = "de")
    public void germanOwnsEveryBlockAndUnblockResult() {
        verify(new Expected(
                NAME + " blockiert",
                "TikTok hat " + NAME + " nicht blockiert. Versuch es gleich noch einmal.",
                "Die Blockierung von " + NAME + " konnte nicht bestätigt werden. Öffne das Profil,"
                        + " um nachzusehen.",
                NAME + " entblockt",
                "TikTok hat die Blockierung von " + NAME + " nicht aufgehoben. Versuch es gleich"
                        + " noch einmal.",
                "Die Aufhebung der Blockierung von " + NAME + " konnte nicht bestätigt werden."
                        + " Öffne das Profil, um nachzusehen."
        ));
    }

    @Test @Config(sdk = 28, qualifiers = "in-rID")
    public void indonesianOwnsEveryBlockAndUnblockResult() {
        verify(new Expected(
                NAME + " diblokir",
                "TikTok tidak memblokir " + NAME + ". Coba lagi sebentar lagi.",
                "Pemblokiran " + NAME + " tidak dapat dikonfirmasi. Buka profilnya untuk memeriksa.",
                NAME + " dibuka blokirnya",
                "TikTok tidak membuka blokir " + NAME + ". Coba lagi sebentar lagi.",
                "Pembukaan blokir " + NAME + " tidak dapat dikonfirmasi. Buka profilnya untuk"
                        + " memeriksa."
        ));
    }

    /**
     * The fallback for a creator with nothing to name sits inside a translated sentence, so it is
     * translated too. It used to be English on every phone.
     */
    @Test @Config(sdk = 28, qualifiers = "de")
    public void aCreatorWithNothingToNameIsNamedInTheReadersLanguage() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Utils.setActivity(owner.get());
            assertEquals("dieser Creator", new VideoAuthor(null, null, null, null).label());
        }
    }

    private static void verify(Expected expected) {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            owner.windowFocusChanged(true);
            Utils.setContext(activity);
            Utils.setActivity(activity);

            BlockAuthorOverlay.reportBlockResult(
                    AUTHOR, BlockAuthorService.Result.CONFIRMED);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ViewGroup root = activity.findViewById(android.R.id.content);
            assertTrue(root.getChildAt(root.getChildCount() - 1).getContentDescription()
                    .toString().startsWith(expected.blockConfirmed + ". "));

            ShadowToast.reset();
            BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.REJECTED);
            assertEquals(expected.blockRejected, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportBlockResult(AUTHOR, BlockAuthorService.Result.UNCONFIRMED);
            assertEquals(expected.blockUnconfirmed, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportUnblockResult(AUTHOR, BlockAuthorService.Result.CONFIRMED);
            assertEquals(expected.unblockConfirmed, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportUnblockResult(AUTHOR, BlockAuthorService.Result.REJECTED);
            assertEquals(expected.unblockRejected, ShadowToast.getTextOfLatestToast());

            ShadowToast.reset();
            BlockAuthorOverlay.reportUnblockResult(
                    AUTHOR, BlockAuthorService.Result.UNCONFIRMED);
            assertEquals(expected.unblockUnconfirmed, ShadowToast.getTextOfLatestToast());
        }
    }

    private static final class Expected {
        final String blockConfirmed;
        final String blockRejected;
        final String blockUnconfirmed;
        final String unblockConfirmed;
        final String unblockRejected;
        final String unblockUnconfirmed;

        Expected(
                String blockConfirmed,
                String blockRejected,
                String blockUnconfirmed,
                String unblockConfirmed,
                String unblockRejected,
                String unblockUnconfirmed
        ) {
            this.blockConfirmed = blockConfirmed;
            this.blockRejected = blockRejected;
            this.blockUnconfirmed = blockUnconfirmed;
            this.unblockConfirmed = unblockConfirmed;
            this.unblockRejected = unblockRejected;
            this.unblockUnconfirmed = unblockUnconfirmed;
        }
    }
}
