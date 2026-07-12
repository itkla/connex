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
import ooo.klae.connex.backend.dto.NotificationPreferenceDto;
import ooo.klae.connex.backend.dto.NotificationPageDto;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.services.NotificationPreferenceService;
import ooo.klae.connex.backend.services.NotificationService;

/**
 * Authenticated notification inbox and preference endpoints.
 */
@RestController
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notificationService;
    private final NotificationPreferenceService preferenceService;

    @GetMapping("/api/notifications")
    public NotificationPageDto getNotifications(
        @RequestParam(defaultValue = "active") String state,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String contextType,
        @RequestParam(required = false) Integer contextId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return notificationService.getPage(
            state,
            category,
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
        return notificationService.snooze(id, request.getHours());
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
}
