package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four caller shapes on 46.2.3, 46.7.3 and 46.8.3, rebuilt with readable names and run
 * through a small interpreter with the guard answering both ways. Every fixture keeps the
 * register numbering of the real site, since which registers the chain reads is the whole
 * question for the flag register.
 */
class GhostModeCallSitesTest {
    private val guard = "shouldBlockStoryView"

    /** What the interpreter reads `sget-object Lcom/example/Coroutines;->SUSPENDED` as. */
    private val suspended = "Coroutines.SUSPENDED"

    // 46.2.3 LX/0QiI;->LIZJ(Boolean, String, String)V: reportStoryViewed, subscribeOn(io), two
    // consumers, subscribe. The Boolean-null path jumps back onto the report call, which is
    // the branch the guard has to be in front of.
    private fun storyViewedCaller() = method(
        registers = 8, params = listOf("Ljava/lang/Boolean;", "Ljava/lang/String;", "Ljava/lang/String;"),
        returns = "V", static = false,
        smali = """
            sget-object v2, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
            const-string v1, ""
            if-eqz v5, :no_flag
            invoke-virtual {v5}, Ljava/lang/Boolean;->booleanValue()Z
            move-result v0
            invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
            move-result-object v0
            :report
            invoke-virtual {v2, v6, v1, v0}, Lcom/example/StoryApi;->reportStoryViewed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Lcom/example/Observable;
            move-result-object v1
            invoke-static {}, Lcom/example/Schedulers;->io()Lcom/example/Scheduler;
            move-result-object v0
            invoke-virtual {v1, v0}, Lcom/example/Observable;->subscribeOn(Lcom/example/Scheduler;)Lcom/example/Observable;
            move-result-object v3
            new-instance v2, Lcom/example/Consumer;
            const/4 v0, 0x0
            invoke-direct {v2, v6, v0}, Lcom/example/Consumer;-><init>(Ljava/lang/String;I)V
            new-instance v1, Lcom/example/Consumer;
            const/4 v0, 0x1
            invoke-direct {v1, v6, v0}, Lcom/example/Consumer;-><init>(Ljava/lang/String;I)V
            invoke-virtual {v3, v2, v1}, Lcom/example/Observable;->subscribe(Lcom/example/Consumer;Lcom/example/Consumer;)Lcom/example/Disposable;
            invoke-static {}, Lcom/example/Flags;->enabled()Z
            move-result v0
            if-eqz v0, :done
            invoke-static {v6}, Lcom/example/Log;->note(Ljava/lang/String;)V
            :done
            return-void
            :no_flag
            const/4 v0, 0x0
            goto :report
        """,
    )

    // 46.2.3 LY/ALAdapterS7S0100000_11;->onAnimationEnd$5: reportUserInteraction, a callback,
    // enqueue, then a goto. The instruction after the send is the goto.
    private fun userInteractionCaller() = method(
        registers = 7, params = listOf("Ljava/lang/Object;", "Landroid/animation/Animator;"),
        returns = "V", static = true,
        smali = """
            sget-object v3, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
            const-string v2, "aid"
            const-string v1, ""
            const/4 v0, 0x0
            invoke-virtual {v3, v2, v0, v1}, Lcom/example/StoryApi;->reportUserInteraction(Ljava/lang/String;ILjava/lang/String;)Lcom/example/Call;
            move-result-object v1
            new-instance v0, Lcom/example/Callback;
            invoke-direct {v0}, Lcom/example/Callback;-><init>()V
            invoke-interface {v1, v0}, Lcom/example/Call;->enqueue(Lcom/example/Callback;)V
            goto :finish
            nop
            :finish
            invoke-static {v5}, Lcom/example/Component;->finish(Ljava/lang/Object;)V
            return-void
        """,
    )

