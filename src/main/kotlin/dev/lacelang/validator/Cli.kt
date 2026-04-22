/**
 * CLI for lacelang-validator -- parse + validate only.
 *
 * Subcommands:
 *   parse <script>                                    -> { "ast": ... } | { "errors": [...] }
 *   validate <script> [--vars-list P] [--context P]   -> { "errors": [...], "warnings": [...] }
 *
 * Exit codes:
 *   0 on processed request (parse/validate errors are in the JSON body)
 *   2 on tool/arg errors
 */
package dev.lacelang.validator

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

private const val VERSION = "0.1.0"

private fun stripAstMetadata(node: Any?): Any? {
    if (node is List<*>) {
        return node.map { stripAstMetadata(it) }
    }
    if (node is Map<*, *>) {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in node) {
            val key = k as String
            if (!key.startsWith("__")) {
                out[key] = stripAstMetadata(v)
            }
        }
        return out
    }
    return node
}

private fun emit(obj: Any?, pretty: Boolean) {
    val gson: Gson = if (pretty) {
        GsonBuilder().setPrettyPrinting().serializeNulls().create()
    } else {
        GsonBuilder().serializeNulls().create()
    }
    println(gson.toJson(obj))
}

private fun readText(path: String): String = File(path).readText(Charsets.UTF_8)

private fun readJson(path: String): Any? {
    val text = readText(path)
    return jsonElementToNative(JsonParser.parseString(text))
}

private fun jsonElementToNative(el: JsonElement): Any? {
    if (el.isJsonNull) return null
    if (el.isJsonPrimitive) {
        val prim = el.asJsonPrimitive
        if (prim.isBoolean) return prim.asBoolean
        if (prim.isNumber) {
            val num = prim.asBigDecimal
            return if (num.scale() <= 0 && num.toBigIntegerExact().bitLength() < 32) {
                num.toInt()
            } else {
                num.toDouble()
            }
        }
        return prim.asString
    }
    if (el.isJsonArray) {
        return el.asJsonArray.map { jsonElementToNative(it) }
    }
    if (el.isJsonObject) {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in el.asJsonObject.entrySet()) {
            out[k] = jsonElementToNative(v)
        }
        return out
    }
    return null
}

private fun cmdParse(scriptPath: String, pretty: Boolean): Int {
    val source: String
    try {
        source = readText(scriptPath)
    } catch (e: Exception) {
        System.err.println("error reading script: $e")
        return 2
    }
    try {
        val ast = parse(source)
        val out = LinkedHashMap<String, Any?>()
        out["ast"] = stripAstMetadata(ast)
        emit(out, pretty)
    } catch (e: ParseError) {
        val errObj = LinkedHashMap<String, Any?>()
        errObj["code"] = "PARSE_ERROR"
        errObj["line"] = e.line
        val out = LinkedHashMap<String, Any?>()
        out["errors"] = listOf(errObj)
        emit(out, pretty)
        return 0
    }
    return 0
}

@Suppress("UNCHECKED_CAST")
private fun cmdValidate(
    scriptPath: String,
    pretty: Boolean,
    varsListPath: String?,
    contextPath: String?,
    enableExtensions: List<String>,
): Int {
    val source: String
    try {
        source = readText(scriptPath)
    } catch (e: Exception) {
        System.err.println("error reading script: $e")
        return 2
    }

    var variables: List<String>? = null
    var context: Map<String, Any?>? = null
    try {
        if (varsListPath != null) {
            variables = readJson(varsListPath) as List<String>
        }
        if (contextPath != null) {
            context = readJson(contextPath) as Map<String, Any?>
        }
    } catch (e: Exception) {
        System.err.println("error reading aux input: $e")
        return 2
    }

    try {
        val ast = parse(source)
        val activeExtensions = enableExtensions.toMutableList()
        if (context != null) {
            val ctxExts = context["extensions"]
            if (ctxExts is List<*>) {
                for (name in ctxExts) {
                    if (name is String && name !in activeExtensions) {
                        activeExtensions.add(name)
                    }
                }
            }
        }
        val exts: List<String>? = if (activeExtensions.isEmpty()) null else activeExtensions
        val sink = validate(ast, variables, context, false, exts)
        emit(sink.toDict(), pretty)
    } catch (e: ParseError) {
        val errObj = LinkedHashMap<String, Any?>()
        errObj["code"] = "PARSE_ERROR"
        errObj["line"] = e.line
        val out = LinkedHashMap<String, Any?>()
        out["errors"] = listOf(errObj)
        out["warnings"] = emptyList<Any>()
        emit(out, pretty)
        return 0
    }
    return 0
}

private fun printUsage() {
    System.err.println(
        """Usage: lacelang-validate <command> [options] <script>

Commands:
  parse     Parse a script; emit AST or parse errors.
  validate  Validate a script; emit errors/warnings.

Options:
  --pretty                  Emit indented JSON instead of a single line.
  --enable-extension NAME   Activate a Lace extension (may be repeated).
  --vars-list PATH          JSON array of declared variable names (validate only).
  --context PATH            JSON object with validator context (validate only).
  --version                 Show version.
  --help                    Show this help.
"""
    )
}

fun main(args: Array<String>) {
    val exitCode = mainImpl(args.toList())
    if (exitCode != 0) {
        System.exit(exitCode)
    }
}

fun mainImpl(args: List<String>): Int {
    if ("--version" in args) {
        println("lacelang-validator $VERSION")
        return 0
    }
    if ("--help" in args || "-h" in args || args.isEmpty()) {
        printUsage()
        return 2
    }

    val command = args[0]
    if (command != "parse" && command != "validate") {
        System.err.println("unknown command: $command")
        printUsage()
        return 2
    }

    var pretty = false
    var varsListPath: String? = null
    var contextPath: String? = null
    val enableExtensions = mutableListOf<String>()
    var scriptPath: String? = null

    var i = 1
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--pretty" -> pretty = true
            arg == "--enable-extension" -> {
                i++
                if (i >= args.size) {
                    System.err.println("--enable-extension requires a value")
                    return 2
                }
                enableExtensions.add(args[i])
            }
            arg == "--vars-list" -> {
                i++
                if (i >= args.size) {
                    System.err.println("--vars-list requires a value")
                    return 2
                }
                varsListPath = args[i]
            }
            arg == "--context" -> {
                i++
                if (i >= args.size) {
                    System.err.println("--context requires a value")
                    return 2
                }
                contextPath = args[i]
            }
            arg.startsWith("-") -> {
                System.err.println("unknown option: $arg")
                return 2
            }
            else -> scriptPath = arg
        }
        i++
    }

    if (scriptPath == null) {
        System.err.println("missing script argument")
        return 2
    }

    return if (command == "parse") {
        cmdParse(scriptPath, pretty)
    } else {
        cmdValidate(scriptPath, pretty, varsListPath, contextPath, enableExtensions)
    }
}
