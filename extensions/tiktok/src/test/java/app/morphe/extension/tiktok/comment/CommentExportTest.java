package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.DocumentExportProvider;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.DocumentOperation;
import app.morphe.extension.tiktok.settings.preference.DocumentOperationTest;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** What Export comments writes, when it shows, and what it does with a file app that hangs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Author {
        public final String uniqueId, nickname;
        Author(String handle, String name) { uniqueId = handle; nickname = name; }
    }

    /** Stands in for TikTok's Comment, with the field names all three declared builds carry. */
    public static final class Comment {
        public String cid, text, rootCommentId = "0", commentLanguage = "en";
        public Author user;
        public int createTime = 1_700_000_000, diggCount, replyCount, stickPosition;
        public long replyCommentTotal;
        public boolean authorPin, isAuthorDigged;
        public List<Object> imageList = new ArrayList<>();
        public List<Comment> replyComments = new ArrayList<>();

        Comment(String id, String said, String handle, String name) {
            cid = id;
            text = said;
            user = new Author(handle, name);
        }
    }

    public static class SettingsWindow extends Activity {
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            setTheme(android.R.style.Theme_Material_NoActionBar);
            setContentView(new LinearLayout(this));
            findViewById(android.R.id.content).setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        }
    }

    // ---- the writers --------------------------------------------------------------------

    /** A minimal RFC 4180 reader, so the test checks what a spreadsheet would see. */
    static List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\r') { /* the \n that follows ends the record */ }
            else if (c == '\n') { row.add(field.toString()); field.setLength(0); rows.add(row); row = new ArrayList<>(); }
            else field.append(c);
        }
        return rows;
    }

    private static CommentExport.Row row(String id, String parent, String text) {
        CommentExport.Row row = new CommentExport.Row();
        row.id = id;
        row.parentId = parent;
        row.text = text;
        row.username = "user_" + id;
        row.nickname = "Nick, \"the\" " + id;
        row.createdEpoch = 1_700_000_000L;
        row.likes = 12;
        return row;
    }

    @Test public void csvEscapesCommasQuotesNewlinesAndEmoji() {
        String nasty = "plain, comma \"quoted\" line one\nline two\r\nthree 😀 café";
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", nasty), row("2", "1", "=SUM(A1)"));
        List<List<String>> parsed = parseCsv(CommentExport.toCsv(rows));

        assertEquals(3, parsed.size());
        assertEquals(Arrays.asList(CommentExport.COLUMNS), parsed.get(0));
        for (List<String> record : parsed) assertEquals(CommentExport.COLUMNS.length, record.size());
        // Every awkward character comes back exactly as it went in.
        assertEquals(nasty, parsed.get(1).get(5));
        assertEquals("Nick, \"the\" 1", parsed.get(1).get(4));
        assertEquals("comment", parsed.get(1).get(2));
        assertEquals("reply", parsed.get(2).get(2));
        assertEquals("1", parsed.get(2).get(1));
        assertEquals("2023-11-14T22:13:20Z", parsed.get(1).get(6));
        // A field with none of them is left bare.
        assertTrue(CommentExport.toCsv(rows).contains(",user_1,"));
    }

    /** A spreadsheet runs a cell that starts like a formula; the CSV keeps every one of them text. */
    @Test public void csvKeepsFormulaLookingCommentsAsText() {
        String[] formulas = {"=HYPERLINK(\"https://x\",\"hi\")", "+1+1", "-2+3", "@SUM(A1)", "\tlead", "\rlead"};
        List<CommentExport.Row> rows = new java.util.ArrayList<>();
        for (int index = 0; index < formulas.length; index++) rows.add(row(String.valueOf(index), "", formulas[index]));
        rows.add(row("9", "", "a = b, not a formula"));
        List<List<String>> parsed = parseCsv(CommentExport.toCsv(rows));
        for (int index = 0; index < formulas.length; index++) {
            assertEquals("'" + formulas[index], parsed.get(index + 1).get(5));
        }
        assertEquals("a = b, not a formula", parsed.get(formulas.length + 1).get(5));
    }

    @Test public void aTruncatedSheetSaysSoInTheJsonHeader() throws Exception {
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", "=HYPERLINK(\"x\")"));
        JSONObject whole = new JSONObject(CommentExport.toJson(rows, "2026-10-07T00:00:00Z"));
        assertEquals(false, whole.getBoolean("truncated"));
        // JSON is no spreadsheet, so its text stays exactly as written.
        assertEquals("=HYPERLINK(\"x\")", whole.getJSONArray("comments").getJSONObject(0).getString("text"));
        JSONObject cut = new JSONObject(CommentExport.toJson(rows, "2026-10-07T00:00:00Z", true));
        assertEquals(true, cut.getBoolean("truncated"));
        assertTrue(cut.getString("note"), cut.getString("note").contains("more comments loaded than Metra keeps"));
    }

    @Test public void csvFileLeadsWithAByteOrderMarkAndJsonDoesNot() throws Exception {
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", "hi 😀"));
        byte[] csv = CommentExport.encode(CommentExport.Format.CSV, rows, "2026-10-07T00:00:00Z");
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        assertTrue(new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8).contains("😀"));
        byte[] json = CommentExport.encode(CommentExport.Format.JSON, rows, "2026-10-07T00:00:00Z");
        assertEquals('{', json[0]);
    }

    @Test public void jsonCarriesCountsInItsHeaderAndEveryField() throws Exception {
        String nasty = "say \"hi\"\nnew line 😀";
        List<CommentExport.Row> rows = Arrays.asList(row("1", "", nasty), row("2", "1", "reply"), row("3", "1", "reply two"));
        rows.get(0).pinned = true;
        rows.get(0).creatorLiked = true;
        rows.get(0).images = 2;
        JSONObject root = new JSONObject(CommentExport.toJson(rows, "2026-10-07T00:00:00Z"));

        assertEquals(1, root.getInt("comment_count"));
        assertEquals(2, root.getInt("reply_count"));
        assertEquals(3, root.getInt("total"));
        assertEquals("2026-10-07T00:00:00Z", root.getString("exported_at"));
        JSONArray comments = root.getJSONArray("comments");
        assertEquals(3, comments.length());
        JSONObject first = comments.getJSONObject(0);
        assertEquals(nasty, first.getString("text"));
        assertTrue(first.isNull("parent_cid"));
        assertEquals("comment", first.getString("type"));
        assertTrue(first.getBoolean("pinned"));
        assertTrue(first.getBoolean("creator_liked"));
        assertEquals(2, first.getInt("images"));
        assertEquals("1", comments.getJSONObject(1).getString("parent_cid"));
        assertEquals("reply", comments.getJSONObject(1).getString("type"));
        for (String key : new String[]{"cid", "username", "nickname", "created_at", "created_epoch",
                "likes", "reply_count"}) {
            assertTrue(key, first.has(key));
        }
    }

    // ---- reading the loaded set ---------------------------------------------------------

    @Test public void previewRepliesAreFlattenedOnceAndPointAtTheirParent() {
        Comment parent = new Comment("10", "top", "a", "A");
        parent.replyCommentTotal = 7;
        parent.stickPosition = 1;
        parent.isAuthorDigged = true;
        parent.imageList.add(new Object());
        Comment preview = new Comment("11", "preview reply", "b", "B");
        parent.replyComments.add(preview);
        // The same reply, also bound as a row of its own once the thread opened.
        Comment opened = new Comment("11", "preview reply", "b", "B");
        opened.rootCommentId = "10";
        Comment second = new Comment("12", "opened reply", "c", "C");
        second.rootCommentId = "10";
        Comment other = new Comment("20", "another top", "d", "D");

        List<CommentExport.Row> rows = CommentExport.collect(Arrays.asList(parent, opened, second, other));

        assertEquals(4, rows.size());
        assertEquals(2, CommentExport.topLevel(rows));
        assertEquals("10", rows.get(0).id);
        assertEquals("", rows.get(0).parentId);
        assertEquals(7, rows.get(0).replyCount);
        assertTrue(rows.get(0).pinned);
        assertTrue(rows.get(0).creatorLiked);
        assertEquals(1, rows.get(0).images);
        assertEquals("11", rows.get(1).id);
        assertEquals("10", rows.get(1).parentId);
        assertEquals("12", rows.get(2).id);
        assertEquals("10", rows.get(2).parentId);
        assertEquals(4, CommentExport.count(Arrays.asList(parent, opened, second, other)));
    }

    @Test public void aModelThatCannotBeReadStillExportsAnEmptyRowRatherThanFailing() {
        List<CommentExport.Row> rows = CommentExport.collect(Arrays.asList(new Object(), null));
        assertEquals(1, rows.size());
        assertEquals("", rows.get(0).text);
        assertEquals(0, CommentExport.collect(null).size());
    }

    // ---- gating -------------------------------------------------------------------------

    @Test public void exportIsOffByDefault() {
        assertEquals(Boolean.FALSE, Settings.COMMENT_EXPORT.defaultValue);
        assertFalse(CommentExport.enabled());
    }

    private static LinearLayout sheet(Activity activity, LinearLayout column, Comment... comments) {
        LinearLayout listView = new LinearLayout(activity);
        listView.setOrientation(LinearLayout.VERTICAL);
        column.addView(listView);
        for (Comment comment : comments) {
            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);
            CommentSearch.onCellBound(row, comment);
        }
        shadowOf(Looper.getMainLooper()).idle();
        return listView;
    }

    @Test public void withTheSwitchOffTheSearchBoxGainsNoButtons() {
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.COMMENT_EXPORT.save(false);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            sheet(activity, column, new Comment("1", "one", "a", "A"));

            assertNull(column.findViewWithTag(CommentSearch.EXPORT_TAG));
            // The box, its status line and the list: what the search alone adds.
            assertEquals(3, column.getChildCount());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void withTheSwitchOnTheCountMatchesWhatTheFileHolds() throws Exception {
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.COMMENT_EXPORT.save(true);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            Comment parent = new Comment("1", "top, with a comma", "a", "A");
            parent.replyComments.add(new Comment("2", "a preview reply", "b", "B"));
            sheet(activity, column, parent, new Comment("3", "second 😀", "c", "C"));

            View exports = column.findViewWithTag(CommentSearch.EXPORT_TAG);
            assertNotNull(exports);
            TextView count = (TextView) exports.findViewWithTag(CommentSearch.EXPORT_COUNT_TAG);
            assertEquals("3 comments and replies loaded", count.getText().toString());

            LinearLayout buttons = (LinearLayout) exports;
            TextView csv = (TextView) buttons.getChildAt(1);
            assertEquals("Export CSV", csv.getText().toString());
            assertEquals("Export JSON", ((TextView) buttons.getChildAt(2)).getText().toString());

            var provider = DocumentExportProvider.register(activity);
            csv.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            var started = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull("the file picker was never opened", started);
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
            assertEquals("text/csv", started.intent.getType());
            assertTrue(started.intent.getStringExtra(Intent.EXTRA_TITLE).endsWith(".csv"));

            answer(activity, started.requestCode, provider);
            awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
            String written = new String(Files.readAllBytes(provider.file.toPath()), StandardCharsets.UTF_8);
            assertTrue(written.startsWith("﻿"));
            List<List<String>> parsed = parseCsv(written.substring(1));
            // A header and one record per comment or reply the panel counted.
            assertEquals(1 + 3, parsed.size());
            assertEquals("top, with a comma", parsed.get(1).get(5));
            assertEquals("reply", parsed.get(2).get(2));
            assertEquals("1", parsed.get(2).get(1));
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
            Settings.COMMENT_EXPORT.save(false);
        }
    }

    @Test public void aFileAppThatHangsOffersStopWaitingAndNothingIsExported() throws Exception {
        try (var controller = Robolectric.buildActivity(SettingsWindow.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.COMMENT_EXPORT.save(true);
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            sheet(activity, column, new Comment("1", "one", "a", "A"));
            TextView json = (TextView) ((LinearLayout) column.findViewWithTag(CommentSearch.EXPORT_TAG)).getChildAt(2);

            var provider = DocumentExportProvider.register(activity);
            provider.honorCancel = true;
            var release = provider.holdOpens();
            LogBufferManager.clearLogBuffer();
            try {
                json.performClick();
                shadowOf(Looper.getMainLooper()).idle();
                var started = shadowOf(activity).getNextStartedActivityForResult();
                assertEquals("application/json", started.intent.getType());
                answer(activity, started.requestCode, provider);
                assertTrue("the file app was never asked for the file", provider.awaitOpening());
                shadowOf(Looper.getMainLooper()).idle();
                assertNull("the stop was offered before the file app stalled", bannerAction(activity));

                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DocumentOperation.stallMillisForTests()));
                assertEquals("Still waiting for the file app.", bannerText(activity));
                View stop = bannerAction(activity);
                assertEquals("Stop waiting", ((TextView) stop).getText().toString());
                assertTrue(stop.performClick());
                awaitWorker(DocumentOperation.Kind.COMMENT_FILE);
                // The write was at the file app's open, past the gate, so the stop can't say it was clean.
                assertEquals("The export wasn't saved. Export again when the file app is ready.",
                        bannerText(activity));
                DocumentOperationTest.assertStopLoggedAsInfo("Comment export failed");
            } finally {
                release.countDown();
            }
            assertEquals(0, provider.file.length());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
            Settings.COMMENT_EXPORT.save(false);
        }
    }

    private static void answer(Activity activity, int requestCode, DocumentExportProvider provider) {
        android.app.Fragment request = activity.getFragmentManager().findFragmentByTag("hushfeed_comment_export");
        assertNotNull("the picker's fragment is missing", request);
        request.onActivityResult(requestCode, Activity.RESULT_OK, new Intent().setData(provider.uri));
    }

    private static void awaitWorker(DocumentOperation.Kind kind) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (DocumentOperation.busy(kind) && System.nanoTime() < deadline) {
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
        Utils.awaitBackgroundTasksForTests();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse("the file worker never returned", DocumentOperation.busy(kind));
    }

    private static String bannerText(Activity activity) {
        View label = activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_settings_action_message");
        return label instanceof TextView ? ((TextView) label).getText().toString() : null;
    }

    private static View bannerAction(Activity activity) {
        return activity.findViewById(android.R.id.content).findViewWithTag("hushfeed_settings_action_button");
    }
}
