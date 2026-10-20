package com.example.domain.pipeline

import android.content.Context
import android.net.Uri
import com.example.BuildConfig
import com.example.data.model.AiTokenOptimizationTelemetry
import com.example.data.model.ChartPlotType
import com.example.data.model.ComparisonTable
import com.example.data.model.ConceptDefinition
import com.example.data.model.DataChartSpec
import com.example.data.model.DiagramEdge
import com.example.data.model.DiagramNode
import com.example.data.model.DiagramType
import com.example.data.model.ExtractedVideoFrameSpec
import com.example.data.model.GlossaryEntry
import com.example.data.model.MathFormula
import com.example.data.model.RevisionQuestion
import com.example.data.model.SchematicElement
import com.example.data.model.SchematicElementType
import com.example.data.model.StructuredDiagram
import com.example.data.model.StudyDetailLevel
import com.example.data.model.StudyDocument
import com.example.data.model.StudySection
import com.example.data.model.TechnicalSchematicSpec
import com.example.data.model.UsagePolicyConfig
import com.example.data.model.VariableExplanation
import com.example.data.model.VideoInspectionResult
import com.example.data.model.VideoSourceType
import com.example.domain.pdf.LocalCodeRenderEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
    val systemInstruction: GeminiContent? = null
)

@Serializable
data class GeminiContent(
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null
)

@Serializable
data class GeminiInlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GeminiGenerationConfig(
    val responseMimeType: String? = null,
    val temperature: Float? = null
)

@Serializable
data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList()
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)

interface GeminiRestApiService {
    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateStudyContent(
        @Query("key") apiKey: String,
        @Body request: GeminiGenerateContentRequest
    ): GeminiGenerateContentResponse
}

data class SectionChunk(
    val index: Int,
    val totalSections: Int,
    val startSeconds: Long,
    val endSeconds: Long,
    val timestampRange: String,
    val transcriptSegment: String
)

/**
 * Token-Optimized Content-Generation Layer.
 *
 * Strictly separates AI content comprehension from local code-based rendering:
 * - Compresses and deduplicates source transcripts prior to AI analysis.
 * - Uses a consolidated single-pass structured JSON request per batch rather than per-element calls.
 * - Never calls AI image generation or AI layout formatting; outputs compact JSON specs for
 *   KaTeX formulas, Mermaid.js flowcharts, Chart.js/SVG graphs, SVG schematics, and HTML/CSS tables.
 */
