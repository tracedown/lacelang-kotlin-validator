/**
 * Recursive descent parser for Lace -- emits AST objects matching `ast.json`.
 *
 * The parser is permissive: it accepts any syntactically well-formed script,
 * including calls to unknown identifiers and extension-shaped fields. The
 * validator (spec S12) is responsible for rejecting semantic errors.
 *
 * Grammar reference: `lacelang.g4`.
 */
package dev.lacelang.validator

// A string literal whose content is *exactly* one of:
//   $$ident        -> run_var
//   $ident         -> script_var
// collapses to the corresponding expression node at parse time.
private val PURE_RUN_RE = Regex("""^\$\$([a-zA-Z_][a-zA-Z0-9_]*)$""")
private val PURE_VAR_RE = Regex("""^\$([a-zA-Z_][a-zA-Z0-9_]*)$""")

typealias AstNode = LinkedHashMap<String, Any?>

fun astNode(vararg pairs: Pair<String, Any?>): AstNode {
    val m = AstNode()
    for ((k, v) in pairs) m[k] = v
    return m
}

private fun stringToExpr(s: String): AstNode {
    var m = PURE_RUN_RE.matchEntire(s)
    if (m != null) {
        return astNode("kind" to "runVar", "name" to m.groupValues[1])
    }
    m = PURE_VAR_RE.matchEntire(s)
    if (m != null) {
        return astNode("kind" to "scriptVar", "name" to m.groupValues[1])
    }
    return astNode("kind" to "literal", "valueType" to "string", "value" to s)
}

const val AST_VERSION = "0.9.1"

private val SCOPE_NAMES: Set<String> = setOf(
    "status", "body", "headers", "bodySize", "totalDelayMs",
    "dns", "connect", "tls", "ttfb", "transfer", "size",
    "redirects",
)

private val CALL_FIELD_KEYWORDS: Set<String> = setOf(
    "headers", "body", "cookies", "cookieJar", "clearCookies",
    "redirects", "security", "timeout",
)

class ParseError(
    override val message: String,
    val line: Int,
) : Exception("line $line: $message")

private val EQ_OPS: Set<String> = setOf("eq", "neq")
private val ORD_OPS: Set<String> = setOf("lt", "lte", "gt", "gte")

class Parser(private val tokens: List<Token>) {
    private var pos: Int = 0

    // -- token helpers --

    private val tok: Token get() = tokens[pos]

    private fun peek(offset: Int = 0): Token = tokens[pos + offset]

    private fun advance(): Token {
        val t = tokens[pos]
        if (pos < tokens.size - 1) {
            pos++
        }
        return t
    }

    private fun check(ttype: TokenType, value: String? = null): Boolean {
        val t = tok
        if (t.type != ttype) return false
        if (value != null && t.value != value) return false
        return true
    }

    private fun match(ttype: TokenType, value: String? = null): Token? {
        if (check(ttype, value)) {
            return advance()
        }
        return null
    }

    private fun expect(ttype: TokenType, value: String? = null): Token {
        if (check(ttype, value)) {
            return advance()
        }
        val want = value ?: ttype.name
        val got = tok.value.ifEmpty { tok.type.name }
        throw ParseError("expected ${quote(want)}, got ${quote(got)}", tok.line)
    }

    private fun expectKw(vararg kws: String): Token {
        if (tok.type == TokenType.KEYWORD && tok.value in kws) {
            return advance()
        }
        val got = tok.value.ifEmpty { tok.type.name }
        throw ParseError("expected keyword one of ${kws.map { quote(it) }}, got ${quote(got)}", tok.line)
    }

    private fun isIdentKey(): Boolean {
        return tok.type == TokenType.IDENT || tok.type == TokenType.KEYWORD
    }

    private fun quote(s: String): String = "\"$s\""

    // -- script --

    fun parseScript(): AstNode {
        val calls = mutableListOf<AstNode>()
        calls.add(parseCall())
        while (!check(TokenType.EOF)) {
            calls.add(parseCall())
        }
        expect(TokenType.EOF)
        return astNode("version" to AST_VERSION, "calls" to calls)
    }

    // -- call --

