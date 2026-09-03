package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Payload for adding or replacing a team seat.
 *
 * @param userId active workspace member receiving the seat
 * @param role {@code member} or {@code manager}
 */
public record TeamMemberRequest(
        @Positive int userId,
        @NotBlank @Pattern(regexp = "member|manager") String role) {
}
