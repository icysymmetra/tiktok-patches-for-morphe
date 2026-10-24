package app.morphe.patches.tiktok.misc.spoof.region

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Region spoof's request fields rest on, held to each declared TikTok build.
 *
 * Sign-in requests keep the real region through TTNet's common-parameter handler, which reads the
 * request's path off p1 and fills a new map on the same thread; the patch copies that path read
 * in front of the map and calls the extension around the fill.
 *
 * The patch hooks every return of AppLog's common-parameter builder and hands the extension the
 * map in p3. That needs the builder to be one instance method that never puts anything else in
 * that register. It also leaves carrier_region, sys_region and region alone, because the
 * network_common_params feature producer reads them from the region hub's static getters, which
 * the patch already wraps; current_region and residence come from SharePrefCache instead, which
 * is why the builder hook exists at all.
 *
 * Two fields in that map are copies TikTok takes once at start-up, sys_region and timezone_name.
 * A sign-in puts back what their sources say now, so the sources must be ones the extension can
 * read again: the system configuration's locale and the default time zone.
 *
 * Common parameters reach a request by three more routes than the handler's path read: the token
 * interceptor, AppLog's two URL entry points and the JS request helpers that fill a POST body.
 * Each is marked with the URL it is about to send, and each shape is held here.
 */
