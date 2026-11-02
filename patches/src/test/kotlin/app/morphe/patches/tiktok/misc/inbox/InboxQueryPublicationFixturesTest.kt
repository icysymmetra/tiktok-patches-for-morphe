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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Test

/** The rendered-list seams must retain the native model, metadata and completion trajectories. */
class InboxQueryPublicationFixturesTest {
    @Test fun `publication replaces only the List argument before native mask defaults and dispatch`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owner = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "Lcom/bytedance/ies/powerlist/ListState;" }
            val native = owner.methods.single { it.name == "LIZ" }
            assertTrue(InboxListPublication.takes(native, owner))
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val receiver = mutable.implementation!!.registerCount - 6
            mutable.loadedPublicationHook()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size + 2, after.size)
            assertEquals("onLoadedPublication", after[0].getReference<MethodReference>()!!.name)
            val call = after[0] as RegisterRangeInstruction
            assertEquals(receiver, call.startRegister)
            assertEquals(6, call.registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[1].opcode)
            assertEquals(receiver + 1, (after[1] as OneRegisterInstruction).registerA)
            original.forEachIndexed { index, instruction -> assertSame(instruction, after[index + 2]) }
            assertTrue(original.any { it.getReference<MethodReference>()?.name == "setValue" })
            assertTrue(original.any { it.getReference<MethodReference>()?.name == "postValue" })
            assertTrue(original.any { it.getReference<MethodReference>()?.definingClass == "LX/0FXE;" })
        }
    }

    @Test fun `native wrapper metadata and allocated diff generation are observed without replacement`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val stubOwner = dex.getEntry("classes4.dex")!!.dexFile.classes.single { it.type == "Lcom/bytedance/ies/powerlist/PowerStub;" }
            val stub = stubOwner.methods.single { it.name == "onChanged" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") }
            assertTrue(InboxStubPublication.takes(stub, stubOwner))
            val mutableStub = MutableMethod(stub)
            val originalStub = mutableStub.implementation!!.instructions.toList()
            mutableStub.loadedStubHook()
            val stubCode = mutableStub.implementation!!.instructions.toList()
            assertEquals(originalStub.size + 1, stubCode.size)
            val receipt = stubCode.indexOfFirst { it.extensionCall("onLoadedStubPublication") }
            assertEquals("LX/0Gy2;", stubCode[receipt - 1].getReference<MethodReference>()!!.definingClass)
            assertEquals("<init>", stubCode[receipt - 1].getReference<MethodReference>()!!.name)
            val metadata = (stubCode[receipt - 1] as RegisterRangeInstruction).startRegister
            assertEquals(metadata, (stubCode[receipt] as FiveRegisterInstruction).registerE)
            assertEquals(originalStub, stubCode.filterNot { it.extensionCall("onLoadedStubPublication") })

            val differ = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/09Mk;" }
            val submit = differ.methods.single { it.name == "LJ" }
            assertTrue(InboxDiffSubmission.takes(submit, differ))
            val mutableSubmit = MutableMethod(submit)
            val originalSubmit = mutableSubmit.implementation!!.instructions.toList()
            mutableSubmit.loadedDiffSubmissionHook()
            val submitted = mutableSubmit.implementation!!.instructions.toList()
            val submission = submitted.indexOfFirst { it.extensionCall("onLoadedDiffSubmitted") }
            assertEquals(Opcode.IPUT, submitted[submission - 1].opcode)
            assertEquals("LJIIJ", submitted[submission - 1].getReference<FieldReference>()!!.name)
            assertEquals(5, (submitted[submission] as RegisterRangeInstruction).registerCount)
            assertEquals(originalSubmit, submitted.filterNot { it.extensionCall("onLoadedDiffSubmitted") })
        }
    }

    @Test fun `diff acceptance follows real dispatch and every listener exit reaches it before native completions`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owner = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/09Mk;" }
            val native = owner.methods.single { it.name == "LIZIZ" }
            assertTrue(InboxDiffAcceptance.takes(native, owner))
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val completion = original.indexOfFirst { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == "LX/0Gy2;" && field.name == "LJ" } == true }
            val exits = original.filterIsInstance<BuilderOffsetInstruction>().filter { it.target.location.index == completion }
            assertTrue("the native listener loop has a branch directly to completion delivery", exits.isNotEmpty())
            mutable.loadedDiffAcceptanceHook()
            val after = mutable.implementation!!.instructions.toList()
            val accepted = after.indexOfFirst { it.extensionCall("onLoadedDiffAccepted") }
            assertEquals(completion, accepted)
            assertSame(original[completion], after[accepted + 1])
            exits.forEach { assertEquals(accepted, it.target.location.index) }
            val dispatched = after.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "LX/00J8;" && method.name == "LIZIZ" } == true }
            val completed = after.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "Ljava/lang/Runnable;" && method.name == "run" } == true }
            assertTrue(dispatched in 0 until accepted)
            assertTrue(completed > accepted)
            assertEquals(original, after.filterNot { it.extensionCall("onLoadedDiffAccepted") })
            val registers = after[accepted] as FiveRegisterInstruction
            assertEquals(mutable.implementation!!.registerCount - 4, registers.registerC)
            assertEquals(registers.registerC + 2, registers.registerD)
        }
    }

    @Test fun `native async generation rejection happens before list acceptance or diff dispatch`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val native = dex.getEntry("classes31.dex")!!.dexFile.classes.single { it.type == "LX/14Uz;" }
                .methods.single { it.name == "LIZ" }
            val code = MutableMethod(native).implementation!!.instructions.toList()
            val generation = code.indexOfFirst { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == "LX/09Mk;" && field.name == "LJIIJ" } == true }
            val rejection = code.filterIsInstance<BuilderOffsetInstruction>().single { it.opcode == Opcode.IF_NE }
            assertTrue(code.indexOf(rejection) > generation)
            assertEquals(Opcode.RETURN_VOID, rejection.target.location.instruction!!.opcode)
            val listWrite = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>()?.name == "LJIIJJI" }
            val dispatch = code.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "LX/09Mk;" && method.name == "LIZLLL" } == true }
            assertTrue(listWrite > code.indexOf(rejection))
            assertTrue(dispatch > listWrite && dispatch < rejection.target.location.index)
        }
    }

    @Test fun `indexed callbacks keep the native helper and pair while current row clicks keep c6`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val closureOwner = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == INBOX_MERGED_CLOSURE }
            val closure = closureOwner.methods.single { it.name == "invoke\$43" }
            assertTrue(InboxIndexedCallback.takes(closure, closureOwner))
            val mutable = MutableMethod(closure)
            val original = mutable.implementation!!.instructions.toList()
            val index = original.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "LX/0NaB;" && method.name == "LJFF" } == true }
            val call = original[index] as FiveRegisterInstruction
            mutable.loadedIndexedHook()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size, after.size)
            original.forEachIndexed { i, instruction -> if (i != index) assertSame(instruction, after[i]) }
            assertTrue(after[index].extensionCall("onLoadedIndexedCallback"))
            val bridge = after[index] as FiveRegisterInstruction
            assertEquals(4, bridge.registerCount)
            assertEquals(call.registerC, bridge.registerD)
            assertEquals(call.registerD, bridge.registerE)
            assertEquals(call.registerE, bridge.registerF)

            val cell = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == TEMPLATE_NOTIFICATION_CELL }
            val click = cell.methods.single { it.name == "onClick" }
            val mutableClick = MutableMethod(click)
            val originalClick = mutableClick.implementation!!.instructions.toList()
            mutableClick.loadedGuard("allowLoadedNotificationClick")
            val clickCode = mutableClick.implementation!!.instructions.toList()
            assertEquals(originalClick, clickCode.drop(4))
            assertTrue(clickCode.any { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == cell.type && method.name == "c6" } == true })
            val entry = clickCode[2] as BuilderOffsetInstruction
            assertSame(originalClick[0], entry.target.location.instruction)
        }
    }

    @Test fun `both indexed live-data reads use the accepted aggregate without changing other native work`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owner = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == INBOX_RESULT_FRAGMENT }
            val native = owner.methods.single { it.name == "DX" }
            assertTrue(InboxVisibleIndices.takes(native, owner))
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            mutable.loadedVisibleHook()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size + 8, after.size)
            assertTrue(after[0].extensionCall("allowLoadedVisibleIndices"))
            val reads = after.withIndex().filter { it.value.getReference<FieldReference>()?.let { field ->
                field.definingClass == "LX/0FXE;" && field.name == "LIZ" } == true }
            assertEquals(2, reads.size)
            reads.forEach { assertTrue(after[it.index + 1].extensionCall("onLoadedIndexedList")) }
            val originalCalls = original.mapNotNull { it.getReference<MethodReference>() }
            assertEquals(originalCalls, after.mapNotNull { it.getReference<MethodReference>() }
                .filterNot { it.definingClass == INBOX_QUERY_EXTENSION })
        }
    }

    @Test fun `pull and manual refresh entry guards precede every native flag listener and animation`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val spring = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/09Ni;" }
            listOf(Triple("setRefreshing", InboxSpringRefresh, "allowLoadedRefresh"),
                Triple("LJ", InboxSpringManualRefresh, "allowLoadedManualRefresh")).forEach { (name, fingerprint, hook) ->
                val native = spring.methods.single { it.name == name }
                assertTrue(fingerprint.takes(native, spring))
                val mutable = MutableMethod(native)
                val original = mutable.implementation!!.instructions.toList()
                mutable.loadedRefreshHook()
                val after = mutable.implementation!!.instructions.toList()
                assertTrue(after[0].extensionCall(hook))
                assertEquals(original, after.drop(4))
                assertEquals(Opcode.RETURN_VOID, after[3].opcode)
                assertSame(original[0], (after[2] as BuilderOffsetInstruction).target.location.instruction)
                val firstFlag = after.indexOfFirst { it.opcode == Opcode.IPUT_BOOLEAN }
                assertTrue(firstFlag > 3)
                if (name == "LJ") {
                    val refreshCall = after.indexOfFirst { it.getReference<MethodReference>()?.name == "setRefreshing" }
                    val manualFlag = after.indexOfFirst { it.getReference<FieldReference>()?.name == "LLLIIIIL" }
                    val animation = after.indexOfFirst { it.getReference<MethodReference>()?.let { ref ->
                        ref.definingClass == "Landroid/animation/ValueAnimator;" && ref.name == "start" } == true }
                    assertTrue(refreshCall > 3 && manualFlag > refreshCall && animation > manualFlag)
                } else {
                    assertTrue(after.indexOfFirst { it.getReference<MethodReference>()?.name == "LJFF" } > firstFlag)
                    assertEquals(2, (after[0] as RegisterRangeInstruction).registerCount)
                }
            }
        }
    }

    @Test fun `synthetic index gate repeats the exact native getter and field without touching yX`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owner = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == INBOX_MERGED_CLOSURE }
            val native = owner.methods.single { it.name == "invoke\$43" }
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val bridge = loadedIndicesGate(mutable)
            assertEquals(INBOX_RESULT_FRAGMENT, bridge.definingClass)
            assertEquals(INBOX_INDICES_GATE, bridge.name)
            assertTrue(bridge.parameterTypes.isEmpty())
            assertEquals("Z", bridge.returnType)
            val gate = bridge.implementation!!.instructions.toList()
            assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IGET_BOOLEAN, Opcode.RETURN),
                gate.map { it.opcode })
            assertEquals(original[0].getReference<MethodReference>(), gate[0].getReference<MethodReference>())
            assertEquals(original[2].getReference<FieldReference>(), gate[2].getReference<FieldReference>())
            assertEquals("enable", gate[2].getReference<FieldReference>()!!.name)
            assertFalse(gate.any { it.getReference<MethodReference>()?.name == "yX" })
            val disabled = original[3] as BuilderOffsetInstruction
            assertEquals(Opcode.IF_EQZ, disabled.opcode)
            assertEquals("Lkotlin/Unit;", disabled.target.location.instruction!!.getReference<FieldReference>()!!.definingClass)
            mutable.loadedIndexedHook()
            assertSame(original[3], mutable.implementation!!.instructions[3])
            assertTrue(mutable.implementation!!.instructions.indexOfFirst { it.extensionCall("onLoadedIndexedCallback") } > 3)
        }
    }
}

private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.extensionCall(name: String): Boolean =
    getReference<MethodReference>()?.let { it.definingClass == INBOX_QUERY_EXTENSION && it.name == name } == true
