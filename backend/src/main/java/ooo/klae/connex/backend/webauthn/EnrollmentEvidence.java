package ooo.klae.connex.backend.webauthn;

import ooo.klae.connex.backend.session.StepUpProof;

/**
 * What the enrolling session proves about where a new passkey's privileged coverage may come
 * from (#1534). Registration re-checks both under the account lock before relying on them.
 *
 * @param sessionPrimaryId the enrolling session's stable {@code SPRING_SESSION.PRIMARY_ID}, or
 *     null when the session is not stored
 * @param stepUpProof the passkey behind the session's fresh step-up, or null when there is none
 */
public record EnrollmentEvidence(String sessionPrimaryId, StepUpProof stepUpProof) {
}
