package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

/**
 * Current contact-channel consent state.
 * @param id consent id
 * @param personId person id
 * @param channel contact channel
 * @param purpose consent purpose
 * @param status current state
 * @param source capture source
 * @param evidenceRef optional evidence reference
 * @param capturedAt optional capture timestamp
 * @param updatedAt last update timestamp
 */
public record ContactChannelConsentDto(
        int id,
        int personId,
        String channel,
        String purpose,
        String status,
        String source,
        String evidenceRef,
        LocalDateTime capturedAt,
        LocalDateTime updatedAt) {
}
