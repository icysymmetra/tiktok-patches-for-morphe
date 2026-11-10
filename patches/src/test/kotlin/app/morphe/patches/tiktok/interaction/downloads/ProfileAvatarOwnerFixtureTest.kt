package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #160: ProfileAvatarSaver reads the profile behind a held avatar through the avatar component's
 * own getter, which R8 renames on every build (`bq`, `ds`, `Qr`). The extension finds it as the
 * nearest class's one no-argument method returning a User. This holds that rule to each declared
 * build: both components the hold hands over resolve to exactly one such getter, and it is the
 * very getter TikTok's own-profile hold calls to read the profile it is about to act on.
 */
class ProfileAvatarOwnerFixtureTest {
    private val avatar = "Lcom/ss/android/ugc/profile/business/avatar/"
    private val user = "Lcom/ss/android/ugc/aweme/profile/model/User;"

    @Test
    fun `both avatar components resolve to the one profile getter the native hold reads`() {
        val apks = Fixtures.apks()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
                .associateBy { it.type }

            val ownHolds = classes.values.flatMap { it.methods }.filter { method ->
                listOf("long_press", "long_hold_head", "photo", "video").all { method.hasString(it) }
            }
            assertEquals("${apk.name}: one own-profile avatar hold", 1, ownHolds.size)
            val nativeReads = ownHolds.single().implementation!!.instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .filter { it.parameterTypes.isEmpty() && it.returnType == user }
                .map { "${it.definingClass}->${it.name}" }
                .distinct()
            assertEquals("${apk.name}: the native hold reads one profile getter", 1, nativeReads.size)

            for (owner in listOf("${avatar}OtherProfileAvatarAssem;", "${avatar}MyProfileAvatarAssem;")) {
                val getter = userGetter(owner, classes)
                assertTrue("${apk.name}: $owner has one profile getter", getter != null)
                assertEquals("${apk.name}: $owner", nativeReads.single(), "${getter!!.definingClass}->${getter.name}")
            }
        }
    }

    /** ProfileAvatarSaver.userGetter's rule, on the dex. */
    private fun userGetter(type: String, classes: Map<String, ClassDef>): Method? {
        var current: ClassDef? = classes[type]
        while (current != null && current.type != "Ljava/lang/Object;") {
            val getters = current.methods.filter {
                it.parameterTypes.isEmpty() && it.returnType == user &&
                    !AccessFlags.STATIC.isSet(it.accessFlags) && !AccessFlags.SYNTHETIC.isSet(it.accessFlags)
            }
            if (getters.isNotEmpty()) return getters.singleOrNull()
            current = current.superclass?.let(classes::get)
        }
        return null
    }

    private fun Method.hasString(value: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true
}
