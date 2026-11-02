/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TogglePreference.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/TogglePreference.java
 */

package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.preference.SwitchPreference;
import android.view.View;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.Utils;

import java.util.regex.Pattern;

@SuppressWarnings("deprecation")
public class TogglePreference extends SwitchPreference {

    /**
     * The sentence a row gets when its switch does nothing until TikTok is started again.
     *
     * <p>Fifty settings need a restart and thirty-four of their summaries said so, each in its
     * own words, which left sixteen switches that look like they did nothing. A switch that
     * silently does nothing is the hardest kind of bug to report, and two of the "this does not
     * work" reports on the tracker are about restart-gated switches.
     */
    public static final String RESTART_SENTENCE = "Restart TikTok to apply this.";

    /**
     * A summary that already asks for a restart, in whatever words it chose.
     *
     * <p>This was the literal "Restart TikTok". Two Region summaries said "and a restart"
     * instead, so the check missed them and both rows shipped the sentence twice, in all five
     * languages, because the join happens after translation. Matched on the word rather than on
     * one spelling of it, and against the English, which is the key, so a translation that words
     * it differently cannot fool this either way.
     */
    private static final Pattern SAYS_RESTART =
            Pattern.compile("\\brestart", Pattern.CASE_INSENSITIVE);

    /** The summary without the line {@link #showExtraLine} adds, so the line can go again. */
    private CharSequence plainSummary;

    public TogglePreference(Context context, String title, String summary, BooleanSetting setting) {
        super(context);
        setTitle(title);
        // super, because withRestartNote has already translated. Going through the override
        // would look the translated text up as though it were a key of its own, which finds
        // nothing today and would find the wrong row the day a translation equals a key.
        super.setSummary(withRestartNote(context, summary, setting));
        plainSummary = getSummary();
        setKey(setting.key);
        setChecked(setting.savedValue());
    }

    /**
     * Adds the restart sentence to a summary whose setting needs one and does not have one.
     *
     * <p>The two halves are looked up separately and then joined, because the joined sentence is
     * not a key in the table: translating it as one string would lose the translation of the
     * summary as well.
     *
     * <p>Three of the four callers hand this the English, which is the key, so the check below
     * cannot be confused by a translation that words the sentence differently. The tab rows are
     * the exception: {@code TabSelectionPreference.refreshSummary} builds its summary out of tab
     * labels it has already translated. No tab label carries the word today, so the answer is
     * right there by luck rather than by construction.
     *
     * <p>Shared with the text, range and tab rows, which carry restart-gated settings as well:
     * nine of their rows said nothing about it while every switch did. A summary that is a list
     * rather than a sentence, "Home" or "All loaded tabs", gets a full stop before the note.
     *
     * @return the summary as given when there is nothing to add, so the caller's own translation
     *         still applies; otherwise the translated summary with the note after it.
     */
    static String withRestartNote(Context context, String summary, Setting<?> setting) {
        if (summary == null) return null;
        // Translated here, once, so no caller has to look the joined text up again. Looking up
        // a string that already carries the note finds nothing and hands back what it was given,
        // which works but reads as though the join were a phrase of its own.
        String translated = L10n.t(context, summary);
        if (setting == null || !setting.rebootApp) return translated;
        if (SAYS_RESTART.matcher(summary).find()) return translated;

        String note = L10n.t(context, RESTART_SENTENCE);
        String body = translated.trim();
        if (body.isEmpty()) return note;
        char last = body.charAt(body.length() - 1);
        // A summary that is a sentence takes the note after it. One that is a state line, "Home"
        // or "All loaded tabs", takes it on the line below: bolting a full stop onto a fragment
        // would make the pair a string that is in no translation table, and the row would then
        // be the only one on the page whose text nobody could translate.
        return body + (".!?:…".indexOf(last) >= 0 ? " " : "\n") + note;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        Utils.setTitleAndSummaryColor(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
        plainSummary = getSummary();
    }

    /**
     * Shows {@code line}, already translated, under the summary, or takes the last one away
     * when it is null. The budget's switches use it for a change that waits for the next day.
     */
    public void showExtraLine(CharSequence line) {
        super.setSummary(line == null ? plainSummary : plainSummary + "\n" + line);
    }
}
