/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the status bar in LIVE rooms (#38) recognises TikTok's LIVE room by its class name,
 * LiveStatusBar.LIVE_ROOM. A build that renamed or moved it would leave the switch doing nothing
 * with no failure anywhere else, so each declared build is held to it here.
 */
class LiveRoomAnchorsTest {
    private val liveRoom = "Lcom/ss/android/ugc/aweme/live/LivePlayActivity;"

    @Test
    fun `each declared build has the LIVE room activity the status bar switch looks for`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val found = container.dexEntryNames.any { entry ->
                container.getEntry(entry)!!.dexFile.classes.any { it.type == liveRoom }
            }
            assertTrue("$liveRoom is not in this build", found)
        }
    }
}
