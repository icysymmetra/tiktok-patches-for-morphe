package app.morphe

import app.morphe.patches.tiktok.interaction.searchfeatures.NonPersonalizedSearchGateFingerprint
import app.morphe.patches.tiktok.interaction.speed.LongPressSpeedUpEnableFingerprint
import app.morphe.patches.tiktok.privacy.SelfPackageInfoCacheFingerprint
import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Patcher2GateFingerprintTest {
    @Test
    fun `the native package cache excludes the injected fallback with the same signature`() {
        val packageInfo = "Landroid/content/pm/PackageInfo;"
        val packageManager = "Landroid/content/pm/PackageManager;"
        fun matchesCache(type: String): Boolean {
            val method = ImmutableMethod(type, "cache", listOf(packageManager, "Ljava/lang/String;", "I")
                .map { com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(it, null, null) },
                packageInfo, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(4, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 1, 2, 3, 0, 0,
                        ImmutableMethodReference(packageManager, "getPackageInfo", listOf("Ljava/lang/String;", "I"), packageInfo)),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                    ImmutableInstruction21c(Opcode.SPUT_OBJECT, 0, ImmutableFieldReference(type, "cached", packageInfo)),
                    ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ), null, null))
            val owner = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
                emptyList(), null, emptySet(), emptyList(), listOf(method))
            return SelfPackageInfoCacheFingerprint.takes(method, owner)
        }
        assertTrue(matchesCache("LNativeCache;"))
        assertFalse(matchesCache("Lapp/morphe/extension/tiktok/privacy/StoreIdentity;"))
    }

    private fun matches(fingerprint: Fingerprint, name: String, returnType: String, key: String,
                        static: Boolean, lookupParameters: List<String>?): Boolean {
        val body = mutableListOf<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key)))
        lookupParameters?.let {
            body += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 1, 2, 3, 0,
                ImmutableMethodReference("LNativeLookup;", "read", it, if (returnType == "V") "Z" else "I"))
        }
        body += if (returnType == "V") ImmutableInstruction10x(Opcode.RETURN_VOID)
                else ImmutableInstruction11x(Opcode.RETURN, 0)
        val method = ImmutableMethod("LFixture;", name, emptyList(), returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(5, body, null, null))
        val owner = ImmutableClassDef("LFixture;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(), listOf(method))
        return fingerprint.takes(method, owner)
    }

    @Test
    fun `a Lab catalog key is not the native speed enable experiment read`() {
        val parameters = listOf("I", "Ljava/lang/String;", "Z", "Z")
        assertTrue(matches(LongPressSpeedUpEnableFingerprint, "<clinit>", "V", "long_press_speed_up_enable", true, parameters))
        assertFalse(matches(LongPressSpeedUpEnableFingerprint, "<clinit>", "V", "long_press_speed_up_enable", true, null))
    }

    @Test
    fun `the search gate is a static exact experiment read rather than a widget or second gate`() {
        val parameters = listOf("I", "I", "Ljava/lang/String;", "Z")
        assertTrue(matches(NonPersonalizedSearchGateFingerprint, "gate", "Z", "enable_non_personalized_search", true, parameters))
        assertFalse(matches(NonPersonalizedSearchGateFingerprint, "gate", "Z", "enable_non_personalized_search", false, parameters))
        for (key in listOf("enable_non_personalized_search_widget", "enable_non_personalized_search_second")) {
            assertFalse(matches(NonPersonalizedSearchGateFingerprint, "gate", "Z", key, true, parameters))
        }
    }
}
