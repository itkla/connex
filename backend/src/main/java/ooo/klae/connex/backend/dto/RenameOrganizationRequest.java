package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Versioned organization display-name mutation; the organization slug remains immutable. */
@Data
@NoArgsConstructor
public class RenameOrganizationRequest {
    @NotBlank
    @Size(max = 128)
    private String name;

    @NotBlank
    @Size(max = 128)
    private String expectedName;

    @NotNull
    @PositiveOrZero
    private Long expectedIdentityVersion;
}
