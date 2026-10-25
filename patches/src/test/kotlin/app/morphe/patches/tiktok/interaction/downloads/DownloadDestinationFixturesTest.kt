/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.*
import org.junit.Test

/** A nested native lookup after consuming the staging name would lose the creator folder. */
class DownloadDestinationFixturesTest {
    @Test fun `native destination methods do not hand a renamed file to another destination hook`() {
        Fixtures.forEachDeclared { apk ->
            val fingerprints = listOf(VideoDownloadUriFingerprint, PhotoDownloadUriFingerprint,
                VideoLookupUriFingerprint, PhotoLookupUriFingerprint, ImagePostMediaCopyFingerprint)
            val matches = fingerprints.associateWith { mutableListOf<Method>() }
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) for (type in container.getEntry(entry)!!.dexFile.classes) {
                for (method in type.methods) for (fingerprint in fingerprints) {
                    if (fingerprint.takes(method, type)) matches.getValue(fingerprint).add(method)
                }
            }
            matches.forEach { (fingerprint, methods) -> assertEquals("$fingerprint candidates: $methods", 1, methods.size) }
            val targets = matches.values.map { it.single().toString() }.toSet()
            for (method in matches.values.map { it.single() }) {
                val nested = method.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
                    .map { it.toString() }.filter { it in targets }
                assertTrue("$method delegates after consuming its staging name: $nested", nested.isEmpty())
            }
        }
    }
}
