/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtext

import app.morphe.patcher.patch.PatchException
import app.morphe.util.RegisterLiveness
import app.morphe.patcher.util.getReference
import app.morphe.util.namedRegisters
import app.morphe.patcher.util.p0Register
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val DESCRIPTION = "Lcom/ss/android/ugc/aweme/feed/assem/desc/VideoDescAssem;"
internal const val AUTHOR = "Lcom/ss/android/ugc/aweme/feed/assem/videoauthorinfo/VideoAuthorInfoRelationAssem;"
internal const val TITLE_INFLATER = "Lcom/by/andInflater/common_feed_layout_video_title;"
internal const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val VIEW = "Landroid/view/View;"
internal const val LAYOUT = "Landroid/text/Layout;"
private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val PAINT = "Landroid/graphics/Paint;"
private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
private const val WHAT = "Feed text sizes"
// 47.4.3's ids for the caption text, the creator name (`title`) and the post date beside it.
internal val DESCRIPTION_IDS = setOf(0x7f0a206a)
internal val TITLE_IDS = setOf(0x7f0a882c)
internal val POST_TIME_IDS = setOf(0x7f0a93e9)

internal data class SizeInput(val index: Int, val builderRegister: Int, val ownerLocal: Int, val argumentLocal: Int)
internal data class CacheBypass(val index: Int, val fallback: Int, val ownerRegister: Int, val resultLocal: Int)
internal data class FeedTextMembers(
    val controller: ClassDef,
    val descriptionView: FieldReference,
    val layoutFactory: Method,
    val sizeInputs: List<SizeInput>,
    val layoutDispatch: Method,
    val bypass: CacheBypass,
    val builder: ClassDef,
    val builderPaint: FieldReference,
    val builderSize: FieldReference,
    val builderCache: FieldReference,
    val build: Method,
    val refreshOriginal: Method,
    val refreshTranslated: Method,
    val translationOwner: FieldReference,
    val translationFlag: FieldReference,
    val layoutCaches: List<FieldReference>,
    val authorLoader: Method,
    val authorStore: Int,
    val authorView: FieldReference,
    val dateView: FieldReference,
    val authorBind: Method,
)

private fun <T> List<T>.one(what: String): T = singleOrNull()
    ?: throw PatchException("$WHAT: expected one $what, found $size")
private fun Method.instructions() = implementation?.instructions?.toList().orEmpty()
private fun Method.calls() = instructions().mapNotNull { it.getReference<MethodReference>() }
private fun Method.fields() = instructions().mapNotNull { it.getReference<FieldReference>() }
private fun Method.parameters() = parameterTypes.map(CharSequence::toString)
private fun Method.isInstance() = !AccessFlags.STATIC.isSet(accessFlags)
private fun Method.hasCall(owner: String, name: String) = calls().any { it.definingClass == owner && it.name == name }
private fun Method.hasLiteral(ids: Set<Int>) = instructions().any {
    it is NarrowLiteralInstruction && it.narrowLiteral in ids
}
private fun ClassDef.method(reference: MethodReference) = methods.filter {
    it.name == reference.name && it.parameters() == reference.parameterTypes.map(CharSequence::toString) &&
        it.returnType == reference.returnType
}.one("declaration of $reference")
private fun ClassDef.hasLayoutWrapperShape() = fields.count { it.type == LAYOUT } == 1 &&
    fields.count { it.type == "Ljava/lang/String;" } == 1 && fields.count { it.type == "Z" } == 2

/** The named native owner loads this exact resource and immediately stores its cast view. */
private fun Method.loadedView(ids: Set<Int>): Pair<Int, FieldReference> {
    val code = instructions()
    return code.indices.mapNotNull { index ->
        val id = code[index] as? NarrowLiteralInstruction ?: return@mapNotNull null
        if (id.narrowLiteral !in ids || index + 4 >= code.size) return@mapNotNull null
        val call = code[index + 1].getReference<MethodReference>() ?: return@mapNotNull null
        val result = code[index + 2] as? OneRegisterInstruction ?: return@mapNotNull null
        val store = code[index + 4] as? TwoRegisterInstruction ?: return@mapNotNull null
        val field = code[index + 4].getReference<FieldReference>() ?: return@mapNotNull null
        if (call.name != "findViewById" || call.parameterTypes != listOf("I") || call.returnType != VIEW ||
            code[index + 2].opcode != Opcode.MOVE_RESULT_OBJECT || code[index + 3].opcode != Opcode.CHECK_CAST ||
            code[index + 4].opcode != Opcode.IPUT_OBJECT || store.registerA != result.registerA ||
            field.definingClass != definingClass || code[index + 3].getReference<TypeReference>()?.type != field.type
        ) return@mapNotNull null
        index + 4 to field
    }.one("native resource-to-view store in $this")
}

