package ooo.klae.connex.backend.connectedaccounts.capture;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Bounded retry loop for durable connection-to-catalog reconciliation.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.connected-capture",
    name = "scheduling-enabled",
    havingValue = "true")
public class ProviderCaptureConnectionReconciler {
    private static final int CONNECTION_BATCH = 25;

    private final ProviderConnectionMapper connectionMapper;
    private final ProviderCaptureConnectionStateService stateService;
    private final TenantWorkScope tenantWorkScope;

    /** Retries incomplete fan-out independently of the request that changed connection state. */
    @Scheduled(fixedDelayString = "${connex.connected-capture.reconcile-delay:PT15S}")
    public void retryPending() {
        List<ProviderConnection> pending = tenantWorkScope.unrouted(
            () -> connectionMapper.findCaptureReconcileDue(CONNECTION_BATCH));
        for (ProviderConnection connection : pending) {
            stateService.process(connection);
        }
    }
}
