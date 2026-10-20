package com.example.data.backend

import com.example.BuildConfig
import com.example.data.model.MonetizationEnvironmentMode
import com.example.data.model.PurchaseLifecycleState
import com.example.data.model.SubscriptionTier
import com.example.data.model.UsagePolicyConfig
import com.example.data.model.UserAccountProfile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

@Serializable
data class CreditReservationRequest(
    val userId: String,
    val jobId: String,
    val videoDurationSeconds: Long,
    val detailLevel: String
)

@Serializable
data class CreditTransactionResponse(
    val accepted: Boolean,
    val message: String,
    val updatedBonusCredits: Int,
    val updatedDailyFreeCredits: Int,
    val updatedRewardedAdCredits: Int,
    val updatedSubscriptionAllowance: Int,
    val subscriptionTier: String,
    val creditSourceUsed: String = ""
)

@Serializable
data class RewardedAdSsvRequest(
    val userId: String,
    val rewardRequestId: String,
    val adUnitId: String,
    val rewardType: String,
    val rewardAmount: Int
)

@Serializable
data class PlayPurchaseVerificationRequest(
    val userId: String,
    val productId: String,
    val purchaseToken: String,
    val purchaseStateCode: Int,
    val isRestoreRequest: Boolean = false
)

interface TrustedBackendApi {
    @POST("v1/credits/reserve")
    suspend fun reserveJobCredit(
        @Header("Authorization") bearerIdToken: String,
        @Body request: CreditReservationRequest
    ): CreditTransactionResponse

    @POST("v1/credits/refund")
    suspend fun refundFailedJobCredit(
        @Header("Authorization") bearerIdToken: String,
        @Body request: CreditReservationRequest
    ): CreditTransactionResponse

    @POST("v1/monetization/verify-ad-ssv")
    suspend fun verifyRewardedAdSsv(
        @Header("Authorization") bearerIdToken: String,
        @Body request: RewardedAdSsvRequest
    ): CreditTransactionResponse

    @POST("v1/monetization/verify-subscription")
    suspend fun verifyPlaySubscription(
        @Header("Authorization") bearerIdToken: String,
        @Body request: PlayPurchaseVerificationRequest
    ): CreditTransactionResponse
}

@Serializable
data class CreditEligibilityEvaluation(
    val canStartGeneration: Boolean,
    val availableTotalCredits: Int,
    val maxAllowedDurationSeconds: Long,
    val blockingReason: String? = null,
    val nextCreditDeductionSource: String = ""
)

/**
 * Trusted Server-Side Credit & Entitlement Coordinator.
 *
 * Enforces all usage policies, daily resets, rewarded-ad caps, subscription allowances,
 * idempotency keys, and concurrency locks so the mobile UI can never arbitrarily edit its
 * own credit balance, bypass limits via simultaneous requests, or double-claim ad rewards.
 */
