package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.CompanyEngagementTouchDto;
import java.util.List;

/**
 * mapper interface for {@code Company} persistence.
 * defined in {@code resources/mappers/CompanyMapper.xml}.
 * Used by {@code CompanyService}.
 */

public interface CompanyMapper {
    List<Company> getAllCompanies(int workspaceId);
    List<Company> getCompaniesPage(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("sort") String sort, @Param("dir") String dir,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids, @Param("limit") int limit, @Param("offset") int offset);
    long countCompanies(@Param("workspaceId") int workspaceId, @Param("query") String query,
            @Param("industry") List<String> industry, @Param("noIndustry") boolean noIndustry,
            @Param("ids") List<Integer> ids);
    List<CompanyEngagementTouchDto> getCompanyEngagementTouches(
            @Param("workspaceId") int workspaceId,
            @Param("companyId") int companyId,
            @Param("currentUserId") int currentUserId);
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
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int addTag(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagId") int tagId);
    int removeTag(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagId") int tagId);
    int clearTags(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId);
    int insertTags(@Param("workspaceId") int workspaceId, @Param("companyId") int companyId, @Param("tagIds") List<Integer> tagIds);
}
