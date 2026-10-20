package com.example.domain.pdf

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

sealed interface MathAstNode {
    data class Sequence(val children: List<MathAstNode>) : MathAstNode
    data class SymbolOrText(val text: String, val isItalicVariable: Boolean = false) : MathAstNode
    data class Fraction(val numerator: MathAstNode, val denominator: MathAstNode) : MathAstNode
    data class Radical(val radicand: MathAstNode) : MathAstNode
    data class Scripted(
        val base: MathAstNode,
        val superscript: MathAstNode? = null,
        val subscript: MathAstNode? = null
    ) : MathAstNode
}

/**
 * Parses LaTeX / mathematical expressions into a 2D layout AST and renders them natively onto
 * an Android Canvas (for PDF generation and Compose preview) without leaving raw '^', '_',
 * or unparsed LaTeX commands.
 */
object MathFormulaRenderer {

    private val symbolReplacements = mapOf(
        "\\lambda" to "λ",
        "\\Lambda" to "Λ",
        "\\Delta" to "Δ",
        "\\delta" to "δ",
        "\\alpha" to "α",
        "\\beta" to "β",
        "\\gamma" to "γ",
        "\\theta" to "θ",
        "\\Theta" to "Θ",
        "\\omega" to "ω",
        "\\Omega" to "Ω",
        "\\mu" to "μ",
        "\\sigma" to "σ",
        "\\pi" to "π",
        "\\Pi" to "Π",
        "\\sum" to "∑",
        "\\prod" to "∏",
        "\\int" to "∫",
        "\\oint" to "∮",
        "\\pm" to "±",
        "\\mp" to "∓",
        "\\times" to "×",
        "\\cdot" to "·",
        "\\leq" to "≤",
        "\\le" to "≤",
        "\\geq" to "≥",
        "\\ge" to "≥",
        "\\neq" to "≠",
        "\\ne" to "≠",
        "\\approx" to "≈",
        "\\rightarrow" to "→",
        "\\to" to "→",
        "\\infty" to "∞",
        "\\det" to "det",
        "\\cos" to "cos",
        "\\sin" to "sin",
        "\\tan" to "tan",
        "\\log" to "log",
        "\\ln" to "ln"
    )

