/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.follow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A follow that does not land comes back as an ordinary success with the refusal in the body,
 * so the two fields that explain it have to survive both shapes the body arrives in.
 */
public class FollowVerdictTest {
    /** The shape TikTok's follow endpoint answers with when it turns a follow down. */
    private static final String REFUSED_BODY =
            "{\"extra\":{\"now\":1757116800000,\"logid\":\"20260906T031000\"},"
                    + "\"log_pb\":{\"impr_id\":\"20260906031000ABCDEF\"},"
                    + "\"status_code\":2098,"
                    + "\"status_msg\":\"You\\u2019re following too fast. Try again later.\","
                    + "\"follow_status\":0,\"watch_status\":0}";

    private static final String ACCEPTED_BODY =
            "{\"status_code\":0,\"status_msg\":\"\",\"follow_status\":1,\"watch_status\":1}";

    /** Stands in for the parsed model: TikTok leaves these field names alone. */
    public static final class ParsedBody {
        int status_code = 3058;
        String status_msg = "Verification needed";
    }

    /** Some models expose the same two through getters instead. */
    public static final class GetterBody {
        public int getStatusCode() {
            return 2096;
        }

        public String getStatusMsg() {
            return "Follow limit reached";
        }
    }

    /** A static getter answers for the class, not for this response. */
    public static final class StaticGetterBody {
        public static int getStatusCode() {
            return 9999;
        }
    }

    /** A holder that keeps the answer as text it has not parsed. */
    public static final class BlobBody {
        private final String body;

        BlobBody(String body) {
            this.body = body;
        }

        @Override
        public String toString() {
            return body;
        }
    }

    @Test
    public void aCapturedRefusalKeepsItsCodeAndMessage() {
        FollowVerdict verdict = FollowVerdict.parse(REFUSED_BODY);

        assertEquals("2098", verdict.statusCode);
        assertEquals("You’re following too fast. Try again later.", verdict.statusMsg);
        assertTrue(verdict.isRefusal());
    }

    @Test
    public void anAcceptedFollowIsNotARefusal() {
        FollowVerdict verdict = FollowVerdict.parse(ACCEPTED_BODY);

        assertEquals("0", verdict.statusCode);
        // An empty message is no message, not a message that happens to be blank.
        assertEquals(FollowVerdict.UNKNOWN, verdict.statusMsg);
        assertFalse(verdict.isRefusal());
    }

    @Test
    public void aParsedModelIsReadByItsFields() {
        FollowVerdict verdict = FollowVerdict.of(new ParsedBody());

        assertEquals("3058", verdict.statusCode);
        assertEquals("Verification needed", verdict.statusMsg);
        assertTrue(verdict.isRefusal());
    }

    @Test
    public void aModelWithoutFieldsIsReadByItsGetters() {
        FollowVerdict verdict = FollowVerdict.of(new GetterBody());

        assertEquals("2096", verdict.statusCode);
        assertEquals("Follow limit reached", verdict.statusMsg);
    }

    @Test
    public void abodyThatSaysNothingStaysUnknown() {
        FollowVerdict verdict = FollowVerdict.of(new Object());

        assertEquals(FollowVerdict.UNKNOWN, verdict.statusCode);
        assertEquals(FollowVerdict.UNKNOWN, verdict.statusMsg);
        assertTrue(verdict.isEmpty());
        assertFalse(verdict.isRefusal());
    }

    @Test
    public void aMissingBodyIsNotARefusal() {
        assertTrue(FollowVerdict.of(null).isEmpty());
        assertTrue(FollowVerdict.parse(null).isEmpty());
        assertFalse(FollowVerdict.parse("not json at all").isRefusal());
    }

    @Test
    public void aNullMessageDoesNotBecomeTheStringNull() {
        assertEquals(FollowVerdict.UNKNOWN,
                FollowVerdict.parse("{\"status_code\":2098,\"status_msg\":null}").statusMsg);
    }

    @Test
    public void aQuotedCodeReadsTheSameAsABareOne() {
        assertEquals("2098", FollowVerdict.parse("{\"status_code\": \"2098\"}").statusCode);
        assertEquals("2098", FollowVerdict.parse("{\"status_code\" : 2098 }").statusCode);
    }

    @Test
    public void anErrorCodeIsReadWhenTheStatusOneIsAbsent() {
        FollowVerdict verdict =
                FollowVerdict.parse("{\"error_code\":8,\"error_msg\":\"Blocked by this user\"}");

        assertEquals("8", verdict.statusCode);
        assertEquals("Blocked by this user", verdict.statusMsg);
    }

    @Test
    public void aNestedStatusDoesNotAnswerForTheWholeResponse() {
        // TikTok wraps a per-item status under data; the response's own status is the one
        // that says whether the follow landed.
        FollowVerdict verdict = FollowVerdict.parse(
                "{\"data\":{\"status_code\":0,\"status_msg\":\"ok\"},"
                        + "\"status_code\":2098,\"status_msg\":\"Try again later.\"}");

        assertEquals("2098", verdict.statusCode);
        assertEquals("Try again later.", verdict.statusMsg);
        assertTrue(verdict.isRefusal());
    }

    @Test
    public void aKeyInsideAnArrayIsNotMistakenForTheAnswer() {
        assertEquals(FollowVerdict.UNKNOWN,
                FollowVerdict.parse("{\"items\":[{\"status_code\":42}]}").statusCode);
    }

    @Test
    public void anUnparsedBodyIsReadFromItsText() {
        FollowVerdict verdict = FollowVerdict.of(new BlobBody(REFUSED_BODY));

        assertEquals("2098", verdict.statusCode);
        assertTrue(verdict.isRefusal());
    }

    @Test
    public void aStaticGetterIsNotReadAsThisResponse() {
        assertEquals(FollowVerdict.UNKNOWN, FollowVerdict.of(new StaticGetterBody()).statusCode);
    }

    @Test
    public void aFractionalZeroIsStillAnAcceptedFollow() {
        // The code arrives as text because the model may hold it as any numeric type.
        assertFalse(FollowVerdict.isRefusalCode("0"));
        assertFalse(FollowVerdict.isRefusalCode("0.0"));
        assertFalse(FollowVerdict.isRefusalCode(FollowVerdict.UNKNOWN));
        assertFalse(FollowVerdict.isRefusalCode(null));
        assertTrue(FollowVerdict.isRefusalCode("2098"));
        assertTrue(FollowVerdict.isRefusalCode("2098.0"));
    }

    @Test
    public void malformedAndNonFiniteCodesStayUnconfirmed() {
        assertFalse(FollowVerdict.isRefusalCode("not-a-code"));
        assertFalse(FollowVerdict.isRefusalCode("NaN"));
        assertFalse(FollowVerdict.isRefusalCode("Infinity"));
        assertFalse(FollowVerdict.isRefusalCode("1e999"));
        assertFalse(FollowVerdict.parse("{\"status_code\":\"NaN\"}").isRefusal());
        assertFalse(FollowVerdict.parse("{\"status_code\":{\"value\":2098}}").isRefusal());
    }

    @Test
    public void theTextFormNamesBothFields() {
        assertEquals("status_code=2098 status_msg=You’re following too fast. Try again later.",
                FollowVerdict.parse(REFUSED_BODY).toString());
    }
}
