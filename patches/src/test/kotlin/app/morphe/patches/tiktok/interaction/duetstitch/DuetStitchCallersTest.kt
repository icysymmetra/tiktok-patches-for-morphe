package app.morphe.patches.tiktok.interaction.duetstitch

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Allow Duet and Stitch answers the creator's account-wide choice at its two getters on User,
 * which is only safe while the duet and stitch checks for a video on screen are all that read
 * them. Answered there, a settings screen that read them would show the owner "anyone" for
 * their own posts. Every fixture is held to the readers the patch was written against: the duet
 * getter read by DuetHelperKt.checkDuetSetting alone, the stitch getter by the share sheet's
 * Stitch entry, the stitch status and the Stitch button.
 */
class DuetStitchCallersTest {
    @Test
    fun `the account-wide getters are read only by the duet and stitch checks`() {
        for (apk in Fixtures.apks()) {
            val callers = callersOf(apk, setOf("getDuetSetting", "getStitchSetting"))

            val duet = callers.getValue("getDuetSetting")
            assertEquals("${apk.name}: ${duet.map(::label)}", 1, duet.size)
            val (duetClass, duetMethod) = duet.single()
            assertEquals("${apk.name}: the duet getter's reader moved",
                "Lcom/ss/android/ugc/aweme/feed/model/DuetHelperKt;->checkDuetSetting",
                "${duetClass.type}->${duetMethod.name}")

            val stitch = callers.getValue("getStitchSetting")
            assertEquals("${apk.name}: ${stitch.map(::label)}", 3, stitch.size)
            // The share sheet's entry, by the interface method every entry implements.
            assertTrue("${apk.name}: no share entry reads it: ${stitch.map(::label)}",
                stitch.any { (_, method) -> method.name == "enable" && method.returnType == "Z" && method.parameterTypes.isEmpty() })
            // The Stitch button, whose view model kept its name.
            assertTrue("${apk.name}: the Stitch button does not read it: ${stitch.map(::label)}",
                stitch.any { (classDef, _) -> classDef.type == "Lcom/ss/android/ugc/aweme/feed/assem/stitchbutton/VideoStitchButtonViewModel;" })
            // The stitch status: a no-argument void method, the shape it has on every fixture.
            assertTrue("${apk.name}: the stitch status does not read it: ${stitch.map(::label)}",
                stitch.any { (_, method) -> method.returnType == "V" && method.parameterTypes.isEmpty() })
        }
    }

    @Test
    fun `the patch answers both account-wide getters`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/duetstitch/DuetStitchPatch.kt").readText()
        assertTrue("the account-wide duet getter is not answered",
            source.contains("AuthorDuetSettingFingerprint to \"authorSetting\""))
        assertTrue("the account-wide stitch getter is not answered",
            source.contains("AuthorStitchSettingFingerprint to \"authorSetting\""))
    }

    private fun label(pair: Pair<ClassDef, Method>) = "${pair.first.type}->${pair.second.name}"

    /** Every method in the fixture that invokes one of the named no-argument int getters on User. */
    private fun callersOf(apk: File, names: Set<String>): Map<String, List<Pair<ClassDef, Method>>> {
        val found = names.associateWith { mutableListOf<Pair<ClassDef, Method>>() }
        val seen = HashSet<String>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (!seen.add(classDef.type)) continue
                for (method in classDef.methods) {
                    // 47.4.3's User compares and hashes itself through its getters. Both sides of
                    // that read the same answer, and nothing shows it to anyone.
                    if (classDef.type == USER && method.name in setOf("equals", "hashCode")) continue
                    val instructions = method.implementation?.instructions ?: continue
                    for (instruction in instructions) {
                        if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) continue
                        val reference = instruction.getReference<MethodReference>() ?: continue
                        if (reference.definingClass != USER || reference.returnType != "I" ||
                            reference.parameterTypes.isNotEmpty() || reference.name !in names
                        ) continue
                        val list = found.getValue(reference.name)
                        if (list.none { it.first.type == classDef.type && it.second.name == method.name }) list += classDef to method
                    }
                }
            }
        }
        return found
    }
}
