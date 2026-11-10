package app.morphe.patches.tiktok.misc.commentpublish

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comment send fix steps in between TikTok's top page and its send check, so every fixture is
 * held to the shape the patch is written against: one entry that logs the send click, one send
 * check in it, and the top page read before that check. A second check would leave one of them
 * answering on the top page alone, which is the silent stop the patch exists to take away.
 */
class CommentSendFixAnchorsTest {
    @Test
    fun `the send click entry reads the top page and then checks the send against a screen`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val entries = container.dexEntryNames.flatMap { name ->
                container.getEntry(name)!!.dexFile.classes
                    .filter { it.type.endsWith(PUBLISH_VIEW_MODEL_SUFFIX) }
                    .flatMap { classDef ->
                        classDef.methods.filter { it.isPublishEntryPart() && CLICK_EVENT in it.stringConstants() }
                    }
            }
            assertEquals("${apk.name}: entries that log the send click", 1, entries.size)
            val instructions = entries.single().implementation!!.instructions.toList()

            val checks = instructions.withIndex().filter { it.value.isSendCheck() }
            assertEquals("${apk.name}: send checks in the entry", 1, checks.size)
            val (checkIndex, check) = checks.single()
            val screenType = check.getReference<MethodReference>()!!.parameterTypes[0].toString()
            assertTrue("${apk.name}: the check's first parameter is $screenType, not a class", screenType.startsWith("L"))

            val topPage = instructions.topPageRead()
            assertNotNull("${apk.name}: the entry does not read TikTok's top page", topPage)
            assertTrue("${apk.name}: the top page is read after the check", topPage!!.first < checkIndex)
        }
    }
}
