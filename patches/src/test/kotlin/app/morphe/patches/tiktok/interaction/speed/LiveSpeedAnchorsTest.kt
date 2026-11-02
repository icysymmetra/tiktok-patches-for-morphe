package app.morphe.patches.tiktok.interaction.speed

import app.morphe.Fixtures
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerProgressAidFingerprint
import app.morphe.patches.tiktok.interaction.cleardisplay.OnRenderFirstFrameBodyFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the live speed change calls and hooks, held to each declared TikTok build.
 *
 * <p>Swipe for brightness and volume, set to Speed, changes the speed of the video on screen
 * through two extension bridges and one hook, all written from members the Playback speed patch
 * already reads. This holds each of them where the bridges need it: the one PlayerController, its
 * public instance setSpeed and Aweme getter (the bridges call both virtually from the extension),
 * the progress report with the controller as p0 and the video id as p1, and the selection state
 * the bridges write the way the first-frame bridge does, which has to be public and static and
 * must not be final, since the extension writes it from another class.
 */
class LiveSpeedAnchorsTest {
    private val controllerSuffix = "/feed/controller/PlayerController;"
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

    @Test
    fun `each declared build has the player members the live speed bridges and hook use`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }

            val controllers = classes.filter { it.type.endsWith(controllerSuffix) }
            assertEquals("PlayerController classes: ${controllers.map { it.type }}", 1, controllers.size)
            val controller = controllers.single()
            assertTrue("PlayerController is public, so the extension may name it", AccessFlags.PUBLIC.isSet(controller.accessFlags))

            // setSpeed: called virtually on the controller with the speed in p1.
            val setSpeeds = controller.methods.filter { PlayerControllerSetSpeedFingerprint.takes(it, controller) }
            assertEquals("setSpeed: ${setSpeeds.map { it.name }}", 1, setSpeeds.size)
            val setSpeed = setSpeeds.single()
            assertEquals(listOf("F"), setSpeed.parameterTypes.map(CharSequence::toString))
            assertTrue("setSpeed is public", AccessFlags.PUBLIC.isSet(setSpeed.accessFlags))
            assertFalse("setSpeed is an instance method", AccessFlags.STATIC.isSet(setSpeed.accessFlags))
            assertFalse("setSpeed is not abstract", AccessFlags.ABSTRACT.isSet(setSpeed.accessFlags))

            // The video the controller has: the getter the first-frame body reads it with.
            val bodies = controller.methods.filter { OnRenderFirstFrameBodyFingerprint.takes(it, controller) }
            assertEquals("first-frame body: ${bodies.map { it.name }}", 1, bodies.size)
            val getterRef = bodies.single().firstFrameAwemeGetter()
            assertEquals(controller.type, getterRef.definingClass)
            val getters = controller.methods.filter {
                it.name == getterRef.name && it.parameterTypes.isEmpty() && it.returnType == aweme
            }
            assertEquals("Aweme getter ${getterRef.name}: ${getters.map { it.name }}", 1, getters.size)
            assertTrue("the Aweme getter is public", AccessFlags.PUBLIC.isSet(getters.single().accessFlags))
            assertFalse("the Aweme getter is an instance method", AccessFlags.STATIC.isSet(getters.single().accessFlags))

            // The progress report: p0 is the controller and p1 the video id it is playing.
            val progress = controller.methods.filter { PlayerProgressAidFingerprint.takes(it, controller) }
            assertEquals("progress report: ${progress.map { it.name }}", 1, progress.size)
            assertFalse("the progress report is an instance method", AccessFlags.STATIC.isSet(progress.single().accessFlags))
            assertEquals(
                listOf("Ljava/lang/String;", "J", "J"),
                progress.single().parameterTypes.map(CharSequence::toString),
            )
        }
    }

    @Test
    fun `each declared build keeps the speed selection state public static and writable`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }

            val selections = classes.flatMap { classDef ->
                classDef.methods.filter { method ->
                    method.returnType == "V" && method.parameterTypes.size == 4 &&
                        PlaybackSpeedSelectionBoundaryFingerprint.takes(method, classDef)
                }.map { classDef to it }
            }
            assertEquals("selection methods: ${selections.map { it.second.name }}", 1, selections.size)
            val (holder, selection) = selections.single()
            assertTrue("the selection class is public", AccessFlags.PUBLIC.isSet(holder.accessFlags))

            val writes = selection.speedSelectionWrites()
            val awemeFields = writes.filter { it.type == aweme }
            assertEquals("selected-video fields: $awemeFields", 1, awemeFields.size)
            val speedFields = writes.filter { it.type == "F" }.distinctBy { it.toString() }
            assertEquals("current-speed fields: $speedFields", 2, speedFields.size)

            for (reference in awemeFields + speedFields) {
                val field = holder.fields.singleOrNull { it.name == reference.name && it.type == reference.type }
                    ?: error("${holder.type} declares no $reference")
                assertTrue("$reference is public", AccessFlags.PUBLIC.isSet(field.accessFlags))
                assertTrue("$reference is static", AccessFlags.STATIC.isSet(field.accessFlags))
                assertFalse("$reference is not final", AccessFlags.FINAL.isSet(field.accessFlags))
            }
        }
    }
}
