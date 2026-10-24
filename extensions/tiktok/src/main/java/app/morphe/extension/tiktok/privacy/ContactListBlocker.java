/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.ContentResolver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.ContactsContract;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Contact reads answered with an empty cursor while the switch is on.
 *
 * <p>Every ContentResolver.query TikTok makes lands here, whatever it is asking for, so the
 * authority decides: only the contacts provider is refused, and the media store and the rest
 * are handed straight through. A read before the settings context exists takes the switch
 * straight from the saved file ({@link EarlySwitch}), so a switch that's on covers TikTok's
 * earliest start-up reads too.
 */
@SuppressWarnings("unused")
public final class ContactListBlocker {
    /** {@link Settings#BLOCK_CONTACT_LIST}'s key, for the read before the settings context. */
    static final String SWITCH_KEY = "block_contact_list";

    private static boolean blocks(Uri uri) {
        if (uri == null || !ContactsContract.AUTHORITY.equals(uri.getAuthority())) return false;
        boolean on = Utils.getContext() != null ? Settings.BLOCK_CONTACT_LIST.get() : EarlySwitch.isOn(SWITCH_KEY);
        if (!on) return false;
        Logger.printInfo(() -> "Contact list blocker: blocked a query to " + uri);
        return true;
    }

    /**
     * What a null projection gets back: every column the contacts provider's tables and views
     * answer with, so a caller that looks a column up by name, getColumnIndexOrThrow included,
     * finds the ones the real provider would have given it. There are no rows, so the columns no
     * single table has tell the caller nothing. TikTok 47.1.4's own contact reader always names
     * its columns; the null-projection readers are its generic content-URI helpers (file name,
     * size and path lookups), which a shared contact card can reach, and two of them look
     * _display_name up before checking for a row.
     */
    static final String[] ALL_COLUMNS = {
            // BaseColumns, ContactsContract.Contacts and its name, option and status columns
            "_id", "_count", "lookup", "display_name", "display_name_alt", "display_name_source",
            "phonetic_name", "phonetic_name_style", "sort_key", "sort_key_alt", "name_raw_contact_id",
            "photo_id", "photo_file_id", "photo_uri", "photo_thumb_uri", "in_visible_group",
            "in_default_directory", "is_user_profile", "has_phone_number",
            "contact_last_updated_timestamp", "times_contacted", "last_time_contacted", "starred",
            "pinned", "custom_ringtone", "send_to_voicemail", "contact_presence",
            "contact_chat_capability", "contact_status", "contact_status_ts",
            "contact_status_res_package", "contact_status_label", "contact_status_icon",
            // RawContacts and its sync columns
            "contact_id", "raw_contact_id", "aggregation_mode", "deleted", "raw_contact_is_read_only",
            "raw_contact_is_user_profile", "account_name", "account_type", "account_type_and_data_set",
            "data_set", "sourceid", "version", "dirty", "sync1", "sync2", "sync3", "sync4",
            // Data and the presence columns joined onto it
            "mimetype", "is_primary", "is_super_primary", "is_read_only", "data_version",
            "data1", "data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9", "data10",
            "data11", "data12", "data13", "data14", "data15",
            "data_sync1", "data_sync2", "data_sync3", "data_sync4", "carrier_presence",
            "preferred_phone_account_component_name", "preferred_phone_account_id",
            "mode", "chat_capability", "status", "status_ts", "status_res_package", "status_label",
            "status_icon",
            // PhoneLookup
            "number", "type", "label", "normalized_number", "data_id",
            // Groups
            "title", "notes", "system_id", "group_visible", "should_sync", "auto_add", "favorites",
            "group_is_read_only", "summ_count", "summ_phones",
            // OpenableColumns, for a contact card (contacts/as_vcard)
            "_display_name", "_size",
    };

    private static Cursor empty(String[] projection) {
        // A copy each time: MatrixCursor hands its array out through getColumnNames().
        return new MatrixCursor(projection != null ? projection : ALL_COLUMNS.clone());
    }

    public static Cursor interceptQuery(ContentResolver resolver, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        if (blocks(uri)) return empty(projection);
        return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
    }

    public static Cursor interceptQuery(ContentResolver resolver, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, CancellationSignal signal) {
        if (blocks(uri)) return empty(projection);
        return resolver.query(uri, projection, selection, selectionArgs, sortOrder, signal);
    }

    public static Cursor interceptQuery(ContentResolver resolver, Uri uri, String[] projection,
            Bundle queryArgs, CancellationSignal signal) {
        if (blocks(uri)) return empty(projection);
        // TikTok only reaches this overload where it exists; the branch below it is for the
        // API floor's benefit and never runs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return resolver.query(uri, projection, queryArgs, signal);
        }
        return resolver.query(uri, projection, null, null, null, signal);
    }

    private ContactListBlocker() {}
}
