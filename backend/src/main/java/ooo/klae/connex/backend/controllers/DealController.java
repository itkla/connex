package ooo.klae.connex.backend.controllers;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ooo.klae.connex.backend.ai.brief.DealBriefService;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.BulkDeleteRequest;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.BulkOwnerRequest;
import ooo.klae.connex.backend.dto.BulkStageRequest;
import ooo.klae.connex.backend.dto.BulkTagRequest;
import ooo.klae.connex.backend.dto.CloseDealRequest;
import ooo.klae.connex.backend.dto.CustomFieldEntryDto;
import ooo.klae.connex.backend.dto.CustomFieldValueRequest;
import ooo.klae.connex.backend.dto.CustomFieldValuesRequest;
import ooo.klae.connex.backend.dto.DealAgingDto;
import ooo.klae.connex.backend.dto.DealBriefDto;
import ooo.klae.connex.backend.dto.DealCollaboratorsDto;
import ooo.klae.connex.backend.dto.DealDto;
import ooo.klae.connex.backend.dto.DealEvaluationDto;
import ooo.klae.connex.backend.dto.DealFacets;
import ooo.klae.connex.backend.dto.DealKpisDto;
import ooo.klae.connex.backend.dto.DealMetricsDto;
import ooo.klae.connex.backend.dto.DealMoveRequest;
import ooo.klae.connex.backend.dto.DealOwnerDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealRescheduleRequest;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealStageHistoryDto;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.dto.DealTopDto;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.LikePattern;
import ooo.klae.connex.backend.util.PageBounds;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for {@code Deal} CRUD operations and stage transitions.
 * Accepts and returns {@code DealDto}. Delegates to {@code DealService}.
 */

@RestController
@RequestMapping("/api/deals")
@RequiredArgsConstructor
public class DealController {
    private static final Set<String> DEAL_STATUSES = Set.of("open", "closed", "won", "lost");
    private static final Set<String> SORT_DIRECTIONS = Set.of("asc", "desc");
    private static final Set<String> ANALYTICS_RANGES = Set.of("30d", "90d", "12m");

    private final DealService dealService;
    private final BulkOperationService bulkOperationService;
    private final DealRiskService dealRiskService;
    private final DealBriefService dealBriefService;
    private final WorkspaceService workspaceService;

    /**
     * GET endpoint to retrieve deals, with filtering by pipelineId, stageId, companyId, personId, or tagId.
     * @param pipelineId
     * @param stageId
     * @param companyId
     * @param personId
     * @param tagId
     * @return
     */
    @GetMapping
    public List<DealDto> getDeals(
        @RequestParam(required = false) Integer pipelineId,
        @RequestParam(required = false) Integer stageId,
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer tagId
    ) {
        List<Deal> deals;
        if (stageId != null)         deals = dealService.getDealsByStageId(stageId);
        else if (pipelineId != null) deals = dealService.getDealsByPipelineId(pipelineId);
        else if (companyId != null)  deals = dealService.getDealsByCompanyId(companyId);
        else if (personId != null)   deals = dealService.getDealsByPersonId(personId);
        else if (tagId != null)      deals = dealService.getDealsByTagId(tagId);
        else                         throw new BadRequestException("A filter is required; use /api/deals/page for workspace-wide lists");
        return deals.stream().map(DealDto::from).toList();
    }

