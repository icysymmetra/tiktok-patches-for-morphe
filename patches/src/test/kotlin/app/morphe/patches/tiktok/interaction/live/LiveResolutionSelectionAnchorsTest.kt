package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offer LIVE quality in more rooms answers true at the entry of the two methods that read TikTok's
 * LIVE resolution selection setting. Each declared build has one of each, and nothing outside the
 * setting reads its value another way, so the two answers are the whole setting. The room menu
 * still gates the newer picker on the first, beside the stream's own showQualityButton, and the
 * pull stream service still picks a room's starting quality on the second.
 */
class LiveResolutionSelectionAnchorsTest {
    private val options = "Lcom/bytedance/android/livesdkapi/depend/model/live/LiveCoreSDKData\$Options;"
    private val pullStreamService = "Lcom/bytedance/android/live/livepullstream/PullStreamService;"

    @Test
    fun `each declared build has one guardable answer for both resolution selection reads`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            for (fingerprint in listOf(LiveResolutionSelectionFingerprint, LiveResolutionPersistFingerprint)) {
                val taken = classes.flatMap { cls -> cls.methods.filter { fingerprint.takes(it, cls) }.map { cls to it } }
                assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
                val (classDef, method) = taken.single()
                assertEquals(LIVE_RESOLUTION_SELECTION_SETTING, classDef.type)
                assertTrue("${apk.name}: ${method.name} is static", !AccessFlags.STATIC.isSet(method.accessFlags))
                assertTrue(
                    "${apk.name}: ${method.name} frame has no local for the guard",
                    method.implementation!!.registerCount > method.parameterTypes.size + 1,
                )
            }

            // Outside the setting itself, only its instance and the two hooked answers are read.
            val calls = classes.filter { it.type != LIVE_RESOLUTION_SELECTION_SETTING }.flatMap { cls ->
                cls.methods.flatMap { method ->
                    method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
                        val reference = (instruction as? ReferenceInstruction)?.reference
                        val owner = (reference as? MethodReference)?.definingClass ?: (reference as? FieldReference)?.definingClass
                        if (owner == LIVE_RESOLUTION_SELECTION_SETTING) Triple(cls, method, reference) else null
                    }
                }
            }
            val names = calls.map { (it.third as? MethodReference)?.name ?: (it.third as FieldReference).name }.toSet()
            assertEquals("${apk.name}: setting reads", setOf("INSTANCE", "isEnable", "needPersistAcrossRooms"), names)

            fun readers(name: String): List<Pair<ClassDef, Method>> =
                calls.filter { (it.third as? MethodReference)?.name == name }.map { it.first to it.second }.distinct()
            assertTrue(
                "${apk.name}: no room menu gates the picker on isEnable beside showQualityButton",
                readers("isEnable").any { (_, method) ->
                    method.implementation!!.instructions.any {
                        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
                        field?.definingClass == options && field.name == "showQualityButton"
                    }
                },
            )
            assertTrue(
                "${apk.name}: the pull stream service no longer picks a room's quality on needPersistAcrossRooms",
                readers("needPersistAcrossRooms").any { (cls, method) ->
                    cls.type == pullStreamService && method.returnType == "Ljava/lang/String;"
                },
            )
        }
    }
}
