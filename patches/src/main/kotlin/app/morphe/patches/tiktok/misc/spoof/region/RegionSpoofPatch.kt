/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.spoof.region

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.spoof.sim.simSpoofPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/spoof/region/RegionSpoof;"
private object RegionService : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/app/services/RegionService;",
    name = "getRegion", parameters = emptyList(), returnType = "Ljava/lang/String;",
)

/**
 * AppLog's common-parameter builder, an instance method taking (Context, isApi, Map, level): the
 * one place the query fields every TTNet and AppLog request carries are put together, from the
 * device ids, the fields cached once at startup and the network_common_params feature map. R8
 * renames it on every build; the two query keys only it loads as literals find it
 * (RegionSpoofAnchorsTest). carrier_region, sys_region and region in that map are the region hub's
 * getters, which the hooks below already wrap; current_region, residence and carrier_region_v2
 * come from caches no getter hook reaches. The map also holds copies of sys_region and
 * timezone_name that TikTok took once at start-up, through the wrapped getters, so they carry the
 * preset until the process ends. A sign-in puts the live answers back (RegionSpoof.requestParams).
 */
internal object CommonParamsBuilderFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Z", "Ljava/util/Map;", "L"),
    strings = listOf("ssmix", "_rticket").map { string(it, StringComparisonType.CONTAINS) },
)

internal const val COMMON_PARAMS_HANDLER = "Lcom/ss/android/ugc/aweme/net/partner/CommonParamsTTNetHandler;"
internal const val REGEX_MATCHES = "Lkotlin/text/Regex;->matches(Ljava/lang/CharSequence;)Z"

/**
 * TTNet's common-parameter handler: for each request it hands a new map to a static
 * (Map, boolean) fill, which reaches the builder above on the same thread, and copies the map
 * into the request's query. It's the only place that sees both the request's path and its common
 * parameters being built. R8 renames the method; the fill and the path read find it.
 */
internal object CommonParamsHandlerFingerprint : Fingerprint(
    definingClass = COMMON_PARAMS_HANDLER,
    returnType = "V",
    custom = { method, _ -> commonParamsFill(method) != null },
)

/** Where the handler makes the map and fills it, and how it reads the request's path. */
internal class CommonParamsFill(
    val mapAt: Int,
    val mapRegister: Int,
    val fillAt: Int,
    val requestField: FieldReference,
    val urlField: FieldReference,
    val pathGetter: MethodReference,
)

/**
 * The handler's fill, or null when [method] doesn't have exactly this shape: `new LinkedHashMap`,
 * its constructor, then one static `(Map, boolean)V` call taking it, and a path read off p1 as
 * `iget-object` (the request), `iget-object` (its URL) and a no-argument String getter whose
 * result TikTok matches its hpack-optimization pattern against. The getter joins the URL's path
 * segments with "/" (RegionSpoofAnchorsTest).
 */
internal fun commonParamsFill(method: Method): CommonParamsFill? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.size != 2) return null
    val body = method.implementation ?: return null
    val instructions = body.instructions.toList()
    val request = body.registerCount - 2
    val fillAt = instructions.indices.filter { index ->
        val instruction = instructions[index]
        val reference = instruction.getReference<MethodReference>() ?: return@filter false
        instruction.opcode == Opcode.INVOKE_STATIC && reference.returnType == "V" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Map;", "Z")
    }.singleOrNull() ?: return null
    val map = (instructions[fillAt] as FiveRegisterInstruction).registerC
    val mapAt = fillAt - 2
    val created = instructions.getOrNull(mapAt) ?: return null
    if (created.opcode != Opcode.NEW_INSTANCE || (created as OneRegisterInstruction).registerA != map ||
        created.getReference<TypeReference>()?.type != "Ljava/util/LinkedHashMap;"
    ) {
        return null
    }
    val init = instructions[fillAt - 1]
    if (init.opcode != Opcode.INVOKE_DIRECT || init.getReference<MethodReference>()?.name != "<init>" ||
        (init as FiveRegisterInstruction).registerC != map
    ) {
        return null
    }

    val matches = instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.toString() == REGEX_MATCHES
    }
    if (matches < 0) return null
    val path = (instructions[matches] as FiveRegisterInstruction).registerD
    val result = (matches - 1 downTo 0).firstOrNull { writes(instructions[it], path) } ?: return null
    if (instructions[result].opcode != Opcode.MOVE_RESULT_OBJECT || result < 3) return null
    val call = instructions[result - 1]
    val getter = call.getReference<MethodReference>() ?: return null
    if (call.opcode != Opcode.INVOKE_VIRTUAL || getter.parameterTypes.isNotEmpty() ||
        getter.returnType != "Ljava/lang/String;"
    ) {
        return null
    }
    val url = instructions[result - 2]
    val requestRead = instructions[result - 3]
    if (url.opcode != Opcode.IGET_OBJECT || requestRead.opcode != Opcode.IGET_OBJECT) return null
    url as TwoRegisterInstruction
    requestRead as TwoRegisterInstruction
    if ((call as FiveRegisterInstruction).registerC != url.registerA || url.registerB != requestRead.registerA ||
        requestRead.registerB != request
    ) {
        return null
    }
    val urlField = url.getReference<FieldReference>() ?: return null
    val requestField = requestRead.getReference<FieldReference>() ?: return null
    if (requestField.type != urlField.definingClass || urlField.type != getter.definingClass) return null
    return CommonParamsFill(mapAt, map, fillAt, requestField, urlField, getter)
}

