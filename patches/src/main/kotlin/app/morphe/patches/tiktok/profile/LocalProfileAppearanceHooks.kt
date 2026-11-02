/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.RegisterLiveness
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import app.morphe.util.literalReads
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val LOCAL_APPEARANCE = "Lapp/morphe/extension/tiktok/profile/LocalProfileAppearance;"
internal const val LEGACY_NICKNAME_OWNER = "Lcom/ss/android/ugc/profile/platform/business/header/business/info/business/userinfo/base/AccountNameInfoComponent;"
internal const val NICKNAME_OWNER = "Lcom/ss/android/ugc/profile/business/ur/nickname/ProfileInfoNicknameProtocol;"
internal const val NICKNAME_REPAINT = "LX/0Q4X;"
internal const val NAVBAR_NICKNAME_OWNER = "Lcom/ss/android/ugc/profile/business/ur/ProfileNavbarNickNameProtocol;"
internal const val NAVBAR_NICKNAME_REPAINT = "LX/0Q4U;"
internal const val NAVBAR_ADD_NAME_REPAINT = "LX/0Q4V;"
internal const val NICKNAME_DATA = "Lcom/ss/android/ugc/profile/business/ur/platform/NickNameData;"
internal const val ADD_NAME_VIEW = "LX/0XeG;"
internal const val BADGE_ICON = "LX/040W;"
internal const val BADGE_SPAN = "LX/0ERz;"
internal const val RESTRICTION_OWNER = "Lcom/ss/android/ugc/profile/business/pns/account/AccountAGSWarningProtocol;"
internal const val NATIVE_BLUE_BADGE = 0x7f01026c
internal const val APPEARANCE_SELECT = "hushfeedLocalNicknameBadge"
internal const val APPEARANCE_RENDER = "hushfeedRenderNicknameBadge"
internal const val APPEARANCE_LEGACY = "hushfeedLocalVerifiedNickname"
internal const val APPEARANCE_NOTICE = "hushfeedOwnNoticeVisibility"
internal const val APPEARANCE_BEGIN = "hushfeedBeginNicknameBind"
internal const val APPEARANCE_CURRENT = "hushfeedCurrentNicknameRepaint"
internal const val APPEARANCE_APPEND = "hushfeedAppendLocalNickname"
internal const val APPEARANCE_ADD_NAME = "hushfeedAddNameText"
internal const val APPEARANCE_CACHED = "hushfeedNativeNicknameCached"
private const val STRING = "Ljava/lang/String;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val VIEW = "Landroid/view/View;"
private const val OBJECT = "Ljava/lang/Object;"
private const val SPANNABLE = "Landroid/text/SpannableStringBuilder;"
private const val NATIVE_ICON = "Lcom/ss/android/ugc/profile/platform/base/data/Icon;"
private const val CONTEXT = "Landroid/content/Context;"
private const val TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
private const val NAVBAR_CAPTURE_PARAMETERS = "$TEXT_VIEW" + "LX/02xT;" + NAVBAR_NICKNAME_OWNER + INTEGER + "I" + STRING
private const val APPEARANCE = "Local profile appearance"
internal const val NOTICE_VISIBILITY = "LX/00ki;->LJLLILLLL(ILandroid/view/View;)V"

private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Instruction.calls(descriptor: String) = getReference<MethodReference>()?.descriptor() == descriptor
private fun Instruction.registers(): List<Int>? = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> null
}
private fun Method.isInstance(name: String, result: String, parameters: List<String>, registers: Int) =
    this.name == name && returnType == result && parameterTypes.map(CharSequence::toString) == parameters &&
        !AccessFlags.STATIC.isSet(accessFlags) && implementation?.registerCount == registers

internal data class LegacyAppearanceSite(val index: Int, val original: MethodReference)
internal data class NicknameAppearanceSite(val branch: Int, val target: Int, val render: Int, val capture: Int)
internal data class NoticeAppearanceSite(val index: Int, val visibility: Int, val view: Int)
internal data class NavbarAppearanceSite(val branch: Int, val target: Int, val render: Int,
    val capture: Int, val addNameCapture: Int, val cached: Int)

/** The Boolean comparison controls only native nickname geometry and its native image span. */
internal fun Method.legacyAppearanceSite(): LegacyAppearanceSite? {
    if (definingClass != LEGACY_NICKNAME_OWNER || !isInstance("Cr", "V", emptyList(), 18)) return null
    val code = implementation!!.instructions.toList()
    val receiver = code.firstOrNull() as? TwoRegisterInstruction ?: return null
    if (code[0].opcode != Opcode.MOVE_OBJECT_FROM16 || receiver.registerA != 2 || receiver.registerB != 17) return null
    if (code.none { (it as? WideLiteralInstruction)?.wideLiteral == NATIVE_BLUE_BADGE.toLong() }) return null
    return code.indices.mapNotNull { index ->
        if (index < 4 || index + 2 >= code.size || code[index].opcode != Opcode.INVOKE_STATIC ||
            !code[index].calls("Lkotlin/jvm/internal/Intrinsics;->LJI($OBJECT$OBJECT)Z") ||
            code[index].registers() != listOf(4, 0) ||
            !code[index - 3].calls("Lcom/ss/android/ugc/profile/platform/base/data/UserAccountInfo;->isVerified()$BOOLEAN") ||
            code[index - 2].opcode != Opcode.MOVE_RESULT_OBJECT || code[index - 2].namedRegisters() != listOf(4) ||
            code[index - 1].opcode != Opcode.SGET_OBJECT ||
            code[index - 1].getReference<FieldReference>()?.toString() != "$BOOLEAN->TRUE:$BOOLEAN" ||
            code[index + 1].opcode != Opcode.MOVE_RESULT || code[index + 1].namedRegisters() != listOf(0) ||
            code[index + 2].opcode != Opcode.IF_EQZ || code[index + 2].namedRegisters() != listOf(0)) return@mapNotNull null
        LegacyAppearanceSite(index, code[index].getReference<MethodReference>()!!)
    }.singleOrNull()
}

private fun target(code: List<Instruction>, index: Int): Int? {
    val branch = code[index] as? OffsetInstruction ?: return null
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    return addresses.indexOf(addresses[index] + branch.codeOffset).takeIf { it in code.indices }
}

