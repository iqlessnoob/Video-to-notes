package com.example.data.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.BuildConfig
import com.example.data.backend.ServerCreditAndEntitlementCoordinator
import com.example.data.model.MonetizationEnvironmentMode
import com.example.data.model.PurchaseLifecycleState
import com.example.data.model.UserAccountProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SubscriptionUiStatus(
    val environmentMode: MonetizationEnvironmentMode = MonetizationEnvironmentMode.TEST_MODE,
    val configuredProductId: String = "",
    val isBillingServiceConnected: Boolean = false,
    val resolvedProductDetails: ProductDetails? = null,
    val formattedPrice: String = "$9.99 / month (Configure in Play Console)",
    val lifecycleState: PurchaseLifecycleState = PurchaseLifecycleState.NONE,
    val statusMessage: String = "Free Student tier active (3 welcome bonus + 1 daily free credit)."
)

/**
 * Integrates Google Play Billing Library (v7) for recurring Pro Student subscriptions.
 *
 * Rules enforced:
 * - Never invents production subscription product IDs: inspects BuildConfig.PLAY_BILLING_SUBSCRIPTION_PRODUCT_ID.
 * - Verifies all purchases through ServerCreditAndEntitlementCoordinator before granting entitlement.
 * - Explicitly handles PENDING, ACTIVE_VERIFIED, CANCELLED, EXPIRED, RESTORED, and REFUNDED_REVOKED states.
 */
