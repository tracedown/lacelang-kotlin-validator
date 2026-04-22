package dev.lacelang.validator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParserTest {

    private fun stripAstMetadata(node: Any?): Any? {
        if (node is List<*>) return node.map { stripAstMetadata(it) }
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

    @Suppress("UNCHECKED_CAST")
    private fun _parse(src: String): Map<String, Any?> =
        stripAstMetadata(parse(src)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun calls(ast: Map<String, Any?>): List<Map<String, Any?>> =
        ast["calls"] as List<Map<String, Any?>>

    // -- CallParsing --

    @Test
    fun `parse get`() {
        val ast = _parse("get(\"https://example.com\")\n    .expect(status: 200)\n")
        assertEquals("0.9.1", ast["version"])
        assertEquals(1, calls(ast).size)
        assertEquals("get", calls(ast)[0]["method"])
        assertEquals("https://example.com", calls(ast)[0]["url"])
    }

    @Test
    fun `all methods`() {
        for (m in listOf("get", "post", "put", "patch", "delete")) {
            val ast = _parse("$m(\"\$u\")\n    .expect(status: 200)\n")
            assertEquals(m, calls(ast)[0]["method"])
        }
    }

    @Test
    fun `multiple calls`() {
        val ast = _parse(
            "get(\"\$a\")\n    .expect(status: 200)\n" +
                "post(\"\$b\")\n    .expect(status: 201)\n"
        )
        assertEquals(2, calls(ast).size)
        assertEquals("get", calls(ast)[0]["method"])
        assertEquals("post", calls(ast)[1]["method"])
    }

    // -- CallConfig --

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `config headers`() {
        val ast = _parse("get(\"\$u\", {\n    headers: { \"X-Token\": \"abc\" }\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val headers = config["headers"] as Map<String, Any?>
        assertTrue("X-Token" in headers)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `body json`() {
        val ast = _parse("post(\"\$u\", {\n    body: json({ key: \"val\" })\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val body = config["body"] as Map<String, Any?>
        assertEquals("json", body["type"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `body form`() {
        val ast = _parse("post(\"\$u\", {\n    body: form({ key: \"val\" })\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val body = config["body"] as Map<String, Any?>
        assertEquals("form", body["type"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `body raw string`() {
        val ast = _parse("post(\"\$u\", {\n    body: \"raw data\"\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val body = config["body"] as Map<String, Any?>
        assertEquals("raw", body["type"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `config timeout`() {
        val ast = _parse("get(\"\$u\", {\n    timeout: { ms: 5000, action: \"fail\" }\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val timeout = config["timeout"] as Map<String, Any?>
        assertEquals(5000, timeout["ms"])
        assertEquals("fail", timeout["action"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `config redirects`() {
        val ast = _parse("get(\"\$u\", {\n    redirects: { follow: true, max: 3 }\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val redirects = config["redirects"] as Map<String, Any?>
        assertEquals(true, redirects["follow"])
        assertEquals(3, redirects["max"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `config security`() {
        val ast = _parse("get(\"\$u\", {\n    security: { rejectInvalidCerts: false }\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        val security = config["security"] as Map<String, Any?>
        assertEquals(false, security["rejectInvalidCerts"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `config cookie jar`() {
        val ast = _parse("get(\"\$u\", {\n    cookieJar: \"fresh\"\n})\n    .expect(status: 200)\n")
        val config = calls(ast)[0]["config"] as Map<String, Any?>
        assertEquals("fresh", config["cookieJar"])
    }

    // -- ChainMethods --

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `chain expect`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: 200)\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        assertTrue("status" in (chain["expect"] as Map<String, Any?>))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `chain check`() {
        val ast = _parse("get(\"\$u\")\n    .check(status: 200)\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        assertTrue("check" in chain)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `store run var`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: 200)\n    .store({ \$\$x: this.status })\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val store = chain["store"] as Map<String, Any?>
        assertTrue("\$\$x" in store)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `store script var`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: 200)\n    .store({ \$x: this.status })\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val store = chain["store"] as Map<String, Any?>
        assertTrue("\$x" in store)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `store plain key`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: 200)\n    .store({ mykey: this.status })\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val store = chain["store"] as Map<String, Any?>
        assertTrue("mykey" in store)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `assert expect`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.status eq 200] })\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val assertBlock = chain["assert"] as Map<String, Any?>
        val items = assertBlock["expect"] as List<*>
        assertEquals(1, items.size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `assert check`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ check: [this.status eq 200] })\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val assertBlock = chain["assert"] as Map<String, Any?>
        val items = assertBlock["check"] as List<*>
        assertEquals(1, items.size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `chain wait`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: 200)\n    .wait(1000)\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        assertEquals(1000, chain["wait"])
    }

    // -- ScopeNames --

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `all scope names accepted`() {
        for (scope in listOf(
            "status", "body", "headers", "bodySize", "totalDelayMs",
            "dns", "connect", "tls", "ttfb", "transfer", "size", "redirects"
        )) {
            val ast = _parse("get(\"\$u\")\n    .expect($scope: 200)\n")
            val chain = calls(ast)[0]["chain"] as Map<String, Any?>
            val expect = chain["expect"] as Map<String, Any?>
            assertTrue(scope in expect, "Expected scope $scope to be present")
        }
    }

    // -- Expressions --

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `int literal`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.status eq 200] })\n")
        val cond = getCondition(ast)
        assertEquals(200, (cond["right"] as Map<String, Any?>)["value"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `string literal`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.body eq \"ok\"] })\n")
        val cond = getCondition(ast)
        assertEquals("ok", (cond["right"] as Map<String, Any?>)["value"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `bool literal`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.body.valid eq true] })\n")
        val cond = getCondition(ast)
        assertEquals(true, (cond["right"] as Map<String, Any?>)["value"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `null literal`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.body.x eq null] })\n")
        val cond = getCondition(ast)
        assertEquals(null, (cond["right"] as Map<String, Any?>)["value"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `script var`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [\$x eq 1] })\n")
        val cond = getCondition(ast)
        assertEquals("scriptVar", (cond["left"] as Map<String, Any?>)["kind"])
        assertEquals("x", (cond["left"] as Map<String, Any?>)["name"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `run var`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [\$\$x eq 1] })\n")
        val cond = getCondition(ast)
        assertEquals("runVar", (cond["left"] as Map<String, Any?>)["kind"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `this ref`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.status eq 200] })\n")
        val cond = getCondition(ast)
        val left = cond["left"] as Map<String, Any?>
        assertEquals("thisRef", left["kind"])
        assertEquals(listOf("status"), left["path"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `this nested`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.body.data.id eq 1] })\n")
        val cond = getCondition(ast)
        val left = cond["left"] as Map<String, Any?>
        assertEquals(listOf("body", "data", "id"), left["path"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `prev ref`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [prev.outcome eq \"success\"] })\n")
        val cond = getCondition(ast)
        assertEquals("prevRef", (cond["left"] as Map<String, Any?>)["kind"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `binary ops`() {
        for (op in listOf("eq", "neq", "lt", "lte", "gt", "gte")) {
            val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.status $op 200] })\n")
            val cond = getCondition(ast)
            assertEquals(op, cond["op"])
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `arithmetic ops`() {
        for (opSym in listOf("+", "-", "*", "/", "%")) {
            val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.x $opSym 1 eq 0] })\n")
            val cond = getCondition(ast)
            val left = cond["left"] as Map<String, Any?>
            assertEquals("binary", left["kind"])
            assertEquals(opSym, left["op"])
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `logical and`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.a eq 1 and this.b eq 2] })\n")
        val cond = getCondition(ast)
        assertEquals("and", cond["op"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `logical or`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [this.a eq 1 or this.b eq 2] })\n")
        val cond = getCondition(ast)
        assertEquals("or", cond["op"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `not operator`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [not this.body.disabled] })\n")
        val cond = getCondition(ast)
        assertEquals("unary", cond["kind"])
        assertEquals("not", cond["op"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `unary minus`() {
        val ast = _parse("get(\"\$u\")\n    .assert({ expect: [-1 eq this.x] })\n")
        val cond = getCondition(ast)
        val left = cond["left"] as Map<String, Any?>
        assertEquals("unary", left["kind"])
        assertEquals("-", left["op"])
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `array literal`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: [200, 201, 202])\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val expect = chain["expect"] as Map<String, Any?>
        val status = expect["status"] as Map<String, Any?>
        val value = status["value"] as Map<String, Any?>
        assertEquals("arrayLit", value["kind"])
        assertEquals(3, (value["items"] as List<*>).size)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `object literal in store`() {
        val ast = _parse("get(\"\$u\")\n    .expect(status: 200)\n    .store({ \$\$data: this.body })\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val store = chain["store"] as Map<String, Any?>
        assertTrue("\$\$data" in store)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `func call schema`() {
        val ast = _parse("get(\"\$u\")\n    .expect(body: schema(\$s))\n")
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val expect = chain["expect"] as Map<String, Any?>
        val body = expect["body"] as Map<String, Any?>
        val value = body["value"] as Map<String, Any?>
        assertEquals("funcCall", value["kind"])
        assertEquals("schema", value["name"])
    }

    // -- Comments --

    @Test
    fun `comment before call`() {
        val ast = _parse("// a comment\nget(\"\$u\")\n    .expect(status: 200)\n")
        assertEquals(1, calls(ast).size)
    }

    @Test
    fun `comment between calls`() {
        val ast = _parse(
            "get(\"\$a\")\n    .expect(status: 200)\n// gap\nget(\"\$b\")\n    .expect(status: 200)\n"
        )
        assertEquals(2, calls(ast).size)
    }

    // -- ParseErrors --

    @Test
    fun `no method`() {
        assertFailsWith<ParseError> { parse("\"https://example.com\"\n") }
    }

    @Test
    fun `unclosed paren`() {
        assertFailsWith<ParseError> { parse("get(\"url\"\n") }
    }

    @Test
    fun `invalid keyword as method`() {
        assertFailsWith<ParseError> { parse("headers(\"url\")\n") }
    }

    // -- helpers --

    @Suppress("UNCHECKED_CAST")
    private fun getCondition(ast: Map<String, Any?>): Map<String, Any?> {
        val chain = calls(ast)[0]["chain"] as Map<String, Any?>
        val assertBlock = chain["assert"] as Map<String, Any?>
        val items = assertBlock["expect"] as List<Map<String, Any?>>
        return items[0]["condition"] as Map<String, Any?>
    }
}
