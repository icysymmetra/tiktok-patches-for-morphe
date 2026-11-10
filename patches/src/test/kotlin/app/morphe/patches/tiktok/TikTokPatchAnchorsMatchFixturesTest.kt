package app.morphe.patches.tiktok

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patches.tiktok.feedfilter.COMMENT_TOP_BAR_BRIDGE_BASE
import app.morphe.patches.tiktok.feedfilter.TAKO_COMMENT_TOP_BAR_BRIDGE
import app.morphe.patches.tiktok.feedfilter.TAKO_COMMENT_TOP_BAR_SERVICE
import app.morphe.patches.tiktok.feedfilter.COLD_START_STORES
import app.morphe.patches.tiktok.feedfilter.coldStartCall
import app.morphe.patches.tiktok.feedfilter.coldStartOrchestrators
import app.morphe.patches.tiktok.feedfilter.countColdStartFeedItemListStores
import app.morphe.patches.tiktok.feedfilter.isCommentTopBarCanShow
import app.morphe.patches.tiktok.feedfilter.isTakoSearchEntranceInflater
import app.morphe.patches.tiktok.feedfilter.takoSearchEntranceVariants
import app.morphe.patches.tiktok.interaction.copyids.BASE_SHARE_PACKAGE
import app.morphe.patches.tiktok.interaction.copyids.BioBindFingerprint
import app.morphe.patches.tiktok.interaction.copyids.SHARE_APPS_MARKER
import app.morphe.patches.tiktok.interaction.copyids.SHARE_PANEL_MARKER
import app.morphe.patches.tiktok.interaction.copyids.SIGNATURE_COMPONENT
import app.morphe.patches.tiktok.interaction.copyids.bioTextField
import app.morphe.patches.tiktok.interaction.copyids.resolveShareAppsPanel
import app.morphe.patches.tiktok.interaction.copyids.sharePackageField
import app.morphe.patches.tiktok.interaction.downloads.drawsCommentImageWatermark
import app.morphe.patches.tiktok.interaction.sharesheet.ShareSnapshotFingerprint
import app.morphe.patches.tiktok.interaction.sharesheet.resolveSharePanel
import app.morphe.patches.tiktok.interaction.searchsuggestions.isSearchRewardsAccessor
import app.morphe.patches.tiktok.interaction.speed.playerManagerSpeedBoundary
import app.morphe.patches.tiktok.misc.settings.isSettingsComposeRowsMethod
import app.morphe.patches.tiktok.misc.optimizer.backendBuilderCall
import app.morphe.patches.tiktok.misc.optimizer.framePreparerGateIndex
import app.morphe.patches.tiktok.interaction.exactcounts.COMPACT_COUNT_FORMATTERS
import app.morphe.patches.tiktok.interaction.exactcounts.COUNT_FORMATTERS
import app.morphe.patches.tiktok.interaction.exactcounts.CompactCountFormatterFingerprint
import app.morphe.patches.tiktok.interaction.exactcounts.CountFormatterFingerprint
import app.morphe.patches.tiktok.interaction.exactcounts.isCountFormatter
import app.morphe.patches.tiktok.interaction.engagement.ProfileGridBindFingerprint
import app.morphe.patches.tiktok.interaction.engagement.ProfileGridHolderBindFingerprint
import app.morphe.patches.tiktok.interaction.engagement.gridCountSite
import app.morphe.patches.tiktok.interaction.engagement.holderCountSites
import app.morphe.patches.tiktok.interaction.feedtoolbar.SidebarIconViewFingerprint
import app.morphe.patches.tiktok.interaction.feedtoolbar.isToolbarEnabledCheck
import app.morphe.patches.tiktok.interaction.favorites.isDialog
import app.morphe.patches.tiktok.interaction.favorites.isQuickFavoritePanelShow
import app.morphe.patches.tiktok.interaction.favorites.quickFavoritePanelField
import app.morphe.patches.tiktok.profile.BASE_UI_COMPONENT
import app.morphe.patches.tiktok.profile.HEADER_TEXT_ITEM
import app.morphe.patches.tiktok.profile.PROFILE_COMMON_INFO
import app.morphe.patches.tiktok.profile.ProfileHeaderTextBindFingerprint
import app.morphe.patches.tiktok.profile.RELATION_USER_CELL
import app.morphe.patches.tiktok.profile.REUSED_SLOT_ASSEM
import app.morphe.patches.tiktok.profile.RelationCellBindFingerprint
import app.morphe.patches.tiktok.profile.commonInfoGetter
import app.morphe.patches.tiktok.profile.itemViewField
import app.morphe.patches.tiktok.misc.commenttools.COMMENT_SCENE_SERVICE
import app.morphe.patches.tiktok.misc.commenttools.COMMENT_SCENE_SERVICE_IMPL
import app.morphe.patches.tiktok.misc.commenttools.COMMENT_TOP_RELATED_SEARCH
import app.morphe.patches.tiktok.misc.commenttools.commentSuggestionGate
import app.morphe.patches.tiktok.misc.commenttools.isCommentSearchHeaderFactory
import app.morphe.patches.tiktok.misc.commenttools.resolveCommentSearchSuggestions
import app.morphe.patches.tiktok.misc.commenttools.resolveCommentSuggestionGate
import app.morphe.patches.tiktok.misc.commenttools.compactCommentHeaderComponents
import app.morphe.patches.tiktok.misc.commenttools.isCompactCommentHeaderBind
import app.morphe.patches.tiktok.misc.commenttools.resolveCompactCommentHeader
import app.morphe.patches.tiktok.misc.commenttools.resolveLikeTouchListener
import app.morphe.patches.tiktok.interaction.videooverlays.*
import app.morphe.patches.tiktok.interaction.quality.ForceHdrOffFingerprint
import app.morphe.patches.tiktok.interaction.quality.SimVideoSetBitRateFingerprint
import app.morphe.patches.tiktok.interaction.quality.SimVideoUrlModelSetBitRateFingerprint
import app.morphe.patches.tiktok.interaction.quality.SIM_BIT_RATE
import app.morphe.patches.tiktok.interaction.quality.requireGearCodecField
import app.morphe.patches.tiktok.interaction.quality.requirePlayerGearSetter
import app.morphe.patches.tiktok.misc.comment.BIO_EDITOR_CLASSES
import app.morphe.patches.tiktok.misc.comment.CommentInputLimitFingerprint
import app.morphe.patches.tiktok.misc.comment.REPOST_NOTE_INPUTS
import app.morphe.patches.tiktok.misc.comment.bioLimitConstants
import app.morphe.patches.tiktok.misc.comment.lengthFilterConstructions
import app.morphe.patches.tiktok.misc.comment.repostNoteInputFingerprints
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
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
 * Instruction-level anchors the patches use on TikTok remain unique on every
 * retained universal APK. The assertions describe behavior the patches consume rather than R8
 * names or strings that can move into adjacent methods.
 */
