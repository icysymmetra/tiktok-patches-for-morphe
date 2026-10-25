/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.comment;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.DocumentOperation;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Saves the comments and replies a comment sheet has loaded to a CSV or JSON file.
 *
 * <p>It reads the comment models TikTok already holds, the same ones the search box counts, so
 * nothing is fetched. TikTok pages comments and replies, and a reply thread that hasn't been
 * opened isn't loaded, so the file holds what the sheet has seen and says how many of each in its
 * header (JSON) and in the notice. The file goes through the same file picker worker the settings
 * backup uses, so a file app that hangs offers "Stop waiting".
 */
public final class CommentExport {
    public enum Format {
        CSV("text/csv", "csv"),
        JSON("application/json", "json");

        final String mime;
        final String extension;

        Format(String mime, String extension) {
            this.mime = mime;
            this.extension = extension;
        }
    }

    /** One exported comment, flattened out of TikTok's model. */
    static final class Row {
        String id = "";
        /** The comment a reply answers, empty for a top-level comment. */
        String parentId = "";
        String username = "";
        String nickname = "";
        String text = "";
        long createdEpoch;
        long likes;
        long replyCount;
        boolean pinned;
        boolean creatorLiked;
        int images;
        String language = "";

        boolean isReply() {
            return !parentId.isEmpty();
        }
    }

    static final String[] COLUMNS = {
            "cid", "parent_cid", "type", "username", "nickname", "text", "created_at", "created_epoch",
            "likes", "reply_count", "pinned", "creator_liked", "images", "language"
    };

    private CommentExport() {}

    public static boolean enabled() {
        return Settings.COMMENT_EXPORT.get();
    }

    // ---- reading the loaded comments ----------------------------------------------------

    /**
     * Every comment and reply in the loaded set, once each, in the order they were loaded with a
     * reply that arrived inside its parent's preview list placed straight after that parent.
     */
    static List<Row> collect(Collection<?> loaded) {
        List<Row> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (loaded == null) return rows;
        for (Object comment : loaded) addRow(rows, seen, comment, "");
        return rows;
    }

    /** How many rows {@link #collect} would produce, for the count beside the buttons. */
    static int count(Collection<?> loaded) {
        return collect(loaded).size();
    }

