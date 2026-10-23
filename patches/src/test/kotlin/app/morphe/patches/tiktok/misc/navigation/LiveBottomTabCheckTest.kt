package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed's LIVE button asks whether LIVE already has a bottom tab (issue #28): the one static
 * ()Z it carries on every fixture, called by the button alone, and each of its answers goes through
 * the extension on the way out.
 */
class LiveBottomTabCheckTest {
    private val list = "Ljava/util/concurrent/CopyOnWriteArrayList;"
    private val extension = "Lapp/morphe/extension/tiktok/navigation/NavigationTabsFilter;"

    @Test
    fun `every answer is handed to the extension before it leaves, and the branches still land`() {
        val check = MutableMethod(
            ImmutableMethod(
                LIVE_ICON_GENERATOR, "LIZLLL", emptyList(), "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(2, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    sget-object v0, LX/03wG;->LIZJ:$list
                    invoke-virtual { v0 }, $list->isEmpty()Z
                    move-result v0
                    const/4 v1, 0x1
                    if-eqz v0, :has
                    const/4 v1, 0x0
                    :has
                    return v1
                """,
            )
        }
        assertTrue(check.isLiveBottomTabCheck())

        check.answerLiveBottomTab()

        val instructions = check.implementation!!.instructions.toList()
        val call = instructions[instructions.size - 3]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
        val reference = call.getReference<MethodReference>()!!
        assertEquals(extension, reference.definingClass)
        assertEquals("liveHasBottomTab", reference.name)
        assertEquals(1, (call as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.MOVE_RESULT, instructions[instructions.size - 2].opcode)
        assertEquals(1, (instructions[instructions.size - 2] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.RETURN, instructions.last().opcode)
        // The branch to the return lands on the call, so both answers pass through it.
        val branch = instructions.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        val target = (instructions[branch] as com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction).codeOffset
        val address = instructions.take(branch).sumOf { it.codeUnits } + target
        val landed = instructions.indices.first { index -> instructions.take(index).sumOf { it.codeUnits } == address }
        assertEquals("the branch skips the extension call", instructions.size - 3, landed)
    }

    @Test
    fun `the patch answers the button's check`() {
        val root = java.io.File("src/main/kotlin").takeIf { it.isDirectory } ?: java.io.File("patches/src/main/kotlin")
        val source = java.io.File(root, "app/morphe/patches/tiktok/misc/navigation/FeedTabNavigationPatch.kt").readText()
        assertTrue("the LIVE button's bottom tab check is not answered", source.contains("LiveBottomTabCheckFingerprint.method.answerLiveBottomTab()"))
    }

    @Test
    fun `the button's bottom tab check is one method on every fixture, and only the button calls it`() {
        for (apk in Fixtures.apks()) {
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val button = classes[LIVE_ICON_GENERATOR] ?: error("${apk.name}: no LiveIconGenerator")
            val taken = button.methods.filter { LiveBottomTabCheckFingerprint.takes(it, button) }
            assertEquals("${apk.name}: ${taken.map { it.name }}", 1, taken.size)
            val check = taken.single()
            val callers = classes.values.asSequence().flatMap { classDef ->
                classDef.methods.asSequence().filter { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.getReference<MethodReference>()?.let {
                            it.definingClass == LIVE_ICON_GENERATOR && it.name == check.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
                        } == true
                    } == true
                }.map { classDef.type }
            }.toSet()
            assertEquals("${apk.name}: the check is asked outside the button: $callers", setOf(LIVE_ICON_GENERATOR), callers)
        }
    }
}
