package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Show TikTok's feed buttons answers, held to every fixture.
 *
 * <p>TikTok's accessibility row (previous, pause and play, next) sits in every feed page and shows
 * only when one check in its component says so: the row's own switch, stored as
 * "settings_switch_on", with an accessibility service running touch exploration. The patch
 * answers yes at the start of that check while the switch is on. Each button of the row is then
 * handed to one method as the row updates, and the patch hands it to the extension there first,
 * so a finger's first tap presses it instead of only taking focus.
 */
class FeedButtonsAnchorsTest {
    @Test
    fun `the row's check is one method on every fixture and reads the row's switch and the screen reader`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> FeedButtonsGateFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val gate = taken.single().second
            val calls = gate.implementation!!.instructions.mapNotNull { it.call()?.let { call -> call.definingClass to call.name } }.toSet()
            assertTrue("${apk.name}: the check no longer asks whether accessibility is on: $calls",
                "Landroid/view/accessibility/AccessibilityManager;" to "isEnabled" in calls)
            assertTrue("${apk.name}: the check no longer asks about touch exploration: $calls",
                "Landroid/view/accessibility/AccessibilityManager;" to "isTouchExplorationEnabled" in calls)
            assertTrue("${apk.name}: the check has no local register for the answer",
                gate.implementation!!.registerCount - 1 >= 1)

            val component = build.byType.getValue(A11Y_FEED_TOOL)
            val askers = component.methods.filter { method -> method.implementation?.instructions?.any { it.calls(gate) } == true }
            assertTrue("${apk.name}: nothing in the component asks the check", askers.isNotEmpty())
            val resume = component.methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" }
            assertTrue("${apk.name}: the component no longer asks the check as the feed resumes, so the switch would wait for a restart",
                resume.implementation!!.instructions.any { it.calls(gate) })
        }
    }

    @Test
    fun `each of the row's three buttons is handed to one method as the row updates, on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> FeedButtonStateFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val state = taken.single().second
            assertFalse("${apk.name}: the method is static, so p1 would not be the button",
                AccessFlags.STATIC.isSet(state.accessFlags))

            val component = build.byType.getValue(A11Y_FEED_TOOL)
            val buttonFields = component.fields.filter {
                it.type == TUX_ICON && !AccessFlags.STATIC.isSet(it.accessFlags)
            }.map { it.name }.toSet()
            assertEquals("${apk.name}: the row's buttons are $buttonFields", 3, buttonFields.size)

            val handed = HashSet<String>()
            for (method in component.methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                instructions.forEachIndexed { index, instruction ->
                    if (!instruction.calls(state)) return@forEachIndexed
                    val button = (instruction as FiveRegisterInstruction).registerD
                    val source = instructions.subList(0, index).lastOrNull {
                        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == button
                    }
                    val field = (source as? ReferenceInstruction)?.reference as? FieldReference
                    if (source?.opcode == Opcode.IGET_OBJECT && field?.type == TUX_ICON) handed += field.name
                }
            }
            assertEquals("${apk.name}: a button the row never hands over keeps its focus tap", buttonFields, handed)
        }
    }

    @Test
    fun `the patch answers the check first and hands each button over before TikTok sets it`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/navigation/FeedTabNavigationPatch.kt").readText()
        assertTrue("the check is not hooked", source.contains("FeedButtonsGateFingerprint.method.apply"))
        assertTrue("the check does not answer yes on the switch", source.contains(
            "invoke-static {}, \$FEED_BUTTONS_CLASS_DESCRIPTOR->showWithoutScreenReader()Z\n" +
                "                    move-result v0\n" +
                "                    if-eqz v0, :gate\n" +
                "                    const/4 v0, 0x1\n" +
                "                    return v0"))
        assertTrue("the check's own code no longer follows a no", source.contains("ExternalLabel(\"gate\", getInstruction(0))"))
        assertTrue("the buttons are not handed over at the start of the state method", source.contains(
            "FeedButtonStateFingerprint.method.addInstruction(\n            0,\n" +
                "            \"invoke-static {p1}, \$FEED_BUTTONS_CLASS_DESCRIPTOR->buttonShown(Landroid/view/View;)V\","))
    }

    private fun Instruction.call(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.calls(target: Method): Boolean = call()?.let {
        it.definingClass == target.definingClass && it.name == target.name &&
            it.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString) &&
            it.returnType == target.returnType
    } == true

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
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
    }

    private companion object {
        const val TUX_ICON = "Lcom/bytedance/tux/icon/TuxIconView;"
    }
}
