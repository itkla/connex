package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import ooo.klae.connex.backend.beans.ProviderCaptureUserPolicy;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Durably reconciles control-plane connection transitions into active tenant catalogs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderCaptureConnectionStateService {
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final ProviderConnectionMapper connectionMapper;
    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ProviderCaptureMapper captureMapper;
    private final WorkspaceService workspaceService;
    private final TenantWorkScope tenantWorkScope;
    private final PlatformTransactionManager transactionManager;
    private final ConnectedCaptureProperties properties;

    /** Attempts the first durable reconciliation page after a connection transition. */
    public void reconcile(int userId, String provider) {
        if (!properties.isCaptureEnabled(provider)) {
            return;
        }
        ProviderConnection connection = tenantWorkScope.unrouted(
            () -> connectionMapper.getByUserAndProvider(userId, provider));
        if (connection != null) {
            process(connection);
        }
    }

    /** Claims and reconciles one bounded workspace page for a connection generation. */
    public void process(ProviderConnection candidate) {
        if (!properties.isCaptureEnabled(candidate.getProvider())) {
            return;
        }
        String owner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        int claimed = tenantWorkScope.unrouted(
            () -> connectionMapper.claimCaptureReconcile(
                candidate.getId(),
                candidate.getCredentialGeneration(),
                owner,
                mysql(now),
                mysql(now.plus(properties.getLeaseDuration()))));
        if (claimed != 1) {
            return;
        }
        try {
            ProviderConnection connection = tenantWorkScope.unrouted(
                () -> connectionMapper.getById(candidate.getId()));
            if (connection == null
                    || connection.getCredentialGeneration()
                        != candidate.getCredentialGeneration()) {
                return;
            }
            int limit = properties.getSchedulerBatchSize();
            List<Integer> workspaceIds = tenantWorkScope.unrouted(
                () -> workspaceMapper.findWorkspaceIdsPage(
                    connection.getCaptureReconcileAfterWorkspaceId(), limit));
            for (int workspaceId : workspaceIds) {
                tenantWorkScope.inWorkspace(
                    workspaceId,
                    () -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status ->
                            reconcileWorkspace(
                                workspaceId, connection, owner)));
            }
            int afterWorkspaceId = workspaceIds.isEmpty()
                ? connection.getCaptureReconcileAfterWorkspaceId()
                : workspaceIds.getLast();
            boolean complete = workspaceIds.size() < limit;
            int advanced = tenantWorkScope.unrouted(
                () -> connectionMapper.advanceCaptureReconcile(
                    connection.getId(),
                    connection.getCredentialGeneration(),
                    owner,
                    afterWorkspaceId,
                    complete));
            if (advanced != 1) {
                throw new IllegalStateException(
                    "Provider connection reconciliation lease was superseded");
            }
        } catch (RuntimeException exception) {
            tenantWorkScope.unrouted(
                () -> connectionMapper.releaseCaptureReconcile(
                    candidate.getId(),
                    candidate.getCredentialGeneration(),
                    owner));
            log.warn(
                "Provider capture connection reconciliation failed for connection {}: {}",
                candidate.getId(),
                exception.getClass().getSimpleName());
        }
    }

    private void reconcileWorkspace(
            int workspaceId, ProviderConnection connection, String owner) {
        if (userMapper.lockByIdForShare(connection.getUserId()) == null) {
            throw new IllegalStateException(
                "Provider connection owner no longer exists");
        }
        ProviderConnection current =
            connectionMapper.getByIdForShare(connection.getId());
        if (current == null
                || current.getCredentialGeneration()
                    != connection.getCredentialGeneration()
                || !connection.getStatus().equals(current.getStatus())
                || !owner.equals(current.getCaptureReconcileLeaseOwner())) {
            throw new IllegalStateException(
                "Provider connection transition was superseded");
        }
        if (!"connected".equals(connection.getStatus())) {
            captureMapper.pauseUserSync(
                workspaceId, connection.getUserId(), connection.getProvider());
            return;
        }
        ProviderCaptureUserPolicy policy = captureMapper.getUserPolicy(
            workspaceId, connection.getUserId(), connection.getProvider());
        if (policy == null
                || !policy.isEnabled()
                || !workspaceService.permissionsFor(
                    workspaceId, connection.getUserId())
                    .contains(Permission.ACTIVITY_CREATE)) {
            captureMapper.pauseUserSync(
                workspaceId, connection.getUserId(), connection.getProvider());
            return;
        }
        ensureStreams(workspaceId, connection, policy);
        captureMapper.resetSyncGeneration(
            workspaceId,
            connection.getUserId(),
            connection.getProvider(),
            connection.getCredentialGeneration());
        if ("manual".equals(policy.getAdmissionMode())) {
            captureMapper.waitManualSync(
                workspaceId, connection.getUserId(), connection.getProvider());
        } else {
            captureMapper.queueSync(
                workspaceId, connection.getUserId(), connection.getProvider());
        }
    }

    private void ensureStreams(
            int workspaceId,
            ProviderConnection connection,
            ProviderCaptureUserPolicy policy) {
        if (policy.isCalendarEnabled()) {
            captureMapper.ensureSyncState(
                workspaceId,
                connection.getUserId(),
                connection.getProvider(),
                "calendar",
                connection.getCredentialGeneration());
        }
        if (policy.isMailInboxEnabled()) {
            captureMapper.ensureSyncState(
                workspaceId,
                connection.getUserId(),
                connection.getProvider(),
                "mail_inbox",
                connection.getCredentialGeneration());
        }
        if (policy.isMailSentEnabled()) {
            captureMapper.ensureSyncState(
                workspaceId,
                connection.getUserId(),
                connection.getProvider(),
                "mail_sent",
                connection.getCredentialGeneration());
        }
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }
}
