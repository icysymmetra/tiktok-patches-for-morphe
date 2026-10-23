package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed key's register in the final feed insertion payload constructor.
 *
 * <p>R8 reordered that constructor's parameters between 46.2.3 and 46.7.3 without TikTok changing
 * anything: the static factory beside it still takes them the old way round. The register was
 * written into the patch as `p2`, which is the List on the newer builds.
 */
class InsertionPayloadKeyTest {
    private val payload = "LX/0SN6;"

    @Test
    fun `the key is found wherever the constructor puts it`() {
        assertEquals("p2", constructor(listOf("I", "Ljava/lang/String;", "Ljava/util/List;")).insertionPayloadKeyRegister())
        assertEquals("p3", constructor(listOf("I", "Ljava/util/List;", "Ljava/lang/String;")).insertionPayloadKeyRegister())
        assertEquals("p1", constructor(listOf("Ljava/lang/String;", "I", "Ljava/util/List;")).insertionPayloadKeyRegister())
    }

    @Test
    fun `a wide parameter ahead of the key takes two registers`() {
        // this is p0, the long is p1 and p2, the double is p3 and p4, so the key is p5.
        assertEquals("p3", constructor(listOf("J", "Ljava/lang/String;")).insertionPayloadKeyRegister())
        assertEquals("p5", constructor(listOf("J", "D", "Ljava/lang/String;")).insertionPayloadKeyRegister())
    }

    @Test
    fun `a static method has no this to count`() {
        val static = ImmutableMethod(
            payload,
            "LIZ",
            listOf("I", "Ljava/lang/String;").map { ImmutableMethodParameter(it, null, null) },
            payload,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            null,
        )
        assertEquals("p1", MutableMethod(static).insertionPayloadKeyRegister())
    }

    @Test
    fun `a constructor with no key says so`() {
        val error = assertThrows(PatchException::class.java) {
            constructor(listOf("I", "Ljava/util/List;")).insertionPayloadKeyRegister()
        }
        assertEquals(true, error.message!!.contains("takes no feed key"))
    }

    @Test
    fun `the constructor is matched whichever order this build put its parameters in`() {
        assertTrue(constructor(listOf("I", "Ljava/lang/String;", "Ljava/util/List;")).isInsertionPayloadConstructor())
        assertTrue(constructor(listOf("I", "Ljava/util/List;", "Ljava/lang/String;")).isInsertionPayloadConstructor())
        assertTrue(constructor(listOf("Ljava/util/List;", "Ljava/lang/String;", "I")).isInsertionPayloadConstructor())
    }

    @Test
    fun `the static factory beside it takes the same three and is not the constructor`() {
        // LX/0SN6;->LIZ(I, String, List) builds the payload and is what the app calls. Only the
        // name and the return type tell it apart from the constructor it forwards to.
        val factory = ImmutableMethod(
            payload,
            "LIZ",
            listOf("I", "Ljava/lang/String;", "Ljava/util/List;").map { ImmutableMethodParameter(it, null, null) },
            payload,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            null,
        )
        assertFalse(MutableMethod(factory).isInsertionPayloadConstructor())
    }

    @Test
    fun `a void method of the same three parameters is not the constructor either`() {
        // The factory is separated by its return type. A plain void helper of the same shape is
        // not, and the class is free to grow one, so the name is what rules it out.
        val helper = ImmutableMethod(
            payload,
            "LIZ",
            listOf("I", "Ljava/lang/String;", "Ljava/util/List;").map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            null,
        )
        assertFalse(MutableMethod(helper).isInsertionPayloadConstructor())
    }

    @Test
    fun `a constructor of some other shape is not it`() {
        assertFalse(constructor(listOf("I", "Ljava/lang/String;")).isInsertionPayloadConstructor())
        assertFalse(constructor(listOf("I", "Ljava/lang/String;", "Ljava/util/List;", "Z")).isInsertionPayloadConstructor())
        assertFalse(constructor(listOf("I", "I", "Ljava/util/List;")).isInsertionPayloadConstructor())
    }

    private fun constructor(parameters: List<String>) = MutableMethod(
        ImmutableMethod(
            payload,
            "<init>",
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
            null,
            null,
            null,
        ),
    )
}
