package ooo.klae.connex.backend.dto;

/**
 * Workspace-authorized business-card operation readiness.
 *
 * @param scanning whether automatic extraction can currently be attempted
 * @param importing whether reviewed values and the sanitized source image can be imported
 */
public record BusinessCardAvailabilityResponse(
        boolean scanning,
        boolean importing) {
}
