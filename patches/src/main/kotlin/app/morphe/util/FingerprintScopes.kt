/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.util

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.Match
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext

/**
 * Every match of [fingerprint] in the classes whose type ends with [suffix], like `/StoryApi;`,
 * the same suffix the fingerprint declares as its definingClass.
 *
 * <p>Morphe patcher's `matchAll` (1.14 and 1.15) narrows its search by instruction filters alone.
 * Without one it runs the fingerprint over every method of TikTok, whatever `definingClass` says,
 * which cost about half a second a fingerprint on a desktop and far more on a phone (#54). A
 * single match does use the suffix; this gives `matchAll` the same. Patcher 1.15 no longer lets a
 * patch read a fingerprint's definingClass, so the caller passes it.
 */
internal fun BytecodePatchContext.matchAllInDefiningClasses(fingerprint: Fingerprint, suffix: String): List<Match> {
    require(!suffix.startsWith("L") && suffix.startsWith("/") && suffix.endsWith(";")) {
        "matchAllInDefiningClasses needs a definingClass suffix like /Name;, not $suffix"
    }
    val matches = mutableListOf<Match>()
    classDefForEach { classDef ->
        if (classDef.type.endsWith(suffix)) matches += fingerprint.matchAllOrNull(classDef).orEmpty()
    }
    return matches
}

/**
 * The types of the classes holding a call to some method of one of [owners], each an exact type
 * (`Lpkg/Type;`).
 *
 * <p>The patcher keeps an index of which classes reference which types, and a fingerprint whose
 * one instruction filter is a call on such a type is matched against those classes alone. A scan
 * that has to read every call in TikTok can skip every other class: these classes are a superset
 * of the callers of any one method, so the scan that follows still reads each instruction and
 * finds what it found before, over a few hundred classes instead of every class of the app (#54).
 * Each lookup also walks the class list once, so this takes types, not type and name pairs,
 * which cost the browser guard about eighty lookups. Hushfeed's own injected calls go to the
 * extension, so a class that calls one of these only after an earlier patch ran is not a case
 * this has to cover.
 */
internal fun BytecodePatchContext.classesCalling(owners: Collection<String>): Set<String> {
    val types = HashSet<String>()
    for (owner in owners.toSet()) {
        require(owner.startsWith("L") && owner.endsWith(";")) { "classesCalling needs an exact type, not $owner" }
        Fingerprint(filters = listOf(methodCall(definingClass = owner)))
            .matchAllOrNull().orEmpty()
            .mapTo(types) { it.originalClassDef.type }
    }
    return types
}
