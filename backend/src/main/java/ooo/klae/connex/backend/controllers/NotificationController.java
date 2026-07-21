package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationFacets;
import ooo.klae.connex.backend.dto.NotificationPreferenceDto;
import ooo.klae.connex.backend.dto.NotificationPageDto;
import ooo.klae.connex.backend.dto.NotificationQuietHoursDto;
import ooo.klae.connex.backend.dto.NotificationQuietHoursRequest;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.services.NotificationPreferenceService;
import ooo.klae.connex.backend.services.NotificationQuietHoursService;
import ooo.klae.connex.backend.services.NotificationService;

/**
 * Authenticated notification inbox and preference endpoints.
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;
    private final NotificationQuietHoursService quietHoursService;

    @GetMapping("/api/notifications")
    public NotificationPageDto getNotifications(
        @RequestParam(name = "status", required = false) String status,
        @RequestParam(name = "state", required = false) String state,
        @RequestParam(name = "type", required = false) List<String> types,
        @RequestParam(name = "category", required = false) List<String> categories,
        @RequestParam(name = "severity", required = false) List<String> severities,
        @RequestParam(name = "workspaceId", required = false) Integer workspaceId,
        @RequestParam(name = "contextType", required = false) String contextType,
        @RequestParam(name = "contextId", required = false) Integer contextId,
        @RequestParam(name = "page", defaultValue = "1") int page,
        @RequestParam(name = "size", defaultValue = "25") int size
    ) {
        return notificationService.getPage(
            status,
            state,
            types,
            categories,
            severities,
            workspaceId,
            contextType,
            contextId,
            page,
            size
        );
    }

    @GetMapping("/api/notifications/counts")
    public NotificationCountsDto getCounts() {
        return notificationService.getUnreadCounts();
    }

    @GetMapping("/api/notifications/facets")
    public NotificationFacets getFacets() {
        return notificationService.getFacets();
    }

    @PostMapping("/api/notifications/{id}/read")
    public NotificationDto markRead(@PathVariable int id) {
        return notificationService.markRead(id);
    }

    @PostMapping("/api/notifications/{id}/unread")
    public NotificationDto markUnread(@PathVariable int id) {
        return notificationService.markUnread(id);
    }

    @PostMapping("/api/notifications/{id}/dismiss")
    public NotificationDto dismiss(@PathVariable int id) {
        return notificationService.dismiss(id);
    }

    @PostMapping("/api/notifications/{id}/restore")
    public NotificationDto restore(@PathVariable int id) {
        return notificationService.restore(id);
    }

    @PostMapping("/api/notifications/{id}/snooze")
    public NotificationDto snooze(@PathVariable int id, @Valid @RequestBody SnoozeRequest request) {
        return notificationService.snooze(id, request);
    }

    @PostMapping("/api/notifications/{id}/unsnooze")
    public NotificationDto unsnooze(@PathVariable int id) {
        return notificationService.unsnooze(id);
    }

    @PostMapping("/api/notifications/read-all")
    public NotificationCountsDto markAllRead() {
        return notificationService.markAllRead();
    }

    @GetMapping("/api/notification-preferences")
    public List<NotificationPreferenceDto> getPreferences() {
        return preferenceService.getCurrentPreferences();
    }

    @PutMapping("/api/notification-preferences")
    public List<NotificationPreferenceDto> updatePreferences(
        @RequestBody List<@Valid NotificationPreferenceDto> preferences
    ) {
        return preferenceService.updateCurrentPreferences(preferences);
    }

    @GetMapping("/api/notification-preferences/quiet-hours")
    public NotificationQuietHoursDto getQuietHours() {
        return quietHoursService.getCurrent();
    }

    @PutMapping("/api/notification-preferences/quiet-hours")
    public NotificationQuietHoursDto updateQuietHours(
        @Valid @RequestBody NotificationQuietHoursRequest request
    ) {
        return quietHoursService.updateCurrent(request);
    }
}
