package app.morphe.extension.tiktok.blockauthor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class BlockAuthorServiceTest {
    private static final Method STATUS;

    static {
        try {
            STATUS = BlockAuthorService.class.getDeclaredMethod("statusCodeOf", Object.class);
            STATUS.setAccessible(true);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    public static final class GetterResponse {
        private final Object body;

        GetterResponse(Object body) {
            this.body = body;
        }

        Object body() {
            return body;
        }
    }

    public static final class GetterBody {
        private final Number status;

        GetterBody(Number status) {
            this.status = status;
        }

        Number getStatusCode() {
            return status;
        }
    }

    public static final class FieldBody {
        public final Object statusCode;

        FieldBody(Object statusCode) {
            this.statusCode = statusCode;
        }
    }

    public static final class UnknownResponse {
    }

    private static Integer status(Object response) throws Exception {
        return (Integer) STATUS.invoke(null, response);
    }

    @Test
    public void onlyExactIntegralZeroThroughGetterConfirms() throws Exception {
        assertEquals(Integer.valueOf(0), status(new GetterResponse(new GetterBody(Integer.valueOf(0)))));
        assertEquals(Integer.valueOf(2), status(new GetterResponse(new GetterBody(Long.valueOf(2)))));
        assertNull(status(new GetterResponse(new GetterBody(Double.valueOf(0.5)))));
        assertNull(status(new GetterResponse(new GetterBody(Double.valueOf(Double.NaN)))));
        assertNull(status(new GetterResponse(new GetterBody(Long.valueOf(Long.MAX_VALUE)))));
        assertNull(status(new GetterResponse(new GetterBody(new BigDecimal("2147483647.0000000001")))));
        assertEquals(Integer.valueOf(2147483647),
                status(new GetterResponse(new GetterBody(new BigDecimal("2147483647.0000")))));
    }

    @Test
    public void fieldShapeAndUnreadableResponsesAreUnconfirmed() throws Exception {
        assertEquals(Integer.valueOf(0), status(new GetterResponse(new FieldBody(Integer.valueOf(0)))));
        assertEquals(Integer.valueOf(-1), status(new GetterResponse(new FieldBody(Integer.valueOf(-1)))));
        assertNull(status(null));
        assertNull(status(new UnknownResponse()));
        assertNull(status(new GetterResponse(new FieldBody("zero"))));
    }
}
