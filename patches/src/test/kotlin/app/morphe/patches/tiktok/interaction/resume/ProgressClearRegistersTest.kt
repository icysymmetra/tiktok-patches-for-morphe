/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The clear takes its registers from the host's own put, and only when the host is done with them.
 *
 * <p>The flag register was the value argument of `LruCache.put`, taken on the strength of a
 * comment saying the value "is spent the moment it returns". That is true of every build this
 * has been run against and is not a property of the shape: the value register is an ordinary
 * host register, and a build that read it again after the put would have had the flag written
 * over the top of it, showing up as wrong playback rather than as a failed patch. The same
 * question is put to `RegisterLiveness` here that `GhostModeCallSites` puts to it before its own
 * mid-method write.
 */
class ProgressClearRegistersTest {
    private val holder = "LX/0Lze;"
    private val record = "LX/0LxV;"
    private val lazy = "LX/01xP;"

    @Test
    fun `the three registers are the ones the put names`() {
        val progress = progress()
        val registers = progressClearRegistersAt(progress, putIndexOf(progress))
        assertEquals("cache", 14, registers.cache)
        assertEquals("key", 6, registers.key)
        assertEquals("flag", 5, registers.flag)
    }

    @Test
    fun `a value register the host reads again after the put is refused`() {
        // The one case the old comment asserted could not happen. v5 is the put's value.
        val progress = progress(afterPut = "sput-object v5, $holder->LJIIIIZZ:Ljava/lang/Object;")
        assertRefused("v5, the value handed to the progress cache put, is read again after it") {
            progressClearRegistersAt(progress, putIndexOf(progress))
        }
    }

    /**
     * Without this the case above would pass just as happily against a check that refused any
     * instruction after the put at all, which is the shape every build actually has.
     */
    @Test
    fun `a register the clear does not use being read after the put is fine`() {
        val progress = progress(afterPut = "sput-object v3, $holder->LJIIIIZZ:Ljava/lang/Object;")
        assertEquals(5, progressClearRegistersAt(progress, putIndexOf(progress)).flag)
    }

    @Test
    fun `a value register read only on a branch after the put is refused`() {
        // Liveness is asked of the whole method rather than of the lines that follow the put,
        // so a read the linear tail never reaches still counts.
        val progress = progress(
            afterPut = """
                if-eqz v3, :skip
                goto :done
                :skip
                sput-object v5, $holder->LJIIIIZZ:Ljava/lang/Object;
                :done
                nop
            """,
        )
        assertRefused("is read again after it") {
            progressClearRegistersAt(progress, putIndexOf(progress))
        }
    }

    @Test
    fun `a static progress callback is refused`() {
        // p2 to p5 are the two timestamps only when p0 is the receiver.
        val progress = progress(static = true)
        assertRefused("the progress callback is static") {
            progressClearRegistersAt(progress, putIndexOf(progress))
        }
    }

    @Test
    fun `a put whose answer the host keeps is refused`() {
        val progress = progress(afterPut = "move-result-object v5")
        assertRefused("keeps its answer") {
            progressClearRegistersAt(progress, putIndexOf(progress))
        }
    }

    private fun putIndexOf(progress: MutableMethod): Int =
        progress.implementation!!.instructions.toList()
            .indexOfLast { it.getReference<MethodReference>()?.name == "put" }

    /** The tail of onPlayProgressChange, from the record being made to its put. */
    private fun progress(afterPut: String = "", static: Boolean = false) = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
            "onPlayProgressChange",
            listOf("Ljava/lang/String;", "J", "J").map { ImmutableMethodParameter(it, null, null) },
            "V",
            if (static) AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            else AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(37, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(
            0,
            """
                new-instance v14, $record
                const-wide/16 v0, 0x0
                const-wide/16 v5, 0x0
                invoke-direct {v14, v0, v1, v5, v6}, $record-><init>(JJ)V
                sput-object v14, $holder->LJ:$record
                sget-object v5, $holder->LIZLLL:$lazy
                invoke-interface {v5}, $lazy->getValue()Ljava/lang/Object;
                move-result-object v14
                check-cast v14, Landroid/util/LruCache;
                invoke-virtual {v3}, Lcom/ss/android/ugc/aweme/feed/model/Aweme;->getAid()Ljava/lang/String;
                move-result-object v6
                sget-object v5, $holder->LJ:$record
                invoke-virtual {v14, v6, v5}, Landroid/util/LruCache;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                $afterPut
                return-void
            """,
        )
    }

    private fun assertRefused(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal naming \"$expected\"")
        } catch (refused: Exception) {
            assertTrue(
                "refused with ${refused::class.simpleName}: ${refused.message}",
                refused is PatchException || refused is IllegalStateException,
            )
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains(expected))
        }
    }
}
