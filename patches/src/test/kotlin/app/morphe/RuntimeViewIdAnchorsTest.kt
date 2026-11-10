package app.morphe

import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view ids the extension looks up by name on the phone, held against every TikTok build the
 * bundle declares, each by the names that apply on it.
 *
 * <p>Those lookups happen at run time, so a renamed id used to surface as a switch that did nothing
 * on somebody's phone. Most of the names are the three character ones TikTok's build makes up, and
 * each build hands the same names out again, at nearly the same index, mostly to other views:
 * g6r is 0x7f0a2170 on both 46.9.3 and 47.0.3, the share sheet's on one and the like button's on
 * the other. Every name here exists in every retained build from 46.2.3 on, so a name that
 * resolves proves very little. What `view-id-anchors.txt` adds is an owner, a class with a real
 * name whose code loads the id. VideoDiggAssem loading g6r's id says g6r is still the like button,
 * and a build that hands g6r to something else fails here instead of on a phone. An id that only
 * layout XML sets is held through the layout instead: the feed cell's inflater loads the survey
 * layouts, and the survey card's id has to be one of the ids those layouts set.
 *
 * <p>The table has to list exactly the lookups the code makes, so it can't fall behind the code.
 * Older fixtures only report what they cover, since the bundle doesn't claim them. Each group
 * carries the declared build's name alone: when the bundle moves to a newer TikTok, the owner and
 * tell that held the old name find the new one, and the old build's name goes with the old build.
 */
