package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;

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

        Company found = companyMapper.getCompanyById(workspace.getId(), company.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals(company.getName(), found.getName());
        assertEquals(company.getWebsite(), found.getWebsite());
        assertEquals("Tech", found.getIndustry());
        assertEquals(company.getPhone(), found.getPhone());
        assertEquals(company.getAddress(), found.getAddress());
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
    }

    /**
     * Gets a company by ID and checks if the returned company is null when the ID is missing.
     */
    @Test
    void getCompanyById_returnsNullWhenMissing() {
        assertNull(companyMapper.getCompanyById(workspace.getId(), -1));
    }

    /**
     * Gets all companies and checks if the returned list includes the inserted company.
     */
    @Test
    void getAllCompanies_includesInsertedRow() {
        Company company = newCompany();

        List<Company> allCompanies = companyMapper.getAllCompanies(workspace.getId());

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

        Company found = companyMapper.getCompanyById(workspace.getId(), company.getId());
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

        companyMapper.delete(workspace.getId(), company.getId());

        assertNull(companyMapper.getCompanyById(workspace.getId(), company.getId()));
    }

    /**
     * Adds a tag to a company and checks if the returned list includes the inserted company.
     */
    @Test
    void addTag_thenGetCompaniesByTagId_returnsCompany() {
        Company company = newCompany();
        Tag tag = newTag();

        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        assertTrue(companies.stream().anyMatch(x -> x.getId() == company.getId()));
    }

    /**
     * Adds a tag to a company and checks if the tag is added only once.
     */
    @Test
    void addTag_isIdempotent() {
        Company company = newCompany();
        Tag tag = newTag();

        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());
        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
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
        companyMapper.addTag(workspace.getId(), company.getId(), tag.getId());

        companyMapper.removeTag(workspace.getId(), company.getId(), tag.getId());

        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        assertTrue(companies.stream().noneMatch(x -> x.getId() == company.getId()));
    }

    /**
     * A tag write issued with another workspace's id must not associate the tag: the
     * scoped statement only matches a company owned by the given workspace, so the
     * insert affects no rows (write-path tenant isolation — pairs with the static
     * {@code TenantScopeArchTest}).
     */
    @Test
    void addTag_fromAnotherWorkspace_doesNotAssociate() {
        Company company = newCompany();
        Tag tag = newTag();
        Workspace other = newWorkspace();

        int affected = companyMapper.addTag(other.getId(), company.getId(), tag.getId());

        assertEquals(0, affected, "cross-workspace addTag must affect no rows");
        List<Company> companies = companyMapper.getCompaniesByTagId(workspace.getId(), tag.getId());
        assertTrue(companies.stream().noneMatch(x -> x.getId() == company.getId()));
    }

    /**
     * insertTags links only tags owned by the active workspace: a foreign-workspace tag id is
     * filtered out by the {@code t.workspace_id} join predicate, while a same-workspace tag in the
     * same call still links — so exactly one of the two ids is written.
     */
    @Test
    void insertTags_linksOnlySameWorkspaceTags() {
        Company company = newCompany();
        Tag ownTag = newTag();
        Workspace other = newWorkspace();
        Tag foreignTag = new Tag();
        foreignTag.setName("tag_" + unique());
        foreignTag.setColor("#abcdef");
        foreignTag.setWorkspaceId(other.getId());
        tagMapper.insert(foreignTag);

        int affected = companyMapper.insertTags(workspace.getId(), company.getId(),
            List.of(ownTag.getId(), foreignTag.getId()));

        assertEquals(1, affected, "only the same-workspace tag links; the foreign tag is filtered out");
        List<Tag> tags = tagMapper.getTagsByCompanyId(workspace.getId(), company.getId());
        assertTrue(tags.stream().anyMatch(t -> t.getId() == ownTag.getId()));
        assertTrue(tags.stream().noneMatch(t -> t.getId() == foreignTag.getId()));
    }

    /**
     * A company in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void companies_areIsolatedByWorkspace() {
        Company mine = newCompany();
        Company foreign = newCompanyIn(newWorkspace());

        assertNull(companyMapper.getCompanyById(workspace.getId(), foreign.getId()));
        assertFalse(companyMapper.exists(workspace.getId(), foreign.getId()));
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream().noneMatch(c -> c.getId() == foreign.getId()));
        assertTrue(companyMapper.getAllCompanies(workspace.getId()).stream().anyMatch(c -> c.getId() == mine.getId()));

        // cross-workspace mutation affects zero rows; the foreign row survives
        assertEquals(0, companyMapper.delete(workspace.getId(), foreign.getId()));
        assertTrue(companyMapper.exists(foreign.getWorkspaceId(), foreign.getId()));
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }

    private Company newCompanyIn(Workspace ws) {
        Company company = new Company();
        company.setName("Company " + unique());
        company.setWorkspaceId(ws.getId());
        companyMapper.insert(company);
        return company;
    }
}