    // 46.2.3 ProfilePlatformViewModel;->lU2, the part that matters: reportView, subscribeOn,
    // two static consumers, subscribe, then the follow-status work that used to be lost to
    // the NullPointerException. The wrapper register v4 is reused for a consumer after the
    // chain has moved on to v12.
    private fun profileViewCaller() = method(
        registers = 16, params = listOf("Lcom/example/User;"), returns = "V", static = true,
        smali = """
            sget-object v12, Lcom/example/ProfileViewerApiService;->INSTANCE:Lcom/example/ProfileViewerApiService;
            const-string v4, "from"
            const-string v3, "uid"
            const-string v13, "viewer"
            invoke-virtual {v12, v4, v3, v13}, Lcom/example/ProfileViewerApiService;->reportView(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lcom/example/Single;
            move-result-object v4
            invoke-static {}, Lcom/example/Schedulers;->io()Lcom/example/Scheduler;
            move-result-object v3
            invoke-virtual {v4, v3}, Lcom/example/Single;->subscribeOn(Lcom/example/Scheduler;)Lcom/example/Single;
            move-result-object v12
            sget-object v4, Lcom/example/Consumers;->ON_SUCCESS:Lcom/example/Consumer;
            sget-object v3, Lcom/example/Consumers;->ON_ERROR:Lcom/example/Consumer;
            invoke-virtual {v12, v4, v3}, Lcom/example/Single;->subscribe(Lcom/example/Consumer;Lcom/example/Consumer;)Lcom/example/Disposable;
            if-eqz v15, :skip_follow
            invoke-static {v15}, Lcom/example/Profile;->publishUserInfo(Lcom/example/User;)V
            :skip_follow
            return-void
        """,
    )

    // 46.2.3 LX/015b;->invokeSuspend: reportStoryReveal is a suspend function, so the caller
    // compares what comes back against the suspended marker and returns either way.
    private fun storyRevealCaller() = method(
        registers = 6, params = listOf("Ljava/lang/Object;"), returns = "Ljava/lang/Object;", static = false,
        smali = """
            sget-object v2, Lcom/example/Coroutines;->SUSPENDED:Ljava/lang/Object;
            const-string v3, "reveal"
            sget-object v1, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
            const/4 v0, 0x1
            invoke-virtual {v1, v0, v4}, Lcom/example/StoryApi;->reportStoryReveal(ILcom/example/Continuation;)Ljava/lang/Object;
            move-result-object v0
            if-ne v0, v2, :completed
            invoke-static {v3}, Lcom/example/Log;->note(Ljava/lang/String;)V
            return-object v2
            :completed
            invoke-static {v3}, Lcom/example/Log;->done(Ljava/lang/String;)V
            return-object v0
        """,
    )

    @Test
    fun `an observable chain is skipped from before the call to after subscribe`() {
        val method = storyViewedCaller()
        val call = method.indexOfCall("reportStoryViewed")
        val original = method.instructionCount()

        val chain = method.skipReportAtCallSite(call, guard)

        assertEquals(ReportChain.Sent(method.indexOfCall("subscribe") - 3, setOf(0, 1, 2, 3)), chain)
        assertEquals(original + 3, method.instructionCount())
        method.assertGuardLandsAfter("subscribe")
        method.assertFlagIsNotReadByTheChain("reportStoryViewed", "subscribe")

        // The caller still builds the report's arguments; the guard sits right before the call.
        val on = method.run(mapOf(4 to "this", 5 to "flag", 6 to "story", 7 to "item"), block = true)
        assertEquals(listOf("booleanValue", "valueOf", guard, "enabled"), on.calls.map { it.name })

        val off = method.run(mapOf(4 to "this", 5 to "flag", 6 to "story", 7 to "item"), block = false)
        assertEquals(
            listOf("booleanValue", "valueOf", guard, "reportStoryViewed", "io", "subscribeOn",
                "<init>", "<init>", "subscribe", "enabled"),
            off.calls.map { it.name },
        )
        assertEquals(listOf("StoryApi.INSTANCE", "story", "", "Integer(1)"), off.call("reportStoryViewed").arguments)
        assertEquals(listOf("observable-on-io", "new Consumer", "new Consumer"), off.call("subscribe").arguments)
    }

    @Test
    fun `a branch onto the report call reaches the guard, not the call`() {
        val method = storyViewedCaller()
        method.skipReportAtCallSite(method.indexOfCall("reportStoryViewed"), guard)
        val gotoIndex = method.instructions().indexOfLast { it.opcode == Opcode.GOTO }
        assertEquals(method.indexOfCall(guard), method.branchTarget(gotoIndex))

        // The Boolean-null path, guard on: no report, the rest of the method still runs.
        val on = method.run(mapOf(4 to "this", 5 to null, 6 to "story", 7 to "item"), block = true)
        assertEquals(listOf(guard, "enabled"), on.calls.map { it.name })

        val off = method.run(mapOf(4 to "this", 5 to null, 6 to "story", 7 to "item"), block = false)
        assertEquals(listOf("StoryApi.INSTANCE", "story", "", 0), off.call("reportStoryViewed").arguments)
        assertTrue(off.calls.any { it.name == "subscribe" })
    }

