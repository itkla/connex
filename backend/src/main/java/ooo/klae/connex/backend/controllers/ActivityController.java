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
import ooo.klae.connex.backend.services.ActivityService;

import java.util.List;

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
    public List<Activity> getActivities(
        @RequestParam(required = false) Integer personId,
        @RequestParam(required = false) Integer dealId,
        @RequestParam(required = false) Integer createdById
    ) {
        if (personId != null) return activityService.getActivitiesByPersonId(personId);
        if (dealId != null) return activityService.getActivitiesByDealId(dealId);
        if (createdById != null) return activityService.getActivitiesByCreatedById(createdById);
        return activityService.getAllActivities();
    }

    /**
     * GET endpoint to retrieve a single activity by ID.
     * @param id
     * @return
     */
    @GetMapping("/{id}")
    public Activity getActivityById(@PathVariable int id) {
        return activityService.getActivityById(id);
    }

    /**
     * POST endpoint to create a new activity.
     * @param activity
     * @return
     */
    @PostMapping
    public Activity createActivity(@RequestBody Activity activity) {
        return activityService.create(activity);
    }

    /**
     * PUT endpoint to update an existing activity.
     * @param id
     * @param activity
     * @return
     */
    @PutMapping("/{id}")
    public Activity updateActivity(@PathVariable int id, @RequestBody Activity activity) {
        return activityService.update(id, activity);
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
