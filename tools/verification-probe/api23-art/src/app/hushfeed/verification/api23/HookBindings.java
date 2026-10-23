package app.hushfeed.verification.api23;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads exact shipped snapshot fields. Human-readable reports expose counts, not bindings. */
final class HookBindings {
    private static final String OWNER = "app.morphe.extension.shared.diagnostics.HookStatus";

    static String require(Object snapshot) throws ReflectiveOperationException {
        requireOwner(snapshot, OWNER + "$Snapshot");
        List<?> families = (List<?>) field(snapshot, "families", List.class);
        if (families.size() > 256) throw new AssertionError("unexpected payload hook family count");
        Map<String, List<String>> bindings = new LinkedHashMap<>();
        for (Object family : families) {
            requireOwner(family, OWNER + "$FamilySnapshot");
            String name = (String) field(family, "name", String.class);
            List<?> bound = (List<?>) field(family, "bound", List.class);
            if (name == null || bindings.containsKey(name) || bound.size() > 200) {
                throw new AssertionError("invalid payload hook snapshot");
            }
            List<String> names = new ArrayList<>();
            for (Object member : bound) {
                if (!(member instanceof String)) throw new AssertionError("invalid payload bound member");
                names.add((String) member);
            }
            bindings.put(name, names);
        }
        requireMember(bindings, "captions", "47.4.3:dm5");
        requireMember(bindings, "captions", "47.4.3:dly");
        requireMember(bindings, "main feed", "FeedItemList.getItems");
        return "captions=" + bindings.get("captions") + "; main feed=" + bindings.get("main feed");
    }

    private static void requireOwner(Object value, String name) {
        if (value == null || !value.getClass().getName().equals(name)) {
            throw new AssertionError("unexpected payload hook snapshot owner");
        }
    }

    private static Object field(Object owner, String name, Class<?> type) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers())) {
            throw new AssertionError("unexpected payload hook snapshot field");
        }
        field.setAccessible(true);
        Object value = field.get(owner);
        if (value == null) throw new AssertionError("missing payload hook snapshot field");
        return value;
    }

    private static void requireMember(Map<String, List<String>> bindings, String family, String member) {
        List<String> names = bindings.get(family);
        if (names == null || !names.contains(member)) {
            throw new AssertionError("positive payload HookStatus binding missing: " + family + "/" + member);
        }
    }

    private HookBindings() { }
}
