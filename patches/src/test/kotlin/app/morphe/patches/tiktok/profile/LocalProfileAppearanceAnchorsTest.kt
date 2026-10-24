/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.RegisterLiveness
import app.morphe.patcher.util.getReference
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native model reads, display joins and asynchronous captures on the declared original host. */
class LocalProfileAppearanceAnchorsTest {
    private data class Found(val classes: Map<String, ClassDef>, val legacy: Pair<Method, LegacyAppearanceSite>,
        val nickname: Pair<Method, NicknameAppearanceSite>, val repaint: Pair<Method, Int>,
        val warnings: List<Pair<Method, NoticeAppearanceSite>>) {
        val navbar get() = classes.getValue(NAVBAR_NICKNAME_OWNER).methods.mapNotNull { method ->
            method.navbarAppearanceSite()?.let { method to it }
        }.single()
        val navbarRepaint get() = classes.getValue(NAVBAR_NICKNAME_REPAINT).methods.mapNotNull { method ->
            method.navbarRepaintSite()?.let { method to it }
        }.single()
        val navbarAddName get() = classes.getValue(NAVBAR_NICKNAME_OWNER).methods.mapNotNull { method ->
            method.navbarAddNameSite()?.let { method to it }
        }.single()
        val navbarAddRepaint get() = classes.getValue(NAVBAR_ADD_NAME_REPAINT).methods.single { it.navbarAddNameRepaintSite() }
    }

    companion object {
        private val cache = mutableMapOf<String, Found>()
        private const val STRING = "Ljava/lang/String;"
        private const val INTEGER = "Ljava/lang/Integer;"
        private const val BOOLEAN = "Ljava/lang/Boolean;"
    }