/** Keep the nullable native resource in v5, including the value captured by the native repaint. */
internal fun Method.nicknameAppearanceSite(): NicknameAppearanceSite? {
    if (definingClass != NICKNAME_OWNER || !isInstance("LJJLIIIJJI", "Z", emptyList(), 14)) return null
    val code = implementation!!.instructions.toList()
    val render = code.indices.singleOrNull { index -> index + 2 < code.size &&
        code[index].calls("$INTEGER->intValue()I") && code[index].registers() == listOf(5) &&
        code[index + 1].opcode == Opcode.MOVE_RESULT && code[index + 1].namedRegisters() == listOf(0) &&
        code[index + 2].calls("$NICKNAME_OWNER->LLII(II$STRING)V") &&
        code[index + 2].registers() == listOf(13, 0, 9, 4)
    } ?: return null
    val branch = code.indices.singleOrNull { index -> code[index].opcode == Opcode.IF_NEZ &&
        code[index].namedRegisters() == listOf(5) && target(code, index)?.let { at ->
            at < render && code[at].opcode == Opcode.IGET_OBJECT && code[at].namedRegisters() == listOf(0, 13) &&
                code[at].getReference<FieldReference>()?.toString() == "$PROFILE_PROTOCOL->LLJLILLLLZIIL:Lcom/ss/android/ugc/profile/platform/base/data/BizBaseData;"
        } == true
    } ?: return null
    if (0 in RegisterLiveness.of(this).liveInto(branch)) return null
    val capture = code.indices.singleOrNull { code[it].calls("$NICKNAME_REPAINT-><init>(I$NICKNAME_OWNER$INTEGER$STRING)V") &&
        code[it].registers() == listOf(0, 9, 13, 5, 4)
    } ?: return null
    if (capture <= render || code.getOrNull(capture + 1)?.getReference<MethodReference>()?.name != "viewPost") return null
    val boxed = code.indices.singleOrNull { index -> code[index].calls("$INTEGER->valueOf(I)$INTEGER") &&
        code.getOrNull(index + 1)?.namedRegisters() == listOf(5)
    } ?: return null
    val join = boxed + 2
    if (code[join].opcode != Opcode.CONST_4 || (code[join] as? WideLiteralInstruction)?.wideLiteral != 1L) return null
    val nullValue = code.indices.singleOrNull { index -> code[index].opcode == Opcode.MOVE_OBJECT &&
        code[index].namedRegisters() == listOf(5, 6) && index + 1 < code.size && target(code, index + 1) == join
    } ?: return null
    if (boxed < 1 || code[boxed - 1].getReference<FieldReference>()?.toString() != "LX/0DxS;->LIZ:I" || nullValue <= capture) return null
    return NicknameAppearanceSite(branch, target(code, branch)!!, render, capture)
}

internal fun Method.nicknameRepaintSite(): Int? {
    if (definingClass != NICKNAME_REPAINT || !isInstance("LIZ", "V", emptyList(), 9)) return null
    val code = implementation!!.instructions.toList()
    return code.indices.singleOrNull { index -> index >= 2 && index + 3 < code.size &&
        code[index - 2].getReference<FieldReference>()?.toString() == "$NICKNAME_REPAINT->LL:$NICKNAME_OWNER" &&
        code[index - 2].namedRegisters() == listOf(2, 8) &&
        code[index - 1].getReference<FieldReference>()?.toString() == "$NICKNAME_REPAINT->LLJJIII:$INTEGER" &&
        code[index - 1].namedRegisters() == listOf(0, 8) &&
        code[index].calls("$INTEGER->intValue()I") && code[index].registers() == listOf(0) &&
        code[index + 1].opcode == Opcode.MOVE_RESULT && code[index + 1].namedRegisters() == listOf(1) &&
        code[index + 2].getReference<FieldReference>()?.toString() == "$NICKNAME_REPAINT->LLJJIJI:I" &&
        code[index + 2].namedRegisters() == listOf(0, 8) &&
        code[index + 3].calls("$NICKNAME_OWNER->LLII(II$STRING)V") && code[index + 3].registers() == listOf(2, 1, 0, 3)
    }
}

/** The original constructor only stores its arguments and accepts a null native badge. */
internal fun Method.nullableNicknameCapture(): Boolean {
    if (definingClass != NICKNAME_REPAINT || !isInstance("<init>", "V", listOf("I", NICKNAME_OWNER, INTEGER, STRING), 5)) return false
    val code = implementation!!.instructions.toList()
    if (code.map { it.opcode } != listOf(Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT,
            Opcode.IPUT, Opcode.INVOKE_DIRECT, Opcode.RETURN_VOID)) return false
    val fields = listOf("LL:$NICKNAME_OWNER", "LLJJI:$STRING", "LLJJIII:$INTEGER", "LLJJIJI:I")
    val args = listOf(listOf(2, 0), listOf(4, 0), listOf(3, 0), listOf(1, 0))
    return (0..3).all { code[it].getReference<FieldReference>()?.toString() == "$NICKNAME_REPAINT->${fields[it]}" &&
        code[it].namedRegisters() == args[it] } && code[4].calls("Ljava/lang/Object;-><init>()V") &&
        code[4].registers() == listOf(0)
}

/** The native Add name widget owns its plus span, text and account-arrow listeners. */
internal fun Method.nicknameAddNameSite(): Int? {
    if (definingClass != NICKNAME_OWNER || !isInstance("LJJLIIIJJI", "Z", emptyList(), 14)) return null
    val code = implementation!!.instructions.toList()
    return code.indices.singleOrNull { at -> at >= 3 &&
        code[at].calls("$ADD_NAME_VIEW->setAddNameText($STRING)V") && code[at].registers() == listOf(0, 4) &&
        code[at - 3].calls("$NICKNAME_OWNER->LJLJJLL()$ADD_NAME_VIEW") &&
        code[at - 3].registers() == listOf(13) && code[at - 2].opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[at - 2].namedRegisters() == listOf(0) && code[at - 1].opcode == Opcode.IF_EQZ &&
        code[at - 1].namedRegisters() == listOf(0) && target(code, at - 1) == at + 1
    }
}

