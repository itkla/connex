package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.ActivityMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Note;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CompanyEngagementDto;
import ooo.klae.connex.backend.dto.CompanyEngagementCountsDto;
import ooo.klae.connex.backend.dto.CompanyEngagementUserDto;
import ooo.klae.connex.backend.dto.CompanyEngagementWeekBucketDto;
import ooo.klae.connex.backend.dto.CompanyEngagementWeekDto;
import ooo.klae.connex.backend.dto.CompanyRevenueCurrencyDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.businesscard.BusinessCardTextNormalizer;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredImage;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.util.LikePattern;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ActivityMapper activityMapper;
    private final NoteMapper noteMapper;
    private final TaskMapper taskMapper;
    private final AuditService auditService;
    private final RuleTriggerPublisher ruleTriggers;
    private final WorkspaceService workspaceService;
    private final CustomFieldValueService customFieldValueService;
    private final SegmentService segmentService;
    private final ReferenceService referenceService;
    private final Clock clock;
    private final ManagedObjectService managedObjectService;

    private static final Set<String> AUDIT_FIELDS =
        Set.of("name", "website", "industry", "phone", "address", "logoUrl");

    private static final int MAX_MATCHING_IDS = 1000;
    private static final int COMPANY_NAME_CANDIDATE_LIMIT = 16;
    private static final int ENGAGEMENT_PREVIEW_SIZE = 5;
    private static final int ENGAGEMENT_WEEKS = 12;
    private static final long WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Retrieves all {@code Company} records in the active workspace.
     */
    public List<Company> getAllCompanies() {
        return companyMapper.getAllCompanies(workspaceService.getCurrentWorkspaceId());
    }

    /**
     * Returns visible companies whose names exactly match after Unicode NFKC, whitespace, and
     * case normalization. More than one result is intentionally preserved so callers never bind
     * an ambiguous OCR candidate automatically.
     */
    public NormalizedCompanyMatches findVisibleByNormalizedName(String name) {
        String key = BusinessCardTextNormalizer.companyKey(name);
        if (key.isBlank()) {
            return new NormalizedCompanyMatches(List.of(), false);
        }
        String pattern = java.util.Arrays.stream(key.split(" "))
                .map(LikePattern::escape)
                .collect(Collectors.joining("%", "", "%"));
        List<Company> candidates = companyMapper.findVisibleNameCandidates(
                workspaceService.getCurrentWorkspaceId(), pattern, key,
                COMPANY_NAME_CANDIDATE_LIMIT + 1);
        boolean truncated = candidates.size() > COMPANY_NAME_CANDIDATE_LIMIT;
        List<Company> matches = candidates.stream()
                .limit(COMPANY_NAME_CANDIDATE_LIMIT)
                .filter(company -> key.equals(BusinessCardTextNormalizer.companyKey(company.getName())))
                .toList();
        return new NormalizedCompanyMatches(matches, truncated);
    }

    /**
     * Exact normalized company-name matches plus whether the broad candidate query was truncated.
     *
     * @param companies exact normalized visible matches
     * @param truncated whether additional broad candidates were omitted
     */
    public record NormalizedCompanyMatches(List<Company> companies, boolean truncated) {
        public NormalizedCompanyMatches {
            companies = List.copyOf(companies);
        }
    }

    public CompanyEngagementDto getCompanyEngagement(int companyId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!companyMapper.exists(workspaceId, companyId)) {
            throw new ResourceNotFoundException("Company not found with id: " + companyId);
        }
        CompanyEngagementCountsDto counts = companyMapper.getCompanyEngagementCounts(workspaceId, companyId);
        List<CompanyEngagementUserDto> users = companyMapper.getCompanyEngagementUsers(
            workspaceId, companyId, ENGAGEMENT_PREVIEW_SIZE);
        List<CompanyRevenueCurrencyDto> revenue = companyMapper.getCompanyRevenueByCurrency(workspaceId, companyId);
        CompanyRevenueCurrencyDto dominant = revenue.isEmpty()
            ? new CompanyRevenueCurrencyDto("USD", 0, 0, 0)
            : revenue.getFirst();
        Instant now = Instant.now(clock);
        long firstWeekStart = LocalDate.ofInstant(now, ZoneOffset.UTC)
            .minusWeeks(ENGAGEMENT_WEEKS - 1L)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli();
        Map<Integer, CompanyEngagementWeekBucketDto> buckets = companyMapper.getCompanyEngagementWeeks(
            workspaceId, companyId, mysql(firstWeekStart), mysql(now.toEpochMilli())).stream()
            .collect(Collectors.toMap(CompanyEngagementWeekBucketDto::bucketIndex, bucket -> bucket));
        List<CompanyEngagementWeekDto> weeks = java.util.stream.IntStream.range(0, ENGAGEMENT_WEEKS)
            .mapToObj(index -> {
                CompanyEngagementWeekBucketDto bucket = buckets.get(index);
                long activities = bucket == null ? 0 : bucket.activities();
                long tasks = bucket == null ? 0 : bucket.tasks();
                long notes = bucket == null ? 0 : bucket.notes();
                return new CompanyEngagementWeekDto(
                    firstWeekStart + index * WEEK_MILLIS,
                    activities + tasks + notes,
                    activities,
                    tasks,
                    notes
                );
            })
            .toList();
        return new CompanyEngagementDto(
            personMapper.getCompanyEngagementPeople(workspaceId, companyId, ENGAGEMENT_PREVIEW_SIZE),
            counts.personCount(),
            users.stream().map(CompanyEngagementUserDto::userId).toList(),
            users.isEmpty() ? 0 : users.getFirst().totalUsers(),
            dominant.pastRevenue(),
            dominant.projectedRevenue(),
            dominant.currency(),
            counts.numDeals(),
            counts.numTasks(),
            counts.openTasks(),
            counts.numActivities(),
            counts.numNotes(),
            weeks
        );
    }

    /** Bounded recent records for the company detail timeline. */
    public CompanyTimelineData getCompanyTimeline(int companyId, int limit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (!companyMapper.exists(workspaceId, companyId)) {
            throw new ResourceNotFoundException("Company not found with id: " + companyId);
        }
        int currentUserId = workspaceService.getCurrentUserId();
        List<Task> tasks = referenceService.hydrateTasks(
            workspaceId, taskMapper.getCompanyTasks(workspaceId, companyId, limit));
        List<Activity> activities = referenceService.hydrateActivities(
            workspaceId, activityMapper.getCompanyActivities(workspaceId, companyId, limit));
        List<Note> notes = referenceService.hydrate(
            workspaceId, noteMapper.getVisibleCompanyNotes(
                workspaceId, companyId, currentUserId, limit));
        return new CompanyTimelineData(
            activities, tasks, notes);
    }

    /** Recent record slices rendered by the company detail timeline. */
    public record CompanyTimelineData(
        List<Activity> activities,
        List<Task> tasks,
        List<Note> notes
    ) {}

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
     * Evaluates a company segment within the active workspace and returns one filtered page without
     * exposing the evaluated id set to the client.
     */
    public PageResponse<Company> getSegmentCompaniesPage(SegmentDefinition definition, String query,
            String sort, String dir, List<String> industry, boolean noIndustry, int limit, int offset) {
        List<Integer> ids = segmentService.evaluate("company", definition);
        if (ids.isEmpty()) {
            return new PageResponse<>(List.of(), 0);
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String segmentIdsJson = idsJson(ids);
        return new PageResponse<>(
            companyMapper.getSegmentCompaniesPage(
                workspaceId, segmentIdsJson, query, sort, dir, industry, noIndustry, limit, offset),
            companyMapper.countSegmentCompanies(
                workspaceId, segmentIdsJson, query, industry, noIndustry)
        );
    }

    /**
     * Retrieves every company id matching a segment and the supplied company filters, subject to
     * the bulk-operation limit.
     */
    public List<Integer> getMatchingSegmentCompanyIds(SegmentDefinition definition, String query,
            List<String> industry, boolean noIndustry) {
        List<Integer> ids = segmentService.evaluate("company", definition);
        if (ids.isEmpty()) {
            return List.of();
        }
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<Integer> matches = companyMapper.getSegmentCompanyIdsFiltered(
            workspaceId, idsJson(ids), query, industry, noIndustry, MAX_MATCHING_IDS + 1);
        if (matches.size() > MAX_MATCHING_IDS) {
            throw new BadRequestException(
                "Too many matching companies; narrow the filters before selecting all");
        }
        return matches;
    }

    private static String idsJson(List<Integer> ids) {
        return ids.stream()
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .map(String::valueOf)
            .collect(Collectors.joining(",", "[", "]"));
    }

    private static String mysql(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC)
            .format(MYSQL_DATETIME);
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
            workspaceId, query, industry, noIndustry, ids, MAX_MATCHING_IDS, 0);
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
        return company;
    }

    /**
     * Creates a new {@code Company} in the active workspace. The ID is auto-generated.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_CREATE)
    public Company createCompany(Company company) {
        company.setWorkspaceId(workspaceService.getCurrentWorkspaceId());
        int ownerId = workspaceService.getCurrentUserId();
        workspaceService.lockAndRequireMember(company.getWorkspaceId(), ownerId);
        company.setOwnerId(ownerId);
        company.setLogoUrl(null);
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
    @Transactional
    @RequirePermission(Permission.COMPANY_UPDATE)
    public Company updateCompany(int id, Company company) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company before = requireOwnedCompany(workspaceId, id);
        company.setId(id);
        company.setWorkspaceId(workspaceId);
        company.setLogoUrl(before.getLogoUrl());
        assertUniqueWebsite(company);
        int updated = companyMapper.update(company);
        Company after = requireOwnedCompany(workspaceId, id);
        auditService.record("company.update", "company", id, after.getName(),
            "Updated company " + after.getName(),
            auditService.diff(before, after, AUDIT_FIELDS));
        if (updated > 0) {
            ruleTriggers.publish(workspaceId, "company", id, "company.updated");
        }
        return after;
    }

    /** Assigns or clears the owner of a company in the active workspace. */
    @Transactional
    @RequirePermission(Permission.COMPANY_UPDATE)
    public Company updateOwner(int id, Integer ownerId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company company = requireOwnedCompany(workspaceId, id);
        if (ownerId != null) workspaceService.lockAndRequireMember(workspaceId, ownerId);
        companyMapper.updateOwner(workspaceId, id, ownerId);
        auditService.record("company.updateOwner", "company", id, company.getName(),
            "Updated owner on " + company.getName(),
            auditService.singleChange("ownerId", company.getOwnerId(), ownerId));
        if (!Objects.equals(company.getOwnerId(), ownerId)) {
            ruleTriggers.publish(workspaceId, "company", id, "company.owner_changed");
        }
        return requireOwnedCompany(workspaceId, id);
    }

    @Transactional
    @RequirePermission(Permission.COMPANY_UPDATE)
    public Company updateLogo(int id, UploadSource source) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Company before = requireOwnedCompany(workspaceId, id);
        StoredImage stored = managedObjectService.storeCompanyImage(workspaceId, id, source);
        int updated = companyMapper.updateLogoUrlIfCurrent(
            workspaceId, id, before.getLogoUrl(), stored.url());
        if (updated != 1) {
            throw new ConflictException("Company logo changed while the image was uploading; retry");
        }
        managedObjectService.deleteCompanyImageAfterCommit(
            before.getWorkspaceId(), id, before.getLogoUrl());
        Company after = requireOwnedCompany(workspaceId, id);
        auditService.record("company.updateLogo", "company", id, before.getName(),
            "Updated logo for " + before.getName(),
            auditService.singleChange("logoUrl", before.getLogoUrl(), after.getLogoUrl()));
        ruleTriggers.publish(workspaceId, "company", id, "company.updated");
        return after;
    }

    public ManagedContent getLogoContent(int id, String token) {
        Company company = requireCompany(id);
        return managedObjectService.openCompanyImage(
            company.getWorkspaceId(), id, company.getLogoUrl(), token);
    }

    /**
     * Deletes a {@code Company} in the active workspace.
     */
    @Transactional
    @RequirePermission(Permission.COMPANY_DELETE)
    public void deleteCompany(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (companyMapper.lockById(workspaceId, id) == null) {
            throw new ResourceNotFoundException("Company not found with id: " + id);
        }
        Company before = requireOwnedCompany(workspaceId, id);
        managedObjectService.deleteCompanyImageAfterCommit(
            before.getWorkspaceId(), id, before.getLogoUrl());
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
        Company company = requireOwnedCompany(workspaceId, companyId);
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
        Company company = requireOwnedCompany(workspaceId, companyId);
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
        Company company = requireOwnedCompany(workspaceId, companyId);
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
    public List<Person> getPersonsByCompanyId(int companyId, int limit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return personMapper.getPersonsByCompanyId(workspaceId, companyId, limit);
    }

    /**
     * Retrieves the deals associated with a company in the active workspace.
     */
    public List<Deal> getDealsByCompanyId(int companyId, int limit) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCompany(workspaceId, companyId);
        return referenceService.hydrateDeals(
            workspaceId, dealMapper.getDealsByCompanyIdPage(workspaceId, companyId, limit));
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

    private Company requireOwnedCompany(int workspaceId, int companyId) {
        if (!companyMapper.existsOwned(workspaceId, companyId)) {
            throw new ResourceNotFoundException("Company not found with id: " + companyId);
        }
        return requireCompany(workspaceId, companyId);
    }
}
