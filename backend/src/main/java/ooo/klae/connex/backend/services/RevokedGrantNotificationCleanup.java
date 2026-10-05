package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.dto.RevokedInvitationDto;
import ooo.klae.connex.backend.mappers.NotificationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.notifications.NotificationStateVersionService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Clears the notifications a verified email change left behind in each workspace whose pending
 * invitation it revoked (#1708). The revocation itself is control-plane and commits atomically
 * with the change; notifications and their baselines are tenant data, so they are deleted
 * afterwards, one transaction per workspace under that workspace's routed catalog. A single
 * transaction cannot span catalogs, and the email change's own transaction cannot install one.
 *
 * <p>Each workspace gets one best-effort attempt. A unit that fails (an unservable placement, a
 * lock failure, a crash) leaves that workspace's rows in place, possibly indefinitely, because
 * account erasure does not visit every catalog. Those rows stay out of the inbox, which needs a
 * membership the revocation removed, and a fresh membership purges them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RevokedGrantNotificationCleanup {

    private final TenantWorkScope tenantWorkScope;
    private final TransactionTemplate transactionTemplate;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final NotificationMapper notificationMapper;
    private final NotificationStateVersionService notificationStateVersionService;

    /**
     * Runs one cleanup unit per revoked workspace, in ascending workspace id. Each unit locks the
     * account root, the workspace root and then the account's ordered membership set, as declining
     * an invitation does, and skips a workspace where the account holds any membership again, so a
     * re-invitation or an acceptance committed since the change keeps its notifications. A failed
     * unit is logged by type and never affects the others or the caller.
     *
     * @param userId the account whose address changed
     * @param revoked the invitations the change revoked
     * @throws IllegalStateException when called inside a transaction, where the routed units could
     *     neither see the committed revocation nor install their catalogs
     */
    public void cleanUp(int userId, List<RevokedInvitationDto> revoked) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Revoked-grant notification cleanup must run after the email change commits");
        }
        revoked.stream()
            .map(RevokedInvitationDto::getWorkspaceId)
            .distinct()
            .sorted()
            .forEach(workspaceId -> {
                try {
                    tenantWorkScope.inLifecycleWorkspace(workspaceId, () -> transactionTemplate
                        .executeWithoutResult(status -> cleanUpWorkspace(userId, workspaceId)));
                } catch (RuntimeException exception) {
                    log.warn("Revoked-grant notification cleanup failed in workspace {}: {}",
                        workspaceId, exception.getClass().getSimpleName());
                }
            });
    }

    private void cleanUpWorkspace(int userId, int workspaceId) {
        if (userMapper.lockById(userId) == null) {
            return;
        }
        workspaceMapper.lockWorkspace(workspaceId);
        if (notificationMapper.lockRecipientMemberships(userId).contains(workspaceId)) {
            return;
        }
        notificationMapper.deleteHistoricalNotificationBaselinesForRecipient(workspaceId, userId);
        notificationMapper.deleteAllForRecipient(workspaceId, userId);
        notificationStateVersionService.markChanged(userId);
    }
}
