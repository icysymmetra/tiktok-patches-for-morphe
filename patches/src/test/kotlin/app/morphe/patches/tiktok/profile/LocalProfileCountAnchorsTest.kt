/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exact native display owners. Model getters and the counts used by analytics stay native. */
class LocalProfileCountAnchorsTest {
    private data class Found(val classes: Map<String, ClassDef>, val legacy: Pair<Method, ProfileCountTextSite>,
        val sites: Map<ProfileCountMetric, List<Pair<Method, ProfileCountFormatSite>>>, val formatter: MethodReference)

    companion object {
        private val cache = mutableMapOf<String, Found>()
        private const val TEXT = "Ljava/lang/CharSequence;"
        private const val NUMBER = "Ljava/lang/Number;"
        private const val STRING = "Ljava/lang/String;"
    }

    @Test fun `47 4 3 has one older count bind and four formatter results delivered to profile TextViews`() {
        Fixtures.forEachDeclared { apk ->
            assertEquals("47.4.3", Fixtures.versionOf(apk))
            val found = find(apk)
            assertEquals("gr", found.legacy.first.name)
            assertEquals("V", found.legacy.first.returnType)
            assertTrue(found.legacy.first.parameterTypes.isEmpty())
            assertEquals(mapOf(
                ProfileCountMetric.FOLLOWERS to listOf("LJJLIL(Z)V", "LJLJJL(I)V"),
                ProfileCountMetric.FOLLOWING to listOf("LJJLIIIJJI()Z"),
                ProfileCountMetric.LIKES to listOf("LJJLIIIJJI()Z"),
            ), found.sites.mapValues { (_, sites) -> sites.map { (method, _) -> signature(method) }.sorted() })
            assertEquals("LX/0OSF;->LIZ(I${NUMBER}${STRING})$TEXT", found.formatter.toString())
            val formatter = found.classes.getValue(found.formatter.definingClass).methods.single {
                it.name == found.formatter.name && it.parameterTypes.map(CharSequence::toString) == listOf("I", NUMBER, STRING) && it.returnType == TEXT
            }
            assertTrue(AccessFlags.PUBLIC.isSet(formatter.accessFlags))
            assertTrue(AccessFlags.STATIC.isSet(formatter.accessFlags))
        }
    }