class RuntimeViewIdAnchorsTest {
    @Test
    fun `the anchor table lists exactly the view ids the extension looks up`() {
        val table = anchors().map { it.lookup }.toSet()
        val code = lookups()
        assertTrue("could not find any id lookup in the extension", code.isNotEmpty())
        val missing = code - table
        val stale = table - code
        assertTrue(
            "The extension looks these ids up and view-id-anchors.txt doesn't list them. Add a line " +
                "with the package and, where one exists, a class of the target that loads the id:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
        assertTrue(
            "view-id-anchors.txt lists lookups the extension no longer makes, or makes with other " +
                "names or in another order:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /**
     * A group names one view, with one name on each declared build. Its second name used to be the
     * 46.x name of the same view, tried when the current one found nothing, and on 47.0.3 each of
     * those names some other view, which the fallback then hid or read. A name written for one
     * build is skipped on every other, so a build the bundle doesn't declare never resolves it to
     * the wrong view. A group may try more than one name on a build only with a reason in
     * [MORE_THAN_ONE_NAME], and that list only shrinks: a group that drops its extra names fails
     * here until its entry goes too.
     */
    @Test
    fun `a group looks up more than one name only for a reason the test records`() {
        val several = anchors().filter { anchor ->
            Fixtures.declaredVersions().any { anchor.namesOn(it).size > 1 }
        }.map { it.lookup }.toSortedSet()
        assertEquals(
            "Groups that try more than one name. On the target a second name is an older build's " +
                "name for the view, now some other view, so drop it; or record why the group needs " +
                "both in MORE_THAN_ONE_NAME. A recorded group that has one name now loses its entry.",
            MORE_THAN_ONE_NAME.keys.toSortedSet(),
            several,
        )
    }

    @Test
    fun `every anchor resolves on each declared target and its owner loads the id`() {
        val compatibility = AppCompatibilities.tiktok().single()
        val anchors = anchors()
        for (version in Fixtures.declaredVersions()) {
            val apk = Fixtures.apkOf(version)
            val coverage = coverage(apk, anchors, checkNotNull(compatibility.packageName), version)
            // Every group has had an owner since fdaec7f8. A group that only resolves passes
            // whatever view TikTok hands its name to next, so a missing owner fails like a broken one.
            val failures = coverage.filter { it.state != State.OWNED }.map {
                "${it.anchor.lookup}: " +
                    if (it.state == State.UNOWNED) "no owner holds it (${it.detail})" else it.detail
            }
            assertEquals("${apk.name} (the declared $version target)", emptyList<String>(), failures)
            println("${apk.name}: ${coverage.count { it.state == State.OWNED }} owners load their id")
        }
    }

    @Test
    fun `layout backed view ids keep their element shape across declared targets`() {
        val anchors = anchors()
        val appPackage = checkNotNull(AppCompatibilities.tiktok().single().packageName)
        val versions = Fixtures.declaredVersions()
        val shapesByVersion = versions.associateWith { version ->
            val apk = Fixtures.apkOf(version)
            val ids = ResourceIds.read(apk)
            val selected = anchors.associateWith { anchor ->
                anchor.namesOn(version).firstNotNullOfOrNull { name ->
                    ids[packageOf(anchor, appPackage)]?.get(name)?.singleOrNull()
                }
            }
            val wanted = selected.values.filterNotNull().toSet()
            val shapes = mutableMapOf<Int, MutableSet<Pair<String?, String?>>>()
            val paths = ResourceIds.files(apk, "layout").values
                .flatMap { it.values.flatMap { paths -> paths } }.distinct()
            ZipFile(apk).use { zip ->
                for (path in paths) for (use in elementsSetIn(zip, path)) {
                    if (use.id !in wanted) continue
                    val tag = use.tag?.let { if (it.matches(OBFUSCATED)) "<obfuscated>" else it }
                    val parent = use.parentTag?.let { if (it.matches(OBFUSCATED)) "<obfuscated>" else it }
                    shapes.getOrPut(use.id) { mutableSetOf() } += tag to parent
                }
            }
            anchors.associateWith { anchor -> selected[anchor]?.let { shapes[it]?.toSet() }.orEmpty() }
        }
        val first = versions.first()
        val reference = shapesByVersion.getValue(first)
        val layoutBacked = anchors.count { reference.getValue(it).isNotEmpty() }
        assertTrue("only $layoutBacked anchors have layout elements on $first", layoutBacked > 30)
        for (version in versions.drop(1)) for (anchor in anchors) {
            val before = reference.getValue(anchor)
            val after = shapesByVersion.getValue(version).getValue(anchor)
            if (before.isNotEmpty() || after.isNotEmpty()) {
                assertEquals("${anchor.lookup}: layout element and parent on $first and $version", before, after)
            }
        }
    }

    /**
     * TikTok hands its short names out again on every build, mostly to other views: on 46.7.3 to
     * 46.9.3 no owner loads the id of any name its group lists, and only the ids with real names
     * (desc, title, view_rootview and the like) still hold.
     */
    @Test
    fun `older fixtures report which anchors they cover`() {
        val compatibility = AppCompatibilities.tiktok().single()
        val declared = Fixtures.declaredVersions()
        val older = Fixtures.apks().filter { Fixtures.versionOf(it) !in declared }
        val anchors = anchors()
        for (apk in older) {
            val coverage = coverage(apk, anchors, checkNotNull(compatibility.packageName), Fixtures.versionOf(apk))
            val broken = coverage.filter { it.state == State.BROKEN }
            println("${apk.name}: ${coverage.count { it.state == State.OWNED }} of " +
                "${anchors.count { it.owner != null }} owners load the id of the first name their group " +
                "defines there; ${broken.size} groups don't hold")
            broken.forEach { println("  ${it.anchor.lookup}: ${it.detail}") }
        }
        if (older.isEmpty()) println("No fixture older than $declared to report on.")
    }

    @Test
    fun `the resource table reader finds entries in every chunk and entry layout it reads`() {
        for ((flags, compact) in listOf(0 to false, SPARSE to false, OFFSET16 to false, 0 to true)) {
            for (layouts in listOf(false, true)) {
                val table = syntheticTable(flags, compact, layouts)
                val label = "type chunk flags $flags, compact entries $compact, layouts $layouts"
                assertEquals(
                    label,
                    mapOf("com.example" to mapOf("first" to listOf(0x7f020000), "third" to listOf(0x7f020002))),
                    ResourceIds.read(table),
                )
                assertEquals(
                    label,
                    if (layouts) mapOf("com.example" to mapOf(
                        0x7f030000 to listOf("res/a/first.xml"), 0x7f030002 to listOf("res/a/third.xml")))
                    else emptyMap(),
                    ResourceIds.files(table, "layout"),
                )
            }
        }
    }

    /** Each group handed another group's id on each declared build, by that build's name for it. */
    @Test
    fun `semantic owners reject an unrelated id for every group that needs one`() {
        val actions = "share/ShareSheetTools.java|ACTIONS_LIST_IDS|47.4.3:a5x"
        val dislike = "comment/CommentTools.java|DISLIKE_BUTTON_IDS|47.4.3:k11"
        val dislikeIcon = "comment/CommentTools.java|DISLIKE_ICON_IDS|47.4.3:mne"
        val captionText = "captions/CaptionStyle.java|TEXT_IDS|47.4.3:dm5"
        val wrongNames = mapOf(
            "47.4.3" to mapOf(actions to "k11", dislike to "a5x", dislikeIcon to "a5x", captionText to "k11"),
        )
        assertEquals(Fixtures.declaredVersions().toSet(), wrongNames.keys)
        for ((version, wrong) in wrongNames) {
            val changed = anchors().map { anchor ->
                wrong[anchor.lookup]?.let { anchor.copy(names = listOf(it)) } ?: anchor
            }
            val rejected = coverage(Fixtures.apkOf(version), changed, checkNotNull(
                AppCompatibilities.tiktok().single().packageName,
            ), version).filter { it.anchor.lookup in wrong && it.state == State.BROKEN }
                .map { it.anchor.lookup }.toSet()
            assertEquals(version, wrong.keys, rejected)
        }
    }

    /**
     * A tell has to be doing work: its owner loads more than one id, so the line would fail
     * without one, and the verdict already holds that the tell picks the group's id alone.
     *
     * <p>The first form of this test swapped each group's id for a sibling and expected
     * BROKEN, which the verdict guarantees by construction: the tell is evaluated against the
     * owner's uses, and the name only enters as the answer to compare against, so 801 swaps
     * could not fail for any reason but the test's own wiring. What can fail is a tell carried
     * by a single-id owner, decoration the next edit to that owner will trust. An owner that
     * loads ids the verdict cannot see, through an array payload, is reported by owned() itself.
     */
    @Test
    fun `every tell is carried by an owner that needs one`() {
        val appPackage = checkNotNull(AppCompatibilities.tiktok().single().packageName)
        for (version in Fixtures.declaredVersions()) tellsNeeded(Fixtures.apkOf(version), version, appPackage)
    }

    private fun tellsNeeded(apk: File, version: String, appPackage: String) {
        val anchors = anchors()
        val facts = facts(apk, anchors, appPackage)
        val owned = coverage(facts, anchors, appPackage, version).filter { it.state == State.OWNED }
        assertTrue("too few owned groups to check: ${owned.size}", owned.size > 40)
        val told = owned.filter { it.anchor.tell != null }
        assertTrue("too few tells to check: ${told.size}", told.size > 10)
        val idle = told.filter { it.candidates.size < 2 }
            .map { "${it.anchor.lookup}: ${it.anchor.tell} on an owner that loads ${it.candidates.size} id" }
        assertEquals(emptyList<String>(), idle)
        println("${apk.name}: ${told.size} tells, each on an owner that loads ${told.minOf { it.candidates.size }} ids or more")
    }

    private data class Anchor(
        val lookup: String,
        val names: List<String>,
        val packageSuffix: String,
        val owner: Owner?,
        val tell: Tell?,
    ) {
        /**
         * The names the code resolves on [version], in its order: that build's own, written
         * `47.1.4:g85`, and the real names with no build in front. BuildNames in the shared
         * extension skips every other build's.
         */
        fun namesOn(version: String): List<String> = names.mapNotNull { name ->
            val colon = name.indexOf(':')
            when {
                colon < 0 -> name
                name.substring(0, colon) == version -> name.substring(colon + 1)
                else -> null
            }
        }
    }

    private sealed interface Owner {
        /** A real-named class loads the id directly. */
        data class ClassLiteral(val className: String) : Owner

        /** A real-named class loads a layout that sets the id. */
        data class LayoutClass(val className: String) : Owner

        /** One method loads both the id and this semantic string. */
        data class MethodString(val value: String) : Owner

        /**
         * One method that names this semantic string loads a layout that sets the id: for a view
         * whose layout is inflated by an obfuscated class, as the auto caption strip is.
         */
        data class LayoutMethodString(val value: String) : Owner

        /** A class that declares this real method name loads the id somewhere in that class. */
        data class ClassMethod(val name: String) : Owner
    }

    /**
     * How a group's id is told from the other ids the same owner loads.
     *
     * <p>An owner that loads many ids passes any of them: point SYSTEM_ROW_IDS at kp1 and
     * PreloadInboxTask, which inflates 51 ids between its layouts, still "owns" it. The tell
     * is the one fact about the id that its siblings under the same owner do not share, and
     * the test holds every group to it: the tell must pick the group's id and no other.
     */
    private sealed interface Tell {
        /**
         * The id is set on, cast to or stored as a view of this type: the receiver of `setId`
         * in a generated inflater, the `check-cast` after a `findViewById`, or the type of the
         * field the found view lands in. With an ordinal, the n-th distinct such id in code order.
         */
        data class On(val type: String, val ordinal: Int?) : Tell

        /** The id sits on an element with this tag in a layout the owner inflates; the n-th such id. */
        data class Tag(val tag: String, val ordinal: Int?) : Tell

        /** The id is handed to a method of this class, other than a find or `setId`. */
        data class To(val className: String) : Tell

        /** The n-th distinct id the owner loads, in code order. For an id nothing else tells apart. */
        data class At(val ordinal: Int) : Tell
    }

    /** One place an owner loads an id: the view type it reaches, or the class it is handed to. */
    private class IdUse(val id: Int, val type: String?, val to: String?, val tag: String?, val parentTag: String? = null)

    private enum class State { OWNED, UNOWNED, BROKEN }

    private class Coverage(
        val anchor: Anchor,
        val state: State,
        val detail: String,
        /** Every id the owner loads on this APK, the group's own included, for the sibling test. */
        val candidates: Set<Int> = emptySet(),
    )

    /** Everything read off one APK that the verdicts need, read once so the sibling test can reuse it. */
    private class Facts(
        val tables: Map<String, Map<String, List<Int>>>,
        val loaded: Map<String, Set<Int>>,
        val classUses: Map<String, List<IdUse>>,
        val layouts: Map<Int, List<String>>,
        val elements: Map<String, List<IdUse>>,
        val semantic: SemanticLiterals,
        /** Every id in every package's table, to tell a table id among the literals a class loads. */
        val allIds: Set<Int>,
    )

    private fun facts(apk: File, anchors: List<Anchor>, appPackage: String): Facts {
        val tables = ResourceIds.read(apk)
        val allIds = tables.values.flatMap { names -> names.values.flatten() }.toSet()
        val classOwners = anchors.mapNotNull { anchor ->
            when (val owner = anchor.owner) {
                is Owner.ClassLiteral -> owner.className
                is Owner.LayoutClass -> owner.className
                else -> null
            }
        }.map(::descriptor).toSet()
        val (loaded, classUses) = literalsLoadedBy(apk, classOwners, allIds)
        // Every package's layouts in one map: an id carries its package in its top byte.
        val layouts = if (anchors.none { it.owner is Owner.LayoutClass || it.owner is Owner.LayoutMethodString }) emptyMap()
            else ResourceIds.files(apk, "layout").values.fold(mutableMapOf<Int, List<String>>()) { all, one -> all.apply { putAll(one) } }
        val semantic = semanticLiteralsLoadedBy(
            apk,
            allIds,
            anchors.mapNotNull { (it.owner as? Owner.MethodString)?.value }.toSet(),
            anchors.mapNotNull { (it.owner as? Owner.ClassMethod)?.name }.toSet(),
            layouts.keys,
            anchors.mapNotNull { (it.owner as? Owner.LayoutMethodString)?.value }.toSet(),
        )
        val elements = mutableMapOf<String, List<IdUse>>()
        ZipFile(apk).use { zip ->
            for (anchor in anchors) {
                val paths = when (val owner = anchor.owner) {
                    is Owner.LayoutClass -> layoutPaths(loaded, layouts, owner)
                    is Owner.LayoutMethodString -> layoutPaths(semantic, layouts, owner)
                    else -> continue
                }
                for (path in paths) {
                    elements.getOrPut(path) { elementsSetIn(zip, path) }
                }
            }
        }
        return Facts(tables, loaded, classUses, layouts, elements, semantic, allIds)
    }

    /** The layouts a layout owner inflates, in the order of their ids, each path once. */
    private fun layoutPaths(loaded: Map<String, Set<Int>>, layouts: Map<Int, List<String>>, owner: Owner.LayoutClass): List<String> =
        loaded[descriptor(owner.className)].orEmpty().sorted().flatMap { layouts[it].orEmpty() }.distinct()

    /** The layouts the methods naming the owner's string load, in the order of their ids. */
    private fun layoutPaths(semantic: SemanticLiterals, layouts: Map<Int, List<String>>, owner: Owner.LayoutMethodString): List<String> =
        semantic.methodStringLayouts[owner.value].orEmpty().sorted().flatMap { layouts[it].orEmpty() }.distinct()

    private fun packageOf(anchor: Anchor, appPackage: String) =
        if (anchor.packageSuffix == "app") appPackage else "$appPackage.${anchor.packageSuffix}"

    /**
     * Where each anchor stands on one APK: the first name it defines is the one the code will use
     * there, so that is the one its owner has to load.
     */
    private fun coverage(apk: File, anchors: List<Anchor>, appPackage: String, version: String): List<Coverage> =
        coverage(facts(apk, anchors, appPackage), anchors, appPackage, version)

    /** Each anchor on one build, held to the names that apply there. */
    private fun coverage(facts: Facts, anchors: List<Anchor>, appPackage: String, version: String): List<Coverage> {
        return anchors.map { anchor ->
            val packageName = packageOf(anchor, appPackage)
            val ids = facts.tables[packageName].orEmpty()
            val names = anchor.namesOn(version)
            val used = names.firstOrNull { it in ids }
            val candidates = used?.let { ids.getValue(it) }.orEmpty()
            val owner = anchor.owner
            when {
                used == null -> Coverage(anchor, State.BROKEN, "$packageName defines none of $names")
                // Two entries under one name: an invented short name that is also a real one. The
                // lookup on the phone lands on one of them, and which one is the platform's choice.
                candidates.size > 1 -> Coverage(anchor, State.BROKEN,
                    "$packageName gives $used ${candidates.size} ids, ${candidates.joinToString { hex(it) }}")
                owner == null -> Coverage(anchor, State.UNOWNED, "resolves as $used")
                else -> owned(facts, anchor, owner, used, candidates.single(), names)
            }
        }
    }

    /** The owner's verdict on one id: does it load the id, and does the tell pick that id alone. */
    private fun owned(facts: Facts, anchor: Anchor, owner: Owner, used: String, wanted: Int, names: List<String>): Coverage {
        val uses: List<IdUse>
        val loads: String
        when (owner) {
            is Owner.LayoutClass -> {
                val literals = facts.loaded[descriptor(owner.className)]
                    ?: return Coverage(anchor, State.BROKEN, "there is no class ${owner.className}")
                val paths = layoutPaths(facts.loaded, facts.layouts, owner)
                if (paths.isEmpty()) return Coverage(anchor, State.BROKEN, "${owner.className} loads no layout")
                uses = paths.flatMap { facts.elements[it].orEmpty() }
                if (uses.none { it.id == wanted }) {
                    return Coverage(anchor, State.BROKEN,
                        "none of the ${paths.size} layouts ${owner.className} loads sets ${hex(wanted)}, the id of $used",
                        uses.map { it.id }.toSet())
                }
                loads = "${owner.className} loads a layout that sets $used"
                check(literals.isNotEmpty())
            }
            is Owner.ClassLiteral -> {
                val literals = facts.loaded[descriptor(owner.className)]
                    ?: return Coverage(anchor, State.BROKEN, "there is no class ${owner.className}")
                uses = facts.classUses[descriptor(owner.className)].orEmpty()
                if (wanted !in literals) {
                    val ids = facts.tables.values.firstOrNull { names.any { name -> name in it } }.orEmpty()
                    val others = names.filter { name -> ids[name].orEmpty().any { it in literals } }
                    return Coverage(anchor, State.BROKEN,
                        "${owner.className} doesn't load ${hex(wanted)}, the id of $used" +
                            if (others.isEmpty()) ", nor the id of any other name in the group"
                            else "; it loads the id of ${others.joinToString()}",
                        uses.map { it.id }.toSet())
                }
                loads = "${owner.className} loads $used"
            }
            is Owner.LayoutMethodString -> {
                val paths = layoutPaths(facts.semantic, facts.layouts, owner)
                if (paths.isEmpty()) return Coverage(anchor, State.BROKEN, "no method that names ${owner.value} loads a layout")
                uses = paths.flatMap { facts.elements[it].orEmpty() }
                if (uses.none { it.id == wanted }) {
                    return Coverage(anchor, State.BROKEN,
                        "none of the ${paths.size} layouts a method naming ${owner.value} loads sets ${hex(wanted)}, the id of $used",
                        uses.map { it.id }.toSet())
                }
                loads = "a method that names ${owner.value} loads a layout that sets $used"
            }
            is Owner.MethodString -> {
                uses = facts.semantic.methodStringUses[owner.value].orEmpty()
                if (wanted !in facts.semantic.methodStrings[owner.value].orEmpty()) {
                    return Coverage(anchor, State.BROKEN,
                        "no method that names ${owner.value} loads ${hex(wanted)}, the id of $used", uses.map { it.id }.toSet())
                }
                loads = "a method that names ${owner.value} loads $used"
            }
            is Owner.ClassMethod -> {
                uses = facts.semantic.classMethodUses[owner.name].orEmpty()
                if (wanted !in facts.semantic.classMethods[owner.name].orEmpty()) {
                    return Coverage(anchor, State.BROKEN,
                        "no class that declares ${owner.name} loads ${hex(wanted)}, the id of $used", uses.map { it.id }.toSet())
                }
                loads = "a class that declares ${owner.name} loads $used"
            }
        }
        val candidates = uses.map { it.id }.toSet()
        // A table id the owner loads with no use this can read comes through an array payload,
        // which no tell can name. It is a sibling the line cannot be told from, so the line is
        // broken until the owner loads it another way or the group moves, rather than passing
        // as a single-id owner while the payload holds the rest.
        if (owner is Owner.ClassLiteral) {
            val unseen = facts.loaded[descriptor(owner.className)].orEmpty().intersect(facts.allIds) - candidates
            if (unseen.isNotEmpty()) {
                return Coverage(anchor, State.BROKEN,
                    "$loads, but it also loads ${unseen.size} table id(s) with no use this can read, an array payload: " +
                        unseen.joinToString { hex(it) },
                    candidates)
            }
        }
        val tell = anchor.tell
        if (tell == null) {
            return if (candidates.size <= 1) Coverage(anchor, State.OWNED, loads, candidates)
            else Coverage(anchor, State.BROKEN,
                "$loads, but it loads ${candidates.size} ids and the line has no tell. In code order: " +
                    uses.map { it.id }.distinct().joinToString("; ") { id ->
                        "${hex(id)}${if (id == wanted) " (this one)" else ""} ${describe(uses.filter { it.id == id })}"
                    },
                candidates)
        }
        val matching = when (tell) {
            is Tell.On -> uses.filter { it.type != null && typeMatches(it.type, tell.type) }.map { it.id }.distinct()
            is Tell.Tag -> uses.filter { it.tag != null && typeMatches(it.tag, tell.tag) }.map { it.id }.distinct()
            is Tell.To -> uses.filter { it.to != null && typeMatches(it.to, tell.className) }.map { it.id }.distinct()
            is Tell.At -> uses.map { it.id }.distinct()
        }
        val ordinal = when (tell) {
            is Tell.On -> tell.ordinal
            is Tell.Tag -> tell.ordinal
            is Tell.At -> tell.ordinal
            is Tell.To -> null
        }
        val picked = when {
            ordinal != null -> matching.getOrNull(ordinal - 1)
            matching.size == 1 -> matching.single()
            else -> return Coverage(anchor, State.BROKEN,
                "$loads, but its tell $tell matches ${matching.size} ids (${matching.joinToString { hex(it) }}); " +
                    "the id's own uses: ${describe(uses.filter { it.id == wanted })}", candidates)
        }
        return if (picked == wanted) Coverage(anchor, State.OWNED, "$loads, told by $tell", candidates)
        else Coverage(anchor, State.BROKEN,
            "$loads, but its tell $tell picks ${picked?.let { hex(it) } ?: "nothing"} (of ${matching.size} matching), " +
                "not ${hex(wanted)}, the id of $used; the id's own uses: ${describe(uses.filter { it.id == wanted })}",
            candidates)
    }

    private fun describe(uses: List<IdUse>) =
        if (uses.isEmpty()) "none" else uses.joinToString { use ->
            use.type?.let { "on $it" } ?: use.to?.let { "to $it" } ?: use.tag?.let { "tag $it" } ?: "no view"
        }

    /** A tell names a type in full or by its simple name; a layout tag is either shape too. */
    private fun typeMatches(actual: String, wanted: String) =
        actual == wanted || actual.substringAfterLast('.') == wanted

    private fun dotted(descriptor: String) = descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')

    /**
     * Every id the instructions load and what each is handed to, in code order: the receiver's
     * type when the id goes to `setId`, the found view's type after a find (the cast, or the field
     * it lands in, or plainly a View), or the class of any other method the id is passed to. An id
     * whose register is overwritten first, or never read, is kept with nothing known about it.
     */
    private fun usesIn(instructions: List<Instruction>, ids: Set<Int>): List<IdUse> {
        val uses = mutableListOf<IdUse>()
        for ((i, ins) in instructions.withIndex()) {
            val literal = ins as? NarrowLiteralInstruction ?: continue
            val one = ins as? OneRegisterInstruction ?: continue
            val value = literal.narrowLiteral
            if (value !in ids) continue
            val reg = one.registerA
            var use: IdUse? = null
            for (j in i + 1 until instructions.size) {
                val next = instructions[j]
                val args = argRegisters(next)
                if (args == null) {
                    if (writes(next, reg)) break else continue
                }
                val position = args.indexOf(reg)
                if (position < 0) continue
                val ref = (next as ReferenceInstruction).reference as MethodReference
                use = when {
                    ref.name == "setId" && position == 1 -> IdUse(value, receiverType(instructions, j, args[0]), null, null)
                    ref.returnType == "Landroid/view/View;" || ref.name == "findViewById" ->
                        IdUse(value, resultType(instructions, j), null, null)
                    else -> IdUse(value, null, dotted(ref.definingClass), null)
                }
                break
            }
            uses += use ?: IdUse(value, null, null, null)
        }
        return uses
    }

    private fun argRegisters(ins: Instruction): List<Int>? {
        if ((ins as? ReferenceInstruction)?.reference !is MethodReference) return null
        return when (ins) {
            is FiveRegisterInstruction -> listOf(ins.registerC, ins.registerD, ins.registerE, ins.registerF, ins.registerG).take(ins.registerCount)
            is RegisterRangeInstruction -> (ins.startRegister until ins.startRegister + ins.registerCount).toList()
            else -> null
        }
    }

    private fun writes(ins: Instruction, reg: Int) =
        ins.opcode.setsRegister() && (ins as? OneRegisterInstruction)?.registerA == reg

    /** The type a register was last given: a new instance, a cast, or the return of the call whose result moved into it. */
    private fun receiverType(instructions: List<Instruction>, from: Int, reg: Int): String? {
        for (j in from - 1 downTo maxOf(0, from - 400)) {
            val ins = instructions[j]
            if ((ins as? OneRegisterInstruction)?.registerA != reg) continue
            when (ins.opcode) {
                Opcode.NEW_INSTANCE, Opcode.CHECK_CAST -> return dotted(((ins as ReferenceInstruction).reference as TypeReference).type)
                Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT -> {
                    val call = instructions.getOrNull(j - 1) as? ReferenceInstruction
                    return (call?.reference as? MethodReference)?.returnType?.let(::dotted)
                }
                else -> if (ins.opcode.setsRegister()) return null
            }
        }
        return null
    }

    /** What a found view becomes: the type it is cast to, the field it is stored in, or a plain View. */
    private fun resultType(instructions: List<Instruction>, callIndex: Int): String {
        val move = instructions.getOrNull(callIndex + 1) ?: return "android.view.View"
        if (move.opcode != Opcode.MOVE_RESULT_OBJECT) return "android.view.View"
        val reg = (move as OneRegisterInstruction).registerA
        for (j in callIndex + 2 until minOf(instructions.size, callIndex + 7)) {
            val ins = instructions[j]
            when {
                ins.opcode == Opcode.CHECK_CAST && (ins as OneRegisterInstruction).registerA == reg ->
                    return dotted(((ins as ReferenceInstruction).reference as TypeReference).type)
                (ins.opcode == Opcode.IPUT_OBJECT || ins.opcode == Opcode.SPUT_OBJECT) && (ins as OneRegisterInstruction).registerA == reg ->
                    return dotted(((ins as ReferenceInstruction).reference as FieldReference).type)
                argRegisters(ins)?.contains(reg) == true -> return "android.view.View"
                writes(ins, reg) -> return "android.view.View"
            }
        }
        return "android.view.View"
    }

    /** Every android:id a compiled layout sets, on any element. */
    private fun idsSetIn(zip: ZipFile, path: String): Set<Int> = elementsSetIn(zip, path).map { it.id }.toSet()

    /** Every android:id a compiled layout sets, with its element and parent tags, in document order. */
    private fun elementsSetIn(zip: ZipFile, path: String): List<IdUse> {
        val entry = zip.getEntry(path) ?: return emptyList()
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(zip.getInputStream(entry).use { it.readBytes() }))
        val elements = mutableListOf<IdUse>()
        val parents = ArrayDeque<String>()
        while (true) {
            when (parser.next()) {
                AndroidBinXmlParser.EVENT_END_DOCUMENT -> return elements
                AndroidBinXmlParser.EVENT_START_ELEMENT -> {
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeNameResourceId(i) == ANDROID_ID &&
                            parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_REFERENCE
                        ) elements += IdUse(parser.getAttributeIntValue(i), null, null, parser.name, parents.lastOrNull())
                    }
                    parents.addLast(parser.name)
                }
                AndroidBinXmlParser.EVENT_END_ELEMENT -> parents.removeLast()
            }
        }
    }

