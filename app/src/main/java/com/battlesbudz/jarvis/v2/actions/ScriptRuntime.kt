package com.battlesbudz.jarvis.v2.actions

/**
 * M5 isolated script runtime: "JarvisScript", a tiny deterministic scripting
 * language for on-phone workflow scripts (D44, T20).
 *
 * Isolation model — why this is a real boundary, not a WebView with a broad
 * native bridge:
 * - The language has NO I/O primitives: no file, network, reflection, or
 *   platform APIs exist in the grammar. A script literally cannot name an
 *   operation the host did not provide.
 * - The only effects available are the host functions in [ScriptHost].
 *   Calls to anything else are denied at runtime ([ScriptResult.Denied]).
 *   The allowlist is explicit and deny-by-default.
 * - CPU is bounded by an operation budget, memory by a string-allocation
 *   budget, output by a cap, and wall-clock by a deadline. Exhaustion
 *   terminates the script ([ScriptResult.Killed]).
 * - [isCancelled] is consulted on every operation, so cancellation is
 *   prompt ([ScriptResult.Cancelled]).
 * - Parsing is bounded too: cancellation is re-checked while statements
 *   are parsed, block nesting is capped ([MAX_BLOCK_DEPTH]), expression
 *   trees are bounded ([MAX_EVAL_DEPTH]), and the total statement count
 *   is capped ([MAX_PARSE_STMTS]). Prefix runs are bounded and evaluated
 *   iteratively rather than recursing once per operator. A pathological source
 *   reports a typed [ScriptResult.Failed] — never a stack overflow, never
 *   unbounded parsing.
 *
 * What this does NOT defend against: a malicious host (the host is part of
 * the app and trusted), or a host function that itself misbehaves (host
 * functions are app code, reviewed like any other app code).
 *
 * Portability: pure Kotlin, no engine dependency. The same interpreter
 * runs on the JVM (unit tests) and on Android (production), so the
 * security properties never depend on a WebView or a platform JS engine.
 * It needs no resources, manifest entries, or native code, so it behaves
 * identically in the release and compact APK variants.
 */

const val SCRIPT_ENGINE_NAME = "jarvis-script/1"
const val DEFAULT_SCRIPT_MAX_TIME_MS = 10_000L
const val DEFAULT_SCRIPT_MAX_MEMORY_KB = 8192L
const val DEFAULT_SCRIPT_MAX_OUTPUT_CHARS = 8192
/** Deepest allowed nesting for recursive script expressions (parens, call args). */
const val MAX_EXPR_DEPTH = 100
/** Deepest expression tree the evaluator may descend; checked during parsing. */
const val MAX_EVAL_DEPTH = 100
/** Maximum operators in one prefix run, which is parsed/evaluated iteratively. */
const val MAX_UNARY_OPS = 4096
/**
 * Deepest allowed nesting for recursive statement blocks (if/while bodies).
 * Bounded separately from [MAX_EXPR_DEPTH]: the expression limit does not
 * cover statement blocks, and deeply nested blocks recurse through
 * parseStmt/parseBlock the same way.
 */
const val MAX_BLOCK_DEPTH = 100
/** Maximum statements parsed in one script; bounds pathologically long flat programs. */
const val MAX_PARSE_STMTS = 20_000

/** Values in the scripting language: numbers, strings, booleans. No objects, no null pointers. */
sealed interface ScriptValue {
    data class Num(val v: Double) : ScriptValue
    data class Str(val v: String) : ScriptValue
    data class Bool(val v: Boolean) : ScriptValue
    data object Null : ScriptValue
}

fun ScriptValue.display(): String = when (this) {
    is ScriptValue.Num -> if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 9.007199254740992E15)
        v.toLong().toString() else v.toString()
    is ScriptValue.Str -> v
    is ScriptValue.Bool -> v.toString()
    is ScriptValue.Null -> ""
}

/** A host function a script may call. [impl] receives evaluated args and an output emitter. */
class ScriptHostFunction(
    val name: String,
    val arity: IntRange,
    val impl: (args: List<ScriptValue>, emit: (String) -> Unit) -> ScriptValue
)

/**
 * The explicit allowlist. Scripts can call exactly these functions;
 * everything else is denied. Empty by default — deny-by-default.
 */
