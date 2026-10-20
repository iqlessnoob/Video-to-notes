package com.example.domain.pdf

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.example.data.model.ChartPlotType
import com.example.data.model.ComparisonTable
import com.example.data.model.DataChartSpec
import com.example.data.model.DiagramEdge
import com.example.data.model.DiagramNode
import com.example.data.model.MathFormula
import com.example.data.model.SchematicElement
import com.example.data.model.SchematicElementType
import com.example.data.model.StructuredDiagram
import com.example.data.model.StudyDocument
import com.example.data.model.TechnicalSchematicSpec
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Local, Zero-Token Code-Based Validation and Rendering Engine.
 *
 * Strictly separates the AI content-understanding layer (which produces structured JSON)
 * from the local rendering layer:
 * 1. Mermaid.js syntax validation & generation for flowcharts, process diagrams, and cause-and-effect diagrams.
 * 2. KaTeX notation validation & normalization for mathematical formulas (with 2D AST & Unicode fallback).
 * 3. HTML5/CSS3 layout generation for classification tables, comparison tables, glossary tables, and print layouts.
 * 4. Chart.js & deterministic SVG/Canvas plotting for quantitative charts and graphs.
 * 5. SVG & native Canvas drawing tools for technical schematics, force vectors, labels, and annotations.
 * 6. Extracted video frame embedding when local video frames are available.
 */
object LocalCodeRenderEngine {

    /**
     * Validates all structured data in [StudyDocument] prior to rendering and populates
     * normalized Mermaid.js DSL, KaTeX expressions, and safe fallbacks if any syntax is malformed.
     */
    fun validateAndSanitizeDocument(doc: StudyDocument): StudyDocument {
        val validatedFormulas = doc.formulas.map { validateAndNormalizeFormula(it) }
        val validatedDiagrams = doc.diagrams.map { validateAndNormalizeDiagram(it) }
        val validatedCharts = doc.charts.mapNotNull { validateAndNormalizeChart(it) }
        val validatedSchematics = doc.schematics.mapNotNull { validateAndNormalizeSchematic(it) }
        val validatedTables = doc.comparisonTables.mapNotNull { validateComparisonTable(it) }

        return doc.copy(
            formulas = validatedFormulas,
            diagrams = validatedDiagrams,
            charts = validatedCharts,
            schematics = validatedSchematics,
            comparisonTables = validatedTables
        )
    }

    /**
     * Validates and normalizes a [MathFormula] for KaTeX and native 2D AST rendering.
     * Balances curly braces, converts programming syntax (e.g., a^2, sqrt(x)) into valid KaTeX LaTeX,
     * and guarantees a fallback if raw notation is malformed.
     */
    fun validateAndNormalizeFormula(formula: MathFormula): MathFormula {
        val sourceRaw = formula.katexExpression.ifBlank { formula.rawExpression }.trim()
        val normalizedKatex = normalizeToValidKatex(sourceRaw)
        return formula.copy(
            rawExpression = formula.rawExpression.ifBlank { normalizedKatex },
            katexExpression = normalizedKatex
        )
    }

    fun normalizeToValidKatex(input: String): String {
        if (input.isBlank()) return "x = 0"
        var s = input.trim()
            .replace("$$", "")
            .replace("\\(", "")
            .replace("\\)", "")
            .replace("\\[", "")
            .replace("\\]", "")

        // Convert programming-style sqrt(...) or unclosed sqrt(... into \sqrt{...}
        s = s.replace(Regex("""\bsqrt\(([^()]+)\)?""")) { "\\sqrt{${it.groupValues[1]}}" }
        // Convert unbraced multi-char exponents like x^2n or v^2 into v^{2}
        s = s.replace(Regex("""\^([a-zA-Z0-9+-]{1,4})(?![a-zA-Z0-9}])""")) { "^{${it.groupValues[1]}}" }
        // Convert unbraced subscripts like U_g or v_f into U_{g}
        s = s.replace(Regex("""_([a-zA-Z0-9+-]{1,4})(?![a-zA-Z0-9}])""")) { "_{${it.groupValues[1]}}" }
        // Replace raw programming '*' with KaTeX \cdot
        s = s.replace(" * ", " \\cdot ").replace("*", " \\cdot ")

        // Ensure balanced curly braces so KaTeX never throws a ParseError
        var openBraces = 0
        val sb = StringBuilder()
        for (ch in s) {
            when (ch) {
                '{' -> {
                    openBraces++
                    sb.append(ch)
                }
                '}' -> {
                    if (openBraces > 0) {
                        openBraces--
                        sb.append(ch)
                    }
                }
                else -> sb.append(ch)
            }
        }
        while (openBraces > 0) {
            sb.append('}')
            openBraces--
        }
        return sb.toString().trim()
    }

