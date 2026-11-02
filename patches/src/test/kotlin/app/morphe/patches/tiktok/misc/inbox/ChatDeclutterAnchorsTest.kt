/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TITLE_BAR = "Lcom/ss/android/ugc/aweme/im/chatroom/common/titlebar/BaseSingleChatTitleBarRightAssem;"
private const val TUX = "Lcom/bytedance/tux/icon/TuxIconView;"
private const val CONTROLS = "Lapp/morphe/extension/tiktok/inbox/InboxControls;"
private const val CHAT_TITLE_BAR = "Lapp/morphe/extension/tiktok/inbox/ChatTitleBar;"
private const val CHAT_GESTURES = "Lapp/morphe/extension/tiktok/inbox/ChatGestures;"
private const val SKELETON = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/base/assems/base/SkeletonLayoutAssembler;"

/**
 * The chat switches in Hide inbox items: the title bar's two call buttons, the sticker banner,
 * the suggested reply cells and their intro, and a message's double tap and swipe. Each anchor
 * is held to every declared build, and each hook is applied to the real method it would patch.
 */
class ChatDeclutterAnchorsTest {
    private val gates = listOf(
        Triple(ChatStickerBannerEnabledFingerprint, "PRESHOWN_STICKER_BANNER_PROTOCOL", "shouldShowChatStickerBanner"),
        Triple(ChatSuggestedReplyEnabledFingerprint, "UNIFIED_SUGGESTED_REPLY_MSG", "shouldShowChatAiReplies"),
        Triple(ChatSmartReplyIntroEnabledFingerprint, "SMART_REPLY_ENTRANCE_CARD", "shouldShowChatAiReplies"),
    )

