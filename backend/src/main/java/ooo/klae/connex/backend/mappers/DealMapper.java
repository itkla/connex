package ooo.klae.connex.backend.mappers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.DealStakeholder;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.BoardPositionUpdate;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealBucketValueDto;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealKpiClosedBucketDto;
import ooo.klae.connex.backend.dto.DealKpiPeriodDto;
import ooo.klae.connex.backend.dto.DealMonthDecimalTotalDto;
import ooo.klae.connex.backend.dto.DealPeriodDecimalTotalDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;
import ooo.klae.connex.backend.dto.DealRevenueMonthBoundary;
import ooo.klae.connex.backend.dto.DealRevenueRangeDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealTouchDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.util.AnalyticsPeriods.AnalyticsPeriod;

/**
 * mapper interface for {@code Deal} persistence.
 * SQL is defined in {@code resources/mappers/DealMapper.xml}.
 * Used by {@code DealService}.
 */

public interface DealMapper {
    List<Deal> getAllDeals(int workspaceId);
    List<Deal> getDealsPage(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("sort") String sort,
        @Param("dir") String dir,
        @Param("currency") String currency,
        @Param("pipelineId") Integer pipelineId,
        @Param("stageId") Integer stageId,
        @Param("companyId") Integer companyId,
        @Param("status") String status,
        @Param("limit") int limit,
        @Param("offset") int offset
    );
    long countDeals(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("currency") String currency,
        @Param("pipelineId") Integer pipelineId,
        @Param("stageId") Integer stageId,
        @Param("companyId") Integer companyId,
        @Param("status") String status
    );
    List<DealCurrencyMetricsDto> dealMetrics(
        @Param("workspaceId") int workspaceId,
        @Param("query") String query,
        @Param("currency") String currency,
        @Param("pipelineId") Integer pipelineId,
        @Param("stageId") Integer stageId,
        @Param("companyId") Integer companyId,
        @Param("status") String status
    );
    List<Deal> getDealsPageFiltered(
        @Param("workspaceId") int workspaceId,
        @Param("segmentIdsJson") String segmentIdsJson,
        @Param("query") String query,
        @Param("sort") String sort,
        @Param("dir") String dir,
        @Param("currency") String currency,
        @Param("pipelineIds") List<Integer> pipelineIds,
        @Param("stageIds") List<Integer> stageIds,
        @Param("companyIds") List<Integer> companyIds,
        @Param("noCompany") boolean noCompany,
        @Param("statuses") List<String> statuses,
        @Param("riskIds") List<Integer> riskIds,
        @Param("memberScope") MemberScope memberScope,
        @Param("limit") int limit,
        @Param("offset") int offset
    );
    long countDealsFiltered(
        @Param("workspaceId") int workspaceId,
        @Param("segmentIdsJson") String segmentIdsJson,
        @Param("query") String query,
        @Param("currency") String currency,
        @Param("pipelineIds") List<Integer> pipelineIds,
        @Param("stageIds") List<Integer> stageIds,
        @Param("companyIds") List<Integer> companyIds,
        @Param("noCompany") boolean noCompany,
        @Param("statuses") List<String> statuses,
        @Param("riskIds") List<Integer> riskIds,
        @Param("memberScope") MemberScope memberScope
    );
    /** Full filtered+scoped deal set for CSV export, mirroring the visible list without pagination. */
    List<Deal> getDealsFiltered(
        @Param("workspaceId") int workspaceId,
        @Param("segmentIdsJson") String segmentIdsJson,
        @Param("query") String query,
        @Param("currency") String currency,
        @Param("pipelineIds") List<Integer> pipelineIds,
        @Param("stageIds") List<Integer> stageIds,
        @Param("companyIds") List<Integer> companyIds,
        @Param("noCompany") boolean noCompany,
        @Param("statuses") List<String> statuses,
        @Param("riskIds") List<Integer> riskIds,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealCurrencyMetricsDto> dealMetricsFiltered(
        @Param("workspaceId") int workspaceId,
        @Param("segmentIdsJson") String segmentIdsJson,
        @Param("query") String query,
        @Param("currency") String currency,
        @Param("pipelineIds") List<Integer> pipelineIds,
        @Param("stageIds") List<Integer> stageIds,
        @Param("companyIds") List<Integer> companyIds,
        @Param("noCompany") boolean noCompany,
        @Param("statuses") List<String> statuses,
        @Param("riskIds") List<Integer> riskIds,
        @Param("memberScope") MemberScope memberScope
    );
    List<Integer> getFilteredDealIds(
        @Param("workspaceId") int workspaceId,
        @Param("segmentIdsJson") String segmentIdsJson,
        @Param("query") String query,
        @Param("currency") String currency,
        @Param("pipelineIds") List<Integer> pipelineIds,
        @Param("stageIds") List<Integer> stageIds,
        @Param("companyIds") List<Integer> companyIds,
        @Param("noCompany") boolean noCompany,
        @Param("statuses") List<String> statuses,
        @Param("riskIds") List<Integer> riskIds,
        @Param("memberScope") MemberScope memberScope,
        @Param("limit") int limit
    );
    DealRevenueRangeDto revenueClosedEventRange(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealMonthDecimalTotalDto> revenueClosedByBoundaries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("boundaries") List<DealRevenueMonthBoundary> boundaries,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealMonthDecimalTotalDto> revenueScheduledClosedByMonth(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealMonthDecimalTotalDto> revenueProjectedByMonth(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealStageDistributionDto> stageDistribution(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    DealKpiPeriodDto dealKpiCurrent(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("memberScope") MemberScope memberScope
    );
    DealKpiPeriodDto dealKpiPrevious(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("previousDays") int previousDays,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealKpiClosedBucketDto> dealKpiClosedSeries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("span") double span,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealBucketValueDto> dealKpiNewPipelineSeries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("span") double span,
        @Param("memberScope") MemberScope memberScope
    );
    DealKpiPeriodDto dealKpiWindow(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealKpiClosedBucketDto> dealKpiClosedSeriesByBoundaries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("periods") List<AnalyticsPeriod> periods,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealBucketValueDto> dealKpiNewPipelineSeriesByBoundaries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("periods") List<AnalyticsPeriod> periods,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealPipelineValueDto> dealPipelineValue(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealPipelineValueDto> dealPipelineValueWindow(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealPeriodDecimalTotalDto> revenueClosedByPeriods(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc,
        @Param("periods") List<AnalyticsPeriod> periods,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealPeriodDecimalTotalDto> revenueScheduledClosedByPeriods(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("periods") List<AnalyticsPeriod> periods,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealPeriodDecimalTotalDto> revenueProjectedByPeriods(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate,
        @Param("periods") List<AnalyticsPeriod> periods,
        @Param("memberScope") MemberScope memberScope
    );
    List<DealAgingDto> dealAging(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    long closingSoonCount(
        @Param("workspaceId") int workspaceId,
        @Param("today") LocalDate today,
        @Param("days") int days
    );
    List<Deal> closingSoonDeals(
        @Param("workspaceId") int workspaceId,
        @Param("today") LocalDate today,
        @Param("days") int days,
        @Param("limit") int limit
    );
    List<Deal> topOpenDeals(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    List<Deal> topWonDeals(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("memberScope") MemberScope memberScope
    );
    List<FacetCount> countsByStatus(@Param("workspaceId") int workspaceId);
    List<FacetCount> countsByStage(@Param("workspaceId") int workspaceId);
    List<FacetCount> countsByPipeline(@Param("workspaceId") int workspaceId);
    List<FacetCount> countsByCompany(@Param("workspaceId") int workspaceId);
    /**
     * Owner facet counts over the whole workspace: like every facet, the owner picker keeps
     * showing all options (with stable counts) while a member scope is applied.
     */
    List<FacetCount> countsByOwner(@Param("workspaceId") int workspaceId);
    List<FacetCount> countsByCurrency(@Param("workspaceId") int workspaceId);
    List<Deal> getDealBoard(
        @Param("workspaceId") int workspaceId,
        @Param("pipelineId") int pipelineId
    );
    List<Deal> getDealsByPipelineId(@Param("workspaceId") int workspaceId, @Param("pipelineId") int pipelineId);
    long countDealsByPipelineId(@Param("workspaceId") int workspaceId, @Param("pipelineId") int pipelineId);
    List<Deal> getDealsByStageId(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    List<Deal> getDealsByCompanyId(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    List<Deal> getDealsByCompanyIdPage(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("limit") int limit);
    List<Deal> getAccountHistoryDeals(@Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId, @Param("excludeDealId") int excludeDealId,
            @Param("limit") int limit);
    List<Deal> getDealsByPersonId(@Param("workspaceId") int workspaceId, @Param("personId") int personId);
    List<Deal> getDealsByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    Deal getDealById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Deal getDealByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** Deals are owned-only already; mirrors the person/company method so bulk write-scoping is uniform. */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Deal> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    List<Deal> findMentionedRecords(
            @Param("workspaceId") int workspaceId,
            @Param("text") String text,
            @Param("limit") int limit);
    /** Bounded candidates for interactive canonical-name and company duplicate rechecking. */
    List<Deal> findDuplicatePreflightCandidates(
        @Param("workspaceId") int workspaceId,
        @Param("normalizedName") String normalizedName,
        @Param("companyId") Integer companyId,
        @Param("limit") int limit);
    /** id + name + company for every deal in the workspace; for import dedup (normalized in the service). */
    List<Deal> getDealsForDedup(int workspaceId);
    /** Deals in the workspace with the given ids (workspace-scoped); for export of a selected view. */
    List<Deal> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    List<Deal> getDealsByCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    List<Integer> getRiskCandidateIds(@Param("workspaceId") int workspaceId,
            @Param("memberScope") MemberScope memberScope,
            @Param("limit") int limit);
    int insert(Deal deal);
    /** Bulk-insert deals in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Deal> deals);
    int update(Deal deal);
    int updateName(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("name") String name,
        @Param("duplicateNormalizedName") String duplicateNormalizedName
    );
    int updateValueAndSource(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("value") BigDecimal value,
        @Param("valueSource") String valueSource
    );
    int updateValueSource(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("valueSource") String valueSource
    );
    int updateActualValue(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("actualValue") BigDecimal actualValue
    );
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Deal ids in a stage, in board order (position, then id), for renumbering a column on a move. */
    List<Integer> getDealIdsInStageOrdered(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    /** The next free tail position in a stage column ({@code MAX(position)+1}, or 0 when empty). */
    int nextDealPosition(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    /** Sets manual sort positions for deals that still belong to the expected workspace stage. */
    int setPositions(
        @Param("workspaceId") int workspaceId,
        @Param("stageId") int stageId,
        @Param("positions") List<BoardPositionUpdate> positions
    );

    /** Sets only a deal's expected close date, scoped to the workspace. */
    int updateExpectedCloseDate(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("expectedCloseDate") String expectedCloseDate
    );

    String getStageOutcome(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);

    Integer getLastNormalStageId(@Param("workspaceId") int workspaceId, @Param("pipelineId") int pipelineId);

    int addTag(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int insertTags(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("tagIds") List<Integer> tagIds
    );

    List<DealPerson> getDealPeopleByDealId(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId
    );
    List<DealPrimaryContactDto> getPrimaryContactsByDealIds(
        @Param("workspaceId") int workspaceId,
        @Param("dealIds") List<Integer> dealIds
    );
    /** Every deal stakeholder in the workspace as {@code (dealId, personId, name, role)} rows; for deal-risk scoring. */
    List<DealStakeholder> getAllDealStakeholders(int workspaceId);
    List<DealStakeholder> getDealStakeholdersByDealIds(
        @Param("workspaceId") int workspaceId,
        @Param("dealIds") List<Integer> dealIds
    );
    List<DealTouchDto> getLatestDealTouches(
        @Param("workspaceId") int workspaceId,
        @Param("dealIds") List<Integer> dealIds,
        @Param("now") String now
    );
    /** Stakeholders for a single deal; the single-deal risk path scopes to this rather than the whole workspace. */
    List<DealStakeholder> getDealStakeholdersByDealId(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId
    );
    int addPerson(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId,
        @Param("role") String role
    );
    int addPersonIfAbsent(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId
    );
    int updatePersonRole(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId,
        @Param("role") String role
    );
    int removePerson(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("personId") int personId
    );
    int clearPeople(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);

    int updateOwner(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("ownerId") Integer ownerId
    );
    /** Targeted update of the deal-risk evaluation opt-out. */
    int updateRiskExcluded(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("riskExcluded") boolean riskExcluded
    );
    List<User> getCollaborators(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int clearCollaborators(@Param("workspaceId") int workspaceId, @Param("dealId") int dealId);
    int removeCollaborator(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("userId") int userId
    );
    int insertCollaborators(
        @Param("workspaceId") int workspaceId,
        @Param("dealId") int dealId,
        @Param("userIds") List<Integer> userIds
    );

    /**
     * Clears deal ownership held by a member within one workspace. Moved from
     * {@code WorkspaceMapper} so the control plane never writes org-data tables
     * (#440 increment 3).
     */
    void clearMemberDealOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /**
     * Clears deal ownership across all workspaces. Offboarding replacement for
     * the {@code deal.owner_id} ON DELETE SET NULL (#440 increment 3).
     */
    void clearOwnershipAnywhere(@Param("userId") int userId);

    /**
     * Removes a member from every deal-collaborator list in one workspace.
     * Offboarding replacement for the {@code deal_collaborator ->
     * workspace_member} CASCADE (#440 increment 3).
     */
    void removeCollaboratorFromWorkspace(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /**
     * Removes a user from every deal-collaborator list across all workspaces.
     * Offboarding replacement for the DB-level cascade chain on account
     * deletion (#440 increment 3).
     */
    void removeCollaboratorAnywhere(@Param("userId") int userId);
}
