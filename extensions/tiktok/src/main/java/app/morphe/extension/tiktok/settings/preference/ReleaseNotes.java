/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LocaleSpan;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shows published changes locally and remembers the last version the reader dismissed. */
public final class ReleaseNotes {
    static final String KEY = "action_release_notes";
    public static final String PREFS_NAME = "hushfeed_release_notes";
    private static final String DISMISSED = "dismissed_version";
    private static final String CHANGELOG_URL = "https://github.com/icysymmetra/tiktok-patches-for-morphe/blob/main/CHANGELOG.md";
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(\\S*)");
    private static final Pattern HEADING = Pattern.compile("(?m)^## (\\d+\\.\\d+\\.\\d+) ");

    private ReleaseNotes() {}

    static boolean pending(Context context, String current) {
        return !text(current, dismissed(context)).isEmpty();
    }

    /** The version the row names: the newest the notes show, or the installed one. */
    static String rowVersion(Context context, String current) {
        String newest = newestShown(text(current, dismissed(context)));
        return newest == null ? current : newest;
    }

    private static String dismissed(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(DISMISSED, null);
    }

    static String text(String current, String dismissed) {
        return text(ReleaseNotesData.TEXT, current, dismissed);
    }

    static String text(String source, String current, String dismissed) {
        return render(sections(source, null, current, dismissed), Locale.ENGLISH).toString();
    }

    /** One release's summaries as shown, and whether they came from the English table. */
    static final class Section {
        final String text;
        final boolean english;

        Section(String text, boolean english) {
            this.text = text;
            this.english = english;
        }
    }

    /**
     * The releases the reader hasn't dismissed, newest first, each already formatted. A release
     * {@code translated} holds comes in the phone's language (#91), and the rest in English.
     */
    static List<Section> sections(String source, String translated, String current, String dismissed) {
        List<Section> shown = new ArrayList<>();
        BigInteger[] installed = version(current);
        if (installed == null) return shown;
        BigInteger[] last = version(dismissed);
        if (last != null && compare(last, installed) >= 0) return shown;

        Map<String, String> ownLanguage = releases(translated);
        Matcher matcher = HEADING.matcher(source);
        while (matcher.find()) {
            String number = matcher.group(1);
            BigInteger[] entry = version(number);
            if (entry == null || compare(entry, installed) > 0
                    || (last == null && compare(entry, installed) != 0)
                    || (last != null && compare(entry, last) <= 0)) continue;
            int start = matcher.start();
            int end = matcher.find() ? matcher.start() : source.length();
            matcher.region(end, source.length());
            String own = ownLanguage.get(number);
            shown.add(new Section(format(own != null ? own : source.substring(start, end)), own == null));
        }
        return shown;
    }

    /** Each release section of {@code notes} by its version, or none for null. */
    private static Map<String, String> releases(String notes) {
        Map<String, String> found = new HashMap<>();
        if (notes == null) return found;
        Matcher matcher = HEADING.matcher(notes);
        while (matcher.find()) {
            String number = matcher.group(1);
            int start = matcher.start();
            int end = matcher.find() ? matcher.start() : notes.length();
            matcher.region(end, notes.length());
            found.put(number, notes.substring(start, end));
        }
        return found;
    }

    /**
     * The summaries' markdown as the dialog shows it. Every rule works line by line, so a
     * section formats the same alone as inside the whole text. The backticks around setting
     * names render as code on GitHub and only as stray marks in the dialog.
     */
    private static String format(String section) {
        return section
                .replaceAll("(?m)^## ", "Metra ")
                .replaceAll("(?m)^\\* (\\*\\*TikTok:\\*\\* )?", "• ")
                .replace("**", "")
                .replace("`", "");
    }

