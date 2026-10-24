/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.sharesheet.SHARE_BUILDER_ACTIONS
import app.morphe.patches.tiktok.interaction.sharesheet.ShareSnapshotFingerprint
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the save button in a story's share sheet (#93) hooks, held to each declared build: TikTok's
 * own video save action, found by its constructor and the shape of its key, enable check and
 * click, and the share sheet snapshot whose finished actions row the button joins. The snapshot
 * is the one Share sheet tools already hooks, so its fingerprint is that patch's.
 */
class StoryShareSaveAnchorsTest {
    private class Found(
        val action: ClassDef,
        val constructor: Method,
        val interfaces: List<ClassDef>,
        val snapshot: ClassDef,
        val snapshotConstructor: Method,
    )

    @Test
    fun `the save action fingerprint takes one public constructor on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val shaped = mutableListOf<String>()
            val taken = mutableListOf<String>()
            walk(apk) { classDef, method ->
                if (method.isSaveActionConstructor()) shaped += classDef.type
                if (NativeSaveActionFingerprint.takes(method, classDef)) taken += classDef.type
            }
            // The only constructor of its signature in the app, and the fingerprint takes it.
            assertEquals("$version: constructors of the save action's signature $shaped", 1, shaped.size)
            assertEquals("$version: the fingerprint takes $taken", shaped, taken)

            val found = find(apk)
            assertTrue("$version: ${found.action.type} isn't public", AccessFlags.PUBLIC.isSet(found.action.accessFlags))
            assertTrue("$version: its constructor isn't public", AccessFlags.PUBLIC.isSet(found.constructor.accessFlags))
            val key = found.action.methods.single { it.isSaveKey() }
            assertEquals(NATIVE_SAVE_KEY, key.implementation!!.instructions.first().getReference<StringReference>()!!.string)
        }
    }

    @Test
    fun `the panel reaches the hooked enable check and click through the action interface`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = find(apk)
            val enable = found.action.methods.filter { it.isEnableCheck() }
            assertEquals("$version: enable checks", 1, enable.size)
            val clicks = found.action.saveClicks()
            assertEquals("$version: clicks ${clicks.map { it.name }}", 1, clicks.size)
            // The class has other (Context, BaseSharePackage) methods, and the cast tells the click apart.
            val sameSignature = found.action.methods.filter {
                it.parameterTypes.map(CharSequence::toString) == clicks.single().parameterTypes.map(CharSequence::toString) &&
                    it.returnType == "V"
            }
            assertTrue("$version: ${sameSignature.map { it.name }}", sameSignature.size >= 2)

            // TikTok's panel calls both through the action's interface, so these are the bodies a
            // tap and a bind run.
            fun declares(method: Method) = found.interfaces.any { owner ->
                owner.methods.any {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }
            }
            assertTrue("$version: no interface of ${found.action.type} declares the enable check", declares(enable.single()))
            assertTrue("$version: no interface of ${found.action.type} declares ${clicks.single().name}", declares(clicks.single()))
        }
    }

    @Test
    fun `the hooks go first in the enable check and the click, with registers that fit`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = find(apk)

            val enable = MutableMethod(found.action.methods.single { it.isEnableCheck() })
            val enableBefore = enable.implementation!!.instructions.size
            enable.answerStorySaveEnable()
            val enableCode = enable.implementation!!.instructions.toList()
            val owns = enableCode.first().getReference<MethodReference>()!!
            assertEquals(STORY_SHARE_SAVE, owns.definingClass)
            assertEquals("owns", owns.name)
            assertEquals("$version: the action itself is asked about", enable.implementation!!.registerCount - 1,
                (enableCode.first() as RegisterRangeInstruction).startRegister)
            // The call, its answer, the branch, a true and its return, the nop the branch lands on.
            assertEquals(enableBefore + 6, enableCode.size)
            assertEquals(Opcode.RETURN, enableCode[4].opcode)

            val click = MutableMethod(found.action.saveClicks().single())
            val clickBefore = click.implementation!!.instructions.size
            click.takeStorySaveClick()
            val clickCode = click.implementation!!.instructions.toList()
            val taken = clickCode.first() as RegisterRangeInstruction
            val call = clickCode.first().getReference<MethodReference>()!!
            assertEquals(STORY_SHARE_SAVE, call.definingClass)
            assertEquals("click", call.name)
            assertEquals(listOf("Ljava/lang/Object;", "Landroid/content/Context;"), call.parameterTypes.map(CharSequence::toString))
            // The action and the context the panel hands its click.
            assertEquals("$version: click is handed", click.implementation!!.registerCount - 3, taken.startRegister)
            assertEquals(2, taken.registerCount)
            assertEquals(Opcode.RETURN_VOID, clickCode[3].opcode)
            assertEquals(clickBefore + 5, clickCode.size)
        }
    }

    @Test
    fun `the snapshot keeps one package and one actions row, final once its constructor returns`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = find(apk)
            val fields = found.snapshotConstructor.snapshotActionFields(found.snapshot)
            val code = found.snapshotConstructor.implementation!!.instructions.toList()
            fun touches(opcode: Opcode, name: String) = code.count {
                it.opcode == opcode && it.getReference<FieldReference>()
                    ?.let { field -> field.definingClass == found.snapshot.type && field.name == name } == true
            }
            // Deduped and filtered, then sorted, or taken as it came: stored more than once and
            // never read back in the constructor.
            assertTrue("$version: the actions row is stored ${touches(Opcode.IPUT_OBJECT, fields.actions)} times",
                touches(Opcode.IPUT_OBJECT, fields.actions) >= 2)
            assertEquals("$version: the constructor reads its actions row back", 0, touches(Opcode.IGET_OBJECT, fields.actions))
            assertEquals("$version: the package is stored", 1, touches(Opcode.IPUT_OBJECT, fields.sharePackage))

            // Share sheet tools filters the builder's row between the read and the store, and the
            // same field is found through it, with another hook's filter in there too.
            val builder = found.snapshotConstructor.parameterTypes.single().toString()
            for (filters in 1..2) {
                val filtered = MutableMethod(found.snapshotConstructor)
                filtered.implementation!!.instructions.withIndex().toList().asReversed().forEach { (index, instruction) ->
                    if (instruction.opcode != Opcode.IGET_OBJECT) return@forEach
                    val field = instruction.getReference<FieldReference>() ?: return@forEach
                    if (field.definingClass != builder || field.name != SHARE_BUILDER_ACTIONS) return@forEach
                    val register = (instruction as TwoRegisterInstruction).registerA
                    filtered.addInstructions(
                        index + 1,
                        List(filters) {
                            """
                                invoke-static/range { v$register .. v$register }, Lfixture/Filter$it;->actions(Ljava/util/List;)Ljava/util/List;
                                move-result-object v$register
                            """
                        }.joinToString("\n"),
                    )
                }
                assertEquals("$version, $filters filters: ", fields, filtered.snapshotActionFields(found.snapshot))
            }

            // A load into the register between the read and the store puts another list there, so
            // the store after it isn't the builder's row and no field is taken from it.
            val reloaded = MutableMethod(found.snapshotConstructor)
            reloaded.implementation!!.instructions.withIndex().toList().asReversed().forEach { (index, instruction) ->
                if (instruction.opcode != Opcode.IGET_OBJECT) return@forEach
                val field = instruction.getReference<FieldReference>() ?: return@forEach
                if (field.definingClass != builder || field.name != SHARE_BUILDER_ACTIONS) return@forEach
                val register = (instruction as TwoRegisterInstruction).registerA
                reloaded.addInstructions(index + 1, "const/16 v$register, 0x0")
            }
            assertThrows("$version: a reloaded register still named the actions row", PatchException::class.java) {
                reloaded.snapshotActionFields(found.snapshot)
            }
        }
    }

    @Test
    fun `the row is handed to the extension and stored back before every return of the snapshot`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = find(apk)
            val fields = found.snapshotConstructor.snapshotActionFields(found.snapshot)
            val before = found.snapshotConstructor.implementation!!.instructions.toList()
            val returns = before.count { it.opcode == Opcode.RETURN_VOID }
            assertTrue("$version: the snapshot never returns", returns > 0)
            // Nine locals on 47.1.4, so the hook names the snapshot as p0 and needs no copy of it.
            val registers = found.snapshotConstructor.implementation!!.registerCount
            assertTrue("$version: the snapshot's frame has $registers registers", registers - 2 <= 15)

            val hooked = MutableMethod(found.snapshotConstructor)
            hooked.addStorySaveToSnapshot(fields)
            val after = hooked.implementation!!.instructions.toList()
            assertEquals("$version: grew by", before.size + 5 * returns, after.size)
            for (at in after.indices.filter { after[it].opcode == Opcode.RETURN_VOID }) {
                val read = after[at - 5].getReference<FieldReference>()!!
                assertEquals(Opcode.IGET_OBJECT, after[at - 5].opcode)
                assertEquals(fields.sharePackage, read.name)
                assertEquals(fields.actions, after[at - 4].getReference<FieldReference>()!!.name)
                val call = after[at - 3].getReference<MethodReference>()!!
                assertEquals(STORY_SHARE_SAVE, call.definingClass)
                assertEquals("actions", call.name)
                assertEquals("Ljava/util/List;", call.returnType)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, after[at - 2].opcode)
                val store = after[at - 1]
                assertEquals(Opcode.IPUT_OBJECT, store.opcode)
                assertEquals(fields.actions, store.getReference<FieldReference>()!!.name)
                // The row stored is the one the extension answered.
                assertEquals((after[at - 2] as OneRegisterInstruction).registerA, (store as TwoRegisterInstruction).registerA)
            }
            // Everything else is TikTok's, in its own order.
            val hookOpcodes = listOf(Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.MOVE_RESULT_OBJECT, Opcode.IPUT_OBJECT)
            val stripped = after.map { it.opcode }.toMutableList()
            for (at in stripped.indices.filter { stripped[it] == Opcode.RETURN_VOID }.asReversed()) {
                assertEquals(hookOpcodes, stripped.subList(at - 5, at))
                repeat(5) { stripped.removeAt(at - 5) }
            }
            assertEquals("$version: ", before.map { it.opcode }, stripped)
        }
    }

    @Test
    fun `the bridge builds TikTok's save action from the activity and the story`() {
        val stub = MutableMethod(
            ImmutableMethod(
                STORY_SHARE_SAVE, "newSaveAction",
                listOf(
                    ImmutableMethodParameter("Landroid/app/Activity;", null, null),
                    ImmutableMethodParameter("Ljava/lang/Object;", null, null),
                ),
                "Ljava/lang/Object;", AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        ).apply { addInstructions(0, "const/4 v0, 0x0\nreturn-object v0") }

        val bridge = stub.withStorySaveBody("Lfixture/SaveAction;")
        val registers = bridge.implementation!!.registerCount
        assertEquals(10, registers)
        val code = bridge.implementation!!.instructions.toList()
        // The story is cast to TikTok's post, from the last register, where p1 now is.
        assertEquals(Opcode.CHECK_CAST, code[0].opcode)
        assertEquals(registers - 1, (code[0] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.NEW_INSTANCE, code[1].opcode)
        assertEquals(registers - 2, (code[2] as TwoRegisterInstruction).registerB)
        assertEquals(1, (code[2] as TwoRegisterInstruction).registerA)
        assertEquals(registers - 1, (code[3] as TwoRegisterInstruction).registerB)
        assertEquals(2, (code[3] as TwoRegisterInstruction).registerA)

        val construct = code.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT_RANGE }
        val range = code[construct] as RegisterRangeInstruction
        assertEquals(0, range.startRegister)
        assertEquals(7, range.registerCount)
        val init = code[construct].getReference<MethodReference>()!!
        assertEquals("Lfixture/SaveAction;", init.definingClass)
        assertEquals("<init>", init.name)
        assertEquals(SAVE_ACTION_PARAMETERS, init.parameterTypes.map(CharSequence::toString))
        assertEquals(Opcode.RETURN_OBJECT, code[construct + 1].opcode)
        assertEquals(0, (code[construct + 1] as OneRegisterInstruction).registerA)
    }

