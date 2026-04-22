/**
 * Render AST expressions back to source-form Lace text.
 *
 * Used by the executor to populate the `expression` field on assert-type
 * assertion records (spec S9.2, `assertions[].expression`). The output
 * should round-trip: it parses back to an equivalent AST (modulo whitespace).
 * Operator precedence is preserved by always parenthesising binary
 * sub-expressions that could be ambiguous.
 */
package dev.lacelang.validator

import com.google.gson.Gson

private val BINARY_PRIORITY: Map<String, Int> = mapOf(
    "or" to 1, "and" to 2,
    "eq" to 3, "neq" to 3,
    "lt" to 4, "lte" to 4, "gt" to 4, "gte" to 4,
    "+" to 5, "-" to 5,
    "*" to 6, "/" to 6, "%" to 6,
)

private val gson = Gson()

fun fmt(expr: Any?): String {
    if (expr == null || expr !is Map<*, *>) {
        return gson.toJson(expr)
    }
    val node = expr
    val k = node["kind"]

    if (k == "literal") {
        val vt = node["valueType"]
        val v = node["value"]
        if (vt == "string") return gson.toJson(v)
        if (vt == "null") return "null"
        if (vt == "bool") return if (v == true) "true" else "false"
        // int or float
        if (v is Number) {
            // For int values, render without decimal
            if (vt == "int") return v.toLong().toString()
            return v.toString()
        }
        return v.toString()
    }
    if (k == "scriptVar") {
        return "\$${node["name"]}${fmtVarPath(node["path"])}"
    }
    if (k == "runVar") {
        return "\$\$${node["name"]}${fmtVarPath(node["path"])}"
    }
    if (k == "thisRef") {
        @Suppress("UNCHECKED_CAST")
        val path = (node["path"] as? List<String>) ?: emptyList()
        return "this" + path.joinToString("") { ".$it" }
    }
    if (k == "prevRef") {
        val sb = StringBuilder("prev")
        @Suppress("UNCHECKED_CAST")
        val path = (node["path"] as? List<Map<String, Any?>>) ?: emptyList()
        for (seg in path) {
            if (seg["type"] == "field") {
                sb.append(".${seg["name"]}")
            } else {
                sb.append("[${seg["index"]}]")
            }
        }
        return sb.toString()
    }
    if (k == "unary") {
        val op = node["op"] as String
        if (op == "not") {
            return "not ${fmt(node["operand"])}"
        }
        return "$op${fmt(node["operand"])}"
    }
    if (k == "binary") {
        val op = node["op"] as String
        return "${paren(node["left"], op)} $op ${paren(node["right"], op)}"
    }
    if (k == "funcCall") {
        @Suppress("UNCHECKED_CAST")
        val args = (node["args"] as? List<Any?>) ?: emptyList()
        return "${node["name"]}(${args.joinToString(", ") { fmt(it) }})"
    }
    if (k == "objectLit") {
        @Suppress("UNCHECKED_CAST")
        val entries = (node["entries"] as? List<Map<String, Any?>>) ?: emptyList()
        val inner = entries.joinToString(", ") { "${it["key"]}: ${fmt(it["value"])}" }
        return "{$inner}"
    }
    if (k == "arrayLit") {
        @Suppress("UNCHECKED_CAST")
        val items = (node["items"] as? List<Any?>) ?: emptyList()
        return "[${items.joinToString(", ") { fmt(it) }}]"
    }
    return "<unknown>"
}

private fun fmtVarPath(path: Any?): String {
    if (path == null || path !is List<*>) return ""
    val sb = StringBuilder()
    for (seg in path) {
        if (seg !is Map<*, *>) continue
        if (seg["type"] == "field") {
            sb.append(".${seg["name"]}")
        } else {
            sb.append("[${seg["index"]}]")
        }
    }
    return sb.toString()
}

private fun paren(sub: Any?, outerOp: String): String {
    if (sub != null && sub is Map<*, *> && sub["kind"] == "binary") {
        val inner = sub["op"] as String
        if ((BINARY_PRIORITY[inner] ?: 99) < (BINARY_PRIORITY[outerOp] ?: 99)) {
            return "(${fmt(sub)})"
        }
    }
    return fmt(sub)
}
