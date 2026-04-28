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
    List<Company> getAllCompanies();
    List<Company> getCompaniesByTagId(int tagId);
    Company getCompanyById(int id);
    int insert(Company company);
    int update(Company company);
    int delete(int id);

    int addTag(@Param("companyId") int companyId, @Param("tagId") int tagId);
    int removeTag(@Param("companyId") int companyId, @Param("tagId") int tagId);
}
