package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommentReplySearchPatchTest {
    @Test
    fun `each reply bind return passes its own parent and native state without changing the frame`() {
        val cell = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/CommentMoreItemCell;"
        val model = "LX/0nlo;"
        val method = MutableMethod(
            ImmutableMethod(
                cell, "onBindItemView", listOf(ImmutableMethodParameter("LX/0lOS;", null, null)),
                "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(30, emptyList(), null, null),
            ),
        ).apply {
            // The native frame keeps the holder in p0=v28 and its model in p1=v29.
            // Both native branches render through Q5 before returning.
            appendFixtureInstructions(
                """
                    move-object/from16 v2, p1
                    check-cast v2, $model
                    move-object/from16 v3, p0
                    invoke-super {v3, v2}, Lcom/bytedance/ies/powerlist/PowerCell;->onBindItemView(LX/0lOS;)V
                    const/4 v1, 0
                    if-eqz v1, :other
                    invoke-virtual {v3, v2, v1}, $cell->Q5(${model}Z)V
                    return-void
                    :other
                    invoke-virtual {v3, v2, v1}, $cell->Q5(${model}Z)V
                    return-void
                """,
            )
        }
        val original = method.implementation!!.instructions.toList()

        val write = method.resolveReplySearch(replyClasses(cell, model))
        val planned = method.implementation!!.instructions.toList()
        assertEquals(original.size, planned.size)
        for (index in original.indices) assertSame(original[index], planned[index])
        write()

        val after = method.implementation!!.instructions.toList()
        assertEquals(30, method.implementation!!.registerCount)
        assertEquals(original.size + 18, after.size)
        var next = 0
        for (instruction in original) {
            if (instruction.opcode == Opcode.RETURN_VOID) {
                val bridge = after.subList(next, next + 9)
                val holderMove = bridge[0] as TwoRegisterInstruction
                assertEquals(Opcode.MOVE_OBJECT_FROM16, bridge[0].opcode)
                assertEquals(28, holderMove.registerB)
                val viewRegister = holderMove.registerA
                val viewField = (bridge[1] as ReferenceInstruction).reference as FieldReference
                assertEquals("Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;", viewField.definingClass)
                assertEquals("itemView", viewField.name)
                assertEquals("Landroid/view/View;", viewField.type)
                assertEquals(viewRegister, (bridge[1] as TwoRegisterInstruction).registerA)
                assertEquals(viewRegister, (bridge[1] as TwoRegisterInstruction).registerB)

                val modelMove = bridge[2] as TwoRegisterInstruction
                assertEquals(Opcode.MOVE_OBJECT_FROM16, bridge[2].opcode)
                assertEquals(29, modelMove.registerB)
                val modelRegister = modelMove.registerA
                assertEquals(Opcode.CHECK_CAST, bridge[3].opcode)
                assertEquals(model, (bridge[3] as ReferenceInstruction).reference.toString())
                assertEquals(modelRegister, (bridge[3] as OneRegisterInstruction).registerA)
                val stateCall = (bridge[4] as ReferenceInstruction).reference as MethodReference
                assertEquals(model, stateCall.definingClass)
                assertEquals("LIZ", stateCall.name)
                assertEquals(emptyList<String>(), stateCall.parameterTypes)
                assertEquals("I", stateCall.returnType)
                assertEquals(Opcode.INVOKE_VIRTUAL, bridge[4].opcode)
                assertEquals(1, (bridge[4] as FiveRegisterInstruction).registerCount)
                assertEquals(modelRegister, (bridge[4] as FiveRegisterInstruction).registerC)
                assertEquals(Opcode.MOVE_RESULT, bridge[5].opcode)
                val stateRegister = (bridge[5] as OneRegisterInstruction).registerA

                val extra = (bridge[6] as ReferenceInstruction).reference as FieldReference
                assertEquals(model, extra.definingClass)
                assertEquals("LLILZIL", extra.name)
                assertEquals("LX/0nls;", extra.type)
                val parent = (bridge[7] as ReferenceInstruction).reference as FieldReference
                assertEquals("LX/0nls;", parent.definingClass)
                assertEquals("LJI", parent.name)
                assertEquals("Lcom/ss/android/ugc/aweme/comment/model/Comment;", parent.type)
                for (index in listOf(6, 7)) {
                    assertEquals(Opcode.IGET_OBJECT, bridge[index].opcode)
                    assertEquals(modelRegister, (bridge[index] as TwoRegisterInstruction).registerA)
                    assertEquals(modelRegister, (bridge[index] as TwoRegisterInstruction).registerB)
                }
                assertEquals(Opcode.INVOKE_STATIC, bridge[8].opcode)
                val call = bridge[8] as FiveRegisterInstruction
                assertEquals(3, call.registerCount)
                assertEquals(viewRegister, call.registerC)
                assertEquals(modelRegister, call.registerD)
                assertEquals(stateRegister, call.registerE)
                assertEquals(3, setOf(viewRegister, modelRegister, stateRegister).size)
                val target = (bridge[8] as ReferenceInstruction).reference as MethodReference
                assertEquals("Lapp/morphe/extension/tiktok/comment/CommentSearch;", target.definingClass)
                assertEquals("onReplyControlBound", target.name)
                assertEquals(
                    listOf("Landroid/view/View;", "Ljava/lang/Object;", "I"),
                    target.parameterTypes.map(CharSequence::toString),
                )
                assertEquals("V", target.returnType)
                next += 9
            }
            assertSame(instruction, after[next++])
        }
    }

    @Test
    fun `the reply model and what it holds are read off the cell whatever the build calls them`() {
        // 46.8.3: the model is LX/0lDH;, handed over as LX/0U5F;, relaid out by Y5, and its
        // parent's data sits in LLJILLL of LX/0lDJ;. Not one of those names is 46.2.3's.
        val cell = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/CommentMoreItemCell;"
        val method = bind(cell, "LX/0lDH;", "LX/0U5F;", "Y5")
        method.registerReplySearch(replyClasses(cell, "LX/0lDH;", "Y5", "LLJILLL", "LX/0lDJ;"))
        val references = method.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertTrue(references.toString(), "LX/0lDH;" in references)
        assertTrue(references.toString(), "LX/0lDH;->LIZ()I" in references)
        assertTrue(references.toString(), "LX/0lDH;->LLJILLL:LX/0lDJ;" in references)
        assertTrue(
            references.toString(),
            "LX/0lDJ;->LJI:Lcom/ss/android/ugc/aweme/comment/model/Comment;" in references,
        )
        assertTrue(references.toString(), references.none { "0nlo" in it || "0nls" in it })
    }

    @Test
    fun `a model with two fields that could hold the parent is refused rather than guessed`() {
        val cell = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/CommentMoreItemCell;"
        val method = bind(cell, "LX/0nlo;", "LX/0lOS;", "Q5")
        try {
            method.registerReplySearch(replyClasses(cell, "LX/0nlo;", secondHolder = true))
            fail("expected a refusal")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "hold the parent comment, found 2" in refused.message.orEmpty())
        }
    }

    @Test
    fun `a late reply model refusal applies none of the earlier writes`() {
        val cell = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/CommentMoreItemCell;"
        val method = bind(cell, "LX/0nlo;", "LX/0lOS;", "Q5")
        val original = method.implementation!!.instructions.toList()
        var settingsWrites = 0
        var dislikeWrites = 0

        try {
            applyAfterCommentToolsPreflight(
                { { settingsWrites++ } },
                { { dislikeWrites++ } },
                { method.resolveReplySearch(replyClasses(cell, "LX/0nlo;", secondHolder = true)) },
            )
            fail("expected a refusal")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), "hold the parent comment, found 2" in refused.message.orEmpty())
        }

        assertEquals(0, settingsWrites)
        assertEquals(0, dislikeWrites)
        val after = method.implementation!!.instructions.toList()
        assertEquals(original.size, after.size)
        for (index in original.indices) assertSame(original[index], after[index])
    }

    @Test
    fun `all five host checks finish before the first deferred write`() {
        val events = mutableListOf<String>()
        fun resolver(name: String): () -> CommentToolsWrite = {
            events += "check $name"
            val write: CommentToolsWrite = { events += "write $name" }
            write
        }

        applyAfterCommentToolsPreflight(
            resolver("settings"),
            resolver("dislike"),
            resolver("reply"),
            resolver("cell"),
            resolver("list"),
        )

        assertEquals(
            listOf(
                "check settings", "check dislike", "check reply", "check cell", "check list",
                "write settings", "write dislike", "write reply", "write cell", "write list",
            ),
            events,
        )
    }

    /** A bind shaped like both builds': the item copied, cast to the model, then relaid out. */
    private fun bind(cell: String, model: String, item: String, relayout: String) = MutableMethod(
        ImmutableMethod(
            cell, "onBindItemView", listOf(ImmutableMethodParameter(item, null, null)),
            "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(30, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions(
            """
                move-object/from16 v2, p1
                check-cast v2, $model
                move-object/from16 v3, p0
                const/4 v1, 0
                invoke-virtual {v3, v2, v1}, $cell->$relayout(${model}Z)V
                return-void
            """,
        )
    }

    /**
     * The classes the names are read from: the cell, whose relayout of the model reads its
     * state; the model and its fields; and the data class that carries the parent comment.
     */
    private fun replyClasses(
        cell: String,
        model: String,
        relayout: String = "Q5",
        dataField: String = "LLILZIL",
        data: String = "LX/0nls;",
        secondHolder: Boolean = false,
    ): (String) -> ClassDef? {
        fun field(owner: String, name: String, type: String) = ImmutableField(
            owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null,
        )
        val relayoutMethod = MutableMethod(
            ImmutableMethod(
                cell, relayout,
                listOf(ImmutableMethodParameter(model, null, null), ImmutableMethodParameter("Z", null, null)),
                "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(4, emptyList(), null, null),
            ),
        ).apply {
            appendFixtureInstructions(
                """
                    invoke-virtual {p1}, $model->LIZ()I
                    move-result v0
                    invoke-virtual {p1}, $model->LIZ()I
                    move-result v0
                    return-void
                """,
            )
        }
        val cellClass = ImmutableClassDef(
            cell, AccessFlags.PUBLIC.value, "Lcom/bytedance/ies/powerlist/PowerCell;",
            null, null, null, null, listOf(relayoutMethod),
        )
        val modelClass = ImmutableClassDef(
            model, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null,
            listOfNotNull(
                field(model, "LL", "I"),
                field(model, "LLILLL", "Ljava/lang/String;"),
                field(model, "LLILZ", "I"),
                field(model, dataField, data),
                field(model, "LLILZLL", "Z"),
                if (secondHolder) field(model, "LLJ", data) else null,
            ),
            null,
        )
        val dataClass = ImmutableClassDef(
            data, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null,
            listOf(
                field(data, "LIZ", "I"),
                field(data, "LJ", "J"),
                field(data, "LJI", "Lcom/ss/android/ugc/aweme/comment/model/Comment;"),
                field(data, "LJII", "Ljava/util/List;"),
            ),
            null,
        )
        val classes = mapOf(cell to cellClass, model to modelClass, data to dataClass)
        return { type -> classes[type] }
    }
}
