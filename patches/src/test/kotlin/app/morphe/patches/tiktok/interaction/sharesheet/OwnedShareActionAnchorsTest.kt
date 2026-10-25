/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.downloads.STORY_SHARE_SAVE
import app.morphe.patches.tiktok.interaction.downloads.addShareActionToSnapshot
import app.morphe.patches.tiktok.interaction.downloads.addStorySaveToSnapshot
import app.morphe.patches.tiktok.interaction.downloads.snapshotActionFields
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native-only tests: interface dispatch, exact post identity, and coexistence of every share sheet addition. */
class OwnedShareActionAnchorsTest {
    private data class Found(
        val action: ClassDef,
        val constructor: Method,
        val helper: Method,
        val iconBinders: List<Pair<Method, OwnedShareIconBind>>,
        val imageFields: List<String>,
        val snapshot: ClassDef,
        val snapshotConstructor: Method,
        val models: Map<String, ClassDef>,
        val interfaces: List<ClassDef>,
    )

    companion object {
        private val cache = mutableMapOf<String, Found>()
        private const val POST_PACKAGE = "Lcom/ss/android/ugc/aweme/share/improve/pkg/AwemeSharePackage;"
        private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        private const val USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
        private const val BASE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
        private const val STRING = "Ljava/lang/String;"
    }

