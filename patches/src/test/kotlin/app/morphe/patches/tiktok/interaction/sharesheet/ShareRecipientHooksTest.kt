package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRecipientHooksTest {
    private val tools = "Lapp/morphe/extension/tiktok/share/ShareSheetTools;"
    private val viewHolder = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"

    @Test
    fun `recipient binder is selected by stable model APIs and reports the bind before its null branch`() {
        val method = recipientBinder(includeUid = true)
        assertTrue(method.isShareRecipientBinder())
        val original = method.implementation!!.instructions.toList()

        method.notifyContactBound()

        val code = method.implementation!!.instructions.toList()
        assertEquals(27, method.implementation!!.registerCount)
        assertEquals(original.size + 1, code.size)
        original.take(6).forEachIndexed { index, instruction ->
            assertSame(instruction, code[index])
        }
        assertEquals(Opcode.INVOKE_STATIC, code[6].opcode)
        assertEquals(0, (code[6] as FiveRegisterInstruction).registerCount)
        assertMethod(code[6], tools, "contactBound", emptyList(), "V")
        assertEquals(Opcode.IF_NEZ, code[7].opcode)
        val nullBranch = code[7] as OffsetInstruction
        assertEquals(
            code[9].location.codeAddress,
            code[7].location.codeAddress + nullBranch.codeOffset,
        )
    }

    @Test
    fun `binder selector refuses a lookalike without the stable user id API`() {
        assertFalse(recipientBinder(includeUid = false).isShareRecipientBinder())
    }

    private fun recipientBinder(includeUid: Boolean): MutableMethod {
        val uid = if (includeUid) {
            """
                invoke-virtual { v3 }, Lcom/ss/android/ugc/aweme/im/common/model/BaseContact;->getUid()Ljava/lang/String;
                move-result-object v4
            """
        } else {
            ""
        }
        return mutableMethod(
            "Lfixture/ShareAdapter;",
            "onBindViewHolder",
            listOf(viewHolder, "I"),
            27,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            """
                move-object/from16 v0, p1
                move-object/from16 v2, p0
                move/from16 v1, p2
                invoke-interface { v2, v1 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                move-result-object v3
                check-cast v3, Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMContact;
                if-nez v3, :bound
                return-void
                :bound
                invoke-virtual { v3 }, Lcom/ss/android/ugc/aweme/im/common/model/BaseContact;->getDisplayName()Ljava/lang/String;
                move-result-object v4
                iget-object v1, v0, $viewHolder->itemView:Landroid/view/View;
                invoke-virtual { v1, v4 }, Landroid/view/View;->setContentDescription(Ljava/lang/CharSequence;)V
                $uid
                check-cast v3, Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMConversation;
                invoke-virtual { v3 }, Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMConversation;->getConversationId()Ljava/lang/String;
                move-result-object v4
                return-void
            """,
        )
    }

    private fun mutableMethod(
        owner: String,
        name: String,
        parameters: List<String>,
        registers: Int,
        flags: Int,
        body: String,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            "V",
            flags,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(0, body)
    }

    private fun assertMethod(
        instruction: Any,
        owner: String,
        name: String,
        parameters: List<String>,
        result: String,
    ) {
        val reference = (instruction as ReferenceInstruction).reference as MethodReference
        assertEquals(owner, reference.definingClass)
        assertEquals(name, reference.name)
        assertEquals(parameters, reference.parameterTypes.map(CharSequence::toString))
        assertEquals(result, reference.returnType)
    }
}
