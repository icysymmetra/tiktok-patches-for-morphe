/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tiktok.misc.extension.HostApplicationAttachBaseContextFingerprint
import app.morphe.takes
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOST_APPLICATION = "Lcom/ss/android/ugc/aweme/app/host/AwemeHostApplication;"
private const val ANDROID_NAME = 0x01010003

/**
 * What "App lock" rests on, held to each declared TikTok build. The lock is installed from the
 * host application's attachBaseContext, right after the framework call, with p0 handed over as
 * the application the screen callbacks are registered on. And the prompt needs the biometric
 * permissions, which TikTok declares itself, so the patch adds no manifest edit and the
 * resource decoding one would cost.
 */
class AppLockAnchorsTest {
    @Test
    fun `the host application takes the install after its framework call on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = HashMap<String, ClassDef>()
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
            }
            val taken = classes.values.flatMap { classDef ->
                classDef.methods.filter { HostApplicationAttachBaseContextFingerprint.takes(it, classDef) }
                    .map { classDef to it }
            }
            assertEquals("$version: attachBaseContext methods matched", 1, taken.size)
            val (owner, method) = taken.single()
            assertEquals(HOST_APPLICATION, owner.type)
            assertTrue("$version: attachBaseContext is static", !AccessFlags.STATIC.isSet(method.accessFlags))

            val index = appLockInstallIndex(method)
            val implementation = method.implementation!!
            // The install hands over p0 with a range invoke, which takes any register number, so
            // the one thing to hold is that p0 (this, ahead of the Context in p1) is in the frame.
            assertTrue("$version: p0 isn't in the frame", implementation.registerCount >= 2)
            val instructions = implementation.instructions.toList()
            val call = (instructions[index - 1] as ReferenceInstruction).reference as MethodReference
            assertEquals("$version: the install doesn't follow the framework call",
                "Landroid/app/Application;->attachBaseContext", "${call.definingClass}->${call.name}")
            assertTrue("$version: nothing follows the framework call to insert before", index < instructions.size)
            assertNotEquals("$version: the install would split a call from its result",
                Opcode.MOVE_RESULT_OBJECT, instructions[index].opcode)

            // p0 has to reach the lock as an Application, or the callbacks are never registered.
            var type: String? = owner.superclass
            while (type != null && type in classes) type = classes.getValue(type).superclass
            assertEquals("$version: the host application isn't an Application", "Landroid/app/Application;", type)
        }
    }

    @Test
    fun `each declared build carries the permissions the prompt needs`() {
        Fixtures.forEachDeclared { apk ->
            val permissions = usesPermissions(apk)
            assertTrue("${Fixtures.versionOf(apk)} lacks USE_BIOMETRIC: the prompt needs a manifest edit",
                "android.permission.USE_BIOMETRIC" in permissions)
            assertTrue("${Fixtures.versionOf(apk)} lacks USE_FINGERPRINT: Android 9's prompt needs a manifest edit",
                "android.permission.USE_FINGERPRINT" in permissions)
        }
    }

    @Test
    fun `the install refuses a method it can't place itself in`() {
        val noCall = method(isStatic = false)
        assertThrows(PatchException::class.java) { appLockInstallIndex(noCall) }
        val static = method(isStatic = true)
        assertThrows(PatchException::class.java) { appLockInstallIndex(static) }
    }

    private fun method(isStatic: Boolean): Method = ImmutableMethod(
        HOST_APPLICATION,
        "attachBaseContext",
        listOf(ImmutableMethodParameter("Landroid/content/Context;", emptySet(), null)),
        "V",
        AccessFlags.PROTECTED.value or if (isStatic) AccessFlags.STATIC.value else 0,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), emptyList(), emptyList()),
    )

    private fun usesPermissions(apk: File): Set<String> {
        val bytes = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val found = mutableSetOf<String>()
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT || parser.name != "uses-permission") continue
            for (i in 0 until parser.attributeCount) {
                if (parser.getAttributeNameResourceId(i) == ANDROID_NAME) found += parser.getAttributeStringValue(i)
            }
        }
        return found
    }
}
