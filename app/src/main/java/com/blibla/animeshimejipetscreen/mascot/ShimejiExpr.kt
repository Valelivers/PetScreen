package com.blibla.animeshimejipetscreen.mascot

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Phase 2 of the Shimeji interpreter: a tiny expression evaluator for the
 * Group-Finity attribute language used in actions.xml / behaviors.xml.
 *
 * Both `${ ... }` and `#{ ... }` wrappers are accepted (Group-Finity uses the
 * first for eager and the second for lazy evaluation; for our purposes we just
 * evaluate when asked). A bare number or expression without wrappers also works.
 *
 * Supported:
 *  - numbers, `( )`, unary `-` / `!`
 *  - arithmetic `+ - * /`, comparison `< > <= >= == !=`
 *  - logical `&& ||`, ternary `cond ? a : b`
 *  - `Math.random()` / `Math.random` (tolerates the `Math.random*100` typo in the
 *    stock XML), `Math.min/max/floor/ceil/round/abs/sqrt`, `Math.PI`, `Math.E`
 *  - dotted variables (e.g. `mascot.anchor.x`) resolved by an [ExprContext]
 *  - method calls (e.g. `mascot.environment.floor.isOn(...)`) resolved by the
 *    context; unknown variables/calls degrade to 0 instead of throwing.
 *
 * Booleans are represented numerically (true = 1.0, false = 0.0).
 */

interface ExprContext {
    /** Resolve a dotted variable like "mascot.anchor.x". Return null if unknown. */
    fun resolveVariable(name: String): Double?

    /** Resolve a function/method call. Return null if unknown. */
    fun resolveCall(name: String, args: List<Double>): Double? = null

    companion object {
        /** Context that knows nothing; every variable/call degrades to 0. */
        val EMPTY = object : ExprContext {
            override fun resolveVariable(name: String): Double? = null
        }
    }
}

object ShimejiExpr {

    fun eval(raw: String?, ctx: ExprContext = ExprContext.EMPTY, default: Double = 0.0): Double {
        val body = unwrap(raw) ?: return default
        if (body.isBlank()) return default
        return try {
            Parser(body, ctx).parse()
        } catch (t: Throwable) {
            default
        }
    }

    fun evalInt(raw: String?, ctx: ExprContext = ExprContext.EMPTY, default: Int = 0): Int =
        eval(raw, ctx, default.toDouble()).let { if (it.isFinite()) it.toInt() else default }

    fun evalBool(raw: String?, ctx: ExprContext = ExprContext.EMPTY, default: Boolean = false): Boolean {
        val body = unwrap(raw) ?: return default
        if (body.isBlank()) return default
        return try {
            Parser(body, ctx).parse() != 0.0
        } catch (t: Throwable) {
            default
        }
    }

    /** Strip a single `${ ... }` or `#{ ... }` wrapper if present. */
    private fun unwrap(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if ((s.startsWith("\${") || s.startsWith("#{")) && s.endsWith("}")) {
            return s.substring(s.indexOf('{') + 1, s.length - 1)
        }
        return s
    }

    // =====================================================================
    // Tokenizer
    // =====================================================================

    private enum class T { NUM, IDENT, OP, EOF }
    private data class Tok(val type: T, val text: String, val num: Double = 0.0)

    private fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++

