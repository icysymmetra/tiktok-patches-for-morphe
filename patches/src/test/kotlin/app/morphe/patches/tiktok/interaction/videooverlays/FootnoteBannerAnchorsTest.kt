/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val NOTE = "Lcom/ss/android/ugc/aweme/feed/model/FootNoteInfo;"
private const val SERVICE = "Lcom/ss/android/ugc/aweme/foonote/service/IFootNoteService;"
private const val CONTROLS = "Lapp/morphe/extension/tiktok/feed/FeedOverlayControls;"

/**
 * What Hide Footnotes hooks, held to each declared build: the one static (Aweme)Z that asks a
 * video for its FootNoteInfo, reads the note's banner and asks the Footnotes service, which the
 * banner's two components (the slot that draws it and the trigger that decides whether to) both
 * call before they build anything. The guard is applied to the real method, so the question
 * lands in a local register and TikTok's own body follows it untouched.
 */
class FootnoteBannerAnchorsTest {
    @Test
    fun `each declared build has one footnote banner gate and the fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = methodsOf(apk) { _, method -> isFootnoteBannerGate(method) }
            val byFingerprint = methodsOf(apk) { classDef, method -> FootnoteBannerGateFingerprint.takes(method, classDef) }
            assertEquals("$version: gates ${byShape.map { it.first.type }}", 1, byShape.size)
            assertEquals(
                "$version: the fingerprint took ${byFingerprint.map { it.first.type }}",
                byShape.map { it.first.type to it.second.name },
                byFingerprint.map { it.first.type to it.second.name },
            )
            val gate = byShape.single().second
            assertTrue("$version: the gate is not static", AccessFlags.STATIC.isSet(gate.accessFlags))
            // One parameter register, so the guard's v0 is a local when the frame is any bigger.
            assertTrue(
                "$version: ${gate.implementation!!.registerCount} registers leave no local for the guard",
                gate.implementation!!.registerCount >= 2,
            )
        }
    }

    @Test
    fun `the gate is called by the banner slot and its trigger and by nothing else`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val gate = methodsOf(apk) { _, method -> isFootnoteBannerGate(method) }.single().second
            val callers = methodsOf(apk) { _, method ->
                method.implementation?.instructions?.any { instruction ->
                    instruction.getReference<MethodReference>()?.let {
                        it.definingClass == gate.definingClass && it.name == gate.name && it.returnType == "Z" &&
                            it.parameterTypes.map(CharSequence::toString) == listOf(AWEME)
                    } == true
                } == true
            }
            assertEquals(
                "$version: callers ${callers.map { it.first.type + "->" + it.second.name }}",
                listOf("TnSBannerAssem;", "TnsBannerAssemTrigger;"),
                callers.map { it.first.type.substringAfterLast('/') }.sorted(),
            )
        }
    }

    @Test
    fun `the guard goes in front of the real gate and leaves its body as it was`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val native = methodsOf(apk) { _, method -> isFootnoteBannerGate(method) }.single().second
            val before = native.implementation!!.instructions.toList()

            val guarded = MutableMethod(native)
            guarded.hideFootnoteBanner("Hide video overlays")
            val after = guarded.implementation!!.instructions.toList()

            assertEquals(
                "$version: the guard",
                listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN),
                after.take(5).map { it.opcode },
            )
            val call = after[0].getReference<MethodReference>()!!
            assertEquals(CONTROLS, call.definingClass)
            assertEquals("shouldHideFootnotes", call.name)
            assertEquals("Z", call.returnType)
            assertTrue(call.parameterTypes.isEmpty())
            // The answer is read from and returned out of the same local, never a parameter.
            assertEquals(0, (after[1] as OneRegisterInstruction).registerA)
            assertEquals(0, (after[4] as OneRegisterInstruction).registerA)
            // TikTok's own instructions follow the guard, in their own order.
            assertEquals(before.map { it.opcode }, after.takeLast(before.size).map { it.opcode })
        }
    }

    @Test
    fun `only a static gate on an Aweme that reads the note's banner and the service is taken`() {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val asksVideo = listOf("invoke-virtual {p0}, $AWEME->getFootNoteInfo()$NOTE", "move-result-object v0")
        val readsBanner = listOf("iget-object v0, v0, $NOTE->banner:Lcom/ss/android/ugc/aweme/feed/model/tuc/TUCBannerData;")
        val namesService = listOf("const-class v1, $SERVICE")
        fun gate(
            lines: List<String>,
            flags: Int = static,
            parameters: List<String> = listOf(AWEME),
            returns: String = "Z",
        ) = MutableMethod(
            ImmutableMethod(
                "Lfixture/Gate;", "LIZ", parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags,
                null, null, ImmutableMethodImplementation(7, emptyList(), null, null),
            ),
        ).apply {
            addInstructions(0, (lines + "const/4 v2, 0x0" + "return v2").joinToString("\n"))
        }
        val reads = asksVideo + readsBanner + namesService

        assertTrue(isFootnoteBannerGate(gate(reads)))
        // Each of the three reads is needed on its own: a method with only two is another gate.
        assertFalse(isFootnoteBannerGate(gate(readsBanner + namesService)))
        assertFalse(isFootnoteBannerGate(gate(asksVideo + namesService)))
        assertFalse(isFootnoteBannerGate(gate(asksVideo + readsBanner)))
        // And the signature: static, answering Z, taking the one Aweme.
        assertFalse(isFootnoteBannerGate(gate(reads, flags = AccessFlags.PUBLIC.value)))
        assertFalse(isFootnoteBannerGate(gate(reads, returns = "V")))
        assertFalse(isFootnoteBannerGate(gate(reads, parameters = listOf("Ljava/lang/Object;"))))
        assertFalse(isFootnoteBannerGate(gate(reads, parameters = listOf(AWEME, "I"))))
        // A method with no code is not one.
        assertFalse(isFootnoteBannerGate(ImmutableMethod(
            "Lfixture/Gate;", "LIZ", listOf(ImmutableMethodParameter(AWEME, null, null)), "Z", static, null, null, null,
        )))
    }

    @Test
    fun `a gate with no local register for the answer stops the patch`() {
        val tight = MutableMethod(
            ImmutableMethod(
                "Lfixture/Gate;", "LIZ", listOf(ImmutableMethodParameter(AWEME, null, null)), "Z",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    1, listOf(ImmutableInstruction11x(Opcode.RETURN, 0)), null, null,
                ),
            ),
        )
        val before = tight.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { tight.hideFootnoteBanner("Hide video overlays") }
        assertEquals(before, tight.implementation!!.instructions.toList())
    }

    /** Every method of one build the filter takes, walked once and never held (the dex has millions). */
    private fun methodsOf(apk: File, accept: (ClassDef, Method) -> Boolean): List<Pair<ClassDef, Method>> {
        val found = mutableListOf<Pair<ClassDef, Method>>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) {
                    if (accept(classDef, method)) found += classDef to method
                }
            }
        }
        return found
    }
}
