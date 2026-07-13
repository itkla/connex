package ooo.klae.connex.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Validated bound parameters for one deterministic report aggregation.
 * @param workspaceId active workspace
 * @param measure allowlisted measure key
 * @param groupBy allowlisted grouping key
 * @param bucket allowlisted date bucket key
 * @param startUtc inclusive period start
 * @param endUtc exclusive period end
 * @param startDate inclusive local calendar start for SQL DATE measures
 * @param endDateExclusive exclusive local calendar end for SQL DATE measures
 * @param pipelineIds pipeline filters
 * @param ownerIds owner filters
 * @param statuses status filters
 * @param tagIds tag filters
 * @param riskIds deterministic at-risk deal ids
 * @param offsets constant-offset intervals for local calendar bucketing
 */
public record ReportAggregateQuery(
        int workspaceId,
        String measure,
        String groupBy,
        String bucket,
        LocalDateTime startUtc,
        LocalDateTime endUtc,
        LocalDate startDate,
        LocalDate endDateExclusive,
        List<Integer> pipelineIds,
        List<Integer> ownerIds,
        List<String> statuses,
        List<Integer> tagIds,
        List<Integer> riskIds,
        List<ReportOffsetSegment> offsets) {
}
