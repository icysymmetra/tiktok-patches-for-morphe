package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommentDislikeTouchTest {
    @Test
    fun `like halo captures only the native like receiver and leaves dislike unchanged`() {
        for (range in listOf(false, true)) {
            val method = nativeTouchInstalls(false, range)
            val before = method.implementation!!.instructions.toList()
            val write = method.resolveLikeTouchListener(listeners())
            assertEquals(before, method.implementation!!.instructions.toList())
            write()
            val after = method.implementation!!.instructions.toList()
            for (index in before.indices.filter { it != 5 }) assertSame(before[index], after[index])
            assertEquals(if (range) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC, after[5].opcode)
            val hook = (after[5] as ReferenceInstruction).reference as MethodReference
            assertEquals("Lapp/morphe/extension/tiktok/comment/CommentLikeTouchTarget;", hook.definingClass)
            assertEquals("setNativeListener", hook.name)
            assertEquals(listOf("Landroid/view/View;", "Landroid/view/View\$OnTouchListener;"),
                hook.parameterTypes.map(CharSequence::toString))
            if (range) {
                assertEquals(1, (after[5] as RegisterRangeInstruction).startRegister)
                assertEquals(2, (after[5] as RegisterRangeInstruction).registerCount)
            } else {
                assertEquals(2, (after[5] as FiveRegisterInstruction).registerC)
                assertEquals(1, (after[5] as FiveRegisterInstruction).registerD)
            }
        }
    }

    @Test
    fun `both listener changes resolve before either writes and ambiguous likes fail closed`() {
        val method = nativeTouchInstalls(false)
        val before = method.implementation!!.instructions.toList()
        try {
            applyAfterCommentToolsPreflight(
                { method.resolveDislikeTouchListener(listeners()) },
                { method.resolveLikeTouchListener(listeners(dislikeAsks = "isUserDigged")) },
            )
            fail("ambiguous likes must refuse the patch")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "found 2" in refused.message.orEmpty())
            assertEquals(before, method.implementation!!.instructions.toList())
        }
        applyAfterCommentToolsPreflight(
            { method.resolveDislikeTouchListener(listeners()) },
            { method.resolveLikeTouchListener(listeners()) },
        )
        assertEquals(Opcode.INVOKE_STATIC, method.implementation!!.instructions.elementAt(5).opcode)
        assertEquals(Opcode.INVOKE_STATIC, method.implementation!!.instructions.elementAt(11).opcode)
    }

    @Test
    fun `capture preserves the like listener and both native dislike operands`() {
        for (range in listOf(false, true)) {
            val method = nativeTouchInstalls(range)
            val before = method.implementation!!.instructions.toList()
            val write = method.resolveDislikeTouchListener(listeners())
            val planned = method.implementation!!.instructions.toList()
            assertEquals(before.size, planned.size)
            for (index in before.indices) assertSame(before[index], planned[index])
            write()
            val after = method.implementation!!.instructions.toList()
            assertEquals(before.size, after.size)
            // Only the final native install changes. The preceding like install stays virtual.
            for (index in before.indices.filter { it != 11 }) assertSame(before[index], after[index])
            assertEquals(Opcode.INVOKE_VIRTUAL, after[5].opcode)
            val captured = after[11]
            assertEquals(if (range) Opcode.INVOKE_STATIC_RANGE else Opcode.INVOKE_STATIC, captured.opcode)
            val target = (captured as ReferenceInstruction).reference as MethodReference
            assertEquals("Lapp/morphe/extension/tiktok/comment/CommentTools;", target.definingClass)
            assertEquals("setDislikeTouchListener", target.name)
            assertEquals(
                listOf("Landroid/view/View;", "Landroid/view/View\$OnTouchListener;"),
                target.parameterTypes.map(CharSequence::toString),
            )
            assertEquals("V", target.returnType)
            if (range) {
                val call = captured as RegisterRangeInstruction
                assertEquals(1, call.startRegister)
                assertEquals(2, call.registerCount)
            } else {
                val call = captured as FiveRegisterInstruction
                assertEquals(2, call.registerC)
                assertEquals(1, call.registerD)
                assertEquals(2, call.registerCount)
            }
            assertEquals(before[11].codeUnits, captured.codeUnits)
        }
    }

    private fun nativeTouchInstalls(range: Boolean, likeRange: Boolean = false): MutableMethod {
        val method = MutableMethod(
            ImmutableMethod(
                "LX/0nvj;", "LIZIZ", emptyList(), "V", AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(12, emptyList(), null, null),
            ),
        )
        val receiver = if (range) 1 else 2
        val listener = if (range) 2 else 1
        val likeReceiver = if (likeRange) 1 else 2
        val likeListener = if (likeRange) 2 else 1
        val likeCall = if (likeRange) "invoke-virtual/range {v1 .. v2}" else "invoke-virtual {v2, v1}"
        val call = if (range) "invoke-virtual/range {v1 .. v2}" else "invoke-virtual {v2, v1}"
        method.appendFixtureInstructions(
            """
                iget-object v$likeReceiver, p0, LX/0nvj;->LLJJ:Landroid/widget/RelativeLayout;
                if-eqz v$likeReceiver, :dislike
                new-instance v$likeListener, LY/ATListenerS437S0100000_22;
                const/16 v0, 8
                invoke-direct {v$likeListener, p0, v0}, LY/ATListenerS437S0100000_22;-><init>(Ljava/lang/Object;I)V
                $likeCall, Landroid/view/View;->setOnTouchListener(Landroid/view/View${'$'}OnTouchListener;)V
                :dislike
                iget-object v$receiver, p0, LX/0nvj;->LLJJIJIIJIL:Landroid/widget/RelativeLayout;
                if-eqz v$receiver, :done
                new-instance v$listener, LY/ATListenerS437S0100000_22;
                const/16 v0, 9
                invoke-direct {v$listener, p0, v0}, LY/ATListenerS437S0100000_22;-><init>(Ljava/lang/Object;I)V
                $call, Landroid/view/View;->setOnTouchListener(Landroid/view/View${'$'}OnTouchListener;)V
                :done
                return-void
            """,
        )
        return method
    }

    @Test
    fun `the dislike is picked by what its listener does, in either order and on 46_8_3's names`() {
        // 46.8.3: the view is LX/0lNa;, the like and dislike sit in LLJJJJJIL and LLJL, and the
        // group is LY/ATListenerS409S0100000_21;. Taking the last install was right on 46.2.3 and
        // would put the block gesture on the like the day the two swapped places.
        val group = "LY/ATListenerS409S0100000_21;"
        for (dislikeFirst in listOf(false, true)) {
            val method = installs("LX/0lNa;", group, "LLJJJJJIL", "LLJL", dislikeFirst)
            val before = method.implementation!!.instructions.toList()
            method.captureDislikeTouchListener(listeners(group))
            val after = method.implementation!!.instructions.toList()
            val dislike = if (dislikeFirst) 5 else 11
            val like = if (dislikeFirst) 11 else 5
            assertEquals(Opcode.INVOKE_VIRTUAL, after[like].opcode)
            assertSame(before[like], after[like])
            assertEquals(Opcode.INVOKE_STATIC, after[dislike].opcode)
            val target = (after[dislike] as ReferenceInstruction).reference as MethodReference
            assertEquals("setDislikeTouchListener", target.name)
        }
    }

    @Test
    fun `no install whose listener asks about a dislike is refused rather than guessed`() {
        val method = installs("LX/0nvj;", group, "LLJJ", "LLJJIJIIJIL", dislikeFirst = false)
        try {
            method.captureDislikeTouchListener(listeners(group, dislikeAsks = "isUserDigged"))
            fail("expected a refusal")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "found 0" in refused.message.orEmpty())
        }
    }

    private val group = "LY/ATListenerS437S0100000_22;"

    /** Two installs on two RelativeLayouts of the view, numbered 8 for the like and 9 for the dislike. */
    private fun installs(view: String, group: String, likeField: String, dislikeField: String, dislikeFirst: Boolean): MutableMethod {
        val method = MutableMethod(
            ImmutableMethod(
                view, "LIZIZ", emptyList(), "V", AccessFlags.PUBLIC.value,
                null, null, ImmutableMethodImplementation(12, emptyList(), null, null),
            ),
        )
        val order = if (dislikeFirst) listOf(dislikeField to 9, likeField to 8) else listOf(likeField to 8, dislikeField to 9)
        method.appendFixtureInstructions(
            """
                iget-object v2, p0, $view->${order[0].first}:Landroid/widget/RelativeLayout;
                if-eqz v2, :second
                new-instance v1, $group
                const/16 v0, ${order[0].second}
                invoke-direct {v1, p0, v0}, $group-><init>(Ljava/lang/Object;I)V
                invoke-virtual {v2, v1}, Landroid/view/View;->setOnTouchListener(Landroid/view/View${'$'}OnTouchListener;)V
                :second
                iget-object v2, p0, $view->${order[1].first}:Landroid/widget/RelativeLayout;
                if-eqz v2, :done
                new-instance v1, $group
                const/16 v0, ${order[1].second}
                invoke-direct {v1, p0, v0}, $group-><init>(Ljava/lang/Object;I)V
                invoke-virtual {v2, v1}, Landroid/view/View;->setOnTouchListener(Landroid/view/View${'$'}OnTouchListener;)V
                :done
                return-void
            """,
        )
        return method
    }

    /**
     * The merged listener group: `onTouch` switching on its number onto one body per listener.
     * Body 8 asks the comment whether it is liked and body 9 whether it is disliked, as both
     * builds' do.
     */
    private fun listeners(
        group: String = this.group,
        dislikeAsks: String = "isUserBuried",
    ): (String) -> ClassDef? {
        val comment = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
        val touch = listOf(group, "Landroid/view/View;", "Landroid/view/MotionEvent;")
        fun body(number: Int, question: String) = MutableMethod(
            ImmutableMethod(
                group, "onTouch${'$'}$number", touch.map { ImmutableMethodParameter(it, null, null) }, "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    const/4 v0, 0x0
                    invoke-virtual {v0}, $comment->$question()Z
                    move-result v0
                    return v0
                """,
            )
        }
        val dispatcher = MutableMethod(
            ImmutableMethod(
                group, "onTouch",
                listOf("Landroid/view/View;", "Landroid/view/MotionEvent;").map { ImmutableMethodParameter(it, null, null) },
                "Z", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(
                0,
                """
                    iget v0, p0, $group->${'$'}t:I
                    packed-switch v0, :switch_data
                    const/4 v0, 0x0
                    return v0
                    :case_8
                    invoke-static {p0, p1, p2}, $group->onTouch${'$'}8(${touch.joinToString("")})Z
                    move-result v0
                    return v0
                    :case_9
                    invoke-static {p0, p1, p2}, $group->onTouch${'$'}9(${touch.joinToString("")})Z
                    move-result v0
                    return v0
                    :switch_data
                    .packed-switch 8
                        :case_8
                        :case_9
                    .end packed-switch
                """,
            )
        }
        val listenerClass = ImmutableClassDef(
            group, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            listOf("Landroid/view/View${'$'}OnTouchListener;"), null, null, null,
            listOf(dispatcher, body(8, "isUserDigged"), body(9, dislikeAsks)),
        )
        return { type -> if (type == group) listenerClass else null }
    }
}
