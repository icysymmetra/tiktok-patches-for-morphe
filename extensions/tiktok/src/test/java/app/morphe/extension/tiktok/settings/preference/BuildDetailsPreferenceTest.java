package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.preference.Preference;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import app.morphe.extension.shared.diagnostics.BuildDetailsTest;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.BuildDetailsFixture;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPreferenceOwnershipTest.SettingsActivity;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 35}, qualifiers = "en-w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@SuppressWarnings("deprecation")
public class BuildDetailsPreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private boolean oldDiagnostics;

    @Before public void start() {
        oldDiagnostics = SettingsStatus.diagnosticsEnabled;
        SettingsStatus.diagnosticsEnabled = false;
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    @After public void finish() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        SettingsStatus.diagnosticsEnabled = oldDiagnostics;
        Utils.setIsDarkModeEnabled(true);
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    @Test public void aboutCanCopyLegacyDetailsWithoutDiagnosticToolsOrEvents() {
        try (var owner = root()) {
            SettingsActivity activity = owner.get();
            Utils.setContext(activity);
            // Setting the context logs a line of its own; only what the button adds is under test.
            LogBufferManager.clearLogBuffer();
            TikTokPreferenceFragment page = page(activity);
            assertFalse(SettingsStatus.diagnosticsEnabled);
            for (int index = 0; index < page.getPreferenceScreen().getPreferenceCount(); index++) {
                assertNotEquals("Diagnostics", page.getPreferenceScreen().getPreference(index).getTitle());
            }
            assertEquals("", LogBufferManager.buildExportText());
            AlertDialog dialog = clickDetails(activity);
            TextView text = dialog.findViewById(android.R.id.message);
            assertEquals(BuildDetails.report(""), text.getText().toString());
            assertTrue(text.isTextSelectable());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            // The dialog delivers button clicks through a posted message.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(BuildDetails.report(""), clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            if (Build.VERSION.SDK_INT >= 24) {
                assertTrue(clipboard.getPrimaryClip().getDescription().getExtras()
                        .getBoolean("android.content.extra.IS_SENSITIVE"));
            }
        }
    }

    @Test @Config(sdk = 28)
    public void saveUsesTheRealLegacyWriterWithoutAnyAutomaticReport() throws Exception {
        try (var owner = root()) {
            SettingsActivity activity = owner.get();
            Utils.setContext(activity);
            // Setting the context logs a line of its own; only what the button adds is under test.
            LogBufferManager.clearLogBuffer();
            assertEquals("", LogBufferManager.buildExportText());
            File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            assertNotNull(directory);
            java.util.Set<File> existing = new java.util.HashSet<>(java.util.Arrays.asList(directory.listFiles() == null
                    ? new File[0] : directory.listFiles()));
            clickDetails(activity).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            // The dialog delivers button clicks through a posted message.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Utils.awaitBackgroundTasksForTests();
            java.util.List<File> created = new java.util.ArrayList<>();
            for (File file : directory.listFiles()) if (!existing.contains(file)) created.add(file);
            try {
                assertEquals(1, created.size());
                assertEquals(BuildDetails.report(""), new String(Files.readAllBytes(created.get(0).toPath()), StandardCharsets.UTF_8));
            } finally {
                for (File file : created) assertTrue(file.delete());
            }
        }
    }

    @Test @Config(sdk = 35)
    public void savePublishesTheCompleteValidatedMetadataThroughTheModernWriter() throws Exception {
        try (var owner = root(); BuildDetailsFixture asset = new BuildDetailsFixture(owner.get(), temporary.newFile(), BuildDetailsTest.metadata())) {
            Utils.setContext(asset.context);
            // Setting the context logs a line of its own; only what the button adds is under test.
            LogBufferManager.clearLogBuffer();
            ReportProvider provider = new ReportProvider(temporary.newFile());
            ProviderInfo info = new ProviderInfo();
            info.authority = "media";
            provider.attachInfo(owner.get(), info);
            ShadowContentResolver.registerProviderInternal("media", provider);
            assertEquals("", LogBufferManager.buildExportText());
            clickDetails(owner.get()).getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            // The dialog delivers button clicks through a posted message.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Utils.awaitBackgroundTasksForTests();
            assertEquals(BuildDetails.report(BuildDetailsTest.metadata()), new String(Files.readAllBytes(provider.file.toPath()), StandardCharsets.UTF_8));
            assertEquals("text/plain", provider.created.getAsString(MediaStore.MediaColumns.MIME_TYPE));
            assertEquals("Download/Morphe", provider.created.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertEquals(Integer.valueOf(1), provider.created.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            assertTrue(provider.published);
            assertFalse(provider.deleted);
        }
    }

    @Test public void deniedClipboardPointsToSaveBuildDetails() {
        try (var owner = root()) {
            Context denied = new ContextWrapper(owner.get()) {
                @Override public Object getSystemService(String name) {
                    if (name.equals(Context.CLIPBOARD_SERVICE)) throw new SecurityException("Clipboard denied");
                    return super.getSystemService(name);
                }
            };
            BuildDetailsPreference row = new BuildDetailsPreference(denied);
            assertTrue(row.getOnPreferenceClickListener().onPreferenceClick(row));
            ShadowToast.reset();
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Couldn't copy the build details. Use Save build details instead.",
                    String.valueOf(ShadowToast.getTextOfLatestToast()));
        }
    }

    @Test @Config(sdk = 35, fontScale = 1.5f)
    public void theRealBuildDialogCanBeCapturedInBothThemesWithLargeText() throws Exception {
        try (var owner = root(); BuildDetailsFixture asset = new BuildDetailsFixture(owner.get(), temporary.newFile(), BuildDetailsTest.metadata())) {
            Utils.setContext(asset.context);
            // Setting the context logs a line of its own; only what the button adds is under test.
            LogBufferManager.clearLogBuffer();
            for (boolean dark : new boolean[]{true, false}) {
                Utils.setIsDarkModeEnabled(dark);
                AlertDialog dialog = clickDetails(owner.get());
                assertEquals("Copy build details", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
                assertEquals("Save build details", dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString());
                assertTrue(((TextView) dialog.findViewById(android.R.id.message)).getText().toString()
                        .contains("patch_time.amoled_color: #121212"));
                UiCapture.save(dialog.getWindow().getDecorView(), "dialogs/" + (dark ? "dark" : "light") + "/build-details.png");
                dialog.dismiss();
            }
        }
    }

    private static ActivityController<SettingsActivity> root() {
        var owner = Robolectric.buildActivity(SettingsActivity.class, new Intent("morphe_settings").putExtra("morphe", true))
                .setup().visible();
        settle(owner.get());
        Preference about = page(owner.get()).findPreference("hub_about");
        assertNotNull("the home menu has no About Metra route", about);
        assertTrue(about.getOnPreferenceClickListener().onPreferenceClick(about));
        settle(owner.get());
        assertEquals("ABOUT", page(owner.get()).getArguments().getString("morphe_settings_hub"));
        return owner;
    }

    private static TikTokPreferenceFragment page(SettingsActivity activity) {
        var root = activity.getFragmentManager().findFragmentByTag("hushfeed_settings_root");
        return (TikTokPreferenceFragment) activity.getFragmentManager().findFragmentById(root.getId());
    }

    private static AlertDialog clickDetails(SettingsActivity activity) {
        ListView list = page(activity).getView().findViewById(android.R.id.list);
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            Object item = list.getAdapter().getItem(index);
            if (item instanceof Preference && "action_build_details".equals(((Preference) item).getKey())) {
                list.setSelection(index);
                settle(activity);
                View row = list.getChildAt(index - list.getFirstVisiblePosition());
                assertNotNull("the actual About row was not laid out", row);
                assertTrue(list.performItemClick(row, index, list.getAdapter().getItemId(index)));
                settle(activity);
                return ShadowAlertDialog.getLatestAlertDialog();
            }
        }
        throw new AssertionError("About has no Build details action");
    }

    private static void settle(SettingsActivity activity) {
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 480, 960);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static final class ReportProvider extends ContentProvider {
        final File file;
        ContentValues created;
        boolean published, deleted;
        ReportProvider(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            created = new ContentValues(values);
            return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "1");
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public Cursor query(Uri uri, String[] fields, String where, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[]{MediaStore.MediaColumns.DISPLAY_NAME});
            cursor.addRow(new Object[]{created.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)});
            return cursor;
        }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            published = Integer.valueOf(0).equals(values.getAsInteger(MediaStore.MediaColumns.IS_PENDING));
            return 1;
        }
        @Override public int delete(Uri uri, String where, String[] args) { deleted = true; return 1; }
        @Override public String getType(Uri uri) { return "text/plain"; }
    }
}
