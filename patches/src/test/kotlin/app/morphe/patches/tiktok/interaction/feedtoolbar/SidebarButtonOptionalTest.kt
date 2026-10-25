/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The side menu half of Hide feed LIVE button (#128) is optional off the declared builds and
 * required on them. Every fixture is a declared build that has the button, so the probe takes the
 * button's one call out of it, the way a build without it would look, and passes a version
 * that isn't declared. Patch tests otherwise only ever saw the hook go in.
 */
class SidebarButtonOptionalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a build without the side menu button keeps the patch off the declared builds and fails it on one`() {
        Fixtures.forEachDeclared { apk ->
            val declared = Fixtures.versionOf(apk)
            var checked = false
            val probe = bytecodePatch(name = "side menu button probe") {
                execute {
                    assertEquals("${apk.name}: the fixture's side menu button", 1,
                        SidebarIconViewFingerprint.matchAllOrNull().orEmpty().size)
                    val generator = SidebarIconViewFingerprint.method
                    val call = generator.implementation!!.instructions.indexOfFirst {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name ==
                            "getInflatedSidebarIcon"
                    }
                    assertTrue("${apk.name}: no getInflatedSidebarIcon call in $generator", call >= 0)
                    generator.replaceInstruction(call, "nop")
                    SidebarIconViewFingerprint.clearMatch()
                    assertEquals("${apk.name}: the button still matched after its call was taken out", 0,
                        SidebarIconViewFingerprint.matchAllOrNull().orEmpty().size)

                    // This run has no extension, so the flag's anchor matches nothing: had the
                    // patch gone on to add the enableHideFeedSidebarButton call, it would throw.
                    assertNull(SettingsStatusLoadFingerprint.matchOrNull())
                    assertFalse("${apk.name}: the hook reported itself in on a build without the button",
                        hookSidebarButtonOn("0.0.1"))

                    val thrown = assertThrows(PatchException::class.java) { hookSidebarButtonOn(declared) }
                    assertTrue("${apk.name}: ${thrown.message}",
                        thrown.message.orEmpty().contains("side menu button"))
                    checked = true
                }
            }
            // A fingerprint keeps its last match across runs in one JVM (BackgroundPushSetupTest).
            SidebarIconViewFingerprint.clearMatch()
            SettingsStatusLoadFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("${apk.name}: the probe never ran", checked)
        }
    }
}
