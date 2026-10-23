package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Compiled runtime declarations merged into the extension payload. The patch selects these by
 * unique name, then replaces their bodies while preserving the signatures. Host fixture tests
 * read the vendor APKs and runtime behavior tests use a Native stand, so neither covers this.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeedTextBridgeContractTest {
    @Test public void patchPreparationHasOneStaticBridgeWithEachExactNameAndSignature() {
        Map<String, Class<?>[]> expected = new LinkedHashMap<>();
        expected.put("descriptionViewOf", new Class<?>[]{Object.class});
        expected.put("authorViewOf", new Class<?>[]{Object.class});
        expected.put("dateViewOf", new Class<?>[]{Object.class});
        expected.put("resizeDescriptionBuilder", new Class<?>[]{Object.class, View.class});
        expected.put("refreshDescription", new Class<?>[]{Object.class});
        expected.put("refreshAuthor", new Class<?>[]{Object.class, Object.class});
        for (Map.Entry<String, Class<?>[]> entry : expected.entrySet()) {
            List<Method> named = new ArrayList<>();
            for (Method method : FeedTextSize.class.getDeclaredMethods()) {
                if (method.getName().equals(entry.getKey())) named.add(method);
            }
            assertEquals("Patch bridge selection by name must be unique: " + entry.getKey(), 1, named.size());
            Method bridge = named.get(0);
            assertArrayEquals(entry.getKey() + " parameters", entry.getValue(), bridge.getParameterTypes());
            Class<?> result = entry.getKey().equals("descriptionViewOf") ? View.class
                    : entry.getKey().equals("authorViewOf") || entry.getKey().equals("dateViewOf") ? TextView.class
                    : void.class;
            assertEquals(entry.getKey() + " return type", result, bridge.getReturnType());
            assertTrue(entry.getKey() + " must be static", Modifier.isStatic(bridge.getModifiers()));
        }
    }
}
