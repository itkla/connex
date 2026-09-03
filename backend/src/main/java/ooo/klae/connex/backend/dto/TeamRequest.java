package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Create or replace payload for a workspace team.
 *
 * @param name required team name
 * @param description optional team description
 * @param managerUserId optional active workspace member who manages the team
 */
public record TeamRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1000) String description,
        @Positive Integer managerUserId) {
}
