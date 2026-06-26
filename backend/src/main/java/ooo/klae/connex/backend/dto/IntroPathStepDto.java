package ooo.klae.connex.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One contact along a warm-introduction path.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntroPathStepDto {
    private int personId;
    private String personName;
    private String companyName;
    /** Type of the connection linking this step to the previous one; {@code null} for the entry point. */
    private String connectionType;
    /** Whether the team already engages this contact (a possible entry point). */
    private boolean engaged;
}
