/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/BrowserPrivacyGuard;"
private const val WEB_VIEW = "Landroid/webkit/WebView;"
private const val WEB_VIEW_CLIENT = "Landroid/webkit/WebViewClient;"
private const val ADD_INTERFACE = "->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V"

private val navigationMethods = mapOf(
    "->loadUrl(Ljava/lang/String;)V" to
        "$EXTENSION->loadUrl(${WEB_VIEW}Ljava/lang/String;)V",
    "->loadUrl(Ljava/lang/String;Ljava/util/Map;)V" to
        "$EXTENSION->loadUrl(${WEB_VIEW}Ljava/lang/String;Ljava/util/Map;)V",
    "->postUrl(Ljava/lang/String;[B)V" to
        "$EXTENSION->postUrl(${WEB_VIEW}Ljava/lang/String;[B)V",
    "->loadData(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" to
        "$EXTENSION->loadData(${WEB_VIEW}Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
    "->loadDataWithBaseURL(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" to
        "$EXTENSION->loadDataWithBaseURL(${WEB_VIEW}Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
    "->reload()V" to "$EXTENSION->reload(${WEB_VIEW})V",
    // A history step loads the page it returns to, and Chromium asks no WebViewClient about it.
    "->goBack()V" to "$EXTENSION->goBack(${WEB_VIEW})V",
    "->goForward()V" to "$EXTENSION->goForward(${WEB_VIEW})V",
    "->goBackOrForward(I)V" to "$EXTENSION->goBackOrForward(${WEB_VIEW}I)V",
)

@Suppress("unused")
val browserPrivacyGuardPatch = bytecodePatch(
    name = "In-app browser privacy guard",
    description = "Stops websites you open in TikTok's built-in browser from reaching into " +
        "the TikTok app. TikTok's own pages, like Watch history and checkout, keep working. " +
        "Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableBrowserPrivacyGuard()V",
        )

        // TikTok wraps WebView in a subclass of its own that overrides addJavascriptInterface
        // and calls super, and three of the sites call the override by the subclass's name. The
        // subclass is found rather than named, because its name changes with every build. The
        // invoke-super inside the override is left alone: it is the pass-through path, and
        // rewriting it to a static that dispatches virtually again would recurse.
        val webViewTypes = descendantsOf(WEB_VIEW)
        val interfaceReplacement =
            "$EXTENSION->filterJsInterface(${WEB_VIEW}Ljava/lang/Object;Ljava/lang/String;)V"
        val interfaceReplacements = webViewTypes.associate { type ->
            "$type$ADD_INTERFACE" to interfaceReplacement
        }
        val interfaceSites = invokeSitesOf(interfaceReplacements.keys)
        if (interfaceSites.isEmpty()) {
            throw PatchException("In-app browser privacy guard: no addJavascriptInterface call site was found.")
        }
        replaceSites(interfaceSites, interfaceReplacements)

        val navigationReplacements = buildMap {
            for (type in webViewTypes) {
                for ((suffix, replacement) in navigationMethods) put("$type$suffix", replacement)
            }
        }
        val navigationSites = invokeSitesOf(navigationReplacements.keys)
        if (navigationSites.isEmpty()) {
            throw PatchException("In-app browser privacy guard: no WebView navigation call site was found.")
        }
        replaceSites(navigationSites, navigationReplacements)

        // TikTok's hybrid view interface (LX/0EFO on 47.0.3) declares reload(), and WebKitView
        // implements it along with Lynx views that are not WebViews at all. A reload through it
        // never names WebView, so the call is kept and the guard runs just before it. These
        // insertions shift instruction indices, so they go after every in-place replacement.
        val reloadTargets = interfacesOf(webViewTypes).map { "$it->reload()V" }.toSet()
        val interfaceReloads = invokeSitesOf(reloadTargets, throughInterface = true)
        prefixSites(interfaceReloads, "$EXTENSION->beforeInterfaceReload(Ljava/lang/Object;)V")

        val callbacks = webViewClientCallbacks()
        val pageStarts = callbacks.count { it.method.name == "onPageStarted" }
        val requestIntercepts = callbacks.count { it.method.name == "shouldInterceptRequest" }
        val pageRequests = callbacks.size - pageStarts - requestIntercepts
        if (pageStarts == 0 || pageRequests == 0 || requestIntercepts == 0) {
            throw PatchException(
                "In-app browser privacy guard: found $pageStarts page-start, " +
                    "$pageRequests page-request and $requestIntercepts request-intercept " +
                    "WebViewClient callbacks.",
            )
        }
        callbacks.forEach { callback ->
            val bridge = when {
                callback.method.name == "shouldInterceptRequest" -> "onRequestIntercepted"
                callback.method.name == "onPageStarted" -> "onPageStarted"
                callback.method.parameterTypes[1].toString() == "Ljava/lang/String;" ||
                    callback.method.parameterTypes[1].toString() == "Landroid/webkit/WebResourceRequest;" ->
                    "onPageRequested"
                else -> error("unrecognised WebViewClient callback ${callback.method}")
            }
            val secondType = callback.method.parameterTypes[1]
            mutableClassDefBy(callback.owner).findMutableMethodOf(callback.method).addInstruction(
                0,
                "invoke-static/range { p1 .. p2 }, $EXTENSION->$bridge" +
                    "(${WEB_VIEW}$secondType)V",
            )
        }
        println(
            "[Browser privacy guard] Intercepted ${interfaceSites.size} bridge sites, " +
                "${navigationSites.size} navigation sites, ${interfaceReloads.size} interface " +
                "reloads and ${callbacks.size} navigation callbacks across ${webViewTypes.size} " +
                "WebView types.",
        )
    }
}

