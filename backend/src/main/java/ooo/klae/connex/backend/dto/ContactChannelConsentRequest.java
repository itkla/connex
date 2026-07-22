package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Consent state to set for the person identified by the route.
 * @param channel contact channel
 * @param purpose consent purpose token
 * @param status granted, revoked, or unknown
 * @param source capture source
 * @param evidenceRef optional evidence reference
 * @param capturedAt optional capture timestamp
 */
public record ContactChannelConsentRequest(
        @NotBlank @Pattern(regexp = "email|sms|line|whatsapp") String channel,
        @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{0,31}") String purpose,
        @NotBlank @Pattern(regexp = "granted|revoked|unknown") String status,
        @NotBlank @Size(max = 64) String source,
        @Size(max = 255) String evidenceRef,
        LocalDateTime capturedAt) {
}
