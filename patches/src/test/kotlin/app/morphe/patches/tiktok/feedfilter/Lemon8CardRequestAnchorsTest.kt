package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Lemon8 promo card is stopped where its handler builds the card type requests. Each declared
 * build has exactly one such method keyed by the big card's not-interested time, it returns the
 * list the guard replaces, and its frame has the register the guard writes.
 */
class Lemon8CardRequestAnchorsTest {
    private val insertData = "Lcom/ss/android/ugc/feed/platform/cardinsert/data/FeedCardInsertData;"

    @Test
    fun `each declared build has one guardable Lemon8 request builder`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (method.implementation != null && Lemon8CardRequestFingerprint.takes(method, classDef)) {
                            taken += classDef to method
                        }
                    }
                }
            }
            assertEquals("${apk.name}: ${taken.map { it.first.type }}", 1, taken.size)
            val (classDef, method) = taken.single()
            assertEquals(Lemon8CardRequestFingerprint.javaClass.simpleName, "Ljava/util/List;", method.returnType)
            assertEquals(insertData, method.parameterTypes[1].toString())
            assertTrue("${apk.name}: instance method on the handler, p0 is the handler", !AccessFlags.STATIC.isSet(method.accessFlags))
            assertTrue("${apk.name}: ${classDef.type} frame has no local for the guard", method.implementation!!.registerCount > method.parameterTypes.size + 1)
        }
    }
}
