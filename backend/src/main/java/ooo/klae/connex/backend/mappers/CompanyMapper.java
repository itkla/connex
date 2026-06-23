package ooo.klae.connex.backend.mappers;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.Company;
import java.util.List;

/**
 * mapper interface for {@code Company} persistence.
 * defined in {@code resources/mappers/CompanyMapper.xml}.
 * Used by {@code CompanyService}.
 */

public interface CompanyMapper {
    List<Company> getAllCompanies(int workspaceId);
    List<Company> getCompaniesByTagId(@Param("workspaceId") int workspaceId, @Param("tagId") int tagId);
    Company getCompanyById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    List<Company> getCompaniesWithWebsite(int workspaceId);
    List<Company> search(@Param("workspaceId") int workspaceId, @Param("query") String query);
    boolean exists(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int insert(Company company);
    int update(Company company);
    int delete(@Param("workspaceId") int workspaceId, @Param("id") int id);

    int addTag(@Param("companyId") int companyId, @Param("tagId") int tagId);
    int removeTag(@Param("companyId") int companyId, @Param("tagId") int tagId);
    int clearTags(int companyId);
    int insertTags(@Param("companyId") int companyId, @Param("tagIds") List<Integer> tagIds);
}
