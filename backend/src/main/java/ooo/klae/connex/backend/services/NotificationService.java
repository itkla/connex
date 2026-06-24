package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;

/**
 * Authenticated notification inbox operations.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final Set<String> STATES = Set.of("active", "unread", "history", "all");

    private final NotificationMapper notificationMapper;
    private final AuthService authService;
    private final NotificationProperties properties;

    public PageResponse<NotificationDto> getPage(
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
        List<NotificationDto> items = notificationMapper.findPage(
            recipientId,
            normalizedState,
            normalizedCategory,
            normalizedContextType,
            contextId,
            cappedSize,
            offset
        ).stream().map(NotificationDto::from).toList();
        long total = notificationMapper.countPage(
            recipientId,
            normalizedState,
            normalizedCategory,
            normalizedContextType,
            contextId
        );
        return new PageResponse<>(items, total);
    }

    public NotificationCountsDto getUnreadCounts() {
        return notificationMapper.getUnreadCounts(currentRecipientId());
    }

    public NotificationDto markRead(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getReadAt() != null) {
            return NotificationDto.from(current);
        }
        requireMutation(notificationMapper.markRead(recipientId, id), id);
        return NotificationDto.from(requireNotification(recipientId, id));
    }

    public NotificationDto markUnread(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getReadAt() == null) {
            return NotificationDto.from(current);
        }
        requireMutation(notificationMapper.markUnread(recipientId, id), id);
        return NotificationDto.from(requireNotification(recipientId, id));
    }

    public NotificationDto dismiss(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getDismissedAt() != null || current.getResolvedAt() != null) {
            return NotificationDto.from(current);
        }
        requireMutation(notificationMapper.dismiss(recipientId, id), id);
        return NotificationDto.from(requireNotification(recipientId, id));
    }

    public NotificationDto restore(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireNotification(recipientId, id);
        if (current.getDismissedAt() == null && current.getResolvedAt() == null) {
            return NotificationDto.from(current);
        }
        requireMutation(notificationMapper.restore(recipientId, id), id);
        return NotificationDto.from(requireNotification(recipientId, id));
    }

    public NotificationCountsDto markAllRead() {
        int recipientId = currentRecipientId();
        notificationMapper.markAllRead(recipientId);
        return notificationMapper.getUnreadCounts(recipientId);
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