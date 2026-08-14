package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.LogoutAuditClaimMapper;

/** Bounds logout idempotency claims after the duplicate-request window has safely elapsed. */
@Component
@RequiredArgsConstructor
public class LogoutAuditClaimRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(
        LogoutAuditClaimRetentionScheduler.class);

    private final LogoutAuditClaimMapper logoutAuditClaimMapper;

    /** Removes claims older than 24 hours, far beyond any overlapping HTTP logout request. */
    @Scheduled(
        fixedDelayString = "${connex.audit.logout-claim-cleanup-delay-ms:3600000}",
        initialDelayString = "${connex.audit.logout-claim-cleanup-initial-delay-ms:3600000}")
    public void purgeExpired() {
        try {
            int removed = logoutAuditClaimMapper.deleteExpired();
            if (removed > 0) {
                log.info("Purged {} expired logout audit idempotency claims", removed);
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled logout audit claim purge failed", exception);
        }
    }
}
