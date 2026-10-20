const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validProfilePayload(uid, overrides = {}) {
  const now = new Date();
  return {
    userId: uid,
    email: `${uid}@example.com`,
    displayName: "Alice Learner",
    preferredDetailLevel: "STANDARD_NOTES",
    bonusCreditsRemaining: 3,
    dailyFreeCredits: 1,
    lastDailyResetEpochDay: 20740,
    rewardedAdCreditsToday: 0,
    rewardedAdsWatchedToday: 0,
    subscriptionTier: "FREE",
    subscriptionAllowanceRemaining: 0,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validJobPayload(uid, overrides = {}) {
  const now = new Date();
  return {
    userId: uid,
    sourceType: "YOUTUBE",
    sourceUri: "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    videoTitle: "Introduction to Linear Algebra",
    videoDurationSeconds: 1800,
    detailLevel: "DETAILED_STUDY_NOTES",
    status: "VALIDATING",
    progressPercent: 0,
    currentStageDetail: "Validating source and reserving credit",
    creditDeducted: true,
    creditRefunded: false,
    contentHash: "hash_linear_algebra_detailed",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

// --- DIRTY DOZEN & SECURITY BOUNDS TESTS ---

test("1. Unauthenticated user cannot read user profiles or study jobs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("study_jobs").doc("job_1").get());
});

test("2. Cross-user isolation: Bob cannot read Alice's profile or jobs", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
    await ctx.firestore().collection("study_jobs").doc("job_alice").set(validJobPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("study_jobs").doc("job_alice").get());
});

test("3. Authenticated user can initialize own profile ONLY with default free credits", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID))
  );
});

test("4. Client cannot self-grant bonus credits or PRO_STUDENT tier on creation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set(
      validProfilePayload(ALICE_UID, { bonusCreditsRemaining: 999 })
    )
  );
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set(
      validProfilePayload(ALICE_UID, { subscriptionTier: "PRO_STUDENT" })
    )
  );
});

test("5. Client cannot edit own credit balance or subscriptionTier on update", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  // Allowed preference update
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Engineering",
      preferredDetailLevel: "DETAILED_STUDY_NOTES",
      updatedAt: new Date(),
    })
  );

  // Forbidden credit tampering
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      bonusCreditsRemaining: 50,
      updatedAt: new Date(),
    })
  );

  // Forbidden subscription tampering
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      subscriptionTier: "PRO_STUDENT",
      updatedAt: new Date(),
    })
  );
});

test("6. Shadow update with ghost field is rejected", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice",
      isAdmin: true,
      updatedAt: new Date(),
    })
  );
});

test("7. Study jobs: owner can create, query with userId filter, and update active job", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertSucceeds(
    aliceDb.collection("study_jobs").doc("job_1").set(validJobPayload(ALICE_UID))
  );

  await assertSucceeds(
    aliceDb.collection("study_jobs").where("userId", "==", ALICE_UID).get()
  );

  await assertSucceeds(
    aliceDb.collection("study_jobs").doc("job_1").update({
      status: "ANALYZING_SECTIONS",
      progressPercent: 45,
      currentStageDetail: "Analyzing Section 2 of 4",
      updatedAt: new Date(),
    })
  );
});

test("8. Study jobs: query without userId filter is rejected (rules are not filters)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("study_jobs").get());
});

test("9. Study jobs: terminal state locking prevents updates after COMPLETED", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("study_jobs").doc("job_done").set(
      validJobPayload(ALICE_UID, { status: "COMPLETED", progressPercent: 100 })
    );
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("study_jobs").doc("job_done").update({
      status: "VALIDATING",
      progressPercent: 10,
      updatedAt: new Date(),
    })
  );
});

test("10. Monetization events: append-only idempotency enforced, update/delete rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const eventId = "ssv_nonce_001";
  await assertSucceeds(
    aliceDb.collection("monetization_events").doc(eventId).set({
      userId: ALICE_UID,
      eventType: "REWARDED_AD_SSV",
      idempotencyKey: eventId,
      referenceId: "ca-app-pub-3940256099942544/5224354917",
      verificationPayload: "ssv_sig_payload_001",
      verificationStatus: "PENDING_SERVER_VERIFICATION",
      createdAt: new Date(),
    })
  );

  // Cannot replay or mutate existing event
  await assertFails(
    aliceDb.collection("monetization_events").doc(eventId).update({
      verificationStatus: "PENDING_SERVER_VERIFICATION",
    })
  );
  await assertFails(aliceDb.collection("monetization_events").doc(eventId).delete());
});
