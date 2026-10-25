package app.morphe.patches.tiktok.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk through R8's merged-lambda dispatch.
 *
 * <p>A group holds many lambdas as one class and tells them apart by a number in a field. Its
 * entry point switches on that number and calls the body belonging to it, so following the switch
 * is what says which body a given lambda is. The alternative is trusting that R8 keeps naming the
 * body after the number, which is what this exists not to do.
 */
class LazyAbGateTest {
    private val group = "Lkotlin/jvm/internal/AFwS194S0000000_4;"

    @Test
    fun `the switch says which body belongs to each number`() {
        val entry = dispatcher(
            "invoke",
            firstKey = 1472,
            targets = listOf("invoke\$1472", "invoke\$1473", "invoke\$1474"),
        )
        val classDef = group(entry, body("invoke\$1472"), body("invoke\$1473"), body("invoke\$1474"))

        assertEquals("invoke\$1472", entry.dispatchTarget(classDef, 1472)?.name)
        assertEquals("invoke\$1473", entry.dispatchTarget(classDef, 1473)?.name)
        assertEquals("invoke\$1474", entry.dispatchTarget(classDef, 1474)?.name)
    }

    @Test
    fun `a number the switch does not carry has no body`() {
        val entry = dispatcher("invoke", firstKey = 1472, targets = listOf("invoke\$1472", "invoke\$1473"))
        val classDef = group(entry, body("invoke\$1472"), body("invoke\$1473"))

        assertNull(entry.dispatchTarget(classDef, 1471))
        assertNull(entry.dispatchTarget(classDef, 1474))
    }

    @Test
    fun `a body named after some other number is not what the switch reaches`() {
        // R8 names the body of number n `invoke$n` on every build seen. Reading the switch has to
        // disagree with that name when the two are made to disagree, or it is not reading it.
        val entry = dispatcher("invoke", firstKey = 7, targets = listOf("invoke\$999", "invoke\$998"))
        val classDef = group(entry, body("invoke\$999"), body("invoke\$998"))

        assertEquals("invoke\$999", entry.dispatchTarget(classDef, 7)?.name)
        assertEquals("invoke\$998", entry.dispatchTarget(classDef, 8)?.name)
        assertNull(entry.dispatchTarget(classDef, 999))
    }

    @Test
    fun `a later switch in the same body is not the dispatch`() {
        // The entry point switches on the number first. A second switch further down is on
        // something else, and a number it happens to carry must not be answered from it.
        val entry = method("invoke", emptyList(), "Ljava/lang/Object;", 2).apply {
            addInstructionsWithLabels(
                0,
                """
                    iget v0, v1, $group->${'$'}t:I
                    packed-switch v0, :first
                    const/4 v0, 0x0
                    packed-switch v0, :second
                    const/4 v0, 0x0
                    return-object v0
                    :case_a
                    invoke-static { v1 }, $group->invoke${'$'}5($group)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                    :case_b
                    invoke-static { v1 }, $group->invoke${'$'}9($group)Ljava/lang/Object;
                    move-result-object v0
                    return-object v0
                    :first
                    .packed-switch 5
                        :case_a
                    .end packed-switch
                    :second
                    .packed-switch 9
                        :case_b
                    .end packed-switch
                """,
            )
        }
        val classDef = group(entry, body("invoke\$5"), body("invoke\$9"))

        assertEquals("invoke\$5", entry.dispatchTarget(classDef, 5)?.name)
        assertNull(entry.dispatchTarget(classDef, 9))
        // And a body that is not a dispatcher has no target at all.
        assertNull(body("invoke\$5").dispatchTarget(classDef, 5))
    }

