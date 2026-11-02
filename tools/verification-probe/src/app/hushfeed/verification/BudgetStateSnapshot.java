package app.hushfeed.verification;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact process-local scalar state. References such as native focus listeners are never copied. */
final class BudgetStateSnapshot {
    private final Map<Field, Object> values;

    private BudgetStateSnapshot(Map<Field, Object> values) { this.values = values; }

    static BudgetStateSnapshot capture(Class<?> owner, String... excluded) throws ReflectiveOperationException {
        Map<Field, Object> values = new LinkedHashMap<>();
        Field[] fields = owner.getDeclaredFields();
        Arrays.sort(fields, (a, b) -> a.getName().compareTo(b.getName()));
        for (Field field : fields) {
            int flags = field.getModifiers();
            if (!Modifier.isStatic(flags) || Modifier.isFinal(flags)
                    || (!field.getType().isPrimitive() && field.getType() != String.class)
                    || Arrays.asList(excluded).contains(field.getName())) continue;
            field.setAccessible(true);
            values.put(field, field.get(null));
        }
        return new BudgetStateSnapshot(values);
    }

    void restore() throws IllegalAccessException {
        for (Map.Entry<Field, Object> value : values.entrySet()) value.getKey().set(null, value.getValue());
    }

    boolean matches() throws IllegalAccessException {
        for (Map.Entry<Field, Object> value : values.entrySet()) {
            if (!Objects.equals(value.getValue(), value.getKey().get(null))) return false;
        }
        return true;
    }

    Map<String, Object> journalValues(String... privateFields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Field, Object> value : values.entrySet()) {
            if (!Arrays.asList(privateFields).contains(value.getKey().getName())) {
                out.put(value.getKey().getName(), value.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }

    static BudgetStateSnapshot fromJournal(Class<?> owner, Map<String, Object> recorded,
                                           String... excluded) throws ReflectiveOperationException {
        BudgetStateSnapshot expected = capture(owner, excluded);
        Map<Field, Object> restored = new LinkedHashMap<>();
        for (Field field : expected.values.keySet()) {
            if (!recorded.containsKey(field.getName())) throw new IllegalArgumentException("incomplete scalar journal");
            Object value = recorded.get(field.getName());
            if (field.getType() == int.class && (value instanceof Integer || value instanceof Long)) {
                long number = ((Number) value).longValue();
                if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) value = (int) number;
            }
            if (field.getType() == long.class && (value instanceof Integer || value instanceof Long)) {
                value = ((Number) value).longValue();
            }
            if ((field.getType() == String.class && value != null && !(value instanceof String))
                    || (field.getType() == boolean.class && !(value instanceof Boolean))
                    || (field.getType() == int.class && !(value instanceof Integer))
                    || (field.getType() == long.class && !(value instanceof Long))) {
                throw new IllegalArgumentException("wrong scalar journal type");
            }
            restored.put(field, value);
        }
        if (recorded.size() != restored.size()) throw new IllegalArgumentException("unexpected scalar journal field");
        return new BudgetStateSnapshot(restored);
    }
}
