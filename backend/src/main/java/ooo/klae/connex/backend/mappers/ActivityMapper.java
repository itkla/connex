package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.ai.assistant.AiAssistantScopeActivity;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.HistoryImportProvenance;
import ooo.klae.connex.backend.beans.HistoryImportWrite;
import ooo.klae.connex.backend.dto.ActivityVolumeBucketDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.TeamLeaderboardEntryDto;
import ooo.klae.connex.backend.util.AnalyticsPeriods.AnalyticsPeriod;

import java.time.LocalDateTime;
import java.util.List;

/**
 * mapper interface for {@code Activity} persistence.
 * Used by {@code ActivityService}.
 */

public interface ActivityMapper {
    List<Activity> getAllActivities(int workspaceId);
    List<Activity> getActivitiesPage(@Param("workspaceId") int workspaceId, @Param("limit") int limit, @Param("offset") int offset);
    List<Activity> getActivitiesFilteredPage(
        @Param("workspaceId") int workspaceId,
        @Param("personId") Integer personId,
        @Param("dealId") Integer dealId,
        @Param("createdById") Integer createdById,
        @Param("limit") int limit,
        @Param("offset") int offset);
    long countActivities(
        @Param("workspaceId") int workspaceId,
        @Param("personId") Integer personId,
        @Param("dealId") Integer dealId,
        @Param("createdById") Integer createdById);
    List<ActivityVolumeBucketDto> activityVolume(
        @Param("workspaceId") int workspaceId,
        @Param("days") int days,
        @Param("buckets") int buckets,
        @Param("spanDays") double spanDays,
        @Param("memberScope") MemberScope memberScope
    );
    List<ActivityVolumeBucketDto> activityVolumeByBoundaries(
        @Param("workspaceId") int workspaceId,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("periods") List<AnalyticsPeriod> periods,
        @Param("memberScope") MemberScope memberScope
    );
    List<TeamLeaderboardEntryDto> teamLeaderboard(
        @Param("workspaceId") int workspaceId,
        @Param("days") int days
    );
    List<TeamLeaderboardEntryDto> teamLeaderboardWindow(
        @Param("workspaceId") int workspaceId,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc
    );
    long upcomingCount(@Param("workspaceId") int workspaceId, @Param("days") int days);
    List<Activity> getActivitiesByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Activity> getAiAssistantActivitiesByPersonId(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("limit") int limit);
    List<Activity> getActivitiesByPersonIdInWindow(
        @Param("workspaceId") int workspaceId,
        @Param("personId") int personId,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("limit") int limit);
    /**
     * Reads recent activity across a bounded cohort with both the per-record and the total limit
     * applied inside the query, so a broad request never materializes a workspace's whole history
     * before discarding it.
     *
     * @param workspaceId active workspace
     * @param organizationWorkspaceIds workspaces whose shared people remain readable here
     * @param cohortKind {@code person}, {@code company}, or {@code deal}
     * @param recordIds bounded cohort record ids
     * @param startUtc inclusive lower time bound, or null
     * @param endUtc inclusive upper time bound, or null
     * @param types lowercase activity types to include, or null for every type
     * @param honorRestrictions whether processing-restricted people are excluded
     * @param perRecordLimit maximum rows returned for any single cohort record
     * @param limit maximum rows returned overall
     * @return newest-first activity rows already attributed to their cohort record
     */
    List<AiAssistantScopeActivity> getAiAssistantScopeActivities(
        @Param("workspaceId") int workspaceId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("cohortKind") String cohortKind,
        @Param("recordIds") List<Integer> recordIds,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("types") List<String> types,
        @Param("honorRestrictions") boolean honorRestrictions,
        @Param("perRecordLimit") int perRecordLimit,
        @Param("limit") int limit);

    /**
     * Counts every activity the same cohort predicate matches, before either limit is applied, so a
     * bounded answer can state the true size of what it sampled.
     *
     * @param workspaceId active workspace
     * @param organizationWorkspaceIds workspaces whose shared people remain readable here
     * @param cohortKind {@code person}, {@code company}, or {@code deal}
     * @param recordIds bounded cohort record ids
     * @param startUtc inclusive lower time bound, or null
     * @param endUtc inclusive upper time bound, or null
     * @param types lowercase activity types to include, or null for every type
     * @param honorRestrictions whether processing-restricted people are excluded
     * @return matching activity count
     */
    long countAiAssistantScopeActivities(
        @Param("workspaceId") int workspaceId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("cohortKind") String cohortKind,
        @Param("recordIds") List<Integer> recordIds,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("types") List<String> types,
        @Param("honorRestrictions") boolean honorRestrictions);

    List<Activity> getActivitiesByPersonIds(@Param("workspaceId") int workspaceId,
            @Param("personIds") List<Integer> personIds);
    List<Activity> getActivitiesByDealId(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    List<Activity> getAiAssistantActivitiesByDealId(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("limit") int limit);
    List<Activity> getActivitiesByCreatedById(@Param("workspaceId") int workspaceId, @Param("createdById") int createdById);
    List<Activity> getCompanyActivities(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") int limit);
    List<Activity> getAiAssistantActivitiesByCompanyId(
        @Param("workspaceId") int workspaceId,
        @Param("companyId") int companyId,
        @Param("organizationWorkspaceIds") List<Integer> organizationWorkspaceIds,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("limit") int limit);
    List<Activity> getActivitiesByDealCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    Activity getActivityById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Activity getActivityByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Integer> getVisibleIdsIn(
        @Param("workspaceId") int workspaceId,
        @Param("ids") List<Integer> ids
    );
    List<Activity> search(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("limit") int limit,
        @Param("offset") int offset
    );
    int insert(Activity activity);
    List<HistoryImportProvenance> findHistoryImports(
        @Param("workspaceId") int workspaceId,
        @Param("historyImportKeys") List<String> historyImportKeys
    );
    int insertHistoryBatch(
        @Param("workspaceId") int workspaceId,
        @Param("rows") List<HistoryImportWrite> rows
    );
    int update(Activity activity);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Counts activities created by a user across all workspaces. Service-layer
     * mirror of the {@code activity.created_by_id} ON DELETE RESTRICT (#440
     * increment 3).
     */
    int countCreatedAnywhere(@Param("userId") int userId);
}
