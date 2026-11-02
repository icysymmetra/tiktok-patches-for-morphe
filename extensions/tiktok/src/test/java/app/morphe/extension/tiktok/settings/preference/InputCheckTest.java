/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;

import org.junit.Rule;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * The free-text fields used to take anything and fail somewhere else: a creator pattern that
 * would not compile was only reported at the next feed page, an operator code that was not
 * digits reached TikTok's own telephony code, and an app name with a typo saved quietly and
 * did nothing at the next download. Each one now says no while the dialog is still open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InputCheckTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @After public void reset() throws Exception {
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                field.setBoolean(null, false);
            }
        }
        Settings.BLOCKED_CREATORS.save("");
        Settings.BLOCKED_SOUND_IDS.save("");
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.SIMSPOOF_MCCMNC.save(Settings.SIMSPOOF_MCCMNC.defaultValue);
        Settings.EXTERNAL_DOWNLOADER_PACKAGE.save("");
        Settings.MIN_MAX_VIEWS.resetToDefault();
        Settings.DOWNLOAD_VIDEO_PATH.resetToDefault();
    }

    /** The field as the settings screen really builds it, not one made up for the test. */
    private interface WithField {
        void check(InputTextPreference field) throws Exception;
    }


    /**
     * Refusing a value used to close the dialog first and say why afterwards, over whatever
     * screen the reader was left looking at, with the box emptied. Saying no is only useful
     * next to the thing being said no about.
     */
    @Test public void aRefusedPatternLeavesTheDialogOpenWithTheReasonUnderTheField()
            throws Exception {
        onScreen("FEED_FILTER", "blocked_creators", field -> {
            openDialog(field);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
            assertNotNull("the dialog did not open", dialog);
            field.getEditText().setText("/^news_/, /([bad/");

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the dialog closed on a value it refused", dialog.isShowing());
            assertFalse("nothing was said under the field",
                    inlineError(field.getEditText()).getText().toString().isEmpty());
            assertNull("the host error bubble was used as well",
                    field.getEditText().getError());
            assertEquals("what was typed was thrown away",
                    "/^news_/, /([bad/", field.getEditText().getText().toString());
            assertEquals("a refused pattern was saved anyway", "",
                    Settings.BLOCKED_CREATORS.get());

            // And a value it will take closes it, which is the half that would still pass if
            // the button simply stopped working.
            field.getEditText().setText("news_uk, @someone");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the dialog stayed open on a value it took", dialog.isShowing());
            assertEquals("news_uk, @someone", Settings.BLOCKED_CREATORS.get());
        });
    }

    /**
     * A refused value is said to the reader, not only shown. Save was the focused view when the
     * check ran, and Android reads a field's error only while the field holds focus, so a
     * TalkBack user heard nothing and was left with a dialog that would not close.
     */
    @Test public void aRefusedValueMovesFocusToTheFieldAndSaysWhy() throws Exception {
        onScreen("FEED_FILTER", "blocked_creators", field -> {
            openDialog(field);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
            android.widget.EditText editor = field.getEditText();
            java.util.List<String> heard = new java.util.ArrayList<>();
            android.view.accessibility.AccessibilityManager manager =
                    (android.view.accessibility.AccessibilityManager) editor.getContext()
                            .getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
            Shadows.shadowOf(manager).setEnabled(true);
            ((android.view.ViewGroup) editor.getParent()).setAccessibilityDelegate(
                    new android.view.View.AccessibilityDelegate() {
                        @Override public boolean onRequestSendAccessibilityEvent(
                                android.view.ViewGroup host, android.view.View child,
                                android.view.accessibility.AccessibilityEvent event) {
                            if (event.getEventType()
                                    == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT) {
                                heard.add(String.valueOf(event.getText()));
                            }
                            return false;
                        }
                    });
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).requestFocus();
            editor.setText("/^news_/, /([bad/");

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the dialog closed on a value it refused", dialog.isShowing());
            String problem = inlineError(editor).getText().toString();
            assertTrue("focus stayed on Save, so the reason under the field is never read",
                    editor.isFocused());
            assertEquals("the reason was not announced: " + heard, 1, heard.size());
            assertTrue(heard.get(0), heard.get(0).contains(problem));
        });
    }

    @Test public void oversizedFeedRuleDialogsStayOpenAndKeepStorageUntouched()
            throws Exception {
        String tooMany = entryList(FeedRuleLimits.MAX_ENTRIES + 1);
        onScreen("FEED_FILTER", "blocked_caption_words", field -> {
            openDialog(field);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
            field.getEditText().setText(tooMany);
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the caption dialog closed on 10,001 entries", dialog.isShowing());
            assertEquals("That list has too many entries. Keep it to 10,000 or fewer.",
                    inlineError(field.getEditText()).getText().toString());
            assertEquals("", Settings.BLOCKED_CAPTION_WORDS.get());
        });

        StringBuilder tooLarge = new StringBuilder(FeedRuleLimits.MAX_UTF8_BYTES / 2 + 1);
        for (int index = 0; index <= FeedRuleLimits.MAX_UTF8_BYTES / 2; index++) {
            tooLarge.append('é');
        }
        onScreen("FEED_FILTER", "blocked_creators", field -> {
            openDialog(field);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
            field.getEditText().setText(tooLarge);
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the creator dialog closed on more than 256 KB", dialog.isShowing());
            assertEquals("That list is too large. Keep it to 256 KB or less.",
                    inlineError(field.getEditText()).getText().toString());
            assertEquals("", Settings.BLOCKED_CREATORS.get());
        });
    }

    private static String entryList(int count) {
        StringBuilder value = new StringBuilder(count * 8);
        for (int index = 0; index < count; index++) {
            if (index > 0) value.append(',');
            value.append("item").append(index);
        }
        return value.toString();
    }

    /** The same for the two-box rows, where retyping meant retyping both numbers. */
    @Test public void aBackwardsRangeLeavesTheDialogOpenWithBothNumbersStillThere()
            throws Exception {
        onRangeScreen("min_max_views", field -> {
            openDialog(field);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
            assertNotNull("the dialog did not open", dialog);
            android.widget.EditText[] boxes = rangeBoxes(dialog);
            boxes[0].setText("900");
            boxes[1].setText("100");

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the dialog closed on a range it refused", dialog.isShowing());
            assertFalse("nothing was said under the first box",
                    inlineError(boxes[0]).getText().toString().isEmpty());
            assertEquals("the smallest was thrown away", "900", boxes[0].getText().toString());
            assertEquals("the largest was thrown away", "100", boxes[1].getText().toString());

            boxes[1].setText("9000");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the dialog stayed open on a range it took", dialog.isShowing());
            assertEquals("900-9000", Settings.MIN_MAX_VIEWS.get());
        });
    }

    /**
     * The download folder is a fourth row of the same shape, found by the refutation pass over
     * the first three: it validated the path after the dialog had gone and then said what was
     * wrong over whatever screen was behind it.
     */
    @Test public void ARefusedDownloadFolderLeavesTheDialogOpenWithTheReasonUnderTheField()
            throws Exception {
        onScreenRaw("DOWNLOADS", "download_video_path", found -> {
            assertTrue("download_video_path is a " + found.getClass().getSimpleName(),
                    found instanceof DownloadPathPreference);
            String before = Settings.DOWNLOAD_VIDEO_PATH.get();
            openDialog(found);
            android.app.AlertDialog dialog =
                    (android.app.AlertDialog) ((android.preference.DialogPreference) found)
                            .getDialog();
            assertNotNull("the dialog did not open", dialog);
            android.widget.EditText box = pathBox(dialog);
            box.setText("/data/local/tmp/anywhere");

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("the dialog closed on a folder it refused", dialog.isShowing());
            assertFalse("nothing was said under the field",
                    inlineError(box).getText().toString().isEmpty());
            assertEquals("what was typed was thrown away",
                    "/data/local/tmp/anywhere", box.getText().toString());
            assertEquals("a refused folder was saved anyway", before,
                    Settings.DOWNLOAD_VIDEO_PATH.get());

            box.setText("DCIM/Hushfeed");
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse("the dialog stayed open on a folder it took", dialog.isShowing());
            assertEquals("DCIM/Hushfeed", Settings.DOWNLOAD_VIDEO_PATH.get());
        });
    }

    private static android.widget.EditText pathBox(android.app.AlertDialog dialog) {
        java.util.List<android.widget.EditText> found = new java.util.ArrayList<>();
        collectEditTexts(dialog.getWindow().getDecorView(), found);
        assertEquals("the folder dialog does not have one box", 1, found.size());
        return found.get(0);
    }

    private static android.widget.TextView inlineError(android.widget.EditText field) {
        assertTrue("the field is not in a form", field.getParent() instanceof android.view.ViewGroup);
        android.view.ViewGroup parent = (android.view.ViewGroup) field.getParent();
        int index = parent.indexOfChild(field);
        assertTrue("the field has no inline error after it", index >= 0
                && index + 1 < parent.getChildCount()
                && parent.getChildAt(index + 1) instanceof android.widget.TextView);
        return (android.widget.TextView) parent.getChildAt(index + 1);
    }

    private interface WithRange {
        void check(RangeValuePreference field) throws Exception;
    }

    private void onRangeScreen(String key, WithRange body) throws Exception {
        onScreenRaw("FEED_FILTER", key, found -> {
            assertTrue(key + " is a " + found.getClass().getSimpleName(),
                    found instanceof RangeValuePreference);
            body.check((RangeValuePreference) found);
        });
    }

    /** The two boxes in the order the dialog shows them, smallest first. */
    private static android.widget.EditText[] rangeBoxes(android.app.AlertDialog dialog) {
        java.util.List<android.widget.EditText> found = new java.util.ArrayList<>();
        collectEditTexts(dialog.getWindow().getDecorView(), found);
        assertEquals("the range dialog does not have two boxes", 2, found.size());
        return new android.widget.EditText[]{found.get(0), found.get(1)};
    }

    private static void collectEditTexts(
            android.view.View view, java.util.List<android.widget.EditText> found) {
        if (view instanceof android.widget.EditText) {
            found.add((android.widget.EditText) view);
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectEditTexts(group.getChildAt(index), found);
            }
        }
    }

    /** DialogPreference.showDialog is protected, and the row is reached through the screen. */
    public static void openDialog(Preference row) throws Exception {
        java.lang.reflect.Method show = null;
        for (Class<?> type = row.getClass(); type != null; type = type.getSuperclass()) {
            try {
                show = type.getDeclaredMethod("showDialog", Bundle.class);
                break;
            } catch (NoSuchMethodException keepLooking) {
                // The override lives further up.
            }
        }
        assertNotNull("this row has no dialog", show);
        show.setAccessible(true);
        show.invoke(row, (Bundle) null);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private interface WithPreference {
        void check(Preference found) throws Exception;
    }

    private void onScreenRaw(String section, String key, WithPreference body) throws Exception {
        try (var owner = Robolectric.buildActivity(
                app.morphe.extension.tiktok.captions.CaptionToolsTest.CaptionActivity.class)
                .setup().visible()) {
            var activity = owner.get();
            Utils.setContext(activity);
            for (Field field : SettingsStatus.class.getDeclaredFields()) {
                if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    field.setBoolean(null, true);
                }
            }
            var fragment = new TikTokPreferenceFragment();
            Bundle arguments = new Bundle();
            arguments.putString("morphe_settings_section", section);
            fragment.setArguments(arguments);
            activity.getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, fragment).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            Preference found = fragment.findPreference(key);
            assertNotNull(key + " is not on the " + section + " screen", found);
            ShadowToast.reset();
            body.check(found);
        }
    }

    private void onScreen(String section, String key, WithField body) throws Exception {
        onScreenRaw(section, key, found -> {
            assertTrue(key + " is a " + found.getClass().getSimpleName(),
                    found instanceof InputTextPreference);
            body.check((InputTextPreference) found);
        });
    }

    /** The preview line under the field, found by its heading. */
    private static String previewIn(android.view.View view) {
        if (view instanceof android.widget.TextView && !(view instanceof android.widget.EditText)
                && ((android.widget.TextView) view).getText().toString().startsWith("Preview with a made-up post:")) {
            android.widget.TextView text = (android.widget.TextView) view;
            return text.getVisibility() == android.view.View.VISIBLE ? text.getText().toString() : null;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                String found = previewIn(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void theFilenameEditorPreviewsWhatTheTypedTemplateSavesAs() throws Exception {
        onScreen("DOWNLOADS", Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.key, field -> {
            String saved = Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get();
            openDialog(field);
            android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
            android.view.View root = dialog.getWindow().getDecorView();

            field.getEditText().setText("{creator}_{vidoe_id}");
            String shown = previewIn(root);
            assertNotNull("no preview under the field", shown);
            assertTrue(shown, shown.contains("/creator_name_{vidoe_id}.mp4"));
            assertTrue(shown, shown.contains("Not a part Metra fills in, so it's kept as typed: {vidoe_id}"));

            field.getEditText().setText("{video_id}");
            shown = previewIn(root);
            assertTrue("the preview follows the typing: " + shown, shown.contains("/7312345678901234567.mp4"));
            assertFalse(shown, shown.contains("kept as typed"));
            assertEquals("looking saves nothing", saved, Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.get());
            dialog.dismiss();
            // The dismissal reaches the row as a posted message, and run after the second opening
            // it would clear that dialog instead, the way no tap can.
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            // Opened again, the editor has one watcher, not one per opening.
            openDialog(field);
            field.getEditText().setText("{creator}");
            assertTrue(previewIn(field.getDialog().getWindow().getDecorView()).contains("/creator_name.mp4"));
            field.getDialog().dismiss();
        });
    }

    /** The answer line under the sample box, found by its heading. */
    private static android.widget.TextView wordRuleAnswer(android.view.View view) {
        if (view instanceof android.widget.TextView && !(view instanceof android.widget.EditText)
                && ((android.widget.TextView) view).getText().toString()
                .startsWith("Word rules only, not every feed filter:")) {
            return (android.widget.TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.TextView found = wordRuleAnswer(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void theWordEditorsTryUnsavedRulesOnASampleWithoutSavingAnything() throws Exception {
        for (String[] editor : new String[][]{
                {"blocked_caption_words", "Sample caption to test"},
                {"live_hidden_categories", "Sample category to test"}}) {
            onScreen("FEED_FILTER", editor[0], field -> {
                String saved = field.getText();
                openDialog(field);
                android.app.AlertDialog dialog = (android.app.AlertDialog) field.getDialog();
                android.view.View root = dialog.getWindow().getDecorView();
                java.util.List<android.widget.EditText> boxes = new java.util.ArrayList<>();
                collectEditTexts(root, boxes);
                assertEquals("the rules box and the sample box", 2, boxes.size());
                android.widget.EditText rules = boxes.get(0);
                android.widget.EditText sample = boxes.get(1);
                assertEquals(editor[1], sample.getHint().toString());
                assertEquals("the sample box starts empty", "", sample.getText().toString());
                android.widget.TextView answer = wordRuleAnswer(root);
                assertNotNull("no answer line", answer);
                assertEquals("read out as it changes",
                        android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE,
                        answer.getAccessibilityLiveRegion());

                rules.setText("\"cat\" !& \"dog\", Crypto");
                sample.setText("Just a CAT");
                assertTrue(answer.getText().toString(),
                        answer.getText().toString().endsWith("Matches the rule: \"cat\" !& \"dog\""));
                sample.setText("a cat and a dog");
                assertTrue(answer.getText().toString(),
                        answer.getText().toString().endsWith("No rule matches this text."));
                rules.setText("\"cat\" & ");
                assertTrue("a syntax error is not a non-match: " + answer.getText(),
                        answer.getText().toString().contains("Can't check yet: "));
                assertEquals("looking saves nothing", saved, field.getText());

                // The sample is capped as it's typed.
                assertEquals(1, sample.getFilters().length);
                assertEquals(4_000,
                        ((android.text.InputFilter.LengthFilter) sample.getFilters()[0]).getMax());

                // Cancel leaves the setting alone, and the next opening starts clean.
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(saved, field.getText());
                openDialog(field);
                java.util.List<android.widget.EditText> again = new java.util.ArrayList<>();
                collectEditTexts(field.getDialog().getWindow().getDecorView(), again);
                assertEquals("no second sample box per opening", 2, again.size());
                assertEquals("", again.get(1).getText().toString());

                // Save still follows the existing check.
                again.get(0).setText("\"cat\" & ");
                ((android.app.AlertDialog) field.getDialog())
                        .getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertTrue("an unfinished rule still can't be saved", field.getDialog().isShowing());
                assertEquals(saved, field.getText());
                field.getDialog().dismiss();
            });
        }
    }

    @Test public void aCreatorPatternThatWillNotCompileIsRefusedInTheDialog() throws Exception {
        onScreen("FEED_FILTER", "blocked_creators", field -> {
            assertFalse("an unclosed group should not save",
                    field.callChangeListener("/^news_/, /([bad/"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String said = ShadowToast.getTextOfLatestToast();
            assertNotNull("nothing was said", said);
            assertTrue("the bad entry is named, said: " + said, said.contains("/([bad/"));

            // A pattern nobody would type, long enough to be somebody else's backup.
            StringBuilder huge = new StringBuilder("/");
            for (int index = 0; index < 60; index++) huge.append("(a+)+");
            huge.append("$/");
            assertFalse(field.callChangeListener(huge.toString()));

            // Plain handles and a pattern that compiles both save.
            assertTrue(field.callChangeListener("news_uk, @someone"));
            assertTrue(field.callChangeListener("/^news_/, /dropship/"));
            assertTrue(field.callChangeListener(""));
        });
    }

    @Test public void anOperatorCodeThatIsNotDigitsIsRefusedInTheDialog() throws Exception {
        onScreen("REGION", "simspoof_mccmnc", field -> {
            for (String nonsense : new String[]{"abc", "3102", "3102601", "31026a", ""}) {
                assertFalse("saved " + nonsense, field.callChangeListener(nonsense));
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String said = ShadowToast.getTextOfLatestToast();
            assertNotNull("nothing was said", said);
            assertTrue("said: " + said, said.contains("310260"));

            assertTrue(field.callChangeListener("310260"));
            assertTrue(field.callChangeListener("23415"));
            // Whitespace either side is a typing accident, not a different code.
            assertTrue(field.callChangeListener(" 310260 "));
        });
    }

    @Test public void anAppNameWithATypoIsRefusedInTheDialog() throws Exception {
        onScreen("DOWNLOADS", "external_downloader_package", field -> {
            for (String typo : new String[]{"com dv adm", "adm", "com.", "/data/local/tmp",
                    "com.dv.adm --flag"}) {
                assertFalse("saved " + typo, field.callChangeListener(typo));
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull("nothing was said", ShadowToast.getTextOfLatestToast());

            assertTrue(field.callChangeListener("com.dv.adm"));
            assertTrue(field.callChangeListener("idm.internet.download.manager"));
            // Empty means the save stays in TikTok, which is the setting's own default.
            assertTrue(field.callChangeListener(""));
            assertTrue(field.callChangeListener("   "));
        });
    }

    /**
     * An app name and a host have to match exactly, so the keyboard is told they aren't prose.
     * Left to treat them as sentences, SwiftKey on the S25 turned com.deniscerri.ytdl into
     * "Com. Deniscerri. Ytdl", which the check above then refused, and the reader couldn't type
     * the name at all. Handles, codes, ids, filename templates and speeds are names in the same
     * sense: a capital or a space after a dot changes what they mean.
     */
    @Test public void anAppNameAndAHostAreTypedAsNamesNotSentences() throws Exception {
        int name = android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        String[][] fields = {{"DOWNLOADS", Settings.EXTERNAL_DOWNLOADER_PACKAGE.key},
                {"PRIVACY", Settings.CUSTOM_SHARE_DOMAIN.key},
                {"FEED_FILTER", Settings.BLOCKED_CREATORS.key},
                {"FEED_FILTER", Settings.CREATOR_FILTER_EXCEPTIONS.key},
                {"FEED_FILTER", Settings.BLOCKED_SOUND_IDS.key},
                {"FEED_FILTER", Settings.REGION_ONLY_FROM.key},
                {"FEED_FILTER", Settings.REGION_NEVER_FROM.key},
                {"FEED_FILTER", Settings.CAPTION_LANGUAGES.key},
                {"COMMENTS", Settings.COMMENT_BLOCKED_USERS.key},
                {"REGION", Settings.SIM_SPOOF_ISO.key},
                {"DOWNLOADS", Settings.DOWNLOAD_VIDEO_FILENAME_TEMPLATE.key},
                {"DOWNLOADS", Settings.DOWNLOAD_PHOTO_FILENAME_TEMPLATE.key},
                {"DOWNLOADS", Settings.DOWNLOAD_COMMENT_MEDIA_FILENAME_TEMPLATE.key},
                {"PLAYBACK", Settings.CUSTOM_SPEEDS.key}};
        for (String[] where : fields) {
            onScreen(where[0], where[1], field -> {
                openDialog(field);
                assertNotNull(where[1] + ": the dialog did not open", field.getDialog());
                android.widget.EditText box = field.getEditText();
                assertTrue(where[1] + ": the field isn't in the dialog",
                        box.isAttachedToWindow() && box.getRootView() == field.getDialog().getWindow().getDecorView());
                int type = box.getInputType();
                assertEquals(where[1] + ": typed as " + Integer.toHexString(type), name, type);
                assertEquals(where[1] + ": the keyboard is asked to capitalize", 0,
                        type & (android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                                | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                                | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT));
                field.getDialog().dismiss();
            });
        }
    }

    /**
     * The name keyboard must not make a field single-line. On Android 14 a single-line field
     * gets a 5,000-character limit that also cuts the text it loads, so a long Blocked creators
     * list was cut when the dialog opened and the cut copy was saved over it with Save.
     */
    @Test @Config(sdk = 34)
    public void aLongListStaysWholeInANameField() throws Exception {
        StringBuilder creators = new StringBuilder();
        StringBuilder sounds = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            if (i > 0) { creators.append(", "); sounds.append(", "); }
            creators.append("creator_handle_").append(i);
            sounds.append(7_000_000_000_000_000_000L + i);
        }
        Object[][] fields = {{Settings.BLOCKED_CREATORS, creators.toString()},
                {Settings.BLOCKED_SOUND_IDS, sounds.toString()}};
        for (Object[] field : fields) {
            app.morphe.extension.shared.settings.StringSetting setting =
                    (app.morphe.extension.shared.settings.StringSetting) field[0];
            String value = (String) field[1];
            assertTrue("the list is too short to test the limit", value.length() > 5000);
            setting.save(value);
            onScreen("FEED_FILTER", setting.key, row -> {
                openDialog(row);
                android.app.AlertDialog dialog = (android.app.AlertDialog) row.getDialog();
                assertEquals(setting.key + ": the dialog shows a cut list", value.length(),
                        row.getEditText().getText().length());
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(setting.key + ": Save stored a cut list", value, setting.get());
            });
        }
    }

    /** Words and phrases are prose, and there the keyboard's suggestions help. */
    @Test public void aFieldOfWordsKeepsTheKeyboardsSuggestions() throws Exception {
        String[][] fields = {{"FEED_FILTER", Settings.BLOCKED_CAPTION_WORDS.key},
                {"FEED_FILTER", Settings.BLOCKED_SOUND_NAMES.key}};
        for (String[] where : fields) {
            onScreen(where[0], where[1], field -> assertEquals(where[1] + " lost its suggestions", 0,
                    field.getEditText().getInputType()
                            & android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        }
    }

    @Test public void aCountryCodeThatIsNotOneIsRefusedInTheDialog() throws Exception {
        onScreen("REGION", "simspoof_iso", field -> {
            for (String nonsense : new String[]{"zz", "united kingdom", "u", "", "12"}) {
                assertFalse("saved " + nonsense, field.callChangeListener(nonsense));
            }
            assertTrue(field.callChangeListener("gb"));
            assertTrue(field.callChangeListener("JP"));
        });
    }

    @Test public void aFieldWithNoCheckStillSaves() throws Exception {
        onScreen("FEED_FILTER", "blocked_caption_words", field ->
                assertEquals(true, field.callChangeListener("anything at all, /([bad/")));
    }
}