    @Test
    fun `a call chain is skipped past enqueue and lands on the goto`() {
        val method = userInteractionCaller()
        val chain = method.skipReportAtCallSite(method.indexOfCall("reportUserInteraction"), guard)

        assertEquals(ReportChain.Sent(method.indexOfCall("enqueue") - 3, setOf(0, 1)), chain)
        method.assertGuardLandsAfter("enqueue")
        method.assertFlagIsNotReadByTheChain("reportUserInteraction", "enqueue")

        val on = method.run(mapOf(5 to "adapter", 6 to "animator"), block = true)
        assertEquals(listOf(guard, "finish"), on.calls.map { it.name })
        assertEquals(listOf("adapter"), on.call("finish").arguments)

        val off = method.run(mapOf(5 to "adapter", 6 to "animator"), block = false)
        assertEquals(listOf(guard, "reportUserInteraction", "<init>", "enqueue", "finish"), off.calls.map { it.name })
        assertEquals(listOf("call", "new Callback"), off.call("enqueue").arguments)
    }

    @Test
    fun `a single chain is followed across registers and the work after it survives`() {
        val method = profileViewCaller()
        val chain = method.skipReportAtCallSite(method.indexOfCall("reportView"), "shouldBlockProfileView")

        assertEquals(ReportChain.Sent(method.indexOfCall("subscribe") - 3, setOf(3, 4, 12)), chain)
        method.assertGuardLandsAfter("subscribe")
        method.assertFlagIsNotReadByTheChain("reportView", "subscribe")

        val on = method.run(mapOf(15 to "user"), block = true)
        assertEquals(listOf("shouldBlockProfileView", "publishUserInfo"), on.calls.map { it.name })

        val off = method.run(mapOf(15 to "user"), block = false)
        assertEquals(
            listOf("shouldBlockProfileView", "reportView", "io", "subscribeOn", "subscribe", "publishUserInfo"),
            off.calls.map { it.name },
        )
        assertEquals(listOf("single-on-io", "Consumers.ON_SUCCESS", "Consumers.ON_ERROR"), off.call("subscribe").arguments)
    }

    @Test
    fun `a suspend reporter completes with nothing instead of being called`() {
        val method = storyRevealCaller()
        val chain = method.skipReportAtCallSite(method.indexOfCall("reportStoryReveal"), guard)

        assertEquals(ReportChain.SuspendedCheck::class.java, chain.javaClass)
        method.assertFlagIsNotReadByTheChain("reportStoryReveal", "reportStoryReveal")

        val on = method.run(mapOf(4 to "this", 5 to "result"), block = true)
        assertEquals(listOf(guard, "done"), on.calls.map { it.name })
        assertNull(on.returned)

        val off = method.run(mapOf(4 to "this", 5 to "result"), block = false)
        assertEquals(listOf(guard, "reportStoryReveal", "done"), off.calls.map { it.name })
        assertEquals("response", off.returned)

        val stillSuspends = method.run(mapOf(4 to "this", 5 to "result"), block = false, reveal = suspended)
        assertEquals(listOf(guard, "reportStoryReveal", "note"), stillSuspends.calls.map { it.name })
        assertEquals(suspended, stillSuspends.returned)
    }

