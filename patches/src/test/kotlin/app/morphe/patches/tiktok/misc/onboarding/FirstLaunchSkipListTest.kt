/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skip list SetupStepDecisionAnchorsTest checks against each build is the one the extension
 * ships. It reads the extension source, so it runs in :patches:test, apart from the fixture scans.
 */
class FirstLaunchSkipListTest {
    /** Neither step that shows Android's notification prompt is on it. */
    @Test
    fun `the shipped skip list is this one and keeps Android's notification prompt`() {
        val path = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/misc/FirstLaunchSetup.java"
        val source = listOf(java.io.File("../$path"), java.io.File(path)).first { it.isFile }.readText()
        val list = source.substringAfter("SKIPPED = ").substringBefore(")));")
        assertEquals(SKIPPED, Regex("\"([a-z_]+)\"").findAll(list).map { it.groupValues[1] }.toSet())
        assertTrue("push_page_advance" !in SKIPPED && "push_popup_background" !in SKIPPED)
    }
}
