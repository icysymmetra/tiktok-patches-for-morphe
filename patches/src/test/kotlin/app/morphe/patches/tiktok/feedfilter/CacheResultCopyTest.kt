/*
 * Copyright 2026 Metra contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The constructor proof must fail closed when a result stops being a pure value store. */
class CacheResultCopyTest {
    private val type = "LFixture/Result;"
    private val fields = listOf("Z", "LFixture/Payload;", "Ljava/lang/String;", "Ljava/util/List;", "LFixture/Status;")
        .mapIndexed { index, fieldType -> ImmutableField(type, "field$index", fieldType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null) }

    private fun owner(extra: String = "", superclass: String = "Ljava/lang/Object;"): ImmutableClassDef {
        val constructor = MutableMethod(ImmutableMethod(type, "<init>",
            fields.map { ImmutableMethodParameter(it.type, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
            ImmutableMethodImplementation(6, emptyList(), null, null))).apply {
            addInstructions(0, """
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                $extra
                iput-boolean p1, p0, ${fields[0]}
                iput-object p2, p0, ${fields[1]}
                iput-object p3, p0, ${fields[2]}
                iput-object p4, p0, ${fields[3]}
                iput-object p5, p0, ${fields[4]}
                return-void
            """)
        }
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, emptyList(), null,
            emptySet(), fields, listOf(constructor))
    }

    @Test fun `copies preserve every other constructor field without final stores`() {
        for (payload in listOf(null, fields[1])) {
            val fixture = owner()
            val helper = rejectedCacheCopy(fixture, fields[0], payload)
            val code = helper.implementation!!.instructions.toList()
            val replaced = payload ?: fields[0]
            val reads = code.filter { it.opcode == Opcode.IGET_OBJECT || it.opcode == Opcode.IGET_BOOLEAN }
                .map { it.getReference<FieldReference>() }.toSet()
            assertEquals(fields.filter { it != replaced }.toSet(), reads)
            assertTrue(code.none { it.opcode in setOf(Opcode.IPUT_OBJECT, Opcode.IPUT_BOOLEAN) })
            assertEquals(1, code.count { it.opcode == Opcode.INVOKE_DIRECT_RANGE })
        }
    }

    @Test fun `a constructor null invariant is not silently bypassed`() {
        assertThrows(PatchException::class.java) {
            rejectedCacheCopy(owner("""
                if-nez p2, :payload_present
                throw p2
                :payload_present
            """), fields[0], fields[1])
        }
    }

    @Test fun `constructor side effects invalidate copying proof`() {
        assertThrows(PatchException::class.java) {
            rejectedCacheCopy(owner("invoke-static {}, LFixture/Observer;->changed()V"), fields[0])
        }
    }

    @Test fun `an unreviewed superclass invalidates copying proof`() {
        assertThrows(PatchException::class.java) {
            rejectedCacheCopy(owner(superclass = "LFixture/Parent;"), fields[0])
        }
    }
}
