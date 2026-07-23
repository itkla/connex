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
import ooo.klae.connex.backend.ai.riskrationale.DealRiskRationaleService;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealPerson;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.BulkDeleteRequest;
import ooo.klae.connex.backend.dto.BulkOperationResult;
import ooo.klae.connex.backend.dto.BulkOwnerRequest;
import ooo.klae.connex.backend.dto.BulkStageRequest;
import ooo.klae.connex.backend.dto.BulkTagRequest;
import ooo.klae.connex.backend.dto.CloseDealRequest;
import ooo.klae.connex.backend.dto.CountDto;
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
import ooo.klae.connex.backend.dto.DealNameUpdateRequest;
import ooo.klae.connex.backend.dto.DealOwnerDto;
import ooo.klae.connex.backend.dto.DealPipelineValueDto;
import ooo.klae.connex.backend.dto.DealPrimaryContactDto;
import ooo.klae.connex.backend.dto.DealRationaleDto;
import ooo.klae.connex.backend.dto.DealRevenueSeriesDto;
import ooo.klae.connex.backend.dto.DealRescheduleRequest;
import ooo.klae.connex.backend.dto.DealRiskAnalyticsDto;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealSegmentQueryRequest;
import ooo.klae.connex.backend.dto.DealStageDistributionDto;
import ooo.klae.connex.backend.dto.DealStageHistoryDto;
import ooo.klae.connex.backend.dto.DealSummaryDto;
import ooo.klae.connex.backend.dto.DealTopDto;
import ooo.klae.connex.backend.dto.DealValueUpdateRequest;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.NoteDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.TagDto;
import ooo.klae.connex.backend.dto.TaskDto;
import ooo.klae.connex.backend.dto.UserDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.BulkOperationService;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.DealService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.DealFilterNormalizer;
import ooo.klae.connex.backend.util.LikePattern;
import ooo.klae.connex.backend.util.PageBounds;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
    private static final Set<String> SORT_DIRECTIONS = Set.of("asc", "desc");
    private static final Set<String> ANALYTICS_RANGES = Set.of("30d", "90d", "12m");

    private final DealService dealService;
    private final BulkOperationService bulkOperationService;
    private final DealRiskService dealRiskService;
    private final DealBriefService dealBriefService;
    private final DealRiskRationaleService dealRiskRationaleService;
    private final WorkspaceService workspaceService;
    private final MemberScopeResolver memberScopeResolver;

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
        @RequestParam(required = false) List<Integer> pipelineId,
        @RequestParam(required = false) List<Integer> stageId,
        @RequestParam(required = false) List<Integer> companyId,
        @RequestParam(defaultValue = "false") boolean noCompany,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) List<String> risk,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        PageBounds bounds = PageBounds.of(page, size);
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        String direction = validateOptionalValue(dir, SORT_DIRECTIONS, "dir");
        MemberScope memberScope = resolveMemberScope(scope, memberIds);
        PageResponse<Deal> result = dealService.queryDealsPage(
            query, sort, direction, currency,
            normalizeIds(pipelineId, "pipelineId"),
            normalizeIds(stageId, "stageId"),
            normalizeIds(companyId, "companyId"),
            noCompany, normalizeStatuses(status), normalizeValues(risk, DealFilterNormalizer.DEAL_RISKS, "risk"),
            memberScope, bounds.size(), bounds.offset());
        return new PageResponse<>(result.items().stream().map(DealDto::from).toList(), result.total());
    }

    /** Returns one bounded deal page intersected with a Smart Segment definition. */
    @PostMapping("/segment/page")
    public PageResponse<DealDto> getSegmentDealsPage(
            @Valid @RequestBody DealSegmentQueryRequest request) {
        PageBounds bounds = PageBounds.of(request.getPage(), request.getSize());
        SegmentDealFilters filters = segmentFilters(request);
        PageResponse<Deal> result = dealService.querySegmentDealsPage(
            request.getDefinition(), filters.query(), request.getSort(), filters.direction(),
            filters.currency(), filters.pipelineIds(), filters.stageIds(), filters.companyIds(),
            request.isNoCompany(), filters.statuses(), filters.risks(), filters.memberScope(),
            bounds.size(), bounds.offset());
        return new PageResponse<>(result.items().stream().map(DealDto::from).toList(), result.total());
    }

    /**
     * GET endpoint for filtered deal summary metrics grouped by currency.
     */
    @GetMapping("/metrics")
    public DealMetricsDto getDealMetrics(
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) List<Integer> pipelineId,
        @RequestParam(required = false) List<Integer> stageId,
        @RequestParam(required = false) List<Integer> companyId,
        @RequestParam(defaultValue = "false") boolean noCompany,
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) List<String> risk,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String query = (q == null || q.isBlank()) ? null : LikePattern.containing(q);
        return dealService.queryDealMetrics(
            query, currency,
            normalizeIds(pipelineId, "pipelineId"),
            normalizeIds(stageId, "stageId"),
            normalizeIds(companyId, "companyId"),
            noCompany, normalizeStatuses(status), normalizeValues(risk, DealFilterNormalizer.DEAL_RISKS, "risk"),
            resolveMemberScope(scope, memberIds));
    }

    /** Returns current-list deal metrics intersected with a Smart Segment definition. */
    @PostMapping("/segment/metrics")
    public DealMetricsDto getSegmentDealMetrics(
            @Valid @RequestBody DealSegmentQueryRequest request) {
        SegmentDealFilters filters = segmentFilters(request);
        return dealService.querySegmentDealMetrics(
            request.getDefinition(), filters.query(), filters.currency(), filters.pipelineIds(),
            filters.stageIds(), filters.companyIds(), request.isNoCompany(), filters.statuses(),
            filters.risks(), filters.memberScope());
    }

    /** Returns a bounded id set matching the current native deal filters. */
    @GetMapping("/ids")
    public List<Integer> getDealIds(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) List<Integer> pipelineId,
            @RequestParam(required = false) List<Integer> stageId,
            @RequestParam(required = false) List<Integer> companyId,
            @RequestParam(defaultValue = "false") boolean noCompany,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) List<String> risk,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) List<Integer> memberIds) {
        String query = q == null || q.isBlank() ? null : LikePattern.containing(q);
        return dealService.getMatchingDealIds(
            query, blankToNull(currency), normalizeIds(pipelineId, "pipelineId"),
            normalizeIds(stageId, "stageId"), normalizeIds(companyId, "companyId"), noCompany,
            normalizeStatuses(status), normalizeValues(risk, DealFilterNormalizer.DEAL_RISKS, "risk"),
            resolveMemberScope(scope, memberIds));
    }

    /** Returns a bounded id set matching the segment and current native deal filters. */
    @PostMapping("/segment/ids")
    public List<Integer> getSegmentDealIds(
            @Valid @RequestBody DealSegmentQueryRequest request) {
        SegmentDealFilters filters = segmentFilters(request);
        return dealService.getMatchingSegmentDealIds(
            request.getDefinition(), filters.query(), filters.currency(), filters.pipelineIds(),
            filters.stageIds(), filters.companyIds(), request.isNoCompany(), filters.statuses(),
            filters.risks(), filters.memberScope());
    }

    /**
     * Returns every deal in one bounded pipeline board, always unscoped: board rows carry the
     * global {@code position} values that reordering clients anchor move ordinals against, so
     * member scoping is applied client-side over the full board rather than here — a scoped
     * subset would let a reorder silently move hidden deals.
     */
    @GetMapping("/board")
    public List<DealDto> getDealBoard(@RequestParam int pipelineId) {
        if (pipelineId < 1) {
            throw new BadRequestException("pipelineId must be a positive integer");
        }
        return dealService.getDealBoard(pipelineId).stream()
            .map(DealDto::from)
            .toList();
    }

    /**
     * GET endpoint for the workspace-wide deal filter facet vocabulary. Facet counts are
     * deliberately never member-scoped (matching every other filter): options must not vanish
     * while a scope is active, so the owner picker keeps stable all-team counts.
     */
    @GetMapping("/facets")
    public DealFacets getDealFacets() {
        return dealService.getDealFacets();
    }

    /** Returns the first visible contact for each requested deal without per-deal fan-out. */
    @GetMapping("/people/primary")
    public List<DealPrimaryContactDto> getPrimaryContacts(
            @RequestParam(required = false) List<Integer> ids) {
        List<Integer> normalizedIds = normalizeIds(ids, "ids");
        return normalizedIds == null ? List.of() : dealService.getPrimaryContacts(normalizedIds);
    }

    /**
     * GET endpoint for workspace-wide realized and projected deal revenue by month.
     */
    @GetMapping("/revenue-timeseries")
    public DealRevenueSeriesDto getRevenueTimeseries(
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) String timezone,
        @RequestParam(required = false) String tzOffset,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getRevenueTimeseries(
            normalizedCurrency, resolveTimezone(timezone, tzOffset), analyticsMemberScope(scope, memberIds));
    }

    /**
     * GET endpoint for workspace-wide deal totals grouped by stage and pipeline.
     */
    @GetMapping("/stage-distribution")
    public List<DealStageDistributionDto> getStageDistribution(
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getStageDistribution(normalizedCurrency, analyticsMemberScope(scope, memberIds));
    }

    /**
     * GET endpoint for workspace-wide deal KPIs and twelve-bucket trend series.
     */
    @GetMapping("/kpis")
    public DealKpisDto getDealKpis(
        @RequestParam(required = false) String currency,
        @RequestParam(defaultValue = "90d") String range,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getDealKpis(
            normalizedCurrency, analyticsRangeDays(range), analyticsMemberScope(scope, memberIds));
    }

    /**
     * GET endpoint for realized won and open deal value grouped by pipeline.
     */
    @GetMapping("/pipeline-value")
    public List<DealPipelineValueDto> getDealPipelineValue(
        @RequestParam(required = false) String currency,
        @RequestParam(defaultValue = "90d") String range,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getDealPipelineValue(
            normalizedCurrency, analyticsRangeDays(range), analyticsMemberScope(scope, memberIds));
    }

    /**
     * GET endpoint for open-deal aging counts grouped by stage.
     */
    @GetMapping("/aging")
    public List<DealAgingDto> getDealAging(
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getDealAging(normalizedCurrency, analyticsMemberScope(scope, memberIds));
    }

    /**
     * GET endpoint for the highest-value open and won deals.
     */
    @GetMapping("/top")
    public DealTopDto getTopDeals(
        @RequestParam(required = false) String currency,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        String normalizedCurrency = (currency == null || currency.isBlank()) ? null : currency;
        return dealService.getTopDeals(normalizedCurrency, analyticsMemberScope(scope, memberIds));
    }

    /**
     * GET endpoint for open deals expected to close within the requested number of days.
     */
    @GetMapping("/closing-soon-count")
    public CountDto getClosingSoonCount(@RequestParam(defaultValue = "7") int days) {
        return dealService.getClosingSoonCount(validatePositiveDays(days));
    }

    /** Returns the earliest open deals in the current user's local closing-soon window. */
    @GetMapping("/closing-soon")
    public List<DealDto> getClosingSoonDeals(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "6") int limit) {
        if (limit < 1 || limit > 50) {
            throw new BadRequestException("limit must be between 1 and 50");
        }
        return dealService.getClosingSoonDeals(validatePositiveDays(days), limit)
            .stream().map(DealDto::from).toList();
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
        return DealFilterNormalizer.validateOptionalValue(value, allowed, parameter);
    }

    private static List<Integer> normalizeIds(List<Integer> values, String parameter) {
        return DealFilterNormalizer.normalizeIds(values, parameter);
    }

    private SegmentDealFilters segmentFilters(DealSegmentQueryRequest request) {
        return new SegmentDealFilters(
            request.getQ() == null || request.getQ().isBlank()
                ? null
                : LikePattern.containing(request.getQ()),
            validateOptionalValue(request.getDir(), SORT_DIRECTIONS, "dir"),
            blankToNull(request.getCurrency()),
            normalizeIds(request.getPipelineId(), "pipelineId"),
            normalizeIds(request.getStageId(), "stageId"),
            normalizeIds(request.getCompanyId(), "companyId"),
            normalizeStatuses(request.getStatus()),
            normalizeValues(request.getRisk(), DealFilterNormalizer.DEAL_RISKS, "risk"),
            resolveMemberScope(request.getScope(), request.getMemberIds()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SegmentDealFilters(
        String query,
        String direction,
        String currency,
        List<Integer> pipelineIds,
        List<Integer> stageIds,
        List<Integer> companyIds,
        List<String> statuses,
        List<String> risks,
        MemberScope memberScope
    ) {
    }

    private MemberScope resolveMemberScope(String scope, List<Integer> memberIds) {
        return memberScopeResolver.resolve(scope, memberIds, workspaceService.getCurrentUserId());
    }

    /**
     * Resolves a member scope for per-member analytics, restricting any
     * non-workspace-wide scope to workspace managers (admin or owner). Members
     * retain the all-team view; only managers may narrow analytics to an
     * individual member.
     */
    private MemberScope analyticsMemberScope(String scope, List<Integer> memberIds) {
        MemberScope resolved = resolveMemberScope(scope, memberIds);
        if (resolved.mode() != MemberScope.Mode.ALL_TEAM) {
            workspaceService.requireRole(WorkspaceService.Role.ADMIN);
        }
        return resolved;
    }

    private static List<String> normalizeStatuses(List<String> values) {
        return DealFilterNormalizer.normalizeStatuses(values);
    }

    private static List<String> normalizeValues(List<String> values, Set<String> allowed, String parameter) {
        return DealFilterNormalizer.normalizeValues(values, allowed, parameter);
    }

    private static int validatePositiveDays(int days) {
        if (days < 1) {
            throw new BadRequestException("days must be a positive integer");
        }
        if (days > 366) {
            throw new BadRequestException("days must be 366 or fewer");
        }
        return days;
    }

    private static String resolveTimezone(String timezone, String tzOffset) {
        boolean hasTimezone = timezone != null && !timezone.isBlank();
        boolean hasOffset = tzOffset != null && !tzOffset.isBlank();
        if (hasTimezone && hasOffset) {
            throw new BadRequestException("Specify either timezone or tzOffset, not both");
        }
        if (!hasTimezone && !hasOffset) {
            return null;
        }
        String value = hasTimezone ? timezone.trim() : tzOffset.trim();
        try {
            return hasTimezone ? ZoneId.of(value).getId() : ZoneOffset.of(value).getId();
        } catch (DateTimeException exception) {
            throw new BadRequestException(hasTimezone
                ? "Invalid timezone: " + value
                : "tzOffset must be a UTC offset like +09:00 or -05:00");
        }
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

    /** Risk assessment for a bounded requested deal set, highest risk first. */
    @GetMapping("/risk")
    public List<DealRiskDto> getDealRisks(
            @RequestParam(required = false) List<Integer> ids) {
        List<Integer> normalizedIds = normalizeIds(ids, "ids");
        if (normalizedIds == null) {
            throw new BadRequestException("ids are required for interactive deal-risk assessment");
        }
        return dealRiskService.assessDeals(
            workspaceService.getCurrentWorkspaceId(), normalizedIds);
    }

    /** Compact bounded risk totals for analytics. */
    @GetMapping("/risk/analytics")
    public DealRiskAnalyticsDto getDealRiskAnalytics(
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds
    ) {
        return dealRiskService.analytics(
            workspaceService.getCurrentWorkspaceId(), analyticsMemberScope(scope, memberIds));
    }

    /** Risk assessment for a single deal; {@code level} is {@code "none"} when it is not at risk. */
    @GetMapping("/{id}/risk")
    public DealRiskDto getDealRisk(@PathVariable int id) {
        return dealRiskService.assessDeal(workspaceService.getCurrentWorkspaceId(), id);
    }

    /** Returns an AI-generated before-you-call brief, or a graceful unavailability response. */
    @PostMapping("/{id}/brief")
    public DealBriefDto brief(@PathVariable int id, @RequestParam(defaultValue = "false") boolean refresh) {
        return dealBriefService.generate(id, refresh);
    }

    /** Returns an AI-generated deal-risk rationale, or a graceful unavailability response. */
    @PostMapping("/{id}/rationale")
    public DealRationaleDto rationale(@PathVariable int id, @RequestParam(defaultValue = "false") boolean refresh) {
        return dealRiskRationaleService.generate(id, refresh);
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
     * Changes only a deal's name.
     * @param id the deal to rename
     * @param request the replacement name
     * @return the updated deal
     */
    @PutMapping("/{id}/name")
    public DealDto updateDealName(
        @PathVariable int id,
        @Valid @RequestBody DealNameUpdateRequest request
    ) {
        return DealDto.from(dealService.updateName(id, request.getName()));
    }

    /**
     * Changes only a deal's manually projected value.
     * @param id the deal whose value should change
     * @param request the replacement value
     * @return the updated deal
     */
    @PutMapping("/{id}/value")
    public DealDto updateDealValue(
        @PathVariable int id,
        @Valid @RequestBody DealValueUpdateRequest request
    ) {
        return DealDto.from(dealService.updateValue(id, request.getValue()));
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
        return dealService.getCollaborators(id);
    }

    @PutMapping("/{id}/collaborators")
    public List<UserDto> replaceCollaborators(
        @PathVariable int id,
        @Valid @RequestBody DealCollaboratorsDto dto
    ) {
        return dealService.replaceCollaborators(id, dto.getUserIds());
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
