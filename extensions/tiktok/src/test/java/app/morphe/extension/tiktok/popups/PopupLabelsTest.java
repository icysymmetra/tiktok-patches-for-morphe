package app.morphe.extension.tiktok.popups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PopupLabelsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's popup task: a public getter for the label the layer logs and filters on. */
    public static class Task {
        private final String label;

        public Task(String label) {
            this.label = label;
        }

        public final String getElementLabel() {
            return label;
        }
    }

    /** A subclass, the way the real popups extend the task base. */
    public static final class Sub extends Task {
        public Sub(String label) {
            super(label);
        }
    }

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.POPUP_LABEL_PICKS.save("");
        Settings.POPUP_LABEL_CATALOG.save("");
        PopupLabels.forgetSeen();
    }

    private static List<String> catalog() {
        return PopupLabelCatalog.labels();
    }

    @Test public void recordsEachLabelOnceInTheOrderSeenAndBlocksNothingByDefault() {
        assertFalse(PopupLabels.filter(new Sub("Follow friends"), false));
        assertFalse(PopupLabels.filter(new Sub("Upsell sheet"), false));
        assertFalse(PopupLabels.filter(new Sub("Follow friends"), false));

        assertEquals(Arrays.asList("Follow friends", "Upsell sheet"), catalog());
    }

    @Test public void aTickedLabelIsDroppedAtItsNextTriggerAndOthersAreNot() {
        PopupLabels.filter(new Sub("Follow friends"), false);
        Settings.POPUP_LABEL_PICKS.save("follow friends");

        assertTrue("ticked, matched without regard to case", PopupLabels.filter(new Sub("Follow Friends"), false));
        assertFalse(PopupLabels.filter(new Sub("Upsell sheet"), false));
        assertTrue("TikTok's own drop stays a drop", PopupLabels.filter(new Sub("Upsell sheet"), true));
    }

    @Test public void promptsTheAccountMustAnswerAreNeverRecordedOrBlockedEvenWhenTyped() {
        List<String> safety = Arrays.asList(
                "CAPTCHA challenge", "ReCaptchaPopup", "phone_verification", "Verify your email",
                "login_required", "SignInSheet", "age_gate", "AgeGate", "ban appeal", "Appeal",
                "privacy_consent", "GDPR consent", "terms of service", "Account suspended");
        Settings.POPUP_LABEL_PICKS.save(String.join(", ", safety));
        for (String label : safety) {
            assertTrue(label + " is a safety label", PopupLabels.isSafety(label));
            assertFalse(label + " is not blocked", PopupLabels.filter(new Sub(label), false));
        }
        assertEquals("none of them is listed", new ArrayList<String>(), catalog());
    }

    @Test public void ordinaryWordsThatContainAShortSafetyWordAreStillOrdinary() {
        for (String label : Arrays.asList("Banner promo", "Message friends", "Pinterest tip", "Image tips", "Manage storage")) {
            assertFalse(label, PopupLabels.isSafety(label));
        }
        assertTrue(PopupLabels.isSafety("com.ss.android.ugc.aweme.compliance.PopupTask"));
        assertFalse(PopupLabels.isSafety("com.ss.android.ugc.aweme.recommend.RecUserPopup"));
    }

    @Test public void aSafetyClassIsExemptWhateverItsLabel() {
        Settings.POPUP_LABEL_PICKS.save("some label");
        assertTrue(PopupLabels.shouldDrop("Some label", "com.ss.android.ugc.aweme.recommend.Task"));
        assertFalse(PopupLabels.shouldDrop("Some label", "com.ss.android.ugc.aweme.compliance.privacy.Task"));
    }

    @Test public void pausedNothingIsRecordedOrBlocked() {
        Settings.POPUP_LABEL_PICKS.save("follow friends");
        PausedProcess.set(true);

        assertFalse(PopupLabels.filter(new Sub("Follow friends"), false));
        assertEquals(new ArrayList<String>(), catalog());
        assertEquals("the saved pick is untouched", "follow friends", Settings.POPUP_LABEL_PICKS.savedValue());

        PausedProcess.set(false);
        assertTrue(PopupLabels.filter(new Sub("Follow friends"), false));
    }

    @Test public void theListIsBoundedAndKeepsWhatTheReaderTicked() {
        Settings.POPUP_LABEL_PICKS.save("label 0");
        for (int index = 0; index < PopupLabels.MAX_LABELS; index++) {
            PopupLabels.filter(new Sub("Label " + index), false);
        }
        assertEquals(PopupLabels.MAX_LABELS, catalog().size());

        PopupLabels.filter(new Sub("Fresh"), false);

        List<String> labels = catalog();
        assertEquals(PopupLabels.MAX_LABELS, labels.size());
        assertTrue("a ticked label stays", labels.contains("Label 0"));
        assertFalse("the oldest one not ticked makes room", labels.contains("Label 1"));
        assertEquals("the new one is last", "Fresh", labels.get(labels.size() - 1));
    }

    @Test public void anythingUnreadableIsLeftAlone() {
        assertFalse(PopupLabels.filter(null, false));
        assertFalse("no label getter", PopupLabels.filter(new Object(), false));
        assertFalse("no label", PopupLabels.filter(new Sub(""), false));
        assertFalse(PopupLabels.filter(new Sub(null), false));
        assertEquals(new ArrayList<String>(), catalog());
    }
}
