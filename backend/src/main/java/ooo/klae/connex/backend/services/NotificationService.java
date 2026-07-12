package ooo.klae.connex.backend.services;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationPageDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

/**
 * Authenticated notification inbox operations.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Set<String> STATES = Set.of("active", "unread", "history", "all");
    private static final DateTimeFormatter UTC_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationMapper notificationMapper;
    private final AuthService authService;
    private final NotificationProperties properties;
    private final NotificationStateVersionService stateVersionService;

    @Transactional(readOnly = true)
    public NotificationPageDto getPage(
        String state,
        String category,
        String contextType,
        Integer contextId,
        int page,
        int size
    ) {
        int recipientId = currentRecipientId();
        String normalizedState = normalizeState(state);
        String normalizedCategory = blankToNull(category);
        String normalizedContextType = blankToNull(contextType);
        validateContext(normalizedContextType, contextId);
        int cappedSize = Math.max(1, Math.min(size, Math.max(1, properties.getMaxPageSize())));
        int normalizedPage = Math.max(1, page);
        long offsetValue = (long) (normalizedPage - 1) * cappedSize;
        if (offsetValue > Integer.MAX_VALUE) {
            throw new BadRequestException("Notification page is too large");
        }
        int offset = (int) offsetValue;
        String asOf = notificationMapper.getDatabaseUtcTimestamp();
        List<NotificationDto> items = notificationMapper.findPage(
            recipientId,
            normalizedState,
            normalizedCategory,
            normalizedContextType,
            contextId,
            asOf,
            cappedSize,
            offset
        ).stream().map(NotificationDto::from).toList();
        long total = notificationMapper.countPage(
            recipientId,
            normalizedState,
            normalizedCategory,
            normalizedContextType,
            contextId,
            asOf
        );
        long stateVersion = notificationMapper.getStateVersion(recipientId);
        return new NotificationPageDto(items, total, stateVersion);
    }

    @Transactional(readOnly = true)
    public NotificationCountsDto getUnreadCounts() {
        int recipientId = currentRecipientId();
        String asOf = notificationMapper.getDatabaseUtcTimestamp();
        return countsAt(recipientId, asOf);
    }

    @Transactional
    public NotificationDto markRead(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getReadAt() != null) {
            return response(recipientId, current);
        }
        requireMutation(notificationMapper.markRead(recipientId, id), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    @Transactional
    public NotificationDto markUnread(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getReadAt() == null) {
            return response(recipientId, current);
        }
        requireMutation(notificationMapper.markUnread(recipientId, id), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    @Transactional
    public NotificationDto dismiss(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getDismissedAt() != null || current.getResolvedAt() != null) {
            return response(recipientId, current);
        }
        requireMutation(notificationMapper.dismiss(recipientId, id), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    @Transactional
    public NotificationDto restore(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getDismissedAt() == null && current.getResolvedAt() == null) {
            return response(recipientId, current);
        }
        requireMutation(notificationMapper.restore(recipientId, id), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    /**
     * Hides an active notification from the inbox for {@code hours} hours, after which the next
     * inbox read surfaces it again. A dismissed or resolved notification is left untouched.
     */
    @Transactional
    public NotificationDto snooze(int id, int hours) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getDismissedAt() != null || current.getResolvedAt() != null) {
            return response(recipientId, current);
        }
        String snoozedUntil = LocalDateTime
            .ofInstant(Instant.now().plus(Duration.ofHours(hours)), ZoneOffset.UTC)
            .format(UTC_DATETIME);
        requireMutation(notificationMapper.snooze(recipientId, id, snoozedUntil), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    @Transactional
    public NotificationCountsDto markAllRead() {
        int recipientId = currentRecipientId();
        notificationMapper.lockRecipientMemberships(recipientId);
        String readAt = notificationMapper.getDatabaseUtcTimestamp();
        long cutoffId = notificationMapper.getInboxCutoffId(recipientId);
        int rows = notificationMapper.markAllRead(recipientId, cutoffId, readAt);
        if (rows > 0) {
            stateVersionService.bumpNow(recipientId);
        }
        NotificationCountsDto counts = countsAt(recipientId, readAt);
        counts.setCutoffId(cutoffId);
        counts.setReadAt(readAt);
        return counts;
    }

    private NotificationDto response(int recipientId, Notification notification) {
        NotificationDto dto = NotificationDto.from(notification);
        dto.setStateVersion(notificationMapper.getStateVersion(recipientId));
        return dto;
    }

    private NotificationDto mutationResponse(int recipientId, Notification notification) {
        NotificationDto dto = NotificationDto.from(notification);
        dto.setStateVersion(stateVersionService.bumpNow(recipientId));
        return dto;
    }

    private NotificationCountsDto countsAt(int recipientId, String asOf) {
        NotificationCountsDto counts = notificationMapper.getUnreadCounts(recipientId, asOf);
        counts.setAsOf(asOf);
        counts.setNextSnoozeExpiry(notificationMapper.getNextSnoozeExpiry(recipientId, asOf));
        return counts;
    }

    private int currentRecipientId() {
        return authService.getCurrentUser().getId();
    }

    private Notification requireNotification(int recipientId, int id) {
        Notification notification = notificationMapper.findById(recipientId, id);
        if (notification == null) {
            throw notFound(id);
        }
        return notification;
    }

    private static void requireMutation(int rows, int id) {
        if (rows == 0) {
            throw notFound(id);
        }
    }

    private static ResourceNotFoundException notFound(int id) {
        return new ResourceNotFoundException("Notification not found with id: " + id);
    }

    private static String normalizeState(String state) {
        String normalized = blankToNull(state);
        normalized = normalized == null ? "active" : normalized.toLowerCase(Locale.ROOT);
        if (!STATES.contains(normalized)) {
            throw new BadRequestException("Unsupported notification state: " + state);
        }
        return normalized;
    }

    private static void validateContext(String contextType, Integer contextId) {
        if ((contextType == null) != (contextId == null)) {
            throw new BadRequestException("contextType and contextId must be provided together");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
