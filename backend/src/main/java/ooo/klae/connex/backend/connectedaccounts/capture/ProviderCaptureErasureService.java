package ooo.klae.connex.backend.connectedaccounts.capture;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Explicit current-workspace erasure of one user's captured provider evidence.
 */
@Service
@RequiredArgsConstructor
public class ProviderCaptureErasureService {
    private final ConnectedAccountProviders providers;
    private final WorkspaceService workspaceService;
    private final SessionSecurityService sessionSecurityService;
    private final ProviderCapturePurgeService purgeService;
    private final AuditService auditService;
    private final PlatformTransactionManager transactionManager;
    private final TenantWorkScope tenantWorkScope;

    /** Erases current-workspace capture without requiring an active connection or capture flag. */
    public ProviderCaptureOverviewDto.PurgeState eraseCurrent(String provider) {
        requireSupported(provider);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        sessionSecurityService.requireRecentAuthentication(userId);
        int orgId = workspaceService.getOrgId(workspaceId);
        recordStrictRequest(workspaceId, orgId, provider);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            workspaceService.lockAndRequireMember(workspaceId, userId);
            purgeService.purge(workspaceId, userId, provider);
        });
        recordCompletion(workspaceId, orgId, provider);
        return new ProviderCaptureOverviewDto.PurgeState(false, "idle", null);
    }

    private void requireSupported(String provider) {
        if (!providers.isSupported(provider)) {
            throw new ResourceNotFoundException("Unknown provider: " + provider);
        }
    }

    private void recordStrictRequest(
            int workspaceId, int orgId, String provider) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordStrictIndependentScoped(
                "provider.capture.erase",
                "workspace",
                workspaceId,
                workspaceId,
                orgId,
                provider,
                "Requested erasure of captured provider data for the current member",
                Map.of("provider", provider));
            return null;
        });
    }

    private void recordCompletion(
            int workspaceId, int orgId, String provider) {
        tenantWorkScope.unrouted(() -> {
            auditService.recordScoped(
                "provider.capture.erase.complete",
                "workspace",
                workspaceId,
                workspaceId,
                orgId,
                provider,
                "Erased captured provider data for the current member",
                Map.of("provider", provider));
            return null;
        });
    }
}
