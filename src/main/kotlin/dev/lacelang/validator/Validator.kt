/**
 * Semantic validator for Lace ASTs.
 *
 * Emits canonical error codes from `specs/error-codes.json` via DiagnosticSink.
 * Strict by default: the parser is permissive and the validator rejects anything
 * that violates spec S12. Context (maxRedirects, maxTimeoutMs) gates
 * system-limit checks; when absent, reasonable spec defaults are used.
 */
package dev.lacelang.validator

private val CHAIN_ORDER: List<String> = listOf("expect", "check", "assert", "store", "wait")
private val CORE_FUNCS: Set<String> = setOf("json", "form", "schema")
private val OP_VALUES: Set<String> = setOf("lt", "lte", "eq", "neq", "gte", "gt")
private val TIMEOUT_ACTIONS: Set<String> = setOf("fail", "warn", "retry")

private val MAX_BODY_RE = Regex("""^\d+(k|kb|m|mb|g|gb)?$""", RegexOption.IGNORE_CASE)
private val COOKIE_JAR_FIXED: Set<String> = setOf("inherit", "fresh", "selective_clear")
private val COOKIE_JAR_NAMED_RE = Regex("""^named:([A-Za-z0-9_\-]+)$""")
private val COOKIE_JAR_NAMED_SELECTIVE_RE = Regex("""^([A-Za-z0-9_\-]+):selective_clear$""")

private data class ExprCtx(
    val callIndex: Int,
    val chainMethod: String?,
    val allowThis: Boolean,
    val allowExtensionFuncs: Boolean,
)

fun validate(
    ast: AstNode,
    variables: List<String>? = null,
    context: Map<String, Any?>? = null,
    prevResultsAvailable: Boolean = false,
    activeExtensions: List<String>? = null,
): DiagnosticSink {
    val sink = DiagnosticSink()
    val ctx = context ?: emptyMap()
    val varsSet = (variables ?: emptyList()).toSet()
    val extensionsActive = !activeExtensions.isNullOrEmpty()

    @Suppress("UNCHECKED_CAST")
    val calls = (ast["calls"] as? List<AstNode>) ?: emptyList()
    if (calls.isEmpty()) {
        sink.error("AT_LEAST_ONE_CALL")
        return sink
    }
    if (calls.size > 10) {
        sink.warning("HIGH_CALL_COUNT")
    }

    val runVarAssigned = mutableMapOf<String, Int>()

    for (i in calls.indices) {
        validateCall(calls[i], i, sink, varsSet, ctx, prevResultsAvailable, runVarAssigned, extensionsActive)
    }

    return sink
}

private fun validateCall(
    call: AstNode,
    idx: Int,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    ctx: Map<String, Any?>,
    prevAvailable: Boolean,
    runVarAssigned: MutableMap<String, Int>,
    extensionsActive: Boolean,
) {
    @Suppress("UNCHECKED_CAST")
    val cfg = (call["config"] as? AstNode) ?: AstNode()
    validateCallConfig(cfg, idx, sink, varsSet, ctx, prevAvailable, extensionsActive)

    @Suppress("UNCHECKED_CAST")
    val chain = (call["chain"] as? AstNode) ?: AstNode()
    @Suppress("UNCHECKED_CAST")
    val order = (chain["__order"] as? List<String>) ?: emptyList()
    @Suppress("UNCHECKED_CAST")
    val dupes = (chain["__duplicates"] as? List<String>) ?: emptyList()

    if (dupes.isNotEmpty()) {
        sink.error("CHAIN_DUPLICATE", callIndex = idx, detail = dupes.joinToString(","))
    }

    // Order check on the de-duplicated observed sequence.
    val seen = mutableListOf<String>()
    for (m in order) {
        if (m !in seen) seen.add(m)
    }
    val expectedOrder = CHAIN_ORDER.filter { it in seen }
    if (seen.size != expectedOrder.size || seen.zip(expectedOrder).any { (a, b) -> a != b }) {
        sink.error("CHAIN_ORDER", callIndex = idx)
    }

    if (order.isEmpty()) {
        sink.error("EMPTY_CHAIN", callIndex = idx)
        return
    }

    for (method in listOf("expect", "check")) {
        if (method in chain) {
            @Suppress("UNCHECKED_CAST")
            validateScopeBlock(chain[method] as AstNode, call, idx, method, sink, varsSet, prevAvailable)
        }
    }

    if ("assert" in chain) {
        @Suppress("UNCHECKED_CAST")
        validateAssertBlock(chain["assert"] as AstNode, call, idx, sink, varsSet, prevAvailable)
    }

    if ("store" in chain) {
        @Suppress("UNCHECKED_CAST")
        validateStoreBlock(chain["store"] as AstNode, call, idx, sink, varsSet, prevAvailable, runVarAssigned)
    }

    if ("wait" in chain) {
        val w = chain["wait"]
        if (w !is Int || w < 0) {
            sink.error("EXPRESSION_SYNTAX", callIndex = idx, chainMethod = "wait")
        }
    }
}

