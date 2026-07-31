package ooo.klae.connex.backend.connectedaccounts.capture;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.dto.ProviderCaptureSyncRef;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Fair catalog-aware polling scheduler instantiated only when capture work is authorized.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.connected-capture",
    name = "scheduling-enabled",
    havingValue = "true")
public class ProviderCaptureScheduler {
    private static final DateTimeFormatter MYSQL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private final AtomicLong workspaceCursor = new AtomicLong();

    private final WorkspaceMapper workspaceMapper;
    private final ProviderCaptureMapper captureMapper;
    private final ProviderCaptureWorker worker;
    private final TenantWorkScope tenantWorkScope;
    private final ConnectedCaptureProperties properties;

    /** Runs at most one due stream per workspace per sweep before taking another. */
    @Scheduled(fixedDelayString = "${connex.connected-capture.scheduler-delay:PT5S}")
    public void poll() {
        int visitLimit = properties.getSchedulerBatchSize();
        int afterId = Math.toIntExact(Math.min(
            Integer.MAX_VALUE, workspaceCursor.get()));
        List<Integer> workspaceIds = tenantWorkScope.unrouted(
            () -> workspaceMapper.findWorkspaceIdsPage(afterId, visitLimit));
        if (workspaceIds.isEmpty() && afterId != 0) {
            workspaceIds = tenantWorkScope.unrouted(
                () -> workspaceMapper.findWorkspaceIdsPage(0, visitLimit));
        }
        if (workspaceIds.isEmpty()) {
            workspaceCursor.set(0);
            return;
        }
        int processed = 0;
        for (int workspaceId : workspaceIds) {
            if (processed >= properties.getSchedulerBatchSize()) {
                break;
            }
            try {
                List<ProviderCaptureSyncRef> refs = tenantWorkScope.inWorkspace(
                    workspaceId,
                    () -> captureMapper.findDueSyncRefs(
                        workspaceId, mysql(Instant.now()), 1));
                for (ProviderCaptureSyncRef ref : refs) {
                    tenantWorkScope.inWorkspace(
                        workspaceId,
                        () -> worker.runPage(workspaceId, ref.syncStateId()));
                    processed++;
                }
            } catch (RuntimeException exception) {
                log.warn(
                    "Skipping provider capture workspace {}: {}",
                    workspaceId,
                    exception.getClass().getSimpleName());
            }
        }
        workspaceCursor.set(workspaceIds.getLast());
    }

    private static String mysql(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC).format(MYSQL_TIMESTAMP);
    }
}
