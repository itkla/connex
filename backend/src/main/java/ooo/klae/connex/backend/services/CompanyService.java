package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;

/**
 * Business logic for {@code Company} operations.
 * Every read/write is scoped to the caller's active workspace; cross-workspace
 * ids resolve to "not found" (404) rather than leaking existence.
 * Delegates persistence to {@code CompanyMapper}.
 */

@Service
@RequiredArgsConstructor
public class CompanyService {
    private final CompanyMapper companyMapper;
    private final TagMapper tagMapper;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final AuditService auditService;
    private final RuleTriggerPublisher ruleTriggers;
    private final WorkspaceService workspaceService;
    private final CustomFieldValueService customFieldValueService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("name", "website", "industry", "phone", "address", "logoUrl");

    private static final int MAX_MATCHING_IDS = 1000;

    /**
     * Retrieves all {@code Company} records in the active workspace.
     */
    public List<Company> getAllCompanies() {
        return companyMapper.getAllCompanies(workspaceService.getCurrentWorkspaceId());
    }

    public List<Company> getCompaniesPage(String query, String sort, String dir, List<String> industry,
            boolean noIndustry, List<Integer> ids, int limit, int offset) {
        return companyMapper.getCompaniesPage(workspaceService.getCurrentWorkspaceId(), query, sort, dir,
            industry, noIndustry, ids, limit, offset);
    }

    public long countCompanies(String query, List<String> industry, boolean noIndustry, List<Integer> ids) {
        return companyMapper.countCompanies(
            workspaceService.getCurrentWorkspaceId(), query, industry, noIndustry, ids);
    }

    /**
     * Retrieves every matching company id in the active workspace, rejecting unfiltered or overly
     * broad requests before loading the ids.
     */
    public List<Integer> getMatchingCompanyIds(String query, List<String> industry, boolean noIndustry,
            List<Integer> ids) {
        if (!hasMatchingIdFilter(query, industry, noIndustry, ids)) {
            throw new BadRequestException("At least one filter is required before selecting matching company ids");
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long total = companyMapper.countCompanies(workspaceId, query, industry, noIndustry, ids);
        if (total > MAX_MATCHING_IDS) {
            throw new BadRequestException("Too many matching companies; narrow the filters before selecting all");
        }
        return companyMapper.getCompanyIdsFiltered(
            workspaceId, query, industry, noIndustry, ids, MAX_MATCHING_IDS);
    }

    private static boolean hasMatchingIdFilter(String query, List<String> industry, boolean noIndustry,
            List<Integer> ids) {
        return query != null
            || (industry != null && !industry.isEmpty())
            || noIndustry
            || (ids != null && !ids.isEmpty());
    }

    public List<String> distinctIndustries() {
        return companyMapper.distinctIndustries(workspaceService.getCurrentWorkspaceId());
    }

    public boolean hasCompanyWithoutIndustry() {
        return companyMapper.hasCompanyWithoutIndustry(workspaceService.getCurrentWorkspaceId());
    }

    public List<Company> getCompaniesByTagId(int tagId) {
        return companyMapper.getCompaniesByTagId(workspaceService.getCurrentWorkspaceId(), tagId);
    }

    /**
     * Retrieves a workspace-scoped {@code Company} by ID, throwing
     * {@code ResourceNotFoundException} if absent in the active workspace.
     */
    public Company getCompanyById(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company company = companyMapper.getCompanyById(workspaceId, id);
        if (company == null) throw new ResourceNotFoundException("Company not found with id: " + id);
        company.setDeals(dealMapper.getDealsByCompanyId(workspaceId, id).toArray(Deal[]::new));
        return company;
    }

    /**
     * Creates a new {@code Company} in the active workspace. The ID is auto-generated.
     */
    @RequirePermission(Permission.COMPANY_CREATE)
    public Company createCompany(Company company) {
        company.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        assertUniqueWebsite(company);
        companyMapper.insert(company);
        auditService.record("company.create", "company", company.getId(), company.getName(),
            "Created company " + company.getName(),
            auditService.diff(null, company, AUDIT_FIELDS));
        ruleTriggers.publish(company.getWorkspaceId(), "company", company.getId(), "company.created");
        return company;
    }

    /**
     * Ensures the website is unique within the company's workspace.
     */
    private void assertUniqueWebsite(Company company) {
        String target = normalizeWebsite(company.getWebsite());
        if (target.isEmpty()) return;
        for (Company other : companyMapper.getCompaniesWithWebsite(company.getWorkspaceId())) {
            if (other.getId() == company.getId()) continue; // skip self (id is 0 on create)
            if (target.equals(normalizeWebsite(other.getWebsite())))
                throw new DuplicateResourceException("website", "A company with this website already exists");
        }
    }

    /**
     * Normalizes a website for comparison.
     * Lowercases, trims, removes leading "www." and trailing slashes.
     */
    private static String normalizeWebsite(String website) {
        if (website == null) return "";
        String w = website.trim().toLowerCase();
        w = w.replaceFirst("^https?://", "");
        w = w.replaceFirst("^www\\.", "");
        w = w.replaceAll("/+$", "");
        return w;
    }

    /**
     * Updates an existing {@code Company} in the active workspace.
     */
    @RequirePermission(Permission.COMPANY_UPDATE)
    public Company updateCompany(int id, Company company) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company before = companyMapper.getCompanyById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Company not found with id: " + id);
        company.setId(id);
        company.setWorkspaceId(workspaceId);
        assertUniqueWebsite(company);
        int updated = companyMapper.update(company);
        auditService.record("company.update", "company", id, company.getName(),
            "Updated company " + company.getName(),
            auditService.diff(before, company, AUDIT_FIELDS));
        if (updated > 0) {
            ruleTriggers.publish(workspaceId, "company", id, "company.updated");
        }
        return company;
    }