    /**
     * GET endpoint for a bounded, paginated slice of deals in the active workspace.
     */
    @GetMapping("/page")
    public PageResponse<DealDto> getDealsPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String dir,
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) Integer pipelineId,
        @RequestParam(required = false) Integer stageId,
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) String status
    ) {
        PageBounds bounds = PageBounds.of(page, size);
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        String direction = validateOptionalValue(dir, SORT_DIRECTIONS, "dir");
        String dealStatus = validateOptionalValue(status, DEAL_STATUSES, "status");
        List<DealDto> items = dealService.getDealsPage(query, sort, direction, currency,
            pipelineId, stageId, companyId, dealStatus, bounds.size(), bounds.offset())
            .stream().map(DealDto::from).toList();
        return new PageResponse<>(items, dealService.countDeals(
            query, currency, pipelineId, stageId, companyId, dealStatus));
    }

    /**
     * GET endpoint for filtered deal summary metrics grouped by currency.
     */
    @GetMapping("/metrics")
    public DealMetricsDto getDealMetrics(
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) Integer pipelineId,
        @RequestParam(required = false) Integer stageId,
        @RequestParam(required = false) Integer companyId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String q
    ) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        String dealStatus = validateOptionalValue(status, DEAL_STATUSES, "status");
        return dealService.getDealMetrics(query, currency, pipelineId, stageId, companyId, dealStatus);
    }

    /**
     * GET endpoint for workspace-wide deal filter facets.
     */
    @GetMapping("/facets")
    public DealFacets getDealFacets() {
        return dealService.getDealFacets();
    }

    /**
     * GET endpoint for workspace-wide realized and projected deal revenue by month.
     */
    @GetMapping("/revenue-timeseries")
    public DealRevenueSeriesDto getRevenueTimeseries(
        @RequestParam(required = false) String currency
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getRevenueTimeseries(normalizedCurrency);
    }

    /**
     * GET endpoint for workspace-wide deal totals grouped by stage and pipeline.
     */
    @GetMapping("/stage-distribution")
    public List<DealStageDistributionDto> getStageDistribution(
        @RequestParam(required = false) String currency
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getStageDistribution(normalizedCurrency);
    }

    /**
     * GET endpoint for workspace-wide deal KPIs and twelve-bucket trend series.
     */
    @GetMapping("/kpis")
    public DealKpisDto getDealKpis(
        @RequestParam(required = false) String currency,
        @RequestParam(defaultValue = "90d") String range
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getDealKpis(normalizedCurrency, analyticsRangeDays(range));
    }

    /**
     * GET endpoint for realized won and open deal value grouped by pipeline.
     */
    @GetMapping("/pipeline-value")
    public List<DealPipelineValueDto> getDealPipelineValue(
        @RequestParam(required = false) String currency,
        @RequestParam(defaultValue = "90d") String range
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getDealPipelineValue(normalizedCurrency, analyticsRangeDays(range));
    }

    /**
     * GET endpoint for open-deal aging counts grouped by stage.
     */
    @GetMapping("/aging")
    public List<DealAgingDto> getDealAging(
        @RequestParam(required = false) String currency
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getDealAging(normalizedCurrency);
    }

    /**
     * GET endpoint for the highest-value open and won deals.
     */
    @GetMapping("/top")
    public DealTopDto getTopDeals(
        @RequestParam(required = false) String currency
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getTopDeals(normalizedCurrency);
    }

    private static int analyticsRangeDays(String range) {
        String normalizedRange = validateOptionalValue(range, ANALYTICS_RANGES, "range");
        return switch (normalizedRange == null ? "90d" : normalizedRange) {
            case "30d" -> 30;
            case "90d" -> 90;
            case "12m" -> 365;
            default -> throw new BadRequestException("range must be one of: 30d, 90d, 12m");
        };
    }

    private static String validateOptionalValue(String value, Set<String> allowed, String parameter) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!allowed.contains(value)) {
            throw new BadRequestException(parameter + " must be one of: " + String.join(", ", allowed));
        }
        return value;
    }

    /**
     * GET endpoint to retrieve a single deal by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public DealDto getDealById(@PathVariable int id) {
        return DealDto.from(dealService.getDealById(id));
    }

    /** Risk assessment for every at-risk open deal in the active workspace, highest risk first. */
    @GetMapping("/risk")
    public List<DealRiskDto> getDealRisks() {
        return dealRiskService.assessWorkspace(workspaceService.getCurrentWorkspaceId());
    }

    /** Risk assessment for a single deal; {@code level} is {@code "none"} when it is not at risk. */
    @GetMapping("/{id}/risk")
    public DealRiskDto getDealRisk(@PathVariable int id) {
        return dealRiskService.assessDeal(workspaceService.getCurrentWorkspaceId(), id);
    }

    /** Returns an AI-generated before-you-call brief, or a graceful unavailability response. */
    @GetMapping("/{id}/brief")
    public DealBriefDto brief(@PathVariable int id) {
        return dealBriefService.generate(id);
    }

    /**
     * GET endpoint returning a name-resolved summary of a deal for previews.
     * @param id
     * @return
     */
    @GetMapping("/{id}/summary")
    public DealSummaryDto getDealSummary(@PathVariable int id) {
        return dealService.getDealSummary(id);
    }

    /**
     * GET endpoint returning when the deal reached each stage, earliest first.
     * @param id the deal to read history for
     * @return the deal's stage-achievement log
     */
    @GetMapping("/{id}/stage-history")
    public List<DealStageHistoryDto> getStageHistory(@PathVariable int id) {
        return dealService.getStageHistory(id).stream().map(DealStageHistoryDto::from).toList();
    }

    /**
     * POST endpoint to create a new deal.
     * @param deal
     * @return
     */
    @PostMapping
    public DealDto createDeal(@Valid @RequestBody DealDto dto) {
        return DealDto.from(dealService.create(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing deal.
     * @param id
     * @param deal
     * @return
     */
    @PutMapping("/{id}")
    public DealDto updateDeal(@PathVariable int id, @Valid @RequestBody DealDto dto) {
        return DealDto.from(dealService.update(id, dto.toBean()));
    }

    /**
     * DELETE endpoint to delete a deal by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteDeal(@PathVariable int id) {
        dealService.delete(id);
    }

    /**
     * POST endpoint to close a deal.
     * @param id
     * @param req
     * @return
     */
    @PostMapping("/{id}/close")
    public DealDto closeDeal(@PathVariable int id, @Valid @RequestBody(required = false) CloseDealRequest req) {
        Boolean won = req != null ? req.getWon() : null;
        String reason = req != null ? req.getReason() : null;
        Double actualValue = req != null ? req.getActualValue() : null;
        return DealDto.from(dealService.close(id, won, reason, actualValue));
    }

    /**
     * POST endpoint to reopen a closed deal.
     * @param id
     * @return
     */
    @PostMapping("/{id}/reopen")
    public DealDto reopenDeal(@PathVariable int id) {
        return DealDto.from(dealService.reopen(id));
    }

    /**
     * POST endpoint to move a deal to a target stage and ordinal position on the Kanban board.
     * @param id the deal to move
     * @param req the target stage and 0-based position within that stage's column
     * @return the moved deal
     */
    @PostMapping("/{id}/move")
    public DealDto moveDeal(@PathVariable int id, @Valid @RequestBody DealMoveRequest req) {
        return DealDto.from(dealService.move(id, req.getStageId(), req.getPosition()));
    }

    /**
     * POST endpoint to change only a deal's expected close date, leaving every other field untouched.
     * @param id the deal to reschedule
     * @param req the target expected close date as a {@code YYYY-MM-DD} calendar day
     * @return the rescheduled deal
     */
    @PostMapping("/{id}/reschedule")
    public DealDto rescheduleDeal(@PathVariable int id, @Valid @RequestBody DealRescheduleRequest req) {
        return DealDto.from(dealService.reschedule(id, req.getExpectedCloseDate()));
    }

    /**
     * GET endpoint to retrieve tags associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tags")
    public List<TagDto> getTagsForDeal(@PathVariable int id) {
        return dealService.getTagsByDealId(id).stream().map(TagDto::from).toList();
    }

    /**
     * POST endpoint to add a tag to a deal.
     * @param id
     * @param tagId
     */
    @PostMapping("/{id}/tags/{tagId}")
    public void addTagToDeal(@PathVariable int id, @PathVariable int tagId) {
        dealService.addTag(id, tagId);
    }

    /**
     * DELETE endpoint to remove a tag from a deal.
     * @param id
     * @param tagId
     */
    @DeleteMapping("/{id}/tags/{tagId}")
    public void removeTagFromDeal(@PathVariable int id, @PathVariable int tagId) {
        dealService.removeTag(id, tagId);
    }

    /**
     * GET endpoint to retrieve people associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/people")
    public List<DealPerson> getPeopleForDeal(@PathVariable int id) {
        return dealService.getPeopleByDealId(id);
    }

    /**
     * POST endpoint to add a person to a deal.
     * @param id
     * @param personId
     * @param role
     */
    @PostMapping("/{id}/people/{personId}")
    public void addPersonToDeal(@PathVariable int id, @PathVariable int personId, @RequestParam(required = false) String role) {
        dealService.addPerson(id, personId, role);
    }

    /**
     * PUT endpoint to update the role of a person on a deal.
     * @param id
     * @param personId
     * @param role
     */
    @PutMapping("/{id}/people/{personId}")
    public void updatePersonRoleOnDeal(@PathVariable int id, @PathVariable int personId, @RequestParam(required = false) String role) {
        dealService.updatePersonRole(id, personId, role);
    }

    /**
     * DELETE endpoint to remove a person from a deal.
     * @param id
     * @param personId
     */
    @DeleteMapping("/{id}/people/{personId}")
    public void removePersonFromDeal(@PathVariable int id, @PathVariable int personId) {
        dealService.removePerson(id, personId);
    }

    /**
     * PUT endpoint to replace the tags associated with a deal.
     * @param id
     * @param tagIds
     * @return
     */
    @PutMapping("/{id}/tags")
    public List<TagDto> replaceTagsForDeal(@PathVariable int id, @RequestBody List<Integer> tagIds) {
        return dealService.replaceTags(id, tagIds).stream().map(TagDto::from).toList();
    }

    /**
     * PUT endpoint to replace the people associated with a deal.
     * @param id
     * @param personIds
     * @return List of people
     */
    @PutMapping("/{id}/people")
    public List<DealPerson> replacePeopleForDeal(@PathVariable int id, @RequestBody List<DealPerson> people) {
        return dealService.replacePeople(id, people);
    }

    /**
     * GET endpoint to retrieve activities associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/activities")
    public List<ActivityDto> getActivitiesForDeal(@PathVariable int id) {
        return dealService.getActivitiesByDealId(id).stream().map(ActivityDto::from).toList();
    }

    /**
     * GET endpoint to retrieve notes associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/notes")
    public List<NoteDto> getNotesForDeal(@PathVariable int id) {
        return dealService.getNotesByDealId(id).stream().map(NoteDto::from).toList();
    }

    /**
     * GET endpoint to retrieve tasks associated with a deal.
     * @param id
     * @return
     */
    @GetMapping("/{id}/tasks")
    public List<TaskDto> getTasksForDeal(@PathVariable int id) {
        return dealService.getTasksByDealId(id).stream().map(TaskDto::from).toList();
    }

    @PutMapping("/{id}/owner")
    public DealDto updateOwner(@PathVariable int id, @Valid @RequestBody DealOwnerDto dto) {
        return DealDto.from(dealService.updateOwner(id, dto.getOwnerId()));
    }

    /**
     * PUT endpoint to set the deal's risk-evaluation opt-out (issue #358).
     * @param id
     * @param dto
     * @return the updated deal
     */
    @PutMapping("/{id}/evaluation")
    public DealDto updateEvaluation(@PathVariable int id, @Valid @RequestBody DealEvaluationDto dto) {
        return DealDto.from(dealService.updateRiskExcluded(id, dto.getRiskExcluded()));
    }

    @GetMapping("/{id}/collaborators")
    public List<UserDto> getCollaborators(@PathVariable int id) {
        return dealService.getCollaborators(id).stream().map(UserDto::from).toList();
    }

    @PutMapping("/{id}/collaborators")
    public List<UserDto> replaceCollaborators(
        @PathVariable int id,
        @Valid @RequestBody DealCollaboratorsDto dto
    ) {
        return dealService.replaceCollaborators(id, dto.getUserIds()).stream().map(UserDto::from).toList();
    }

    /**
     * POST endpoint to add one tag to many deals in a single request.
     * @param request the target deal ids and the tag to add
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/tags/add")
    public BulkOperationResult bulkAddTag(@Valid @RequestBody BulkTagRequest request) {
        return bulkOperationService.addTagToDeals(request.getIds(), request.getTagId());
    }

    /**
     * POST endpoint to remove one tag from many deals in a single request.
     * @param request the target deal ids and the tag to remove
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/tags/remove")
    public BulkOperationResult bulkRemoveTag(@Valid @RequestBody BulkTagRequest request) {
        return bulkOperationService.removeTagFromDeals(request.getIds(), request.getTagId());
    }

    /**
     * POST endpoint to delete many deals in a single request.
     * @param request the target deal ids
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/delete")
    public BulkOperationResult bulkDelete(@Valid @RequestBody BulkDeleteRequest request) {
        return bulkOperationService.deleteDeals(request.getIds());
    }

    /**
     * POST endpoint to assign an owner across many deals in a single request. A null ownerId
     * unassigns the owner.
     * @param request the target deal ids and the owner to assign
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/owner")
    public BulkOperationResult bulkAssignOwner(@Valid @RequestBody BulkOwnerRequest request) {
        return bulkOperationService.assignOwnerToDeals(request.getIds(), request.getOwnerId());
    }

    /**
     * POST endpoint to move many deals to a single stage in one request. Deals outside the target
     * stage's pipeline are reported as failures rather than moved across pipelines.
     * @param request the target deal ids and the stage to move them to
     * @return per-record success/failure counts
     */
    @PostMapping("/bulk/stage")
    public BulkOperationResult bulkChangeStage(@Valid @RequestBody BulkStageRequest request) {
        return bulkOperationService.changeStageForDeals(request.getIds(), request.getStageId());
    }

    /**
     * GET retrieves the custom-field values for a deal.
     */
    @GetMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> getCustomFieldsForDeal(@PathVariable int id) {
        return dealService.getCustomFields(id);
    }

    /**
     * PUT replaces the custom-field values for a deal.
     */
    @PutMapping("/{id}/custom-fields")
    public List<CustomFieldEntryDto> updateCustomFieldsForDeal(@PathVariable int id,
            @Valid @RequestBody CustomFieldValuesRequest request) {
        return dealService.updateCustomFields(id, request.getValues());
    }

    /**
     * PUT sets or clears a single custom-field value on a deal.
     */
    @PutMapping("/{id}/custom-fields/{definitionId}")
    public List<CustomFieldEntryDto> updateCustomFieldForDeal(@PathVariable int id,
            @PathVariable int definitionId, @Valid @RequestBody CustomFieldValueRequest request) {
        return dealService.updateCustomField(id, definitionId, request.getValue());
    }

    /**
     * GET filled custom-field values for many deals, keyed by deal id.
     */
    @GetMapping("/custom-field-values")
    public Map<Integer, Map<Integer, Object>> getCustomFieldValuesForDeals(@RequestParam List<Integer> ids) {
        return dealService.getCustomFieldValues(ids);
    }
}
