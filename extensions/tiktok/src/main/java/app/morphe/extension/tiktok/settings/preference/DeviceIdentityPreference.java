/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.tiktok.privacy.DeviceIdentity;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Arms a new device identity for the next start, and a second tap takes it back. Arming owes a
 * restart, so the restart row appears at once, and the wipe itself runs as TikTok starts again
 * ({@link DeviceIdentity#start}). No dialog: the row says what it will do, and what's armed can
 * be disarmed until the restart.
 */
@SuppressWarnings("deprecation")
public final class DeviceIdentityPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String KEY = "action_new_device_identity";
    static final String TITLE = "New device identity";
    static final String SUMMARY = "Wipe TikTok's data and give it a new Android ID and advertising ID "
            + "on the next start, so it sees a phone it hasn't met. You'll be signed out and lose "
            + "your drafts. Metra's settings stay.";
    static final String ARMED_SUMMARY = "TikTok starts as a new phone after the restart. Tap again to cancel.";
    static final String FAILED = "Couldn't set up a new device identity. Try again.";

    public DeviceIdentityPreference(Context context) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        setTitle(TITLE);
        applyState();
        setOnPreferenceClickListener(preference -> {
            toggle(getContext());
            return true;
        });
    }

    private void toggle(Context context) {
        boolean arm = !DeviceIdentity.isArmed(context);
        if (!DeviceIdentity.setArmed(context, arm)) {
            SettingsActionBanner.showNotice(context, L10n.t(context, FAILED));
            return;
        }
        if (arm) {
            TikTokPreferenceFragment.noteFileRestartPending(KEY);
        } else {
            TikTokPreferenceFragment.noteFileRestartSettled(KEY);
        }
        applyState();
    }

    private void applyState() {
        setSummary(DeviceIdentity.isArmed(getContext()) ? ARMED_SUMMARY : SUMMARY);
    }

    @Override
    protected void onBindView(View view) {
        applyState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }
}
