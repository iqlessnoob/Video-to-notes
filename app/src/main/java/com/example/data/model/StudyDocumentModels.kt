package com.example.data.model

import kotlinx.serialization.Serializable

/**
 * Result of validating and inspecting a YouTube URL or local device video prior to starting a job.
 */
@Serializable
data class VideoInspectionResult(
    val isValid: Boolean,
    val sourceType: VideoSourceType,
    val normalizedUri: String,
    val videoIdOrFileName: String,
    val title: String,
    val authorOrChannel: String = "",
    val durationSeconds: Long,
    val durationFormatted: String,
    val fileSizeMb: Double? = null,
    val resolutionLabel: String? = null,
    val extractedTranscript: String? = null,
    val transcriptStatusMessage: String = "",
    val requiresSupplementaryNotes: Boolean = false,
    val errorMessage: String? = null,
    val estimatedSectionsCount: Int = 1
)

/**
 * Telemetry report quantifying AI token optimization for a generated StudyDocument.
 * Strictly separates AI calls required for content understanding from AI calls avoided by local code-based rendering.
 */
@Serializable
data class AiTokenOptimizationTelemetry(
    val aiCallsForContentUnderstanding: Int = 0,
    val estimatedPromptTokens: Int = 0,
    val estimatedCompletionTokens: Int = 0,
    val transcriptCompressionRatioPercent: Int = 0,
    val aiCallsAvoidedByLocalMathKatex: Int = 0,
    val aiCallsAvoidedByLocalMermaidDiagrams: Int = 0,
    val aiCallsAvoidedByLocalChartsAndSchematics: Int = 0,
    val aiCallsAvoidedByLocalHtmlCssTablesAndPdfLayout: Int = 0,
    val servedFromLocalContentHashCache: Boolean = false
) {
    val totalAiCallsAvoidedByLocalRendering: Int
        get() = aiCallsAvoidedByLocalMathKatex +
            aiCallsAvoidedByLocalMermaidDiagrams +
            aiCallsAvoidedByLocalChartsAndSchematics +
            aiCallsAvoidedByLocalHtmlCssTablesAndPdfLayout
}

/**
 * Complete structured study document generated from the staged video analysis pipeline
 * (Content-Generation Layer) and passed as validated JSON into the Local Code-Based Rendering Layer.
 */
@Serializable
data class StudyDocument(
    val documentId: String,
    val title: String,
    val subtitle: String = "",
    val sourceTypeLabel: String,
    val sourceUrlOrFileName: String,
    val authorOrChannel: String = "",
    val videoDurationFormatted: String,
    val detailLevel: StudyDetailLevel,
    val generatedDateFormatted: String,
    val executiveSummary: String,
    val sections: List<StudySection> = emptyList(),
    val concepts: List<ConceptDefinition> = emptyList(),
    val formulas: List<MathFormula> = emptyList(),
    val diagrams: List<StructuredDiagram> = emptyList(),
    val charts: List<DataChartSpec> = emptyList(),
    val schematics: List<TechnicalSchematicSpec> = emptyList(),
    val extractedVideoFrames: List<ExtractedVideoFrameSpec> = emptyList(),
    val comparisonTables: List<ComparisonTable> = emptyList(),
    val keyTakeaways: List<String> = emptyList(),
    val revisionQuestions: List<RevisionQuestion> = emptyList(),
    val glossary: List<GlossaryEntry> = emptyList(),
    val tokenTelemetry: AiTokenOptimizationTelemetry = AiTokenOptimizationTelemetry(),
    val limitationNotice: String? = null
)

@Serializable
data class StudySection(
    val sectionNumber: Int,
    val timestampRange: String,
    val heading: String,
    val summaryParagraph: String,
    val keyPoints: List<String> = emptyList()
)

@Serializable
data class ConceptDefinition(
    val term: String,
    val definition: String,
    val contextOrExample: String = ""
)

@Serializable
data class MathFormula(
    val name: String,
    val rawExpression: String,
    val katexExpression: String = "",
    val units: String = "",
    val variables: List<VariableExplanation> = emptyList(),
    val derivationOrApplicationNote: String = "",
    val isDirectlyFromSource: Boolean = true
)

@Serializable
data class VariableExplanation(
    val symbol: String,
    val meaning: String,
    val unit: String = ""
)

@Serializable
enum class DiagramType {
    FLOWCHART,
    CYCLE,
    HIERARCHY,
    MECHANISM,
    CAUSE_AND_EFFECT
}

@Serializable
data class StructuredDiagram(
    val title: String,
    val diagramType: DiagramType = DiagramType.FLOWCHART,
    val mermaidDsl: String = "",
    val nodes: List<DiagramNode> = emptyList(),
    val edges: List<DiagramEdge> = emptyList(),
    val caption: String = ""
)

@Serializable
data class DiagramNode(
    val id: String,
    val label: String,
    val subLabel: String = ""
)

@Serializable
data class DiagramEdge(
    val fromId: String,
    val toId: String,
    val relationLabel: String = ""
)

@Serializable
enum class ChartPlotType {
    LINE,
    BAR
}

/**
 * Structured specification for quantitative charts and graphs rendered locally via Chart.js / SVG / Canvas.
 */
@Serializable
data class DataChartSpec(
    val title: String,
    val chartType: ChartPlotType = ChartPlotType.LINE,
    val xAxisLabel: String,
    val yAxisLabel: String,
    val xLabels: List<String> = emptyList(),
    val seriesName: String,
    val yValues: List<Double> = emptyList(),
    val secondarySeriesName: String = "",
    val secondaryYValues: List<Double> = emptyList(),
    val caption: String = ""
)

/**
 * Structured specification for technical schematics rendered locally via SVG and native Canvas drawing tools.
 */
@Serializable
data class TechnicalSchematicSpec(
    val title: String,
    val elements: List<SchematicElement> = emptyList(),
    val caption: String = ""
)

@Serializable
enum class SchematicElementType {
    BLOCK,
    CIRCLE_BODY,
    FORCE_ARROW,
    AXIS_LINE,
    ANNOTATION_LABEL
}

@Serializable
data class SchematicElement(
    val type: SchematicElementType,
    val x1Percent: Float,
    val y1Percent: Float,
    val x2Percent: Float = 0f,
    val y2Percent: Float = 0f,
    val label: String = "",
    val colorHex: String = "#1D4ED8"
)

/**
 * Extracted video frame from a local video file (via MediaMetadataRetriever) when supported and permitted.
 */
@Serializable
data class ExtractedVideoFrameSpec(
    val timestampFormatted: String,
    val sectionHeading: String,
    val caption: String,
    val jpegBase64: String
)

@Serializable
data class ComparisonTable(
    val title: String,
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList()
)

@Serializable
data class RevisionQuestion(
    val questionNumber: Int,
    val question: String,
    val answerExplanation: String,
    val difficultyTag: String = "Conceptual"
)

@Serializable
data class GlossaryEntry(
    val term: String,
    val definition: String
)