                c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit()) -> {
                    val start = i
                    while (i < n && (src[i].isDigit() || src[i] == '.')) i++
                    out.add(Tok(T.NUM, src.substring(start, i), src.substring(start, i).toDouble()))
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    // dotted identifier: letters, digits, _, and '.' between word chars
                    while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '.')) i++
                    out.add(Tok(T.IDENT, src.substring(start, i)))
                }

                else -> {
                    // multi-char operators first
                    val two = if (i + 1 < n) src.substring(i, i + 2) else ""
                    if (two in setOf("<=", ">=", "==", "!=", "&&", "||")) {
                        out.add(Tok(T.OP, two)); i += 2
                    } else {
                        out.add(Tok(T.OP, c.toString())); i++
                    }
                }
            }
        }
        out.add(Tok(T.EOF, ""))
        return out
    }

    // =====================================================================
    // Recursive-descent parser / evaluator (precedence climbing)
    // =====================================================================

    private class Parser(src: String, val ctx: ExprContext) {
        private val toks = tokenize(src)
        private var pos = 0

        private fun peek() = toks[pos]
        private fun next() = toks[pos++]
        private fun eat(op: String): Boolean {
            if (peek().type == T.OP && peek().text == op) { pos++; return true }
            return false
        }
        private fun expect(op: String) {
            if (!eat(op)) throw IllegalStateException("expected '$op'")
        }

        fun parse(): Double {
            val v = ternary()
            return v
        }

        private fun ternary(): Double {
            val cond = logicalOr()
            if (eat("?")) {
                val a = ternary()
                expect(":")
                val b = ternary()
                return if (cond != 0.0) a else b
            }
            return cond
        }

        private fun logicalOr(): Double {
            var v = logicalAnd()
            while (peek().type == T.OP && peek().text == "||") {
                next()
                val r = logicalAnd()
                v = if (v != 0.0 || r != 0.0) 1.0 else 0.0
            }
            return v
        }

        private fun logicalAnd(): Double {
            var v = equality()
            while (peek().type == T.OP && peek().text == "&&") {
                next()
                val r = equality()
                v = if (v != 0.0 && r != 0.0) 1.0 else 0.0
            }
            return v
        }

        private fun equality(): Double {
            var v = comparison()
            while (peek().type == T.OP && (peek().text == "==" || peek().text == "!=")) {
                val op = next().text
                val r = comparison()
                v = bool(if (op == "==") v == r else v != r)
            }
            return v
        }

        private fun comparison(): Double {
            var v = additive()
            while (peek().type == T.OP && peek().text in setOf("<", ">", "<=", ">=")) {
                val op = next().text
                val r = additive()
                v = bool(
                    when (op) {
                        "<" -> v < r
                        ">" -> v > r
                        "<=" -> v <= r
                        else -> v >= r
                    }
                )
            }
            return v
        }

        private fun additive(): Double {
            var v = multiplicative()
            while (peek().type == T.OP && (peek().text == "+" || peek().text == "-")) {
                val op = next().text
                val r = multiplicative()
                v = if (op == "+") v + r else v - r
            }
            return v
        }

        private fun multiplicative(): Double {
            var v = unary()
            while (peek().type == T.OP && (peek().text == "*" || peek().text == "/")) {
                val op = next().text
                val r = unary()
                v = if (op == "*") v * r else v / r
            }
            return v
        }

        private fun unary(): Double {
            if (eat("-")) return -unary()
            if (eat("!")) return bool(unary() == 0.0)
            if (eat("+")) return unary()
            return primary()
        }

        private fun primary(): Double {
            val t = peek()
            when (t.type) {
                T.NUM -> { next(); return t.num }
                T.OP -> {
                    if (eat("(")) {
                        val v = ternary()
                        expect(")")
                        return v
                    }
                    throw IllegalStateException("unexpected '${t.text}'")
                }
                T.IDENT -> {
                    next()
                    // function/method call?
                    if (peek().type == T.OP && peek().text == "(") {
                        next() // consume '('
                        val args = ArrayList<Double>()
                        if (!(peek().type == T.OP && peek().text == ")")) {
                            args.add(ternary())
                            while (eat(",")) args.add(ternary())
                        }
                        expect(")")
                        return callFunction(t.text, args)
                    }
                    return resolveIdentifier(t.text)
                }
                T.EOF -> throw IllegalStateException("unexpected end")
            }
        }

        private fun resolveIdentifier(name: String): Double = when (name) {
            "Math.PI" -> Math.PI
            "Math.E" -> Math.E
            // tolerate the stock XML typo `Math.random*100` (no parentheses)
            "Math.random" -> Random.nextDouble()
            "true" -> 1.0
            "false" -> 0.0
            else -> ctx.resolveVariable(name) ?: 0.0
        }

        private fun callFunction(name: String, args: List<Double>): Double = when (name) {
            "Math.random" -> Random.nextDouble()
            "Math.min" -> if (args.isEmpty()) 0.0 else args.reduce { a, b -> min(a, b) }
            "Math.max" -> if (args.isEmpty()) 0.0 else args.reduce { a, b -> max(a, b) }
            "Math.floor" -> floor(args.firstOrNull() ?: 0.0)
            "Math.ceil" -> ceil(args.firstOrNull() ?: 0.0)
            "Math.round" -> (args.firstOrNull() ?: 0.0).roundToLong().toDouble()
            "Math.abs" -> abs(args.firstOrNull() ?: 0.0)
            "Math.sqrt" -> sqrt(args.firstOrNull() ?: 0.0)
            "Math.pow" -> Math.pow(args.getOrElse(0) { 0.0 }, args.getOrElse(1) { 0.0 })
            else -> ctx.resolveCall(name, args) ?: 0.0
        }

        private fun bool(b: Boolean) = if (b) 1.0 else 0.0
    }
}
