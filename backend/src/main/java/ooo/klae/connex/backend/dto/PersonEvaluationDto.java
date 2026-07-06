package ooo.klae.connex.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Engine-evaluation opt-outs for a contact (issue #358). Either flag may be omitted to leave it
 * unchanged; {@code riskExcluded} governs relationship-decay nudges and the stakeholder-cold deal
 * risk contribution, {@code introExcluded} governs introduction suggestions.
 */
@Data
@NoArgsConstructor
public class PersonEvaluationDto {
    private Boolean riskExcluded;
    private Boolean introExcluded;
}
