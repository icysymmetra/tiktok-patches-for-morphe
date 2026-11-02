package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/Video;"

/**
 * The seek bar's preview check (#140), held to every declared fixture: it lives in the class that
 * logs the seek bar's reasons, its gate reads one video field, the loader it asks is the one that
 * reads `Video.getBigThumbs`, and the method has the local register and the low `this` the hook
 * writes with. A build that isn't declared may miss it; the patch leaves the line out there.
 */
class SeekbarPreviewAnchorsTest {
    @Test
    fun `the preview check resolves on every declared fixture and asks the sheets loader`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val reasons = build.methods.filter { (classDef, method) -> SeekbarGateLogFingerprint.takes(method, classDef) }
                .toList().single()
            val seekbar = build.byType.getValue(reasons.first.type)
            val declared = Fixtures.versionOf(apk) in Fixtures.declaredVersions()
            val check = try {
                previewCheckOf(seekbar)
            } catch (problem: Exception) {
                if (declared) throw AssertionError("${apk.name}: ${problem.message}", problem)
                println("${apk.name}: no preview check (${problem.message}); the patch leaves the line out")
                continue
            }

            val registers = check.method.implementation!!.registerCount
            assertTrue("${apk.name}: ${check.method.name} has no local register", registers >= 2)
            assertTrue("${apk.name}: ${check.method.name} keeps this in v${registers - 1}", registers - 1 <= 15)

            val loaderCall = check.method.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
                .last { it.returnType == "Z" && it.parameterTypes.isEmpty() && it.definingClass != seekbar.type }
            val loader = build.resolve(loaderCall)
            assertNotNull("${apk.name}: ${loaderCall.definingClass}->${loaderCall.name} is not in this build", loader)
            val readsSheets = loader!!.implementation!!.instructions.any {
                val call = it.getReference<MethodReference>()
                call?.definingClass == VIDEO && call.name == "getBigThumbs"
            }
            assertTrue("${apk.name}: the loader's ${loader.name} no longer reads Video.getBigThumbs", readsSheets)
            val loaderField = check.method.implementation!!.instructions.first { it.opcode == Opcode.IGET_OBJECT }
                .getReference<FieldReference>()!!
            assertEquals("${apk.name}: the loader is read from the seek bar", seekbar.type, loaderField.definingClass)
        }
    }

    @Test
    fun `the patch writes the line from the thumbnail patch`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readLines()
        val hooked = source.filter { it.contains(".named(check.method).reportPreviewCheck(check.video)") }
        assertEquals("the preview check is hooked once", 1, hooked.size)
        assertTrue("the preview check's hook is commented out", !hooked.single().trimStart().startsWith("//"))
    }

    /** One fixture's classes by type; methods are walked on each ask, never held. */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }

        /** The body a virtual call runs, looked up from the named class through its superclasses. */
        fun resolve(reference: MethodReference): Method? {
            var type: String? = reference.definingClass
            while (type != null) {
                val classDef = byType[type] ?: return null
                classDef.methods.firstOrNull {
                    it.name == reference.name && it.returnType == reference.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString) &&
                        it.implementation != null
                }?.let { return it }
                type = classDef.superclass
            }
            return null
        }
    }
}
