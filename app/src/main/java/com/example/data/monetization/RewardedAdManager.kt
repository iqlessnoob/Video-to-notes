package com.example.data.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.backend.CreditTransactionResponse
import com.example.data.backend.ServerCreditAndEntitlementCoordinator
import com.example.data.model.MonetizationEnvironmentMode
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.ServerSideVerificationOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RewardedAdUiState {
    data object Idle : RewardedAdUiState
    data object LoadingAd : RewardedAdUiState
    data class AdReady(val adUnitId: String, val isTestAd: Boolean) : RewardedAdUiState
    data object ShowingAd : RewardedAdUiState
    data class RewardVerified(val response: CreditTransactionResponse) : RewardedAdUiState
    data class AdFailedOrDismissed(val reason: String) : RewardedAdUiState
}

/**
 * Integrates Google Mobile Ads Rewarded Advertisements with Server-Side Verification (SSV) nonces.
 *
 * Rules enforced:
 * - Never invents production Ad Unit IDs: uses BuildConfig.ADMOB_REWARDED_AD_UNIT_ID when configured,
 *   or Google's official documented Android Rewarded Test Ad Unit ID ONLY in clearly labeled TEST_MODE.
 * - Grants 1 credit ONLY after OnUserEarnedRewardListener fires AND backend SSV verification succeeds.
 */
