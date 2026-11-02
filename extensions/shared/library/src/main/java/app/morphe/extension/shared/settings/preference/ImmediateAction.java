/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared.settings.preference;

/**
 * A row that does what it says the moment it is tapped.
 *
 * <p>Every selectable row that is not a switch used to get a chevron, the same ">" that means
 * "this opens a page". So "Reset settings", which replaces every setting, looked exactly like
 * the row that opens the Diagnostics page. These rows say so and go without it.
 */
public interface ImmediateAction {
    /**
     * True when a tap acts. False for a row that opens something first, such as a file picker,
     * where the chevron is telling the truth.
     */
    boolean actsOnTap();
}
