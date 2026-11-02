/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feed lock reads the intent a link brings to the running app at the main activity's
 * onNewIntent, as its first instruction. Held to each declared build: one such method, with a
 * body, whose p1 is the Intent and fits the range invoke the hook uses.
 */
class FeedLockAnchorsTest {
    @Test
    fun `the main activity's onNewIntent takes the intent in p1 on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val found = ArrayList<com.android.tools.smali.dexlib2.iface.Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type != MAIN_ACTIVITY) continue
                    classDef.methods.filterTo(found) { MainNewIntentFingerprint.takes(it, classDef) }
                }
            }
            assertEquals("$version: onNewIntent(Intent) takes ${found.size}", 1, found.size)
            val method = found.single()
            val implementation = method.implementation
            assertNotNull("$version: no body", implementation)
            assertEquals("$version: parameters", listOf("Landroid/content/Intent;"), method.parameterTypes.map { it.toString() })
            // p0 is the activity and p1 the intent, so the range invoke reads the last two registers.
            assertTrue("$version: too few registers for p1", implementation!!.registerCount >= 2)
        }
    }
}
