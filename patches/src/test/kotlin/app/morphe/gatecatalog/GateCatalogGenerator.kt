package app.morphe.gatecatalog

import app.morphe.Fixtures

import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Rebuilds the Feature Gate Lab's offline catalogs from each TikTok build the bundle declares.
 *
 * `./gradlew :patches:generateGateCatalog` reads the declared builds' APKs from the fixture
 * folder (`HUSHFEED_FIXTURE_DIR`; `-Papk=<a.apk>;<b.apk>` names them instead) and writes the
 * `Generated*Catalog.java` files and `GeneratedGateCatalogBuilds.java` under the extension's
 * featuregatelab package. Each file stores the rows every build has once and each build's other
 * rows beside them, since two builds share nearly every row: on 47.0.3 and 47.1.3 all but a few
 * hundred of some 24,000. A SettingsManager row's call site is R8's name for it on one build, so
 * the sites are a table of their own and the rest of the row is shared. Every source is
 * read by what it is rather than by a name R8 made up, so the same code runs on the next build:
 * - the AB registry: each `com.bytedance.ies.abmock.ConfigItem` TikTok puts into a map under its
 *   key, with the item's type and default;
 * - Live settings: each `com.bytedance.android.live.settings.SettingModel(key, typeName, default)`;
 * - VE settings: each `VEConfigCenter.addConfig(key, ValuePkt(type, default, _, description))`,
 *   and the JSON registry of camera settings the VE SDK carries as one string;
 * - player settings: the `(default, key)` factory calls in the class that registers the player
 *   setting `AWEDanmakuSupportMask`, the one key the player registry is found by;
 * - SettingsManager: each `SettingsManager` read that names its key, its model class and its
 *   default, with the model's fields as the default shown.
 *
 * Rows the generator cannot produce, researched by hand, live in `gate-catalog-curated.tsv` and
 * are merged into every build unchanged. GateCatalogFixturesTest holds the committed files to
 * what this reads off each declared build.
 */
object GateCatalogGenerator {
    private const val CONFIG_ITEM = "Lcom/bytedance/ies/abmock/ConfigItem;"
    private const val SETTINGS_MANAGER = "Lcom/bytedance/ies/abmock/SettingsManager;"
    private const val SETTING_MODEL = "Lcom/bytedance/android/live/settings/SettingModel;"
    private const val VE_CONFIG_CENTER = "Lcom/ss/android/vesdk/VEConfigCenter;"
    private const val VE_VALUE_PKT = "Lcom/ss/android/vesdk/VEConfigCenter\$ValuePkt;"
    private const val VE_DATA_TYPE = "Lcom/ss/android/vesdk/VEConfigCenter\$DataType;"
    private const val LIVE_SETTINGS_MANAGER = "Lcom/bytedance/android/live_settings/SettingsManager;"
    private const val PLAYER_ANCHOR = "AWEDanmakuSupportMask"
    private val FEATURE_DEX = Regex("""lib/[^/]+/libdex_[^/]+\.so""")

    /**
     * The app AB class's typed getters, as `(parameters)return`, and the type each reads. The class
     * has no stable name; it is the one class carrying these six and the raw getter, which is how
     * FeatureGateLabPatch finds it too. The key is the first String parameter in every one.
     */
    private val APP_AB_GETTERS = mapOf(
        "(ILjava/lang/String;ZZ)Z" to "BOOLEAN",
        "(DILjava/lang/String;Z)D" to "DOUBLE",
        "(ILjava/lang/String;ZF)F" to "FLOAT",
        "(IILjava/lang/String;Z)I" to "INT",
        "(IJLjava/lang/String;Z)J" to "LONG",
        "(ILjava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;" to "STRING",
    )
    private const val APP_AB_RAW = "(Ljava/lang/String;Z)Ljava/lang/Object;"

    /** SettingsManager's static typed getters, `(key, default)`, and the Live and VE ones. */
    private val VALUE_TYPES = mapOf("Z" to "BOOLEAN", "D" to "DOUBLE", "F" to "FLOAT", "I" to "INT",
        "J" to "LONG", "Ljava/lang/String;" to "STRING")
    private val LIVE_GETTERS = mapOf("getBooleanValue" to "BOOLEAN", "getDoubleValue" to "DOUBLE",
        "getFloatValue" to "FLOAT", "getIntValue" to "INT", "getLongValue" to "LONG", "getStringValue" to "STRING")
    private val SCALARS = setOf("BOOLEAN", "INT", "LONG", "FLOAT", "DOUBLE", "STRING")
    val CATALOG_NAMES = setOf("GeneratedFeatureGateCatalog", "GeneratedPlayerFeatureGateCatalog",
        "GeneratedVeFeatureGateCatalog", "GeneratedSettingsManagerCatalog")

    /** The SettingsManager rows' call sites, split off because they are R8's names for one build. */
    const val SITES_NAME = "GeneratedSettingsManagerSites"
    const val BUILDS_NAME = "GeneratedGateCatalogBuilds"