    @Test fun `one public native Report carrier has the full interface and a side effect free constructor`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            assertTrue(AccessFlags.PUBLIC.isSet(found.action.accessFlags))
            assertTrue(AccessFlags.PUBLIC.isSet(found.constructor.accessFlags))
            val code = found.constructor.implementation!!.instructions.toList()
            val calls = code.mapNotNull { it.getReference<MethodReference>() }
            assertEquals(1, calls.size)
            assertEquals("Ljava/lang/Object;", calls.single().definingClass)
            assertEquals("<init>", calls.single().name)
            assertEquals(6, code.count { it.opcode == Opcode.IPUT_OBJECT })
            for (member in listOf(found.action.reportKey(), found.action.reportLabel(found.helper), found.action.reportClick())) {
                assertTrue("${member.name} isn't dispatched through the action interface", found.interfaces.any { owner ->
                    owner.methods.any { it.name == member.name && sameSignature(it, member) }
                })
            }
            // The native text helper calls the selected label getter. A second empty getter is unrelated.
            val label = found.action.reportLabel(found.helper)
            assertTrue(found.helper.implementation!!.instructions.any {
                it.opcode == Opcode.INVOKE_INTERFACE && it.getReference<MethodReference>()?.name == label.name
            })
        }
    }

    @Test fun `owned guards preserve every original Report instruction and pass the actual package to click`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            for ((method, member) in listOf(found.action.reportKey() to "key", found.action.reportLabel(found.helper) to "label")) {
                val patched = MutableMethod(method)
                val before = method.implementation!!.instructions.toList()
                patched.answerOwnedShareObject(member, STRING)
                val code = patched.implementation!!.instructions.toList()
                assertEquals(before.map { it.opcode }, code.takeLast(before.size).map { it.opcode })
                assertEquals("owns", code[0].getReference<MethodReference>()!!.name)
                assertEquals(Opcode.IF_EQZ, code[2].opcode)
                assertEquals(member, code[3].getReference<MethodReference>()!!.name)
                assertEquals(Opcode.RETURN_OBJECT, code[5].opcode)
                assertEquals(before.size + 7, code.size)
            }
            val original = found.action.reportClick()
            val click = MutableMethod(original)
            click.takeOwnedShareClick()
            val code = click.implementation!!.instructions.toList()
            val call = code[0].getReference<MethodReference>()!!
            val registers = code[0] as RegisterRangeInstruction
            assertEquals(OWNED_SHARE_ACTIONS, call.definingClass)
            assertEquals(listOf("Ljava/lang/Object;", "Landroid/content/Context;", "Ljava/lang/Object;"),
                call.parameterTypes.map(CharSequence::toString))
            assertEquals(3, registers.registerCount)
            assertEquals(click.implementation!!.registerCount - 3, registers.startRegister)
            assertEquals(Opcode.RETURN_VOID, code[3].opcode)
            assertEquals(original.implementation!!.instructions.map { it.opcode }, code.drop(5).map { it.opcode })
        }
    }

    @Test fun `the label follows the String sent to TextView despite repeated and unrelated reads`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val label = found.action.reportLabel(found.helper)
            val other = found.action.methods.single { method ->
                method.name != label.name && method.returnType == STRING && method.parameterTypes.isEmpty() &&
                    method.implementation?.instructions?.any {
                        it.getReference<StringReference>()?.string == ""
                    } == true
            }
            val helper = MutableMethod(found.helper)
            helper.addInstructions(0, """
                invoke-interface/range { p0 .. p0 }, ${found.action.interfaces.single()}->${other.name}()$STRING
                move-result-object v0
            """)
            assertEquals(label.name, found.action.reportLabel(helper).name)
            assertTrue(found.helper.implementation!!.instructions.count {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass in found.action.interfaces && ref.name == label.name
                } == true
            } > 1)
        }
    }

    @Test fun `every native action bind resets its drawable before the owned icon hook`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            for ((method, bind) in found.iconBinders) {
                val binder = MutableMethod(method)
                val before = binder.implementation!!.instructions.toList()
                binder.bindOwnedShareIcon(bind)
                val code = binder.implementation!!.instructions.toList()
                val at = bind.index
                val callback = before[at - 2].getReference<MethodReference>()!!
                assertTrue(callback.definingClass in found.action.interfaces)
                assertTrue("The post Report carrier must declare the icon callback", found.action.methods.any {
                    it.definingClass == found.action.type && it.name == callback.name &&
                        it.returnType == callback.returnType && it.parameterTypes.map(CharSequence::toString) ==
                        callback.parameterTypes.map(CharSequence::toString)
                })
                assertEquals("setImageDrawable", code[at].getReference<MethodReference>()!!.name)
                assertEquals("bindIcon", code[at + 1].getReference<MethodReference>()!!.name)
                val args = code[at + 1] as FiveRegisterInstruction
                assertEquals(bind.actionRegister, args.registerC)
                assertEquals(bind.imageRegister, args.registerD)
                assertTrue(args.registerC in 0..15 && args.registerD in 0..15)
                assertEquals(before.map { it.opcode }, (code.take(at + 1) + code.drop(at + 2)).map { it.opcode })
            }
        }
    }

    @Test fun `each icon binder's framed-key test asks the owned carrier for its key once`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val lists = mutableSetOf<Pair<String, String>>()
            for ((method, bind) in found.iconBinders) {
                val frame = method.ownedShareFrameCheck(found.action)
                assertNotNull("${method.definingClass} lost its framed-key test", frame)
                frame!!
                assertTrue("the test comes before the icon store", frame.keyIndex < bind.index)
                val binder = MutableMethod(method)
                val before = binder.implementation!!.instructions.toList()
                binder.frameOwnedShareIcon(frame)
                val code = binder.implementation!!.instructions.toList()
                val asked = code[frame.keyIndex + 1]
                assertEquals("framedKey", asked.getReference<MethodReference>()!!.name)
                assertEquals(listOf(frame.actionRegister, frame.keyRegister),
                    (asked as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
                assertEquals(frame.keyRegister, (code[frame.keyIndex + 2] as OneRegisterInstruction).registerA)
                // The key's register dies at the index-of, so nothing after it sees Report's key.
                val test = code[frame.keyIndex + 3] as FiveRegisterInstruction
                assertEquals(frame.keyRegister, test.registerC)
                assertEquals(Opcode.MOVE_RESULT, code[frame.keyIndex + 4].opcode)
                assertEquals(frame.keyRegister, (code[frame.keyIndex + 4] as OneRegisterInstruction).registerA)
                // The list is the static String[] the test reads; on 47.1.4 it holds "report".
                val list = before[frame.keyIndex - 2].getReference<FieldReference>()!!
                assertEquals("[Ljava/lang/String;", list.type)
                assertEquals(Opcode.SGET_OBJECT, before[frame.keyIndex - 2].opcode)
                lists += list.definingClass to list.name
            }
            // "report" among the strings built into that exact field in its class's <clinit>, between
            // the static store before it and its own.
            val holding = mutableSetOf<Pair<String, String>>()
            walk(apk) { owner ->
                val clinit = owner.methods.firstOrNull { it.name == "<clinit>" }
                    ?.implementation?.instructions?.toList() ?: return@walk
                for ((type, name) in lists) {
                    if (owner.type != type) continue
                    val store = clinit.indexOfFirst { instruction ->
                        instruction.opcode == Opcode.SPUT_OBJECT &&
                            instruction.getReference<FieldReference>()?.let { it.definingClass == type && it.name == name } == true
                    }
                    if (store < 0) continue
                    val start = clinit.subList(0, store).indexOfLast { it.opcode == Opcode.SPUT_OBJECT } + 1
                    if (clinit.subList(start, store).any { it.getReference<StringReference>()?.string == "report" }) {
                        holding += type to name
                    }
                }
            }
            assertEquals("the framed-key list holds Report's key", lists, holding)
        }
    }

    @Test fun `the share grid and the long-press panel list both get the owned icon`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            assertEquals("the grid's ImageView and the long-press list's TuxIconView",
                listOf("Landroid/widget/ImageView;", "Lcom/bytedance/tux/icon/TuxIconView;"), found.imageFields.sorted())
            assertTrue("only the plain ImageView store matches without the subclass check",
                found.iconBinders.count { (method, _) -> method.ownedShareIconBind(found.action) != null } == 1)
        }
    }

    @Test fun `the factory binds the post and activity and every addition keeps the current finished row`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val stub = MutableMethod(ImmutableMethod(
                OWNED_SHARE_ACTIONS, "newAction",
                listOf(ImmutableMethodParameter("Landroid/app/Activity;", null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "Ljava/lang/Object;", AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(3, emptyList(), null, null),
            )).apply { addInstructions(0, "const/4 v0, 0x0\nreturn-object v0") }
            val factory = stub.withOwnedShareBody(found.action)
            val code = factory.implementation!!.instructions.toList()
            val total = factory.implementation!!.registerCount
            assertEquals(Opcode.CHECK_CAST, code[0].opcode)
            assertEquals(total - 1, (code[0] as OneRegisterInstruction).registerA)
            assertEquals(1, (code[2] as TwoRegisterInstruction).registerA)
            assertEquals(total - 1, (code[2] as TwoRegisterInstruction).registerB)
            assertEquals(5, (code[6] as TwoRegisterInstruction).registerA)
            assertEquals(total - 2, (code[6] as TwoRegisterInstruction).registerB)
            val constructor = code.single { it.opcode == Opcode.INVOKE_DIRECT_RANGE }
            assertEquals(7, (constructor as RegisterRangeInstruction).registerCount)
            assertEquals(found.action.type, constructor.getReference<MethodReference>()!!.definingClass)
            assertEquals("<init>", constructor.getReference<MethodReference>()!!.name)
            assertEquals(found.constructor.parameterTypes.map(CharSequence::toString),
                constructor.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))

            val snapshot = MutableMethod(found.snapshotConstructor)
            val fields = snapshot.snapshotActionFields(found.snapshot)
            val returns = snapshot.implementation!!.instructions.count { it.opcode == Opcode.RETURN_VOID }
            snapshot.addShareActionToSnapshot(fields, SHARE_BLOCK_AUTHOR)
            snapshot.addStorySaveToSnapshot(fields)
            snapshot.addShareActionToSnapshot(fields, SHARE_SAVE_COVER)
            val finished = snapshot.implementation!!.instructions.toList()
            for (extension in listOf(SHARE_BLOCK_AUTHOR, STORY_SHARE_SAVE, SHARE_SAVE_COVER)) {
                val calls = finished.indices.filter { finished[it].getReference<MethodReference>()?.definingClass == extension }
                assertEquals(returns, calls.size)
                for (at in calls) {
                    assertEquals(fields.sharePackage, finished[at - 2].getReference<FieldReference>()!!.name)
                    assertEquals(fields.actions, finished[at - 1].getReference<FieldReference>()!!.name)
                    assertEquals(Opcode.MOVE_RESULT_OBJECT, finished[at + 1].opcode)
                    assertEquals(fields.actions, finished[at + 2].getReference<FieldReference>()!!.name)
                }
            }
        }
    }

    @Test fun `the explicit post package and user identity members exist on the declared build`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            assertTrue(found.models.getValue(POST_PACKAGE).fields.any { it.name == "aweme" && it.type == AWEME })
            assertTrue(found.models.getValue(BASE).fields.any { it.name == "itemType" && it.type == STRING })
            assertTrue(found.models.getValue(AWEME).methods.any {
                it.name == "getAuthor" && it.parameterTypes.isEmpty() && it.returnType == USER
            })
            for (name in listOf("getUid", "getSecUid", "getUniqueId", "getNickname")) {
                assertTrue(found.models.getValue(USER).methods.any {
                    it.name == name && it.parameterTypes.isEmpty() && it.returnType == STRING
                })
            }
        }
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            val actions = mutableListOf<Pair<ClassDef, Method>>()
            val snapshots = mutableListOf<Pair<ClassDef, Method>>()
            val models = mutableMapOf<String, ClassDef>()
            val superclasses = mutableMapOf<String, String?>()
            walk(apk) { owner ->
                superclasses[owner.type] = owner.superclass
                if (owner.type in setOf(POST_PACKAGE, BASE, AWEME, USER)) models[owner.type] = owner
                for (method in owner.methods) {
                    if (NativeReportActionFingerprint.takes(method, owner)) actions += owner to method
                    if (ShareSnapshotFingerprint.takes(method, owner)) snapshots += owner to method
                }
            }
            assertEquals("native Report carriers", 1, actions.size)
            assertEquals("share snapshots", 1, snapshots.size)
            val (action, constructor) = actions.single()
            val helperRef = action.reportLabelHelper()
            var helper: Method? = null
            val binders = mutableListOf<Pair<Method, OwnedShareIconBind>>()
            val interfaces = mutableListOf<ClassDef>()
            val isImageView = { type: String ->
                generateSequence(type) { superclasses[it] }.take(20).any { it == "Landroid/widget/ImageView;" }
            }
            walk(apk) { owner ->
                if (owner.type in action.interfaces) interfaces += owner
                if (owner.type == helperRef.definingClass) helper = owner.methods.single {
                    it.name == helperRef.name && it.parameterTypes.map(CharSequence::toString) ==
                        helperRef.parameterTypes.map(CharSequence::toString) && it.returnType == helperRef.returnType
                }
                for (method in owner.methods) method.ownedShareIconBind(action, isImageView)?.let { binders += method to it }
            }
            assertNotNull("native text helper", helper)
            assertEquals("native action icon binders", 2, binders.size)
            val imageFields = binders.map { (method, bind) ->
                method.implementation!!.instructions.toList()[bind.index - 1].getReference<FieldReference>()!!.type
            }
            val (snapshot, snapshotConstructor) = snapshots.single()
            Found(action, constructor, helper!!, binders, imageFields,
                snapshot, snapshotConstructor, models, interfaces)
        }
    }

    private fun sameSignature(left: Method, right: Method) = left.returnType == right.returnType &&
        left.parameterTypes.map(CharSequence::toString) == right.parameterTypes.map(CharSequence::toString)

    private fun walk(apk: File, visit: (ClassDef) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) for (owner in container.getEntry(entry)!!.dexFile.classes) visit(owner)
    }
}
