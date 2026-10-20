package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.StudyDetailLevel
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun initializeUserProfile_authenticatedOwner_setsDefaultThreeBonusAndOneDailyCredit() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = StudyFirestoreRepository(firestore)

        val result = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.initializeUserProfileIfNeeded(ALICE_EMAIL, "Alice Student")
        }
        assertTrue(result.isSuccess)
        val profile = result.getOrThrow()
        assertEquals(uid, profile.userId)
        assertEquals(3L, profile.bonusCreditsRemaining)
        assertEquals(1L, profile.dailyFreeCredits)
        assertEquals("FREE", profile.subscriptionTier)
    }

    @Test
    fun clientCannotDirectlyTamperWithCreditBalanceOrSubscriptionTier() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = StudyFirestoreRepository(firestore)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.initializeUserProfileIfNeeded(ALICE_EMAIL, "Alice Student").getOrThrow()
        }

        // Allowed preference update succeeds
        val prefUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.updateUserPreferences("Alice Updated", StudyDetailLevel.DETAILED_STUDY_NOTES)
        }
        assertTrue(prefUpdate.isSuccess)

        // Direct attempt to tamper with bonusCreditsRemaining on Firestore document is rejected by rules
        var rejectedAsExpected = false
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid)
                    .update("bonusCreditsRemaining", 999L)
                    .await()
            }
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
            rejectedAsExpected = true
        }
        assertTrue("Expected PERMISSION_DENIED when client edits credit balance", rejectedAsExpected)
    }

    @Test
    fun createAndObserveStudyJobs_authenticatedOwner_succeedsAndAlignsWithRules() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val repo = StudyFirestoreRepository(firestore)
        val uniqueJobId = "job_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.createStudyJob(
                jobId = uniqueJobId,
                sourceType = "YOUTUBE",
                sourceUri = "https://www.youtube.com/watch?v=abc123xyz00",
                videoTitle = "Thermodynamics Lecture 1",
                videoDurationSeconds = 1200L,
                detailLevel = StudyDetailLevel.STANDARD_NOTES.id,
                contentHash = "hash_thermo_1",
                creditDeducted = true
            )
        }
        assertTrue(createResult.isSuccess)

        val jobs = withTimeout(FLOW_TIMEOUT_MS) {
            repo.observeUserStudyJobs(uid).first { list -> list.any { it.id == uniqueJobId } }
        }
        assertTrue(jobs.any { it.id == uniqueJobId })
    }

    @Test
    fun crossUserStudyJobAccess_failsWithPermissionDenied() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val aliceRepo = StudyFirestoreRepository(firestore)
        val uniqueJobId = "job_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createStudyJob(
                jobId = uniqueJobId,
                sourceType = "YOUTUBE",
                sourceUri = "https://www.youtube.com/watch?v=abc123xyz00",
                videoTitle = "Alice Private Job",
                videoDurationSeconds = 900L,
                detailLevel = StudyDetailLevel.QUICK_SUMMARY.id,
                contentHash = "hash_alice_priv",
                creditDeducted = true
            ).getOrThrow()
        }

        // Switch to Bob
        signInTestUser(BOB_EMAIL)
        val bobRepo = StudyFirestoreRepository(firestore)
        val bobReadResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getStudyJobById(uniqueJobId)
        }
        assertTrue(bobReadResult.isFailure)
        val exception = bobReadResult.exceptionOrNull() as? FirebaseFirestoreException
        assertNotNull(exception)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, exception?.code)
    }

    @Test
    fun unauthenticatedStudyJobObservation_failsWithPermissionDenied() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        auth.signOut()
        val repo = StudyFirestoreRepository(firestore)

        var denied = false
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeUserStudyJobs(uid).first()
            }
        } catch (e: FirebaseFirestoreException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, e.code)
            denied = true
        }
        assertTrue("Unauthenticated query must fail with PERMISSION_DENIED", denied)
    }

    @Test
    fun recordMonetizationEvent_appendOnlyIdempotency_rejectsDuplicateReplay() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repo = StudyFirestoreRepository(firestore)
        val nonce = "ssv_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        val firstRecord = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.recordMonetizationEvent(
                eventType = "REWARDED_AD_SSV",
                idempotencyKey = nonce,
                referenceId = "ca-app-pub-3940256099942544/5224354917",
                verificationPayload = "verified_ssv_token_payload"
            )
        }
        assertTrue(firstRecord.isSuccess)

        // Attempting to overwrite/replay the same idempotencyKey fails because update is forbidden
        val replayAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            repo.recordMonetizationEvent(
                eventType = "REWARDED_AD_SSV",
                idempotencyKey = nonce,
                referenceId = "ca-app-pub-3940256099942544/5224354917",
                verificationPayload = "replayed_payload"
            )
        }
        assertTrue(replayAttempt.isFailure)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_rules@test.com"
        const val BOB_EMAIL = "bob_rules@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
