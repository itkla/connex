package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Complete mutable workspace identity submitted to the workspace settings endpoint.
 * The name is required; a null timezone explicitly clears the workspace override.
 */
@Data
@NoArgsConstructor
public class UpdateWorkspaceIdentityRequest {
    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 64)
    private String timezone;
}