/** All discoveries follow the native owner's calls. No obfuscated class name is an input. */
internal fun resolveFeedText(classOf: (String) -> ClassDef?): FeedTextMembers {
    fun requireClass(type: String) = classOf(type) ?: throw PatchException("$WHAT: missing $type")
    val component = requireClass(DESCRIPTION)
    // 47.4.3 erased the bind's parameter to Object (`l4`) and casts it to VideoItemParams first.
    val descBind = component.methods.filter {
        it.isInstance() && it.parameters().let { p -> p == listOf(VIDEO_ITEM_PARAMS) || p == listOf("Ljava/lang/Object;") } &&
            it.returnType == "V" && it.hasLiteral(DESCRIPTION_IDS) && it.hasCall(AWEME, "getDesc")
    }.one("description component bind")
    val controller = descBind.calls().mapNotNull { reference ->
        if (reference.parameterTypes != listOf(AWEME) || reference.returnType != "V") return@mapNotNull null
        val owner = classOf(reference.definingClass) ?: return@mapNotNull null
        if (component.fields.none { it.type == owner.type } || owner.fields.none { it.type == DESCRIPTION } ||
            !owner.method(reference).isInstance()
        ) null else owner
    }.distinctBy { it.type }.one("description controller called by the component")
    val descLoader = controller.methods.filter { it.parameters() == listOf(VIEW) && it.hasLiteral(DESCRIPTION_IDS) }
        .one("description view loader")
    val descView = descLoader.loadedView(DESCRIPTION_IDS).second
    val factory = controller.methods.filter {
        it.isInstance() && it.parameters().let { p -> p.size == 5 && p.drop(1) == listOf("I", AWEME, "Z", "Z") } &&
            classOf(it.returnType)?.fields?.count { field -> field.type == LAYOUT } == 3
    }.one("native full/collapsed description layout factory")
    val builder = factory.instructions().filter { it.opcode == Opcode.NEW_INSTANCE }.mapNotNull {
        it.getReference<TypeReference>()?.type?.let(classOf)
    }.filter { owner -> owner.fields.any { classOf(it.type)?.superclass == "Landroid/text/TextPaint;" } }
        .distinctBy { it.type }.one("description builder")
    val paint = builder.fields.filter { classOf(it.type)?.superclass == "Landroid/text/TextPaint;" }
        .toList().one("builder TextPaint")
    val font = builder.methods.filter { method ->
        method.parameters() == listOf("I") && method.returnType == "V" && method.calls().any { call ->
            call.definingClass == paint.type && classOf(paint.type)?.method(call)?.let {
                it.hasCall(PAINT, "setTextSize") && it.hasCall(PAINT, "setTypeface")
            } == true
        }
    }.one("builder's native font input")
    val inputs = factory.instructions().withIndex().filter { (_, instruction) ->
        instruction.getReference<MethodReference>()?.toString() == font.toString()
    }.map { (index, instruction) ->
        val register = instruction.namedRegisters().first()
        val owner = freeLocal(factory, index + 1, setOf(register))
        SizeInput(index + 1, register, owner,
            if (register < 16) register else freeLocal(factory, index + 1, setOf(register, owner)))
    }
    if (inputs.size != 2 || inputs.map { it.builderRegister }.distinct().size != 2) {
        throw PatchException("$WHAT: expected two independently owned full/collapsed builder inputs, found $inputs")
    }
    val build = builder.methods.filter {
        it.parameters().isEmpty() && it.returnType == LAYOUT &&
            it.hasCall("Landroid/text/StaticLayout\$Builder;", "obtain") && it.hasCall("Landroid/util/LruCache;", "get") &&
            it.hasCall("Landroid/util/LruCache;", "put")
    }.one("native cached line breaker")
    val code = build.instructions()
    val cacheGet = code.indexOfFirst { it.getReference<MethodReference>()?.let {
        it.definingClass == "Landroid/util/LruCache;" && it.name == "get"
    } == true }
    val cache = code.take(cacheGet).filter { it.opcode == Opcode.IGET_BOOLEAN }.mapNotNull {
        it.getReference<FieldReference>()?.takeIf { field -> field.definingClass == builder.type }
    }.one("line breaker's cache-enabled input")
    val customSize = code.indices.filter { code[it].getReference<MethodReference>()?.let { call ->
        call.definingClass == PAINT && call.name == "setTextSize" && call.parameterTypes == listOf("F")
    } == true }.mapNotNull { index ->
        val value = code[index].namedRegisters().getOrNull(1) ?: return@mapNotNull null
        code.take(index).lastOrNull {
            it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == value
        }?.getReference<FieldReference>()?.takeIf { it.definingClass == builder.type && it.type == "F" }
    }.one("native explicit size input used before line breaking")
    val refresh = controller.methods.filter { method ->
        method.parameters().isEmpty() && method.returnType == "V" && method.instructions().any {
            it.getReference<StringReference>()?.string == " turn back original source"
        }
    }
    if (refresh.size != 2) throw PatchException("$WHAT: expected original and translated native refresh methods")
    val refreshByFlag = refresh.associateBy { method ->
        val write = method.instructions().withIndex().filter { (_, instruction) -> instruction.opcode == Opcode.IPUT_BOOLEAN }
            .one("translation-state write in $method")
        constantBefore(method, write.index, (write.value as OneRegisterInstruction).registerA)
    }
    if (refreshByFlag.keys != setOf(0, 1)) throw PatchException("$WHAT: refresh methods no longer select original/translated state")
    val original = refreshByFlag.getValue(0)
    val translated = refreshByFlag.getValue(1)
    val flag = original.instructions().single { it.opcode == Opcode.IPUT_BOOLEAN }.getReference<FieldReference>()!!
    if (translated.instructions().single { it.opcode == Opcode.IPUT_BOOLEAN }.getReference<FieldReference>()?.toString() != flag.toString()) {
        throw PatchException("$WHAT: refresh methods use different translation states")
    }
    val translationOwner = original.fields().filter { it.definingClass == controller.type && it.type == flag.definingClass }
        .distinctBy { it.toString() }.one("native translation owner")
    val cacheType = refresh.flatMap { it.fields() }.filter { it.definingClass == controller.type }
        .mapNotNull { classOf(it.type) }.filter { it.hasLayoutWrapperShape() }.distinctBy { it.type }
        .one("description layout cache wrapper").type
    val caches = controller.fields.filter { it.type == cacheType }.toList()
    if (caches.size != 6) throw PatchException("$WHAT: expected six owned description layout caches, found ${caches.size}")
    val dispatch = controller.methods.filter {
        it.parameters() == listOf(factory.parameterTypes.first().toString(), "I", "Z", AWEME, "Z", "Z") &&
            it.returnType == "Lkotlin/Pair;" && it.calls().any { call -> call.toString() == factory.toString() }
    }.one("native cached description layout dispatch")
    val bypass = dispatch.cacheBypass(factory)

    val author = requireClass(AUTHOR)
    val authorLoader = author.methods.filter {
        it.name == "onViewCreated" && it.parameters() == listOf(VIEW) && it.returnType == "V"
    }.one("native author view loader")
    val (authorStore, authorView) = authorLoader.loadedView(TITLE_IDS)
    // The post date sits in the same row, loaded by the same owner before its name.
    val (dateStore, dateView) = authorLoader.loadedView(POST_TIME_IDS)
    if (dateStore > authorStore) throw PatchException("$WHAT: the post date is now loaded after the author name")
    if (dateView.type != authorView.type) throw PatchException("$WHAT: the post date is no longer the name's kind of text view")
    val inflater = requireClass(TITLE_INFLATER)
    if (inflater.methods.none { it.hasLiteral(TITLE_IDS) && it.hasCall(TEXT_VIEW, "setMaxLines") &&
            it.hasCall(TEXT_VIEW, "setEllipsize") }) throw PatchException("$WHAT: native author title inflater changed")
    if (inflater.methods.none { it.hasLiteral(POST_TIME_IDS) }) throw PatchException("$WHAT: native post date inflater changed")
    val bind = author.methods.filter {
        it.isInstance() && it.parameters() == listOf(AWEME) && it.returnType == "V" &&
            it.fields().any { field -> field.toString() == authorView.toString() } &&
            it.hasCall(PAINT, "measureText") && it.hasCall(TEXT_VIEW, "setText")
    }.one("native author measurement/bind")
    // The cell's recycled-item entry is onBind(Object) on 47.0.3 and an obfuscated (Object)V on 47.1.x.
    if (author.methods.none { it.parameters() == listOf("Ljava/lang/Object;") && it.returnType == "V" &&
            it.calls().any { call -> call.toString() == bind.toString() } }) {
        throw PatchException("$WHAT: recycled author cells no longer use the resolved binder")
    }
    // The bridges access native fields directly. Reject a host that makes them inaccessible.
    for (field in listOf(descView, paint, customSize, cache, translationOwner, flag, authorView, dateView) + caches) {
        val declaration = requireClass(field.definingClass).fields.filter { it.name == field.name }.toList().one("field $field")
        if (!AccessFlags.PUBLIC.isSet(declaration.accessFlags)) throw PatchException("$WHAT: $field isn't public")
    }
    for (method in listOf(original, translated, bind)) {
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags)) throw PatchException("$WHAT: $method isn't public")
    }
    return FeedTextMembers(controller, descView, factory, inputs, dispatch, bypass, builder, paint, customSize,
        cache, build, original, translated, translationOwner, flag, caches, authorLoader, authorStore, authorView,
        dateView, bind)
}

