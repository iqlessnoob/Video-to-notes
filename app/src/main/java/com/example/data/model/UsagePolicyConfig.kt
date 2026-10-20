package com.example.data.model

import kotlinx.serialization.Serializable

/**
 * Centralized, configurable usage, credit, and cost-control policy settings.
 * Keeps initial allowances, daily reset rules, rewarded-ad caps, and duration limits
 * in one place rather than scattering hardcoded constants across the app.
 */
@Serializable
data class UsagePolicyConfig(
    val initialBonusCredits: Int = 3,
    val dailyFreeAllowance: Int = 1,
    val maxRewardedAdsPerDay: Int = 3,
    val creditsGrantedPerRewardedAd: Int = 1,
    val proSubscriptionDailyAllowance: Int = 30,
    val maxVideoDurationMinutesFree: Int = 45,
    val maxVideoDurationMinutesPro: Int = 180,
    val maxLocalFileSizeMb: Int = 250,
    val sectionChunkDurationSeconds: Int = 600, // 10-minute sections for long videos
    val maxKeyframesPerSection: Int = 2,
    val maxTokensPerSectionRequest: Int = 4096,
    val requestTimeoutSeconds: Long = 60L,
    val maxAutomaticRetries: Int = 2,
    val enableResultCacheByContentHash: Boolean = true
) {
    fun maxDurationSecondsForTier(tier: SubscriptionTier): Long {
        val minutes = when (tier) {
            SubscriptionTier.FREE -> maxVideoDurationMinutesFree
            SubscriptionTier.PRO_STUDENT -> maxVideoDurationMinutesPro
        }
        return minutes * 60L
    }
}
