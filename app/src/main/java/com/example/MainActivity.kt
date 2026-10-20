package com.example

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.auth.AuthManager
import com.example.ui.auth.AuthenticatedSessionUser
import com.example.ui.screens.CreditsUsageScreen
import com.example.ui.screens.HomeVideoInputScreen
import com.example.ui.screens.LibraryHistoryScreen
import com.example.ui.screens.PdfPreviewResultsScreen
import com.example.ui.screens.ProcessingStatusScreen
import com.example.ui.screens.SettingsPrivacyHelpScreen
import com.example.ui.screens.UpgradeMonetizationScreen
import com.example.ui.screens.WelcomeAuthScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AppScreenDestination
import com.example.ui.viewmodel.StudyAppViewModel
import com.google.android.gms.ads.MobileAds
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeFirebaseAndMobileAdsSafely()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                VideoToStudyPdfRootApp()
            }
        }
    }

    private fun initializeFirebaseAndMobileAdsSafely() {
        try {
            val apps = FirebaseApp.getApps(this)
            if (apps.isNotEmpty() || FirebaseApp.initializeApp(this) != null) {
                Firebase.appCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Firebase/AppCheck init deferred until google-services.json is provisioned: ${e.message}")
        }
        try {
            MobileAds.initialize(this) {}
        } catch (e: Exception) {
            Log.w("MainActivity", "MobileAds initialization skipped in test environment: ${e.message}")
        }
    }
}

private data class PrimaryNavTab(
    val destination: AppScreenDestination,
    val label: String,
    val icon: ImageVector,
    val tag: String
)

private val PRIMARY_NAV_TABS = listOf(
    PrimaryNavTab(AppScreenDestination.HOME, "Studio", Icons.Default.Home, "nav_tab_home"),
    PrimaryNavTab(AppScreenDestination.PDF_PREVIEW, "Preview", Icons.Default.PictureAsPdf, "nav_tab_preview"),
    PrimaryNavTab(AppScreenDestination.LIBRARY, "Library", Icons.Default.FolderSpecial, "nav_tab_library"),
    PrimaryNavTab(AppScreenDestination.CREDITS, "Credits", Icons.Default.AccountBalanceWallet, "nav_tab_credits"),
    PrimaryNavTab(AppScreenDestination.UPGRADE, "Pro & Ads", Icons.Default.Star, "nav_tab_upgrade")
)

