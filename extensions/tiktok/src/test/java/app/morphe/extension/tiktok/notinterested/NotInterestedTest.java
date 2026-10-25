package app.morphe.extension.tiktok.notinterested;

import static org.junit.Assert.*;
import org.junit.Test;

public class NotInterestedTest {
    @Test public void acceptsOnlyAnExplicitSuccessStatus() throws Exception {
        assertTrue(NotInterested.succeeded(new Response(new Body(0))));
        assertFalse(NotInterested.succeeded(new Response(new Body(1))));
        assertFalse(NotInterested.succeeded(new Response(null)));
        assertFalse(NotInterested.succeeded(new Object()));
        assertFalse(NotInterested.succeeded(null));
    }
    private static final class Response {
        public final Object data;
        Response(Object data) { this.data = data; }
    }
    private static final class Body {
        public final int status_code;
        Body(int status) { status_code = status; }
    }
}