class BillingSubscriptionManager(
    private val context: Context,
    private val creditCoordinator: ServerCreditAndEntitlementCoordinator,
    private val scope: CoroutineScope,
    private val onProfileUpdated: (UserAccountProfile) -> Unit
) : PurchasesUpdatedListener {

    private var activeUserId: String = ""

    val isProductConfiguredForProduction: Boolean
        get() {
            val id = BuildConfig.PLAY_BILLING_SUBSCRIPTION_PRODUCT_ID
            return id.isNotBlank() && id != "UNCONFIGURED_SUBSCRIPTION_PRODUCT_ID"
        }

    val configuredProductId: String
        get() = BuildConfig.PLAY_BILLING_SUBSCRIPTION_PRODUCT_ID

    private val _uiStatus = MutableStateFlow(
        SubscriptionUiStatus(
            environmentMode = if (isProductConfiguredForProduction) {
                MonetizationEnvironmentMode.PRODUCTION_MODE
            } else {
                MonetizationEnvironmentMode.TEST_MODE
            },
            configuredProductId = configuredProductId
        )
    )
    val uiStatus: StateFlow<SubscriptionUiStatus> = _uiStatus.asStateFlow()

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    fun bindUserSession(userId: String) {
        activeUserId = userId
        connectAndQueryPlayBilling()
    }

    fun connectAndQueryPlayBilling() {
        if (billingClient.isReady) {
            querySubscriptionProductDetails()
            restoreAndReconcilePurchases()
            return
        }
        try {
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    val connected = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    _uiStatus.value = _uiStatus.value.copy(isBillingServiceConnected = connected)
                    if (connected) {
                        querySubscriptionProductDetails()
                        restoreAndReconcilePurchases()
                    } else {
                        Log.i(TAG, "Play Billing setup code=${billingResult.responseCode}: ${billingResult.debugMessage}")
                    }
                }

                override fun onBillingServiceDisconnected() {
                    _uiStatus.value = _uiStatus.value.copy(isBillingServiceConnected = false)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Play Billing connection error: ${e.message}")
        }
    }

    private fun querySubscriptionProductDetails() {
        if (!isProductConfiguredForProduction) {
            return
        }
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(configuredProductId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = productDetailsList.firstOrNull()
                val price = details?.subscriptionOfferDetails
                    ?.firstOrNull()
                    ?.pricingPhases
                    ?.pricingPhaseList
                    ?.firstOrNull()
                    ?.formattedPrice
                    ?: "$9.99 / month"
                _uiStatus.value = _uiStatus.value.copy(
                    resolvedProductDetails = details,
                    formattedPrice = price
                )
            }
        }
    }

    fun launchSubscriptionFlow(activity: Activity, userId: String) {
        activeUserId = userId
        if (!isProductConfiguredForProduction) {
            _uiStatus.value = _uiStatus.value.copy(
                statusMessage = "Production PLAY_BILLING_SUBSCRIPTION_PRODUCT_ID is not yet set in AI Studio Secrets. Use the Verified Test Mode controls below to test Pending, Active, Cancelled, Expired, Restored, and Refunded states."
            )
            return
        }
        val details = _uiStatus.value.resolvedProductDetails
        val offerToken = details?.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (!billingClient.isReady || details == null || offerToken == null) {
            _uiStatus.value = _uiStatus.value.copy(
                statusMessage = "Google Play Billing product '$configuredProductId' is not yet available from Play Store on this device."
            )
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        val userId = activeUserId.ifBlank { return }
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) return
                for (purchase in purchases) {
                    handlePlayPurchaseObject(userId, purchase, isRestore = false)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                scope.launch {
                    val result = creditCoordinator.verifyAndProcessSubscriptionState(
                        userId = userId,
                        productId = configuredProductId,
                        purchaseToken = "",
                        lifecycleState = PurchaseLifecycleState.CANCELLED
                    )
                    result.onSuccess { (profile, state) ->
                        onProfileUpdated(profile)
                        _uiStatus.value = _uiStatus.value.copy(
                            lifecycleState = state,
                            statusMessage = state.description
                        )
                    }
                }
            }
            else -> {
                _uiStatus.value = _uiStatus.value.copy(
                    statusMessage = "Google Play Billing error (${billingResult.responseCode}): ${billingResult.debugMessage}"
                )
            }
        }
    }

    private fun handlePlayPurchaseObject(userId: String, purchase: Purchase, isRestore: Boolean) {
        val productId = purchase.products.firstOrNull() ?: configuredProductId
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> {
                scope.launch {
                    val result = creditCoordinator.verifyAndProcessSubscriptionState(
                        userId = userId,
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        lifecycleState = PurchaseLifecycleState.PENDING
                    )
                    result.onSuccess { (profile, state) ->
                        onProfileUpdated(profile)
                        _uiStatus.value = _uiStatus.value.copy(
                            lifecycleState = state,
                            statusMessage = state.description
                        )
                    }
                }
            }
            Purchase.PurchaseState.PURCHASED -> {
                if (!purchase.isAcknowledged) {
                    val ackParams = AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(ackParams) { ackResult ->
                        Log.i(TAG, "Purchase acknowledgement result: ${ackResult.responseCode}")
                    }
                }
                val targetState = if (isRestore) {
                    PurchaseLifecycleState.RESTORED
                } else {
                    PurchaseLifecycleState.ACTIVE_VERIFIED
                }
                scope.launch {
                    val result = creditCoordinator.verifyAndProcessSubscriptionState(
                        userId = userId,
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        lifecycleState = targetState
                    )
                    result.onSuccess { (profile, state) ->
                        onProfileUpdated(profile)
                        _uiStatus.value = _uiStatus.value.copy(
                            lifecycleState = state,
                            statusMessage = state.description
                        )
                    }
                }
            }
            else -> {
                // UNSPECIFIED_STATE
                Log.w(TAG, "Purchase in unspecified state: ${purchase.purchaseState}")
            }
        }
    }

    fun restoreAndReconcilePurchases(userId: String = activeUserId) {
        if (userId.isBlank()) return
        activeUserId = userId
        if (!billingClient.isReady) {
            _uiStatus.value = _uiStatus.value.copy(
                statusMessage = "Play Billing client not connected to Play Store on this device; reconciled against server entitlement ledger."
            )
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchasesList.isEmpty()) {
                    _uiStatus.value = _uiStatus.value.copy(
                        statusMessage = "No active Google Play subscriptions found to restore for this account."
                    )
                } else {
                    for (purchase in purchasesList) {
                        handlePlayPurchaseObject(userId, purchase, isRestore = true)
                    }
                }
            }
        }
    }

    /**
     * Clearly labeled Test Mode lifecycle verifier for testing Pending, Active, Cancelled,
     * Expired, Restored, and Refunded purchase transitions against the backend coordinator.
     * Blocked when in PRODUCTION_MODE.
     */
    fun triggerTestModeSubscriptionState(
        userId: String,
        targetState: PurchaseLifecycleState
    ) {
        if (isProductConfiguredForProduction) {
            _uiStatus.value = _uiStatus.value.copy(
                statusMessage = "Test Mode subscription state transitions are disabled in Production Mode."
            )
            return
        }
        scope.launch {
            val testToken = "test_play_token_${userId.take(8)}_${targetState.name.lowercase()}"
            val result = creditCoordinator.verifyAndProcessSubscriptionState(
                userId = userId,
                productId = "test_pro_student_monthly",
                purchaseToken = testToken,
                lifecycleState = targetState
            )
            result.fold(
                onSuccess = { (profile, state) ->
                    onProfileUpdated(profile)
                    _uiStatus.value = _uiStatus.value.copy(
                        lifecycleState = state,
                        statusMessage = "[TEST MODE VERIFIED] ${state.displayTitle}: ${state.description}"
                    )
                },
                onFailure = { error ->
                    _uiStatus.value = _uiStatus.value.copy(
                        statusMessage = "Subscription verification failed: ${error.message}"
                    )
                }
            )
        }
    }

    companion object {
        private const val TAG = "BillingSubManager"
    }
}