internal const val TOKEN_INTERCEPTOR = "Lcom/ss/android/ugc/aweme/net/interceptor/TokenSdkCommonParamsInterceptorTTNet;"
internal const val REQUEST_GET_URL = "Lcom/bytedance/retrofit2/client/Request;->getUrl()Ljava/lang/String;"

/** The three account-token URLs the interceptor fills common parameters for itself. */
internal val TOKEN_PATHS = listOf("/passport/token/beat/", "/passport/token/change/", "/passport/user/logout/")

/**
 * The token SDK's interceptor. For the token heartbeat, token change and logout it fills a new
 * map through the same static (Map, boolean) fill the handler uses and adds it to the URL, which
 * the handler's path read never sees. The class keeps its name; R8 renames the chain type only.
 */
internal object TokenInterceptorFingerprint : Fingerprint(
    definingClass = TOKEN_INTERCEPTOR,
    name = "intercept",
    strings = TOKEN_PATHS.map { string(it, StringComparisonType.CONTAINS) },
)

/** Where the interceptor makes its map and fills it, and which register holds the request. */
internal class TokenFill(val mapAt: Int, val mapRegister: Int, val fillAt: Int, val requestRegister: Int)

/**
 * The interceptor's fill, or null when [method] doesn't have exactly this shape: one static
 * `(Map, boolean)V` call, on a `new HashMap` made and constructed just before it (a constant for
 * the boolean may sit between), with the request's last `getUrl()` before the map on a register
 * nothing writes until the fill. Both registers fit a four-bit call.
 */
internal fun tokenFill(method: Method): TokenFill? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val fillAt = instructions.indices.filter { index ->
        val reference = instructions[index].getReference<MethodReference>() ?: return@filter false
        instructions[index].opcode == Opcode.INVOKE_STATIC && reference.returnType == "V" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Map;", "Z")
    }.singleOrNull() ?: return null
    val map = (instructions[fillAt] as FiveRegisterInstruction).registerC
    val mapAt = (fillAt - 1 downTo maxOf(0, fillAt - 3)).firstOrNull { instructions[it].opcode == Opcode.NEW_INSTANCE }
        ?: return null
    val created = instructions[mapAt] as OneRegisterInstruction
    if (created.registerA != map || instructions[mapAt].getReference<TypeReference>()?.type != "Ljava/util/HashMap;") {
        return null
    }
    val init = instructions[mapAt + 1]
    if (init.opcode != Opcode.INVOKE_DIRECT || init.getReference<MethodReference>()?.name != "<init>" ||
        (init as FiveRegisterInstruction).registerC != map
    ) {
        return null
    }
    if ((mapAt + 2 until fillAt).any { writes(instructions[it], map) }) return null
    val urlRead = (mapAt - 1 downTo 0).firstOrNull {
        instructions[it].opcode == Opcode.INVOKE_VIRTUAL &&
            instructions[it].getReference<MethodReference>()?.toString() == REQUEST_GET_URL
    } ?: return null
    val request = (instructions[urlRead] as FiveRegisterInstruction).registerC
    if ((urlRead + 1 until mapAt).any { writes(instructions[it], request) }) return null
    if (map > 15 || request > 15) return null
    return TokenFill(mapAt, map, fillAt, request)
}

