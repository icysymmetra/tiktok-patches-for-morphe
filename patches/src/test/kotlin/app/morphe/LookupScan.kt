package app.morphe

import java.io.File

/**
 * Every view-id lookup in the extension, with the name and the package it resolves under traced
 * back to their literals.
 *
 * <p>The anchor table used to be matched against a pattern: a constant named `*_ID`, `*_IDS` or
 * `*_RESOURCE_NAMES`, or a literal written straight into `resolve(` or `getIdentifier(`. A name
 * handed to a helper as an argument, or a constant named any other way, was looked up on the
 * phone with no table line and no owner. This reads from the other end: every call that resolves
 * an id, then the expression its name comes from and the expression its package comes from,
 * through constants, arrays, for-each variables, ternaries and helper parameters (each of a
 * helper's callers traced in turn, name and package together, since one helper serves both the
 * app's ids and a feature module's). A lookup it cannot trace is reported, not skipped.
 */
internal object LookupScan {
    /** One traced lookup: the constant it came from (or `inline`), its names and its package. */
    class Lookup(val source: String, val group: String, val names: List<String>, val packageSuffix: String, val site: String) {
        val key get() = "$source|$group|${names.joinToString(",")}"
    }

    class Untraced(val site: String, val why: String)

    class Result(
        val lookups: List<Lookup>,
        val untraced: List<Untraced>,
        /** Lookups under the platform's own package: site and name. */
        val framework: List<Pair<String, String>>,
    )

    private const val APP_PACKAGE = "com.zhiliaoapp.musically"
    private const val MAX_DEPTH = 6

    private class Named(val group: String, val names: List<String>)

    private class Constant(val dims: Int, val initializer: String)

    private class Header(val name: String, val parameters: List<String>, val start: Int, val bodyStart: Int)

    private class Source(val path: String, val text: String) {
        val constants: Map<String, Constant> = CONSTANT.findAll(text).associate { match ->
            val (dims, name, initializer) = match.destructured
            name to Constant(dims.count { it == '[' }, initializer.trim())
        }
        val headers: List<Header> = HEADER.findAll(text).map { match ->
            Header(match.groupValues[1], splitArguments(match.groupValues[2]).map { it.trim().substringAfterLast(' ') }
                .filter { it.isNotEmpty() }, match.range.first, match.range.last + 1)
        }.toList()

        fun enclosing(offset: Int): Header? = headers.filter { it.start < offset }.maxByOrNull { it.start }

