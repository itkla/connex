package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * Request body for creating a new workspace.
 */
@Data
public class CreateWorkspaceRequest {
    @NotBlank
    @Size(max = 128)
    private String name;
}