// -- call config --

private fun validateCallConfig(
    cfg: AstNode,
    idx: Int,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    ctx: Map<String, Any?>,
    prevAvailable: Boolean,
    extensionsActive: Boolean,
) {
    // cookieJar / clearCookies
    val jar = cfg["cookieJar"]
    if (jar != null) {
        validateCookieJar(jar as String, cfg, idx, sink)
    } else {
        @Suppress("UNCHECKED_CAST")
        val clearCookies = cfg["clearCookies"] as? List<*>
        if (clearCookies != null && clearCookies.isNotEmpty()) {
            sink.error("CLEAR_COOKIES_WRONG_JAR", callIndex = idx)
        }
    }

    // redirects
    @Suppress("UNCHECKED_CAST")
    val red = (cfg["redirects"] as? AstNode) ?: AstNode()
    if ("max" in red) {
        val limit = ctx["maxRedirects"]
        val maxNode = red["max"]
        val maxVal = if (maxNode is Map<*, *> && maxNode["kind"] == "literal") (maxNode["value"] as? Number)?.toInt() else maxNode as? Int
        if (limit is Number && maxVal != null && maxVal > limit.toInt()) {
            sink.error("REDIRECTS_MAX_LIMIT", callIndex = idx, field = "redirects.max")
        }
    }

    // timeout
    @Suppress("UNCHECKED_CAST")
    val to = (cfg["timeout"] as? AstNode) ?: AstNode()
    if ("action" in to && to["action"] !in TIMEOUT_ACTIONS) {
        sink.error("TIMEOUT_ACTION_INVALID", callIndex = idx, field = "timeout.action")
    }
    if ("retries" in to && to["action"] != "retry") {
        sink.error("TIMEOUT_RETRIES_REQUIRES_RETRY", callIndex = idx)
    }
    if ("ms" in to) {
        val limit = ctx["maxTimeoutMs"]
        val msNode = to["ms"]
        val msVal = if (msNode is Map<*, *> && msNode["kind"] == "literal") (msNode["value"] as? Number)?.toInt() else msNode as? Int
        if (limit is Number && msVal != null && msVal > limit.toInt()) {
            sink.error("TIMEOUT_MS_LIMIT", callIndex = idx, field = "timeout.ms")
        }
    }

    // Walk expressions in config for variable/function/this checks.
    val ctxInfo = ExprCtx(callIndex = idx, chainMethod = null, allowThis = false, allowExtensionFuncs = false)
    walkAny(cfg["headers"], sink, varsSet, ctxInfo, prevAvailable)
    walkBody(cfg["body"], sink, varsSet, ctxInfo, prevAvailable)
    walkAny(cfg["cookies"], sink, varsSet, ctxInfo, prevAvailable)

    // extensions passthrough
    val ctxExt = ExprCtx(callIndex = idx, chainMethod = null, allowThis = false, allowExtensionFuncs = true)
    @Suppress("UNCHECKED_CAST")
    val extensions = cfg["extensions"] as? AstNode
    if (extensions != null) {
        for (name in extensions.keys) {
            if (!extensionsActive) {
                sink.warning("EXT_FIELD_INACTIVE", callIndex = idx, field = name)
            }
        }
    }
    walkAny(extensions, sink, varsSet, ctxExt, prevAvailable)

    for (sub in listOf("redirects", "security", "timeout")) {
        @Suppress("UNCHECKED_CAST")
        val subObj = (cfg[sub] as? AstNode) ?: continue
        @Suppress("UNCHECKED_CAST")
        val ext = subObj["extensions"] as? AstNode
        if (ext != null) {
            if (!extensionsActive) {
                for (name in ext.keys) {
                    sink.warning("EXT_FIELD_INACTIVE", callIndex = idx, field = "$sub.$name")
                }
            }
            walkAny(ext, sink, varsSet, ctxExt, prevAvailable)
        }
    }
}