    @Test
    fun `each enable check is one method that answers true, and the gate wraps it on every build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = gates.associate { it.first to mutableListOf<Method>() }
            for (classDef in allClasses(apk)) {
                for ((fingerprint) in gates) {
                    classDef.methods.filterTo(taken.getValue(fingerprint)) { fingerprint.takes(it, classDef) }
                }
            }
            for ((fingerprint, owner, callback) in gates) {
                assertEquals("$version: $owner enable checks matched", 1, taken.getValue(fingerprint).size)
                val native = taken.getValue(fingerprint).single()
                assertTrue("$version: $owner is not a bare answer of true", answersTrue(native))

                val gated = MutableMethod(native)
                gated.hideInboxWidget(callback)
                val code = gated.implementation!!.instructions.toList()
                assertEquals(
                    "$version: $owner opcodes",
                    listOf(
                        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.RETURN,
                        Opcode.CONST_4, Opcode.RETURN,
                    ),
                    code.map { it.opcode },
                )
                val call = code[0].getReference<MethodReference>()!!
                assertEquals(CONTROLS, call.definingClass)
                assertEquals(callback, call.name)
                assertEquals("Z", call.returnType)
                assertTrue(call.parameterTypes.isEmpty())
                // TikTok's own answer sits behind the gate, untouched.
                native.implementation!!.instructions.toList().forEachIndexed { index, original ->
                    assertEquals(original.opcode, code[index + 4].opcode)
                }
            }
        }
    }

    @Test
    fun `each protocol constant is registered by exactly one message list protocol on every build`() {
        val constants = gates.map { it.second }
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val protocols = allClasses(apk).filter { MESSAGE_LIST_PROTOCOL in it.interfaces }.toList()
            assertTrue("$version: no message list protocols", protocols.size > constants.size)
            for (constant in constants) {
                val owners = protocols.filter { it.registersMessageListProtocol(constant) }.map { it.type }
                assertEquals("$version: protocols registering $constant", 1, owners.size)
            }
        }
    }

    @Test
    fun `the title bar stores exactly two call buttons on every build and each is handed over before its store`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classDef = classesOf(apk, setOf(TITLE_BAR))[TITLE_BAR] ?: error("$version: no title bar")
            val taken = classDef.methods.filter { ChatTitleBarRightBindFingerprint.takes(it, classDef) }
            assertEquals("$version: title bar binds matched", 1, taken.size)

            val bind = MutableMethod(taken.single())
            val before = bind.implementation!!.instructions.toList()
            val stores = bind.callButtonStores()
            assertEquals("$version: call button stores", EXPECTED_CALL_BUTTONS, stores.size)
            val sources = stores.map { (before[it] as OneRegisterInstruction).registerA }

            bind.hideCallButtonsAtBind()
            val after = bind.implementation!!.instructions.toList()
            assertEquals(before.size + 2, after.size)
            // Each store is now preceded by the hand over of the register it stores.
            val newStores = bind.callButtonStores()
            assertEquals(stores.size, newStores.size)
            newStores.forEachIndexed { n, index ->
                assertEquals(Opcode.IPUT_OBJECT, after[index].opcode)
                val call = after[index - 1]
                assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
                val range = call as RegisterRangeInstruction
                assertEquals(1, range.registerCount)
                assertEquals("$version: hand over register", sources[n], range.startRegister)
                val target = call.getReference<MethodReference>()!!
                assertEquals(CHAT_TITLE_BAR, target.definingClass)
                assertEquals("hideCallButton", target.name)
                assertEquals(listOf("Landroid/view/View;"), target.parameterTypes.map(CharSequence::toString))
                assertEquals("V", target.returnType)
            }
            // Nothing else moved: dropping the two calls gives back TikTok's own bind.
            val kept = after.filterIndexed { i, _ -> newStores.none { it - 1 == i } }
            assertEquals(before.size, kept.size)
            before.indices.forEach { assertEquals(before[it].opcode, kept[it].opcode) }
        }
    }

    @Test
    fun `a title bar that does not store two icon views stops the patch`() {
        for (count in listOf(0, 1, 3)) {
            val bind = bind(List(count) { stored(it) })
            val failure = assertThrows(PatchException::class.java) { bind.hideCallButtonsAtBind() }
            assertTrue(failure.message!!.contains("stores $count icon views"))
        }
    }

    @Test
    fun `only icon views stored in the title bar's own fields count`() {
        val otherType = ImmutableInstruction22c(
            Opcode.IPUT_OBJECT, 2, 3, ImmutableFieldReference(TITLE_BAR, "x", "Landroid/view/View;"),
        )
        val otherClass = ImmutableInstruction22c(
            Opcode.IPUT_OBJECT, 2, 3, ImmutableFieldReference("Lcom/example/Other;", "y", TUX),
        )
        val bind = bind(listOf(stored(0), otherType, stored(1), otherClass))
        // The cast sits at 0, so the stores are at 1 and 3.
        assertEquals(listOf(1, 3), bind.callButtonStores())
        bind.hideCallButtonsAtBind()
        assertEquals(8, bind.implementation!!.instructions.count())
    }

    @Test
    fun `the hand over is a range call over the stored register`() {
        val first = ImmutableInstruction22c(Opcode.IPUT_OBJECT, 14, 15, ImmutableFieldReference(TITLE_BAR, "a", TUX))
        val second = ImmutableInstruction22c(Opcode.IPUT_OBJECT, 2, 15, ImmutableFieldReference(TITLE_BAR, "b", TUX))
        val bind = bind(listOf(first, second), registers = 16)
        bind.hideCallButtonsAtBind()
        val code = bind.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[1].opcode)
        assertEquals(14, (code[1] as RegisterRangeInstruction).startRegister)
        assertEquals(Opcode.IPUT_OBJECT, code[2].opcode)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[3].opcode)
        assertEquals(2, (code[3] as RegisterRangeInstruction).startRegister)
    }

    @Test
    fun `the gesture dispatcher is one method on every build and each switch stops only its own gesture's branch`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = gestureDispatchers(apk)
            assertEquals("$version: gesture dispatchers matched", 1, taken.size)
            val native = taken.single()
            val register = chatGestureRegister(native)
            assertEquals("$version: gesture register", native.implementation!!.registerCount - 1, register)
            val code = native.implementation!!.instructions.toList()

            val gestureType = native.parameterTypes.last().toString()
            val mapType = code.first().getReference<FieldReference>()!!.definingClass
            val classes = classesOf(apk, setOf(gestureType, mapType))
            val gesture = classes[gestureType] ?: error("$version: no $gestureType")
            assertEquals("$version: the gesture is not an enum", "Ljava/lang/Enum;", gesture.superclass)
            // ChatGestures reads Enum.name(), the string each constant is built with.
            assertEquals(
                "$version: gesture constants",
                listOf("SINGLE_TAP", "DOUBLE_TAP", "LONG_PRESS", "SWIPE").associateWith { it },
                enumNames(gesture),
            )
            val values = switchValues(classes[mapType] ?: error("$version: no $mapType"), gestureType)
            val doubleTap = branchCasts(code, values.getValue("DOUBLE_TAP"))
            val swipe = branchCasts(code, values.getValue("SWIPE"))
            assertTrue("$version: double tap branch $doubleTap", doubleTap.any { it.endsWith("/IMPendingReactionViewModel;") })
            assertTrue("$version: double tap branch $doubleTap", doubleTap.none { it.endsWith("/MessageReplyVM;") })
            assertTrue("$version: swipe branch $swipe", swipe.any { it.endsWith("/MessageReplyVM;") })
            assertTrue("$version: swipe branch $swipe", swipe.none { it.endsWith("/IMPendingReactionViewModel;") })

            val gated = MutableMethod(native)
            gated.skipChatGestures(register)
            val after = gated.implementation!!.instructions.toList()
            assertEquals(
                listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                after.take(4).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(CHAT_GESTURES, call.definingClass)
            assertEquals("skip", call.name)
            assertEquals(listOf("Ljava/lang/Enum;"), call.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", call.returnType)
            val invoke = after[0] as FiveRegisterInstruction
            assertEquals(1, invoke.registerCount)
            assertEquals("$version: the gesture handed over", register, invoke.registerC)
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals(0, (after[2] as OneRegisterInstruction).registerA)
            // With the switch off the dispatcher runs from its own first instruction, untouched.
            assertEquals(4, (after[2] as BuilderOffsetInstruction).target.location.index)
            assertEquals(code.size + 4, after.size)
            code.forEachIndexed { index, original -> assertEquals(original.opcode, after[index + 4].opcode) }
        }
    }

    @Test
    fun `a dispatcher whose gesture can't be read beside a free local stops the patch`() {
        fun dispatcher(
            registers: Int,
            parameters: List<String> = listOf("LX/a;", "LX/b;", "LX/c;"),
            flags: Int = AccessFlags.PUBLIC.value,
        ) = ImmutableMethod(
            SKELETON, "ol0", parameters.map { ImmutableMethodParameter(it, null, null) }, "V", flags, null, null,
            ImmutableMethodImplementation(registers, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null),
        )
        assertEquals(12, chatGestureRegister(dispatcher(13)))
        assertEquals(15, chatGestureRegister(dispatcher(16)))
        assertEquals(5, chatGestureRegister(dispatcher(6, listOf("J", "LX/b;", "LX/c;"))))
        val broken = listOf(
            dispatcher(17), // the gesture sits past v15
            dispatcher(4), // no local is left for the answer
            dispatcher(5, listOf("J", "LX/b;", "LX/c;")), // nor here, with a wide argument
            dispatcher(13, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value),
            dispatcher(13, listOf("LX/a;", "LX/b;")),
            dispatcher(13, listOf("LX/a;", "LX/b;", "I")),
        )
        broken.forEach { assertThrows(PatchException::class.java) { chatGestureRegister(it) } }
    }

    private fun gestureDispatchers(apk: java.io.File): List<Method> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.flatMap { entry ->
            container.getEntry(entry)!!.dexFile.classes
                .filter { it.type.endsWith("/SkeletonLayoutAssembler;") }
                .flatMap { classDef -> classDef.methods.filter { ChatMessageGestureFingerprint.takes(it, classDef) } }
        }
    }

    /** Each constant's field and the name string its constructor gets, from the enum's static initializer. */
    private fun enumNames(enum: ClassDef): Map<String, String?> {
        val names = LinkedHashMap<String, String?>()
        var last: String? = null
        for (instruction in enum.methods.single { it.name == "<clinit>" }.implementation!!.instructions) {
            instruction.getReference<StringReference>()?.let { last = it.string }
            val field = instruction.getReference<FieldReference>()
            if (instruction.opcode == Opcode.SPUT_OBJECT && field != null && field.type == enum.type) names[field.name] = last
        }
        return names
    }

    /** The value a when-mapping stores for each constant: a read of the constant, then the literal stored for it. */
    private fun switchValues(map: ClassDef, enumType: String): Map<String, Int> {
        val values = HashMap<String, Int>()
        var pending: String? = null
        for (instruction in map.methods.single { it.name == "<clinit>" }.implementation!!.instructions) {
            val field = instruction.getReference<FieldReference>()
            if (instruction.opcode == Opcode.SGET_OBJECT && field != null && field.definingClass == enumType) {
                pending = field.name
            } else if (pending != null && (instruction.opcode == Opcode.CONST_4 || instruction.opcode == Opcode.CONST_16)) {
                values[pending] = (instruction as NarrowLiteralInstruction).narrowLiteral
                pending = null
            }
        }
        return values
    }

    /**
     * The types the dispatcher casts to where its chain of value tests sends [value], up to that
     * branch's return or throw.
     */
    private fun branchCasts(code: List<Instruction>, value: Int): List<String> {
        val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
        val literals = HashMap<Int, Int>()
        var switched = -1
        var at = -1
        for ((index, instruction) in code.withIndex()) {
            if (instruction.opcode == Opcode.AGET) {
                switched = (instruction as OneRegisterInstruction).registerA
                continue
            }
            if (switched < 0) continue
            if (instruction.opcode == Opcode.CONST_4) {
                literals[(instruction as OneRegisterInstruction).registerA] =
                    (instruction as NarrowLiteralInstruction).narrowLiteral
                continue
            }
            if (instruction.opcode != Opcode.IF_EQ && instruction.opcode != Opcode.IF_NE) {
                at = index
                break
            }
            val test = instruction as TwoRegisterInstruction
            if (test.registerA != switched) {
                at = index
                break
            }
            val equal = literals[test.registerB] == value
            if (equal == (instruction.opcode == Opcode.IF_EQ)) {
                at = addresses.indexOf(addresses[index] + (instruction as OffsetInstruction).codeOffset)
                break
            }
        }
        check(at >= 0) { "the value tests for $value lead nowhere" }
        val casts = ArrayList<String>()
        for (instruction in code.drop(at)) {
            if (instruction.opcode == Opcode.CHECK_CAST) casts += instruction.getReference<TypeReference>()!!.type
            if (instruction.opcode == Opcode.RETURN_VOID || instruction.opcode == Opcode.THROW) break
        }
        return casts
    }

    private fun answersTrue(method: Method): Boolean {
        val code = method.implementation?.instructions?.toList() ?: return false
        if (code.size != 2) return false
        val literal = code[0] as? NarrowLiteralInstruction ?: return false
        val register = (code[0] as OneRegisterInstruction).registerA
        return literal.narrowLiteral == 1 && code[1].opcode == Opcode.RETURN &&
            (code[1] as OneRegisterInstruction).registerA == register
    }

    private fun allClasses(apk: java.io.File): Sequence<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence().flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
    }

    private fun classesOf(apk: java.io.File, names: Set<String>): Map<String, ClassDef> {
        val found = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type in names) found.putIfAbsent(classDef.type, classDef)
            }
            if (found.size == names.size) break
        }
        return found
    }

    private fun stored(n: Int) = ImmutableInstruction22c(
        Opcode.IPUT_OBJECT, 2, 3, ImmutableFieldReference(TITLE_BAR, "f$n", TUX),
    )

    private fun bind(stores: List<Instruction>, registers: Int = 12) = MutableMethod(
        ImmutableMethod(
            TITLE_BAR, "onViewCreated",
            listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
            "V", AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(
                registers,
                listOf<Instruction>(ImmutableInstruction21c(Opcode.CHECK_CAST, 2, ImmutableTypeReference(TUX))) +
                    stores + ImmutableInstruction10x(Opcode.RETURN_VOID),
                null, null,
            ),
        ),
    )
}