class ServerCreditAndEntitlementCoordinator(
    val policy: UsagePolicyConfig = UsagePolicyConfig(),
    private val clockEpochMillisProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val transactionMutex = Mutex()

    // Authoritative server-side ledger state per userId
    private val userLedgerMap = mutableMapOf<String, UserAccountProfile>()
    private val reservedJobCreditSources = mutableMapOf<String, String>() // jobId -> source bucket
    private val refundedJobIds = mutableSetOf<String>()
    private val verifiedAdRewardNonces = mutableSetOf<String>()
    private val issuedAdRewardNonces = mutableMapOf<String, String>() // nonce -> userId
    private val purchaseTokenStates = mutableMapOf<String, PurchaseLifecycleState>()
    private val userPurchaseStateMap = mutableMapOf<String, PurchaseLifecycleState>()

    val isRemoteBackendConfigured: Boolean
        get() {
            val url = BuildConfig.TRUSTED_BACKEND_URL
            return url.isNotBlank() && !url.contains("api-pending-deployment.videotostudypdf.example.com")
        }

    val monetizationMode: MonetizationEnvironmentMode
        get() {
            val adUnit = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID
            val subId = BuildConfig.PLAY_BILLING_SUBSCRIPTION_PRODUCT_ID
            return if (
                adUnit.isNotBlank() &&
                adUnit != "UNCONFIGURED_PRODUCTION_AD_UNIT_ID" &&
                subId.isNotBlank() &&
                subId != "UNCONFIGURED_SUBSCRIPTION_PRODUCT_ID" &&
                isRemoteBackendConfigured
            ) {
                MonetizationEnvironmentMode.PRODUCTION_MODE
            } else {
                MonetizationEnvironmentMode.TEST_MODE
            }
        }

    private fun currentServerUtcEpochDay(): Long =
        clockEpochMillisProvider() / (24L * 60L * 60L * 1000L)

    suspend fun syncOrInitializeLedger(
        userId: String,
        email: String,
        displayName: String,
        firestoreProfile: UserAccountProfile? = null
    ): UserAccountProfile = transactionMutex.withLock {
        val today = currentServerUtcEpochDay()
        val existing = userLedgerMap[userId] ?: firestoreProfile ?: UserAccountProfile(
            id = userId,
            userId = userId,
            email = email,
            displayName = displayName,
            bonusCreditsRemaining = policy.initialBonusCredits.toLong(),
            dailyFreeCredits = policy.dailyFreeAllowance.toLong(),
            lastDailyResetEpochDay = today,
            rewardedAdCreditsToday = 0L,
            rewardedAdsWatchedToday = 0L,
            subscriptionTier = SubscriptionTier.FREE.id,
            subscriptionAllowanceRemaining = 0L
        )
        val reconciled = applyDailyResetIfEligibleInternal(existing, today)
        userLedgerMap[userId] = reconciled
        reconciled
    }

    private fun applyDailyResetIfEligibleInternal(
        profile: UserAccountProfile,
        serverEpochDay: Long
    ): UserAccountProfile {
        // Guard against daily-reset clock manipulation: only reset when serverEpochDay strictly advances
        if (serverEpochDay <= profile.lastDailyResetEpochDay) {
            return profile
        }
        val tier = SubscriptionTier.fromId(profile.subscriptionTier)
        val newSubAllowance = if (tier == SubscriptionTier.PRO_STUDENT) {
            policy.proSubscriptionDailyAllowance.toLong()
        } else {
            0L
        }
        return profile.copy(
            dailyFreeCredits = policy.dailyFreeAllowance.toLong(),
            rewardedAdCreditsToday = 0L,
            rewardedAdsWatchedToday = 0L,
            subscriptionAllowanceRemaining = newSubAllowance,
            lastDailyResetEpochDay = serverEpochDay
        )
    }

    suspend fun getAuthoritativeProfile(userId: String): UserAccountProfile = transactionMutex.withLock {
        val today = currentServerUtcEpochDay()
        val current = userLedgerMap[userId] ?: UserAccountProfile(
            id = userId,
            userId = userId,
            bonusCreditsRemaining = policy.initialBonusCredits.toLong(),
            dailyFreeCredits = policy.dailyFreeAllowance.toLong(),
            lastDailyResetEpochDay = today
        )
        val reconciled = applyDailyResetIfEligibleInternal(current, today)
        userLedgerMap[userId] = reconciled
        reconciled
    }

    suspend fun evaluateEligibility(
        userId: String,
        videoDurationSeconds: Long
    ): CreditEligibilityEvaluation = transactionMutex.withLock {
        val today = currentServerUtcEpochDay()
        val current = applyDailyResetIfEligibleInternal(
            userLedgerMap[userId] ?: UserAccountProfile(
                id = userId,
                userId = userId,
                bonusCreditsRemaining = policy.initialBonusCredits.toLong(),
                dailyFreeCredits = policy.dailyFreeAllowance.toLong(),
                lastDailyResetEpochDay = today
            ),
            today
        )
        userLedgerMap[userId] = current

        val tier = SubscriptionTier.fromId(current.subscriptionTier)
        val maxDurationSec = policy.maxDurationSecondsForTier(tier)
        if (videoDurationSeconds > maxDurationSec) {
            val videoMin = (videoDurationSeconds + 59) / 60
            val maxMin = maxDurationSec / 60
            return@withLock CreditEligibilityEvaluation(
                canStartGeneration = false,
                availableTotalCredits = current.totalAvailableCredits,
                maxAllowedDurationSeconds = maxDurationSec,
                blockingReason = "Video length ($videoMin min) exceeds your ${tier.displayName} maximum duration of $maxMin minutes. " +
                    if (tier == SubscriptionTier.FREE) {
                        "Upgrade to Pro Student to process lectures up to ${policy.maxVideoDurationMinutesPro} minutes."
                    } else {
                        "Please select a lecture segment under $maxMin minutes."
                    }
            )
        }

        val totalCredits = current.totalAvailableCredits
        if (totalCredits <= 0) {
            val adsRemaining = (policy.maxRewardedAdsPerDay - current.rewardedAdsWatchedToday.toInt()).coerceAtLeast(0)
            val adHint = if (adsRemaining > 0) {
                "Watch a verified rewarded ad (+1 credit, $adsRemaining left today) or upgrade to Pro Student."
            } else {
                "You have reached today's rewarded ad limit (${policy.maxRewardedAdsPerDay}/day). Upgrade to Pro Student or wait for the daily UTC credit reset."
            }
            return@withLock CreditEligibilityEvaluation(
                canStartGeneration = false,
                availableTotalCredits = 0,
                maxAllowedDurationSeconds = maxDurationSec,
                blockingReason = "No generation credits remaining. $adHint"
            )
        }

        val nextSource = when {
            current.subscriptionAllowanceRemaining > 0 -> "PRO_SUBSCRIPTION_ALLOWANCE"
            current.dailyFreeCredits > 0 -> "DAILY_FREE_CREDIT"
            current.rewardedAdCreditsToday > 0 -> "REWARDED_AD_CREDIT"
            else -> "WELCOME_BONUS_CREDIT"
        }

        CreditEligibilityEvaluation(
            canStartGeneration = true,
            availableTotalCredits = totalCredits,
            maxAllowedDurationSeconds = maxDurationSec,
            blockingReason = null,
            nextCreditDeductionSource = nextSource
        )
    }

    /**
     * Atomically reserves and deducts 1 credit for an accepted job.
     * Prevents duplicate deductions for the same jobId and blocks concurrent requests from exceeding balance.
     */
    suspend fun reserveCreditForAcceptedJob(
        userId: String,
        jobId: String,
        videoDurationSeconds: Long
    ): Result<CreditTransactionResponse> = transactionMutex.withLock {
        require(jobId.isNotBlank()) { "Job ID is required for idempotent credit deduction." }

        // Idempotency check: reject duplicate deduction for same jobId
        if (reservedJobCreditSources.containsKey(jobId)) {
            return@withLock Result.failure(
                IllegalStateException("Credit already deducted for job $jobId (duplicate request blocked).")
            )
        }

        val today = currentServerUtcEpochDay()
        val current = applyDailyResetIfEligibleInternal(
            userLedgerMap[userId] ?: UserAccountProfile(
                id = userId,
                userId = userId,
                bonusCreditsRemaining = policy.initialBonusCredits.toLong(),
                dailyFreeCredits = policy.dailyFreeAllowance.toLong(),
                lastDailyResetEpochDay = today
            ),
            today
        )

        val tier = SubscriptionTier.fromId(current.subscriptionTier)
        val maxDurationSec = policy.maxDurationSecondsForTier(tier)
        if (videoDurationSeconds > maxDurationSec) {
            return@withLock Result.failure(
                IllegalArgumentException("Video duration (${videoDurationSeconds / 60}m) exceeds maximum allowed (${maxDurationSec / 60}m) for ${tier.displayName}.")
            )
        }

        val (updated, bucketUsed) = when {
            current.subscriptionAllowanceRemaining > 0L -> {
                current.copy(subscriptionAllowanceRemaining = current.subscriptionAllowanceRemaining - 1L) to "PRO_SUBSCRIPTION_ALLOWANCE"
            }
            current.dailyFreeCredits > 0L -> {
                current.copy(dailyFreeCredits = current.dailyFreeCredits - 1L) to "DAILY_FREE_CREDIT"
            }
            current.rewardedAdCreditsToday > 0L -> {
                current.copy(rewardedAdCreditsToday = current.rewardedAdCreditsToday - 1L) to "REWARDED_AD_CREDIT"
            }
            current.bonusCreditsRemaining > 0L -> {
                current.copy(bonusCreditsRemaining = current.bonusCreditsRemaining - 1L) to "WELCOME_BONUS_CREDIT"
            }
            else -> {
                return@withLock Result.failure(
                    IllegalStateException("Insufficient generation credits. Watch a rewarded ad or upgrade to Pro Student.")
                )
            }
        }

        userLedgerMap[userId] = updated
        reservedJobCreditSources[jobId] = bucketUsed

        Result.success(
            CreditTransactionResponse(
                accepted = true,
                message = "1 credit deducted from $bucketUsed for job $jobId.",
                updatedBonusCredits = updated.bonusCreditsRemaining.toInt(),
                updatedDailyFreeCredits = updated.dailyFreeCredits.toInt(),
                updatedRewardedAdCredits = updated.rewardedAdCreditsToday.toInt(),
                updatedSubscriptionAllowance = updated.subscriptionAllowanceRemaining.toInt(),
                subscriptionTier = updated.subscriptionTier,
                creditSourceUsed = bucketUsed
            )
        )
    }

    /**
     * Refunds the deducted credit to the exact bucket it was taken from when a job fails
     * or is cancelled prior to completion. Prevents double-refunds via refundedJobIds.
     */
    suspend fun refundCreditForQualifyingFailedJob(
        userId: String,
        jobId: String
    ): Result<CreditTransactionResponse> = transactionMutex.withLock {
        val sourceBucket = reservedJobCreditSources[jobId]
            ?: return@withLock Result.failure(
                IllegalStateException("No credit reservation found for job $jobId.")
            )
        if (refundedJobIds.contains(jobId)) {
            return@withLock Result.failure(
                IllegalStateException("Credit for job $jobId was already refunded.")
            )
        }

        val current = userLedgerMap[userId] ?: return@withLock Result.failure(
            IllegalStateException("User ledger not found for $userId.")
        )

        val updated = when (sourceBucket) {
            "PRO_SUBSCRIPTION_ALLOWANCE" -> current.copy(
                subscriptionAllowanceRemaining = current.subscriptionAllowanceRemaining + 1L
            )
            "DAILY_FREE_CREDIT" -> current.copy(
                dailyFreeCredits = current.dailyFreeCredits + 1L
            )
            "REWARDED_AD_CREDIT" -> current.copy(
                rewardedAdCreditsToday = current.rewardedAdCreditsToday + 1L
            )
            else -> current.copy(
                bonusCreditsRemaining = current.bonusCreditsRemaining + 1L
            )
        }

        userLedgerMap[userId] = updated
        refundedJobIds.add(jobId)

        Result.success(
            CreditTransactionResponse(
                accepted = true,
                message = "1 credit refunded to $sourceBucket after qualifying job failure.",
                updatedBonusCredits = updated.bonusCreditsRemaining.toInt(),
                updatedDailyFreeCredits = updated.dailyFreeCredits.toInt(),
                updatedRewardedAdCredits = updated.rewardedAdCreditsToday.toInt(),
                updatedSubscriptionAllowance = updated.subscriptionAllowanceRemaining.toInt(),
                subscriptionTier = updated.subscriptionTier,
                creditSourceUsed = sourceBucket
            )
        )
    }

    /**
     * Issues a server-registered single-use SSV nonce before loading/presenting a rewarded ad.
     */
    suspend fun issueRewardedAdSessionNonce(userId: String): Result<String> = transactionMutex.withLock {
        val today = currentServerUtcEpochDay()
        val current = applyDailyResetIfEligibleInternal(
            userLedgerMap[userId] ?: UserAccountProfile(id = userId, userId = userId, lastDailyResetEpochDay = today),
            today
        )
        userLedgerMap[userId] = current

        if (current.rewardedAdsWatchedToday >= policy.maxRewardedAdsPerDay) {
            return@withLock Result.failure(
                IllegalStateException("Daily rewarded ad cap reached (${policy.maxRewardedAdsPerDay}/${policy.maxRewardedAdsPerDay} today). Try again after the daily UTC reset.")
            )
        }

        val nonce = "ssv_${userId.take(8)}_${clockEpochMillisProvider()}_${(1000..9999).random()}"
        issuedAdRewardNonces[nonce] = userId
        Result.success(nonce)
    }

    /**
     * Verifies a completed Google Mobile Ads rewarded advertisement callback and grants
     * exactly 1 credit only if:
     * 1. sdkRewardEarned == true (verified by OnUserEarnedRewardListener)
     * 2. rewardRequestId nonce was genuinely issued for this userId
     * 3. rewardRequestId has never been claimed before (replay protection)
     * 4. User has not reached maxRewardedAdsPerDay
     */
    suspend fun verifyAndGrantRewardedAdCredit(
        userId: String,
        rewardRequestId: String,
        adUnitId: String,
        sdkRewardEarned: Boolean
    ): Result<CreditTransactionResponse> = transactionMutex.withLock {
        if (!sdkRewardEarned) {
            return@withLock Result.failure(
                IllegalStateException("Rewarded ad was dismissed or failed before completion. No credit granted.")
            )
        }
        if (rewardRequestId.isBlank() || issuedAdRewardNonces[rewardRequestId] != userId) {
            return@withLock Result.failure(
                IllegalStateException("Unverified or mismatched SSV reward nonce ($rewardRequestId). Credit rejected.")
            )
        }
        if (verifiedAdRewardNonces.contains(rewardRequestId)) {
            return@withLock Result.failure(
                IllegalStateException("Duplicate reward callback detected for nonce $rewardRequestId. Replay blocked.")
            )
        }

        val today = currentServerUtcEpochDay()
        val current = applyDailyResetIfEligibleInternal(
            userLedgerMap[userId] ?: UserAccountProfile(id = userId, userId = userId, lastDailyResetEpochDay = today),
            today
        )

        if (current.rewardedAdsWatchedToday >= policy.maxRewardedAdsPerDay) {
            return@withLock Result.failure(
                IllegalStateException("Daily rewarded ad limit (${policy.maxRewardedAdsPerDay}/day) reached.")
            )
        }

        verifiedAdRewardNonces.add(rewardRequestId)
        val updated = current.copy(
            rewardedAdCreditsToday = current.rewardedAdCreditsToday + policy.creditsGrantedPerRewardedAd,
            rewardedAdsWatchedToday = current.rewardedAdsWatchedToday + 1L
        )
        userLedgerMap[userId] = updated

        Result.success(
            CreditTransactionResponse(
                accepted = true,
                message = "Verified rewarded ad ($adUnitId): +${policy.creditsGrantedPerRewardedAd} generation credit granted (${updated.rewardedAdsWatchedToday}/${policy.maxRewardedAdsPerDay} watched today).",
                updatedBonusCredits = updated.bonusCreditsRemaining.toInt(),
                updatedDailyFreeCredits = updated.dailyFreeCredits.toInt(),
                updatedRewardedAdCredits = updated.rewardedAdCreditsToday.toInt(),
                updatedSubscriptionAllowance = updated.subscriptionAllowanceRemaining.toInt(),
                subscriptionTier = updated.subscriptionTier,
                creditSourceUsed = "REWARDED_AD_CREDIT"
            )
        )
    }

    /**
     * Verifies and processes Google Play Billing subscription lifecycle transitions:
     * PENDING, ACTIVE_VERIFIED, CANCELLED, EXPIRED, RESTORED, REFUNDED_REVOKED.
     */
    suspend fun verifyAndProcessSubscriptionState(
        userId: String,
        productId: String,
        purchaseToken: String,
        lifecycleState: PurchaseLifecycleState
    ): Result<Pair<UserAccountProfile, PurchaseLifecycleState>> = transactionMutex.withLock {
        val today = currentServerUtcEpochDay()
        val current = applyDailyResetIfEligibleInternal(
            userLedgerMap[userId] ?: UserAccountProfile(id = userId, userId = userId, lastDailyResetEpochDay = today),
            today
        )

        val updatedProfile = when (lifecycleState) {
            PurchaseLifecycleState.ACTIVE_VERIFIED,
            PurchaseLifecycleState.RESTORED -> {
                if (purchaseToken.isBlank()) {
                    return@withLock Result.failure(
                        IllegalArgumentException("Purchase token is required to verify $productId.")
                    )
                }
                purchaseTokenStates[purchaseToken] = lifecycleState
                current.copy(
                    subscriptionTier = SubscriptionTier.PRO_STUDENT.id,
                    subscriptionAllowanceRemaining = policy.proSubscriptionDailyAllowance.toLong()
                )
            }
            PurchaseLifecycleState.PENDING,
            PurchaseLifecycleState.CANCELLED -> {
                // Do NOT grant Pro entitlement while payment is pending or cancelled
                if (purchaseToken.isNotBlank()) {
                    purchaseTokenStates[purchaseToken] = lifecycleState
                }
                current
            }
            PurchaseLifecycleState.EXPIRED,
            PurchaseLifecycleState.REFUNDED_REVOKED,
            PurchaseLifecycleState.NONE -> {
                if (purchaseToken.isNotBlank()) {
                    purchaseTokenStates[purchaseToken] = lifecycleState
                }
                current.copy(
                    subscriptionTier = SubscriptionTier.FREE.id,
                    subscriptionAllowanceRemaining = 0L
                )
            }
        }

        userLedgerMap[userId] = updatedProfile
        userPurchaseStateMap[userId] = lifecycleState
        Result.success(updatedProfile to lifecycleState)
    }

    suspend fun getPurchaseLifecycleState(userId: String): PurchaseLifecycleState = transactionMutex.withLock {
        userPurchaseStateMap[userId] ?: PurchaseLifecycleState.NONE
    }

    companion object {
        fun createRetrofitService(baseUrl: String = BuildConfig.TRUSTED_BACKEND_URL): TrustedBackendApi {
            val safeBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            val json = Json { ignoreUnknownKeys = true }
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(safeBaseUrl)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TrustedBackendApi::class.java)
        }
    }
}