    private fun parseCall(): AstNode {
        val methodTok = expectKw("get", "post", "put", "patch", "delete")
        expect(TokenType.LPAREN)
        val urlTok = expect(TokenType.STRING)
        val call = astNode("method" to methodTok.value, "url" to urlTok.value)
        if (match(TokenType.COMMA) != null) {
            call["config"] = parseCallConfig()
        }
        expect(TokenType.RPAREN)
        call["chain"] = parseChain()
        return call
    }

    // -- call config --

    private fun parseCallConfig(): AstNode {
        expect(TokenType.LBRACE)
        val config = AstNode()
        val extensions = AstNode()
        while (!check(TokenType.RBRACE)) {
            val key = tok
            if (key.type == TokenType.KEYWORD && key.value in CALL_FIELD_KEYWORDS) {
                advance()
                expect(TokenType.COLON)
                parseCallField(key.value, config)
            } else if (key.type == TokenType.IDENT) {
                advance()
                expect(TokenType.COLON)
                extensions[key.value] = parseOptionsValue()
            } else {
                throw ParseError("unexpected call config field ${quote(key.value)}", key.line)
            }
            if (match(TokenType.COMMA) == null) break
        }
        expect(TokenType.RBRACE)
        if (extensions.isNotEmpty()) {
            config["extensions"] = extensions
        }
        return config
    }

    private fun parseCallField(name: String, config: AstNode) {
        when (name) {
            "headers" -> config["headers"] = objLitToMap(parseObjectLit())
            "body" -> config["body"] = parseBodyValue()
            "cookies" -> config["cookies"] = objLitToMap(parseObjectLit())
            "cookieJar" -> {
                val t = expect(TokenType.STRING)
                config["cookieJar"] = t.value
            }
            "clearCookies" -> {
                expect(TokenType.LBRACK)
                val vals = mutableListOf(expect(TokenType.STRING).value)
                while (match(TokenType.COMMA) != null) {
                    if (check(TokenType.RBRACK)) break
                    vals.add(expect(TokenType.STRING).value)
                }
                expect(TokenType.RBRACK)
                config["clearCookies"] = vals
            }
            "redirects" -> config["redirects"] = parseTypedObj(mapOf("follow" to "BOOL", "max" to "INT"))
            "security" -> config["security"] = parseTypedObj(mapOf("rejectInvalidCerts" to "BOOL"))
            "timeout" -> config["timeout"] = parseTypedObj(mapOf("ms" to "INT", "action" to "STRING", "retries" to "INT"))
        }
    }

    private fun parseTypedObj(fields: Map<String, String>): AstNode {
        expect(TokenType.LBRACE)
        val obj = AstNode()
        val extensions = AstNode()
        while (!check(TokenType.RBRACE)) {
            val key = tok
            if (key.type == TokenType.KEYWORD && key.value in fields) {
                advance()
                expect(TokenType.COLON)
                when (fields[key.value]) {
                    "BOOL" -> {
                        val t = expect(TokenType.BOOL)
                        obj[key.value] = t.value == "true"
                    }
                    "INT" -> {
                        val t = expect(TokenType.INT)
                        obj[key.value] = t.value.toInt()
                    }
                    "STRING" -> {
                        val t = expect(TokenType.STRING)
                        obj[key.value] = t.value
                    }
                }
            } else if (key.type == TokenType.IDENT) {
                advance()
                expect(TokenType.COLON)
                extensions[key.value] = parseOptionsValue()
            } else {
                throw ParseError("unexpected field ${quote(key.value)}", key.line)
            }
            if (match(TokenType.COMMA) == null) break
        }
        expect(TokenType.RBRACE)
        if (extensions.isNotEmpty()) {
            obj["extensions"] = extensions
        }
        return obj
    }

    private fun parseBodyValue(): AstNode {
        if (check(TokenType.KEYWORD, "json")) {
            advance()
            expect(TokenType.LPAREN)
            val v = parseObjectLit()
            expect(TokenType.RPAREN)
            return astNode("type" to "json", "value" to v)
        }
        if (check(TokenType.KEYWORD, "form")) {
            advance()
            expect(TokenType.LPAREN)
            val v = parseObjectLit()
            expect(TokenType.RPAREN)
            return astNode("type" to "form", "value" to v)
        }
        if (check(TokenType.STRING)) {
            return astNode("type" to "raw", "value" to advance().value)
        }
        throw ParseError("expected body value, got ${quote(tok.value)}", tok.line)
    }

    // -- chain --