    private fun descriptor(className: String) = "L" + className.replace('.', '/') + ";"

    private fun hex(id: Int) = "0x%08x".format(id)

    private class SemanticLiterals(
        val methodStrings: Map<String, Set<Int>>,
        val classMethods: Map<String, Set<Int>>,
        val methodStringUses: Map<String, List<IdUse>>,
        val classMethodUses: Map<String, List<IdUse>>,
        /** Layout ids loaded by the methods naming each layout owner's string. */
        val methodStringLayouts: Map<String, Set<Int>> = emptyMap(),
    )

    /**
     * The ids tied to semantics that survive R8: either a string in the same method, or a real
     * method name declared by the class. This is for views whose loaders and layout names are both
     * obfuscated. Only literals that are ids of the resource table are kept, and only for the
     * methods and classes that qualify, so reading the whole APK does not retain the rest of
     * TikTok's constants. The uses say what each id is handed to, for the tell.
     */
    private fun semanticLiteralsLoadedBy(
        apk: File,
        ids: Set<Int>,
        methodStrings: Set<String>,
        classMethods: Set<String>,
        layoutIds: Set<Int> = emptySet(),
        layoutStrings: Set<String> = emptySet(),
    ): SemanticLiterals {
        val byString = methodStrings.associateWith { mutableSetOf<Int>() }.toMutableMap()
        val byMethod = classMethods.associateWith { mutableSetOf<Int>() }.toMutableMap()
        val usesByString = methodStrings.associateWith { mutableListOf<IdUse>() }.toMutableMap()
        val usesByMethod = classMethods.associateWith { mutableListOf<IdUse>() }.toMutableMap()
        val layoutsByString = layoutStrings.associateWith { mutableSetOf<Int>() }.toMutableMap()
        if (methodStrings.isEmpty() && classMethods.isEmpty() && layoutStrings.isEmpty()) {
            return SemanticLiterals(byString, byMethod, usesByString, usesByMethod, layoutsByString)
        }

        fun scan(dexFile: DexFile) {
            for (classDef in dexFile.classes) {
                val methods = classDef.methods.toList()
                val declared = methods.map { it.name }.toSet()
                val classHits = mutableSetOf<Int>()
                val classUses = mutableListOf<IdUse>()
                val declaresWanted = classMethods.any { it in declared }
                for (method in methods) {
                    val instructions = method.implementation?.instructions?.toList() ?: continue
                    val literals = instructions.filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral }
                    if (layoutStrings.isNotEmpty()) {
                        val layoutHits = literals.filter { it in layoutIds }.toSet()
                        if (layoutHits.isNotEmpty()) {
                            val named = instructions.filterIsInstance<ReferenceInstruction>()
                                .mapNotNull { (it.reference as? StringReference)?.string }.toSet()
                            for (value in layoutStrings) if (value in named) layoutsByString.getValue(value) += layoutHits
                        }
                    }
                    val hits = literals.filter { it in ids }.toSet()
                    if (hits.isEmpty()) continue
                    classHits += hits
                    val strings = instructions.filterIsInstance<ReferenceInstruction>()
                        .mapNotNull { (it.reference as? StringReference)?.string }.toSet()
                    val wantedHere = methodStrings.filter { it in strings }
                    if (wantedHere.isEmpty() && !declaresWanted) continue
                    val uses = usesIn(instructions, ids)
                    for (value in wantedHere) {
                        byString.getValue(value) += hits
                        usesByString.getValue(value) += uses
                    }
                    if (declaresWanted) classUses += uses
                }
                for (name in classMethods) {
                    if (name in declared) {
                        byMethod.getValue(name) += classHits
                        usesByMethod.getValue(name) += classUses
                    }
                }
            }
        }
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) scan(container.getEntry(entry)!!.dexFile)
        ZipFile(apk).use { zip ->
            val modules = zip.entries().asSequence().filter { FEATURE_DEX.matches(it.name) }
                .distinctBy { it.name.substringAfterLast('/') }.toList()
            for (module in modules) {
                ZipInputStream(zip.getInputStream(module)).use { inner ->
                    generateSequence { inner.nextEntry }.filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                        .forEach { scan(DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(inner.readBytes()))) }
                }
            }
        }
        return SemanticLiterals(byString, byMethod, usesByString, usesByMethod, layoutsByString)
    }

    /**
     * Every literal each of [owners] loads in any of its methods, and what each id among them is
     * handed to, one dex file at a time: the APK's own and each dynamic feature module's. TikTok
     * ships a module's code as a zip holding its dex, named lib/<abi>/libdex_<module>.so, the same
     * file under every ABI.
     */
    private fun literalsLoadedBy(apk: File, owners: Set<String>, ids: Set<Int>): Pair<Map<String, Set<Int>>, Map<String, List<IdUse>>> {
        val found = mutableMapOf<String, MutableSet<Int>>()
        val uses = mutableMapOf<String, MutableList<IdUse>>()
        if (owners.isEmpty()) return found to uses

        fun scan(dexFile: DexFile) {
            for (classDef in dexFile.classes) {
                if (classDef.type !in owners) continue
                val literals = found.getOrPut(classDef.type) { mutableSetOf() }
                val classUses = uses.getOrPut(classDef.type) { mutableListOf() }
                for (method in classDef.methods) {
                    val instructions = method.implementation?.instructions?.toList() ?: continue
                    for (instruction in instructions) {
                        when (instruction) {
                            is NarrowLiteralInstruction -> literals += instruction.narrowLiteral
                            is ArrayPayload -> instruction.arrayElements.forEach { literals += it.toInt() }
                        }
                    }
                    classUses += usesIn(instructions, ids)
                }
            }
        }
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) scan(container.getEntry(entry)!!.dexFile)
        ZipFile(apk).use { zip ->
            val modules = zip.entries().asSequence().filter { FEATURE_DEX.matches(it.name) }
                .distinctBy { it.name.substringAfterLast('/') }.toList()
            for (module in modules) {
                ZipInputStream(zip.getInputStream(module)).use { inner ->
                    generateSequence { inner.nextEntry }.filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach {
                        scan(DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(inner.readBytes())))
                    }
                }
            }
        }
        return found to uses
    }

    private fun anchors(): List<Anchor> {
        val text = checkNotNull(javaClass.getResourceAsStream("/view-id-anchors.txt")) {
            "view-id-anchors.txt is missing from the test resources"
        }.bufferedReader().readText()
        val anchors = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val fields = line.split('|')
            assertEquals("a line of view-id-anchors.txt needs six fields: $line", 6, fields.size)
            val (source, group, names, packageSuffix, ownerField) = fields
            val tellField = fields[5]
            assertTrue("bad package in: $line", packageSuffix.matches(Regex("[a-z][a-z0-9_]*")))
            val owner = when {
                ownerField == "-" -> null
                ownerField.startsWith(LAYOUT_OWNER) -> {
                    val className = ownerField.removePrefix(LAYOUT_OWNER)
                    assertTrue("bad layout owner in: $line", className.matches(CLASS_NAME))
                    Owner.LayoutClass(className)
                }
                ownerField.startsWith(LAYOUT_METHOD_STRING_OWNER) -> {
                    val value = ownerField.removePrefix(LAYOUT_METHOD_STRING_OWNER)
                    assertTrue("empty layout method string owner in: $line", value.isNotBlank())
                    Owner.LayoutMethodString(value)
                }
                ownerField.startsWith(METHOD_STRING_OWNER) -> {
                    val value = ownerField.removePrefix(METHOD_STRING_OWNER)
                    assertTrue("empty method string owner in: $line", value.isNotBlank())
                    Owner.MethodString(value)
                }
                ownerField.startsWith(CLASS_METHOD_OWNER) -> {
                    val name = ownerField.removePrefix(CLASS_METHOD_OWNER)
                    assertTrue("bad class method owner in: $line", name.matches(METHOD_NAME))
                    Owner.ClassMethod(name)
                }
                else -> {
                    assertTrue("bad owner in: $line", ownerField.matches(CLASS_NAME))
                    Owner.ClassLiteral(ownerField)
                }
            }
            val tell = when {
                tellField == "-" -> null
                tellField.startsWith("at:") -> Tell.At(tellField.removePrefix("at:").toIntOrNull()
                    ?.also { assertTrue("an ordinal counts from one in: $line", it >= 1) }
                    ?: error("bad ordinal in: $line"))
                tellField.startsWith("to:") -> Tell.To(tellField.removePrefix("to:").also { name ->
                    assertTrue("bad class in tell of: $line", name.matches(TYPE_NAME))
                    assertTrue("an obfuscated name in the tell of: $line; use at:n", !name.matches(OBFUSCATED))
                })
                tellField.startsWith("on:") || tellField.startsWith("tag:") -> {
                    val body = tellField.substringAfter(':')
                    val name = body.substringBefore('#')
                    val ordinal = if ('#' in body) body.substringAfter('#').toIntOrNull() ?: error("bad ordinal in: $line") else null
                    assertTrue("bad type in tell of: $line", name.matches(TYPE_NAME))
                    assertTrue("an obfuscated name in the tell of: $line; use at:n", !name.matches(OBFUSCATED))
                    assertTrue("an ordinal counts from one in: $line", ordinal == null || ordinal >= 1)
                    if (tellField.startsWith("on:")) Tell.On(name, ordinal) else Tell.Tag(name, ordinal)
                }
                else -> error("bad tell in: $line (on:<type>[#n], tag:<tag>[#n], to:<class>, at:<n> or -)")
            }
            assertTrue("a tell without an owner in: $line", owner != null || tell == null)
            assertTrue("a layout owner takes tag: or at:, not $tellField, in: $line",
                (owner !is Owner.LayoutClass && owner !is Owner.LayoutMethodString) || tell == null || tell is Tell.Tag || tell is Tell.At)
            assertTrue("a code owner takes on:, to: or at:, not $tellField, in: $line",
                owner is Owner.LayoutClass || owner is Owner.LayoutMethodString || owner == null || tell == null || tell !is Tell.Tag)
            Anchor(
                lookup = "$source|$group|$names",
                names = names.split(','),
                packageSuffix = packageSuffix,
                owner = owner,
                tell = tell,
            )
        }.toList()

        val repeated = anchors.groupBy { it.lookup }.filterValues { it.size > 1 }.keys
        assertTrue("lines listed twice: $repeated", repeated.isEmpty())
        return anchors
    }

    /**
     * `source|group|names` for every id the extension looks up by a name written into its code,
     * traced from each lookup call by [LookupScan]. A lookup the scan cannot trace to its
     * literals fails here, since a name that reaches the phone untraced has no table line and
     * no owner. Lookups under the platform's own package (`android`) are not TikTok's views and
     * take no table line; they are held to [FRAMEWORK_IDS] instead.
     */
    private fun lookups(): Set<String> = lookupScan().lookups.map { it.key }.toSortedSet()

    private fun lookupScan(): LookupScan.Result {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val root = File(repo, EXTENSION)
        assertTrue("could not find the extension sources from ${File(".").absolutePath}", root.isDirectory)
        val result = LookupScan.scan(root)
        assertEquals("lookups whose name or package could not be traced to a literal:\n" +
            result.untraced.joinToString("\n") { "${it.site}: ${it.why}" }, 0, result.untraced.size)
        assertTrue("the scan found too few lookups to mean anything: ${result.lookups.size}", result.lookups.size > 40)
        return result
    }

    @Test
    fun `every lookup's package is the one its table line says`() {
        val byKey = anchors().associateBy { it.lookup }
        val wrong = lookupScan().lookups.mapNotNull { lookup ->
            val line = byKey[lookup.key] ?: return@mapNotNull null
            if (line.packageSuffix == lookup.packageSuffix) null
            else "${lookup.key}: the code resolves it under ${lookup.packageSuffix} (${lookup.site}), the table says ${line.packageSuffix}"
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /**
     * The five panels of the platform's own alert dialog, looked up under `android` to make them
     * transparent. They belong to Android, not to TikTok's build, so they have no owner to hold
     * and no table line; the rule is that nothing else is looked up under that package.
     */
    @Test
    fun `only the dialog panels are looked up under the platform's own package`() {
        val framework = lookupScan().framework
        assertEquals(FRAMEWORK_IDS, framework.map { it.second }.toSortedSet())
        assertTrue("framework ids are looked up from more than one place: $framework",
            framework.map { it.first.substringBefore(':') }.toSet().size == 1)
    }

    private fun syntheticTable(flags: Int, compact: Boolean, layouts: Boolean): ByteBuffer {
        val out = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun chunk(type: Int, headerSize: Int, body: () -> Unit) {
            val start = out.position()
            out.putShort(type.toShort()).putShort(headerSize.toShort()).putInt(0)
            body()
            while (out.position() % 4 != 0) out.put(0)
            out.putInt(start + 4, out.position() - start)
        }
        fun pool(strings: List<String>) = chunk(0x0001, 28) {
            val start = out.position() - 8
            out.putInt(strings.size).putInt(0).putInt(0x100).putInt(28 + 4 * strings.size).putInt(0)
            val encoded = strings.map { it.toByteArray(Charsets.UTF_8) }
            var offset = 0
            for (bytes in encoded) { out.putInt(offset); offset += bytes.size + 3 }
            check(out.position() - start == 28 + 4 * strings.size)
            for (bytes in encoded) { out.put(bytes.size.toByte()).put(bytes.size.toByte()).put(bytes).put(0) }
        }
        // Entries first at index 0, nothing at 1, third at 2, with key strings 0 and 1. An id entry
        // is written as its header alone; a layout entry carries a string value, the index of its
        // path in the table's pool: a full entry as a Res_value after the header, a compact one in
        // the header's second word, with the value's type in the top byte of its flags.
        fun typeChunk(typeId: Int, withValues: Boolean) = chunk(0x0201, 20 + 4) {
            val start = out.position() - 8
            out.put(typeId.toByte()).put(flags.toByte()).putShort(0)
            val count = if (flags and SPARSE != 0) 2 else 3
            out.putInt(count)
            val entriesStart = out.position()
            out.putInt(0)
            out.putInt(4)
            val size = if (withValues && !compact) 16 else 8
            when {
                flags and SPARSE != 0 -> out.putShort(0).putShort(0).putShort(2).putShort((size / 4).toShort())
                flags and OFFSET16 != 0 -> out.putShort(0).putShort(0xffff.toShort()).putShort((size / 4).toShort()).putShort(0)
                else -> out.putInt(0).putInt(-1).putInt(size)
            }
            out.putInt(entriesStart, out.position() - start)
            for (key in 0..1) {
                // A full entry is its size, its flags and a 32 bit key; a compact one puts
                // a 16 bit key where the size goes and flags it.
                when {
                    compact && withValues -> out.putShort(key.toShort()).putShort((0x0008 or (0x03 shl 8)).toShort()).putInt(key)
                    compact -> out.putShort(key.toShort()).putShort(0x0008).putInt(0)
                    withValues -> out.putShort(8).putShort(0).putInt(key).putShort(8).put(0).put(0x03).putInt(key)
                    else -> out.putShort(8).putShort(0).putInt(key)
                }
            }
        }
        chunk(0x0002, 12) {
            out.putInt(1)
            pool(if (layouts) listOf("res/a/first.xml", "res/a/third.xml") else emptyList())
            chunk(0x0200, 288) {
                val start = out.position() - 8
                out.putInt(0x7f)
                "com.example".forEach { out.putChar(it) }
                repeat(128 - "com.example".length) { out.putChar(0.toChar()) }
                val offsets = out.position()
                out.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
                out.putInt(offsets, out.position() - start)
                pool(if (layouts) listOf("attr", "id", "layout") else listOf("attr", "id"))
                out.putInt(offsets + 8, out.position() - start)
                pool(listOf("first", "third"))
                typeChunk(2, withValues = false)
                if (layouts) typeChunk(3, withValues = true)
            }
        }
        out.flip()
        return out
    }

    private companion object {
        const val EXTENSION = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok"
        /** The platform dialog panels SettingsUi makes transparent: Android's, not TikTok's. */
        val FRAMEWORK_IDS = sortedSetOf("buttonPanel", "contentPanel", "customPanel", "parentPanel", "topPanel")
        const val SPARSE = 0x01
        const val OFFSET16 = 0x02

        val CLASS_NAME = Regex("""[a-z][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_$]*)+""")
        val METHOD_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*""")
        /** A type in a tell: a dotted class name, or a bare framework tag such as LinearLayout. */
        val TYPE_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*""")
        /** R8's names, new on every build: `X.05y1`. A tell that carries one is a tell that dies with the build. */
        val OBFUSCATED = Regex("""(?:.*\.)?X\.[0-9A-Za-z_$]{3,6}""")

        /** An owner written `layout:<class>`: the class loads a layout that sets the id. */
        const val LAYOUT_OWNER = "layout:"
        /** An owner written `method-string:<text>`: one method loads the id and the text. */
        const val METHOD_STRING_OWNER = "method-string:"
        /** An owner written `layout-method-string:<text>`: a method naming the text loads a layout that sets the id. */
        const val LAYOUT_METHOD_STRING_OWNER = "layout-method-string:"
        /** An owner written `class-method:<name>`: a class declaring the method loads the id. */
        const val CLASS_METHOD_OWNER = "class-method:"
        const val ANDROID_ID = 0x010100d0

        /** A dynamic feature module's code: `lib/arm64-v8a/libdex_df_search_biz.so`. */
        val FEATURE_DEX = Regex("""lib/[^/]+/libdex_[^/]+\.so""")

        /** The groups that may look up more than one name, as `source|group|names`, and why. */
        val MORE_THAN_ONE_NAME = mapOf<String, String>(
        )
    }
}