class ScriptHost(val functions: Map<String, ScriptHostFunction>) {
    companion object {
        fun empty(): ScriptHost = ScriptHost(emptyMap())

        /** A host with just `log(message)`: appends text to the run's output. */
        fun withLog(): ScriptHost = ScriptHost(mapOf(
            "log" to ScriptHostFunction("log", 1..1) { args, emit ->
                emit(args[0].display() + "\n")
                ScriptValue.Null
            }
        ))
    }
}

/** Resource bounds for one script run. */
data class ScriptLimits(
    val maxOps: Long = 50_000L,
    val maxStringChars: Long = 262_144L,
    val maxOutputChars: Int = DEFAULT_SCRIPT_MAX_OUTPUT_CHARS,
    val maxTimeMs: Long = DEFAULT_SCRIPT_MAX_TIME_MS
)

sealed interface ScriptResult {
    data class Success(val value: ScriptValue, val output: String, val opsUsed: Long) : ScriptResult
    /** The script tried something outside its grants (unknown host function). */
    data class Denied(val reason: String) : ScriptResult
    /** Parse error or runtime type error. */
    data class Failed(val reason: String) : ScriptResult
    /** Killed by a resource budget (operations, memory, output, wall-clock). */
    data class Killed(val reason: String) : ScriptResult
    data object Cancelled : ScriptResult
}

/** Outcome shape the workflow engine consumes (defined in WorkflowEngine.kt). */
fun ScriptResult.toExecution(): ScriptExecution = when (this) {
    is ScriptResult.Success -> ScriptExecution.Succeeded(value.display())
    is ScriptResult.Denied -> ScriptExecution.Failed("denied: $reason")
    is ScriptResult.Failed -> ScriptExecution.Failed(reason)
    is ScriptResult.Killed -> ScriptExecution.Failed("terminated: $reason")
    is ScriptResult.Cancelled -> ScriptExecution.Cancelled
}

/**
 * Convenience for the workflow engine: run this step's source with the
 * isolated interpreter and map the result to the engine's outcome shape.
 * The Android runtime wires this with its allowlisted host.
 *
 * Least privilege: the script may call only the host functions this step
 * declares in [WorkflowStep.Script.requiredHostFunctions]. A function the
 * host provides but the step did not declare is denied, never silently
 * granted — the supplied host is intersected with the declaration.
 */
fun WorkflowStep.Script.runWithInterpreter(
    host: ScriptHost,
    limits: ScriptLimits = ScriptLimits(),
    isCancelled: () -> Boolean = { false }
): ScriptExecution {
    val declared = requiredHostFunctions.toSet()
    val allowed = if (declared.isEmpty()) emptyMap()
    else host.functions.filterKeys { it in declared }
    return runScript(source, ScriptHost(allowed), limits, isCancelled).toExecution()
}

/**
 * Run [source] with [host]'s allowlist under [limits].
 * Deterministic: same source + same host behavior = same result.
 *
 * Cancellation and the empty-script check come before parsing, and parsing
 * itself is bounded: cancellation is re-checked while statements are
 * parsed, statement blocks and expression trees have nesting limits, and
 * statement/prefix-operator counts are capped. A pre-cancelled run reports [ScriptResult.Cancelled]
 * without doing parse work, and a malformed or pathologically nested
 * script reports a typed [ScriptResult.Failed] — never a stack overflow,
 * never unbounded parsing.
 */
fun runScript(
    source: String,
    host: ScriptHost,
    limits: ScriptLimits = ScriptLimits(),
    isCancelled: () -> Boolean = { false }
): ScriptResult {
    if (isCancelled()) return ScriptResult.Cancelled
    if (source.isBlank()) return ScriptResult.Failed("empty script")
    val program = try {
        ScriptParser(source, isCancelled).parseProgram()
    } catch (e: CancelSignal) {
        return ScriptResult.Cancelled
    } catch (e: ParseSignal) {
        return ScriptResult.Failed("parse error: ${e.message}")
    }
    val runner = Runner(
        host, limits, isCancelled,
        deadlineAtMs = if (limits.maxTimeMs > 0) System.currentTimeMillis() + limits.maxTimeMs else 0L
    )
    return try {
        runner.execProgram(program)
        ScriptResult.Success(runner.result, runner.output.toString(), runner.ops)
    } catch (e: DenySignal) {
        ScriptResult.Denied(e.message ?: "denied")
    } catch (e: RuntimeSignal) {
        ScriptResult.Failed(e.message ?: "runtime error")
    } catch (e: KillSignal) {
        ScriptResult.Killed(e.message ?: "killed")
    } catch (e: CancelSignal) {
        ScriptResult.Cancelled
    } catch (e: Exception) {
        ScriptResult.Failed("internal error: ${e.message}")
    }
}

