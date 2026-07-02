package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Request body to confirm a verified email change, carrying the raw token from
 * the verification link. The token is excluded from {@code toString} so it never
 * lands in logs.
 */
@Data
@NoArgsConstructor
@ToString(exclude = {"token"})
public class EmailChangeConfirmDto {

    @NotBlank
    private String token;
}
