package com.example.ui.viewmodel

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.R
import com.example.data.backend.CreditEligibilityEvaluation
import com.example.data.backend.ServerCreditAndEntitlementCoordinator
import com.example.data.local.CachedStudyDocumentDao
import com.example.data.local.CachedStudyDocumentEntity
import com.example.data.local.StudyLocalDatabase
import com.example.data.model.JobStatus
import com.example.data.model.PurchaseLifecycleState
import com.example.data.model.StudyDetailLevel
import com.example.data.model.StudyDocument
import com.example.data.model.StudyJob
import com.example.data.model.UsagePolicyConfig
import com.example.data.model.UserAccountProfile
import com.example.data.model.VideoInspectionResult
import com.example.data.monetization.BillingSubscriptionManager
import com.example.data.monetization.RewardedAdManager
import com.example.data.monetization.RewardedAdUiState
import com.example.data.monetization.SubscriptionUiStatus
import com.example.data.repository.StudyFirestoreRepository
import com.example.domain.pdf.StudyPdfGenerator
import com.example.domain.pipeline.GeminiStudySynthesizer
import com.example.domain.pipeline.VideoSourceInspector
import com.example.ui.auth.AuthenticatedSessionUser
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class AppScreenDestination(val route: String, val label: String) {
    HOME("home", "Study Studio"),
    PROCESSING("processing", "Processing Status"),
    PDF_PREVIEW("pdf_preview", "PDF Preview"),
    LIBRARY("library", "PDF Library"),
    CREDITS("credits", "Usage & Credits"),
    UPGRADE("upgrade", "Upgrade & Ads"),
    SETTINGS("settings", "Settings & Help")
}

data class StudyStudioUiState(
    val currentUser: AuthenticatedSessionUser,
    val currentScreen: AppScreenDestination = AppScreenDestination.HOME,
    val profile: UserAccountProfile = UserAccountProfile(),
    val policy: UsagePolicyConfig = UsagePolicyConfig(),
    val youtubeUrlInput: String = "",
    val supplementaryNotesInput: String = "",
    val selectedDetailLevel: StudyDetailLevel = StudyDetailLevel.STANDARD_NOTES,
    val isInspectingVideo: Boolean = false,
    val currentInspection: VideoInspectionResult? = null,
    val eligibilityEvaluation: CreditEligibilityEvaluation? = null,
    val activeJob: StudyJob? = null,
    val activeStudyDocument: StudyDocument? = null,
    val activePdfFile: File? = null,
    val activePdfPageCount: Int = 0,
    val recentJobs: List<StudyJob> = emptyList(),
    val snackbarMessage: String? = null,
    val lastActionStatusBanner: String? = null
)