    private fun parseChain(): AstNode {
        val chain = AstNode()
        val order = mutableListOf<String>()
        val duplicates = mutableListOf<String>()

        if (!check(TokenType.DOT)) {
            throw ParseError("expected chain method after call arguments", tok.line)
        }

        while (match(TokenType.DOT) != null) {
            val nameTok = expectKw("expect", "check", "assert", "store", "wait")
            val name = nameTok.value
            if (name in chain) {
                duplicates.add(name)
            }
            order.add(name)
            expect(TokenType.LPAREN)
            when (name) {
                "expect", "check" -> chain[name] = parseScopeList()
                "assert" -> chain[name] = parseAssertBlock()
                "store" -> chain[name] = parseStoreBlock()
                "wait" -> {
                    val t = expect(TokenType.INT)
                    chain[name] = t.value.toInt()
                }
            }
            expect(TokenType.RPAREN)
        }

        chain["__order"] = order
        if (duplicates.isNotEmpty()) {
            chain["__duplicates"] = duplicates
        }
        return chain
    }

    // -- scope blocks --

    private fun parseScopeList(): AstNode {
        val block = AstNode()
        val duplicates = mutableListOf<String>()
        if (check(TokenType.RPAREN)) {
            block["__order"] = mutableListOf<String>()
            return block
        }
        val order = mutableListOf<String>()
        while (true) {
            val name = parseScopeName()
            expect(TokenType.COLON)
            val v = parseScopeVal()
            if (name in block) {
                duplicates.add(name)
            }
            block[name] = v
            order.add(name)
            if (match(TokenType.COMMA) == null) break
            if (check(TokenType.RPAREN)) break
        }
        block["__order"] = order
        if (duplicates.isNotEmpty()) {
            block["__duplicates"] = duplicates
        }
        return block
    }

    private fun parseScopeName(): String {
        if (tok.type == TokenType.KEYWORD && tok.value in SCOPE_NAMES) {
            return advance().value
        }
        throw ParseError("expected scope name, got ${quote(tok.value)}", tok.line)
    }

    private fun parseScopeVal(): AstNode {
        // Full form: { value:, op:, options: }
        if (check(TokenType.LBRACE)) {
            return parseScopeFullForm()
        }
        // Array shorthand: [ expr, ... ]
        if (check(TokenType.LBRACK)) {
            return astNode("value" to parseArrayLit())
        }
        // Scalar shorthand: single expression
        return astNode("value" to parseExpr())
    }

