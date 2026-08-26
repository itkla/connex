package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotNull;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.QualificationAnswer;

/**
 * A requested answer to one qualification criterion (#559). A {@code null} answer clears the
 * criterion back to unanswered, which is how a team retracts an assessment it can no longer stand
 * behind without pretending it was never made.
 */
@Data
@NoArgsConstructor
public class PersonQualificationRequest {

    @NotNull
    private Integer criterionId;

    private QualificationAnswer answer;
}