    /** One catalog table, as the TSV lines the Lab reads, and the sentence its header gives. */
    class Catalog(val className: String, val source: String, val lines: List<String>)

    class Catalogs(val version: String, val versionCode: String, val catalogs: List<Catalog>)

    /** One table over every build: the rows all of them have, then each build's others. */
    class Combined(val className: String, val source: String, val shared: List<String>, val own: List<List<String>>) {
        fun rowsOf(build: Int): List<String> = shared + own[build]
    }

    /** What a generated Java file stores: each build's row count, the shared rows and each build's own. */
    class Stored(val counts: List<Int>, val shared: List<String>, val own: List<List<String>>) {
        fun rowsOf(build: Int): List<String> = shared + own[build]
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: GateCatalogGenerator <featuregatelab source folder> <curated.tsv> [TikTok APK ...]" }
        val out = File(args[0])
        val curated = File(args[1]).readLines()
        require(out.isDirectory) { "$out is not a folder" }
        val apks = args.drop(2).flatMap { it.split(File.pathSeparatorChar) }.filter { it.isNotBlank() }.map(::File)
            .ifEmpty { app.morphe.Fixtures.declared() }
        apks.forEach { require(it.isFile) { "$it is not a file" } }
        val perBuild = apks.map { apk -> generate(apk, curated).also { println("read TikTok ${it.version} (${it.versionCode})") } }
        val declared = app.morphe.Fixtures.declaredVersions()
        check(perBuild.map { it.version }.sorted() == declared.sorted()) {
            "the catalogs cover the builds the bundle declares, $declared, and these APKs are ${perBuild.map { it.version }}"
        }
        val ordered = perBuild.sortedBy { declared.indexOf(it.version) }
        for (table in combine(ordered)) {
            File(out, table.className + ".java").writeText(javaSource(table, ordered))
            println("${table.className}: ${table.shared.size} shared rows, own " +
                ordered.indices.joinToString { "${ordered[it].version} ${table.own[it].size}" })
        }
        File(out, "$BUILDS_NAME.java").writeText(buildsSource(ordered))
    }

    /**
     * Each table over [builds], oldest first: a row every build has, byte for byte, is stored
     * once. The Lab reads a build's rows as the shared ones followed by its own, which is the
     * order [generate] gave them when both parts are sorted.
     */
    fun combine(builds: List<Catalogs>): List<Combined> =
        builds.first().catalogs.indices.map { table ->
            val sets = builds.map { it.catalogs[table].lines }
            val inEvery = sets.drop(1).fold(sets.first().toSet()) { kept, lines -> kept intersect lines.toSet() }
            val first = builds.first().catalogs[table]
            Combined(first.className, first.source, sets.first().filter { it in inEvery },
                sets.map { lines -> lines.filter { it !in inEvery } })
        }

    /** Reads a generated catalog file back, for the test that holds it to the fixtures. */
    fun readJavaSource(text: String): Stored {
        val counts = Regex("""ENTRY_COUNTS = \{([^}]*)}""").find(text)!!.groupValues[1]
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(String::toInt)
        val shared = decode(chunks(text.substringAfter("SHARED_GZIP_BASE64 = {").substringBefore("};")))
        // Base64 has no braces, so each build's block is the text between one pair.
        val own = Regex("""\{([^{}]*)}""").findAll(text.substringAfter("OWN_GZIP_BASE64 = {"))
            .take(counts.size).map { decode(chunks(it.groupValues[1])) }.toList()
        check(own.size == counts.size) { "the file stores ${own.size} builds' own rows for ${counts.size} counts" }
        return Stored(counts, shared, own)
    }

