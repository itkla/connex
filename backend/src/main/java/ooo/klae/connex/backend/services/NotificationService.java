package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationCountsDto;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationFacets;
import ooo.klae.connex.backend.dto.NotificationPageDto;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
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
    private static final Set<String> STATUSES = Set.of("active", "unread", "snoozed", "history", "all");
    private static final int MAX_FILTER_VALUES = 50;
    private static final DateTimeFormatter UTC_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationMapper notificationMapper;
    private final AuthService authService;
    private final NotificationProperties properties;
    private final NotificationStateVersionService stateVersionService;
    private final NotificationSnoozeResolver snoozeResolver;
    private final NotificationQuietHoursService quietHoursService;
    private final WorkspaceService workspaceService;

    @Transactional(readOnly = true)
    public NotificationPageDto getPage(
        String state,
        String category,
        String contextType,
        Integer contextId,
        int page,
        int size
    ) {
        return getPage(
            null,
            state,
            null,
            category == null ? null : List.of(category),
            null,
            null,
            contextType,
            contextId,
            page,
            size
        );
    }

    @Transactional(readOnly = true)
    public NotificationPageDto getPage(
        String status,
        String state,
        List<String> types,
        List<String> categories,
        List<String> severities,
        Integer workspaceId,
        String contextType,
        Integer contextId,
        int page,
        int size
    ) {
        int recipientId = currentRecipientId();
        if (status != null && state != null) {
            throw new BadRequestException("Provide status or state, not both");
        }
        String normalizedStatus = normalizeStatus(status == null ? state : status);
        List<String> normalizedTypes = normalizeFilters(types, "type", 64);
        List<String> normalizedCategories = normalizeFilters(categories, "category", 32);
        List<String> normalizedSeverities = normalizeFilters(severities, "severity", 16);
        String normalizedContextType = blankToNull(contextType);
        validateContext(normalizedContextType, contextId);
        validateWorkspaceFilter(workspaceId, recipientId);
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
            normalizedStatus,
            normalizedTypes,
            normalizedCategories,
            normalizedSeverities,
            workspaceId,
            normalizedContextType,
            contextId,
            asOf,
            cappedSize,
            offset
        ).stream().map(NotificationDto::from).toList();
        long total = notificationMapper.countPage(
            recipientId,
            normalizedStatus,
            normalizedTypes,
            normalizedCategories,
            normalizedSeverities,
            workspaceId,
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

    @Transactional(readOnly = true)
    public NotificationFacets getFacets() {
        int recipientId = currentRecipientId();
        return new NotificationFacets(
            notificationMapper.countsByCategory(recipientId),
            notificationMapper.countsBySeverity(recipientId),
            notificationMapper.countsByWorkspace(recipientId)
        );
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

    @Transactional
    public NotificationDto snooze(int id, SnoozeRequest request) {
        int recipientId = currentRecipientId();
        Notification current = requireLockedNotification(recipientId, id);
        if (current.getDismissedAt() != null || current.getResolvedAt() != null) {
            throw new ConflictException("Dismissed or resolved notifications cannot be snoozed");
        }
        NotificationSnoozeResolver.Resolution resolution = snoozeResolver.resolve(request);
        int rows = notificationMapper.snooze(
            recipientId,
            id,
            resolution.databaseTimestamp(),
            resolution.timezone()
        );
        if (rows == 0) {
            Notification latest = requireNotification(recipientId, id);
            if (latest.getDismissedAt() != null || latest.getResolvedAt() != null) {
                throw new ConflictException("Dismissed or resolved notifications cannot be snoozed");
            }
            if (resolution.databaseTimestamp().equals(latest.getSnoozedUntil())
                    && resolution.timezone().equals(latest.getSnoozeTimezone())) {
                return response(recipientId, latest);
            }
            throw notFound(id);
        }
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    @Transactional
    public NotificationDto unsnooze(int id) {
        int recipientId = currentRecipientId();
        Notification current = requireLockedNotification(recipientId, id);
        if (current.getSnoozedUntil() == null) {
            return response(recipientId, current);
        }
        int rows = notificationMapper.unsnooze(recipientId, id);
        if (rows == 0) {
            Notification latest = requireNotification(recipientId, id);
            if (latest.getSnoozedUntil() == null) {
                return response(recipientId, latest);
            }
            throw notFound(id);
        }
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
        Instant snapshot = databaseTimestamp(asOf);
        NotificationQuietHoursEvaluator.Evaluation quietHours =
            quietHoursService.evaluateForUser(recipientId, snapshot);
        counts.setAsOf(snapshot.toString());
        counts.setNextSnoozeExpiry(
            toUtcInstant(notificationMapper.getNextSnoozeExpiry(recipientId, asOf)));
        counts.setQuietHoursActive(quietHours.active());
        counts.setNextQuietHoursTransition(
            quietHours.nextTransitionAt() == null ? null : quietHours.nextTransitionAt().toString());
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

    private Notification requireLockedNotification(int recipientId, int id) {
        Notification notification = notificationMapper.findByIdForUpdate(recipientId, id);
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

    private void validateWorkspaceFilter(Integer workspaceId, int recipientId) {
        if (workspaceId == null) {
            return;
        }
        if (workspaceId < 1) {
            throw new BadRequestException("workspaceId must be positive");
        }
        if (workspaceService.getRole(workspaceId, recipientId) == null) {
            throw new ResourceNotFoundException("Workspace not found with id: " + workspaceId);
        }
    }

    private static String normalizeStatus(String status) {
        String normalized = blankToNull(status);
        normalized = normalized == null ? "active" : normalized.toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new BadRequestException("Unsupported notification status: " + status);
        }
        return normalized;
    }

    private static List<String> normalizeFilters(List<String> values, String name, int maxLength) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > MAX_FILTER_VALUES) {
            throw new BadRequestException("Too many notification " + name + " filters");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String item = blankToNull(value);
            if (item == null || item.length() > maxLength) {
                throw new BadRequestException("Invalid notification " + name + " filter");
            }
            normalized.add(item);
        }
        return List.copyOf(normalized);
    }

    private static void validateContext(String contextType, Integer contextId) {
        if ((contextType == null) != (contextId == null)) {
            throw new BadRequestException("contextType and contextId must be provided together");
        }
        if (contextType != null && (contextType.length() > 32 || contextId < 1)) {
            throw new BadRequestException("Invalid notification context filter");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Instant databaseTimestamp(String value) {
        try {
            return LocalDateTime.parse(value, UTC_DATETIME).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Invalid database UTC timestamp", exception);
        }
    }

    private static String toUtcInstant(String value) {
        return value == null ? null : databaseTimestamp(value).toString();
    }
}
