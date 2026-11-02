/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import app.morphe.patches.tiktok.interaction.gesture.VIDEO_SHARE_ASSEM
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Native-only tests: the Share button press, the item it shares, and the views it listens to. */
class ShareButtonTapAnchorsTest {
    private data class Found(val owner: ClassDef, val clicks: List<Method>, val created: List<Method>)

    companion object {
        private val cache = mutableMapOf<String, Found>()
        private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
    }

    @Test fun `the press reads the cell's own item the way TikTok's sheet does`() {
        Fixtures.forEachDeclared { apk ->
            val click = find(apk).clicks.single()
            val read = click.shareItemRead()
            assertNotNull("the press no longer reads its cell's item", read)
            assertEquals("Lcom/bytedance/assem/arch/reused/ReusedAssem;", read!!.state.definingClass)
            assertEquals("Ljava/lang/Object;", read.value.type)
            val code = click.implementation!!.instructions.toList()
            val cast = code.indexOfFirst {
                it.opcode == Opcode.CHECK_CAST && it.getReference<TypeReference>()?.type == VIDEO_ITEM_PARAMS
            }
            assertEquals("the cast item is where the post comes from", "getAweme",
                code[cast + 1].getReference<MethodReference>()?.name)
            // TikTok reads the same pair again where it builds the sheet, so the hook's video is the sheet's.
            val stateReads = code.count { it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>() == read.state }
            assertTrue("TikTok's sheet reads the item another way", stateReads >= 2)
        }
    }

    @Test fun `the press has a local for the answer and names its view in a five register call`() {
        Fixtures.forEachDeclared { apk ->
            val body = find(apk).clicks.single().implementation!!
            assertTrue(body.registerCount <= 16)
            assertTrue(body.registerCount - 2 >= 1)
        }
    }

    @Test fun `the assem listens to its content view and its root, and only those`() {
        Fixtures.forEachDeclared { apk ->
            val created = find(apk).created.single()
            val sites = created.clickListenerSites()
            assertEquals(2, sites.size)
            val code = created.implementation!!.instructions.toList()
            // Both name the same listener register, the assem's copy of this.
            assertEquals(1, sites.map { (code[it] as FiveRegisterInstruction).registerC }.toSet().size)
            assertNull("the item read matches a method that has none", created.shareItemRead())
        }
    }

    private fun find(apk: File): Found = synchronized(cache) {
        cache.getOrPut(apk.absolutePath) {
            var owner: ClassDef? = null
            val clicks = mutableListOf<Method>()
            val created = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type != VIDEO_SHARE_ASSEM) continue
                owner = classDef
                for (method in classDef.methods) {
                    if (ShareButtonClickFingerprint.takes(method, classDef)) clicks += method
                    if (ShareButtonViewCreatedFingerprint.takes(method, classDef)) created += method
                }
            }
            Found(owner ?: error("$VIDEO_SHARE_ASSEM is missing from ${apk.name}"), clicks, created)
        }
    }
}
