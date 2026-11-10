/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.popups

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SLOT_VIEW_MODEL =
    "Lcom/ss/android/ugc/aweme/compliance/protection/timelock/ui/viewmodel/SleepHourComponentViewModel;"
private const val FAMILY_PAIRING =
    "Lcom/ss/android/ugc/aweme/compliance/protection/familypairing/FamilyPairingManagerV2;"
private const val SPI = "Lcom/ss/android/ugc/aweme/framework/services/PluggableExtentionKt;"
private const val PROTECTION_SERVICE = "Lcom/ss/android/ugc/aweme/compliance/api/services/teenmode/IProtectionService;"
private const val USER_DETAILS = "Lcom/ss/android/ugc/aweme/compliance/api/model/UserDetailsInfoBean;"

/**
 * TikTok's wind-down, breathing exercise and daily limit screens. The check every trigger is asked
 * is found the way the patch finds it and held to every declared build, the hook is applied to each
 * real trigger, and the members WindDownScreens reads by reflection are held to their shapes.
 */
class WindDownScreensAnchorsTest {
    @Test
    fun `each declared build has the three triggers and one check the slot asks as it's built`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val sites = windDownSites { app[it] }
            assertEquals(version, WIND_DOWN_TRIGGERS, sites.triggers.map { it.definingClass })

            // The check is the one the slot and its view model ask before they prepare or switch to
            // a trigger. The other yes-or-no is "show it now", which neither of them ever asks.
            val other = app.getValue(SLEEP_HOUR_TRIGGER).methods
                .single { it.parameterTypes.isEmpty() && it.returnType == "Z" && it.name != sites.check }.name
            fun calls(owner: String, name: String) = app.getValue(owner).methods.sumOf { method ->
                method.implementation?.instructions?.toList().orEmpty().count { instruction ->
                    instruction.getReference<MethodReference>()?.let {
                        it.definingClass == SLEEP_HOUR_TRIGGER && it.name == name
                    } == true
                }
            }
            assertTrue("$version: the view model asks the check", calls(SLOT_VIEW_MODEL, sites.check) >= 2)
            assertEquals("$version: the slot asks show-it-now itself", 0, calls(SLEEP_HOUR_SLOT, other))
            assertEquals("$version: the view model asks show-it-now", 0, calls(SLOT_VIEW_MODEL, other))

