package ooo.klae.connex.backend.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The exact scope the server interpreted from a declared assistant query scope.
 *
 * <p>This is the truthful echo the cockpit renders as correctable chips and the scope preview
 * states as breadth. Every value here is what the retrieval will actually use, including the caps
 * it will apply; anything the caller asked for that the server could not honour appears in
 * {@code unavailable} rather than being silently dropped.
 *
 * <p>Property inclusion is pinned to ALWAYS because the browser distinguishes "no owner filter"
 * from "owner filter unknown" and the application-wide {@code non_null} inclusion would otherwise
 * erase that difference for {@code matchedRecordCount} and {@code savedView}.
 *
 * @param declared whether the caller supplied any scope at all
 * @param periodStart inclusive resolved ISO-8601 local start date, or null when unbounded
 * @param periodEnd inclusive resolved ISO-8601 local end date, or null when unbounded
 * @param periodDays resolved trailing window in days, or null when unbounded
 * @param ownerMode resolved ownership mode
 * @param owners resolved active workspace members named by {@code members} mode
 * @param warmthBands resolved warmth bands
 * @param recordKinds resolved record kinds
 * @param stages resolved deal stages
 * @param dealStatuses resolved deal statuses
 * @param activityTypes resolved activity types
 * @param savedView resolved accessible saved view, or null
 * @param matchedRecordCount records the interpreted cohort matched, or null when not evaluated
 * @param matchedRecordCountTruncated whether the cohort exceeded {@code recordCap}
 * @param recordCap maximum records the retrieval will read
 * @param activityCap maximum activity rows the retrieval will return
 * @param perRecordCap maximum activity rows returned for any single record
 * @param unavailable stable reasons a requested filter could not be applied
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiChatQueryScopeDto(
        boolean declared,
        String periodStart,
        String periodEnd,
        Integer periodDays,
        String ownerMode,
        List<AiChatScopeReferenceDto> owners,
        List<String> warmthBands,
        List<String> recordKinds,
        List<AiChatScopeReferenceDto> stages,
        List<String> dealStatuses,
        List<String> activityTypes,
        AiChatScopeReferenceDto savedView,
        Integer matchedRecordCount,
        boolean matchedRecordCountTruncated,
        int recordCap,
        int activityCap,
        int perRecordCap,
        List<String> unavailable) {

    public AiChatQueryScopeDto {
        owners = owners == null ? List.of() : List.copyOf(owners);
        warmthBands = warmthBands == null ? List.of() : List.copyOf(warmthBands);
        recordKinds = recordKinds == null ? List.of() : List.copyOf(recordKinds);
        stages = stages == null ? List.of() : List.copyOf(stages);
        dealStatuses = dealStatuses == null ? List.of() : List.copyOf(dealStatuses);
        activityTypes = activityTypes == null ? List.of() : List.copyOf(activityTypes);
        unavailable = unavailable == null ? List.of() : List.copyOf(unavailable);
    }

    /**
     * Returns the same interpretation with every projected display label removed.
     *
     * <p>This is the form durable turn scope is stored in. A workspace member erased by account
     * offboarding must not remain named inside a stored assistant turn, and a saved view renamed
     * after the turn ran must not be restated under its old name, so identifiers are stored and
     * labels are re-resolved on read under the reader's own authorization.
     */
    public AiChatQueryScopeDto withoutLabels() {
        return new AiChatQueryScopeDto(
                declared, periodStart, periodEnd, periodDays, ownerMode,
                owners.stream()
                        .map(owner -> new AiChatScopeReferenceDto(owner.id(), ""))
                        .toList(),
                warmthBands, recordKinds,
                stages.stream()
                        .map(stage -> new AiChatScopeReferenceDto(stage.id(), ""))
                        .toList(),
                dealStatuses, activityTypes,
                savedView == null ? null : new AiChatScopeReferenceDto(savedView.id(), ""),
                matchedRecordCount, matchedRecordCountTruncated,
                recordCap, activityCap, perRecordCap, unavailable);
    }

    /** Returns the same interpreted scope carrying an evaluated cohort size. */
    public AiChatQueryScopeDto withMatchedRecords(int count, boolean truncated) {
        return new AiChatQueryScopeDto(
                declared, periodStart, periodEnd, periodDays, ownerMode, owners,
                warmthBands, recordKinds, stages, dealStatuses, activityTypes, savedView,
                count, truncated, recordCap, activityCap, perRecordCap, unavailable);
    }
}
