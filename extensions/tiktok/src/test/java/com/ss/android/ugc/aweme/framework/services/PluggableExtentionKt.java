/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.ss.android.ugc.aweme.framework.services;

/** Stands in for TikTok's service lookup, handing out whatever a test put here. */
public final class PluggableExtentionKt {
    public static volatile Object service;
    public static volatile int lookups;

    public static Object pluggableSpi(Class<?> type) {
        lookups++;
        return service;
    }

    private PluggableExtentionKt() {
    }
}
