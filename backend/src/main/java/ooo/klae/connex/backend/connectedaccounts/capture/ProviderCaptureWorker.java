package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.connectedaccounts.ProviderCredentialService;
import ooo.klae.connex.backend.connectedaccounts.ProviderTokenException;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * One-page sync executor separating provider I/O from tenant transactions.
 */
@Service
public class ProviderCaptureWorker {
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(6);
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private final ProviderCaptureMapper captureMapper;
    private final ProviderConnectionMapper connectionMapper;
    private final ProviderCredentialService credentialService;
    private final ProviderCapturePolicyService policyService;
    private final ProviderCapturePagePersistence pagePersistence;
    private final ConnectedCaptureProperties properties;
    private final TenantWorkScope tenantWorkScope;
    private final Map<String, ProviderCaptureAdapter> adapters;

    public ProviderCaptureWorker(
            ProviderCaptureMapper captureMapper,
            ProviderConnectionMapper connectionMapper,
            ProviderCredentialService credentialService,
            ProviderCapturePolicyService policyService,
            ProviderCapturePagePersistence pagePersistence,
            ConnectedCaptureProperties properties,
            TenantWorkScope tenantWorkScope,
            List<ProviderCaptureAdapter> adapters) {
        this.captureMapper = captureMapper;
        this.connectionMapper = connectionMapper;
        this.credentialService = credentialService;
        this.policyService = policyService;
        this.pagePersistence = pagePersistence;
        this.properties = properties;
        this.tenantWorkScope = tenantWorkScope;
        Map<String, ProviderCaptureAdapter> byProvider = new HashMap<>();
        for (ProviderCaptureAdapter adapter : adapters) {
            if (byProvider.put(adapter.provider(), adapter) != null) {
                throw new IllegalStateException(
                    "Duplicate capture adapter: " + adapter.provider());
            }
        }
        this.adapters = Map.copyOf(byProvider);
    }

    /** Claims and executes one bounded page for a tenant-routed stream. */
    public void runPage(int workspaceId, long syncStateId) {
        String owner = UUID.randomUUID().toString();
        Instant now = Instant.now();
        if (captureMapper.claimSync(
                workspaceId,
                syncStateId,
                owner,
                mysql(now),
                mysql(now.plus(properties.getLeaseDuration()))) != 1) {
            return;
        }
        ProviderCaptureSyncState state =
            captureMapper.getSyncState(workspaceId, syncStateId);
        if (state == null || !owner.equals(state.getLeaseOwner())) {
            return;
        }
        try {
            ProviderConnection connection = tenantWorkScope.unrouted(
                () -> connectionMapper.getByUserAndProvider(
                    state.getUserId(), state.getProvider()));
            if (connection == null || !"connected".equals(connection.getStatus())) {
                captureMapper.pauseClaimedSync(
                    workspaceId, syncStateId, owner, "connection_unavailable");
                return;
            }
            if (connection.getCredentialGeneration() != state.getCredentialGeneration()) {
                captureMapper.pauseClaimedSync(
                    workspaceId, syncStateId, owner, "credential_changed");
                captureMapper.resetSyncGeneration(
                    workspaceId,
                    state.getUserId(),
                    state.getProvider(),
                    connection.getCredentialGeneration());
                return;
            }
            CaptureExecutionPolicy policy = policyService.effectivePolicy(
                workspaceId, state.getUserId(), state.getProvider(), connection);
            if (!policy.enabled() || !policy.streamEnabled(state.getStream())) {
                captureMapper.pauseClaimedSync(
                    workspaceId, syncStateId, owner, "policy_disabled");
                return;
            }
            String accessToken = tenantWorkScope.unrouted(
                () -> credentialService.accessToken(connection));
            renewLease(workspaceId, syncStateId, owner);
            ProviderCaptureAdapter adapter = adapters.get(state.getProvider());
            if (adapter == null) {
                throw new ProviderCaptureException(
                    "adapter_unavailable", false, false,
                    "Capture adapter is not installed");
            }
            Instant roundEnd = state.getReconciliationMarker() != null
                ? mysqlInstant(state.getBackfillStartedAt())
                : now;
            ProviderCaptureRequest request = new ProviderCaptureRequest(
                accessToken,
                state.getStream(),
                state.getStableCursor(),
                state.getPageCursor(),
                roundEnd.minus(Duration.ofDays(policy.backfillDays())),
                roundEnd,
                policy.includeBodies(),
                item -> pagePersistence.bodyAllowed(
                    item,
                    policy,
                    connection.getProviderAccountEmail()),
                properties.getPageSize(),
                () -> renewLease(workspaceId, syncStateId, owner));
            ProviderCapturePage page = adapter.fetch(request);
            pagePersistence.commit(
                workspaceId,
                syncStateId,
                owner,
                page,
                policy,
                connection.getProviderAccountEmail());
        } catch (ProviderCaptureException exception) {
            fail(workspaceId, state, owner, exception);
        } catch (ProviderTokenException exception) {
            saveFailure(
                workspaceId,
                state,
                owner,
                exception.isRetryable() ? retryStatus(state) : "intervention_required",
                exception.getCode());
        } catch (RuntimeException exception) {
            saveFailure(
                workspaceId, state, owner, retryStatus(state), "capture_failed");
        }
    }