    /**
     * Deletes a {@code Company} in the active workspace.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_DELETE)
    public void deleteCompany(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company before = companyMapper.getCompanyById(workspaceId, id);
        if (before == null) throw new ResourceNotFoundException("Company not found with id: " + id);
        customFieldValueService.deleteByEntity("company", id);
        companyMapper.delete(workspaceId, id);
        auditService.record("company.delete", "company", id, before.getName(),
            "Deleted company " + before.getName(),
            auditService.diff(before, null, AUDIT_FIELDS));
    }

    /**
     * Retrieves the tags associated with a company in the active workspace.
     */
    public List<Tag> getTagsByCompanyId(int companyId) {
        requireCompany(companyId);
        return tagMapper.getTagsByCompanyId(workspaceService.getCurrentWorkspaceId(), companyId);
    }

    /**
     * Adds a tag to a company in the active workspace.
     */
    @RequirePermission(Permission.COMPANY_UPDATE)
    public void addTag(int companyId, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company company = requireCompany(workspaceId, companyId);
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        if (tag == null) throw new ResourceNotFoundException("Tag not found with id: " + tagId);
        companyMapper.addTag(workspaceId, companyId, tagId);
        auditService.record("company.addTag", "company", companyId, company.getName(),
            "Tagged " + company.getName() + " with " + tag.getName(),
            auditService.singleChange("tag", null, tag.getName()));
    }

    /**
     * Removes a tag from a company in the active workspace.
     */
    @RequirePermission(Permission.COMPANY_UPDATE)
    public void removeTag(int companyId, int tagId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company company = requireCompany(workspaceId, companyId);
        Tag tag = tagMapper.getTagById(workspaceId, tagId);
        companyMapper.removeTag(workspaceId, companyId, tagId);
        String tagName = tag != null ? tag.getName() : "#" + tagId;
        auditService.record("company.removeTag", "company", companyId, company.getName(),
            "Removed tag " + tagName + " from " + company.getName(),
            auditService.singleChange("tag", tagName, null));
    }

    /**
     * Replaces the tags associated with a company in the active workspace.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_UPDATE)
    public List<Tag> replaceTags(int companyId, List<Integer> tagIds) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company company = requireCompany(companyId);
        List<String> before = tagMapper.getTagsByCompanyId(workspaceId, companyId).stream().map(Tag::getName).toList();
        companyMapper.clearTags(workspaceId, companyId);
        if (tagIds != null && !tagIds.isEmpty()) companyMapper.insertTags(workspaceId, companyId, tagIds);
        List<Tag> after = tagMapper.getTagsByCompanyId(workspaceId, companyId);
        auditService.record("company.replaceTags", "company", companyId, company.getName(),
            "Updated tags on " + company.getName(),
            auditService.singleChange("tags", before, after.stream().map(Tag::getName).toList()));
        return after;
    }

    /**
     * Retrieves the people associated with a company in the active workspace.
     */
    public List<Person> getPersonsByCompanyId(int companyId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return personMapper.getPersonsByCompanyId(workspaceId, companyId);
    }

    /**
     * Retrieves the deals associated with a company in the active workspace.
     */
    public List<Deal> getDealsByCompanyId(int companyId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return dealMapper.getDealsByCompanyId(workspaceId, companyId);
    }

    /**
     * Custom-field values for a company — every non-archived company field with this
     * record's value. Readable by any member who can see the company.
     */
    public List<CustomFieldEntryDto> getCustomFields(int companyId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return customFieldValueService.getForEntity("company", companyId);
    }

    /**
     * Replaces a company's custom-field values.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_UPDATE)
    public List<CustomFieldEntryDto> updateCustomFields(int companyId, Map<Integer, Object> values) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return customFieldValueService.applyValues("company", companyId, values);
    }

    /**
     * Sets or clears a single custom-field value on a company.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_UPDATE)
    public List<CustomFieldEntryDto> updateCustomField(int companyId, int definitionId, Object value) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return customFieldValueService.applyValue("company", companyId, definitionId, value);
    }

    /**
     * Filled custom-field values for many companies, keyed by company id then definition id.
     */
    public Map<Integer, Map<Integer, Object>> getCustomFieldValues(List<Integer> companyIds) {
        return customFieldValueService.getForEntities("company", companyIds);
    }

    /**
     * Loads a company that must exist in the active workspace, else 404.
     */
    private Company requireCompany(int companyId) {
        return requireCompany(workspaceService.getCurrentWorkspaceId(), companyId);
    }

    private Company requireCompany(int workspaceId, int companyId) {
        Company company = companyMapper.getCompanyById(workspaceId, companyId);
        if (company == null) throw new ResourceNotFoundException("Company not found with id: " + companyId);
        return company;
    }
}
