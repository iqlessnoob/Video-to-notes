package com.example.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.MonetizationEnvironmentMode
import com.example.data.model.PurchaseLifecycleState
import com.example.data.model.SubscriptionTier
import com.example.data.model.UsagePolicyConfig
import com.example.data.model.UserAccountProfile
import com.example.data.monetization.RewardedAdUiState
import com.example.data.monetization.SubscriptionUiStatus
import com.example.ui.auth.AuthenticatedSessionUser

@Composable
fun CreditsUsageScreen(
    profile: UserAccountProfile,
    policy: UsagePolicyConfig,
    lastStatusBanner: String?,
    onNavigateToUpgrade: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tier = SubscriptionTier.fromId(profile.subscriptionTier)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 600.dp)) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("credit_ledger_summary_card")
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AccountBalanceWallet,
                            contentDescription = "Credits",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "SERVER-CONTROLLED CREDIT LEDGER",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${profile.totalAvailableCredits} Total Credit(s)",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Active Plan: ${tier.displayName} • Max Video Duration: ${policy.maxDurationSecondsForTier(tier) / 60} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (!lastStatusBanner.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = lastStatusBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Detailed Credit Bucket Breakdown
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Credit Balance Breakdown",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    LedgerBucketRow(
                        label = "Daily Free Credit (Resets every UTC day)",
                        value = "${profile.dailyFreeCredits} / ${policy.dailyFreeAllowance}"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LedgerBucketRow(
                        label = "One-Time Welcome Bonus Credits",
                        value = "${profile.bonusCreditsRemaining} / ${policy.initialBonusCredits}"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LedgerBucketRow(
                        label = "Verified Rewarded Ad Credits Earned Today",
                        value = "${profile.rewardedAdCreditsToday} (Watched ${profile.rewardedAdsWatchedToday}/${policy.maxRewardedAdsPerDay})"
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    LedgerBucketRow(
                        label = "Pro Student Subscription Allowance Today",
                        value = if (tier == SubscriptionTier.PRO_STUDENT) {
                            "${profile.subscriptionAllowanceRemaining} / ${policy.proSubscriptionDailyAllowance}"
                        } else {
                            "0 (Free Tier)"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Billing & Protection Rules Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Usage & Refund Guarantees",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Every new account receives ${policy.initialBonusCredits} one-time welcome bonus credits plus ${policy.dailyFreeAllowance} daily free credit.\n" +
                            "• 1 credit is deducted only when a valid video job is accepted.\n" +
                            "• If a job fails or is cancelled before completion, your reserved credit is automatically refunded.\n" +
                            "• Credit balances and subscription tiers are enforced by server security rules and idempotency keys.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToUpgrade,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Earn Extra Credits or Subscribe to Pro")
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerBucketRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpgradeMonetizationScreen(
    profile: UserAccountProfile,
    policy: UsagePolicyConfig,
    adState: RewardedAdUiState,
    subscriptionStatus: SubscriptionUiStatus,
    onLoadRewardedAd: () -> Unit,
    onShowRewardedAd: (Activity) -> Unit,
    onTriggerVerifiedTestAd: (Boolean) -> Unit,
    onLaunchPlaySubscription: (Activity) -> Unit,
    onRestorePurchases: () -> Unit,
    onSimulateSubscriptionState: (PurchaseLifecycleState) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 600.dp)) {
            // Environment Mode Banner (Clearly distinguishes Test Mode vs Production Mode)
            Surface(
                color = if (subscriptionStatus.environmentMode == MonetizationEnvironmentMode.TEST_MODE) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("monetization_mode_banner")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = "Mode",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = subscriptionStatus.environmentMode.badgeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "No fake rewards or unverified purchases are permitted. Credits and Pro entitlements unlock only after SDK + backend verification.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. Google Mobile Ads Rewarded Advertisement Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rewarded_ad_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Rewarded Ad",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Google Mobile Ads — Rewarded Credit (+1)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Watch a complete rewarded advertisement to earn +${policy.creditsGrantedPerRewardedAd} extra generation credit. Daily cap: ${profile.rewardedAdsWatchedToday} / ${policy.maxRewardedAdsPerDay} watched today.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // Ad State Diagnostic Banner
                    val adStatusText = when (adState) {
                        is RewardedAdUiState.Idle -> "Ready to request a rewarded ad session nonce."
                        is RewardedAdUiState.LoadingAd -> "Loading Google Mobile Ads rewarded ad & registering SSV nonce..."
                        is RewardedAdUiState.AdReady -> "Ad loaded (${adState.adUnitId}). Tap 'Watch Loaded Ad' to view."
                        is RewardedAdUiState.ShowingAd -> "Displaying full-screen rewarded ad..."
                        is RewardedAdUiState.RewardVerified -> "✓ ${adState.response.message}"
                        is RewardedAdUiState.AdFailedOrDismissed -> "⚠ ${adState.reason}"
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = adStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .padding(10.dp)
                                .testTag("rewarded_ad_status_text")
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onLoadRewardedAd,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("load_rewarded_ad_button")
                        ) {
                            Text("1. Load AdMob Ad")
                        }
                        Button(
                            onClick = { if (activity != null) onShowRewardedAd(activity) },
                            enabled = adState is RewardedAdUiState.AdReady && activity != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("show_rewarded_ad_button")
                        ) {
                            Text("2. Watch Loaded Ad")
                        }
                    }

                    if (subscriptionStatus.environmentMode == MonetizationEnvironmentMode.TEST_MODE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Verified Test Mode SSV Callback Harness (For Headless / Emulator Verification):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onTriggerVerifiedTestAd(false) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_verify_ad_reward_button")
                            ) {
                                Text("Verify Completed Ad (+1)")
                            }
                            OutlinedButton(
                                onClick = { onTriggerVerifiedTestAd(true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_dismiss_ad_early_button")
                            ) {
                                Text("Test Early Dismiss (0)")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. Google Play Billing Subscription Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("play_billing_subscription_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Pro Plan",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Google Play Billing — Pro Student Plan",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• ${policy.proSubscriptionDailyAllowance} Study PDF generations per day\n" +
                            "• Extended lecture duration up to ${policy.maxVideoDurationMinutesPro} minutes (3 hours)\n" +
                            "• Full Detailed Study Notes with multi-section formula & diagram synthesis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Subscription State: ${subscriptionStatus.lifecycleState.displayTitle}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("subscription_lifecycle_title")
                            )
                            Text(
                                text = subscriptionStatus.statusMessage,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { if (activity != null) onLaunchPlaySubscription(activity) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("subscribe_play_billing_button")
                        ) {
                            Text("Subscribe (${subscriptionStatus.formattedPrice.substringBefore(" (")})")
                        }
                        OutlinedButton(
                            onClick = onRestorePurchases,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("restore_purchases_button")
                        ) {
                            Icon(imageVector = Icons.Default.Restore, contentDescription = "Restore")
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Restore")
                        }
                    }

                    if (subscriptionStatus.environmentMode == MonetizationEnvironmentMode.TEST_MODE) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Verified Test Mode — Simulate Play Billing Lifecycle States:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val statesToTest = listOf(
                                PurchaseLifecycleState.PENDING to "Pending",
                                PurchaseLifecycleState.ACTIVE_VERIFIED to "Active Verified",
                                PurchaseLifecycleState.CANCELLED to "Cancelled",
                                PurchaseLifecycleState.EXPIRED to "Expired",
                                PurchaseLifecycleState.RESTORED to "Restored",
                                PurchaseLifecycleState.REFUNDED_REVOKED to "Refunded"
                            )
                            statesToTest.forEach { (state, label) ->
                                AssistChip(
                                    onClick = { onSimulateSubscriptionState(state) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("sim_sub_${state.name.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPrivacyHelpScreen(
    sessionUser: AuthenticatedSessionUser,
    policy: UsagePolicyConfig,
    onClearCache: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 600.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Account & Session",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Name: ${sessionUser.displayName}\nEmail: ${sessionUser.email}\nUID: ${sessionUser.uid}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onClearCache,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("clear_cache_button")
                        ) {
                            Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear Cache")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear PDF Cache")
                        }
                        Button(
                            onClick = onSignOut,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("sign_out_button")
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign Out")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sign Out")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Cost Control & Privacy Architecture Summary
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Security",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Privacy, Security & Cost-Control Rules",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Long videos are analyzed in ${policy.sectionChunkDurationSeconds / 60}-minute sections to preserve timestamps, formulas, and explanations without exceeding token limits.\n" +
                            "• Mathematical formulas and flowcharts are rendered natively using Android's vector Canvas and PdfDocument APIs (0 AI image generation cost).\n" +
                            "• Firestore security rules enforce strict per-user isolation and block client-side credit or subscription tampering.\n" +
                            "• Security Warning: Any prototype API keys configured via BuildConfig are intended for development testing only; do not distribute debug APKs publicly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Production Console Setup Checklist
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Production Console Setup Guide",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. Firebase Console: Click 'Accept' on the AI Studio Firebase setup prompt to provision Cloud Firestore & Google Sign-In, then deploy firestore.rules.\n" +
                            "2. Google AdMob Console: Create an Android app and Rewarded Ad Unit with Server-Side Verification (SSV) enabled, then set ADMOB_REWARDED_AD_UNIT_ID in AI Studio Secrets.\n" +
                            "3. Google Play Console: Create a recurring subscription product under Monetize > Subscriptions and set PLAY_BILLING_SUBSCRIPTION_PRODUCT_ID in AI Studio Secrets.\n" +
                            "4. Trusted Backend Endpoint: Deploy your Cloud Run / Cloud Functions verification service and set TRUSTED_BACKEND_URL in AI Studio Secrets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