    private void fail(
            int workspaceId,
            ProviderCaptureSyncState state,
            String owner,
            ProviderCaptureException exception) {
        Duration retryAfter = exception.getRetryAfter();
        if (retryAfter != null && retryAfter.compareTo(MAX_RETRY_DELAY) > 0) {
            retryAfter = MAX_RETRY_DELAY;
        }
        String next = mysql(Instant.now().plus(
            retryAfter == null ? backoff(state) : retryAfter));
        if (exception.isCursorInvalid()) {
            if ("intervention_required".equals(retryStatus(state))) {
                captureMapper.saveSyncFailure(
                    workspaceId,
                    state.getId(),
                    owner,
                    "intervention_required",
                    exception.getCode(),
                    null);
            } else {
                captureMapper.resetSyncCursorFailure(
                    workspaceId, state.getId(), owner, exception.getCode(), next);
            }
            return;
        }
        captureMapper.saveSyncFailure(
            workspaceId,
            state.getId(),
            owner,
            exception.isRetryable() ? retryStatus(state) : "intervention_required",
            exception.getCode(),
            exception.isRetryable() ? next : null);
    }

    private void saveFailure(
            int workspaceId,
            ProviderCaptureSyncState state,
            String owner,
            String status,
            String errorCode) {
        captureMapper.saveSyncFailure(
            workspaceId,
            state.getId(),
            owner,
            status,
            errorCode,
            "intervention_required".equals(status)
                ? null
                : mysql(Instant.now().plus(backoff(state))));
    }

    private String retryStatus(ProviderCaptureSyncState state) {
        return state.getConsecutiveFailures() + 1
                >= properties.getInterventionFailureCount()
            ? "intervention_required"
            : "retrying";
    }

    private Duration backoff(ProviderCaptureSyncState state) {
        int exponent = Math.min(state.getConsecutiveFailures(), 8);
        long baseMillis = properties.getRetryBase().toMillis();
        long bounded = Math.min(
            MAX_RETRY_DELAY.toMillis(),
            baseMillis * (1L << exponent));
        long jitter = ThreadLocalRandom.current().nextLong(
            Math.max(1L, bounded / 4L));
        return Duration.ofMillis(bounded + jitter);
    }

    private void renewLease(int workspaceId, long syncStateId, String owner) {
        Instant now = Instant.now();
        if (captureMapper.renewSyncLease(
                workspaceId,
                syncStateId,
                owner,
                mysql(now),
                mysql(now.plus(properties.getLeaseDuration()))) != 1) {
            throw new ProviderCaptureException(
                "lease_lost", true, false, "Capture page lease is no longer owned");
        }
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }

    private static Instant mysqlInstant(String value) {
        if (value == null) {
            throw new ProviderCaptureException(
                "backfill_window_missing", true, false,
                "Capture backfill has no durable window");
        }
        return LocalDateTime.parse(value, MYSQL_TIMESTAMP).toInstant(ZoneOffset.UTC);
    }
}
