/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.ghostmode

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Ghost mode finds TikTok's play report sender by, held to each declared build (#39): one
 * static void method with the report's shape and its monitor string. Two would leave the guard
 * on whichever the patch picked; none would fail the patch, which the gate's fixture run shows,
 * but this names the reason.
 */
class StoryPlayStatsAnchorsTest {
    private val shape = listOf(
        "Ljava/lang/String;", "I", "Ljava/lang/String;", "I",
        "Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;", "Lkotlin/jvm/functions/Function1;",
    )

    @Test
    fun `each declared build has one play report sender with the shape Ghost mode guards`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val senders = mutableListOf<String>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.returnType != "V" || method.parameterTypes.map { it.toString() } != shape) continue
                        if (!AccessFlags.STATIC.isSet(method.accessFlags)) continue
                        val monitors = method.implementation?.instructions?.any {
                            it.opcode == Opcode.CONST_STRING &&
                                ((it as ReferenceInstruction).reference as StringReference).string == "aweme_stats_monitor"
                        } ?: false
                        if (monitors) senders += classDef.type + "->" + method.name
                    }
                }
            }
            assertEquals("play report senders: $senders", 1, senders.size)
        }
    }
}