class RegionSpoofAnchorsTest {
    @Test
    fun `the common-parameter builder is one instance method that only reads its map register`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val matches = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (CommonParamsBuilderFingerprint.takes(method, classDef)) matches += method
            }

            assertEquals(
                "$version: builders matched ${matches.map { "${it.definingClass}->${it.name}" }}",
                1, matches.size,
            )
            val builder = matches.single()
            assertFalse("$version: the builder is static", AccessFlags.STATIC.isSet(builder.accessFlags))
            val body = builder.implementation ?: error("$version: the builder has no body")
            val map = body.registerCount - 2
            val instructions = body.instructions.toList()
            assertTrue("$version: the builder writes its map register", instructions.none { writes(it, map) })
            assertTrue("$version: the builder has no return to hook", instructions.any { it.opcode == Opcode.RETURN_VOID })
            assertTrue("$version: the builder never puts into p3", instructions.any { puts(it, map) })
        }
    }

    @Test
    fun `the feature producer reads carrier_region sys_region and region from the wrapped hub`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var hub: String? = null
            val producers = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (classDef.type == REGION_SERVICE && method.name == "getRegion" && method.parameterTypes.isEmpty()) {
                    // The same reading the patch makes of it.
                    hub = method.implementation!!.instructions
                        .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                        .single { it.returnType == STRING && it.parameterTypes.isEmpty() }
                        .definingClass
                }
                // A list of the same feature names sits elsewhere; the producer is the override
                // that answers one, and FeatureProducer keeps that method's name.
                if (method.name == "getFeatureInternal") {
                    val loaded = method.implementation?.instructions?.mapNotNull { string(it) }.orEmpty()
                    if ("f_global_carrier_region" in loaded && "f_global_sys_region" in loaded) producers += method
                }
            }

            val hubClass = hub
            assertNotNull("$version: RegionService.getRegion was not found", hubClass)
            assertEquals("$version: feature producers ${producers.map { it.definingClass }}", 1, producers.size)
            val instructions = producers.single().implementation!!.instructions.toList()
            val readers = mapOf(
                "f_global_carrier_region" to hubClass,
                "f_global_sys_region" to hubClass,
                "f_global_region" to hubClass,
                "f_global_current_region" to SHARE_PREF_CACHE,
                "f_global_residence" to SHARE_PREF_CACHE,
            )
            readers.forEach { (feature, owner) ->
                val at = instructions.indexOfFirst { string(it) == feature }
                assertTrue("$version: $feature is not in the producer", at >= 0)
                val read = instructions.drop(at + 1).firstOrNull { it.opcode == Opcode.INVOKE_STATIC }
                    ?.let { (it as ReferenceInstruction).reference as MethodReference }
                assertEquals("$version: what $feature is read from", owner, read?.definingClass)
                if (owner == hubClass) {
                    assertTrue(
                        "$version: $feature is read through ${read?.name}, not a static getter the patch wraps",
                        read!!.returnType == STRING && read.parameterTypes.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun `the common-parameter handler fills one new map and reads the request path off p1`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val handlers = mutableListOf<Method>()
            // Every no-argument String method that joins with '/', keyed the way a reference prints.
            val joiners = mutableSetOf<String>()
            forEachMethod(apk) { classDef, method ->
                if (CommonParamsHandlerFingerprint.takes(method, classDef)) handlers += method
                if (method.returnType == STRING && method.parameterTypes.isEmpty() && joinsWithSlash(method)) {
                    joiners += "${method.definingClass}->${method.name}()$STRING"
                }
            }

            assertEquals("$version: handlers ${handlers.map { it.name }}", 1, handlers.size)
            val handler = handlers.single()
            val fill = commonParamsFill(handler) ?: error("$version: the handler's fill was not found")
            val registers = handler.implementation!!.registerCount
            assertTrue("$version: the map register v${fill.mapRegister} is past v15", fill.mapRegister <= 15)
            assertTrue("$version: p1 is past v15", registers - 2 <= 15)
            assertEquals("$version: what p1 is", handler.parameterTypes.first().toString(), fill.requestField.definingClass)
            // Read off the bytecode itself rather than off what commonParamsFill hands back.
            val instructions = handler.implementation!!.instructions.toList()
            val created = instructions[fill.mapAt]
            assertEquals("$version: what makes the map", Opcode.NEW_INSTANCE, created.opcode)
            assertEquals("$version: the map's type", "Ljava/util/LinkedHashMap;", created.typeReference())
            assertEquals("$version: where the map is made", fill.mapRegister, (created as OneRegisterInstruction).registerA)
            val init = instructions[fill.fillAt - 1]
            assertEquals("$version: what follows new-instance", Opcode.INVOKE_DIRECT, init.opcode)
            assertEquals("$version: the constructor", "<init>", ((init as ReferenceInstruction).reference as MethodReference).name)
            assertEquals("$version: what the constructor runs on", fill.mapRegister, (init as FiveRegisterInstruction).registerC)
            assertEquals("$version: the fill takes the map", fill.mapRegister, (instructions[fill.fillAt] as FiveRegisterInstruction).registerC)
            assertTrue(
                "$version: ${fill.pathGetter} doesn't join path segments with '/'",
                fill.pathGetter.toString() in joiners,
            )
        }
    }

    @Test
    fun `the token interceptor fills its own map through the handler's fill, off the request's URL`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val handlers = mutableListOf<Method>()
            val interceptors = mutableListOf<Method>()
            forEachMethod(apk) { classDef, method ->
                if (CommonParamsHandlerFingerprint.takes(method, classDef)) handlers += method
                if (TokenInterceptorFingerprint.takes(method, classDef)) interceptors += method
            }
            assertEquals("$version: token interceptors ${interceptors.map { it.name }}", 1, interceptors.size)
            val interceptor = interceptors.single()
            val fill = tokenFill(interceptor) ?: error("$version: the token interceptor's fill was not found")
            val instructions = interceptor.implementation!!.instructions.toList()
            val handlerInstructions = handlers.single().implementation!!.instructions.toList()
            assertEquals(
                "$version: the interceptor and the handler call different fills",
                (handlerInstructions[commonParamsFill(handlers.single())!!.fillAt] as ReferenceInstruction).reference.toString(),
                (instructions[fill.fillAt] as ReferenceInstruction).reference.toString(),
            )
            // Each token path is loaded and tested with contains before the map is made, so the
            // fill only runs for one of them. The extension checks the URL's path again anyway.
            TOKEN_PATHS.forEach { path ->
                val load = instructions.indexOfFirst { string(it) == path }
                assertTrue("$version: $path isn't loaded before the map", load in 0 until fill.mapAt)
                val contains = instructions[load + 1].let { (it as? ReferenceInstruction)?.reference?.toString() }
                assertEquals("$version: what $path goes to", "Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z", contains)
            }
            val created = instructions[fill.mapAt]
            assertEquals("$version: the map's type", "Ljava/util/HashMap;", created.typeReference())
            assertEquals("$version: the map register", fill.mapRegister, (created as OneRegisterInstruction).registerA)
            assertTrue("$version: registers past v15", fill.mapRegister <= 15 && fill.requestRegister <= 15)
            // The URL read the patch copies in front of the map is the last one, on a register
            // that still holds the request there.
            val urlRead = (fill.mapAt - 1 downTo 0).first {
                (instructions[it] as? ReferenceInstruction)?.reference?.toString() == REQUEST_GET_URL
            }
            assertEquals("$version: the request register", fill.requestRegister, (instructions[urlRead] as FiveRegisterInstruction).registerC)
            assertTrue(
                "$version: the request register is written between its URL read and the map",
                (urlRead + 1 until fill.mapAt).none { writes(instructions[it], fill.requestRegister) },
            )
        }
    }

    @Test
    fun `sys_region and timezone_name are copied once at start-up from sources the extension can read again`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val installs = mutableListOf<Method>()
            val producers = mutableListOf<Method>()
            val localeReaders = mutableSetOf<String>()
            forEachMethod(apk) { classDef, method ->
                val instructions = method.implementation?.instructions ?: return@forEachMethod
                if (classDef.type == SETTING_SERVICE && method.name == "installCommonParams") installs += method
                if (method.name == "getFeatureInternal") {
                    val loaded = instructions.mapNotNull { string(it) }
                    if ("f_global_carrier_region" in loaded && "f_global_sys_region" in loaded) producers += method
                }
                if (AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == STRING &&
                    method.parameterTypes.isEmpty() && readsSystemLocale(instructions)
                ) {
                    localeReaders += "${method.definingClass}->${method.name}()$STRING"
                }
            }

            // Each key is stored with its feature name and then the once-only cache's name, so the
            // value is worked out one time and every later request gets that copy.
            assertEquals("$version: installCommonParams in ${installs.map { it.definingClass }}", 1, installs.size)
            val installed = installs.single().implementation!!.instructions.mapNotNull { string(it) }
            mapOf("timezone_name" to "f_global_timezone_name", "sys_region" to "f_global_sys_region")
                .forEach { (key, feature) ->
                    val at = installed.indexOf(key)
                    assertTrue("$version: $key is not stored at start-up", at >= 0)
                    assertEquals("$version: what $key is worked out from", feature, installed.getOrNull(at + 1))
                    assertEquals("$version: where $key is kept", ONCE_CACHE, installed.getOrNull(at + 2))
                }

            assertEquals("$version: feature producers ${producers.map { it.definingClass }}", 1, producers.size)
            val producer = producers.single().implementation!!.instructions.toList()
            // timezone_name is the default zone's ID, read in the producer itself. The patch's
            // TimeZone hook answers the preset there, and the extension's own call is not hooked.
            val zone = producer.indexOfFirst { string(it) == "f_global_timezone_name" }
            assertTrue("$version: f_global_timezone_name is not in the producer", zone >= 0)
            assertEquals(
                "$version: how f_global_timezone_name is answered",
                listOf(
                    "Ljava/lang/String;->equals(Ljava/lang/Object;)Z",
                    "Ljava/util/TimeZone;->getDefault()Ljava/util/TimeZone;",
                    "Ljava/util/TimeZone;->getID()Ljava/lang/String;",
                ),
                producer.drop(zone + 1).takeWhile { it.opcode != Opcode.RETURN_OBJECT }
                    .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() },
            )
            // sys_region is the hub getter that reads the system configuration's locale.
            val region = producer.indexOfFirst { string(it) == "f_global_sys_region" }
            assertTrue("$version: f_global_sys_region is not in the producer", region >= 0)
            val getter = producer.drop(region + 1).first { it.opcode == Opcode.INVOKE_STATIC }
                .let { (it as ReferenceInstruction).reference as MethodReference }
            assertTrue(
                "$version: ${getter.definingClass}->${getter.name} doesn't read the system configuration's locale",
                "${getter.definingClass}->${getter.name}()$STRING" in localeReaders,
            )
        }
    }

    @Test
    fun `AppLog's two URL entry points reach the common-parameter builder through one funnel`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val urls = mutableListOf<Method>()
            val appenders = mutableListOf<Method>()
            val builders = mutableListOf<Method>()
            // Static void methods taking (StringBuilder, boolean, level) or (Map, boolean, level).
            val funnels = mutableMapOf<String, Method>()
            val fills = mutableMapOf<String, Method>()
            forEachMethod(apk) { classDef, method ->
                if (AppLogUrlFingerprint.takes(method, classDef)) urls += method
                if (AppLogBuilderFingerprint.takes(method, classDef)) appenders += method
                if (CommonParamsBuilderFingerprint.takes(method, classDef)) builders += method
                if (AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                    method.parameterTypes.size == 3 && method.parameterTypes[1].toString() == "Z"
                ) {
                    val first = method.parameterTypes[0].toString()
                    if (first == "Ljava/lang/StringBuilder;") funnels[key(method)] = method
                    if (first == "Ljava/util/Map;") fills[key(method)] = method
                }
            }
            assertEquals("$version: addCommonParams in ${urls.map { it.definingClass }}", 1, urls.size)
            assertEquals("$version: appendCommonParams in ${appenders.map { it.definingClass }}", 1, appenders.size)
            assertEquals("$version: builders ${builders.map { it.name }}", 1, builders.size)

            val url = appLogEntry(urls.single(), STRING, Opcode.RETURN_OBJECT)
                ?: error("$version: addCommonParams has a different shape")
            val appender = appLogEntry(appenders.single(), "Ljava/lang/StringBuilder;", Opcode.RETURN_VOID)
                ?: error("$version: appendCommonParams has a different shape")
            assertEquals("$version: the two entry points use different funnels", url.funnel.toString(), appender.funnel.toString())
            // The URL is p0, read before anything runs. addCommonParams hands back what p0 ends up
            // holding, so its one return ends both the long path and the early exit.
            assertEquals("$version: addCommonParams returns", 1, url.returns.size)
            assertEquals("$version: appendCommonParams returns", 1, appender.returns.size)

            // Funnel, then the fill that takes a map, then the builder: the mark set at the entry
            // is still on the thread when the builder's hook runs.
            val funnel = funnels[key(url.funnel)] ?: error("$version: the funnel ${url.funnel} was not found")
            val fill = funnel.implementation!!.instructions
                .filter { it.opcode == Opcode.INVOKE_STATIC }
                .mapNotNull { (it as ReferenceInstruction).reference as? MethodReference }
                .filter { fills.containsKey(key(it)) }
                .map { fills.getValue(key(it)) }
                .singleOrNull() ?: error("$version: the funnel calls no single map fill")
            val builder = builders.single()
            val level = builder.parameterTypes.last().toString()
            assertEquals("$version: the funnel's level is not the builder's", level, funnel.parameterTypes[2].toString())
            assertEquals("$version: the fill's level is not the builder's", level, fill.parameterTypes[2].toString())
            val reaches = fill.implementation!!.instructions.any {
                val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_VIRTUAL && reference != null && key(reference) == key(builder)
            }
            assertTrue("$version: ${key(fill)} doesn't call the builder", reaches)
        }
    }

    @Test
    fun `the JS request helpers fill a POST body through the handler's fill, ahead of the URL they send it to`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val handlers = mutableListOf<Method>()
            val helpers = mutableListOf<Pair<Method, ProxyFill>>()
            forEachMethod(apk) { classDef, method ->
                if (CommonParamsHandlerFingerprint.takes(method, classDef)) handlers += method
                proxyFill(method)?.let { helpers += method to it }
            }
            assertEquals("$version: handlers ${handlers.map { it.name }}", 1, handlers.size)
            val handler = handlers.single()
            val handlerFill = (handler.implementation!!.instructions.toList()[commonParamsFill(handler)!!.fillAt]
                as ReferenceInstruction).reference.toString()
            // The helper and its second version, each a numbered static method the Callable
            // lambdas were moved to.
            assertEquals(
                "$version: helpers ${helpers.map { "${it.first.definingClass}->${it.first.name}" }}",
                2, helpers.size,
            )
            helpers.forEach { (method, proxy) ->
                val where = "$version: ${method.definingClass}->${method.name}"
                val instructions = method.implementation!!.instructions.toList()
                assertTrue("$where isn't the JS request helper", instructions.any { string(it) == "_AME_Header_RequestID" })
                assertEquals(
                    "$where fills through a different fill than the handler",
                    handlerFill, (instructions[proxy.fillAt] as ReferenceInstruction).reference.toString(),
                )
                // Straight after the fill: field reads, then doPost taking the map. One read is the URL.
                val post = (proxy.fillAt + 1 until instructions.size).first { instructions[it].opcode == Opcode.INVOKE_INTERFACE }
                val call = (instructions[post] as ReferenceInstruction).reference as MethodReference
                assertEquals("$where: what the map is sent with", "doPost", call.name)
                assertEquals("$where: what it is sent through", COMMON_API, call.definingClass)
                assertTrue(
                    "$where: something other than a field read sits between the fill and the call",
                    (proxy.fillAt + 1 until post).all { instructions[it].opcode == Opcode.IGET_OBJECT },
                )
                val sent = (instructions[post] as FiveRegisterInstruction).registerD
                val read = (proxy.fillAt + 1 until post).single { writes(instructions[it], sent) }
                assertEquals(
                    "$where: the URL field",
                    proxy.urlField.toString(),
                    ((instructions[read] as ReferenceInstruction).reference as FieldReference).toString(),
                )
                assertEquals("$where: the URL field's type", STRING, proxy.urlField.type)
                assertTrue("$where: registers past v15", proxy.urlRegister <= 15 && proxy.objectRegister <= 15)
            }
        }
    }

    private fun Instruction.typeReference(): String? =
        ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

    private fun joinsWithSlash(method: Method): Boolean {
        val instructions = method.implementation?.instructions ?: return false
        var slash = false
        var appendsChar = false
        for (instruction in instructions) {
            if ((instruction as? NarrowLiteralInstruction)?.narrowLiteral == '/'.code) slash = true
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
            if (reference.toString() == "Ljava/lang/StringBuilder;->append(C)Ljava/lang/StringBuilder;") appendsChar = true
        }
        return slash && appendsChar
    }

    private fun forEachMethod(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                classDef.methods.forEach { method -> visit(classDef, method) }
            }
        }
    }

    private fun string(instruction: Instruction): String? =
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun key(method: Method) =
        "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

    private fun key(reference: MethodReference) =
        "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}"

    /** Resources.getSystem().getConfiguration().locale, the way the hub's sys_region getter reads it. */
    private fun readsSystemLocale(instructions: Iterable<Instruction>): Boolean {
        val references = instructions.mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        return "Landroid/content/res/Resources;->getSystem()Landroid/content/res/Resources;" in references &&
            "Landroid/content/res/Resources;->getConfiguration()Landroid/content/res/Configuration;" in references &&
            "Landroid/content/res/Configuration;->locale:Ljava/util/Locale;" in references
    }

    private fun writes(instruction: Instruction, register: Int): Boolean {
        if (!instruction.opcode.setsRegister()) return false
        val target = (instruction as? OneRegisterInstruction)?.registerA ?: return false
        return target == register || (instruction.opcode.setsWideRegister() && target + 1 == register)
    }

    private fun puts(instruction: Instruction, register: Int): Boolean {
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return reference.definingClass == "Ljava/util/Map;" && reference.name == "put" &&
            (instruction as? FiveRegisterInstruction)?.registerC == register
    }

    private companion object {
        const val REGION_SERVICE = "Lcom/ss/android/ugc/aweme/app/services/RegionService;"
        const val SHARE_PREF_CACHE = "Lcom/ss/android/ugc/aweme/app/SharePrefCache;"
        const val SETTING_SERVICE = "Lcom/ss/android/ugc/aweme/setting/services/SettingServiceImpl;"
        const val ONCE_CACHE = "network_common_params_once"
        const val STRING = "Ljava/lang/String;"
    }
}
