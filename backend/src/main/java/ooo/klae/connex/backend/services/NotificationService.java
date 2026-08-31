package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import ooo.klae.connex.backend.dto.NotificationWorkItem;
import ooo.klae.connex.backend.dto.NotificationWorkPage;
import ooo.klae.connex.backend.dto.SnoozeRequest;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationProperties;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.work.InvalidWorkItemSourceRowsException;
import ooo.klae.connex.backend.work.WorkItemStateHash;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
    private final DocumentApprovalService documentApprovalService;
    private final ApprovalMutationRetryService approvalMutationRetryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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
        return new NotificationPageDto(items, total, stateVersion, databaseTimestamp(asOf).toString());
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
            notificationMapper.countsByWorkspace(recipientId),
            notificationMapper.getStateVersion(recipientId)
        );
    }

    /** Returns bounded active-workspace deal-close work for the current recipient. */
    @Transactional(readOnly = true)
    public NotificationWorkPage findActiveDealCloseWork(
            int workspaceId,
            Instant asOf,
            int limit) {
        return findActiveDealCloseWork(workspaceId, asOf, Set.of(), limit);
    }

    /** Returns bounded urgency-filtered deal-close work for the current recipient. */
    @Transactional(readOnly = true)
    public NotificationWorkPage findActiveDealCloseWork(
            int workspaceId,
            Instant asOf,
            Set<WorkItemUrgency> urgencies,
            int limit) {
        if (workspaceId != workspaceService.getCurrentWorkspaceId()
                || asOf == null || urgencies == null || limit < 1 || limit > 1000) {
            throw new BadRequestException("Invalid notification-work query");
        }
        int recipientId = currentRecipientId();
        List<String> severities = workSeverities(urgencies);
        String snapshot = utcTimestamp(asOf);
        List<Notification> rows = !urgencies.isEmpty() && severities.isEmpty()
            ? List.of()
            : notificationMapper.findActiveDealCloseWork(
                workspaceId, recipientId, snapshot, severities, limit);
        List<NotificationWorkItem> items = new ArrayList<>(rows.size());
        for (Notification row : rows) {
            items.add(toWorkItem(row));
        }
        long matchingTotal = !urgencies.isEmpty() && severities.isEmpty()
            ? 0
            : notificationMapper.countActiveDealCloseWork(
                workspaceId, recipientId, snapshot, severities);
        long overallTotal = urgencies.isEmpty()
            ? matchingTotal
            : notificationMapper.countActiveDealCloseWork(
                workspaceId, recipientId, snapshot, List.of());
        return new NotificationWorkPage(List.copyOf(items), matchingTotal, overallTotal, asOf);
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

    /** Dismisses active deal-close work only when its locked canonical state still matches. */
    @Transactional
    public NotificationDto dismiss(int id, String expectedStateHash) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int recipientId = currentRecipientId();
        Notification current = notificationMapper.findActiveDealCloseByIdForUpdate(
            workspaceId, recipientId, id, utcTimestamp(clock.instant()),
            List.of("critical", "warning"));
        requireExpectedWorkState(current, id, expectedStateHash);
        requireMutation(notificationMapper.dismiss(recipientId, id), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    public NotificationDto restore(int id) {
        int recipientId = currentRecipientId();
        return approvalMutationRetryService.execute(() -> restoreLocked(recipientId, id));
    }

    private NotificationDto restoreLocked(int recipientId, int id) {
        Notification current = requireNotification(recipientId, id);
        if ("document.approval_request".equals(current.getType())
                && "deal_document".equals(current.getSourceType())) {
            return restoreApprovalRequest(recipientId, id, current);
        }
        if (current.getDismissedAt() == null && current.getResolvedAt() == null) {
            return response(recipientId, current);
        }
        requireMutation(notificationMapper.restore(recipientId, id), id);
        return mutationResponse(recipientId, requireNotification(recipientId, id));
    }

    private NotificationDto restoreApprovalRequest(
            int recipientId, int id, Notification current) {
        Integer documentId = current.getSourceId();
        Integer dealId = current.getContextId();
        if (documentId == null || dealId == null) {
            throw new ConflictException("Approval request changed; refresh and try again");
        }
        int workspaceId = current.getWorkspaceId();
        DocumentApprovalService.ApprovalMutationLocks locks =
            documentApprovalService.lockApprovalMutationRecipients(
                workspaceId, documentId, recipientId);
        boolean actionable = documentApprovalService.approvalRequestActionableForRestore(
            workspaceId, dealId, documentId, recipientId, locks);
        Notification locked = requireLockedNotification(recipientId, id);
        if (locked.getWorkspaceId() != workspaceId
                || !documentId.equals(locked.getSourceId())
                || !"document.approval_request".equals(locked.getType())
                || !"deal_document".equals(locked.getSourceType())) {
            throw new ApprovalRecipientSetChangedException();
        }
        if (actionable) {
            if (locked.getDismissedAt() == null && locked.getResolvedAt() == null) {
                return response(recipientId, locked);
            }
            requireMutation(notificationMapper.restoreActionableApprovalRequest(
                workspaceId, recipientId, id), id);
        } else {
            if (locked.getDismissedAt() == null && locked.getResolvedAt() != null) {
                return response(recipientId, locked);
            }
            requireMutation(notificationMapper.restoreResolvedApprovalRequest(
                workspaceId, recipientId, id), id);
        }
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

    /** Snoozes active deal-close work only when its locked canonical state still matches. */
    @Transactional
    public NotificationDto snooze(
            int id,
            SnoozeRequest request,
            String expectedStateHash) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int recipientId = currentRecipientId();
        Notification current = notificationMapper.findActiveDealCloseByIdForUpdate(
            workspaceId, recipientId, id, utcTimestamp(clock.instant()),
            List.of("critical", "warning"));
        requireExpectedWorkState(current, id, expectedStateHash);
        NotificationSnoozeResolver.Resolution resolution = snoozeResolver.resolve(request);
        int rows = notificationMapper.snooze(
            recipientId, id, resolution.databaseTimestamp(), resolution.timezone());
        if (rows == 0) {
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

    private void requireExpectedWorkState(
            Notification current,
            int id,
            String expectedStateHash) {
        if (current == null) {
            throw notFound(id);
        }
        if (expectedStateHash == null || expectedStateHash.isBlank()) {
            throw new BadRequestException("Expected notification state is required");
        }
        if (!workItemVersion(current).equals(expectedStateHash)) {
            throw new ConflictException("Notification changed; refresh and try again");
        }
    }

    private NotificationWorkItem toWorkItem(Notification notification) {
        try {
            if (!("critical".equals(notification.getSeverity())
                    || "warning".equals(notification.getSeverity()))
                    || notification.getData() == null) {
                throw new InvalidWorkItemSourceRowsException();
            }
            JsonNode data = objectMapper.readTree(notification.getData());
            JsonNode dateNode = data.get("expectedCloseDate");
            JsonNode dealIdNode = data.get("dealId");
            if (dateNode == null || !dateNode.isTextual()
                    || dealIdNode == null || !dealIdNode.canConvertToInt()
                    || notification.getSourceId() == null
                    || dealIdNode.intValue() != notification.getSourceId()) {
                throw new InvalidWorkItemSourceRowsException();
            }
            return new NotificationWorkItem(
                notification,
                LocalDate.parse(dateNode.textValue()),
                workItemVersion(notification));
        } catch (InvalidWorkItemSourceRowsException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidWorkItemSourceRowsException(exception);
        }
    }

    private static List<String> workSeverities(Set<WorkItemUrgency> urgencies) {
        List<String> severities = new ArrayList<>(2);
        if (urgencies.isEmpty() || urgencies.contains(WorkItemUrgency.critical)) {
            severities.add("critical");
        }
        if (urgencies.isEmpty() || urgencies.contains(WorkItemUrgency.high)) {
            severities.add("warning");
        }
        return List.copyOf(severities);
    }

    private static String workItemVersion(Notification notification) {
        return WorkItemStateHash.sha256(
            notification.getId(),
            notification.getSeverity(),
            notification.getData(),
            notification.getReadAt(),
            notification.getDismissedAt(),
            notification.getResolvedAt(),
            notification.getSnoozedUntil(),
            notification.getSnoozeTimezone(),
            notification.getUpdatedAt());
    }

    private static String utcTimestamp(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).format(UTC_DATETIME);
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
