package ooo.klae.connex.backend.connectedaccounts;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.capture.ProviderCapturePurgeService;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Durable disconnect orchestration across control and tenant catalogs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderConnectionLifecycleService {
    private static final int RETRY_BATCH = 25;
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final ProviderConnectionMapper connectionMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final TenantLifecycleControlMapper lifecycleControlMapper;
    private final TenantWorkScope tenantWorkScope;
    private final ProviderCapturePurgeService purgeService;
    private final PlatformTransactionManager transactionManager;
    private final ProviderConnectionLifecyclePersistence persistence;
    private final UserProviderSecretCipher secretCipher;
    private final ConnectedAccountProviders providers;
    private final ProviderTokenClient tokenClient;
    private final ObjectMapper objectMapper;
    private final ConnectedCaptureProperties captureProperties;
    private final AuditService auditService;

    /** Advances ordinary revocation or one bounded legacy purge page. */
    public boolean process(ProviderConnection connection) {
        ProviderConnection current = tenantWorkScope.unrouted(
            () -> connectionMapper.getById(connection.getId()));
        if (current == null) {
            return true;
        }
        if (current.getCredentialGeneration() != connection.getCredentialGeneration()) {
            return false;
        }
        if ("revoking".equals(current.getStatus())) {
            return revokeAndRetainTombstone(current);
        }
        if (!"disconnecting".equals(current.getStatus())
                && !"purge_failed".equals(current.getStatus())) {
            return false;
        }
        if (!current.isCaptureReconcileRequired()) {
            return finish(current);
        }
        String owner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        int claimed = tenantWorkScope.unrouted(
            () -> connectionMapper.claimCaptureReconcile(
                current.getId(),
                current.getCredentialGeneration(),
                owner,
                mysql(now),
                mysql(now.plus(captureProperties.getLeaseDuration()))));
        if (claimed != 1) {
            return false;
        }
        try {
            int limit = captureProperties.getSchedulerBatchSize();
            List<Integer> workspaceIds = tenantWorkScope.unrouted(
                () -> workspaceMapper.findWorkspaceIdsLifecyclePage(
                    current.getCaptureReconcileAfterWorkspaceId(), limit));
            for (int workspaceId : workspaceIds) {
                int renewed = tenantWorkScope.unrouted(
                    () -> connectionMapper.renewCaptureReconcile(
                        current.getId(),
                        current.getCredentialGeneration(),
                        owner,
                        mysql(Instant.now().plus(captureProperties.getLeaseDuration()))));
                if (renewed != 1) {
                    throw new IllegalStateException(
                        "Provider disconnect purge lease was superseded");
                }
                boolean hasResiduals = tenantWorkScope.inLifecycleWorkspace(
                    workspaceId,
                    () -> purgeService.hasResiduals(
                        workspaceId, current.getUserId(), current.getProvider()));
                Integer orgId = null;
                if (hasResiduals) {
                    orgId = tenantWorkScope.unrouted(
                        () -> lifecycleControlMapper.findWorkspaceOrgIdForLifecycle(workspaceId));
                    if (orgId == null) {
                        throw new IllegalStateException(
                            "Provider purge workspace scope no longer exists");
                    }
                    auditService.recordStrictIndependentScoped(
                        "provider.capture.purge",
                        "user",
                        current.getUserId(),
                        workspaceId,
                        orgId,
                        current.getProvider(),
                        "Requested provider data purge during provider account cleanup",
                        Map.of("provider", current.getProvider()));
                }
                tenantWorkScope.inLifecycleWorkspace(workspaceId,
                    () -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status ->
                            purgeWorkspace(workspaceId, current, owner)));
                if (hasResiduals) {
                    auditService.recordIndependentScoped(
                        "provider.capture.purge.complete",
                        "user",
                        current.getUserId(),
                        workspaceId,
                        orgId,
                        current.getProvider(),
                        "Completed provider data purge during provider account cleanup",
                        Map.of("provider", current.getProvider()));
                }
            }
            int afterWorkspaceId = workspaceIds.isEmpty()
                ? current.getCaptureReconcileAfterWorkspaceId()
                : workspaceIds.getLast();
            boolean complete = workspaceIds.size() < limit;
            int advanced = tenantWorkScope.unrouted(
                () -> connectionMapper.advanceCaptureReconcile(
                    current.getId(),
                    current.getCredentialGeneration(),
                    owner,
                    afterWorkspaceId,
                    complete));
            if (advanced != 1) {
                throw new IllegalStateException(
                    "Provider disconnect purge lease was superseded");
            }
            return complete && finish(current);
        } catch (RuntimeException exception) {
            tenantWorkScope.unrouted(() -> connectionMapper.markPurgeFailed(
                current.getId(),
                current.getCredentialGeneration(),
                owner,
                "purge_failed"));
            tenantWorkScope.unrouted(
                () -> connectionMapper.releaseCaptureReconcile(
                    current.getId(),
                    current.getCredentialGeneration(),
                    owner));
            log.warn(
                "Provider disconnect purge failed for connection {}: {}",
                current.getId(),
                exception.getClass().getSimpleName());
            return false;
        }
    }

    private void purgeWorkspace(
            int workspaceId, ProviderConnection expected, String owner) {
        if (userMapper.lockByIdForShare(expected.getUserId()) == null) {
            throw new IllegalStateException(
                "Provider connection owner no longer exists");
        }
        ProviderConnection locked =
            connectionMapper.getByIdForShare(expected.getId());
        if (locked == null
                || locked.getCredentialGeneration()
                    != expected.getCredentialGeneration()
                || (!"disconnecting".equals(locked.getStatus())
                    && !"purge_failed".equals(locked.getStatus()))
                || !owner.equals(locked.getCaptureReconcileLeaseOwner())) {
            throw new IllegalStateException(
                "Provider disconnect generation was superseded");
        }
        purgeService.purge(
            workspaceId, expected.getUserId(), expected.getProvider());
    }

    private boolean finish(ProviderConnection connection) {
        tenantWorkScope.unrouted(() -> {
            revokeBestEffort(connection);
            return null;
        });
        return tenantWorkScope.unrouted(() -> persistence.finish(connection));
    }

    private boolean revokeAndRetainTombstone(ProviderConnection connection) {
        int claimed = tenantWorkScope.unrouted(
            () -> connectionMapper.claimRevocationAttempt(
                connection.getId(), connection.getCredentialGeneration()));
        if (claimed != 1) {
            return false;
        }
        tenantWorkScope.unrouted(() -> {
            revokeBestEffort(connection);
            return null;
        });
        return tenantWorkScope.unrouted(
            () -> persistence.finishRevocation(connection));
    }

    /** Retries durable cleanup independently of capture-ingestion feature flags. */
    @Scheduled(fixedDelayString = "${connex.connected-capture.cleanup-interval:PT1M}")
    public void retryPending() {
        List<ProviderConnection> pending = tenantWorkScope.unrouted(
            () -> connectionMapper.findDisconnecting(RETRY_BATCH));
        for (ProviderConnection connection : pending) {
            process(connection);
        }
    }

    private void revokeBestEffort(ProviderConnection connection) {
        String revokeUri = providers.revokeUri(connection.getProvider());
        if (revokeUri == null || connection.getCredentialRef() == null) {
            return;
        }
        try {
            JsonNode bundle = objectMapper.readTree(secretCipher.decryptTokenBundle(
                connection.getProvider(),
                connection.getUserId(),
                connection.getCredentialRef()));
            if (bundle.hasNonNull("refreshToken")) {
                tokenClient.revoke(revokeUri, bundle.get("refreshToken").asString());
            }
        } catch (RuntimeException exception) {
            log.warn(
                "Provider revocation failed for connection {}: {}",
                connection.getId(),
                exception.getClass().getSimpleName());
        }
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }
}