/**
 * The `id` entries (or another type's) of an APK's resource table, by package name and then entry name, with every id
 * a name has in the order of the entries. A name normally has one. TikTok 47.0.3 gives ten names
 * two or three, some because a made-up short name like `url` or `tv1` matches a real one. And the
 * file each entry of a file type such as `layout` points to. Only as much of the format as that
 * takes: the table's value strings, the package chunks, their type and key string pools and the
 * type chunks, in the dense, sparse and 16 bit offset layouts, with full or compact entries.
 */
internal object ResourceIds {
    private const val STRING_POOL = 0x0001
    private const val TABLE = 0x0002
    private const val PACKAGE = 0x0200
    private const val TYPE = 0x0201
    private const val SPARSE = 0x01
    private const val OFFSET16 = 0x02
    private const val COMPLEX = 0x0001
    private const val COMPACT = 0x0008
    private const val TYPE_STRING = 0x03

    fun read(apk: File, typeName: String = "id"): Map<String, Map<String, List<Int>>> = read(tableOf(apk), typeName)

    fun read(table: ByteBuffer, typeName: String = "id"): Map<String, Map<String, List<Int>>> {
        check(u16(table, 0) == TABLE) { "not a resource table" }
        val packages = linkedMapOf<String, MutableMap<String, MutableList<Int>>>()
        forEachChunk(table, u16(table, 2), table.limit()) { start, type ->
            if (type == PACKAGE) readIds(table, PackageHeader(table, start), typeName, packages)
        }
        return packages
    }

