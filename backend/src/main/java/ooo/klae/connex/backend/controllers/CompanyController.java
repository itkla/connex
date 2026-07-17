package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.BulkDeleteRequest;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.BulkOwnerRequest;
import ooo.klae.connex.backend.dto.BulkTagRequest;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.CompanyEngagementDto;
import ooo.klae.connex.backend.dto.CompanyFacets;
import ooo.klae.connex.backend.dto.CompanyOwnerDto;
import ooo.klae.connex.backend.dto.CompanySegmentQueryRequest;
import ooo.klae.connex.backend.dto.CompanyTimelineDto;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldValueRequest;
import ooo.klae.connex.backend.dto.CustomFieldValuesRequest;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.CompanyService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.LikePattern;
import ooo.klae.connex.backend.util.PageBounds;
import ooo.klae.connex.backend.storage.UploadSource;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Company} CRUD operations.
 * Accepts and returns {@code CompanyDto}. Delegates to {@code CompanyService}.
 */

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyService companyService;
    private final BulkOperationService bulkOperationService;
    private final WorkspaceService workspaceService;
    private final MemberScopeResolver memberScopeResolver;

    /**
     * Retrieves all companies, optionally filtered by tag.
     * @return
     */
    @GetMapping
    public List<CompanyDto> getAllCompanies(@RequestParam(required = false) Integer tagId) {
        if (tagId == null) {
            throw new BadRequestException("A filter is required; use /api/companies/page for workspace-wide lists");
        }
        List<Company> companies = companyService.getCompaniesByTagId(tagId);
        return companies.stream().map(CompanyDto::from).toList();
    }

    /**
     * Retrieves a bounded, paginated, searchable, and sortable slice of companies visible to the
     * active workspace.
     */
    @GetMapping("/page")
    public PageResponse<CompanyDto> getCompaniesPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String dir,
        @RequestParam(required = false) List<String> industry,
        @RequestParam(defaultValue = "false") boolean noIndustry,
        @RequestParam(required = false) List<Integer> ids,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        PageBounds bounds = PageBounds.of(page, size);
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        List<CompanyDto> items = companyService.getCompaniesPage(
            query, sort, dir, industry, noIndustry, ids, memberScope, bounds.size(), bounds.offset())
            .stream().map(CompanyDto::from).toList();
        return new PageResponse<>(items,
            companyService.countCompanies(query, industry, noIndustry, ids, memberScope));
    }

    /** Returns bounded company-scoped engagement aggregates for one expanded company card. */
    @GetMapping("/{id}/engagement")
    public CompanyEngagementDto getCompanyEngagement(@PathVariable int id) {
        return companyService.getCompanyEngagement(id);
    }

    /** Returns bounded company-scoped records for the detail timeline. */
    @GetMapping("/{id}/timeline")
    public CompanyTimelineDto getCompanyTimeline(
            @PathVariable int id,
            @RequestParam(defaultValue = "100") int limit) {
        PageBounds bounds = PageBounds.of(1, limit);
        CompanyService.CompanyTimelineData timeline = companyService.getCompanyTimeline(id, bounds.size());
        return new CompanyTimelineDto(
            timeline.activities().stream().map(ActivityDto::from).toList(),
            timeline.tasks().stream().map(TaskDto::from).toList(),
            timeline.notes().stream().map(NoteDto::from).toList());
    }

    /**
     * Retrieves a company page matching a smart segment without accepting an expanded id list in
     * the URL.
     */
    @PostMapping("/segment/page")
    public PageResponse<CompanyDto> getSegmentCompaniesPage(
            @Valid @RequestBody CompanySegmentQueryRequest request) {
        PageBounds bounds = PageBounds.of(request.getPage(), request.getSize());
        String query = request.getQ() == null || request.getQ().isBlank()
            ? null
            : LikePattern.containing(request.getQ());
        PageResponse<Company> result = companyService.getSegmentCompaniesPage(
            request.getDefinition(), query, request.getSort(), request.getDir(), request.getIndustry(),
            request.isNoIndustry(), bounds.size(), bounds.offset());
        return new PageResponse<>(result.items().stream().map(CompanyDto::from).toList(), result.total());
    }

    /**
     * Retrieves the ids of every company matching at least one supplied filter.
     */
    @GetMapping("/ids")
    public List<Integer> getCompanyIds(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) List<String> industry,
        @RequestParam(defaultValue = "false") boolean noIndustry,
        @RequestParam(required = false) List<Integer> ids,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        if (query == null
            && (industry == null || industry.isEmpty())
            && !noIndustry
            && (ids == null || ids.isEmpty())
            && memberScope.mode() == MemberScope.Mode.ALL_TEAM) {
            throw new BadRequestException("At least one filter is required before selecting matching company ids");
        }
        return companyService.getMatchingCompanyIds(query, industry, noIndustry, ids, memberScope);
    }

    /**
     * Retrieves the bounded id set matching a smart segment and optional company filters.
     */
    @PostMapping("/segment/ids")
    public List<Integer> getSegmentCompanyIds(@Valid @RequestBody CompanySegmentQueryRequest request) {
        String query = request.getQ() == null || request.getQ().isBlank()
            ? null
            : LikePattern.containing(request.getQ());
        return companyService.getMatchingSegmentCompanyIds(
            request.getDefinition(), query, request.getIndustry(), request.isNoIndustry());
    }

    /**
     * Retrieves the distinct industry facets across companies visible to the active workspace.
     */
    @GetMapping("/facets")
    public CompanyFacets getCompanyFacets() {
        return new CompanyFacets(
            companyService.distinctIndustries(),
            companyService.hasCompanyWithoutIndustry(),
            companyService.countsByOwner()
        );
    }

    /**
     * GET Retrieves a company by ID. Throws RuntimeException if not found.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public CompanyDto oneCompany(@PathVariable int id) {
        return CompanyDto.from(companyService.getCompanyById(id));
    }

    /**
     * Stores and assigns a private company logo.
     */
    @PutMapping(value = "/{id}/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompanyDto updateLogo(
            @PathVariable int id,
            @RequestPart("file") MultipartFile file) {
        return CompanyDto.from(companyService.updateLogo(id, UploadSource.from(file)));
    }

    /**
     * Streams the currently assigned company logo after tenant authorization.
     */
    @GetMapping("/{id}/logo/{token:.+}")
    public ResponseEntity<StreamingResponseBody> getLogo(
            @PathVariable int id,
            @PathVariable String token) {
        return ManagedContentResponse.inline(companyService.getLogoContent(id, token));
    }

    /**
     * POST Creates a new company.
     * @param company
     * @return
     */
    @PostMapping
    public CompanyDto createCompany(@Valid @RequestBody CompanyDto dto) {
        return CompanyDto.from(companyService.createCompany(dto.toBean()));
    }

    /**
     * PUT Updates an existing company.
     * @param id
     * @param company
     * @return
     */
    @PutMapping("/{id}")
    public CompanyDto updateCompany(@PathVariable int id, @Valid @RequestBody CompanyDto dto) {
        return CompanyDto.from(companyService.updateCompany(id, dto.toBean()));
    }

    @PutMapping("/{id}/owner")
    public CompanyDto updateOwner(@PathVariable int id, @Valid @RequestBody CompanyOwnerDto dto) {
        return CompanyDto.from(companyService.updateOwner(id, dto.getOwnerId()));
    }

    /**
     * DELETE Deletes a company by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteCompany(@PathVariable int id) {
        companyService.deleteCompany(id);
    }

    /**
     * GET Retrieves tags associated with a company.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tags")
    public List<TagDto> getTagsForCompany(@PathVariable int id) {
        return companyService.getTagsByCompanyId(id).stream().map(TagDto::from).toList();
    }

    /**
     * POST Adds a tag to a company.
     * @param id
     * @param tagId
     */
    @PostMapping("/{id}/tags/{tagId}")
    public void addTagToCompany(@PathVariable int id, @PathVariable int tagId) {
        companyService.addTag(id, tagId);
    }

    /**
     * DELETE Removes a tag from a company.
     * @param id
     * @param tagId
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    public void removeTagFromCompany(@PathVariable int id, @PathVariable int tagId) {
        companyService.removeTag(id, tagId);
    }

    /**
     * PUT endpoint to replace the tags associated with a company.
     * @param id
     * @param tagIds
     * @return List of tags
     */
    @PutMapping("/{id}/tags")
    public List<TagDto> replaceTagsForCompany(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return companyService.replaceTags(id, tagIds).stream().map(TagDto::from).toList();
    }

    /**
     * POST endpoint to add one tag to many companies in a single request.
     * @param request the target company ids and the tag to add
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/tags/add")
    public BulkOperationResult bulkAddTag(@Valid @RequestBody BulkTagRequest request) {
        return bulkOperationService.addTagToCompanies(request.getIds(), request.getTagId());
    }

    /**
     * POST endpoint to remove one tag from many companies in a single request.
     * @param request the target company ids and the tag to remove
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/tags/remove")
    public BulkOperationResult bulkRemoveTag(@Valid @RequestBody BulkTagRequest request) {
        return bulkOperationService.removeTagFromCompanies(request.getIds(), request.getTagId());
    }

    /**
     * POST endpoint to delete many companies in a single request.
     * @param request the target company ids
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/delete")
    public BulkOperationResult bulkDelete(@Valid @RequestBody BulkDeleteRequest request) {
        return bulkOperationService.deleteCompanies(request.getIds());
    }

    @PostMapping("/bulk/owner")
    public BulkOperationResult bulkAssignOwner(@Valid @RequestBody BulkOwnerRequest request) {
        return bulkOperationService.assignOwnerToCompanies(request.getIds(), request.getOwnerId());
    }

    /**
     * GET endpoint to retrieve people associated with a company.
     * @param id
     * @return
     */
    @GetMapping("/{id}/people")
    public List<PersonDto> getPeopleForCompany(
            @PathVariable int id,
            @RequestParam(defaultValue = "100") int limit) {
        PageBounds bounds = PageBounds.of(1, limit);
        return companyService.getPersonsByCompanyId(id, bounds.size()).stream().map(PersonDto::from).toList();
    }

    /**
     * GET endpoint to retrieve deals associated with a company.
     * @param id
     * @return
     */
    @GetMapping("/{id}/deals")
    public List<DealDto> getDealsForCompany(
            @PathVariable int id,
            @RequestParam(defaultValue = "100") int limit) {
        PageBounds bounds = PageBounds.of(1, limit);
        return companyService.getDealsByCompanyId(id, bounds.size()).stream().map(DealDto::from).toList();
    }

    /**
     * GET retrieves the custom-field values for a company.
     */
    @GetMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> getCustomFieldsForCompany(@PathVariable int id) {
        return companyService.getCustomFields(id);
    }

    /**
     * PUT replaces the custom-field values for a company.
     */
    @PutMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> updateCustomFieldsForCompany(@PathVariable int id,
            @Valid @RequestBody CustomFieldValuesRequest request) {
        return companyService.updateCustomFields(id, request.getValues());
    }

    /**
     * PUT sets or clears a single custom-field value on a company.
     */
    @PutMapping("/{id}/custom-fields/{definitionId}")
    public List<CustomFieldEntryDto> updateCustomFieldForCompany(@PathVariable int id,
            @PathVariable int definitionId, @Valid @RequestBody CustomFieldValueRequest request) {
        return companyService.updateCustomField(id, definitionId, request.getValue());
    }

    /**
     * GET filled custom-field values for many companies, keyed by company id.
     */
    @GetMapping("/custom-field-values")
    public Map<Integer, Map<Integer, Object>> getCustomFieldValuesForCompanies(@RequestParam List<Integer> ids) {
        return companyService.getCustomFieldValues(ids);
    }

    private MemberScope resolveMemberScope(String scope, List<Integer> memberIds) {
        return memberScopeResolver.resolve(scope, memberIds, workspaceService.getCurrentUserId());
    }
}
