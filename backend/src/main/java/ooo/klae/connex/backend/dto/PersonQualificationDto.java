package ooo.klae.connex.backend.dto;

import java.util.List;

/**
 * A contact's full qualification picture (#559): every active criterion with the answer recorded
 * against it, and the deterministic score for each dimension.
 *
 * <p>{@code qualifiable} is the server's own verdict on whether the required criteria are satisfied,
 * so a client renders the qualify action from the same rule the transition enforces instead of
 * reimplementing it and drifting.
 *
 * @param criteria active criteria with this contact's answers, in configured order
 * @param scores one entry per dimension
 * @param qualifiable whether every required criterion is met
 */
public record PersonQualificationDto(
    List<PersonQualificationCriterionDto> criteria,
    List<QualificationScoreDto> scores,
    boolean qualifiable
) { }