class TikTokPatchAnchorsMatchFixturesTest {
    /**
     * Issue #21. The regional Report button's gate and the search rewards accessor are each one
     * method on every build, static and without parameters, so every register is a local the
     * entry guard can use.
     */
    @Test
    fun `regional Report gate and search rewards accessor stay unique on every retained fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val gates = mutableListOf<Method>()
            val accessors = mutableListOf<Method>()
            container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { it.methods.asSequence() }
                .forEach {
                    if (isFeedReportButtonGate(it)) gates += it
                    if (isSearchRewardsAccessor(it)) accessors += it
                }
            assertEquals("${apk.name}: Report button gate ${gates.map { it.definingClass }}", 1, gates.size)
            assertEquals("${apk.name}: search rewards accessor ${accessors.map { it.definingClass }}", 1, accessors.size)
            for (method in gates + accessors) {
                assertTrue("${apk.name}: ${method.definingClass} has no local for the guard",
                    method.implementation!!.registerCount >= 1)
            }
            // The gate's own false is the ordinary answer, so the guard's false is one it handles.
            assertTrue("${apk.name}: the Report gate never answers false itself",
                gates.single().implementation!!.instructions.any {
                    it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0
                })
        }
    }

    /**
     * Issue #23. The Save media button finds the sticker sheet's actions by the sheet's shape:
     * exactly one set of two or more fields sharing a TextView-descended type. The type itself is
     * renamed on every build (0GSy, 1AWY, 1D84, 0CNa, 02Lg), which is what broke the old lookup.
     */
    @Test
    fun `sticker sheet keeps one group of like typed action fields on every retained fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val byType = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.associateBy { it.type }
            fun descendsFrom(type: String, ancestor: String): Boolean {
                var current: String? = type
                repeat(8) {
                    if (current == ancestor) return true
                    current = byType[current]?.superclass ?: return false
                }
                return false
            }
            val rows = byType.values.filter { row ->
                row.superclass == "Landroid/widget/LinearLayout;" &&
                    row.fields.any { it.type == "Lcom/bytedance/lighten/loader/SmartImageView;" } &&
                    row.fields.any { it.type == "Lcom/bytedance/tux/input/TuxTextView;" } &&
                    row.methods.any { method ->
                        val parameters = method.parameterTypes.map(CharSequence::toString)
                        method.returnType == "V" && parameters.size == 4 && parameters[1] == "Z" &&
                            parameters[2] == "Ljava/lang/String;" && parameters[3] == "Ljava/util/Map;" &&
                            !AccessFlags.STATIC.isSet(method.accessFlags) &&
                            byType[parameters[0]]?.fields?.any {
                                it.type == "Lcom/ss/android/ugc/aweme/base/model/UrlModel;"
                            } == true
                    }
            }
            assertEquals("${apk.name}: sticker preview row", 1, rows.size)
            val groups = rows.single().fields
                .filter { !AccessFlags.STATIC.isSet(it.accessFlags) && descendsFrom(it.type, "Landroid/widget/TextView;") }
                .groupBy { it.type }.filterValues { it.size >= 2 }
            assertEquals("${apk.name}: like typed action fields ${groups.keys}", 1, groups.size)
        }
    }

    /**
     * Issue #22. Each search-page Tako entrance has exactly one ViewStub inflater with a local
     * for the guard, and TikTok itself answers null from it, which is the answer the guard gives.
     */
    @Test
    fun `both search page Tako entrances keep one nullable inflater on every retained fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val inflaters = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type in takoSearchEntranceVariants }
                .flatMap { it.methods.asSequence() }
                .filter(::isTakoSearchEntranceInflater).toList()
            assertEquals(
                "${apk.name}: one inflater per entrance",
                takoSearchEntranceVariants.toSet(),
                inflaters.map { it.definingClass }.toSet(),
            )
            assertEquals("${apk.name}: no second inflater", 2, inflaters.size)
            for (inflater in inflaters) {
                val body = inflater.implementation!!
                assertTrue("${apk.name}: ${inflater.definingClass} has no local for the guard",
                    body.registerCount - 2 >= 1)
                val instructions = body.instructions.toList()
                assertTrue("${apk.name}: ${inflater.definingClass} never answers null itself",
                    instructions.zipWithNext().any { (first, second) ->
                        first.opcode == Opcode.CONST_4 && (first as NarrowLiteralInstruction).narrowLiteral == 0 &&
                            second.opcode == Opcode.RETURN_OBJECT
                    })
            }
        }
    }

    /**
     * The Tako bar inside the comments sheet. Both services that can serve it keep one canShow
     * of the guarded shape on every build, with a local for the guard's answer; the Tako bridge
     * still sits on the base whose canShow is guarded, and the Tako service already answers
     * false itself, so the guard's false is one the sheet's resolver handles.
     */
    @Test
    fun `both comment sheet Tako top bar gates stay unique and guardable on every retained fixture`() {
        // The extension answers the bridge base's guard by class name, so the name it compares
        // has to be the one the fingerprint pins; a rename of either alone would apply cleanly
        // and leave the bar standing.
        val extension = File("../extensions/tiktok/src/main/java/app/morphe/extension/tiktok/feedfilter/TakoAiFilter.java")
            .takeIf { it.isFile } ?: File("extensions/tiktok/src/main/java/app/morphe/extension/tiktok/feedfilter/TakoAiFilter.java")
        assertTrue("could not find TakoAiFilter.java from ${File(".").absolutePath}", extension.isFile)
        val compared = Regex("COMMENT_TOP_BAR_BRIDGE\\s*=\\s*\"([A-Za-z0-9_]+)\"").find(extension.readText())?.groupValues?.get(1)
        assertEquals("the class name the extension compares is the bridge the patch pins",
            TAKO_COMMENT_TOP_BAR_BRIDGE.substringAfterLast('/').removeSuffix(";"), compared)

        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val wanted = setOf(TAKO_COMMENT_TOP_BAR_SERVICE, COMMENT_TOP_BAR_BRIDGE_BASE, TAKO_COMMENT_TOP_BAR_BRIDGE)
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type in wanted }.associateBy { it.type }
            assertEquals("${apk.name}: named Tako comment services", wanted, classes.keys)
            val bridge = classes.getValue(TAKO_COMMENT_TOP_BAR_BRIDGE)
            assertEquals("${apk.name}: the Tako bridge extends the guarded base",
                COMMENT_TOP_BAR_BRIDGE_BASE, bridge.superclass)
            assertEquals("${apk.name}: the bridge has no canShow of its own", 0,
                bridge.methods.count { it.name == "canShow" })
            assertEquals("${apk.name}: the bridge names its Tako service", 1,
                bridge.methods.count { it.name == "bridgeTopBar" && it.parameterTypes.isEmpty() })
            for (type in listOf(TAKO_COMMENT_TOP_BAR_SERVICE, COMMENT_TOP_BAR_BRIDGE_BASE)) {
                val gates = classes.getValue(type).methods.filter(::isCommentTopBarCanShow)
                assertEquals("${apk.name}: $type canShow", 1, gates.size)
                val body = gates.single().implementation!!
                // this plus five parameters; the guard writes its answer to v0.
                assertTrue("${apk.name}: $type canShow has no local for the guard",
                    body.registerCount - 6 >= 1)
            }
            val takoGate = classes.getValue(TAKO_COMMENT_TOP_BAR_SERVICE).methods.single(::isCommentTopBarCanShow)
            assertTrue("${apk.name}: the Tako service never answers false itself",
                takoGate.implementation!!.instructions.any {
                    it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0
                })
        }
    }

    /**
     * Discussion #115. Both bottom-right Tako entrances keep one bind of the guarded shape and
     * an onViewCreated that calls the base first, each with a local for the guard's answer, so
     * the bind can return early and the view can be hidden before the coroutine starts.
     */
    @Test
    fun `both bottom right Tako entrances keep one bind and a guardable onViewCreated on every retained fixture`() {
        val base = "Lcom/ss/android/ugc/aweme/tako/otherpage/feed/mainentrance/ui/AbsTakoRightBottomEntrance;"
        val variants = listOf("TakoRightBottomFixedEntrance", "TakoRightBottomInCellEntrance")
            .map { "Lcom/ss/android/ugc/aweme/tako/otherpage/feed/mainentrance/ui/$it;" }
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type in variants }.associateBy { it.type }
            assertEquals("${apk.name}: bottom right Tako entrances", variants.toSet(), classes.keys)
            for ((type, entrance) in classes) {
                assertEquals("${apk.name}: $type extends the shared entrance", base, entrance.superclass)
                val binds = entrance.methods.filter {
                    it.returnType == "V" && it.implementation != null &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;")
                }
                assertEquals("${apk.name}: $type bind", 1, binds.size)
                assertTrue("${apk.name}: $type bind has no local for the guard",
                    binds.single().implementation!!.registerCount - 2 >= 1)
                val created = entrance.methods.single {
                    it.name == "onViewCreated" && it.returnType == "V" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
                }
                val body = created.implementation!!
                assertTrue("${apk.name}: $type onViewCreated has no local for the guard", body.registerCount - 2 >= 1)
                val instructions = body.instructions.toList()
                val superIndex = instructions.indexOfFirst {
                    it.opcode == Opcode.INVOKE_SUPER &&
                        ((it as ReferenceInstruction).reference as MethodReference).name == "onViewCreated"
                }
                assertEquals("${apk.name}: $type onViewCreated calls the base first", 0, superIndex)
                assertTrue("${apk.name}: $type onViewCreated ends at the base call",
                    superIndex + 1 < instructions.size)
            }
        }
    }

    /**
     * Issue #142. The quick favorites popup is shown through TikTok's call interceptor, so its
     * show carries the abstract base's name and "show", and returns normally, which is where the
     * extension is handed the popup. 47.4.3 has one show, a lambda keeping the popup it builds
     * in a static Dialog field. Older builds had a horizontal and a vertical popup, each showing
     * itself.
     */
    @Test
    fun `quick favorites popup show and its popup resolve on every retained fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val byType = classes.associateBy { it.type }
            val shows = classes.flatMap { classDef ->
                classDef.methods.filter { it.isQuickFavoritePanelShow() }.map { classDef to it }
            }
            assertEquals("${apk.name}: quick favorites shows ${shows.map { it.first.type }}", 1, shows.size)
            val (owner, show) = shows.single()
            assertFalse("${apk.name}: the show is the lambda, not the popup", isDialog(owner.type) { byType[it] })
            val field = show.quickFavoritePanelField { byType[it] }
            assertNotNull("${apk.name}: ${owner.type}->${show.name} keeps one popup", field)
            assertTrue("${apk.name}: the lambda stores the popup it builds",
                show.implementation!!.instructions.any {
                    it.opcode == Opcode.SPUT_OBJECT &&
                        ((it as ReferenceInstruction).reference as? FieldReference)?.toString() == field!!.toString()
                })
            assertTrue("${apk.name}: ${owner.type}->${show.name} never returns",
                show.implementation!!.instructions.any { it.opcode == Opcode.RETURN_VOID })
        }
    }

    @Test
    fun `block skip native pager methods survive every retained fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val pager = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .single { it.type == "Lcom/ss/android/ugc/aweme/common/widget/VerticalViewPager;" }
            app.morphe.patches.tiktok.interaction.blockauthor.validateBlockPager(pager.methods)
        }
    }
    @Test
    fun `bottom search banner model and native component key survive every retained fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val wanted = setOf(
                "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
                "Lcom/ss/android/ugc/aweme/feed/model/banner/BannerCommonStruct;",
                "Lcom/ss/android/ugc/aweme/feed/model/banner/BannerCommonKey;",
                "Lcom/ss/android/ugc/feed/platform/cell/interact/bottom/bar/FeedSearchBottomBarPreload;",
            )
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type in wanted }.associateBy { it.type }
            assertEquals("${apk.name}: named banner contracts", wanted, classes.keys)
            val model = classes.getValue("Lcom/ss/android/ugc/aweme/feed/model/Aweme;")
            assertEquals(1, model.methods.count { it.name == "getBanners" && it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/util/List;" && !AccessFlags.STATIC.isSet(it.accessFlags) })
            assertEquals(1, model.methods.count { it.name == "setBanners" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;") && it.returnType == "V" })
            val banner = classes.getValue("Lcom/ss/android/ugc/aweme/feed/model/banner/BannerCommonStruct;")
            assertTrue(banner.fields.any { it.name == "bannerKey" &&
                it.type == "Lcom/ss/android/ugc/aweme/feed/model/banner/BannerCommonKey;" && AccessFlags.PUBLIC.isSet(it.accessFlags) })
            val key = classes.getValue("Lcom/ss/android/ugc/aweme/feed/model/banner/BannerCommonKey;")
            assertTrue(key.fields.any { it.name == "componentKey" && it.type == "Ljava/lang/String;" &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) })
            val preload = classes.getValue("Lcom/ss/android/ugc/feed/platform/cell/interact/bottom/bar/FeedSearchBottomBarPreload;")
            assertTrue(preload.methods.any { "bottom_banner_search_rs" in it.stringConstants() })
        }
    }

    @Test
    fun `fullscreen entry and both location card contracts survive every retained fixture`() {
        val apks=fixtures()
        for(apk in apks) {
            val container=Fixtures.dexContainer(apk,Opcodes.getDefault())
            val classes=container.dexEntryNames.flatMap{container.getEntry(it)!!.dexFile.classes}
            val byType=classes.associateBy{it.type}
            val listFactories = classes.asSequence().flatMap { it.methods.asSequence() }
                .filter(::isLocationBadgeListFactory).toList()
            assertEquals("${apk.name}: location-only presentation list boundary", 1, listFactories.size)
            val factory = MutableMethod(listFactories.single())
            val factoryBefore = factory.implementation!!.instructions.toList()
            val writeFactory = factory.resolveLocationBadgeList()
            assertEquals(factoryBefore, factory.implementation!!.instructions.toList())
            writeFactory()
            assertEquals(factoryBefore.size + 2, factory.implementation!!.instructions.size)
            val model = byType.getValue("Lcom/ss/android/ugc/aweme/feed/model/AnchorCommonStruct;")
            assertTrue(model.methods.any { it.name == "getComponentKey" && it.parameterTypes.isEmpty() &&
                it.returnType == "Ljava/lang/String;" })
            val preload = byType.getValue("Lcom/ss/android/ugc/aweme/poi/preload/PoiAnchorPreloadTask;")
            assertTrue(preload.methods.any { method -> "anchor_poi" in method.stringConstants() })
            val full=byType.getValue(FULLSCREEN_COMPONENT).methods.filter(::isFullscreenBind)
            assertEquals("${apk.name}: exact Full screen entry",1,full.size)
            val cards=locationCardMarkers.map{marker->
                // Don't materialize every method in a large APK just to retain one binder.
                val matches=classes.asSequence().flatMap{it.methods.asSequence()}
                    .filter{isLocationCardBind(it,marker)}.toList()
                assertEquals("${apk.name}: $marker",1,matches.size)
                marker to MutableMethod(matches.single())
            }
            val f=MutableMethod(full.single())
            val methods=listOf(f)+cards.map{it.second}
            val before=methods.map{it.implementation!!.instructions.toList()}
            val write=resolveFeedOverlayControls(f,cards){byType[it]}
            methods.forEachIndexed{i,m->assertEquals(before[i],m.implementation!!.instructions.toList())}
            write()
            methods.forEachIndexed{i,m->assertEquals(before[i],m.implementation!!.instructions.drop(1))}
        }
    }

    @Test
    fun `native comment like installer is unique and leaves other actions intact on every fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
            val owners = classes.filter { type -> type.methods.any { method ->
                method.implementation?.instructions?.any {
                    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "diggView bind: comment id "
                } == true
            } }
            assertEquals("${apk.name}: native comment like view", 1, owners.size)
            val installers = owners.single().methods.filter { method ->
                method.returnType == "V" && method.parameterTypes.isEmpty() &&
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "setOnTouchListener"
                    } == true
            }
            assertEquals("${apk.name}: listener installer", 1, installers.size)
            val byType = classes.associateBy { it.type }
            val method = MutableMethod(installers.single())
            val before = method.implementation!!.instructions.toList()
            val write = method.resolveLikeTouchListener { byType[it] }
            assertEquals(before, method.implementation!!.instructions.toList())
            write()
            val after = method.implementation!!.instructions.toList()
            assertEquals(before.size, after.size)
            assertEquals("${apk.name}: only one native call changes", 1,
                before.indices.count { before[it] !== after[it] })
            assertEquals(1, after.count {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass ==
                    "Lapp/morphe/extension/tiktok/comment/CommentLikeTouchTarget;"
            })
        }
    }

    @Test
    fun `all three compact comment roots keep their named lifecycle contract on every fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val components = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type in compactCommentHeaderComponents }
            }
            assertEquals("${apk.name}: three dedicated header components", 3, components.size)
            for (component in components) {
                assertEquals("${apk.name}: ${component.type} is a slot, not the sheet root",
                    "Lcom/bytedance/assem/arch/view/UISlotAssem;", component.superclass)
                val binds = component.methods.filter(::isCompactCommentHeaderBind)
                assertEquals("${apk.name}: ${component.type} lifecycle", 1, binds.size)
                val method = MutableMethod(binds.single())
                val original = method.implementation!!.instructions.toList()
                method.resolveCompactCommentHeader()()
                assertEquals(original, method.implementation!!.instructions.drop(1).toList())
            }
        }
    }

    @Test
    fun `comment suggestion banner factory is unique and guardable on every fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val matches = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter {
                    it.type == COMMENT_SCENE_SERVICE_IMPL
                }.flatMap { it.methods.filter(::isCommentSearchHeaderFactory) }
            }
            assertEquals("${apk.name}: optional comment_top banner factory", 1, matches.size)
            val method = MutableMethod(matches.single())
            val original = method.implementation!!.instructions.toList()
            assertTrue("native absent-banner returns remain supported",
                original.count { it.opcode == Opcode.RETURN_OBJECT } >= 2)
            val write = method.resolveCommentSearchSuggestions()
            assertEquals("preflight must not edit", original, method.implementation!!.instructions.toList())
            write()
            assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.CONST_4, Opcode.RETURN_OBJECT, Opcode.NOP),
                method.implementation!!.instructions.take(6).map { it.opcode })
            assertEquals(original, method.implementation!!.instructions.drop(6).toList())
        }
    }

    @Test
    fun `comment suggestion gate closes every header check before a slot is claimed on every fixture`() {
        val apks = fixtures()
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
                .associateBy { it.type }
            val gate = commentSuggestionGate { classes[it] }
            assertEquals(COMMENT_SCENE_SERVICE_IMPL, gate.definingClass)
            fun Method.calls() = implementation?.instructions?.mapNotNull {
                (it as? ReferenceInstruction)?.reference as? MethodReference
            }.orEmpty()
            fun isService(type: String) = type == COMMENT_SCENE_SERVICE ||
                classes[type]?.interfaces?.contains(COMMENT_SCENE_SERVICE) == true
            fun MethodReference.isGate() = name == gate.name && parameterTypes.isEmpty() && returnType == "Z"
            // 47.4.3 asks through an obfuscated facade whose method of the gate's name forwards to the service.
            val facades = classes.values.filter { owner ->
                !isService(owner.type) && owner.methods.any { method ->
                    method.name == gate.name && method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                        method.calls().any { it.isGate() && isService(it.definingClass) }
                }
            }.map { it.type }.toSet()
            fun isAsked(type: String) = isService(type) || type in facades
            fun asksGate(method: Method) = method.calls().any { it.isGate() && isAsked(it.definingClass) }
            fun forwards(method: Method) = method.name == gate.name && method.parameterTypes.isEmpty() &&
                isAsked(method.definingClass)
            fun readsSuggestions(method: Method) = method.calls().any { it.name == "getCommentSuggestWordList" }

            // #161: the older header anchor's provider builds its banner through the guarded
            // factory, and claims the header slot after its check says yes. That check must ask
            // the gate, itself or through the service's header check that asks it first, so a
            // closed gate leaves no slot claimed.
            val impl = classes.getValue(COMMENT_SCENE_SERVICE_IMPL)
            val factory = impl.methods.single(::isCommentSearchHeaderFactory)
            val headerChecks = impl.methods.filter { method ->
                method.returnType == "Z" && !forwards(method) &&
                    method.calls().firstOrNull { it.returnType == "Z" }?.isGate() == true
            }.map { it.name }.toSet()
            assertTrue("${apk.name}: the service has a header check that asks the gate first", headerChecks.isNotEmpty())
            val providers = classes.values.filter { owner ->
                !isAsked(owner.type) && owner.methods.any { method ->
                    method.returnType == "Landroid/view/View;" &&
                        method.calls().any { it.name == factory.name && isAsked(it.definingClass) }
                }
            }
            assertEquals("${apk.name}: one header anchor provider builds the banner", 1, providers.size)
            val checks = providers.single().methods.filter { method ->
                method.returnType == "Z" && (asksGate(method) ||
                    method.calls().any { it.name in headerChecks && it.returnType == "Z" && isAsked(it.definingClass) })
            }
            assertTrue("${apk.name}: the provider's check asks the gate", checks.isNotEmpty())

            // Every asker outside the service's own forwarding reads the comment suggestions, so
            // closing it reaches nothing else.
            val askers = classes.values.flatMap { owner -> owner.methods.filter(::asksGate) }.filterNot(::forwards)
            assertTrue("${apk.name}: top bar and anchor checks, $askers", askers.size >= 2)
            askers.forEach { assertTrue("${apk.name}: ${it.definingClass}->${it.name}", readsSuggestions(it)) }
            assertTrue(classes.getValue(COMMENT_TOP_RELATED_SEARCH).methods.single { it.name == "canShow" }
                .let(::asksGate))

            val method = MutableMethod(gate)
            val original = method.implementation!!.instructions.toList()
            val write = method.resolveCommentSuggestionGate()
            assertEquals("preflight must not edit", original, method.implementation!!.instructions.toList())
            write()
            val after = method.implementation!!.instructions.toList()
            assertEquals(listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                Opcode.CONST_4, Opcode.RETURN, Opcode.NOP), after.take(6).map { it.opcode })
            assertEquals("Lapp/morphe/extension/tiktok/comment/CommentTools;->closeCommentSuggestionGate()Z",
                (after[0] as ReferenceInstruction).reference.toString())
            assertEquals(1, (after[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(original, after.drop(6))
        }
    }

    @Test
    fun `location affiliate disclosure uses the same named contract on every fixture`() {
        val apks = fixtures()
        val model = "Lcom/ss/android/ugc/aweme/feed/model/"
        val expectedClasses = listOf("Aweme", "ContentModel", "StandardBusinessModel", "LocalAllianceInfo")
        for (apk in apks) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val models = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type in expectedClasses.map { "$model$it;" } }
            }.associateBy { it.type }
            assertEquals("${apk.name}: complete model chain", 4, models.size)
            val aweme = models.getValue("${model}Aweme;")
            assertTrue(aweme.methods.any {
                it.name == "getContentModel" && it.parameterTypes.isEmpty() &&
                    it.returnType == "${model}ContentModel;"
            })
            assertTrue(models.getValue("${model}ContentModel;").fields.any {
                it.name == "standardBusinessModel" && it.type == "${model}StandardBusinessModel;"
            })
            assertTrue(models.getValue("${model}StandardBusinessModel;").methods.any {
                it.name == "getLocalAllianceInfo" && it.parameterTypes.isEmpty() &&
                    it.returnType == "${model}LocalAllianceInfo;"
            })
            val show = models.getValue("${model}LocalAllianceInfo;").methods.single {
                it.name == "showBottomLabel" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            }
            val instructions = show.implementation!!.instructions.toList()
            assertEquals(
                "${apk.name}: label type is 1 and label text is nonempty",
                listOf(Opcode.IGET_OBJECT, Opcode.CONST_4, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL,
                    Opcode.MOVE_RESULT, Opcode.CONST_4, Opcode.IF_NE, Opcode.IGET_OBJECT,
                    Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                    Opcode.CONST_4, Opcode.RETURN),
                instructions.map { it.opcode },
            )
            assertEquals(listOf(0, 1, 1), instructions.filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral })
            assertEquals(listOf(
                "${model}LocalAllianceInfo;->allianceItemLabelType:Ljava/lang/Integer;",
                "Ljava/lang/Integer;->intValue()I",
                "${model}LocalAllianceInfo;->allianceItemLabelText:Ljava/lang/String;",
                "Ljava/lang/String;->length()I",
            ), instructions.filterIsInstance<ReferenceInstruction>().map { it.reference.toString() })
        }
    }

    /**
     * AdsFilter.isPseudoAd reads the commerce struct by name. TikTok's own AwemeExtKt.isPseudoAd
     * has to read exactly those three members on every build, or the two tests have drifted apart.
     * Its shape is pinned too: each read returns false early, so only the flag and the ad data
     * together make a pseudo ad, which is how AdsFilter combines them.
     */
    @Test
    fun `pseudo ad check reads the members TikTok's own isPseudoAd reads on every fixture`() {
        val model = "Lcom/ss/android/ugc/aweme/feed/model/"
        val commerce = "Lcom/ss/android/ugc/aweme/commerce/AwemeCommerceStruct;"
        for (apk in fixtures()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val check = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .single { it.type == "${model}AwemeExtKt;" }
                .methods.single { it.name == "isPseudoAd" && it.parameterTypes == listOf("${model}Aweme;") }
            assertEquals("${apk.name}: TikTok's pseudo ad test", listOf(
                "${model}Aweme;->getCommerceVideoAuthInfo()$commerce",
                "$commerce->isPseudoAd()Z",
                "$commerce->getPseudoAdData()${model}AwemeRawAd;",
            ), check.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().map { it.reference.toString() })
            assertEquals("${apk.name}: TikTok's pseudo ad test needs both signals", listOf(
                "invoke-virtual", "move-result-object", "const/4", "if-nez", "return",
                "invoke-virtual", "move-result", "if-nez", "return",
                "invoke-virtual", "move-result-object", "if-nez", "return",
                "const/4", "return",
            ), check.implementation!!.instructions.map { it.opcode.name })
        }
    }

    /**
     * Drop the animated image cache turns the frame preparer off and leaves TikTok's own caching
     * strategy alone (#100, #130). On every declared build the factory has to be one method that
     * calls one backend builder whose preparer gate nothing jumps to directly.
     */
    @Test
    fun `animated image cache factory keeps one shape on every declared build`() {
        for (apk in Fixtures.declared()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val factories = classes.flatMap { cls ->
                cls.methods.filter { app.morphe.patches.tiktok.misc.optimizer.AnimatedDrawableFactoryFingerprint.takes(it, cls) }
            }
            assertEquals("${apk.name}: animated drawable factories", 1, factories.size)
            val factory = factories.single()
            val call = requireNotNull(factory.backendBuilderCall()) { "${apk.name}: backend builder call" }
            val builder = classes.single { it.type == factory.definingClass }.methods.single {
                it.name == call.name && it.parameterTypes.map(CharSequence::toString) == call.parameterTypes.map(CharSequence::toString) && it.returnType == call.returnType
            }
            assertTrue("${apk.name}: frame preparer gate", builder.framePreparerGateIndex() != null)
        }
    }

    /**
     * Show exact counts hooks every count formatter. Each declared build has the main one and up
     * to three copies, all static, a long in and a string out, with a spare register for the hook,
     * and four compact ones of the same shape, which print the feed rail's counts. The two sets
     * never overlap.
     */
    @Test
    fun `count formatters for exact counts stay within the reviewed number on every declared build`() {
        for (apk in Fixtures.declared()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val formatters = classes.flatMap { cls -> cls.methods.filter { CountFormatterFingerprint.takes(it, cls) } }
            val compact = classes.flatMap { cls -> cls.methods.filter { CompactCountFormatterFingerprint.takes(it, cls) } }
            assertTrue("${apk.name}: ${formatters.size} count formatters", formatters.size in COUNT_FORMATTERS)
            assertTrue("${apk.name}: ${compact.size} compact count formatters", compact.size in COMPACT_COUNT_FORMATTERS)
            assertTrue("${apk.name}: a formatter without the hook's register",
                (formatters + compact).all { it.isCountFormatter() })
            assertTrue("${apk.name}: a formatter in both sets", formatters.none { it in compact })
        }
    }

    /**
     * Show engagement rate hooks the profile grid adapter's bind right after the cell's view count
     * is formatted, and hands the extension the text and the cell's Aweme. One bind per declared
     * build, the item in the register its statistics were asked of, and the text the one the
     * cell's next setText writes (v1 and v12 on all three builds).
     */
    @Test
    fun `profile grid view count site for engagement rate resolves on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val binds = classes.flatMap { cls -> cls.methods.filter { ProfileGridBindFingerprint.takes(it, cls) } }
            assertEquals("profile grid binds", 1, binds.size)
            val bind = binds.single()
            val site = runCatching { bind.gridCountSite() }.getOrElse { throw AssertionError(it.message, it) }
            assertEquals("text and item registers", 1 to 12, site.textRegister to site.itemRegister)
            val instructions = bind.implementation!!.instructions.toList()
            val result = instructions[site.insertAt - 1]
            assertEquals(Opcode.MOVE_RESULT_OBJECT, result.opcode)
            assertEquals(site.textRegister, (result as OneRegisterInstruction).registerA)
            val setText = (site.insertAt until instructions.size).first {
                val reference = (instructions[it] as? ReferenceInstruction)?.reference as? MethodReference
                reference?.name == "setText" && reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;")
            }
            assertEquals("the cell's text is the formatted count", site.textRegister,
                (instructions[setText] as FiveRegisterInstruction).registerD)
        }
    }

    /**
     * The grid cell holder's bind, which set the count on another creator's Videos tab, Favorites
     * and Liked on the S22 with 47.1.4: one bind per declared build, the item in the parameter register its statistics
     * are read from (v15), and two count sites (the plain cell and the pinned or scheduled one),
     * each text the one the next setText writes.
     */
    @Test
    fun `profile grid holder view count sites resolve on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val binds = classes.flatMap { cls -> cls.methods.filter { ProfileGridHolderBindFingerprint.takes(it, cls) } }
            assertEquals("grid holder binds", 1, binds.size)
            val bind = binds.single()
            val found = runCatching { bind.holderCountSites() }.getOrElse { throw AssertionError(it.message, it) }
            assertEquals("item register", 15, found.itemRegister)
            assertEquals("count sites", 2, found.sites.size)
            assertTrue("highest first", found.sites.zipWithNext().all { (a, b) -> a.insertAt > b.insertAt })
            val instructions = bind.implementation!!.instructions.toList()
            val thisRegister = bind.implementation!!.registerCount - bind.parameters.size - 1
            val views = found.sites.map { site ->
                val setText = (site.insertAt until instructions.size).first {
                    val reference = (instructions[it] as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.name == "setText" && reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/CharSequence;")
                }
                val call = instructions[setText] as FiveRegisterInstruction
                assertEquals("the cell's text is the formatted count", site.textRegister, call.registerD)
                // The view is the holder's own count field, read right before the call.
                val load = instructions[setText - 1]
                assertEquals("count view load", Opcode.IGET_OBJECT, load.opcode)
                assertEquals("count view register", call.registerC, (load as TwoRegisterInstruction).registerA)
                assertEquals("count view owner", thisRegister, load.registerB)
                (load as ReferenceInstruction).reference as FieldReference
            }
            assertEquals("one count view", 1, views.toSet().size)
            assertEquals("count view class", bind.definingClass, views.first().definingClass)
            assertEquals("count view type", "Lcom/bytedance/tux/input/TuxTextView;", views.first().type)
        }
    }

    /**
     * Play SDR instead of HDR hooks TikTok's own HDR-off answer and both player gear setters, one
     * method each. SdrPlayback counts hdrType 1 and 2 as HDR because SimBitRate.isHdr does.
     */
    @Test
    fun `SDR playback hooks land on one method each and isHdr keeps its HDR types on every fixture`() {
        val models = "Lcom/ss/android/ugc/playerkit/simapicommon/model/"
        val expected = mapOf(
            ForceHdrOffFingerprint to "Lcom/ss/android/ugc/aweme/video/simplayer/PlayerConfigImpl;->isForceHdrOff()Z",
            SimVideoSetBitRateFingerprint to "${models}SimVideo;->setBitRate(Ljava/util/List;)V",
            SimVideoUrlModelSetBitRateFingerprint to "${models}SimVideoUrlModel;->setBitRate(Ljava/util/List;)V",
        )
        for (apk in fixtures()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            for ((fingerprint, signature) in expected) {
                val taken = classes.flatMap { classDef ->
                    classDef.methods.filter { fingerprint.takes(it, classDef) }
                }
                assertEquals("${apk.name}: $signature", listOf(signature), taken.map { it.anchorSignature() })
            }
            val isHdr = classes.single { it.type == "${models}SimBitRate;" }
                .methods.single { it.name == "isHdr" && it.parameterTypes.isEmpty() }
            val instructions = isHdr.implementation!!.instructions.toList()
            assertEquals("${apk.name}: SimBitRate.isHdr literals", listOf(1, 2, 0),
                instructions.filterIsInstance<NarrowLiteralInstruction>().map { it.narrowLiteral })
            assertEquals("${apk.name}: SimBitRate.isHdr reads", List(2) { "${models}SimBitRate;->getHdrType()I" },
                instructions.filterIsInstance<ReferenceInstruction>().map { it.reference.toString() })
        }
    }

    /**
     * Prefer H.264 playback hooks both player gear setters and keeps the gears whose
     * SimBitRate.getCodecType is 0. That getter has to return the field a converter fills from the
     * feed's is_bytevc1 (0 H.264, 1 ByteVC1, 2 ByteVC2), and the patch's own shape checks have to
     * pass, on every declared build.
     */
    @Test
    fun `H264 playback hooks the gear setters and getCodecType reads is_bytevc1 on every declared build`() {
        val models = "Lcom/ss/android/ugc/playerkit/simapicommon/model/"
        val bitRate = "Lcom/ss/android/ugc/aweme/feed/model/BitRate;"
        val expected = mapOf(
            SimVideoSetBitRateFingerprint to "${models}SimVideo;->setBitRate(Ljava/util/List;)V",
            SimVideoUrlModelSetBitRateFingerprint to "${models}SimVideoUrlModel;->setBitRate(Ljava/util/List;)V",
        )
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            for ((fingerprint, signature) in expected) {
                val taken = classes.flatMap { classDef ->
                    classDef.methods.filter { fingerprint.takes(it, classDef) }
                }
                assertEquals(signature, listOf(signature), taken.map { it.anchorSignature() })
                requirePlayerGearSetter(taken.single())
            }

            val byType = classes.associateBy { it.type }
            val codec = requireGearCodecField { byType[it] }
            fun Method.fieldReads() = implementation!!.instructions
                .mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString() }.toList()
            val gear = byType.getValue(SIM_BIT_RATE)
            assertEquals("SimBitRate.isBytevc1 reads the codec field", listOf(codec.toString()),
                gear.methods.single { it.name == "isBytevc1" && it.parameterTypes.isEmpty() }.fieldReads())

            // The feed model's is_bytevc1 is the field its isBytevc1() returns.
            val feedField = byType.getValue(bitRate).fields.single { field ->
                field.annotations.any { annotation ->
                    annotation.elements.any { (it.value as? StringEncodedValue)?.value == "is_bytevc1" }
                }
            }
            assertEquals("I", feedField.type)
            assertEquals(listOf("$bitRate->${feedField.name}:I"),
                byType.getValue(bitRate).methods.single { it.name == "isBytevc1" && it.parameterTypes.isEmpty() }
                    .fieldReads())

            // And a converter hands BitRate.isBytevc1() straight to the gear's codec setter.
            val feedCodec = "$bitRate->isBytevc1()I"
            val gearCodec = setOf("$SIM_BIT_RATE->setCodecType(I)V", "$SIM_BIT_RATE->setBytevc1(I)V")
            val converters = classes.flatMap { it.methods }.filter { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@filter false
                instructions.indices.any { index ->
                    val read = (instructions[index] as? ReferenceInstruction)?.reference?.toString() == feedCodec
                    val result = instructions.getOrNull(index + 1) as? OneRegisterInstruction
                    read && result != null && result.opcode == Opcode.MOVE_RESULT &&
                        instructions.drop(index + 2).firstOrNull { it.opcode.name.startsWith("invoke") }?.let { call ->
                            (call as ReferenceInstruction).reference.toString() in gearCodec &&
                                (call as FiveRegisterInstruction).registerD == result.registerA
                        } == true
                }
            }
            assertTrue("no converter copies BitRate.isBytevc1 into a gear's codec", converters.isNotEmpty())
        }
    }

    /**
     * ContentMarkerFilters.aiSignal reads each AI marker by name, getter first and field second.
     * Every one it relies on has to be there on every declared build, in the shape it reads:
     * a no-argument getter or a field of that name.
     */
    @Test
    fun `every AI filter signal resolves on every declared build`() {
        val model = "Lcom/ss/android/ugc/aweme/feed/model/"
        val wanted = mapOf(
            "${model}Aweme;" to listOf(
                "getAigcInfo", "getModerationAigcInfo", "getC2paInfo", "getAiAliveInfo", "aiRemixInfo",
                "aiPortraitInfo", "aiTheaterInfo", "aiChatEditorInfo", "getAnchors", "getTextExtra",
                "getDesc", "getAid",
            ),
            "Lcom/ss/android/ugc/aweme/feed/AIGCInfo;" to listOf("getAIGCLabelType", "createByAI"),
            "${model}ModerationAigcInfo;" to listOf(
                "moderationAigcLabelType", "moderationUserLabelStatus", "creatorGuidanceStatus",
                "moderationCreatorSegment",
            ),
            "${model}C2PAInfo;" to listOf("aigcSrc", "firstAigcSrc", "lastAigcSrc"),
            "${model}AIAliveInfo;" to listOf("getModelKey", "getModelPrompt", "getText"),
            "${model}AIRemixInfo;" to listOf("getTaskId", "getPromptId"),
            "${model}AIPortraitInfo;" to listOf("getTaskId", "getPromptId"),
            "${model}AITheaterInfo;" to listOf("getTaskId", "getPromptId"),
            "${model}AiChatEditorInfo;" to listOf("getTaskId"),
            "${model}AnchorCommonStruct;" to listOf("getComponentKey"),
            "Lcom/ss/android/ugc/aweme/model/TextExtraStruct;" to listOf("getHashTagName"),
        )
        val aiAnchorKeys = setOf(
            "anchor_aigc_avatar", "anchor_ai_portrait", "anchor_ai_remix", "anchor_ai_style",
            "anchor_ai_group_shot",
        )
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type in wanted }
                .associateBy { it.type }
            for ((type, members) in wanted) {
                val classDef = classes[type] ?: throw AssertionError("$type is missing")
                val getters = classDef.methods.filter { it.parameterTypes.isEmpty() }.map { it.name }.toSet()
                val fields = classDef.fields.map { it.name }.toSet()
                assertEquals(
                    "$type: AI signal members",
                    emptyList<String>(),
                    members.filter { it !in getters && it !in fields },
                )
            }
            val aweme = classes.getValue("${model}Aweme;")
            for (name in listOf("aiRemixInfo", "aiPortraitInfo", "aiTheaterInfo", "aiChatEditorInfo")) {
                assertTrue("Aweme.$name is a struct field", aweme.fields.any { it.name == name && it.type.startsWith(model) })
            }
            val loaded = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { it.methods.asSequence() }
                .flatMap { it.stringConstants().asSequence() }
                .filter { it in aiAnchorKeys }
                .toSet()
            assertEquals("AI effect anchor keys", aiAnchorKeys, loaded)
        }
    }

    /**
     * Match text stickers too reads Aweme's sticker list and each sticker's type and textStruct.
     * TikTok's own translation service is what says a type 18 sticker's textStruct is its words:
     * it checks the type against 18 before it reads the string; type 5 keeps anchor JSON there.
     */
    @Test
    fun `text sticker members the sticker word rule reads resolve on every declared build`() {
        val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        val sticker = "Lcom/ss/android/ugc/aweme/sticker/data/InteractStickerStruct;"
        val translation = "Lcom/ss/android/ugc/aweme/translation/service/TranslationServiceImpl;"
        val textModel = "Lcom/ss/android/ugc/aweme/tools/sticker/core/text/model/TextStickerModel;"
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type == aweme || it.type == sticker || it.type == translation || it.type == textModel }
                .associateBy { it.type }
            for ((type, name, returns) in listOf(
                Triple(aweme, "getInteractStickerStructs", "Ljava/util/List;"),
                Triple(sticker, "getType", "I"),
                Triple(sticker, "getTextStruct", "Ljava/lang/String;"),
            )) {
                assertTrue("${apk.name}: $type.$name()$returns", classes[type]?.methods?.toList().orEmpty().any {
                    it.name == name && it.parameterTypes.isEmpty() && it.returnType == returns
                })
            }
            val readsTextStickerWords = classes[translation]?.methods?.toList().orEmpty().any { method ->
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                fun calls(name: String) = instructions.any {
                    val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
                    reference != null && reference.definingClass == sticker && reference.name == name
                }
                calls("getType") && calls("getTextStruct") && instructions.any {
                    it.opcode == Opcode.CONST_16 && (it as NarrowLiteralInstruction).narrowLiteral == 18
                }
            }
            assertTrue("${apk.name}: the translation service reads a type 18 sticker's textStruct", readsTextStickerWords)
            // The editor writes the typed text into textStruct as type 20 with a caption model, else 18.
            val written = classes[textModel]?.methods?.toList().orEmpty().singleOrNull { it.name == "getInteractStickerStruct" }
            val instructions = written?.implementation?.instructions?.toList().orEmpty()
            val literals = instructions.filter { it.opcode == Opcode.CONST_16 }
                .map { (it as NarrowLiteralInstruction).narrowLiteral }.toSet()
            assertTrue("${apk.name}: the editor's text sticker types $literals", literals.containsAll(setOf(18, 20)))
            assertTrue("${apk.name}: the editor writes textStruct", instructions.any {
                val reference = (it as? ReferenceInstruction)?.reference as? MethodReference
                reference != null && reference.definingClass == sticker && reference.name == "setTextStruct"
            })
        }
    }

    /**
     * Remove avatar rings: the story status getter, the feed avatar's bind and the one author
     * live check it calls, and the User.isLive reads in AvatarLiveDataAdapter, on every declared
     * build. The bind and the check are R8 names that move each build.
     */
    @Test
    fun `avatar ring hooks resolve on every declared build`() {
        for (apk in Fixtures.declaredVersions().map(Fixtures::apkOf)) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.associateBy { it.type }
            fun taken(fingerprint: app.morphe.patcher.Fingerprint) = classes.values.flatMap { classDef ->
                classDef.methods.filter { fingerprint.takes(it, classDef) }
            }
            assertEquals("${apk.name}: story status getter", listOf("$AVATAR_USER->getStoryStatus()I"),
                taken(UserStoryStatusFingerprint).map { it.anchorSignature() })
            val bind = taken(FeedAvatarWrapBindFingerprint)
            assertEquals("${apk.name}: feed avatar bind", 1, bind.size)
            val check = bind.single().authorLiveCheck()
            val checkMethod = classes.getValue(check.definingClass).methods.single { method ->
                method.name == check.name && method.returnType == "Z" &&
                    method.parameterTypes.map { it.toString() } == check.parameterTypes.map { it.toString() }
            }
            assertTrue("${apk.name}: ${check.definingClass}->${check.name} is static",
                AccessFlags.STATIC.isSet(checkMethod.accessFlags))
            assertTrue("${apk.name}: the author live check returns",
                checkMethod.implementation!!.instructions.any { it.opcode == Opcode.RETURN })
            val adapterReads = classes.getValue(AVATAR_LIVE_DATA_ADAPTER).methods.sumOf { method ->
                method.implementation?.instructions?.toList()?.isLiveResults()?.size ?: 0
            }
            assertTrue("${apk.name}: AvatarLiveDataAdapter reads User.isLive", adapterReads > 0)
        }
    }

    /**
     * Lift text length limits: the comment keyboard's limit getter, the length filters each
     * repost note box builds (two alternatives in the video repost and reply boxes, one in the
     * LIVE repost note box) and the bio editor's three comparisons with 160, on every declared
     * build. The getter and both filter classes are R8 names that move each build.
     */
    @Test
    fun `length limit hooks resolve on every declared build`() {
        val filtersPerBox = REPOST_NOTE_INPUTS.zip(listOf(2, 1, 2)).toMap()
        for (apk in Fixtures.declaredVersions().map(Fixtures::apkOf)) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.associateBy { it.type }
            fun taken(fingerprint: app.morphe.patcher.Fingerprint) = classes.values.flatMap { classDef ->
                classDef.methods.filter { fingerprint.takes(it, classDef) }
            }
            val limitGetters = taken(CommentInputLimitFingerprint)
            assertEquals("${apk.name}: comment limit getter", 1, limitGetters.size)
            assertTrue("${apk.name}: the comment limit getter returns",
                limitGetters.single().implementation!!.instructions.any { it.opcode == Opcode.RETURN })
            val isInputFilter = { type: String ->
                classes[type]?.interfaces?.contains("Landroid/text/InputFilter;") == true
            }
            for (fingerprint in repostNoteInputFingerprints) {
                val method = taken(fingerprint).single()
                assertEquals("${apk.name}: ${method.definingClass} length filters", filtersPerBox[method.definingClass],
                    method.implementation!!.instructions.toList().lengthFilterConstructions(isInputFilter).size)
            }
            val bioSites = BIO_EDITOR_CLASSES.sumOf { type ->
                classes.getValue(type).methods.sumOf { method ->
                    method.implementation?.instructions?.toList()?.bioLimitConstants()?.size ?: 0
                }
            }
            assertEquals("${apk.name}: bio limit comparisons", 3, bioSites)
        }
    }

    /**
     * Show follow status hooks the profile header's text item bind and the follow list cell bind.
     * The header hook reads the item's view and its profile's common info through the one
     * BaseUIComponent field and getter of those types, and the cell hook asks ReusedUISlotAssem for
     * the cell's content view. The extension then reads the relation and profile by name.
     */
    @Test
    fun `follow status hooks and the relation members they read resolve on every declared build`() {
        val data = "Lcom/ss/android/ugc/profile/platform/base/data/"
        val user = "Lcom/ss/android/ugc/aweme/profile/model/User;"
        val getters = mapOf(
            PROFILE_COMMON_INFO to listOf("getUserProfileInfo", "getUserRelationInfo"),
            "${data}UserRelationInfo;" to listOf("getFollowStatus", "getFollowerStatus"),
            "${data}UserProfileInfo;" to listOf("getUsername", "getUid"),
            user to listOf("getUniqueId", "getNickname", "getUid", "getFollowStatus", "getFollowerStatus"),
        )
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }

            val header = classes.getValue(HEADER_TEXT_ITEM)
            assertEquals("the header text item's base", BASE_UI_COMPONENT, header.superclass)
            val headerBinds = header.methods.filter { ProfileHeaderTextBindFingerprint.takes(it, header) }
            assertEquals("the header text bind", 1, headerBinds.size)
            assertTrue("the header text bind has two free registers", headerBinds.single().implementation!!.registerCount >= 3)
            val base = classes.getValue(BASE_UI_COMPONENT)
            assertTrue("BaseUIComponent's one common info getter", base.commonInfoGetter() != null)
            assertTrue("BaseUIComponent's one view field", base.itemViewField() != null)

            val cell = classes.getValue(RELATION_USER_CELL)
            assertEquals("the follow list cell's base", REUSED_SLOT_ASSEM, cell.superclass)
            val cellBinds = cell.methods.filter { RelationCellBindFingerprint.takes(it, cell) }
            assertEquals("the follow list cell bind", 1, cellBinds.size)
            val itemType = ((cellBinds.single().implementation!!.instructions.first() as ReferenceInstruction)
                .reference as TypeReference).type
            assertEquals("the cell's item holds one User", 1, classes.getValue(itemType).fields.count { it.type == user })
            assertTrue(
                "ReusedUISlotAssem.getContentView",
                classes.getValue(REUSED_SLOT_ASSEM).methods.any {
                    it.name == "getContentView" && it.parameterTypes.isEmpty() && it.returnType == "Landroid/view/View;" &&
                        AccessFlags.PUBLIC.isSet(it.accessFlags)
                },
            )

            for ((type, names) in getters) {
                val declared = classes.getValue(type).methods.filter { it.parameterTypes.isEmpty() }.map { it.name }.toSet()
                assertEquals("$type: follow status getters", emptyList<String>(), names.filter { it !in declared })
            }
        }
    }

    /**
     * Copy bio and IDs hooks the bio item's bind, the share model's constructor and the share
     * sheet's action panel. The bio hook reads the item's one TuxTextView field, and the model hook
     * the builder's one BaseSharePackage field. The extension then reads `user` off a profile's
     * package and `aweme` off a video's, so those fields and the getters it calls are held too.
     */
    @Test
    fun `copy bio and IDs hooks and the share package members they read resolve on every declared build`() {
        val pkg = "Lcom/ss/android/ugc/aweme/share/improve/pkg/"
        val user = "Lcom/ss/android/ugc/aweme/profile/model/User;"
        val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .associateBy { it.type }

            val bio = classes.getValue(SIGNATURE_COMPONENT)
            val bioBinds = bio.methods.filter { BioBindFingerprint.takes(it, bio) }
            assertEquals("the bio bind", 1, bioBinds.size)
            assertTrue("the bio bind has two free registers", bioBinds.single().implementation!!.registerCount - 3 >= 2)
            assertTrue("the bio item's one text view", bio.bioTextField() != null)

            val snapshots = classes.values.flatMap { classDef ->
                classDef.methods.filter {
                    it.name == "<init>" && it.parameterTypes.size == 1 && ShareSnapshotFingerprint.takes(it, classDef)
                }
            }
            assertEquals("the share model constructor", 1, snapshots.size)
            assertTrue("the share model has a free register", snapshots.single().implementation!!.registerCount - 2 >= 1)
            val builder = classes.getValue(snapshots.single().parameterTypes.single().toString())
            assertTrue("the share model builder's one package", builder.sharePackageField() != null)

            val panel = resolveSharePanel(classes.values.filter { classDef ->
                classDef.methods.any { method ->
                    method.name == "onAttachedToWindow" && method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SHARE_PANEL_MARKER
                    } == true
                }
            })
            assertEquals("the action panel", "onAttachedToWindow", panel.name)
            val apps = resolveShareAppsPanel(classes.values.filter { classDef ->
                classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SHARE_APPS_MARKER
                    } == true
                }
            })
            assertTrue("the apps panel is its own class", apps.definingClass != panel.definingClass)

            assertEquals("a share package's kind", 1, classes.getValue(BASE_SHARE_PACKAGE).fields.count {
                it.name == "itemType" && it.type == "Ljava/lang/String;"
            })
            assertEquals("a profile's share package holds its account", 1, classes.getValue("${pkg}UserSharePackage;").fields.count {
                it.name == "user" && it.type == user
            })
            assertEquals("a video's share package holds its video", 1, classes.getValue("${pkg}AwemeSharePackage;").fields.count {
                it.name == "aweme" && it.type == aweme
            })
            for ((type, getter) in listOf(user to "getUniqueId", user to "getUid", aweme to "getAid")) {
                assertTrue("$type->$getter()", classes.getValue(type).methods.any {
                    it.name == getter && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
                })
            }
            // Account facts reads these by their real names; a renamed or retyped one says Not sent.
            val accountFields = classes.getValue(user).fields.associate { it.name to it.type }
            for ((name, type) in listOf(
                "_createTime" to "Ljava/lang/Long;",
                "registerTime" to "J",
                "region" to "Ljava/lang/String;",
                "accountRegion" to "Ljava/lang/String;",
                "language" to "Ljava/lang/String;",
                "uniqueIdModifyTime" to "J",
                "nickNameModifyTs" to "I",
                "isSecret" to "Z",
                "hasOpenFavorite" to "Z",
            )) {
                assertEquals("the account's $name", type, accountFields[name])
            }
        }
    }

    /**
     * Issue #128. The side menu button beside LIVE is found by the one method that asks for the
     * inflated sidebar icon. Its class has to be a feed toolbar button like LIVE's, tag itself
     * SIDEBAR, and keep one enabled check with a return the patch can answer, on every declared
     * build. The class is an R8 name (16o0 on 47.1.4).
     */
    @Test
    fun `side menu button and its enabled check resolve on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.associateBy { it.type }
            val iconViews = classes.values.flatMap { classDef ->
                classDef.methods.filter { SidebarIconViewFingerprint.takes(it, classDef) }
            }
            assertEquals("side menu icon view ${iconViews.map { it.anchorSignature() }}", 1, iconViews.size)

            val button = classes.getValue(iconViews.single().definingClass)
            val live = classes.getValue("Lcom/bytedance/tiktok/homepage/mainfragment/toolbar/LiveIconGenerator;")
            assertTrue("${button.type} is not a feed toolbar button: ${button.interfaces} vs LIVE's ${live.interfaces}",
                button.interfaces.any { it in live.interfaces })

            val enabled = button.methods.filter(::isToolbarEnabledCheck)
            assertEquals("${button.type} enabled checks", 1, enabled.size)
            assertTrue("${button.type}->enabled() has no return to wrap",
                enabled.single().implementation!!.instructions.any { it.opcode == Opcode.RETURN })

            val tags = button.methods.filter { it.name == "<init>" }.flatMap { method ->
                method.implementation?.instructions?.toList().orEmpty()
                    .filter { it.opcode == Opcode.SGET_OBJECT }
                    .mapNotNull { ((it as ReferenceInstruction).reference as? FieldReference)?.name }
            }
            assertTrue("${button.type} does not tag itself SIDEBAR: $tags", "SIDEBAR" in tags)
        }
    }

    @Test
    fun `main feed items getter exists once on every fixture`() {
        val apks = fixtures()

        for (apk in apks) {
            val matches = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type !=
                        "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"
                    ) continue
                    matches += classDef.methods.filter { method ->
                        method.name == "getItems" && method.parameterTypes.isEmpty() &&
                            method.returnType == "Ljava/util/List;"
                    }
                }
            }

            assertEquals(
                "${apk.name}: FeedItemList.getItems late filter boundary",
                1,
                matches.size,
            )
        }
    }

    @Test
    fun `settings compose rows anchor is unique on every fixture`() {
        val apks = fixtures()

        for (apk in apks.sortedByDescending { it.name }) {
            val matches = mutableListOf<Method>()
            val classMethods = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type.endsWith("/SettingsComposeRvmpFragment;")) {
                        classMethods += classDef.methods
                    }
                    for (method in classDef.methods) {
                        if (isSettingsComposeRowsMethod(method, classDef)) matches += method
                    }
                }
            }

            assertEquals(
                "${apk.name}: settings compose rows " +
                    matches.joinToString { it.anchorSignature() } +
                    "; class methods: " + classMethods.joinToString(" | ") { method ->
                        method.anchorSignature() + " calls=" + method.listReturningCalls().joinToString()
                    },
                1,
                matches.size,
            )
        }
    }

    @Test
    fun `watermark cache and playback anchors are unique on every fixture`() {
        val apks = fixtures()

        for (apk in apks) {
            val watermark = mutableListOf<Method>()
            val goldenCache = mutableListOf<Method>()
            val offlineCache = mutableListOf<Method>()
            val speed = mutableListOf<Method>()

            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        val strings = method.stringConstants()
                        if (
                            method.parameterTypes == listOf("Landroid/graphics/Bitmap;") &&
                            method.returnType == "V" &&
                            "[tiktok_logo]" in strings &&
                            method.drawsCommentImageWatermark()
                        ) {
                            watermark += method
                        }
                        val storeCount = method.countColdStartFeedItemListStores()
                        if (
                            method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                            "processGoldenVideoHitCache hitCache , time cost " in strings &&
                            storeCount in 1..4
                        ) {
                            goldenCache += method
                        }
                        if (
                            method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                            "processOfflineVideoHitCache error" in strings &&
                            storeCount in 1..4
                        ) {
                            offlineCache += method
                        }
                        if (
                            method.definingClass.endsWith("/feed/controller/PlayerController;") &&
                            method.parameterTypes == listOf("F") && method.returnType == "V" &&
                            "speed_begin" in strings && "begin_speed" in strings &&
                            method.playerManagerSpeedBoundary() != null
                        ) {
                            speed += method
                        }
                    }
                }
            }

            assertEquals("${apk.name}: comment watermark anchor", 1, watermark.size)
            assertEquals("${apk.name}: golden cold-cache anchor", 1, goldenCache.size)
            assertEquals("${apk.name}: offline cold-cache anchor", 1, offlineCache.size)
            assertEquals("${apk.name}: playback speed anchor", 1, speed.size)

            // What calls both cache methods: the cold start's orchestrator, which the patch hooks
            // too. Up to 47.0.3 the golden method ran the cold start itself, a shape the patch no
            // longer takes, so only a declared build is held to it.
            if (Fixtures.versionOf(apk) !in Fixtures.declaredVersions()) continue
            val targets = setOf(goldenCache.single().coldStartCall(), offlineCache.single().coldStartCall())
            val calls = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { it.methods.asSequence() }
                .mapNotNull { method ->
                    val called = method.implementation?.instructions
                        ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                        ?.map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
                        ?.filter { it in targets }?.toSet().orEmpty()
                    if (called.isEmpty()) null else method to called
                }.toMap()
            val orchestrators = coldStartOrchestrators(goldenCache.single(), offlineCache.single(), calls)
            assertEquals("${apk.name}: cold-start orchestrators", 1, orchestrators.size)
            val coldMethods = (goldenCache + offlineCache + orchestrators).distinctBy { it.anchorSignature() }
            assertEquals(
                "${apk.name}: cold-cache FeedItemList stores",
                COLD_START_STORES,
                coldMethods.sumOf { it.countColdStartFeedItemListStores() },
            )
            val offlineMarkers = coldMethods.sumOf { method ->
                method.implementation?.instructions?.count { instruction ->
                    instruction.opcode == Opcode.SGET_OBJECT &&
                        (instruction as? ReferenceInstruction)?.reference.let { reference ->
                            reference is FieldReference &&
                                reference.name == "OFFLINE_MODE" &&
                                reference.type == reference.definingClass
                        }
                } ?: 0
            }
            assertEquals("${apk.name}: cold-cache OFFLINE_MODE marker", 1, offlineMarkers)
        }
    }

    private fun Method.stringConstants(): Set<String> =
        implementation?.instructions?.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }?.toSet() ?: emptySet()

    private fun Method.anchorSignature(): String =
        "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"

    private fun Method.listReturningCalls(): List<String> =
        implementation?.instructions?.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@mapNotNull null
            reference.toString().takeIf {
                reference.returnType == "Ljava/util/List;" ||
                    "Ljava/util/Comparator;" in reference.parameterTypes ||
                    "Ljava/lang/Iterable;" in reference.parameterTypes
            }
        } ?: emptyList()

    private fun fixtures(): List<File> = Fixtures.apks()
}
