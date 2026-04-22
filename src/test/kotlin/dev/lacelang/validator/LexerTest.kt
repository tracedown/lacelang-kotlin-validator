package dev.lacelang.validator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LexerTest {

    private fun tokens(src: String): List<Pair<String, String>> =
        tokenize(src)
            .filter { it.type != TokenType.EOF }
            .map { it.type.name to it.value }

    // -- BasicTokens --

    @Test
    fun `string token`() {
        assertEquals(listOf("STRING" to "hello"), tokens("\"hello\""))
    }

    @Test
    fun `int token`() {
        assertEquals(listOf("INT" to "42"), tokens("42"))
    }

    @Test
    fun `float token`() {
        assertEquals(listOf("FLOAT" to "3.14"), tokens("3.14"))
    }

    @Test
    fun `bool tokens`() {
        assertEquals(listOf("BOOL" to "true", "BOOL" to "false"), tokens("true false"))
    }

    @Test
    fun `ident token`() {
        assertEquals(listOf("IDENT" to "myVar"), tokens("myVar"))
    }

    @Test
    fun `keyword token`() {
        assertEquals(listOf("KEYWORD" to "get"), tokens("get"))
    }

    @Test
    fun `run_var token`() {
        assertEquals("RUN_VAR", tokens("\$\$token")[0].first)
    }

    @Test
    fun `script_var token`() {
        assertEquals("SCRIPT_VAR", tokens("\$host")[0].first)
    }

    // -- EscapeSequences --

    @Test
    fun `escape newline`() {
        assertEquals(listOf("STRING" to "line\n"), tokens("\"line\\n\""))
    }

    @Test
    fun `escape tab`() {
        assertEquals(listOf("STRING" to "col\t"), tokens("\"col\\t\""))
    }

    @Test
    fun `escape backslash`() {
        assertEquals(listOf("STRING" to "path\\"), tokens("\"path\\\\\""))
    }

    @Test
    fun `escape quote`() {
        assertEquals(listOf("STRING" to "say\"hi\""), tokens("\"say\\\"hi\\\"\""))
    }

    @Test
    fun `escape dollar`() {
        assertEquals(listOf("STRING" to "price\$100"), tokens("\"price\\\$100\""))
    }

    @Test
    fun `escape carriage return`() {
        assertEquals(listOf("STRING" to "cr\r"), tokens("\"cr\\r\""))
    }

    @Test
    fun `invalid escape`() {
        assertFailsWith<LexError> { tokenize("\"\\z\"") }
    }

    // -- Punctuation --

    @Test
    fun `all punctuation`() {
        val types = tokens("(){}[],:.")!!.map { it.first }
        assertEquals(
            listOf("LPAREN", "RPAREN", "LBRACE", "RBRACE", "LBRACK", "RBRACK", "COMMA", "COLON", "DOT"),
            types
        )
    }

    @Test
    fun `arithmetic operators`() {
        val types = tokens("+ - * / %").map { it.first }
        assertEquals(listOf("PLUS", "MINUS", "STAR", "SLASH", "PERCENT"), types)
    }

    // -- Comments --

    @Test
    fun `line comment skipped`() {
        assertEquals(listOf("KEYWORD" to "get"), tokens("// this is a comment\nget"))
    }

    @Test
    fun `inline comment`() {
        assertEquals(listOf("KEYWORD" to "get", "STRING" to "url"), tokens("get // comment\n\"url\""))
    }

    // -- EdgeCases --

    @Test
    fun `empty string`() {
        assertEquals(listOf("STRING" to ""), tokens("\"\""))
    }

    @Test
    fun `unterminated string`() {
        assertFailsWith<LexError> { tokenize("\"hello") }
    }

    @Test
    fun `whitespace ignored`() {
        assertEquals(listOf("KEYWORD" to "get"), tokens("   get   "))
    }

    @Test
    fun `unknown char`() {
        assertFailsWith<LexError> { tokenize("#") }
    }
}