internal fun Method.navbarAppearanceSite(): NavbarAppearanceSite? {
    if (definingClass != NAVBAR_NICKNAME_OWNER || !isInstance("LJJLIIIJJI", "Z", emptyList(), 20)) return null
    val code = implementation!!.instructions.toList()
    if (code.firstOrNull()?.opcode != Opcode.MOVE_OBJECT_FROM16 || code[0].namedRegisters() != listOf(9, 19)) return null
    val render = code.indices.singleOrNull { at -> at + 2 < code.size &&
        code[at].calls("$INTEGER->intValue()I") && code[at].registers() == listOf(10) &&
        code[at + 1].opcode == Opcode.MOVE_RESULT && code[at + 1].namedRegisters() == listOf(2) &&
        code[at + 2].calls("$NAVBAR_NICKNAME_OWNER->LLFZ(II$STRING$STRING)V") &&
        code[at + 2].registers() == listOf(9, 2, 11, 4, 12)
    } ?: return null
    val branch = code.indices.singleOrNull { at -> at < render && code[at].opcode == Opcode.IF_EQZ &&
        code[at].namedRegisters() == listOf(10) && target(code, at)?.let { to -> to > render &&
            code[to].calls("LX/03FD;->LIZJ()Lcom/ss/android/ugc/aweme/ILoginService;") } == true
    } ?: return null
    if (6 in RegisterLiveness.of(this).liveInto(branch)) return null
    fun capture(owner: String) = code.indices.singleOrNull { at ->
        code[at].calls("$owner-><init>($NAVBAR_CAPTURE_PARAMETERS)V") &&
            code[at].registers() == (6..12).toList() && code.getOrNull(at + 1)?.getReference<MethodReference>()?.name == "viewPost"
    }
    val capture = capture(NAVBAR_NICKNAME_REPAINT) ?: return null
    val addCapture = capture(NAVBAR_ADD_NAME_REPAINT) ?: return null
    val cached = code.indices.singleOrNull { at -> at >= 1 && at + 1 < code.size &&
        code[at].opcode == Opcode.IF_NEZ && code[at].namedRegisters() == listOf(2) &&
        code[at - 1].getReference<FieldReference>()?.toString() == "$NAVBAR_NICKNAME_OWNER->LLLLLLL:Z" &&
        code[at - 1].namedRegisters() == listOf(2, 9) &&
        code[at + 1].calls("$NAVBAR_NICKNAME_OWNER->LLD(I$STRING$STRING)V") &&
        code[at + 1].registers() == listOf(9, 11, 4, 12) && target(code, at) == at + 2
    } ?: return null
    val boxed = code.indices.singleOrNull { at -> code[at].calls("$INTEGER->valueOf(I)$INTEGER") &&
        code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && code[at + 1].namedRegisters() == listOf(10)
    } ?: return null
    if (code[boxed - 1].getReference<FieldReference>()?.toString() != "LX/0DxS;->LIZ:I" ||
        code.indices.count { at -> code[at].opcode == Opcode.MOVE_OBJECT && code[at].namedRegisters() == listOf(10, 3) &&
            at + 1 < code.size && target(code, at + 1) == boxed + 2 } != 1 ||
        capture <= render || addCapture <= cached) return null
    return NavbarAppearanceSite(branch, target(code, branch)!!, render, capture, addCapture, cached)
}

internal fun Method.navbarRepaintSite(): Int? {
    if (definingClass != NAVBAR_NICKNAME_REPAINT || !isInstance("LIZ", "V", emptyList(), 9)) return null
    val code = implementation!!.instructions.toList()
    return code.indices.singleOrNull { at -> at >= 2 && at + 4 < code.size &&
        code[at - 2].getReference<FieldReference>()?.toString() == "$NAVBAR_NICKNAME_REPAINT->LLJJIII:$NAVBAR_NICKNAME_OWNER" &&
        code[at - 2].namedRegisters() == listOf(3, 8) &&
        code[at - 1].getReference<FieldReference>()?.toString() == "$NAVBAR_NICKNAME_REPAINT->LLJJIJI:$INTEGER" &&
        code[at - 1].namedRegisters() == listOf(0, 8) &&
        code[at].calls("$INTEGER->intValue()I") && code[at].registers() == listOf(0) &&
        code[at + 1].opcode == Opcode.MOVE_RESULT && code[at + 1].namedRegisters() == listOf(2) &&
        code[at + 2].getReference<FieldReference>()?.toString() == "$NAVBAR_NICKNAME_REPAINT->LLJJIJIIJIL:I" &&
        code[at + 2].namedRegisters() == listOf(1, 8) &&
        code[at + 3].getReference<FieldReference>()?.toString() == "$NAVBAR_NICKNAME_REPAINT->LLJJIJIL:$STRING" &&
        code[at + 3].namedRegisters() == listOf(0, 8) &&
        code[at + 4].calls("$NAVBAR_NICKNAME_OWNER->LLFZ(II$STRING$STRING)V") &&
        code[at + 4].registers() == listOf(3, 2, 1, 4, 0)
    }
}

internal fun Method.nullableNavbarCapture(): Boolean {
    if (definingClass !in listOf(NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT) ||
        !isInstance("<init>", "V", listOf(TEXT_VIEW, "LX/02xT;", NAVBAR_NICKNAME_OWNER, INTEGER, "I", STRING), 7)) return false
    val code = implementation!!.instructions.toList()
    if (code.map { it.opcode } != listOf(Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT,
            Opcode.IPUT_OBJECT, Opcode.IPUT, Opcode.IPUT_OBJECT, Opcode.INVOKE_DIRECT, Opcode.RETURN_VOID)) return false
    val fields = listOf("LL:$TEXT_VIEW", "LLJJI:LX/02xT;", "LLJJIII:$NAVBAR_NICKNAME_OWNER",
        "LLJJIJI:$INTEGER", "LLJJIJIIJIL:I", "LLJJIJIL:$STRING")
    return fields.indices.all { at -> code[at].getReference<FieldReference>()?.toString() == "$definingClass->${fields[at]}" &&
        code[at].namedRegisters() == listOf(at + 1, 0) } && code[6].calls("$OBJECT-><init>()V") && code[6].registers() == listOf(0)
}

internal fun Method.navbarAddNameRepaintSite(): Boolean {
    if (definingClass != NAVBAR_ADD_NAME_REPAINT || !isInstance("LIZ", "V", emptyList(), 9)) return false
    val code = implementation!!.instructions.toList()
    return code.indices.count { at -> at >= 3 && code[at].calls("$NAVBAR_NICKNAME_OWNER->LLD(I$STRING$STRING)V") &&
        code[at].registers() == listOf(2, 1, 5, 0) &&
        code[at - 3].getReference<FieldReference>()?.toString() == "$NAVBAR_ADD_NAME_REPAINT->LLJJIII:$NAVBAR_NICKNAME_OWNER" &&
        code[at - 3].namedRegisters() == listOf(2, 8) &&
        code[at - 2].getReference<FieldReference>()?.toString() == "$NAVBAR_ADD_NAME_REPAINT->LLJJIJIIJIL:I" &&
        code[at - 2].namedRegisters() == listOf(1, 8) &&
        code[at - 1].getReference<FieldReference>()?.toString() == "$NAVBAR_ADD_NAME_REPAINT->LLJJIJIL:$STRING" &&
        code[at - 1].namedRegisters() == listOf(0, 8)
    } == 1
}