    // The shape that broke the forward register search on 46.2.3: the send lands on a branch
    // into a long tail where nearly every low register is read again on one arm or another.
    // The four chain registers are read as arguments of the report call, so they are not free
    // either; the first register nothing reads on any path is v14, which is what the real
    // method needed.
    private fun profileViewCallerWithLongTail() = method(
        registers = 32, params = listOf("Lcom/example/User;", "Ljava/lang/String;"), returns = "V", static = true,
        smali = """
            sget-object v12, Lcom/example/ProfileViewerApiService;->INSTANCE:Lcom/example/ProfileViewerApiService;
            const-string v4, "from"
            const-string v3, "uid"
            const-string v13, "viewer"
            const/4 v0, 0x1
            const/4 v1, 0x1
            const/4 v2, 0x1
            const/4 v5, 0x1
            const/4 v6, 0x1
            const/4 v7, 0x1
            const/4 v8, 0x1
            const/4 v9, 0x1
            const/4 v10, 0x1
            const/4 v11, 0x1
            const/16 v17, 0x1
            const/16 v29, 0x1
            invoke-virtual {v12, v4, v3, v13}, Lcom/example/ProfileViewerApiService;->reportView(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lcom/example/Single;
            move-result-object v4
            invoke-static {}, Lcom/example/Schedulers;->io()Lcom/example/Scheduler;
            move-result-object v3
            invoke-virtual {v4, v3}, Lcom/example/Single;->subscribeOn(Lcom/example/Scheduler;)Lcom/example/Single;
            move-result-object v12
            sget-object v4, Lcom/example/Consumers;->ON_SUCCESS:Lcom/example/Consumer;
            sget-object v3, Lcom/example/Consumers;->ON_ERROR:Lcom/example/Consumer;
            invoke-virtual {v12, v4, v3}, Lcom/example/Single;->subscribe(Lcom/example/Consumer;Lcom/example/Consumer;)Lcom/example/Disposable;
            if-eqz v0, :later
            invoke-virtual {v0}, Lcom/example/User;->getFollowStatus()I
            move-result v4
            const/4 v3, 0x1
            if-ne v4, v3, :later
            invoke-static {v9, v1}, Lcom/example/Im;->follow(Ljava/lang/Object;Ljava/lang/Object;)V
            :later
            invoke-static {v2}, Lcom/example/Log;->note(Ljava/lang/Object;)V
            if-eqz v30, :skip
            invoke-static {v5, v6, v7, v8}, Lcom/example/Profile;->publish(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
            :skip
            invoke-static {v10, v11}, Lcom/example/Profile;->more(Ljava/lang/Object;Ljava/lang/Object;)V
            invoke-static/range {v17 .. v17}, Lcom/example/Profile;->tail(Ljava/lang/Object;)V
            invoke-static/range {v29 .. v29}, Lcom/example/Profile;->end(Ljava/lang/Object;)V
            invoke-static/range {v31 .. v31}, Lcom/example/Profile;->name(Ljava/lang/Object;)V
            return-void
        """,
    )

    @Test
    fun `a long branchy tail still yields a flag register nothing reads`() {
        val method = profileViewCallerWithLongTail()
        val chain = method.skipReportAtCallSite(method.indexOfCall("reportView"), "shouldBlockProfileView")

        assertEquals(setOf(3, 4, 12), (chain as ReportChain.Sent).defined)
        method.assertGuardLandsAfter("subscribe")
        val flagIndex = method.indexOfCall("shouldBlockProfileView") + 1
        assertEquals(14, (method.instructions()[flagIndex] as OneRegisterInstruction).registerA)

        val on = method.run(mapOf(30 to "user", 31 to "name"), block = true)
        assertEquals(listOf("shouldBlockProfileView", "getFollowStatus", "note", "publish", "more", "tail", "end", "name"),
            on.calls.map { it.name })
        val off = method.run(mapOf(30 to "user", 31 to "name"), block = false)
        assertEquals(listOf("shouldBlockProfileView", "reportView", "io", "subscribeOn", "subscribe", "getFollowStatus",
            "note", "publish", "more", "tail", "end", "name"), off.calls.map { it.name })
    }

    @Test
    fun `a chain register read after the send refuses the site`() {
        val method = method(
            registers = 6, params = emptyList(), returns = "V", static = true,
            smali = """
                sget-object v0, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
                const-string v1, "a"
                invoke-virtual {v0, v1, v1, v1}, Lcom/example/StoryApi;->reportStoryViewed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Lcom/example/Observable;
                move-result-object v2
                invoke-static {}, Lcom/example/Schedulers;->io()Lcom/example/Scheduler;
                move-result-object v3
                invoke-virtual {v2, v3}, Lcom/example/Observable;->subscribeOn(Lcom/example/Scheduler;)Lcom/example/Observable;
                move-result-object v4
                invoke-virtual {v4}, Lcom/example/Observable;->subscribe()V
                invoke-static {v3}, Lcom/example/Log;->note(Ljava/lang/Object;)V
                return-void
            """,
        )
        val failure = assertThrows(PatchException::class.java) {
            method.skipReportAtCallSite(method.indexOfCall("reportStoryViewed"), guard)
        }
        assertTrue(failure.message!!, failure.message!!.contains("v3") && failure.message!!.contains("read after the send"))
    }