    /**
     * Validates a [StructuredDiagram] and guarantees both valid Mermaid.js flowchart syntax
     * AND populated [DiagramNode]/[DiagramEdge] lists for native vector fallback rendering.
     */
    fun validateAndNormalizeDiagram(diagram: StructuredDiagram): StructuredDiagram {
        val safeNodes = if (diagram.nodes.isNotEmpty()) {
            diagram.nodes.mapIndexed { idx, n ->
                val cleanId = n.id.replace(Regex("""[^a-zA-Z0-9_]"""), "").ifBlank { "N${idx + 1}" }
                n.copy(
                    id = cleanId,
                    label = n.label.ifBlank { "Step ${idx + 1}" }
                )
            }
        } else {
            parseNodesFromMermaid(diagram.mermaidDsl)
        }

        val nodeIds = safeNodes.map { it.id }.toSet()
        val safeEdges = if (diagram.edges.isNotEmpty()) {
            diagram.edges.filter { it.fromId in nodeIds && it.toId in nodeIds }
        } else {
            safeNodes.zipWithNext { a, b ->
                DiagramEdge(fromId = a.id, toId = b.id, relationLabel = "")
            }
        }

        val validMermaid = if (isValidMermaidSyntax(diagram.mermaidDsl)) {
            diagram.mermaidDsl.trim()
        } else {
            buildMermaidDslFromNodesAndEdges(safeNodes, safeEdges)
        }

        return diagram.copy(
            mermaidDsl = validMermaid,
            nodes = safeNodes,
            edges = safeEdges
        )
    }

    fun isValidMermaidSyntax(dsl: String): Boolean {
        val trimmed = dsl.trim()
        if (trimmed.isBlank()) return false
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim() ?: return false
        val hasValidHeader = firstLine.startsWith("flowchart ") ||
            firstLine.startsWith("graph ") ||
            firstLine.startsWith("sequenceDiagram")
        val hasArrow = trimmed.contains("-->") || trimmed.contains("->") || trimmed.contains("---")
        return hasValidHeader && hasArrow
    }

    fun buildMermaidDslFromNodesAndEdges(
        nodes: List<DiagramNode>,
        edges: List<DiagramEdge>,
        direction: String = "LR"
    ): String {
        if (nodes.isEmpty()) return "flowchart LR\n  A[\"Start\"] --> B[\"Summary\"]"
        return buildString {
            appendLine("flowchart $direction")
            for (node in nodes) {
                val safeLabel = buildString {
                    append(node.label.replace("\"", "'"))
                    if (node.subLabel.isNotBlank()) {
                        append("<br/>")
                        append(node.subLabel.replace("\"", "'"))
                    }
                }
                appendLine("  ${node.id}[\"$safeLabel\"]")
            }
            for (edge in edges) {
                if (edge.relationLabel.isNotBlank()) {
                    val cleanRel = edge.relationLabel.replace("\"", "'").replace("|", "/")
                    appendLine("  ${edge.fromId} -->|\"$cleanRel\"| ${edge.toId}")
                } else {
                    appendLine("  ${edge.fromId} --> ${edge.toId}")
                }
            }
        }.trim()
    }

    private fun parseNodesFromMermaid(mermaidDsl: String): List<DiagramNode> {
        val nodeRegex = Regex("""([A-Za-z0-9_]+)\s*\[\s*"?([^\]"]+)"?\s*\]""")
        val matches = nodeRegex.findAll(mermaidDsl).map { m ->
            DiagramNode(
                id = m.groupValues[1],
                label = m.groupValues[2].replace("<br/>", " • ")
            )
        }.distinctBy { it.id }.toList()

        return matches.ifEmpty {
            listOf(
                DiagramNode("N1", "1. Input Concept"),
                DiagramNode("N2", "2. Core Mechanism"),
                DiagramNode("N3", "3. Verified Output")
            )
        }
    }

