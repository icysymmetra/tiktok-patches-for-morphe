package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.os.Looper;
import android.util.AtomicFile;
import app.morphe.extension.shared.BackgroundPoolSaturation;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabRuntime;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import android.view.View;
import android.widget.TextView;
import app.morphe.extension.tiktok.settings.preference.SettingsBackupPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsBackupTest {
    @After public void tearDownStatus() {
        SettingsStatus.diagnosticsEnabled = false;
        // Settings live in a static registry that outlives this class, and one test here saves a
        // German app language. Utils.setContext reads that setting and, when it is not the
        // default, hands back a createConfigurationContext copy instead of the context it was
        // given. Any later test in the same JVM that installs a ContextWrapper to fail a file
        // write then had its override quietly dropped, so the operation succeeded and the test
        // failed asking where its exception went. Which class that hit depended on run order.
        for (Setting<?> setting : Setting.allLoadedSettings()) setting.resetToDefault();
        Utils.setContext(RuntimeEnvironment.getApplication());
    }
    @Before public void setup() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.REGION_SPOOF.get();
        for (Setting<?> setting : Setting.allLoadedSettings()) setting.resetToDefault();
        FeatureGateLabStore.resetAllLabData();
        AtomicFile journal = new AtomicFile(new File(Utils.getContext().getFilesDir(), SettingsOperationJournal.FILE_NAME));
        journal.delete();
        new AtomicFile(new File(Utils.getContext().getFilesDir(), "feature-gate-lab-undo.json")).delete();
        SettingsOperationJournal.consumeRecoveryNotice();
    }

    @Test public void everySwitchAndTypedValuesReturnAfterPreferencesAreCleared() throws Exception {
        Map<Setting<?>, Object> expected = new LinkedHashMap<>();
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (setting instanceof BooleanSetting) ((BooleanSetting) setting).save(!(Boolean) setting.defaultValue);
        }
        Settings.BLOCKED_SOUND_NAMES.save("音楽, Straße, original sound");
        Settings.MAX_VIDEO_SECONDS.save(90);
        Settings.REMEMBERED_SPEED.save(2.5f);
        BaseSettings.MORPHE_LANGUAGE.save(AppLanguage.DE);
        BaseSettings.DEBUG_LOG_FILTERS.save("network");
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            if (setting.includeWithImportExport || setting == BaseSettings.DEBUG_LOG_FILTERS) expected.put(setting, setting.get());
        }
        // The one switch a file can't turn on (NetworkProxyTest.aRestoredFileNeverTurnsTheProxyOnOrMovesIt).
        expected.put(Settings.NETWORK_PROXY, false);
        FeatureGateLabStore.saveRule("abmock", "test_gate", "BOOLEAN", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        FeatureGateLabStore.acknowledgeWarning();
        String backup = SettingsBackup.create(false);
        Setting.preferences.preferences.edit().clear().commit();
        for (Setting<?> setting : expected.keySet()) setting.resetToDefault();
        FeatureGateLabStore.resetAllLabData();
        SettingsBackup.restore(Utils.getContext(), backup, true);
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getKey().key, entry.getValue(), entry.getKey().get());
            if (!entry.getKey().isSetToDefault()) assertTrue(Setting.preferences.preferences.contains(entry.getKey().key));
        }
        assertTrue(FeatureGateLabStore.masterEnabled());
        assertTrue(FeatureGateLabStore.warningAcknowledged());
        var rule = FeatureGateLabStore.rule("abmock", "test_gate", "BOOLEAN");
        assertNotNull(rule);
        assertTrue(rule.enabled);
        assertEquals("true", rule.value);
    }

    @Test public void aBackupFromBeforeTheDownloadPathSplitFillsInTheThreeDestinations()
            throws Exception {
        // The one shot down_path migration cannot help here: its flag is already true on the
        // device and was never in a backup, so the three destinations took their default and a
        // custom folder went with them.
        Settings.DOWNLOAD_PATH.save("Pictures/Saved");
        String legacy = withoutKeys(
                new JSONObject(SettingsBackup.create(false)).put("format", "metra-settings").toString(),
                Settings.DOWNLOAD_VIDEO_PATH.key,
                Settings.DOWNLOAD_PHOTO_PATH.key,
                Settings.DOWNLOAD_STICKER_PATH.key);
        Settings.DOWNLOAD_PATH.resetToDefault();
        Settings.DOWNLOAD_VIDEO_PATH.save("DCIM/Somewhere");

        // The three are accounted for by the migration, so they are not reported as left alone.
        assertEquals(0, SettingsBackup.settingsNotInFile(legacy));
        SettingsBackup.restore(Utils.getContext(), legacy, true);

        // Pictures holds photos but not videos or stickers: those two keep the device's folder
        // and the restore names them, instead of saving to DCIM/TikTok while showing Pictures.
        assertEquals("DCIM/Somewhere", Settings.DOWNLOAD_VIDEO_PATH.get());
        assertEquals("Pictures/Saved", Settings.DOWNLOAD_PHOTO_PATH.get());
        assertEquals("DCIM/TikTok", Settings.DOWNLOAD_STICKER_PATH.get());
        assertEquals(java.util.List.of(app.morphe.extension.tiktok.download.DownloadDestination.Kind.VIDEO,
                        app.morphe.extension.tiktok.download.DownloadDestination.Kind.STICKER),
                SettingsBackup.foldersKept(legacy));
    }

    /**
     * A restore that kept a device folder wrote less than its file carries. Its journal, left
     * behind by a failed delete, used to read at the next start as an interrupted restore and put
     * the old settings back over a restore that had finished.
     */
    /** The same for a network proxy the file wasn't allowed to turn on: the file says on, the phone off. */
    @Test public void aJournalLeftByARestoreThatHeldTheProxyBackReadsAsCommitted() throws Exception {
        Settings.NETWORK_PROXY.save(true);
        Settings.NETWORK_PROXY_HOST.save("theirs.example.com");
        Settings.REGION_SPOOF.save(true);
        String backup = SettingsBackup.create(false);
        Settings.NETWORK_PROXY.save(false);
        Settings.NETWORK_PROXY_HOST.save("");
        Settings.REGION_SPOOF.save(false);

        SettingsOperationJournal.failCommittedDeletesForTests(true);
        try {
            SettingsBackup.restore(Utils.getContext(), backup, true);
        } finally {
            SettingsOperationJournal.failCommittedDeletesForTests(false);
        }
        assertTrue("no journal was left behind, so this checks nothing",
                new java.io.File(Utils.getContext().getFilesDir(), "hushfeed-settings-operation.json").isFile());

        SettingsOperationJournal.acquire(Utils.getContext()).complete();

        assertTrue("the next start put a committed restore back", Settings.REGION_SPOOF.get());
        assertFalse(Settings.NETWORK_PROXY.get());
        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.consumeRecoveryNotice());
    }

    @Test public void aJournalLeftByARestoreThatKeptAFolderReadsAsCommitted() throws Exception {
        Settings.DOWNLOAD_VIDEO_PATH.save("Pictures/Clips");
        Settings.REGION_SPOOF.save(true);
        String backup = SettingsBackup.create(false);
        Settings.DOWNLOAD_VIDEO_PATH.save("Movies/Mine");
        Settings.REGION_SPOOF.save(false);

        SettingsOperationJournal.failCommittedDeletesForTests(true);
        try {
            SettingsBackup.restore(Utils.getContext(), backup, true);
        } finally {
            SettingsOperationJournal.failCommittedDeletesForTests(false);
        }
        assertTrue("no journal was left behind, so this checks nothing",
                new java.io.File(Utils.getContext().getFilesDir(), "hushfeed-settings-operation.json").isFile());

        SettingsOperationJournal.acquire(Utils.getContext()).complete();

        assertTrue("the next start put a committed restore back", Settings.REGION_SPOOF.get());
        assertEquals("Movies/Mine", Settings.DOWNLOAD_VIDEO_PATH.get());
        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.consumeRecoveryNotice());
    }

    /** A folder that can't hold its kind keeps the device's; the rest restores; undo puts all back. */
    @Test public void aRestoredFolderThatCantHoldItsKindKeepsTheDevicesAndUndoPutsItAllBack()
            throws Exception {
        Settings.DOWNLOAD_VIDEO_PATH.save("Pictures/Clips");
        Settings.DOWNLOAD_PHOTO_PATH.save("Pictures/Hush");
        String backup = SettingsBackup.create(false);
        Settings.DOWNLOAD_VIDEO_PATH.save("Movies/Mine");
        Settings.DOWNLOAD_PHOTO_PATH.save("DCIM/Before");

        SettingsBackup.restore(Utils.getContext(), backup, true);
        assertEquals("the file's Pictures folder can't hold videos", "Movies/Mine", Settings.DOWNLOAD_VIDEO_PATH.get());
        assertEquals("Pictures/Hush", Settings.DOWNLOAD_PHOTO_PATH.get());
        assertEquals(java.util.List.of(app.morphe.extension.tiktok.download.DownloadDestination.Kind.VIDEO),
                SettingsBackup.foldersKept(backup));

        SettingsBackup.undo(Utils.getContext());
        assertEquals("Movies/Mine", Settings.DOWNLOAD_VIDEO_PATH.get());
        assertEquals("DCIM/Before", Settings.DOWNLOAD_PHOTO_PATH.get());
    }

    /** The same file with those keys taken out of both the values and the declared inventory. */
    private static String withoutKeys(String backup, String... keys) throws Exception {
        JSONObject root = new JSONObject(backup);
        JSONObject values = root.getJSONObject("settings");
        java.util.List<String> dropped = java.util.Arrays.asList(keys);
        for (String key : keys) {
            assertTrue(key + " is not in the backup to begin with", values.has(key));
            values.remove(key);
        }
        org.json.JSONArray kept = new org.json.JSONArray();
        org.json.JSONArray declared = root.getJSONArray("setting_keys");
        for (int i = 0; i < declared.length(); i++) {
            String key = declared.getString(i);
            if (!dropped.contains(key)) kept.put(key);
        }
        return root.put("setting_keys", kept).toString();
    }

    /**
     * Undo used to leave its copy as it was and keep nothing of what it replaced, so an Undo a
     * week after a restore wiped the week's changes with no way back. A second Undo brings them.
     */
    @Test public void aSecondUndoBringsBackWhatTheFirstReplaced() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(11);
        String restored = SettingsBackup.create(false);
        Settings.MAX_VIDEO_SECONDS.save(22);
        SettingsBackup.restore(Utils.getContext(), restored, true);
        assertEquals(11, (int) Settings.MAX_VIDEO_SECONDS.get());
        Settings.MAX_VIDEO_SECONDS.save(33);

        SettingsBackup.undo(Utils.getContext());
        assertEquals(22, (int) Settings.MAX_VIDEO_SECONDS.get());
        SettingsBackup.undo(Utils.getContext());
        assertEquals("the changes made after the restore were lost", 33, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    @Test public void malformedLateValuesNeverPartiallyApplyOrReplaceUndo() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(42);
        SettingsBackup.reset(Utils.getContext());
        String baseline = SettingsBackup.create(false);
        for (Object bad : new Object[]{"bad", true, 1.25, 2147483648L, JSONObject.NULL}) {
            JSONObject root = new JSONObject(baseline);
            root.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true).put(Settings.MAX_VIDEO_SECONDS.key, bad);
            assertThrows(Exception.class, () -> SettingsBackup.restore(Utils.getContext(), root.toString(), true));
            assertEquals(baseline, SettingsBackup.create(false));
        }
        SettingsBackup.undo(Utils.getContext());
        assertEquals(42, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    @Test public void invalidLabRuleAndTrailingInputLeaveSettingsUntouched() throws Exception {
        // A backup stamped with another TikTok version used to belong in this list. It does not
        // any more: the settings half of a backup does not depend on the TikTok build, so it is
        // restored and only the Lab rules are dropped. That case is its own test below.
        Settings.REGION_SPOOF.save(true);
        String baseline = SettingsBackup.create(false);
        JSONObject wrongLab = new JSONObject(baseline);
        wrongLab.getJSONObject("lab").put("master", "true");
        JSONObject badRule = new JSONObject(baseline);
        badRule.getJSONObject("lab").getJSONArray("rules").put(new JSONObject()
                .put("manager", "abmock").put("key", "bad").put("type", "FLOAT").put("value", "NaN").put("force", true));
        for (String invalid : new String[]{wrongLab.toString(), badRule.toString(), baseline + "garbage", "[]"}) {
            assertThrows(Exception.class, () -> SettingsBackup.restore(Utils.getContext(), invalid, true));
            assertEquals(baseline, SettingsBackup.create(false));
        }
    }

    /**
     * The Lab's own undo copy held the rules from before its last change. Pressed after a
     * restore that wrote the Lab, it took back every rule the restore had put there.
     */
    @Test public void aRestoreThatWritesTheLabRetiresTheLabsOwnUndo() throws Exception {
        java.io.File labUndo = new java.io.File(Utils.getContext().getFilesDir(), "feature-gate-lab-undo.json");
        java.nio.file.Files.write(labUndo.toPath(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JSONObject backup = new JSONObject(SettingsBackup.create(false));
        backup.getJSONObject("lab").getJSONArray("rules").put(new JSONObject().put("manager", "abmock")
                .put("key", "restored_gate").put("type", "BOOLEAN").put("value", "true").put("force", true));

        SettingsBackup.restore(Utils.getContext(), backup.toString(), true);

        assertEquals(1, FeatureGateLabStore.rules().size());
        assertFalse("the Lab kept an undo that would take the restored rules back", labUndo.exists());
        FeatureGateLabStore.resetAllLabData();
    }

    @Test public void aBackupWithMoreLabRulesThanTheLabKeepsIsRefusedByName() throws Exception {
        // Some 26,000 of these fit the 2 MB cap. Restored, they made every later backup and Lab
        // undo copy too large to write, which refused Restore, Reset and every Lab change.
        String baseline = SettingsBackup.create(false);
        JSONObject many = new JSONObject(baseline);
        org.json.JSONArray rules = many.getJSONObject("lab").getJSONArray("rules");
        for (int i = 0; i < 2000; i++) {
            rules.put(new JSONObject().put("manager", "abmock").put("key", "gate" + i)
                    .put("type", "BOOLEAN").put("value", "true").put("force", true));
        }

        assertEquals(SettingsBackup.Reason.LAB_RULES, reasonFor(many.toString()));
        assertEquals("That settings backup holds more Feature Gate Lab rules than the Lab takes. "
                + "Nothing was altered.", sentenceFor(many.toString()));
        assertEquals(baseline, SettingsBackup.create(false));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
    }

    @Test public void oversizedFeedRuleListsAreRejectedBeforeAnyBackupWrite() throws Exception {
        String baseline = SettingsBackup.create(false);
        String tooMany = ruleEntries(FeedRuleLimits.MAX_ENTRIES + 1);
        StringBuilder tooLarge = new StringBuilder(FeedRuleLimits.MAX_UTF8_BYTES / 2 + 1);
        for (int index = 0; index <= FeedRuleLimits.MAX_UTF8_BYTES / 2; index++) {
            tooLarge.append('é');
        }
        for (String key : new String[]{Settings.BLOCKED_CAPTION_WORDS.key,
                Settings.BLOCKED_CREATORS.key, Settings.LOCAL_HIDDEN_CREATORS.key,
                Settings.CREATOR_FILTER_EXCEPTIONS.key}) {
            for (String invalid : new String[]{tooMany, tooLarge.toString()}) {
                JSONObject backup = new JSONObject(baseline);
                backup.getJSONObject("settings").put(key, invalid);
                assertEquals(key, SettingsBackup.Reason.RULE_LIST,
                        reasonFor(backup.toString()));
                assertEquals("That settings backup contains a feed rule list larger than "
                                + "Metra accepts. Nothing was altered.",
                        sentenceFor(backup.toString()));
                assertEquals("a rejected " + key + " value changed storage",
                        baseline, SettingsBackup.create(false));
            }
        }
    }

    /**
     * A list stored before the 2026-09-14 limits: load() keeps what an older build saved, and the
     * bound only runs on save. Every restore reads the device's own state first, so reading that
     * with the limits made restore, reset and undo on such a phone all fail with the generic
     * message. The save row refuses instead, naming the list, since every restore would refuse
     * the file it wrote.
     */
    @Test public void aListKeptFromBeforeTheLimitsLeavesRestoreResetAndUndoWorking() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        String backup = SettingsBackup.create(false);
        String oversized = ruleEntries(FeedRuleLimits.MAX_ENTRIES + 1);
        storedByAnOlderBuild(Settings.BLOCKED_CREATORS, oversized);
        assertEquals(oversized, Settings.BLOCKED_CREATORS.get());

        SettingsBackup.restore(context, backup, true);
        assertEquals("a file that carries the list replaces it", "", Settings.BLOCKED_CREATORS.get());
        SettingsBackup.undo(context);
        assertEquals("the way back puts the older list back as it was", oversized, Settings.BLOCKED_CREATORS.get());

        JSONObject without = new JSONObject(backup);
        without.getJSONObject("settings").remove(Settings.BLOCKED_CREATORS.key);
        JSONArray keys = without.getJSONArray("setting_keys");
        for (int index = keys.length() - 1; index >= 0; index--) {
            if (Settings.BLOCKED_CREATORS.key.equals(keys.getString(index))) keys.remove(index);
        }
        SettingsBackup.restore(context, without.toString(), false);
        assertEquals("a file without the list leaves it alone", oversized, Settings.BLOCKED_CREATORS.get());

        SettingsBackup.reset(context);
        assertEquals("", Settings.BLOCKED_CREATORS.get());

        storedByAnOlderBuild(Settings.LOCAL_HIDDEN_CREATORS, oversized);
        SettingsBackup.RuleListTooLarge refused =
                assertThrows(SettingsBackup.RuleListTooLarge.class, SettingsBackup::export);
        assertEquals("Creators hidden on this phone", refused.listTitle);
        storedByAnOlderBuild(Settings.LOCAL_HIDDEN_CREATORS, "");
        assertEquals(SettingsBackup.create(false), SettingsBackup.export());
    }

    /** Writes a value the way an older build left it, past the bound a save applies. */
    private static void storedByAnOlderBuild(app.morphe.extension.shared.settings.StringSetting setting, String value) {
        Setting.preferences.preferences.edit().putString(setting.key, value).commit();
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(setting, "load");
    }

    private static String ruleEntries(int count) {
        StringBuilder value = new StringBuilder(count * 8);
        for (int index = 0; index < count; index++) {
            if (index > 0) value.append(',');
            value.append("item").append(index);
        }
        return value.toString();
    }

    @Test public void aBackupFromAnotherTikTokVersionRestoresSettingsAndLeavesTheLabAlone() throws Exception {
        // The target stamp is there for the Lab rules, which name gates in one TikTok build. It
        // used to refuse the whole file, so the day this project retargets, every backup anyone
        // holds stops restoring, settings included.
        Settings.BLOCKED_CREATORS.save("from the backup");
        Settings.AUTO_ADVANCE.save(true);
        String backup = SettingsBackup.create(false);
        String otherVersion = fromUnknownBuild(new JSONObject(backup)).toString();

        // State that must survive: a Lab rule this backup knows nothing about.
        Settings.BLOCKED_CREATORS.save("changed since");
        Settings.AUTO_ADVANCE.save(false);
        FeatureGateLabStore.saveRule("abmock", "rule_for_this_build", "INT", "7", true);
        FeatureGateLabStore.setMasterEnabled(true);

        SettingsBackup.restore(Utils.getContext(), otherVersion, true);

        assertEquals("the settings half did not restore", "from the backup", Settings.BLOCKED_CREATORS.get());
        assertTrue("the settings half did not restore", Settings.AUTO_ADVANCE.get());
        assertTrue("the caller was not told the Lab rules were left out",
                SettingsBackup.labRulesWereSkipped(otherVersion));
        assertNotNull("the Lab was emptied by a backup that said nothing about this build",
                FeatureGateLabStore.rule("abmock", "rule_for_this_build", "INT"));
        assertTrue("the Lab master switch was changed by a backup for another build",
                FeatureGateLabStore.masterEnabled());
    }

    /**
     * A backup whose Lab block can't be tied to a TikTok build: the target is one no catalog has
     * and the Lab names no build of its own, so nothing says which gates its rules mean.
     */
    private static JSONObject fromUnknownBuild(JSONObject backup) throws Exception {
        backup.put("target", "40.0.0");
        backup.getJSONObject("lab").remove("tiktok_version");
        return backup;
    }

    @Test public void everyRefusalSaysWhichOneItWas() throws Exception {
        String baseline = SettingsBackup.create(false);
        JSONObject wrongFormat = new JSONObject(baseline).put("format", "something-else");
        JSONObject wrongSchema = new JSONObject(baseline).put("schema", 99);
        JSONObject shortKeys = new JSONObject(baseline).put("setting_keys", new org.json.JSONArray());
        JSONObject badValue = new JSONObject(baseline);
        badValue.getJSONObject("settings").put(Settings.AUTO_ADVANCE.key, "not a boolean");

        assertEquals(SettingsBackup.Reason.FORMAT, reasonFor(wrongFormat.toString()));
        assertEquals(SettingsBackup.Reason.SCHEMA, reasonFor(wrongSchema.toString()));
        // Newer than this build reads, and not a number at all, are both the schema's refusal.
        assertEquals(SettingsBackup.Reason.SCHEMA,
                reasonFor(new JSONObject(baseline).put("schema", SettingsBackup.SCHEMA + 1).toString()));
        assertEquals(SettingsBackup.Reason.SCHEMA,
                reasonFor(new JSONObject(baseline).put("schema", "1").toString()));
        assertEquals(SettingsBackup.Reason.SCHEMA,
                reasonFor(new JSONObject(baseline).put("schema", 0).toString()));
        assertEquals("a fractional schema was silently truncated",
                SettingsBackup.Reason.SCHEMA,
                reasonFor(new JSONObject(baseline).put("schema", 1.5).toString()));
        assertEquals("an overflowing schema wrapped into a supported number",
                SettingsBackup.Reason.SCHEMA,
                reasonFor(new JSONObject(baseline).put("schema", 4_294_967_297L).toString()));
        // A file from before the key existed is the first schema, not a damaged one.
        JSONObject noSchema = new JSONObject(baseline);
        noSchema.remove("schema");
        SettingsBackup.restore(Utils.getContext(), noSchema.toString(), true);
        assertEquals(SettingsBackup.Reason.INCOMPLETE, reasonFor(shortKeys.toString()));
        assertEquals(SettingsBackup.Reason.VALUE, reasonFor(badValue.toString()));

        // And each of them reaches the user as its own sentence rather than one shared rejection,
        // which is what made a truncated download and a good backup read the same.
        java.util.Set<String> sentences = new java.util.HashSet<>();
        for (String input : new String[]{wrongFormat.toString(), wrongSchema.toString(),
                shortKeys.toString(), badValue.toString()}) {
            sentences.add(sentenceFor(input));
        }
        assertEquals("two refusals share a sentence, so the user cannot tell them apart",
                4, sentences.size());
    }

    @Test public void aFinishedRestoreFromAnotherTikTokVersionIsNotUndoneAtNextLaunch() throws Exception {
        // A journal that outlives its own commit is ordinary: the delete after a commit is
        // allowed to fail, on the reasoning that the after snapshot still describes the result.
        // Reconciliation compares the live settings against that snapshot, and a snapshot that
        // leaves the Lab alone carries no rules to compare, so the comparison threw, was caught
        // as "does not match", and the startup path rolled a successful restore back.
        var app = Utils.getContext();
        Settings.BLOCKED_CREATORS.save("from the backup");
        String backup = fromUnknownBuild(new JSONObject(SettingsBackup.create(false))).toString();

        Settings.BLOCKED_CREATORS.save("changed since");
        String before = SettingsBackup.create(false);
        SettingsBackup.restore(app, backup, true);
        assertEquals("the restore itself did not take", "from the backup", Settings.BLOCKED_CREATORS.get());

        writeJournal("settings", before, backup);
        assertEquals("a finished restore was read as interrupted",
                SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.initialize(app));
        assertEquals("the settings the user restored were reverted at startup",
                "from the backup", Settings.BLOCKED_CREATORS.get());
    }

    @Test public void aFinishedRestoreThatClampedAValueIsNotUndoneAtNextLaunch() throws Exception {
        // The journal keeps the file as the after snapshot, and the save keeps each number in its
        // setting's range. A backup edited, or written by an older build, to hold 90 for a
        // setting that stops at 60 saved 60, matched neither snapshot at the next start, and the
        // restore that had finished was put back with a note saying it was interrupted.
        var app = Utils.getContext();
        Settings.EDGE_SEEK_SECONDS.save(5);
        JSONObject edited = new JSONObject(SettingsBackup.create(false));
        edited.getJSONObject("settings").put(Settings.EDGE_SEEK_SECONDS.key, 90);
        String backup = edited.toString();

        Settings.EDGE_SEEK_SECONDS.save(10);
        String before = SettingsBackup.create(false);
        SettingsBackup.restore(app, backup, true);
        assertEquals("the save did not keep the value in range", 60, (int) Settings.EDGE_SEEK_SECONDS.get());

        writeJournal("settings", before, backup);
        assertEquals("a finished restore was read as interrupted",
                SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.initialize(app));
        assertEquals("the restored value was put back at startup", 60, (int) Settings.EDGE_SEEK_SECONDS.get());
    }

    @Test public void aBackupThatStopsHalfwayThroughSaysSoRatherThanRefusingWithoutAReason()
            throws Exception {
        // Valid UTF-8, valid nothing else: exactly what a download that was cut off looks like.
        String half = SettingsBackup.create(false);
        half = half.substring(0, half.length() / 2);

        assertEquals(SettingsBackup.Reason.DAMAGED, reasonFor(half));

        // Its own sentence, not the one a photograph or a newer Hushfeed's backup gets.
        String damaged = sentenceFor(half);
        String wrongFormat = sentenceFor(
                new JSONObject(SettingsBackup.create(false)).put("format", "something-else").toString());
        String unknown = sentenceFor("{\"format\":\"hushfeed-settings\"}");
        assertNotEquals("a cut-off download reads the same as a file that is not a backup",
                wrongFormat, damaged);
        assertNotEquals("a cut-off download still reads as the unexplained rejection",
                unknown, damaged);
    }

    @Test public void aFileThatCannotEvenBeReadIsRefusedWithItsOwnReason() throws Exception {
        var app = Utils.getContext();
        // Bytes that are not UTF-8 at all, the shape of a file damaged in transit.
        byte[] notText = {(byte) 0xC3, (byte) 0x28, (byte) 0xA0, (byte) 0xA1};
        assertEquals("an unreadable file is still refused without a reason",
                SettingsBackup.Reason.ENCODING,
                reasonForStream(app, new java.io.ByteArrayInputStream(notText)));

        byte[] tooBig = new byte[SettingsBackup.MAX_BYTES + 1024];
        java.util.Arrays.fill(tooBig, (byte) 'x');
        assertEquals("an oversized file is still refused without a reason",
                SettingsBackup.Reason.SIZE,
                reasonForStream(app, new java.io.ByteArrayInputStream(tooBig)));
    }

    private static SettingsBackup.Reason reasonForStream(android.content.Context app,
            java.io.InputStream input) {
        try {
            SettingsBackup.restoreFrom(app, input, true);
        } catch (SettingsBackup.RestoreException rejected) {
            return rejected.getReason();
        } catch (Exception other) {
            throw new AssertionError("expected a RestoreException, got " + other, other);
        }
        throw new AssertionError("that input was accepted");
    }

    private static SettingsBackup.Reason reasonFor(String text) {
        try {
            SettingsBackup.restore(Utils.getContext(), text, true);
        } catch (SettingsBackup.RestoreException rejected) {
            return rejected.getReason();
        } catch (Exception other) {
            throw new AssertionError("expected a RestoreException, got " + other, other);
        }
        throw new AssertionError("that input was accepted");
    }

    /** The sentence the user actually sees. failureMessage is package private one package over. */
    private static String sentenceFor(String text) throws Exception {
        Exception refusal;
        try {
            SettingsBackup.restore(Utils.getContext(), text, true);
            throw new AssertionError("that input was accepted");
        } catch (SettingsBackup.RestoreException rejected) {
            refusal = rejected;
        }
        Class<?> preference = Class.forName(
                "app.morphe.extension.tiktok.settings.preference.SettingsBackupPreference");
        java.lang.reflect.Method message =
                preference.getDeclaredMethod("failureMessage", int.class, Exception.class);
        message.setAccessible(true);
        return (String) message.invoke(null, 7312 /* IMPORT */, refusal);
    }

    /**
     * A reset sets the setup Calm feed saved aside, and Undo brings it back with the settings.
     * The card went on offering "Restore setup" after a reset, which would have put back what
     * the reset had just cleared; dropping the setup instead lost it for good on Undo.
     */
    @Test public void aResetSetsTheCalmFeedSetupAsideAndUndoBringsItBack() throws Exception {
        android.content.Context context = Utils.getContext();
        Settings.HIDE_LIVE.save(false);
        CalmFeedPreset.apply(context);
        assertEquals(CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));

        SettingsBackup.reset(context);
        assertFalse("the Calm feed card still offers the setup from before the reset",
                CalmFeedPreset.hasSnapshot(context));

        SettingsBackup.undo(context);
        assertEquals("Undo brought the settings back without the setup they came with",
                CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));
        CalmFeedPreset.restore(context);
        assertFalse("the setup put back is not the one saved before Calm feed", Settings.HIDE_LIVE.get());

        // Undo swaps with what it replaced: once more puts the reset back, with no setup to
        // offer, and once more after that brings Calm feed back with the setup saved for it.
        CalmFeedPreset.apply(context);
        SettingsBackup.undo(context);
        assertFalse("the reset came back with a setup from before it", CalmFeedPreset.hasSnapshot(context));
        SettingsBackup.undo(context);
        assertEquals(CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));
    }

    /**
     * A restore that fails after writing the undo copy leaves Undo on offer, and the banner asks
     * for it when putting things back needs it. The Calm feed setup went aside only once a
     * restore had worked, so that Undo swapped in whatever an earlier reset had left there, or
     * nothing, and took the current setup away; a reset and Undo after that lost it for good.
     */
    @Test public void anUndoAfterAFailedImportKeepsTheCalmFeedSetup() throws Exception {
        android.content.Context context = Utils.getContext();
        Settings.HIDE_LIVE.save(false);
        CalmFeedPreset.apply(context);
        JSONObject next = new JSONObject(SettingsBackup.create(false));
        next.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);
        var original = Setting.preferences.preferences;
        var field = app.morphe.extension.shared.settings.preference.SharedPrefCategory.class
                .getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(Setting.preferences, failingCommits(original, () -> true, () -> {}));
        try {
            assertThrows(Exception.class, () -> SettingsBackup.restore(context, next.toString(), true));
        } finally {
            field.set(Setting.preferences, original);
        }
        assertEquals(CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));

        SettingsBackup.undo(context);
        assertEquals("the Undo after a failed import took the Calm feed setup away",
                CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));
        SettingsBackup.reset(context);
        SettingsBackup.undo(context);
        assertEquals("a reset and its Undo lost the setup",
                CalmFeedPreset.State.ACTIVE, CalmFeedPreset.state(context));
        CalmFeedPreset.restore(context);
        assertFalse("the setup put back is not the one saved before Calm feed", Settings.HIDE_LIVE.get());
    }

    @Test public void resetAndUndoRestoreBothStoresAndSurviveAnUnrelatedSettingChange() throws Exception {
        Settings.BLOCKED_CREATORS.save("creator");
        Settings.AUTO_ADVANCE.save(true);
        FeatureGateLabStore.saveRule("abmock", "another_gate", "INT", "3", false);
        FeatureGateLabStore.setMasterEnabled(true);
        SettingsBackup.reset(Utils.getContext());
        assertEquals("", Settings.BLOCKED_CREATORS.get());
        assertFalse(Settings.AUTO_ADVANCE.get());
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertTrue(SettingsBackup.hasUndo(Utils.getContext()));
        Settings.BLOCKED_CREATORS.save("later change");
        SettingsBackup.undo(Utils.getContext());
        assertEquals("creator", Settings.BLOCKED_CREATORS.get());
        assertTrue(Settings.AUTO_ADVANCE.get());
        assertTrue(FeatureGateLabStore.masterEnabled());
        assertEquals("3", FeatureGateLabStore.rule("abmock", "another_gate", "INT").value);
    }

    @Test public void inputIsBoundedAndRejectsMalformedUtf8() throws Exception {
        byte[] bytes = SettingsBackup.create(false).getBytes(StandardCharsets.UTF_8);
        assertEquals(new String(bytes, StandardCharsets.UTF_8), SettingsBackup.read(new ByteArrayInputStream(bytes)));
        assertThrows(java.io.IOException.class, () -> SettingsBackup.read(new ByteArrayInputStream(new byte[SettingsBackup.MAX_BYTES + 1])));
        assertThrows(java.io.IOException.class, () -> SettingsBackup.read(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})));
    }

    @Test public void exactNumericTokensAndTrailingNulAreValidatedBeforeAnyBackupChanges() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(42);
        SettingsBackup.reset(Utils.getContext());
        String baseline = SettingsBackup.create(false);
        JSONObject fractional = new JSONObject(baseline);
        fractional.getJSONObject("settings").put(Settings.MAX_VIDEO_SECONDS.key, "precise-number");
        for (String invalid : new String[]{fractional.toString().replace("\"precise-number\"", "1.00000000000000001"),
                baseline + '\0' + "garbage"}) {
            assertThrows(Exception.class, () -> SettingsBackup.restore(Utils.getContext(), invalid, true));
            assertEquals(baseline, SettingsBackup.create(false));
        }
        SettingsBackup.undo(Utils.getContext());
        assertEquals(42, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    @Test public void missingSettingsAreRejectedBeforeWritingUndoOrChangingValues() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(34);
        String original = SettingsBackup.create(false);
        JSONObject empty = new JSONObject(original).put("settings", new JSONObject());
        JSONObject missing = new JSONObject(original);
        missing.getJSONObject("settings").remove(Settings.REGION_SPOOF.key);
        for (String text : new String[]{empty.toString(), missing.toString()}) {
            assertThrows(Exception.class, () -> SettingsBackup.restore(Utils.getContext(), text, true));
            assertEquals(original, SettingsBackup.create(false));
        }
    }

    @Test public void aFailureBeforeTheLabIsReachedLeavesTheLabAlone() throws Exception {
        // The apply writes the ordinary settings first, so a failure in those never reaches the
        // Lab. Putting it back anyway is not free: replaceSettings clears every triggered marker
        // and raises a restart notice, for a store the failed restore did not touch.
        var app = Utils.getContext();
        FeatureGateLabStore.saveRule("abmock", "untouched", "BOOLEAN", "true", true);
        JSONObject next = new JSONObject(SettingsBackup.create(false));
        next.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);

        var original = Setting.preferences.preferences;
        var labWrites = new java.util.concurrent.atomic.AtomicInteger();
        var normal = failingCommits(original, () -> true, () -> {});
        var lab = failingCommits(app.getSharedPreferences("morphe_feature_gate_lab", 0),
                () -> false, labWrites::incrementAndGet);
        var field = app.morphe.extension.shared.settings.preference.SharedPrefCategory.class
                .getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(Setting.preferences, normal);
        Utils.setContext(new android.content.ContextWrapper(app) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("morphe_feature_gate_lab") ? lab : super.getSharedPreferences(name, mode);
            }
        });
        try {
            assertThrows(Exception.class,
                    () -> SettingsBackup.restore(Utils.getContext(), next.toString(), true));
            assertEquals("the Lab was written on a failure that never reached it",
                    0, labWrites.get());
        } finally {
            field.set(Setting.preferences, original);
            Utils.setContext(app);
        }
        assertNotNull("the rule the restore never touched is gone",
                FeatureGateLabStore.rule("abmock", "untouched", "BOOLEAN"));
    }

    @Test public void rollbackStillAttemptsLabWhenOrdinaryPreferenceRecoveryFails() throws Exception {
        var app = Utils.getContext();
        FeatureGateLabStore.saveRule("abmock", "rollback_gate", "BOOLEAN", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        // A gate that actually fired before the restore was attempted. The rollback puts these
        // very rules back, so what the Lab recorded about them is still true afterwards.
        FeatureGateLabRuntime.reloadRules();
        assertTrue(FeatureGateLabRuntime.overrideBoolean("rollback_gate", false));
        assertTrue(FeatureGateLabRuntime.isTriggered("abmock", "rollback_gate", "BOOLEAN"));
        JSONObject next = new JSONObject(SettingsBackup.create(false));
        next.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);
        next.getJSONObject("lab").put("master", false);
        var original = Setting.preferences.preferences;
        var normalFailure = new java.util.concurrent.atomic.AtomicBoolean();
        var labCommits = new java.util.concurrent.atomic.AtomicInteger();
        var normal = failingCommits(original, normalFailure::get, () -> {});
        var lab = failingCommits(app.getSharedPreferences("morphe_feature_gate_lab", 0),
                () -> labCommits.get() == 1, () -> {
                    if (labCommits.incrementAndGet() == 1) normalFailure.set(true);
                });
        var field = app.morphe.extension.shared.settings.preference.SharedPrefCategory.class.getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(Setting.preferences, normal);
        Utils.setContext(new android.content.ContextWrapper(app) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("morphe_feature_gate_lab") ? lab : super.getSharedPreferences(name, mode);
            }
        });
        try {
            assertThrows(Exception.class, () -> SettingsBackup.restore(Utils.getContext(), next.toString(), true));
            assertTrue("Lab recovery must run even after the other store fails", FeatureGateLabStore.masterEnabled());
            assertTrue(labCommits.get() >= 2);
            assertTrue("the rollback cleared the record of which overrides fired",
                    FeatureGateLabRuntime.isTriggered("abmock", "rollback_gate", "BOOLEAN"));
        } finally {
            field.set(Setting.preferences, original);
            Utils.setContext(app);
        }
        SettingsBackup.undo(app);
        assertFalse(Settings.REGION_SPOOF.get());
        assertTrue(FeatureGateLabStore.masterEnabled());
    }

    @Test public void partialRollbackReportsRecoveryAndKeepsPersistedValuesVisible() throws Exception {
        var app = Utils.getContext();
        FeatureGateLabStore.setMasterEnabled(true);
        JSONObject next = new JSONObject(SettingsBackup.create(false));
        next.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);
        next.getJSONObject("lab").put("master", false);
        var original = Setting.preferences.preferences;
        var normalFailure = new java.util.concurrent.atomic.AtomicBoolean();
        var labCommits = new java.util.concurrent.atomic.AtomicInteger();
        var normal = failingCommitsWithoutApply(original, normalFailure::get, () -> {});
        var lab = failingCommitsWithoutApply(app.getSharedPreferences("morphe_feature_gate_lab", 0),
                () -> labCommits.get() == 1, () -> {
                    if (labCommits.incrementAndGet() == 1) normalFailure.set(true);
                });
        var field = app.morphe.extension.shared.settings.preference.SharedPrefCategory.class
                .getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(Setting.preferences, normal);
        Utils.setContext(new android.content.ContextWrapper(app) {
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return name.equals("morphe_feature_gate_lab") ? lab : super.getSharedPreferences(name, mode);
            }
        });
        try {
            try {
                SettingsBackup.restore(Utils.getContext(), next.toString(), true);
                fail("restore should report the failed rollback");
            } catch (SettingsBackup.RestoreException error) {
                assertEquals(SettingsBackup.Failure.RECOVERY_REQUIRED, error.getFailure());
                assertFalse(error.isRollbackComplete());
                assertTrue(error.isRecoveryAvailable());
            }
            assertEquals(Boolean.TRUE, Setting.preferences.preferences.getAll().get(Settings.REGION_SPOOF.key));
            assertTrue(FeatureGateLabStore.masterEnabled());
            assertTrue(SettingsBackup.hasUndo(app));
        } finally {
            field.set(Setting.preferences, original);
            Utils.setContext(app);
        }
        SettingsBackup.undo(app);
        assertFalse(Settings.REGION_SPOOF.get());
        assertTrue(FeatureGateLabStore.masterEnabled());
    }

    @Test public void secondaryProcessCanReadButCannotOverwritePersistentSettings() {
        var app = Utils.getContext();
        Settings.REGION_SPOOF.save(true);
        FeatureGateLabStore.setMasterEnabled(true);
        FeatureGateLabStore.saveRule("abmock", "secondary_test", "BOOLEAN", "true", true);
        var secondary = new android.content.ContextWrapper(app) {
            @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
                android.content.pm.ApplicationInfo info = new android.content.pm.ApplicationInfo(
                        super.getApplicationInfo());
                info.processName = app.getPackageName() + ":secondary";
                return info;
            }
        };
        Utils.setContext(secondary);
        try {
            assertFalse(Utils.isMainProcess());
            assertTrue(Settings.REGION_SPOOF.get());
            assertTrue(FeatureGateLabStore.masterEnabled());
            assertNotNull(FeatureGateLabStore.rule("abmock", "secondary_test", "BOOLEAN"));
            Settings.REGION_SPOOF.save(false);
            FeatureGateLabStore.setMasterEnabled(false);
            FeatureGateLabStore.deleteRule("abmock", "secondary_test", "BOOLEAN");
            assertTrue(Settings.REGION_SPOOF.get());
            assertTrue(Setting.preferences.preferences.getBoolean(Settings.REGION_SPOOF.key, false));
            assertTrue(FeatureGateLabStore.masterEnabled());
            assertNotNull(FeatureGateLabStore.rule("abmock", "secondary_test", "BOOLEAN"));
        } finally {
            Utils.setContext(app);
        }
    }

    @Test public void interruptedSettingsJournalRestoresThePriorStateAfterMixedWrites() throws Exception {
        var app = Utils.getContext();
        String before = SettingsBackup.create(false);
        JSONObject after = new JSONObject(before);
        after.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);
        after.getJSONObject("lab").put("master", true);

        Settings.REGION_SPOOF.save(true);
        writeJournal("settings", before, after.toString());
        assertEquals(SettingsOperationJournal.Recovery.RECOVERED_PRIOR,
                SettingsOperationJournal.initialize(app));
        assertFalse(Settings.REGION_SPOOF.get());
        assertFalse(FeatureGateLabStore.masterEnabled());
        assertFalse(new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME).isFile());
    }

    @Test public void recoveringOrdinarySettingsKeepsALabRecordItDidNotChange() throws Exception {
        // The journal recovers ordinary settings and the Lab together, so it writes the Lab back
        // even when only an ordinary setting moved. Writing the same rules again used to throw
        // away the record of which overrides had fired, for a store the recovery did not change.
        var app = Utils.getContext();
        FeatureGateLabStore.saveRule("abmock", "recovery_gate", "BOOLEAN", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        FeatureGateLabRuntime.reloadRules();
        assertTrue(FeatureGateLabRuntime.overrideBoolean("recovery_gate", false));
        assertTrue(FeatureGateLabRuntime.isTriggered("abmock", "recovery_gate", "BOOLEAN"));

        String before = SettingsBackup.create(false);
        JSONObject after = new JSONObject(before);
        after.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);
        // Neither state may match, or the journal reads the change as already committed and
        // applies nothing. The lab master is the half that did not land.
        after.getJSONObject("lab").put("master", false);
        Settings.REGION_SPOOF.save(true);
        writeJournal("settings", before, after.toString());

        assertEquals(SettingsOperationJournal.Recovery.RECOVERED_PRIOR,
                SettingsOperationJournal.initialize(app));
        assertFalse(Settings.REGION_SPOOF.get());
        assertTrue("recovering an ordinary setting cleared the Lab's record",
                FeatureGateLabRuntime.isTriggered("abmock", "recovery_gate", "BOOLEAN"));
    }

    @Test public void aLabRecoveryKeepsTheRecordEvenWhereTheRulesMoved() throws Exception {
        // A recovery puts the prior rules back, so it keeps the record, and that holds even when
        // the half written state it is undoing had different rules. A marker left over for a rule
        // that no longer exists shows nowhere: the detail screen looks the rule up first and says
        // "Using TikTok's value" when there is none.
        var app = Utils.getContext();
        String before = FeatureGateLabStore.exportSettings().toString();
        FeatureGateLabStore.saveRule("abmock", "changed_gate", "BOOLEAN", "true", true);
        FeatureGateLabStore.setMasterEnabled(true);
        String after = FeatureGateLabStore.exportSettings().toString();

        // saveRule clears the marker for the rule it writes, so the gate has to fire after the
        // write this recovery is undoing, not before it.
        FeatureGateLabStore.saveRule("abmock", "changed_gate", "BOOLEAN", "false", true);
        FeatureGateLabRuntime.reloadRules();
        assertFalse(FeatureGateLabRuntime.overrideBoolean("changed_gate", true));
        assertTrue(FeatureGateLabRuntime.isTriggered("abmock", "changed_gate", "BOOLEAN"));
        writeJournal("lab", before, after);

        assertEquals(SettingsOperationJournal.Recovery.RECOVERED_PRIOR,
                SettingsOperationJournal.initialize(app));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertTrue("a recovery threw away a record it had no reason to",
                FeatureGateLabRuntime.isTriggered("abmock", "changed_gate", "BOOLEAN"));
    }

    @Test public void committedSettingsJournalIsClearedWithoutRevertingTheCommit() throws Exception {
        var app = Utils.getContext();
        String before = SettingsBackup.create(false);
        Settings.REGION_SPOOF.save(true);
        FeatureGateLabStore.setMasterEnabled(true);
        String after = SettingsBackup.create(false);
        writeJournal("settings", before, after);

        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.initialize(app));
        assertTrue(Settings.REGION_SPOOF.get());
        assertTrue(FeatureGateLabStore.masterEnabled());
        assertFalse(new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME).isFile());
    }

    @Test public void aCleanupFailureDoesNotRelabelAnAppliedJournalAsFailed() throws Exception {
        var app = Utils.getContext();
        String before = SettingsBackup.create(false);
        Settings.REGION_SPOOF.save(true);
        Settings.MAX_VIDEO_SECONDS.save(73);
        String after = SettingsBackup.create(false);
        writeJournal("settings", before, after);
        File journal = new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME);
        File damaged = new File(journal.getPath() + SettingsOperationJournal.DAMAGED_SUFFIX);
        if (damaged.exists()) assertTrue(damaged.delete());
        AtomicInteger deletes = new AtomicInteger();

        SettingsOperationJournal.Recovery result = SettingsOperationJournal.initialize(app, file -> {
            deletes.incrementAndGet();
            throw new java.io.IOException("forced journal cleanup failure");
        });

        assertEquals(1, deletes.get());
        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED, result);
        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertFalse(journal.isFile());
        assertTrue(damaged.isFile());
        assertEquals(SettingsOperationJournal.Recovery.NONE,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertTrue(damaged.delete());
    }

    @Test public void interruptedLabJournalRestoresPriorRulesAndKeepsTheUndoCopy() throws Exception {
        var app = Utils.getContext();
        String before = FeatureGateLabStore.exportSettings().toString();
        FeatureGateLabStore.saveRule("abmock", "journal_gate", "BOOLEAN", "true", true);
        String after = FeatureGateLabStore.exportSettings().toString();
        try (var undo = new FileOutputStream(new File(app.getFilesDir(), "feature-gate-lab-undo.json"))) {
            undo.write(after.getBytes(StandardCharsets.UTF_8));
        }
        FeatureGateLabStore.saveRule("abmock", "journal_gate", "BOOLEAN", "false", true);
        writeJournal("lab", before, after);

        assertEquals(SettingsOperationJournal.Recovery.RECOVERED_PRIOR,
                SettingsOperationJournal.initialize(app));
        assertTrue(FeatureGateLabStore.rules().isEmpty());
        assertTrue(new File(app.getFilesDir(), "feature-gate-lab-undo.json").isFile());
        assertFalse(new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME).isFile());
    }

    @Test public void malformedSettingsJournalIsSetAsideWithoutChangingValues() throws Exception {
        var app = Utils.getContext();
        Settings.REGION_SPOOF.save(true);
        File journal = new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME);
        File damaged = new File(journal.getPath() + SettingsOperationJournal.DAMAGED_SUFFIX);
        try (var output = new FileOutputStream(journal)) {
            output.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(SettingsOperationJournal.Recovery.MALFORMED,
                SettingsOperationJournal.initialize(app));
        assertEquals(SettingsOperationJournal.Recovery.MALFORMED,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertEquals(SettingsOperationJournal.Recovery.NONE,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertTrue(Settings.REGION_SPOOF.get());
        // The record is kept for the diagnostics and out of the way of the next change: a
        // journal left in place refused every later operation, Undo and Restore included,
        // which were the two things the notice told the reader to use.
        assertFalse(journal.isFile());
        assertTrue(damaged.isFile());
        assertEquals("{}", new String(java.nio.file.Files.readAllBytes(damaged.toPath()),
                StandardCharsets.UTF_8));
        SettingsOperationJournal.Operation operation = SettingsOperationJournal.acquire(app);
        operation.abort();
        assertTrue(damaged.delete());
    }

    @Test public void eachUnreadableJournalPublishesItsOwnNoticeInOneProcess() throws Exception {
        var app = Utils.getContext();
        File journal = new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME);
        File damaged = new File(journal.getPath() + SettingsOperationJournal.DAMAGED_SUFFIX);
        if (damaged.exists()) assertTrue(damaged.delete());

        try (var output = new FileOutputStream(journal)) {
            output.write(new byte[] {(byte) 0xc3, 0x28});
        }
        assertEquals(SettingsOperationJournal.Recovery.MALFORMED,
                SettingsOperationJournal.initialize(app));
        assertEquals(SettingsOperationJournal.Recovery.MALFORMED,
                SettingsOperationJournal.consumeRecoveryNotice());

        try (var output = new FileOutputStream(journal)) {
            output.write(new byte[] {(byte) 0xe2, 0x28, (byte) 0xa1});
        }
        assertEquals(SettingsOperationJournal.Recovery.MALFORMED,
                SettingsOperationJournal.initialize(app));
        assertEquals(SettingsOperationJournal.Recovery.MALFORMED,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertEquals(SettingsOperationJournal.Recovery.NONE,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertFalse(journal.isFile());
        assertTrue(damaged.isFile());
        assertTrue(damaged.delete());
    }

    @Test public void aJournalThatCannotBeAppliedIsSetAsideAndTheNextChangeStarts() throws Exception {
        var app = Utils.getContext();
        Settings.MAX_VIDEO_SECONDS.save(51);
        String before = SettingsBackup.create(false);
        Settings.MAX_VIDEO_SECONDS.save(52);
        String after = SettingsBackup.create(false);
        Settings.MAX_VIDEO_SECONDS.save(53);
        writeJournal("settings", before, after);
        File journal = new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME);
        File damaged = new File(journal.getPath() + SettingsOperationJournal.DAMAGED_SUFFIX);

        var original = Setting.preferences.preferences;
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        var unwritable = failingCommitsWithoutApply(original, () -> true, attempts::incrementAndGet);
        var field = app.morphe.extension.shared.settings.preference.SharedPrefCategory.class
                .getDeclaredField("preferences");
        field.setAccessible(true);
        field.set(Setting.preferences, unwritable);
        SettingsOperationJournal.Recovery result;
        try {
            result = SettingsOperationJournal.initialize(app);
        } finally {
            field.set(Setting.preferences, original);
        }

        assertEquals(2, attempts.get());
        assertEquals(SettingsOperationJournal.Recovery.FAILED, result);
        assertEquals(SettingsOperationJournal.Recovery.FAILED,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertEquals(SettingsOperationJournal.Recovery.NONE,
                SettingsOperationJournal.consumeRecoveryNotice());
        assertFalse(journal.isFile());
        assertTrue(damaged.isFile());
        SettingsOperationJournal.Operation operation = SettingsOperationJournal.acquire(app);
        operation.abort();
        assertTrue(damaged.delete());
    }

    @Test public void journalIntentIsDurableBeforeASettingsMutationRuns() throws Exception {
        var app = Utils.getContext();
        String before = SettingsBackup.create(false);
        SettingsOperationJournal.Operation operation = SettingsOperationJournal.acquire(app);
        try {
            operation.recordSettings(before, before);
            JSONObject journal = new JSONObject(SettingsBackup.read(new AtomicFile(
                    new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME)).openRead()));
            assertEquals("settings", journal.getString("kind"));
            assertEquals(before, journal.getString("before"));
            assertEquals(before, journal.getString("after"));
        } finally {
            operation.abort();
        }
    }

    @Test public void anAtomicFileBackupIsReconciledAfterAWriteCrash() throws Exception {
        var app = Utils.getContext();
        String before = SettingsBackup.create(false);
        JSONObject after = new JSONObject(before);
        after.getJSONObject("settings").put(Settings.REGION_SPOOF.key, true);
        Settings.REGION_SPOOF.save(true);
        writeJournal("settings", before, after.toString());

        File base = new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME);
        File backup = new File(base.getPath() + ".bak");
        assertTrue(base.renameTo(backup));
        assertFalse(base.isFile());
        assertTrue(backup.isFile());

        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.initialize(app));
        assertTrue(Settings.REGION_SPOOF.get());
        assertFalse(base.exists());
        assertFalse(backup.exists());
    }

    @Test public void labJournalMatchingIgnoresRuleOrder() throws Exception {
        var app = Utils.getContext();
        JSONArray ordered = new JSONArray()
                .put(new JSONObject().put("manager", "abmock").put("key", "order_a")
                        .put("type", "BOOLEAN").put("value", "true").put("force", true))
                .put(new JSONObject().put("manager", "abmock").put("key", "order_b")
                        .put("type", "INT").put("value", "3").put("force", false));
        JSONObject before = FeatureGateLabStore.exportSettings();
        JSONObject after = new JSONObject().put("schema", 1).put("target", "TikTok global")
                .put("tiktok_version", FeatureGateLabStore.targetVersion()).put("rules", ordered)
                .put("master", false).put("acknowledged", false);
        JSONArray reversed = new JSONArray().put(ordered.get(1)).put(ordered.get(0));
        JSONObject current = new JSONObject(after.toString()).put("rules", reversed);
        FeatureGateLabStore.replaceSettings(FeatureGateLabStore.parseSettings(current), false, false);
        writeJournal("lab", before.toString(), after.toString());

        assertEquals(SettingsOperationJournal.Recovery.ALREADY_COMMITTED,
                SettingsOperationJournal.initialize(app));
        assertEquals(2, FeatureGateLabStore.rules().size());
        assertFalse(new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME).isFile());
    }

    private static void writeJournal(String kind, String before, String after) throws Exception {
        var app = Utils.getContext();
        JSONObject root = new JSONObject().put("schema", 1).put("kind", kind)
                .put("before", before).put("after", after);
        AtomicFile file = new AtomicFile(new File(app.getFilesDir(), SettingsOperationJournal.FILE_NAME));
        try (var output = file.startWrite()) {
            output.write(root.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        }
    }

/**
     * A file written before a setting existed says nothing about that setting.
     *
     * <p>This used to assert the opposite, that the missing key took its default. Read on its
     * own that is a defensible rule, and it is what a restore does if a backup is a picture of
     * the whole app. It is the wrong rule for what this file actually is, which is a set of
     * values to apply: it meant restoring any backup silently undid every setting added since
     * it was taken, and a file from before the download destinations were split put a custom
     * folder back to DCIM/TikTok with nothing said.
     */
    @Test public void anOlderCompleteInventoryLeavesNewerSettingsAsTheDeviceHasThem() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(75);
        String older = withoutKeys(SettingsBackup.create(false), Settings.REGION_SPOOF.key);
        Settings.REGION_SPOOF.save(true);
        Settings.MAX_VIDEO_SECONDS.save(0);

        assertEquals(1, SettingsBackup.settingsNotInFile(older));
        SettingsBackup.restore(Utils.getContext(), older, true);

        assertTrue(Settings.REGION_SPOOF.get());
        assertEquals(75, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    @Test public void anOlderBackupWithRemovedSettingKeyStillImports() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(81);
        JSONObject root = new JSONObject(SettingsBackup.create(false));
        root.getJSONObject("settings").put("comment_translation_excluded_languages", "es");
        root.getJSONArray("setting_keys").put("comment_translation_excluded_languages");
        Settings.MAX_VIDEO_SECONDS.save(0);

        SettingsBackup.restore(Utils.getContext(), root.toString(), true);

        assertEquals(81, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertEquals(0, SettingsBackup.settingsSkipped(root.toString()));
        assertEquals(0, SettingsBackup.settingsNotInFile(root.toString()));
        assertFalse(Setting.preferences.preferences.contains("comment_translation_excluded_languages"));
    }

    @Test public void skippedKeysAndMissingCurrentSettingsStaySeparateAcrossHostsAndUndo() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(81);
        JSONObject file = fromUnknownBuild(
                new JSONObject(withoutKeys(SettingsBackup.create(false), Settings.REGION_SPOOF.key)));
        file.getJSONObject("settings").put("retired_or_future_option", new JSONObject().put("value", true))
                .put(Settings.AUTO_STREAK_STATE.key, "state-from-another-phone");
        file.getJSONArray("setting_keys").put("retired_or_future_option").put(Settings.AUTO_STREAK_STATE.key);
        Settings.MAX_VIDEO_SECONDS.save(29);
        Settings.REGION_SPOOF.save(true);
        Settings.AUTO_STREAK_STATE.save("state-for-this-phone");
        Setting.preferences.preferences.edit().putString("tiktok_native_state", "native-marker").commit();
        FeatureGateLabStore.saveRule("abmock", "current_host_gate", "INT", "7", true);
        FeatureGateLabStore.setMasterEnabled(true);
        assertEquals(2, SettingsBackup.settingsSkipped(file.toString()));
        assertEquals(1, SettingsBackup.settingsNotInFile(file.toString()));
        SettingsBackup.restore(Utils.getContext(), file.toString(), true);
        assertEquals(81, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertTrue(Settings.REGION_SPOOF.get());
        assertEquals("state-for-this-phone", Settings.AUTO_STREAK_STATE.savedValue());
        assertEquals("native-marker", Setting.preferences.preferences.getString("tiktok_native_state", null));
        assertFalse(Setting.preferences.preferences.contains("retired_or_future_option"));
        assertTrue(SettingsBackup.labRulesWereSkipped(file.toString()));
        assertEquals("7", FeatureGateLabStore.rule("abmock", "current_host_gate", "INT").value);
        assertTrue(FeatureGateLabStore.masterEnabled());

        String undone = SettingsBackup.undo(Utils.getContext());
        assertEquals(0, SettingsBackup.settingsSkipped(undone));
        assertEquals(0, SettingsBackup.settingsNotInFile(undone));
        assertEquals(29, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertTrue(Settings.REGION_SPOOF.get());
        assertEquals("state-for-this-phone", Settings.AUTO_STREAK_STATE.savedValue());
        assertEquals("native-marker", Setting.preferences.preferences.getString("tiktok_native_state", null));
        assertEquals("7", FeatureGateLabStore.rule("abmock", "current_host_gate", "INT").value);
    }

    @Test public void unknownKeysDoNotPreventCompatibleLabRulesOrUndoFromRestoring() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(72);
        FeatureGateLabStore.saveRule("abmock", "from_backup", "BOOLEAN", "true", true);
        JSONObject file = new JSONObject(SettingsBackup.create(false));
        file.getJSONObject("settings").put("future_setting", new JSONArray().put("not applied"));
        file.getJSONArray("setting_keys").put("future_setting");
        Settings.MAX_VIDEO_SECONDS.save(9);
        FeatureGateLabStore.resetAllLabData();
        FeatureGateLabStore.saveRule("abmock", "before_restore", "INT", "4", true);
        SettingsBackup.restore(Utils.getContext(), file.toString(), true);
        assertEquals(1, SettingsBackup.settingsSkipped(file.toString()));
        assertFalse(SettingsBackup.labRulesWereSkipped(file.toString()));
        assertEquals(72, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertNotNull(FeatureGateLabStore.rule("abmock", "from_backup", "BOOLEAN"));
        assertNull(FeatureGateLabStore.rule("abmock", "before_restore", "INT"));
        assertFalse(Setting.preferences.preferences.contains("future_setting"));
        SettingsBackup.undo(Utils.getContext());
        assertEquals(9, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertNull(FeatureGateLabStore.rule("abmock", "from_backup", "BOOLEAN"));
        assertNotNull(FeatureGateLabStore.rule("abmock", "before_restore", "INT"));
    }

    private static android.content.SharedPreferences failingCommits(android.content.SharedPreferences target,
            java.util.function.BooleanSupplier fail, Runnable committed) {
        return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                target.getClass().getClassLoader(), new Class[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(target, args);
                    var editor = target.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class[]{android.content.SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                Object result = call.invoke(editor, values);
                                if (call.getName().equals("commit")) {
                                    committed.run();
                                    return !fail.getAsBoolean() && (Boolean) result;
                                }
                                return result instanceof android.content.SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }

    private static android.content.SharedPreferences failingCommitsWithoutApply(
            android.content.SharedPreferences target, java.util.function.BooleanSupplier fail,
            Runnable attempted) {
        return (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                target.getClass().getClassLoader(), new Class[]{android.content.SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(target, args);
                    var editor = target.edit();
                    return java.lang.reflect.Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                            new Class[]{android.content.SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                if (call.getName().equals("commit")) {
                                    attempted.run();
                                    if (fail.getAsBoolean()) return false;
                                }
                                Object result = call.invoke(editor, values);
                                return result instanceof android.content.SharedPreferences.Editor ? editorProxy : result;
                            });
                });
    }

    @Test public void anUnwritableUndoLocationPreventsAnyChange() throws Exception {
        var file = java.io.File.createTempFile("unwritable-backup", ".tmp", Utils.getContext().getCacheDir());
        var context = new android.content.ContextWrapper(Utils.getContext()) {
            @Override public java.io.File getFilesDir() { return file; }
        };
        Settings.MAX_VIDEO_SECONDS.save(52);
        assertThrows(java.io.IOException.class, () -> SettingsBackup.restore(context, SettingsBackup.create(true), true));
        assertEquals(52, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertEquals("52", Setting.preferences.preferences.getString(Settings.MAX_VIDEO_SECONDS.key, null));
        assertTrue(file.delete());
    }

    @Test public void settingsScreenExportsThroughTheFilePickerWithoutTheDiagnosticsPatch() throws Exception {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.diagnosticsEnabled = false;
            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "BACKUP");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();
            var export = fragment.findPreference("settings_backup_7311");
            assertNotNull(export);
            assertNotNull(fragment.findPreference("settings_backup_7312"));
            assertNotNull(fragment.findPreference("settings_backup_7313"));
            assertNull(fragment.findPreference(BaseSettings.DEBUG.key));
            export.getOnPreferenceClickListener().onPreferenceClick(export);
            var started = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, started.intent.getAction());
            assertEquals("application/json", started.intent.getType());
            // The file app's own document: backups and restores go through the descriptor it
            // hands over, so a stream registered with the resolver would never be read.
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            Uri uri = provider.uri;
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(uri));
            waitFor("Settings backup saved");
            assertEquals("metra-settings", new JSONObject(new String(java.nio.file.Files.readAllBytes(
                    provider.file.toPath()), StandardCharsets.UTF_8)).getString("format"));
            Settings.MAX_VIDEO_SECONDS.save(73);
            fragment.onActivityResult(7312, android.app.Activity.RESULT_OK, new Intent().setData(uri));
            waitFor("Settings restored. Restart TikTok to apply all changes.");
            assertEquals(0, (int) Settings.MAX_VIDEO_SECONDS.get());
            var undo = fragment.findPreference("settings_backup_7314");
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            waitFor("Last change put back. Restart TikTok to apply all changes.");
            assertEquals(73, (int) Settings.MAX_VIDEO_SECONDS.get());
            var reset = fragment.findPreference("settings_backup_7313");
            reset.getOnPreferenceClickListener().onPreferenceClick(reset);
            waitFor("Settings are back to their defaults. Restart TikTok to apply all changes.");
            assertEquals(0, (int) Settings.MAX_VIDEO_SECONDS.get());
            fragment.onActivityResult(7312, android.app.Activity.RESULT_CANCELED, null);
            assertNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
        }
    }

    /**
     * Reset and Undo still run on the shared worker pool, so a full pool refuses them and leaves
     * nothing busy. Backup and Restore read and write on a worker of their own now, bounded by
     * the one file slot their kind has; that bound is DocumentOperationTest's.
     */
    @Test public void aFullWorkerQueueRejectsResetWithoutLeavingSettingsBusy() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class)
                .setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Settings.MAX_VIDEO_SECONDS.save(73);
            File undoFile = new File(activity.getApplicationContext().getFilesDir(),
                    "metra-settings-undo.json");
            assertTrue("fixture needs no Undo copy", !undoFile.exists() || undoFile.delete());

            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "BACKUP");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();

            var run = SettingsBackupPreference.class.getDeclaredMethod(
                    "run", TikTokPreferenceFragment.class, int.class, Uri.class);
            run.setAccessible(true);
            var busyField = SettingsBackupPreference.class.getDeclaredField("BUSY");
            busyField.setAccessible(true);
            var busy = (java.util.concurrent.atomic.AtomicBoolean) busyField.get(null);

            try (BackgroundPoolSaturation saturation = BackgroundPoolSaturation.fill()) {
                ShadowToast.reset();
                run.invoke(null, fragment, 7313, null);
                Shadows.shadowOf(Looper.getMainLooper()).idle();

                assertEquals("Couldn't start the settings change. Try again in a moment.",
                        ShadowToast.getTextOfLatestToast());
                assertEquals("a rejected reset changed settings", 73,
                        (int) Settings.MAX_VIDEO_SECONDS.get());
                assertFalse("a rejected reset saved an Undo copy", SettingsBackup.hasUndo(activity));
                assertFalse("the import guard remained set",
                        AbstractPreferenceFragment.settingImportInProgress);
                assertFalse("the backup control remained busy", busy.get());

                saturation.release();
                ShadowToast.reset();
                run.invoke(null, fragment, 7313, null);
                waitFor("Settings are back to their defaults. Restart TikTok to apply all changes.");
                assertEquals("the same reset could not be retried", 0,
                        (int) Settings.MAX_VIDEO_SECONDS.get());
                assertFalse(AbstractPreferenceFragment.settingImportInProgress);
                assertFalse(busy.get());
            }
        }
    }

    /**
     * An undo of a copy written before a retarget still says it is an undo.
     *
     * <p>The success line tested whether Lab rules had been dropped before it tested which
     * action had run, so any undo that dropped them said settings had been restored. An undo
     * copy is written from the settings as they were, so it carries the Lab rules of whatever
     * build wrote it, and the day this project retargets every one of them is such a copy.
     */
    @Test public void anUndoThatDropsLabRulesStillSaysItIsAnUndo() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class)
                .setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            SettingsStatus.diagnosticsEnabled = false;

            Settings.MAX_VIDEO_SECONDS.save(73);
            String undoCopy = fromUnknownBuild(new JSONObject(SettingsBackup.create(false))).toString();
            File undoFile = new File(activity.getApplicationContext().getFilesDir(),
                    "metra-settings-undo.json");
            try (FileOutputStream output = new FileOutputStream(undoFile)) {
                output.write(undoCopy.getBytes(StandardCharsets.UTF_8));
            }
            Settings.MAX_VIDEO_SECONDS.save(11);

            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "BACKUP");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();

            var undo = fragment.findPreference("settings_backup_7314");
            assertNotNull(undo);
            undo.getOnPreferenceClickListener().onPreferenceClick(undo);
            waitFor("Last change put back. The Feature Gate Lab rules were for another "
                    + "TikTok version and were left out. Restart TikTok to apply all changes.");
            assertEquals(73, (int) Settings.MAX_VIDEO_SECONDS.get());
        }
    }

    /**
     * The outcome stays in the settings window as one banner: a file that left a setting out said
     * so in a toast, and the outcome followed in a second toast that replaced it.
     */
    @Test public void aRestoreSaysWhatItLeftAloneAndWhatItDidInOneBanner() throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class)
                .setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            activity.findViewById(android.R.id.content).setTag(app.morphe.extension.tiktok.settings.preference.SettingsActionBanner.CONTENT_ROOT_TAG);
            JSONObject backup = new JSONObject(SettingsBackup.create(false));
            backup.getJSONObject("settings").remove(Settings.MAX_VIDEO_SECONDS.key);
            backup.getJSONObject("settings").put("removed_setting", true).put("future_setting", 2);
            // Movies holds videos, not stickers: the device's sticker folder stays and is named.
            String stickerBefore = Settings.DOWNLOAD_STICKER_PATH.get();
            backup.getJSONObject("settings").put(Settings.DOWNLOAD_STICKER_PATH.key, "Movies/Stick");
            JSONArray keys = backup.getJSONArray("setting_keys");
            keys.put("removed_setting").put("future_setting");
            for (int index = keys.length() - 1; index >= 0; index--) {
                if (Settings.MAX_VIDEO_SECONDS.key.equals(keys.getString(index))) keys.remove(index);
            }
            Uri uri = app.morphe.extension.tiktok.DocumentExportProvider.register(activity)
                    .contents(backup.toString().getBytes(StandardCharsets.UTF_8)).uri;

            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "BACKUP");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();

            var run = SettingsBackupPreference.class.getDeclaredMethod(
                    "run", TikTokPreferenceFragment.class, int.class, Uri.class);
            run.setAccessible(true);
            ShadowToast.reset();
            run.invoke(null, fragment, 7312, uri);
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            View banner = activity.getWindow().getDecorView().findViewWithTag("hushfeed_settings_action_banner");
            assertNotNull("the outcome didn't reach the settings banner", banner);
            TextView message = banner.findViewWithTag("hushfeed_settings_action_message");
            assertEquals("1 setting wasn't in that file and was left as it is. "
                    + "2 settings in that file can't be restored here and were skipped. "
                    + "Stickers can't be saved to the folder in that file, so your sticker folder was kept. "
                    + "Settings restored. Restart TikTok to apply all changes.", String.valueOf(message.getText()));
            assertEquals(stickerBefore, Settings.DOWNLOAD_STICKER_PATH.get());
            assertNotNull("a restore offers the restart", banner.findViewWithTag("hushfeed_settings_action_button"));
            assertEquals("the outcome went to a toast as well", 0, ShadowToast.shownToastCount());
        }
    }

    private static void waitFor(String message) throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        String toast = ShadowToast.getTextOfLatestToast();
        if (!message.equals(toast)) {
            fail("Missing completion toast: " + toast);
        }
    }

    @Test public void aBackupCannotPlantANumberTheDialogWouldRefuse() throws Exception {
        // Every numeric setting the dialog bounds, bounded again where the value is stored.
        // A backup file is written by hand or shared by somebody else, and until now the
        // only thing enforcing a range was a dialog it never went through.
        String backup = SettingsBackup.create(false);
        JSONObject root = new JSONObject(backup);
        JSONObject values = root.getJSONObject("settings");
        values.put("edge_seek_seconds", 100000);
        values.put("seen_video_retention_days", -12);
        values.put("seen_video_mark_percent", 500);
        values.put("max_video_seconds", 999999999);
        values.put("caption_text_size", 400);
        SettingsBackup.restore(Utils.getContext(), root.toString(), true);

        assertEquals(60, (int) Settings.EDGE_SEEK_SECONDS.get());
        assertEquals(0, (int) Settings.SEEN_VIDEO_RETENTION_DAYS.get());
        assertEquals(90, (int) Settings.SEEN_VIDEO_MARK_PERCENT.get());
        assertEquals(86400, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertEquals(48, (int) Settings.CAPTION_TEXT_SIZE.get());

        // A number inside the range is left exactly alone.
        values.put("edge_seek_seconds", 12);
        SettingsBackup.restore(Utils.getContext(), root.toString(), true);
        assertEquals(12, (int) Settings.EDGE_SEEK_SECONDS.get());
    }

    @Test public void theSuggestedBackupNameIsOneAPersonCanRead() {
        // It was epoch milliseconds, so two backups a minute apart were indistinguishable in the
        // picker and neither said when it was made. The other two exports already used this.
        String name = app.morphe.extension.tiktok.settings.preference.SettingsBackupPreference
                .suggestedExportName();
        assertTrue("the picker would show " + name,
                name.matches("metra-settings-\\d{8}-\\d{6}\\.json"));
    }

    /**
     * The four rows go out of reach while one of them runs, and the acting row says what it is
     * doing.
     *
     * <p>A restore of a large file or a reset takes long enough to notice, and the rows used to
     * look exactly as they had a moment earlier: a second tap earned "A settings operation is
     * already running", which is a refusal where a disabled row with a reason belongs, and a
     * screen reader was told nothing at all.
     */
    @Test public void theBackupRowsSayWhatIsRunningAndCannotBeTappedWhileItDoes() throws Exception {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", "BACKUP");
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Preference reset = backupRow(fragment, "Reset settings");
            Preference export = backupRow(fragment, "Back up settings");
            assertNotNull(reset);
            assertNotNull(export);
            String restingReset = String.valueOf(reset.getSummary());
            String restingExport = String.valueOf(export.getSummary());
            assertTrue("the rows start out of reach", reset.isEnabled() && export.isEnabled());

            var setRowsBusy = SettingsBackupPreference.class.getDeclaredMethod(
                    "setRowsBusy", int.class, String.class);
            setRowsBusy.setAccessible(true);
            setRowsBusy.invoke(null, 7313, "Putting the settings back to their defaults");

            assertFalse("the acting row can still be tapped", reset.isEnabled());
            assertFalse("the other rows can still be tapped", export.isEnabled());
            assertEquals("the acting row does not say what is happening",
                    "Putting the settings back to their defaults", String.valueOf(reset.getSummary()));
            assertEquals("a row that is not acting changed its summary",
                    restingExport, String.valueOf(export.getSummary()));

            // A tap while the run is going does nothing at all, rather than refusing out loud.
            var busyField = SettingsBackupPreference.class.getDeclaredField("BUSY");
            busyField.setAccessible(true);
            var busy = (java.util.concurrent.atomic.AtomicBoolean) busyField.get(null);
            busy.set(true);
            try {
                ShadowToast.reset();
                if (reset.getOnPreferenceClickListener() != null) {
                    reset.getOnPreferenceClickListener().onPreferenceClick(reset);
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertNull("a tap during a run said something", ShadowToast.getTextOfLatestToast());
                assertEquals("a tap during a run started a second one",
                        "Putting the settings back to their defaults",
                        String.valueOf(reset.getSummary()));
            } finally {
                busy.set(false);
            }

            setRowsBusy.invoke(null, 0, null);
            assertTrue("the rows stayed out of reach after the run", reset.isEnabled());
            assertTrue(export.isEnabled());
            assertEquals("the acting row kept its running line", restingReset,
                    String.valueOf(reset.getSummary()));
            assertEquals(restingExport, String.valueOf(export.getSummary()));
        }
    }

    @Test public void aFailedSafBackupOpenRemovesOnlyItsCreatedDocument() throws Exception {
        assertFailedSafBackupRemoved("open");
    }

    @Test public void aFailedSafBackupWriteRemovesOnlyItsCreatedDocument() throws Exception {
        assertFailedSafBackupRemoved("write");
    }

    @Test public void aBackupRefusedBeforeSerializationRemovesItsCreatedDocument() throws Exception {
        assertFailedSafBackupRemoved("serialize");
    }

    private void assertFailedSafBackupRemoved(String failure) throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            if (failure.equals("open")) provider.failOpen = true;
            else if (failure.equals("serialize")) {
                storedByAnOlderBuild(Settings.LOCAL_HIDDEN_CREATORS,
                        ruleEntries(FeedRuleLimits.MAX_ENTRIES + 1));
            } else {
                // The write goes through the descriptor the file app hands over, so the failure
                // is a descriptor that can't be written.
                provider.readOnlyWrites = true;
            }
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(failure + " left the file created by the picker behind", provider.awaitDeletion());
            assertFalse(provider.exists);
            assertEquals(1, provider.deleteCalls);
        }
    }

    /**
     * The picker can hand back a file the user chose to replace. A backup that fails before it
     * opens that file has written nothing to it, so the older backup in it stays: the cleanup
     * used to remove it, which left no backup at all.
     */
    @Test public void aBackupThatFailsBeforeWritingLeavesTheFileItWouldHaveReplaced() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            byte[] older = "{\"format\":\"hushfeed-settings\",\"older\":true}".getBytes(StandardCharsets.UTF_8);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity).contents(older);
            storedByAnOlderBuild(Settings.LOCAL_HIDDEN_CREATORS, ruleEntries(FeedRuleLimits.MAX_ENTRIES + 1));
            ShadowToast.reset();
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String message = ShadowToast.getTextOfLatestToast();
            assertNotNull(message);
            assertTrue("the oversized list wasn't named: " + message, message.contains("is too long for a settings backup"));
            assertFalse("a file that was left alone was called partial: " + message, message.contains("partial file"));
            assertEquals("the file the user chose to replace was removed", 0, provider.deleteCalls);
            assertTrue(provider.exists);
            assertArrayEquals(older, java.nio.file.Files.readAllBytes(provider.file.toPath()));
        }
    }

    /**
     * A file app that doesn't say how big the file is can't show it's the empty one the picker
     * made, so a failed backup leaves it rather than risk removing an older backup.
     */
    @Test public void aFailedBackupKeepsAFileWhoseSizeTheFileAppDoesntGive() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            provider.failOpen = true;
            provider.sizeUnknown = true;
            ShadowToast.reset();
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Couldn't save the settings backup. Try again.", ShadowToast.getTextOfLatestToast());
            assertEquals(0, provider.deleteCalls);
            assertTrue(provider.exists);
        }
    }

    @Test public void aSuccessfulSafBackupIsKeptAndCanBeReadAsJson() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(provider.exists);
            assertEquals(0, provider.deleteCalls);
            assertEquals("metra-settings", new JSONObject(new String(java.nio.file.Files.readAllBytes(
                    provider.file.toPath()), StandardCharsets.UTF_8)).getString("format"));
            Preference undo = fragment.findPreference("settings_backup_7314");
            assertFalse("an export must not create a settings Undo", undo.isEnabled());
            assertEquals("completion replaced the missing-Undo explanation", "Nothing to undo yet.",
                    undo.getSummary().toString());
        }
    }

    @Test public void failedSafCleanupNamesTheChosenFileWithoutAssumingDownloads() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            provider.failOpen = true;
            provider.refuseDeletion = true;
            ShadowToast.reset();
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("cleanup was never attempted", provider.awaitDeletion());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String message = ShadowToast.getTextOfLatestToast();
            assertNotNull(message);
            assertTrue("the abandoned file was not disclosed: " + message,
                    message.contains("partial file") && message.contains("couldn't be removed"));
            assertFalse("the picker may have selected a cloud folder", message.contains("Downloads"));
            assertTrue(provider.exists);
        }
    }

    @Test public void aDetachedBackupResultStillCleansItsNewSafDocument() throws Exception {
        var application = RuntimeEnvironment.getApplication();
        Utils.setContext(application);
        var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(application);
        SettingsBackupPreference.onResult(new TikTokPreferenceFragment(), 7311,
                android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue("a detached result abandoned its new file", provider.awaitDeletion());
        assertFalse(provider.exists);
    }

    @Test public void aBusyBackupResultCleansOnlyItsFileWithoutUnlockingTheOtherRun() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            var field = SettingsBackupPreference.class.getDeclaredField("BUSY");
            field.setAccessible(true);
            var busy = (java.util.concurrent.atomic.AtomicBoolean) field.get(null);
            busy.set(true);
            try {
                fragment.onActivityResult(7311, android.app.Activity.RESULT_OK,
                        new Intent().setData(provider.uri));
                assertTrue("a second result leaked its new file", provider.awaitDeletion());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue("cleanup released another operation's guard", busy.get());
            } finally {
                busy.set(false);
            }
        }
    }

    @Test public void aFailedImportNeverDeletesTheSelectedSourceDocument() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            provider.failOpen = true;
            fragment.onActivityResult(7312, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            Utils.awaitBackgroundTasksForTests();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(provider.exists);
            assertEquals(0, provider.deleteCalls);
        }
    }

    @Test public void backupRowsStayDisabledAfterRebindingAndRecreationDuringAWrite() throws Exception {
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            SettingsBackup.reset(activity);
            assertTrue("fixture needs an existing Undo", SettingsBackup.hasUndo(activity));
            var fragment = attachBackupPage(activity);
            var provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity);
            // The file app is still opening the document, which is where a slow one keeps a
            // backup waiting now that the write goes through the descriptor it hands over.
            var release = provider.holdOpens();
            fragment.onActivityResult(7311, android.app.Activity.RESULT_OK, new Intent().setData(provider.uri));
            try {
                assertTrue(provider.awaitOpening());
                Preference undo = fragment.findPreference("settings_backup_7314");
                undo.getView(null, null);
                assertFalse("binding Undo re-enabled it while export was still writing", undo.isEnabled());
                activity.getFragmentManager().beginTransaction().remove(fragment).commit();
                activity.getFragmentManager().executePendingTransactions();
                var replacement = attachBackupPage(activity);
                for (int action = 7311; action <= 7314; action++) {
                    Preference row = replacement.findPreference("settings_backup_" + action);
                    row.getView(null, null);
                    assertFalse("a recreated row ignored the running operation: " + action, row.isEnabled());
                }
            } finally {
                release.countDown();
                Utils.awaitBackgroundTasksForTests();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
        }
    }

    private static TikTokPreferenceFragment attachBackupPage(android.app.Activity activity) {
        var fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "BACKUP");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }

    private static Preference backupRow(TikTokPreferenceFragment fragment, String title) {
        var screen = fragment.getPreferenceScreen();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (title.equals(String.valueOf(row.getTitle()))) return row;
        }
        return null;
    }
}