/** All native plus/tag/dropdown spans are complete at this join, before publication or caching. */
internal fun Method.navbarAddNameSite(): Int? {
    if (definingClass != NAVBAR_NICKNAME_OWNER || !isInstance("LLD", "V", listOf("I", STRING, STRING), 22)) return null
    val code = implementation!!.instructions.toList()
    if (code.none { it.opcode == Opcode.MOVE_OBJECT_FROM16 && it.namedRegisters() == listOf(2, 18) }) return null
    val at = code.indices.singleOrNull { index -> index >= 3 &&
        code[index].calls("$NAVBAR_NICKNAME_OWNER->LJLJLLL()Lcom/ss/android/ugc/profile/platform/business/navbar/base/INavBarActionAbility;") &&
        code[index].registers() == listOf(2) && (index - 3 until index).all { before ->
            code[before].calls("$SPANNABLE->setSpan($OBJECT" + "III)V") && code[before].registers()?.first() == 4
        }
    } ?: return null
    val publication = code.indices.filter { code[it].getReference<FieldReference>()?.toString() in listOf(
        "LX/0OQI;->LIZJ:$SPANNABLE", "$NAVBAR_NICKNAME_OWNER->LLLLIL:$SPANNABLE", "LX/0OJU;->LIZJ:Ljava/lang/CharSequence;") }
    if (publication.isEmpty() || publication.any { it <= at } ||
        code.indices.count { before -> before < at && code[before].opcode == Opcode.NEW_INSTANCE &&
            code[before].getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()?.type == SPANNABLE &&
            code[before].namedRegisters() == listOf(4) } != 1) return null
    return at
}

/** Constant provenance distinguishes VISIBLE attempts from the two native GONE calls. */
internal fun Method.noticeAppearanceSites(): List<NoticeAppearanceSite> {
    if (definingClass != RESTRICTION_OWNER || !(isInstance("LJJLIIIJJI", "Z", emptyList(), 7) ||
            isInstance("LJJLIIIJLLLLLLLZ", "V", listOf(VIEW), 6))) return emptyList()
    val code = implementation!!.instructions.toList()
    val calls = code.indices.filter { code[it].calls(NOTICE_VISIBILITY) && code[it].opcode == Opcode.INVOKE_STATIC }
    if (calls.size != 2 || code.none { (it as? WideLiteralInstruction)?.wideLiteral == 0x7f0a66a5L }) return emptyList()
    fun reads(value: Long) = code.indices.flatMap { index ->
        val constant = code[index] as? WideLiteralInstruction ?: return@flatMap emptyList<Int>()
        if (constant.wideLiteral != value || !code[index].opcode.name.startsWith("const")) return@flatMap emptyList<Int>()
        val register = (constant as? OneRegisterInstruction)?.registerA ?: return@flatMap emptyList<Int>()
        literalReads(index).filter { it in calls && code[it].registers()?.firstOrNull() == register }
    }.distinct()
    val visible = reads(0)
    val gone = reads(8)
    if (visible.size != 1 || gone.size != 1 || (visible + gone).toSet() != calls.toSet()) return emptyList()
    return visible.map { index ->
        val args = code[index].registers()!!
        if (args.size != 2) return emptyList()
        NoticeAppearanceSite(index, args[0], args[1])
    }
}