    private fun chunks(block: String): String = Regex(""""([^"]*)"""").findAll(block).joinToString("") { it.groupValues[1] }

    private fun decode(base64: String): List<String> {
        if (base64.isEmpty()) return emptyList()
        val bytes = Base64.getDecoder().decode(base64)
        return java.util.zip.GZIPInputStream(bytes.inputStream()).bufferedReader(Charsets.UTF_8).readLines()
    }

    fun generate(apk: File, curated: List<String>): Catalogs {
        val (version, versionCode) = manifestVersion(apk)
        val scan = Scan()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = mutableListOf<Pair<String, ClassDef>>()
        // Patcher 2.0's reader visits classes2 before classes10; the previous
        // reader used lexical paths. Pin that order so duplicate keys keep the
        // same representative call site, independent of the reader version.
        for (entry in container.dexEntryNames.sorted()) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) classes += entry to classDef
        }
        // Each dynamic feature module's code, a zip holding its dex under lib/<abi>/, the same
        // file under every ABI.
        ZipFile(apk).use { zip ->
            val modules = zip.entries().asSequence().filter { FEATURE_DEX.matches(it.name) }
                .distinctBy { it.name.substringAfterLast('/') }.toList()
            for (module in modules) {
                ZipInputStream(zip.getInputStream(module)).use { inner ->
                    generateSequence { inner.nextEntry }.filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { dex ->
                        val file = DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(inner.readBytes()))
                        for (classDef in file.classes) classes += "${module.name.substringAfterLast('/')}!${dex.name}" to classDef
                    }
                }
            }
        }
        val byType = classes.associate { it.second.type to it.second }
        scan.abClass = appAbClass(classes.map { it.second })
        scan.veTypeOrder = enumOrder(byType[VE_DATA_TYPE])
        scan.playerOwner = classes.firstOrNull { (_, c) -> loadsString(c.methods.firstOrNull { it.name == "<clinit>" }, PLAYER_ANCHOR) }?.second?.type
        for ((dex, classDef) in classes) {
            for (method in classDef.methods) scan.visit(dex, classDef, method)
        }
        check(scan.ab.isNotEmpty()) { "no ConfigItem registry in $apk" }
        check(scan.live.isNotEmpty()) { "no Live SettingModel in $apk" }
        check(scan.ve.isNotEmpty()) { "no VEConfigCenter.addConfig in $apk" }
        checkNotNull(scan.playerOwner) { "no class registers the player setting $PLAYER_ANCHOR in $apk" }
        check(scan.player.isNotEmpty()) { "no player setting factory calls in ${scan.playerOwner}" }

        val curatedByCatalog = curated.filter { it.isNotBlank() && !it.startsWith("#") }.groupBy { it.substringBefore('\t') }
        val unknownCatalogs = curatedByCatalog.keys - CATALOG_NAMES
        check(unknownCatalogs.isEmpty()) { "curated rows name no catalog: $unknownCatalogs" }
        fun curatedRows(catalog: String) = curatedByCatalog[catalog].orEmpty().map { it.substringAfter('\t') }
        val retyped = mutableListOf<String>()
        fun readType(key: String, registered: String, reads: Set<String>?): String? {
            if (reads.isNullOrEmpty()) return null
            if (registered in reads) return registered
            retyped += "$key registered $registered, read as ${reads.joinToString()}"
            return reads.first()
        }
        // The Lab hooks the typed getters and looks a rule up under the type the getter asks for,
        // so where a getter reads a key as another type than its registration says, a row typed
        // as registered would be a switch that does nothing.
        fun typedRow(key: String, manager: String, item: Item, reads: Set<String>?, proof: String): String {
            val type = readType(key, item.type, reads) ?: item.type
            return row(key, manager, type, carriedDefault(item.type, type, item.defaultJson), "", proof)
        }
        // Every AB and Live setting registered with a type the Lab can override, read by string
        // or not: the old catalogs were this set with whole registry methods missing. A key a
        // typed getter reads that is registered under no scalar type is a row too, typed as read.
        fun rows(manager: String, registered: Map<String, Item>, reads: Map<String, Set<String>>, proof: String) =
            (registered.keys + reads.keys).sorted().mapNotNull { key ->
                val item = registered[key]
                when {
                    item != null && item.type in SCALARS -> typedRow(key, manager, item, reads[key], proof)
                    reads[key].isNullOrEmpty() -> null
                    else -> row(key, manager, reads.getValue(key).first(), "", "", "${manager}_typed_getter")
                }
            }
        val abLive = (rows("abmock", scan.ab, scan.abReads, "abmock_config_registry") +
            rows("live", scan.live, scan.liveReads, "live_setting_model") +
            curatedRows("GeneratedFeatureGateCatalog")).sorted()
        val player = (scan.player.map { (key, entry) ->
            row(key, "player_config", entry.type, entry.defaultJson, "", "player_setting_registry")
        } + curatedRows("GeneratedPlayerFeatureGateCatalog")).sorted()
        // Every registered VE setting stays, read or not: the VE SDK's native side reads them too.
        // Where the Java getter asks for another type than the registration says, the getter's
        // type is the one a rule has to carry.
        val ve = (scan.ve.map { (key, entry) ->
            val type = readType(key, entry.type, scan.veReads[key]) ?: entry.type
            row(key, "ve_config", type, carriedDefault(entry.type, type, entry.defaultJson), entry.description, entry.proof)
        } + curatedRows("GeneratedVeFeatureGateCatalog")).sorted()
        println("read by a typed getter with a string key: AB ${scan.abReads.size} (${scan.abReads.keys.count { it !in scan.ab }} " +
            "unregistered), Live ${scan.liveReads.size} (${scan.liveReads.keys.count { it !in scan.live }} unregistered), " +
            "VE ${scan.veReads.size}")
        retyped.forEach { println("typed as read: $it") }
        val settings = (scan.settings.map { (key, call) ->
            val registered = scan.ab.containsKey(key)
            listOf(key, call.modelName, call.defaultJson(byType), call.site, call.dex,
                if (registered) "generated_registry" else "call_site_only").joinToString("\t")
        } + curatedRows("GeneratedSettingsManagerCatalog")).sorted()
        for (catalog in listOf(abLive, player, ve, settings)) {
            val keys = catalog.map { it.substringBefore('\t') + "\t" + it.split('\t')[1] }
            val twice = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            check(twice.isEmpty()) { "rows listed twice: $twice" }
        }
        scan.unknownLiveTypes.forEach { (name, count) -> println("skipped $count Live settings of type $name") }
        // Key, model class, default and provenance, which two builds mostly share; the call site
        // and its dex, which are R8's names on one build, go in a table of their own.
        val settingsRows = settings.map { it.split('\t').let { f -> listOf(f[0], f[1], f[2], f[5]).joinToString("\t") } }
        val settingsSites = settings.map { it.split('\t').let { f -> listOf(f[0], f[3], f[4]).joinToString("\t") } }
        return Catalogs(version, versionCode, listOf(
            Catalog("GeneratedFeatureGateCatalog", "the AB registry (ConfigItem) and Live SettingModel definitions", abLive),
            Catalog("GeneratedPlayerFeatureGateCatalog", "the player setting registry", player),
            Catalog("GeneratedVeFeatureGateCatalog", "VEConfigCenter.addConfig and the VE camera settings JSON", ve),
            Catalog("GeneratedSettingsManagerCatalog", "SettingsManager reads with a key, a model class and a default", settingsRows),
            Catalog(SITES_NAME, "the call site of each SettingsManager read in GeneratedSettingsManagerCatalog", settingsSites),
        ))
    }

    private fun row(key: String, manager: String, type: String, defaultJson: String, description: String, proof: String) =
        listOf(key, manager, type, "1", "1", "[$defaultJson]", "[]", "[]", description.replace('\t', ' '), proof).joinToString("\t")

    private val DECIMALS = setOf("FLOAT", "DOUBLE")
    private val NUMBERS = setOf("INT", "LONG") + DECIMALS

    /**
     * A registered default under the type a getter reads it as. A number stays a number where the
     * read type can hold it, so -1 registered as a long is still -1 read as a float; anything else
     * is dropped rather than shown as a default the getter would never return.
     */
    private fun carriedDefault(registered: String, read: String, json: String): String = when {
        registered == read -> json
        registered in NUMBERS && read in NUMBERS && (read in DECIMALS || '.' !in json) -> json
        else -> ""
    }

    /** `(parameters)return`, the shape a getter is known by when its name is R8's. */
    private fun signature(method: MethodReference) =
        method.parameterTypes.joinToString("", "(", ")") + method.returnType

    /** The one class carrying every app AB getter, the raw one included, as FeatureGateLabPatch finds it. */
    private fun appAbClass(classes: List<ClassDef>): String {
        val wanted = APP_AB_GETTERS.keys + APP_AB_RAW
        val carriers = classes.filter { classDef ->
            val shapes = classDef.methods.filter { it.accessFlags and STATIC == 0 }.mapTo(HashSet()) { signature(it) }
            wanted.all { it in shapes }
        }.map { it.type }.distinct()
        check(carriers.size == 1) { "expected one class carrying the app AB getters, found ${carriers.size}: $carriers" }
        return carriers.single()
    }

    private const val STATIC = 0x0008

    // ---- the pass over every method ----

    private class Item(val type: String, val defaultJson: String)
    private class VeEntry(val type: String, val defaultJson: String, val description: String, val proof: String)

    /**
     * One SettingsManager read. The default shown is what the call passes where that is a
     * constant, and otherwise the shape of the model class: its fields with the values a new
     * instance starts from before its constructor runs.
     */
    private class SettingsCall(val modelName: String, val modelType: String, val default: Value?, val site: String, val dex: String) {
        fun defaultJson(classes: Map<String, ClassDef>): String = when (val value = default) {
            is StringArray -> value.items.joinToString(",", "[", "]") { if (it == null) "null" else jsonString(it) }
            is Boxed -> value.json
            is Str -> jsonString(value.text)
            is Num -> if (value.bits == 0L) "null" else modelDefaults(modelType, classes)
            else -> modelDefaults(modelType, classes)
        }.ifEmpty { "null" }
    }

    private class Scan {
        val ab = sortedMapOf<String, Item>()
        val live = sortedMapOf<String, Item>()
        val ve = sortedMapOf<String, VeEntry>()
        val player = sortedMapOf<String, Item>()
        val settings = sortedMapOf<String, SettingsCall>()
        val unknownLiveTypes = sortedMapOf<String, Int>()
        /** Each key a typed getter reads, with the types it is read as. */
        val abReads = HashMap<String, MutableSet<String>>()
        val liveReads = HashMap<String, MutableSet<String>>()
        val veReads = HashMap<String, MutableSet<String>>()
        var abClass: String? = null
        var veTypeOrder: List<String> = emptyList()
        var playerOwner: String? = null

        private fun read(into: MutableMap<String, MutableSet<String>>, key: Value?, type: String) {
            val text = (key as? Str)?.text ?: return
            into.getOrPut(text) { sortedSetOf() } += type
        }

        fun visit(dex: String, owner: ClassDef, method: Method) {
            val site = "${owner.type}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"
            val inPlayerRegistry = owner.type == playerOwner && method.name == "<clinit>"
            track(method, onString = { text -> if (text.startsWith("[{") && text.contains("\"dataType\"")) veJson(text) }) { call ->
                val ref = call.method
                when {
                    ref.definingClass == CONFIG_ITEM && ref.name == "<init>" -> {
                        (call.args.firstOrNull() as? Instance)?.args = call.args.drop(1)
                        null
                    }
                    ref.name == "put" && ref.parameterTypes.size == 2 && call.args.size == 3 -> {
                        val key = (call.args[1] as? Str)?.text
                        val item = call.args[2] as? Instance
                        if (key != null && item != null && item.type == CONFIG_ITEM) {
                            val type = (item.args.getOrNull(0) as? EnumConst)?.name
                            if (type != null) ab.putIfAbsent(key, Item(type, json(item.args.getOrNull(1), type)))
                        }
                        null
                    }
                    ref.definingClass == SETTING_MODEL && ref.name == "<init>" && call.args.size == 4 -> {
                        val key = (call.args[1] as? Str)?.text
                        val typeName = (call.args[2] as? Str)?.text
                        val type = liveType(typeName)
                        if (key != null && type != null) {
                            live.putIfAbsent(key, Item(type, json(call.args[3], type)))
                        } else if (key != null) {
                            unknownLiveTypes.merge(typeName ?: "(not a constant)", 1) { a, b -> a + b }
                        }
                        null
                    }
                    ref.definingClass == VE_VALUE_PKT && ref.name == "<init>" -> {
                        (call.args.firstOrNull() as? Instance)?.args = call.args.drop(1)
                        null
                    }
                    ref.definingClass == VE_CONFIG_CENTER && ref.name == "addConfig" && call.args.size == 3 -> {
                        val key = (call.args[1] as? Str)?.text
                        val pkt = call.args[2] as? Instance
                        val type = veType((pkt?.args?.getOrNull(0) as? EnumConst)?.name)
                        if (key != null && pkt != null && type != null) {
                            val description = (pkt.args.getOrNull(3) as? Str)?.text.orEmpty()
                            ve.putIfAbsent(key, VeEntry(type, json(pkt.args.getOrNull(1), type), description, "ve_config_add_config"))
                        }
                        null
                    }
                    ref.definingClass == SETTINGS_MANAGER &&
                        ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;", "Ljava/lang/Class;", "Ljava/lang/Object;") -> {
                        // The last three whether the read is static or on the instance.
                        val (keyArg, modelArg, defaultArg) = call.args.takeLast(3)
                        val key = (keyArg as? Str)?.text
                        val model = modelArg as? ClassConst
                        if (key != null && model != null) {
                            settings.putIfAbsent(key, SettingsCall(binaryName(model.type), model.type, defaultArg, site, dex))
                        }
                        null
                    }
                    ref.definingClass == abClass && APP_AB_GETTERS.containsKey(signature(ref)) -> {
                        val keyAt = ref.parameterTypes.indexOfFirst { it.toString() == "Ljava/lang/String;" }
                        read(abReads, call.args.getOrNull(1 + keyAt), APP_AB_GETTERS.getValue(signature(ref)))
                        null
                    }
                    ref.definingClass == SETTINGS_MANAGER && call.isStatic && ref.parameterTypes.size == 2 &&
                        ref.parameterTypes[0].toString() == "Ljava/lang/String;" &&
                        ref.parameterTypes[1].toString() == ref.returnType && ref.returnType in VALUE_TYPES -> {
                        read(abReads, call.args[0], VALUE_TYPES.getValue(ref.returnType))
                        null
                    }
                    ref.definingClass == LIVE_SETTINGS_MANAGER && ref.name in LIVE_GETTERS && !call.isStatic &&
                        ref.parameterTypes.size == 2 && ref.parameterTypes[0].toString() == "Ljava/lang/String;" -> {
                        read(liveReads, call.args[1], LIVE_GETTERS.getValue(ref.name))
                        null
                    }
                    ref.definingClass == VE_CONFIG_CENTER && ref.name == "getValue" && !call.isStatic &&
                        ref.parameterTypes.size == 2 && ref.parameterTypes[0].toString() == "Ljava/lang/String;" -> {
                        VALUE_TYPES[ref.parameterTypes[1].toString()]?.let { read(veReads, call.args[1], it) }
                        null
                    }
                    inPlayerRegistry && call.isStatic &&
                        ref.parameterTypes.map { it.toString() } == listOf("Ljava/lang/Object;", "Ljava/lang/String;") -> {
                        val key = (call.args[1] as? Str)?.text
                        val type = kindOf(call.args[0])
                        if (key != null && type != null) player.putIfAbsent(key, Item(type, json(call.args[0], type)))
                        null
                    }
                    else -> null
                }
            }
        }

        private fun veJson(text: String) {
            for (objectText in Regex("""\{[^{}]*}""").findAll(text).map { it.value }) {
                val key = Regex(""""key"\s*:\s*"([^"]*)"""").find(objectText)?.groupValues?.get(1) ?: continue
                val index = Regex(""""dataType"\s*:\s*(\d+)""").find(objectText)?.groupValues?.get(1)?.toInt() ?: continue
                val type = veType(veTypeOrder.getOrNull(index)) ?: continue
                val raw = Regex(""""defaultVal"\s*:\s*("(?:[^"\\]|\\.)*"|[^,}\s]+)""").find(objectText)?.groupValues?.get(1) ?: continue
                val description = Regex(""""description"\s*:\s*"([^"]*)"""").find(objectText)?.groupValues?.get(1).orEmpty()
                ve.putIfAbsent(key, VeEntry(type, normaliseJson(raw, type), description, "ve_config_embedded_registry_json"))
            }
        }
    }

    // ---- values a register can hold ----

    internal sealed interface Value
    internal class Str(val text: String) : Value
    internal class Num(val bits: Long) : Value
    internal class EnumConst(val owner: String, val name: String) : Value
    internal class Boxed(val kind: String, val json: String) : Value
    internal class ClassConst(val type: String) : Value
    internal class Instance(val type: String) : Value { var args: List<Value?> = emptyList() }
    internal class StringArray(size: Int) : Value { val items = arrayOfNulls<String>(size) }

    internal class Call(val method: MethodReference, val args: List<Value?>, val opcode: Opcode) {
        val isStatic get() = opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE
    }

    /**
     * Walks one method in order, keeping what each register holds as far as constants, boxed
     * constants, enum constants, class constants, new instances and string arrays go. A switch's
     * cases each start from what the registers held at the switch, which is what the Live
     * settings provider needs: its type names and defaults are loaded once above a string switch.
     * [onCall] sees every invoke with its arguments lined up with the parameters and may return
     * what the call leaves for move-result.
     */
    internal fun track(method: Method, onString: (String) -> Unit = {}, onCall: (Call) -> Value?) {
        val implementation = method.implementation ?: return
        val instructions = implementation.instructions.toList()
        val addresses = IntArray(instructions.size)
        var address = 0
        for ((i, instruction) in instructions.withIndex()) {
            addresses[i] = address
            address += instruction.codeUnits
        }
        val indexAt = HashMap<Int, Int>(instructions.size * 2)
        addresses.forEachIndexed { i, a -> indexAt[a] = i }
        val caseOf = HashMap<Int, Int>()
        for ((i, instruction) in instructions.withIndex()) {
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = indexAt[addresses[i] + (instruction as OffsetInstruction).codeOffset]
                    ?.let { instructions[it] } as? SwitchPayload ?: continue
                for (element in payload.switchElements) caseOf[addresses[i] + element.offset] = i
            }
        }
        val snapshots = HashMap<Int, HashMap<Int, Value>>()
        var registers = HashMap<Int, Value>()
        var result: Value? = null
        for ((i, instruction) in instructions.withIndex()) {
            caseOf[addresses[i]]?.let { switch -> snapshots[switch]?.let { registers = HashMap(it) } }
            val opcode = instruction.opcode
            when (opcode) {
                Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> snapshots[i] = HashMap(registers)
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> {
                    val text = ((instruction as ReferenceInstruction).reference as StringReference).string
                    registers[(instruction as OneRegisterInstruction).registerA] = Str(text)
                    onString(text)
                }
                Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16 ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        Num((instruction as NarrowLiteralInstruction).narrowLiteral.toLong())
                Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16 ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        Num((instruction as WideLiteralInstruction).wideLiteral)
                Opcode.CONST_CLASS -> registers[(instruction as OneRegisterInstruction).registerA] =
                    ClassConst(((instruction as ReferenceInstruction).reference as TypeReference).type)
                Opcode.SGET_OBJECT -> {
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    val a = (instruction as OneRegisterInstruction).registerA
                    when {
                        field.definingClass == "Ljava/lang/Boolean;" && (field.name == "TRUE" || field.name == "FALSE") ->
                            registers[a] = Boxed("BOOLEAN", if (field.name == "TRUE") "true" else "false")
                        field.type == field.definingClass -> registers[a] = EnumConst(field.definingClass, field.name)
                        else -> registers.remove(a)
                    }
                }
                Opcode.NEW_INSTANCE -> registers[(instruction as OneRegisterInstruction).registerA] =
                    Instance(((instruction as ReferenceInstruction).reference as TypeReference).type)
                Opcode.NEW_ARRAY -> {
                    val two = instruction as TwoRegisterInstruction
                    val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
                    val size = (registers[two.registerB] as? Num)?.bits
                    if (type == "[Ljava/lang/String;" && size != null && size in 0..4096) {
                        registers[two.registerA] = StringArray(size.toInt())
                    } else {
                        registers.remove(two.registerA)
                    }
                }
                Opcode.APUT_OBJECT -> {
                    val three = instruction as ThreeRegisterInstruction
                    val array = registers[three.registerB] as? StringArray
                    val index = (registers[three.registerC] as? Num)?.bits?.toInt()
                    val value = registers[three.registerA] as? Str
                    if (array != null && index != null && index in array.items.indices) array.items[index] = value?.text
                }
                Opcode.FILLED_NEW_ARRAY, Opcode.FILLED_NEW_ARRAY_RANGE -> {
                    val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
                    val values = registersOf(instruction).map { registers[it] }
                    result = if (type == "[Ljava/lang/String;" && values.all { it is Str }) {
                        StringArray(values.size).also { array -> values.forEachIndexed { k, v -> array.items[k] = (v as Str).text } }
                    } else null
                }
                Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16,
                Opcode.MOVE_OBJECT_16, Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16 -> {
                    val two = instruction as TwoRegisterInstruction
                    val value = registers[two.registerB]
                    if (value == null) registers.remove(two.registerA) else registers[two.registerA] = value
                }
                Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT -> {
                    val a = (instruction as OneRegisterInstruction).registerA
                    val value = result
                    if (value == null) registers.remove(a) else registers[a] = value
                    result = null
                }
                else -> {
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    if (reference is MethodReference) {
                        val raw = registersOf(instruction)
                        val args = ArrayList<Value?>()
                        var r = 0
                        if (!opcode.name.startsWith("INVOKE_STATIC")) args += raw.getOrNull(r++)?.let { registers[it] }
                        for (parameter in reference.parameterTypes) {
                            args += raw.getOrNull(r)?.let { registers[it] }
                            r += if (parameter.toString() == "J" || parameter.toString() == "D") 2 else 1
                        }
                        result = onCall(Call(reference, args, opcode)) ?: boxed(reference, args)
                    } else if (opcode.setsRegister()) {
                        val a = (instruction as? OneRegisterInstruction)?.registerA
                        if (a != null) {
                            registers.remove(a)
                            if (opcode.setsWideRegister()) registers.remove(a + 1)
                        }
                    }
                }
            }
        }
    }

    private fun registersOf(instruction: Instruction): List<Int> = when (instruction) {
        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE,
            instruction.registerF, instruction.registerG).take(instruction.registerCount)
        else -> emptyList()
    }

    /** What `Integer.valueOf(5)` and its siblings leave behind. */
    private fun boxed(reference: MethodReference, args: List<Value?>): Value? {
        if (reference.name != "valueOf") return null
        val bits = (args.firstOrNull() as? Num)?.bits ?: return null
        return when (reference.definingClass) {
            "Ljava/lang/Integer;" -> Boxed("INT", bits.toInt().toString())
            "Ljava/lang/Long;" -> Boxed("LONG", bits.toString())
            "Ljava/lang/Float;" -> Boxed("FLOAT", floatJson(bits))
            "Ljava/lang/Double;" -> Boxed("DOUBLE", doubleJson(bits))
            "Ljava/lang/Boolean;" -> Boxed("BOOLEAN", if (bits != 0L) "true" else "false")
            else -> null
        }
    }

    /** A float constant as written in source, 0.005 rather than the 0.004999999888 it widens to. */
    private fun floatJson(bits: Long) = decimal(java.lang.Float.intBitsToFloat(bits.toInt()).toString())

    private fun doubleJson(bits: Long) = decimal(java.lang.Double.longBitsToDouble(bits).toString())

    /** A number in plain JSON, or nothing for the values JSON has no spelling for. */
    private fun decimal(text: String): String =
        text.trim().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: ""

    /**
     * A default as the Lab's JSON array element, typed by the setting it belongs to; empty where
     * the default is null or not a constant, which the Lab shows as no default.
     */
    private fun json(value: Value?, type: String): String = when (value) {
        is Boxed -> value.json
        is Str -> jsonString(value.text)
        is Num -> when (type) {
            "BOOLEAN" -> if (value.bits != 0L) "true" else "false"
            "FLOAT" -> floatJson(value.bits)
            "DOUBLE" -> doubleJson(value.bits)
            "STRING" -> ""
            else -> value.bits.toString()
        }
        else -> ""
    }

    private fun kindOf(value: Value?): String? = when (value) {
        is Boxed -> value.kind
        is Str -> "STRING"
        else -> null
    }

    private fun normaliseJson(raw: String, type: String): String = when (type) {
        "BOOLEAN" -> if (raw.trim() == "true") "true" else "false"
        "STRING" -> if (raw.startsWith("\"")) raw else jsonString(raw)
        else -> decimal(raw)
    }

    private fun liveType(name: String?): String? = when (name) {
        "boolean" -> "BOOLEAN"
        "int" -> "INT"
        "long" -> "LONG"
        "float" -> "FLOAT"
        "double" -> "DOUBLE"
        "java.lang.String" -> "STRING"
        else -> null
    }

    private fun veType(name: String?): String? = when (name) {
        "BOOLEAN" -> "BOOLEAN"
        "INTEGER", "INT" -> "INT"
        "LONG" -> "LONG"
        "FLOAT" -> "FLOAT"
        "DOUBLE" -> "DOUBLE"
        "STRING" -> "STRING"
        else -> null
    }

    /** An enum's constants in declaration order, read off the names its static initializer passes. */
    private fun enumOrder(classDef: ClassDef?): List<String> {
        val clinit = classDef?.methods?.firstOrNull { it.name == "<clinit>" } ?: return emptyList()
        val names = mutableListOf<String>()
        track(clinit, onString = { names += it }) { null }
        return names
    }

    private fun loadsString(method: Method?, text: String): Boolean =
        method?.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference.let { ref -> ref is StringReference && ref.string == text }
        } == true

    /**
     * A model class as the Lab shows its default: each instance field, its superclasses' too,
     * with the value a new instance holds before its constructor runs.
     */
    private fun modelDefaults(type: String, classes: Map<String, ClassDef>): String {
        if (type.startsWith("[") || type.startsWith("Ljava/") || type.startsWith("Lkotlin/")) return "null"
        val fields = mutableListOf<String>()
        var current = classes[type]
        var depth = 0
        while (current != null && depth++ < 8) {
            for (field in current.instanceFields) {
                val value = when (field.type) {
                    "Z" -> "false"
                    "I", "J", "S", "B", "C", "F", "D" -> "0"
                    else -> "null"
                }
                fields += jsonString(field.name) + ":" + value
            }
            current = current.superclass?.let { classes[it] }
        }
        return (listOf("\"__class\":" + jsonString(type)) + fields).joinToString(",", "{", "}")
    }

    /** The name `Class.forName` takes: `[Ljava.lang.String;` for an array, `a.b.C$D` for a class. */
    private fun binaryName(type: String): String = when {
        type.startsWith("[") -> type.replace('/', '.')
        type.startsWith("L") && type.endsWith(";") -> type.substring(1, type.length - 1).replace('/', '.')
        else -> type
    }

    internal fun jsonString(text: String): String = buildString {
        append('"')
        for (c in text) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    // ---- the APK's own version and the Java files ----

    private fun manifestVersion(apk: File): Pair<String, String> {
        val bytes = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT || parser.name != "manifest") continue
            var name = ""
            var code = ""
            for (i in 0 until parser.attributeCount) {
                when (parser.getAttributeNameResourceId(i)) {
                    0x0101021c -> name = parser.getAttributeStringValue(i)
                    0x0101021b -> code = parser.getAttributeStringValue(i)
                }
            }
            return name to code
        }
        error("${apk.name} has no manifest element")
    }

    private const val HEADER = "/*\n * Copyright 2026 Metra contributors\n * https://github.com/SysAdminDoc/hushfeed\n *\n" +
        " * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).\n */\n" +
        "package app.morphe.extension.tiktok.featuregatelab;\n\n"

    private fun from(builds: List<Catalogs>) =
        builds.joinToString(" and ") { "TikTok ${it.version} (version code ${it.versionCode})" }

    fun javaSource(table: Combined, builds: List<Catalogs>): String = buildString {
        append(HEADER)
        append("/**\n * Generated from ${from(builds)}, from ${table.source}.\n")
        append(" * The rows every build has are stored once; each build's other rows follow, in\n")
        append(" * $BUILDS_NAME.BUILDS order, and a build's rows are the shared ones then its own.\n")
        append(" * Do not edit: ./gradlew :patches:generateGateCatalog writes it again.\n */\n")
        append("final class ${table.className} {\n")
        append("    static final int[] ENTRY_COUNTS = {")
        append(builds.indices.joinToString(", ") { table.rowsOf(it).size.toString() }).append("};\n")
        append("    static final String[] SHARED_GZIP_BASE64 = {\n")
        appendChunks(this, table.shared, "            ")
        append("    };\n")
        append("    static final String[][] OWN_GZIP_BASE64 = {\n")
        table.own.forEachIndexed { index, rows ->
            append("            {\n")
            appendChunks(this, rows, "                    ")
            append(if (index < table.own.size - 1) "            },\n" else "            }\n")
        }
        append("    };\n\n    private ${table.className}() {\n    }\n}\n")
    }

    fun buildsSource(builds: List<Catalogs>): String = buildString {
        append(HEADER)
        append("/**\n * The TikTok builds the Feature Gate Lab's catalogs were generated from, oldest first.\n")
        append(" * Do not edit: ./gradlew :patches:generateGateCatalog writes it again.\n */\n")
        append("final class $BUILDS_NAME {\n")
        append("    static final String[] BUILDS = {").append(builds.joinToString(", ") { "\"${it.version}\"" }).append("};\n\n")
        append("    private $BUILDS_NAME() {\n    }\n}\n")
    }

    /** The rows as gzip in Base64, in 120-character string literals; nothing at all for no rows. */
    private fun appendChunks(out: StringBuilder, rows: List<String>, indent: String) {
        if (rows.isEmpty()) return
        val text = rows.joinToString("\n", postfix = "\n")
        val gzip = ByteArrayOutputStream().also { buffer ->
            GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val chunks = Base64.getEncoder().encodeToString(gzip).chunked(120)
        chunks.forEachIndexed { i, chunk ->
            out.append(indent).append('"').append(chunk).append('"').append(if (i < chunks.size - 1) ",\n" else "\n")
        }
    }
}
