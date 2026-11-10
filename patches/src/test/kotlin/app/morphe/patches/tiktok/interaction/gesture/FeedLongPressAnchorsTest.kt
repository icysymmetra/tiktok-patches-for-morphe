package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed's long press hook, held to each TikTok build the bundle declares. On 47.1.4 two gesture
 * listeners hand their double tap to handleDoubleClick, the feed cell's and the landscape cell's,
 * so a fingerprint taking both would hook whichever the patcher met first. A taken double tap's
 * second touch is swallowed in that hook (#63), so it has to be the feed's.
 */
class FeedLongPressAnchorsTest {
    @Test
    fun `the feed long press hook takes one listener, never the landscape cell's`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val seen = HashSet<String>()
            val taken = mutableListOf<String>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (!seen.add(classDef.type)) continue
                    for (method in classDef.methods) {
                        if (!FeedLongPressFingerprint.takes(method, classDef)) continue
                        taken += classDef.type
                        assertTrue(
                            "the hook took the landscape cell's listener ${classDef.type}",
                            classDef.fields.none { it.type == LANDSCAPE_CELL },
                        )
                    }
                }
            }
            assertEquals("listeners the feed long press hook takes: $taken", 1, taken.size)
        }
    }
}
