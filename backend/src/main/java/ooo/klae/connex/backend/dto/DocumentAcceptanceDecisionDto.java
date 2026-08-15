package ooo.klae.connex.backend.dto;

/** Idempotent result of a public recipient decision. */
public record DocumentAcceptanceDecisionDto(
        String deliveryStatus,
        String recipientStatus,
        boolean completed) {
}
