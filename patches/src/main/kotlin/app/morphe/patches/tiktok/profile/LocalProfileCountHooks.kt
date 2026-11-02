/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.profile

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val LOCAL_COUNTS = "Lapp/morphe/extension/tiktok/profile/LocalProfileCounts;"
internal const val RELATION_COMPONENT = "Lcom/ss/android/ugc/profile/platform/business/header/business/info/business/relationinfo/base/RelationInfoBaseUIComponent;"
internal const val RELATION_DATA = "Lcom/ss/android/ugc/profile/platform/business/header/business/info/business/relationinfo/data/RelationData;"
internal const val PROFILE_PROTOCOL = "Lcom/ss/android/ugc/profile/platform/standard/protocol/ProfilePlatformProtocol;"
internal const val PROFILE_INFO = "Lcom/ss/android/ugc/profile/platform/base/data/UserProfileInfo;"
private const val COMPONENTS = "Lcom/ss/android/ugc/profile/platform/base/data/ProfileComponents;"
private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val STRING = "Ljava/lang/String;"
private const val TEXT = "Ljava/lang/CharSequence;"
private const val NUMBER = "Ljava/lang/Number;"
private const val BRIDGE = "hushfeedLocalProfileCount"
private const val COUNTS = "Local profile counts"

internal enum class ProfileCountMetric(val component: String, val protocol: String, val sites: Int) {
    FOLLOWERS("relation_info_follower", "Lcom/ss/android/ugc/profile/business/ur/platform/business/RelationFollowerProtocol;", 2),
    FOLLOWING("relation_info_following", "Lcom/ss/android/ugc/profile/business/ur/platform/business/RelationFollowingProtocol;", 1),
    LIKES("relation_info_like", "Lcom/ss/android/ugc/profile/business/ur/platform/business/RelationLikeProtocol;", 1),
}

internal data class ProfileCountFormatSite(val index: Int, val formatter: MethodReference, val registers: List<Int>)
internal data class ProfileCountTextSite(val index: Int, val view: Int, val text: Int)
internal data class LegacyCountIdentity(val attached: FieldReference, val holder: FieldReference,
    val key: FieldReference, val fallback: FieldReference)

private fun MethodReference.descriptor() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun MethodReference.isCountFormatter() = returnType == TEXT &&
    parameterTypes.map(CharSequence::toString) == listOf("I", NUMBER, STRING)
private fun MethodReference.isTextSetter() = definingClass == TEXT_VIEW && name == "setText" &&
    returnType == "V" && parameterTypes.map(CharSequence::toString) == listOf(TEXT)

/** Only a formatter result delivered directly to a profile TextView is a display count. */
internal fun Method.profileCountFormats(): List<ProfileCountFormatSite> {
    if (ProfileCountMetric.entries.none { it.protocol == definingClass } || AccessFlags.STATIC.isSet(accessFlags)) return emptyList()
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        if (at + 2 >= code.size || code[at].opcode != Opcode.INVOKE_STATIC || code[at + 1].opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val ref = code[at].getReference<MethodReference>() ?: return@mapNotNull null
        val call = code[at] as? FiveRegisterInstruction ?: return@mapNotNull null
        val result = code[at + 1] as? OneRegisterInstruction ?: return@mapNotNull null
        val setter = code[at + 2] as? FiveRegisterInstruction ?: return@mapNotNull null
        if (!ref.isCountFormatter() || call.registerCount != 3 || code[at + 2].opcode != Opcode.INVOKE_VIRTUAL ||
            code[at + 2].getReference<MethodReference>()?.isTextSetter() != true || setter.registerCount != 2 ||
            setter.registerD != result.registerA) return@mapNotNull null
        ProfileCountFormatSite(at, ref, listOf(call.registerC, call.registerD, call.registerE))
    }
}