    @Test
    fun `a report whose result is dropped has no chain to skip`() {
        val method = method(
            registers = 4, params = emptyList(), returns = "V", static = true,
            smali = """
                sget-object v0, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
                const-string v1, "a"
                invoke-virtual {v0, v1, v1, v1}, Lcom/example/StoryApi;->reportStoryViewed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Lcom/example/Observable;
                return-void
            """,
        )
        val failure = assertThrows(PatchException::class.java) {
            method.skipReportAtCallSite(method.indexOfCall("reportStoryViewed"), guard)
        }
        assertTrue(failure.message!!, failure.message!!.contains("not captured"))
    }

    @Test
    fun `a result that is tested rather than compared is refused`() {
        val method = method(
            registers = 4, params = emptyList(), returns = "V", static = true,
            smali = """
                sget-object v0, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
                const-string v1, "a"
                invoke-virtual {v0, v1, v1, v1}, Lcom/example/StoryApi;->reportStoryViewed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Lcom/example/Observable;
                move-result-object v1
                if-eqz v1, :done
                invoke-virtual {v1}, Lcom/example/Observable;->subscribe()V
                :done
                return-void
            """,
        )
        val failure = assertThrows(PatchException::class.java) {
            method.skipReportAtCallSite(method.indexOfCall("reportStoryViewed"), guard)
        }
        assertTrue(failure.message!!, failure.message!!.contains("leaves the straight line"))
    }

    @Test
    fun `a chain register written over before the send is refused`() {
        val method = method(
            registers = 4, params = emptyList(), returns = "V", static = true,
            smali = """
                sget-object v0, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
                const-string v1, "a"
                invoke-virtual {v0, v1, v1, v1}, Lcom/example/StoryApi;->reportStoryViewed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Lcom/example/Observable;
                move-result-object v1
                const/4 v1, 0x0
                invoke-static {v1}, Lcom/example/Log;->note(Ljava/lang/String;)V
                return-void
            """,
        )
        val failure = assertThrows(PatchException::class.java) {
            method.skipReportAtCallSite(method.indexOfCall("reportStoryViewed"), guard)
        }
        assertTrue(failure.message!!, failure.message!!.contains("written over"))
    }

    @Test
    fun `a chain with no send inside the cap is refused`() {
        val method = method(
            registers = 4, params = emptyList(), returns = "V", static = true,
            smali = """
                sget-object v0, Lcom/example/StoryApi;->INSTANCE:Lcom/example/StoryApi;
                const-string v1, "a"
                invoke-virtual {v0, v1, v1, v1}, Lcom/example/StoryApi;->reportStoryViewed(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Lcom/example/Observable;
                move-result-object v1
                ${"nop\n".repeat(30)}
                invoke-virtual {v1}, Lcom/example/Observable;->subscribe()V
                return-void
            """,
        )
        val failure = assertThrows(PatchException::class.java) {
            method.skipReportAtCallSite(method.indexOfCall("reportStoryViewed"), guard)
        }
        assertTrue(failure.message!!, failure.message!!.contains("no send within"))
    }

    @Test
    fun `the early return is only installed on a reporter that returns nothing`() {
        val silent = method(
            registers = 3, params = listOf("Ljava/lang/String;"), returns = "V", static = false,
            smali = """
                invoke-static {v2}, Lcom/example/Typing;->send(Ljava/lang/String;)V
                return-void
            """,
        )
        assertTrue(silent.returnBeforeReporting("shouldBlockTypingStatus"))
        assertEquals("shouldBlockTypingStatus", silent.callName(0))
        assertEquals(Opcode.RETURN_VOID, silent.instructions()[3].opcode)

        // The wrapper that hands back a lazy Call. Restoring a fabricated null return here
        // would put the story crash back, so it must be left exactly as it was.
        val lazy = method(
            registers = 5, params = listOf("Ljava/lang/String;", "I", "Ljava/lang/String;"), returns = "Lcom/example/Call;",
            static = false,
            smali = """
                iget-object v0, v1, Lcom/example/StoryApi;->api:Lcom/example/IStoryApi;
                invoke-interface {v0, v2, v3, v4}, Lcom/example/IStoryApi;->reportUserInteraction(Ljava/lang/String;ILjava/lang/String;)Lcom/example/Call;
                move-result-object v0
                return-object v0
            """,
        )
        val before = lazy.instructions().map { it.opcode }
        assertFalse(lazy.returnBeforeReporting("shouldBlockStoryView"))
        assertEquals(before, lazy.instructions().map { it.opcode })

        val noLocals = method(
            registers = 2, params = listOf("Ljava/lang/String;"), returns = "V", static = false,
            smali = "return-void",
        )
        assertFalse(noLocals.returnBeforeReporting("shouldBlockTypingStatus"))
    }

