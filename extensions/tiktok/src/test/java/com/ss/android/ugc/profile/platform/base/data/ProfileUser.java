package com.ss.android.ugc.profile.platform.base.data;

/**
 * The readback fields exposed by TikTok 46.2.3's profile response, and the header tree the
 * profile shortcut filter reads (#49).
 */
public final class ProfileUser {
    public ProfileCommonInfo common;
    public ProfileComponents headerComponents;
}
