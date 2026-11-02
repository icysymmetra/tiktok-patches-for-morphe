package app.hushfeed.verification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Read-only, closed-vocabulary evidence. No model stringification or ad classification. */
final class SearchLynxEvidence {
    static final int MAX_ROWS = 8, MAX_BOUND = 128, MAX_NODES = 16, MAX_DEPTH = 4;
    static final int MAX_MEMBERS = 64, MAX_JSON_CHARS = 65_536;
    private static final int MAX_JSON_DEPTH = 16, MAX_JSON_CONTAINERS = 256;
    private static final String[] FLAGS = {
            "isAd", "is_ad", "isAdOrContainAd", "is_ad_or_contain_ad", "isEcom", "is_ecom"};
    private static final String[] TYPES = {
            "adType", "ad_type", "feedType", "feed_type", "cardType", "card_type", "type"};
    private static final String[] PRESENCE = {"rawAdData", "raw_ad_data", "adData", "ad_data"};
    private static final Object MISSING = new Object(), UNREADABLE = new Object(), LIMITED = new Object();

    interface Rows { Row read(Object row); }
    interface Templates { String read(Object schema) throws ReflectiveOperationException; }

    /** Only public view geometry and native class names, never tags, text or model identity. */
    static final class Row {
        final String type;
        final List<String> parents;
        final boolean attached, shown, windowVisible, clipped;
        final float alpha;
        final int x, y, width, height, left, top, right, bottom;
        final int[] selfClip, localVisible, eligibleLocal;

        Row(String type, List<String> parents, boolean attached, boolean shown,
            boolean windowVisible, boolean clipped, float alpha, int x, int y, int width,
            int height, int left, int top, int right, int bottom, int[] selfClip, int[] localVisible) {
            this.type = type; this.parents = parents; this.attached = attached; this.shown = shown;
            this.windowVisible = windowVisible; this.clipped = clipped; this.alpha = alpha;
            this.x = x; this.y = y; this.width = width; this.height = height;
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.selfClip = selfClip == null ? null : selfClip.clone();
            this.localVisible = localVisible == null ? null : localVisible.clone();
            this.eligibleLocal = intersection(width, height, this.selfClip, this.localVisible);
        }

        boolean visible() {
            return attached && shown && windowVisible && clipped && alpha > 0f
                    && width > 0 && height > 0 && right > left && bottom > top
                    && eligibleLocal != null;
        }

        private static int[] intersection(int width, int height, int[] clip, int[] visible) {
            if (visible == null || visible.length != 4 || (clip != null && clip.length != 4)) return null;
            int left = Math.max(0, visible[0]), top = Math.max(0, visible[1]);
            int right = Math.min(width, visible[2]), bottom = Math.min(height, visible[3]);
            if (clip != null) {
                left = Math.max(left, clip[0]); top = Math.max(top, clip[1]);
                right = Math.min(right, clip[2]); bottom = Math.min(bottom, clip[3]);
            }
            return right > left && bottom > top ? new int[]{left, top, right, bottom} : null;
        }

        boolean sameGeometry(Row other) {
            return other != null && other.visible() && x == other.x && y == other.y
                    && width == other.width && height == other.height && left == other.left
                    && top == other.top && right == other.right && bottom == other.bottom
                    && java.util.Arrays.equals(selfClip, other.selfClip)
                    && java.util.Arrays.equals(localVisible, other.localVisible);
        }
    }

    private SearchLynxEvidence() {}