    // Fixture and assertion helpers.

    private fun method(
        registers: Int, params: List<String>, returns: String, static: Boolean, smali: String,
    ): MutableMethod {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0)
        return MutableMethod(
            ImmutableMethod(
                "Lcom/example/Caller;", "call",
                params.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        ).apply { addInstructionsWithLabels(0, smali.trimIndent()) }
    }

    private fun MutableMethod.instructions() = implementation!!.instructions.toList()
    private fun MutableMethod.instructionCount() = instructions().size

    private fun MutableMethod.callName(index: Int) =
        ((instructions()[index] as ReferenceInstruction).reference as MethodReference).name

    private fun MutableMethod.indexOfCall(name: String) = instructions().indexOfFirst {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == name
    }.also { require(it >= 0) { "no call to $name" } }

    private fun MutableMethod.addresses(): List<Int> {
        var address = 0
        return instructions().map { address.also { _ -> address += it.codeUnits } }
    }

    private fun MutableMethod.branchTarget(index: Int): Int {
        val addresses = addresses()
        val target = addresses[index] + (instructions()[index] as OffsetInstruction).codeOffset
        return addresses.indexOf(target).also { require(it >= 0) { "branch at $index lands between instructions" } }
    }

    /** The guard's branch lands on the instruction right after the named send. */
    private fun MutableMethod.assertGuardLandsAfter(send: String) {
        val guardIndex = instructions().indexOfFirst { it.opcode == Opcode.IF_NEZ }
        assertEquals(Opcode.INVOKE_STATIC, instructions()[guardIndex - 2].opcode)
        assertEquals(Opcode.MOVE_RESULT, instructions()[guardIndex - 1].opcode)
        assertEquals(indexOfCall(send) + 1, branchTarget(guardIndex))
    }

