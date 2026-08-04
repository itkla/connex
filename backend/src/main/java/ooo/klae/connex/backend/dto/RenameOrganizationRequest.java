package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Organization display-name mutation; the organization slug remains immutable. */
@Data
@NoArgsConstructor
public class RenameOrganizationRequest {
    @NotBlank
    @Size(max = 128)
    private String name;
}
