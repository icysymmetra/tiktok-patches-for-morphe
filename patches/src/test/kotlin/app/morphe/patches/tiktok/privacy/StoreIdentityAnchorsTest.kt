package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Look like the store app" rests on, held to each declared TikTok build.
 *
 * TikTok reads its own signing certificate through one cached self-package wrapper, and the patch
 * answers from the head of it. The wrapper carries an obfuscated name that moved between builds, so
 * the fingerprint matches on shape. This pins that the shape still finds exactly one method and that
 * the method has a local register for the entry hook's result. It also pins that every Android 11
 * install source read TikTok makes is one the patch stands in front of, so a build that starts
 * reading something new off it fails here instead of reporting Morphe Manager.
 */
class StoreIdentityAnchorsTest {
    @Test
    fun `the self-package wrapper resolves to one method with a local on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val matches = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    classDef.methods.forEach { method ->
                        if (SelfPackageInfoCacheFingerprint.takes(method, classDef)) matches += method
                    }
                }
            }

            assertEquals("$version: self-package wrappers matched", 1, matches.size)
            val wrapper = matches.single()
            val body = wrapper.implementation ?: error("$version: the wrapper has no body")
            val locals = body.registerCount - wrapper.parameterTypes.sumOf {
                if (it == "J" || it == "D") 2 else 1
            }
            assertTrue("$version: the wrapper has no local for the hook's result (locals=$locals)", locals >= 1)
        }
    }

    @Test
    fun `every install source read is one the patch answers on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val reads = sortedSetOf<String>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    classDef.methods.forEach { method ->
                        method.implementation?.instructions?.forEach { instruction ->
                            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                ?: return@forEach
                            val read = ref.definingClass == INSTALL_SOURCE_INFO ||
                                (ref.definingClass == PACKAGE_MANAGER && ref.name == "getInstallSourceInfo")
                            if (read) reads += ref.toString()
                        }
                    }
                }
            }

            assertTrue("$version: no install source read found", reads.any { "->getInstallSourceInfo(" in it })
            val unanswered = reads - INSTALL_SOURCE_READS.keys
            assertTrue("$version: install source reads the patch doesn't answer: $unanswered", unanswered.isEmpty())
        }
    }

    private companion object {
        const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
        const val INSTALL_SOURCE_INFO = "Landroid/content/pm/InstallSourceInfo;"
    }
}
