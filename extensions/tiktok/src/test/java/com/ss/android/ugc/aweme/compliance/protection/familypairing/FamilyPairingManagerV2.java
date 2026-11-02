/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package com.ss.android.ugc.aweme.compliance.protection.familypairing;

/**
 * Stands in for TikTok's Family Pairing manager: one static instance of itself, and one method
 * handing out the account's role, an enum TikTok names NONE, CHILD, PARENT and UNLINK_LOCKED.
 */
public final class FamilyPairingManagerV2 {
    public enum Role { NONE, CHILD, PARENT, UNLINK_LOCKED }

    public static final FamilyPairingManagerV2 INSTANCE = new FamilyPairingManagerV2();

    /** The role the next call hands out. */
    public static volatile Role current = Role.NONE;

    public Role role() {
        return current;
    }

    public int childCount() {
        return 0;
    }
}
