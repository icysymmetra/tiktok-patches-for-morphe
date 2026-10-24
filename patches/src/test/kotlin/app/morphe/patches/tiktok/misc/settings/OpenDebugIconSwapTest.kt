package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VM = "LX/OpenDebugCellVM;"
private const val STATE = "LX/State;"
private const val VECTOR = "LX/Vector;"
private const val GEAR = 0x7f010900

class OpenDebugIconSwapTest {
    @Test
    fun `a state constructor that loads the icon itself gets the gear there`() {
        val constructor = method(
            STATE, "<init>", "V", listOf("Ljava/lang/Integer;"), 4,
            """
                invoke-direct {v2}, Ljava/lang/Object;-><init>()V
                sget-object v0, LX/Icons;->debug:$VECTOR
                iput-object v0, v2, $STATE->icon:$VECTOR
                iput-object v3, v2, $STATE->title:Ljava/lang/Integer;
                return-void
            """,
        )
        val defaultState = method(
            VM, "defaultState", "Ljava/lang/Object;", emptyList(), 3,
            """
                new-instance v0, $STATE
                const v1, 0x7f0b0001
                invoke-static {v1}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v1
                invoke-direct {v0, v1}, $STATE-><init>(Ljava/lang/Integer;)V
                return-object v0
            """,
        )
        val untouched = defaultState.implementation!!.instructions.count()

        assertEquals("<init>", openDebugStateConstructor(defaultState, STATE).name)
        swapOpenDebugIcon(constructor, defaultState, VECTOR, GEAR)

        assertGearBuiltAfterLoad(constructor, loadIndex = 1, iconRegister = 0, liveRegisters = setOf(2, 3))
        assertEquals(untouched, defaultState.implementation!!.instructions.count())
    }

    @Test
    fun `on 47_1_16 defaultState loads the icon and passes it twice through a range call`() {
        val constructor = method(
            STATE, "<init>", "V", listOf(VECTOR, VECTOR, "Ljava/lang/Integer;", "LX/Rch;", "LX/Lambda;"), 7,
            """
                invoke-direct {v1}, Ljava/lang/Object;-><init>()V
                iput-object v2, v1, $STATE->icon:$VECTOR
                iput-object v3, v1, $STATE->selectedIcon:$VECTOR
                iput-object v4, v1, $STATE->title:Ljava/lang/Integer;
                return-void
            """,
        )
        val defaultState = method(
            VM, "defaultState", "Ljava/lang/Object;", emptyList(), 8,
            """
                new-instance v1, $STATE
                new-instance v5, LX/Rch;
                invoke-direct {v5, v7}, LX/Rch;-><init>(Ljava/lang/Object;)V
                sget-object v2, LX/Icons;->debug:$VECTOR
                const v0, 0x7f0b0001
                invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                move-result-object v4
                new-instance v6, LX/Lambda;
                const/16 v0, 0xef
                invoke-direct {v6, v7, v0}, LX/Lambda;-><init>(${VM}I)V
                move-object v3, v2
                invoke-direct/range {v1 .. v6}, $STATE-><init>($VECTOR${VECTOR}Ljava/lang/Integer;LX/Rch;LX/Lambda;)V
                return-object v1
            """,
        )
        val untouched = constructor.implementation!!.instructions.count()

        val reference = openDebugStateConstructor(defaultState, STATE)
        assertEquals(listOf(VECTOR, VECTOR, "Ljava/lang/Integer;", "LX/Rch;", "LX/Lambda;"),
            reference.parameterTypes.map(CharSequence::toString))
        swapOpenDebugIcon(constructor, defaultState, VECTOR, GEAR)

        val instructions = assertGearBuiltAfterLoad(
            defaultState, loadIndex = 3, iconRegister = 2, liveRegisters = setOf(1, 5, 7),
        )
        val copy = instructions.single { it.opcode == Opcode.MOVE_OBJECT }
        assertEquals(Opcode.MOVE_OBJECT, copy.opcode)
        assertEquals("the second icon argument copies the gear", 2, (copy as TwoRegisterInstruction).registerB)
        assertEquals(untouched, constructor.implementation!!.instructions.count())
    }

    @Test
    fun `an icon loaded nowhere fails instead of patching around it`() {
        val constructor = method(
            STATE, "<init>", "V", listOf(VECTOR), 2,
            """
                invoke-direct {v0}, Ljava/lang/Object;-><init>()V
                iput-object v1, v0, $STATE->icon:$VECTOR
                return-void
            """,
        )
        val defaultState = method(
            VM, "defaultState", "Ljava/lang/Object;", emptyList(), 3,
            """
                new-instance v0, $STATE
                new-instance v1, $VECTOR
                const v2, 0x7f010001
                invoke-direct {v1, v2}, $VECTOR-><init>(I)V
                invoke-direct {v0, v1}, $STATE-><init>($VECTOR)V
                return-object v0
            """,
        )

        val error = assertThrows(PatchException::class.java) {
            swapOpenDebugIcon(constructor, defaultState, VECTOR, GEAR)
        }
        assertTrue(error.message!!.contains("loads a $VECTOR icon"))
    }

    @Test
    fun `defaultState without a state constructor call is refused`() {
        val defaultState = method(
            VM, "defaultState", "Ljava/lang/Object;", emptyList(), 2,
            """
                new-instance v0, LX/Other;
                invoke-direct {v0}, LX/Other;-><init>()V
                return-object v0
            """,
        )

        assertThrows(PatchException::class.java) { openDebugStateConstructor(defaultState, STATE) }
    }

    private fun assertGearBuiltAfterLoad(
        method: MutableMethod,
        loadIndex: Int,
        iconRegister: Int,
        liveRegisters: Set<Int>,
    ): List<Instruction> {
        val instructions = method.implementation!!.instructions.toList()
        assertEquals(Opcode.SGET_OBJECT, instructions[loadIndex].opcode)

        val build = instructions[loadIndex + 1]
        assertEquals(Opcode.NEW_INSTANCE, build.opcode)
        assertEquals(iconRegister, (build as OneRegisterInstruction).registerA)
        assertEquals(VECTOR, (build as ReferenceInstruction).reference.toString())

        val id = instructions[loadIndex + 2]
        assertEquals(GEAR, (id as NarrowLiteralInstruction).narrowLiteral)
        val temp = (id as OneRegisterInstruction).registerA
        assertFalse("v$temp still holds a live value", temp in liveRegisters || temp == iconRegister)

        val init = instructions[loadIndex + 3] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_DIRECT, init.opcode)
        assertEquals(listOf(iconRegister, temp), listOf(init.registerC, init.registerD))
        assertEquals("$VECTOR-><init>(I)V", (init as ReferenceInstruction).reference.toString())
        return instructions
    }

    private fun method(
        owner: String,
        name: String,
        returnType: String,
        parameters: List<String>,
        registers: Int,
        body: String,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            owner,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or if (name == "<init>") AccessFlags.CONSTRUCTOR.value else 0,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply {
        addInstructions(0, body)
    }
}
