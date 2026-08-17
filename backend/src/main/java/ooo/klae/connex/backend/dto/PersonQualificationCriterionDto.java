package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;

import ooo.klae.connex.backend.beans.QualificationAnswer;
import ooo.klae.connex.backend.beans.QualificationDimension;

/**
 * One active criterion paired with this contact's answer to it (#559).
 *
 * @param criterionId criterion being answered
 * @param label the question as the workspace wrote it
 * @param dimension dimension the criterion scores against
 * @param weight contribution within the dimension
 * @param required whether the criterion gates qualification
 * @param answer the recorded answer, or {@code null} when the question has not been put
 * @param answeredById acting user who last answered
 * @param answeredAt when the answer was last recorded
 */
public record PersonQualificationCriterionDto(
    int criterionId,
    String label,
    QualificationDimension dimension,
    int weight,
    boolean required,
    QualificationAnswer answer,
    Integer answeredById,
    LocalDateTime answeredAt
) { }
