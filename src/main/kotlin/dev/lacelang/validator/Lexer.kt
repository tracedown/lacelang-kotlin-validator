/**
 * Tokenizer matching lacelang.g4 lexer rules.
 *
 * Longest-match with lookahead order:
 *     RUN_VAR > SCRIPT_VAR > keyword > IDENT > numbers > punctuation
 */
package dev.lacelang.validator

enum class TokenType {
    STRING, INT, FLOAT, BOOL, IDENT,
    RUN_VAR, SCRIPT_VAR,
    KEYWORD,
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACK, RBRACK,
    COMMA, COLON, DOT, SEMI,
    PLUS, MINUS, STAR, SLASH, PERCENT,
    EOF
}

val KEYWORDS: Set<String> = setOf(
    "get", "post", "put", "patch", "delete",
    "expect", "check", "assert", "store", "wait",
    "headers", "body", "cookies", "cookieJar", "clearCookies",
    "redirects", "security", "timeout",
    "follow", "max",
    "rejectInvalidCerts",
    "ms", "action", "retries",
    "status", "bodySize", "totalDelayMs", "dns", "connect", "tls",
    "ttfb", "transfer", "size",
    "value", "op", "match", "mode", "options", "condition",
    "json", "form", "schema",
    "this", "prev", "null",
    "eq", "neq", "lt", "lte", "gt", "gte",
    "and", "or", "not",
)

private val BOOLS: Set<String> = setOf("true", "false")

data class Token(
    val type: TokenType,
    val value: String,
    val line: Int,
    val col: Int,
)

class LexError(
    override val message: String,
    val line: Int,
    val col: Int,
) : Exception("$message at line $line, col $col")

private val ESCAPE_MAP: Map<Char, Char> = mapOf(
    '\\' to '\\',
    '"' to '"',
    'n' to '\n',
    'r' to '\r',
    't' to '\t',
    '$' to '$',
)

private val PUNCT_MAP: Map<Char, TokenType> = mapOf(
    '(' to TokenType.LPAREN, ')' to TokenType.RPAREN,
    '{' to TokenType.LBRACE, '}' to TokenType.RBRACE,
    '[' to TokenType.LBRACK, ']' to TokenType.RBRACK,
    ',' to TokenType.COMMA, ':' to TokenType.COLON, '.' to TokenType.DOT, ';' to TokenType.SEMI,
    '+' to TokenType.PLUS, '-' to TokenType.MINUS,
    '*' to TokenType.STAR, '/' to TokenType.SLASH, '%' to TokenType.PERCENT,
)

class Lexer(private val src: String) {
    private var pos: Int = 0
    private var line: Int = 1
    private var col: Int = 1

    private fun peek(offset: Int = 0): Char {
        val p = pos + offset
        return if (p < src.length) src[p] else '\u0000'
    }

    private fun advance(n: Int = 1): String {
        val chunk = src.substring(pos, minOf(pos + n, src.length))
        for (ch in chunk) {
            if (ch == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        pos += n
        return chunk
    }

    private fun skipTrivia() {
        while (pos < src.length) {
            val ch = peek()
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                advance()
            } else if (ch == '/' && peek(1) == '/') {
                while (pos < src.length && peek() != '\n') {
                    advance()
                }
            } else {
                break
            }
        }
    }

    private fun readString(): Token {
        val startLine = line
        val startCol = col
        advance() // opening quote
        val chars = StringBuilder()
        while (pos < src.length) {
            val ch = peek()
            if (ch == '"') {
                advance()
                return Token(TokenType.STRING, chars.toString(), startLine, startCol)
            }
            if (ch == '\\') {
                val nxt = peek(1)
                val mapped = ESCAPE_MAP[nxt]
                if (mapped != null) {
                    advance(2)
                    chars.append(mapped)
                    continue
                }
                throw LexError("invalid escape \\$nxt", line, col)
            }
            if (ch == '\n') {
                throw LexError("unterminated string literal", startLine, startCol)
            }
            chars.append(ch)
            advance()
        }
        throw LexError("unterminated string literal", startLine, startCol)
    }

    private fun readNumber(): Token {
        val startLine = line
        val startCol = col
        val startPos = pos
        while (pos < src.length && peek().isDigit()) {
            advance()
        }
        if (peek() == '.' && peek(1).isDigit()) {
            advance()
            while (pos < src.length && peek().isDigit()) {
                advance()
            }
            return Token(TokenType.FLOAT, src.substring(startPos, pos), startLine, startCol)
        }
        return Token(TokenType.INT, src.substring(startPos, pos), startLine, startCol)
    }

    private fun readIdentLike(): Token {
        val startLine = line
        val startCol = col
        val startPos = pos
        advance() // first [a-zA-Z_]
        while (pos < src.length) {
            val ch = peek()
            if (ch.isLetterOrDigit() || ch == '_') {
                advance()
            } else {
                break
            }
        }
        val lex = src.substring(startPos, pos)
        if (lex in BOOLS) {
            return Token(TokenType.BOOL, lex, startLine, startCol)
        }
        if (lex in KEYWORDS) {
            return Token(TokenType.KEYWORD, lex, startLine, startCol)
        }
        return Token(TokenType.IDENT, lex, startLine, startCol)
    }

    private fun readDollar(): Token {
        val startLine = line
        val startCol = col
        val startPos = pos

        if (peek(1) == '$') {
            advance(2) // $$
            if (!(peek().isLetter() || peek() == '_')) {
                throw LexError("expected identifier after \$\$", line, col)
            }
            while (pos < src.length && (peek().isLetterOrDigit() || peek() == '_')) {
                advance()
            }
            return Token(TokenType.RUN_VAR, src.substring(startPos, pos), startLine, startCol)
        }

        advance() // $
        if (!(peek().isLetter() || peek() == '_')) {
            throw LexError("expected identifier after \$", line, col)
        }
        while (pos < src.length && (peek().isLetterOrDigit() || peek() == '_')) {
            advance()
        }
        return Token(TokenType.SCRIPT_VAR, src.substring(startPos, pos), startLine, startCol)
    }

    private fun readPunct(): Token {
        val startLine = line
        val startCol = col
        val ch = peek()

        val tt = PUNCT_MAP[ch]
        if (tt != null) {
            advance()
            return Token(tt, ch.toString(), startLine, startCol)
        }
        throw LexError("unexpected character \"$ch\"", startLine, startCol)
    }

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()
        while (true) {
            skipTrivia()
            if (pos >= src.length) {
                tokens.add(Token(TokenType.EOF, "", line, col))
                return tokens
            }
            val ch = peek()
            when {
                ch == '"' -> tokens.add(readString())
                ch == '$' -> tokens.add(readDollar())
                ch.isDigit() -> tokens.add(readNumber())
                ch.isLetter() || ch == '_' -> tokens.add(readIdentLike())
                else -> tokens.add(readPunct())
            }
        }
    }
}

fun tokenize(source: String): List<Token> = Lexer(source).tokenize()
