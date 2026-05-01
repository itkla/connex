package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Tag;

class CompanyMapperTest extends AbstractMapperTest {

    /**
     * Inserts a new company and checks if the generated ID is not zero.
     */
    @Test
    void insert_assignsGeneratedId() {
        Company company = newCompany();
        assertNotEquals(0, company.getId());
    }

    /**
     * Gets a company by ID and checks if the returned company is not null.
     */
    @Test
    void getCompanyById_returnsInsertedRow() {
        Company company = newCompany();

        Company found = companyMapper.getCompanyById(company.getId());

        assertNotNull(found);
        assertEquals(company.getName(), found.getName());
        assertEquals(company.getWebsite(), found.getWebsite());
        assertEquals("Tech", found.getIndustry());
        assertEquals(company.getPhone(), found.getPhone());
        assertEquals(company.getAddress(), found.getAddress());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    /**
     * Gets a company by ID and checks if the returned company is null when the ID is negative.
     */
    @Test
    void getCompanyById_returnsNullWhenMissing() {
        assertNull(companyMapper.getCompanyById(-1));
    }

    /**
     * Gets all companies and checks if the returned list includes the inserted company.
     */
    @Test
    void getAllCompanies_includesInsertedRow() {
        Company company = newCompany();

        List<Company> allCompanies = companyMapper.getAllCompanies();

        assertTrue(allCompanies.stream().anyMatch(x -> x.getId() == company.getId()));
    }

    /**
     * Updates a company and checks if the new values are persisted.
     */
    @Test
    void update_persistsNewValues() {
        Company company = newCompany();
        company.setName("Renamed Co");
        company.setIndustry("Finance");
        company.setPhone("+1-555-9999");

        companyMapper.update(company);

        Company found = companyMapper.getCompanyById(company.getId());
        assertEquals("Renamed Co", found.getName());
        assertEquals("Finance", found.getIndustry());
        assertEquals("+1-555-9999", found.getPhone());
    }

    /**
     * Deletes a company and checks if the company is removed.
     */
    @Test
    void delete_removesRow() {
        Company company = newCompany();

        companyMapper.delete(company.getId());

        assertNull(companyMapper.getCompanyById(company.getId()));
    }

    /**
     * Adds a tag to a company and checks if the returned list includes the inserted company.
     */
    @Test
    void addTag_thenGetCompaniesByTagId_returnsCompany() {
        Company company = newCompany();
        Tag tag = newTag();

        companyMapper.addTag(company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(tag.getId());
        assertTrue(companies.stream().anyMatch(x -> x.getId() == company.getId()));
    }

    /**
     * Adds a tag to a company and checks if the tag is added only once.
     */
    @Test
    void addTag_isIdempotent() {
        Company company = newCompany();
        Tag tag = newTag();

        companyMapper.addTag(company.getId(), tag.getId());
        companyMapper.addTag(company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(tag.getId());
        long matching = companies.stream().filter(x -> x.getId() == company.getId()).count();
        assertEquals(1, matching);
    }

    /**
     * Removes a tag from a company and checks if the tag is removed.
     */
    @Test
    void removeTag_dropsAssociation() {
        Company company = newCompany();
        Tag tag = newTag();
        companyMapper.addTag(company.getId(), tag.getId());

        companyMapper.removeTag(company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(tag.getId());
        assertTrue(companies.stream().noneMatch(x -> x.getId() == company.getId()));
    }
}
