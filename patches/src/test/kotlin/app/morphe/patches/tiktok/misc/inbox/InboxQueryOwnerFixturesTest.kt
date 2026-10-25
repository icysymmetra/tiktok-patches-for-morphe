package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import org.junit.Assert.*
import org.junit.Test

private const val FILTER = "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"
private const val CELL = "Lcom/bytedance/ies/powerlist/PowerCell;"
private const val STUB = "Lcom/bytedance/ies/powerlist/PowerStub;"
private const val FRAGMENT = "Lcom/ss/android/ugc/aweme/notification/v2/NotificationResultFragment;"
private const val WIDGET = "Lcom/ss/android/ugc/aweme/notification/v2/widget/NotificationWidget;"

/** The current notification cell and VM identity contracts used by loaded-only search. */
class InboxQueryOwnerFixturesTest {
    @Test
    fun `both current native bind variants capture after success without replacing a listener or model`() {
        assertEquals(listOf("47.4.3"), Fixtures.declaredVersions())
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val cell = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == TEMPLATE_NOTIFICATION_CELL }
            val variants = listOf(InboxNotificationNormalBind to listOf("LX/05u8;"),
                InboxNotificationPayloadBind to listOf("LX/05u8;", "Ljava/util/List;"))
            variants.forEach { (fingerprint, parameters) ->
                val native = cell.methods.single { it.name == "onBindItemView" && it.parameterTypes.map(CharSequence::toString) == parameters }
                assertTrue(fingerprint.takes(native, cell))
                assertBindUnchanged(native)
            }
            // The current model getter is what native clicks read after a payload rebind/recycle.
            val powerCell = dex.getEntry("classes4.dex")!!.dexFile.classes.single { it.type == CELL }
            assertEquals("item", powerCell.method("getItem").references<FieldReference>().single().name)
            assertEquals("attachView", powerCell.method("getAttachView\$powerlist_release").references<FieldReference>().single().name)
            assertEquals("stub", powerCell.method("getStub").references<FieldReference>().single().name)
            val currentNotice = cell.method("c6").references<MethodReference>()
            assertTrue(currentNotice.any { it.definingClass == CELL && it.name == "getItem" })
            assertTrue(cell.method("onClick").references<MethodReference>().any { it.definingClass == cell.type && it.name == "c6" })
        }
    }

    @Test
    fun `the observed VM is the native returned object and its lazy getter runs only once`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val widget = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == WIDGET }
            val native = widget.method("hq")
            assertTrue(InboxNotificationVmGetter.takes(native, widget))
            val mutable = MutableMethod(native)
            val original = mutable.implementation!!.instructions.toList()
            val receiver = mutable.implementation!!.registerCount - 1
            mutable.captureLoadedInboxVm()
            val after = mutable.implementation!!.instructions.toList()
            assertEquals(original.size + original.count { it.opcode == Opcode.RETURN_OBJECT }, after.size)
            var source = 0
            after.forEachIndexed { index, instruction ->
                val reference = instruction.getReference<MethodReference>()
                if (reference?.definingClass != FILTER) { assertSame(original[source++], instruction); return@forEachIndexed }
                assertEquals("onNotificationVmObserved", reference.name)
                assertEquals(listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), reference.parameterTypes.map(CharSequence::toString))
                val call = instruction as FiveRegisterInstruction
                assertEquals(receiver, call.registerC)
                assertEquals(2, call.registerCount)
                assertEquals(Opcode.RETURN_OBJECT, after[index + 1].opcode)
                assertEquals((after[index + 1] as OneRegisterInstruction).registerA, call.registerD)
            }
            assertEquals(original.size, source)
            assertEquals(1, after.count { it.getReference<MethodReference>()?.definingClass == "Lcom/bytedance/assem/arch/viewModel/AssemVMLazy;" })
        }
    }

    @Test
    fun `native owner state and manager identities have the exact current signatures`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val stub = dex.getEntry("classes4.dex")!!.dexFile.classes.single { it.type == STUB }
            assertEquals("LX/09Mx;", stub.method("LJIIZILJ").returnType)
            assertEquals("LX/09MC;", stub.fields.single { it.name == "LLJJIII" }.type)
            val recycler = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/09LW;" }
            assertEquals("LX/09Mx;", recycler.method("getState").returnType)
            val state = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/09Mx;" }
            assertEquals("Lcom/bytedance/ies/powerlist/ListState;", state.superclass)
            assertEquals("Ljava/util/List;", state.fields.single { it.name == "LJ" }.type)
            assertEquals("Ljava/util/concurrent/locks/ReentrantLock;", state.fields.single { it.name == "LJFF" }.type)
            val fragment = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == FRAGMENT }
            assertEquals(recycler.type, fragment.method("BX").returnType)
            val spring = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/09Nj;" }
            assertEquals(spring.type, fragment.method("zX").returnType)
            assertEquals("LX/09Ni;", spring.superclass)
            val constrainedParent = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == spring.superclass }
            assertEquals("Landroid/widget/FrameLayout;", constrainedParent.superclass)
            assertTrue(constrainedParent.method("getNestedChild").references<MethodReference>()
                .any { it.name == "getChildCount" })
            assertTrue(constrainedParent.method("getNestedChild").references<MethodReference>()
                .any { it.definingClass == "Ljava/lang/IllegalArgumentException;" && it.name == "<init>" })
            assertEquals("LX/0NaB;", fragment.method("yX").returnType)
            assertEquals("Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidgetContainer;",
                fragment.fields.single { it.name == "LLJJIJIL" }.type)
        }
    }

    private fun assertBindUnchanged(native: Method) {
        val mutable = MutableMethod(native)
        val original = mutable.implementation!!.instructions.toList()
        val receiver = mutable.implementation!!.registerCount - mutable.parameterTypes.size - 1
        mutable.captureLoadedInboxOwner()
        val after = mutable.implementation!!.instructions.toList()
        assertEquals(original.size + original.count { it.opcode == Opcode.RETURN_VOID }, after.size)
        var source = 0
        after.forEachIndexed { index, instruction ->
            val reference = instruction.getReference<MethodReference>()
            if (reference?.definingClass != FILTER) { assertSame(original[source++], instruction); return@forEachIndexed }
            assertEquals("onNotificationRowBound", reference.name)
            val call = instruction as RegisterRangeInstruction
            assertEquals(receiver, call.startRegister)
            assertEquals(1, call.registerCount)
            assertEquals(Opcode.RETURN_VOID, after[index + 1].opcode)
        }
        assertEquals(original.size, source)
    }

    private fun ClassDef.method(name: String): Method = methods.single { it.name == name }
    private inline fun <reified T : Reference> Method.references(): List<T> = implementation!!.instructions.mapNotNull { it.getReference<T>() }
}
