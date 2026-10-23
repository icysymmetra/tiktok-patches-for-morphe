package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.declaredAccessFlags
import app.morphe.declaredDefiningClass
import app.morphe.declaredName
import app.morphe.declaredReturnType
import app.morphe.patches.tiktok.shared.OnRenderFirstFrameFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render event class is renamed by every TikTok build, and so is the name R8 gives the body it
 * outlines out of `onRenderFirstFrame`. Only the handler's own name survives, so both anchors are
 * written to read the event type off it. These hold that reading in place.
 */
class RenderFirstFrameAnchorTest {
    private val controller = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
    private val event = "LX/0pb0;"
    private val other = "LX/0000;"

    @Test
    fun `the outlined body is the static taking the controller and the event its handler declares`() {
        val body = static("LLILZIL", listOf(controller, event))
        val classDef = playerController(handler(listOf(event)), body)

        assertTrue(matchesBody(body, classDef))
    }

    @Test
    fun `a static taking some other class beside the controller is not the body`() {
        val decoy = static("LJL", listOf(controller, other))
        val classDef = playerController(handler(listOf(event)), decoy)

        assertFalse(matchesBody(decoy, classDef))
    }

    @Test
    fun `the controller has to be the first parameter`() {
        val reversed = static("LJIL", listOf(event, controller))
        val classDef = playerController(handler(listOf(event)), reversed)

        assertFalse(matchesBody(reversed, classDef))
    }

    @Test
    fun `nothing matches on a build that no longer declares the handler`() {
        val body = static("LLILZIL", listOf(controller, event))
        val classDef = playerController(handler(listOf(event), name = "onRenderSecondFrame"), body)

        assertFalse(matchesBody(body, classDef))
    }

    @Test
    fun `a handler that stops taking exactly one event is not read for a type`() {
        val body = static("LLILZIL", listOf(controller, event))
        val twoParameters = playerController(handler(listOf(event, "I")), body)
        val noParameters = playerController(handler(emptyList()), body)

        assertFalse(matchesBody(body, twoParameters))
        assertFalse(matchesBody(body, noParameters))
        assertFalse(matchesHandler(handler(listOf(event, "I"))))
        assertFalse(matchesHandler(handler(emptyList())))
        assertTrue(matchesHandler(handler(listOf(event))))
    }

    @Test
    fun `the anchors keep the parts the custom clause does not cover`() {
        // The custom clause is only half of each anchor. PlayerController carries four public
        // static (PlayerController, something)V methods on 46.8.3 and a second, synthetic
        // onRenderFirstFrame taking a String as well, so the flags and the defining class are
        // doing work and deleting either line leaves the rest of this suite green.
        assertEquals("/feed/controller/PlayerController;", OnRenderFirstFrameFingerprint.declaredDefiningClass)
        assertEquals("onRenderFirstFrame", OnRenderFirstFrameFingerprint.declaredName)
        assertEquals("V", OnRenderFirstFrameFingerprint.declaredReturnType)

        assertEquals("/feed/controller/PlayerController;", OnRenderFirstFrameBodyFingerprint.declaredDefiningClass)
        assertEquals("V", OnRenderFirstFrameBodyFingerprint.declaredReturnType)
        assertEquals(
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            OnRenderFirstFrameBodyFingerprint.declaredAccessFlags,
        )
    }

    private fun matchesBody(method: Method, classDef: ClassDef) =
        OnRenderFirstFrameBodyFingerprint.custom!!(method, classDef)

    private fun matchesHandler(method: Method) =
        OnRenderFirstFrameFingerprint.custom!!(method, playerController(method))

    private fun handler(parameters: List<String>, name: String = "onRenderFirstFrame") =
        method(name, parameters, AccessFlags.PUBLIC.value)

    private fun static(name: String, parameters: List<String>) =
        method(name, parameters, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)

    private fun method(name: String, parameters: List<String>, accessFlags: Int) =
        ImmutableMethod(
            controller,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "V",
            accessFlags,
            null,
            null,
            null,
        )

    private fun playerController(vararg methods: Method) = ImmutableClassDef(
        controller,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        methods.toList(),
    )
}