private fun bridge(owner: String, name: String, parameters: List<String>, result: String, locals: Int,
    visible: Boolean = false, instructions: String): MutableMethod = MutableMethod(ImmutableMethod(owner, name,
    parameters.map { ImmutableMethodParameter(it, null, null) }, result,
    (if (visible) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value) or AccessFlags.SYNTHETIC.value,
    null, null, ImmutableMethodImplementation(locals + parameters.size + 1, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, instructions)
    }

/**
 * Leaves v[register] null or the profile's uid and walks the holders in v[scratch], which nothing
 * reads after the label. ART's verifier does not narrow a reference on if-eqz, so a holder left in
 * the uid register merges with the String path to Object at the label, and a String use after it
 * fails verification for the whole class (#165: the profile tab crashed on 0.72.0 to 0.74.0).
 */
private fun uidRead(getter: Method, register: Int, scratch: Int, fallback: String, receiver: String = "p0") = """
    const/4 v$register, 0x0
    invoke-virtual { $receiver }, ${getter.descriptor()}
    move-result-object v$scratch
    if-eqz v$scratch, :$fallback
    invoke-virtual { v$scratch }, $PROFILE_COMMON_INFO->getUserProfileInfo()$PROFILE_INFO
    move-result-object v$scratch
    if-eqz v$scratch, :$fallback
    invoke-virtual { v$scratch }, $PROFILE_INFO->getUid()$STRING
    move-result-object v$register
"""

internal fun legacyAppearanceBridge(getter: Method, original: MethodReference) = bridge(
    LEGACY_NICKNAME_OWNER, APPEARANCE_LEGACY, listOf(BOOLEAN, BOOLEAN), "Z", 5, instructions = """
        invoke-static { p1, p2 }, ${original.descriptor()}
        move-result v0
        if-nez v0, :native_badge
        if-eqz p1, :native_badge
        invoke-virtual { p0 }, $LEGACY_NICKNAME_OWNER->zr()Z
        move-result v1
        if-eqz v1, :native_badge
        ${uidRead(getter, 2, 4, "native_badge")}
        const-string v3, "user_account_user_name"
        invoke-static { p1, v2, v3 }, $LOCAL_APPEARANCE->legacyBadge($BOOLEAN$STRING$STRING)$BOOLEAN
        move-result-object v1
        if-eqz v1, :native_badge
        invoke-virtual { v1 }, $BOOLEAN->booleanValue()Z
        move-result v0
        :native_badge
        return v0
    """)

private fun component(owner: String) = when (owner) {
    NICKNAME_OWNER -> "user_info_nickname"
    NAVBAR_NICKNAME_OWNER -> "nav_bar_center_nickname"
    else -> throw PatchException("$APPEARANCE: unknown nickname owner")
}

internal fun nicknameAppearanceBridge(getter: Method, owner: String = NICKNAME_OWNER) = bridge(owner, APPEARANCE_SELECT,
    listOf(INTEGER), INTEGER, 5, instructions = """
        move-object v0, p1
        if-nez v0, :native_badge
        iget-object v2, p0, $PROFILE_PROTOCOL->LLJLILLLLZIIL:Lcom/ss/android/ugc/profile/platform/base/data/BizBaseData;
        instance-of v3, v2, $NICKNAME_DATA
        if-eqz v3, :native_badge
        check-cast v2, $NICKNAME_DATA
        invoke-virtual { v2 }, $NICKNAME_DATA->getVerifyIcon()$NATIVE_ICON
        move-result-object v2
        if-nez v2, :native_badge
        ${uidRead(getter, 1, 4, "native_badge")}
        const/4 v2, 0x1
        const-string v3, "${component(owner)}"
        invoke-static { p1, v2, v1, v3 }, $LOCAL_APPEARANCE->badgeResource(${INTEGER}Z$STRING$STRING)$INTEGER
        move-result-object v0
        :native_badge
        return-object v0
    """)

/** Native callbacks capture the original nullable resource, never a local replacement. */
internal fun nicknameRenderBridge(owner: String = NICKNAME_OWNER) = bridge(owner, APPEARANCE_RENDER,
    listOf(INTEGER, "I", STRING) + if (owner == NAVBAR_NICKNAME_OWNER) listOf(STRING) else emptyList(),
    "V", 1, visible = true, instructions = """
        invoke-direct { p0, p1 }, $owner->$APPEARANCE_SELECT($INTEGER)$INTEGER
        move-result-object v0
        if-eqz v0, :native_rebind
        invoke-virtual { v0 }, $INTEGER->intValue()I
        move-result v0
        ${if (owner == NAVBAR_NICKNAME_OWNER) "invoke-virtual { p0, v0, p2, p3, p4 }, $owner->LLFZ(II$STRING$STRING)V"
          else "invoke-virtual { p0, v0, p2, p3 }, $owner->LLII(II$STRING)V"}
        return-void
        :native_rebind
        invoke-virtual { p0 }, $owner->LJJLIIIJJI()Z
        return-void
    """)

internal fun nicknameBeginBridge(getter: Method, owner: String) = bridge(owner, APPEARANCE_BEGIN,
    emptyList(), "V", 3, instructions = """
        ${uidRead(getter, 0, 2, "identity_ready")}
        :identity_ready
        const-string v1, "${component(owner)}"
        invoke-static { p0, v0, v1 }, $LOCAL_APPEARANCE->beginNicknameBind($OBJECT$STRING$STRING)V
        return-void
    """)

internal fun nicknameCurrentBridge(getter: Method, repaint: String) = bridge(repaint, APPEARANCE_CURRENT,
    emptyList(), "Z", 4, instructions = """
        iget-object v0, p0, $repaint->${if (repaint == NICKNAME_REPAINT) "LL:$NICKNAME_OWNER" else "LLJJIII:$NAVBAR_NICKNAME_OWNER"}
        if-eqz v0, :stale
        ${uidRead(getter, 1, 3, "identity_ready", "v0")}
        :identity_ready
        const-string v2, "${component(if (repaint == NICKNAME_REPAINT) NICKNAME_OWNER else NAVBAR_NICKNAME_OWNER)}"
        invoke-static { p0, v0, v1, v2 }, $LOCAL_APPEARANCE->currentNicknameRepaint($OBJECT$OBJECT$STRING$STRING)Z
        move-result v0
        return v0
        :stale
        const/4 v0, 0x0
        return v0
    """)

/** Construct only the host's existing icon configuration and native image span. */
internal fun nicknameAppendBridge(getter: Method, owner: String) = bridge(owner, APPEARANCE_APPEND,
    listOf(SPANNABLE), SPANNABLE, 6, instructions = """
        const/4 v0, 0x0
        invoke-direct { p0, v0 }, $owner->$APPEARANCE_SELECT($INTEGER)$INTEGER
        move-result-object v0
        if-eqz v0, :native_text
        invoke-virtual { p0 }, $PROFILE_PROTOCOL->getContext()$CONTEXT
        move-result-object v3
        if-eqz v3, :native_text
        new-instance v2, $BADGE_ICON
        invoke-direct { v2 }, $BADGE_ICON-><init>()V
        const v0, $NATIVE_BLUE_BADGE
        iput v0, v2, $BADGE_ICON->LIZ:I
        const/16 v0, 0x10
        invoke-static { v0 }, $INTEGER->valueOf(I)$INTEGER
        move-result-object v0
        invoke-static { v0 }, LX/03sU;->LIZIZ(Ljava/lang/Number;)F
        move-result v0
        invoke-static { v0 }, LX/05F3;->LIZJ(F)I
        move-result v0
        iput v0, v2, $BADGE_ICON->LIZIZ:I
        iput v0, v2, $BADGE_ICON->LIZJ:I
        const/4 v0, 0x2
        invoke-virtual { v2, v0, v3 }, $BADGE_ICON->LIZIZ(I$CONTEXT)$BADGE_SPAN
        move-result-object v2
        const/4 v0, 0x0
        invoke-direct { p0, v0 }, $owner->$APPEARANCE_SELECT($INTEGER)$INTEGER
        move-result-object v0
        if-eqz v0, :native_text
        ${uidRead(getter, 1, 5, "native_text")}
        const-string v4, "${component(owner)}"
        invoke-static { p0, p1, v2, v1, v4 }, $LOCAL_APPEARANCE->badgeText($OBJECT$SPANNABLE$OBJECT$STRING$STRING)$SPANNABLE
        move-result-object v0
        return-object v0
        :native_text
        invoke-static { p0 }, $LOCAL_APPEARANCE->clearLocalNicknameText($OBJECT)V
        return-object p1
    """)

internal fun nicknameAddNameBridge() = bridge(NICKNAME_OWNER, APPEARANCE_ADD_NAME, listOf(ADD_NAME_VIEW, STRING),
    "V", 3, instructions = """
        invoke-virtual { p1, p2 }, $ADD_NAME_VIEW->setAddNameText($STRING)V
        iget-object v2, p1, $ADD_NAME_VIEW->LL:$TEXT_VIEW
        if-eqz v2, :done
        invoke-virtual { v2 }, Landroid/widget/TextView;->getText()Ljava/lang/CharSequence;
        move-result-object v0
        if-eqz v0, :done
        new-instance v1, $SPANNABLE
        invoke-direct { v1, v0 }, $SPANNABLE-><init>(Ljava/lang/CharSequence;)V
        invoke-direct { p0, v1 }, $NICKNAME_OWNER->$APPEARANCE_APPEND($SPANNABLE)$SPANNABLE
        move-result-object v0
        if-eq v0, v1, :done
        invoke-virtual { v2, v0 }, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
        :done
        return-void
    """)

/** A prior local Add name must be rebuilt from native inputs, including when Pause is now on. */
internal fun nicknameCachedBridge() = bridge(NAVBAR_NICKNAME_OWNER, APPEARANCE_CACHED, listOf("Z"), "Z", 1,
    instructions = """
        invoke-static { p0 }, $LOCAL_APPEARANCE->hasLocalNicknameText($OBJECT)Z
        move-result v0
        if-eqz v0, :native_cache
        const/4 v0, 0x0
        return v0
        :native_cache
        return p1
    """)

internal fun noticeAppearanceBridge(getter: Method) = bridge(RESTRICTION_OWNER, APPEARANCE_NOTICE,
    listOf("I", VIEW), "V", 5, instructions = """
        move v0, p1
        ${uidRead(getter, 1, 4, "native_visibility")}
        iget-object v2, p0, $PROFILE_PROTOCOL->LLJJJJJIL:$VIEW
        const-string v3, "user_account_ags_warning"
        invoke-static { v0, p2, v2, v1, v3 }, $LOCAL_APPEARANCE->noticeVisibility(I$VIEW$VIEW$STRING$STRING)I
        move-result v0
        :native_visibility
        invoke-static { v0, p2 }, $NOTICE_VISIBILITY
        return-void
    """)

internal fun MutableMethod.replaceLegacyAppearance(site: LegacyAppearanceSite) {
    if (legacyAppearanceSite() != site) throw PatchException("$APPEARANCE: older nickname bind changed")
    replaceInstruction(site.index, "invoke-direct { v2, v4, v0 }, $LEGACY_NICKNAME_OWNER->$APPEARANCE_LEGACY($BOOLEAN$BOOLEAN)Z")
}

internal fun MutableMethod.replaceNicknameAppearance(site: NicknameAppearanceSite) {
    if (nicknameAppearanceSite() != site) throw PatchException("$APPEARANCE: current nickname bind changed")
    val target = implementation!!.instructions.toList()[site.target] as BuilderInstruction
    replaceInstruction(site.render, "invoke-virtual { p0, v5, v9, v4 }, $NICKNAME_OWNER->$APPEARANCE_RENDER(${INTEGER}I$STRING)V")
    replaceInstruction(site.render + 1, "nop")
    replaceInstruction(site.render + 2, "nop")
    replaceInstruction(site.branch, "invoke-direct { p0, v5 }, $NICKNAME_OWNER->$APPEARANCE_SELECT($INTEGER)$INTEGER")
    addInstructionsWithLabels(site.branch + 1, "move-result-object v0\nif-nez v0, :native_badge_layout",
        ExternalLabel("native_badge_layout", target))
}

internal fun MutableMethod.replaceNicknameRepaint(index: Int) {
    if (nicknameRepaintSite() != index) throw PatchException("$APPEARANCE: deferred nickname bind changed")
    replaceInstruction(index, "move-object v1, v0")
    replaceInstruction(index + 1, "nop")
    replaceInstruction(index + 3, "invoke-virtual { v2, v1, v0, v3 }, $NICKNAME_OWNER->$APPEARANCE_RENDER(${INTEGER}I$STRING)V")
}

internal fun MutableMethod.replaceNicknameAddName(index: Int) {
    if (nicknameAddNameSite() != index) throw PatchException("$APPEARANCE: Add name widget changed")
    replaceInstruction(index, "invoke-direct { p0, v0, v4 }, $NICKNAME_OWNER->$APPEARANCE_ADD_NAME($ADD_NAME_VIEW$STRING)V")
}

internal fun MutableMethod.replaceNavbarAppearance(site: NavbarAppearanceSite) {
    if (navbarAppearanceSite() != site) throw PatchException("$APPEARANCE: navbar nickname bind changed")
    val nativePlain = implementation!!.instructions.toList()[site.target] as BuilderInstruction
    replaceInstruction(site.render, "invoke-virtual { v9, v10, v11, v4, v12 }, $NAVBAR_NICKNAME_OWNER->$APPEARANCE_RENDER(${INTEGER}I$STRING$STRING)V")
    replaceInstruction(site.render + 1, "nop")
    replaceInstruction(site.render + 2, "nop")
    addInstructionsWithLabels(site.cached, """
        invoke-direct { v9, v2 }, $NAVBAR_NICKNAME_OWNER->$APPEARANCE_CACHED(Z)Z
        move-result v2
    """)
    replaceInstruction(site.branch, "invoke-direct { v9, v10 }, $NAVBAR_NICKNAME_OWNER->$APPEARANCE_SELECT($INTEGER)$INTEGER")
    addInstructionsWithLabels(site.branch + 1, "move-result-object v6\nif-eqz v6, :native_plain",
        ExternalLabel("native_plain", nativePlain))
}

internal fun MutableMethod.replaceNavbarRepaint(index: Int) {
    if (navbarRepaintSite() != index) throw PatchException("$APPEARANCE: navbar deferred nickname changed")
    replaceInstruction(index, "move-object v2, v0")
    replaceInstruction(index + 1, "nop")
    replaceInstruction(index + 4, "invoke-virtual { v3, v2, v1, v4, v0 }, $NAVBAR_NICKNAME_OWNER->$APPEARANCE_RENDER(${INTEGER}I$STRING$STRING)V")
}

internal fun MutableMethod.replaceNavbarAddName(index: Int) {
    if (navbarAddNameSite() != index) throw PatchException("$APPEARANCE: navbar Add name span join changed")
    addInstructionsAtControlFlowLabel(index, """
        invoke-direct { v2, v4 }, $NAVBAR_NICKNAME_OWNER->$APPEARANCE_APPEND($SPANNABLE)$SPANNABLE
        move-result-object v4
    """)
}

internal fun MutableMethod.trackNicknameBinding() {
    val callbacks = when (definingClass) {
        NICKNAME_OWNER -> listOf(NICKNAME_REPAINT)
        NAVBAR_NICKNAME_OWNER -> listOf(NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT)
        else -> throw PatchException("$APPEARANCE: unknown binding owner")
    }
    val code = implementation!!.instructions.toList()
    val captures = callbacks.map { callback -> code.indices.singleOrNull { at ->
        code[at].getReference<MethodReference>()?.let { it.definingClass == callback && it.name == "<init>" } == true
    } ?: throw PatchException("$APPEARANCE: expected one original callback capture") }
    for (at in captures.sortedDescending()) {
        val args = code[at].registers()!!
        val owner = if (definingClass == NICKNAME_OWNER) args[2] else args[3]
        addInstructionsWithLabels(at + 1,
            "invoke-static { v${args[0]}, v$owner }, $LOCAL_APPEARANCE->captureNicknameRepaint($OBJECT$OBJECT)V")
    }
    addInstructionsWithLabels(0, "invoke-direct/range { p0 .. p0 }, $definingClass->$APPEARANCE_BEGIN()V")
}

internal fun MutableMethod.guardNicknameRepaint() {
    if (definingClass !in listOf(NICKNAME_REPAINT, NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT) ||
        !isInstance("LIZ", "V", emptyList(), 9)) throw PatchException("$APPEARANCE: unexpected deferred repaint")
    val first = implementation!!.instructions.first() as BuilderInstruction
    if (first.opcode != Opcode.IGET_OBJECT || first.namedRegisters() != listOf(0, 8)) {
        throw PatchException("$APPEARANCE: native repaint entry changed")
    }
    addInstructionsWithLabels(0, """
        invoke-direct { p0 }, $definingClass->$APPEARANCE_CURRENT()Z
        move-result v0
        if-nez v0, :native_repaint
        return-void
    """, ExternalLabel("native_repaint", first))
}

internal fun MutableMethod.replaceNoticeAppearance(site: NoticeAppearanceSite) {
    if (noticeAppearanceSites().singleOrNull() != site) throw PatchException("$APPEARANCE: warning visibility changed")
    val receiver = implementation!!.registerCount - parameterTypes.size - 1
    if (receiver > 15 || listOf(site.visibility, site.view).any { it !in 0..15 }) {
        throw PatchException("$APPEARANCE: warning arguments cannot be encoded")
    }
    replaceInstruction(site.index, "invoke-direct { p0, v${site.visibility}, v${site.view} }, $RESTRICTION_OWNER->$APPEARANCE_NOTICE(I$VIEW)V")
}

private fun ClassDef.requireComponent(name: String) {
    val getter = methods.singleOrNull { it.name == "getName" && it.parameterTypes.isEmpty() && it.returnType == STRING }
    if (getter?.implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string } != listOf(name)) {
        throw PatchException("$APPEARANCE: unexpected component $type")
    }
}

