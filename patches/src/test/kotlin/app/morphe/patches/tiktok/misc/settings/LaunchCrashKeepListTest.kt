/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.appendFixtureInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private const val OWNER = "LX/KeepList;"
private const val HELPER = "Lapp/morphe/extension/tiktok/settings/LaunchCrashCleanup;"

/** Hushfeed's preferences files join the list TikTok's launch-crash cleanup keeps. */
class LaunchCrashKeepListTest {
    @Test
    fun `the list the cleanup reads gains Hushfeed's files and the other lists do not`() {
        val init = keepListInit()
        keepHushfeedPreferenceFiles(init, listOf(init, sharedPrefsWalker(reads = "LIZIZ")))

        val store = assertHelperFeedsTheStore(init)
        assertEquals("LIZIZ", store.name)
    }

    /** 46.x's list has no language preferences entry; the launch-crash one finds it all the same. */
    @Test
    fun `a list without TikTok's language preferences is found by its launch-crash entry`() {
        val init = keepListInit(withLanguage = false)
        keepHushfeedPreferenceFiles(init, listOf(init, sharedPrefsWalker(reads = "LIZIZ")))

        assertEquals("LIZIZ", assertHelperFeedsTheStore(init).name)
    }

    @Test
    fun `a list nothing reads while deleting is refused before anything is written`() {
        val init = keepListInit()
        val before = init.implementation!!.instructions.map { it.opcode }
        val refused = assertThrows(PatchException::class.java) {
            keepHushfeedPreferenceFiles(init, listOf(init, sharedPrefsWalker(reads = "LIZJ")))
        }
        assertTrue(refused.message.orEmpty(), "nothing that walks shared_prefs" in refused.message.orEmpty())
        assertEquals("a refused list was still changed", before, init.implementation!!.instructions.map { it.opcode })
    }

    /**
     * Every fixture, the forced 46.x builds with the declared one: the Settings patch every other
     * patch depends on starts here, so a build it can't find the list on loses them all.
     */
    @Test
    fun `every fixture's keep-list is the one its shared_prefs walker reads`() {
        val apks = Fixtures.apks()
        Fixtures.declaredVersions().forEach { version ->
            assertTrue("the declared $version fixture is missing", apks.any { Fixtures.versionOf(it) == version })
        }
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            // The patch's own fingerprint picks the initialiser, so a string it gains that a build
            // lacks fails here and not only in a full apply.
            val owners = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { classDef ->
                    classDef.methods.any { method -> LaunchCrashKeepListFingerprint.takes(method, classDef) }
                }
            }
            assertEquals("${apk.name}: classes whose initialiser LaunchCrashKeepListFingerprint takes", 1, owners.size)
            val owner = owners.single()
            val init = MutableMethod(owner.methods.single { it.name == "<clinit>" })

            keepHushfeedPreferenceFiles(init, owner.methods)

            val store = assertHelperFeedsTheStore(init)
            val walker = owner.methods.single { it.hasStrings("shared_prefs") }
            assertTrue(
                "${apk.name}: the extended list isn't the one the shared_prefs walker reads",
                walker.implementation!!.instructions.any {
                    it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>() == store
                },
            )
        }
    }

    @Test
    fun `the Settings patch extends the keep-list before it writes anything else`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/settings/SettingsPatch.kt").readText()
        val execute = source.indexOf("execute {", source.indexOf("val settingsPatch"))
        assertTrue("the Settings patch has no execute block", execute >= 0)
        val call = source.indexOf("keepHushfeedPreferenceFiles(", execute)
        val firstWrite = Regex("""\.(addInstructions?|addInstructionsWithLabels|replaceInstruction|removeInstruction)\b""")
            .find(source, execute)?.range?.first ?: source.length
        assertTrue("the Settings patch no longer extends TikTok's launch-crash keep-list", call >= 0)
        assertTrue("the keep-list is extended after the Settings patch has written", call < firstWrite)
    }

    /** The helper's result is what the initialiser stores, and it is called once. */
    private fun assertHelperFeedsTheStore(init: MutableMethod): FieldReference {
        val instructions = init.implementation!!.instructions.toList()
        val calls = instructions.withIndex().filter { (_, instruction) ->
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == HELPER && it.name == "keepHushfeedFiles" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("[Ljava/lang/String;") &&
                    it.returnType == "[Ljava/lang/String;"
            } == true
        }
        assertEquals("one call to the helper", 1, calls.size)
        val at = calls.single().index
        val result = instructions[at + 1]
        val store = instructions[at + 2]
        assertEquals(Opcode.MOVE_RESULT_OBJECT, result.opcode)
        assertEquals(Opcode.SPUT_OBJECT, store.opcode)
        val register = (store as OneRegisterInstruction).registerA
        assertEquals(register, (result as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, instructions[at].opcode)
        return store.getReference<FieldReference>()!!
    }

    private fun Method.hasStrings(vararg wanted: String): Boolean {
        val found = implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }?.toSet()
            ?: return false
        return wanted.all { it in found }
    }

    /**
     * The initialiser as 47.0.3 writes it: ten names into one array, then a second, shorter list.
     * 46.x writes the same first array without the language preferences, nine names.
     */
    private fun keepListInit(withLanguage: Boolean = true): MutableMethod {
        val names = listOf(
            "safe_mode_config_sp", "safe_mode_exception_repo", "safe_mode_v2_status",
            "cold_boot_launch_protector", "launch_crash_intercept_sp", "aweme_user",
            "token_shared_preference", "sp_TicketGuardHelper", "com.bytedance.sdk.account_setting",
        ) + if (withLanguage) listOf("key_language_sp_key") else emptyList()
        val body = StringBuilder()
        names.forEachIndexed { register, name -> body.append("const-string v$register, \"$name\"\n") }
        body.append("filled-new-array/range {v0 .. v${names.size - 1}}, [Ljava/lang/String;\n")
        body.append("move-result-object v0\n")
        body.append("sput-object v0, $OWNER->LIZIZ:[Ljava/lang/String;\n")
        body.append("const-string v1, \"token_shared_preference\"\n")
        body.append("const-string v0, \"imsdk_encryption\"\n")
        body.append("filled-new-array {v1, v0}, [Ljava/lang/String;\n")
        body.append("move-result-object v0\n")
        body.append("sput-object v0, $OWNER->LIZJ:[Ljava/lang/String;\n")
        body.append("return-void\n")
        return method("<clinit>", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 10, body)
    }

    /** The method that deletes every preferences file not on the list it reads. */
    private fun sharedPrefsWalker(reads: String): MutableMethod = method(
        "LIZLLL",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        2,
        StringBuilder()
            .append("const-string v0, \"shared_prefs\"\n")
            .append("sget-object v1, $OWNER->$reads:[Ljava/lang/String;\n")
            .append("return-void\n"),
    )

    private fun method(name: String, flags: Int, registers: Int, body: StringBuilder): MutableMethod =
        MutableMethod(
            ImmutableMethod(
                OWNER, name, emptyList(), "V", flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { appendFixtureInstructions(body.toString()) }
}