    /**
     * The files the entries of [typeName] point to, by package name and then id, one path for each
     * configuration that has one. A layout entry holds a string from the table's own pool: the path
     * of its compiled XML inside the APK.
     */
    fun files(apk: File, typeName: String): Map<String, Map<Int, List<String>>> = files(tableOf(apk), typeName)

    fun files(table: ByteBuffer, typeName: String): Map<String, Map<Int, List<String>>> {
        check(u16(table, 0) == TABLE) { "not a resource table" }
        var values: StringPool? = null
        val packages = linkedMapOf<String, MutableMap<Int, MutableList<String>>>()
        forEachChunk(table, u16(table, 2), table.limit()) { start, type ->
            when (type) {
                STRING_POOL -> if (values == null) values = StringPool(table, start)
                PACKAGE -> readFiles(table, PackageHeader(table, start), typeName,
                    checkNotNull(values) { "a package comes before the table's string pool" }, packages)
                else -> Unit
            }
        }
        return packages
    }

    private fun tableOf(apk: File): ByteBuffer {
        val bytes = ZipFile(apk).use { zip ->
            val entry = checkNotNull(zip.getEntry("resources.arsc")) { "${apk.name} has no resources.arsc" }
            zip.getInputStream(entry).use { it.readBytes() }
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private class PackageHeader(table: ByteBuffer, val start: Int) {
        val headerSize = u16(table, start + 2)
        val end = start + table.getInt(start + 4)
        val id = table.getInt(start + 8)
        val name = buildString {
            for (i in 0 until 128) {
                val c = table.getChar(start + 12 + 2 * i)
                if (c == '\u0000') break
                append(c)
            }
        }
        val typeNames = StringPool(table, start + table.getInt(start + 268))
        val keys = StringPool(table, start + table.getInt(start + 276))
        private val typeIdOffset = if (headerSize >= 288) table.getInt(start + 284) else 0

        fun typeId(typeName: String) =
            (0 until typeNames.count).firstOrNull { typeNames[it] == typeName }?.let { it + 1 + typeIdOffset }
    }

    private fun readIds(
        table: ByteBuffer,
        header: PackageHeader,
        typeName: String,
        into: MutableMap<String, MutableMap<String, MutableList<Int>>>,
    ) {
        val idType = header.typeId(typeName) ?: return
        val ids = into.getOrPut(header.name) { linkedMapOf() }
        forEachChunk(table, header.start + header.headerSize, header.end) { chunk, type ->
            if (type != TYPE || u8(table, chunk + 8) != idType) return@forEachChunk
            forEachEntry(table, chunk) { index, entry ->
                val id = (header.id shl 24) or (idType shl 16) or index
                val sameName = ids.getOrPut(header.keys[keyOf(table, entry)]) { mutableListOf() }
                // Another configuration's chunk repeats the entries; an id is listed once.
                if (id !in sameName) sameName += id
            }
        }
    }

    private fun readFiles(
        table: ByteBuffer,
        header: PackageHeader,
        typeName: String,
        values: StringPool,
        into: MutableMap<String, MutableMap<Int, MutableList<String>>>,
    ) {
        val fileType = header.typeId(typeName) ?: return
        val files = into.getOrPut(header.name) { linkedMapOf() }
        forEachChunk(table, header.start + header.headerSize, header.end) { chunk, type ->
            if (type != TYPE || u8(table, chunk + 8) != fileType) return@forEachChunk
            forEachEntry(table, chunk) { index, entry ->
                val flags = u16(table, entry + 2)
                // A compact entry keeps its value's type in the top byte of its flags and the value
                // where a full entry keeps its key; a full one is followed by a Res_value. A complex
                // entry holds a map, not a file.
                val dataType: Int
                val data: Int
                when {
                    flags and COMPACT != 0 -> { dataType = flags ushr 8; data = table.getInt(entry + 4) }
                    flags and COMPLEX != 0 -> return@forEachEntry
                    else -> {
                        val value = entry + u16(table, entry)
                        dataType = u8(table, value + 3)
                        data = table.getInt(value + 4)
                    }
                }
                if (dataType != TYPE_STRING) return@forEachEntry
                val paths = files.getOrPut((header.id shl 24) or (fileType shl 16) or index) { mutableListOf() }
                val path = values[data]
                if (path !in paths) paths += path
            }
        }
    }

    /** A full entry keeps a 32 bit key after its size and flags; a compact one a 16 bit key first. */
    private fun keyOf(table: ByteBuffer, entry: Int) =
        if (u16(table, entry + 2) and COMPACT != 0) u16(table, entry) else table.getInt(entry + 4)

    private fun forEachEntry(table: ByteBuffer, chunk: Int, each: (index: Int, entry: Int) -> Unit) {
        val flags = u8(table, chunk + 9)
        val count = table.getInt(chunk + 12)
        val entries = chunk + table.getInt(chunk + 16)
        val offsets = chunk + u16(table, chunk + 2)
        for (i in 0 until count) {
            val index: Int
            val offset: Int
            when {
                flags and SPARSE != 0 -> {
                    index = u16(table, offsets + 4 * i)
                    offset = u16(table, offsets + 4 * i + 2) * 4
                }
                flags and OFFSET16 != 0 -> {
                    index = i
                    offset = u16(table, offsets + 2 * i).let { if (it == 0xffff) -1 else it * 4 }
                }
                else -> {
                    index = i
                    offset = table.getInt(offsets + 4 * i)
                }
            }
            if (offset == -1) continue
            each(index, entries + offset)
        }
    }

    private fun forEachChunk(table: ByteBuffer, from: Int, until: Int, each: (start: Int, type: Int) -> Unit) {
        var at = from
        while (at + 8 <= until) {
            val size = table.getInt(at + 4)
            check(size >= 8 && at + size <= until) { "a chunk at $at runs past its parent" }
            each(at, u16(table, at))
            at += size
        }
    }

    private class StringPool(private val table: ByteBuffer, start: Int) {
        val count = table.getInt(start + 8)
        private val utf8 = table.getInt(start + 16) and 0x100 != 0
        private val strings = start + table.getInt(start + 20)
        private val offsets = start + u16(table, start + 2)

        operator fun get(index: Int): String {
            check(index in 0 until count) { "string $index of $count" }
            var at = strings + table.getInt(offsets + 4 * index)
            if (utf8) {
                at += if (u8(table, at) and 0x80 != 0) 2 else 1
                var length = u8(table, at++)
                if (length and 0x80 != 0) length = ((length and 0x7f) shl 8) or u8(table, at++)
                val bytes = ByteArray(length)
                table.get(at, bytes)
                return String(bytes, Charsets.UTF_8)
            }
            var length = u16(table, at)
            at += 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7fff) shl 16) or u16(table, at)
                at += 2
            }
            return buildString { for (i in 0 until length) append(table.getChar(at + 2 * i)) }
        }
    }

    private fun u8(table: ByteBuffer, at: Int) = table.get(at).toInt() and 0xff
    private fun u16(table: ByteBuffer, at: Int) = table.getShort(at).toInt() and 0xffff
}