    private static void addRow(List<Row> rows, Set<String> seen, Object comment, String enclosingParent) {
        if (comment == null) return;
        String id = Reflect.string(comment, "getCid", "cid");
        // A model with no id can't be told from itself on the next rebind.
        String key = id != null ? id : "model:" + System.identityHashCode(comment);
        if (!seen.add(key)) return;

        Row row = new Row();
        row.id = id == null ? "" : id;
        String root = Reflect.string(comment, "getRootCommentId", "rootCommentId");
        if (root != null && !"0".equals(root) && !root.equals(id)) {
            row.parentId = root;
        } else if (!enclosingParent.isEmpty()) {
            row.parentId = enclosingParent;
        }
        Object user = Reflect.property(comment, "getUser", "user");
        row.username = orEmpty(Reflect.string(user, "getUniqueId", "uniqueId"));
        row.nickname = orEmpty(Reflect.string(user, "getNickname", "nickname"));
        // The text as TikTok stores it, not trimmed the way Reflect.string does.
        Object text = Reflect.property(comment, "getText", "text");
        row.text = text == null ? "" : text.toString();
        row.createdEpoch = number(Reflect.property(comment, "getCreateTime", "createTime"));
        row.likes = number(Reflect.property(comment, "getDiggCount", "diggCount"));
        row.replyCount = Math.max(
                number(Reflect.property(comment, "getReplyCommentTotal", "replyCommentTotal")),
                number(Reflect.property(comment, "getReplyCount", "replyCount")));
        row.pinned = flag(Reflect.property(comment, "isAuthorPin", "authorPin"))
                || number(Reflect.property(comment, "getStickPosition", "stickPosition")) > 0;
        row.creatorLiked = flag(Reflect.property(comment, "isAuthorDigged", "isAuthorDigged"));
        Object images = Reflect.property(comment, "getImageList", "imageList");
        row.images = images instanceof List ? ((List<?>) images).size() : 0;
        row.language = orEmpty(Reflect.string(comment, "getCommentLanguage", "commentLanguage"));
        rows.add(row);

        Object previews = Reflect.property(comment, "getReplyComments", "replyComments");
        if (previews instanceof List) {
            String parent = row.isReply() ? row.parentId : row.id;
            for (Object reply : new ArrayList<>((List<?>) previews)) addRow(rows, seen, reply, parent);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static boolean flag(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    // ---- the two formats ----------------------------------------------------------------

    static int topLevel(List<Row> rows) {
        int top = 0;
        for (Row row : rows) if (!row.isReply()) top++;
        return top;
    }

    /** RFC 4180: CRLF between records, a field quoted when it holds a comma, quote or line break. */
    static String toCsv(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < COLUMNS.length; index++) {
            if (index > 0) out.append(',');
            out.append(COLUMNS[index]);
        }
        out.append("\r\n");
        for (Row row : rows) {
            appendField(out, row.id).append(',');
            appendField(out, row.parentId).append(',');
            appendField(out, row.isReply() ? "reply" : "comment").append(',');
            appendField(out, row.username).append(',');
            appendField(out, row.nickname).append(',');
            appendField(out, row.text).append(',');
            appendField(out, iso(row.createdEpoch)).append(',');
            appendField(out, Long.toString(row.createdEpoch)).append(',');
            appendField(out, Long.toString(row.likes)).append(',');
            appendField(out, Long.toString(row.replyCount)).append(',');
            appendField(out, Boolean.toString(row.pinned)).append(',');
            appendField(out, Boolean.toString(row.creatorLiked)).append(',');
            appendField(out, Integer.toString(row.images)).append(',');
            appendField(out, row.language);
            out.append("\r\n");
        }
        return out.toString();
    }

    private static StringBuilder appendField(StringBuilder out, String value) {
        // A spreadsheet runs a cell that starts like a formula, so a comment reading
        // =HYPERLINK(...) would become a live link. A leading apostrophe keeps it text.
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        boolean quote = false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                quote = true;
                break;
            }
        }
        if (!quote) return out.append(value);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"') out.append('"');
            out.append(c);
        }
        return out.append('"');
    }

    /** The header counts what the file holds, so a reader can tell a partial export from a whole one. */
    static String toJson(List<Row> rows, String exportedAt) throws JSONException {
        return toJson(rows, exportedAt, false);
    }

    /** As above; {@code truncated} says the sheet held more than the search keeps. */
    static String toJson(List<Row> rows, String exportedAt, boolean truncated) throws JSONException {
        JSONObject root = new JSONObject();
        int top = topLevel(rows);
        root.put("exported_at", exportedAt);
        root.put("comment_count", top);
        root.put("reply_count", rows.size() - top);
        root.put("total", rows.size());
        root.put("truncated", truncated);
        root.put("note", "Comments and replies the sheet had loaded when this was exported. "
                + "Reply threads that weren't opened aren't included."
                + (truncated ? " The sheet had more comments loaded than Metra keeps, so the "
                        + "later ones aren't in this file either." : ""));
        JSONArray comments = new JSONArray();
        for (Row row : rows) {
            JSONObject item = new JSONObject();
            item.put("cid", row.id);
            item.put("parent_cid", row.isReply() ? row.parentId : JSONObject.NULL);
            item.put("type", row.isReply() ? "reply" : "comment");
            item.put("username", row.username);
            item.put("nickname", row.nickname);
            item.put("text", row.text);
            item.put("created_at", iso(row.createdEpoch));
            item.put("created_epoch", row.createdEpoch);
            item.put("likes", row.likes);
            item.put("reply_count", row.replyCount);
            item.put("pinned", row.pinned);
            item.put("creator_liked", row.creatorLiked);
            item.put("images", row.images);
            item.put("language", row.language);
            comments.put(item);
        }
        root.put("comments", comments);
        return root.toString(2);
    }

    static String iso(long epochSeconds) {
        if (epochSeconds <= 0) return "";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochSeconds * 1000L));
    }

    /** The bytes of the file. CSV leads with a byte order mark so a spreadsheet reads emoji right. */
    static byte[] encode(Format format, List<Row> rows, String exportedAt) throws JSONException {
        return encode(format, rows, exportedAt, false);
    }

    static byte[] encode(Format format, List<Row> rows, String exportedAt, boolean truncated)
            throws JSONException {
        if (format == Format.JSON) return toJson(rows, exportedAt, truncated).getBytes(StandardCharsets.UTF_8);
        byte[] body = toCsv(rows).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    // ---- the picker and the write -------------------------------------------------------

    private static final String FRAGMENT_TAG = "hushfeed_comment_export";
    private static final int REQUEST = 0x4345;

    /** What the tap saw, held for the picker's answer. Main thread only. */
    private static Format pendingFormat;
    private static List<Row> pendingRows;
    private static boolean pendingTruncated;
    private static DocumentOperation fileOperation;
    private static boolean stopOffered;

    /** From the Export button: takes the rows now, so the file is what the count beside it said. */
    static void begin(Activity activity, Format format, Collection<?> loaded) {
        begin(activity, format, loaded, false);
    }

    /** {@code truncated}: the sheet stopped keeping comments, so the file and notice say so. */
    static void begin(Activity activity, Format format, Collection<?> loaded, boolean truncated) {
        if (activity == null) return;
        try {
            if (DocumentOperation.busy(DocumentOperation.Kind.COMMENT_FILE)) {
                tellFileOut(activity);
                return;
            }
            List<Row> rows = collect(loaded);
            if (rows.isEmpty()) {
                notice(activity, L10n.t(activity, "No comments are loaded to export yet."));
                return;
            }
            pendingFormat = format;
            pendingRows = rows;
            pendingTruncated = truncated;
            Request request = new Request();
            Bundle arguments = new Bundle();
            arguments.putString("name", fileName(format));
            arguments.putString("mime", format.mime);
            request.setArguments(arguments);
            FragmentManager fragments = activity.getFragmentManager();
            fragments.beginTransaction().add(request, FRAGMENT_TAG).commitAllowingStateLoss();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not open the picker for a comment export", error);
            notice(activity, L10n.t(activity, "Couldn't open the file picker to export. Try again."));
        }
    }

    static String fileName(Format format) {
        SimpleDateFormat clock = new SimpleDateFormat("yyyyMMdd-HHmmss'Z'", Locale.US);
        clock.setTimeZone(TimeZone.getTimeZone("UTC"));
        String stamp = clock.format(new Date());
        return "tiktok-comments-" + stamp + "." + format.extension;
    }

    /**
     * A framework fragment that asks for the document and takes the answer, because the answer
     * goes to whoever asked and TikTok's activities aren't ours to hook for it.
     */
    public static final class Request extends Fragment {
        public Request() {}

        @Override
        public void onCreate(Bundle state) {
            super.onCreate(state);
            // Restored with the picker already up: stay, so its answer still lands here after a
            // rotation or a theme change.
            if (state != null) return;
            Bundle arguments = getArguments();
            try {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(arguments.getString("mime"))
                        .putExtra(Intent.EXTRA_TITLE, arguments.getString("name"));
                startActivityForResult(intent, REQUEST);
            } catch (RuntimeException unavailable) {
                leave();
                pendingRows = null;
                Logger.printInfo(() -> "No file picker for a comment export: " + unavailable);
                notice(getActivity(), L10n.t(getActivity(),
                        "Couldn't open the file picker to export. Try again."));
            }
        }

        @Override
        public void onActivityResult(int request, int result, Intent data) {
            if (request != REQUEST) return;
            Activity activity = getActivity();
            leave();
            Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
            Format format = pendingFormat;
            List<Row> rows = pendingRows;
            boolean truncated = pendingTruncated;
            pendingRows = null;
            if (uri == null || activity == null) return;
            if (format == null || rows == null) {
                // The process restarted under the picker, which already made an empty file.
                DocumentOperation.removeUnsaved(activity.getContentResolver(), uri, false);
                notice(activity, L10n.t(activity, "Couldn't open the file picker to export. Try again."));
                return;
            }
            write(activity.getApplicationContext(), activity, uri, format, rows, truncated);
        }

        private void leave() {
            FragmentManager fragments = getFragmentManager();
            if (fragments != null) fragments.beginTransaction().remove(this).commitAllowingStateLoss();
        }
    }

    private static void write(Context context, Activity window, Uri uri, Format format, List<Row> rows,
            boolean truncated) {
        DocumentOperation[] started = new DocumentOperation[1];
        started[0] = DocumentOperation.start(DocumentOperation.Kind.COMMENT_FILE,
                file -> exportTo(context, window, uri, format, rows, truncated, file),
                () -> fileOperationChanged(window, started[0]));
        if (started[0] != null) {
            fileOperation = started[0];
            stopOffered = false;
            return;
        }
        // The picker made a document and nothing will fill it now.
        DocumentOperation.removeUnsaved(context.getContentResolver(), uri, false);
        tellFileOut(window);
    }

    private static void exportTo(Context context, Activity window, Uri uri, Format format,
            List<Row> rows, boolean truncated, DocumentOperation file) {
        ContentResolver resolver = context.getContentResolver();
        boolean opened = false;
        try {
            byte[] bytes = encode(format, rows, iso(System.currentTimeMillis() / 1000L), truncated);
            if (!file.publish()) {
                DocumentOperation.removeUnsaved(resolver, uri, false);
                return;
            }
            try (OutputStream output = file.openForWrite(resolver, uri, "wt")) {
                opened = true;
                output.write(bytes);
            }
            file.finish();
            int top = topLevel(rows);
            String exported = L10n.f(context, "Exported %1$d comments and %2$d replies", top, rows.size() - top);
            if (truncated) {
                exported += " " + L10n.f(context, "More than %1$d comments were loaded, so the rest aren't in the file.",
                        CommentSearch.MAX_LOADED_COMMENTS);
            }
            notice(window, exported);
        } catch (IOException | JSONException | RuntimeException | OutOfMemoryError error) {
            // Stopped before the file app had anything: the stop has said what happened.
            if (file.stage() == DocumentOperation.Stage.STOPPED) {
                DocumentOperation.removeUnsaved(resolver, uri, false);
                return;
            }
            DocumentOperation.logFailure(file, () -> "Comment export failed", error);
            String message = file.stage() == DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING
                    ? L10n.t(context, "The export wasn't saved. Export again when the file app is ready.")
                    : L10n.t(context, "Comment export failed");
            if (!DocumentOperation.removeUnsaved(resolver, uri, opened)) {
                message += " " + L10n.t(context,
                        "The partial file couldn't be removed. Delete it from the folder you chose.");
            }
            notice(window, message);
        }
    }

    // ---- a stuck file app ---------------------------------------------------------------

    private static void fileOperationChanged(Activity window, DocumentOperation operation) {
        if (operation == null || operation != fileOperation) return;
        if (!DocumentOperation.busy(DocumentOperation.Kind.COMMENT_FILE)) {
            fileOperation = null;
            SettingsActionBanner.dismissShowing(stillWaiting(window));
            return;
        }
        if (operation.offersStop()) {
            if (!stopOffered) {
                stopOffered = true;
                offerStop(window);
            }
        } else if (!operation.isStopped()) {
            // Past the gate, where a stop can't do anything any more.
            SettingsActionBanner.dismissShowing(stillWaiting(window));
        }
    }

    private static void offerStop(Context context) {
        String waiting = stillWaiting(context);
        SettingsActionBanner.showAction(context, waiting, L10n.t(context, "Stop waiting"),
                () -> stopWaiting(context), waiting);
    }

    private static String stillWaiting(Context context) {
        return L10n.t(context, "Still waiting for the file app.");
    }

    private static void stopWaiting(Context context) {
        DocumentOperation operation = fileOperation;
        if (operation == null) return;
        switch (operation.stop()) {
            case STOPPED:
                notice(context, L10n.t(context, "Stopped waiting for the file app. Nothing was exported."));
                break;
            case STOPPED_WHILE_PUBLISHING:
                notice(context, L10n.t(context,
                        "Stopped waiting for the file app. It hasn't said yet whether the export was saved."));
                break;
            default:
                break;
        }
    }

    private static void tellFileOut(Context context) {
        DocumentOperation operation = fileOperation;
        if (DocumentOperation.heldAfterStop(DocumentOperation.Kind.COMMENT_FILE)) {
            notice(context, L10n.t(context,
                    "The file app still has the last file. Try again once it lets go, or restart TikTok."));
        } else if (operation != null && operation.offersStop()) {
            offerStop(context);
        } else {
            notice(context, stillWaiting(context));
        }
    }

    private static void notice(Context context, String message) {
        SettingsActionBanner.showNotice(context, message);
    }

    /** Test seam: the state a tap leaves for the picker's answer. */
    static List<Row> pendingRowsForTests() {
        return pendingRows;
    }
}