class StudyAppViewModel(
    private val appContext: Context,
    private val sessionUser: AuthenticatedSessionUser,
    private val firestoreRepository: StudyFirestoreRepository,
    private val localDao: CachedStudyDocumentDao,
    val creditCoordinator: ServerCreditAndEntitlementCoordinator = ServerCreditAndEntitlementCoordinator(),
    val videoInspector: VideoSourceInspector = VideoSourceInspector(creditCoordinator.policy),
    private val synthesizer: GeminiStudySynthesizer = GeminiStudySynthesizer(creditCoordinator.policy, videoInspector),
    val pdfGenerator: StudyPdfGenerator = StudyPdfGenerator()
) : ViewModel() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val rewardedAdManager = RewardedAdManager(appContext, creditCoordinator)
    val billingManager = BillingSubscriptionManager(
        context = appContext,
        creditCoordinator = creditCoordinator,
        scope = viewModelScope,
        onProfileUpdated = { updatedProfile ->
            _uiState.value = _uiState.value.copy(profile = updatedProfile)
            refreshEligibilityCheck()
        }
    )

    private val _uiState = MutableStateFlow(
        StudyStudioUiState(
            currentUser = sessionUser,
            profile = UserAccountProfile(
                id = sessionUser.uid,
                userId = sessionUser.uid,
                email = sessionUser.email,
                displayName = sessionUser.displayName,
                bonusCreditsRemaining = 3L,
                dailyFreeCredits = 1L
            )
        )
    )
    val uiState: StateFlow<StudyStudioUiState> = _uiState.asStateFlow()

    val cachedDocuments: StateFlow<List<CachedStudyDocumentEntity>> =
        localDao.observeUserDocuments(sessionUser.uid)
            .catch { e ->
                Log.w(TAG, "Error observing local cached documents", e)
                emit(emptyList())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList()
            )

    val rewardedAdState: StateFlow<RewardedAdUiState> = rewardedAdManager.adState
    val subscriptionStatus: StateFlow<SubscriptionUiStatus> = billingManager.uiStatus

    private var runningJobCoroutine: Job? = null

    init {
        initializeUserAndListeners()
    }

    private fun initializeUserAndListeners() {
        billingManager.bindUserSession(sessionUser.uid)
        viewModelScope.launch {
            var firestoreProfile: UserAccountProfile? = null
            if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
                val initRes = firestoreRepository.initializeUserProfileIfNeeded(
                    email = sessionUser.email,
                    displayName = sessionUser.displayName
                )
                firestoreProfile = initRes.getOrNull()
            }
            val authoritative = creditCoordinator.syncOrInitializeLedger(
                userId = sessionUser.uid,
                email = sessionUser.email,
                displayName = sessionUser.displayName,
                firestoreProfile = firestoreProfile
            )
            _uiState.value = _uiState.value.copy(
                profile = authoritative,
                selectedDetailLevel = StudyDetailLevel.fromId(authoritative.preferredDetailLevel)
            )
            refreshEligibilityCheck()
        }

        if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
            viewModelScope.launch {
                firestoreRepository.observeUserStudyJobs(sessionUser.uid)
                    .catch { e ->
                        Log.w(TAG, "Firestore jobs listener warning: ${e.message}")
                    }
                    .collect { cloudJobs ->
                        if (cloudJobs.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(recentJobs = cloudJobs)
                        }
                    }
            }
        }
    }

    private fun isFirebaseSignedIn(): Boolean {
        return try {
            FirebaseAuth.getInstance().currentUser?.uid == sessionUser.uid
        } catch (_: Exception) {
            false
        }
    }

    fun navigateTo(destination: AppScreenDestination) {
        _uiState.value = _uiState.value.copy(currentScreen = destination)
    }

    fun updateYoutubeUrl(url: String) {
        _uiState.value = _uiState.value.copy(youtubeUrlInput = url)
    }

    fun updateSupplementaryNotes(notes: String) {
        _uiState.value = _uiState.value.copy(supplementaryNotesInput = notes)
    }

    fun selectDetailLevel(level: StudyDetailLevel) {
        _uiState.value = _uiState.value.copy(selectedDetailLevel = level)
        if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
            viewModelScope.launch {
                firestoreRepository.updateUserPreferences(
                    displayName = _uiState.value.profile.displayName,
                    preferredDetailLevel = level
                )
            }
        }
    }

    fun loadVerifiedLecturePreset(videoId: String) {
        val preset = VideoSourceInspector.BUILT_IN_OPEN_COURSEWARE_LECTURES[videoId] ?: return
        _uiState.value = _uiState.value.copy(
            youtubeUrlInput = preset.url,
            supplementaryNotesInput = preset.transcript
        )
        inspectCurrentYoutubeUrl()
    }

    fun inspectCurrentYoutubeUrl() {
        val url = _uiState.value.youtubeUrlInput
        val notes = _uiState.value.supplementaryNotesInput
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInspectingVideo = true, snackbarMessage = null)
            val result = videoInspector.inspectYouTubeUrl(url, notes)
            val eligibility = if (result.isValid) {
                creditCoordinator.evaluateEligibility(sessionUser.uid, result.durationSeconds)
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(
                isInspectingVideo = false,
                currentInspection = result,
                eligibilityEvaluation = eligibility
            )
        }
    }

    fun inspectSelectedLocalVideo(uri: Uri) {
        val notes = _uiState.value.supplementaryNotesInput
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isInspectingVideo = true, snackbarMessage = null)
            val result = videoInspector.inspectLocalVideoUri(appContext, uri, notes)
            val eligibility = if (result.isValid) {
                creditCoordinator.evaluateEligibility(sessionUser.uid, result.durationSeconds)
            } else {
                null
            }
            _uiState.value = _uiState.value.copy(
                isInspectingVideo = false,
                currentInspection = result,
                eligibilityEvaluation = eligibility
            )
        }
    }

    private fun refreshEligibilityCheck() {
        val inspection = _uiState.value.currentInspection ?: return
        if (!inspection.isValid) return
        viewModelScope.launch {
            val eval = creditCoordinator.evaluateEligibility(sessionUser.uid, inspection.durationSeconds)
            val profile = creditCoordinator.getAuthoritativeProfile(sessionUser.uid)
            _uiState.value = _uiState.value.copy(
                profile = profile,
                eligibilityEvaluation = eval
            )
        }
    }

    /**
     * Starts the end-to-end Video-to-Study-PDF processing job:
     * 1. Checks cached result by contentHash when policy.enableResultCacheByContentHash is enabled.
     * 2. Reserves 1 credit atomically on the trusted backend coordinator.
     * 3. Creates and updates the StudyJob in Firestore & local state across genuine stages.
     * 4. Synthesizes the multi-section StudyDocument and renders the native multi-page vector PDF.
     * 5. Automatically refunds the reserved credit if any stage fails or is cancelled.
     */
    fun startVideoToPdfProcessing(forceBypassCache: Boolean = false) {
        val state = _uiState.value
        val inspection = state.currentInspection
        if (inspection == null || !inspection.isValid) {
            _uiState.value = state.copy(
                snackbarMessage = "Please validate a YouTube URL or select a valid local video file first."
            )
            return
        }
        if (inspection.requiresSupplementaryNotes && state.supplementaryNotesInput.isBlank()) {
            _uiState.value = state.copy(
                snackbarMessage = "Public captions are unavailable for this video. Please paste lecture notes/transcript or choose a verified lecture preset."
            )
            return
        }
        if (runningJobCoroutine?.isActive == true) {
            _uiState.value = state.copy(
                snackbarMessage = "A video-to-PDF job is already running. Please wait or cancel the current job."
            )
            return
        }

        val detailLevel = state.selectedDetailLevel
        val contentHash = videoInspector.computeContentHash(
            normalizedUri = inspection.normalizedUri,
            detailLevel = detailLevel,
            transcriptOrNotes = state.supplementaryNotesInput.ifBlank { inspection.extractedTranscript.orEmpty() }
        )

        runningJobCoroutine = viewModelScope.launch {
            // Check cache reuse first (Cost Control requirement 8)
            if (state.policy.enableResultCacheByContentHash && !forceBypassCache) {
                val cached = localDao.findByContentHash(contentHash, sessionUser.uid)
                if (cached != null) {
                    val cachedPdf = File(cached.localPdfPath)
                    if (cachedPdf.exists()) {
                        try {
                            val studyDoc = json.decodeFromString<StudyDocument>(cached.studyDocumentJson)
                            val cachedJob = StudyJob(
                                id = cached.jobId,
                                userId = sessionUser.uid,
                                sourceType = cached.sourceType,
                                sourceUri = cached.sourceUri,
                                videoTitle = cached.title,
                                videoDurationSeconds = cached.videoDurationSeconds,
                                detailLevel = cached.detailLevel,
                                status = JobStatus.COMPLETED.id,
                                progressPercent = 100L,
                                currentStageDetail = "Loaded verified study PDF from local content-hash cache (0 credits deducted)",
                                creditDeducted = false,
                                creditRefunded = false,
                                localPdfPath = cachedPdf.absolutePath,
                                contentHash = contentHash,
                                createdAt = Timestamp.now()
                            )
                            _uiState.value = _uiState.value.copy(
                                activeJob = cachedJob,
                                activeStudyDocument = studyDoc,
                                activePdfFile = cachedPdf,
                                activePdfPageCount = cached.pageCount,
                                currentScreen = AppScreenDestination.PDF_PREVIEW,
                                lastActionStatusBanner = "Reused cached analysis for identical video & detail level (0 credits spent)."
                            )
                            return@launch
                        } catch (_: Exception) {
                            // Proceed with fresh generation if cached JSON could not be decoded
                        }
                    }
                }
            }

            val jobId = "job_${UUID.randomUUID().toString().replace("-", "").take(18)}"
            val reservationResult = creditCoordinator.reserveCreditForAcceptedJob(
                userId = sessionUser.uid,
                jobId = jobId,
                videoDurationSeconds = inspection.durationSeconds
            )
            if (reservationResult.isFailure) {
                val err = reservationResult.exceptionOrNull()?.message ?: "Cannot start generation."
                refreshEligibilityCheck()
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = err,
                    lastActionStatusBanner = err
                )
                return@launch
            }

            val updatedProfileAfterReserve = creditCoordinator.getAuthoritativeProfile(sessionUser.uid)
            val initialJob = StudyJob(
                id = jobId,
                userId = sessionUser.uid,
                sourceType = inspection.sourceType.id,
                sourceUri = inspection.normalizedUri,
                videoTitle = inspection.title,
                videoDurationSeconds = inspection.durationSeconds,
                detailLevel = detailLevel.id,
                status = JobStatus.VALIDATING.id,
                progressPercent = 5L,
                currentStageDetail = "Credit reserved (${reservationResult.getOrNull()?.creditSourceUsed}). Initializing pipeline...",
                creditDeducted = true,
                creditRefunded = false,
                contentHash = contentHash,
                createdAt = Timestamp.now()
            )

            _uiState.value = _uiState.value.copy(
                profile = updatedProfileAfterReserve,
                activeJob = initialJob,
                recentJobs = listOf(initialJob) + _uiState.value.recentJobs.filterNot { it.id == jobId },
                currentScreen = AppScreenDestination.PROCESSING,
                lastActionStatusBanner = reservationResult.getOrNull()?.message
            )

            if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
                firestoreRepository.createStudyJob(
                    jobId = jobId,
                    sourceType = inspection.sourceType.id,
                    sourceUri = inspection.normalizedUri,
                    videoTitle = inspection.title,
                    videoDurationSeconds = inspection.durationSeconds,
                    detailLevel = detailLevel.id,
                    contentHash = contentHash,
                    creditDeducted = true
                )
                firestoreRepository.recordMonetizationEvent(
                    eventType = "CREDIT_JOB_RESERVATION",
                    idempotencyKey = "res_$jobId",
                    referenceId = jobId,
                    verificationPayload = "deducted_1_credit_for_$jobId"
                )
            }

            try {
                val synthesisResult = synthesizer.synthesizeStudyDocument(
                    context = appContext,
                    inspection = inspection,
                    detailLevel = detailLevel,
                    supplementaryNotes = state.supplementaryNotesInput,
                    onStageProgress = { stageStatus, pct, detail ->
                        updateJobStateInternal(jobId, stageStatus, pct.toLong(), detail)
                    }
                )

                val studyDocument = synthesisResult.getOrThrow()
                updateJobStateInternal(
                    jobId = jobId,
                    status = JobStatus.RENDERING_PDF.id,
                    progressPercent = 88L,
                    detail = "Rendering native vector PDF (formulas, diagrams, tables, pagination)..."
                )

                val pdfResult = pdfGenerator.generateStudyPdf(
                    context = appContext,
                    document = studyDocument,
                    fileNamePrefix = "StudyPDF_${inspection.videoIdOrFileName.take(20)}_${detailLevel.id}"
                ).getOrThrow()

                val entity = CachedStudyDocumentEntity(
                    contentHash = contentHash,
                    jobId = jobId,
                    userId = sessionUser.uid,
                    title = studyDocument.title,
                    sourceType = inspection.sourceType.id,
                    sourceUri = inspection.normalizedUri,
                    detailLevel = detailLevel.id,
                    videoDurationSeconds = inspection.durationSeconds,
                    studyDocumentJson = json.encodeToString(studyDocument),
                    localPdfPath = pdfResult.pdfFile.absolutePath,
                    pageCount = pdfResult.pageCount
                )
                localDao.upsertDocument(entity)

                val completedJob = _uiState.value.activeJob?.copy(
                    status = JobStatus.COMPLETED.id,
                    progressPercent = 100L,
                    currentStageDetail = "Study PDF generated (${pdfResult.pageCount} pages, ${pdfResult.fileSizeBytes / 1024} KB).",
                    localPdfPath = pdfResult.pdfFile.absolutePath,
                    updatedAt = Timestamp.now()
                )
                if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
                    firestoreRepository.updateJobProgress(
                        jobId = jobId,
                        status = JobStatus.COMPLETED.id,
                        progressPercent = 100L,
                        currentStageDetail = "Study PDF generated (${pdfResult.pageCount} pages).",
                        localPdfPath = pdfResult.pdfFile.absolutePath
                    )
                }

                _uiState.value = _uiState.value.copy(
                    activeJob = completedJob,
                    activeStudyDocument = studyDocument,
                    activePdfFile = pdfResult.pdfFile,
                    activePdfPageCount = pdfResult.pageCount,
                    recentJobs = listOfNotNull(completedJob) + _uiState.value.recentJobs.filterNot { it.id == jobId },
                    currentScreen = AppScreenDestination.PDF_PREVIEW,
                    lastActionStatusBanner = "Study PDF ready (${pdfResult.pageCount} pages)."
                )
                refreshEligibilityCheck()
            } catch (cancelled: CancellationException) {
                handleJobFailureOrCancellation(
                    jobId = jobId,
                    targetStatus = JobStatus.CANCELLED,
                    reason = "Job cancelled by user. Reserved credit automatically refunded."
                )
                throw cancelled
            } catch (error: Exception) {
                handleJobFailureOrCancellation(
                    jobId = jobId,
                    targetStatus = JobStatus.FAILED,
                    reason = "Processing error: ${error.localizedMessage ?: "Failed to generate PDF"}. Credit automatically refunded."
                )
            }
        }
    }

    private suspend fun updateJobStateInternal(
        jobId: String,
        status: String,
        progressPercent: Long,
        detail: String
    ) {
        val currentActive = _uiState.value.activeJob
        if (currentActive != null && currentActive.id == jobId) {
            val updated = currentActive.copy(
                status = status,
                progressPercent = progressPercent,
                currentStageDetail = detail,
                updatedAt = Timestamp.now()
            )
            _uiState.value = _uiState.value.copy(
                activeJob = updated,
                recentJobs = listOf(updated) + _uiState.value.recentJobs.filterNot { it.id == jobId }
            )
        }
        if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
            firestoreRepository.updateJobProgress(
                jobId = jobId,
                status = status,
                progressPercent = progressPercent,
                currentStageDetail = detail
            )
        }
    }

    private suspend fun handleJobFailureOrCancellation(
        jobId: String,
        targetStatus: JobStatus,
        reason: String
    ) {
        val refundRes = creditCoordinator.refundCreditForQualifyingFailedJob(sessionUser.uid, jobId)
        val updatedProfile = creditCoordinator.getAuthoritativeProfile(sessionUser.uid)
        val updatedJob = _uiState.value.activeJob?.copy(
            status = targetStatus.id,
            currentStageDetail = reason,
            creditRefunded = refundRes.isSuccess,
            errorMessage = reason,
            updatedAt = Timestamp.now()
        )
        if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
            firestoreRepository.updateJobProgress(
                jobId = jobId,
                status = targetStatus.id,
                progressPercent = updatedJob?.progressPercent ?: 0L,
                currentStageDetail = reason,
                errorMessage = reason
            )
            if (refundRes.isSuccess) {
                firestoreRepository.recordMonetizationEvent(
                    eventType = "CREDIT_JOB_REFUND",
                    idempotencyKey = "ref_$jobId",
                    referenceId = jobId,
                    verificationPayload = "refunded_1_credit_for_$jobId"
                )
            }
        }
        _uiState.value = _uiState.value.copy(
            profile = updatedProfile,
            activeJob = updatedJob,
            recentJobs = listOfNotNull(updatedJob) + _uiState.value.recentJobs.filterNot { it.id == jobId },
            snackbarMessage = reason,
            lastActionStatusBanner = reason
        )
        refreshEligibilityCheck()
    }

    fun cancelActiveProcessingJob() {
        runningJobCoroutine?.cancel()
        runningJobCoroutine = null
    }

    fun openCachedDocument(entity: CachedStudyDocumentEntity) {
        viewModelScope.launch {
            try {
                val doc = json.decodeFromString<StudyDocument>(entity.studyDocumentJson)
                var pdfFile = File(entity.localPdfPath)
                var pages = entity.pageCount
                if (!pdfFile.exists()) {
                    val regen = pdfGenerator.generateStudyPdf(appContext, doc, "StudyPDF_${entity.jobId}").getOrThrow()
                    pdfFile = regen.pdfFile
                    pages = regen.pageCount
                }
                _uiState.value = _uiState.value.copy(
                    activeStudyDocument = doc,
                    activePdfFile = pdfFile,
                    activePdfPageCount = pages,
                    currentScreen = AppScreenDestination.PDF_PREVIEW
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    snackbarMessage = "Could not open PDF: ${e.localizedMessage}"
                )
            }
        }
    }

    fun downloadActivePdfToDevice() {
        val pdfFile = _uiState.value.activePdfFile
        val doc = _uiState.value.activeStudyDocument
        if (pdfFile == null || !pdfFile.exists() || doc == null) {
            _uiState.value = _uiState.value.copy(snackbarMessage = "No generated PDF file available to download.")
            return
        }
        viewModelScope.launch {
            val res = pdfGenerator.savePdfToPublicDownloads(appContext, pdfFile, doc.title)
            val msg = res.getOrElse { "Download failed: ${it.localizedMessage}" }
            _uiState.value = _uiState.value.copy(
                snackbarMessage = msg,
                lastActionStatusBanner = msg
            )
        }
    }

    fun shareActivePdf(context: Context) {
        val pdfFile = _uiState.value.activePdfFile
        val doc = _uiState.value.activeStudyDocument
        if (pdfFile == null || !pdfFile.exists() || doc == null) {
            _uiState.value = _uiState.value.copy(snackbarMessage = "No generated PDF file available to share.")
            return
        }
        try {
            val chooser = pdfGenerator.createSharePdfIntent(context, pdfFile, doc.title)
            context.startActivity(chooser)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "Unable to launch Share sheet: ${e.localizedMessage}"
            )
        }
    }

    // --- Rewarded Ads & Play Billing Actions ---

    fun loadRewardedAd() {
        rewardedAdManager.loadRewardedAd(sessionUser.uid, viewModelScope)
    }

    fun showRewardedAd(activity: Activity) {
        rewardedAdManager.showLoadedRewardedAd(
            activity = activity,
            userId = sessionUser.uid,
            scope = viewModelScope,
            onCreditGranted = { response ->
                viewModelScope.launch {
                    val updatedProfile = creditCoordinator.getAuthoritativeProfile(sessionUser.uid)
                    _uiState.value = _uiState.value.copy(
                        profile = updatedProfile,
                        snackbarMessage = response.message,
                        lastActionStatusBanner = response.message
                    )
                    refreshEligibilityCheck()
                    if (sessionUser.isFirebaseBacked && isFirebaseSignedIn()) {
                        firestoreRepository.recordMonetizationEvent(
                            eventType = "REWARDED_AD_SSV",
                            idempotencyKey = "ad_${System.currentTimeMillis()}",
                            referenceId = rewardedAdManager.resolvedAdUnitId,
                            verificationPayload = response.message
                        )
                    }
                }
            }
        )
    }

    fun triggerVerifiedTestModeRewardedAd(simulateEarlyDismissal: Boolean = false) {
        rewardedAdManager.triggerVerifiedTestModeAdCallback(
            userId = sessionUser.uid,
            simulateEarlyDismissal = simulateEarlyDismissal,
            scope = viewModelScope,
            onCreditGranted = { response ->
                viewModelScope.launch {
                    val updatedProfile = creditCoordinator.getAuthoritativeProfile(sessionUser.uid)
                    _uiState.value = _uiState.value.copy(
                        profile = updatedProfile,
                        snackbarMessage = response.message,
                        lastActionStatusBanner = response.message
                    )
                    refreshEligibilityCheck()
                }
            }
        )
    }

    fun launchPlayBillingSubscription(activity: Activity) {
        billingManager.launchSubscriptionFlow(activity, sessionUser.uid)
    }

    fun restorePlayBillingPurchases() {
        billingManager.restoreAndReconcilePurchases(sessionUser.uid)
    }

    fun simulateTestModeSubscriptionLifecycle(state: PurchaseLifecycleState) {
        billingManager.triggerTestModeSubscriptionState(sessionUser.uid, state)
    }

    fun clearLocalPdfCache() {
        viewModelScope.launch {
            localDao.clearAllForUser(sessionUser.uid)
            _uiState.value = _uiState.value.copy(
                snackbarMessage = "Cleared local cached study PDFs."
            )
        }
    }

    fun clearSnackbar() {
        _uiState.value = _uiState.value.copy(snackbarMessage = null)
    }

    companion object {
        private const val TAG = "StudyAppVM"

        fun provideFactory(
            context: Context,
            sessionUser: AuthenticatedSessionUser
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val appContext = context.applicationContext
                com.example.ui.auth.AuthManager.ensureFirebaseInitialized(appContext)
                val dbId = appContext.getString(R.string.firestore_database_id)
                val firestore = FirebaseFirestore.getInstance(dbId)
                val firestoreRepo = StudyFirestoreRepository(firestore)
                val localDb = StudyLocalDatabase.getInstance(appContext)
                StudyAppViewModel(
                    appContext = appContext,
                    sessionUser = sessionUser,
                    firestoreRepository = firestoreRepo,
                    localDao = localDb.cachedStudyDocumentDao()
                )
            }
        }
    }
}
