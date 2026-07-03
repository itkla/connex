package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for renaming an enrolled passkey.
 */
@Data
@NoArgsConstructor
public class RenamePasskeyRequest {

    @NotBlank
    @Size(max = 255)
    private String label;
}
