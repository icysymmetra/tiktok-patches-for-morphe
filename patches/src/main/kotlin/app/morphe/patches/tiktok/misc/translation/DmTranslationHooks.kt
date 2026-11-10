/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val DM_PREVIEW = "Lapp/morphe/extension/tiktok/translation/DmTranslationPreview;"
internal const val DM_INPUT = "Lcom/ss/android/ugc/aweme/im/chatroom/input/components/inputfield/InputFieldVM;"
internal const val DM_ASSEM = "Lcom/ss/android/ugc/aweme/im/chatroom/input/components/inputfield/InputFieldAssem;"
internal const val DM_COMPOSER = "Lcom/ss/android/ugc/aweme/im/sdk/chat/viewmodel/SingleChatInputViewModel;"
internal const val DM_ABILITY = "Lcom/ss/android/ugc/aweme/im/chatroom/input/components/inputfield/IInputFieldAbility;"
internal const val DM_SESSION = "Lcom/ss/android/ugc/aweme/im/chatroom/api/model/SessionInfo;"
private const val DM = "Translate comments (DM draft preview)"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val EDITOR = "Landroid/widget/EditText;"
private const val VIEW = "Landroid/view/View;"

internal data class DmInputStore(val index: Int, val input: Int, val editor: Int)
internal data class DmPreviewTargets(val viewCreated: Method, val editorStore: DmInputStore,
    val constructor: Method, val cleared: Method)
internal data class DmPreviewPlans(val outgoing: DmPreviewTargets?, val received: ReceivedPreviewTargets?,
    val accounts: DmAccountTargets?)

/** Qualification is read-only. Installation failures must never be treated as optional hosts. */
internal fun findDmPreviewPlans(required: Boolean, classOf: (String) -> ClassDef?,
    omitted: (String, PatchException) -> Unit = { _, _ -> }): DmPreviewPlans {
    fun <T> qualify(feature: String, resolve: () -> T): T? = try { resolve() } catch (changed: PatchException) {
        if (required) throw changed
        omitted(feature, changed)
        null
    }
    val outgoing = qualify("DM draft preview") { findDmPreviewTargets(classOf) }
    val received = qualify("received message preview") { findReceivedPreviewTargets(classOf) }
    if (outgoing == null && received == null) return DmPreviewPlans(null, null, null)
    val accounts = try { findDmAccountTargets(classOf) } catch (changed: PatchException) {
        if (required) throw changed
        if (outgoing != null) omitted("DM draft preview", changed)
        if (received != null) omitted("received message preview", changed)
        return DmPreviewPlans(null, null, null)
    }
    return DmPreviewPlans(outgoing, received, accounts)
}

private fun ClassDef.abi(name: String, result: String, parameters: List<String>, static: Boolean = false): Method =
    methods.singleOrNull { it.name == name && it.returnType == result &&
        it.parameterTypes.map(CharSequence::toString) == parameters &&
        AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) == static }
        ?: throw PatchException("$DM: changed $type->$name ABI")

private fun ClassDef.field(name: String, fieldType: String, static: Boolean = false) {
    if (fields.count { it.name == name && it.type == fieldType && AccessFlags.STATIC.isSet(it.accessFlags) == static } != 1)
        throw PatchException("$DM: changed $type->$name field")
}

internal fun Method.dmEditorStore(): DmInputStore {
    val stores = implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
        val field = instruction.getReference<FieldReference>()
        val registers = instruction as? TwoRegisterInstruction
        if (instruction.opcode == Opcode.IPUT_OBJECT && registers != null && field?.definingClass == DM_INPUT &&
            field.name == "LLJJIII" && field.type == EDITOR && registers.registerA != registers.registerB)
            DmInputStore(index, registers.registerB, registers.registerA) else null
    }.orEmpty()
    return stores.singleOrNull() ?: throw PatchException("$DM: found ${stores.size} cached editor assignments")
}

