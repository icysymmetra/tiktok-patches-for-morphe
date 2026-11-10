/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.ss.android.ugc.aweme.compliance.api.model;

/** Stands in for the user details TikTok's compliance settings save, isMinor among them. */
public final class UserDetailsInfoBean {
    public final Boolean isMinor;

    public UserDetailsInfoBean(Boolean isMinor) {
        this.isMinor = isMinor;
    }
}