        /** Whether an offset sits inside a string literal, where a name like `tab (` is only words. */
        val quoted: BooleanArray = BooleanArray(text.length).also { mask ->
            var inString = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    mask[i] = true
                    if (c == '\\') { i++; if (i < text.length) mask[i] = true } else if (c == '"') inString = false
                } else if (c == '"') {
                    inString = true
                    mask[i] = true
                }
                i++
            }
        }
    }

    private class Trace(val values: List<Pair<Named, String>>, val problems: List<String>)

    fun scan(root: File): Result {
        val lookups = mutableListOf<Lookup>()
        val untraced = mutableListOf<Untraced>()
        val framework = mutableListOf<Pair<String, String>>()
        root.walkTopDown().filter { it.extension == "java" }.sortedBy { it.path }.forEach { file ->
            val text = withoutComments(file.readText())
            if (!text.contains(".resolve(") && !text.contains("getIdentifier(")) return@forEach
            val source = Source(file.relativeTo(root).invariantSeparatorsPath, text)
            for (call in calls(source)) {
                val site = "${source.path}:${lineOf(text, call.offset)}"
                if (call.problem != null) {
                    untraced += Untraced(site, call.problem)
                    continue
                }
                val trace = trace(source, call.nameExpr, call.packageExpr, source.enclosing(call.offset), 0, mutableSetOf())
                for (problem in trace.problems) untraced += Untraced(site, problem)
                for ((named, packageSuffix) in trace.values) {
                    if (packageSuffix == "android") {
                        named.names.forEach { framework += site to it }
                    } else {
                        lookups += Lookup(source.path, named.group, named.names, packageSuffix, site)
                    }
                }
            }
        }
        return Result(lookups, untraced, framework)
    }

    /** A lookup call site, or a call in the shape of one that could not be read, with why. */
    private class Call(val offset: Int, val nameExpr: String, val packageExpr: String, val problem: String? = null)

    /**
     * Every `<cache>.resolve(res, package, name, retry)` on a ResourceIdCache and every
     * `getIdentifier(name, "id", package)`.
     *
     * <p>A call in either shape that cannot be read the way this reads it is returned as a
     * problem rather than passed over: a resolve with too few arguments, a four-argument resolve
     * on a cache this file does not declare, or a getIdentifier whose type is not written as a
     * literal would otherwise be a lookup that reaches the phone with no table line. A literal
     * type other than "id" (a dimension, a raw resource) is not a view id and is left alone.
     */
    private fun calls(source: Source): List<Call> {
        val text = source.text
        val caches = RESOURCE_ID_CACHE_FIELD.findAll(text).map { it.groupValues[1] }.toSet()
        val found = mutableListOf<Call>()
        for (match in RESOLVE_CALL.findAll(text)) {
            if (source.quoted[match.range.first]) continue
            val receiver = match.groupValues[1]
            val arguments = splitArguments(argumentText(text, match.range.last + 1))
            if (receiver !in caches) {
                if (arguments.size == 4) found += Call(match.range.first, "", "",
                    "a four-argument resolve on $receiver looks like a ResourceIdCache lookup, but this file declares no cache of that name")
                continue
            }
            if (arguments.size < 3) {
                found += Call(match.range.first, "", "",
                    "a resolve on $receiver with ${arguments.size} argument(s) cannot be read as (resources, package, name, retry)")
                continue
            }
            found += Call(match.range.first, arguments[2].trim(), arguments[1].trim())
        }
        for (match in GET_IDENTIFIER_CALL.findAll(text)) {
            if (source.quoted[match.range.first]) continue
            val arguments = splitArguments(argumentText(text, match.range.last + 1))
            if (arguments.size != 3) {
                found += Call(match.range.first, "", "",
                    "a getIdentifier with ${arguments.size} argument(s) cannot be read as (name, type, package)")
                continue
            }
            val type = arguments[1].trim()
            if (!LITERAL.matches(type)) {
                found += Call(match.range.first, "", "", "the type of a getIdentifier call is not written as a literal: $type")
                continue
            }
            if (type != "\"id\"") continue
            found += Call(match.range.first, arguments[0].trim(), arguments[2].trim())
        }
        return found
    }

    /**
     * The names an expression stands for and the package another stands for, traced together.
     * A parameter of the enclosing method is followed into each caller of that method, both
     * expressions substituted from that call, so a helper that serves two packages yields its
     * lookups under each.
     */
    private fun trace(source: Source, nameExpr: String, packageExpr: String, method: Header?, depth: Int, seen: MutableSet<String>): Trace {
        if (depth > MAX_DEPTH) return Trace(emptyList(), listOf("traced $nameExpr / $packageExpr deeper than $MAX_DEPTH calls"))
        val nameParameter = method?.parameters?.indexOf(nameExpr)?.takeIf { it >= 0 }
        val packageParameter = method?.parameters?.indexOf(packageExpr)?.takeIf { it >= 0 }
        // A for-each variable stands for the elements of what it iterates.
        val loop = loopSource(source, nameExpr, method)
        if (loop != null) return trace(source, loop, packageExpr, method, depth, seen)
        // An array built on the spot, an element of an array, or a choice: each part is traced
        // here rather than in names(), since a part may be a parameter of this very method.
        ARRAY_LITERAL.matchEntire(nameExpr)?.let { match ->
            val parts = splitArguments(match.groupValues[1]).map { trace(source, it.trim(), packageExpr, method, depth + 1, seen) }
            return Trace(parts.flatMap { it.values }, parts.flatMap { it.problems })
        }
        INDEXED.matchEntire(nameExpr)?.let { match ->
            if (source.constants[match.groupValues[1]] == null) return trace(source, match.groupValues[1], packageExpr, method, depth + 1, seen)
        }
        ternary(nameExpr)?.let { (left, right) ->
            val a = trace(source, left, packageExpr, method, depth + 1, seen)
            val b = trace(source, right, packageExpr, method, depth + 1, seen)
            return Trace(a.values + b.values, a.problems + b.problems)
        }
        if (nameParameter != null || packageParameter != null) {
            val values = mutableListOf<Pair<Named, String>>()
            val problems = mutableListOf<String>()
            val callers = callers(source, checkNotNull(method))
            if (callers.isEmpty()) problems += "${method.name}(${nameExpr}) has no caller in ${source.path}"
            for (caller in callers) {
                val key = "${caller.offset}:$nameExpr:$packageExpr"
                if (!seen.add(key)) continue
                val callerName = if (nameParameter != null) caller.arguments.getOrNull(nameParameter) else nameExpr
                val callerPackage = if (packageParameter != null) caller.arguments.getOrNull(packageParameter) else packageExpr
                if (callerName == null || callerPackage == null) {
                    problems += "a call of ${method.name} at ${source.path}:${lineOf(source.text, caller.offset)} has too few arguments"
                    continue
                }
                val inner = trace(source, callerName.trim(), callerPackage.trim(), source.enclosing(caller.offset), depth + 1, seen)
                values += inner.values
                problems += inner.problems
            }
            return Trace(values, problems)
        }
        val names = names(source, nameExpr, depth)
        val packages = packages(source, packageExpr, method, depth, seen)
        val problems = names.second + packages.second
        if (packages.first.size > 1) return Trace(emptyList(), problems + "$packageExpr stands for more than one package: ${packages.first}")
        val packageSuffix = packages.first.singleOrNull() ?: return Trace(emptyList(), problems + "the package of a lookup could not be traced: $packageExpr")
        return Trace(names.first.map { it to packageSuffix }, problems)
    }

    /** The named groups an expression stands for, each with its literals. */
    private fun names(source: Source, expr: String, depth: Int): Pair<List<Named>, List<String>> {
        val trimmed = expr.trim()
        if (depth > MAX_DEPTH) return emptyList<Named>() to listOf("$trimmed traced too deep")
        LITERAL.matchEntire(trimmed)?.let { return listOf(Named("inline", listOf(it.groupValues[1]))) to emptyList() }
        // X[i]: the whole of X. new String[]{a, b}: each element. a ? b : c: both sides.
        INDEXED.matchEntire(trimmed)?.let { return names(source, it.groupValues[1], depth + 1) }
        ARRAY_LITERAL.matchEntire(trimmed)?.let { match ->
            val results = splitArguments(match.groupValues[1]).map { names(source, it, depth + 1) }
            return results.flatMap { it.first } to results.flatMap { it.second }
        }
        ternary(trimmed)?.let { (left, right) ->
            val a = names(source, left, depth + 1)
            val b = names(source, right, depth + 1)
            return a.first + b.first to a.second + b.second
        }
        if (IDENTIFIER.matches(trimmed)) {
            val constant = source.constants[trimmed]
            if (constant != null) return constantNames(source, trimmed, constant, depth)
            val local = LOCAL_ARRAY.find(source.text)?.takeIf { it.groupValues[1] == trimmed }
                ?: LOCAL_ARRAY.findAll(source.text).firstOrNull { it.groupValues[1] == trimmed }
            if (local != null) {
                val literals = LITERAL.findAll(local.groupValues[2]).map { it.groupValues[1] }.toList()
                return listOf(Named(trimmed, literals)) to emptyList()
            }
            return emptyList<Named>() to listOf("$trimmed is not a constant, a local array, a loop variable or a parameter that could be traced")
        }
        METHOD_CALL.matchEntire(trimmed)?.let { match ->
            // An array a method builds: every String constant its body names counts, and so
            // does every literal written inside braces there, an array built on the spot or a
            // local array's initializer. Loose on purpose, since a new name fed in there, as a
            // constant or as a literal, is what has to be caught.
            val body = methodBody(source, match.groupValues[1])
                ?: return emptyList<Named>() to listOf("${match.groupValues[1]}() is not declared in ${source.path}")
            val referenced = IDENTIFIER_TOKEN.findAll(body).map { it.value }.distinct()
                .filter { it in source.constants }
            val results = referenced.map { names(source, it, depth + 1) }.toMutableList()
            for (braced in BRACED.findAll(body)) {
                val literals = LITERAL.findAll(braced.groupValues[1]).map { it.groupValues[1] }.toList()
                if (literals.isNotEmpty()) results += listOf(Named("inline", literals)) to emptyList<String>()
            }
            return results.flatMap { it.first } to results.flatMap { it.second }
        }
        return emptyList<Named>() to listOf("cannot read the name expression $trimmed")
    }

    private fun constantNames(source: Source, name: String, constant: Constant, depth: Int): Pair<List<Named>, List<String>> {
        return when (constant.dims) {
            0 -> {
                val literal = LITERAL.matchEntire(constant.initializer)
                    ?: return emptyList<Named>() to listOf("$name is a String constant that is not a plain literal: ${constant.initializer}")
                listOf(Named(name, listOf(literal.groupValues[1]))) to emptyList()
            }
            1 -> {
                val body = constant.initializer.removePrefix("new String[]").trim()
                val literals = LITERAL.findAll(body).map { it.groupValues[1] }.toList()
                if (literals.isEmpty() || body.replace(LITERAL, "").trim('{', '}', ' ', ',', '\n', '\r', '\t').isNotEmpty()) {
                    emptyList<Named>() to listOf("$name is a String[] that is not a list of plain literals: ${constant.initializer}")
                } else listOf(Named(name, literals)) to emptyList()
            }
            else -> {
                // A String[][] of named arrays, or one a method builds.
                val results = if (METHOD_CALL.matches(constant.initializer)) listOf(names(source, constant.initializer, depth + 1))
                else splitArguments(constant.initializer.trim().removePrefix("new String[][]").trim().removeSurrounding("{", "}"))
                    .map { names(source, it, depth + 1) }
                results.flatMap { it.first } to results.flatMap { it.second }
            }
        }
    }

    /** The package suffixes an expression stands for: `app`, a feature module, or `android`. */
    private fun packages(source: Source, expr: String, method: Header?, depth: Int, seen: MutableSet<String>): Pair<Set<String>, List<String>> {
        val trimmed = expr.trim()
        if (depth > MAX_DEPTH) return emptySet<String>() to listOf("$trimmed traced too deep")
        if (trimmed.isEmpty() || trimmed == "\"\"") return emptySet<String>() to emptyList()
        // The whole expression, not any expression containing the call: `x.getPackageName() +
        // ".df_search_biz"` is the suffix, read by the concatenation below, not the app.
        if (GET_PACKAGE_NAME.matches(trimmed)) return setOf("app") to emptyList()
        LITERAL.matchEntire(trimmed)?.let { return setOf(suffixOf(it.groupValues[1])) to emptyList() }
        ternary(trimmed)?.let { (left, right) ->
            val a = packages(source, left, method, depth + 1, seen)
            val b = packages(source, right, method, depth + 1, seen)
            return a.first + b.first to a.second + b.second
        }
        CONCAT.matchEntire(trimmed)?.let { match ->
            val base = packages(source, match.groupValues[1], method, depth + 1, seen)
            val suffix = match.groupValues[2].removePrefix(".")
            return base.first.map { if (it == "app") suffix else "$it.$suffix" }.toSet() to base.second
        }
        if (IDENTIFIER.matches(trimmed)) {
            val constant = source.constants[trimmed]
            if (constant != null && constant.dims == 0) return packages(source, constant.initializer, method, depth + 1, seen)
            return emptySet<String>() to listOf("$trimmed is not a package literal, a constant or a traced parameter")
        }
        return emptySet<String>() to listOf("cannot read the package expression $trimmed")
    }

    private fun suffixOf(packageName: String) = when {
        packageName == APP_PACKAGE -> "app"
        packageName == "android" -> "android"
        packageName.startsWith("$APP_PACKAGE.") -> packageName.removePrefix("$APP_PACKAGE.")
        else -> packageName
    }

    /** What a for-each variable iterates, when the name is one, looking back from the method's body. */
    private fun loopSource(source: Source, name: String, method: Header?): String? {
        if (method == null || !IDENTIFIER.matches(name)) return null
        val body = source.text.substring(method.bodyStart, methodEnd(source.text, method.bodyStart))
        val loop = Regex("""for\s*\(\s*(?:final\s+)?String(?:\[])?\s+${Regex.escape(name)}\s*:\s*([^)]+)\)""").find(body) ?: return null
        return loop.groupValues[1].trim()
    }

    private class Caller(val offset: Int, val arguments: List<String>)

    /**
     * Every call of the method in its file, the declaration itself left out, and only the calls
     * with as many arguments as the method has parameters: an overload of another arity would
     * otherwise be read at this method's parameter index.
     */
    private fun callers(source: Source, method: Header): List<Caller> {
        val text = source.text
        val headerStarts = source.headers.map { it.start..it.bodyStart }
        return Regex("""(?<![\w.])${Regex.escape(method.name)}\s*\(""").findAll(text)
            .filter { match -> !source.quoted[match.range.first] && headerStarts.none { match.range.first in it } }
            .map { Caller(it.range.first, splitArguments(argumentText(text, it.range.last + 1)).map { a -> a.trim() }) }
            .filter { it.arguments.count { a -> a.isNotEmpty() } == method.parameters.size }
            .toList()
    }

    private fun methodBody(source: Source, name: String): String? {
        val header = source.headers.firstOrNull { it.name == name } ?: return null
        return source.text.substring(header.bodyStart, methodEnd(source.text, header.bodyStart))
    }

    /** The offset of the brace closing the body that starts at [bodyStart] (the offset just past its opening brace). */
    private fun methodEnd(text: String, bodyStart: Int): Int {
        var depth = 1
        var i = bodyStart
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return text.length
    }

    /** The text between the parenthesis that opens at [from] (the offset just past it) and its match. */
    private fun argumentText(text: String, from: Int): String {
        var depth = 1
        var i = from
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return text.substring(from, i) }
            }
            i++
        }
        return text.substring(from)
    }

    /** Comma-separated arguments, commas inside brackets, braces, parentheses and strings kept. */
    private fun splitArguments(arguments: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var i = 0
        while (i < arguments.length) {
            val c = arguments[i]
            when {
                inString -> { current.append(c); if (c == '\\') { i++; if (i < arguments.length) current.append(arguments[i]) } else if (c == '"') inString = false }
                c == '"' -> { inString = true; current.append(c) }
                // A lambda's arrow is not a closing angle bracket: counted as one, it put the
                // rest of the list one level down and the commas after it were never split on,
                // so a call handing a lambda counted fewer arguments than it has.
                c == '-' && i + 1 < arguments.length && arguments[i + 1] == '>' -> { current.append("->"); i++ }
                c == '(' || c == '[' || c == '{' || c == '<' -> { depth++; current.append(c) }
                c == ')' || c == ']' || c == '}' || c == '>' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { parts += current.toString(); current.setLength(0) }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.filter { it.isNotBlank() }
    }

    /** `a ? b : c` at the top level, as its two branches. */
    private fun ternary(expr: String): Pair<String, String>? {
        var depth = 0
        var question = -1
        var inString = false
        for (i in expr.indices) {
            val c = expr[i]
            when {
                inString -> if (c == '"') inString = false
                c == '"' -> inString = true
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == '?' && depth == 0 && question < 0 -> question = i
                c == ':' && depth == 0 && question >= 0 -> return expr.substring(question + 1, i).trim() to expr.substring(i + 1).trim()
            }
        }
        return null
    }

    private fun withoutComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> { out.append(c); if (c == '\\' && i + 1 < text.length) { out.append(text[i + 1]); i++ } else if (c == '"') inString = false }
                c == '"' -> { inString = true; out.append(c) }
                text.startsWith("//", i) -> { while (i < text.length && text[i] != '\n') i++; continue }
                text.startsWith("/*", i) -> { val end = text.indexOf("*/", i + 2); val stop = if (end < 0) text.length else end + 2
                    for (k in i until stop) out.append(if (text[k] == '\n') '\n' else ' '); i = stop; continue }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun lineOf(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1

    private val RESOURCE_ID_CACHE_FIELD = Regex("""\bResourceIdCache\s+(\w+)\s*=""")
    private val RESOLVE_CALL = Regex("""\b(\w+)\s*\.\s*resolve\s*\(""")
    private val GET_IDENTIFIER_CALL = Regex("""\bgetIdentifier\s*\(""")
    private val CONSTANT = Regex("""static\s+final\s+String((?:\s*\[])*)\s+(\w+)\s*=\s*([^;]+);""")
    private val LOCAL_ARRAY = Regex("""String\[]\s+(\w+)\s*=\s*(\{[^}]*})""")
    /** A class-level method: four spaces of indentation, then modifiers, a type, the name and its parameters. */
    private val HEADER = Regex("""(?m)^ {4}(?:(?:public|private|protected|static|final|synchronized)\s+)*[\w<>\[\]][\w<>\[\],. ?]*\s+(\w+)\s*\(([^)]*)\)\s*(?:throws[^{]+)?\{""")
    private val LITERAL = Regex(""""([^"\\]*)"""")
    private val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")
    private val IDENTIFIER_TOKEN = Regex("""\b[A-Za-z_$][\w$]*\b""")
    private val INDEXED = Regex("""([A-Za-z_$][\w$]*)\s*\[[^\]]+]""")
    private val ARRAY_LITERAL = Regex("""new\s+String\s*\[]\s*\{(.*)}""", RegexOption.DOT_MATCHES_ALL)
    private val METHOD_CALL = Regex("""(\w+)\s*\(\s*\)""")
    /** The inside of one pair of braces with no braces of its own: an array initializer, or a plain block. */
    private val BRACED = Regex("""\{([^{}]*)}""")
    /** `x.getPackageName()` as the whole expression, whatever chain of fields and calls x is. */
    private val GET_PACKAGE_NAME = Regex("""(?:[\w$]+(?:\s*\(\s*\))?\s*\.\s*)*getPackageName\s*\(\s*\)""")
    /** A base expression plus a literal: a constant, or a getPackageName() chain, and its suffix. */
    private val CONCAT = Regex("""(.+?)\s*\+\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
}