    /*
     * The extension side of these calls is held by ExtensionReferencesResolveTest on a patched
     * build and the bridge lookup by ExtensionBridgeLookupTest, so this test reads patch sources
     * only and stays in nativeTest.
     */
    @Test
    fun `advanced downloads adds the button`() {
        val sources = listOf("src/main/kotlin", "patches/src/main/kotlin").map(::File).first { it.isDirectory }
        val patch = File(sources, "app/morphe/patches/tiktok/interaction/downloads/AdvancedDownloadsPatch.kt").readText()
        assertTrue("Advanced downloads never adds the button", patch.contains("hookStoryShareSave()"))
    }

    /** The save action and the snapshot on one build, with the interfaces the action implements. */
    private fun find(apk: File): Found {
        var action: ClassDef? = null
        var constructor: Method? = null
        var snapshot: ClassDef? = null
        var snapshotConstructor: Method? = null
        walk(apk) { classDef, method ->
            if (NativeSaveActionFingerprint.takes(method, classDef)) {
                assertTrue("two save actions", action == null)
                action = classDef
                constructor = method
            }
            if (ShareSnapshotFingerprint.takes(method, classDef)) {
                assertTrue("two share sheet snapshots", snapshot == null)
                snapshot = classDef
                snapshotConstructor = method
            }
        }
        assertNotNull("no save action", action)
        assertNotNull("no share sheet snapshot", snapshot)
        // The interfaces it implements and the ones those extend in the app, whichever declares the
        // methods. A framework interface isn't in the dex and is passed over.
        val interfaces = mutableMapOf<String, ClassDef>()
        val asked = mutableSetOf<String>()
        var wanted = action!!.interfaces.toSet()
        while (wanted.isNotEmpty()) {
            asked += wanted
            walkClasses(apk) { if (it.type in wanted) interfaces[it.type] = it }
            wanted = wanted.mapNotNull { interfaces[it] }.flatMap { it.interfaces }.toSet() - asked
        }
        assertTrue("none of ${action!!.type}'s interfaces are in the app", interfaces.isNotEmpty())
        return Found(action!!, constructor!!, interfaces.values.toList(), snapshot!!, snapshotConstructor!!)
    }

    /** Every method of one build, walked once and never held (the dex has millions). */
    private fun walk(apk: File, visit: (ClassDef, Method) -> Unit) =
        walkClasses(apk) { classDef -> for (method in classDef.methods) visit(classDef, method) }

    private fun walkClasses(apk: File, visit: (ClassDef) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) visit(classDef)
        }
    }
}