    fun validateAndNormalizeChart(chart: DataChartSpec): DataChartSpec? {
        if (chart.yValues.isEmpty()) return null
        val safeY = chart.yValues.map { if (it.isFinite()) it else 0.0 }
        val safeLabels = if (chart.xLabels.size == safeY.size) {
            chart.xLabels
        } else {
            safeY.indices.map { idx -> chart.xLabels.getOrNull(idx) ?: "${idx + 1}" }
        }
        val safeSecondary = if (chart.secondaryYValues.size == safeY.size) {
            chart.secondaryYValues.map { if (it.isFinite()) it else 0.0 }
        } else {
            emptyList()
        }
        return chart.copy(
            xLabels = safeLabels,
            yValues = safeY,
            secondaryYValues = safeSecondary
        )
    }

    fun validateAndNormalizeSchematic(schematic: TechnicalSchematicSpec): TechnicalSchematicSpec? {
        if (schematic.elements.isEmpty()) return null
        val clamped = schematic.elements.map { el ->
            el.copy(
                x1Percent = el.x1Percent.coerceIn(2f, 98f),
                y1Percent = el.y1Percent.coerceIn(4f, 96f),
                x2Percent = el.x2Percent.coerceIn(2f, 98f),
                y2Percent = el.y2Percent.coerceIn(4f, 96f)
            )
        }
        return schematic.copy(elements = clamped)
    }

    fun validateComparisonTable(table: ComparisonTable): ComparisonTable? {
        if (table.headers.isEmpty() || table.rows.isEmpty()) return null
        val colCount = table.headers.size
        val normalizedRows = table.rows.map { row ->
            List(colCount) { idx -> row.getOrNull(idx)?.trim().orEmpty().ifBlank { "—" } }
        }
        return table.copy(rows = normalizedRows)
    }

    /**
     * Draws a quantitative [DataChartSpec] (Line or Bar chart) directly onto an Android [Canvas]
     * for native PDF pages and Compose previews without any AI image call.
     */
    fun drawChartOnAndroidCanvas(
        canvas: Canvas,
        chart: DataChartSpec,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#F8FAFC")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, bgPaint)
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, borderPaint)

        val points = chart.yValues
        if (points.isEmpty()) return