internal const val APP_LOG = "Lcom/ss/android/common/applog/AppLog;"
private const val STRING = "Ljava/lang/String;"
private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"

/**
 * AppLog's `addCommonParams(String, boolean)`: it copies a URL into a StringBuilder, runs the URL
 * funnel into the common-parameter builder and hands the URL back. The callers in the declared
 * builds pass page, media, log and push URLs, and none of them is a /passport/ one, but the URL is
 * the caller's to choose, so it is read here like the handler's. AppLog is not renamed by R8.
 */
internal object AppLogUrlFingerprint : Fingerprint(
    definingClass = APP_LOG,
    name = "addCommonParams",
    returnType = STRING,
    parameters = listOf(STRING, "Z"),
)

/**
 * AppLog's `appendCommonParams(StringBuilder, boolean)`: the same funnel, on a URL the caller is
 * still building. The StringBuilder holds the URL so far.
 */
internal object AppLogBuilderFingerprint : Fingerprint(
    definingClass = APP_LOG,
    name = "appendCommonParams",
    returnType = "V",
    parameters = listOf(STRING_BUILDER, "Z"),
)

/** The URL funnel an AppLog entry point calls, and where the entry point returns. */
internal class AppLogEntry(val funnel: MethodReference, val returns: List<Int>)

/**
 * [method]'s entry, or null when it isn't a static `(argument, boolean)` method that makes exactly
 * one static `(StringBuilder, boolean, level)V` call, has a [returnOpcode] to hook, and keeps p0
 * within a four-bit operand.
 */
internal fun appLogEntry(method: Method, argument: String, returnOpcode: Opcode): AppLogEntry? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags)) return null
    if (method.parameterTypes.map(CharSequence::toString) != listOf(argument, "Z")) return null
    val body = method.implementation ?: return null
    if (body.registerCount - 2 > 15) return null
    val instructions = body.instructions.toList()
    val funnel = instructions.mapNotNull { instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@mapNotNull null
        if (instruction.opcode != Opcode.INVOKE_STATIC || reference.returnType != "V") return@mapNotNull null
        val types = reference.parameterTypes.map(CharSequence::toString)
        if (types.size == 3 && types[0] == STRING_BUILDER && types[1] == "Z") reference else null
    }.singleOrNull() ?: return null
    val returns = instructions.indices.filter { instructions[it].opcode == returnOpcode }
    if (returns.isEmpty()) return null
    return AppLogEntry(funnel, returns)
}

internal const val COMMON_API = "Lcom/ss/android/ugc/aweme/net/CommonApi;"

/**
 * Where a JS bridge request helper fills the body of a POST, and which field of its request
 * holds the URL: the page chooses that URL, so a /passport/ one can come through. The URL read
 * is copied in front of the fill, onto the register that read writes.
 */
internal class ProxyFill(val fillAt: Int, val urlRegister: Int, val objectRegister: Int, val urlField: FieldReference)

/**
 * The JS request helper's fill, or null when [method] isn't a static one-argument method returning
 * Object with this shape: one static `(Map, boolean)V` fill, then only field reads, then a
 * `doPost` on CommonApi taking `(String, ..., Map)` with that map. One of the field reads writes
 * the URL's register and nothing else between the fill and the call reads it, so that register is
 * free at the fill. R8 renames the helper's classes and the numbered methods the Callable lambdas
 * were moved to; the fill, CommonApi and the String field find them.
 */