    @Test fun `the native component keys and explicit profile UID getters exist independently of User models`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            for (metric in ProfileCountMetric.entries) {
                val getName = found.classes.getValue(metric.protocol).methods.single { it.name == "getName" && it.parameterTypes.isEmpty() }
                assertEquals(STRING, getName.returnType)
                assertEquals(listOf(metric.component), getName.implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string })
            }
            assertEquals("uq", found.classes.getValue(BASE_UI_COMPONENT).commonInfoGetter()!!.name)
            assertEquals("LJIJI", found.classes.getValue(PROFILE_PROTOCOL).commonInfoGetter()!!.name)
            for ((owner, name, type) in listOf(Triple(PROFILE_COMMON_INFO, "getUserProfileInfo", PROFILE_INFO), Triple(PROFILE_INFO, "getUid", STRING))) {
                val method = found.classes.getValue(owner).methods.single { it.name == name && it.parameterTypes.isEmpty() }
                assertEquals(type, method.returnType)
                assertTrue(AccessFlags.PUBLIC.isSet(method.accessFlags))
            }
            val identity = found.legacy.first.legacyCountIdentity()
            assertEquals("LLL", identity.attached.name)
            assertEquals("LX/0ON5;", identity.attached.type)
            assertEquals("LLJJI", identity.holder.name)
            assertEquals("componentId", identity.key.name)
            assertEquals("LLJLLIL", identity.fallback.name)
        }
    }

    @Test fun `the shared native formatter only formats numbers with fresh format options`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val method = found.classes.getValue(found.formatter.definingClass).methods.single {
                it.name == found.formatter.name && it.parameterTypes.map(CharSequence::toString) == listOf("I", NUMBER, STRING) && it.returnType == TEXT
            }
            val code = method.implementation!!.instructions.toList()
            assertTrue(code.none { it.opcode.name.startsWith("IPUT") || it.opcode.name.startsWith("SPUT") })
            assertEquals(setOf("Ljava/lang/Number;", "LX/01Qa;", "LX/01OU;"),
                code.mapNotNull { it.getReference<MethodReference>()?.definingClass }.toSet())
            assertTrue(code.any { it.opcode == Opcode.NEW_INSTANCE })
        }
    }

    @Test fun `the four protocol edits preserve frames and every instruction except the formatter invocation`() {
        Fixtures.forEachDeclared { apk ->
            for ((_, sites) in find(apk).sites) for ((original, site) in sites) {
                val patched = MutableMethod(original)
                val before = patched.implementation!!.instructions.toList()
                patched.replaceProfileCount(site.index, site.registers)
                val after = patched.implementation!!.instructions.toList()
                assertEquals(original.implementation!!.registerCount, patched.implementation!!.registerCount)
                assertEquals(before.size, after.size)
                before.forEachIndexed { index, instruction -> if (index != site.index) assertSame(instruction, after[index]) }
                assertEquals(Opcode.INVOKE_DIRECT, after[site.index].opcode)
                val call = after[site.index] as FiveRegisterInstruction
                assertEquals(4, call.registerCount)
                assertEquals(patched.implementation!!.registerCount - patched.numberOfParameterRegisters, call.registerC)
                assertEquals(site.registers, listOf(call.registerD, call.registerE, call.registerF))
                val bridge = after[site.index].getReference<MethodReference>()!!
                assertEquals(original.definingClass, bridge.definingClass)
                assertEquals(listOf("I", NUMBER, STRING), bridge.parameterTypes.map(CharSequence::toString))
                assertEquals(TEXT, bridge.returnType)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, after[site.index + 1].opcode)
                assertEquals("setText", after[site.index + 2].getReference<MethodReference>()!!.name)
            }
        }
    }

    @Test fun `the older edit changes only the number setter and preserves its unset fallback branch`() {
        Fixtures.forEachDeclared { apk ->
            val (original, site) = find(apk).legacy
            val patched = MutableMethod(original)
            val before = patched.implementation!!.instructions.toList()
            patched.replaceProfileCount(site.index, listOf(site.view, site.text), true)
            val after = patched.implementation!!.instructions.toList()
            before.forEachIndexed { index, instruction -> if (index != site.index) assertSame(instruction, after[index]) }
            assertEquals(before.size, after.size)
            assertEquals(original.implementation!!.registerCount, patched.implementation!!.registerCount)
            val call = after[site.index] as FiveRegisterInstruction
            assertEquals(3, call.registerCount)
            assertEquals(15, call.registerC)
            assertEquals(site.view, call.registerD)
            assertEquals(site.text, call.registerE)
            assertEquals(listOf("Landroid/widget/TextView;", TEXT), after[site.index].getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            assertEquals(Opcode.IF_EQZ, after[site.index - 1].opcode)
            assertTrue(after.any { it.getReference<StringReference>()?.string == "-" })
        }
    }

    @Test fun `the bridges preserve original formatting and guard identity before returning a local display argument`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val protocolGetter = found.classes.getValue(PROFILE_PROTOCOL).commonInfoGetter()!!
            val legacyGetter = found.classes.getValue(BASE_UI_COMPONENT).commonInfoGetter()!!
            val bridges = ProfileCountMetric.entries.map { metric ->
                profileCountBridge(metric.protocol, protocolGetter, found.formatter, metric)
            } + profileCountBridge(RELATION_COMPONENT, legacyGetter, found.formatter, identity = found.legacy.first.legacyCountIdentity())
            for (bridge in bridges) {
                val legacy = bridge.definingClass == RELATION_COMPONENT
                val code = bridge.implementation!!.instructions.toList()
                val enabled = code.indexOfFirst { it.getReference<MethodReference>()?.name == "enabled" }
                assertEquals(if (legacy) 1 else 2, enabled)
                if (!legacy) assertEquals(found.formatter.toString(), code[0].getReference<MethodReference>().toString())
                val branchIndex = enabled + 2
                assertEquals(Opcode.IF_EQZ, code[branchIndex].opcode)
                val branch = code[branchIndex] as OffsetInstruction
                val fallBack = code.single { it.location.codeAddress == code[branchIndex].location.codeAddress + branch.codeOffset }
                assertEquals(if (legacy) Opcode.INVOKE_VIRTUAL else Opcode.RETURN_OBJECT, fallBack.opcode)
                if (legacy) assertEquals("setText", fallBack.getReference<MethodReference>()!!.name)
                val calls = code.mapNotNull { it.getReference<MethodReference>() }
                assertTrue(calls.any { it.definingClass == PROFILE_COMMON_INFO && it.name == "getUserProfileInfo" })
                assertTrue(calls.any { it.definingClass == PROFILE_INFO && it.name == "getUid" })
                assertEquals(1, calls.count { it.definingClass == LOCAL_COUNTS && it.name == "override" })
                val choose = calls.single { it.definingClass == LOCAL_COUNTS && it.name == "choose" }
                assertEquals(listOf(TEXT, TEXT, NUMBER, STRING, STRING), choose.parameterTypes.map(CharSequence::toString))
                assertEquals(TEXT, choose.returnType)
                assertEquals(if (legacy) 1 else 2, calls.count { it.toString() == found.formatter.toString() })
                assertTrue(code.none { it.opcode.name.startsWith("IPUT") || it.opcode.name.startsWith("SPUT") })
                assertTrue(calls.none { it.name.startsWith("set") && it.name != "setText" })
                assertTrue(calls.none { it.definingClass == RELATION_DATA || it.definingClass.endsWith("/User;") ||
                    it.definingClass.contains("retrofit") || it.definingClass.contains("Network") })
                assertTrue(AccessFlags.PRIVATE.isSet(bridge.accessFlags))
                assertTrue(AccessFlags.SYNTHETIC.isSet(bridge.accessFlags))
            }
        }
    }

    @Test fun `unowned classes static methods and formatter values not sent directly to text do not match`() {
        Fixtures.forEachDeclared { apk ->
            val (method, site) = find(apk).sites.getValue(ProfileCountMetric.FOLLOWERS).first()
            assertTrue(copy(method, owner = "Lfixture/Unowned;").profileCountFormats().isEmpty())
            assertTrue(copy(method, flags = method.accessFlags or AccessFlags.STATIC.value).profileCountFormats().isEmpty())
            val interrupted = MutableMethod(method)
            interrupted.addInstructions(site.index + 2, "nop")
            assertTrue(interrupted.profileCountFormats().isEmpty())
            val legacy = find(apk).legacy.first
            assertTrue(copy(legacy, owner = "Lfixture/Unowned;").profileCountTexts().isEmpty())
            assertTrue(copy(legacy, flags = legacy.accessFlags or AccessFlags.STATIC.value).profileCountTexts().isEmpty())
        }
    }

    @Test fun `a frame that cannot encode its receiver is refused before any instruction changes`() {
        Fixtures.forEachDeclared { apk ->
            val (original, site) = find(apk).sites.getValue(ProfileCountMetric.FOLLOWERS).first()
            val tooLarge = MutableMethod(copy(original, registers = original.implementation!!.registerCount + 20))
            val before = tooLarge.implementation!!.instructions.toList()
            assertThrows(PatchException::class.java) { tooLarge.replaceProfileCount(site.index, site.registers) }
            assertEquals(before, tooLarge.implementation!!.instructions.toList())
        }
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            val classes = Fixtures.dexContainer(apk, Opcodes.getDefault()).getEntry("classes12.dex")!!.dexFile.classes.associateBy { it.type }
            val legacy = classes.getValue(RELATION_COMPONENT).methods.flatMap { method -> method.profileCountTexts().map { method to it } }
            assertEquals("older count sites", 1, legacy.size)
            val sites = ProfileCountMetric.entries.associateWith { metric ->
                classes.getValue(metric.protocol).methods.flatMap { method -> method.profileCountFormats().map { method to it } }
                    .also { assertEquals(metric.name, metric.sites, it.size) }
            }
            val formatter = sites.values.flatten().map { it.second.formatter }.distinctBy { it.toString() }.single()
            Found(classes, legacy.single(), sites, formatter)
        }
    }

    private fun signature(method: Method) = "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

    private fun copy(method: Method, owner: String = method.definingClass, flags: Int = method.accessFlags,
        registers: Int = method.implementation!!.registerCount): Method = ImmutableMethod(owner, method.name,
        method.parameters, method.returnType, flags, null, null,
        ImmutableMethodImplementation(registers, method.implementation!!.instructions, null, null))
}
