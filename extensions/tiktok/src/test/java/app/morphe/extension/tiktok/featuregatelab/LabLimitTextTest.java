package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import app.morphe.extension.shared.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.text.NumberFormat;

/**
 * The Lab's two size refusals say the limit the store enforces. "64 KB" and "4096" were typed
 * into the text beside the constants that hold them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LabLimitTextTest {
    @Test public void theSizeRefusalsSayTheLimitsTheStoreEnforces() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);

        String tooLong = FeatureGateLabText.validation(context, FeatureGateLabStore.ValidationFailure.of(
                FeatureGateLabStore.ValidationCode.STRING_TOO_LONG, "STRING"));
        assertTrue(tooLong, tooLong.contains(
                NumberFormat.getInstance().format(FeatureGateLabStore.MAX_STRING_CHARS) + " characters"));

        String tooLarge = FeatureGateLabText.validation(context, FeatureGateLabStore.ValidationFailure.of(
                FeatureGateLabStore.ValidationCode.STRUCTURED_VALUE_TOO_LARGE, "OBJECT"));
        assertTrue(tooLarge, tooLarge.contains(
                NumberFormat.getInstance().format(FeatureGateLabStore.MAX_STRUCTURED_CHARS / 1024) + " KB"));
        assertFalse(tooLong.contains("%"));
        assertFalse(tooLarge.contains("%"));
    }
}