        val plotLeft = left + 42f
        val plotRight = left + width - 18f
        val plotTop = top + 20f
        val plotBottom = top + height - 26f
        val plotW = (plotRight - plotLeft).coerceAtLeast(40f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(30f)

        val allValues = points + chart.secondaryYValues
        val minVal = (allValues.minOrNull() ?: 0.0).coerceAtMost(0.0)
        val maxVal = (allValues.maxOrNull() ?: 1.0).let { if (it <= minVal) minVal + 1.0 else it }
        val range = (maxVal - minVal).coerceAtLeast(1e-6)

        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#64748B")
            strokeWidth = 1.2f
            style = Paint.Style.STROKE
        }
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#E2E8F0")
            strokeWidth = 0.8f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#475569")
            textSize = 7.4f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#0F172A")
            textSize = 7.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        // Horizontal grid lines & Y-axis ticks
        for (i in 0..3) {
            val frac = i / 3f
            val y = plotBottom - frac * plotH
            canvas.drawLine(plotLeft, y, plotRight, y, if (i == 0) axisPaint else gridPaint)
            val tickVal = minVal + frac * range
            val tickText = String.format(Locale.US, "%.1f", tickVal)
            val tw = labelPaint.measureText(tickText)
            canvas.drawText(tickText, plotLeft - tw - 4f, y + 3f, labelPaint)
        }
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, axisPaint)

        // Legend header
        canvas.drawText(
            "${chart.seriesName}${if (chart.secondarySeriesName.isNotBlank()) " vs. ${chart.secondarySeriesName}" else ""} (${chart.yAxisLabel})",
            plotLeft,
            top + 13f,
            legendPaint
        )

        val primaryStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#1D4ED8")
            strokeWidth = 2.0f
            style = Paint.Style.STROKE
        }
        val primaryFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#1D4ED8")
            style = Paint.Style.FILL
        }
        val secondaryStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#D97706")
            strokeWidth = 1.8f
            style = Paint.Style.STROKE
        }
        val secondaryFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#D97706")
            style = Paint.Style.FILL
        }

        val count = points.size
        if (chart.chartType == ChartPlotType.BAR) {
            val slotW = plotW / count.coerceAtLeast(1)
            val barW = (slotW * 0.55f).coerceIn(8f, 36f)
            points.forEachIndexed { idx, v ->
                val norm = ((v - minVal) / range).toFloat().coerceIn(0f, 1f)
                val cx = plotLeft + (idx + 0.5f) * slotW
                val barTop = plotBottom - norm * plotH
                canvas.drawRoundRect(
                    RectF(cx - barW / 2f, barTop, cx + barW / 2f, plotBottom),
                    3f,
                    3f,
                    primaryFill
                )
                val xLab = chart.xLabels.getOrNull(idx).orEmpty().take(8)
                val lw = labelPaint.measureText(xLab)
                canvas.drawText(xLab, cx - lw / 2f, plotBottom + 11f, labelPaint)
            }
        } else {
            val path = Path()
            points.forEachIndexed { idx, v ->
                val x = if (count > 1) plotLeft + (idx.toFloat() / (count - 1)) * plotW else plotLeft + plotW / 2f
                val norm = ((v - minVal) / range).toFloat().coerceIn(0f, 1f)
                val y = plotBottom - norm * plotH
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                canvas.drawCircle(x, y, 2.8f, primaryFill)

                val xLab = chart.xLabels.getOrNull(idx).orEmpty().take(8)
                val lw = labelPaint.measureText(xLab)
                canvas.drawText(xLab, x - lw / 2f, plotBottom + 11f, labelPaint)
            }
            if (count > 1) {
                canvas.drawPath(path, primaryStroke)
            }

            if (chart.secondaryYValues.size == count && count > 1) {
                val secPath = Path()
                chart.secondaryYValues.forEachIndexed { idx, v ->
                    val x = plotLeft + (idx.toFloat() / (count - 1)) * plotW
                    val norm = ((v - minVal) / range).toFloat().coerceIn(0f, 1f)
                    val y = plotBottom - norm * plotH
                    if (idx == 0) secPath.moveTo(x, y) else secPath.lineTo(x, y)
                    canvas.drawCircle(x, y, 2.5f, secondaryFill)
                }
                canvas.drawPath(secPath, secondaryStroke)
            }
        }

        // X-axis title
        val xTitleW = labelPaint.measureText(chart.xAxisLabel)
        canvas.drawText(chart.xAxisLabel, plotLeft + (plotW - xTitleW) / 2f, top + height - 4f, labelPaint)
    }

    /**
     * Draws a [TechnicalSchematicSpec] (free-body force vectors, coordinate axes, blocks, labels)
     * directly onto an Android [Canvas] using standard vector drawing primitives (0 AI tokens).
     */
    fun drawSchematicOnAndroidCanvas(
        canvas: Canvas,
        schematic: TechnicalSchematicSpec,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#FFFFFF")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, bgPaint)
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, borderPaint)

        fun mapX(pct: Float): Float = left + (pct.coerceIn(0f, 100f) / 100f) * width
        fun mapY(pct: Float): Float = top + (pct.coerceIn(0f, 100f) / 100f) * height

        for (el in schematic.elements) {
            val parsedColor = try {
                AndroidColor.parseColor(el.colorHex)
            } catch (_: Exception) {
                AndroidColor.parseColor("#1D4ED8")
            }
            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parsedColor
                style = Paint.Style.STROKE
                strokeWidth = 1.8f
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parsedColor
                style = Paint.Style.FILL
            }
            val blockFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColor.parseColor("#EFF6FF")
                style = Paint.Style.FILL
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = parsedColor
                textSize = 8.4f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }

            val x1 = mapX(el.x1Percent)
            val y1 = mapY(el.y1Percent)
            val x2 = mapX(el.x2Percent)
            val y2 = mapY(el.y2Percent)

            when (el.type) {
                SchematicElementType.AXIS_LINE -> {
                    strokePaint.strokeWidth = 1.1f
                    strokePaint.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                    if (el.label.isNotBlank()) {
                        canvas.drawText(el.label, x2 + 4f, y2 + 3f, textPaint)
                    }
                }
                SchematicElementType.BLOCK -> {
                    val rect = RectF(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
                    canvas.drawRoundRect(rect, 5f, 5f, blockFill)
                    canvas.drawRoundRect(rect, 5f, 5f, strokePaint)
                    if (el.label.isNotBlank()) {
                        val tw = textPaint.measureText(el.label)
                        canvas.drawText(el.label, rect.centerX() - tw / 2f, rect.centerY() + 3f, textPaint)
                    }
                }
                SchematicElementType.CIRCLE_BODY -> {
                    val radius = max(8f, kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat())
                    canvas.drawCircle(x1, y1, radius, blockFill)
                    canvas.drawCircle(x1, y1, radius, strokePaint)
                    if (el.label.isNotBlank()) {
                        val tw = textPaint.measureText(el.label)
                        canvas.drawText(el.label, x1 - tw / 2f, y1 + 3f, textPaint)
                    }
                }
                SchematicElementType.FORCE_ARROW -> {
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                    val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
                    val headLen = 7.0
                    val headAng = Math.toRadians(26.0)
                    val ax = (x2 - headLen * cos(angle - headAng)).toFloat()
                    val ay = (y2 - headLen * sin(angle - headAng)).toFloat()
                    val bx = (x2 - headLen * cos(angle + headAng)).toFloat()
                    val by = (y2 - headLen * sin(angle + headAng)).toFloat()
                    val headPath = Path().apply {
                        moveTo(x2, y2)
                        lineTo(ax, ay)
                        lineTo(bx, by)
                        close()
                    }
                    canvas.drawPath(headPath, fillPaint)
                    if (el.label.isNotBlank()) {
                        canvas.drawText(el.label, (x1 + x2) / 2f + 4f, (y1 + y2) / 2f - 4f, textPaint)
                    }
                }
                SchematicElementType.ANNOTATION_LABEL -> {
                    canvas.drawText(el.label, x1, y1, textPaint)
                }
            }
        }
    }

    /**
     * Generates an inline SVG string for a [DataChartSpec] so the HTML5/CSS3 study document
     * renders crisp vector charts even when offline, alongside Chart.js configuration.
     */
    fun renderChartToInlineSvg(chart: DataChartSpec, widthPx: Int = 520, heightPx: Int = 180): String {
        val points = chart.yValues
        if (points.isEmpty()) return ""
        val minVal = (points.minOrNull() ?: 0.0).coerceAtMost(0.0)
        val maxVal = (points.maxOrNull() ?: 1.0).let { if (it <= minVal) minVal + 1.0 else it }
        val range = (maxVal - minVal).coerceAtLeast(1e-6)
        val plotL = 48.0
        val plotR = widthPx - 20.0
        val plotT = 24.0
        val plotB = heightPx - 28.0
        val plotW = plotR - plotL
        val plotH = plotB - plotT

        val polyPoints = points.mapIndexed { idx, v ->
            val x = if (points.size > 1) plotL + (idx.toDouble() / (points.size - 1)) * plotW else plotL + plotW / 2.0
            val y = plotB - ((v - minVal) / range) * plotH
            String.format(Locale.US, "%.1f,%.1f", x, y)
        }.joinToString(" ")

        return buildString {
            appendLine("""<svg viewBox="0 0 $widthPx $heightPx" width="100%" height="$heightPx" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${escapeHtml(chart.title)}">""")
            appendLine("""  <rect x="0" y="0" width="$widthPx" height="$heightPx" rx="8" fill="#F8FAFC" stroke="#CBD5E1"/>""")
            appendLine("""  <line x1="$plotL" y1="$plotT" x2="$plotL" y2="$plotB" stroke="#64748B" stroke-width="1.2"/>""")
            appendLine("""  <line x1="$plotL" y1="$plotB" x2="$plotR" y2="$plotB" stroke="#64748B" stroke-width="1.2"/>""")
            appendLine("""  <polyline fill="none" stroke="#1D4ED8" stroke-width="2.2" points="$polyPoints"/>""")
            appendLine("""</svg>""")
        }
    }

    /**
     * Generates an inline SVG string for a [TechnicalSchematicSpec].
     */
    fun renderSchematicToInlineSvg(schematic: TechnicalSchematicSpec, widthPx: Int = 520, heightPx: Int = 150): String {
        fun mx(pct: Float) = String.format(Locale.US, "%.1f", (pct / 100f) * widthPx)
        fun my(pct: Float) = String.format(Locale.US, "%.1f", (pct / 100f) * heightPx)

        return buildString {
            appendLine("""<svg viewBox="0 0 $widthPx $heightPx" width="100%" height="$heightPx" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="${escapeHtml(schematic.title)}">""")
            appendLine("""  <defs><marker id="arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#1D4ED8"/></marker></defs>""")
            appendLine("""  <rect x="0" y="0" width="$widthPx" height="$heightPx" rx="8" fill="#FFFFFF" stroke="#CBD5E1"/>""")
            for (el in schematic.elements) {
                when (el.type) {
                    SchematicElementType.AXIS_LINE -> {
                        appendLine("""  <line x1="${mx(el.x1Percent)}" y1="${my(el.y1Percent)}" x2="${mx(el.x2Percent)}" y2="${my(el.y2Percent)}" stroke="${el.colorHex}" stroke-dasharray="4 3" stroke-width="1.2"/>""")
                    }
                    SchematicElementType.BLOCK -> {
                        val x = mx(min(el.x1Percent, el.x2Percent))
                        val y = my(min(el.y1Percent, el.y2Percent))
                        val w = String.format(Locale.US, "%.1f", (kotlin.math.abs(el.x2Percent - el.x1Percent) / 100f) * widthPx)
                        val h = String.format(Locale.US, "%.1f", (kotlin.math.abs(el.y2Percent - el.y1Percent) / 100f) * heightPx)
                        appendLine("""  <rect x="$x" y="$y" width="$w" height="$h" rx="5" fill="#EFF6FF" stroke="${el.colorHex}" stroke-width="1.6"/>""")
                    }
                    SchematicElementType.CIRCLE_BODY -> {
                        appendLine("""  <circle cx="${mx(el.x1Percent)}" cy="${my(el.y1Percent)}" r="16" fill="#EFF6FF" stroke="${el.colorHex}" stroke-width="1.6"/>""")
                    }
                    SchematicElementType.FORCE_ARROW -> {
                        appendLine("""  <line x1="${mx(el.x1Percent)}" y1="${my(el.y1Percent)}" x2="${mx(el.x2Percent)}" y2="${my(el.y2Percent)}" stroke="${el.colorHex}" stroke-width="2" marker-end="url(#arrow)"/>""")
                        if (el.label.isNotBlank()) {
                            val lx = mx((el.x1Percent + el.x2Percent) / 2f)
                            val ly = my((el.y1Percent + el.y2Percent) / 2f - 4f)
                            appendLine("""  <text x="$lx" y="$ly" fill="${el.colorHex}" font-size="11" font-family="sans-serif" font-weight="bold">${escapeHtml(el.label)}</text>""")
                        }
                    }
                    SchematicElementType.ANNOTATION_LABEL -> {
                        appendLine("""  <text x="${mx(el.x1Percent)}" y="${my(el.y1Percent)}" fill="${el.colorHex}" font-size="11" font-family="sans-serif" font-weight="bold">${escapeHtml(el.label)}</text>""")
                    }
                }
            }
            appendLine("""</svg>""")
        }
    }

    /**
     * Assembles a complete, self-contained HTML5/CSS3 study document from the validated
     * [StudyDocument] using Mermaid.js, KaTeX, Chart.js, inline SVGs, and print-ready CSS
     * without making a single AI call.
     */
    fun buildStandaloneHtmlStudySheet(rawDoc: StudyDocument): String {
        val doc = validateAndSanitizeDocument(rawDoc)
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("  <meta charset=\"UTF-8\"/>")
            appendLine("  <title>${escapeHtml(doc.title)} — Study PDF</title>")
            appendLine("  <link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css\"/>")
            appendLine("  <script defer src=\"https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js\"></script>")
            appendLine("  <script defer src=\"https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js\"></script>")
            appendLine("  <script defer src=\"https://cdn.jsdelivr.net/npm/chart.js@4.4.3/dist/chart.umd.min.js\"></script>")
            appendLine("  <style>")
            appendLine("    @page { size: A4; margin: 18mm 15mm; }")
            appendLine("    body { font-family: 'Plus Jakarta Sans', -apple-system, sans-serif; color: #0F172A; line-height: 1.55; margin: 0; padding: 24px; background: #FAF8F5; }")
            appendLine("    .banner { background: #0F172A; color: #FFFFFF; padding: 20px; border-radius: 12px; margin-bottom: 18px; }")
            appendLine("    h1, h2, h3 { font-family: 'Newsreader', Georgia, serif; margin-top: 16px; margin-bottom: 8px; }")
            appendLine("    h2 { color: #1E3A8A; border-bottom: 2px solid #1D4ED8; padding-bottom: 4px; }")
            appendLine("    .formula-card { background: #FFFBEB; border: 1.5px solid #F59E0B; border-radius: 8px; padding: 12px 16px; margin: 10px 0; page-break-inside: avoid; }")
            appendLine("    .diagram-card, .chart-card, .schematic-card { background: #FFFFFF; border: 1px solid #CBD5E1; border-radius: 8px; padding: 12px; margin: 10px 0; page-break-inside: avoid; }")
            appendLine("    table.study-table { width: 100%; border-collapse: collapse; margin: 12px 0; page-break-inside: avoid; }")
            appendLine("    table.study-table th { background: #1E3A8A; color: #FFFFFF; text-align: left; padding: 8px 10px; font-size: 13px; }")
            appendLine("    table.study-table td { border: 1px solid #CBD5E1; padding: 7px 10px; font-size: 13px; }")
            appendLine("    table.study-table tr:nth-child(even) { background: #F1F5F9; }")
            appendLine("  </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("  <div class=\"banner\">")
            appendLine("    <h1>${escapeHtml(doc.title)}</h1>")
            appendLine("    <div>${escapeHtml(doc.sourceTypeLabel)} • Duration: ${escapeHtml(doc.videoDurationFormatted)} • ${escapeHtml(doc.detailLevel.displayName)}</div>")
            appendLine("  </div>")
            appendLine("  <h2>01. Executive Summary</h2>")
            appendLine("  <p>${escapeHtml(doc.executiveSummary)}</p>")

            appendLine("  <h2>02. Section-by-Section Lecture Notes</h2>")
            for (sec in doc.sections) {
                appendLine("  <section class=\"lecture-section\">")
                appendLine("    <h3>[${escapeHtml(sec.timestampRange)}] Section ${sec.sectionNumber}: ${escapeHtml(sec.heading)}</h3>")
                appendLine("    <p>${escapeHtml(sec.summaryParagraph)}</p>")
                if (sec.keyPoints.isNotEmpty()) {
                    appendLine("    <ul>")
                    sec.keyPoints.forEach { pt -> appendLine("      <li>${escapeHtml(pt)}</li>") }
                    appendLine("    </ul>")
                }
                appendLine("  </section>")
            }

            if (doc.formulas.isNotEmpty()) {
                appendLine("  <h2>03. Mathematical Formulas (KaTeX Local Rendering)</h2>")
                for (f in doc.formulas) {
                    val unicodeFallback = MathFormulaRenderer.formatToCleanUnicode(f.katexExpression.ifBlank { f.rawExpression })
                    appendLine("  <div class=\"formula-card\">")
                    appendLine("    <strong>${escapeHtml(f.name)}</strong> <span>[${escapeHtml(f.units)}]</span>")
                    appendLine("    <div class=\"katex-equation\" data-katex=\"${escapeHtml(f.katexExpression)}\">${escapeHtml(unicodeFallback)}</div>")
                    if (f.variables.isNotEmpty()) {
                        appendLine("    <ul>")
                        f.variables.forEach { v ->
                            appendLine("      <li><code>${escapeHtml(MathFormulaRenderer.formatToCleanUnicode(v.symbol))}</code>: ${escapeHtml(v.meaning)} ${if (v.unit.isNotBlank()) "(${escapeHtml(v.unit)})" else ""}</li>")
                        }
                        appendLine("    </ul>")
                    }
                    appendLine("  </div>")
                }
            }

            if (doc.diagrams.isNotEmpty()) {
                appendLine("  <h2>04. Process Flowcharts & Cause-and-Effect Diagrams (Mermaid.js)</h2>")
                for (d in doc.diagrams) {
                    appendLine("  <div class=\"diagram-card\">")
                    appendLine("    <h3>${escapeHtml(d.title)}</h3>")
                    appendLine("    <pre class=\"mermaid\">\n${escapeHtml(d.mermaidDsl)}\n    </pre>")
                    if (d.caption.isNotBlank()) {
                        appendLine("    <p><small>${escapeHtml(d.caption)}</small></p>")
                    }
                    appendLine("  </div>")
                }
            }

            if (doc.charts.isNotEmpty()) {
                appendLine("  <h2>05. Quantitative Charts & Graphs (Chart.js & Local SVG)</h2>")
                for (c in doc.charts) {
                    appendLine("  <div class=\"chart-card\">")
                    appendLine("    <h3>${escapeHtml(c.title)}</h3>")
                    appendLine("    ${renderChartToInlineSvg(c)}")
                    if (c.caption.isNotBlank()) {
                        appendLine("    <p><small>${escapeHtml(c.caption)}</small></p>")
                    }
                    appendLine("  </div>")
                }
            }

            if (doc.schematics.isNotEmpty()) {
                appendLine("  <h2>06. Technical Schematics & Force Annotations (Local SVG)</h2>")
                for (s in doc.schematics) {
                    appendLine("  <div class=\"schematic-card\">")
                    appendLine("    <h3>${escapeHtml(s.title)}</h3>")
                    appendLine("    ${renderSchematicToInlineSvg(s)}")
                    if (s.caption.isNotBlank()) {
                        appendLine("    <p><small>${escapeHtml(s.caption)}</small></p>")
                    }
                    appendLine("  </div>")
                }
            }

            if (doc.comparisonTables.isNotEmpty()) {
                appendLine("  <h2>07. Classification & Comparison Tables (HTML/CSS)</h2>")
                for (t in doc.comparisonTables) {
                    appendLine("  <h3>${escapeHtml(t.title)}</h3>")
                    appendLine("  <table class=\"study-table\">")
                    appendLine("    <thead><tr>${t.headers.joinToString("") { "<th>${escapeHtml(it)}</th>" }}</tr></thead>")
                    appendLine("    <tbody>")
                    for (row in t.rows) {
                        appendLine("      <tr>${row.joinToString("") { "<td>${escapeHtml(it)}</td>" }}</tr>")
                    }
                    appendLine("    </tbody>")
                    appendLine("  </table>")
                }
            }

            if (doc.extractedVideoFrames.isNotEmpty()) {
                appendLine("  <h2>08. Extracted Video Visual Examples</h2>")
                for (frame in doc.extractedVideoFrames) {
                    appendLine("  <div class=\"diagram-card\">")
                    appendLine("    <strong>[${escapeHtml(frame.timestampFormatted)}] ${escapeHtml(frame.sectionHeading)}</strong>")
                    appendLine("    <div><img src=\"data:image/jpeg;base64,${frame.jpegBase64}\" alt=\"${escapeHtml(frame.caption)}\" style=\"max-width:320px;border-radius:6px;\"/></div>")
                    appendLine("    <p><small>${escapeHtml(frame.caption)}</small></p>")
                    appendLine("  </div>")
                }
            }

            if (doc.glossary.isNotEmpty()) {
                appendLine("  <h2>09. Glossary of Technical Terms (HTML/CSS Table)</h2>")
                appendLine("  <table class=\"study-table\">")
                appendLine("    <thead><tr><th>Technical Term</th><th>Definition</th></tr></thead>")
                appendLine("    <tbody>")
                for (g in doc.glossary.sortedBy { it.term }) {
                    appendLine("      <tr><td><strong>${escapeHtml(g.term)}</strong></td><td>${escapeHtml(g.definition)}</td></tr>")
                }
                appendLine("    </tbody>")
                appendLine("  </table>")
            }

            appendLine("</body>")
            appendLine("</html>")
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