    private fun parseScopeFullForm(): AstNode {
        expect(TokenType.LBRACE)
        val out = AstNode()
        while (!check(TokenType.RBRACE)) {
            val k = tok
            when {
                k.type == TokenType.KEYWORD && k.value == "value" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["value"] = if (check(TokenType.LBRACK)) parseArrayLit() else parseExpr()
                }
                k.type == TokenType.KEYWORD && k.value == "op" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["op"] = expect(TokenType.STRING).value
                }
                k.type == TokenType.KEYWORD && k.value == "match" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["match"] = expect(TokenType.STRING).value
                }
                k.type == TokenType.KEYWORD && k.value == "mode" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["mode"] = expect(TokenType.STRING).value
                }
                k.type == TokenType.KEYWORD && k.value == "options" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["options"] = parseOptionsObj()
                }
                else -> throw ParseError("unexpected scope field ${quote(k.value)}", k.line)
            }
            if (match(TokenType.COMMA) == null) break
        }
        expect(TokenType.RBRACE)
        if ("value" !in out) {
            throw ParseError("scope full form requires 'value'", tok.line)
        }
        return out
    }

    // -- options (extension passthrough) --

    private fun parseOptionsObj(): AstNode {
        expect(TokenType.LBRACE)
        val out = AstNode()
        if (match(TokenType.RBRACE) != null) {
            return out
        }
        while (true) {
            val key = expect(TokenType.IDENT)
            expect(TokenType.COLON)
            out[key.value] = parseOptionsValue()
            if (match(TokenType.COMMA) == null) break
            if (check(TokenType.RBRACE)) break
        }
        expect(TokenType.RBRACE)
        return out
    }

    private fun parseOptionsValue(): Any? {
        if (check(TokenType.LBRACE)) {
            return parseObjectLit()
        }
        if (check(TokenType.LBRACK)) {
            val items = mutableListOf<Any?>()
            expect(TokenType.LBRACK)
            if (!check(TokenType.RBRACK)) {
                while (true) {
                    items.add(parseOptionsValue())
                    if (match(TokenType.COMMA) == null) break
                    if (check(TokenType.RBRACK)) break
                }
            }
            expect(TokenType.RBRACK)
            return astNode("kind" to "arrayLit", "items" to items)
        }
        return parseExpr()
    }

    // -- assert / store --

    private fun parseAssertBlock(): AstNode {
        expect(TokenType.LBRACE)
        val out = AstNode()
        while (!check(TokenType.RBRACE)) {
            val k = tok
            if (k.type == TokenType.KEYWORD && (k.value == "expect" || k.value == "check")) {
                advance()
                expect(TokenType.COLON)
                expect(TokenType.LBRACK)
                val items = mutableListOf<AstNode>()
                if (!check(TokenType.RBRACK)) {
                    while (true) {
                        items.add(parseConditionItem())
                        if (match(TokenType.COMMA) == null) break
                        if (check(TokenType.RBRACK)) break
                    }
                }
                expect(TokenType.RBRACK)
                out[k.value] = items
            } else {
                throw ParseError("unexpected assert clause ${quote(k.value)}", k.line)
            }
            if (match(TokenType.COMMA) == null) break
        }
        expect(TokenType.RBRACE)
        return out
    }

    private fun parseConditionItem(): AstNode {
        if (check(TokenType.LBRACE)) {
            // full form -- but only if a known field appears; peek ahead.
            val save = pos
            advance()
            val first = tok
            pos = save
            if (first.type == TokenType.KEYWORD && (first.value == "condition" || first.value == "options")) {
                return parseConditionFullForm()
            }
        }
        return astNode("condition" to parseExpr())
    }

    private fun parseConditionFullForm(): AstNode {
        expect(TokenType.LBRACE)
        val out = AstNode()
        while (!check(TokenType.RBRACE)) {
            val k = tok
            when {
                k.type == TokenType.KEYWORD && k.value == "condition" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["condition"] = parseExpr()
                }
                k.type == TokenType.KEYWORD && k.value == "options" -> {
                    advance()
                    expect(TokenType.COLON)
                    out["options"] = parseOptionsObj()
                }
                else -> throw ParseError("unexpected condition field ${quote(k.value)}", k.line)
            }
            if (match(TokenType.COMMA) == null) break
        }
        expect(TokenType.RBRACE)
        if ("condition" !in out) {
            throw ParseError("condition full form requires 'condition'", tok.line)
        }
        return out
    }

    private fun parseStoreBlock(): AstNode {
        expect(TokenType.LBRACE)
        val out = AstNode()
        if (match(TokenType.RBRACE) != null) {
            return out
        }
        while (true) {
            val (key, srcKey) = parseStoreKey()
            expect(TokenType.COLON)
            val v = parseExpr()
            val scope = if (srcKey.startsWith("\$\$")) "run" else "writeback"
            out[key] = astNode("scope" to scope, "value" to v)
            if (match(TokenType.COMMA) == null) break
            if (check(TokenType.RBRACE)) break
        }
        expect(TokenType.RBRACE)
        return out
    }

    private fun parseStoreKey(): Pair<String, String> {
        val t = tok
        if (t.type == TokenType.RUN_VAR) {
            advance()
            return t.value to t.value
        }
        if (t.type == TokenType.SCRIPT_VAR) {
            advance()
            return t.value to t.value
        }
        if (t.type == TokenType.STRING) {
            advance()
            return t.value to t.value
        }
        if (isIdentKey()) {
            advance()
            return t.value to t.value
        }
        throw ParseError("unexpected store key ${quote(t.value)}", t.line)
    }

    // -- expressions --
    // Precedence climb: or < and < eq < ord < addsub < muldiv < unary < primary

    fun parseExpr(): AstNode {
        return parseOr()
    }

    private fun parseOr(): AstNode {
        var left = parseAnd()
        while (tok.type == TokenType.KEYWORD && tok.value == "or") {
            advance()
            val right = parseAnd()
            left = astNode("kind" to "binary", "op" to "or", "left" to left, "right" to right)
        }
        return left
    }

    private fun parseAnd(): AstNode {
        var left = parseEq()
        while (tok.type == TokenType.KEYWORD && tok.value == "and") {
            advance()
            val right = parseEq()
            left = astNode("kind" to "binary", "op" to "and", "left" to left, "right" to right)
        }
        return left
    }

    private fun parseEq(): AstNode {
        var left = parseOrd()
        if (tok.type == TokenType.KEYWORD && tok.value in EQ_OPS) {
            val op = advance().value
            val right = parseOrd()
            left = astNode("kind" to "binary", "op" to op, "left" to left, "right" to right)
            if (tok.type == TokenType.KEYWORD && tok.value in EQ_OPS) {
                throw ParseError(
                    "chained comparison ${quote(op)}: comparisons do not associate; " +
                        "use `and`/`or` with parentheses to combine",
                    tok.line,
                )
            }
        }
        return left
    }

    private fun parseOrd(): AstNode {
        var left = parseAddsub()
        if (tok.type == TokenType.KEYWORD && tok.value in ORD_OPS) {
            val op = advance().value
            val right = parseAddsub()
            left = astNode("kind" to "binary", "op" to op, "left" to left, "right" to right)
            if (tok.type == TokenType.KEYWORD && tok.value in ORD_OPS) {
                throw ParseError(
                    "chained comparison ${quote(op)}: comparisons do not associate; " +
                        "use `and`/`or` with parentheses to combine",
                    tok.line,
                )
            }
        }
        return left
    }

    private fun parseAddsub(): AstNode {
        var left = parseMuldiv()
        while (tok.type == TokenType.PLUS || tok.type == TokenType.MINUS) {
            val op = advance().value
            val right = parseMuldiv()
            left = astNode("kind" to "binary", "op" to op, "left" to left, "right" to right)
        }
        return left
    }

    private fun parseMuldiv(): AstNode {
        var left = parseUnary()
        while (tok.type == TokenType.STAR || tok.type == TokenType.SLASH || tok.type == TokenType.PERCENT) {
            val op = advance().value
            val right = parseUnary()
            left = astNode("kind" to "binary", "op" to op, "left" to left, "right" to right)
        }
        return left
    }

    private fun parseUnary(): AstNode {
        if (tok.type == TokenType.KEYWORD && tok.value == "not") {
            advance()
            return astNode("kind" to "unary", "op" to "not", "operand" to parseUnary())
        }
        if (tok.type == TokenType.MINUS) {
            advance()
            return astNode("kind" to "unary", "op" to "-", "operand" to parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): AstNode {
        val t = tok
        if (t.type == TokenType.LPAREN) {
            advance()
            val e = parseExpr()
            expect(TokenType.RPAREN)
            return e
        }
        if (t.type == TokenType.LBRACK) {
            return parseArrayLit()
        }
        if (t.type == TokenType.LBRACE) {
            return parseObjectLit()
        }
        if (t.type == TokenType.KEYWORD && t.value == "this") {
            return parseThisRef()
        }
        if (t.type == TokenType.KEYWORD && t.value == "prev") {
            return parsePrevRef()
        }
        if (t.type == TokenType.RUN_VAR) {
            advance()
            return parseVarTail(astNode("kind" to "runVar", "name" to t.value.substring(2), "path" to mutableListOf<Any?>()))
        }
        if (t.type == TokenType.SCRIPT_VAR) {
            advance()
            return parseVarTail(astNode("kind" to "scriptVar", "name" to t.value.substring(1), "path" to mutableListOf<Any?>()))
        }
        if (t.type == TokenType.STRING) {
            advance()
            return stringToExpr(t.value)
        }
        if (t.type == TokenType.INT) {
            advance()
            return astNode("kind" to "literal", "valueType" to "int", "value" to t.value.toInt())
        }
        if (t.type == TokenType.FLOAT) {
            advance()
            return astNode("kind" to "literal", "valueType" to "float", "value" to t.value.toDouble())
        }
        if (t.type == TokenType.BOOL) {
            advance()
            return astNode("kind" to "literal", "valueType" to "bool", "value" to (t.value == "true"))
        }
        if (t.type == TokenType.KEYWORD && t.value == "null") {
            advance()
            return astNode("kind" to "literal", "valueType" to "null", "value" to null)
        }
        // function call: IDENT or keyword in {json, form, schema} followed by (
        if ((t.type == TokenType.IDENT || (t.type == TokenType.KEYWORD && t.value in setOf("json", "form", "schema")))
            && peek(1).type == TokenType.LPAREN
        ) {
            return parseFuncCall()
        }
        throw ParseError("unexpected token ${quote(t.value)}", t.line)
    }

    private fun parseVarTail(node: AstNode): AstNode {
        val path = mutableListOf<AstNode>()
        while (true) {
            if (match(TokenType.DOT) != null) {
                if (!isIdentKey()) {
                    throw ParseError("expected field name after '.'", tok.line)
                }
                path.add(astNode("type" to "field", "name" to advance().value))
            } else if (match(TokenType.LBRACK) != null) {
                val idx = expect(TokenType.INT)
                expect(TokenType.RBRACK)
                path.add(astNode("type" to "index", "index" to idx.value.toInt()))
            } else {
                break
            }
        }
        if (path.isNotEmpty()) {
            node["path"] = path
        } else {
            node.remove("path")
        }
        return node
    }

    private fun parseThisRef(): AstNode {
        advance() // this
        val path = mutableListOf<String>()
        while (match(TokenType.DOT) != null) {
            if (!isIdentKey()) {
                throw ParseError("expected field name after '.'", tok.line)
            }
            path.add(advance().value)
        }
        if (path.isEmpty()) {
            throw ParseError("'this' requires at least one '.field'", tok.line)
        }
        return astNode("kind" to "thisRef", "path" to path)
    }

    private fun parsePrevRef(): AstNode {
        advance() // prev
        val path = mutableListOf<AstNode>()
        while (true) {
            if (match(TokenType.DOT) != null) {
                if (!isIdentKey()) {
                    throw ParseError("expected field name after '.'", tok.line)
                }
                path.add(astNode("type" to "field", "name" to advance().value))
            } else if (match(TokenType.LBRACK) != null) {
                val t = expect(TokenType.INT)
                expect(TokenType.RBRACK)
                path.add(astNode("type" to "index", "index" to t.value.toInt()))
            } else {
                break
            }
        }
        return astNode("kind" to "prevRef", "path" to path)
    }

    private fun parseFuncCall(): AstNode {
        val name = advance().value
        expect(TokenType.LPAREN)
        val args = mutableListOf<Any?>()
        if (!check(TokenType.RPAREN)) {
            while (true) {
                if (check(TokenType.LBRACE)) {
                    args.add(parseObjectLit())
                } else {
                    args.add(parseExpr())
                }
                if (match(TokenType.COMMA) == null) break
                if (check(TokenType.RPAREN)) break
            }
        }
        expect(TokenType.RPAREN)
        return astNode("kind" to "funcCall", "name" to name, "args" to args)
    }

    // -- object / array literals --

    fun parseObjectLit(): AstNode {
        expect(TokenType.LBRACE)
        val entries = mutableListOf<AstNode>()
        if (match(TokenType.RBRACE) != null) {
            return astNode("kind" to "objectLit", "entries" to entries)
        }
        while (true) {
            val k = tok
            val key: String = when {
                k.type == TokenType.STRING -> advance().value
                isIdentKey() -> advance().value
                else -> throw ParseError("expected object key, got ${quote(k.value)}", k.line)
            }
            expect(TokenType.COLON)
            entries.add(astNode("key" to key, "value" to parseExpr()))
            if (match(TokenType.COMMA) == null) break
            if (check(TokenType.RBRACE)) break
        }
        expect(TokenType.RBRACE)
        return astNode("kind" to "objectLit", "entries" to entries)
    }

    fun parseArrayLit(): AstNode {
        expect(TokenType.LBRACK)
        val items = mutableListOf<AstNode>()
        if (match(TokenType.RBRACK) != null) {
            return astNode("kind" to "arrayLit", "items" to items)
        }
        while (true) {
            items.add(parseExpr())
            if (match(TokenType.COMMA) == null) break
            if (check(TokenType.RBRACK)) break
        }
        expect(TokenType.RBRACK)
        return astNode("kind" to "arrayLit", "items" to items)
    }

    // -- small helpers --

    private fun objLitToMap(lit: AstNode): AstNode {
        val out = AstNode()
        @Suppress("UNCHECKED_CAST")
        val entries = lit["entries"] as List<AstNode>
        for (e in entries) {
            out[e["key"] as String] = e["value"]
        }
        return out
    }
}

fun parse(source: String): AstNode {
    val tokens = tokenize(source)
    return Parser(tokens).parseScript()
}