    static List<String> snapshot(Object lock, Map<?, ?> bound, Rows rows, Templates templates,
                                 long uptime, long epochMillis) {
        String marker = "lynx-" + Long.toHexString(uptime);
        List<String> out = new ArrayList<>();
        int scanned = 0, included = 0, changed = 0, unreadable = 0;
        boolean limited = false;
        try {
            synchronized (lock) {
                for (Map.Entry<?, ?> entry : bound.entrySet()) {
                    if (scanned == MAX_BOUND || included == MAX_ROWS) { limited = true; break; }
                    scanned++;
                    Object key = entry.getKey(), patch = entry.getValue();
                    try {
                        Row row = rows.read(key);
                        if (row == null || !row.visible()) continue;
                        if (!sameBinding(bound, key, patch)) { changed++; continue; }
                        List<String> candidate = row(marker, included, row, patch, templates);
                        // A recycled/rebound row cannot publish evidence for its old patch.
                        Row current = rows.read(key);
                        if (!sameBinding(bound, key, patch) || !row.sameGeometry(current)) {
                            changed++;
                            continue;
                        }
                        out.addAll(candidate);
                        included++;
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        // Native exception messages and toString implementations can contain content.
                        unreadable++;
                    }
                }
            }
            out.add(0, base(marker, "snapshot").put("schema", 1).put("epochMillis", epochMillis)
                    .put("source", "SearchLynxCards.BOUND").put("scanned", scanned)
                    .put("visibleRows", included).put("changedRowsSkipped", changed)
                    .put("visibilityBasis", "hierarchy-only").put("occlusion", "unknown")
                    .put("unreadableRows", unreadable).put("limited", limited)
                    .put("missingFields", "unknown").put("maxRows", MAX_ROWS)
                    .put("maxBoundRows", MAX_BOUND).put("maxPayloadNodes", MAX_NODES)
                    .put("maxPayloadDepth", MAX_DEPTH).put("maxMembersPerNode", MAX_MEMBERS)
                    .put("maxJsonChars", MAX_JSON_CHARS).put("maxJsonDepth", MAX_JSON_DEPTH)
                    .put("maxJsonContainers", MAX_JSON_CONTAINERS).toString());
        } catch (JSONException | RuntimeException | LinkageError failure) {
            // Do not return a partial registry snapshot as if it were complete.
            out.clear();
            out.add("{\"schema\":1,\"kind\":\"snapshot\",\"marker\":\"" + marker
                    + "\",\"status\":\"unavailable\",\"missingFields\":\"unknown\"}");
        }
        return out;
    }

    private static boolean sameBinding(Map<?, ?> bound, Object key, Object patch) {
        int scanned = 0;
        for (Map.Entry<?, ?> entry : bound.entrySet()) {
            if (scanned++ == MAX_BOUND) return false;
            if (entry.getKey() == key) return entry.getValue() == patch;
        }
        return false;
    }

    private static List<String> row(String marker, int slot, Row row, Object patch, Templates templates)
            throws JSONException, ReflectiveOperationException {
        List<String> out = new ArrayList<>();
        JSONArray parents = new JSONArray();
        for (int i = 0; i < row.parents.size() && i < 8; i++) parents.put(className(row.parents.get(i)));
        Object schema = field(patch, "schema");
        String template = schema instanceof String && ((String) schema).length() <= MAX_JSON_CHARS
                ? knownTemplate(templates.read(schema)) : null;
        JSONObject meta = base(marker, "row").put("row", slot).put("rowClass", className(row.type))
                .put("patchClass", patch == null ? "unknown" : className(patch.getClass().getName()))
                .put("parentClasses", parents).put("boundsPx", rectangle(row.x, row.y,
                        row.x + row.width, row.y + row.height))
                .put("boundsPxBasis", "screen-origin-layout-size")
                .put("visibleBoundsPx", rectangle(row.left, row.top, row.right, row.bottom))
                .put("visibleBoundsPxBasis", "hierarchy-global-rect-not-pixel-exposure")
                .put("localVisibleBoundsPx", rectangle(row.localVisible[0], row.localVisible[1], row.localVisible[2], row.localVisible[3]))
                .put("eligibleBoundsLocalPx", rectangle(row.eligibleLocal[0], row.eligibleLocal[1], row.eligibleLocal[2], row.eligibleLocal[3]))
                .put("eligibleBoundsBasis", "row-local-visible-and-self-clip-intersection")
                .put("occlusion", "unknown").put("selfClipState", row.selfClip == null ? "none" : "present")
                .put("templateState", template != null ? "known" : "unknown");
        if (row.selfClip != null) meta.put("selfClipBoundsLocalPx",
                rectangle(row.selfClip[0], row.selfClip[1], row.selfClip[2], row.selfClip[3]));
        if (template != null) meta.put("template", template);
        out.add(meta.toString());
        out.add(node(marker, slot, "patch", patch, "model").toString());
        Object data = field(patch, "data");
        new Payload(marker, slot, out).visit(data, "data", 0);
        return out;
    }

    private static String knownTemplate(String candidate) {
        // The captured hotel card names this channel and poi_card/template.js. lynxTemplate
        // returns the channel, or the URL's parent folder when no channel exists. Its character
        // sanitizer is NOT a privacy boundary: ASCII search terms and tokens survive it.
        // Return only these observed static identities, never the supplied string or its hash.
        if ("fe_search_vertical_card_lynx".equals(candidate)) return "fe_search_vertical_card_lynx";
        if ("poi_card".equals(candidate)) return "poi_card";
        return null;
    }

    private static JSONArray rectangle(int left, int top, int right, int bottom) {
        return new JSONArray().put(left).put(top).put(right).put(bottom);
    }