    /**
     * The flag register is not one the chain reads before writing. A register the chain
     * writes first (the subscribeOn result, say) is fair game: the guard's value is gone
     * before anything looks at it.
     */
    private fun MutableMethod.assertFlagIsNotReadByTheChain(report: String, send: String) {
        // The guard's own move-result, not an earlier one the fixture happens to have.
        val flagIndex = instructions().indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == GHOST_MODE_EXTENSION
        } + 1
        assertEquals(Opcode.MOVE_RESULT, instructions()[flagIndex].opcode)
        val flag = (instructions()[flagIndex] as OneRegisterInstruction).registerA
        val written = mutableSetOf<Int>()
        val inputs = mutableSetOf<Int>()
        for (index in indexOfCall(report)..indexOfCall(send)) {
            val instruction = instructions()[index]
            instruction.namedRegisters().forEachIndexed { position, register ->
                if (position == 0 && instruction.opcode.setsRegister()) written += register
                else if (register !in written) inputs += register
            }
        }
        assertFalse("flag v$flag is an input of the chain $inputs", flag in inputs)
    }

    // A small interpreter over the opcodes the fixtures use.

    private class Call(val name: String, val arguments: List<Any?>)
    private class Run(val calls: List<Call>, val returned: Any?) {
        fun call(name: String) = calls.first { it.name == name }
    }

    private fun MutableMethod.run(initial: Map<Int, Any?>, block: Boolean, reveal: Any? = "response"): Run {
        val instructions = instructions()
        val addresses = addresses()
        val registers = arrayOfNulls<Any?>(implementation!!.registerCount)
        initial.forEach { (register, value) -> registers[register] = value }
        val calls = mutableListOf<Call>()
        var result: Any? = null
        var index = 0
        var steps = 0
        while (true) {
            check(steps++ < 500) { "runaway fixture" }
            val instruction = instructions[index]
            when (val opcode = instruction.opcode) {
                Opcode.NOP -> index++
                Opcode.CONST_4, Opcode.CONST_16 -> {
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        (instruction as NarrowLiteralInstruction).narrowLiteral
                    index++
                }
                Opcode.CONST_STRING -> {
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        ((instruction as ReferenceInstruction).reference as StringReference).string
                    index++
                }
                Opcode.SGET_OBJECT -> {
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        field.definingClass.substringAfterLast('/').trimEnd(';') + "." + field.name
                    index++
                }
                Opcode.IGET_OBJECT -> {
                    val field = (instruction as ReferenceInstruction).reference as FieldReference
                    registers[(instruction as TwoRegisterInstruction).registerA] = "field " + field.name
                    index++
                }
                Opcode.NEW_INSTANCE -> {
                    val type = ((instruction as ReferenceInstruction).reference as TypeReference).type
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        "new " + type.substringAfterLast('/').trimEnd(';')
                    index++
                }
                Opcode.MOVE_OBJECT -> {
                    val move = instruction as TwoRegisterInstruction
                    registers[move.registerA] = registers[move.registerB]
                    index++
                }
                Opcode.INVOKE_STATIC, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_DIRECT, Opcode.INVOKE_INTERFACE,
                Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL_RANGE -> {
                    val reference = (instruction as ReferenceInstruction).reference as MethodReference
                    val arguments = when (instruction) {
                        is RegisterRangeInstruction ->
                            (instruction.startRegister until instruction.startRegister + instruction.registerCount)
                        is FiveRegisterInstruction ->
                            listOf(instruction.registerC, instruction.registerD, instruction.registerE,
                                instruction.registerF, instruction.registerG).take(instruction.registerCount)
                        else -> error("unexpected invoke form")
                    }.map { registers[it] }
                    calls += Call(reference.name, arguments)
                    result = when (reference.name) {
                        guard, "shouldBlockProfileView" -> if (block) 1 else 0
                        "booleanValue" -> 1
                        "valueOf" -> "Integer(${arguments[0]})"
                        "io" -> "scheduler"
                        "subscribeOn" -> if (reference.definingClass.endsWith("Single;")) "single-on-io" else "observable-on-io"
                        "reportStoryViewed" -> "observable"
                        "reportView" -> "single"
                        "reportUserInteraction" -> "call"
                        "reportStoryReveal" -> reveal
                        "subscribe" -> "disposable"
                        "enabled" -> 0
                        else -> null
                    }
                    index++
                }
                Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT -> {
                    registers[(instruction as OneRegisterInstruction).registerA] = result
                    index++
                }
                Opcode.IF_EQZ, Opcode.IF_NEZ -> {
                    val value = registers[(instruction as OneRegisterInstruction).registerA]
                    val zero = value == null || value == 0
                    val taken = if (opcode == Opcode.IF_EQZ) zero else !zero
                    index = if (taken) jump(addresses, index, instruction) else index + 1
                }
                Opcode.IF_EQ, Opcode.IF_NE -> {
                    val compare = instruction as TwoRegisterInstruction
                    val equal = registers[compare.registerA] == registers[compare.registerB]
                    val taken = if (opcode == Opcode.IF_EQ) equal else !equal
                    index = if (taken) jump(addresses, index, instruction) else index + 1
                }
                Opcode.GOTO, Opcode.GOTO_16 -> index = jump(addresses, index, instruction)
                Opcode.RETURN_VOID -> return Run(calls, null)
                Opcode.RETURN_OBJECT -> {
                    // A zero constant in a reference register is null, which is how the
                    // guarded suspend path hands the coroutine nothing.
                    val value = registers[(instruction as OneRegisterInstruction).registerA]
                    return Run(calls, if (value == 0) null else value)
                }
                else -> error("fixture uses $opcode, which the interpreter does not know")
            }
        }
    }

    private fun jump(addresses: List<Int>, index: Int, instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction): Int {
        val target = addresses[index] + (instruction as OffsetInstruction).codeOffset
        return addresses.indexOf(target).also { check(it >= 0) { "jump from $index lands between instructions" } }
    }
}
