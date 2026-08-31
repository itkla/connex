package ooo.klae.connex.backend.work;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.dto.NotificationDto;
import ooo.klae.connex.backend.dto.NotificationWorkItem;
import ooo.klae.connex.backend.dto.NotificationWorkPage;
import ooo.klae.connex.backend.dto.WorkItemAction;
import ooo.klae.connex.backend.dto.WorkItemActionOutcome;
import ooo.klae.connex.backend.dto.WorkItemActionResponse;
import ooo.klae.connex.backend.dto.WorkItemContextDto;
import ooo.klae.connex.backend.dto.WorkItemDto;
import ooo.klae.connex.backend.dto.WorkItemEvidenceCode;
import ooo.klae.connex.backend.dto.WorkItemEvidenceDto;
import ooo.klae.connex.backend.dto.WorkItemReasonCode;
import ooo.klae.connex.backend.dto.WorkItemReasonDto;
import ooo.klae.connex.backend.dto.WorkItemSource;
import ooo.klae.connex.backend.dto.WorkItemUrgency;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.NotificationService;

/** Projects actionable deal-close notifications and delegates recipient-owned mutations. */
@Component
@RequiredArgsConstructor
public class ActionableNotificationWorkItemProvider implements WorkItemProvider {
    private final NotificationService notificationService;
    private final Clock clock;

    @Override
    public WorkItemSource source() {
        return WorkItemSource.notification;
    }

    @Override
    public WorkItemProviderResult load(WorkItemProviderQuery query) {
        NotificationWorkPage page = notificationService.findActiveDealCloseWork(
            query.workspaceId(), query.asOf(), query.urgencies(), query.candidateLimit());
        List<WorkItemDto> items = page.items().stream()
            .map(item -> toDto(item, query))
            .sorted(WorkItemOrdering.comparator())
            .toList();
        return new WorkItemProviderResult(
            items, page.matchingTotal(), page.overallTotal(), page.asOf());
    }

    @Override
    public WorkItemActionResponse execute(int sourceId, WorkItemActionCommand command) {
        NotificationDto result;
        if (command.action() == WorkItemAction.snooze && command.snooze() != null) {
            result = notificationService.snooze(
                sourceId, command.snooze(), command.expectedStateHash());
        } else if (command.action() == WorkItemAction.dismiss) {
            result = notificationService.dismiss(sourceId, command.expectedStateHash());
        } else {
            throw new BadRequestException("Unsupported notification work action");
        }
        return new WorkItemActionResponse(
            source(),
            sourceId,
            WorkItemActionOutcome.applied,
            true,
            result.getStateVersion(),
            clock.instant());
    }

    private WorkItemDto toDto(NotificationWorkItem item, WorkItemProviderQuery query) {
        Notification notification = item.notification();
        if (notification.getTitle() == null || notification.getTitle().isBlank()
                || notification.getContextType() == null
                || notification.getContextId() == null
                || notification.getContextLabel() == null
                || notification.getActionUrl() == null
                || !notification.getActionUrl().startsWith("/")) {
            throw new InvalidWorkItemSourceRowsException();
        }
        boolean overdue = item.expectedCloseDate().isBefore(query.actorToday());
        int days = Math.toIntExact(Math.abs(ChronoUnit.DAYS.between(
            query.actorToday(), item.expectedCloseDate())));
        WorkItemUrgency urgency = "critical".equals(notification.getSeverity())
            ? WorkItemUrgency.critical
            : WorkItemUrgency.high;
        return new WorkItemDto(
            "notification:" + notification.getId(),
            source(),
            notification.getId(),
            notification.getTitle(),
            new WorkItemReasonDto(
                overdue
                    ? WorkItemReasonCode.deal_close_overdue
                    : WorkItemReasonCode.deal_closing_soon,
                item.expectedCloseDate(),
                days,
                null),
            item.expectedCloseDate(),
            urgency,
            List.of(new WorkItemEvidenceDto(
                WorkItemEvidenceCode.deal_close_date,
                source(),
                notification.getId(),
                WorkItemProjectionSupport.instant(notification.getTriggeredAt()),
                item.expectedCloseDate(),
                notification.getContextLabel())),
            WorkItemProjectionSupport.instant(
                notification.getUpdatedAt() == null
                    ? notification.getTriggeredAt()
                    : notification.getUpdatedAt()),
            query.asOf(),
            item.currentVersion(),
            WorkItemProjectionSupport.etag(item.currentVersion()),
            new WorkItemContextDto(
                notification.getContextType(),
                notification.getContextId(),
                notification.getContextLabel(),
                notification.getActionUrl()),
            List.of(
                WorkItemAction.snooze,
                WorkItemAction.dismiss,
                WorkItemAction.open_context));
    }
}