private fun constantBefore(method: Method, index: Int, register: Int): Int {
    val instruction = method.instructions().take(index).lastOrNull {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
    }
    return (instruction as? NarrowLiteralInstruction)?.narrowLiteral
        ?: throw PatchException("$WHAT: native translation selector is no longer a constant")
}

internal fun freeLocal(method: Method, index: Int, reserved: Set<Int> = emptySet()): Int {
    val live = RegisterLiveness.of(method).liveInto(index) + reserved
    return (0 until minOf(16, method.p0Register)).firstOrNull { it !in live }
        ?: throw PatchException("$WHAT: no dead low local at $index in $method (including exception handlers)")
}

/** The straight-line prefix must have copied every fallback argument before the added branch. */
internal fun Method.cacheBypass(factory: Method): CacheBypass {
    val code = instructions()
    val fallback = code.indices.filter { code[it].getReference<MethodReference>()?.toString() == factory.toString() }
        .one("uncached native description fallback")
    val entry = code.indexOfFirst { it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ }
    if (entry < 0) throw PatchException("$WHAT: no cache dispatch entry in $this")
    val aliases = (0 until implementation!!.registerCount).associateWith { it }.toMutableMap()
    for (instruction in code.take(entry)) {
        if (instruction.opcode.name.startsWith("invoke") || instruction.opcode.name.startsWith("goto")) {
            throw PatchException("$WHAT: description dispatch no longer has a straight-line parameter prefix")
        }
        if (instruction.opcode.setsRegister()) {
            val destination = (instruction as OneRegisterInstruction).registerA
            aliases[destination] = if (instruction.opcode.name.startsWith("move") && instruction is TwoRegisterInstruction) {
                aliases.getValue(instruction.registerB)
            } else -1
        }
    }
    val registers = code[fallback].namedRegisters()
    val expected = listOf(p0Register, p0Register + 1, p0Register + 2, p0Register + 4, p0Register + 5, p0Register + 6)
    if (registers.map { aliases[it] } != expected) {
        throw PatchException("$WHAT: description fallback arguments aren't initialized at dispatch entry")
    }
    // The bypass jumps from entry straight to the fallback. Anything the code after the fallback
    // reads must hold the same value on both paths, or ART rejects the class at the merge.
    val live = RegisterLiveness.of(this).liveInto(fallback)
    val skipped = code.subList(entry, fallback).filter { it.opcode.setsRegister() }.flatMap {
        val register = (it as OneRegisterInstruction).registerA
        if (it.opcode.setsWideRegister()) listOf(register, register + 1) else listOf(register)
    }.toSet()
    if (live.any { it in skipped }) {
        throw PatchException("$WHAT: description fallback reads ${live.filter { it in skipped }} set on the skipped path")
    }
    // The flag local is dead at entry, so if the fallback read it, the skipped path would set it.
    return CacheBypass(entry, fallback, registers.first(), freeLocal(this, entry, registers.toSet()))
}
