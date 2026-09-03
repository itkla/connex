package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.PersonDisqualificationReason;

/**
 * Editable fields for one disqualification reason (#559).
 *
 * <p>{@code code} must already be canonical uppercase ASCII matching
 * {@code ^[A-Z][A-Z0-9_]{1,31}$}; the API rejects rather than normalizes lower-case, padded, or
 * accented values.
 */
@Data
@NoArgsConstructor
public class DisqualificationReasonRequest {
    @NotBlank
    @Pattern(regexp = PersonDisqualificationReason.CODE_PATTERN)
    private String code;

    @Size(max = 200)
    private String label;

    private Boolean requiresNote;

    @Min(0)
    private Integer position;
}
