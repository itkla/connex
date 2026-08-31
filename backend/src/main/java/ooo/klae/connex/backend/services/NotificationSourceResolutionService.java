package ooo.klae.connex.backend.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;

/** Resolves stale source-owned notifications without weakening approval eligibility. */
@Service
public class NotificationSourceResolutionService {
    private static final DateTimeFormatter MYSQL_DATETIME =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationMapper notificationMapper;
    private final NotificationStateVersionService stateVersionService;
    private final DocumentApprovalService approvalService;

    /** Creates the transactional approval-notification reconciler. */
    public NotificationSourceResolutionService(
            NotificationMapper notificationMapper,
            NotificationStateVersionService stateVersionService,
            @Lazy DocumentApprovalService approvalService) {
        this.notificationMapper = notificationMapper;
        this.stateVersionService = stateVersionService;
        this.approvalService = approvalService;
    }

    /** Returns unresolved request-notification recipients in deterministic membership-lock order. */
    public Set<Integer> approvalRequestRecipientIds(int workspaceId, int documentId) {
        return Set.copyOf(new TreeSet<>(
            notificationMapper.findUnresolvedApprovalRequestRecipientIds(workspaceId, documentId)));
    }

    /** Resolves request notifications only for prelocked recipients with no actionable step. */
    public void resolveApprovalRequests(
            int workspaceId,
            int documentId,
            Instant resolvedAt,
            Set<Integer> lockedRecipientIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Approval notification resolution requires a transaction");
        }
        Set<Integer> recipients = new TreeSet<>(
            notificationMapper.findUnresolvedApprovalRequestRecipientIds(workspaceId, documentId));
        if (recipients.isEmpty()) {
            return;
        }
        if (lockedRecipientIds == null || !lockedRecipientIds.containsAll(recipients)) {
            throw new ApprovalRecipientSetChangedException();
        }
        Set<Integer> actionable = approvalService.actionableRecipientIdsForDocument(
            workspaceId, documentId, recipients);
        TreeSet<Integer> resolvedRecipients = new TreeSet<>(recipients);
        resolvedRecipients.removeAll(actionable);
        String timestamp = LocalDateTime.ofInstant(resolvedAt, ZoneOffset.UTC)
            .format(MYSQL_DATETIME);
        for (int recipientId : resolvedRecipients) {
            if (notificationMapper.resolveApprovalRequestsForRecipient(
                    workspaceId, documentId, recipientId, timestamp) > 0) {
                stateVersionService.markChanged(recipientId);
            }
        }
    }
}
