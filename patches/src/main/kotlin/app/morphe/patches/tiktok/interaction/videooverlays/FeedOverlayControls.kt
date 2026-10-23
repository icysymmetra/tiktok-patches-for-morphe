/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val FULLSCREEN_COMPONENT = "Lcom/ss/android/ugc/aweme/feed/landscape/LandscapeEntranceAssem;"

/** The gate key behind the Report button TikTok shows above the creator's avatar in some regions. */
internal const val FEED_REPORT_GATE_KEY = "id_hide_report_entrance"

/**
 * Whether the feed shows its regional Report button (issue #21), Indonesia at the time of
 * writing. `ReportViewAssem.onBind` shows the button only when this answers true and hides it
 * otherwise, which is the state every other region already runs in. Its class is renamed on
 * every build (17q6, 15MA, 19jl, 08zM, 099k), so it is found as the one static `()Z` that reads
 * the gate key. The other method naming that key registers it and returns nothing.
 */
internal fun isFeedReportButtonGate(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" &&
        method.parameterTypes.isEmpty() &&
        method.implementation?.instructions?.any {
            it.getReference<StringReference>()?.string == FEED_REPORT_GATE_KEY
        } == true
internal val locationCardMarkers = listOf("PoiAnchorView2", "PoiDealAnchorView")
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val ANCHOR = "Lcom/ss/android/ugc/aweme/feed/model/AnchorCommonStruct;"
private const val MULTI_TAG = "Lcom/ss/android/ugc/aweme/fcpanchor/access/VideoFeedMultiTagAssem;"

/** 46.2-46.8 inline this builder into a lambda; 46.9+ use a static list factory. */
internal fun isLocationBadgeListFactory(method: Method): Boolean {
    val params = method.parameterTypes.map(CharSequence::toString)
    val legacy = method.name == "invoke" && params.isEmpty() && method.returnType == "Ljava/lang/Object;" &&
        !AccessFlags.STATIC.isSet(method.accessFlags)
    val modern = params.size == 4 && params[0] == AWEME && params[1] == "Z" &&
        params.drop(2).all { it.startsWith("L") } && method.returnType == "Ljava/util/List;" &&
        AccessFlags.STATIC.isSet(method.accessFlags)
    if (!legacy && !modern) return false
    val instructions = method.implementation?.instructions ?: return false
    var anchors = 0
    var component = false
    var viewOwner = false
    var copy = false
    for (instruction in instructions) {
        instruction.getReference<MethodReference>()?.let { call ->
            if (call.definingClass == AWEME && call.name == "getAnchors" &&
                call.parameterTypes.isEmpty() && call.returnType == "Ljava/util/List;") anchors++
            if (call.definingClass == ANCHOR && call.name == "getComponentKey" &&
                call.parameterTypes.isEmpty() && call.returnType == "Ljava/lang/String;") component = true
            if (call.definingClass == "Ljava/util/ArrayList;" && call.name == "<init>" &&
                call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Collection;")) copy = true
        }
        if (instruction.getReference<FieldReference>()?.definingClass == MULTI_TAG) viewOwner = true
    }
    return anchors == 1 && component && viewOwner && copy
}

internal fun MutableMethod.resolveLocationBadgeList(): () -> Unit {
    if (!isLocationBadgeListFactory(this))
        throw PatchException("Hide video overlays: location badge renderer contract changed")
    val instructions = implementation!!.instructions.toList()
    val getter = instructions.indexOfFirst {
        it.getReference<MethodReference>()?.let { call -> call.definingClass == AWEME && call.name == "getAnchors" } == true
    }
    val result = instructions.getOrNull(getter + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || result !is OneRegisterInstruction)
        throw PatchException("Hide video overlays: location badge list result changed")
    val register = result.registerA
    return {
        // Intercept only the renderer's read, never the canonical getter or stored model list.
        addInstructions(getter + 2, """
            invoke-static/range { v$register .. v$register }, Lapp/morphe/extension/tiktok/feedfilter/LocationBadgeFilter;->visibleAnchors(Ljava/util/List;)Ljava/util/List;
            move-result-object v$register
        """.trimIndent())
    }
}

internal fun isFullscreenBind(method: Method): Boolean =
    method.definingClass == FULLSCREEN_COMPONENT && method.name == "onViewCreated" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
        method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.implementation != null

internal fun isLocationCardBind(method: Method, marker: String): Boolean {
    if (marker !in locationCardMarkers || method.returnType != "V" ||
        AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (parameters.size != 4 || parameters[0] != "Lcom/ss/android/ugc/aweme/feed/model/AnchorCommonStruct;" ||
        !parameters[1].startsWith("L") || parameters[2] != "I" ||
        parameters[3] != "Lkotlin/jvm/functions/Function0;") return false
    val strings = method.implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }
        ?: return false
    return marker in strings && "bindData" in strings
}

internal fun isLocationCardView(owner: ClassDef, classOf: (String) -> ClassDef?): Boolean {
    var type: String? = owner.type
    repeat(12) {
        if (type == "Landroid/widget/FrameLayout;") return true
        type = type?.let(classOf)?.superclass ?: return false
    }
    return false
}

/** Resolve all three contracts before returning any write, preserving the native bodies. */
internal fun resolveFeedOverlayControls(
    fullscreen: MutableMethod,
    locations: List<Pair<String, MutableMethod>>,
    classOf: (String) -> ClassDef?,
): () -> Unit {
    if (!isFullscreenBind(fullscreen) || fullscreen.implementation!!.registerCount < 2 ||
        classOf(FULLSCREEN_COMPONENT)?.superclass != "Lcom/ss/android/ugc/feed/platform/cell/BaseCellSlotComponent;")
        throw PatchException("Hide video overlays: incompatible Full screen entry")
    if (locations.map { it.first }.toSet() != locationCardMarkers.toSet() || locations.size != 2)
        throw PatchException("Hide video overlays: expected both location card variants")
    for ((marker, method) in locations) {
        val owner = classOf(method.definingClass)
        if (!isLocationCardBind(method, marker) || method.implementation!!.registerCount < 5 ||
            owner == null || !isLocationCardView(owner, classOf))
            throw PatchException("Hide video overlays: incompatible $marker card")
    }
    return {
        fullscreen.addInstruction(0, "invoke-static/range { p1 .. p1 }, " +
            "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->bindFullscreen(Landroid/view/View;)V")
        locations.forEach { (_, method) ->
            // p0 is the location-only FrameLayout. No model or shared multi-tag host is changed.
            method.addInstruction(0, "invoke-static/range { p0 .. p0 }, " +
                "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;->bindLocation(Landroid/view/View;)V")
        }
    }
}
