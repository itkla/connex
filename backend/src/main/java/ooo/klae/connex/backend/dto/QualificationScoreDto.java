package ooo.klae.connex.backend.dto;

import java.util.List;

import ooo.klae.connex.backend.beans.QualificationDimension;

/**
 * A contact's deterministic score on one qualification dimension (#559).
 *
 * <p>{@code percent} is {@code null} when the workspace has configured no criteria for the
 * dimension. That is not the same as zero: a workspace that never defined a fit question has not
 * decided the contact is a bad fit, and rendering 0% would invent an assessment nobody made.
 *
 * <p>{@code unansweredCount} is reported separately rather than folded into the score. An
 * unanswered criterion is not evidence of fitness, so it never lifts the percentage; but a contact
 * scoring 40% with every question answered and one scoring 40% with half the list untouched are
 * different situations, and the caller is told which it is looking at.
 *
 * @param dimension dimension being scored
 * @param percent whole-percent score, or {@code null} when no criteria are configured
 * @param metWeight summed weight of the criteria this contact meets
 * @param totalWeight summed weight of every active criterion in the dimension
 * @param unansweredCount active criteria with no answer recorded
 * @param unmetRequiredLabels labels of required criteria this contact does not yet meet
 */
public record QualificationScoreDto(
    QualificationDimension dimension,
    Integer percent,
    int metWeight,
    int totalWeight,
    int unansweredCount,
    List<String> unmetRequiredLabels
) { }
