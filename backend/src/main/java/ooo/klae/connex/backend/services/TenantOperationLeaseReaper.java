package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Periodically clears tenant export leases that outlived the export request
 * timeout, so a killed or crashed streaming request cannot permanently consume
 * its organization's concurrent-export budget or block that workspace's
 * teardown. Teardown leases are never reaped: they stay fail-closed for
 * privileged operator clearance.
 */
@Component
@RequiredArgsConstructor
public class TenantOperationLeaseReaper {
    private static final Logger log = LoggerFactory.getLogger(TenantOperationLeaseReaper.class);

    private final TenantLifecycleControlOperations controlOperations;
    private final TenantWorkScope tenantWorkScope;

    /** Runs one bounded control-plane sweep of stale export leases. */
    @Scheduled(
        fixedDelayString = "${connex.tenant-lifecycle.lease-reaper-delay-ms:300000}",
        initialDelayString = "${connex.tenant-lifecycle.lease-reaper-initial-delay-ms:60000}")
    public void reapStaleExportLeases() {
        try {
            int reaped = tenantWorkScope.unrouted(controlOperations::reapStaleExportLeases);
            if (reaped > 0) {
                log.warn("Cleared {} tenant export leases past the export timeout", reaped);
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled tenant export lease reap failed", exception);
        }
    }
}
