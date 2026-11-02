/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.*;

import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;

import com.ss.android.ugc.aweme.translation.model.MultiTranslationResultDM;
import com.ss.android.ugc.aweme.translation.model.TranslationResultDM;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class NativeDmTranslationTest {
    @Test public void onlyOneConfirmedPlainResultWithTheExactOriginalEchoIsReturned() throws Exception {
        MultiTranslationResultDM response = response();
        assertEquals(" translated draft ", NativeDmTranslation.readResponse(response, "original draft"));
        response.statusMsg = "";
        response.results.get(0).textExtraList = Collections.emptyList();
        assertEquals(" translated draft ", NativeDmTranslation.readResponse(response, "original draft"));
    }

    @Test public void outerFailuresMissingStatusAndAnyServerStatusMessageAreRejected() {
        MultiTranslationResultDM response = response();
        response.statusCode = null; rejected(response, "original draft");
        response.statusCode = 1; rejected(response, "original draft");
        response.statusCode = 0; response.statusMsg = "service error"; rejected(response, "original draft");
    }

    @Test public void knownNativeFailureStatusesAndUnknownStatusesNeverLookSuccessful() {
        MultiTranslationResultDM response = response();
        for (Integer status : Arrays.asList(null, 10000, 10001, 10002, 10003, 10004, 1, -1)) {
            response.results.get(0).translationStatusCode = status;
            rejected(response, "original draft");
        }
    }

    @Test public void absentExtraOrForeignResultsCannotBePairedWithTheDraft() {
        MultiTranslationResultDM response = response();
        TranslationResultDM result = response.results.get(0);
        response.results = null; rejected(response, "original draft");
        response.results = Collections.emptyList(); rejected(response, "original draft");
        response.results = Arrays.asList(result, result); rejected(response, "original draft");
        response.results = Collections.singletonList(null); rejected(response, "original draft");
        rejected(null, "original draft"); rejected("foreign response", "original draft");
    }

    @Test public void aDifferentOrMissingEchoAndRichMetadataAreRefused() {
        MultiTranslationResultDM response = response();
        TranslationResultDM result = response.results.get(0);
        result.originalText = "other draft"; rejected(response, "original draft");
        result.originalText = null; rejected(response, "original draft");
        result.originalText = "original draft";
        result.textExtraList = Collections.singletonList(new Object()); rejected(response, "original draft");
        rejected(response, null);
    }

    @Test public void emptyAndOversizedTranslationsAreRejectedWithoutTrimmingValidText() throws Exception {
        MultiTranslationResultDM response = response();
        TranslationResultDM result = response.results.get(0);
        for (String invalid : Arrays.asList(null, "", " \r\n\t ")) {
            result.translatedText = invalid; rejected(response, "original draft");
        }
        result.translatedText = new String(new char[DmTranslationPreview.MAX_TEXT + 1]).replace('\0', 'x');
        rejected(response, "original draft");
        result.translatedText = result.translatedText.substring(1);
        assertEquals(DmTranslationPreview.MAX_TEXT, NativeDmTranslation.readResponse(response, "original draft").length());
    }

    @Test @LooperMode(LooperMode.Mode.PAUSED)
    public void anUnavailableAccountCompletesTheRequestInsteadOfLeavingItsPreviewWaiting() throws Exception {
        String previous = SignedInUser.idForTests;
        AtomicInteger completed = new AtomicInteger();
        AtomicReference<String> answer = new AtomicReference<>("waiting");
        try {
            SignedInUser.idForTests = "";
            DmTranslationPreview.Request request = new NativeDmTranslation().translate(getClass().getClassLoader(),
                    "account-a", "draft original", () -> true, result -> {
                        answer.set(result);
                        completed.incrementAndGet();
                    });
            Utils.awaitBackgroundTasksForTests();
            SignedInUser.idForTests = "account-a";
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, completed.get());
            assertNull(answer.get());
            request.cancel();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, completed.get());
        } finally {
            SignedInUser.idForTests = previous;
        }
    }

    private static MultiTranslationResultDM response() {
        TranslationResultDM result = new TranslationResultDM();
        result.translationStatusCode = 0; result.originalText = "original draft"; result.translatedText = " translated draft ";
        MultiTranslationResultDM response = new MultiTranslationResultDM();
        response.statusCode = 0; response.results = Collections.singletonList(result);
        return response;
    }

    private static void rejected(Object response, String original) {
        try { NativeDmTranslation.readResponse(response, original); fail("An unconfirmed result was accepted"); }
        catch (IllegalArgumentException | ReflectiveOperationException expected) {}
    }
}
