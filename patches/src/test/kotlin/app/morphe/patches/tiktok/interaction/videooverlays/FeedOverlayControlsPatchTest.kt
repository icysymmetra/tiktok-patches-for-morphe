package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.immutable.*
import org.junit.Assert.*
import org.junit.Test

class FeedOverlayControlsPatchTest {
    private val fullParent="Lcom/ss/android/ugc/feed/platform/cell/BaseCellSlotComponent;"
    private fun owner(type:String,parent:String)=ImmutableClassDef(type,AccessFlags.PUBLIC.value,parent,null,null,null,null,null)
    private fun method(type:String,parameters:List<String>,body:String)=MutableMethod(ImmutableMethod(type,
        if(type==FULLSCREEN_COMPONENT) "onViewCreated" else "bind",
        parameters.map{ImmutableMethodParameter(it,null,null)},"V",AccessFlags.PUBLIC.value,null,null,
        ImmutableMethodImplementation(30,emptyList(),null,null))).apply{addInstructions(0,body)}
    private fun full()=method(FULLSCREEN_COMPONENT,listOf("Landroid/view/View;"),"return-void")
    private fun card(marker:String)=method("Lfixture/$marker;",listOf(
        "Lcom/ss/android/ugc/aweme/feed/model/AnchorCommonStruct;","Lfixture/Host;","I","Lkotlin/jvm/functions/Function0;"),
        "const-string v0, \"$marker\"\nconst-string v0, \"bindData\"\nreturn-void")
    private fun definitions()=(listOf(owner(FULLSCREEN_COMPONENT,fullParent))+
        locationCardMarkers.map{owner("Lfixture/$it;","Landroid/widget/FrameLayout;")}).associateBy{it.type}

    private fun factory(legacy: Boolean = false, typedOwner: Boolean = true) = MutableMethod(ImmutableMethod(
        "Lfixture/BadgeFactory;", if (legacy) "invoke" else "build",
        if (legacy) emptyList() else listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Z", "Lfixture/Host;", "Lfixture/Ui;")
            .map { ImmutableMethodParameter(it, null, null) },
        if (legacy) "Ljava/lang/Object;" else "Ljava/util/List;",
        AccessFlags.PUBLIC.value or if (legacy) 0 else AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(32, emptyList(), null, null)
    )).apply { addInstructions(0, """
        invoke-virtual/range { v20 .. v20 }, Lcom/ss/android/ugc/aweme/feed/model/Aweme;->getAnchors()Ljava/util/List;
        move-result-object v19
        new-instance v0, Ljava/util/ArrayList;
        move-object/from16 v1, v19
        invoke-direct { v0, v1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
        invoke-virtual { v1 }, Lcom/ss/android/ugc/aweme/feed/model/AnchorCommonStruct;->getComponentKey()Ljava/lang/String;
        move-result-object v1
        iget-object v1, v2, ${if (typedOwner) "Lcom/ss/android/ugc/aweme/fcpanchor/access/VideoFeedMultiTagAssem;" else "Lfixture/Unrelated;"}->host:Ljava/lang/Object;
        return-object v0
    """.trimIndent()) }

    @Test fun `badge copy hooks only the typed renderer result and handles high registers`() {
        for (legacy in listOf(false, true)) {
            val method = factory(legacy)
            val before = method.implementation!!.instructions.toList()
            val write = method.resolveLocationBadgeList()
            assertEquals(before, method.implementation!!.instructions.toList())
            write()
            val after = method.implementation!!.instructions.toList()
            assertEquals(before.take(2), after.take(2))
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
            assertEquals(19, (after[2] as RegisterRangeInstruction).startRegister)
            assertEquals(1, (after[2] as RegisterRangeInstruction).registerCount)
            assertEquals("visibleAnchors", after[2].getReference<MethodReference>()!!.name)
            assertEquals(19, (after[3] as OneRegisterInstruction).registerA)
            assertEquals(before.drop(2), after.drop(4))
        }
    }

    @Test fun `similar model consumers outside the renderer are not patched`() {
        val method = factory(typedOwner = false)
        val before = method.implementation!!.instructions.toList()
        assertFalse(isLocationBadgeListFactory(method))
        assertThrows(PatchException::class.java) { method.resolveLocationBadgeList() }
        assertEquals(before, method.implementation!!.instructions.toList())
    }

    @Test fun `only exact component and typed location binders are accepted`() {
        assertTrue(isFullscreenBind(full()))
        assertFalse(isFullscreenBind(card("PoiAnchorView2")))
        assertTrue(isLocationCardBind(card("PoiAnchorView2"),"PoiAnchorView2"))
        assertFalse(isLocationCardBind(card("PoiDealAnchorView"),"PoiAnchorView2"))
        assertFalse(isLocationCardBind(card("ProductAnchorView"),"ProductAnchorView"))
    }

    @Test fun `range hooks use actual roots before aliasing and retain native bodies`() {
        val f=full();val cards=locationCardMarkers.map{it to card(it)}
        val methods=listOf(f)+cards.map{it.second}
        val before=methods.map{it.implementation!!.instructions.toList()}
        val definitions=definitions()
        val write=resolveFeedOverlayControls(f,cards){definitions[it]}
        methods.forEachIndexed{i,m->assertEquals(before[i],m.implementation!!.instructions.toList())}
        write()
        methods.forEachIndexed{i,m->
            val after=m.implementation!!.instructions.toList()
            assertEquals(Opcode.INVOKE_STATIC_RANGE,after.first().opcode)
            assertEquals(if(i==0)29 else 25,(after.first() as RegisterRangeInstruction).startRegister)
            assertEquals(1,(after.first() as RegisterRangeInstruction).registerCount)
            assertEquals(before[i],after.drop(1))
        }
    }

    @Test fun `missing or nonview final card refuses all writes`() {
        val f=full();val cards=locationCardMarkers.map{it to card(it)}
        val before=f.implementation!!.instructions.toList()
        val defs=definitions().toMutableMap()
        assertThrows(PatchException::class.java){resolveFeedOverlayControls(f,cards.take(1)){defs[it]}}
        defs[cards.last().second.definingClass]=owner(cards.last().second.definingClass,"Ljava/lang/Object;")
        assertThrows(PatchException::class.java){resolveFeedOverlayControls(f,cards){defs[it]}}
        assertEquals(before,f.implementation!!.instructions.toList())
    }
}
