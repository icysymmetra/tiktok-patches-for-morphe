/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patches.tiktok.interaction.engagement.profileGridCountPatch
import app.morphe.patches.tiktok.interaction.engagement.showEngagementRatePatch
import app.morphe.patches.tiktok.interaction.publishdate.alwaysShowPublishDatePatch
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The saved-video check mark rides the profile grid's count hook. Advanced downloads brings the
 * same shared patch Show engagement rate and Always show publish date bring, so a bundle with
 * any of them puts one hook in the grid's bind, never a second one beside it.
 */
class SavedVideoMarkHookTest {
    @Test
    fun `advanced downloads brings the grid count hook the other grid features share`() {
        listOf(advancedDownloadsPatch, showEngagementRatePatch, alwaysShowPublishDatePatch).forEach { patch ->
            assertTrue("${patch.name} doesn't bring the shared grid count hook",
                profileGridCountPatch in patch.dependencies)
        }
    }
}
