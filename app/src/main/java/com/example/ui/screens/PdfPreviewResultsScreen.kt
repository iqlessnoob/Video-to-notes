package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DataChartSpec
import com.example.data.model.ExtractedVideoFrameSpec
import com.example.data.model.MathFormula
import com.example.data.model.StudyDocument
import com.example.data.model.TechnicalSchematicSpec
import com.example.domain.pdf.LocalCodeRenderEngine
import com.example.domain.pdf.MathFormulaRenderer
import com.example.domain.pdf.StudyPdfGenerator
import com.example.domain.pdf.StudyVectorDiagramPreviewCard
import java.io.File

@Composable
fun PdfPreviewResultsScreen(
    studyDocument: StudyDocument?,
    pdfFile: File?,
    pageCount: Int,
    pdfGenerator: StudyPdfGenerator,
    onDownloadClicked: () -> Unit,
    onShareClicked: () -> Unit,
    onRegenerateClicked: () -> Unit,
    onBackToStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (studyDocument == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No Study PDF Selected",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Generate a study PDF from the Study Studio tab or open one from your PDF Library.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBackToStudio) {
                Text("Go to Study Studio")
            }
        }
        return
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf(
        "Rendered PDF ($pageCount p.)",
        "Sections (${studyDocument.sections.size})",
        "Formulas, Charts & Schematics",
        "Tables, Quiz & Glossary"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 640.dp)) {
            // Document Title & Action Bar
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pdf_preview_header_card")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = studyDocument.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${studyDocument.detailLevel.displayName} • ${studyDocument.videoDurationFormatted} • $pageCount Page(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val telemetry = studyDocument.tokenTelemetry
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("token_telemetry_banner")
                    ) {
                        Text(
                            text = "Token Optimization: ${telemetry.aiCallsForContentUnderstanding} AI reasoning call(s) • " +
                                "${telemetry.totalAiCallsAvoidedByLocalRendering} AI rendering calls avoided via local KaTeX, Mermaid.js, Chart.js/SVG & HTML/CSS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onDownloadClicked,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("download_pdf_button")
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = "Download")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download")
                        }
                        OutlinedButton(
                            onClick = onShareClicked,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("share_pdf_button")
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Share PDF")
                        }
                        IconButton(
                            onClick = onRegenerateClicked,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("regenerate_pdf_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Regenerate PDF"
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTabIndex == idx,
                        onClick = { selectedTabIndex = idx },
                        text = { Text(title) },
                        modifier = Modifier.testTag("preview_tab_$idx")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTabIndex) {
                    0 -> RenderedPdfBitmapPagerSection(
                        pdfFile = pdfFile,
                        pageCount = pageCount,
                        pdfGenerator = pdfGenerator,
                        studyDocument = studyDocument
                    )
                    1 -> SectionsAndSummaryTabContent(studyDocument = studyDocument)
                    2 -> FormulasAndDiagramsTabContent(studyDocument = studyDocument)
                    3 -> TablesQuizAndGlossaryTabContent(studyDocument = studyDocument)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RenderedPdfBitmapPagerSection(
    pdfFile: File?,
    pageCount: Int,
    pdfGenerator: StudyPdfGenerator,
    studyDocument: StudyDocument
) {
    var currentPageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val totalPages = pageCount.coerceAtLeast(1)

    LaunchedEffect(pdfFile, currentPageIndex) {
        if (pdfFile != null && pdfFile.exists()) {
            pageBitmap = pdfGenerator.renderPdfPageToBitmap(pdfFile, currentPageIndex)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (currentPageIndex > 0) currentPageIndex-- },
                    enabled = currentPageIndex > 0
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                        contentDescription = "Previous Page"
                    )
                }
                Text(
                    text = "PDF Page ${currentPageIndex + 1} of $totalPages",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = { if (currentPageIndex < totalPages - 1) currentPageIndex++ },
                    enabled = currentPageIndex < totalPages - 1
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                        contentDescription = "Next Page"
                    )
                }
            }

            val bmp = pageBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Rendered PDF Page ${currentPageIndex + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                )
            } else {
                // Fallback structured summary view if PdfRenderer bitmap is still loading
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(16.dp)
                ) {
                    Column {
                        Text(
                            text = studyDocument.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = studyDocument.executiveSummary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF334155)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionsAndSummaryTabContent(studyDocument: StudyDocument) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Executive Summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = studyDocument.executiveSummary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    studyDocument.sections.forEach { sec ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "[${sec.timestampRange}] • Section ${sec.sectionNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sec.heading,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = sec.summaryParagraph,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (sec.keyPoints.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    sec.keyPoints.forEach { pt ->
                        Text(
                            text = "• $pt",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FormulasAndDiagramsTabContent(studyDocument: StudyDocument) {
    if (studyDocument.formulas.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "No explicit mathematical equations were present in this video source.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
    } else {
        studyDocument.formulas.forEach { formula ->
            FormulaPreviewCard(formula = formula)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    if (studyDocument.diagrams.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        studyDocument.diagrams.forEach { diagram ->
            StudyVectorDiagramPreviewCard(diagram = diagram)
            if (diagram.mermaidDsl.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = "Mermaid.js Specification:\n${diagram.mermaidDsl}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    if (studyDocument.charts.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        studyDocument.charts.forEach { chart ->
            LocalChartPreviewCard(chart = chart)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    if (studyDocument.schematics.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        studyDocument.schematics.forEach { schematic ->
            LocalSchematicPreviewCard(schematic = schematic)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    if (studyDocument.extractedVideoFrames.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        studyDocument.extractedVideoFrames.forEach { frame ->
            ExtractedVideoFrameCard(frame = frame)
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
fun LocalChartPreviewCard(chart: DataChartSpec) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chart_card_${chart.title.take(15)}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "${chart.title} (Local Chart.js / SVG Plot)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    LocalCodeRenderEngine.drawChartOnAndroidCanvas(
                        canvas = drawContext.canvas.nativeCanvas,
                        chart = chart,
                        left = 0f,
                        top = 0f,
                        width = size.width,
                        height = size.height
                    )
                }
            }
            if (chart.caption.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = chart.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun LocalSchematicPreviewCard(schematic: TechnicalSchematicSpec) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schematic_card_${schematic.title.take(15)}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "${schematic.title} (Local SVG / Vector Schematic)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(155.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    LocalCodeRenderEngine.drawSchematicOnAndroidCanvas(
                        canvas = drawContext.canvas.nativeCanvas,
                        schematic = schematic,
                        left = 0f,
                        top = 0f,
                        width = size.width,
                        height = size.height
                    )
                }
            }
            if (schematic.caption.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = schematic.caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ExtractedVideoFrameCard(frame: ExtractedVideoFrameSpec) {
    val bmp = remember(frame.jpegBase64) {
        try {
            val bytes = Base64.decode(frame.jpegBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "[${frame.timestampFormatted}] ${frame.sectionHeading}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (bmp != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = frame.caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = frame.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun FormulaPreviewCard(formula: MathFormula) {
    val expr = formula.katexExpression.ifBlank { formula.rawExpression }
    val cleanDisplay = remember(expr) {
        MathFormulaRenderer.formatToCleanUnicode(expr)
    }
    val ast = remember(expr) {
        MathFormulaRenderer.parseToAst(expr)
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("formula_card_${formula.name.take(15)}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formula.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (formula.isDirectlyFromSource) "Source Verified • KaTeX" else "Explanatory • KaTeX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (formula.units.isNotBlank()) {
                Text(
                    text = "SI Units: ${formula.units}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            // 2D Native Canvas Equation Renderer Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.parseColor("#0F172A")
                        textSize = 34f
                        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
                    }
                    val measured = MathFormulaRenderer.measureNode(ast, paint, 1f)
                    val startX = ((size.width - measured.width) / 2f).coerceAtLeast(8f)
                    val baselineY = (size.height / 2f) + (measured.ascent - measured.descent) / 3f
                    MathFormulaRenderer.drawNode(
                        canvas = drawContext.canvas.nativeCanvas,
                        node = ast,
                        startX = startX,
                        baselineY = baselineY,
                        basePaint = paint,
                        scale = 1f
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Formatted Notation: $cleanDisplay",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (formula.katexExpression.isNotBlank()) {
                Text(
                    text = "KaTeX Expression: ${formula.katexExpression}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (formula.variables.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                formula.variables.forEach { v ->
                    val sym = MathFormulaRenderer.formatToCleanUnicode(v.symbol)
                    val unitStr = if (v.unit.isNotBlank()) " [${v.unit}]" else ""
                    Text(
                        text = "• $sym : ${v.meaning}$unitStr",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (formula.derivationOrApplicationNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Application: ${formula.derivationOrApplicationNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TablesQuizAndGlossaryTabContent(studyDocument: StudyDocument) {
    studyDocument.comparisonTables.forEach { table ->
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = table.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    table.headers.forEach { h ->
                        Text(
                            text = h,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                table.rows.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                    ) {
                        row.forEach { cell ->
                            Text(
                                text = cell,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Revision Questions
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Self-Test Revision Questions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            studyDocument.revisionQuestions.forEach { rq ->
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        text = "Q${rq.questionNumber} [${rq.difficultyTag}]: ${rq.question}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Answer: ${rq.answerExplanation}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Glossary
    if (studyDocument.glossary.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Glossary of Technical Terms",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                studyDocument.glossary.forEach { g ->
                    Text(
                        text = "• ${g.term}: ${g.definition}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}
