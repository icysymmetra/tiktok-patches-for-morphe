/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.onboarding

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The steps FirstLaunchSetup.SKIPPED names. Kept here too, so a step that leaves TikTok shows up. */
internal val SKIPPED = setOf(
    "interest_list", "interest_sub_tag", "content_language", "gender_selection",
    "follow_trending_creators", "swipe_up", "push_auth_preposition_page",
)

/**
 * Every step id the declared builds set up, by build. A build that adds one fails here, so
 * somebody reads what the new step is (consent or taste) before it's left showing by default.
 */
private val STEP_IDS = setOf(
    "ad_choice", "ad_subscription", "age_gate", "consent_box_page", "consent_box_page_hu",
    "content_language", "deep_link", "feed_refresh", "follow_trending_creators", "free_trial",
    "gender_selection", "interest_list", "interest_sub_tag", "login", "m2_one_tap_login",
    "privacy_for_teens", "private_account", "push_auth_preposition_page", "push_page_advance",
    "push_popup_background", "server_delay", "skippable_login", "slogan_consent_box_page",
    "slogan_page", "store_age_check", "swipe_up",
)

/**
 * Skip first-launch setup answers no at the entry of the setup's step decision. Each declared
 * build has one such method, it reads the step's id through getId() on p0, the hook lands on the
 * real method as written, and every step id the build carries is one this patch has looked at.
 */
class SetupStepDecisionAnchorsTest {
    @Test
    fun `each declared build has one step decision that reads the step's id`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val taken = app.values.flatMap { classDef ->
                classDef.methods.filter { it.implementation != null && SetupStepDecisionFingerprint.takes(it, classDef) }
            }
            assertEquals("$version: ${taken.map { it.definingClass }}", 1, taken.size)
            val decision = taken.single()
            val step = setupStepType(decision)
            val stepClass = app.getValue(step)
            assertTrue("$version: $step is an interface", AccessFlags.INTERFACE.isSet(stepClass.accessFlags))
            assertTrue("$version: three locals beside the step", decision.implementation!!.registerCount >= 4)

            // Every answer is a Pair of a reason and a Boolean, the one this hook builds.
            val pairs = decision.implementation!!.instructions.count {
                it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == "Lkotlin/Pair;"
            }
            assertTrue("$version: pairs built", pairs >= 4)
            // TikTok's own skip is the answer the hook gives: the reason first, then FALSE.
            val body = decision.implementation!!.instructions.toList()
            val reason = body.indices.single { body[it].getReference<StringReference>()?.string == "ignore_by_deeplink" }
            val pair = body.drop(reason).first { it.opcode == Opcode.INVOKE_DIRECT } as FiveRegisterInstruction
            assertEquals("$version: the reason goes first", (body[reason] as OneRegisterInstruction).registerA, pair.registerD)
            val shown = body.take(reason).last {
                it.opcode == Opcode.SGET_OBJECT && (it as OneRegisterInstruction).registerA == pair.registerE
            }.getReference<FieldReference>()!!
            assertEquals("$version: the deep link skip answers FALSE", "Ljava/lang/Boolean;.FALSE", "${shown.definingClass}.${shown.name}")

            checkHook(version, decision, step)

            val ids = stepIds(app)
            assertEquals("$version: setup step ids", STEP_IDS.sorted(), ids.sorted())
            assertTrue("$version: every skipped step exists", SKIPPED.all { it in ids })
        }
    }

    private fun checkHook(version: String, decision: Method, step: String) {
        val method = MutableMethod(decision)
        val size = method.implementation!!.instructions.count()
        val self = method.implementation!!.registerCount - 1
        method.skipSetupSteps(step)
        val after = method.implementation!!.instructions.toList()
        assertEquals(size + 11, after.size)
        assertEquals(
            "$version: the hook",
            listOf(
                Opcode.INVOKE_INTERFACE_RANGE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                Opcode.IF_EQZ, Opcode.NEW_INSTANCE, Opcode.CONST_STRING, Opcode.SGET_OBJECT, Opcode.INVOKE_DIRECT,
                Opcode.RETURN_OBJECT, Opcode.NOP,
            ),
            after.take(11).map { it.opcode },
        )
        val read = after[0] as RegisterRangeInstruction
        assertEquals("$version: the id is read off the step", self, read.startRegister)
        assertEquals(1, read.registerCount)
        assertEquals("getId", after[0].getReference<MethodReference>()!!.name)
        val ask = after[2].getReference<MethodReference>()!!
        assertEquals(FIRST_LAUNCH_SETUP_EXTENSION, ask.definingClass)
        assertEquals("skipStep", ask.name)
        assertEquals("local_rule", after[6].getReference<StringReference>()!!.string)
        assertEquals("FALSE", after[7].getReference<FieldReference>()!!.name)
        // Nothing the hook writes lands on the step.
        assertTrue(after.take(11).none { (it as? OneRegisterInstruction)?.registerA == self && it.opcode.setsRegister() })
        // TikTok's own first check follows the hook untouched.
        assertEquals(decision.implementation!!.instructions.first().opcode, after[11].opcode)
    }

    @Test
    fun `a decision that reads the id off another register leaves the patch out`() {
        val apk = Fixtures.declared().firstOrNull { it.isFile } ?: return
        val app = load(apk)
        val decision = app.values.flatMap { classDef ->
            classDef.methods.filter { it.implementation != null && SetupStepDecisionFingerprint.takes(it, classDef) }
        }.single()
        val step = decision.parameterTypes.single().toString()
        // The same method with its own getId() calls dropped and one made on a local instead.
        val broken = MutableMethod(decision).apply {
            val reads = implementation!!.instructions.withIndex()
                .filter { (_, it) -> it.getReference<MethodReference>()?.name == "getId" }.map { it.index }
            reads.reversed().forEach { implementation!!.removeInstruction(it) }
            addInstructionsWithLabels(0, "invoke-interface { v0 }, $step->getId()Ljava/lang/String;")
        }
        val failure = assertThrows(PatchException::class.java) { setupStepType(broken) }
        assertTrue(failure.message, failure.message!!.contains("doesn't read its step's id"))
    }

    /**
     * The id of each step provider: TikTok's providers share a base class whose constructor takes
     * (List, the step type enum, String), and each subclass's constructor passes its id first.
     */
    private fun stepIds(app: Map<String, ClassDef>): Set<String> {
        val stepTypes = app.values.filter { classDef ->
            classDef.superclass == "Ljava/lang/Enum;" && classDef.fields.any { it.name == "JOURNEY_SLOGAN_ID" }
        }.map { it.type }.toSet()
        val bases = app.values.filter { classDef ->
            classDef.methods.any { method ->
                val parameters = method.parameterTypes.map(CharSequence::toString)
                method.name == "<init>" && parameters.size == 3 && parameters[0] == "Ljava/util/List;" &&
                    parameters[1] in stepTypes && parameters[2] == "Ljava/lang/String;"
            }
        }.map { it.type }.toSet()
        assertTrue("a step provider base", bases.isNotEmpty())
        return app.values.filter { it.superclass in bases }.mapNotNull { provider ->
            provider.methods.filter { it.name == "<init>" }.firstNotNullOfOrNull { constructor ->
                constructor.implementation?.instructions?.firstNotNullOfOrNull { it.getReference<StringReference>()?.string }
            }
        }.toSet()
    }

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