internal fun BytecodePatchContext.hookLocalProfileAppearance() {
    val legacyOwner = classDefBy(LEGACY_NICKNAME_OWNER)
    val nicknameOwner = classDefBy(NICKNAME_OWNER)
    val navbarOwner = classDefBy(NAVBAR_NICKNAME_OWNER)
    val warningOwner = classDefBy(RESTRICTION_OWNER)
    nicknameOwner.requireComponent("user_info_nickname")
    navbarOwner.requireComponent("nav_bar_center_nickname")
    warningOwner.requireComponent("user_account_ags_warning")
    val legacy = legacyOwner.methods.mapNotNull { method -> method.legacyAppearanceSite()?.let { method to it } }.singleOrNull()
        ?: throw PatchException("$APPEARANCE: missing older native badge decision")
    val nickname = nicknameOwner.methods.mapNotNull { method -> method.nicknameAppearanceSite()?.let { method to it } }.singleOrNull()
        ?: throw PatchException("$APPEARANCE: missing current native badge join")
    val repaint = classDefBy(NICKNAME_REPAINT).methods.mapNotNull { method -> method.nicknameRepaintSite()?.let { method to it } }.singleOrNull()
        ?: throw PatchException("$APPEARANCE: missing original-resource deferred repaint")
    val addName = nickname.first.nicknameAddNameSite()
        ?: throw PatchException("$APPEARANCE: missing native Add name widget")
    val navbar = navbarOwner.methods.mapNotNull { method -> method.navbarAppearanceSite()?.let { method to it } }.singleOrNull()
        ?: throw PatchException("$APPEARANCE: missing navbar native badge join")
    val navbarRepaint = classDefBy(NAVBAR_NICKNAME_REPAINT).methods.mapNotNull { method -> method.navbarRepaintSite()?.let { method to it } }.singleOrNull()
        ?: throw PatchException("$APPEARANCE: missing navbar original-resource repaint")
    val navbarAddRepaint = classDefBy(NAVBAR_ADD_NAME_REPAINT).methods.singleOrNull { it.navbarAddNameRepaintSite() }
        ?: throw PatchException("$APPEARANCE: missing navbar Add name repaint")
    val navbarAddName = navbarOwner.methods.mapNotNull { method -> method.navbarAddNameSite()?.let { method to it } }.singleOrNull()
        ?: throw PatchException("$APPEARANCE: missing navbar native Add name span join")
    if (classDefBy(NICKNAME_REPAINT).methods.count { it.nullableNicknameCapture() } != 1) {
        throw PatchException("$APPEARANCE: native repaint no longer accepts a nullable original badge")
    }
    for (callback in listOf(NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT)) {
        if (classDefBy(callback).methods.count { it.nullableNavbarCapture() } != 1) {
            throw PatchException("$APPEARANCE: navbar repaint no longer accepts the original badge")
        }
    }
    for (original in listOf(repaint.first, navbarRepaint.first, navbarAddRepaint)) {
        if (original.implementation!!.instructions.first().let { it.opcode != Opcode.IGET_OBJECT || it.namedRegisters() != listOf(0, 8) }) {
            throw PatchException("$APPEARANCE: native repaint entry changed")
        }
    }
    val warnings = warningOwner.methods.flatMap { method -> method.noticeAppearanceSites().map { method to it } }
    if (warnings.size != 2) throw PatchException("$APPEARANCE: expected both native warning VISIBLE sites")
    val legacyGetter = classDefBy(BASE_UI_COMPONENT).commonInfoGetter()
        ?: throw PatchException("$APPEARANCE: missing component profile identity")
    val protocolGetter = classDefBy(PROFILE_PROTOCOL).commonInfoGetter()
        ?: throw PatchException("$APPEARANCE: missing protocol profile identity")
    for ((owner, name, result) in listOf(Triple(PROFILE_COMMON_INFO, "getUserProfileInfo", PROFILE_INFO),
        Triple(PROFILE_INFO, "getUid", STRING), Triple(NICKNAME_DATA, "getVerifyIcon", NATIVE_ICON),
        Triple(PROFILE_PROTOCOL, "getContext", CONTEXT))) {
        if (classDefBy(owner).methods.none { it.name == name && it.returnType == result && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
            throw PatchException("$APPEARANCE: missing explicit profile identity $name")
        }
    }
    val legacyIdentity = legacyOwner.methods.singleOrNull { it.name == "zr" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
    if (legacyIdentity?.implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string } != listOf("user_account_user_name")) {
        throw PatchException("$APPEARANCE: missing native older nickname identity")
    }
    if (nicknameOwner.methods.none { it.name == "LLII" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I", STRING) && it.returnType == "V" && AccessFlags.PUBLIC.isSet(it.accessFlags) } ||
        navbarOwner.methods.none { it.name == "LLFZ" && it.parameterTypes.map(CharSequence::toString) == listOf("I", "I", STRING, STRING) && it.returnType == "V" && AccessFlags.PUBLIC.isSet(it.accessFlags) } ||
        classDefBy(PROFILE_PROTOCOL).fields.none { it.name == "LLJJJJJIL" && it.type == VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
        throw PatchException("$APPEARANCE: missing native renderer or warning root")
    }
    fun requireMethod(owner: String, name: String, parameters: List<String>, result: String, static: Boolean = false) {
        if (classDefBy(owner).methods.none { it.name == name && it.returnType == result &&
            it.parameterTypes.map(CharSequence::toString) == parameters && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            AccessFlags.STATIC.isSet(it.accessFlags) == static }) throw PatchException("$APPEARANCE: missing native presentation factory $owner->$name")
    }
    requireMethod(BADGE_ICON, "<init>", emptyList(), "V")
    requireMethod(BADGE_ICON, "LIZIZ", listOf("I", CONTEXT), BADGE_SPAN)
    requireMethod("LX/03sU;", "LIZIZ", listOf("Ljava/lang/Number;"), "F", true)
    requireMethod("LX/05F3;", "LIZJ", listOf("F"), "I", true)
    requireMethod(ADD_NAME_VIEW, "setAddNameText", listOf(STRING), "V")
    if (classDefBy(BADGE_SPAN).superclass != "Landroid/text/style/ReplacementSpan;" ||
        listOf("LIZ", "LIZIZ", "LIZJ").any { name -> classDefBy(BADGE_ICON).fields.none {
            it.name == name && it.type == "I" && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) } } ||
        classDefBy(ADD_NAME_VIEW).fields.none { it.name == "LL" && it.type == TEXT_VIEW && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) } ||
        classDefBy(PROFILE_PROTOCOL).fields.none { it.name == "LLJLILLLLZIIL" && it.type == "Lcom/ss/android/ugc/profile/platform/base/data/BizBaseData;" && !AccessFlags.STATIC.isSet(it.accessFlags) }) {
        throw PatchException("$APPEARANCE: missing native icon configuration or nickname text view")
    }
    val added = mapOf(LEGACY_NICKNAME_OWNER to listOf(APPEARANCE_LEGACY),
        NICKNAME_OWNER to listOf(APPEARANCE_SELECT, APPEARANCE_RENDER, APPEARANCE_BEGIN, APPEARANCE_APPEND, APPEARANCE_ADD_NAME),
        NAVBAR_NICKNAME_OWNER to listOf(APPEARANCE_SELECT, APPEARANCE_RENDER, APPEARANCE_BEGIN, APPEARANCE_APPEND, APPEARANCE_CACHED),
        NICKNAME_REPAINT to listOf(APPEARANCE_CURRENT), NAVBAR_NICKNAME_REPAINT to listOf(APPEARANCE_CURRENT),
        NAVBAR_ADD_NAME_REPAINT to listOf(APPEARANCE_CURRENT), RESTRICTION_OWNER to listOf(APPEARANCE_NOTICE))
    if (added.any { (owner, names) -> classDefBy(owner).methods.any { it.name in names } }) {
        throw PatchException("$APPEARANCE: an owned bridge name already exists")
    }
    fun mutable(original: Method) = mutableClassDefBy(original.definingClass).methods.single {
        it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType
    }
    // Validate every original before edits. Writes below configure only a newly allocated native icon.
    mutableClassDefBy(LEGACY_NICKNAME_OWNER).methods.add(legacyAppearanceBridge(legacyGetter, legacy.second.original))
    for (owner in listOf(NICKNAME_OWNER, NAVBAR_NICKNAME_OWNER)) {
        mutableClassDefBy(owner).methods.add(nicknameAppearanceBridge(protocolGetter, owner))
        mutableClassDefBy(owner).methods.add(nicknameRenderBridge(owner))
        mutableClassDefBy(owner).methods.add(nicknameBeginBridge(protocolGetter, owner))
        mutableClassDefBy(owner).methods.add(nicknameAppendBridge(protocolGetter, owner))
    }
    mutableClassDefBy(NICKNAME_OWNER).methods.add(nicknameAddNameBridge())
    mutableClassDefBy(NAVBAR_NICKNAME_OWNER).methods.add(nicknameCachedBridge())
    for (callback in listOf(NICKNAME_REPAINT, NAVBAR_NICKNAME_REPAINT, NAVBAR_ADD_NAME_REPAINT)) {
        mutableClassDefBy(callback).methods.add(nicknameCurrentBridge(protocolGetter, callback))
    }
    mutableClassDefBy(RESTRICTION_OWNER).methods.add(noticeAppearanceBridge(protocolGetter))
    mutable(legacy.first).replaceLegacyAppearance(legacy.second)
    mutable(nickname.first).replaceNicknameAddName(addName)
    mutable(nickname.first).replaceNicknameAppearance(nickname.second)
    mutable(nickname.first).trackNicknameBinding()
    mutable(repaint.first).replaceNicknameRepaint(repaint.second)
    mutable(repaint.first).guardNicknameRepaint()
    mutable(navbar.first).replaceNavbarAppearance(navbar.second)
    mutable(navbar.first).trackNicknameBinding()
    mutable(navbarRepaint.first).replaceNavbarRepaint(navbarRepaint.second)
    mutable(navbarRepaint.first).guardNicknameRepaint()
    mutable(navbarAddName.first).replaceNavbarAddName(navbarAddName.second)
    mutable(navbarAddRepaint).guardNicknameRepaint()
    for ((original, site) in warnings) mutable(original).replaceNoticeAppearance(site)
    println("[Show follow status] Added owned nickname badges and both warning presentation hooks.")
}
