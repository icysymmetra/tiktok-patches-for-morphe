package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.*
import org.junit.Test

class PolicyTrialTest {
    private fun classes()=listOf("classes2.dex","classes18.dex","classes21.dex").flatMap {
        DexFileFactory.loadDexEntry(Fixtures.apkOf("47.4.3"),it,true,Opcodes.getDefault()).dexFile.classes
    }
    @Test fun `exact target resolves all policy operations and eligible young boundary`() {
        val p=policyTargets(classes())
        assertEquals(listOf("adjustGCOption","adjustGCOptionStart","adjustGCOptionEnd","doYoungGcOpt"),p.calls.map { it.native.name })
        assertEquals(6,p.youngIndex)
        assertEquals(listOf("D"),p.youngRequest.parameterTypes.map { it.toString() })
    }
    @Test fun `an extra native caller prevents a partially covered policy trial`() {
        val c=classes();val p=policyTargets(c)
        val duplicate=ImmutableClassDef("Ltest/Duplicate;",1,"Ljava/lang/Object;",null,null,null,null,listOf(p.calls.first().caller))
        assertThrows(PatchException::class.java){policyTargets(c+duplicate)}
    }
    @Test fun `an alternate worker construction path prevents the young request bypass`() {
        val c=classes();val p=policyTargets(c)
        val duplicate=ImmutableClassDef("Ltest/Duplicate;",1,"Ljava/lang/Object;",null,null,null,null,listOf(p.youngRequest))
        assertThrows(PatchException::class.java){policyTargets(c+duplicate)}
    }
}
