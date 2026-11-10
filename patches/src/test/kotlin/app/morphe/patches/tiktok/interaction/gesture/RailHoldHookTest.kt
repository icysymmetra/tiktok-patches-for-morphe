package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * What the rail hold writes into TikTok's methods, instruction by instruction, on methods shaped
 * like 47.0.3's: which extension call each ask's answer goes through and in which register, where
 * each menu skip returns to, and that every branch TikTok had still lands where it did.
 */
class RailHoldHookTest {
    private val edge = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAssem;"
    private val like = "Lcom/ss/android/ugc/feed/platform/cell/ability/VideoDiggAssemAbility;"
    private val view = "Landroid/view/View;"
    private val onLongClick = "Landroid/view/View\$OnLongClickListener;"

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) =
        MutableMethod(ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        )).apply { addInstructionsWithLabels(0, smali) }

    private fun List<Instruction>.address(index: Int) = take(index).sumOf { it.codeUnits }

    private fun List<Instruction>.targetOf(index: Int): Int {
        val address = address(index) + (this[index] as OffsetInstruction).codeOffset
        return indices.first { address(it) == address }
    }

    /** Each branch of [before] against the instruction it lands on. */
    private fun branches(before: List<Instruction>) =
        before.indices.filter { before[it] is OffsetInstruction }.associate { before[it] to before[before.targetOf(it)] }

    private fun assertBranchesKept(kept: Map<Instruction, Instruction>, after: List<Instruction>) {
        kept.forEach { (branch, target) ->
            val index = after.indexOfFirst { it === branch }
            assertSame("a branch moved off its target", target, after[after.targetOf(index)])
        }
    }

    private fun assertExtensionCall(instruction: Instruction, name: String, parameters: List<String>, returnType: String) {
        val reference = instruction.getReference<MethodReference>()!!
        assertEquals(RAIL_HOLD_EXTENSION, reference.definingClass)
        assertEquals(name, reference.name)
        assertEquals(parameters, reference.parameterTypes.map(CharSequence::toString))
        assertEquals(returnType, reference.returnType)
    }

    /** 47.0.3's Z32 with its asks, the Favorites answer moved into v2 so a register slip shows. */
    private fun holdCheck(withFavorites: Boolean = true) = method(edge, "Z32", listOf("F", "F"), "Z", 10, """
        const/4 v6, 0x0
        const/4 v1, 0x1
        iget-object v0, p0, $edge->share:$SHARE_ABILITY
        invoke-interface { v0, p1, p2 }, $SHARE_ABILITY->gc1(FF)Z
        move-result v0
        if-ne v0, v1, :comment
        return v6
        :comment
        iget-object v0, p0, $edge->comment:$COMMENT_ABILITY
        invoke-interface { v0, p1, p2 }, $COMMENT_ABILITY->Z20(FF)Z
        move-result v0
        if-ne v0, v1, :like
        return v6
        :like
        iget-object v0, p0, $edge->like:$like
        invoke-interface { v0, p1, p2 }, $like->dp0(FF)Z
        move-result v0
        if-ne v0, v1, :favorite
        return v6
        :favorite
        ${if (withFavorites) """
        iget-object v0, p0, $edge->favorite:$FAVORITES_ABILITY
        invoke-interface { v0, p1, p2 }, $FAVORITES_ABILITY->go2(FF)Z
        move-result v2
        if-ne v2, v1, :allowed
        return v6
        """ else ""}
        :allowed
        return v1
    """)

    @Test
    fun `each ask's answer goes through its own extension call, in the ask's register, and Like's is left alone`() {
        val method = holdCheck()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.answerRailHitTests()

        val after = method.implementation!!.instructions.toList()
        assertEquals("two instructions for each of the three asks", before.size + 6, after.size)
        for ((ability, answer, register) in listOf(
            Triple(SHARE_ABILITY, "shareStopsHold", 0),
            Triple(COMMENT_ABILITY, "commentStopsHold", 0),
            Triple(FAVORITES_ABILITY, "favoritesStopsHold", 2),
        )) {
            val ask = after.indexOfFirst { it.getReference<MethodReference>()?.definingClass == ability }
            assertEquals(Opcode.MOVE_RESULT, after[ask + 1].opcode)
            val call = after[ask + 2]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertExtensionCall(call, answer, listOf("Z"), "Z")
            assertEquals("$answer reads the ask's own answer", register, (call as RegisterRangeInstruction).startRegister)
            assertEquals(1, call.registerCount)
            assertEquals(Opcode.MOVE_RESULT, after[ask + 3].opcode)
            assertEquals("$answer's answer goes back where TikTok reads it", register, (after[ask + 3] as OneRegisterInstruction).registerA)
            assertEquals("TikTok's own test of the answer follows", Opcode.IF_NE, after[ask + 4].opcode)
        }
        val likeAsk = after.indexOfFirst { it.getReference<MethodReference>()?.definingClass == like }
        assertEquals(Opcode.IF_NE, after[likeAsk + 2].opcode)
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a hold check that stopped asking a button is refused`() {
        assertThrows(PatchException::class.java) { holdCheck(withFavorites = false).answerRailHitTests() }
    }

    @Test
    fun `Comment's emoji row returns before anything of TikTok's runs when the extension says so`() {
        val method = method(VIDEO_COMMENT_ASSEM, "fs", listOf(), "V", 15, """
            iget-object v0, p0, $VIDEO_COMMENT_ASSEM->LLJJJJLIIL:Ljava/lang/Object;
            if-eqz v0, :done
            const-string v1, "$COMMENT_MENU_KEY"
            :done
            return-void
        """)
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.skipCommentMenuWhenHeld()

        val after = method.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC, after[0].opcode)
        assertExtensionCall(after[0], "skipCommentMenu", listOf(), "Z")
        assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, after[2].opcode)
        assertEquals(Opcode.RETURN_VOID, after[3].opcode)
        assertEquals("no skip goes on to TikTok's first instruction", Opcode.NOP, after[after.targetOf(2)].opcode)
        assertEquals(after.targetOf(2) + 1, after.indexOfFirst { it === before[0] })
        before.forEachIndexed { index, instruction -> assertSame(instruction, after[after.size - before.size + index]) }
        assertBranchesKept(kept, after)
    }

    /** 0BJ9's shape: the log, the login check, the offer, and Unit loaded and returned mid-method. */
    private fun favoritesMenu(unitRegister: Int = 0) = method("LX/0BJ9;", "invoke", listOf(), "Ljava/lang/Object;", 8, """
        const-string v0, "$FAVORITES_MENU_EVENT"
        invoke-static {}, LX/03Go;->LJFF()Ljava/lang/Object;
        move-result-object v1
        if-eqz v1, :unit
        const-string/jumbo v0, "$FAVORITES_MENU_KEY"
        :unit
        sget-object v$unitRegister, Lkotlin/Unit;->LIZ:Lkotlin/Unit;
        return-object v0
        :late
        const/4 v2, 0x0
        goto :unit
    """)

    @Test
    fun `Favorites' offer jumps to its own Unit return when the extension says so`() {
        val method = favoritesMenu()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)
        val unit = before.first { it.opcode == Opcode.SGET_OBJECT }

        method.skipFavoritesMenuWhenHeld()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size + 3, after.size)
        assertEquals(Opcode.INVOKE_STATIC, after[0].opcode)
        assertExtensionCall(after[0], "skipFavoritesMenu", listOf(), "Z")
        assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_NEZ, after[2].opcode)
        assertEquals(0, (after[2] as OneRegisterInstruction).registerA)
        assertSame("a skip lands on the lambda's Unit load", unit, after[after.targetOf(2)])
        before.forEachIndexed { index, instruction -> assertSame(instruction, after[index + 3]) }
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a Favorites offer whose Unit load isn't what it returns is refused`() {
        assertThrows(PatchException::class.java) { favoritesMenu(unitRegister = 1).skipFavoritesMenuWhenHeld() }
    }

    @Test
    fun `a Favorites offer with no register of its own to spare is refused`() {
        val bare = method("LX/0BJ9;", "invoke", listOf(), "Ljava/lang/Object;", 1, """
            sget-object p0, Lkotlin/Unit;->LIZ:Lkotlin/Unit;
            return-object p0
        """)
        assertThrows(PatchException::class.java) { bare.skipFavoritesMenuWhenHeld() }
    }

    /** 47.0.3's hold listener, cut down: the check on a press, and the post dropped in two places. */
    private fun holdTouch(drops: Boolean = true) = method("LX/0ANt;", "onTouch", listOf(view, "Landroid/view/MotionEvent;"), "Z", 11, """
        const/4 v5, 0x0
        iget-object v4, p0, LX/0ANt;->LLJL:$EDGE_SPEEDUP_ABILITY
        invoke-virtual {p2}, Landroid/view/MotionEvent;->getActionMasked()I
        move-result v2
        if-nez v2, :later
        const/4 v0, 0x0
        invoke-interface {v4, v0, v0}, $EDGE_SPEEDUP_ABILITY->Z32(FF)Z
        move-result v0
        return v0
        :later
        iget-object v1, p0, LX/0ANt;->LL:Landroid/os/Handler;
        iget-object v0, p0, LX/0ANt;->LLJLL:Ljava/lang/Runnable;
        ${if (drops) "invoke-virtual {v1, v0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V" else "invoke-virtual {v1, v0}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z"}
        iget-object v3, p0, LX/0ANt;->LL:Landroid/os/Handler;
        ${if (drops) "invoke-virtual {v3, v0}, Landroid/os/Handler;->removeCallbacks(Ljava/lang/Runnable;)V" else "nop"}
        return v5
    """)

    @Test
    fun `each of the hold listener's drops goes through the extension with TikTok's handler and post`() {
        val method = holdTouch()
        val before = method.implementation!!.instructions.toList()
        val kept = branches(before)

        method.routeHoldDrops()

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size, after.size)
        val drops = before.indices.filter { before[it].opcode == Opcode.INVOKE_VIRTUAL && before[it].getReference<MethodReference>()?.name == "removeCallbacks" }
        assertEquals(2, drops.size)
        for ((index, handler) in drops.zip(listOf(1, 3))) {
            assertEquals(Opcode.INVOKE_STATIC, after[index].opcode)
            assertExtensionCall(after[index], "holdDropped", listOf("Landroid/os/Handler;", "Ljava/lang/Runnable;"), "V")
            val call = after[index] as FiveRegisterInstruction
            assertEquals(2, call.registerCount)
            assertEquals("TikTok's handler", handler, call.registerC)
            assertEquals("TikTok's post", 0, call.registerD)
        }
        before.indices.filter { it !in drops }.forEach { assertSame(before[it], after[it]) }
        assertBranchesKept(kept, after)
    }

    @Test
    fun `a hold listener that no longer drops its post is refused`() {
        assertThrows(PatchException::class.java) { holdTouch(drops = false).routeHoldDrops() }
    }

    @Test
    fun `each of Share's long presses goes through the extension with TikTok's view and listener`() {
        val method = method(VIDEO_SHARE_ASSEM, "onViewCreated", listOf(view), "V", 4, """
            new-instance v0, LX/09Yy;
            invoke-direct {v0, p0}, LX/09Yy;-><init>($VIDEO_SHARE_ASSEM)V
            invoke-virtual {p1, v0}, $view->setOnLongClickListener($onLongClick)V
            const v1, 0x7f0c0000
            invoke-virtual {p1, v1}, $view->findViewById(I)$view
            move-result-object v1
            invoke-virtual {v1, v0}, $view->setOnLongClickListener($onLongClick)V
            return-void
        """)
        val before = method.implementation!!.instructions.toList()

        method.routeLongClicks("setShareLongClick")

        val after = method.implementation!!.instructions.toList()
        assertEquals(before.size, after.size)
        for ((index, registers) in listOf(2 to (3 to 0), 6 to (1 to 0))) {
            assertEquals(Opcode.INVOKE_STATIC, after[index].opcode)
            assertExtensionCall(after[index], "setShareLongClick", listOf(view, onLongClick), "V")
            val call = after[index] as FiveRegisterInstruction
            assertEquals(2, call.registerCount)
            assertEquals("the view", registers.first, call.registerC)
            assertEquals("TikTok's listener", registers.second, call.registerD)
        }
        before.indices.filter { it != 2 && it != 6 }.forEach { assertSame(before[it], after[it]) }
    }

    @Test
    fun `a view setup with no long press is refused`() {
        val method = method(VIDEO_SHARE_ASSEM, "onViewCreated", listOf(view), "V", 2, "return-void")
        assertThrows(PatchException::class.java) { method.routeLongClicks("setShareLongClick") }
    }
}
