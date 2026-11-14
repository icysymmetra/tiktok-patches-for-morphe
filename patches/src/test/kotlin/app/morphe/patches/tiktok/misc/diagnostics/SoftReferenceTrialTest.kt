package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import org.junit.Assert.*
import org.junit.Test

class SoftReferenceTrialTest {
    private fun owner(): ClassDef = DexFileFactory.loadDexEntry(
        Fixtures.apkOf("47.4.3"), "classes42.dex", true, Opcodes.getDefault(),
    ).dexFile.classes.single { it.type.endsWith("/SoftReferenceLruOpt;") }

    private fun withMethods(owner: ClassDef, methods: Iterable<Method>): ClassDef = ImmutableClassDef(
        owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile,
        owner.annotations, owner.fields, methods,
    )

    @Test fun `actual APK resolves both installers without using wrapper names`() {
        val owner = owner()
        val wrappers = softReferenceInstallers(listOf(owner))
        assertEquals(2, wrappers.size)
        assertTrue(wrappers.all { it.returnType == "V" && it.parameterTypes.map { p -> p.toString() } == listOf("I", "J") })
    }

    @Test fun `an incomplete native pair is refused`() {
        val owner = owner()
        assertThrows(PatchException::class.java) {
            softReferenceInstallers(listOf(withMethods(owner, owner.methods.filter { it.name != "nEnableV2" })))
        }
    }

    @Test fun `a missing wrapper is refused`() {
        val owner = owner()
        val wrapper = softReferenceInstallers(listOf(owner)).last()
        assertThrows(PatchException::class.java) {
            softReferenceInstallers(listOf(withMethods(owner, owner.methods.filter { it != wrapper })))
        }
    }

    @Test fun `ambiguous owners are refused`() {
        val owner = owner()
        assertThrows(PatchException::class.java) { softReferenceInstallers(listOf(owner, owner)) }
    }

    @Test fun `a native caller outside the wrappers is refused`() {
        val owner = owner()
        val implementation = softReferenceInstallers(listOf(owner)).first().implementation
        val method = ImmutableMethod("Ltest/Extra;", "escape", emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, implementation)
        val extra = ImmutableClassDef("Ltest/Extra;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(method))
        assertThrows(PatchException::class.java) { softReferenceInstallers(listOf(owner, extra)) }
    }
}
