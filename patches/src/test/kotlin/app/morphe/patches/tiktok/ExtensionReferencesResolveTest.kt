package app.morphe.patches.tiktok

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every field and method of TikTok's own classes that the extension names exists on every build
 * on the desk.
 *
 * <p>The extension compiles against stubs, so a field TikTok renames still compiles, still patches
 * and still passes the receipt, then throws NoSuchFieldError the first time it runs. Issue #12:
 * 46.9.3 moved FollowFeedList to Kotlin, mItems became items, and Feed filter closed TikTok on the
 * Following tab. The same scan found Video's play addresses renamed to ...Value, which had quietly
 * disabled a download fallback. A reference into a class the APK does not define (the framework,
 * libraries) is not TikTok's to break and is skipped. Without fixtures or a built extension this
 * is skipped rather than passed.
 */
class ExtensionReferencesResolveTest {
    @Test
    fun `every TikTok member the extension names exists on every fixture`() {
        val extension = extensionDex()
        assumeTrue("no built extension dex on the test classpath", extension != null)
        val apks = fixtures()

        val own = classes(extension!!)
        val references = sortedSetOf<String>()
        val users = mutableMapOf<String, MutableSet<String>>()
        for (owner in own.values) for (method in owner.methods) {
            val body = method.implementation ?: continue
            for (instruction in body.instructions) {
                val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
                val key = when (reference) {
                    is FieldReference -> if (reference.definingClass in own) null else
                        "F ${reference.definingClass}->${reference.name}:${reference.type}"
                    is MethodReference -> if (reference.definingClass in own) null else
                        "M ${reference.definingClass}->${reference.name}${reference.parameterTypes.joinToString("", "(", ")")}${reference.returnType}"
                    else -> null
                } ?: continue
                references += key
                users.getOrPut(key) { sortedSetOf() } += "${owner.type.substringAfterLast('/')}.${method.name}"
            }
        }

        val problems = mutableListOf<String>()
        var checked = 0
        for (apk in apks) {
            val app = classes(apk)
            for (key in references) {
                val owner = key.substring(2, key.indexOf("->"))
                if (owner !in app) continue
                checked++
                val member = key.substring(key.indexOf("->") + 2)
                val found = if (key.startsWith("F ")) hasField(app, owner, member) else hasMethod(app, owner, member)
                if (!found) problems += "${apk.name}: ${key.substring(2)} (used by ${users[key]})"
            }
        }
        assertTrue("no reference into TikTok's classes was checked, so the scan is broken", checked > 0)
        assertEquals("extension references a build does not have", emptyList<String>(), problems)
    }

    private fun hasField(app: Map<String, ClassDef>, owner: String, member: String): Boolean {
        val name = member.substringBefore(':')
        val type = member.substringAfter(':')
        var current: String? = owner
        while (current != null && current in app) {
            if (app.getValue(current).fields.any { it.name == name && it.type == type }) return true
            current = app.getValue(current).superclass
        }
        return false
    }

    private fun hasMethod(app: Map<String, ClassDef>, owner: String, member: String): Boolean {
        val pending = ArrayDeque(listOf(owner))
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            // A supertype outside the APK (android.*, java.*) may declare it: not TikTok's to lose.
            val definition = app[current] ?: return true
            val declared = definition.methods.any {
                "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == member
            }
            if (declared) return true
            definition.superclass?.let(pending::add)
            pending.addAll(definition.interfaces)
        }
        return false
    }

    private fun classes(file: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(file, Opcodes.getDefault())
        val result = mutableMapOf<String, ClassDef>()
        for (entry in container.dexEntryNames) {
            for (definition in container.getEntry(entry)!!.dexFile.classes) result.putIfAbsent(definition.type, definition)
        }
        return result
    }

    /** The dex the build copies into this module's resources, as the bundle carries it. */
    private fun extensionDex(): File? {
        val stream = javaClass.getResourceAsStream("/extensions/tiktok.mpe") ?: return null
        val file = File.createTempFile("tiktok-extension", ".dex")
        file.deleteOnExit()
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun fixtures(): List<File> = Fixtures.apks()
}
