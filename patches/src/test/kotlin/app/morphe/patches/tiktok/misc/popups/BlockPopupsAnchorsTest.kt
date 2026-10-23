package app.morphe.patches.tiktok.misc.popups

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ACTIVITY = "Landroid/app/Activity;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

class BlockPopupsAnchorsTest {
    private val filter = "Lx/Filter;->filterTask(Lx/Ctx;Lx/Task;Ljava/lang/String;Lx/Obs;)Z"

    private fun method(smali: String) = MutableMethod(
        ImmutableMethod(
            "Lcom/bytedance/poplayer/core/PopupTaskExecutor;", "start", emptyList(), "V",
            AccessFlags.PUBLIC.value, null, null,
            ImmutableMethodImplementation(12, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    @Test
    fun `the filter call is found with its task and answer registers`() {
        val call = popupFilterCalls(
            method(
                """
                    const/4 v9, 0x0
                    invoke-interface {v5, v6, v4, v1, v7}, $filter
                    move-result v9
                    return-void
                """,
            ),
        )
        assertNotNull(call)
        assertEquals(1, call!!.size)
        assertEquals(1, call[0].index)
        assertEquals(4, call[0].taskRegister)
        assertEquals(9, call[0].resultRegister)
    }

    @Test
    fun `a method with three filter calls, no answer read or a different shape is refused`() {
        assertNull(popupFilterCalls(method("""
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            move-result v9
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            move-result v9
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            move-result v9
            return-void
        """)))
        assertNull(popupFilterCalls(method("""
            invoke-interface {v5, v6, v4, v1, v7}, $filter
            return-void
        """)))
        assertNull(popupFilterCalls(method("""
            invoke-interface {v5, v6, v4, v7}, Lx/Filter;->filterTask(Lx/Ctx;Lx/Task;Lx/Obs;)Z
            move-result v9
            return-void
        """)))
    }

    @Test
    fun `a range call is read with the task in the third register`() {
        val ranged = method(
            """
                invoke-interface/range {v5 .. v9}, $filter
                move-result v1
                return-void
            """,
        )
        val call = popupFilterCalls(ranged)!!.single()
        assertEquals(7, call.taskRegister)
        assertEquals(1, call.resultRegister)
    }

    @Test
    fun `the hook lands before the call and is or-ed in after it, also when the answer reuses the task register`() {
        val m = method(
            """
                invoke-interface {v5, v6, v3, v1, v0}, $filter
                move-result v3
                const/4 v2, 0x1
                return-void
            """,
        )
        val call = popupFilterCalls(m)!!.single()
        assertEquals(3, call.taskRegister)
        assertEquals(3, call.resultRegister)
        m.hookPopupFilterCall(call)

        val opcodes = m.implementation!!.instructions.map { it.opcode }.filter { it != Opcode.NOP }
        assertEquals(
            listOf(
                Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT,
                Opcode.OR_INT, Opcode.CONST_4, Opcode.RETURN_VOID,
            ),
            opcodes,
        )
        val scratch = (m.implementation!!.instructions.first { it.opcode == Opcode.MOVE_RESULT } as OneRegisterInstruction).registerA
        assertTrue("scratch v$scratch is not one of the call's registers", scratch !in listOf(5, 6, 3, 1, 0))
        val or = m.implementation!!.instructions.first { it.opcode == Opcode.OR_INT } as ThreeRegisterInstruction
        assertEquals(3, or.registerA)
        assertEquals(3, or.registerB)
        assertEquals(scratch, or.registerC)
    }

    @Test
    fun `the popup layer's filter call is one method with usable registers on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { classDef -> classDef.methods.asSequence().filter { PopupTaskStartFingerprint.takes(it, classDef) } }
                .toList()
            assertEquals("methods taken: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val calls = popupFilterCalls(taken.single())
            assertNotNull("no usable filterTask call in ${taken.single().name}", calls)
            println("${apk.name}: " + calls!!.joinToString { "at ${it.index} task v${it.taskRegister} answer v${it.resultRegister}" })
            for (call in calls) {
                assertTrue("answer register v${call.resultRegister}", call.resultRegister in 0..255)
            }
        }
    }

    @Test
    fun `an early return asks the switch first and otherwise runs the method as it was`() {
        val m = method(
            """
                const/4 v1, 0x1
                return-void
            """,
        )
        m.returnEarlyWhen("hideLiveBubble", "return-void")
        val code = m.implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID,
                Opcode.CONST_4, Opcode.RETURN_VOID,
            ),
            code.map { it.opcode },
        )
        assertEquals(
            "Lapp/morphe/extension/tiktok/popups/PopupSwitches;->hideLiveBubble()Z",
            ((code[0] as ReferenceInstruction).reference as MethodReference).toString(),
        )
        assertEquals(0, (code[1] as OneRegisterInstruction).registerA)
        assertEquals("a no lands on the method's own first instruction", 4,
            (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals(0, (code[2] as OneRegisterInstruction).registerA)
    }

    private val campaignRead = """
        iget-object v0, v1, Lcom/ss/android/ugc/aweme/services/popsuite/PopSuiteManagerService;->currPopupConfigObj:Ljava/util/concurrent/atomic/AtomicReference;
        invoke-virtual {v0}, Ljava/util/concurrent/atomic/AtomicReference;->get()Ljava/lang/Object;
        move-result-object v2
        check-cast v2, Lcom/ss/android/ugc/aweme/IPopSuiteManagerService${'$'}PopupConfigObject;
    """

    /** Pop Suite's trigger: this, the activity, the frequency cache and the failure callback in p3 (v11). */
    private fun trigger(smali: String, parameters: List<String> = listOf(ACTIVITY, "Lx/FreqCache;", FUNCTION0)) =
        MutableMethod(
            ImmutableMethod(
                "Lcom/ss/android/ugc/aweme/services/popsuite/PopSuiteManagerService;", "popSuiteTriggerPopupInternal",
                parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(12, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali) }

    @Test
    fun `a Pop Suite campaign goes through the checklist between its cast and its null check`() {
        val m = trigger(
            """
                $campaignRead
                if-nez v2, :shows
                return-void
                :shows
                const/4 v1, 0x1
                return-void
            """,
        )
        assertEquals(3, campaignCast(m))
        assertEquals(3, failureCallbackRegister(m))
        m.passCampaignToChecklist()
        val code = m.implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST,
                Opcode.IF_EQZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST,
                Opcode.IF_NEZ, Opcode.INVOKE_STATIC_RANGE,
                Opcode.IF_NEZ, Opcode.RETURN_VOID, Opcode.CONST_4, Opcode.RETURN_VOID,
            ),
            code.map { it.opcode },
        )
        assertEquals(
            "Lapp/morphe/extension/tiktok/popups/PopupSwitches;->campaign(Ljava/lang/Object;)Ljava/lang/Object;",
            ((code[5] as ReferenceInstruction).reference as MethodReference).toString(),
        )
        assertEquals(2, (code[5] as RegisterRangeInstruction).startRegister)
        for (index in listOf(4, 6, 7, 8)) assertEquals(2, (code[index] as OneRegisterInstruction).registerA)
        assertEquals("a campaign that was already null takes the method's own check", 10,
            (code[4] as BuilderOffsetInstruction).target.location.index)
        assertEquals("a campaign that comes back skips the callback", 10,
            (code[8] as BuilderOffsetInstruction).target.location.index)
        assertEquals(
            "Lapp/morphe/extension/tiktok/popups/PopupSwitches;->campaignDropped(Ljava/lang/Object;)V",
            ((code[9] as ReferenceInstruction).reference as MethodReference).toString(),
        )
        val callback = code[9] as RegisterRangeInstruction
        assertEquals("the dropped campaign's callback is p3", 11, callback.startRegister)
        assertEquals(1, callback.registerCount)
        assertEquals("a campaign that comes back goes on as before", 12,
            (code[10] as BuilderOffsetInstruction).target.location.index)
    }

    @Test
    fun `a trigger without exactly one failure callback is refused`() {
        val shape = """
            $campaignRead
            if-nez v2, :shows
            return-void
            :shows
            return-void
        """
        val none = trigger(shape, listOf(ACTIVITY, "Lx/FreqCache;"))
        assertNull(failureCallbackRegister(none))
        assertThrows(PatchException::class.java) { none.passCampaignToChecklist() }
        assertNull(failureCallbackRegister(trigger(shape, listOf(FUNCTION0, ACTIVITY, FUNCTION0))))
        assertEquals("a wide parameter takes two registers", 4,
            failureCallbackRegister(trigger(shape, listOf("J", ACTIVITY, FUNCTION0))))
    }

    @Test
    fun `a campaign read in any other shape is refused`() {
        val noCheck = method(
            """
                $campaignRead
                return-void
            """,
        )
        assertNull(campaignCast(noCheck))
        assertThrows(PatchException::class.java) { noCheck.passCampaignToChecklist() }
        val otherRegister = method(
            """
                $campaignRead
                if-nez v3, :shows
                return-void
                :shows
                return-void
            """,
        )
        assertNull(campaignCast(otherRegister))
    }

    @Test
    fun `a method with no local register for the answer is refused`() {
        val m = MutableMethod(
            ImmutableMethod(
                "Lx/Owner;", "check", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(1, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, "return-void") }
        assertThrows(PatchException::class.java) { m.returnEarlyWhen("hideLiveBubble", "return-void") }
    }

    @Test
    fun `Pop Suite's trigger and the LIVE bubble check are one method each, hookable, on every declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()

            val bubble = classes.flatMap { classDef -> classDef.methods.filter { LiveBubbleCheckFingerprint.takes(it, classDef) } }
            assertEquals("${apk.name}: LIVE bubble check took ${bubble.map { it.name }}", 1, bubble.size)
            val found = bubble.single()
            val ins = found.parameterTypes.sumOf { type -> if (type.toString() == "J" || type.toString() == "D") 2 else 1 } +
                if (AccessFlags.STATIC.isSet(found.accessFlags)) 0 else 1
            assertTrue("${apk.name}: ${found.name} has no local register", found.implementation!!.registerCount > ins)

            val trigger = classes.flatMap { classDef -> classDef.methods.filter { PopSuiteTriggerFingerprint.takes(it, classDef) } }
            assertEquals("${apk.name}: Pop Suite trigger took ${trigger.map { it.name }}", 1, trigger.size)
            assertNotNull("${apk.name}: Pop Suite reads its campaign differently", campaignCast(trigger.single()))
            assertEquals("${apk.name}: the failure callback's register", 3, failureCallbackRegister(trigger.single()))
            // The extension reads the campaign's name by reflection, so it has to stay a public String.
            val campaign = classes.single { it.type == "Lcom/ss/android/ugc/aweme/IPopSuiteManagerService\$PopupConfigObject;" }
            assertTrue(
                "${apk.name}: the campaign has no public String popupName",
                campaign.fields.any {
                    it.name == "popupName" && it.type == "Ljava/lang/String;" && AccessFlags.PUBLIC.isSet(it.accessFlags)
                },
            )
        }
    }
}
