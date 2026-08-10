package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Periodically removes client-error metadata beyond the support window. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.client-errors",
    name = "retention-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ClientErrorRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClientErrorRetentionScheduler.class);

    private final ClientErrorService clientErrorService;

    /** Removes metadata older than the fixed 30-day retention horizon. */
    @Scheduled(
        fixedDelayString = "${connex.client-errors.retention-delay-ms:3600000}",
        initialDelayString = "${connex.client-errors.retention-initial-delay-ms:3600000}"
    )
    public void purgeExpired() {
        try {
            int removed = clientErrorService.purgeExpired();
            if (removed > 0) {
                log.info("Purged {} expired client-error metadata rows", removed);
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled client-error metadata purge failed", exception);
        }
    }
}