/** The older header layout hands its already formatted number straight to setText. */
internal fun Method.profileCountTexts(): List<ProfileCountTextSite> {
    if (definingClass != RELATION_COMPONENT || AccessFlags.STATIC.isSet(accessFlags) ||
        returnType != "V" || parameterTypes.isNotEmpty()) return emptyList()
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        if (at < 3 || code[at].opcode != Opcode.INVOKE_VIRTUAL || code[at].getReference<MethodReference>()?.isTextSetter() != true) return@mapNotNull null
        val getter = code[at - 3].getReference<MethodReference>() ?: return@mapNotNull null
        val result = code[at - 2] as? OneRegisterInstruction ?: return@mapNotNull null
        val branch = code[at - 1] as? OneRegisterInstruction ?: return@mapNotNull null
        val setter = code[at] as? FiveRegisterInstruction ?: return@mapNotNull null
        if (code[at - 3].opcode != Opcode.INVOKE_VIRTUAL || getter.definingClass != RELATION_DATA || getter.name != "getNumber" ||
            getter.returnType != STRING || getter.parameterTypes.isNotEmpty() || code[at - 2].opcode != Opcode.MOVE_RESULT_OBJECT ||
            code[at - 1].opcode != Opcode.IF_EQZ || branch.registerA != result.registerA ||
            setter.registerCount != 2 || setter.registerD != result.registerA) return@mapNotNull null
        ProfileCountTextSite(at, setter.registerC, setter.registerD)
    }
}

internal fun Method.legacyCountIdentity(): LegacyCountIdentity {
    val fields = implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }.distinct()
    val holder = fields.singleOrNull { it.type == COMPONENTS }
        ?: throw PatchException("$COUNTS: missing profile component holder")
    return LegacyCountIdentity(
        fields.singleOrNull { it.definingClass == BASE_UI_COMPONENT && it.type == holder.definingClass }
            ?: throw PatchException("$COUNTS: missing attached profile component"), holder,
        fields.singleOrNull { it.definingClass == COMPONENTS && it.name == "componentId" && it.type == STRING }
            ?: throw PatchException("$COUNTS: missing profile component identifier"),
        fields.singleOrNull { it.definingClass == BASE_UI_COMPONENT && it.type == STRING }
            ?: throw PatchException("$COUNTS: missing fallback component identifier"),
    )
}

private fun uidRead(getter: Method) = """
    invoke-virtual { p0 }, ${getter.descriptor()}
    move-result-object v3
    if-eqz v3, :native_count
    invoke-virtual { v3 }, $PROFILE_COMMON_INFO->getUserProfileInfo()$PROFILE_INFO
    move-result-object v3
    if-eqz v3, :native_count
    invoke-virtual { v3 }, $PROFILE_INFO->getUid()$STRING
    move-result-object v3
"""

internal fun profileCountBridge(owner: String, getter: Method, formatter: MethodReference,
    metric: ProfileCountMetric? = null, identity: LegacyCountIdentity? = null): MutableMethod {
    val legacy = metric == null
    val parameters = if (legacy) listOf(TEXT_VIEW, TEXT) else listOf("I", NUMBER, STRING)
    val bridge = MutableMethod(ImmutableMethod(owner, BRIDGE,
        parameters.map { ImmutableMethodParameter(it, null, null) }, if (legacy) "V" else TEXT,
        AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value, null, null,
        ImmutableMethodImplementation(5 + parameters.size + 1, emptyList(), null, null)))
    val original = if (legacy) "move-object v0, p2" else """
        invoke-static { p1, p2, p3 }, ${formatter.descriptor()}
        move-result-object v0
    """
    val component = if (!legacy) "const-string v4, \"${metric.component}\"" else identity!!.let { fields -> """
        iget-object v4, p0, ${fields.attached}
        if-eqz v4, :fallback_component
        iget-object v4, v4, ${fields.holder}
        if-eqz v4, :fallback_component
        iget-object v4, v4, ${fields.key}
        if-nez v4, :component_ready
        :fallback_component
        iget-object v4, p0, ${fields.fallback}
        :component_ready
    """ }
    val format = if (legacy) """
        const/4 v1, 0x1
        const-string v0, "-"
        invoke-static { v1, v2, v0 }, ${formatter.descriptor()}
        move-result-object v1
        move-object v0, p2
    """ else """
        invoke-static { p1, v2, p3 }, ${formatter.descriptor()}
        move-result-object v1
    """
    val finish = if (legacy) "invoke-virtual { p1, v0 }, $TEXT_VIEW->setText($TEXT)V\nreturn-void" else "return-object v0"
    bridge.addInstructionsWithLabels(0, """
        $original
        invoke-static {}, $LOCAL_COUNTS->enabled()Z
        move-result v1
        if-eqz v1, :native_count
        ${uidRead(getter)}
        $component
        invoke-static { v3, v4 }, $LOCAL_COUNTS->override($STRING$STRING)$NUMBER
        move-result-object v2
        if-eqz v2, :native_count
        $format
        invoke-static { v0, v1, v2, v3, v4 }, $LOCAL_COUNTS->choose($TEXT$TEXT$NUMBER$STRING$STRING)$TEXT
        move-result-object v0
        :native_count
        $finish
    """)
    return bridge
}