private fun validateCookieJar(jar: String, cfg: AstNode, idx: Int, sink: DiagnosticSink) {
    if (jar in COOKIE_JAR_FIXED) {
        @Suppress("UNCHECKED_CAST")
        val clearCookies = cfg["clearCookies"] as? List<*>
        if (clearCookies != null && clearCookies.isNotEmpty() && jar != "selective_clear") {
            sink.error("CLEAR_COOKIES_WRONG_JAR", callIndex = idx)
        }
        return
    }
    if (jar.startsWith("named:")) {
        if (jar == "named:") {
            sink.error("COOKIE_JAR_NAMED_EMPTY", callIndex = idx)
            return
        }
        if (!COOKIE_JAR_NAMED_RE.matches(jar)) {
            sink.error("COOKIE_JAR_FORMAT", callIndex = idx, field = "cookieJar")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val clearCookies = cfg["clearCookies"] as? List<*>
        if (clearCookies != null && clearCookies.isNotEmpty()) {
            sink.error("CLEAR_COOKIES_WRONG_JAR", callIndex = idx)
        }
        return
    }
    if (COOKIE_JAR_NAMED_SELECTIVE_RE.matches(jar)) {
        return
    }
    sink.error("COOKIE_JAR_FORMAT", callIndex = idx, field = "cookieJar")
}

// -- scope / assert / store --

private fun validateScopeBlock(
    block: AstNode,
    @Suppress("UNUSED_PARAMETER") call: AstNode,
    idx: Int,
    method: String,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    prevAvailable: Boolean,
) {
    val realKeys = block.keys.filter { !it.startsWith("__") }
    if (realKeys.isEmpty()) {
        sink.error("EMPTY_SCOPE_BLOCK", callIndex = idx, chainMethod = method)
        return
    }

    val ctxInfo = ExprCtx(callIndex = idx, chainMethod = method, allowThis = true, allowExtensionFuncs = false)
    val ctxOpts = ExprCtx(callIndex = idx, chainMethod = method, allowThis = true, allowExtensionFuncs = true)

    for (field in realKeys) {
        @Suppress("UNCHECKED_CAST")
        val sv = block[field] as AstNode
        if ("op" in sv && sv["op"] !in OP_VALUES) {
            sink.error("OP_VALUE_INVALID", callIndex = idx, chainMethod = method, field = field)
        }
        if (field == "bodySize") {
            val v = sv["value"]
            if (v is LinkedHashMap<*, *> && v["kind"] == "literal" && v["valueType"] == "string") {
                if (!MAX_BODY_RE.matches(v["value"].toString())) {
                    sink.error("MAX_BODY_FORMAT", callIndex = idx, chainMethod = method, field = field)
                }
            }
        }
        walkAny(sv["value"], sink, varsSet, ctxInfo, prevAvailable)
        walkAny(sv["options"], sink, varsSet, ctxOpts, prevAvailable)
    }
}

private fun validateAssertBlock(
    block: AstNode,
    @Suppress("UNUSED_PARAMETER") call: AstNode,
    idx: Int,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    prevAvailable: Boolean,
) {
    val clauses = listOf("expect", "check").filter { it in block }
    val ctxInfo = ExprCtx(callIndex = idx, chainMethod = "assert", allowThis = true, allowExtensionFuncs = false)
    val ctxOpts = ExprCtx(callIndex = idx, chainMethod = "assert", allowThis = true, allowExtensionFuncs = true)
    var total = 0
    for (c in clauses) {
        @Suppress("UNCHECKED_CAST")
        val items = (block[c] as? List<AstNode>) ?: emptyList()
        total += items.size
        for (it in items) {
            walkAny(it["condition"], sink, varsSet, ctxInfo, prevAvailable)
            walkAny(it["options"], sink, varsSet, ctxOpts, prevAvailable)
        }
    }
    if (clauses.isEmpty() || total == 0) {
        sink.error("EMPTY_ASSERT_BLOCK", callIndex = idx, chainMethod = "assert")
        return
    }
}

private fun validateStoreBlock(
    block: AstNode,
    @Suppress("UNUSED_PARAMETER") call: AstNode,
    idx: Int,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    prevAvailable: Boolean,
    runVarAssigned: MutableMap<String, Int>,
) {
    val keys = block.keys.filter { !it.startsWith("__") }
    if (keys.isEmpty()) {
        sink.error("EMPTY_STORE_BLOCK", callIndex = idx, chainMethod = "store")
        return
    }
    val ctxInfo = ExprCtx(callIndex = idx, chainMethod = "store", allowThis = true, allowExtensionFuncs = false)
    for (key in keys) {
        @Suppress("UNCHECKED_CAST")
        val entry = block[key] as AstNode
        // RUN_VAR write-once enforcement.
        if (entry["scope"] == "run") {
            val bare = if (key.startsWith("\$\$")) key.substring(2) else key
            if (bare in runVarAssigned) {
                sink.error("RUN_VAR_REASSIGNED", callIndex = idx, chainMethod = "store")
            } else {
                runVarAssigned[bare] = idx
            }
        }
        walkAny(entry["value"], sink, varsSet, ctxInfo, prevAvailable)
    }
}

// -- expression walking --

private fun walkBody(
    body: Any?,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    ctx: ExprCtx,
    prevAvailable: Boolean,
) {
    if (body == null || body !is Map<*, *>) return
    val tp = body["type"]
    if (tp == "json" || tp == "form") {
        walkAny(body["value"], sink, varsSet, ctx, prevAvailable)
    }
}

private fun walkAny(
    node: Any?,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    ctx: ExprCtx,
    prevAvailable: Boolean,
) {
    if (node == null) return
    if (node is List<*>) {
        for (item in node) {
            walkAny(item, sink, varsSet, ctx, prevAvailable)
        }
        return
    }
    if (node is Map<*, *>) {
        val kind = node["kind"]
        if (kind == null) {
            // container/map -- recurse into all values
            for (v in node.values) {
                walkAny(v, sink, varsSet, ctx, prevAvailable)
            }
            return
        }
        @Suppress("UNCHECKED_CAST")
        walkExpr(node as Map<String, Any?>, sink, varsSet, ctx, prevAvailable)
    }
}

private fun walkExpr(
    expr: Map<String, Any?>,
    sink: DiagnosticSink,
    varsSet: Set<String>,
    ctx: ExprCtx,
    prevAvailable: Boolean,
) {
    val kind = expr["kind"]
    when (kind) {
        "binary" -> {
            @Suppress("UNCHECKED_CAST")
            walkExpr(expr["left"] as Map<String, Any?>, sink, varsSet, ctx, prevAvailable)
            @Suppress("UNCHECKED_CAST")
            walkExpr(expr["right"] as Map<String, Any?>, sink, varsSet, ctx, prevAvailable)
        }
        "unary" -> {
            @Suppress("UNCHECKED_CAST")
            walkExpr(expr["operand"] as Map<String, Any?>, sink, varsSet, ctx, prevAvailable)
        }
        "thisRef" -> {
            if (!ctx.allowThis) {
                sink.error("THIS_OUT_OF_SCOPE", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod)
            }
        }
        "prevRef" -> {
            if (!prevAvailable) {
                sink.warning("PREV_WITHOUT_RESULTS", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod)
            }
        }
        "funcCall" -> {
            val name = expr["name"] as? String ?: ""
            @Suppress("UNCHECKED_CAST")
            val args = (expr["args"] as? List<Any?>) ?: emptyList()
            if (name in CORE_FUNCS) {
                checkCoreFuncArgs(name, args, sink, ctx, varsSet)
            } else if (ctx.allowExtensionFuncs) {
                // extension contexts accept anything
            } else {
                sink.error("UNKNOWN_FUNCTION", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod, field = name)
            }
            for (a in args) {
                walkAny(a, sink, varsSet, ctx, prevAvailable)
            }
        }
        "scriptVar" -> {
            val name = expr["name"] as? String ?: ""
            if (varsSet.isNotEmpty() && name !in varsSet) {
                sink.error("VARIABLE_UNKNOWN", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod, field = name)
            }
        }
        "runVar", "literal" -> return
        "objectLit" -> {
            @Suppress("UNCHECKED_CAST")
            val entries = (expr["entries"] as? List<Map<String, Any?>>) ?: emptyList()
            for (e in entries) {
                walkAny(e["value"], sink, varsSet, ctx, prevAvailable)
            }
        }
        "arrayLit" -> {
            @Suppress("UNCHECKED_CAST")
            val items = (expr["items"] as? List<Any?>) ?: emptyList()
            for (it in items) {
                walkAny(it, sink, varsSet, ctx, prevAvailable)
            }
        }
    }
}

private fun checkCoreFuncArgs(
    name: String,
    args: List<Any?>,
    sink: DiagnosticSink,
    ctx: ExprCtx,
    varsSet: Set<String>,
) {
    if (name == "json" || name == "form") {
        if (args.size != 1 || args[0] !is Map<*, *> || (args[0] as Map<*, *>)["kind"] != "objectLit") {
            sink.error("FUNC_ARG_TYPE", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod, field = name)
        }
    } else if (name == "schema") {
        if (args.size != 1 || args[0] !is Map<*, *> || (args[0] as Map<*, *>)["kind"] != "scriptVar") {
            sink.error("FUNC_ARG_TYPE", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod, field = name)
        } else {
            @Suppress("UNCHECKED_CAST")
            val argName = (args[0] as Map<String, Any?>)["name"] as? String ?: ""
            if (varsSet.isNotEmpty() && argName !in varsSet) {
                sink.error("SCHEMA_VAR_UNKNOWN", callIndex = ctx.callIndex, chainMethod = ctx.chainMethod, field = argName)
            }
        }
    }
}
