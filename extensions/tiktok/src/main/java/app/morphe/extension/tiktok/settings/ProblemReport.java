/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;

/**
 * The GitHub bug report Report a problem opens, with what a reporter is always asked for already
 * filled in: both versions, the phone, and the switches that are on.
 *
 * <p>The repository's bug form is an issue form, which ignores a {@code body} parameter and takes
 * its fields by id instead, so each fact goes into the field that asks for it. {@link #body} is the
 * same text in one piece, capped at {@link #BODY_LIMIT}; the link has a cap of its own,
 * {@link #LINK_LIMIT}, and may carry fewer switches. Nothing here names the account, the phone's
 * serial or a file path: titles, versions and the model only.
 */
public final class ProblemReport {
    static final String NEW_ISSUE_URL = "https://github.com/icysymmetra/tiktok-patches-for-morphe/issues/new";
    /** The bug form in .github/ISSUE_TEMPLATE, whose field ids the link fills. */
    static final String TEMPLATE = "bug_report.yml";
    /** The form's own title prefix; the reporter adds the rest. */
    static final String TITLE = "bug: ";
    /** The longest the report runs as one piece of text. */
    public static final int BODY_LIMIT = 6_000;
    /**
     * The longest the link may be once GitHub's sign-in page has wrapped it. GitHub answers a
     * longer address with 414 URI Too Long (8,225 characters did, 8,209 didn't, 2026-10-13), and a
     * reporter who isn't signed in is sent to {@link #SIGN_IN} with the whole link encoded a second
     * time, every % growing to %25, so that wrapped link is the one measured.
     */
    static final int LINK_LIMIT = 8_000;
    static final String SIGN_IN = "https://github.com/login?return_to=";
    static final String WHAT_HAPPENED = "What happened:";
    static final String WHAT_EXPECTED = "What I expected:";
    private static final String UNKNOWN = "unknown";

    /** What the report says about this install, gathered once so a test can supply its own. */
    public static final class Facts {
        final String hushVersion;
        final String appVersionName;
        final long appVersionCode;
        final String manufacturer;
        final String model;
        final String androidRelease;
        final int sdk;
        final List<String> switchesOn;

        public Facts(String hushVersion, String appVersionName, long appVersionCode,
                String manufacturer, String model, String androidRelease, int sdk,
                List<String> switchesOn) {
            this.hushVersion = known(hushVersion);
            this.appVersionName = known(appVersionName);
            this.appVersionCode = appVersionCode;
            this.manufacturer = known(manufacturer);
            this.model = known(model);
            this.androidRelease = known(androidRelease);
            this.sdk = sdk;
            this.switchesOn = switchesOn == null
                    ? Collections.<String>emptyList() : new ArrayList<>(switchesOn);
        }
    }

    private ProblemReport() {
    }

    /** This install's facts, with the switch titles the settings screen read off its pages. */
    @SuppressWarnings("deprecation")
    public static Facts collect(Context context, List<String> switchesOn) {
        String appVersion = Utils.getAppVersionName();
        long code = -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            if (info.versionName != null) appVersion = info.versionName;
        } catch (Exception unreadable) {
            // The name Utils read is still worth reporting without its code.
        }
        return new Facts(Utils.getPatchesReleaseVersion(), appVersion, code, Build.MANUFACTURER,
                Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, switchesOn);
    }

    /**
     * The versions field, in the words the About card uses, with TikTok's version code after. The
     * Play Store update block installs TikTok with the highest code there is, so that number says
     * only that the block went in (S22, 2026-10-13: "version code 2147483647" on 47.1.4).
     */
    static String versions(Facts facts) {
        String code = facts.appVersionCode == Integer.MAX_VALUE ? " (store updates blocked)"
                : facts.appVersionCode >= 0 ? " (version code " + facts.appVersionCode + ")" : "";
        return "Version " + facts.hushVersion + " for TikTok " + facts.appVersionName + code;
    }

    /** The phone field: make, model and Android, the way the form's example puts it. */
    static String device(Facts facts) {
        String model = facts.model.regionMatches(true, 0, facts.manufacturer, 0, facts.manufacturer.length())
                ? facts.model : facts.manufacturer + " " + facts.model;
        return model + ", Android " + facts.androidRelease + " (SDK " + facts.sdk + ")";
    }

    /**
     * The two lines the reporter fills in, at the end of the body. The link leaves the form's
     * What went wrong box empty, where the form shows them as a hint: every report sent with them
     * filled in came back with nothing typed under them (four by 2026-10-18), and two reporters
     * said the box wouldn't take their text (#197, #201).
     */
    static String whatHappened() {
        return WHAT_HAPPENED + "\n\n" + WHAT_EXPECTED + "\n";
    }

    /** The whole report in one piece, never longer than {@link #BODY_LIMIT}. */
    public static String body(Facts facts) {
        return head(facts) + switchesThatFit(facts) + tail();
    }

    /**
     * The new-issue link, every part encoded. The switch list is the body's, cut further when the
     * link wrapped for GitHub's sign-in page would pass {@link #LINK_LIMIT}: encoding grows the
     * text, and a body at its cap made a link GitHub refused.
     */
    public static String url(Facts facts) {
        String start = NEW_ISSUE_URL
                + "?template=" + encode(TEMPLATE)
                + "&title=" + encode(TITLE)
                + "&versions=" + encode(versions(facts))
                + "&device=" + encode(device(facts))
                + "&additional_context=";
        int fits = 0;
        int tooLong = BODY_LIMIT - head(facts).length() - tail().length() + 1;
        while (tooLong - fits > 1) {
            int room = (fits + tooLong) >>> 1;
            if (signInLength(start + encode(switches(facts, room))) <= LINK_LIMIT) fits = room;
            else tooLong = room;
        }
        return start + encode(switches(facts, fits));
    }

    /** How long {@code link} is once GitHub's sign-in page has wrapped it. */
    static int signInLength(String link) {
        return SIGN_IN.length() + encode(link).length();
    }

    private static String head(Facts facts) {
        return "Metra and TikTok: " + versions(facts) + "\n" + "Phone: " + device(facts) + "\n\n";
    }

    private static String tail() {
        return "\n\n" + whatHappened();
    }

    private static String switchesThatFit(Facts facts) {
        return switches(facts, BODY_LIMIT - head(facts).length() - tail().length());
    }

    /**
     * The switches that are on, one per line, as many as {@code room} holds, then how many more
     * there were. The heading and the count line always fit, so a long list is cut, never the
     * lines the reporter is asked to fill in.
     */
    static String switches(Facts facts, int room) {
        List<String> titles = facts.switchesOn;
        String heading = titles.isEmpty()
                ? "Metra switches that are on: none"
                : "Metra switches that are on (" + titles.size() + "):";
        StringBuilder text = new StringBuilder(heading);
        for (int index = 0; index < titles.size(); index++) {
            String line = "\n- " + titles.get(index);
            int left = titles.size() - index - 1;
            String more = left > 0 ? "\n- and " + left + " more" : "";
            String cut = "\n- and " + (titles.size() - index) + " more";
            if (text.length() + line.length() + more.length() > room) {
                text.append(cut);
                break;
            }
            text.append(line);
        }
        return text.length() <= room ? text.toString() : text.substring(0, Math.max(0, room));
    }

    /** Percent-encoded, with a space as %20: a + reads as a plus sign in some decoders. */
    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String known(String value) {
        return value == null || value.trim().isEmpty() ? UNKNOWN : value.trim();
    }
}
