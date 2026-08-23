package ooo.klae.connex.backend.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Declared query scope carried by one assistant turn or scope preview.
 *
 * <p>Every field is a <em>request</em> filter the server validates, authorizes, and then applies to
 * the retrieval it performs, so the scope the caller states and the scope Ask Connex executes cannot
 * diverge. It is never an access boundary on its own: owner selection narrows results within the
 * workspace the caller already has, exactly as the records browser member scope does.
 *
 * @param periodStart inclusive ISO-8601 local start date, or null
 * @param periodEnd inclusive ISO-8601 local end date, or null
 * @param periodDays trailing window in days used when explicit dates are absent
 * @param ownerMode {@code all_team}, {@code me}, or {@code members}
 * @param ownerMemberIds selected workspace member ids, required by {@code members}
 * @param warmthBands relationship warmth bands to include
 * @param recordKinds record kinds the scope covers
 * @param stageIds deal stage ids to include
 * @param dealStatuses deal statuses to include
 * @param activityTypes activity types to include
 * @param savedViewId accessible saved view whose segment definition bounds the cohort
 */
public record AiChatQueryScopeRequest(
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
        String periodStart,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}")
        String periodEnd,
        @Min(1)
        @Max(365)
        Integer periodDays,
        @Pattern(regexp = "all_team|me|members")
        String ownerMode,
        @Size(max = 50)
        List<@NotNull @Min(1) Integer> ownerMemberIds,
        @Size(max = 4)
        List<@NotNull @Pattern(regexp = "hot|warm|cool|cold") String> warmthBands,
        @Size(max = 3)
        List<@NotNull @Pattern(regexp = "person|company|deal") String> recordKinds,
        @Size(max = 20)
        List<@NotNull @Min(1) Integer> stageIds,
        @Size(max = 3)
        List<@NotNull @Pattern(regexp = "open|won|lost") String> dealStatuses,
        @Size(max = 10)
        List<@NotNull @Size(min = 1, max = 32) String> activityTypes,
        @Min(1)
        Integer savedViewId) {

    public AiChatQueryScopeRequest {
        ownerMemberIds = ownerMemberIds == null ? List.of() : List.copyOf(ownerMemberIds);
        warmthBands = warmthBands == null ? List.of() : List.copyOf(warmthBands);
        recordKinds = recordKinds == null ? List.of() : List.copyOf(recordKinds);
        stageIds = stageIds == null ? List.of() : List.copyOf(stageIds);
        dealStatuses = dealStatuses == null ? List.of() : List.copyOf(dealStatuses);
        activityTypes = activityTypes == null ? List.of() : List.copyOf(activityTypes);
    }
}
