package com.example.domain.pdf

import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.example.data.model.StructuredDiagram
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders structured educational flowcharts, mechanisms, and hierarchies directly onto
 * native Android Canvas (both inside generated PDFs and inside Jetpack Compose previews)
 * without incurring AI image generation costs.
 */
object VectorDiagramRenderer {

    fun drawDiagramOnAndroidCanvas(
        canvas: Canvas,
        diagram: StructuredDiagram,
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
            strokeWidth = 1.2f
        }
        val containerRect = RectF(left, top, left + width, top + height)
        canvas.drawRoundRect(containerRect, 8f, 8f, bgPaint)
        canvas.drawRoundRect(containerRect, 8f, 8f, borderPaint)

        val nodes = diagram.nodes.take(5)
        if (nodes.isEmpty()) return

        val paddingX = 16f
        val usableW = width - paddingX * 2f
        val nodeCount = nodes.size
        val gap = if (nodeCount > 1) 24f else 0f
        val nodeW = ((usableW - gap * (nodeCount - 1)) / nodeCount).coerceIn(72f, 160f)
        val nodeH = (height * 0.56f).coerceIn(42f, 68f)
        val nodeTop = top + (height - nodeH) / 2f

        val nodeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#EFF6FF")
            style = Paint.Style.FILL
        }
        val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#1D4ED8")
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#0F172A")
            textSize = 9.2f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#475569")
            textSize = 7.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#B45309")
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
        }
        val arrowHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#B45309")
            style = Paint.Style.FILL
        }
        val edgeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#92400E")
            textSize = 7.2f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val nodeRects = mutableMapOf<String, RectF>()
        val totalSpan = nodeCount * nodeW + (nodeCount - 1) * gap
        val startX = left + (width - totalSpan) / 2f

        nodes.forEachIndexed { idx, node ->
            val nx = startX + idx * (nodeW + gap)
            val rect = RectF(nx, nodeTop, nx + nodeW, nodeTop + nodeH)
            nodeRects[node.id] = rect

            canvas.drawRoundRect(rect, 6f, 6f, nodeFillPaint)
            canvas.drawRoundRect(rect, 6f, 6f, nodeStrokePaint)

            val cx = rect.centerX()
            val cy = rect.centerY()
            val titleText = node.label.take(22)
            val subText = node.subLabel.take(24)
            if (subText.isNotBlank()) {
                canvas.drawText(titleText, cx, cy - 3f, labelPaint)
                canvas.drawText(subText, cx, cy + 10f, subLabelPaint)
            } else {
                canvas.drawText(titleText, cx, cy + 3f, labelPaint)
            }
        }

        for (edge in diagram.edges) {
            val fromRect = nodeRects[edge.fromId] ?: continue
            val toRect = nodeRects[edge.toId] ?: continue
            val x1 = fromRect.right + 2f
            val y1 = fromRect.centerY()
            val x2 = toRect.left - 3f
            val y2 = toRect.centerY()
            if (x2 > x1) {
                canvas.drawLine(x1, y1, x2, y2, arrowPaint)
                drawArrowHead(canvas, x1, y1, x2, y2, arrowHeadPaint)
                if (edge.relationLabel.isNotBlank()) {
                    canvas.drawText(
                        edge.relationLabel.take(14),
                        (x1 + x2) / 2f,
                        y1 - 6f,
                        edgeLabelPaint
                    )
                }
            }
        }
    }

    private fun drawArrowHead(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        paint: Paint
    ) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowLen = 6.5
        val arrowAngle = Math.toRadians(28.0)

        val xA = (x2 - arrowLen * cos(angle - arrowAngle)).toFloat()
        val yA = (y2 - arrowLen * sin(angle - arrowAngle)).toFloat()
        val xB = (x2 - arrowLen * cos(angle + arrowAngle)).toFloat()
        val yB = (y2 - arrowLen * sin(angle + arrowAngle)).toFloat()

        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(xA, yA)
            lineTo(xB, yB)
            close()
        }
        canvas.drawPath(path, paint)
    }
}

@Composable
fun StudyVectorDiagramPreviewCard(
    diagram: StructuredDiagram,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "${diagram.diagramType.name}: ${diagram.title}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        ComposeCanvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            drawContext.canvas.nativeCanvas.let { nativeCanvas ->
                nativeCanvas.save()
                // Scale diagram coordinates cleanly into Compose Canvas width/height
                val targetW = 500f
                val targetH = 110f
                val scaleX = size.width / targetW
                val scaleY = size.height / targetH
                val scale = minOf(scaleX, scaleY)
                val offsetX = (size.width - targetW * scale) / 2f
                val offsetY = (size.height - targetH * scale) / 2f
                nativeCanvas.translate(offsetX, offsetY)
                nativeCanvas.scale(scale, scale)
                VectorDiagramRenderer.drawDiagramOnAndroidCanvas(
                    canvas = nativeCanvas,
                    diagram = diagram,
                    left = 0f,
                    top = 0f,
                    width = targetW,
                    height = targetH
                )
                nativeCanvas.restore()
            }
        }
        if (diagram.caption.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = diagram.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
