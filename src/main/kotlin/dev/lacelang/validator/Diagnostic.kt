/**
 * Canonical Lace validation error codes and helpers.
 *
 * The code set must match `specs/error-codes.json` in the lacelang repo.
 * Every validator and executor emit the same code for the same condition
 * so conformance vectors are implementation-independent.
 */
package dev.lacelang.validator

data class Diagnostic(
    val code: String,
    val callIndex: Int? = null,
    val chainMethod: String? = null,
    val field: String? = null,
    val line: Int? = null,
    val detail: String? = null,
) {
    fun toDict(): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out["code"] = code
        if (callIndex != null) out["callIndex"] = callIndex
        if (chainMethod != null) out["chainMethod"] = chainMethod
        if (field != null) out["field"] = field
        if (line != null) out["line"] = line
        if (detail != null) out["detail"] = detail
        return out
    }
}

class DiagnosticSink {
    val errors: MutableList<Diagnostic> = mutableListOf()
    val warnings: MutableList<Diagnostic> = mutableListOf()

    fun error(
        code: String,
        callIndex: Int? = null,
        chainMethod: String? = null,
        field: String? = null,
        line: Int? = null,
        detail: String? = null,
    ) {
        errors.add(Diagnostic(code, callIndex, chainMethod, field, line, detail))
    }

    fun warning(
        code: String,
        callIndex: Int? = null,
        chainMethod: String? = null,
        field: String? = null,
        line: Int? = null,
        detail: String? = null,
    ) {
        warnings.add(Diagnostic(code, callIndex, chainMethod, field, line, detail))
    }

    fun toDict(): LinkedHashMap<String, Any> {
        val out = LinkedHashMap<String, Any>()
        out["errors"] = errors.map { it.toDict() }
        out["warnings"] = warnings.map { it.toDict() }
        return out
    }
}