@Composable
fun VideoToStudyPdfRootApp(
    initialSessionUserForTest: AuthenticatedSessionUser? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var sessionUser by remember {
        mutableStateOf<AuthenticatedSessionUser?>(initialSessionUserForTest)
    }

    if (sessionUser == null) {
        WelcomeAuthScreen(
            onAuthenticated = { authenticated ->
                sessionUser = authenticated
            }
        )
    } else {
        val activeUser = sessionUser!!
        val viewModel: StudyAppViewModel = viewModel(
            key = "study_vm_${activeUser.uid}",
            factory = StudyAppViewModel.provideFactory(context, activeUser)
        )
        AuthenticatedStudyStudioScaffold(
            viewModel = viewModel,
            onSignOut = {
                AuthManager.signOut(
                    context = context,
                    credentialManager = credentialManager,
                    onSignOutComplete = { sessionUser = null },
                    scope = scope
                )
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthenticatedStudyStudioScaffold(
    viewModel: StudyAppViewModel,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedDocuments by viewModel.cachedDocuments.collectAsStateWithLifecycle()
    val rewardedAdState by viewModel.rewardedAdState.collectAsStateWithLifecycle()
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // BackHandler on all secondary screens to return to HOME
    if (uiState.currentScreen != AppScreenDestination.HOME) {
        BackHandler {
            viewModel.navigateTo(AppScreenDestination.HOME)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedScreen = maxWidth >= 600.dp

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row {
                            Icon(
                                imageVector = Icons.Default.AutoStories,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.currentScreen.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    actions = {
                        AssistChip(
                            onClick = { viewModel.navigateTo(AppScreenDestination.CREDITS) },
                            label = {
                                Text(
                                    text = "${uiState.profile.totalAvailableCredits} Cr",
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            modifier = Modifier.testTag("top_bar_credits_chip")
                        )
                        IconButton(
                            onClick = { viewModel.navigateTo(AppScreenDestination.SETTINGS) },
                            modifier = Modifier.testTag("top_bar_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings & Privacy"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            bottomBar = {
                if (!isExpandedScreen) {
                    NavigationBar {
                        PRIMARY_NAV_TABS.forEach { tab ->
                            val isSelected = uiState.currentScreen == tab.destination ||
                                (tab.destination == AppScreenDestination.HOME && uiState.currentScreen == AppScreenDestination.PROCESSING)
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { viewModel.navigateTo(tab.destination) },
                                icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isExpandedScreen) {
                    NavigationRail {
                        PRIMARY_NAV_TABS.forEach { tab ->
                            val isSelected = uiState.currentScreen == tab.destination ||
                                (tab.destination == AppScreenDestination.HOME && uiState.currentScreen == AppScreenDestination.PROCESSING)
                            NavigationRailItem(
                                selected = isSelected,
                                onClick = { viewModel.navigateTo(tab.destination) },
                                icon = { Icon(imageVector = tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (uiState.currentScreen) {
                        AppScreenDestination.HOME -> HomeVideoInputScreen(
                            uiState = uiState,
                            onYoutubeUrlChanged = viewModel::updateYoutubeUrl,
                            onInspectYoutubeClicked = viewModel::inspectCurrentYoutubeUrl,
                            onLoadPresetLecture = viewModel::loadVerifiedLecturePreset,
                            onLocalVideoSelected = viewModel::inspectSelectedLocalVideo,
                            onSupplementaryNotesChanged = viewModel::updateSupplementaryNotes,
                            onSelectDetailLevel = viewModel::selectDetailLevel,
                            onStartProcessingClicked = { viewModel.startVideoToPdfProcessing(forceBypassCache = false) },
                            onNavigateToScreen = viewModel::navigateTo
                        )
                        AppScreenDestination.PROCESSING -> ProcessingStatusScreen(
                            job = uiState.activeJob,
                            onCancelJob = viewModel::cancelActiveProcessingJob,
                            onOpenCompletedPdf = { viewModel.navigateTo(AppScreenDestination.PDF_PREVIEW) },
                            onBackToHome = { viewModel.navigateTo(AppScreenDestination.HOME) }
                        )
                        AppScreenDestination.PDF_PREVIEW -> PdfPreviewResultsScreen(
                            studyDocument = uiState.activeStudyDocument,
                            pdfFile = uiState.activePdfFile,
                            pageCount = uiState.activePdfPageCount,
                            pdfGenerator = viewModel.pdfGenerator,
                            onDownloadClicked = viewModel::downloadActivePdfToDevice,
                            onShareClicked = { viewModel.shareActivePdf(context) },
                            onRegenerateClicked = { viewModel.startVideoToPdfProcessing(forceBypassCache = true) },
                            onBackToStudio = { viewModel.navigateTo(AppScreenDestination.HOME) }
                        )
                        AppScreenDestination.LIBRARY -> LibraryHistoryScreen(
                            savedPdfs = savedDocuments,
                            recentJobs = uiState.recentJobs,
                            onOpenSavedPdf = viewModel::openCachedDocument,
                            onCreateNewClicked = { viewModel.navigateTo(AppScreenDestination.HOME) }
                        )
                        AppScreenDestination.CREDITS -> CreditsUsageScreen(
                            profile = uiState.profile,
                            policy = uiState.policy,
                            lastStatusBanner = uiState.lastActionStatusBanner,
                            onNavigateToUpgrade = { viewModel.navigateTo(AppScreenDestination.UPGRADE) }
                        )
                        AppScreenDestination.UPGRADE -> UpgradeMonetizationScreen(
                            profile = uiState.profile,
                            policy = uiState.policy,
                            adState = rewardedAdState,
                            subscriptionStatus = subscriptionStatus,
                            onLoadRewardedAd = viewModel::loadRewardedAd,
                            onShowRewardedAd = viewModel::showRewardedAd,
                            onTriggerVerifiedTestAd = viewModel::triggerVerifiedTestModeRewardedAd,
                            onLaunchPlaySubscription = viewModel::launchPlayBillingSubscription,
                            onRestorePurchases = viewModel::restorePlayBillingPurchases,
                            onSimulateSubscriptionState = viewModel::simulateTestModeSubscriptionLifecycle
                        )
                        AppScreenDestination.SETTINGS -> SettingsPrivacyHelpScreen(
                            sessionUser = uiState.currentUser,
                            policy = uiState.policy,
                            onClearCache = viewModel::clearLocalPdfCache,
                            onSignOut = onSignOut
                        )
                    }
                }
            }
        }
    }
}
