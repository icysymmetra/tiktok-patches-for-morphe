package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.util.RegisterLiveness
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the time beside the progress bar and the bigger handle rest on, held to each TikTok build
 * the bundle declares (#90), through the same lookups the patch makes.
 *
 * <p>The bar is a LinearLayout, the class of the show-type setter. Its setProgress(F) hands the
 * percent to one (FZ)V method, which passes it on to the seek bar as an int, and that is where the
 * time hears every percent the bar draws. The setter calls one (I)V style method, with 100 and up
 * for the drag styles, and the style method hands a seek bar (a ProgressBar, which is what the
 * time looks for in the frame) its line height once, the chapter marks the same height once, and
 * the handle its width, height and corner once, each from a register nothing reads afterwards
 * and that no branch jumps straight to. PlayerController's progress callback builds one progress
 * event, whose percent it works out from the position times 100, and the event's constructor ends
 * in one return with its percent, position and video still in the registers they came in.
 */
class SeekbarExtrasAnchorsTest {
    @Test
    fun `the bar's progress and style methods resolve from the show-type setter`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> SetSeekBarShowTypeFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: show-type setters ${taken.map { it.first.type }}", 1, taken.size)
            val (bar, showType) = taken.single()
            assertTrue("${apk.name}: the bar is no longer a LinearLayout", build.descends(bar.type, LINEAR_LAYOUT))

            val progress = build.method(barProgressOf(bar.type, bar.methods))
            assertTrue(
                "${apk.name}: the bar's progress method no longer hands the seek bar an int progress",
                progress.calls().any { it.name == "setProgress" && it.parameterTypes.map(CharSequence::toString) == listOf("I") },
            )

            for (style in DRAG_STYLES) {
                assertTrue(
                    "${apk.name}: the show-type setter no longer asks for drag style $style",
                    showType.implementation!!.instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == style },
                )
            }

            val style = build.method(showType.barStyle())
            val arguments = style.styleArguments()
            assertEquals(
                listOf("lineHeight", "lineHeight", "handleSide", "handleSide", "handleCorner"),
                arguments.map { it.call },
            )
            val liveness = RegisterLiveness.of(style)
            for (argument in arguments) {
                assertFalse("${apk.name}: ${argument.call} at ${argument.index} is read after its setter",
                    argument.register in liveness.liveInto(argument.index + 1))
                assertFalse("${apk.name}: a branch goes straight to the ${argument.call} setter", style.jumpedTo(argument.index))
            }
            val handle = arguments.filter { it.call != "lineHeight" }
            assertEquals("${apk.name}: the handle's sizes share a register", 3, handle.map { it.register }.toSet().size)

            val instructions = style.implementation!!.instructions.toList()
            val seekBar = instructions[arguments.first().index].getReference<MethodReference>()!!.definingClass
            assertTrue("${apk.name}: $seekBar is not a ProgressBar", build.descends(seekBar, PROGRESS_BAR))
        }
    }

    @Test
    fun `PlayerController builds one progress event, whose constructor ends in one return`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> PlayProgressFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: progress callbacks ${taken.map { it.first.type }}", 1, taken.size)
            val callback = taken.single().second
            assertTrue(
                "${apk.name}: the callback no longer works the percent out against 100",
                callback.implementation!!.instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == HUNDRED_FLOAT_BITS },
            )

            val constructor = build.method(callback.progressEvent())
            val instructions = constructor.implementation!!.instructions.toList()
            val returns = instructions.indices.filter { instructions[it].opcode == Opcode.RETURN_VOID }
            assertEquals("${apk.name}: the event's constructor returns in ${returns.size} places", 1, returns.size)
            assertFalse("${apk.name}: a branch goes straight to the event's return", constructor.jumpedTo(returns.single()))
            assertTrue("${apk.name}: the event's constructor reuses its percent, position or video", constructor.keepsEventArguments())
        }
    }

    @Test
    fun `the patch hooks the bar, its style and the progress event`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readText()
        assertTrue("the bar's progress is not hooked", source.contains(".barProgressHook()"))
        assertTrue("the bar's style is not hooked", source.contains(".barStyleHook()"))
        assertTrue("the progress event is not recorded", source.contains(".progressTickHook()"))
    }

    /**
     * An extra used to be hooked one piece at a time, so a miss on a forced build left the first
     * pieces in and the switches showing with nothing behind them. Every hook is checked first now,
     * and each extra turns on its own rows only once it's in.
     */
    @Test
    fun `each extra is checked whole before any of it goes in`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readText()
        val extra = source.substringAfter("fun extra(").substringBefore("\n        }\n")
        val checked = extra.indexOf("hooks()")
        val applied = extra.indexOf("checked.forEach { it() }")
        assertTrue("the extra's hooks aren't checked first", checked in 0 until applied)
        assertTrue("an extra's rows show before it's in", extra.indexOf("SettingsStatus") > applied)
        assertTrue(source.contains("\"enableSeekbarExtras\""))
        assertTrue(source.contains("\"enableStorySeekbar\""))
        val extras = File(root, "app/morphe/patches/tiktok/interaction/seekbar/SeekbarExtras.kt").readText()
        val stories = File(root, "app/morphe/patches/tiktok/interaction/seekbar/StorySeekbar.kt").readText()
        for (hook in listOf("progressTickHook", "barProgressHook", "barStyleHook")) {
            assertTrue("$hook doesn't hand its hook back", extras.contains("fun MutableMethod.$hook(): () -> Unit"))
        }
        for (hook in listOf("storyCellViewHook", "storyCellBindHook", "storyProgressHook")) {
            assertTrue("$hook doesn't hand its hook back", stories.contains("fun MutableMethod.$hook(): () -> Unit"))
        }
    }

    private fun Method.calls(): List<MethodReference> =
        implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }.orEmpty()

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

        fun method(reference: MethodReference): Method {
            val classDef = byType[reference.definingClass] ?: error("${reference.definingClass} is not in this build")
            return classDef.methods.firstOrNull {
                it.name == reference.name && it.returnType == reference.returnType &&
                    it.parameterTypes.map(CharSequence::toString) == reference.parameterTypes.map(CharSequence::toString)
            } ?: error("${reference.definingClass} has no ${reference.name}")
        }

        /** Whether [type] is [ancestor] or extends it, following superclasses out of the APK into the framework. */
        fun descends(type: String, ancestor: String): Boolean {
            var current: String? = type
            while (current != null) {
                if (current == ancestor) return true
                current = byType[current]?.superclass ?: FRAMEWORK_PARENTS[current]
            }
            return false
        }
    }

    private companion object {
        const val LINEAR_LAYOUT = "Landroid/widget/LinearLayout;"
        const val PROGRESS_BAR = "Landroid/widget/ProgressBar;"
        val DRAG_STYLES = listOf(100, 101, 102)

        /** 100f as the bits a const/high16 loads. */
        val HUNDRED_FLOAT_BITS = java.lang.Float.floatToIntBits(100f)

        /** The framework classes a TikTok seek bar or layout can sit on, which the APK doesn't define. */
        val FRAMEWORK_PARENTS = mapOf(
            "Landroid/widget/SeekBar;" to "Landroid/widget/AbsSeekBar;",
            "Landroid/widget/AbsSeekBar;" to PROGRESS_BAR,
            "Landroidx/appcompat/widget/AppCompatSeekBar;" to "Landroid/widget/SeekBar;",
        )
    }
}