    @Test fun `47 4 3 has both nickname generations one original-resource repaint and both warning show sites`() {
        Fixtures.forEachDeclared { apk ->
            assertEquals("47.4.3", Fixtures.versionOf(apk))
            val found = find(apk)
            assertEquals("Cr", found.legacy.first.name)
            assertEquals("LJJLIIIJJI", found.nickname.first.name)
            assertEquals("LIZ", found.repaint.first.name)
            assertEquals("LJJLIIIJJI", found.navbar.first.name)
            assertEquals("LIZ", found.navbarRepaint.first.name)
            assertEquals("LIZ", found.navbarAddRepaint.name)
            assertNotNull(found.nickname.first.nicknameAddNameSite())
            assertEquals("LLD", found.navbarAddName.first.name)
            assertEquals(listOf("LJJLIIIJJI", "LJJLIIIJLLLLLLLZ"), found.warnings.map { it.first.name }.sorted())
            assertEquals("Lkotlin/jvm/internal/Intrinsics;->LJI(Ljava/lang/Object;Ljava/lang/Object;)Z", found.legacy.second.original.toString())
            for ((owner, component) in listOf(NICKNAME_OWNER to "user_info_nickname", NAVBAR_NICKNAME_OWNER to "nav_bar_center_nickname",
                RESTRICTION_OWNER to "user_account_ags_warning")) {
                assertEquals(listOf(component), found.classes.getValue(owner).methods.single { it.name == "getName" }
                    .implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string })
            }
        }
    }

    @Test fun `legacy badge geometry and icon come from the native Boolean comparison without model writes`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val (original, site) = found.legacy
            val before = original.implementation!!.instructions.toList()
            val patched = MutableMethod(original)
            val mutableBefore = patched.implementation!!.instructions.toList()
            patched.replaceLegacyAppearance(site)
            val after = patched.implementation!!.instructions.toList()
            assertEquals(18, patched.implementation!!.registerCount)
            assertEquals(before.size, after.size)
            mutableBefore.forEachIndexed { index, instruction -> if (index != site.index) assertSame(instruction, after[index]) }
            assertEquals(Opcode.INVOKE_DIRECT, after[site.index].opcode)
            assertEquals(listOf(2, 4, 0), after[site.index].namedRegisters())
            assertEquals(listOf(BOOLEAN, BOOLEAN), after[site.index].getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            assertTrue(before.any { (it as? WideLiteralInstruction)?.wideLiteral == NATIVE_BLUE_BADGE.toLong() })
            assertEquals(0x7f01026c, NATIVE_BLUE_BADGE)
            assertEquals(NATIVE_BLUE_BADGE, extensionBadge())
            assertTrue(before.none { it.opcode.name.startsWith("iput") && it.getReference<FieldReference>()?.definingClass?.endsWith("/UserAccountInfo;") == true })
            val identity = found.classes.getValue(LEGACY_NICKNAME_OWNER).methods.single { it.name == "zr" }
            val fields = identity.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
            assertEquals(listOf("LLL", "LLJJI", "componentName", "LLJLLIL"), fields.map { it.name })
            assertEquals(listOf("user_account_user_name"), identity.implementation!!.instructions.mapNotNull { it.getReference<StringReference>()?.string })
        }
    }

    @Test fun `current badge selection preserves the original nullable resource and capture with the same frame`() {
        Fixtures.forEachDeclared { apk ->
            val (original, site) = find(apk).nickname
            val patched = MutableMethod(original)
            val before = patched.implementation!!.instructions.toList()
            assertFalse(RegisterLiveness.of(original).liveInto(site.branch).contains(0))
            val target = before[site.target]
            val capture = before[site.capture]
            patched.replaceNicknameAppearance(site)
            val after = patched.implementation!!.instructions.toList()
            assertEquals(14, patched.implementation!!.registerCount)
            assertEquals(before.size + 2, after.size)
            before.forEachIndexed { index, instruction ->
                if (index != site.branch && index !in site.render..site.render + 2) {
                    assertSame(instruction, after[index + if (index > site.branch) 2 else 0])
                }
            }
            assertEquals(APPEARANCE_SELECT, after[site.branch].getReference<MethodReference>()!!.name)
            assertEquals(listOf(13, 5), after[site.branch].namedRegisters())
            assertEquals(Opcode.MOVE_RESULT_OBJECT, after[site.branch + 1].opcode)
            assertEquals(listOf(0), after[site.branch + 1].namedRegisters())
            val branch = after[site.branch + 2] as OffsetInstruction
            val address = after.take(site.branch + 2).sumOf { it.codeUnits } + branch.codeOffset
            assertSame(target, after[addressIndex(after, address)])
            assertEquals(APPEARANCE_RENDER, after[site.render + 2].getReference<MethodReference>()!!.name)
            assertEquals(listOf(13, 5, 9, 4), after[site.render + 2].namedRegisters())
            assertSame(capture, after[site.capture + 2])
            assertEquals(listOf(0, 9, 13, 5, 4), capture.namedRegisters())
            assertEquals(listOf("I", NICKNAME_OWNER, INTEGER, STRING), capture.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
        }
    }

    @Test fun `deferred repaint passes the original nullable Integer instead of unboxing a local capture`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val constructor = found.classes.getValue(NICKNAME_REPAINT).methods.single { it.name == "<init>" }
            assertTrue(constructor.nullableNicknameCapture())
            val nullRejecting = MutableMethod(constructor).apply {
                replaceInstruction(2, "invoke-static { p3 }, Lkotlin/jvm/internal/Intrinsics;->checkNotNull(Ljava/lang/Object;)V")
            }
            assertFalse(nullRejecting.nullableNicknameCapture())
            val (original, index) = found.repaint
            val patched = MutableMethod(original)
            val before = patched.implementation!!.instructions.toList()
            patched.replaceNicknameRepaint(index)
            val after = patched.implementation!!.instructions.toList()
            assertEquals(9, patched.implementation!!.registerCount)
            assertEquals(before.size, after.size)
            before.forEachIndexed { at, instruction -> if (at !in setOf(index, index + 1, index + 3)) assertSame(instruction, after[at]) }
            assertEquals(Opcode.MOVE_OBJECT, after[index].opcode)
            assertEquals(listOf(1, 0), after[index].namedRegisters())
            assertEquals(Opcode.NOP, after[index + 1].opcode)
            assertEquals(APPEARANCE_RENDER, after[index + 3].getReference<MethodReference>()!!.name)
            assertEquals(listOf(INTEGER, "I", STRING), after[index + 3].getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            assertEquals(listOf(2, 1, 0, 3), after[index + 3].namedRegisters())
        }
    }

    @Test fun `both warning edits replace only VISIBLE and keep native GONE appeal listeners flags and text`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            assertEquals(2, found.warnings.size)
            for ((original, site) in found.warnings) {
                val patched = MutableMethod(original)
                val before = patched.implementation!!.instructions.toList()
                val visibilityCalls = before.indices.filter { before[it].getReference<MethodReference>()?.toString() == NOTICE_VISIBILITY }
                assertEquals(2, visibilityCalls.size)
                patched.replaceNoticeAppearance(site)
                val after = patched.implementation!!.instructions.toList()
                assertEquals(original.implementation!!.registerCount, patched.implementation!!.registerCount)
                assertEquals(before.size, after.size)
                before.forEachIndexed { index, instruction -> if (index != site.index) assertSame(instruction, after[index]) }
                assertEquals(Opcode.INVOKE_DIRECT, after[site.index].opcode)
                assertEquals(APPEARANCE_NOTICE, after[site.index].getReference<MethodReference>()!!.name)
                assertSame(before[visibilityCalls.single { it != site.index }], after[visibilityCalls.single { it != site.index }])
                val calls = after.mapNotNull { it.getReference<MethodReference>() }
                assertTrue(calls.any { it.name == "J7" && it.parameterTypes.first().toString() == "Landroid/view/View\$OnClickListener;" })
                assertTrue(calls.any { it.name == "setText" })
                assertTrue(after.any { (it as? WideLiteralInstruction)?.wideLiteral == 0x7f0a66a5L })
            }
        }
    }

    @Test fun `bridges read explicit profile identities and only select native rendering arguments`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val protocolGetter = found.classes.getValue(PROFILE_PROTOCOL).commonInfoGetter()!!
            val legacyGetter = found.classes.getValue(BASE_UI_COMPONENT).commonInfoGetter()!!
            assertEquals("LJIJI", protocolGetter.name)
            assertEquals("uq", legacyGetter.name)
            val bridges = listOf(legacyAppearanceBridge(legacyGetter, found.legacy.second.original),
                nicknameAppearanceBridge(protocolGetter), nicknameRenderBridge(), noticeAppearanceBridge(protocolGetter))
            for (bridge in bridges) {
                val code = bridge.implementation!!.instructions.toList()
                val calls = code.mapNotNull { it.getReference<MethodReference>() }
                assertTrue(AccessFlags.SYNTHETIC.isSet(bridge.accessFlags))
                assertTrue(code.none { it.opcode.name.startsWith("iput") || it.opcode.name.startsWith("sput") })
                assertTrue(calls.none { it.name.startsWith("set") || it.definingClass.contains("retrofit") ||
                    it.definingClass.endsWith("/User;") || it.definingClass.endsWith("/UserAccountInfo;") })
                if (bridge.name == APPEARANCE_RENDER) {
                    assertTrue(AccessFlags.PUBLIC.isSet(bridge.accessFlags))
                    assertEquals(listOf(APPEARANCE_SELECT, "intValue", "LLII", "LJJLIIIJJI"), calls.map { it.name })
                    assertEquals(INTEGER, calls.first().parameterTypes.single().toString())
                } else {
                    assertTrue(AccessFlags.PRIVATE.isSet(bridge.accessFlags))
                    assertTrue(calls.any { it.definingClass == PROFILE_COMMON_INFO && it.name == "getUserProfileInfo" })
                    assertTrue(calls.any { it.definingClass == PROFILE_INFO && it.name == "getUid" })
                    assertEquals(1, calls.count { it.definingClass == LOCAL_APPEARANCE })
                }
            }
            val notice = bridges.last().implementation!!.instructions.toList()
            assertEquals("$PROFILE_PROTOCOL->LLJJJJJIL:Landroid/view/View;", notice.mapNotNull { it.getReference<FieldReference>() }.single().toString())
            assertEquals(NOTICE_VISIBILITY, notice.mapNotNull { it.getReference<MethodReference>() }.last().toString())
            val legacy = bridges.first().implementation!!.instructions.toList()
            assertEquals(found.legacy.second.original.toString(), legacy.first().getReference<MethodReference>().toString())
            assertTrue(legacy.any { it.getReference<MethodReference>()?.name == "zr" })
        }
    }

    @Test fun `native unresolved resource joins do not prove absent verification metadata`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            for (owner in listOf(NICKNAME_OWNER, NAVBAR_NICKNAME_OWNER)) {
                val native = found.classes.getValue(owner).methods.single { it.name == "LJJLIIIJJI" }
                    .implementation!!.instructions.toList()
                val verify = native.indexOfFirst { it.getReference<MethodReference>()?.name == "getVerifyIcon" }
                val localIcon = native.indexOfFirst { it.getReference<MethodReference>()?.name == "getLocalIcon" }
                val context = native.indexOfFirst { it.getReference<MethodReference>()?.name == "getContext" }
                val resolver = native.indexOfFirst { it.getReference<MethodReference>()?.definingClass == "LX/0OP8;" }
                val nullTargets = listOf(verify - 1, verify + 2, localIcon + 2, context + 2, resolver + 2)
                    .map { index -> assertEquals(Opcode.IF_EQZ, native[index].opcode); branchIndex(native, index) }
                assertEquals(1, nullTargets.distinct().size)
                assertEquals(Opcode.MOVE_OBJECT, native[nullTargets.first()].opcode)
                val getter = found.classes.getValue(PROFILE_PROTOCOL).commonInfoGetter()!!
                val selected = nicknameAppearanceBridge(getter, owner).implementation!!.instructions.toList()
                val metadata = selected.indexOfFirst { it.getReference<MethodReference>()?.name == "getVerifyIcon" }
                assertTrue(selected.take(metadata).any { it.opcode == Opcode.INSTANCE_OF && it.getReference<TypeReference>()?.type == NICKNAME_DATA })
                assertEquals(Opcode.IF_NEZ, selected[metadata + 2].opcode)
                assertEquals(listOf(2), selected[metadata + 2].namedRegisters())
                val nativeReturn = branchIndex(selected, metadata + 2)
                assertEquals(Opcode.RETURN_OBJECT, selected[nativeReturn].opcode)
                assertEquals(listOf(0), selected[nativeReturn].namedRegisters())
                val override = selected.single { it.getReference<MethodReference>()?.name == "badgeResource" }
                assertEquals(listOf(INTEGER, "Z", STRING, STRING), override.getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString))
            }
        }
    }

    @Test fun `navbar preserves native resource capture names arrows subtitle and branch frame`() {
        Fixtures.forEachDeclared { apk ->
            val (original, site) = find(apk).navbar
            assertFalse(RegisterLiveness.of(original).liveInto(site.branch).contains(6))
            val patched = MutableMethod(original)
            val before = patched.implementation!!.instructions.toList()
            val nativePlain = before[site.target]
            patched.replaceNavbarAppearance(site)
            val after = patched.implementation!!.instructions.toList()
            assertEquals(20, patched.implementation!!.registerCount)
            assertEquals(before.size + 4, after.size)
            assertEquals(APPEARANCE_SELECT, after[site.branch].getReference<MethodReference>()!!.name)
            assertEquals(listOf(9, 10), after[site.branch].namedRegisters())
            assertSame(nativePlain, after[branchIndex(after, site.branch + 2)])
            assertEquals(APPEARANCE_RENDER, after[site.render + 2].getReference<MethodReference>()!!.name)
            assertEquals(listOf(9, 10, 11, 4, 12), after[site.render + 2].namedRegisters())
            for (at in listOf(site.capture, site.addNameCapture)) assertTrue(after.any { it === before[at] })
            before.forEachIndexed { at, instruction ->
                if (at != site.branch && at !in site.render..site.render + 2) assertTrue(after.any { it === instruction })
            }
            assertEquals(APPEARANCE_CACHED, after[site.cached + 2].getReference<MethodReference>()!!.name)
            assertEquals(listOf(9, 2), after[site.cached + 2].namedRegisters())
        }
    }

    @Test fun `all native repaint constructors retain original arguments and capture generation before posting`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            for (owner in listOf(NICKNAME_OWNER, NAVBAR_NICKNAME_OWNER)) {
                val original = found.classes.getValue(owner).methods.single { it.name == "LJJLIIIJJI" }
                val patched = MutableMethod(original)
                val before = patched.implementation!!.instructions.toList()
                val captures = before.filter { it.getReference<MethodReference>()?.let { ref ->
                    ref.name == "<init>" && ref.definingClass in listOf(NICKNAME_REPAINT, NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT)
                } == true }
                patched.trackNicknameBinding()
                val after = patched.implementation!!.instructions.toList()
                assertEquals(APPEARANCE_BEGIN, after.first().getReference<MethodReference>()!!.name)
                assertEquals(Opcode.INVOKE_DIRECT_RANGE, after.first().opcode)
                assertEquals(original.implementation!!.registerCount, patched.implementation!!.registerCount)
                captures.forEach { capture ->
                    val at = after.indexOfFirst { it === capture }
                    assertTrue(at >= 0)
                    assertEquals("captureNicknameRepaint", after[at + 1].getReference<MethodReference>()!!.name)
                    assertEquals(if (owner == NICKNAME_OWNER) listOf(0, 13) else listOf(6, 9), after[at + 1].namedRegisters())
                    assertEquals("viewPost", after[at + 2].getReference<MethodReference>()!!.name)
                }
            }
            for (callback in listOf(NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT)) {
                val constructor = found.classes.getValue(callback).methods.single { it.name == "<init>" }
                assertTrue(constructor.nullableNavbarCapture())
                val altered = MutableMethod(constructor).apply { replaceInstruction(3, "const/4 v4, 0x0") }
                assertFalse(altered.nullableNavbarCapture())
            }
        }
    }

    @Test fun `every delayed nickname route checks captured binding before replaying native name or arrow`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val getter = found.classes.getValue(PROFILE_PROTOCOL).commonInfoGetter()!!
            for (original in listOf(found.repaint.first, found.navbarRepaint.first, found.navbarAddRepaint)) {
                val patched = MutableMethod(original)
                val before = patched.implementation!!.instructions.toList()
                patched.guardNicknameRepaint()
                val after = patched.implementation!!.instructions.toList()
                assertEquals(before.size + 4, after.size)
                assertEquals(APPEARANCE_CURRENT, after[0].getReference<MethodReference>()!!.name)
                assertEquals(Opcode.RETURN_VOID, after[3].opcode)
                assertEquals(4, branchIndex(after, 2))
                before.forEachIndexed { at, instruction -> assertSame(instruction, after[at + 4]) }
                val guard = nicknameCurrentBridge(getter, original.definingClass).implementation!!.instructions.toList()
                val calls = guard.mapNotNull { it.getReference<MethodReference>() }
                assertEquals(listOf(getter.name, "getUserProfileInfo", "getUid", "currentNicknameRepaint"), calls.map { it.name })
                assertEquals(1, guard.count { it.getReference<FieldReference>() != null })
                assertTrue(guard.none { it.opcode.name.startsWith("iput") || it.getReference<MethodReference>()?.name == "LJJLIIIJJI" })
            }
            val (original, index) = found.navbarRepaint
            val patched = MutableMethod(original).apply { replaceNavbarRepaint(index) }
            val code = patched.implementation!!.instructions.toList()
            assertEquals(Opcode.MOVE_OBJECT, code[index].opcode)
            assertEquals(listOf(2, 0), code[index].namedRegisters())
            assertEquals(APPEARANCE_RENDER, code[index + 4].getReference<MethodReference>()!!.name)
            assertEquals(listOf(3, 2, 1, 4, 0), code[index + 4].namedRegisters())
        }
    }

    @Test fun `Add name routes retain native spans listeners and fresh cache publication`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val original = found.nickname.first
            val index = original.nicknameAddNameSite()!!
            val patched = MutableMethod(original)
            val before = patched.implementation!!.instructions.toList()
            patched.replaceNicknameAddName(index)
            val after = patched.implementation!!.instructions.toList()
            assertEquals(before.size, after.size)
            before.forEachIndexed { at, instruction -> if (at != index) assertSame(instruction, after[at]) }
            assertEquals(listOf(13, 0, 4), after[index].namedRegisters())
            val header = nicknameAddNameBridge().implementation!!.instructions.toList()
            val calls = header.mapNotNull { it.getReference<MethodReference>() }
            assertEquals("setAddNameText", calls.first().name)
            assertEquals(listOf("setAddNameText", "getText", "<init>", APPEARANCE_APPEND, "setText"), calls.map { it.name })
            assertEquals("$ADD_NAME_VIEW->LL:Lcom/bytedance/tux/input/TuxTextView;", header.mapNotNull { it.getReference<FieldReference>() }.single().toString())
            assertTrue(header.any { it.opcode == Opcode.IF_EQ && it.namedRegisters() == listOf(0, 1) })
            val (navbar, join) = found.navbarAddName
            val mutable = MutableMethod(navbar)
            val native = mutable.implementation!!.instructions.toList()
            mutable.replaceNavbarAddName(join)
            val decorated = mutable.implementation!!.instructions.toList()
            assertEquals(22, mutable.implementation!!.registerCount)
            assertEquals(APPEARANCE_APPEND, decorated[join].getReference<MethodReference>()!!.name)
            assertEquals(listOf(2, 4), decorated[join].namedRegisters())
            native.forEachIndexed { at, instruction -> assertSame(instruction, decorated[at + if (at >= join) 2 else 0]) }
            assertEquals(1, native.take(join).count { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == "Landroid/text/SpannableStringBuilder;" })
            val cache = nicknameCachedBridge().implementation!!.instructions.toList()
            assertEquals("hasLocalNicknameText", cache.first().getReference<MethodReference>()!!.name)
            assertEquals(Opcode.RETURN, cache.last().opcode)
            assertEquals(listOf(2), cache.last().namedRegisters())
            val widget = found.classes.getValue(ADD_NAME_VIEW).methods.single { it.name == "setAddNameText" }
            assertTrue(widget.implementation!!.instructions.any { it.getReference<MethodReference>()?.name == "setSpan" })
            val gate = found.classes.getValue("LX/0OQE;").methods.single { it.name == "LIZJ" }
            assertTrue(gate.implementation!!.instructions.any { it.getReference<MethodReference>()?.name == "getNickNameModifyTs" })
            assertTrue(gate.implementation!!.instructions.any { it.getReference<StringReference>()?.string == "nickname" })
        }
    }

    @Test fun `Add name icon uses the native ReplacementSpan factory and only writes its new icon configuration`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            assertEquals("Landroid/text/style/ReplacementSpan;", found.classes.getValue(BADGE_SPAN).superclass)
            val nativeFactory = found.classes.getValue(BADGE_ICON).methods.single { it.name == "LIZIZ" }
            assertEquals(listOf("I", "Landroid/content/Context;"), nativeFactory.parameterTypes.map(CharSequence::toString))
            assertEquals(BADGE_SPAN, nativeFactory.returnType)
            assertTrue(nativeFactory.implementation!!.instructions.any { it.getReference<MethodReference>()?.toString() == "$BADGE_SPAN-><init>(Landroid/graphics/drawable/Drawable;I)V" })
            val getter = found.classes.getValue(PROFILE_PROTOCOL).commonInfoGetter()!!
            for (owner in listOf(NICKNAME_OWNER, NAVBAR_NICKNAME_OWNER)) {
                val code = nicknameAppendBridge(getter, owner).implementation!!.instructions.toList()
                val writes = code.filter { it.opcode.name.startsWith("iput") || it.opcode.name.startsWith("sput") }
                assertEquals(listOf("LIZ", "LIZIZ", "LIZJ"), writes.map { it.getReference<FieldReference>()!!.name })
                assertTrue(writes.all { it.getReference<FieldReference>()!!.definingClass == BADGE_ICON && it.namedRegisters() == listOf(0, 2) })
                assertTrue(code.takeWhile { it !== writes.first() }.any { it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == BADGE_ICON && it.namedRegisters() == listOf(2) })
                val calls = code.mapNotNull { it.getReference<MethodReference>() }
                assertEquals(2, calls.count { it.name == APPEARANCE_SELECT })
                assertTrue(calls.any { it.toString() == "$BADGE_ICON->LIZIZ(ILandroid/content/Context;)$BADGE_SPAN" })
                assertEquals(listOf("badgeText", "clearLocalNicknameText"), calls.filter { it.definingClass == LOCAL_APPEARANCE }.map { it.name })
                assertTrue(calls.none { it.definingClass.endsWith("/User;") || it.definingClass.endsWith("/UserAccountInfo;") || it.definingClass.contains("retrofit") })
            }
        }
    }

    @Test fun `navbar Add name branch without an account arrow enters the local span hook at the native join`() {
        Fixtures.forEachDeclared { apk ->
            val (original, join) = find(apk).navbarAddName
            val mutable = MutableMethod(original)
            val before = mutable.implementation!!.instructions.toList()
            val incoming = before.indices.filter { at -> before[at] is OffsetInstruction && branchIndex(before, at) == join }
            assertEquals(1, incoming.size)
            assertEquals(Opcode.IF_EQZ, before[incoming.single()].opcode)
            assertEquals(listOf(17), before[incoming.single()].namedRegisters())
            mutable.replaceNavbarAddName(join)
            val after = mutable.implementation!!.instructions.toList()
            for (at in incoming) {
                val target = branchIndex(after, at)
                assertEquals(join, target)
                assertEquals(APPEARANCE_APPEND, after[target].getReference<MethodReference>()!!.name)
            }
            assertEquals(APPEARANCE_APPEND, after[join].getReference<MethodReference>()!!.name)
            assertEquals("LJLJLLL", after[join + 2].getReference<MethodReference>()!!.name)
        }
    }

    @Test fun `changed navbar resource captures Add name joins or callback arguments fail closed`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val (original, site) = found.navbar
            val wrongCapture = MutableMethod(original).apply { replaceInstruction(site.capture,
                "invoke-direct/range { v5 .. v11 }, $NAVBAR_NICKNAME_REPAINT-><init>(Lcom/bytedance/tux/input/TuxTextView;LX/02xT;$NAVBAR_NICKNAME_OWNER${INTEGER}I$STRING)V") }
            assertNull(wrongCapture.navbarAppearanceSite())
            assertThrows(PatchException::class.java) { wrongCapture.replaceNavbarAppearance(site) }
            val wrongBox = MutableMethod(original).apply { replaceInstruction(site.render, "invoke-virtual { v11 }, $INTEGER->intValue()I") }
            assertNull(wrongBox.navbarAppearanceSite())
            val (name, join) = found.navbarAddName
            val wrongJoin = MutableMethod(name).apply { replaceInstruction(join - 1, "nop") }
            assertNull(wrongJoin.navbarAddNameSite())
            assertThrows(PatchException::class.java) { wrongJoin.replaceNavbarAddName(join) }
            val wrongAdd = MutableMethod(found.nickname.first).apply { replaceInstruction(found.nickname.first.nicknameAddNameSite()!!,
                "invoke-virtual { v0, v3 }, $ADD_NAME_VIEW->setAddNameText($STRING)V") }
            assertNull(wrongAdd.nicknameAddNameSite())
            val callback = MutableMethod(found.navbarAddRepaint).apply { replaceInstruction(0, "nop") }
            assertThrows(PatchException::class.java) { callback.guardNicknameRepaint() }
        }
    }

    @Test fun `the original nickname renderers and warning refresh remain native`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val owner = found.classes.getValue(NICKNAME_OWNER)
            val render = owner.methods.single { it.name == "LLII" }
            assertEquals(listOf("I", "I", STRING), render.parameterTypes.map(CharSequence::toString))
            assertTrue(AccessFlags.PUBLIC.isSet(render.accessFlags))
            assertTrue(render.implementation!!.instructions.any { it.getReference<MethodReference>()?.name == "LJJLIIIJLLLLLLLZ" })
            val nullableRender = owner.methods.single { it.name == "LJJLIIIJLLLLLLLZ" }
            assertEquals(listOf("I", INTEGER, STRING), nullableRender.parameterTypes.map(CharSequence::toString))
            assertTrue(nullableRender.implementation!!.instructions.any { it.opcode == Opcode.IF_EQZ && it.namedRegisters() == listOf(22) })
            val refresh = found.classes.getValue("LX/0PUq;").methods.single { it.name == "run" }
            assertTrue(refresh.implementation!!.instructions.any { it.getReference<MethodReference>()?.toString() == "$RESTRICTION_OWNER->LJJLIIIJLLLLLLLZ(Landroid/view/View;)V" })
        }
    }

    @Test fun `unowned owners static methods and changed frames cannot masquerade as presentation sites`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            for ((original, select) in listOf<Pair<Method, (Method) -> Boolean>>(
                found.legacy.first to { it.legacyAppearanceSite() != null },
                found.nickname.first to { it.nicknameAppearanceSite() != null },
                found.repaint.first to { it.nicknameRepaintSite() != null },
                found.warnings.first().first to { it.noticeAppearanceSites().isNotEmpty() })) {
                assertFalse(select(copy(original, owner = "Lfixture/Unowned;")))
                assertFalse(select(copy(original, flags = original.accessFlags or AccessFlags.STATIC.value)))
                assertFalse(select(copy(original, registers = original.implementation!!.registerCount + 1)))
            }
        }
    }

    @Test fun `wrong badge join capture and repaint argument are rejected before mutation`() {
        Fixtures.forEachDeclared { apk ->
            val found = find(apk)
            val (original, site) = found.nickname
            val wrongCapture = MutableMethod(original).apply {
                replaceInstruction(site.capture, "invoke-direct { v0, v9, p0, v6, v4 }, $NICKNAME_REPAINT-><init>(I$NICKNAME_OWNER$INTEGER$STRING)V")
            }
            assertNull(wrongCapture.nicknameAppearanceSite())
            val before = wrongCapture.implementation!!.instructions.toList()
            assertThrows(PatchException::class.java) { wrongCapture.replaceNicknameAppearance(site) }
            assertEquals(before, wrongCapture.implementation!!.instructions.toList())
            val wrongBox = MutableMethod(original).apply { replaceInstruction(site.render, "invoke-virtual { v6 }, $INTEGER->intValue()I") }
            assertNull(wrongBox.nicknameAppearanceSite())
            val (repaint, index) = found.repaint
            val wrongRepaint = MutableMethod(repaint).apply {
                replaceInstruction(index + 3, "invoke-virtual { v2, v0, v1, v3 }, $NICKNAME_OWNER->LLII(II$STRING)V")
            }
            assertNull(wrongRepaint.nicknameRepaintSite())
            assertThrows(PatchException::class.java) { wrongRepaint.replaceNicknameRepaint(index) }
        }
    }

    @Test fun `a native hidden call or a value without VISIBLE provenance cannot be hooked`() {
        Fixtures.forEachDeclared { apk ->
            for ((original, site) in find(apk).warnings) {
                val changed = MutableMethod(original).apply {
                    replaceInstruction(site.index, "invoke-static { v${site.view}, v${site.visibility} }, $NOTICE_VISIBILITY")
                }
                assertTrue(changed.noticeAppearanceSites().isEmpty())
                val before = changed.implementation!!.instructions.toList()
                assertThrows(PatchException::class.java) { changed.replaceNoticeAppearance(site) }
                assertEquals(before, changed.implementation!!.instructions.toList())
            }
        }
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            val wanted = setOf(LEGACY_NICKNAME_OWNER, NICKNAME_OWNER, NICKNAME_REPAINT, RESTRICTION_OWNER,
                NAVBAR_NICKNAME_OWNER, NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT, NICKNAME_DATA,
                ADD_NAME_VIEW, BADGE_ICON, BADGE_SPAN, PROFILE_PROTOCOL, PROFILE_COMMON_INFO, PROFILE_INFO,
                BASE_UI_COMPONENT, "LX/0PUq;", "LX/0OQE;", "LX/03sU;", "LX/05F3;")
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames
                .flatMap { container.getEntry(it)!!.dexFile.classes.filter { clazz -> clazz.type in wanted } }.associateBy { it.type }
            val legacy = classes.getValue(LEGACY_NICKNAME_OWNER).methods.mapNotNull { method -> method.legacyAppearanceSite()?.let { method to it } }.single()
            val nickname = classes.getValue(NICKNAME_OWNER).methods.mapNotNull { method -> method.nicknameAppearanceSite()?.let { method to it } }.single()
            val repaint = classes.getValue(NICKNAME_REPAINT).methods.mapNotNull { method -> method.nicknameRepaintSite()?.let { method to it } }.single()
            val warnings = classes.getValue(RESTRICTION_OWNER).methods.flatMap { method -> method.noticeAppearanceSites().map { method to it } }
            assertEquals(2, warnings.size)
            Found(classes, legacy, nickname, repaint, warnings)
        }
    }

    /** The badge the extension hands TikTok for the own profile, a bare resource id that moves with each build. */
    private fun extensionBadge(): Int {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val source = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/profile/LocalProfileAppearance.java")
        return checkNotNull(Regex("""VERIFIED_BADGE = 0x([0-9a-f]{8});""").find(source.readText())) {
            "LocalProfileAppearance no longer declares VERIFIED_BADGE"
        }.groupValues[1].toLong(16).toInt()
    }

    private fun addressIndex(code: List<Instruction>, address: Int): Int =
        code.runningFold(0) { at, instruction -> at + instruction.codeUnits }.indexOf(address)

    private fun branchIndex(code: List<Instruction>, index: Int) = addressIndex(code,
        code.take(index).sumOf { it.codeUnits } + (code[index] as OffsetInstruction).codeOffset)

    private fun copy(method: Method, owner: String = method.definingClass, flags: Int = method.accessFlags,
        registers: Int = method.implementation!!.registerCount): Method = ImmutableMethod(owner, method.name,
        method.parameters, method.returnType, flags, null, null,
        ImmutableMethodImplementation(registers, method.implementation!!.instructions, null, null))
}