internal fun MutableMethod.replaceProfileCount(index: Int, registers: List<Int>, legacy: Boolean = false) {
    val receiver = implementation!!.registerCount - numberOfParameterRegisters
    if (receiver > 15 || registers.any { it !in 0..15 } || AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("$COUNTS: $definingClass->$name cannot encode its owned count arguments")
    }
    val args = if (legacy) "$TEXT_VIEW$TEXT" else "I$NUMBER$STRING"
    replaceInstruction(index, "invoke-direct { p0, ${registers.joinToString(", ") { "v$it" }} }, $definingClass->$BRIDGE($args)${if (legacy) "V" else TEXT}")
}

internal fun BytecodePatchContext.hookLocalProfileCounts() {
    val protocols = ProfileCountMetric.entries.associateWith { classDefBy(it.protocol) }
    val sites = protocols.mapValues { (metric, owner) -> owner.methods.flatMap { method ->
        method.profileCountFormats().map { method to it }
    }.also { if (it.size != metric.sites) throw PatchException("$COUNTS: found ${it.size} ${metric.name} display sites") } }
    val legacy = classDefBy(RELATION_COMPONENT).methods.flatMap { method -> method.profileCountTexts().map { method to it } }
        .singleOrNull() ?: throw PatchException("$COUNTS: no single older-layout count bind")
    val formatter = sites.values.flatten().map { it.second.formatter }.distinctBy { it.descriptor() }.singleOrNull()
        ?: throw PatchException("$COUNTS: display counts no longer share one native formatter")
    val nativeFormatter = classDefBy(formatter.definingClass).methods.singleOrNull {
        it.name == formatter.name && it.isCountFormatter() && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: throw PatchException("$COUNTS: native count formatter is unavailable")
    val protocolGetter = classDefBy(PROFILE_PROTOCOL).commonInfoGetter()
        ?: throw PatchException("$COUNTS: no single protocol profile-info getter")
    val legacyGetter = classDefBy(BASE_UI_COMPONENT).commonInfoGetter()
        ?: throw PatchException("$COUNTS: no single component profile-info getter")
    for ((owner, name, result) in listOf(Triple(PROFILE_COMMON_INFO, "getUserProfileInfo", PROFILE_INFO), Triple(PROFILE_INFO, "getUid", STRING))) {
        if (classDefBy(owner).methods.none { it.name == name && it.returnType == result && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
            throw PatchException("$COUNTS: missing explicit profile identity member $name")
        }
    }
    val identity = legacy.first.legacyCountIdentity()
    val owners = protocols.values.map { it.type } + RELATION_COMPONENT
    if (owners.any { classDefBy(it).methods.any { method -> method.name == BRIDGE } }) {
        throw PatchException("$COUNTS: owned bridge name already exists")
    }
    for ((metric, owner) in protocols) {
        mutableClassDefBy(owner.type).methods.add(profileCountBridge(owner.type, protocolGetter, nativeFormatter, metric))
        for ((original, site) in sites.getValue(metric)) {
            mutableClassDefBy(owner.type).methods.single { it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType }
                .replaceProfileCount(site.index, site.registers)
        }
    }
    mutableClassDefBy(RELATION_COMPONENT).methods.add(profileCountBridge(RELATION_COMPONENT, legacyGetter, nativeFormatter, identity = identity))
    val (original, site) = legacy
    mutableClassDefBy(RELATION_COMPONENT).methods.single { it.name == original.name && it.parameterTypes == original.parameterTypes && it.returnType == original.returnType }
        .replaceProfileCount(site.index, listOf(site.view, site.text), true)
    println("[Show follow status] Added five owned profile count presentation hooks.")
}
