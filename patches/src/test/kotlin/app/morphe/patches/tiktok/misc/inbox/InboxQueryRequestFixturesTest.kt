package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Test

/** Concrete entry fences cover native widget requests, direct VMs, deferred closures and loaders. */
class InboxQueryRequestFixturesTest {
    @Test fun `all reviewed native request entries guard before effects and retain every original instruction`() {
        assertEquals(13, loadedInboxRequestSpecs.size)
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            loadedInboxRequestSpecs.forEach { spec ->
                val entry = when (spec.owner) {
                    INBOX_PAGE_LOADER -> "classes8.dex"
                    "Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidgetContainer;" -> "classes9.dex"
                    else -> "classes39.dex"
                }
                val owner = dex.getEntry(entry)!!.dexFile.classes.single { it.type == spec.owner }
                val native = owner.methods.single { it.name == spec.name && it.parameterTypes.map(CharSequence::toString) == spec.parameters }
                assertTrue("${spec.owner}->${spec.name}", spec.fingerprint.takes(native, owner))
                val mutable = MutableMethod(native)
                val original = mutable.implementation!!.instructions.toList()
                val returns = original.filter { it.opcode == if (spec.closure) Opcode.RETURN_OBJECT else Opcode.RETURN_VOID }
                val returnExits = original.filterIsInstance<BuilderOffsetInstruction>().filter { it.target.location.instruction in returns }
                mutable.loadedRequestHook(spec)
                val after = mutable.implementation!!.instructions.toList()
                val guard = after[0].getReference<MethodReference>()!!
                assertEquals(INBOX_QUERY_EXTENSION, guard.definingClass)
                assertEquals(if (spec.closure) "beginLoadedClosureRequest" else if (spec.counted) "beginLoadedRequest" else "allowLoadedRequest", guard.name)
                val allowed = after[2] as BuilderOffsetInstruction
                assertSame(original[0], allowed.target.location.instruction)
                assertEquals(if (spec.closure) 5 else 4, allowed.target.location.index)
                assertEquals(1, (after[0] as RegisterRangeInstruction).registerCount)
                val originalAfter = after.filter { candidate -> original.any { candidate === it } }
                assertEquals(original, originalAfter)
                assertEquals(if (spec.counted) returns.size else 0, after.count { it.getReference<MethodReference>()?.name in
                    listOf("returnedLoadedRequest", "returnedLoadedClosureRequest") })
                if (spec.counted) {
                    returnExits.forEach { exit ->
                        assertTrue("native return branches must execute counter cleanup", exit.target.location.instruction!!
                            .getReference<MethodReference>()?.name in listOf("returnedLoadedRequest", "returnedLoadedClosureRequest"))
                    }
                    returns.forEach { returned ->
                        val index = after.indexOf(returned)
                        assertEquals(INBOX_QUERY_EXTENSION, after[index - 1].getReference<MethodReference>()!!.definingClass)
                    }
                }
                if (spec.tracked) {
                    val launch = after.withIndex().single { it.value.getReference<MethodReference>()?.let { method ->
                        method.definingClass == "LX/03WS;" && method.name == "LIZLLL" && method.returnType == "LX/03Wz;" } == true }
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, after[launch.index + 1].opcode)
                    val nativeResult = (after[launch.index + 1] as OneRegisterInstruction).registerA
                    val observed = after[launch.index + 2]
                    assertEquals(if (spec.closure) "onLoadedClosureRequestJob" else "onLoadedRequestJob", observed.getReference<MethodReference>()!!.name)
                    assertEquals(nativeResult, (observed as FiveRegisterInstruction).registerD)
                    assertEquals(1, after.count { it.getReference<MethodReference>()?.name in
                        listOf("onLoadedClosureRequestJob", "onLoadedRequestJob") })
                }
                assertEquals(original.mapNotNull { it.getReference<MethodReference>() },
                    after.mapNotNull { it.getReference<MethodReference>() }.filterNot { it.definingClass == INBOX_QUERY_EXTENSION })
            }
        }
    }

    @Test fun `request completion observes real native jobs including the load-more sibling job`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val vmClasses = dex.getEntry("classes39.dex")!!.dexFile.classes
            val regular = vmClasses.single { it.type == INBOX_REGULAR_VM }
            val creator = vmClasses.single { it.type == INBOX_CREATOR_VM }
            listOf(regular to listOf("LLJJLIIIJLLLLLLLZ", "LLJL"), creator to listOf("LLJJJJLIIL", "LLJJL")).forEach { (owner, fields) ->
                fields.forEach { name -> assertEquals("LX/03Wz;", owner.fields.single { it.name == name }.type) }
            }
            val jobSupport = dex.getEntry("classes2.dex")!!.dexFile.classes.single { it.type == "Lkotlinx/coroutines/JobSupport;" }
            val completion = jobSupport.methods.single { it.name == "LJIJ" && it.parameterTypes.map(CharSequence::toString) == listOf("Lkotlin/jvm/functions/Function1;") }
            assertEquals("LX/03RD;", completion.returnType)
            assertEquals("Z", jobSupport.methods.single { it.name == "isCompleted" }.returnType)
            assertTrue(completion.implementation!!.instructions.any { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == jobSupport.type && method.name == "LJJJJL" } == true })
            val unit = dex.getEntry("classes2.dex")!!.dexFile.classes.single { it.type == "Lkotlin/Unit;" }
            assertEquals("Lkotlin/Unit;", unit.fields.single { it.name == "LIZ" }.type)
        }
    }
}
