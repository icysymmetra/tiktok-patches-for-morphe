package app.morphe.patches.tiktok.misc.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * TabAbilityAssem exposes one plain getter per tab row: it runs the lazy "ensure" initializer and returns the
 * CopyOnWriteArrayList of tab models. The getter names are obfuscated and change per build
 * (W52/OA in TikTok 46.2.3, th2/KC in 47.1.4), so match on the shape and on the scene string logged by the initializer.
 */
private fun isTabModelListGetter(method: Method, classDef: ClassDef, sceneString: String): Boolean {
    if (!classDef.type.endsWith("/TabAbilityAssem;")) return false
    if (method.returnType != "Ljava/util/List;" || method.parameterTypes.isNotEmpty()) return false
    val instructions = method.implementation?.instructions?.toList() ?: return false
    if (instructions.size != 3) return false
    val initializer = instructions[0].getReference<MethodReference>()
        ?.takeIf { it.definingClass == classDef.type && it.parameterTypes.isEmpty() && it.returnType == "V" }
        ?: return false
    val initializerMethod = classDef.methods.firstOrNull { it.name == initializer.name && it.parameterTypes.isEmpty() }
        ?: return false
    return initializerMethod.implementation?.instructions?.any { instruction ->
        instruction.getReference<StringReference>()?.string == sceneString
    } == true
}

internal object TopTabModelListFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    custom = { method, classDef -> isTabModelListGetter(method, classDef, "scene_get_top_tab") },
)

internal object BottomTabModelListFingerprint : Fingerprint(
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    custom = { method, classDef -> isTabModelListGetter(method, classDef, "scene_get_bottom_tab") },
)
