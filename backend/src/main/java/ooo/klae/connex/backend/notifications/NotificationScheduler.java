package ooo.klae.connex.backend.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.services.NotificationReconciliationService;

/**
 * Runs reconciliation per explicit workspace without an authentication context.
 * Workspace enumeration reads the control-plane {@code workspace} table (which
 * only exists on the default catalog), so it runs unrouted — no catalog
 * fan-out; {@code inWorkspace} then pins each workspace's own catalog.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.notifications",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope tenantWorkScope;
    private final NotificationReconciliationService reconciliationService;

    @Scheduled(
        fixedDelayString = "${connex.notifications.reconciliation-delay-ms:300000}",
        initialDelayString = "${connex.notifications.initial-delay-ms:300000}"
    )
    public void reconcileAndPurge() {
        for (Integer workspaceId : tenantWorkScope.unrouted(workspaceMapper::findWorkspaceIds)) {
            try {
                tenantWorkScope.inWorkspace(workspaceId, () -> {
                    reconciliationService.reconcileWorkspace(workspaceId, true);
                    reconciliationService.purgeWorkspace(workspaceId);
                });
            } catch (Exception exception) {
                log.error("Scheduled notification reconciliation failed for workspace={}", workspaceId, exception);
            }
        }
    }
}
