package ooo.klae.connex.backend.dto;

import tools.jackson.databind.JsonNode;

/**
 * Bounded asynchronous AI generation status.
 * @param handle opaque server-issued polling handle
 * @param kind AI feature wire key
 * @param status accepted, running, resolved, failed, or timed_out
 * @param result typed feature result when resolved
 * @param reason stable terminal reason when failed or timed out
 * @param retryAfterMs recommended delay before the next status read
 * @param pollWindowMs remaining relative lifetime for local monotonic polling
 * @param expiresAt fixed ISO instant after which the handle is unavailable
 */
public record AiGenerationStatusDto(
        String handle,
        String kind,
        String status,
        JsonNode result,
        String reason,
        long retryAfterMs,
        long pollWindowMs,
        String expiresAt) {
}
