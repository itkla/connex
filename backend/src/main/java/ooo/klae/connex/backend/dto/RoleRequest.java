package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * Request body for creating or updating a custom role: a name plus the permission
 * catalog keys it grants. {@code acknowledgeConfinement} accepts that an update may leave
 * assignees privileged without a passkey approved in the organization (#1534).
 */
@Data
public class RoleRequest {
    @NotBlank
    @Size(max = 64)
    private String name;

    private List<String> permissions;
    private boolean acknowledgeConfinement;
}
