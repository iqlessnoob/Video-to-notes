package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.backend.ServerCreditAndEntitlementCoordinator
import com.example.data.model.MathFormula
import com.example.data.model.PurchaseLifecycleState
import com.example.data.model.StructuredDiagram
import com.example.data.model.StudyDetailLevel
import com.example.data.model.SubscriptionTier
import com.example.data.model.UsagePolicyConfig
import com.example.domain.pdf.LocalCodeRenderEngine
import com.example.domain.pdf.MathFormulaRenderer
import com.example.domain.pdf.StudyPdfGenerator
import com.example.domain.pipeline.GeminiStudySynthesizer
import com.example.domain.pipeline.VideoSourceInspector
import com.example.ui.auth.AuthenticatedSessionUser
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appNameAndResourceConfiguration_matchesVideoToStudyPdf() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Video to Study PDF", appName)
    }

    @Test
    fun mathFormulaRenderer_convertsProgrammingAndLatexSyntaxIntoCleanUnicodeAnd2dAst() {
        val raw = "\\frac{1}{2} * m * v^2 + \\Delta U_0 = \\sqrt{k / m}"
        val formatted = MathFormulaRenderer.formatToCleanUnicode(raw)

        // Ensure raw LaTeX commands and caret '^' characters are stripped/converted
        assertFalse("Formatted formula must not contain raw \\frac", formatted.contains("\\frac"))
        assertFalse("Formatted formula must not contain raw caret ^", formatted.contains("^"))
        assertTrue("Formatted formula must contain Unicode superscript ²", formatted.contains("²"))
        assertTrue("Formatted formula must contain Greek Δ", formatted.contains("Δ"))
        assertTrue("Formatted formula must contain radical √", formatted.contains("√"))

        val ast = MathFormulaRenderer.parseToAst(raw)
        assertNotNull(ast)
    }

    @Test
    fun localCodeRenderEngine_validatesKatexMermaidChartsSchematicsAndFallbacks() {
        // 1. Validate unbalanced/programming formula normalization to valid KaTeX
        val malformedFormula = MathFormula(
            name = "Unbalanced Kinetic Energy",
            rawExpression = "K = \\frac{1}{2 * m * v^2 + sqrt(k_0"
        )
        val validatedFormula = LocalCodeRenderEngine.validateAndNormalizeFormula(malformedFormula)
        val openBraces = validatedFormula.katexExpression.count { it == '{' }
        val closeBraces = validatedFormula.katexExpression.count { it == '}' }
        assertEquals("Curly braces must be balanced for KaTeX", openBraces, closeBraces)
        assertTrue(validatedFormula.katexExpression.contains("\\sqrt{"))

        // 2. Validate Mermaid.js fallback generation when DSL is missing or malformed
        val diagramWithoutDsl = StructuredDiagram(
            title = "Thermodynamic Cycle",
            mermaidDsl = "invalid syntax without header"
        )
        val validatedDiagram = LocalCodeRenderEngine.validateAndNormalizeDiagram(diagramWithoutDsl)
        assertTrue(
            "Fallback Mermaid DSL must be valid flowchart syntax",
            LocalCodeRenderEngine.isValidMermaidSyntax(validatedDiagram.mermaidDsl)
        )
        assertTrue("Fallback nodes must be populated", validatedDiagram.nodes.isNotEmpty())
        assertTrue("Fallback edges must be populated", validatedDiagram.edges.isNotEmpty())
    }

    @Test
    fun videoInspectorAndPdfGenerator_endToEndActualEducationalLectures_createsMultiPagePdfAndHtmlWithZeroRenderTokens() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = UsagePolicyConfig()
        val inspector = VideoSourceInspector(policy)
        val synthesizer = GeminiStudySynthesizer(policy, inspector)
        val pdfGenerator = StudyPdfGenerator()

        // Test across multiple actual OpenCourseWare educational lectures:
        // 1. Linear Algebra (ZK3O402wf1c)
        // 2. Classical Mechanics & Conservation of Energy (w4QFJb9a8vo)
        // 3. Algorithm Complexity & Dynamic Programming (8hly31xKli0)
        val lectureUrls = listOf(
            "https://www.youtube.com/watch?v=ZK3O402wf1c",
            "https://www.youtube.com/watch?v=w4QFJb9a8vo",
            "https://www.youtube.com/watch?v=8hly31xKli0"
        )

        for (url in lectureUrls) {
            val inspection = inspector.inspectYouTubeUrl(url)
            assertTrue("Expected valid YouTube inspection for $url", inspection.isValid)
            assertTrue("Expected extracted transcript for $url", !inspection.extractedTranscript.isNullOrBlank())
            assertTrue("Expected multi-section estimation for $url", inspection.estimatedSectionsCount >= 2)

            val stagesObserved = mutableListOf<String>()
            val studyDocument = synthesizer.synthesizeStudyDocument(
                context = context,
                inspection = inspection,
                detailLevel = StudyDetailLevel.DETAILED_STUDY_NOTES,
                supplementaryNotes = "",
                onStageProgress = { stage, _, _ -> stagesObserved.add(stage) }
            ).getOrThrow()

            assertTrue("Expected multiple sections for $url", studyDocument.sections.size >= 2)
            assertTrue("Expected extracted KaTeX formulas for $url", studyDocument.formulas.isNotEmpty())
            assertTrue(
                "All formulas must have validated KaTeX expressions",
                studyDocument.formulas.all { it.katexExpression.isNotBlank() }
            )
            assertTrue("Expected Mermaid.js diagrams for $url", studyDocument.diagrams.isNotEmpty())
            assertTrue(
                "All diagrams must have valid Mermaid.js syntax",
                studyDocument.diagrams.all { LocalCodeRenderEngine.isValidMermaidSyntax(it.mermaidDsl) }
            )
            assertTrue("Expected local quantitative charts for $url", studyDocument.charts.isNotEmpty())
            assertTrue("Expected local SVG technical schematics for $url", studyDocument.schematics.isNotEmpty())
            assertTrue("Expected comparison tables for $url", studyDocument.comparisonTables.isNotEmpty())
            assertTrue("Expected revision questions for $url", studyDocument.revisionQuestions.isNotEmpty())
            assertTrue("Expected technical glossary for $url", studyDocument.glossary.isNotEmpty())
            assertTrue(
                "Token telemetry must record avoided AI rendering calls",
                studyDocument.tokenTelemetry.totalAiCallsAvoidedByLocalRendering >= 5
            )

            // Render native multi-page PDF and standalone HTML5/CSS3 + Mermaid + KaTeX + Chart.js + SVG sheet
            val pdfResult = pdfGenerator.generateStudyPdf(
                context = context,
                document = studyDocument,
                fileNamePrefix = "Test_${inspection.videoIdOrFileName}_StudyPDF"
            ).getOrThrow()

            assertTrue("Generated PDF file must exist on disk", pdfResult.pdfFile.exists())
            assertTrue("Generated PDF file must be non-empty", pdfResult.fileSizeBytes > 1000L)
            assertTrue("Detailed notes must span multiple pages", pdfResult.pageCount >= 2)

            assertTrue("Generated HTML study sheet must exist", pdfResult.htmlFile.exists())
            val htmlText = pdfResult.htmlFile.readText()
            assertTrue("HTML must include KaTeX rendering block", htmlText.contains("katex-equation"))
            assertTrue("HTML must include Mermaid.js flowchart block", htmlText.contains("class=\"mermaid\""))
            assertTrue("HTML must include inline SVG chart/schematic", htmlText.contains("<svg"))
            assertTrue("HTML must include CSS study table", htmlText.contains("class=\"study-table\""))
        }

        // Reject invalid URLs with clear error message
        val badInspection = inspector.inspectYouTubeUrl("https://example.com/not-a-youtube-link")
        assertFalse(badInspection.isValid)
        assertNotNull(badInspection.errorMessage)
    }

    @Test
    fun serverCreditCoordinator_enforcesWelcomeBonusDailyResetRefundsRewardedAdsAndSubscriptions() = runBlocking {
        val policy = UsagePolicyConfig(
            initialBonusCredits = 3,
            dailyFreeAllowance = 1,
            maxRewardedAdsPerDay = 3,
            proSubscriptionDailyAllowance = 15
        )
        val coordinator = ServerCreditAndEntitlementCoordinator(policy = policy)
        val userId = "test_scholar_uid_01"

        // 1. New user starts with 3 bonus + 1 daily free = 4 total credits
        val initialProfile = coordinator.syncOrInitializeLedger(
            userId = userId,
            email = "scholar@mit.edu",
            displayName = "Scholar"
        )
        assertEquals(3L, initialProfile.bonusCreditsRemaining)
        assertEquals(1L, initialProfile.dailyFreeCredits)
        assertEquals(4, initialProfile.totalAvailableCredits)

        // 2. Reserve 1 credit for an accepted job -> consumes dailyFreeCredits first (3 bonus + 0 daily = 3 left)
        val reserve1 = coordinator.reserveCreditForAcceptedJob(userId, "job_001", 1200L).getOrThrow()
        assertTrue(reserve1.accepted)
        assertEquals("DAILY_FREE_CREDIT", reserve1.creditSourceUsed)
        assertEquals(3, coordinator.getAuthoritativeProfile(userId).totalAvailableCredits)

        // 3. Qualifying failed/cancelled job automatically refunds the reserved credit once (idempotent)
        val refund1 = coordinator.refundCreditForQualifyingFailedJob(userId, "job_001").getOrThrow()
        assertTrue(refund1.accepted)
        assertEquals(4, coordinator.getAuthoritativeProfile(userId).totalAvailableCredits)
        val duplicateRefund = coordinator.refundCreditForQualifyingFailedJob(userId, "job_001")
        assertTrue("Duplicate refund for same jobId must be rejected", duplicateRefund.isFailure)

        // 4. Rewarded Ad SSV verification: early dismissal grants 0 credits; verified completion grants +1 credit
        val nonceDismissed = coordinator.issueRewardedAdSessionNonce(userId).getOrThrow()
        val dismissedResult = coordinator.verifyAndGrantRewardedAdCredit(
            userId = userId,
            rewardRequestId = nonceDismissed,
            adUnitId = "ca-app-pub-3940256099942544/5224354917",
            sdkRewardEarned = false
        )
        assertTrue("Dismissed ad must not grant credit", dismissedResult.isFailure)
        assertEquals(4, coordinator.getAuthoritativeProfile(userId).totalAvailableCredits)

        // Grant 3 verified rewarded ads (reaching the 3/day cap)
        repeat(3) { idx ->
            val nonce = coordinator.issueRewardedAdSessionNonce(userId).getOrThrow()
            val adGrant = coordinator.verifyAndGrantRewardedAdCredit(
                userId = userId,
                rewardRequestId = nonce,
                adUnitId = "ca-app-pub-3940256099942544/5224354917",
                sdkRewardEarned = true
            ).getOrThrow()
            assertTrue(adGrant.accepted)
            assertEquals(idx + 1, adGrant.updatedRewardedAdCredits)
        }
        assertEquals(7, coordinator.getAuthoritativeProfile(userId).totalAvailableCredits)

        // 4th ad on the same UTC day must be blocked by daily cap
        val fourthNonceAttempt = coordinator.issueRewardedAdSessionNonce(userId)
        assertTrue("4th rewarded ad on same day must be rejected by daily limit", fourthNonceAttempt.isFailure)

        // 5. Play Billing Subscription Lifecycle verification
        // 5a. Pending purchase does NOT unlock Pro tier
        val (pendingSubProfile, _) = coordinator.verifyAndProcessSubscriptionState(
            userId = userId,
            productId = "pro_student_monthly_test",
            purchaseToken = "tok_pending_1",
            lifecycleState = PurchaseLifecycleState.PENDING
        ).getOrThrow()
        assertEquals(SubscriptionTier.FREE.id, pendingSubProfile.subscriptionTier)

        // 5b. Active Verified purchase unlocks Pro Student tier (+15 daily allowance & 180-min video duration)
        val (activeSubProfile, _) = coordinator.verifyAndProcessSubscriptionState(
            userId = userId,
            productId = "pro_student_monthly_test",
            purchaseToken = "tok_active_1",
            lifecycleState = PurchaseLifecycleState.ACTIVE_VERIFIED
        ).getOrThrow()
        assertEquals(SubscriptionTier.PRO_STUDENT.id, activeSubProfile.subscriptionTier)
        assertEquals(15L, activeSubProfile.subscriptionAllowanceRemaining)

        // Verify a 120-minute (7200s) lecture is now eligible under Pro Student tier
        val proEligibility = coordinator.evaluateEligibility(userId, 7200L)
        assertTrue("Pro Student tier must allow 120-minute lecture", proEligibility.canStartGeneration)

        // 5c. Refunded/Revoked purchase immediately revokes Pro tier
        val (revokedSubProfile, _) = coordinator.verifyAndProcessSubscriptionState(
            userId = userId,
            productId = "pro_student_monthly_test",
            purchaseToken = "tok_revoked_1",
            lifecycleState = PurchaseLifecycleState.REFUNDED_REVOKED
        ).getOrThrow()
        assertEquals(SubscriptionTier.FREE.id, revokedSubProfile.subscriptionTier)
        assertEquals(0L, revokedSubProfile.subscriptionAllowanceRemaining)
    }

    @Test
    fun composeUi_rendersStudioAndNavigatesAcrossCreditsUpgradeAndLibrary() {
        val testUser = AuthenticatedSessionUser(
            uid = "robolectric_student",
            email = "student@stanford.edu",
            displayName = "Test Student",
            isFirebaseBacked = false
        )
        composeTestRule.setContent {
            MyApplicationTheme {
                VideoToStudyPdfRootApp(initialSessionUserForTest = testUser)
            }
        }

        // Verify Home Study Studio screen renders
        composeTestRule.onNodeWithTag("home_credit_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("youtube_url_input").assertIsDisplayed()

        // Navigate to Pro & Ads (UpgradeMonetizationScreen)
        composeTestRule.onNodeWithTag("nav_tab_upgrade").performClick()
        composeTestRule.onNodeWithTag("monetization_mode_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("rewarded_ad_card").assertIsDisplayed()

        // Trigger Verified Test Mode Ad Reward (+1 Credit) and verify UI updates
        composeTestRule.onNodeWithTag("test_verify_ad_reward_button").performScrollTo().performClick()
        composeTestRule.onNodeWithText("5 Cr").assertIsDisplayed()

        // Scroll to Play Billing subscription card and verify it is displayed
        composeTestRule.onNodeWithTag("play_billing_subscription_card").performScrollTo().assertIsDisplayed()

        // Navigate to Credits ledger breakdown screen
        composeTestRule.onNodeWithTag("nav_tab_credits").performClick()
        composeTestRule.onNodeWithTag("credit_ledger_summary_card").assertIsDisplayed()
    }
}
