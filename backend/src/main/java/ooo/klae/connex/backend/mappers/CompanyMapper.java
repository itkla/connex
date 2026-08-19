package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.CompanyEngagementUserDto;
import ooo.klae.connex.backend.dto.CompanyEngagementWeekBucketDto;
import ooo.klae.connex.backend.dto.CompanyRevenueCurrencyDto;
import ooo.klae.connex.backend.dto.FacetCount;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.RelationshipEvidenceRowDto;
import ooo.klae.connex.backend.dto.RelationshipEvidenceTotalsDto;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;
import ooo.klae.connex.backend.dto.WarmthFilter;
import ooo.klae.connex.backend.warmth.RelationshipWarmthModel.SqlParameters;

/**
 * mapper interface for {@code Company} persistence.
 * defined in {@code resources/mappers/CompanyMapper.xml}.
 * Used by {@code CompanyService}.
 */

public interface CompanyMapper {
    List<Company> getAllCompanies(int workspaceId);
    List<Company> findVisibleNameCandidates(
            @Param("workspaceId") int workspaceId,
            @Param("pattern") String pattern,
            @Param("normalizedName") String normalizedName,
            @Param("limit") int limit);
    List<RelationshipScoreAggregateDto> getRelationshipScoreAggregates(
            @Param("workspaceId") int workspaceId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model);
    RelationshipEvidenceTotalsDto getRelationshipEvidenceTotals(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model,
            @Param("sourceLimit") int sourceLimit);
    List<RelationshipEvidenceRowDto> getRelationshipEvidenceContributors(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("reference") LocalDateTime reference,
            @Param("model") SqlParameters model,
            @Param("sourceLimit") int sourceLimit,
            @Param("limit") int limit);
    /** One page of the browser list; {@code archived} selects the archived set instead of the active one. */
    List<Company> getCompaniesPage(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("memberScope") MemberScope memberScope,
            @Param("archived") boolean archived, @Param("warmth") WarmthFilter warmth,
            @Param("limit") int limit, @Param("offset") int offset);
    long countCompanies(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("memberScope") MemberScope memberScope,
            @Param("archived") boolean archived, @Param("warmth") WarmthFilter warmth);
    CompanyEngagementCountsDto getCompanyEngagementCounts(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId);
    List<CompanyEngagementUserDto> getCompanyEngagementUsers(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("limit") int limit);
    List<CompanyEngagementWeekBucketDto> getCompanyEngagementWeeks(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("windowStart") String windowStart,
            @Param("windowEnd") String windowEnd);
    List<CompanyRevenueCurrencyDto> getCompanyRevenueByCurrency(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId);
    List<Company> getSegmentCompaniesPage(@Param("workspaceId") int workspaceId,
            @Param("segmentIdsJson") String segmentIdsJson, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("limit") int limit, @Param("offset") int offset);
    long countSegmentCompanies(@Param("workspaceId") int workspaceId,
            @Param("segmentIdsJson") String segmentIdsJson, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry);
    List<Integer> getSegmentCompanyIdsFiltered(@Param("workspaceId") int workspaceId,
            @Param("segmentIdsJson") String segmentIdsJson, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("limit") int limit);
    /** Ids using the browser's filters and member scope; backs "select all matching". */
    List<Integer> getCompanyIdsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("memberScope") MemberScope memberScope,
            @Param("archived") boolean archived, @Param("warmth") WarmthFilter warmth,
            @Param("limit") int limit, @Param("offset") int offset);
    /**
     * CSV export using the browser filters and member scope, mirroring the visible list.
     * Callers pass {@code archived = false}: an export is defined as the active working set.
     */
    List<Company> getCompaniesFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("memberScope") MemberScope memberScope,
            @Param("archived") boolean archived, @Param("warmth") WarmthFilter warmth);
    List<String> distinctIndustries(int workspaceId);
    boolean hasCompanyWithoutIndustry(int workspaceId);
    List<FacetCount> countsByOwner(@Param("workspaceId") int workspaceId);
    /**
     * How many visible active companies sit in each relationship-warmth band, computed from the same
     * attributed decayed touch aggregate the scoring service uses. Companies with no logged
     * interaction at all are counted under the {@code __none__} key rather than {@code cold}, so the
     * buckets partition the visible set and each count predicts exactly what the band filter returns.
     *
     * @param workspaceId owning workspace
     * @param warmth model parameters and evaluation instant for the decay
     * @return one bucket per band, plus {@code __none__}
     */
    List<FacetCount> countsByWarmthBand(
            @Param("workspaceId") int workspaceId, @Param("warmth") WarmthFilter warmth);
    /** How many companies the workspace currently holds archived; drives the browser's archived toggle. */
    long countArchivedCompanies(@Param("workspaceId") int workspaceId);
    List<Company> getCompaniesByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    Company getCompanyById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Company getOwnedCompanyByIdForUpdate(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** The owned company only when it is archived; the restore path's pre-image read. */
    Company getOwnedArchivedCompanyById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Company> getCompaniesWithWebsite(int workspaceId);
    /** id + name + website for every company in the workspace; for import dedup (normalized in the service). */
    List<Company> getCompaniesForDedup(int workspaceId);
    /** Companies in the workspace with the given ids (workspace-scoped); for export of a selected view. */
    List<Company> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    List<Company> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /**
     * True only when the workspace OWNS an ACTIVE company (excludes records merely shared in and
     * records that have been archived); for write scoping.
     */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** True only when the workspace owns the company AND it is archived; for restore write scoping. */
    boolean existsOwnedArchived(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Integer lockById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Company> findMentionedRecords(
            @Param("workspaceId") int workspaceId,
            @Param("text") String text,
            @Param("limit") int limit);
    int insert(Company company);
    /** Bulk-insert companies in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Company> companies);
    int update(Company company);
    int updateOwner(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("ownerId") Integer ownerId);
    int updateLogoUrlIfCurrent(
        @Param("workspaceId") int workspaceId,
        @Param("id") int id,
        @Param("currentLogoUrl") String currentLogoUrl,
        @Param("logoUrl") String logoUrl);
    /**
     * Archives an active company. There is deliberately no hard-delete statement: archiving replaced
     * it in #854 so no cascade can destroy tags, shares, or identities and no {@code SET NULL} can
     * orphan its people and deals. Whole-workspace erasure remains {@code TenantLifecycleMapper}'s job.
     *
     * @return 1 when an active owned row was archived, 0 otherwise
     */
    int archive(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /**
     * Clears the archive tombstone, returning the company to the active working set.
     *
     * @return 1 when an archived owned row was restored, 0 otherwise
     */
    int restore(@Param("workspaceId") int workspaceId, @Param("id") int id);

    /** Clears company ownership held by one member within one workspace. */
    void clearMemberOwnership(@Param("workspaceId") int workspaceId, @Param("userId") int userId);

    /** Clears company ownership held by a user across every workspace. */
    void clearOwnershipAnywhere(@Param("userId") int userId);

    int addTag(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagIds") List<Integer> tagIds);
}
