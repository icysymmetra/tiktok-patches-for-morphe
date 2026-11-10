/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.featuregatelab;

import app.morphe.extension.shared.settings.preference.LogBufferManager;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Feature Gate Lab has been told to do, in the exported report.
 *
 * <p>Issue #4's reporter set two overrides as asked and the tab stayed empty, and nothing in
 * the export could say whether those overrides were live, loaded for the right type, or ever
 * consulted. This says all three: the master switch, each rule with its value, and for each
 * rule whether a gate read has reached it since the process started and from where.
 */
public final class FeatureGateLabReport implements LogBufferManager.ReportSection {
    static final String TITLE = "FEATURE GATE LAB";
    private static final FeatureGateLabReport INSTANCE = new FeatureGateLabReport();

    private FeatureGateLabReport() {}

    /** Registered once, at settings load, so every export from then on carries the section. */
    public static void install() {
        LogBufferManager.registerReportSection(INSTANCE);
    }

    @Override
    public String title() {
        return TITLE;
    }

    @Override
    public List<String> lines() {
        List<FeatureGateLabStore.Rule> rules = FeatureGateLabStore.rules();
        List<String> lines = new ArrayList<>();
        // Nothing to say on a phone that never touched the Lab: a section that reads
        // "overrides: off, rules: 0" on every export is noise where a finding should be.
        if (rules.isEmpty() && !FeatureGateLabStore.masterEnabled()) return lines;
        lines.add("overrides: " + (FeatureGateLabStore.masterEnabled() ? "on" : "off")
                + ", rules: " + rules.size());
        for (FeatureGateLabStore.Rule rule : rules) {
            StringBuilder line = new StringBuilder()
                    .append(rule.manager).append(' ').append(rule.key).append(' ')
                    .append(rule.type).append(" = ").append(rule.value)
                    .append(rule.enabled ? ", enabled" : ", disabled");
            if (FeatureGateLabRuntime.isTriggered(rule.manager, rule.key, rule.type)) {
                String caller = FeatureGateLabRuntime.firstCaller(rule.manager, rule.key, rule.type);
                String original = FeatureGateLabRuntime.originalValue(rule.manager, rule.key, rule.type);
                line.append(", read");
                if (caller != null && !caller.isEmpty()) line.append(" by ").append(caller);
                if (original != null) line.append(", TikTok had ").append(original);
            } else {
                line.append(", not read yet");
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
