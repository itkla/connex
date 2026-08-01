package ooo.klae.connex.backend.mappers;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.ReportDefinition;
import ooo.klae.connex.backend.beans.ReportSnapshot;
import ooo.klae.connex.backend.dto.ReportAggregateQuery;
import ooo.klae.connex.backend.dto.ReportAggregateRow;
import ooo.klae.connex.backend.dto.ReportForecastAggregateRow;
import ooo.klae.connex.backend.dto.ReportNetworkAccountRow;
import ooo.klae.connex.backend.dto.ReportSnapshotSummaryDto;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel.SqlParameters;

/**
 * Data access for workspace-shared report definitions and frozen snapshots.
 * Every request-path statement is workspace-scoped. SQL lives in
 * {@code resources/mappers/ReportMapper.xml}.
 */
public interface ReportMapper {

    List<ReportDefinition> getDefinitions(@Param("workspaceId") int workspaceId);

    List<Integer> lockDefinitions(@Param("workspaceId") int workspaceId);

    int countDefinitions(@Param("workspaceId") int workspaceId);

    ReportDefinition getDefinition(@Param("workspaceId") int workspaceId, @Param("id") int id);

    void insertDefinition(ReportDefinition definition);

    int updateDefinition(ReportDefinition definition);

    int deleteDefinition(@Param("workspaceId") int workspaceId, @Param("id") int id);

    List<ReportSnapshotSummaryDto> getSnapshots(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId,
        @Param("limit") int limit);

    int countManualSnapshots(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId);

    int countSnapshots(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId);

    int countSnapshotsNotGeneratedBy(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId,
        @Param("generatedBy") int generatedBy);

    int countScheduledSnapshots(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId);

    int countWorkspaceSnapshots(@Param("workspaceId") int workspaceId);

    long workspaceSnapshotBytes(@Param("workspaceId") int workspaceId);

    ReportSnapshot getSnapshot(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId,
        @Param("id") int id);

    void insertSnapshot(ReportSnapshot snapshot);

    int deleteScheduledSnapshotsBeyondRetention(
        @Param("workspaceId") int workspaceId,
        @Param("reportScheduleId") int reportScheduleId,
        @Param("reportDefinitionId") int reportDefinitionId,
        @Param("keepCount") int keepCount);

    int deleteOldestScheduledSnapshots(
        @Param("workspaceId") int workspaceId,
        @Param("limit") int limit);

    int deleteOrphanedScheduledSnapshots(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId);

    int deleteSnapshot(
        @Param("workspaceId") int workspaceId,
        @Param("reportDefinitionId") int reportDefinitionId,
        @Param("id") int id);

    List<ReportAggregateRow> aggregateDeals(@Param("query") ReportAggregateQuery query);

    List<ReportForecastAggregateRow> aggregateForecast(@Param("query") ReportAggregateQuery query);

    List<ReportAggregateRow> aggregateActivities(@Param("query") ReportAggregateQuery query);

    List<ReportAggregateRow> aggregateTasks(@Param("query") ReportAggregateQuery query);

    List<ReportAggregateRow> aggregatePeople(@Param("query") ReportAggregateQuery query);

    List<ReportAggregateRow> aggregateEmployment(@Param("query") ReportAggregateQuery query);

    List<ReportAggregateRow> aggregateCompanies(@Param("query") ReportAggregateQuery query);

    /**
     * Aggregates coverage immediately before the report period's exclusive end instant.
     *
     * <p>The strict touch cutoff preserves the report engine's half-open {@code [start, end)}
     * periods. Decay is evaluated at {@code warmthReference}, which callers align with the
     * millisecond-normalized Java snapshot immediately before {@code end}. An interaction exactly
     * at {@code end} belongs to the following period.
     */
    List<ReportAggregateRow> aggregateCoverageGaps(
        @Param("query") ReportAggregateQuery query,
        @Param("warmthReference") java.time.LocalDateTime warmthReference,
        @Param("model") SqlParameters model
    );

    List<ReportAggregateRow> aggregateSingleThreadedDeals(@Param("query") ReportAggregateQuery query);

    List<ReportNetworkAccountRow> getNetworkAccountValues(
        @Param("query") ReportAggregateQuery query,
        @Param("limit") int limit);

    List<Integer> getVisiblePersonIdsAt(
        @Param("workspaceId") int workspaceId,
        @Param("asOf") java.time.LocalDateTime asOf);

    List<Integer> getVisibleCompanyIdsAt(
        @Param("workspaceId") int workspaceId,
        @Param("asOf") java.time.LocalDateTime asOf);

    /** Clears report-definition creator references during account deletion. */
    void clearDefinitionCreatorsAnywhere(@Param("userId") int userId);

    /** Clears report-snapshot generator references during account deletion. */
    void clearSnapshotGeneratorsAnywhere(@Param("userId") int userId);
}
