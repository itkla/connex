package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.CompanyEngagementUserDto;
import ooo.klae.connex.backend.dto.CompanyEngagementWeekBucketDto;
import ooo.klae.connex.backend.dto.CompanyRevenueCurrencyDto;
import ooo.klae.connex.backend.dto.RelationshipScoreAggregateDto;

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
            @Param("reference") LocalDateTime reference);
    List<Company> getCompaniesPage(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("limit") int limit, @Param("offset") int offset);
    long countCompanies(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids);
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
    /** Ids only for the same filter predicates as {@code getCompaniesPage}; backs "select all matching". */
    List<Integer> getCompanyIdsFiltered(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("limit") int limit, @Param("offset") int offset);
    List<String> distinctIndustries(int workspaceId);
    boolean hasCompanyWithoutIndustry(int workspaceId);
    List<Company> getCompaniesByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    Company getCompanyById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Company> getCompaniesWithWebsite(int workspaceId);
    /** id + name + website for every company in the workspace; for import dedup (normalized in the service). */
    List<Company> getCompaniesForDedup(int workspaceId);
    /** Companies in the workspace with the given ids (workspace-scoped); for export of a selected view. */
    List<Company> getByIds(@Param("workspaceId") int workspaceId, @Param("ids") List<Integer> ids);
    List<Company> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** True only when the workspace OWNS the company (excludes records merely shared in); for write scoping. */
    boolean existsOwned(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(Company company);
    /** Bulk-insert companies in one statement (CSV import); generated ids are written back to each bean. */
    int insertBatch(List<Company> companies);
    int update(Company company);
    int updateLogoUrl(@Param("workspaceId") int workspaceId, @Param("id") int id, @Param("logoUrl") String logoUrl);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int addTag(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagIds") List<Integer> tagIds);
}
