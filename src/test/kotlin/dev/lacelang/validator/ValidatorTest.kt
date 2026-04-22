package dev.lacelang.validator

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ValidatorTest {

    private fun _validate(
        source: String,
        variables: List<String>? = null,
        context: Map<String, Any?>? = null,
        prevResultsAvailable: Boolean = false,
        activeExtensions: List<String>? = null,
    ): DiagnosticSink {
        return validate(
            parse(source),
            variables,
            context,
            prevResultsAvailable,
            activeExtensions,
        )
    }

    private fun errorCodes(
        source: String,
        variables: List<String>? = null,
        context: Map<String, Any?>? = null,
        prevResultsAvailable: Boolean = false,
        activeExtensions: List<String>? = null,
    ): List<String> =
        _validate(source, variables, context, prevResultsAvailable, activeExtensions)
            .errors.map { it.code }

    private fun warningCodes(
        source: String,
        variables: List<String>? = null,
        context: Map<String, Any?>? = null,
        prevResultsAvailable: Boolean = false,
        activeExtensions: List<String>? = null,
    ): List<String> =
        _validate(source, variables, context, prevResultsAvailable, activeExtensions)
            .warnings.map { it.code }

    // -- StructuralRules --

    @Test
    fun `AT_LEAST_ONE_CALL`() {
        val sink = validate(astNode("version" to "0.9.0", "calls" to emptyList<Any>()))
        assertContains(sink.errors.map { it.code }, "AT_LEAST_ONE_CALL")
    }

    @Test
    fun `empty script parse error`() {
        try {
            parse("")
            assert(false) { "Expected ParseError" }
        } catch (_: ParseError) {
            // expected
        }
    }

    @Test
    fun `EMPTY_CHAIN`() {
        val call = astNode("method" to "get", "url" to "\$u", "chain" to astNode())
        val sink = validate(astNode("version" to "0.9.0", "calls" to listOf(call)))
        assertContains(sink.errors.map { it.code }, "EMPTY_CHAIN")
    }

    @Test
    fun `valid chain no error`() {
        assertEquals(emptyList(), errorCodes("get(\"\$u\")\n    .expect(status: 200)\n"))
    }

    // -- ChainOrder --

    @Test
    fun `store before assert`() {
        val src = "get(\"\$u\")\n    .store({ a: this.body.x })\n    .assert({ expect: [this.status eq 200] })\n"
        assertContains(errorCodes(src), "CHAIN_ORDER")
    }

    @Test
    fun `correct order`() {
        val src = "get(\"\$u\")\n    .expect(status: 200)\n    .store({ a: this.body.x })\n"
        assertFalse("CHAIN_ORDER" in errorCodes(src))
    }

    // -- ChainDuplicate --

    @Test
    fun `duplicate expect`() {
        val src = "get(\"\$u\")\n    .expect(status: 200)\n    .expect(body: \"ok\")\n"
        assertContains(errorCodes(src), "CHAIN_DUPLICATE")
    }

    @Test
    fun `no duplicate`() {
        val src = "get(\"\$u\")\n    .expect(status: 200)\n    .check(body: \"ok\")\n"
        assertFalse("CHAIN_DUPLICATE" in errorCodes(src))
    }

    // -- EmptyBlocks --

    @Test
    fun `empty scope block`() {
        assertContains(errorCodes("get(\"\$u\")\n    .expect()\n"), "EMPTY_SCOPE_BLOCK")
    }

    @Test
    fun `empty assert block`() {
        assertContains(errorCodes("get(\"\$u\")\n    .assert({})\n"), "EMPTY_ASSERT_BLOCK")
    }

    @Test
    fun `empty store block`() {
        assertContains(errorCodes("get(\"\$u\")\n    .store({})\n"), "EMPTY_STORE_BLOCK")
    }

    // -- VariableChecks --

    @Test
    fun `unknown variable with registry`() {
        val src = "get(\"\$u\")\n    .assert({ expect: [\$unknown eq 1] })\n"
        assertContains(errorCodes(src, variables = listOf("u")), "VARIABLE_UNKNOWN")
    }

    @Test
    fun `known variable`() {
        val src = "get(\"\$u\")\n    .assert({ expect: [\$host eq 1] })\n"
        assertFalse("VARIABLE_UNKNOWN" in errorCodes(src, variables = listOf("u", "host")))
    }

    @Test
    fun `no registry no error`() {
        val src = "get(\"\$u\")\n    .assert({ expect: [\$anything eq 1] })\n"
        assertFalse("VARIABLE_UNKNOWN" in errorCodes(src))
    }

    @Test
    fun `run var reassigned`() {
        val src =
            "get(\"\$u\")\n    .expect(status: 200)\n    .store({ \$\$x: this.status })\n" +
                "get(\"\$u\")\n    .expect(status: 200)\n    .store({ \$\$x: this.status })\n"
        assertContains(errorCodes(src), "RUN_VAR_REASSIGNED")
    }

    @Test
    fun `run var single assignment`() {
        val src = "get(\"\$u\")\n    .expect(status: 200)\n    .store({ \$\$x: this.status })\n"
        assertFalse("RUN_VAR_REASSIGNED" in errorCodes(src))
    }

    // -- ExpressionChecks --

    @Test
    fun `unknown function`() {
        val src = "get(\"\$u\")\n    .assert({ expect: [random() gt 5] })\n"
        assertContains(errorCodes(src), "UNKNOWN_FUNCTION")
    }

    @Test
    fun `known function json`() {
        val src = "post(\"\$u\", { body: json({ a: 1 }) })\n    .expect(status: 200)\n"
        assertFalse("UNKNOWN_FUNCTION" in errorCodes(src))
    }

    @Test
    fun `schema var unknown`() {
        val src = "get(\"\$u\")\n    .expect(body: schema(\$missing))\n"
        assertContains(errorCodes(src, variables = listOf("u")), "SCHEMA_VAR_UNKNOWN")
    }

    @Test
    fun `schema var known`() {
        val src = "get(\"\$u\")\n    .expect(body: schema(\$s))\n"
        assertFalse("SCHEMA_VAR_UNKNOWN" in errorCodes(src, variables = listOf("u", "s")))
    }

    @Test
    fun `wait valid`() {
        val src = "get(\"\$u\")\n    .expect(status: 200)\n    .wait(1000)\n"
        assertFalse("EXPRESSION_SYNTAX" in errorCodes(src))
    }

    // -- ConfigLimits --

    @Test
    fun `redirects max limit`() {
        val src = "get(\"\$u\", { redirects: { max: 999 } })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src, context = mapOf("maxRedirects" to 10)), "REDIRECTS_MAX_LIMIT")
    }

    @Test
    fun `redirects within limit`() {
        val src = "get(\"\$u\", { redirects: { max: 5 } })\n    .expect(status: 200)\n"
        assertFalse("REDIRECTS_MAX_LIMIT" in errorCodes(src, context = mapOf("maxRedirects" to 10)))
    }

    @Test
    fun `timeout ms limit`() {
        val src = "get(\"\$u\", { timeout: { ms: 999999 } })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src, context = mapOf("maxTimeoutMs" to 300000)), "TIMEOUT_MS_LIMIT")
    }

    @Test
    fun `timeout action invalid`() {
        val src = "get(\"\$u\", { timeout: { ms: 5000, action: \"explode\" } })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src), "TIMEOUT_ACTION_INVALID")
    }

    @Test
    fun `timeout retries requires retry`() {
        val src = "get(\"\$u\", { timeout: { ms: 5000, action: \"fail\", retries: 3 } })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src), "TIMEOUT_RETRIES_REQUIRES_RETRY")
    }

    @Test
    fun `timeout retries with retry ok`() {
        val src = "get(\"\$u\", { timeout: { ms: 5000, action: \"retry\", retries: 3 } })\n    .expect(status: 200)\n"
        assertFalse("TIMEOUT_RETRIES_REQUIRES_RETRY" in errorCodes(src))
    }

    // -- CookieJar --

    @Test
    fun `clear cookies wrong jar`() {
        val src = "get(\"\$u\", { cookieJar: \"inherit\", clearCookies: [\"a\"] })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src), "CLEAR_COOKIES_WRONG_JAR")
    }

    @Test
    fun `clear cookies selective ok`() {
        val src = "get(\"\$u\", { cookieJar: \"selective_clear\", clearCookies: [\"a\"] })\n    .expect(status: 200)\n"
        assertFalse("CLEAR_COOKIES_WRONG_JAR" in errorCodes(src))
    }

    @Test
    fun `named empty`() {
        val src = "get(\"\$u\", { cookieJar: \"named:\" })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src), "COOKIE_JAR_NAMED_EMPTY")
    }

    @Test
    fun `jar format invalid`() {
        val src = "get(\"\$u\", { cookieJar: \"invalid_mode\" })\n    .expect(status: 200)\n"
        assertContains(errorCodes(src), "COOKIE_JAR_FORMAT")
    }

    @Test
    fun `jar format named ok`() {
        val src = "get(\"\$u\", { cookieJar: \"named:session\" })\n    .expect(status: 200)\n"
        assertFalse("COOKIE_JAR_FORMAT" in errorCodes(src))
    }

    // -- ScopeChecks --

    @Test
    fun `op value invalid`() {
        val src = "get(\"\$u\")\n    .expect(status: { value: 200, op: \"nope\" })\n"
        assertContains(errorCodes(src), "OP_VALUE_INVALID")
    }

    @Test
    fun `op value valid`() {
        for (op in listOf("lt", "lte", "eq", "neq", "gte", "gt")) {
            val src = "get(\"\$u\")\n    .expect(status: { value: 200, op: \"$op\" })\n"
            assertFalse("OP_VALUE_INVALID" in errorCodes(src))
        }
    }

    @Test
    fun `body size format invalid`() {
        val src = "get(\"\$u\")\n    .expect(bodySize: \"invalid\")\n"
        assertContains(errorCodes(src), "MAX_BODY_FORMAT")
    }

    @Test
    fun `body size format valid`() {
        for (s in listOf("500", "10k", "2kb", "1mb", "5GB")) {
            val src = "get(\"\$u\")\n    .expect(bodySize: \"$s\")\n"
            assertFalse("MAX_BODY_FORMAT" in errorCodes(src), "Expected $s to be valid")
        }
    }

    // -- Warnings --

    @Test
    fun `prev without results`() {
        val src = "get(\"\$u\")\n    .assert({ expect: [prev.outcome eq \"success\"] })\n"
        assertContains(warningCodes(src), "PREV_WITHOUT_RESULTS")
    }

    @Test
    fun `prev with results`() {
        val src = "get(\"\$u\")\n    .assert({ expect: [prev.outcome eq \"success\"] })\n"
        assertFalse("PREV_WITHOUT_RESULTS" in warningCodes(src, prevResultsAvailable = true))
    }

    @Test
    fun `high call count`() {
        val calls = (1..11).joinToString("\n") { "get(\"\$u\")\n    .expect(status: 200)" }
        assertContains(warningCodes(calls), "HIGH_CALL_COUNT")
    }

    @Test
    fun `normal call count`() {
        val calls = (1..5).joinToString("\n") { "get(\"\$u\")\n    .expect(status: 200)" }
        assertFalse("HIGH_CALL_COUNT" in warningCodes(calls))
    }

    @Test
    fun `ext field inactive`() {
        val src = "get(\"\$u\", { timeout: { ms: 5000 }, myExtField: 42 })\n    .expect(status: 200)\n"
        assertContains(warningCodes(src), "EXT_FIELD_INACTIVE")
    }

    @Test
    fun `ext field active`() {
        val src = "get(\"\$u\", { timeout: { ms: 5000 }, myExtField: 42 })\n    .expect(status: 200)\n"
        assertFalse("EXT_FIELD_INACTIVE" in warningCodes(src, activeExtensions = listOf("someExt")))
    }
}