/** All runtime reflection is held to the shipped typed owners before the first instruction changes. */
internal fun findDmPreviewTargets(classOf: (String) -> ClassDef?): DmPreviewTargets {
    fun owner(type: String) = classOf(type) ?: throw PatchException("$DM: missing $type")
    val input = owner(DM_INPUT)
    if (DM_ABILITY !in input.interfaces) throw PatchException("$DM: changed native input ability owner")
    input.field("LLJJIII", EDITOR)
    input.abi("s21", EDITOR, emptyList())
    val assem = owner(DM_ASSEM)
    assem.field("LLJLLL", "LX/0Ez7;")
    val created = assem.abi("onViewCreated", "V", listOf(VIEW))
    val store = created.dmEditorStore()
    if (created.implementation!!.instructions.withIndex().any { (index, instruction) ->
        instruction.opcode == Opcode.RETURN_VOID && index <= store.index
    }) throw PatchException("$DM: editor assignment no longer precedes every ready return")
    val composer = owner(DM_COMPOSER)
    composer.field("LLJJJ", DM_ABILITY)
    composer.field("LLJJJJLIIL", DM_SESSION)
    val parameters = listOf(DM_ABILITY, "LX/0YPh;", "LX/0dOJ;", "LX/0YPj;", DM_SESSION,
        "LX/0YDk;", "LX/0Xmi;", "LX/0Y0p;", "Lcom/ss/android/ugc/aweme/im/customize/api/ui/wallpaper/IChatWallpaperAbility;")
    val constructor = composer.abi("<init>", "V", parameters)
    val code = constructor.implementation ?: throw PatchException("$DM: no composer constructor body")
    if (code.registerCount > 16) throw PatchException("$DM: composer parameters no longer fit the reviewed invoke")
    val receiver = code.registerCount - parameters.size - 1
    for ((name, fieldType, parameter) in listOf(Triple("LLJJJ", DM_ABILITY, 1), Triple("LLJJJJLIIL", DM_SESSION, 5))) {
        val assignments = code.instructions.filter { it.opcode == Opcode.IPUT_OBJECT &&
            it.getReference<FieldReference>()?.let { field -> field.definingClass == DM_COMPOSER && field.name == name && field.type == fieldType } == true }
        val assignment = assignments.singleOrNull() as? TwoRegisterInstruction
        if (assignment == null || assignment.registerB != receiver || assignment.registerA != receiver + parameter)
            throw PatchException("$DM: composer ownership is no longer its constructor argument")
    }
    val cleared = composer.abi("onCleared", "V", emptyList())
    val session = owner(DM_SESSION)
    listOf("isSingleChat", "isPreviewMode", "isReportPage").forEach { session.abi(it, "Z", emptyList()) }
    session.abi("getConversationId", STRING, emptyList())
    val single = owner("LX/0dli;")
    if (single.superclass != DM_SESSION) throw PatchException("$DM: changed single-peer session")
    single.abi("getSingleChatFromUserId", STRING, emptyList())
    validateNativeDmTranslation(classOf)
    if (listOf(created, constructor, cleared).any { method -> method.implementation?.instructions?.any { it.opcode == Opcode.RETURN_VOID } != true })
        throw PatchException("$DM: missing native lifecycle return")
    return DmPreviewTargets(created, store, constructor, cleared)
}

/** Both previews use this adapter, even when the other preview's UI owners are unavailable. */
internal fun validateNativeDmTranslation(classOf: (String) -> ClassDef?) {
    fun owner(type: String) = classOf(type) ?: throw PatchException("Translate comments (DM transport): missing $type")
    val service = owner("LX/0CHK;")
    service.field("LIZIZ", service.type, true)
    service.abi("LJIIZILJ", "Z", emptyList())
    val language = owner("LX/03j7;")
    language.field("LIZIZ", language.type, true)
    language.abi("LLILLIZIL", STRING, emptyList())
    language.abi("LLFII", "[Ljava/lang/String;", emptyList())
    owner("LX/1O0d;").abi("LIZ", "LX/1O0f;", emptyList(), true)
    val api = owner("LX/1O0f;")
    if (!AccessFlags.INTERFACE.isSet(api.accessFlags)) throw PatchException("$DM: changed native request interface")
    api.abi("LJ", "LX/02tl;", listOf(STRING, STRING, STRING))
    owner("LX/02tl;").abi("LIZ", "V", listOf("LX/02tm;"))
    val observer = owner("LX/02tm;")
    if (!AccessFlags.INTERFACE.isSet(observer.accessFlags)) throw PatchException("$DM: changed native observer")
    observer.abi("onSubscribe", "V", listOf("LX/02ty;"))
    observer.abi("onNext", "V", listOf(OBJECT))
    observer.abi("onError", "V", listOf("Ljava/lang/Throwable;"))
    observer.abi("onComplete", "V", emptyList())
    val disposable = owner("LX/02ty;")
    if (!AccessFlags.INTERFACE.isSet(disposable.accessFlags)) throw PatchException("$DM: changed native cancellation")
    disposable.abi("dispose", "V", emptyList())
    disposable.abi("isDisposed", "Z", emptyList())
    val response = owner("Lcom/ss/android/ugc/aweme/translation/model/MultiTranslationResultDM;")
    response.field("statusCode", "Ljava/lang/Integer;")
    response.field("statusMsg", STRING)
    response.field("results", "Ljava/util/List;")
    val item = owner("Lcom/ss/android/ugc/aweme/translation/model/TranslationResultDM;")
    item.field("translationStatusCode", "Ljava/lang/Integer;")
    item.field("originalText", STRING)
    item.field("translatedText", STRING)
    item.field("textExtraList", "Ljava/util/List;")
}

internal fun MutableMethod.captureDmEditor(store: DmInputStore) {
    // Add after the real store, so exceptions before it cannot leave a fabricated editor owner.
    val returns = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    returns.asReversed().forEach { index -> addInstructionsAtControlFlowLabel(index,
        "invoke-static/range {p0 .. p0}, $DM_PREVIEW->inputReady($OBJECT)V") }
    addInstructions(store.index + 1, "invoke-static {v${store.input}, v${store.editor}}, $DM_PREVIEW->bindInput($OBJECT$VIEW)V")
}

internal fun MutableMethod.captureDmComposer() {
    implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.asReversed().forEach { index ->
        addInstructionsAtControlFlowLabel(index, "invoke-static {p0, p1, p5}, $DM_PREVIEW->composerCreated($OBJECT$OBJECT$OBJECT)V")
    }
}

internal fun MutableMethod.retireDmComposer() = addInstructions(0,
    "invoke-static/range {p0 .. p0}, $DM_PREVIEW->composerCleared($OBJECT)V")

internal fun BytecodePatchContext.hookDmTranslationPreview(targets: DmPreviewTargets) {
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    mutable(targets.viewCreated).captureDmEditor(targets.editorStore)
    mutable(targets.constructor).captureDmComposer()
    mutable(targets.cleared).retireDmComposer()
}