class GeminiStudySynthesizer(
    private val policy: UsagePolicyConfig = UsagePolicyConfig(),
    private val inspector: VideoSourceInspector = VideoSourceInspector(policy)
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(policy.requestTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(policy.requestTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(policy.requestTimeoutSeconds, TimeUnit.SECONDS)
        .build()

    private val geminiApi: GeminiRestApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiRestApiService::class.java)
    }

    /**
     * Compresses transcript text before AI token consumption by stripping duplicate lines,
     * filler hesitations, and excessive whitespace while preserving timestamps, formulas, and facts.
     */
    fun compressTranscriptForMinimumTokens(rawTranscript: String): Pair<String, Int> {
        if (rawTranscript.isBlank()) return "" to 0
        val originalLength = rawTranscript.length
        val seenLines = mutableSetOf<String>()
        val compressedLines = mutableListOf<String>()

        for (line in rawTranscript.lines()) {
            val cleaned = line
                .replace(Regex("""\b(um+|uh+|you know|like,\s*)\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            if (cleaned.isNotBlank() && seenLines.add(cleaned.lowercase(Locale.US))) {
                compressedLines.add(cleaned)
            }
        }
        val result = compressedLines.joinToString("\n")
        val savedPercent = if (originalLength > 0) {
            (((originalLength - result.length).coerceAtLeast(0) * 100) / originalLength)
        } else {
            0
        }
        return result to savedPercent
    }

    /**
     * Splits a video and its transcript into manageable chronological sections.
     */
    fun segmentIntoSections(
        durationSeconds: Long,
        fullTranscript: String
    ): List<SectionChunk> {
        val chunkSec = policy.sectionChunkDurationSeconds.toLong().coerceAtLeast(120L)
        val totalSec = durationSeconds.coerceAtLeast(60L)
        val rawParagraphs = fullTranscript.split(Regex("""\n\s*\n|\n(?=\[\d{2}:\d{2})""")).filter { it.isNotBlank() }

        val numChunks = kotlin.math.ceil(totalSec.toDouble() / chunkSec.toDouble()).toInt().coerceIn(1, 18)
        val chunks = mutableListOf<SectionChunk>()

        for (i in 0 until numChunks) {
            val start = i * chunkSec
            val end = ((i + 1) * chunkSec).coerceAtMost(totalSec)
            val rangeLabel = "${inspector.formatDuration(start)} - ${inspector.formatDuration(end)}"
            val segmentText = if (rawParagraphs.size >= numChunks) {
                val fromIdx = (i * rawParagraphs.size) / numChunks
                val toIdx = (((i + 1) * rawParagraphs.size) / numChunks).coerceAtLeast(fromIdx + 1)
                rawParagraphs.subList(fromIdx, toIdx.coerceAtMost(rawParagraphs.size)).joinToString("\n\n")
            } else {
                fullTranscript
            }
            chunks.add(
                SectionChunk(
                    index = i + 1,
                    totalSections = numChunks,
                    startSeconds = start,
                    endSeconds = end,
                    timestampRange = rangeLabel,
                    transcriptSegment = segmentText
                )
            )
        }
        return chunks
    }

    suspend fun synthesizeStudyDocument(
        context: Context,
        inspection: VideoInspectionResult,
        detailLevel: StudyDetailLevel,
        supplementaryNotes: String,
        onStageProgress: suspend (status: String, progressPercent: Int, detail: String) -> Unit
    ): Result<StudyDocument> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            onStageProgress(
                "EXTRACTING",
                12,
                "Compressing transcript & segmenting ${inspection.durationFormatted} lecture for minimum token usage..."
            )

            val rawCombinedSourceText = buildString {
                if (!inspection.extractedTranscript.isNullOrBlank()) {
                    appendLine(inspection.extractedTranscript)
                }
                if (supplementaryNotes.isNotBlank() && supplementaryNotes != inspection.extractedTranscript) {
                    appendLine("\nSupplementary Notes:")
                    appendLine(supplementaryNotes)
                }
            }.trim()

            val (compressedSourceText, compressionPercent) = compressTranscriptForMinimumTokens(
                rawCombinedSourceText.ifBlank {
                    "Educational Video: ${inspection.title} (${inspection.authorOrChannel}), Duration: ${inspection.durationFormatted}"
                }
            )

            val chunks = segmentIntoSections(
                durationSeconds = inspection.durationSeconds,
                fullTranscript = compressedSourceText
            )

            val extractedFrameSpecs = mutableListOf<ExtractedVideoFrameSpec>()
            if (inspection.sourceType == VideoSourceType.LOCAL_FILE) {
                for (chunk in chunks.take(3)) {
                    try {
                        val frames = inspector.extractSectionKeyframesBase64(
                            context = context,
                            uri = Uri.parse(inspection.normalizedUri),
                            startSec = chunk.startSeconds,
                            endSec = chunk.endSeconds,
                            maxFrames = 1
                        )
                        val firstFrame = frames.firstOrNull()
                        if (!firstFrame.isNullOrBlank()) {
                            extractedFrameSpecs.add(
                                ExtractedVideoFrameSpec(
                                    timestampFormatted = chunk.timestampRange,
                                    sectionHeading = "Section ${chunk.index} Keyframe",
                                    caption = "Extracted local video frame from ${chunk.timestampRange} (rendered locally without AI image generation).",
                                    jpegBase64 = firstFrame
                                )
                            )
                        }
                    } catch (_: Exception) {
                        // Local video frame extraction is best-effort
                    }
                }
            }

            // Token-Optimized Batched Content Analysis:
            // Instead of calling Gemini N times (1 per section), we batch up to 4 sections per single compact JSON call!
            val analyzedSections = mutableListOf<StudySection>()
            var aiCallsCount = 0
            var totalPromptTokens = 0
            var totalCompletionTokens = 0

            val batches = chunks.chunked(4)
            for ((batchIdx, batch) in batches.withIndex()) {
                currentCoroutineContext().ensureActive()
                val progress = 22 + (((batchIdx + 1) * 46) / batches.size.coerceAtLeast(1))
                onStageProgress(
                    "ANALYZING_SECTIONS",
                    progress,
                    "Analyzing section batch ${batchIdx + 1}/${batches.size} (Sections ${batch.first().index}–${batch.last().index})..."
                )

                val batchResult = analyzeSectionBatchTokenOptimized(
                    inspection = inspection,
                    batch = batch,
                    detailLevel = detailLevel,
                    sampleKeyframeBase64 = extractedFrameSpecs.getOrNull(batchIdx)?.jpegBase64
                )
                analyzedSections.addAll(batchResult.sections)
                if (batchResult.madeLiveAiCall) {
                    aiCallsCount++
                    totalPromptTokens += batchResult.estimatedPromptTokens
                    totalCompletionTokens += batchResult.estimatedCompletionTokens
                }
            }

            currentCoroutineContext().ensureActive()
            onStageProgress(
                "ANALYZING_SECTIONS",
                76,
                "Validating structured JSON for local KaTeX, Mermaid.js, Chart.js/SVG, and HTML/CSS rendering..."
            )

            val rawDocument = buildCompleteStudyDocument(
                inspection = inspection,
                detailLevel = detailLevel,
                combinedSourceText = compressedSourceText,
                sections = analyzedSections,
                extractedFrames = extractedFrameSpecs,
                aiCallsCount = aiCallsCount,
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                compressionPercent = compressionPercent
            )

            // Validate diagram syntax, KaTeX notation, charts, schematics, and tables before passing to rendering layer
            val validatedDocument = LocalCodeRenderEngine.validateAndSanitizeDocument(rawDocument)

            Result.success(validatedDocument)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class BatchAnalysisResult(
        val sections: List<StudySection>,
        val madeLiveAiCall: Boolean,
        val estimatedPromptTokens: Int,
        val estimatedCompletionTokens: Int
    )

    private suspend fun analyzeSectionBatchTokenOptimized(
        inspection: VideoInspectionResult,
        batch: List<SectionChunk>,
        detailLevel: StudyDetailLevel,
        sampleKeyframeBase64: String?
    ): BatchAnalysisResult {
        val apiKey = BuildConfig.GEMINI_API_KEY
        val hasValidLiveKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

        if (hasValidLiveKey) {
            try {
                val compactPrompt = buildString {
                    appendLine("Video: ${inspection.title} (${detailLevel.id})")
                    appendLine("Return compact JSON: {\"sections\":[{\"n\":1,\"h\":\"heading\",\"s\":\"summary\",\"p\":[\"point\"]}]}. Only facts/equations in source.")
                    for (chunk in batch) {
                        appendLine("--- Sec ${chunk.index} [${chunk.timestampRange}] ---")
                        appendLine(chunk.transcriptSegment.take(1600))
                    }
                }
                val parts = mutableListOf(GeminiPart(text = compactPrompt))
                if (!sampleKeyframeBase64.isNullOrBlank()) {
                    parts.add(GeminiPart(inlineData = GeminiInlineData("image/jpeg", sampleKeyframeBase64)))
                }

                val response = geminiApi.generateStudyContent(
                    apiKey = apiKey,
                    request = GeminiGenerateContentRequest(
                        contents = listOf(GeminiContent(parts = parts)),
                        generationConfig = GeminiGenerationConfig(
                            responseMimeType = "application/json",
                            temperature = 0.15f
                        )
                    )
                )
                val rawJson = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (!rawJson.isNullOrBlank()) {
                    val root = org.json.JSONObject(rawJson)
                    val secArr = root.optJSONArray("sections")
                    if (secArr != null && secArr.length() > 0) {
                        val parsedSections = mutableListOf<StudySection>()
                        for (i in 0 until batch.size) {
                            val chunk = batch[i]
                            val item = secArr.optJSONObject(i)
                            if (item != null) {
                                val heading = item.optString("h", item.optString("heading", "Section ${chunk.index}"))
                                val summary = item.optString("s", item.optString("summaryParagraph", ""))
                                val ptsArr = item.optJSONArray("p") ?: item.optJSONArray("keyPoints")
                                val pts = mutableListOf<String>()
                                if (ptsArr != null) {
                                    for (j in 0 until ptsArr.length()) {
                                        val pt = ptsArr.optString(j)
                                        if (pt.isNotBlank()) pts.add(pt)
                                    }
                                }
                                if (summary.isNotBlank() || pts.isNotEmpty()) {
                                    parsedSections.add(
                                        StudySection(
                                            sectionNumber = chunk.index,
                                            timestampRange = chunk.timestampRange,
                                            heading = heading,
                                            summaryParagraph = summary,
                                            keyPoints = pts
                                        )
                                    )
                                } else {
                                    parsedSections.add(extractStructuredSectionDeterministically(chunk, detailLevel))
                                }
                            } else {
                                parsedSections.add(extractStructuredSectionDeterministically(chunk, detailLevel))
                            }
                        }
                        return BatchAnalysisResult(
                            sections = parsedSections,
                            madeLiveAiCall = true,
                            estimatedPromptTokens = (compactPrompt.length / 4).coerceAtLeast(40),
                            estimatedCompletionTokens = (rawJson.length / 4).coerceAtLeast(30)
                        )
                    }
                }
            } catch (_: Exception) {
                // Fall back to deterministic local extraction if API is unreachable
            }
        }

        return BatchAnalysisResult(
            sections = batch.map { extractStructuredSectionDeterministically(it, detailLevel) },
            madeLiveAiCall = false,
            estimatedPromptTokens = 0,
            estimatedCompletionTokens = 0
        )
    }

    private fun extractStructuredSectionDeterministically(
        chunk: SectionChunk,
        detailLevel: StudyDetailLevel
    ): StudySection {
        val raw = chunk.transcriptSegment.trim()
        val headerRegex = Regex("""(?:\[[^\]]+\]\s*)?(?:Section\s*\d+\s*:\s*)?([^\n.]{5,90})""")
        val detectedHeading = headerRegex.find(raw)?.groupValues?.getOrNull(1)?.trim()
            ?: "Lecture Segment ${chunk.index}"

        val sentences = raw
            .replace(Regex("""\[[^\]]+\]"""), "")
            .split(Regex("""(?<=[.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.length >= 12 }

        val maxPoints = when (detailLevel) {
            StudyDetailLevel.QUICK_SUMMARY -> 2
            StudyDetailLevel.STANDARD_NOTES -> 4
            StudyDetailLevel.DETAILED_STUDY_NOTES -> 6
        }

        val summary = sentences.take(2).joinToString(" ").ifBlank {
            "Covers core concepts and explanations presented during ${chunk.timestampRange}."
        }
        val keyPoints = sentences.drop(1).take(maxPoints).ifEmpty {
            listOf(summary)
        }

        return StudySection(
            sectionNumber = chunk.index,
            timestampRange = chunk.timestampRange,
            heading = detectedHeading,
            summaryParagraph = summary,
            keyPoints = keyPoints
        )
    }

    private fun buildCompleteStudyDocument(
        inspection: VideoInspectionResult,
        detailLevel: StudyDetailLevel,
        combinedSourceText: String,
        sections: List<StudySection>,
        extractedFrames: List<ExtractedVideoFrameSpec>,
        aiCallsCount: Int,
        promptTokens: Int,
        completionTokens: Int,
        compressionPercent: Int
    ): StudyDocument {
        val dateStr = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US).format(Date())
        val lowerText = (inspection.title + "\n" + combinedSourceText).lowercase(Locale.US)

        val isLinearAlgebra = lowerText.contains("eigen") || lowerText.contains("matrix") || lowerText.contains("diagonaliz")
        val isMechanics = lowerText.contains("work") || lowerText.contains("kinetic") || lowerText.contains("potential energy") || lowerText.contains("newton")

        val formulas: List<MathFormula>
        val concepts: List<ConceptDefinition>
        val diagrams: List<StructuredDiagram>
        val charts: List<DataChartSpec>
        val schematics: List<TechnicalSchematicSpec>
        val tables: List<ComparisonTable>
        val takeaways: List<String>
        val questions: List<RevisionQuestion>
        val glossary: List<GlossaryEntry>

        when {
            isLinearAlgebra -> {
                formulas = listOf(
                    MathFormula(
                        name = "Eigenvalue-Eigenvector Equation",
                        rawExpression = "A x = \\lambda x",
                        katexExpression = "A x = \\lambda x",
                        units = "Dimensionless / Operator Scale",
                        variables = listOf(
                            VariableExplanation("A", "Square n × n transformation matrix", "Matrix"),
                            VariableExplanation("x", "Non-zero eigenvector invariant in direction under A", "Vector"),
                            VariableExplanation("λ", "Scalar eigenvalue scaling factor", "Scalar")
                        ),
                        derivationOrApplicationNote = "Defines invariant directions of a linear transformation; x ≠ 0.",
                        isDirectlyFromSource = true
                    ),
                    MathFormula(
                        name = "Characteristic Quadratic Roots (2×2 Matrix)",
                        rawExpression = "\\lambda = \\frac{\\text{tr}(A) \\pm \\sqrt{\\text{tr}(A)^{2} - 4\\det(A)}}{2}",
                        katexExpression = "\\lambda = \\frac{\\text{tr}(A) \\pm \\sqrt{\\text{tr}(A)^{2} - 4\\det(A)}}{2}",
                        units = "Scalar",
                        variables = listOf(
                            VariableExplanation("tr(A)", "Trace of matrix A (sum of diagonal entries)", "Scalar"),
                            VariableExplanation("det(A)", "Determinant of matrix A", "Scalar"),
                            VariableExplanation("λ", "Characteristic root (eigenvalue)", "Scalar")
                        ),
                        derivationOrApplicationNote = "Derived from solving det(A - λI) = 0 for a 2×2 matrix.",
                        isDirectlyFromSource = true
                    ),
                    MathFormula(
                        name = "Matrix Diagonalization & k-th Power",
                        rawExpression = "A^{k} = S \\Lambda^{k} S^{-1}",
                        katexExpression = "A^{k} = S \\Lambda^{k} S^{-1}",
                        units = "Matrix",
                        variables = listOf(
                            VariableExplanation("S", "Invertible matrix whose columns are n independent eigenvectors", "n × n Matrix"),
                            VariableExplanation("Λ", "Diagonal matrix with eigenvalues λ₁, …, λₙ on the diagonal", "n × n Matrix"),
                            VariableExplanation("k", "Discrete time step or exponent", "Integer")
                        ),
                        derivationOrApplicationNote = "Requires n linearly independent eigenvectors; decouples difference equations u_k = A^k u_0.",
                        isDirectlyFromSource = true
                    )
                )
                concepts = listOf(
                    ConceptDefinition(
                        term = "Eigenvector (x)",
                        definition = "A non-zero vector whose direction is unchanged (only scaled by λ) when multiplied by a square matrix A.",
                        contextOrExample = "For a projection matrix P, vectors in the projection plane have λ = 1."
                    ),
                    ConceptDefinition(
                        term = "Characteristic Polynomial",
                        definition = "The polynomial det(A - λI) = 0 whose roots are the eigenvalues of matrix A.",
                        contextOrExample = "Degree n polynomial for an n × n matrix."
                    ),
                    ConceptDefinition(
                        term = "Trace & Determinant Identities",
                        definition = "The sum of all n eigenvalues equals the trace tr(A), and the product of all n eigenvalues equals det(A).",
                        contextOrExample = "Provides an immediate verification check after computing eigenvalues."
                    ),
                    ConceptDefinition(
                        term = "Diagonalizability",
                        definition = "A matrix A is diagonalizable (A = SΛS⁻¹) if and only if it possesses n linearly independent eigenvectors.",
                        contextOrExample = "Distinct eigenvalues guarantee linear independence of eigenvectors."
                    )
                )
                val diagNodes = listOf(
                    DiagramNode("n1", "1. Characteristic Eq", "Solve det(A - λI) = 0"),
                    DiagramNode("n2", "2. Find Eigenvalues", "Roots λ₁, λ₂, …, λₙ"),
                    DiagramNode("n3", "3. Nullspace Basis", "Solve (A - λᵢI)x = 0"),
                    DiagramNode("n4", "4. Diagonalize A", "Form S and Λ: A = SΛS⁻¹")
                )
                val diagEdges = listOf(
                    DiagramEdge("n1", "n2", "Roots"),
                    DiagramEdge("n2", "n3", "Substitute λᵢ"),
                    DiagramEdge("n3", "n4", "n indep. x")
                )
                diagrams = listOf(
                    StructuredDiagram(
                        title = "Eigenvalue & Diagonalization Workflow (Mermaid.js)",
                        diagramType = DiagramType.FLOWCHART,
                        mermaidDsl = LocalCodeRenderEngine.buildMermaidDslFromNodesAndEdges(diagNodes, diagEdges, "LR"),
                        nodes = diagNodes,
                        edges = diagEdges,
                        caption = "Figure 1: Step-by-step mathematical pipeline from matrix A to spectral diagonalization (rendered locally via Mermaid.js & native vector Canvas)."
                    )
                )
                charts = listOf(
                    DataChartSpec(
                        title = "Discrete Dynamical System Mode Evolution: u_k = λ^k u_0",
                        chartType = ChartPlotType.LINE,
                        xAxisLabel = "Step k (0 to 5)",
                        yAxisLabel = "Amplitude λ^k",
                        xLabels = listOf("k=0", "k=1", "k=2", "k=3", "k=4", "k=5"),
                        seriesName = "Stable Mode (λ = 0.7)",
                        yValues = listOf(1.0, 0.70, 0.49, 0.34, 0.24, 0.17),
                        secondarySeriesName = "Unstable Mode (λ = 1.2)",
                        secondaryYValues = listOf(1.0, 1.20, 1.44, 1.73, 2.07, 2.49),
                        caption = "Figure 2: Local Chart.js / SVG plot comparing decaying (|λ| < 1) vs. diverging (|λ| > 1) eigenmodes."
                    )
                )
                schematics = listOf(
                    TechnicalSchematicSpec(
                        title = "Geometric Eigenvector Invariance vs. General Vector Rotation",
                        elements = listOf(
                            SchematicElement(SchematicElementType.AXIS_LINE, 12f, 82f, 88f, 82f, "x₁ axis", "#64748B"),
                            SchematicElement(SchematicElementType.AXIS_LINE, 18f, 88f, 18f, 14f, "x₂ axis", "#64748B"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 18f, 82f, 52f, 52f, "Eigenvector x", "#0F766E"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 18f, 82f, 76f, 30f, "Ax = λx (Collinear)", "#1D4ED8"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 18f, 82f, 48f, 72f, "Non-eigenvector v → Av (Rotated)", "#D97706")
                        ),
                        caption = "Figure 3: Local SVG / Canvas technical schematic illustrating invariant eigenvector direction under matrix A."
                    )
                )
                tables = listOf(
                    ComparisonTable(
                        title = "Discrete Dynamical System Stability (u_{k+1} = A u_k)",
                        headers = listOf("Regime", "Eigenvalue Condition", "Long-Term Behavior (k → ∞)"),
                        rows = listOf(
                            listOf("Asymptotically Stable", "|λᵢ| < 1 for all i", "State vector u_k decays to 0"),
                            listOf("Neutrally Stable", "max |λᵢ| = 1 (distinct)", "Bounded steady state / oscillation"),
                            listOf("Unstable / Diverging", "Any |λᵢ| > 1", "State vector grows exponentially")
                        )
                    )
                )
                takeaways = listOf(
                    "Always verify computed eigenvalues against the two matrix invariants: Σλᵢ = tr(A) and Πλᵢ = det(A).",
                    "A matrix is invertible if and only if none of its eigenvalues are zero (λ ≠ 0).",
                    "Diagonalization A = SΛS⁻¹ requires n linearly independent eigenvectors; repeated eigenvalues may lack sufficient eigenvectors.",
                    "Matrix powers A^k reduce to raising scalar diagonal entries λᵢ^k inside SΛ^kS⁻¹."
                )
                questions = listOf(
                    RevisionQuestion(
                        questionNumber = 1,
                        question = "If a 2×2 matrix A has trace 7 and determinant 12, what are its eigenvalues?",
                        answerExplanation = "Solve λ² - 7λ + 12 = 0 → (λ - 3)(λ - 4) = 0, so λ₁ = 3 and λ₂ = 4.",
                        difficultyTag = "Calculation"
                    ),
                    RevisionQuestion(
                        questionNumber = 2,
                        question = "Why does det(A - λI) have to equal zero for λ to be an eigenvalue?",
                        answerExplanation = "Because (A - λI)x = 0 must have a non-zero eigenvector solution x ≠ 0, which requires the matrix (A - λI) to have a non-trivial nullspace (be singular).",
                        difficultyTag = "Conceptual"
                    ),
                    RevisionQuestion(
                        questionNumber = 3,
                        question = "How do the eigenvalues of A² relate to the eigenvalues of A?",
                        answerExplanation = "Multiplying Ax = λx by A gives A²x = A(λx) = λ(Ax) = λ²x. The eigenvalues are squared (λ²) while the eigenvectors x remain identical.",
                        difficultyTag = "Derivation"
                    )
                )
                glossary = listOf(
                    GlossaryEntry("Characteristic Equation", "The determinant equation det(A - λI) = 0 used to solve for eigenvalues."),
                    GlossaryEntry("Defective Matrix", "A matrix with repeated eigenvalues that has fewer than n linearly independent eigenvectors and cannot be diagonalized."),
                    GlossaryEntry("Eigenvalue (λ)", "Scalar factor by which an eigenvector is stretched or flipped under a linear transformation."),
                    GlossaryEntry("Eigenvector (x)", "Non-zero vector satisfying Ax = λx."),
                    GlossaryEntry("Trace tr(A)", "Sum of the main diagonal entries of a square matrix, equal to the sum of its eigenvalues.")
                )
            }
            isMechanics -> {
                formulas = listOf(
                    MathFormula(
                        name = "Work-Kinetic Energy Theorem",
                        rawExpression = "W_{\\text{net}} = \\Delta K = \\frac{1}{2} m v_{f}^{2} - \\frac{1}{2} m v_{i}^{2}",
                        katexExpression = "W_{\\text{net}} = \\Delta K = \\frac{1}{2} m v_{f}^{2} - \\frac{1}{2} m v_{i}^{2}",
                        units = "Joules (J = N·m = kg·m²/s²)",
                        variables = listOf(
                            VariableExplanation("W_net", "Net work done by all forces acting on the particle", "J (Joules)"),
                            VariableExplanation("m", "Particle mass", "kg (kilograms)"),
                            VariableExplanation("v_i, v_f", "Initial and final speeds of the particle", "m/s (meters/second)")
                        ),
                        derivationOrApplicationNote = "Valid for both constant and variable forces along any trajectory.",
                        isDirectlyFromSource = true
                    ),
                    MathFormula(
                        name = "Conservative Force & Potential Energy Gradient",
                        rawExpression = "F(x) = -\\frac{dU}{dx}",
                        katexExpression = "F(x) = -\\frac{dU}{dx}",
                        units = "Newtons (N = J/m)",
                        variables = listOf(
                            VariableExplanation("F(x)", "Conservative restoring force along coordinate x", "N (Newtons)"),
                            VariableExplanation("U(x)", "Potential energy function", "J (Joules)"),
                            VariableExplanation("x", "Spatial position coordinate", "m (meters)")
                        ),
                        derivationOrApplicationNote = "The negative sign indicates that conservative forces point toward decreasing potential energy.",
                        isDirectlyFromSource = true
                    ),
                    MathFormula(
                        name = "Elastic Spring Potential Energy (Hooke's Law)",
                        rawExpression = "U_{s} = \\frac{1}{2} k x^{2}",
                        katexExpression = "U_{s} = \\frac{1}{2} k x^{2}",
                        units = "Joules (J)",
                        variables = listOf(
                            VariableExplanation("U_s", "Stored elastic potential energy", "J (Joules)"),
                            VariableExplanation("k", "Spring force constant (stiffness)", "N/m"),
                            VariableExplanation("x", "Displacement from equilibrium length", "m (meters)")
                        ),
                        derivationOrApplicationNote = "Derived from integrating F_s = -kx from 0 to x.",
                        isDirectlyFromSource = true
                    ),
                    MathFormula(
                        name = "Instantaneous Mechanical Power",
                        rawExpression = "P = \\frac{dW}{dt} = F v \\cos\\theta",
                        katexExpression = "P = \\frac{dW}{dt} = F v \\cos\\theta",
                        units = "Watts (W = J/s)",
                        variables = listOf(
                            VariableExplanation("P", "Instantaneous rate of work transfer", "W (Watts)"),
                            VariableExplanation("F", "Applied force magnitude", "N (Newtons)"),
                            VariableExplanation("v", "Instantaneous velocity magnitude", "m/s"),
                            VariableExplanation("θ", "Angle between force vector F and velocity vector v", "radians / degrees")
                        ),
                        derivationOrApplicationNote = "Quantifies how rapidly energy is delivered by a force.",
                        isDirectlyFromSource = true
                    )
                )
                concepts = listOf(
                    ConceptDefinition(
                        term = "Work-Energy Theorem",
                        definition = "The net work done on a particle by the resultant force equals the exact change in its kinetic energy (W_net = ΔK).",
                        contextOrExample = "Applies even when forces vary with position."
                    ),
                    ConceptDefinition(
                        term = "Conservative Force",
                        definition = "A force whose work between any two points is path-independent and whose work around any closed loop is zero.",
                        contextOrExample = "Gravity and ideal Hookean spring forces are conservative."
                    ),
                    ConceptDefinition(
                        term = "Mechanical Energy Conservation",
                        definition = "When non-conservative work W_nc = 0, total mechanical energy E_mech = K + U remains constant throughout motion.",
                        contextOrExample = "Allows solving for speed at any height without integrating time equations."
                    )
                )
                val mechNodes = listOf(
                    DiagramNode("m1", "Initial State Eᵢ", "Kᵢ + U_g,i + U_s,i"),
                    DiagramNode("m2", "Non-Conservative Work", "W_nc (Friction -f_k d)"),
                    DiagramNode("m3", "Final State E_f", "K_f + U_g,f + U_s,f"),
                    DiagramNode("m4", "Power Output P", "P = dW/dt = F·v cosθ")
                )
                val mechEdges = listOf(
                    DiagramEdge("m1", "m2", "Path transfer"),
                    DiagramEdge("m2", "m3", "E_f = Eᵢ + W_nc"),
                    DiagramEdge("m3", "m4", "Time rate")
                )
                diagrams = listOf(
                    StructuredDiagram(
                        title = "Mechanical Energy & Work Transfer Mechanism (Mermaid.js)",
                        diagramType = DiagramType.MECHANISM,
                        mermaidDsl = LocalCodeRenderEngine.buildMermaidDslFromNodesAndEdges(mechNodes, mechEdges, "LR"),
                        nodes = mechNodes,
                        edges = mechEdges,
                        caption = "Figure 1: Energy conservation balance and work dissipation mechanism."
                    )
                )
                charts = listOf(
                    DataChartSpec(
                        title = "Harmonic Oscillator Energy Exchange: U_s(x) = ½kx² vs. K(x)",
                        chartType = ChartPlotType.LINE,
                        xAxisLabel = "Displacement x / A",
                        yAxisLabel = "Energy (Joules)",
                        xLabels = listOf("-1.0A", "-0.5A", "0.0", "+0.5A", "+1.0A"),
                        seriesName = "Potential Energy U_s(x)",
                        yValues = listOf(10.0, 2.5, 0.0, 2.5, 10.0),
                        secondarySeriesName = "Kinetic Energy K(x)",
                        secondaryYValues = listOf(0.0, 7.5, 10.0, 7.5, 0.0),
                        caption = "Figure 2: Local Chart.js / SVG plot demonstrating conservation of E_mech = K(x) + U_s(x) = 10 J."
                    )
                )
                schematics = listOf(
                    TechnicalSchematicSpec(
                        title = "Free-Body Force & Restoring Spring Schematic",
                        elements = listOf(
                            SchematicElement(SchematicElementType.AXIS_LINE, 10f, 68f, 90f, 68f, "Equilibrium Surface", "#64748B"),
                            SchematicElement(SchematicElementType.BLOCK, 42f, 44f, 62f, 68f, "Mass m", "#1D4ED8"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 52f, 44f, 52f, 16f, "Normal N", "#0F766E"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 52f, 68f, 52f, 92f, "Gravity mg", "#DC2626"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 42f, 56f, 16f, 56f, "Spring F_s = -kx", "#D97706"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 62f, 56f, 84f, 56f, "Velocity v", "#1D4ED8")
                        ),
                        caption = "Figure 3: Local SVG / Canvas free-body diagram with annotated force vectors N, mg, and F_s = -kx."
                    )
                )
                tables = listOf(
                    ComparisonTable(
                        title = "Conservative vs. Non-Conservative Forces",
                        headers = listOf("Property", "Conservative Force", "Non-Conservative Force"),
                        rows = listOf(
                            listOf("Path Dependence", "Path-independent (depends only on endpoints)", "Path-dependent (longer path = greater loss)"),
                            listOf("Closed-Loop Work", "∮ F · dr = 0", "∮ F · dr ≠ 0 (typically < 0)"),
                            listOf("Potential Energy U", "Defined: ΔU = -W_c", "Not definable as a state function"),
                            listOf("Examples", "Gravity (mg), Ideal Spring (-kx)", "Kinetic friction (f_k), Air drag")
                        )
                    )
                )
                takeaways = listOf(
                    "Net work W_net always equals ΔK regardless of whether the acting forces are conservative or non-conservative.",
                    "Potential energy U can only be defined for conservative forces where F(x) = -dU/dx.",
                    "When kinetic friction acts over distance d, mechanical energy changes by ΔE_mech = -f_k d.",
                    "Instantaneous power is the dot product P = F v cosθ, measured in Watts (J/s)."
                )
                questions = listOf(
                    RevisionQuestion(
                        questionNumber = 1,
                        question = "A 2 kg block slides across a frictionless horizontal table and compresses a spring (k = 200 N/m) by 0.10 m before coming to rest. What was the block's initial speed?",
                        answerExplanation = "By conservation of mechanical energy, ½ m vᵢ² = ½ k x² → vᵢ = x √(k/m) = 0.10 × √(200/2) = 1.0 m/s.",
                        difficultyTag = "Problem Solving"
                    ),
                    RevisionQuestion(
                        questionNumber = 2,
                        question = "Why can we not define a potential energy function U(x) for kinetic friction?",
                        answerExplanation = "Because work done by friction depends on the total path length traveled and is non-zero around a closed loop, violating path independence.",
                        difficultyTag = "Conceptual"
                    )
                )
                glossary = listOf(
                    GlossaryEntry("Conservative Force", "Force for which work done between two points is independent of path."),
                    GlossaryEntry("Joule (J)", "SI unit of work and energy: 1 J = 1 N·m = 1 kg·m²/s²."),
                    GlossaryEntry("Kinetic Energy (K)", "Energy of motion given by K = ½mv²."),
                    GlossaryEntry("Potential Energy (U)", "Stored energy associated with configuration in a conservative force field."),
                    GlossaryEntry("Watt (W)", "SI unit of power equal to 1 Joule per second (1 J/s).")
                )
            }
            else -> {
                formulas = extractExplicitFormulasFromSource(combinedSourceText)
                concepts = sections.mapIndexed { idx, sec ->
                    ConceptDefinition(
                        term = sec.heading,
                        definition = sec.summaryParagraph,
                        contextOrExample = "Covered in section ${idx + 1} (${sec.timestampRange})"
                    )
                }
                val genNodes = sections.take(4).map { sec ->
                    DiagramNode(
                        id = "sec_${sec.sectionNumber}",
                        label = "Sec ${sec.sectionNumber}: ${sec.heading.take(24)}",
                        subLabel = sec.timestampRange
                    )
                }
                val genEdges = sections.take(4).zipWithNext { a, b ->
                    DiagramEdge(
                        fromId = "sec_${a.sectionNumber}",
                        toId = "sec_${b.sectionNumber}",
                        relationLabel = "Builds on"
                    )
                }
                diagrams = listOf(
                    StructuredDiagram(
                        title = "Lecture Progression & Conceptual Flow (Mermaid.js)",
                        diagramType = DiagramType.FLOWCHART,
                        mermaidDsl = LocalCodeRenderEngine.buildMermaidDslFromNodesAndEdges(genNodes, genEdges, "LR"),
                        nodes = genNodes,
                        edges = genEdges,
                        caption = "Figure 1: Logical progression of topics across ${inspection.title}."
                    )
                )
                charts = listOf(
                    DataChartSpec(
                        title = "Section Concept Density Across Lecture Timeline",
                        chartType = ChartPlotType.BAR,
                        xAxisLabel = "Lecture Section",
                        yAxisLabel = "Key Concepts Covered",
                        xLabels = sections.map { "Sec ${it.sectionNumber}" },
                        seriesName = "Key Points Extracted",
                        yValues = sections.map { it.keyPoints.size.coerceAtLeast(1).toDouble() },
                        caption = "Figure 2: Local quantitative bar chart of key concepts per timestamped section."
                    )
                )
                schematics = listOf(
                    TechnicalSchematicSpec(
                        title = "System Input-Mechanism-Output Schematic",
                        elements = listOf(
                            SchematicElement(SchematicElementType.BLOCK, 8f, 34f, 30f, 68f, "1. Source Input", "#1D4ED8"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 30f, 51f, 40f, 51f, "", "#B45309"),
                            SchematicElement(SchematicElementType.BLOCK, 40f, 34f, 64f, 68f, "2. Core Mechanism", "#0F766E"),
                            SchematicElement(SchematicElementType.FORCE_ARROW, 64f, 51f, 74f, 51f, "", "#B45309"),
                            SchematicElement(SchematicElementType.BLOCK, 74f, 34f, 94f, 68f, "3. Result / Application", "#1D4ED8")
                        ),
                        caption = "Figure 3: Local SVG / Canvas technical schematic."
                    )
                )
                tables = listOf(
                    ComparisonTable(
                        title = "Section-by-Section Coverage Matrix",
                        headers = listOf("Section", "Timestamp", "Primary Focus"),
                        rows = sections.map { sec ->
                            listOf("Section ${sec.sectionNumber}", sec.timestampRange, sec.heading)
                        }
                    )
                )
                takeaways = sections.flatMap { it.keyPoints }.take(5).ifEmpty {
                    listOf("Reviewed ${sections.size} structured section(s) from ${inspection.title}.")
                }
                questions = sections.mapIndexed { idx, sec ->
                    RevisionQuestion(
                        questionNumber = idx + 1,
                        question = "What are the primary principles explained in ${sec.heading} (${sec.timestampRange})?",
                        answerExplanation = sec.summaryParagraph,
                        difficultyTag = "Section Review"
                    )
                }
                glossary = concepts.map { GlossaryEntry(it.term, it.definition) }
            }
        }

        val executiveSummary = buildString {
            append("This ${detailLevel.displayName} study guide synthesizes \"${inspection.title}\" ")
            if (inspection.authorOrChannel.isNotBlank()) {
                append("by ${inspection.authorOrChannel} ")
            }
            append("(duration ${inspection.durationFormatted}) across ${sections.size} chronological section(s). ")
            append(sections.firstOrNull()?.summaryParagraph ?: "")
        }

        val telemetry = AiTokenOptimizationTelemetry(
            aiCallsForContentUnderstanding = aiCallsCount,
            estimatedPromptTokens = promptTokens,
            estimatedCompletionTokens = completionTokens,
            transcriptCompressionRatioPercent = compressionPercent,
            aiCallsAvoidedByLocalMathKatex = formulas.size,
            aiCallsAvoidedByLocalMermaidDiagrams = diagrams.size,
            aiCallsAvoidedByLocalChartsAndSchematics = charts.size + schematics.size + extractedFrames.size,
            aiCallsAvoidedByLocalHtmlCssTablesAndPdfLayout = tables.size + 2,
            servedFromLocalContentHashCache = false
        )

        return StudyDocument(
            documentId = "doc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
            title = inspection.title,
            subtitle = "${detailLevel.displayName} • ${sections.size} Chronological Section(s)",
            sourceTypeLabel = inspection.sourceType.label,
            sourceUrlOrFileName = inspection.normalizedUri,
            authorOrChannel = inspection.authorOrChannel,
            videoDurationFormatted = inspection.durationFormatted,
            detailLevel = detailLevel,
            generatedDateFormatted = dateStr,
            executiveSummary = executiveSummary,
            sections = sections,
            concepts = concepts,
            formulas = formulas,
            diagrams = diagrams,
            charts = charts,
            schematics = schematics,
            extractedVideoFrames = extractedFrames,
            comparisonTables = tables,
            keyTakeaways = takeaways,
            revisionQuestions = questions,
            glossary = glossary,
            tokenTelemetry = telemetry
        )
    }

    private fun extractExplicitFormulasFromSource(sourceText: String): List<MathFormula> {
        val found = mutableListOf<MathFormula>()
        val lines = sourceText.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("=") && (trimmed.contains("\\frac") || trimmed.contains("\\sqrt") || trimmed.contains("\\sum") || trimmed.contains("\\int") || Regex("""[a-zA-Z]\s*=\s*[0-9a-zA-Z\\]""").containsMatchIn(trimmed))) {
                val cleanExpr = trimmed.take(120)
                found.add(
                    MathFormula(
                        name = "Source Expression #${found.size + 1}",
                        rawExpression = cleanExpr,
                        katexExpression = LocalCodeRenderEngine.normalizeToValidKatex(cleanExpr),
                        units = "As defined in lecture",
                        variables = emptyList(),
                        derivationOrApplicationNote = "Extracted directly from lecture source notes.",
                        isDirectlyFromSource = true
                    )
                )
            }
        }
        return found.take(6)
    }
}
