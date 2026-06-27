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
import ooo.klae.connex.backend.services.ActivityService;

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
        List<Activity> activities;
        if (personId != null) activities = activityService.getActivitiesByPersonId(personId);
        else if (dealId != null) activities = activityService.getActivitiesByDealId(dealId);
        else if (createdById != null) activities = activityService.getActivitiesByCreatedById(createdById);
        else activities = activityService.getAllActivities();
        List<ActivityDto> dtos = activities.stream().map(ActivityDto::from).toList();
        if (itemsPerPage == null) return dtos;
        int size = Math.min(Math.max(itemsPerPage, 1), 200);
        int page = paginationRange == null ? 1 : Math.max(paginationRange, 1);
        int from = (int) Math.min(Integer.MAX_VALUE, (long) (page - 1) * size);
        if (from >= dtos.size()) return List.of();
        return dtos.subList(from, Math.min(dtos.size(), from + size));
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
