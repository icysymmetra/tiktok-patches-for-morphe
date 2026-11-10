package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGuessSearchGateTest {
    /**
     * The gate that moves "You may like" off the Lynx search page (47.1.4) is one method on every
     * declared build, with a local for the guard's own answer.
     */
    @Test
    fun `the native guess search gate stays one method on every declared build`() = Fixtures.forEachDeclared { apk ->
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val gates = container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .flatMap { it.methods.asSequence() }
            .filter(::isNativeGuessSearchGate).toList()
        assertEquals("native guess gates ${gates.map { it.definingClass }}", 1, gates.size)
        assertTrue("${gates.single().definingClass} has no local for the guard",
            gates.single().implementation!!.registerCount >= 1)
    }
}