class RewardedAdManager(
    private val context: Context,
    private val creditCoordinator: ServerCreditAndEntitlementCoordinator
) {
    private val isSdkInitialized = AtomicBoolean(false)
    private var loadedRewardedAd: RewardedAd? = null
    private var pendingSessionNonce: String? = null

    private val _adState = MutableStateFlow<RewardedAdUiState>(RewardedAdUiState.Idle)
    val adState: StateFlow<RewardedAdUiState> = _adState.asStateFlow()

    val isUsingOfficialGoogleTestAdUnit: Boolean
        get() {
            val configured = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID
            return configured.isBlank() || configured == "UNCONFIGURED_PRODUCTION_AD_UNIT_ID"
        }

    val resolvedAdUnitId: String
        get() = if (isUsingOfficialGoogleTestAdUnit) {
            OFFICIAL_GOOGLE_TEST_REWARDED_AD_UNIT_ID
        } else {
            BuildConfig.ADMOB_REWARDED_AD_UNIT_ID
        }

    val environmentMode: MonetizationEnvironmentMode
        get() = if (isUsingOfficialGoogleTestAdUnit) {
            MonetizationEnvironmentMode.TEST_MODE
        } else {
            MonetizationEnvironmentMode.PRODUCTION_MODE
        }

    fun ensureInitialized() {
        if (isSdkInitialized.compareAndSet(false, true)) {
            try {
                MobileAds.initialize(context.applicationContext) { status ->
                    Log.i(TAG, "Google Mobile Ads initialized: ${status.adapterStatusMap.keys}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "MobileAds initialization warning: ${e.message}")
            }
        }
    }

    fun loadRewardedAd(userId: String, scope: CoroutineScope) {
        ensureInitialized()
        scope.launch {
            _adState.value = RewardedAdUiState.LoadingAd
            val nonceResult = creditCoordinator.issueRewardedAdSessionNonce(userId)
            if (nonceResult.isFailure) {
                val msg = nonceResult.exceptionOrNull()?.message ?: "Daily rewarded ad limit reached."
                _adState.value = RewardedAdUiState.AdFailedOrDismissed(msg)
                return@launch
            }
            val nonce = nonceResult.getOrThrow()
            pendingSessionNonce = nonce
            val unitId = resolvedAdUnitId

            try {
                val adRequest = AdRequest.Builder().build()
                RewardedAd.load(
                    context.applicationContext,
                    unitId,
                    adRequest,
                    object : RewardedAdLoadCallback() {
                        override fun onAdLoaded(ad: RewardedAd) {
                            val ssvOptions = ServerSideVerificationOptions.Builder()
                                .setUserId(userId)
                                .setCustomData(nonce)
                                .build()
                            ad.setServerSideVerificationOptions(ssvOptions)
                            loadedRewardedAd = ad
                            _adState.value = RewardedAdUiState.AdReady(
                                adUnitId = unitId,
                                isTestAd = isUsingOfficialGoogleTestAdUnit
                            )
                        }

                        override fun onAdFailedToLoad(loadError: LoadAdError) {
                            loadedRewardedAd = null
                            Log.w(TAG, "RewardedAd failed to load: ${loadError.message} (code ${loadError.code})")
                            _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                                "Google Mobile Ads could not load an ad right now (${loadError.message}). No credit was granted."
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                loadedRewardedAd = null
                _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                    "Ad SDK load error: ${e.localizedMessage ?: "Unable to load rewarded ad"}."
                )
            }
        }
    }

    fun showLoadedRewardedAd(
        activity: Activity,
        userId: String,
        scope: CoroutineScope,
        onCreditGranted: (CreditTransactionResponse) -> Unit
    ) {
        val ad = loadedRewardedAd
        val nonce = pendingSessionNonce
        if (ad == null || nonce == null) {
            _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                "No loaded rewarded ad is ready. Please tap 'Load Rewarded Ad' first."
            )
            return
        }

        var sdkEarnedReward = false
        _adState.value = RewardedAdUiState.ShowingAd

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                loadedRewardedAd = null
                if (!sdkEarnedReward) {
                    scope.launch {
                        creditCoordinator.verifyAndGrantRewardedAdCredit(
                            userId = userId,
                            rewardRequestId = nonce,
                            adUnitId = resolvedAdUnitId,
                            sdkRewardEarned = false
                        )
                        _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                            "Ad was closed before completion. Complete the full ad to earn 1 credit."
                        )
                    }
                }
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                loadedRewardedAd = null
                _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                    "Rewarded ad failed to display (${adError.message}). No credit granted."
                )
            }
        }

        ad.show(activity) { rewardItem ->
            sdkEarnedReward = true
            scope.launch {
                val verifyResult = creditCoordinator.verifyAndGrantRewardedAdCredit(
                    userId = userId,
                    rewardRequestId = nonce,
                    adUnitId = resolvedAdUnitId,
                    sdkRewardEarned = rewardItem.amount > 0
                )
                verifyResult.fold(
                    onSuccess = { response ->
                        pendingSessionNonce = null
                        _adState.value = RewardedAdUiState.RewardVerified(response)
                        onCreditGranted(response)
                    },
                    onFailure = { error ->
                        _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                            error.message ?: "Reward verification failed."
                        )
                    }
                )
            }
        }
    }

    /**
     * Explicit, clearly labeled Development Test Mode SSV callback verifier for environments
     * where Google Play Services ad rendering is unavailable (such as headless emulators).
     * Never available in PRODUCTION_MODE.
     */
    fun triggerVerifiedTestModeAdCallback(
        userId: String,
        simulateEarlyDismissal: Boolean = false,
        scope: CoroutineScope,
        onCreditGranted: (CreditTransactionResponse) -> Unit
    ) {
        if (!isUsingOfficialGoogleTestAdUnit) {
            _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                "Test callback simulator is disabled in Production Mode."
            )
            return
        }
        scope.launch {
            val nonceResult = creditCoordinator.issueRewardedAdSessionNonce(userId)
            if (nonceResult.isFailure) {
                _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                    nonceResult.exceptionOrNull()?.message ?: "Daily rewarded ad limit reached."
                )
                return@launch
            }
            val nonce = nonceResult.getOrThrow()
            val verifyResult = creditCoordinator.verifyAndGrantRewardedAdCredit(
                userId = userId,
                rewardRequestId = nonce,
                adUnitId = OFFICIAL_GOOGLE_TEST_REWARDED_AD_UNIT_ID,
                sdkRewardEarned = !simulateEarlyDismissal
            )
            verifyResult.fold(
                onSuccess = { response ->
                    _adState.value = RewardedAdUiState.RewardVerified(response)
                    onCreditGranted(response)
                },
                onFailure = { error ->
                    _adState.value = RewardedAdUiState.AdFailedOrDismissed(
                        error.message ?: "Ad reward verification rejected."
                    )
                }
            )
        }
    }

    fun resetBannerState() {
        _adState.value = RewardedAdUiState.Idle
    }

    companion object {
        private const val TAG = "RewardedAdManager"
        // Official Google-documented sample AdMob Rewarded Ad Unit ID for Android development testing
        const val OFFICIAL_GOOGLE_TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    }
}
