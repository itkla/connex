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
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ActivityService;
import ooo.klae.connex.backend.util.PageBounds;

import java.util.List;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for logging and retrieving {@code Activity} records.
 * Accepts and returns {@code ActivityDto}. Delegates to {@code ActivityService}.
 */

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {
    private final ActivityService activityService;

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
}
