/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The obfuscated class name the OpenDebug lambdas carried in every TikTok build until now. */
private const val TIKTOK_LAMBDA_PREFIX = "Lkotlin/jvm/internal/AwS"

/**
 * The Settings patch finds the OpenDebug row's click wrapper and its Function2 lambda by their
 * constructors' parameters, not by TikTok's obfuscated class name, so it also works on PineDrama
 * (#156). On TikTok it has to land on the same classes the name check used to pick.
 */
class OpenDebugClickWrapperFixtureTest {
    @Test
    fun `the click wrapper and the Function2 lambda are the ones the old name check found`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk)
            val classes: List<ClassDef> = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes
            }
            val defaultState = classes.single { it.type.endsWith("/OpenDebugCellVM;") }
                .methods.single { it.name == "defaultState" }
            val stateClass = openDebugStateClass(defaultState)
            val compose = classes.flatMap { it.methods }.filter { isOpenDebugRowComposeMethod(it, stateClass) }
            assertEquals("OpenDebug row compose methods", 1, compose.size)

            val wrapper = openDebugClickWrapper(compose.single(), stateClass)
            val wanted = listOf(stateClass, "Landroid/content/Context;", "I")
            val candidates = constructorsCalledBy(compose.single()).filter { it.paramTypes() == wanted }
            assertEquals("constructors taking (state, Context, I) in the compose method", 1, candidates.size)
            val byName = candidates.single { it.definingClass.startsWith(TIKTOK_LAMBDA_PREFIX) }
            assertEquals("click wrapper class", byName.definingClass, wrapper.className)
            assertTrue("click wrapper invoke name ${wrapper.invokeName}", wrapper.invokeName.matches(Regex("invoke\\$\\d+")))
            val handlers = classes.single { it.type == wrapper.className }.methods.filter {
                it.name == wrapper.invokeName && it.paramTypes() == listOf(wrapper.className)
            }
            assertEquals("${wrapper.invokeName} methods in ${wrapper.className}", 1, handlers.size)

            val lambda = openDebugFunction2LambdaClass(defaultState)
            val oldLambda = constructorsCalledBy(defaultState).first {
                it.definingClass.startsWith(TIKTOK_LAMBDA_PREFIX) &&
                    it.parameterTypes.firstOrNull()?.toString() == defaultState.definingClass
            }
            assertEquals("Function2 lambda class", oldLambda.definingClass, lambda)
        }
    }

    private fun constructorsCalledBy(method: Method): List<MethodReference> =
        method.implementation!!.instructions.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.INVOKE_DIRECT) return@mapNotNull null
            ((instruction as ReferenceInstruction).reference as? MethodReference)?.takeIf { it.name == "<init>" }
        }

    private fun MethodReference.paramTypes() = parameterTypes.map(CharSequence::toString)
}