            for (trigger in sites.triggers) checkHook(version, trigger)
        }
    }

    private fun checkHook(version: String, trigger: Method) {
        val method = MutableMethod(trigger)
        val returns = returnRegisters(trigger)
        val size = method.implementation!!.instructions.count()
        method.keepBackWindDown()
        val after = method.implementation!!.instructions.toList()
        assertEquals("$version: ${trigger.definingClass}", size + 2 * returns.size, after.size)
        val self = method.implementation!!.registerCount - 1
        val hooked = after.withIndex().filter { it.value.opcode == Opcode.RETURN }
        assertEquals(returns.size, hooked.size)
        for ((index, instruction) in hooked) {
            val register = (instruction as OneRegisterInstruction).registerA
            val call = after[index - 2]
            assertEquals(Opcode.INVOKE_STATIC, call.opcode)
            val target = call.getReference<MethodReference>()!!
            assertEquals(WIND_DOWN_SCREENS, target.definingClass)
            assertEquals("eligible", target.name)
            assertEquals(listOf("Ljava/lang/Object;", "Z"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("Z", target.returnType)
            call as FiveRegisterInstruction
            assertEquals(2, call.registerCount)
            assertEquals("$version: the trigger handed over", self, call.registerC)
            assertEquals("$version: the answer handed over", register, call.registerD)
            val result = after[index - 1] as OneRegisterInstruction
            assertEquals(Opcode.MOVE_RESULT, after[index - 1].opcode)
            assertEquals(register, result.registerA)
        }
    }

    @Test
    fun `each declared build has the members the extension reads by reflection`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)

            // Family Pairing: one static instance of itself and one role getter, an enum with the
            // NONE, PARENT and CHILD constants the extension reads by name.
            val pairing = app.getValue(FAMILY_PAIRING)
            assertEquals("$version: own instance", 1,
                pairing.fields.count { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == FAMILY_PAIRING })
            val roles = pairing.methods.filter { method ->
                AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.parameterTypes.isEmpty() && app[method.returnType]?.let { role ->
                        role.superclass == "Ljava/lang/Enum;" && role.fields.any {
                            it.name == "CHILD" && it.type == role.type && AccessFlags.STATIC.isSet(it.accessFlags)
                        }
                    } == true
            }
            assertEquals("$version: role getters", 1, roles.size)
            val constants = app.getValue(roles.single().returnType).fields.map { it.name }
            assertTrue("$version: $constants", constants.containsAll(listOf("NONE", "PARENT", "CHILD")))

            // The protection service's one getter for the saved user details, and how it's found.
            assertEquals("$version: details getters", 1, app.getValue(PROTECTION_SERVICE).methods.count {
                it.parameterTypes.isEmpty() && it.returnType == USER_DETAILS
            })
            assertTrue("$version: pluggableSpi(Class)", app.getValue(SPI).methods.any {
                it.name == "pluggableSpi" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                    AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Class;") &&
                    it.returnType == "Ljava/lang/Object;"
            })
            val minor = app.getValue(USER_DETAILS).fields.single { it.name == "isMinor" }
            assertEquals("Ljava/lang/Boolean;", minor.type)
            assertTrue(AccessFlags.PUBLIC.isSet(minor.accessFlags))
        }
    }

    @Test
    fun `a slot asking both checks as it's built, or a trigger in high registers or reusing p0, leaves the screens out`() {
        val apk = Fixtures.declared().firstOrNull { it.isFile } ?: return
        val app = load(apk)
        val checks = app.getValue(SLEEP_HOUR_TRIGGER).methods
            .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }.map { it.name }
        val slot = app.getValue(SLEEP_HOUR_SLOT)
        val asksBoth = MutableMethod(
            ImmutableMethod(
                SLEEP_HOUR_SLOT, "onViewCreated", listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
                "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(3, emptyList(), null, null),
            ),
        ).apply {
            addInstructionsWithLabels(0, """
                const/4 v0, 0x0
                invoke-interface {v0}, $SLEEP_HOUR_TRIGGER->${checks[0]}()Z
                invoke-interface {v0}, $SLEEP_HOUR_TRIGGER->${checks[1]}()Z
                return-void
            """)
        }
        val broken = slot.withMethods(slot.methods.filterNot { it.name == "onViewCreated" } + asksBoth)
        val both = assertThrows(PatchException::class.java) {
            windDownSites { if (it == SLEEP_HOUR_SLOT) broken else app[it] }
        }
        assertTrue(both.message, both.message!!.contains("2 of their checks"))

        val check = windDownCheckName { app[it] }
        val type = WIND_DOWN_TRIGGERS.first()
        val trigger = app.getValue(type)
        val high = trigger.withMethods(trigger.methods.map { method ->
            if (method.name != check || method.parameterTypes.isNotEmpty()) return@map method
            val code = method.implementation!!
            ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType, method.accessFlags,
                method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(17, code.instructions, code.tryBlocks, code.debugItems),
            )
        })
        val registers = assertThrows(PatchException::class.java) {
            windDownSites { if (it == type) high else app[it] }
        }
        assertTrue(registers.message, registers.message!!.contains("registers too high"))

        // A check that writes over p0 would hand the hook something other than the trigger.
        val reusesSelf = trigger.withMethods(trigger.methods.map { method ->
            if (method.name != check || method.parameterTypes.isNotEmpty()) return@map method
            MutableMethod(method).apply { addInstructionsWithLabels(0, "const/4 p0, 0x0") }
        })
        val self = assertThrows(PatchException::class.java) {
            windDownSites { if (it == type) reusesSelf else app[it] }
        }
        assertTrue(self.message, self.message!!.contains("reuses p0"))

        val missing = assertThrows(PatchException::class.java) {
            windDownSites { if (it == type) null else app[it] }
        }
        assertTrue(missing.message, missing.message!!.contains("there is no"))
    }

    private fun ClassDef.withMethods(methods: Iterable<Method>) = ImmutableClassDef(
        type, accessFlags, superclass, interfaces, sourceFile, annotations, fields, methods,
    )

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