// ---------------------------------------------------------------------------
// AST
// ---------------------------------------------------------------------------

private sealed interface Stmt {
    data class Let(val name: String, val expr: Expr) : Stmt
    data class Assign(val name: String, val expr: Expr) : Stmt
    data class If(val cond: Expr, val thenBranch: List<Stmt>, val elseBranch: List<Stmt>) : Stmt
    data class While(val cond: Expr, val body: List<Stmt>) : Stmt
    data class ExprStmt(val expr: Expr) : Stmt
    data class Return(val expr: Expr) : Stmt
}

private sealed interface Expr {
    /** Cached as nodes are built, never computed by a recursive AST walk. */
    val evalDepth: Int get() = 1

    data class Num(val v: Double) : Expr
    data class Str(val v: String) : Expr
    data class Bool(val v: Boolean) : Expr
    data class Var(val name: String) : Expr
    data class Unary(val ops: List<String>, val e: Expr) : Expr {
        override val evalDepth = checkedEvalDepth(e.evalDepth + 1)
    }
    data class Binary(val op: String, val l: Expr, val r: Expr) : Expr {
        override val evalDepth = checkedEvalDepth(maxOf(l.evalDepth, r.evalDepth) + 1)
    }
    data class Call(val name: String, val args: List<Expr>) : Expr {
        override val evalDepth = checkedEvalDepth((args.maxOfOrNull { it.evalDepth } ?: 0) + 1)
    }
}

/** Reject the complete program before any host effect, not after starting evaluation. */
private fun checkedEvalDepth(depth: Int): Int {
    if (depth > MAX_EVAL_DEPTH)
        throw ParseSignal("expression is too deeply nested (over $MAX_EVAL_DEPTH evaluation levels)")
    return depth
}

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

private enum class Tk { NUM, STR, IDENT, OP, LPAREN, RPAREN, LBRACE, RBRACE, COMMA, SEMI, EOF }
private data class Tok(val kind: Tk, val text: String, val pos: Int)

private val KEYWORDS = setOf("let", "if", "else", "while", "return", "true", "false")

private fun lex(source: String, isCancelled: () -> Boolean = { false }): List<Tok> {
    val toks = mutableListOf<Tok>()
    var i = 0
    fun err(msg: String): Nothing = throw ParseSignal("$msg at offset $i")
    while (i < source.length) {
        // The token stream is built before any statement is parsed, so a
        // pathological comment/whitespace-only source still answers cancel.
        if ((i and 0xFFFF) == 0 && isCancelled()) throw CancelSignal()
        val c = source[i]
        when {
            c in " \t\n\r" -> i++
            c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                while (i < source.length && source[i] != '\n') i++
            }
            c.isDigit() || (c == '.' && i + 1 < source.length && source[i + 1].isDigit()) -> {
                val s = i
                while (i < source.length && source[i].isDigit()) i++
                if (i < source.length && source[i] == '.') {
                    i++
                    while (i < source.length && source[i].isDigit()) i++
                }
                toks += Tok(Tk.NUM, source.substring(s, i), s)
            }
            c == '"' -> {
                val s = i
                i++
                val sb = StringBuilder()
                while (true) {
                    if (i >= source.length) err("unterminated string")
                    val d = source[i++]
                    if (d == '"') break
                    if (d == '\\') {
                        if (i >= source.length) err("unterminated escape")
                        when (val e = source[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            else -> err("bad escape '\\$e'")
                        }
                    } else sb.append(d)
                }
                toks += Tok(Tk.STR, sb.toString(), s)
            }
            c.isLetter() || c == '_' -> {
                val s = i
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                toks += Tok(Tk.IDENT, source.substring(s, i), s)
            }
            else -> {
                val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
                val op = if (two == "==" || two == "!=" || two == "<=" || two == ">=" ||
                    two == "||" || two == "&&"
                ) {
                    i += 2; two
                } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%' ||
                    c == '<' || c == '>' || c == '!' || c == '=' ||
                    c == '(' || c == ')' || c == '{' || c == '}' || c == ',' || c == ';'
                ) {
                    i++; c.toString()
                } else err("unexpected character '$c'")
                val kind = when (op) {
                    "(" -> Tk.LPAREN
                    ")" -> Tk.RPAREN
                    "{" -> Tk.LBRACE
                    "}" -> Tk.RBRACE
                    "," -> Tk.COMMA
                    ";" -> Tk.SEMI
                    else -> Tk.OP
                }
                toks += Tok(kind, op, i)
            }
        }
    }
    toks += Tok(Tk.EOF, "", source.length)
    return toks
}

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

