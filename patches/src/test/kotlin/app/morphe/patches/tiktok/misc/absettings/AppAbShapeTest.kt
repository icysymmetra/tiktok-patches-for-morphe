/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.absettings

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shape that names one getter and a shape that names a door are different questions.
 *
 * <p>The typed AB getters each have to be the only one of their kind, because the patches read a
 * key out of a register the shape does not mention: two of them would be a coin toss. The raw
 * getter is not like that. Its key is p1 and its flag is p2 whichever one the app calls, so a
 * build carrying two carries two ways in and both are worth holding open. 46.9.3 is the first
 * that does, with `LJIIJJI` and `LJIILIIL` differing only in the boolean they hand on.
 */
class AppAbShapeTest {
    @Test
    fun `the raw shape hands back every method carrying it`() {
        val holder = classWith(
            method("LJIIJJI", "Ljava/lang/Object;", listOf("Ljava/lang/String;", "Z")),
            method("LJIILIIL", "Ljava/lang/Object;", listOf("Ljava/lang/String;", "Z")),
            method("somethingElse", "Z", listOf("I")),
        )

        val matches = holder.methodsOfShape(APP_AB_RAW, "Test")

        assertEquals(listOf("LJIIJJI", "LJIILIIL"), matches.map { it.name })
    }

    @Test
    fun `one is still one`() {
        val holder = classWith(method("LJIIJJI", "Ljava/lang/Object;", listOf("Ljava/lang/String;", "Z")))

        assertEquals(listOf("LJIIJJI"), holder.methodsOfShape(APP_AB_RAW, "Test").map { it.name })
    }

    /**
     * Without this the two cases above would pass just as happily against a version that hands
     * back whatever it was given, empty included, and the Lab would then patch nothing and say
     * nothing about it.
     */
    @Test
    fun `a shape that names nothing is a refusal`() {
        val holder = classWith(method("somethingElse", "Z", listOf("I")))

        val refused = assertThrows(PatchException::class.java) {
            holder.methodsOfShape(APP_AB_RAW, "Feature Gate Lab raw App AB boundary")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("found none"))
        assertTrue(refused.message, refused.message.orEmpty().contains("Feature Gate Lab"))
    }

    /** The typed getters keep the stricter rule, which two of would break. */
    @Test
    fun `a typed getter with a twin is still refused`() {
        val holder = classWith(
            method("LJII", "I", listOf("I", "I", "Ljava/lang/String;", "Z")),
            method("LJIII", "I", listOf("I", "I", "Ljava/lang/String;", "Z")),
        )

        val refused = assertThrows(PatchException::class.java) {
            holder.methodOfShape(APP_AB_INT, "App AB int boundary")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("found 2"))
    }

    private fun method(name: String, returnType: String, parameters: List<String>) = ImmutableMethod(
        "LX/02z2;",
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null,
        null,
        ImmutableMethodImplementation(8, emptyList(), null, null),
    )

    private fun classWith(vararg methods: ImmutableMethod) = MutableClass(
        ImmutableClassDef(
            "LX/02z2;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            null,
            null,
            null,
            null,
            methods.toList(),
        ),
    )
}