internal fun proxyFill(method: Method): ProxyFill? {
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.size != 1 ||
        method.returnType != "Ljava/lang/Object;"
    ) {
        return null
    }
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val fillAt = instructions.indices.filter { index ->
        val reference = instructions[index].getReference<MethodReference>() ?: return@filter false
        instructions[index].opcode == Opcode.INVOKE_STATIC && reference.returnType == "V" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/Map;", "Z")
    }.singleOrNull() ?: return null
    val fill = instructions[fillAt] as FiveRegisterInstruction
    val postAt = (fillAt + 1 until instructions.size).firstOrNull {
        instructions[it].opcode == Opcode.INVOKE_INTERFACE &&
            instructions[it].getReference<MethodReference>()?.let { call ->
                call.definingClass == COMMON_API && call.name == "doPost"
            } == true
    } ?: return null
    if ((fillAt + 1 until postAt).any { instructions[it].opcode != Opcode.IGET_OBJECT }) return null
    val post = instructions[postAt] as FiveRegisterInstruction
    val types = instructions[postAt].getReference<MethodReference>()!!.parameterTypes.map(CharSequence::toString)
    if (types.size < 2 || types.first() != STRING || types.last() != "Ljava/util/Map;" ||
        post.registerCount != types.size + 1
    ) {
        return null
    }
    val registers = listOf(post.registerC, post.registerD, post.registerE, post.registerF, post.registerG)
    val url = registers[1]
    if (registers[types.size] != fill.registerC || url == fill.registerC || url == fill.registerD) return null
    val read = (fillAt + 1 until postAt).filter { writes(instructions[it], url) }.singleOrNull() ?: return null
    val get = instructions[read] as TwoRegisterInstruction
    val field = instructions[read].getReference<FieldReference>() ?: return null
    if (field.type != STRING) return null
    val owner = get.registerB
    // The copy in front of the fill reads the URL into its own register, so a read that replaces
    // the request with its own URL would leave the original read nothing to read from.
    if (url > 15 || owner > 15 || url == owner) return null
    if ((fillAt + 1 until postAt).any { it != read && writes(instructions[it], owner) }) return null
    if ((fillAt + 1 until read).any { (instructions[it] as TwoRegisterInstruction).registerB == url }) return null
    return ProxyFill(fillAt, url, owner, field)
}

private fun writes(instruction: Instruction, register: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
}

