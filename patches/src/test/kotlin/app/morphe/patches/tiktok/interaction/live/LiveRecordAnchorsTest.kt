package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #37: the Record button learns which LIVE room is on screen from the room page's resume. Each
 * declared build has exactly one such resume, it is an instance method with the page as p0 and it
 * runs the page's own resume (so the hook only adds a note in front of it), and the page holds the
 * room in a single Room field. The extension then reads the creator and the stream addresses off
 * the room by name, so those real-named members must still be there.
 */
class LiveRecordAnchorsTest {
    private val streamUrlType = "Lcom/bytedance/android/livesdkapi/depend/model/live/StreamUrl;"
    private val coreDataType = "Lcom/bytedance/android/livesdkapi/depend/model/live/LiveCoreSDKData;"
    private val pullDataType = "Lcom/bytedance/android/livesdkapi/depend/model/live/LiveCoreSDKData\$PullData;"

    @Test
    fun `each declared build has one room page resume and the room members the recorder reads`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val byType = classes.associateBy { it.type }

            val taken = classes.flatMap { cls ->
                cls.methods.filter { it.implementation != null && LivePlayFragmentResumeFingerprint.takes(it, cls) }
            }
            assertEquals("${apk.name}: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val resume = taken.single()
            assertEquals(LIVE_PLAY_FRAGMENT, resume.definingClass)
            assertTrue("${apk.name}: instance method, p0 is the page", !AccessFlags.STATIC.isSet(resume.accessFlags))
            assertTrue(
                "${apk.name}: the resume no longer runs its super's resume, so a note in front of it is no longer harmless",
                resume.implementation!!.instructions.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    it.opcode == Opcode.INVOKE_SUPER && call?.name == "onResume"
                },
            )

            val page = byType.getValue(LIVE_PLAY_FRAGMENT)
            assertTrue("${apk.name}: the room field", liveRoomField(page).isNotEmpty())

            fun requireFields(type: String, vararg names: String) {
                val cls = byType[type]
                assertNotNull("${apk.name}: $type", cls)
                for (name in names) {
                    assertTrue("${apk.name}: $type has no field $name", cls!!.fields.any { it.name == name })
                }
            }
            requireFields(LIVE_ROOM_TYPE, "id", "idStr", "title", "owner", "streamUrl", "pullStreamData", "defaultQualitySdkKey")
            requireFields(streamUrlType, "flvPullUrl", "hlsPullUrl", "hlsPullUrlMap", "liveCoreSDKData")
            requireFields(coreDataType, "pullData")
            requireFields(pullDataType, "streamData")

            // The creator's handle, wherever in the user's class chain it sits.
            var user: ClassDef? = byType["Lcom/bytedance/android/live/base/model/user/User;"]
            assertNotNull("${apk.name}: the room owner's class", user)
            var found = false
            while (user != null && !found) {
                found = user.fields.any { it.name == "username" }
                user = user.superclass?.let { byType[it] }
            }
            assertTrue("${apk.name}: the owner's handle field", found)
        }
    }
}