private open class ScriptSignal(msg: String) : Exception(msg)
private class ParseSignal(msg: String) : ScriptSignal(msg)
private class DenySignal(msg: String) : ScriptSignal(msg)
private class RuntimeSignal(msg: String) : ScriptSignal(msg)
private class KillSignal(msg: String) : ScriptSignal(msg)
private class CancelSignal : ScriptSignal("cancelled")

private class ScriptParser(
    source: String,
    private val isCancelled: () -> Boolean = { false },
    private val maxParseStmts: Int = MAX_PARSE_STMTS
) {
    private val toks = lex(source, isCancelled)
    private var p = 0
    /**
     * Current recursive-expression nesting depth (parenthesized groups,
     * call arguments). Bounded: without this, a few thousand nested
     * parens exhaust the JVM stack during parsing.
     */
    private var exprDepth = 0
    /**
     * Current recursive block nesting depth (if/while bodies). Bounded
     * separately from [exprDepth]: the expression limit does not cover
     * statement blocks, and deeply nested blocks recurse through
     * parseStmt/parseBlock the same way.
     */
    private var blockDepth = 0
    /** Statements parsed so far; bounds pathologically long programs. */
    private var stmtCount = 0

    private fun peek(): Tok = toks[p]
    private fun err(msg: String): Nothing = throw ParseSignal("$msg at offset ${peek().pos}")
    private fun expect(kind: Tk, what: String) {
        if (peek().kind != kind) err("expected $what")
        p++
    }
    private fun expectOp(op: String) {
        val t = peek()
        if (t.kind != Tk.OP || t.text != op) err("expected '$op'")
        p++
    }
    private fun matchOp(op: String): Boolean {
        val t = peek()
        if (t.kind == Tk.OP && t.text == op) { p++; return true }
        return false
    }
    private fun matchIdent(kw: String): Boolean {
        val t = peek()
        if (t.kind == Tk.IDENT && t.text == kw) { p++; return true }
        return false
    }
    private fun parseIdent(what: String): String {
        val t = peek()
        if (t.kind != Tk.IDENT) err("expected $what")
        if (t.text in KEYWORDS) err("'$t.text' is a keyword")
        p++
        return t.text
    }

    fun parseProgram(): List<Stmt> {
        val out = mutableListOf<Stmt>()
        while (peek().kind != Tk.EOF) out += parseStmt()
        return out
    }

    /**
     * Parse-time budget: every statement answers cancellation and counts
     * against the parse statement cap, so parsing stays bounded even for
     * programs that never reach the runtime's operation budget.
     */
    private fun checkParseBudget() {
        if (isCancelled()) throw CancelSignal()
        if (++stmtCount > maxParseStmts)
            throw ParseSignal("script has too many statements (over $maxParseStmts)")
    }

    private fun parseStmt(): Stmt {
        checkParseBudget()
        val t = peek()
        if (t.kind == Tk.IDENT) when (t.text) {
            "let" -> {
                p++
                val name = parseIdent("variable name")
                expectOp("=")
                val e = parseExpr()
                expect(Tk.SEMI, "';'")
                return Stmt.Let(name, e)
            }
            "if" -> {
                p++
                val c = parseExpr()
                val th = parseBlock()
                if (peek().kind == Tk.SEMI) p++ // tolerate "};"
                val el = if (matchIdent("else")) {
                    val b = parseBlock()
                    if (peek().kind == Tk.SEMI) p++
                    b
                } else emptyList()
                return Stmt.If(c, th, el)
            }
            "while" -> {
                p++
                val c = parseExpr()
                val b = parseBlock()
                if (peek().kind == Tk.SEMI) p++ // tolerate "};"
                return Stmt.While(c, b)
            }
            "return" -> {
                p++
                val e = parseExpr()
                expect(Tk.SEMI, "';'")
                return Stmt.Return(e)
            }
        }
        // Assignment: IDENT "=" ...
        if (t.kind == Tk.IDENT && p + 1 < toks.size &&
            toks[p + 1].kind == Tk.OP && toks[p + 1].text == "="
        ) {
            p++
            expectOp("=")
            val e = parseExpr()
            expect(Tk.SEMI, "';'")
            return Stmt.Assign(t.text, e)
        }
        val e = parseExpr()
        expect(Tk.SEMI, "';'")
        return Stmt.ExprStmt(e)
    }

    private fun parseBlock(): List<Stmt> {
        expect(Tk.LBRACE, "'{'")
        if (++blockDepth > MAX_BLOCK_DEPTH)
            err("blocks are too deeply nested (over $MAX_BLOCK_DEPTH levels)")
        try {
            val out = mutableListOf<Stmt>()
            while (peek().kind != Tk.RBRACE) {
                if (peek().kind == Tk.EOF) err("unterminated block")
                out += parseStmt()
            }
            p++
            return out
        } finally {
            blockDepth--
        }
    }

    private fun parseExpr(): Expr {
        if (++exprDepth > MAX_EXPR_DEPTH)
            err("expression is too deeply nested (over $MAX_EXPR_DEPTH levels)")
        try {
            return parseOr()
        } finally {
            exprDepth--
        }
    }

    private fun parseOr(): Expr {
        var e = parseAnd()
        while (matchOp("||")) e = Expr.Binary("||", e, parseAnd())
        return e
    }

    private fun parseAnd(): Expr {
        var e = parseEquality()
        while (matchOp("&&")) e = Expr.Binary("&&", e, parseEquality())
        return e
    }

    private fun parseEquality(): Expr {
        var e = parseComparison()
        while (true) {
            e = when {
                matchOp("==") -> Expr.Binary("==", e, parseComparison())
                matchOp("!=") -> Expr.Binary("!=", e, parseComparison())
                else -> return e
            }
        }
    }

    private fun parseComparison(): Expr {
        var e = parseAddition()
        while (true) {
            e = when {
                matchOp("<=") -> Expr.Binary("<=", e, parseAddition())
                matchOp(">=") -> Expr.Binary(">=", e, parseAddition())
                matchOp("<") -> Expr.Binary("<", e, parseAddition())
                matchOp(">") -> Expr.Binary(">", e, parseAddition())
                else -> return e
            }
        }
    }

    private fun parseAddition(): Expr {
        var e = parseMultiplication()
        while (true) {
            e = when {
                matchOp("+") -> Expr.Binary("+", e, parseMultiplication())
                matchOp("-") -> Expr.Binary("-", e, parseMultiplication())
                else -> return e
            }
        }
    }

    private fun parseMultiplication(): Expr {
        var e = parseUnary()
        while (true) {
            e = when {
                matchOp("*") -> Expr.Binary("*", e, parseUnary())
                matchOp("/") -> Expr.Binary("/", e, parseUnary())
                matchOp("%") -> Expr.Binary("%", e, parseUnary())
                else -> return e
            }
        }
    }

    private fun parseUnary(): Expr {
        // Iterative: a long run of prefix operators ("!!!--...x") must not
        // recurse.
        val ops = mutableListOf<String>()
        var t = peek()
        while (t.kind == Tk.OP && (t.text == "!" || t.text == "-")) {
            if (ops.size == MAX_UNARY_OPS)
                err("too many prefix operators (over $MAX_UNARY_OPS)")
            p++
            ops += t.text
            t = peek()
        }
        val e = parseCall()
        return if (ops.isEmpty()) e else Expr.Unary(ops, e)
    }

    private fun parseCall(): Expr {
        var e = parsePrimary()
        while (peek().kind == Tk.LPAREN) {
            p++
            val args = mutableListOf<Expr>()
            if (peek().kind != Tk.RPAREN) {
                args += parseExpr()
                while (peek().kind == Tk.COMMA) { p++; args += parseExpr() }
            }
            expect(Tk.RPAREN, "')'")
            val name = (e as? Expr.Var)?.name ?: err("only named functions can be called")
            e = Expr.Call(name, args)
        }
        return e
    }

    private fun parsePrimary(): Expr {
        // Keep EOF available to err(): consuming it would make peek() index
        // past the token stream for inputs such as "return" or "let a =".
        if (peek().kind == Tk.EOF) err("expected a value")
        val t = toks[p++]
        return when (t.kind) {
            Tk.NUM -> Expr.Num(t.text.toDoubleOrNull() ?: err("bad number"))
            Tk.STR -> Expr.Str(t.text)
            Tk.IDENT -> when (t.text) {
                "true" -> Expr.Bool(true)
                "false" -> Expr.Bool(false)
                else -> Expr.Var(t.text)
            }
            Tk.LPAREN -> {
                val e = parseExpr()
                expect(Tk.RPAREN, "')'")
                e
            }
            else -> err("expected a value")
        }
    }
}