@Suppress("unused")
val regionSpoofPatch = bytecodePatch(
    name = "Region spoof",
    description = "Makes TikTok's region and time zone match the country you set for SIM spoof. It changes every region check, sign-in included, and its store region switch is experimental, so it isn't picked for you. Starts off. Turn it on in Metra settings > Region spoof.",
    default = false,
) {
    category("Settings")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(settingsPatch, simSpoofPatch)
    execute {
        // Found and checked before anything is written: the patcher doesn't take a failed
        // patch's writes back out.
        val handler = CommonParamsHandlerFingerprint.method
        val fill = commonParamsFill(handler) ?: throw PatchException(
            "Region spoof: the common-parameter handler no longer fills a new map and reads its request's path the way it did.",
        )
        val requestRegister = handler.implementationOrPatchException("Region spoof").registerCount - 2
        if (fill.mapRegister > 15 || requestRegister > 15) {
            throw PatchException("Region spoof: the common-parameter handler's registers are out of reach of the path read.")
        }
        val tokenInterceptor = TokenInterceptorFingerprint.method
        val tokenFill = tokenFill(tokenInterceptor) ?: throw PatchException(
            "Region spoof: the token interceptor no longer fills a new map off its request the way it did.",
        )
        val appLogUrl = AppLogUrlFingerprint.method
        val appLogUrlEntry = appLogEntry(appLogUrl, STRING, Opcode.RETURN_OBJECT) ?: throw PatchException(
            "Region spoof: AppLog.addCommonParams no longer wraps a URL and calls the URL funnel the way it did.",
        )
        val appLogBuilder = AppLogBuilderFingerprint.method
        val appLogBuilderEntry = appLogEntry(appLogBuilder, STRING_BUILDER, Opcode.RETURN_VOID) ?: throw PatchException(
            "Region spoof: AppLog.appendCommonParams no longer calls the URL funnel the way it did.",
        )
        if (appLogUrlEntry.funnel.toString() != appLogBuilderEntry.funnel.toString()) {
            throw PatchException("Region spoof: AppLog's two URL entry points no longer share one funnel.")
        }
        val proxyHelpers = mutableListOf<Method>()
        classDefForEach { definition ->
            definition.methods.forEach { method -> if (proxyFill(method) != null) proxyHelpers += method }
        }
        if (proxyHelpers.isEmpty()) {
            throw PatchException("Region spoof: no JS request helper fills a POST body off a URL the way it did.")
        }

        val replacements = mapOf("Ljava/util/Locale;" to "locale", "Ljava/util/TimeZone;" to "timeZone")
        val counts = mutableMapOf<String, Int>()
        classDefForEach { definition ->
            if (definition.type.startsWith("Lapp/morphe/extension/")) return@classDefForEach
            definition.methods.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                val calls = instructions.withIndex().mapNotNull { (index, instruction) ->
                    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@mapNotNull null
                    val reference = instruction.getReference<MethodReference>() ?: return@mapNotNull null
                    val wrapper = replacements[reference.definingClass] ?: return@mapNotNull null
                    if (reference.name != "getDefault" || reference.returnType != reference.definingClass) return@mapNotNull null
                    val result = instructions.getOrNull(index + 1)
                    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
                    counts[wrapper] = (counts[wrapper] ?: 0) + 1
                    Triple(index + 2, (result as OneRegisterInstruction).registerA, wrapper to reference.returnType)
                }
                if (calls.isNotEmpty()) {
                    val mutable = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
                    calls.asReversed().forEach { (index, register, target) ->
                        mutable.addInstructions(index, """
                            invoke-static/range { v$register .. v$register }, $EXTENSION->${target.first}(${target.second})${target.second}
                            move-result-object v$register
                        """)
                    }
                }
            }
        }
        val missingWrappers = replacements.values.filter { (counts[it] ?: 0) == 0 }
        if (missingWrappers.isNotEmpty()) {
            throw PatchException(
                "Region spoof: no native ${missingWrappers.joinToString()} getDefault call was found.",
            )
        }

        val hubReference = RegionService.method.implementationOrPatchException("Region spoof")
            .instructions.mapNotNull {
            it.getReference<MethodReference>()
        }.filter { it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
            .singleOrPatchException("Region spoof: RegionService string hub call")
        val hub = mutableClassDefBy(hubReference.definingClass)
        val account = mutableClassDefBy("Lcom/ss/android/ugc/aweme/AccountService;")
        val accountSuperclass = account.superclass
            ?: throw PatchException("Region spoof: AccountService has no superclass holding the store region.")
        val store = mutableClassDefBy(accountSuperclass).methods.filter {
            it.name == "getStoreRegionUpperCase" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty()
        }.singleOrPatchException("Region spoof: AccountService store-region getter")
        // Bytecode order is part of this boundary: the getter reads two static String fields,
        // and the first is the store-region value shared with the priority hub getter.
        val storeField = store.implementationOrPatchException("Region spoof").instructions
            .filter { it.opcode == Opcode.SGET_OBJECT }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { it.type == "Ljava/lang/String;" }
            .firstOrNull() ?: throw PatchException(
            "Region spoof: the store-region getter reads no static String field.",
        )
        val getters = hub.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty()
        }
        if (getters.size != 6) {
            throw PatchException(
                "Region spoof: expected six static String getters in ${hub.type}, found ${getters.size}.",
            )
        }
        val priority = getters.filter { method ->
            method.implementationOrPatchException("Region spoof").instructions.any {
                it.getReference<FieldReference>()?.toString() == storeField.toString()
            }
        }.singleOrPatchException("Region spoof: priority getter reading $storeField")
        (getters + store).forEach { method ->
            val wrapper = if (method == priority || method == store) "storeCountry" else "country"
            val returns = method.implementationOrPatchException("Region spoof").instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index to (it.value as OneRegisterInstruction).registerA }
            if (returns.isEmpty()) {
                throw PatchException(
                    "Region spoof: ${method.definingClass}->${method.name} has no String return to wrap.",
                )
            }
            returns.reversed().forEach { (index, register) ->
                    method.addInstructionsAtControlFlowLabel(index, """
                        invoke-static/range { v$register .. v$register }, $EXTENSION->$wrapper(Ljava/lang/String;)Ljava/lang/String;
                        move-result-object v$register
                    """)
                }
        }

        // The request fields: the map is p3, and the builder only ever puts into it, so each
        // return hands the extension the map TikTok is about to send.
        CommonParamsBuilderFingerprint.method.apply {
            if (AccessFlags.STATIC.isSet(accessFlags)) {
                throw PatchException("Region spoof: the common-parameter builder is static, so p3 is not its map.")
            }
            val body = implementationOrPatchException("Region spoof")
            val map = body.registerCount - 2
            val overwrites = body.instructions.filter { instruction ->
                if (!instruction.opcode.setsRegister()) return@filter false
                val target = (instruction as? OneRegisterInstruction)?.registerA ?: return@filter false
                target == map || (instruction.opcode.setsWideRegister() && target + 1 == map)
            }
            if (overwrites.isNotEmpty()) {
                throw PatchException(
                    "Region spoof: the common-parameter builder writes its map register: ${overwrites.map { it.opcode.name }}.",
                )
            }
            val returns = body.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            if (returns.isEmpty()) {
                throw PatchException("Region spoof: the common-parameter builder has no return to hook.")
            }
            returns.reversed().forEach { index ->
                addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range { p3 .. p3 }, $EXTENSION->requestParams(Ljava/util/Map;)V",
                )
            }
        }

        // Sign-in requests: the handler says which request it's about to fill, in the map's own
        // register just before the map is made, and when the fill is over. A fill that throws
        // skips the second call; the thread's next request puts the mark right, and the mark
        // runs out after RegionSpoof.MARK_LIFETIME_MS if no request comes.
        val register = "v${fill.mapRegister}"
        handler.addInstruction(fill.fillAt + 1, "invoke-static {}, $EXTENSION->requestDone()V")
        handler.addInstructionsAtControlFlowLabel(
            fill.mapAt,
            """
                iget-object $register, p1, ${fill.requestField}
                iget-object $register, $register, ${fill.urlField}
                invoke-virtual { $register }, ${fill.pathGetter}
                move-result-object $register
                invoke-static { $register }, $EXTENSION->requestPath(Ljava/lang/String;)V
            """,
        )
        // The token interceptor's own fill, marked the same way off the request's full URL. The
        // map register is free until the map is made, so the URL goes through it.
        val tokenMap = "v${tokenFill.mapRegister}"
        tokenInterceptor.addInstruction(tokenFill.fillAt + 1, "invoke-static {}, $EXTENSION->requestDone()V")
        tokenInterceptor.addInstructionsAtControlFlowLabel(
            tokenFill.mapAt,
            """
                invoke-virtual { v${tokenFill.requestRegister} }, $REQUEST_GET_URL
                move-result-object $tokenMap
                invoke-static { $tokenMap }, $EXTENSION->requestUrl(Ljava/lang/String;)V
            """,
        )

        // The two AppLog entry points that add common parameters to a URL. The URL is p0, read
        // before anything runs, and the end is marked at each return (the early exit of
        // addCommonParams lands on the same return as the long path).
        appLogUrlEntry.returns.asReversed().forEach { index ->
            appLogUrl.addInstructionsAtControlFlowLabel(index, "invoke-static {}, $EXTENSION->requestDone()V")
        }
        appLogUrl.addInstruction(0, "invoke-static { p0 }, $EXTENSION->requestUrl(Ljava/lang/String;)V")
        appLogBuilderEntry.returns.asReversed().forEach { index ->
            appLogBuilder.addInstructionsAtControlFlowLabel(index, "invoke-static {}, $EXTENSION->requestDone()V")
        }
        appLogBuilder.addInstruction(0, "invoke-static { p0 }, $EXTENSION->requestUrlBuilder(Ljava/lang/StringBuilder;)V")

        // The JS request helpers fill a POST body's common parameters themselves, before the
        // request reaches the handler, so the handler's mark never covers that fill. The URL
        // read that follows the fill is copied in front of it, on the register it writes.
        proxyHelpers.forEach { original ->
            val helper = mutableClassDefBy(original.definingClass).findMutableMethodOf(original)
            val proxy = proxyFill(helper) ?: throw PatchException(
                "Region spoof: ${original.definingClass}->${original.name} changed under the patch.",
            )
            val url = "v${proxy.urlRegister}"
            helper.addInstruction(proxy.fillAt + 1, "invoke-static {}, $EXTENSION->requestDone()V")
            helper.addInstructionsAtControlFlowLabel(
                proxy.fillAt,
                """
                    iget-object $url, v${proxy.objectRegister}, ${proxy.urlField}
                    invoke-static { $url }, $EXTENSION->requestUrl(Ljava/lang/String;)V
                """,
            )
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableRegionSpoof()V")
    }
}
