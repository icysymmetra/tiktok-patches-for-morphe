/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [classesCalling] answers from the patcher's type index instead of reading every method, and that
 * index isn't documented (#54 review). The privacy patches, Ghost mode and Allow screenshots only
 * read the classes it names, so a caller it misses is a call they leave alone while still reporting
 * Applied. Held here to a full walk on each declared build, through a real patcher run.
 */
class ClassesCallingTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the type index names every class a full walk finds`() {
        Fixtures.forEachDeclared { apk ->
            val problems = mutableListOf<String>()
            var checked = 0
            val probe = bytecodePatch(name = "classesCalling probe") {
                execute {
                    val callers = HashMap<String, MutableSet<String>>()
                    val interfaces = HashSet<String>()
                    classDefForEach { classDef ->
                        if (classDef.accessFlags and AccessFlags.INTERFACE.value != 0) interfaces += classDef.type
                        classDef.methods.forEach { method ->
                            method.implementation?.instructions?.forEach { instruction ->
                                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                                    ?: return@forEach
                                callers.getOrPut(reference.definingClass) { HashSet() } += classDef.type
                            }
                        }
                    }
                    // The framework owners the patches name, then a fixed draw of TikTok's own
                    // classes and interfaces, since Ghost mode's owners are obfuscated ones.
                    val random = Random(54)
                    val owners = (
                        FRAMEWORK_OWNERS +
                            callers.keys.filter { it.startsWith("LX/") && it !in interfaces }.sorted().shuffled(random).take(40) +
                            callers.keys.filter { it in interfaces }.sorted().shuffled(random).take(20)
                        ).distinct()
                    for (owner in owners) {
                        val wanted = callers[owner].orEmpty()
                        val missed = wanted - classesCalling(listOf(owner))
                        if (missed.isNotEmpty()) {
                            problems += "$owner: missed ${missed.size} of ${wanted.size} callers, such as ${missed.take(3)}"
                        }
                    }
                    // The patches ask for several owners at once.
                    val together = owners.flatMapTo(HashSet()) { callers[it].orEmpty() } - classesCalling(owners)
                    if (together.isNotEmpty()) problems += "all ${owners.size} owners at once: missed ${together.size} callers"
                    checked = owners.size
                }
            }
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            assertTrue("the probe never ran", checked > 60)
            assertTrue(problems.joinToString("\n"), problems.isEmpty())
        }
    }

    private companion object {
        /** Window flags, clipboard, WebView, camera and mic, contacts, telephony and location owners. */
        val FRAMEWORK_OWNERS = listOf(
            "Landroid/view/Window;",
            "Landroid/view/SurfaceView;",
            "Landroid/content/ClipboardManager;",
            "Landroid/webkit/WebView;",
            "Landroid/hardware/Camera;",
            "Landroid/media/AudioRecord;",
            "Landroid/content/ContentResolver;",
            "Landroid/telephony/TelephonyManager;",
            "Landroid/location/LocationManager;",
            "Landroid/content/SharedPreferences\$Editor;",
            "Landroid/app/NotificationManager;",
        )
    }
}
