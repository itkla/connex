package ooo.klae.connex.backend.work;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ApprovalInboxItemDto;
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
import ooo.klae.connex.backend.services.DocumentApprovalService;

/** Projects authoritative actionable approval steps and delegates exact-step decisions. */
@Component
@RequiredArgsConstructor
public class DocumentApprovalWorkItemProvider implements WorkItemProvider {
    private final DocumentApprovalService approvalService;
    private final Clock clock;

    @Override
    public WorkItemSource source() {
        return WorkItemSource.document_approval;
    }

    @Override
    public WorkItemProviderResult load(WorkItemProviderQuery query) {
        List<ApprovalInboxItemDto> sourceItems = approvalService.inbox(query.candidateLimit());
        boolean truncated = sourceItems.size() == query.candidateLimit();
        List<WorkItemDto> allItems = sourceItems.stream()
            .map(item -> toDto(item, query))
            .sorted(WorkItemOrdering.comparator())
            .toList();
        List<WorkItemDto> matching = query.urgencies().isEmpty()
            ? allItems
            : allItems.stream()
                .filter(item -> query.urgencies().contains(item.urgency()))
                .toList();
        List<WorkItemDto> candidates = matching.stream()
            .limit(query.candidateLimit())
            .toList();
        return new WorkItemProviderResult(
            candidates,
            matching.size(),
            allItems.size(),
            query.asOf(),
            !truncated);
    }

    @Override
    public WorkItemActionResponse execute(int sourceId, WorkItemActionCommand command) {
        if (command.stepId() == null
                || !((command.action() == WorkItemAction.approve
                        && "approved".equals(command.decision()))
                    || (command.action() == WorkItemAction.reject
                        && "rejected".equals(command.decision())))) {
            throw new BadRequestException("Unsupported approval work action");
        }
        approvalService.decideWorkItem(
            sourceId,
            command.stepId(),
            command.decision(),
            command.comment(),
            command.expectedStateHash());
        return new WorkItemActionResponse(
            source(), sourceId, WorkItemActionOutcome.applied, true, null, clock.instant());
    }

    private WorkItemDto toDto(ApprovalInboxItemDto item, WorkItemProviderQuery query) {
        if ((item.documentTitle() == null || item.documentTitle().isBlank())
                && (item.documentType() == null || item.documentType().isBlank())
                || item.dealName() == null || item.dealName().isBlank()
                || item.currentVersion() == null || item.currentVersion().isBlank()) {
            throw new InvalidWorkItemSourceRowsException();
        }
        Instant dueAt = item.dueAt() == null ? null
            : WorkItemProjectionSupport.instant(item.dueAt());
        LocalDate dueDate = dueAt == null ? null : LocalDate.ofInstant(dueAt, ZoneOffset.UTC);
        WorkItemUrgency urgency = item.escalated()
                || (dueAt != null && dueAt.isBefore(query.asOf()))
            ? WorkItemUrgency.high
            : WorkItemUrgency.normal;
        Integer days = dueDate == null ? null : Math.toIntExact(Math.abs(
            ChronoUnit.DAYS.between(query.actorToday(), dueDate)));
        String title = item.documentTitle() == null || item.documentTitle().isBlank()
            ? item.documentType() + " v" + item.version()
            : item.documentTitle();
        return new WorkItemDto(
            "document_approval:" + item.approvalId() + ":" + item.stepId(),
            source(),
            item.approvalId(),
            title,
            new WorkItemReasonDto(
                WorkItemReasonCode.document_approval_pending,
                dueDate,
                days,
                item.requestedByLabel()),
            dueDate,
            urgency,
            List.of(new WorkItemEvidenceDto(
                WorkItemEvidenceCode.approval_requested,
                source(),
                item.approvalId(),
                WorkItemProjectionSupport.instant(item.requestedAt()),
                dueDate,
                item.stepName() == null ? title : item.stepName())),
            item.freshnessAt(),
            query.asOf(),
            item.currentVersion(),
            WorkItemProjectionSupport.etag(item.currentVersion()),
            new WorkItemContextDto(
                "deal",
                item.dealId(),
                item.dealName(),
                "/records/deals/" + item.dealId(),
                item.stepId(),
                item.stepOrder(),
                item.stepName(),
                item.requiredCount(),
                item.escalated()),
            List.of(
                WorkItemAction.approve,
                WorkItemAction.reject,
                WorkItemAction.open_context));
    }
}
