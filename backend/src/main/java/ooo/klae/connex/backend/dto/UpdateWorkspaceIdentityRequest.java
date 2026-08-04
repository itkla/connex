package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Complete mutable workspace identity submitted to the workspace settings endpoint.
 * Expected values provide a content precondition; a null timezone explicitly clears
 * the workspace override.
 */
@Data
@NoArgsConstructor
public class UpdateWorkspaceIdentityRequest {
    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 64)
    private String timezone;

    @NotBlank
    @Size(max = 128)
    private String expectedName;

    @Size(max = 64)
    private String expectedTimezone;
}
