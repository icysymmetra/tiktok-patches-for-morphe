/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.navigation.isTopTabLayoutConstructor
import app.morphe.patches.tiktok.misc.navigation.writes
import app.morphe.util.addInstructions
import app.morphe.util.findFreeRegister
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * TikTok's feed shows no logo. The other Hush packages open their settings from a long press on the
 * app's logo, and the nearest thing TikTok's home screen has is For You, the feed's name in the
 * strip of tabs across the top. TikTok gives that tab a tap (switch to it, or reload it when it's
 * already open) and nothing on a long press, so a held For You only acted as a tap.
 */

/** The ability that owns the strip of feed tabs across the top. Its constructor takes the strip. */
internal const val TOP_TAB_LAYOUT_ABILITY = "Lcom/ss/android/ugc/aweme/homepage/ui/view/tab/top/TopTabLayoutAbility;"

/** What gets each top tab once TikTok has bound it, with the tag of the tab it shows. */
internal const val TOP_TAB_HOOK =
    "Lapp/morphe/extension/tiktok/navigation/ForYouTabSettingsShortcut;->onTopTab(Landroid/view/View;Ljava/lang/String;)V"

private const val CONTEXT = "Landroid/content/Context;"
private const val TAG_GETTER = "tag"

/**
 * Where the strip finishes binding a top tab: [index] is the strip's return right after it binds
 * the tab's view, the view is in [tabView], [tabModel] is the parameter holding the tab model TikTok
 * read the tag from with [tagRead], and [tag] is a register free at [index] for the tag.
 */
internal class TopTabBindSite(
    val method: Method,
    val index: Int,
    val tabView: Int,
    val tabModel: Int,
    val tagRead: MethodReference,
    val tag: Int,
)

/** The one place to hook, or why this build has none ([site] null, [miss] says why). */
internal class TopTabBindLookup(val site: TopTabBindSite?, val miss: String?)

/** The strip of feed tabs across the top: the first parameter of TopTabLayoutAbility's constructor. */
internal fun topTabStripType(ability: ClassDef): String? =
    ability.methods.singleOrNull(::isTopTabLayoutConstructor)?.parameterTypes?.first()?.toString()

/**
 * Every place [strip] finishes binding a tab: a method that reads tag() from a tab model it was
 * handed, then calls a method with no arguments on the tab's view as the last thing before it
 * returns. The view is TikTok's top tab, the class it builds with the strip and a Context. In
 * 47.1.4 that's the strip's one tab factory, which every top tab goes through, new or reused.
 */
internal fun topTabBindSites(strip: ClassDef, classOf: (String) -> ClassDef?): List<TopTabBindSite> {
    val sites = mutableListOf<TopTabBindSite>()
    for (method in strip.methods) {
        val body = method.implementation?.instructions?.toList() ?: continue
        val (model, tagRead) = tagRead(method, body) ?: continue
        for (index in 1 until body.size) {
            if (body[index].opcode != Opcode.RETURN_OBJECT) continue
            val bind = body[index - 1] as? FiveRegisterInstruction ?: continue
            if (bind.opcode != Opcode.INVOKE_VIRTUAL || bind.registerCount != 1) continue
            val call = (bind as ReferenceInstruction).reference as? MethodReference ?: continue
            if (call.returnType != "V" || call.parameterTypes.isNotEmpty()) continue
            if (!isTopTabView(call.definingClass, strip.type, classOf)) continue
            val tag = try {
                method.findFreeRegister(index, bind.registerC, model)
            } catch (_: IllegalArgumentException) {
                continue
            }
            sites += TopTabBindSite(method, index, bind.registerC, model, tagRead, tag)
        }
    }
    return sites
}

/** Whether [type] is the strip's own tab view: a class built from the strip and a Context. */
private fun isTopTabView(type: String, strip: String, classOf: (String) -> ClassDef?): Boolean =
    classOf(type)?.methods?.any { method ->
        method.name == "<init>" && method.parameterTypes.map(CharSequence::toString) == listOf(strip, CONTEXT)
    } == true

/**
 * The parameter [method] reads a tab's tag() from, with that read: one parameter register, read
 * through an interface and never written, so it still holds the tab model when the method returns.
 */
private fun tagRead(method: Method, body: List<Instruction>): Pair<Int, MethodReference>? {
    val firstParameter = method.implementation!!.registerCount - method.numberOfParameterRegisters
    val reads = body.mapNotNull { instruction ->
        if (instruction.opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        val call = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@mapNotNull null
        if (call.name != TAG_GETTER || call.parameterTypes.isNotEmpty() || call.returnType != "Ljava/lang/String;") {
            return@mapNotNull null
        }
        val read = instruction as FiveRegisterInstruction
        if (read.registerCount != 1 || read.registerC < firstParameter) return@mapNotNull null
        read.registerC to call
    }
    val register = reads.map { it.first }.distinct().singleOrNull() ?: return null
    if (body.any { it.writes(register) }) return null
    return reads.first()
}

/**
 * Finds where TikTok binds its top tabs, the way [hookForYouLongPress] does. Every class it needs
 * comes from [classOf], so a test can hand it any set of classes.
 */
internal fun findTopTabBind(classOf: (String) -> ClassDef?): TopTabBindLookup {
    fun miss(why: String) = TopTabBindLookup(null, why)
    val ability = classOf(TOP_TAB_LAYOUT_ABILITY) ?: return miss("this TikTok has no $TOP_TAB_LAYOUT_ABILITY")
    val stripType = topTabStripType(ability)
        ?: return miss("TopTabLayoutAbility's constructor no longer takes the top tab strip")
    val strip = classOf(stripType) ?: return miss("the top tab strip $stripType isn't in this TikTok")
    val sites = topTabBindSites(strip, classOf)
    val site = sites.singleOrNull() ?: return miss("$stripType binds a top tab in ${sites.size} places, not one")
    if (maxOf(site.tabView, site.tabModel, site.tag) > 15) {
        return miss("${site.method.definingClass}->${site.method.name} keeps the tab in registers past v15")
    }
    return TopTabBindLookup(site, null)
}

/**
 * Asks the tab model for its tag again and hands the tab's view and the tag to [TOP_TAB_HOOK],
 * right before the strip returns the tab. Only the path that just bound the view gets here: TikTok
 * jumps to that return when the tab has no view, and the jump stays on the return, past the hook.
 */
internal fun MutableMethod.handTopTabOver(site: TopTabBindSite) {
    addInstructions(
        site.index,
        """
            invoke-interface {v${site.tabModel}}, ${site.tagRead.definingClass}->${site.tagRead.name}()${site.tagRead.returnType}
            move-result-object v${site.tag}
            invoke-static {v${site.tabView}, v${site.tag}}, $TOP_TAB_HOOK
        """,
    )
}

/**
 * Hands every top tab TikTok binds to [TOP_TAB_HOOK], so a long press on For You opens Hushfeed
 * settings. Answers false and changes nothing when this build doesn't bind its top tabs that way
 * exactly once: For You then stays as it is, and the settings row and the Home tab still open the
 * page.
 */
internal fun BytecodePatchContext.hookForYouLongPress(): Boolean {
    val lookup = findTopTabBind { classDefByOrNull(it) }
    val site = lookup.site ?: run {
        println("[Settings] Left out the For You long press on ${packageMetadata.versionName}: ${lookup.miss}.")
        return false
    }
    mutableClassDefBy(site.method.definingClass).findMutableMethodOf(site.method).handTopTabOver(site)
    return true
}