// ---------------------------------------------------------------------------
// Interpreter
// ---------------------------------------------------------------------------

private class Runner(
    val host: ScriptHost,
    val limits: ScriptLimits,
    val isCancelled: () -> Boolean,
    val deadlineAtMs: Long
) {
    var ops = 0L
    var allocatedChars = 0L
    val vars = HashMap<String, ScriptValue>()
    var result: ScriptValue = ScriptValue.Null
    var returned = false
    val output = StringBuilder()

    fun tick() {
        if (isCancelled()) throw CancelSignal()
        ops++
        if (ops > limits.maxOps) throw KillSignal("operation budget exhausted (over ${limits.maxOps} operations)")
        if (deadlineAtMs > 0 && (ops and 255L) == 0L && System.currentTimeMillis() > deadlineAtMs)
            throw KillSignal("wall-clock deadline exceeded (${limits.maxTimeMs}ms)")
    }

    fun allocChars(n: Int) {
        allocatedChars += n
        if (allocatedChars > limits.maxStringChars)
            throw KillSignal("memory budget exhausted (over ${limits.maxStringChars} chars allocated)")
    }

    fun emit(text: String) {
        if (output.length + text.length > limits.maxOutputChars)
            throw KillSignal("output budget exhausted (over ${limits.maxOutputChars} chars)")
        output.append(text)
    }

    fun execProgram(program: List<Stmt>) {
        for (s in program) {
            if (returned) break
            exec(s)
        }
    }

    private fun exec(s: Stmt) {
        if (returned) return
        tick()
        when (s) {
            is Stmt.Let -> vars[s.name] = eval(s.expr)
            is Stmt.Assign -> {
                if (s.name !in vars) throw RuntimeSignal("assignment to undeclared '${s.name}' (use let first)")
                vars[s.name] = eval(s.expr)
            }
            is Stmt.If -> {
                val c = eval(s.cond)
                if (c !is ScriptValue.Bool) throw RuntimeSignal("if condition must be true/false")
                execBlock(if (c.v) s.thenBranch else s.elseBranch)
            }
            is Stmt.While -> {
                while (true) {
                    val c = eval(s.cond)
                    if (c !is ScriptValue.Bool) throw RuntimeSignal("while condition must be true/false")
                    if (!c.v) break
                    execBlock(s.body)
                    // A `return` inside the body sets [returned]; without
                    // this the loop would keep running until the operation
                    // budget is exhausted instead of returning.
                    if (returned) break
                }
            }
            is Stmt.ExprStmt -> eval(s.expr)
            is Stmt.Return -> {
                result = eval(s.expr)
                returned = true
            }
        }
    }

    private fun execBlock(block: List<Stmt>) {
        for (s in block) {
            if (returned) break
            exec(s)
        }
    }

    private fun eval(e: Expr): ScriptValue {
        tick()
        return when (e) {
            is Expr.Num -> ScriptValue.Num(e.v)
            is Expr.Str -> {
                allocChars(e.v.length)
                ScriptValue.Str(e.v)
            }
            is Expr.Bool -> ScriptValue.Bool(e.v)
            is Expr.Var -> vars[e.name] ?: throw RuntimeSignal("unknown variable '${e.name}'")
            is Expr.Unary -> {
                // eval() already charged the first operator. Charge the rest
                // before evaluating the operand, preserving budget/cancellation
                // checks before any host call, without recursive prefix frames.
                repeat(e.ops.size - 1) { tick() }
                var value = eval(e.e)
                for (op in e.ops.asReversed()) value = evalUnary(op, value)
                value
            }
            is Expr.Binary -> {
                // Short-circuit the logical operators.
                if (e.op == "&&") {
                    val l = eval(e.l)
                    if (l !is ScriptValue.Bool) throw RuntimeSignal("'&&' needs true/false values")
                    if (!l.v) return ScriptValue.Bool(false)
                    val r = eval(e.r)
                    if (r !is ScriptValue.Bool) throw RuntimeSignal("'&&' needs true/false values")
                    return ScriptValue.Bool(r.v)
                }
                if (e.op == "||") {
                    val l = eval(e.l)
                    if (l !is ScriptValue.Bool) throw RuntimeSignal("'||' needs true/false values")
                    if (l.v) return ScriptValue.Bool(true)
                    val r = eval(e.r)
                    if (r !is ScriptValue.Bool) throw RuntimeSignal("'||' needs true/false values")
                    return ScriptValue.Bool(r.v)
                }
                evalBinary(e.op, eval(e.l), eval(e.r))
            }
            is Expr.Call -> evalCall(e.name, e.args)
        }
    }

    private fun evalUnary(op: String, v: ScriptValue): ScriptValue = when (op) {
        "-" -> {
            if (v !is ScriptValue.Num) throw RuntimeSignal("unary '-' needs a number")
            ScriptValue.Num(-v.v)
        }
        "!" -> {
            if (v !is ScriptValue.Bool) throw RuntimeSignal("'!' needs true/false")
            ScriptValue.Bool(!v.v)
        }
        else -> throw RuntimeSignal("unknown operator '$op'")
    }

    private fun num(v: ScriptValue, op: String): Double =
        (v as? ScriptValue.Num)?.v ?: throw RuntimeSignal("'$op' needs numbers")

    private fun evalBinary(op: String, l: ScriptValue, r: ScriptValue): ScriptValue = when (op) {
        "+" -> when {
            l is ScriptValue.Num && r is ScriptValue.Num -> ScriptValue.Num(l.v + r.v)
            l is ScriptValue.Str && r is ScriptValue.Str -> {
                allocChars(l.v.length + r.v.length)
                ScriptValue.Str(l.v + r.v)
            }
            else -> throw RuntimeSignal("'+' needs two numbers or two strings")
        }
        "-" -> ScriptValue.Num(num(l, op) - num(r, op))
        "*" -> ScriptValue.Num(num(l, op) * num(r, op))
        "/" -> {
            val b = num(r, op)
            if (b == 0.0) throw RuntimeSignal("division by zero")
            ScriptValue.Num(num(l, op) / b)
        }
        "%" -> {
            val b = num(r, op)
            if (b == 0.0) throw RuntimeSignal("division by zero")
            ScriptValue.Num(num(l, op) % b)
        }
        "==" -> ScriptValue.Bool(l == r)
        "!=" -> ScriptValue.Bool(l != r)
        "<" -> ScriptValue.Bool(num(l, op) < num(r, op))
        ">" -> ScriptValue.Bool(num(l, op) > num(r, op))
        "<=" -> ScriptValue.Bool(num(l, op) <= num(r, op))
        ">=" -> ScriptValue.Bool(num(l, op) >= num(r, op))
        else -> throw RuntimeSignal("unknown operator '$op'")
    }

    private fun evalCall(name: String, args: List<Expr>): ScriptValue {
        val fn = host.functions[name] ?: throw DenySignal("host function '$name' is not allowed")
        if (args.size !in fn.arity)
            throw RuntimeSignal("host function '$name' takes ${fn.arity} argument(s), got ${args.size}")
        val vals = args.map { eval(it) }
        return try {
            val out = fn.impl(vals) { emit(it) }
            if (out is ScriptValue.Str) allocChars(out.v.length)
            out
        } catch (e: ScriptSignal) {
            throw e
        } catch (e: Exception) {
            throw RuntimeSignal("host function '$name' failed: ${e.message}")
        }
    }
}
