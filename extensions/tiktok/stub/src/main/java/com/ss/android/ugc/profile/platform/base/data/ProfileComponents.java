/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */

package com.ss.android.ugc.profile.platform.base.data;

import java.util.List;

/**
 * One node of the server-driven profile layout. The fields match 47.0.3, 47.1.3 and 47.1.4.
 * {@code bizData} is left out on purpose: its type is TikTok's shrunk gson JsonObject, whose
 * name can change, so it is read by field name instead.
 */
@SuppressWarnings("unused")
public class ProfileComponents {
    public String componentName;
    public String componentId;
    public List components;
}
