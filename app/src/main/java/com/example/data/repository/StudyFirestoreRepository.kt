package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.MonetizationVerificationEvent
import com.example.data.model.StudyDetailLevel
import com.example.data.model.StudyJob
import com.example.data.model.SubscriptionTier
import com.example.data.model.UserAccountProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class StudyFirestoreRepository(
    private val db: FirebaseFirestore
) {
    // Secondary constructor resolving the mandatory custom database ID from string resources
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth: FirebaseAuth
        get() = FirebaseAuth.getInstance()

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun currentEpochDay(): Long = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)

    suspend fun initializeUserProfileIfNeeded(
        email: String,
        displayName: String
    ): Result<UserAccountProfile> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
        return try {
            val existingSnap = docRef.get().await()
            if (existingSnap.exists()) {
                val profile = existingSnap.toObject(
                    UserAccountProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserAccountProfile(id = uid, userId = uid, email = email, displayName = displayName)
                Result.success(profile)
            } else {
                val safeEmail = email.ifBlank { "$uid@student.edu" }.take(200)
                val safeName = displayName.ifBlank { "Student Learner" }.take(100)
                val payload = mapOf(
                    "userId" to uid,
                    "email" to safeEmail,
                    "displayName" to safeName,
                    "preferredDetailLevel" to StudyDetailLevel.STANDARD_NOTES.id,
                    "bonusCreditsRemaining" to 3L,
                    "dailyFreeCredits" to 1L,
                    "lastDailyResetEpochDay" to currentEpochDay(),
                    "rewardedAdCreditsToday" to 0L,
                    "rewardedAdsWatchedToday" to 0L,
                    "subscriptionTier" to SubscriptionTier.FREE.id,
                    "subscriptionAllowanceRemaining" to 0L,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.set(payload).await()
                val createdSnap = docRef.get().await()
                val profile = createdSnap.toObject(
                    UserAccountProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ) ?: UserAccountProfile(
                    id = uid,
                    userId = uid,
                    email = safeEmail,
                    displayName = safeName,
                    bonusCreditsRemaining = 3L,
                    dailyFreeCredits = 1L,
                    lastDailyResetEpochDay = currentEpochDay()
                )
                Result.success(profile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserProfile(userId: String = requireUserId()): Result<UserAccountProfile?> {
        val docRef = db.collection("users").document(userId)
        return try {
            val snapshot = docRef.get().await()
            val profile = snapshot.toObject(
                UserAccountProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    fun observeUserProfile(userId: String): Flow<UserAccountProfile?> {
        val docRef = db.collection("users").document(userId)
        return docRef.snapshots()
            .map { snapshot ->
                snapshot.toObject(
                    UserAccountProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.GET, docRef.path)
                }
                throw error
            }
    }

    suspend fun updateUserPreferences(
        displayName: String,
        preferredDetailLevel: StudyDetailLevel
    ): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("users").document(uid)
        val updates = mapOf(
            "displayName" to displayName.trim().ifBlank { "Student Learner" }.take(100),
            "preferredDetailLevel" to preferredDetailLevel.id,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createStudyJob(
        jobId: String = "job_${UUID.randomUUID().toString().replace("-", "").take(20)}",
        sourceType: String,
        sourceUri: String,
        videoTitle: String,
        videoDurationSeconds: Long,
        detailLevel: String,
        contentHash: String,
        creditDeducted: Boolean
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("study_jobs").document(jobId)
        val payload = mapOf(
            "userId" to uid,
            "sourceType" to sourceType,
            "sourceUri" to sourceUri.take(1000),
            "videoTitle" to videoTitle.take(300),
            "videoDurationSeconds" to videoDurationSeconds.coerceIn(1L, 36000L),
            "detailLevel" to detailLevel,
            "status" to "VALIDATING",
            "progressPercent" to 0L,
            "currentStageDetail" to "Validating video source and credit reservation",
            "creditDeducted" to creditDeducted,
            "creditRefunded" to false,
            "contentHash" to contentHash.take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateJobProgress(
        jobId: String,
        status: String,
        progressPercent: Long,
        currentStageDetail: String,
        errorMessage: String? = null,
        localPdfPath: String? = null
    ): Result<Unit> {
        val docRef = db.collection("study_jobs").document(jobId)
        val rawUpdates = mapOf<String, Any?>(
            "status" to status,
            "progressPercent" to progressPercent.coerceIn(0L, 100L),
            "currentStageDetail" to currentStageDetail.take(500),
            "errorMessage" to errorMessage?.take(1000),
            "localPdfPath" to localPdfPath?.take(1000),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null }
        @Suppress("UNCHECKED_CAST")
        val cleanUpdates = rawUpdates as Map<String, Any>
        return try {
            docRef.update(cleanUpdates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getStudyJobById(jobId: String): Result<StudyJob?> {
        val docRef = db.collection("study_jobs").document(jobId)
        return try {
            val snap = docRef.get().await()
            val job = snap.toObject(
                StudyJob::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserStudyJobs(userId: String = requireUserId()): Result<List<StudyJob>> {
        val path = "study_jobs"
        return try {
            val snap = db.collection(path)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            val jobs = snap.toObjects(
                StudyJob::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).sortedByDescending { it.createdAt?.seconds ?: 0L }
            Result.success(jobs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    fun observeUserStudyJobs(userId: String): Flow<List<StudyJob>> {
        val path = "study_jobs"
        return db.collection(path)
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    StudyJob::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                ).sortedByDescending { it.createdAt?.seconds ?: 0L }
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun recordMonetizationEvent(
        eventType: String,
        idempotencyKey: String,
        referenceId: String,
        verificationPayload: String
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val docRef = db.collection("monetization_events").document(idempotencyKey)
        val payload = mapOf(
            "userId" to uid,
            "eventType" to eventType,
            "idempotencyKey" to idempotencyKey,
            "referenceId" to referenceId.take(256),
            "verificationPayload" to verificationPayload.take(2048),
            "verificationStatus" to "PENDING_SERVER_VERIFICATION",
            "createdAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(idempotencyKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun getUserMonetizationEvents(userId: String = requireUserId()): Result<List<MonetizationVerificationEvent>> {
        val path = "monetization_events"
        return try {
            val snap = db.collection(path)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            val list = snap.toObjects(
                MonetizationVerificationEvent::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }
}
