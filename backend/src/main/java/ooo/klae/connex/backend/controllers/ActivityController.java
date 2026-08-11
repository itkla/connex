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

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.dto.ActivityDto;
import ooo.klae.connex.backend.dto.ActivityVolumeBucketDto;
import ooo.klae.connex.backend.dto.CountDto;
import ooo.klae.connex.backend.dto.MemberScope;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.TeamLeaderboardEntryDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.services.MemberScopeResolver;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.util.AnalyticsPeriods;
import ooo.klae.connex.backend.util.AnalyticsPeriods.AnalyticsPeriod;
import ooo.klae.connex.backend.util.AnalyticsPeriods.Window;
import ooo.klae.connex.backend.util.PageBounds;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for logging and retrieving {@code Activity} records.
 * Accepts and returns {@code ActivityDto}. Delegates to {@code ActivityService}.
 */

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
@TenantJournalAttributable
public class ActivityController {
    private static final Set<String> ANALYTICS_RANGES = Set.of("30d", "90d", "12m");

    private final ActivityService activityService;
    private final WorkspaceService workspaceService;
    private final MemberScopeResolver memberScopeResolver;

    /**
     * GET endpoint to retrieve activities, with optional filtering by personId, dealId, or createdById.
     * @param personId
     * @param dealId
     * @param createdById
     * @return
     */
    @GetMapping
    public List<ActivityDto> getActivities(
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId,
        @RequestParam(required = false) Integer createdById,
        @RequestParam(required = false) Integer paginationRange,
        @RequestParam(required = false) Integer itemsPerPage
    ) {
        Integer selectedPersonId = personId;
        Integer selectedDealId = selectedPersonId == null ? dealId : null;
        Integer selectedCreatedById = selectedPersonId == null && selectedDealId == null ? createdById : null;
        List<Activity> activities;
        if (itemsPerPage != null) {
            PageBounds bounds = PageBounds.of(paginationRange == null ? 1 : paginationRange, itemsPerPage);
            activities = activityService.getActivitiesPage(
                selectedPersonId, selectedDealId, selectedCreatedById, bounds.size(), bounds.offset());
        } else if (selectedPersonId != null) activities = activityService.getActivitiesByPersonId(selectedPersonId);
        else if (selectedDealId != null) activities = activityService.getActivitiesByDealId(selectedDealId);
        else if (selectedCreatedById != null) activities = activityService.getActivitiesByCreatedById(selectedCreatedById);
        else throw new BadRequestException("A filter or pagination is required; use /api/activities/page for workspace-wide lists");
        return activities.stream().map(ActivityDto::from).toList();
    }

    /**
     * GET endpoint for a bounded, paginated slice of activities in the active workspace.
     */
    @GetMapping("/page")
    public PageResponse<ActivityDto> getActivitiesPage(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId,
        @RequestParam(required = false) Integer createdById
    ) {
        Integer selectedPersonId = personId;
        Integer selectedDealId = selectedPersonId == null ? dealId : null;
        Integer selectedCreatedById = selectedPersonId == null && selectedDealId == null ? createdById : null;
        PageBounds bounds = PageBounds.of(page, size);
        List<ActivityDto> items = activityService.getActivitiesPage(
            selectedPersonId, selectedDealId, selectedCreatedById, bounds.size(), bounds.offset())
            .stream().map(ActivityDto::from).toList();
        return new PageResponse<>(items, activityService.countActivities(selectedPersonId, selectedDealId, selectedCreatedById));
    }

    /**
     * GET endpoint for workspace-wide activity volume by analytics time bucket.
     * Calendar boundaries use the server-owned workspace timezone.
     */
    @GetMapping("/volume")
    public List<ActivityVolumeBucketDto> getActivityVolume(
        @RequestParam(defaultValue = "90d") String range,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) List<Integer> memberIds,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(required = false) String granularity,
        @RequestParam(name = "timezone", required = false) String legacyTimezone,
        @RequestParam(name = "tzOffset", required = false) String legacyTzOffset
    ) {
        Optional<Window> window = analyticsWindow(from, to);
        if (window.isPresent()) {
            List<AnalyticsPeriod> periods = AnalyticsPeriods.periods(window.get(), granularity);
            return activityService.getActivityVolume(
                window.get(), periods, analyticsMemberScope(scope, memberIds));
        }
        return activityService.getActivityVolume(
            analyticsRangeDays(range), analyticsMemberScope(scope, memberIds));
    }

    private MemberScope resolveMemberScope(String scope, List<Integer> memberIds) {
        return memberScopeResolver.resolve(scope, memberIds, workspaceService.getCurrentUserId());
    }

    /**
     * Resolves a member scope for per-member analytics, restricting any
     * non-workspace-wide scope to workspace managers (admin or owner). Members
     * retain the all-team view.
     */
    private MemberScope analyticsMemberScope(String scope, List<Integer> memberIds) {
        MemberScope resolved = resolveMemberScope(scope, memberIds);
        if (resolved.mode() != MemberScope.Mode.ALL_TEAM) {
            workspaceService.requireRole(WorkspaceService.Role.ADMIN);
        }
        return resolved;
    }

    /**
     * GET endpoint for workspace-wide user touch counts within an analytics range.
     * Calendar boundaries use the server-owned workspace timezone.
     */
    @GetMapping("/leaderboard")
    public List<TeamLeaderboardEntryDto> getTeamLeaderboard(
        @RequestParam(defaultValue = "90d") String range,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(name = "timezone", required = false) String legacyTimezone,
        @RequestParam(name = "tzOffset", required = false) String legacyTzOffset
    ) {
        Optional<Window> window = analyticsWindow(from, to);
        if (window.isPresent()) {
            return activityService.getTeamLeaderboard(window.get());
        }
        return activityService.getTeamLeaderboard(analyticsRangeDays(range));
    }

    /**
     * GET endpoint for the number of upcoming activities in the active workspace.
     */
    @GetMapping("/upcoming-count")
    public CountDto getUpcomingCount(@RequestParam(defaultValue = "7") int days) {
        return activityService.getUpcomingCount(validatePositiveDays(days));
    }

    /**
     * GET endpoint to retrieve a single activity by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public ActivityDto getActivityById(@PathVariable int id) {
        return ActivityDto.from(activityService.getActivityById(id));
    }

    /**
     * POST endpoint to create a new activity.
     * @param activity
     * @return
     */
    @PostMapping
    public ActivityDto createActivity(@Valid @RequestBody ActivityDto dto) {
        return ActivityDto.from(activityService.create(dto.toBean()));
    }

    /**
     * PUT endpoint to update an existing activity.
     * @param id
     * @param activity
     * @return
     */
    @PutMapping("/{id}")
    public ActivityDto updateActivity(@PathVariable int id, @Valid @RequestBody ActivityDto dto) {
        return ActivityDto.from(activityService.update(id, dto.toBean()));
    }

    /**
     * DELETE endpoint to delete an activity by ID.
     * @param id
     */
    @DeleteMapping("/{id}")
    public void deleteActivity(@PathVariable int id) {
        activityService.delete(id);
    }

    private Optional<Window> analyticsWindow(String from, String to) {
        if (from == null && to == null) {
            return Optional.empty();
        }
        return AnalyticsPeriods.optionalWindow(
            from, to, workspaceService.getCurrentAnalyticsTimezone(), null);
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

    private static int validatePositiveDays(int days) {
        if (days < 1) {
            throw new BadRequestException("days must be a positive integer");
        }
        if (days > 366) {
            throw new BadRequestException("days must be 366 or fewer");
        }
        return days;
    }
}
