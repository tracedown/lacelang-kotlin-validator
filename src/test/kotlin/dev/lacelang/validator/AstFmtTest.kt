package dev.lacelang.validator

import kotlin.test.Test
import kotlin.test.assertEquals

class AstFmtTest {

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

    private fun expr(src: String): Any? = Parser(tokenize(src)).parseExpr()

    /** fmt() output must parse back to the same AST. */
    private fun assertRoundTrips(src: String): String {
        val rendered = fmt(expr(src))
        assertEquals(
            stripAstMetadata(expr(src)),
            stripAstMetadata(expr(rendered)),
            "rendered expression did not re-parse to the same AST: $rendered",
        )
        return rendered
    }

    // -- object keys --

    @Test
    fun `identifier keys stay bare`() {
        assertEquals("{ok: true}", assertRoundTrips("{ok: true}"))
        assertEquals("{ok: true}", assertRoundTrips("""{"ok": true}"""))
        assertEquals("{_x1: 1, y: 2}", assertRoundTrips("{_x1: 1, y: 2}"))
    }

    @Test
    fun `numeric keys are quoted`() {
        assertEquals("""{"404": 2}""", assertRoundTrips("""{"404": 2}"""))
        assertEquals("""{"0": 1}""", assertRoundTrips("""{"0": 1}"""))
    }

    @Test
    fun `hyphenated keys are quoted`() {
        assertEquals("""{"content-type": 1}""", assertRoundTrips("""{"content-type": 1}"""))
        assertEquals("""{"x.y": 1}""", assertRoundTrips("""{"x.y": 1}"""))
        assertEquals("""{"": 1}""", assertRoundTrips("""{"": 1}"""))
    }

    @Test
    fun `quoted keys escape quotes backslashes and control characters`() {
        assertEquals("""{"a\"b": 1}""", assertRoundTrips("""{"a\"b": 1}"""))
        assertEquals("""{"a\\b": 1}""", assertRoundTrips("""{"a\\b": 1}"""))
        assertEquals("""{"a\tb": 1}""", assertRoundTrips("""{"a\tb": 1}"""))
        assertEquals("""{"a\nb": 1}""", assertRoundTrips("""{"a\nb": 1}"""))
    }

    @Test
    fun `mixed keys in a nested expression`() {
        assertEquals(
            """count([{"content-type": 1, "404": 2, ok: true}]) eq 1""",
            assertRoundTrips("""count([{"content-type": 1, "404": 2, ok: true}]) eq 1"""),
        )
    }

    @Test
    fun `empty object literal`() {
        assertEquals("{}", assertRoundTrips("{}"))
    }
}
