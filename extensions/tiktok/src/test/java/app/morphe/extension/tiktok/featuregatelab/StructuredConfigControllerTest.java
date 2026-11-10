package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Direct coercion and copy-boundary coverage for the Feature Gate Lab editor. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StructuredConfigControllerTest {
    @Test public void actionableFieldsExposeOnlySupportedInstanceShapes() {
        JSONArray fields = StructuredConfigController.actionableFields(Config.class.getName());

        assertTrue(fields.toString().contains("count"));
        assertTrue(fields.toString().contains("ids"));
        assertTrue(fields.toString().contains("levels"));
        assertTrue(fields.toString().contains("weights"));
        assertFalse(fields.toString().contains("ignored"));
        assertEquals("INT", StructuredConfigController.fieldKind(Config.class.getName(), "count"));
        assertEquals("LONG", StructuredConfigController.fieldKind(Config.class.getName(), "total"));
        assertEquals("LIST_INT", StructuredConfigController.fieldKind(Config.class.getName(), "ids"));
        assertEquals("JSON", StructuredConfigController.fieldKind(Config.class.getName(), "child"));
        assertEquals("UNSUPPORTED", StructuredConfigController.fieldKind(Config.class.getName(), "missing"));
    }

    @Test public void applyCoercesScalarsCollectionsMapsAndNestedObjectsOnACopy() throws Exception {
        Config source = new Config();
        source.weights.put("old", 2L);
        JSONObject patch = new JSONObject()
                .put("count", "7")
                .put("total", "9223372036854775807")
                .put("ratio", "1.5")
                .put("enabled", "false")
                .put("ids", new JSONArray().put(3).put(4))
                .put("levels", new JSONArray().put("5").put(6))
                .put("weights", new JSONObject().put("new", "9"))
                .put("child", new JSONObject().put("value", "11").put("label", "nested"));

        StructuredConfigController.ApplyResult result = StructuredConfigController.apply(
                Config.class, null, source, patch.toString());

        assertTrue(result.applied);
        assertEquals(8, result.fieldCount);
        Config updated = (Config) result.value;
        assertEquals(7, updated.count);
        assertEquals(Long.MAX_VALUE, updated.total);
        assertEquals(1.5, updated.ratio, 0.0001);
        assertFalse(updated.enabled);
        assertArrayEquals(new int[]{3, 4}, updated.ids);
        assertEquals(List.of(5, 6), updated.levels);
        assertEquals(Long.valueOf(9), updated.weights.get("new"));
        assertEquals(11, updated.child.value);
        assertEquals("nested", updated.child.label);
        assertEquals("before", updated.name);
        assertEquals(3, source.count);
        assertNotSame(source.child, updated.child);
        assertEquals(2L, source.weights.get("old").longValue());
    }

    @Test public void theSameStoredValueIsParsedOnceHoweverOftenTheGateIsRead() throws Exception {
        // This runs on TikTok's own gate threads. A structured gate the host reads often used to
        // pay a JSON parse of the whole stored value, up to 64 KB of it, on every single read.
        StructuredConfigController.clearParsedPatchesForTests();
        String patch = new JSONObject().put("count", "7").put("name", "after").toString();

        for (int read = 0; read < 100; read++) {
            StructuredConfigController.ApplyResult result =
                    StructuredConfigController.apply(Config.class, null, new Config(), patch);
            assertTrue(result.applied);
        }

        assertEquals("the stored value was parsed again for a read that could have reused it",
                1, StructuredConfigController.parsesForTests);
    }

    @Test public void aValueNestedPastTheLimitIsRefusedRatherThanRecursedInto() throws Exception {
        // The guard that looked like it bounded this could never fire: it sat on a method that
        // does not recurse, and its only caller passed zero. What recurses is coerce, through
        // that method and back, forty deep here.
        // Deep holds a Deep, so this really can nest as far as the text does. Config cannot:
        // its child is a Child with no child of its own, so a nested value there stops two
        // levels down for a different reason and never reaches the depth check at all.
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int level = 0; level < 40; level++) {
            open.append("{\"child\":");
            close.append("}");
        }
        String deep = "{\"child\":" + open + "{\"value\":\"1\"}" + close + "}";

        StructuredConfigController.ApplyResult result =
                StructuredConfigController.apply(Deep.class, null, new Deep(), deep);

        assertFalse("a forty deep value was walked all the way down", result.applied);
        // Not just "nested": without the depth guard this fails anyway, with "unsupported nested
        // field child", so a looser assertion would pass against the bug.
        assertTrue("the refusal does not name the depth limit: "
                        + (result.error == null ? "nothing" : result.error.text()),
                result.error != null && result.error.text().contains("nested more than"));
    }

    @Test public void overflowAndNonFiniteNumbersFailWithoutChangingTheSource() throws Exception {
        Config source = new Config();

        StructuredConfigController.ApplyResult integerOverflow = StructuredConfigController.apply(
                Config.class, null, source, new JSONObject().put("count", "2147483648").toString());
        assertFalse(integerOverflow.applied);
        assertSame(source, integerOverflow.value);
        assertEquals(3, source.count);

        StructuredConfigController.ApplyResult nonFinite = StructuredConfigController.apply(
                Config.class, null, source, new JSONObject().put("ratio", "Infinity").toString());
        assertFalse(nonFinite.applied);
        assertSame(source, nonFinite.value);
        assertEquals(2.0, source.ratio, 0.0001);
    }

    @Test public void rootArraysUseTheExplicitValueKey() throws Exception {
        JSONObject patch = new JSONObject().put("$value", new JSONArray().put("4").put(9));
        StructuredConfigController.ApplyResult result = StructuredConfigController.apply(
                int[].class, null, new int[]{1}, patch.toString());

        assertTrue(StructuredConfigController.isRootArray(int[].class.getName()));
        assertEquals("LIST_INT", StructuredConfigController.fieldKind(int[].class.getName(), "$value"));
        assertTrue(result.applied);
        assertArrayEquals(new int[]{4, 9}, (int[]) result.value);
    }

    /** Nests without limit, which is what the depth guard is for. */
    public static final class Deep {
        public int value = 0;
        public Deep child;
    }

    public static final class Config {
        public int count = 3;
        public long total = 4L;
        public double ratio = 2.0;
        public boolean enabled = true;
        public String name = "before";
        public int[] ids = {1, 2};
        public List<Integer> levels = new ArrayList<>();
        public Map<String, Long> weights = new LinkedHashMap<>();
        public Child child = new Child();
        public static String ignored;
    }

    public static final class Child {
        public int value;
        public String label;
    }

    /**
     * Two Boolean fields and nothing else, so a test can look at how a generated Switch is named
     * without a text input beside it answering for the whole screen. Separate from {@link Config}
     * because adding a field there moves the editor counts other pages assert.
     */
    public static final class TwoBooleans {
        public boolean enabled = true;
        public boolean verbose = false;
    }
}