    private static JSONObject base(String marker, String kind) throws JSONException {
        return new JSONObject().put("marker", marker).put("kind", kind);
    }

    private static JSONObject node(String marker, int row, String path, Object value, String status)
            throws JSONException {
        JSONObject fields = new JSONObject();
        for (String name : FLAGS) fields.put(name, scalar(member(value, name), true));
        for (String name : TYPES) fields.put(name, scalar(member(value, name), false));
        for (String name : PRESENCE) fields.put(name, presence(member(value, name)));
        return base(marker, "fields").put("row", row).put("path", path)
                .put("status", status).put("fields", fields);
    }

    private static Object scalar(Object value, boolean flag) {
        String absent = absent(value);
        if (absent != null) return absent;
        if (value instanceof Boolean && flag) return value;
        // Never coerce strings or arbitrary Number implementations. A type code is not an ID.
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long number = ((Number) value).longValue();
            if (flag ? number == 0 || number == 1 : number >= -1 && number <= 65_535) return number;
            return "present:out-of-range";
        }
        return "present:redacted";
    }

    private static String presence(Object value) {
        String absent = absent(value);
        if (absent != null) return absent;
        if (value instanceof String && ((String) value).isEmpty()) return "empty";
        if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) return "empty";
        if (value instanceof List && ((List<?>) value).isEmpty()) return "empty";
        if (value instanceof JSONObject && ((JSONObject) value).length() == 0) return "empty";
        if (value instanceof JSONArray && ((JSONArray) value).length() == 0) return "empty";
        return "present";
    }

    private static String absent(Object value) {
        if (value == MISSING) return "unknown";
        if (value == UNREADABLE) return "unknown:unreadable";
        if (value == LIMITED) return "unknown:limited";
        if (value == null || value == JSONObject.NULL) return "unknown:null";
        return null;
    }

    private static Object member(Object value, String name) {
        if (value == null || value == MISSING || value == UNREADABLE || value == LIMITED || value == JSONObject.NULL) return MISSING;
        try {
            if (value instanceof Map) {
                // Even Map.get can mutate a native access-order LinkedHashMap. Entry iteration
                // observes at most the declared member budget without touching its access order.
                int scanned = 0;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (scanned++ == MAX_MEMBERS) return LIMITED;
                    if (name.equals(entry.getKey())) return entry.getValue();
                }
                return MISSING;
            }
            if (value instanceof JSONObject) {
                JSONObject json = (JSONObject) value;
                return json.has(name) ? json.opt(name) : MISSING;
            }
            return field(value, name);
        } catch (RuntimeException | LinkageError failure) { return UNREADABLE; }
    }

    private static Object field(Object object, String name) {
        if (object == null) return MISSING;
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                if (Modifier.isStatic(field.getModifiers())) return MISSING;
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException missing) {
                // Only these exact field names, including inherited fields. No getters.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return UNREADABLE; }
        }
        return MISSING;
    }

    private static String className(String name) {
        return name != null && name.matches("[A-Za-z_$][A-Za-z0-9_.$]{0,255}") ? name : "unknown";
    }

    private static final class Payload {
        final String marker;
        final int row;
        final List<String> out;
        final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        int nodes;

        Payload(String marker, int row, List<String> out) { this.marker = marker; this.row = row; this.out = out; }

        void visit(Object value, String path, int depth) throws JSONException {
            if (nodes == MAX_NODES) return;
            nodes++;
            String missing = absent(value);
            if (missing != null) { status(path, missing); return; }
            if (value instanceof String) {
                String text = (String) value;
                if (text.isEmpty()) { status(path, "empty"); return; }
                if (!boundedJson(text)) { status(path, "unknown:json-not-bounded"); return; }
                try {
                    value = text.trim().startsWith("{") ? new JSONObject(text) : new JSONArray(text);
                } catch (JSONException | RuntimeException failure) { status(path, "unknown:invalid-json"); return; }
            }
            boolean object = value instanceof Map || value instanceof JSONObject;
            boolean array = value instanceof List || value instanceof JSONArray;
            if (!object && !array) { status(path, "present:unsupported"); return; }
            if (seen.put(value, Boolean.TRUE) != null) { status(path, "unknown:cycle"); return; }
            int count = value instanceof Map ? ((Map<?, ?>) value).size()
                    : value instanceof JSONObject ? ((JSONObject) value).length()
                    : value instanceof List ? ((List<?>) value).size() : ((JSONArray) value).length();
            JSONObject report = node(marker, row, path, value, count == 0 ? "empty" : object ? "object" : "array")
                    .put("memberCount", Math.min(count, MAX_MEMBERS))
                    .put("membersLimited", count > MAX_MEMBERS);
            out.add(report.toString());
            if (depth == MAX_DEPTH || nodes == MAX_NODES) {
                if (count > 0) status(path, "unknown:traversal-limit");
                return;
            }
            Iterator<?> members = value instanceof Map ? ((Map<?, ?>) value).entrySet().iterator()
                    : value instanceof JSONObject ? ((JSONObject) value).keys() : null;
            int index = 0;
            while (index < count && index < MAX_MEMBERS && nodes < MAX_NODES) {
                Object child;
                String name = null;
                if (value instanceof Map) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>) members.next();
                    name = entry.getKey() instanceof String ? (String) entry.getKey() : null;
                    child = entry.getValue();
                } else if (value instanceof JSONObject) {
                    name = (String) members.next();
                    child = ((JSONObject) value).opt(name);
                } else child = value instanceof List ? ((List<?>) value).get(index) : ((JSONArray) value).opt(index);
                // Unknown member names can themselves be personal identifiers. Use positions only.
                String childPath = path + ".member[" + index++ + "]";
                if (child instanceof Map || child instanceof JSONObject || child instanceof List || child instanceof JSONArray
                        || ("data".equals(name) && child instanceof String)) visit(child, childPath, depth + 1);
            }
            if (index < count) status(path, "unknown:traversal-limit");
        }

        private void status(String path, String status) throws JSONException {
            out.add(base(marker, "payload-state").put("row", row).put("path", path).put("status", status)
                    .put("missingFields", "unknown").toString());
        }
    }

    /** Android org.json accepts comments, unquoted literals and single quotes. Accept only strict
     * JSON grammar here so none of those can hide nesting from the preflight depth counter. */
    static boolean boundedJson(String text) {
        if (text.length() > MAX_JSON_CHARS) return false;
        JsonBoundary parser = new JsonBoundary(text);
        parser.space();
        char first = parser.peek();
        if ((first != '{' && first != '[') || !parser.value(0)) return false;
        parser.space();
        return parser.at == text.length();
    }

    private static final class JsonBoundary {
        final String text;
        int at, containers;
        JsonBoundary(String text) { this.text = text; }

        char peek() { return at < text.length() ? text.charAt(at) : '\0'; }

        void space() {
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
                at++;
            }
        }

        boolean value(int depth) {
            space();
            char c = peek();
            if (c == '{' || c == '[') return container(depth + 1, c == '{');
            if (c == '"') return string();
            if (c == 't') return literal("true");
            if (c == 'f') return literal("false");
            if (c == 'n') return literal("null");
            return number();
        }

        boolean container(int depth, boolean object) {
            if (depth > MAX_JSON_DEPTH || ++containers > MAX_JSON_CONTAINERS) return false;
            at++;
            space();
            char close = object ? '}' : ']';
            if (peek() == close) { at++; return true; }
            while (at < text.length()) {
                if (object) {
                    if (!string()) return false;
                    space();
                    if (peek() != ':') return false;
                    at++;
                }
                if (!value(depth)) return false;
                space();
                if (peek() == close) { at++; return true; }
                if (peek() != ',') return false;
                at++;
                space();
            }
            return false;
        }

        boolean string() {
            if (peek() != '"') return false;
            at++;
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') return true;
                if (c < 0x20) return false;
                if (c != '\\') continue;
                if (at == text.length()) return false;
                c = text.charAt(at++);
                if (c == 'u') {
                    for (int i = 0; i < 4; i++) {
                        if (at == text.length()) return false;
                        c = text.charAt(at++);
                        if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f') && !(c >= 'A' && c <= 'F')) return false;
                    }
                } else if (c != '"' && c != '\\' && c != '/' && c != 'b' && c != 'f' && c != 'n' && c != 'r' && c != 't') return false;
            }
            return false;
        }

        boolean literal(String value) {
            if (!text.startsWith(value, at)) return false;
            at += value.length();
            return true;
        }

        boolean number() {
            if (peek() == '-') at++;
            if (peek() == '0') at++;
            else {
                if (peek() < '1' || peek() > '9') return false;
                digits();
            }
            if (peek() == '.') { at++; if (!digits()) return false; }
            if (peek() == 'e' || peek() == 'E') {
                at++;
                if (peek() == '+' || peek() == '-') at++;
                if (!digits()) return false;
            }
            return true;
        }

        boolean digits() {
            int start = at;
            while (peek() >= '0' && peek() <= '9') at++;
            return at != start;
        }
    }
}