    private val unicodeSuperscripts = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'n' to 'ⁿ', 'i' to 'ⁱ', 'k' to 'ᵏ', 'T' to 'ᵀ'
    )

    private val unicodeSubscripts = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ', 'n' to 'ₙ', 'm' to 'ₘ',
        'a' to 'ₐ', 'e' to 'ₑ', 'o' to 'ₒ', 'x' to 'ₓ'
    )

    /**
     * Converts any raw LaTeX/math expression into a clean, readable Unicode mathematical string
     * with zero raw '^', '_', or backslash commands for accessibility and inline labels.
     */
    fun formatToCleanUnicode(rawExpression: String): String {
        var s = rawExpression.trim()
        // Unwrap \text{...}
        s = Regex("""\\text\{([^}]*)\}""").replace(s) { it.groupValues[1] }
        // Convert \frac{a}{b} -> (a) / (b) or compact Unicode fraction for 1/2
        s = Regex("""\\frac\{1\}\{2\}""").replace(s, "½")
        s = Regex("""\\frac\{([^}]+)\}\{([^}]+)\}""").replace(s) { match ->
            val num = match.groupValues[1].trim()
            val den = match.groupValues[2].trim()
            val numPart = if (num.length > 2) "($num)" else num
            val denPart = if (den.length > 2) "($den)" else den
            "$numPart ⁄ $denPart"
        }
        // Convert \sqrt{...} -> √(...)
        s = Regex("""\\sqrt\{([^}]+)\}""").replace(s) { match ->
            val inner = match.groupValues[1].trim()
            if (inner.length <= 2) "√$inner" else "√($inner)"
        }
        for ((latex, uni) in symbolReplacements) {
            s = s.replace(latex, uni)
        }
        // Superscripts ^{...} or ^x
        s = Regex("""\^\{([^}]+)\}""").replace(s) { match ->
            toSuperscriptText(match.groupValues[1])
        }
        s = Regex("""\^([0-9a-zA-Z+-])""").replace(s) { match ->
            toSuperscriptText(match.groupValues[1])
        }
        // Subscripts _{...} or _x
        s = Regex("""_\{([^}]+)\}""").replace(s) { match ->
            toSubscriptText(match.groupValues[1])
        }
        s = Regex("""_([0-9a-zA-Z+-])""").replace(s) { match ->
            toSubscriptText(match.groupValues[1])
        }
        // Remove any remaining stray braces or backslashes
        s = s.replace("{", "").replace("}", "").replace("\\", "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun toSuperscriptText(input: String): String {
        val mapped = input.map { unicodeSuperscripts[it] }
        return if (mapped.all { it != null }) {
            mapped.joinToString("")
        } else {
            "⁽${input}⁾"
        }
    }

    private fun toSubscriptText(input: String): String {
        val mapped = input.map { unicodeSubscripts[it] }
        return if (mapped.all { it != null }) {
            mapped.joinToString("")
        } else {
            "₍${input}₎"
        }
    }

    /**
     * Parses a raw mathematical string into a structured 2D MathAstNode tree for Canvas typesetting.
     */
    fun parseToAst(raw: String): MathAstNode {
        val parser = RecursiveMathParser(raw.trim())
        return parser.parseExpression()
    }

    private class RecursiveMathParser(private val input: String) {
        private var pos = 0

        fun parseExpression(stopChar: Char? = null): MathAstNode {
            val items = mutableListOf<MathAstNode>()
            while (pos < input.length) {
                val c = input[pos]
                if (stopChar != null && c == stopChar) {
                    pos++ // consume stopChar
                    break
                }
                if (c.isWhitespace()) {
                    pos++
                    continue
                }
                val atom = parsePrimaryAtom()
                // Check for trailing ^ or _ scripts
                var sup: MathAstNode? = null
                var sub: MathAstNode? = null
                while (pos < input.length && (input[pos] == '^' || input[pos] == '_')) {
                    val op = input[pos]
                    pos++
                    val scriptNode = parseScriptArgument()
                    if (op == '^') sup = scriptNode else sub = scriptNode
                }
                if (sup != null || sub != null) {
                    items.add(MathAstNode.Scripted(atom, sup, sub))
                } else {
                    items.add(atom)
                }
            }
            return when (items.size) {
                0 -> MathAstNode.SymbolOrText("")
                1 -> items.first()
                else -> MathAstNode.Sequence(items)
            }
        }

        private fun parseScriptArgument(): MathAstNode {
            while (pos < input.length && input[pos].isWhitespace()) pos++
            if (pos >= input.length) return MathAstNode.SymbolOrText("")
            return if (input[pos] == '{') {
                pos++
                parseExpression(stopChar = '}')
            } else {
                val ch = input[pos].toString()
                pos++
                MathAstNode.SymbolOrText(ch)
            }
        }

        private fun parseBracedGroup(): MathAstNode {
            while (pos < input.length && input[pos].isWhitespace()) pos++
            if (pos < input.length && input[pos] == '{') {
                pos++
                return parseExpression(stopChar = '}')
            }
            return parsePrimaryAtom()
        }

        private fun parsePrimaryAtom(): MathAstNode {
            if (pos >= input.length) return MathAstNode.SymbolOrText("")
            val c = input[pos]
            if (c == '{') {
                pos++
                return parseExpression(stopChar = '}')
            }
            if (c == '\\') {
                pos++
                val start = pos
                while (pos < input.length && input[pos].isLetter()) {
                    pos++
                }
                val cmd = "\\" + input.substring(start, pos)
                return when (cmd) {
                    "\\frac" -> {
                        val num = parseBracedGroup()
                        val den = parseBracedGroup()
                        MathAstNode.Fraction(num, den)
                    }
                    "\\sqrt" -> {
                        val rad = parseBracedGroup()
                        MathAstNode.Radical(rad)
                    }
                    "\\text" -> {
                        val inner = parseBracedGroup()
                        inner
                    }
                    else -> {
                        val sym = symbolReplacements[cmd] ?: cmd.removePrefix("\\")
                        MathAstNode.SymbolOrText(sym)
                    }
                }
            }

            // Operators or numbers or variable runs
            if (c in charArrayOf('=', '+', '-', '(', ')', '[', ']', ',', '|')) {
                pos++
                val spaced = if (c in charArrayOf('=', '+', '-')) " $c " else c.toString()
                return MathAstNode.SymbolOrText(spaced)
            }

            val start = pos
            while (
                pos < input.length &&
                !input[pos].isWhitespace() &&
                input[pos] !in charArrayOf('\\', '{', '}', '^', '_', '=', '+', '-', '(', ')', '[', ']')
            ) {
                pos++
            }
            val token = input.substring(start, pos)
            val isVar = token.length == 1 && token[0].isLetter()
            return MathAstNode.SymbolOrText(token, isItalicVariable = isVar)
        }
    }

    data class MeasuredMathBox(
        val width: Float,
        val ascent: Float, // height above math baseline
        val descent: Float // depth below math baseline
    ) {
        val totalHeight: Float get() = ascent + descent
    }

    fun measureNode(node: MathAstNode, basePaint: Paint, scale: Float = 1f): MeasuredMathBox {
        val workPaint = Paint(basePaint).apply {
            textSize = basePaint.textSize * scale
        }
        return when (node) {
            is MathAstNode.SymbolOrText -> {
                if (node.isItalicVariable) {
                    workPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                }
                val w = workPaint.measureText(node.text)
                val asc = -workPaint.ascent() * 0.85f
                val desc = workPaint.descent() * 0.9f
                MeasuredMathBox(w + 1.5f * scale, asc, desc)
            }
            is MathAstNode.Sequence -> {
                var totalW = 0f
                var maxAsc = 8f * scale
                var maxDesc = 3f * scale
                for (child in node.children) {
                    val m = measureNode(child, basePaint, scale)
                    totalW += m.width
                    maxAsc = max(maxAsc, m.ascent)
                    maxDesc = max(maxDesc, m.descent)
                }
                MeasuredMathBox(totalW, maxAsc, maxDesc)
            }
            is MathAstNode.Scripted -> {
                val baseM = measureNode(node.base, basePaint, scale)
                val supM = node.superscript?.let { measureNode(it, basePaint, scale * 0.68f) }
                val subM = node.subscript?.let { measureNode(it, basePaint, scale * 0.68f) }
                val scriptW = max(supM?.width ?: 0f, subM?.width ?: 0f)
                val asc = max(baseM.ascent, (baseM.ascent * 0.55f) + (supM?.totalHeight ?: 0f))
                val desc = max(baseM.descent, (baseM.descent * 0.45f) + (subM?.totalHeight ?: 0f))
                MeasuredMathBox(baseM.width + scriptW + 2f * scale, asc, desc)
            }
            is MathAstNode.Fraction -> {
                val numM = measureNode(node.numerator, basePaint, scale * 0.86f)
                val denM = measureNode(node.denominator, basePaint, scale * 0.86f)
                val w = max(numM.width, denM.width) + 12f * scale
                val axisOffset = 4f * scale
                val asc = numM.totalHeight + axisOffset + 3f * scale
                val desc = denM.totalHeight + 3f * scale
                MeasuredMathBox(w, asc, desc)
            }
            is MathAstNode.Radical -> {
                val radM = measureNode(node.radicand, basePaint, scale)
                val hookW = 10f * scale
                val padTop = 4f * scale
                MeasuredMathBox(
                    width = radM.width + hookW + 4f * scale,
                    ascent = radM.ascent + padTop,
                    descent = radM.descent + 2f * scale
                )
            }
        }
    }

    /**
     * Draws the parsed 2D mathematical expression AST onto an Android Canvas at (startX, baselineY).
     */
    fun drawNode(
        canvas: Canvas,
        node: MathAstNode,
        startX: Float,
        baselineY: Float,
        basePaint: Paint,
        scale: Float = 1f
    ): Float {
        val workPaint = Paint(basePaint).apply {
            textSize = basePaint.textSize * scale
            isAntiAlias = true
        }
        return when (node) {
            is MathAstNode.SymbolOrText -> {
                if (node.isItalicVariable) {
                    workPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                } else {
                    workPaint.typeface = basePaint.typeface
                }
                canvas.drawText(node.text, startX, baselineY, workPaint)
                startX + workPaint.measureText(node.text) + 1.5f * scale
            }
            is MathAstNode.Sequence -> {
                var curX = startX
                for (child in node.children) {
                    curX = drawNode(canvas, child, curX, baselineY, basePaint, scale)
                }
                curX
            }
            is MathAstNode.Scripted -> {
                val baseM = measureNode(node.base, basePaint, scale)
                val afterBaseX = drawNode(canvas, node.base, startX, baselineY, basePaint, scale)
                var maxEndX = afterBaseX
                node.superscript?.let { sup ->
                    val supBaseline = baselineY - baseM.ascent * 0.52f
                    val endSup = drawNode(canvas, sup, afterBaseX, supBaseline, basePaint, scale * 0.68f)
                    maxEndX = max(maxEndX, endSup)
                }
                node.subscript?.let { sub ->
                    val subBaseline = baselineY + baseM.descent + 4f * scale
                    val endSub = drawNode(canvas, sub, afterBaseX, subBaseline, basePaint, scale * 0.68f)
                    maxEndX = max(maxEndX, endSub)
                }
                maxEndX + 2f * scale
            }
            is MathAstNode.Fraction -> {
                val numM = measureNode(node.numerator, basePaint, scale * 0.86f)
                val denM = measureNode(node.denominator, basePaint, scale * 0.86f)
                val totalW = max(numM.width, denM.width) + 12f * scale
                val barY = baselineY - 4f * scale

                val numX = startX + (totalW - numM.width) / 2f
                val numBaseline = barY - 3f * scale - numM.descent
                drawNode(canvas, node.numerator, numX, numBaseline, basePaint, scale * 0.86f)

                val linePaint = Paint(workPaint).apply {
                    strokeWidth = max(1.2f, 1.3f * scale)
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(startX + 3f * scale, barY, startX + totalW - 3f * scale, barY, linePaint)

                val denX = startX + (totalW - denM.width) / 2f
                val denBaseline = barY + 3f * scale + denM.ascent
                drawNode(canvas, node.denominator, denX, denBaseline, basePaint, scale * 0.86f)

                startX + totalW + 2f * scale
            }
            is MathAstNode.Radical -> {
                val radM = measureNode(node.radicand, basePaint, scale)
                val hookW = 10f * scale
                val topY = baselineY - radM.ascent - 3f * scale
                val bottomY = baselineY + radM.descent

                val linePaint = Paint(workPaint).apply {
                    strokeWidth = max(1.2f, 1.3f * scale)
                    style = Paint.Style.STROKE
                }
                // Draw √ check and top vinculum bar
                val midY = (topY + bottomY) / 2f
                canvas.drawLine(startX + 1f * scale, midY, startX + 4f * scale, bottomY, linePaint)
                canvas.drawLine(startX + 4f * scale, bottomY, startX + hookW, topY, linePaint)
                canvas.drawLine(startX + hookW, topY, startX + hookW + radM.width + 2f * scale, topY, linePaint)

                drawNode(canvas, node.radicand, startX + hookW + 1f * scale, baselineY, basePaint, scale)
                startX + hookW + radM.width + 4f * scale
            }
        }
    }
}