/** Every interface the given classes implement, with the interfaces those extend. */
private fun BytecodePatchContext.interfacesOf(types: Set<String>): Set<String> {
    val byType = HashMap<String, ClassDef>()
    classDefForEach { byType[it.type] = it }
    val found = mutableSetOf<String>()
    val pending = ArrayDeque<String>()
    types.forEach { type -> byType[type]?.interfaces?.let(pending::addAll) }
    while (pending.isNotEmpty()) {
        val type = pending.removeFirst()
        if (found.add(type)) byType[type]?.interfaces?.let(pending::addAll)
    }
    return found
}

private data class ClientCallback(val owner: ClassDef, val method: Method)

private fun BytecodePatchContext.descendantsOf(baseType: String): Set<String> {
    val types = mutableSetOf(baseType)
    var grew = true
    while (grew) {
        grew = false
        classDefForEach { classDef ->
            if (classDef.superclass in types && types.add(classDef.type)) grew = true
        }
    }
    return types
}

private fun BytecodePatchContext.webViewClientCallbacks(): List<ClientCallback> {
    val clientTypes = descendantsOf(WEB_VIEW_CLIENT)
    val callbacks = mutableListOf<ClientCallback>()
    classDefForEach { owner ->
        if (owner.type !in clientTypes || owner.type.startsWith("Lapp/morphe/extension/")) {
            return@classDefForEach
        }
        owner.methods.forEach { method ->
            if (method.implementation == null) return@forEach
            val parameters = method.parameterTypes.map(CharSequence::toString)
            val pageStart = method.name == "onPageStarted" && method.returnType == "V" &&
                parameters == listOf(
                    WEB_VIEW,
                    "Ljava/lang/String;",
                    "Landroid/graphics/Bitmap;",
                )
            val pageRequest = method.name == "shouldOverrideUrlLoading" && method.returnType == "Z" &&
                (parameters == listOf(WEB_VIEW, "Ljava/lang/String;") ||
                    parameters == listOf(WEB_VIEW, "Landroid/webkit/WebResourceRequest;"))
            // The request form only: the string form cannot tell a page from its images.
            val requestIntercept = method.name == "shouldInterceptRequest" &&
                method.returnType == "Landroid/webkit/WebResourceResponse;" &&
                parameters == listOf(WEB_VIEW, "Landroid/webkit/WebResourceRequest;")
            if (pageStart || pageRequest || requestIntercept) callbacks += ClientCallback(owner, method)
        }
    }
    return callbacks
}
