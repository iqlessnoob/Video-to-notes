package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.serialization.Serializable

@Serializable
enum class StudyDetailLevel(
    val id: String,
    val displayName: String,
    val description: String,
    val targetDepthHint: String
) {
    QUICK_SUMMARY(
        id = "QUICK_SUMMARY",
        displayName = "Quick Summary",
        description = "Concise executive overview, core formulas, and top takeaways for rapid pre-class or pre-exam review.",
        targetDepthHint = "Concise 2-page high-yield summary focusing on main concepts, core formulas, and key takeaways."
    ),
    STANDARD_NOTES(
        id = "STANDARD_NOTES",
        displayName = "Standard Notes",
        description = "Balanced section-by-section timestamped notes, definitions, formulas, diagrams, and review questions.",
        targetDepthHint = "Comprehensive structured lecture notes with timestamped explanations, key formulas, flowcharts, and revision questions."
    ),
    DETAILED_STUDY_NOTES(
        id = "DETAILED_STUDY_NOTES",
        displayName = "Detailed Study Notes",
        description = "Exhaustive textbook-depth breakdown with step-by-step derivations, mechanism diagrams, comparison tables, and full glossary.",
        targetDepthHint = "Deep academic textbook notes preserving full derivations, every variable unit, detailed mechanism diagrams, comparison tables, and a technical glossary."
    );

    companion object {
        fun fromId(id: String): StudyDetailLevel =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: STANDARD_NOTES
    }
}

@Serializable
enum class VideoSourceType(val id: String, val label: String) {
    YOUTUBE("YOUTUBE", "YouTube Lecture URL"),
    LOCAL_FILE("LOCAL_FILE", "Local Device Video");

    companion object {
        fun fromId(id: String): VideoSourceType =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: YOUTUBE
    }
}

@Serializable
enum class JobStatus(val id: String, val displayLabel: String, val isTerminal: Boolean) {
    VALIDATING("VALIDATING", "Validating Source & Credits", false),
    EXTRACTING("EXTRACTING", "Extracting Transcript & Keyframes", false),
    ANALYZING_SECTIONS("ANALYZING_SECTIONS", "Analyzing Lecture Sections", false),
    RENDERING_PDF("RENDERING_PDF", "Rendering Native Vector PDF", false),
    COMPLETED("COMPLETED", "Study PDF Ready", true),
    FAILED("FAILED", "Processing Failed", true),
    CANCELLED("CANCELLED", "Cancelled by User", true);

    companion object {
        fun fromId(id: String): JobStatus =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: VALIDATING
    }
}

@Serializable
enum class SubscriptionTier(val id: String, val displayName: String) {
    FREE("FREE", "Free Student Account"),
    PRO_STUDENT("PRO_STUDENT", "Pro Student Scholar");

    companion object {
        fun fromId(id: String): SubscriptionTier =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: FREE
    }
}

@Serializable
enum class PurchaseLifecycleState(val displayTitle: String, val description: String) {
    NONE("No Active Subscription", "You are currently on the Free Student tier."),
    PENDING("Purchase Pending Confirmation", "Your payment is being processed by Google Play. Pro benefits will unlock once verified."),
    ACTIVE_VERIFIED("Pro Student Active (Verified)", "Your recurring subscription is verified by the backend."),
    CANCELLED("Purchase Cancelled", "The subscription checkout flow was cancelled without charge."),
    EXPIRED("Subscription Expired", "Your previous Pro Student subscription has expired. Renew to restore extended limits."),
    RESTORED("Subscription Restored & Verified", "Your active Google Play subscription was restored and verified."),
    REFUNDED_REVOKED("Subscription Refunded / Revoked", "This purchase was refunded or revoked; account returned to Free tier.")
}

@Serializable
enum class MonetizationEnvironmentMode(val badgeLabel: String) {
    TEST_MODE("VERIFIED DEVELOPMENT TEST MODE"),
    PRODUCTION_MODE("PRODUCTION MODE")
}

@IgnoreExtraProperties
data class UserAccountProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val preferredDetailLevel: String = StudyDetailLevel.STANDARD_NOTES.id,
    val bonusCreditsRemaining: Long = 3L,
    val dailyFreeCredits: Long = 1L,
    val lastDailyResetEpochDay: Long = 0L,
    val rewardedAdCreditsToday: Long = 0L,
    val rewardedAdsWatchedToday: Long = 0L,
    val subscriptionTier: String = SubscriptionTier.FREE.id,
    val subscriptionAllowanceRemaining: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val totalAvailableCredits: Int
        get() {
            val tier = SubscriptionTier.fromId(subscriptionTier)
            return if (tier == SubscriptionTier.PRO_STUDENT) {
                (subscriptionAllowanceRemaining + bonusCreditsRemaining + dailyFreeCredits + rewardedAdCreditsToday).toInt()
            } else {
                (dailyFreeCredits + bonusCreditsRemaining + rewardedAdCreditsToday).toInt()
            }
        }
}

@IgnoreExtraProperties
data class StudyJob(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sourceType: String = VideoSourceType.YOUTUBE.id,
    val sourceUri: String = "",
    val videoTitle: String = "",
    val videoDurationSeconds: Long = 0L,
    val detailLevel: String = StudyDetailLevel.STANDARD_NOTES.id,
    val status: String = JobStatus.VALIDATING.id,
    val progressPercent: Long = 0L,
    val currentStageDetail: String = "",
    val creditDeducted: Boolean = false,
    val creditRefunded: Boolean = false,
    val errorMessage: String? = null,
    val localPdfPath: String? = null,
    val contentHash: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MonetizationVerificationEvent(
    @DocumentId val id: String = "",
    val userId: String = "",
    val eventType: String = "",
    val idempotencyKey: String = "",
    val referenceId: String = "",
    val verificationPayload: String = "",
    val verificationStatus: String = "PENDING_SERVER_VERIFICATION",
    @ServerTimestamp val createdAt: Timestamp? = null
)
