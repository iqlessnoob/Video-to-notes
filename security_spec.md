# Security Specification — Video to Study PDF

## 1. Data Invariants
1. **Multi-Tenant User Isolation**: Every document in `/users/{userId}`, `/study_jobs/{jobId}`, and `/monetization_events/{eventId}` is strictly owned by `request.auth.uid`. Cross-user reads, lists, or writes are rejected.
2. **Server-Controlled Credit & Subscription Ledger**: Clients may initialize `/users/{userId}` ONLY with baseline defaults (`bonusCreditsRemaining == 3`, `dailyFreeCredits == 1`, `rewardedAdCreditsToday == 0`, `rewardedAdsWatchedToday == 0`, `subscriptionTier == 'FREE'`, `subscriptionAllowanceRemaining == 0`). Clients are forbidden from mutating credit balances or subscription tiers via client-side `update` operations.
3. **Terminal Job Locking**: Once a `/study_jobs/{jobId}` document reaches `'COMPLETED'`, `'FAILED'`, or `'CANCELLED'`, further client updates are blocked.
4. **Append-Only Idempotent Monetization Ledger**: `/monetization_events/{eventId}` enforces `eventId == incoming().idempotencyKey` on `create` and forbids `update` and `delete`, preventing replay attacks on rewarded ad callbacks or purchase tokens.

## 2. The Dirty Dozen Payloads
1. Unauthenticated read on `/users/alice_123` -> Reject (`PERMISSION_DENIED`)
2. Cross-user read by Bob on `/users/alice_123` -> Reject (`PERMISSION_DENIED`)
3. Profile creation with self-granted `bonusCreditsRemaining: 999` -> Reject (`PERMISSION_DENIED`)
4. Profile creation with self-granted `subscriptionTier: "PRO_STUDENT"` -> Reject (`PERMISSION_DENIED`)
5. Profile update attempting to modify `bonusCreditsRemaining` or `subscriptionTier` -> Reject (`PERMISSION_DENIED`)
6. Profile update with ghost field `isAdmin: true` -> Reject (`PERMISSION_DENIED`)
7. Unfiltered list query on `/study_jobs` without `where("userId", "==", uid)` -> Reject (`PERMISSION_DENIED`)
8. Cross-user job creation where `userId != request.auth.uid` -> Reject (`PERMISSION_DENIED`)
9. Job update after status is already `'COMPLETED'` (terminal state bypass) -> Reject (`PERMISSION_DENIED`)
10. Job update attempting to mutate `creditDeducted` or `creditRefunded` from client -> Reject (`PERMISSION_DENIED`)
11. Monetization event creation where `eventId != idempotencyKey` -> Reject (`PERMISSION_DENIED`)
12. Monetization event update or delete (replay / tamper attempt) -> Reject (`PERMISSION_DENIED`)
