package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags

private object MainEffectiveListFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;",
    name = "getItems", parameters = emptyList(), returnType = "Ljava/util/List;",
)
private object WhyThisPostPanelFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/tiktok/pns/feedsafety/whythisvideo/AdjustableWhyThisVideoManager;",
    parameters = listOf("Landroid/content/Context;", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;",
        "Lcom/ss/android/ugc/aweme/feed/model/RecReasonsStruct;"),
    returnType = "V", strings = listOf("panel_source", "recReasons must be set before calling validateAndShow()"),
)
private object WhyThisPostResponseConsumerFingerprint : Fingerprint(
    name = "onResponse", returnType = "V", strings = listOf("why_this_post_api_error", "reasons_missing"),
)
private object WhyThisPostLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/tiktok/pns/feedsafety/whythispost/ui/ContainerFragment;",
    name = "onGlobalLayout", parameters = emptyList(), returnType = "V",
)
private object WhyThisPostValidationRequestCtorFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/tiktok/pns/feedsafety/whythisvideo/models/WTVValidationRequest;",
    name = "<init>",
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/RecReasonsStruct;"),
    returnType = "V",
)
private object WhyThisPostValidationResponseCtorFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/tiktok/pns/feedsafety/whythisvideo/WTVValidationResponse;",
    name = "<init>",
    parameters = listOf(
        "Lcom/ss/android/ugc/tiktok/pns/feedsafety/whythisvideo/Extra;",
        "Lcom/ss/android/ugc/aweme/feed/model/LogPbBean;",
        "Lcom/ss/android/ugc/aweme/feed/model/RecReasonsStruct;",
        "I", "Ljava/lang/String;",
    ),
    returnType = "V",
)
internal val feedObservationProbeHooksPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4623())
    execute {
        val layout = WhyThisPostLayoutFingerprint.method
        val reasonRead = layout.implementation!!.instructions.withIndex().singleOrNull {
            val reference = (it.value as? ReferenceInstruction)?.reference as? MethodReference
            it.value.opcode == Opcode.INVOKE_VIRTUAL && reference?.definingClass ==
                "Lcom/ss/android/ugc/aweme/feed/model/Aweme;" && reference.name == "getRecReasonsStruct"
        } ?: throw PatchException("Why-this-post layout no longer reads the selected item's reasons")
        val itemRegister = (reasonRead.value as FiveRegisterInstruction).registerC
        layout.addInstructionsAtControlFlowLabel(reasonRead.index,
            "invoke-static/range {v$itemRegister .. v$itemRegister}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->whyThisPostLayout(Ljava/lang/Object;)V")
        WhyThisPostPanelFingerprint.method.addInstruction(0,
            "invoke-static/range {p2 .. p4}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->whyThisPostPanel(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V")
        val responseConsumer = WhyThisPostResponseConsumerFingerprint.method
        val responseCast = responseConsumer.implementation!!.instructions.withIndex().firstOrNull {
            it.value.opcode == Opcode.CHECK_CAST &&
                ((it.value as? ReferenceInstruction)?.reference as? TypeReference)?.type ==
                "Lcom/ss/android/ugc/tiktok/pns/feedsafety/whythisvideo/WTVValidationResponse;"
        } ?: throw PatchException("Why-this-post response consumer no longer casts the validation response")
        val responseRegister = (responseCast.value as OneRegisterInstruction).registerA
        responseConsumer.addInstruction(responseCast.index + 1,
            "invoke-static/range {v$responseRegister .. v$responseRegister}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->whyThisPostResponseConsumed(Ljava/lang/Object;)V")
        val profileTransform = ProfileNativeListTransformFingerprint.method
        val profileItemsRegister = if (AccessFlags.STATIC.isSet(profileTransform.accessFlags)) "p0" else "p1"
        profileTransform.addInstruction(0,
            "invoke-static {$profileItemsRegister}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->profileNativeTransform(Ljava/lang/Object;)V")
        listOf(CacheChainDeliveryFingerprint.method to "cacheChain",
            FinalFeedInsertionFingerprint.method to "finalInsert",
            FollowFeedPresenterPostProcessFingerprint.method to "followPost",
            ProfileDetailAdEventFingerprint.method to "profileDetail").forEach { (method, callback) ->
            method.addInstruction(0,
                "invoke-static/range {p1 .. p1}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->$callback(Ljava/lang/Object;)V")
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->enable()V")
        listOf(MainEffectiveListFingerprint.method to "mainGetter",
            FollowFeedListGetItemsFingerprint.method to "followGetter").forEach { (method, callback) ->
            val returns = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
            returns.asReversed().forEach { index ->
                val register = (method.implementation!!.instructions[index] as OneRegisterInstruction).registerA
                method.addInstructionsAtControlFlowLabel(index,
                    "invoke-static/range {v$register .. v$register}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->$callback(Ljava/lang/Object;)V")
            }
        }
        listOf(
            WhyThisPostValidationRequestCtorFingerprint.method to "whyThisPostRequest",
            WhyThisPostValidationResponseCtorFingerprint.method to "whyThisPostResponse",
        ).forEach { (method, callback) ->
            method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.asReversed()
                .forEach { index ->
                    method.addInstructionsAtControlFlowLabel(index,
                        "invoke-static/range {p0 .. p0}, Lapp/morphe/extension/tiktok/diagnostics/FeedObservationProbe;->$callback(Ljava/lang/Object;)V")
                }
        }
    }
}
