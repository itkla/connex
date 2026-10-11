package ooo.klae.connex.backend.delivery;

/**
 * Non-sendable claim identity and replay capability, containing no credential or password.
 *
 * @param providerId the installed provider adapter id
 * @param attemptTargetFingerprint the non-secret target identity an attempt is claimed against
 * @param idempotentSubmission whether the provider deduplicates a replayed submission
 */
public record DeliveryClaimTarget(
        String providerId, String attemptTargetFingerprint, boolean idempotentSubmission) {
}
