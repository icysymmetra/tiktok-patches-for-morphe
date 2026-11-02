package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the start page asks, held to every fixture, and the tags it answers with, held to the
 * handlers TikTok runs for them.
 *
 * <p>A cold start works out its first tab in the main activity's onCreate and every path meets at
 * a comparison with "HOME". The hook goes right after that string, reads the tag and the activity
 * from the registers TikTok itself uses there, and passes the saved state from p1. The tags it
 * can answer with are the ones TikTok's own notification handlers for Friends, Inbox and Profile
 * compare with, so those comparisons are held here too.
 */
class StartPageAnchorsTest {
    @Test
    fun `the first tab is worked out in one place on every fixture, and the hook's registers reach it`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> ColdStartTabFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val onCreate = taken.single().second
            val start = onCreate.coldStartTab()!!
            val instructions = onCreate.implementation!!.instructions.toList()
            assertEquals("${apk.name}: the hook goes after the one \"HOME\"", "HOME",
                (instructions[start.insertAt - 1] as ReferenceInstruction).reference.let { (it as StringReference).string })
            assertEquals("${apk.name}: \"HOME\" appears once", 1, instructions.count {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "HOME"
            })
            val savedState = onCreate.implementation!!.registerCount - 1
            assertTrue("${apk.name}: the activity v${start.activity}, the tag v${start.tag} or p1 (v$savedState) is past v15",
                maxOf(start.activity, start.tag, savedState) <= 15)
            assertTrue("${apk.name}: nothing may write p1 before the hook reads it", instructions.take(start.insertAt).none {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA?.let { written ->
                    written == savedState || (it.opcode.setsWideRegister() && written + 1 == savedState)
                } == true
            })
        }
    }

    @Test
    fun `the account service the start page reads by name is there on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val manager = build.byType["Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;"]
            assertTrue("${apk.name}: no ServiceManager", manager != null)
            assertTrue("${apk.name}: ServiceManager has no static get()", manager!!.methods.any {
                it.name == "get" && it.parameterTypes.isEmpty() && AccessFlags.STATIC.isSet(it.accessFlags)
            })
            assertTrue("${apk.name}: ServiceManager has no getService(Class)", manager.methods.any {
                it.name == "getService" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Class;")
            })
            val account = build.byType["Lcom/ss/android/ugc/aweme/IAccountUserService;"]
            assertTrue("${apk.name}: no IAccountUserService", account != null)
            assertTrue("${apk.name}: IAccountUserService has no isLogin()Z", account!!.methods.any {
                it.name == "isLogin" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            })
        }
    }

    @Test
    fun `TikTok's handlers for Friends, Inbox and Profile compare with the tags the start page answers with`() {
        val handlers = mapOf(
            "Lcom/ss/android/ugc/aweme/assem/FriendsChangeTabInterceptor;" to listOf("FRIENDS_FEED"),
            "Lcom/ss/android/ugc/aweme/inbox/InboxChangeTabInterceptor;" to listOf("NOTIFICATION", "HOME"),
            "Lcom/ss/android/ugc/profile/platform/framework/aweme/profile/ProfileChangeTabInterceptor;" to listOf("USER", "HOME"),
        )
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            for ((type, tags) in handlers) {
                val handler = build.byType[type]
                assertTrue("${apk.name}: $type is gone", handler != null)
                val decide = handler!!.methods.single { method ->
                    val parameters = method.parameterTypes.map(CharSequence::toString)
                    method.returnType == "Z" && parameters.size == 4 &&
                        parameters[1] == "Landroid/content/Intent;" && parameters[2] == "Ljava/lang/String;"
                }
                val strings = decide.implementation!!.instructions.strings()
                tags.forEach { assertTrue("${apk.name}: $type no longer compares with $it: $strings", it in strings) }
            }
            // The Friends feed's handler goes through the Friends bottom tab when there is one.
            val friends = build.byType.getValue("Lcom/ss/android/ugc/aweme/assem/FriendsChangeTabInterceptor;")
            val friendsStrings = friends.methods.flatMap { it.implementation?.instructions?.strings().orEmpty() }
            assertTrue("${apk.name}: the Friends handler never names FRIENDS_TAB", "FRIENDS_TAB" in friendsStrings)
        }
    }

    @Test
    fun `the patch asks the start page right after the comparison and keeps its answer`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/navigation/FeedTabNavigationPatch.kt").readText()
        assertTrue("the first tab is not hooked", source.contains("ColdStartTabFingerprint.method.apply"))
        assertTrue("the hook does not hand over the activity, the tag and the saved state",
            source.contains("invoke-static {v\${start.activity}, v\${start.tag}, p1}, \$START_PAGE_CLASS_DESCRIPTOR->" +
                "coldStartTag(Landroid/app/Activity;Ljava/lang/String;Landroid/os/Bundle;)Ljava/lang/String;"))
        assertTrue("the answer does not replace the tag", source.contains("move-result-object v\${start.tag}"))
        assertTrue("the hook does not go where the helper says", source.contains("start.insertAt,"))
    }

    @Test
    fun `a synthetic onCreate shaped like TikTok's gives the registers TikTok uses`() {
        val start = onCreate().coldStartTab()
        assertTrue("the synthetic onCreate was not read", start != null)
        assertEquals(2, start!!.insertAt)
        assertEquals(4, start.tag)
        assertEquals(7, start.activity)
    }

    @Test
    fun `a jump onto the comparison, a restore of another register or a comparison of another tag is refused`() {
        assertNull("a jump lands on the comparison, so the hook would be skipped", onCreate(ifOffset = -4).coldStartTab())
        assertNull("the theme restore gets another register", onCreate(restoreRegister = 8).coldStartTab())
        assertNull("the comparison reads another register", onCreate(comparedRegister = 5).coldStartTab())
        assertNull("the switch is not told COLD_BOOT", onCreate(bootField = "ON_NEW_INTENT").coldStartTab())
    }

    @Test
    fun `a write into the saved state's register before the ask is refused, a wide one included`() {
        assertTrue("the plain onCreate is read", onCreate().coldStartTab() != null)
        assertNull("p1 (v15) written", onCreate(savedStateWrite = ImmutableInstruction11x(Opcode.MOVE_RESULT, 15)).coldStartTab())
        assertNull("v14 and v15 written as one wide value",
            onCreate(savedStateWrite = ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 14)).coldStartTab())
        assertTrue("v13 written wide leaves p1 alone",
            onCreate(savedStateWrite = ImmutableInstruction11x(Opcode.MOVE_RESULT_WIDE, 13)).coldStartTab() != null)
    }

    /**
     * TikTok's shape in miniature: the tag in v4, the activity in v7, the join's "HOME" in v0, the
     * theme restore, then getIntent and the cold-boot switch. The if-nez at index 4 skips the
     * restore by default (+5 code units, to getIntent).
     */
    private fun onCreate(
        ifOffset: Int = 5,
        restoreRegister: Int = 7,
        comparedRegister: Int = 4,
        bootField: String = "COLD_BOOT",
        savedStateWrite: Instruction? = null,
    ): Method {
        val assem = "LX/Assem;"
        val boot = "LX/Boot;"
        val instructions = listOfNotNull(savedStateWrite) + listOf<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 4, ImmutableStringReference("SHOP_MALL")),
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("HOME")),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, comparedRegister, 0, 0, 0,
                ImmutableMethodReference("LX/Kt;", "eq", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Z")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
            ImmutableInstruction21t(Opcode.IF_NEZ, 0, ifOffset),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, restoreRegister, 6, 0, 0, 0,
                ImmutableMethodReference("LX/Theme;", "restore", listOf("LX/Act;", "Z"), "V")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0,
                ImmutableMethodReference("Landroid/app/Activity;", "getIntent", listOf(), "Landroid/content/Intent;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(boot, bootField, boot)),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 4, 14, 1, 0, 4, 0,
                ImmutableMethodReference(assem, "switchTab", listOf("Landroid/content/Intent;", boot, "Ljava/lang/String;"), "V")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        return ImmutableMethod(
            assem, "onCreate", listOf(ImmutableMethodParameter("Landroid/os/Bundle;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(16, instructions, null, null),
        )
    }

    @Test
    fun `the home pager picks its first feed tab in one place on every fixture, where a jump lands`() {
        val switchShape = listOf("Landroid/os/Bundle;", "Ljava/lang/String;", "Z")
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> FirstTopTabFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val pager = taken.single().second
            val first = pager.firstTopTab()!!
            val instructions = pager.implementation!!.instructions.toList()
            val switch = (instructions[first.switchAt] as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the first tab goes through Hox", HOX, switch.definingClass)
            assertTrue("${apk.name}: no jump lands on the switch any more, so a plain insert would do",
                first.switchAt in pager.branchTargets())
            val switches = instructions.count { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                    it.definingClass == HOX && it.parameterTypes.map(CharSequence::toString) == switchShape
                } == true
            }
            assertEquals("${apk.name}: teen mode's own switch to For You and everyone else's", 2, switches)
        }
    }

    @Test
    fun `the patch asks the start page on the first feed tab's switch and keeps its answer`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/navigation/FeedTabNavigationPatch.kt").readText()
        assertTrue("the first feed tab is not hooked", source.contains("FirstTopTabFingerprint.method.apply"))
        assertTrue("the ask does not take the switch's label", source.contains("addInstructionsAtControlFlowLabel(\n                first.switchAt,"))
        assertTrue("the ask does not hand over the tag", source.contains(
            "invoke-static/range {v\${first.tag} .. v\${first.tag}}, \$START_PAGE_CLASS_DESCRIPTOR->firstTopTab(Ljava/lang/String;)Ljava/lang/String;"))
        assertTrue("the answer does not replace the tag", source.contains("move-result-object v\${first.tag}"))
    }

    @Test
    fun `a synthetic pager shaped like TikTok's gives the switch and the tag's register`() {
        val first = pagerView().firstTopTab()
        assertTrue("the synthetic pager was not read", first != null)
        assertEquals(5, first!!.switchAt)
        assertEquals(2, first.tag)
    }

    @Test
    fun `an answer moved elsewhere, a tag without For You, a far switch or another switch is refused`() {
        assertNull("the service's answer lands in another register", pagerView(movedInto = 3).firstTopTab())
        assertNull("the answer moved is not the service's", pagerView(movedFrom = 5).firstTopTab())
        assertNull("For You is loaded into another register", pagerView(defaultRegister = 3).firstTopTab())
        assertNull("the switch sits too far from the service", pagerView(padding = 2).firstTopTab())
        assertNull("the switch takes other arguments",
            pagerView(switchParameters = listOf("Landroid/os/Bundle;", "Ljava/lang/String;")).firstTopTab())
    }

    @Test
    fun `the home pager's default page reads TikTok's Following preference in one place on every fixture`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> DefaultPageFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val page = taken.single().second
            val choice = page.followTabChoice()!!
            val instructions = page.implementation!!.instructions.toList()
            val after = instructions.drop(choice.insertAt).take(8)
            assertTrue("${apk.name}: the preference's answer is not tested right after the read",
                after.any { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == choice.register })
            val followingLookup = after.withIndex().any { (offset, instruction) ->
                val lookup = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                instruction.opcode == Opcode.INVOKE_INTERFACE && lookup?.returnType == "I" &&
                    lookup.parameterTypes.map(CharSequence::toString) == listOf("I", "Z") &&
                    after.take(offset).lastOrNull {
                        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA ==
                            (instruction as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction).registerD
                    }?.let { it.opcode == Opcode.CONST_4 && (it as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral == 1 } == true
            }
            assertTrue("${apk.name}: the preference no longer looks up Following's tab type, 1", followingLookup)

            val readers = build.byType.getValue(HOME_VIEW_PAGER_ASSEM).methods.filter { it.followTabChoice() != null }
            assertEquals("${apk.name}: the default page and its sibling for reports read the preference", 2, readers.size)
            val callers = build.methods.filter { (_, method) ->
                method.implementation?.instructions?.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                        it.name == page.name && it.returnType == "I" && it.parameterTypes.isEmpty() &&
                            (it.definingClass == HOME_VIEW_PAGER_ASSEM || it.definingClass.endsWith("/HomeViewPagerAbility;"))
                    } == true
                } == true
            }.map { it.first.type }.toSet()
            assertTrue("${apk.name}: the default page has callers $callers", callers.size >= 2)
        }
    }

    @Test
    fun `the patch answers the Following preference right after the pager reads it`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/navigation/FeedTabNavigationPatch.kt").readText()
        assertTrue("the default page is not hooked", source.contains("DefaultPageFingerprint.method.apply"))
        assertTrue("the answer is not asked where the helper says", source.contains("choice.insertAt,"))
        assertTrue("the ask does not hand over the preference", source.contains(
            "invoke-static/range {v\${choice.register} .. v\${choice.register}}, \$START_PAGE_CLASS_DESCRIPTOR->followingFirst(Z)Z"))
        assertTrue("the answer does not replace the preference", source.contains("move-result v\${choice.register}"))
    }

    @Test
    fun `a synthetic default page gives the register the preference lands in`() {
        val choice = defaultPage().followTabChoice()
        assertTrue("the synthetic default page was not read", choice != null)
        assertEquals(7, choice!!.insertAt)
        assertEquals(1, choice.register)
    }

    @Test
    fun `a preference left boxed, a jump onto the ask or no read at all is refused`() {
        assertNull("the answer is never moved into a register", defaultPage(boxedResult = true).followTabChoice())
        assertNull("a jump lands right after the read, so the ask would be skipped", defaultPage(jumpBack = true).followTabChoice())
        assertNull("the preference is not read", defaultPage(getter = "getIsSomethingElse").followTabChoice())
    }

    /**
     * TikTok's read in miniature: the preference's holder from SharePrefCache, its value, the
     * Boolean unboxed into v1, then Following's type loaded and v1 tested.
     */
    private fun defaultPage(boxedResult: Boolean = false, jumpBack: Boolean = false, getter: String = "getIsChangeFollowTab"): Method {
        val holder = "LX/05fq;"
        val instructions = mutableListOf<Instruction>(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(SHARE_PREF_CACHE, getter, listOf(), holder)),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(holder, "LIZ", listOf(), "Ljava/lang/Object;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.CHECK_CAST, 0, ImmutableTypeReference("Ljava/lang/Boolean;")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference("Ljava/lang/Boolean;", "booleanValue", listOf(), "Z")),
            ImmutableInstruction11x(if (boxedResult) Opcode.MOVE_RESULT_OBJECT else Opcode.MOVE_RESULT, 1),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction21t(Opcode.IF_EQZ, 1, 2),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        // Code units: the const sits at 14 and the goto would sit at 18.
        if (jumpBack) instructions += ImmutableInstruction10t(Opcode.GOTO, -4)
        return ImmutableMethod(
            HOME_VIEW_PAGER_ASSEM, "fG0", listOf(), "I",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(4, instructions, null, null),
        )
    }

    /**
     * TikTok's shape in miniature: "For You" in v2, the home page service's answer in v0, moved
     * into v2 when there is one, then Hox's switch on v1 with the bundle in v4, the tag in v2 and
     * the flag in v10. The if-eqz jumps past the move straight onto the switch.
     */
    private fun pagerView(
        movedInto: Int = 2,
        movedFrom: Int = 0,
        defaultRegister: Int = 2,
        padding: Int = 0,
        switchParameters: List<String> = listOf("Landroid/os/Bundle;", "Ljava/lang/String;", "Z"),
    ): Method {
        val instructions = mutableListOf<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, defaultRegister, ImmutableStringReference("For You")),
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0,
                ImmutableMethodReference(HOME_PAGE_EX_SERVICE, "LIZLLL", listOf(), "Ljava/lang/String;")),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 3 + padding),
            ImmutableInstruction12x(Opcode.MOVE_OBJECT, movedInto, movedFrom),
        )
        repeat(padding) { instructions += ImmutableInstruction10x(Opcode.NOP) }
        instructions += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, switchParameters.size + 1, 1, 4, 2, 10, 0,
            ImmutableMethodReference(HOX, "d73", switchParameters, "V"))
        instructions += ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableMethod(
            HOME_VIEW_PAGER_ASSEM, "onViewCreated", listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)), "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(16, instructions, null, null),
        )
    }

    private fun Iterable<Instruction>.strings(): List<String> =
        mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