    /** The sections one after another, each run of one language marked with its LocaleSpan. */
    private static SpannableStringBuilder render(List<Section> sections, Locale own) {
        SpannableStringBuilder notes = new SpannableStringBuilder();
        int runStart = 0;
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            if (index > 0) {
                notes.append("\n\n");
                if (section.english != sections.get(index - 1).english) {
                    span(notes, runStart, notes.length(), sections.get(index - 1).english ? Locale.ENGLISH : own);
                    runStart = notes.length();
                }
            }
            notes.append(section.text);
        }
        // The last section's trailing blank lines go, as trim() took them from the whole text.
        int end = notes.length();
        while (end > 0 && Character.isWhitespace(notes.charAt(end - 1))) end--;
        notes.delete(end, notes.length());
        if (!sections.isEmpty()) {
            span(notes, runStart, notes.length(),
                    sections.get(sections.size() - 1).english ? Locale.ENGLISH : own);
        }
        return notes;
    }

    private static void span(SpannableStringBuilder notes, int start, int end, Locale locale) {
        if (end > start) notes.setSpan(new LocaleSpan(locale), start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
    }

    static void show(Context context, String current, Runnable onDismiss) {
        if (SettingsUi.dialogOpen(KEY, context)) return;
        TextView body = new TextView(context);
        // A release translated for the phone's table shows in that language (#91), and the rest
        // come from the English summary table. A LocaleSpan per run is what a screen reader that
        // switches languages reads; the text locale only sets line breaking and hyphenation.
        Locale own = L10n.shownLocale(context);
        List<Section> sections = sections(ReleaseNotesData.TEXT,
                ReleaseNotesData.translated(L10n.tableTag(context)), current,
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(DISMISSED, null));
        boolean anyEnglish = false;
        boolean allEnglish = true;
        for (Section section : sections) {
            anyEnglish |= section.english;
            allEnglish &= section.english;
        }
        body.setText(render(sections, own));
        body.setTextColor(SettingsUi.textPrimary());
        body.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY);
        body.setTextIsSelectable(true);
        body.setTextLocale(allEnglish ? Locale.ENGLISH : own);
        int padding = SettingsUi.dp(context, 22);
        body.setPadding(padding, padding, padding, padding);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        // A reader with a translated table is told before any English starts: all of it, or the
        // older releases from before the notes were translated (#91).
        if (L10n.isTranslated(context) && anyEnglish) {
            TextView english = new TextView(context);
            english.setText(L10n.t(context, allEnglish ? "These notes are in English."
                    : "Some of these notes are in English."));
            english.setTextColor(SettingsUi.textSecondary());
            english.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_LABEL);
            english.setPadding(padding, padding, padding, 0);
            body.setPadding(padding, SettingsUi.dp(context, 8), padding, padding);
            content.addView(english, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(context);
        scroller.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dialogListHeight(context, 480)));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "What's new"))
                .setView(scroller)
                // Got it is the answer most readers want, so it takes the primary slot. Later
                // keeps the row for another look. The pair used to be Close and Dismiss update,
                // and the neutral slot made the choice that changes state look like the minor one.
                .setPositiveButton(L10n.t(context, "Got it"), (target, which) -> {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                            .putString(DISMISSED, current).apply();
                    onDismiss.run();
                })
                .setNegativeButton(L10n.t(context, "Later"), (target, which) -> target.dismiss())
                .setNeutralButton(L10n.t(context, "Full changelog"), (target, which) -> Utils.openLink(CHANGELOG_URL))
                .create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);
        // Three translated actions can outgrow the platform's single button row. Keep the
        // platform buttons and their dismissal handlers, but let the existing flow wrap them.
        Button full = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        ViewGroup panel = (ViewGroup) full.getParent();
        ViewGroup actions = SettingsUi.actionRow(context);
        panel.removeAllViews();
        for (int which : new int[]{AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_POSITIVE}) {
            Button button = dialog.getButton(which);
            button.setSingleLine(false);
            button.setMaxLines(Integer.MAX_VALUE);
            button.setEllipsize(null);
            // The platform button inset can disappear after replacing its background.
            // Keep adjacent translated labels distinct when the flow fits them on one line.
            int inset = SettingsUi.dp(context, 8);
            button.setPadding(Math.max(inset, button.getPaddingLeft()), button.getPaddingTop(),
                    Math.max(inset, button.getPaddingRight()), button.getPaddingBottom());
            actions.addView(button, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        panel.addView(actions, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * Major, minor and patch, then 1 for a release and 0 for anything after the number, as in
     * 0.61.0-dev.2: a build ahead of a release sorts below it. Read as the release, Got it on
     * a dev build also dismissed the release's own notes when it came.
     */
    private static BigInteger[] version(String value) {
        if (value == null) return null;
        Matcher match = VERSION.matcher(value.trim());
        if (!match.find()) return null;
        return new BigInteger[]{
                new BigInteger(match.group(1)),
                new BigInteger(match.group(2)),
                new BigInteger(match.group(3)),
                match.group(4).isEmpty() ? BigInteger.ONE : BigInteger.ZERO
        };
    }

    /**
     * The newest version the notes would show, for the row to name. The installed version
     * isn't always one of them: a build with no published section of its own shows the ones
     * before it.
     */
    static String newestShown(String text) {
        Matcher heading = SHOWN_HEADING.matcher(text == null ? "" : text);
        return heading.find() ? heading.group(1) : null;
    }

    private static final Pattern SHOWN_HEADING = Pattern.compile("(?m)^Metra (\\d+\\.\\d+\\.\\d+) ");

    private static int compare(BigInteger[] left, BigInteger[] right) {
        for (int index = 0; index < left.length; index++) {
            int result = left[index].compareTo(right[index]);
            if (result != 0) return result;
        }
        return 0;
    }
}
