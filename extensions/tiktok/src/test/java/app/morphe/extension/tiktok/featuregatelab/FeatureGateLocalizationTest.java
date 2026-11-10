package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsPagesTest.PageActivity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w480dp-h960dp-night-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FeatureGateLocalizationTest {
    public static final class NestedConfig {
        public int count = 1;
    }

    public static final class StructuredConfig {
        public NestedConfig payload = new NestedConfig();
    }

    @Before public void resetSharedState() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        FeatureGateLabFragment.awaitFileIoForTests();
        Utils.awaitBackgroundTasksForTests();
        FeatureGateDetailFragment.awaitChangesForTests();
        FeatureGateCatalog.awaitForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        FeatureGateDetailFragment.setDetailChangeTestHookForTests(null);
        FeatureGateCatalog.resetForTests();
        FeatureGateLabFragment.resetForTests();
        FeatureGateLabSession.resetForTests();
        FeatureGateLabUndo.resetForTests();
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabSession.begin();
        ShadowToast.reset();
    }

    @Test
    @Config(sdk = 28, qualifiers = "de-w480dp-h960dp-night-mdpi")
    public void germanOwnsEveryFeatureGateFailureAndStateSentence() throws Exception {
        verifyFeatureGateLanguage(new Expected(
                "Gib eine ganze Zahl ein.",
                "Kein aktueller Wert und keine aktive Überschreibung",
                "11 (wird bei der Abfrage zurückgegeben)",
                "App-AB · von TikTok deklariert, mit aktuellem Wert",
                "App-AB · nur in der Liste von Metra",
                "7 (Aktuell)",
                "8 (Standard)",
                "9 (Recherchiert)",
                "10 (Historisch)",
                "11 (Ausgewählt)",
                "payload muss korrigiert werden. Gib ein JSON-Objekt oder -Array ein.",
                "Das Feld raw_missing_field lässt sich in diesem Build nicht ändern. Nimm es"
                        + " aus der Überschreibung heraus oder setze die Überschreibung zurück.",
                "Nichts Neues wurde importiert. Bereits identisch: 0. Nicht verfügbar: 0. "
                        + "Abgelehnt: 2. Erste Ablehnung: raw_import_key hat "
                        + "den Typ INT, in der Datei steht string."
        ));
    }

    @Test
    @Config(sdk = 28, qualifiers = "in-rID-w480dp-h960dp-night-mdpi")
    public void indonesianOwnsEveryFeatureGateFailureAndStateSentence() throws Exception {
        verifyFeatureGateLanguage(new Expected(
                "Masukkan bilangan bulat.",
                "Tidak ada nilai saat ini dan tidak ada penimpaan aktif",
                "11 (akan dikembalikan saat diminta)",
                "AB aplikasi · dideklarasikan TikTok, dengan nilai saat ini",
                "AB aplikasi · hanya di daftar Metra",
                "7 (Saat ini)",
                "8 (Bawaan)",
                "9 (Hasil riset)",
                "10 (Historis)",
                "11 (Dipilih)",
                "Nilai payload perlu diperbaiki. Masukkan objek atau larik JSON.",
                "Field raw_missing_field tidak bisa diubah di build ini. Keluarkan dari"
                        + " penimpaan, atau setel ulang penimpaan.",
                "Tidak ada hal baru yang diimpor. 0 sudah cocok, 0 tidak tersedia, 2 ditolak. "
                        + "Penolakan pertama: raw_import_key bertipe INT, tetapi file "
                        + "menyebutkan string."
        ));
    }

    private static void verifyFeatureGateLanguage(Expected expected) throws Exception {
        try (var owner = Robolectric.buildActivity(PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FeatureGateLabStore.setMasterEnabled(true);

            FeatureGateCatalog.Entry loaded = entry(
                    "raw_loaded_key", "RAW_TITLE_17", "INT", true, true,
                    List.of("8"), List.of("10"), List.of("9"), "7", "INT", null);
            cache(loaded);
            FeatureGateLabStore.saveRule(
                    FeatureGateLabStore.MANAGER_ABMOCK, loaded.key, loaded.type, "11", true);
            FeatureGateDetailFragment loadedPage = FeatureGateDetailFragment.forEntry(
                    loaded.manager, loaded.key, loaded.type);
            attach(activity, loadedPage);

            List<String> loadedText = texts(loadedPage.getView());
            assertTrue(loadedText.toString(), loadedText.contains(expected.overridden));
            assertTrue(loadedText.toString(), loadedText.contains(expected.registeredSource));
            assertTrue("the raw gate key changed", loadedText.contains("raw_loaded_key"));
            assertTrue("the raw manager changed", loadedText.contains("abmock"));
            assertTrue("the raw type changed", loadedText.contains("INT"));

            Spinner spinner = find(loadedPage.getView(), Spinner.class);
            assertNotNull(spinner);
            List<String> optionLabels = new ArrayList<>();
            for (int index = 0; index < spinner.getAdapter().getCount(); index++) {
                optionLabels.add(String.valueOf(spinner.getAdapter().getItem(index)));
            }
            for (String label : new String[]{expected.current, expected.defaults,
                    expected.researched, expected.historical, expected.selected}) {
                assertTrue(optionLabels.toString(), optionLabels.contains(label));
            }

            Method showCustom = FeatureGateDetailFragment.class.getDeclaredMethod("showCustomValue");
            showCustom.setAccessible(true);
            showCustom.invoke(loadedPage);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            AlertDialog custom = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(custom);
            EditText customInput = find(custom.getWindow().getDecorView(), EditText.class);
            customInput.setText("not_an_int");
            custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(expected.invalidInteger, inlineError(customInput).getText().toString());
            custom.dismiss();

            FeatureGateCatalog.Entry unloaded = entry(
                    "raw_unloaded_key", "RAW_TITLE_18", "STRING", false, false,
                    List.of(), List.of(), List.of(), null, null, null);
            cache(unloaded);
            FeatureGateDetailFragment unloadedPage = FeatureGateDetailFragment.forEntry(
                    unloaded.manager, unloaded.key, unloaded.type);
            attach(activity, unloadedPage);
            List<String> unloadedText = texts(unloadedPage.getView());
            assertTrue(unloadedText.toString(), unloadedText.contains(expected.unloaded));
            assertTrue(unloadedText.toString(), unloadedText.contains(expected.localSource));
            assertTrue("the unloaded key changed", unloadedText.contains("raw_unloaded_key"));

            // Under the settings manager rather than App AB: that is the manager whose getter
            // hands the Lab an object to patch, so it is the one whose refusals reach the page.
            FeatureGateCatalog.Entry structured = new FeatureGateCatalog.Entry(
                    "raw_structured_key", "RAW_TITLE_19",
                    FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, "OBJECT", true, true,
                    List.of("{\"payload\":{\"count\":1}}"), List.of(), List.of(), "",
                    "RAW_PROOF_22", true, "{\"payload\":{\"count\":1}}", "OBJECT",
                    StructuredConfig.class.getName());
            cache(structured);
            FeatureGateDetailFragment structuredPage = FeatureGateDetailFragment.forEntry(
                    structured.manager, structured.key, structured.type);
            attach(activity, structuredPage);
            EditText json = find(structuredPage.getView(), EditText.class);
            assertNotNull("the JSON editor was not mounted", json);
            json.setText("not_json");
            ShadowToast.reset();
            View save = structuredPage.getView().findViewWithTag("feature_gate_save_fields");
            assertNotNull(save);
            assertTrue(save.performClick());
            assertEquals(expected.invalidStructured, inlineError(json).getText().toString());

            // A saved override naming a field the class does not have is refused when TikTok
            // asks for the object, and the page has to say which field, in this language.
            FeatureGateLabStore.saveRule(structured.manager, structured.key, structured.type,
                    "{\"raw_missing_field\":2}", true);
            FeatureGateLabRuntime.clearTriggered();
            assertNull("the structured page is not driven by the settings manager",
                    FeatureGateLabRuntime.structuredFailure(
                            structured.manager, structured.key, structured.type));
            FeatureGateLabRuntime.observeSettingsObject(structured.key, StructuredConfig.class,
                    new StructuredConfig(), new StructuredConfig());
            FeatureGateFailure refusal = FeatureGateLabRuntime.structuredFailure(
                    structured.manager, structured.key, structured.type);
            assertNotNull("the runtime recorded no refusal for the missing field", refusal);
            structuredPage.onResume();
            TextView reason = (TextView) structuredPage.getView()
                    .findViewWithTag("feature_gate_status_reason");
            assertNotNull(reason);
            assertEquals(View.VISIBLE, reason.getVisibility());
            assertEquals(expected.unsupportedField, reason.getText().toString());
            assertTrue("the raw field name was translated or dropped",
                    reason.getText().toString().contains("raw_missing_field"));

            FeatureGateCatalog.Entry imported = entry(
                    "raw_import_key", "RAW_TITLE_20", "INT", true, true,
                    List.of(), List.of(), List.of(), "3", "INT", null);
            FeatureGateCatalog.Entry importedInvalid = entry(
                    "raw_invalid_import_key_22", "RAW_TITLE_21", "INT", true, true,
                    List.of(), List.of(), List.of(), "4", "INT", null);
            cache(imported, importedInvalid);
            FeatureGateLabFragment lab = new FeatureGateLabFragment();
            attach(activity, lab);
            JSONObject importFile = new JSONObject()
                    .put("payload_kind", "loaded_values")
                    .put("tiktok_version", FeatureGateLabStore.targetVersion())
                    .put("rules", new JSONArray()
                            .put(new JSONObject()
                                    .put("manager", FeatureGateLabStore.MANAGER_ABMOCK)
                                    .put("key", "raw_import_key")
                                    .put("type", "string")
                                    .put("value", "raw_value_21"))
                            .put(new JSONObject()
                                    .put("manager", FeatureGateLabStore.MANAGER_ABMOCK)
                                    .put("key", "raw_invalid_import_key_22")
                                    .put("type", "INT")
                                    .put("value", "not_an_int")));
            Method review = FeatureGateLabFragment.class.getDeclaredMethod(
                    "reviewLoadedImport", JSONObject.class, FeatureGateCatalog.Snapshot.class);
            review.setAccessible(true);
            var shown = FeatureGateLabFragment.class.getDeclaredField("snapshot");
            shown.setAccessible(true);
            ShadowToast.reset();
            // Reviewed against the values the Lab holds, as an import arriving now would be.
            review.invoke(lab, importFile, shown.get(lab));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(expected.importRejection, ShadowToast.getTextOfLatestToast());
            assertTrue("the rejected raw key was translated or dropped",
                    String.valueOf(ShadowToast.getTextOfLatestToast()).contains("raw_import_key"));
        }
    }

    private static TextView inlineError(EditText field) {
        ViewGroup parent = (ViewGroup) field.getParent();
        return (TextView) parent.getChildAt(parent.indexOfChild(field) + 1);
    }

    private static FeatureGateCatalog.Entry entry(
            String key,
            String title,
            String type,
            boolean registered,
            boolean loaded,
            List<String> defaults,
            List<String> historical,
            List<String> researched,
            String currentValue,
            String currentType,
            String requestedClass
    ) {
        return new FeatureGateCatalog.Entry(
                key, title, FeatureGateLabStore.MANAGER_ABMOCK, type,
                true, registered, defaults, historical, researched, "", "RAW_PROOF_22",
                loaded, currentValue, currentType, requestedClass);
    }

    private static void cache(FeatureGateCatalog.Entry... entries) throws Exception {
        Map<String, FeatureGateCatalog.Entry> byIdentity = new LinkedHashMap<>();
        for (FeatureGateCatalog.Entry entry : entries) byIdentity.put(entry.identity(), entry);
        java.lang.reflect.Field cached = FeatureGateCatalog.class.getDeclaredField("cachedSnapshot");
        cached.setAccessible(true);
        cached.set(null, new FeatureGateCatalog.Snapshot(
                List.of(entries), byIdentity, entries.length, 0, true));
    }

    private static void attach(Activity activity, Fragment fragment) {
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static List<String> texts(View root) {
        List<String> result = new ArrayList<>();
        collectText(root, result);
        return result;
    }

    private static void collectText(View view, List<String> result) {
        if (view instanceof TextView) result.add(((TextView) view).getText().toString());
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectText(group.getChildAt(index), result);
        }
    }

    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            T found = find(group.getChildAt(index), type);
            if (found != null) return found;
        }
        return null;
    }

    private static final class Expected {
        final String invalidInteger;
        final String unloaded;
        final String overridden;
        final String registeredSource;
        final String localSource;
        final String current;
        final String defaults;
        final String researched;
        final String historical;
        final String selected;
        final String invalidStructured;
        final String unsupportedField;
        final String importRejection;

        Expected(
                String invalidInteger,
                String unloaded,
                String overridden,
                String registeredSource,
                String localSource,
                String current,
                String defaults,
                String researched,
                String historical,
                String selected,
                String invalidStructured,
                String unsupportedField,
                String importRejection
        ) {
            this.invalidInteger = invalidInteger;
            this.unloaded = unloaded;
            this.overridden = overridden;
            this.registeredSource = registeredSource;
            this.localSource = localSource;
            this.current = current;
            this.defaults = defaults;
            this.researched = researched;
            this.historical = historical;
            this.selected = selected;
            this.invalidStructured = invalidStructured;
            this.unsupportedField = unsupportedField;
            this.importRejection = importRejection;
        }
    }
}