    @Test
    fun `the number handed to the factory is the one in the register the call reads`() {
        // R8 loads other constants around the factory call. The nearest one is not the answer;
        // the one in the register the call names is.
        val clinit = method("<clinit>", emptyList(), "V", 3).apply {
            addInstructionsWithLabels(
                0,
                """
                    const/16 v0, 0x5c0
                    const/4 v1, 0x3
                    const/4 v2, 0x0
                    invoke-static { v0 }, $group->get${'$'}arr${'$'}(I)Ljava/lang/Object;
                    return-void
                """,
            )
        }
        val instructions = clinit.implementation!!.instructions.toList()
        val call = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC }

        assertEquals(1472, constantBefore(instructions, call, 0))
        assertEquals(3, constantBefore(instructions, call, 1))
        assertNull(constantBefore(instructions, call, 7))
    }

    @Test
    fun `the factory number follows one move with a fresh lookback`() {
        val clinit = method("<clinit>", emptyList(), "V", 5).apply {
            addInstructionsWithLabels(
                0,
                """
                    const/16 v3, 0x5c0
                    const/4 v4, 0x0
                    const/4 v4, 0x1
                    move v0, v3
                    const/4 v1, 0x2
                    const/4 v2, 0x3
                    invoke-static { v0 }, $group->get${'$'}arr${'$'}(I)Ljava/lang/Object;
                    return-void
                """,
            )
        }
        val instructions = clinit.implementation!!.instructions.toList()
        val call = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_STATIC }

        assertEquals(1472, constantBefore(instructions, call, 0))
    }

    @Test
    fun `the search refuses a class with two methods of the gate's shape by name`() {
        // The old prefilter asked for exactly one method of the shape and skipped the class
        // when there were two, so the failure read as "no class reads that key" when one did.
        val gateClass = "LX/0lGf;"
        val first = lazyRead(unwraps = true, owner = gateClass, name = "LIZ")
        val second = lazyRead(unwraps = true, owner = gateClass, name = "LIZIZ")
        val clinit = method("<clinit>", emptyList(), "V", 1, owner = gateClass).apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "a_settings_key"
                    return-void
                """,
            )
        }
        val classDef = classDef(gateClass, clinit, first, second)
        val search = LazyAbGateSearch { null }
        val gate = { method: Method, _: ClassDef -> method.returnType == "Z" && method.isLazyAbRead() }

        val refused = runCatching {
            search.find("What", "a_settings_key", gate) { it(classDef) }
        }.exceptionOrNull()
        assertNotNull(refused)
        assertTrue(refused!!.message!!.contains("2 of its methods"))
        assertTrue(refused.message!!.contains(gateClass))

        // With one of them told apart by the gate, the class is found and it is that method.
        val (found, method) =
            search.find("What", "a_settings_key", { m, c -> m.name == "LIZIZ" && gate(m, c) }) { it(classDef) }
        assertEquals(gateClass, found.type)
        assertEquals("LIZIZ", method.name)

        // And a class whose initialiser reads some other key is not it.
        val other = runCatching {
            search.find("What", "another_key", gate) { it(classDef) }
        }.exceptionOrNull()
        assertTrue(other!!.message!!.contains("found 0"))
    }

    @Test
    fun `the key is found behind a merged group whose entry point takes an argument`() {
        // A Function1 group dispatches from invoke(Object), not invoke(). The walk follows any
        // entry point that opens on the number, so the interface's shape is not a condition.
        val entry = dispatcher(
            "invoke",
            firstKey = 1472,
            targets = listOf("invoke\$1472", "invoke\$1473"),
            parameters = listOf("Ljava/lang/Object;"),
        )
        val groupDef =
            group(entry, body("invoke\$1472"), body("invoke\$1473", key = "some_other_key"))
        val clinit = method("<clinit>", emptyList(), "V", 2, owner = "LX/0lGf;").apply {
            addInstructionsWithLabels(
                0,
                """
                    const/16 v1, 0x5c0
                    const/4 v0, 0x1
                    invoke-static { v1 }, $group->get${'$'}arr${'$'}(I)Ljava/lang/Object;
                    return-void
                """,
            )
        }
        val search = LazyAbGateSearch { type -> if (type == group) groupDef else null }

        assertTrue(search.readsSettingsKey(clinit, "a_settings_key"))
        assertFalse(search.readsSettingsKey(clinit, "some_other_key"))
    }

    @Test
    fun `a decoy switch on another field does not make a second gate class`() {
        val key = "a_settings_key"
        val realType = "LX/RealGate;"
        val realClinit = method("<clinit>", emptyList(), "V", 1, owner = realType).apply {
            addInstructionsWithLabels(0, "const-string v0, \"$key\"\nreturn-void")
        }
        val realGate = lazyRead(unwraps = true, owner = realType)
        val realClass = classDef(realType, realClinit, realGate)

        val decoyType = "LX/DecoyGate;"
        val decoyClinit = method("<clinit>", emptyList(), "V", 1, owner = decoyType).apply {
            addInstructionsWithLabels(
                0,
                """
                    const/4 v0, 0x7
                    invoke-static { v0 }, $group->get${'$'}arr${'$'}(I)Ljava/lang/Object;
                    return-void
                """,
            )
        }
        val decoyClass = classDef(
            decoyType,
            decoyClinit,
            lazyRead(unwraps = true, owner = decoyType, name = "LIZ"),
            lazyRead(unwraps = true, owner = decoyType, name = "LIZIZ"),
        )
        val decoyDispatcher = dispatcher(
            "invoke",
            firstKey = 7,
            targets = listOf("invoke\$7"),
            fieldName = "\$u",
        )
        val groupDef = group(decoyDispatcher, body("invoke\$7", key))
        val search = LazyAbGateSearch { type -> if (type == group) groupDef else null }
        val gate = { method: Method, _: ClassDef -> method.returnType == "Z" && method.isLazyAbRead() }

        val (foundClass, foundMethod) = search.find("What", key, gate) { accept ->
            accept(realClass)
            accept(decoyClass)
        }
        assertEquals(realType, foundClass.type)
        assertEquals(realGate.name, foundMethod.name)
        assertFalse(decoyDispatcher.dispatchesOnIndex())
    }

    @Test
    fun `the key is found in any no-argument method of a lambda class, not only the first`() {
        // Kotlin adds a bridge invoke()Object beside invoke()String, and R8 can list the bridge
        // first. The bridge holds no string.
        val lambda = "LX/0abc;"
        val bridge = method("invoke", emptyList(), "Ljava/lang/Object;", 1, owner = lambda).apply {
            addInstructionsWithLabels(
                0,
                """
                    const/4 v0, 0x0
                    return-object v0
                """,
            )
        }
        val real = method("invoke", emptyList(), "Ljava/lang/String;", 1, owner = lambda).apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "a_settings_key"
                    return-object v0
                """,
            )
        }
        val lambdaDef = classDef(lambda, bridge, real)
        val clinit = method("<clinit>", emptyList(), "V", 1, owner = "LX/0lGf;").apply {
            addInstructionsWithLabels(
                0,
                """
                    new-instance v0, $lambda
                    return-void
                """,
            )
        }
        val search = LazyAbGateSearch { type -> if (type == lambda) lambdaDef else null }

        assertTrue(search.readsSettingsKey(clinit, "a_settings_key"))
    }

    @Test
    fun `only a body that opens on the number is a dispatcher`() {
        val entry = dispatcher("invoke", firstKey = 1, targets = listOf("invoke\$1"))
        assertTrue(entry.dispatchesOnIndex())
        assertFalse(body("invoke\$1").dispatchesOnIndex())
        assertFalse(
            dispatcher(
                "invoke",
                firstKey = 1,
                targets = listOf("invoke\$1"),
                fieldOwner = "LX/AnotherGroup;",
            ).dispatchesOnIndex(),
        )
        assertFalse(
            dispatcher(
                "invoke",
                firstKey = 1,
                targets = listOf("invoke\$1"),
                fieldName = "\$u",
            ).dispatchesOnIndex(),
        )
    }

    @Test
    fun `a getter is only the lazy read when it unwraps the number it holds`() {
        assertTrue(lazyRead(unwraps = true).isLazyAbRead())
        assertFalse(lazyRead(unwraps = false).isLazyAbRead())
    }

    /** `iget $t; packed-switch` onto one call per key, which is what the group's entry point is. */
    private fun dispatcher(
        name: String,
        firstKey: Int,
        targets: List<String>,
        parameters: List<String> = emptyList(),
        fieldOwner: String = group,
        fieldName: String = "\$t",
    ): MutableMethod {
        val cases = targets.mapIndexed { index, target ->
            """
                :case_$index
                invoke-static { v1 }, $group->$target($group)Ljava/lang/Object;
                move-result-object v0
                return-object v0
            """.trimIndent()
        }.joinToString("\n")
        val labels = targets.indices.joinToString("\n") { "        :case_$it" }
        return method(name, parameters, "Ljava/lang/Object;", 2 + parameters.size).apply {
            addInstructionsWithLabels(
                0,
                """
                    iget v0, v1, $fieldOwner->$fieldName:I
                    packed-switch v0, :switch_data
                    const/4 v0, 0x0
                    return-object v0
                    $cases
                    :switch_data
                    .packed-switch $firstKey
                    $labels
                    .end packed-switch
                """,
            )
        }
    }

    @Test
    fun `a gate that asks a static helper on its own class for the read is still the gate`() {
        // 46.9.3 splits the comment sort gate: LIZ(Aweme)Z asks LIZIZ()Z on the same class and
        // the lazy read lives there. The read is followed one level, on the owner only: the
        // same call made from some other class is that class's business, not this gate.
        val owner = "LX/0Ey4;"
        val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        val helper = lazyRead(unwraps = true, owner = owner, name = "LIZIZ")
        val asksHelper = """
            invoke-static {}, $owner->LIZIZ()Z
            move-result v0
            return v0
        """
        val gate = method("LIZ", listOf(aweme), "Z", 2, owner = owner).apply {
            addInstructionsWithLabels(0, asksHelper)
        }
        val elsewhere = method("LIZ", listOf(aweme), "Z", 2, owner = "LX/0Ey6;").apply {
            addInstructionsWithLabels(0, asksHelper)
        }
        val holder = classDef(owner, helper, gate)

        assertFalse(gate.isLazyAbRead())
        assertTrue(gate.readsLazyAb(holder))
        assertTrue(helper.readsLazyAb(holder))
        assertFalse(elsewhere.readsLazyAb(classDef("LX/0Ey6;", elsewhere)))
    }

    private fun body(name: String, key: String = "a_settings_key") =
        method(name, listOf(group), "Ljava/lang/Object;", 2).apply {
            addInstructionsWithLabels(
                0,
                """
                    const-string v0, "$key"
                    return-object v0
                """,
            )
        }

    private fun lazyRead(
        unwraps: Boolean,
        owner: String = group,
        name: String = "LIZ",
    ) = method(name, emptyList(), "Z", 2, owner = owner).apply {
        val unwrap = if (unwraps) {
            "invoke-virtual { v0 }, Ljava/lang/Number;->intValue()I\nmove-result v0"
        } else {
            "const/4 v0, 0x1"
        }
        addInstructionsWithLabels(
            0,
            """
                sget-object v0, $group->LIZ:LX/01xP;
                invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Ljava/lang/Number;
                $unwrap
                return v0
            """,
        )
    }

    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        owner: String = group,
    ) =
        MutableMethod(
            ImmutableMethod(
                owner,
                name,
                parameters.map { ImmutableMethodParameter(it, null, null) },
                returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )

    private fun group(vararg methods: Method): ClassDef = classDef(group, *methods)

    private fun classDef(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        methods.toList(),
    )
}
