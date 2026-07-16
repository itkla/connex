package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.beans.DealStakeholder;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealBucketValueDto;
import ooo.klae.connex.backend.dto.DealCurrencyMetricsDto;
import ooo.klae.connex.backend.dto.DealKpiClosedBucketDto;
import ooo.klae.connex.backend.dto.DealKpiPeriodDto;
import ooo.klae.connex.backend.dto.DealMonthDecimalTotalDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;
import ooo.klae.connex.backend.dto.DealRevenueMonthBoundary;
import ooo.klae.connex.backend.dto.DealRevenueRangeDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealTouchDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import java.time.LocalDate;
import java.util.List;

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
        @Param("currency") String currency
    );
    List<DealMonthDecimalTotalDto> revenueClosedByBoundaries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("boundaries") List<DealRevenueMonthBoundary> boundaries
    );
    List<DealMonthDecimalTotalDto> revenueScheduledClosedByMonth(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency
    );
    List<DealMonthDecimalTotalDto> revenueProjectedByMonth(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency
    );
    List<DealStageDistributionDto> stageDistribution(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency
    );
    DealKpiPeriodDto dealKpiCurrent(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days
    );
    DealKpiPeriodDto dealKpiPrevious(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("previousDays") int previousDays
    );
    List<DealKpiClosedBucketDto> dealKpiClosedSeries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("span") double span
    );
    List<DealBucketValueDto> dealKpiNewPipelineSeries(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days,
        @Param("span") double span
    );
    List<DealPipelineValueDto> dealPipelineValue(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency,
        @Param("days") int days
    );
    List<DealAgingDto> dealAging(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency
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
        @Param("currency") String currency
    );
    List<Deal> topWonDeals(
        @Param("workspaceId") int workspaceId,
        @Param("currency") String currency
    );
    List<FacetCount> countsByStatus(
        @Param("workspaceId") int workspaceId,
        @Param("memberScope") MemberScope memberScope
    );
    List<FacetCount> countsByStage(
        @Param("workspaceId") int workspaceId,
        @Param("memberScope") MemberScope memberScope
    );
    List<FacetCount> countsByPipeline(
        @Param("workspaceId") int workspaceId,
        @Param("memberScope") MemberScope memberScope
    );
    List<FacetCount> countsByCompany(
        @Param("workspaceId") int workspaceId,
        @Param("memberScope") MemberScope memberScope
    );
    List<FacetCount> countsByOwner(
        @Param("workspaceId") int workspaceId,
        @Param("memberScope") MemberScope memberScope
    );
    List<FacetCount> countsByCurrency(
        @Param("workspaceId") int workspaceId,
        @Param("memberScope") MemberScope memberScope
    );
    List<Deal> getDealBoard(
        @Param("workspaceId") int workspaceId,
        @Param("pipelineId") int pipelineId,
        @Param("memberScope") MemberScope memberScope
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
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** Deals are owned-only already; mirrors the person/company method so bulk write-scoping is uniform. */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Deal> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    /** id + name + company for every deal in the workspace; for import dedup (normalized in the service). */
    List<Deal> getDealsForDedup(int workspaceId);
    /** Deals in the workspace with the given ids (workspace-scoped); for export of a selected view. */
    List<Deal> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    List<Deal> getDealsByCompanyIds(@Param("workspaceId") int workspaceId,
            @Param("companyIds") List<Integer> companyIds);
    List<Integer> getRiskCandidateIds(@Param("workspaceId") int workspaceId,
            @Param("limit") int limit);
    int insert(Deal deal);
    /** Bulk-insert deals in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Deal> deals);
    int update(Deal deal);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Deal ids in a stage, in board order (position, then id), for renumbering a column on a move. */
    List<Integer> getDealIdsInStageOrdered(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    /** The next free tail position in a stage column ({@code MAX(position)+1}, or 0 when empty). */
    int nextDealPosition(@Param("workspaceId") int workspaceId, @Param("stageId") int stageId);
    /** Sets a single deal's manual sort position within its stage column. */
    int setPosition(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("position") int position);

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
