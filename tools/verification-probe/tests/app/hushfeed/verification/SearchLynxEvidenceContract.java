package app.hushfeed.verification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the same locked snapshot/redaction core used on ART, without starting an app. */
public final class SearchLynxEvidenceContract {
    private static int checks;
    private static final String SECRET = "private-user-cookie-token-video-1234567890123456789";
    private static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        safeFields();
        absentAndLate();
        nestedAndJson();
        rowsAndRecycling();
        ownClipIntersection();
        mapReadOnly();
        limitsAndFailures();
        templateRedaction(SearchLynxEvidenceContract::nativeTemplate);
        if (args.length != 0) {
            if (args.length != 1 || !"--native-template".equals(args[0])) throw new IllegalArgumentException("unsupported contract option");
            Class<?> owner = Class.forName("app.morphe.extension.tiktok.feedfilter.SearchLynxCards");
            Method method = owner.getDeclaredMethod("lynxTemplate", Object.class);
            method.setAccessible(true);
            templateRedaction(schema -> (String) method.invoke(null, schema));
        }
        System.out.println("[search-lynx-evidence] " + checks
                + " read-only/redaction contracts passed; ART and live server payloads not exercised");
    }

    private static void safeFields() throws Exception {
        Patch patch = new Patch();
        patch.data = map("is_ad", true, "ad_type", 65514, "card_type", -1,
                "raw_ad_data", new Opaque(), "text", SECRET, "account_id", SECRET,
                "creator_id", SECRET, "cookie", SECRET, "url", "https://private.example/" + SECRET);
        List<JSONObject> result = capture(patch);
        JSONObject metadata = kind(result, "row");
        require(metadata.getString("patchClass").endsWith("$Patch"), "native patch class");
        require(metadata.getString("rowClass").equals("X.NativeRow"), "native row class");
        require(metadata.getString("template").equals("fe_search_vertical_card_lynx"), "only source-observed allowlisted template identity");
        require(metadata.getJSONArray("boundsPx").toString().equals("[0,1455,1080,2623]"), "full row screenshot bounds");
        require(metadata.getJSONArray("visibleBoundsPx").toString().equals("[0,1455,1080,2400]"), "clipped visible screenshot bounds");
        require(kind(result, "snapshot").getString("visibilityBasis").equals("hierarchy-only")
                && metadata.getString("occlusion").equals("unknown"), "receipt does not claim pixel exposure through dialogs or siblings");
        JSONObject root = fields(result, "patch");
        require(!root.getBoolean("isAd") && root.getBoolean("isEcom"), "false is emitted only when observed");
        require(root.getString("rawAdData").equals("unknown:null"), "null raw ad remains unknown");
        require(root.getString("type").equals("unknown"), "missing type is unknown, not zero");
        JSONObject data = fields(result, "data");
        require(data.getBoolean("is_ad") && data.getInt("ad_type") == 65514 && data.getInt("card_type") == -1,
                "bounded primitive flags and native type codes");
        require(data.getString("raw_ad_data").equals("present"), "opaque raw ad reports presence only");
        assertPrivate(result);

        patch.data = map("is_ad", SECRET, "ad_type", 1234567890123456789L,
                "feed_type", new OpaqueNumber(), "type", 1.5, "isEcom", 2, "adData", "");
        result = capture(patch);
        data = fields(result, "data");
        require(data.getString("is_ad").equals("present:redacted"), "string flags are not echoed or coerced");
        require(data.getString("ad_type").equals("present:out-of-range"), "ID-shaped numeric value is not emitted");
        require(data.getString("feed_type").equals("present:redacted"), "custom Number methods are never invoked");
        require(data.getString("type").equals("present:redacted"), "nonintegral type is not coerced");
        require(data.getString("isEcom").equals("present:out-of-range"), "flag integer restricted to zero or one");
        require(data.getString("adData").equals("empty"), "empty presence is distinct from an ad verdict");
        assertPrivate(result);
    }

    private static void absentAndLate() throws Exception {
        require(state(capture(new Object()), "data").equals("unknown"), "missing payload is unknown");
        require(state(capture(null), "data").equals("unknown"), "missing patch is unknown");
        Patch patch = new Patch();
        require(state(capture(patch), "data").equals("unknown:null"), "not-yet-arrived data is unknown");
        patch.data = "";
        require(state(capture(patch), "data").equals("empty"), "empty string payload is explicit");
        patch.data = Collections.emptyMap();
        require(state(capture(patch), "data").equals("empty"), "empty map is explicit");
        require(fields(capture(patch), "data").getString("is_ad").equals("unknown"), "empty map is not a negative ad verdict");
        patch.data = map("is_ad", JSONObject.NULL);
        require(fields(capture(patch), "data").getString("is_ad").equals("unknown:null"), "JSON null is unknown");
        List<JSONObject> before = capture(patch);
        patch.data = map("is_ad", true);
        require(fields(capture(patch), "data").getBoolean("is_ad"), "fresh snapshot observes late payload");
        require(fields(before, "data").getString("is_ad").equals("unknown:null"), "earlier receipt is immutable");
        require(patch.isAd == Boolean.FALSE && patch.isEcom == Boolean.TRUE, "native flags are not rewritten");
    }

    private static void nestedAndJson() throws Exception {
        Patch patch = new Patch();
        patch.data = map(SECRET, Arrays.asList(map("is_ad", 1, "type", 996, "title", SECRET)),
                "data", "{\"ad_type\":2,\"text\":\"" + SECRET + "\"}");
        List<JSONObject> result = capture(patch);
        require(fields(result, "data.member[0].member[0]").getInt("is_ad") == 1, "nested maps and arrays inspected");
        require(fields(result, "data.member[0].member[0]").getInt("type") == 996, "nested type code observed");
        require(fields(result, "data.member[1]").getInt("ad_type") == 2, "only nested data strings decoded");
        assertPrivate(result);
        patch.data = "{\"unknown_private_key\":{\"is_ad\":false,\"raw_ad_data\":{}},"
                + "\"text\":\"" + SECRET + "\",\"data\":[{\"feed_type\":1}]}";
        result = capture(patch);
        require(anyField(result, "is_ad", Boolean.FALSE), "serialized JSON preserves observed false");
        require(anyField(result, "raw_ad_data", "empty"), "JSON raw-ad object emptiness observed");
        require(anyField(result, "feed_type", 1), "serialized JSON array inspected");
        require(!result.toString().contains("unknown_private_key"), "unknown JSON key names never printed");
        assertPrivate(result);
        patch.data = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("is_ad", true)));
        require(anyField(capture(patch), "is_ad", Boolean.TRUE), "native JSONObject and JSONArray supported");
        patch.data = "{not valid json";
        require(state(capture(patch), "data").startsWith("unknown:"), "malformed payload never implies no ad");
        patch.data = new Opaque();
        require(state(capture(patch), "data").equals("present:unsupported"), "unknown payload models not stringified");
    }

    private static void rowsAndRecycling() throws Exception {
        Map<Object, Object> bound = new LinkedHashMap<>();
        Object key = new Object();
        Patch patch = new Patch();
        bound.put(key, patch);
        for (int invisible = 0; invisible < 8; invisible++) {
            final int condition = invisible;
            List<JSONObject> result = snapshot(bound, ignored -> geometry(condition), schema -> {
                throw new AssertionError("invisible rows must not read template data");
            });
            require(kind(result, "snapshot").getInt("visibleRows") == 0, "invisible/detached/zero row skipped " + invisible);
        }
        List<JSONObject> emptyClip = snapshot(bound, ignored -> clippedGeometry(new int[]{0, 0, 0, 20}), schema -> {
            throw new AssertionError("empty self-clipped rows must not read template data");
        });
        require(kind(emptyClip, "snapshot").getInt("visibleRows") == 0, "empty own clip bounds exclude row");
        List<JSONObject> partialClip = snapshot(bound, ignored -> clippedGeometry(new int[]{10, 20, 200, 300}), schema -> "poi_card");
        JSONObject clipRow = kind(partialClip, "row");
        require(clipRow.getJSONArray("selfClipBoundsLocalPx").toString().equals("[10,20,200,300]"),
                "nonempty own clip is reported in its actual local coordinate frame");
        require(clipRow.getString("visibleBoundsPxBasis").equals("hierarchy-global-rect-not-pixel-exposure"),
                "hierarchy bounds never claim full exposed pixels despite self-clipping");
        List<JSONObject> rebound = snapshot(bound, new SearchLynxEvidence.Rows() {
            int calls;
            @Override public SearchLynxEvidence.Row read(Object row) {
                require(Thread.holdsLock(LOCK), "all row reads under exact registry lock");
                if (++calls == 2) bound.put(key, new Patch());
                return geometry(-1);
            }
        }, schema -> "fe_search_vertical_card_lynx");
        require(kind(rebound, "snapshot").getInt("changedRowsSkipped") == 1, "rebound row discarded after extraction");
        require(rebound.size() == 1, "old patch fields never published for recycled row");
        int[] calls = {0};
        List<JSONObject> hidden = snapshot(bound, ignored -> ++calls[0] == 1 ? geometry(-1) : geometry(1), schema -> "template");
        require(hidden.size() == 1 && kind(hidden, "snapshot").getInt("changedRowsSkipped") == 1, "row hidden during observation discarded");
        List<JSONObject> badTemplate = snapshot(bound, ignored -> geometry(-1), schema -> "https://private.example/" + SECRET);
        require(kind(badTemplate, "row").getString("templateState").equals("unknown"), "template return value validated again");
        assertPrivate(badTemplate);
        require(bound.size() == 1, "snapshot never removes registry entries");
    }

    private static void limitsAndFailures() throws Exception {
        Patch patch = new Patch();
        Map<Object, Object> cycle = map("is_ad", true);
        cycle.put("data", cycle);
        patch.data = cycle;
        require(capture(patch).toString().contains("unknown:cycle"), "cyclic map bounded by identity without hashCode");
        Map<Object, Object> chain = map("is_ad", true);
        for (int i = 0; i < 8; i++) chain = map("data", chain);
        patch.data = chain;
        require(capture(patch).toString().contains("unknown:traversal-limit"), "depth exhaustion explicit");
        List<Object> wide = new ArrayList<>();
        for (int i = 0; i < 100; i++) wide.add(map("is_ad", false));
        patch.data = wide;
        List<JSONObject> bounded = capture(patch);
        require(bounded.size() < SearchLynxEvidence.MAX_NODES * 2 + 4, "node/output count bounded");
        require(bounded.toString().contains("unknown:traversal-limit"), "wide traversal limit explicit");
        patch.data = "[".repeat(17) + "0" + "]".repeat(17);
        require(state(capture(patch), "data").equals("unknown:json-not-bounded"), "parser nesting bounded before parse");
        patch.data = "{\"text\":\"" + "a".repeat(SearchLynxEvidence.MAX_JSON_CHARS) + "\"}";
        require(state(capture(patch), "data").equals("unknown:json-not-bounded"), "parser input characters bounded");
        require(!SearchLynxEvidence.boundedJson("{'data':'\"'}"), "non-JSON quote syntax cannot defeat parser preflight");
        require(SearchLynxEvidence.boundedJson("{\"text\":\"[{}]\\\"\"}"), "braces and escaped quotes in strings ignored");
        String hiddenArrays = "[".repeat(17) + "0" + "]".repeat(17);
        require(!SearchLynxEvidence.boundedJson("[/*\"*/" + hiddenArrays + "/*\"*/]"),
                "Android comments cannot conceal parser nesting");
        require(!SearchLynxEvidence.boundedJson("[x\"," + hiddenArrays + ",x\"]"),
                "Android unquoted literals cannot conceal parser nesting");
        for (String invalid : new String[]{"{a:1}", "[foo]", "[truefalse]", "[NaN]", "[+1]", "[01]", "[1.]", "[.1]",
                "[1e]", "[1e+]", "[1,]", "{\"a\":1,}", "[\"bad\\q\"]", "[\"bad\\u00xz\"]", "[\"raw\nline\"]",
                "{\"is_ad\":true/*comment*/}", "[1;//comment\n2]", "[1] trailing"}) {
            require(!SearchLynxEvidence.boundedJson(invalid), "non-JSON grammar is rejected before Android parsing");
        }
        for (String valid : new String[]{"{}", "[]", "[0,-1,2.5,1e+2,-1.5E-3,true,false,null]",
                " {\"is_ad\":true,\"escaped\":\"\\u0041\\n\\t\\/\\\\\\\"\"} \r\n",
                "[".repeat(16) + "0" + "]".repeat(16), "[" + "{},".repeat(254) + "{}]"}) {
            require(SearchLynxEvidence.boundedJson(valid), "strict JSON remains accepted within exact input bounds");
        }
        require(!SearchLynxEvidence.boundedJson("[" + "{},".repeat(255) + "{}]"), "parser container count bounded before allocation");
        Map<Object, Object> many = new LinkedHashMap<>();
        for (int i = 0; i < SearchLynxEvidence.MAX_ROWS + 1; i++) many.put(new Object(), new Patch());
        JSONObject header = kind(snapshot(many, ignored -> geometry(-1), schema -> "template"), "snapshot");
        require(header.getInt("visibleRows") == SearchLynxEvidence.MAX_ROWS && header.getBoolean("limited"), "visible row bound explicit");
        many.clear();
        for (int i = 0; i < SearchLynxEvidence.MAX_BOUND + 1; i++) many.put(new Object(), new Patch());
        header = kind(snapshot(many, ignored -> geometry(0), schema -> "template"), "snapshot");
        require(header.getInt("scanned") == SearchLynxEvidence.MAX_BOUND && header.getBoolean("limited"), "invisible registry scan bounded");
        List<JSONObject> failed = snapshot(Collections.singletonMap(new Object(), new Patch()), ignored -> {
            throw new IllegalStateException(SECRET);
        }, schema -> "template");
        require(kind(failed, "snapshot").getInt("unreadableRows") == 1, "native failure recorded without message");
        assertPrivate(failed);
    }

    private static void mapReadOnly() throws Exception {
        Map<Object, Object> data = new LinkedHashMap<>(16, 0.75f, true);
        data.put("is_ad", true);
        data.put("other", SECRET);
        List<Object> order = new ArrayList<>(data.keySet());
        Patch patch = new Patch();
        patch.data = data;
        require(fields(capture(patch), "data").getBoolean("is_ad"), "access-order map field is observed");
        require(new ArrayList<>(data.keySet()).equals(order), "payload access-order map is not mutated by observation");
        Map<Object, Object> bound = new LinkedHashMap<>(16, 0.75f, true);
        bound.put(new Object(), patch);
        bound.put(new Object(), new Patch());
        order = new ArrayList<>(bound.keySet());
        JSONObject header = kind(snapshot(bound, ignored -> geometry(-1), schema -> "poi_card"), "snapshot");
        require(header.getInt("visibleRows") == 2, "access-order registry can be read without iterator invalidation");
        require(new ArrayList<>(bound.keySet()).equals(order), "registry identity check does not mutate access order");
        Map<Object, Object> longMap = new LinkedHashMap<>();
        for (int i = 0; i < SearchLynxEvidence.MAX_MEMBERS; i++) longMap.put("private-key-" + i, SECRET);
        longMap.put("is_ad", true);
        patch.data = longMap;
        require(fields(capture(patch), "data").getString("is_ad").equals("unknown:limited"),
                "field beyond bounded map scan is unknown, not absent or false");
    }

    private static void ownClipIntersection() throws Exception {
        int[][] clips = {
                {200, 200, 300, 300}, {-200, -200, -100, -100}, {100, 0, 120, 100},
                {0, 0, 40, 100}, {0, 80, 100, 100}, {40, 0, 50, 100}
        };
        int[][] localVisible = {
                {0, 0, 100, 100}, {0, 0, 100, 100}, {0, 0, 100, 100},
                {50, 0, 100, 100}, {0, 0, 100, 50}, {50, 0, 100, 100}
        };
        for (int i = 0; i < clips.length; i++) {
            final int fixture = i;
            List<JSONObject> result = snapshot(Collections.singletonMap(new Object(), new Patch()),
                    ignored -> smallClippedGeometry(clips[fixture], localVisible[fixture]), schema -> {
                        throw new AssertionError("clip with no drawable intersection must not read template data");
                    });
            require(kind(result, "snapshot").getInt("visibleRows") == 0,
                    "own clip must intersect both row and local visible rectangle " + i);
        }
        int[][] acceptedClips = {{25, 25, 80, 80}, {-20, -20, 120, 120}, null};
        int[][] acceptedVisible = {{50, 0, 100, 60}, {0, 0, 100, 100}, {-20, -20, 120, 120}};
        String[] expected = {"[50,25,80,60]", "[0,0,100,100]", "[0,0,100,100]"};
        for (int i = 0; i < acceptedClips.length; i++) {
            final int fixture = i;
            List<JSONObject> result = snapshot(Collections.singletonMap(new Object(), new Patch()),
                    ignored -> smallClippedGeometry(acceptedClips[fixture], acceptedVisible[fixture]), schema -> "poi_card");
            JSONObject row = kind(result, "row");
            require(row.getJSONArray("eligibleBoundsLocalPx").toString().equals(expected[i]),
                    "reported eligible local bounds are the exact bounded intersection");
            require(row.getString("occlusion").equals("unknown"), "drawable intersection does not claim sibling/dialog pixel exposure");
        }
        List<JSONObject> outsideVisible = snapshot(Collections.singletonMap(new Object(), new Patch()),
                ignored -> smallClippedGeometry(null, new int[]{120, 0, 180, 100}), schema -> {
                    throw new AssertionError("local visible bounds outside row must not read template data");
                });
        require(kind(outsideVisible, "snapshot").getInt("visibleRows") == 0, "local visibility must intersect row even without a self clip");
        int[] reads = {0};
        List<JSONObject> clippedDuringRead = snapshot(Collections.singletonMap(new Object(), new Patch()),
                ignored -> smallClippedGeometry(new int[]{25, 25, 80, 80},
                        ++reads[0] == 1 ? new int[]{0, 0, 100, 100} : new int[]{80, 0, 100, 100}), schema -> "poi_card");
        require(kind(clippedDuringRead, "snapshot").getInt("changedRowsSkipped") == 1 && clippedDuringRead.size() == 1,
                "new local clipping during extraction cannot publish stale patch evidence");
    }

    private static void templateRedaction(SearchLynxEvidence.Templates extraction) throws Exception {
        String[][] known = {
                {"sslocal://lynxview?channel=fe_search_vertical_card_lynx&keyword=" + SECRET, "fe_search_vertical_card_lynx"},
                {"sslocal://lynxview?channel=poi_card", "poi_card"},
                {"sslocal://lynxview?surl=https%3A%2F%2Fprivate.example%2Fstatic%2Fpoi_card%2Ftemplate.js%3Ftoken%3D" + SECRET, "poi_card"},
                {"sslocal://lynxview?url=https%3A%2F%2Fprivate.example%2Fstatic%2Fpoi_card%2Ftemplate.js", "poi_card"}
        };
        for (String[] fixture : known) {
            require(fixture[1].equals(extraction.read(fixture[0])), "known identity follows the native extraction path");
            List<JSONObject> result = snapshot(Collections.singletonMap(new Object(), new Patch(fixture[0])),
                    ignored -> geometry(-1), extraction);
            JSONObject metadata = kind(result, "row");
            require(metadata.getString("templateState").equals("known")
                    && metadata.getString("template").equals(fixture[1]), "closed static template remains useful");
            assertPrivate(result);
        }
        String[] privateChannels = {
                "private_cookie_token_1234567890123456789", "1234567890123456789",
                "hotels_new_york", "hotels%20new%20york", "another_static_but_unobserved_template",
                "fe_search_vertical_card_lynx_private_cookie_token_1234567890123456789",
                "fe_search_vertical_card_lynx%3Ftoken%3Dprivate_cookie_token_1234567890123456789"
        };
        for (String channel : privateChannels) {
            String schema = "sslocal://lynxview?channel=" + channel;
            String candidate = extraction.read(schema);
            require(candidate != null && candidate.matches("[A-Za-z0-9_-]{1,60}"), "private ASCII survives the native sanitizer");
            unknownTemplate(schema, candidate, extraction);
        }
        String privateFolder = "private_cookie_token_1234567890123456789";
        String schema = "sslocal://lynxview?surl=https%3A%2F%2Fprivate.example%2F" + privateFolder + "%2Ftemplate.js";
        require(privateFolder.equals(extraction.read(schema)), "native fallback can also contain private ASCII");
        unknownTemplate(schema, privateFolder, extraction);
        unknownTemplate("sslocal://lynxview?keyword=" + SECRET, null, extraction);
    }

    private static void unknownTemplate(String schema, String candidate, SearchLynxEvidence.Templates extraction) throws Exception {
        List<JSONObject> result = snapshot(Collections.singletonMap(new Object(), new Patch(schema)),
                ignored -> geometry(-1), extraction);
        JSONObject metadata = kind(result, "row");
        require(metadata.getString("templateState").equals("unknown") && !metadata.has("template"),
                "non-allowlisted identity remains unknown without any value");
        if (candidate != null) require(!result.toString().contains(candidate), "sanitized or truncated private candidate never emitted");
        assertPrivate(result);
    }

    /** Mirrors the native helper, including decoding, fallback, character stripping and truncation.
     * --native-template repeats these fixtures against an existing compiled SearchLynxCards class. */
    private static String nativeTemplate(Object schema) {
        if (!(schema instanceof String)) return null;
        String value = nativeParameter((String) schema, "channel");
        if (value == null) {
            String url = nativeParameter((String) schema, "surl");
            if (url == null) url = nativeParameter((String) schema, "url");
            if (url != null) {
                int cut = url.indexOf('?');
                String path = cut >= 0 ? url.substring(0, cut) : url;
                String[] parts = path.split("/");
                value = parts.length >= 2 ? parts[parts.length - 2] : null;
            }
        }
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (out.length() == 60) break;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') out.append(c);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static String nativeParameter(String schema, String name) {
        int query = schema.indexOf('?');
        if (query < 0) return null;
        for (String pair : schema.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !pair.substring(0, eq).equals(name)) continue;
            try { return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8"); }
            catch (Exception badEncoding) { return pair.substring(eq + 1); }
        }
        return null;
    }

    private static List<JSONObject> capture(Object patch) throws Exception {
        return snapshot(Collections.singletonMap(new Object(), patch), ignored -> {
            require(Thread.holdsLock(LOCK), "row read under lock");
            return geometry(-1);
        }, schema -> {
            require(Thread.holdsLock(LOCK), "template read under lock");
            return "fe_search_vertical_card_lynx";
        });
    }

    private static List<JSONObject> snapshot(Map<?, ?> bound, SearchLynxEvidence.Rows rows,
                                            SearchLynxEvidence.Templates template) throws Exception {
        List<String> lines = SearchLynxEvidence.snapshot(LOCK, bound, rows, template, 1234, 5678);
        List<JSONObject> result = new ArrayList<>();
        for (String line : lines) {
            require(line.length() < 3000, "each complete log line below logcat truncation size");
            JSONObject value = new JSONObject(line);
            require(value.getString("marker").equals("lynx-4d2"), "same generated marker on every receipt line");
            result.add(value);
        }
        return result;
    }

    private static SearchLynxEvidence.Row geometry(int hidden) {
        return new SearchLynxEvidence.Row("X.NativeRow", Collections.singletonList("X.NativeList"),
                hidden != 0, hidden != 1, hidden != 2, hidden != 3, hidden == 4 ? 0f : 1f,
                0, 1455, hidden == 5 ? 0 : 1080, hidden == 6 ? 0 : 1168,
                0, 1455, hidden == 7 ? 0 : 1080, 2400, null, new int[]{0, 0, 1080, 945});
    }

    private static SearchLynxEvidence.Row clippedGeometry(int[] selfClip) {
        return new SearchLynxEvidence.Row("X.NativeRow", Collections.singletonList("X.NativeList"),
                true, true, true, true, 1f, 0, 1455, 1080, 1168, 0, 1455, 1080, 2400, selfClip, new int[]{0, 0, 1080, 945});
    }

    private static SearchLynxEvidence.Row smallClippedGeometry(int[] selfClip, int[] localVisible) {
        return new SearchLynxEvidence.Row("X.NativeRow", Collections.singletonList("X.NativeList"),
                true, true, true, true, 1f, 20, 30, 100, 100,
                20 + localVisible[0], 30 + localVisible[1], 20 + localVisible[2], 30 + localVisible[3], selfClip, localVisible);
    }

    private static JSONObject kind(List<JSONObject> values, String kind) throws Exception {
        for (JSONObject value : values) if (kind.equals(value.optString("kind"))) return value;
        throw new AssertionError("missing " + kind);
    }

    private static JSONObject fields(List<JSONObject> values, String path) throws Exception {
        for (JSONObject value : values) {
            if ("fields".equals(value.optString("kind")) && path.equals(value.optString("path"))) return value.getJSONObject("fields");
        }
        throw new AssertionError("missing fields " + path);
    }

    private static String state(List<JSONObject> values, String path) throws Exception {
        for (JSONObject value : values) if (path.equals(value.optString("path"))) return value.getString("status");
        throw new AssertionError("missing state " + path);
    }

    private static boolean anyField(List<JSONObject> values, String name, Object expected) throws Exception {
        for (JSONObject value : values) {
            if (value.has("fields") && expected.equals(value.getJSONObject("fields").opt(name))) return true;
        }
        return false;
    }

    private static Map<Object, Object> map(Object... items) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) out.put(items[i], items[i + 1]);
        return out;
    }

    private static void assertPrivate(List<JSONObject> result) {
        String text = result.toString();
        for (String disallowed : new String[]{SECRET, "https://", "private.example", "account_id", "creator_id", "cookie", "text\"", "title\""}) {
            require(!text.contains(disallowed), "private content/key/URL absent");
        }
    }

    private static class ParentPatch { final Boolean isEcom = Boolean.TRUE; }
    private static final class Patch extends ParentPatch {
        final String schema;
        final Boolean isAd = Boolean.FALSE;
        final Object rawAdData = null;
        Object data;
        Patch() { this("sslocal://lynxview?channel=fe_search_vertical_card_lynx&keyword=" + SECRET); }
        Patch(String schema) { this.schema = schema; }
        @Override public String toString() { throw new AssertionError("model content must not be read"); }
    }
    private static final class Opaque {
        @Override public String toString() { throw new AssertionError("opaque content must not be read"); }
        @Override public int hashCode() { throw new AssertionError("no model hashes"); }
        @Override public boolean equals(Object value) { throw new AssertionError("no model equality"); }
    }
    private static final class OpaqueNumber extends Number {
        @Override public int intValue() { throw new AssertionError("no arbitrary numeric coercion"); }
        @Override public long longValue() { throw new AssertionError("no arbitrary numeric coercion"); }
        @Override public float floatValue() { throw new AssertionError("no arbitrary numeric coercion"); }
        @Override public double doubleValue() { throw new AssertionError("no arbitrary numeric coercion"); }
        @Override public String toString() { throw new AssertionError("no numeric stringification"); }
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
}
