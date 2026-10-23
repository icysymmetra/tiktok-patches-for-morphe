/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.featuregatelab

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frames the Lab reads a gate read's stack by, held to each declared build.
 *
 * GateCallers finds the code that asked for a gate by walking out from the hooked getter past
 * TikTok's settings plumbing, up to `MAX_INNER_FRAMES` frames between one getter frame and the
 * next, and tells a read through the getter with a default by a second SettingsManager frame
 * within that reach. Neither names a class R8 renamed: the Lab used to, with 46.2.3's names,
 * and on 47.x those were an enum, an empty class and unrelated lambdas. What it relies on
 * instead is the chain below, which each build has under its own made-up names: SettingsManager
 * hands its settings cache a wrapper (with a default) or a lambda (a typed getter), the cache
 * calls it through an interface, and that calls the getter without a default or the app AB
 * class. Two frames between getters, on every build.
 */
class FeatureGateLabFramesTest {
    private val settingsManager = "Lcom/bytedance/ies/abmock/SettingsManager;"
    private val typed = listOf("Z", "D", "F", "I", "J", "Ljava/lang/String;")

    /** The app AB class's getters, `(parameters)return`, as FeatureGateLabPatch finds the class. */
    private val appAbGetters = setOf(
        "(ILjava/lang/String;ZZ)Z", "(DILjava/lang/String;Z)D", "(ILjava/lang/String;ZF)F",
        "(IILjava/lang/String;Z)I", "(IJLjava/lang/String;Z)J",
        "(ILjava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;", "(Ljava/lang/String;Z)Ljava/lang/Object;",
    )

    @Test
    fun `each declared build reaches one getter from the next within the Lab's reach`() {
        val reach = Regex("""MAX_INNER_FRAMES = (\d+);""").find(gateCallers().readText())
            ?.groupValues?.get(1)?.toInt() ?: error("GateCallers.java has no MAX_INNER_FRAMES")
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val manager = app.getValue(settingsManager)

            val withDefault = manager.methods.single {
                !it.isStatic() && it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Class;", "Ljava/lang/Object;") &&
                    it.returnType == "Ljava/lang/Object;"
            }
            val withoutDefault = manager.methods.single {
                it.isStatic() && it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/Class;") &&
                    it.returnType == "Ljava/lang/Object;"
            }
            val defaultPath = throughCache(app, withDefault) { callee ->
                callee.definingClass == settingsManager && callee.name == withoutDefault.name &&
                    callee.parameterTypes == withoutDefault.parameterTypes
            }
            assertEquals("frames between the getter with a default and the one without", 2, defaultPath.size)
            assertTrue("the wrapper keeps the SettingsManager it is built with: $defaultPath",
                app.getValue(defaultPath[1]).directMethods.any {
                    it.name == "<init>" && it.parameterTypes == listOf(settingsManager)
                })

            val typedGetters = manager.methods.filter {
                it.isStatic() && it.parameterTypes.size == 2 && it.parameterTypes[0] == "Ljava/lang/String;" &&
                    it.parameterTypes[1] == it.returnType && it.returnType in typed
            }
            assertEquals("typed getters: ${typedGetters.map { it.name }}", typed.size, typedGetters.size)
            for (getter in typedGetters) {
                val path = throughCache(app, getter) { callee -> carriesAppAbGetters(app[callee.definingClass]) }
                assertEquals("frames between ${getter.name}(${getter.returnType}) and the app AB class", 2, path.size)
            }

            assertTrue("the Lab reaches $reach frames and a build needs 2", 2 <= reach)
        }
    }

    @Test
    fun `each getter class the Lab names is on each declared build`() {
        val source = gateCallers().readText()
        val block = source.substringAfter("NAMED_GETTER_CLASSES = {").substringBefore("};")
        val named = Regex(""""([a-z][\w.]+)"""").findAll(block).map { it.groupValues[1] }.toList() +
            // SETTINGS_MANAGER, which the array names by its constant.
            Regex("""SETTINGS_MANAGER = "([\w.]+)";""").find(source)!!.groupValues[1]
        assertTrue("named getter classes: $named", named.size >= 5)
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val missing = named.distinct().filter { "L${it.replace('.', '/')};" !in app }
            assertEquals("getter classes the Lab names that the build lacks", emptyList<String>(), missing)
        }
    }

    /**
     * The classes between [getter] and the call [reaches] accepts: the cache [getter] hands a
     * new object to, then that object's class, whose implementation of the interface the cache
     * calls it through makes the call.
     */
    private fun throughCache(app: Map<String, ClassDef>, getter: Method, reaches: (MethodReference) -> Boolean): List<String> {
        val instructions = getter.implementation!!.instructions.toList()
        val made = instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { (it.getReference<TypeReference>()!!).type }
        val cacheCall = instructions.mapNotNull { it.getReference<MethodReference>() }.single { call ->
            call.parameterTypes.size == 4 && call.parameterTypes[0] == "Ljava/lang/String;" &&
                call.parameterTypes[2] == "Ljava/lang/Class;" && app[call.parameterTypes[3].toString()]
                ?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
        }
        val callback = cacheCall.parameterTypes[3].toString()
        val cacheMethod = app.getValue(cacheCall.definingClass).methods.single {
            it.name == cacheCall.name && it.parameterTypes == cacheCall.parameterTypes
        }
        val callbackCall = cacheMethod.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }
            .mapNotNull { it.getReference<MethodReference>() }.single { it.definingClass == callback }
        val passed = made.singleOrNull { callback in app.getValue(it).interfaces }
            ?: error("${getter.name} builds none of $made for $callback")
        val implementation = app.getValue(passed).methods.single {
            it.name == callbackCall.name && it.parameterTypes == callbackCall.parameterTypes
        }
        val calls = implementation.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
        assertTrue("$passed->${implementation.name} makes none of the calls wanted: $calls", calls.any(reaches))
        return listOf(cacheCall.definingClass, passed)
    }

    private fun carriesAppAbGetters(classDef: ClassDef?): Boolean {
        val shapes = classDef?.methods?.filter { !it.isStatic() }
            ?.mapTo(HashSet()) { it.parameterTypes.joinToString("", "(", ")") + it.returnType } ?: return false
        return shapes.containsAll(appAbGetters)
    }

    private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)

    private fun gateCallers(): File {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        return File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/featuregatelab/GateCallers.java")
            .also { assertTrue("$it is missing", it.isFile) }
    }

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
