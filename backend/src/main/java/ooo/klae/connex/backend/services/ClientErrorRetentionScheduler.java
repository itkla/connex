package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Periodically removes client-error metadata beyond the support window. */
@Component
@RequiredArgsConstructor
public class ClientErrorRetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(ClientErrorRetentionScheduler.class);

    private final ClientErrorService clientErrorService;

    /** Purges expired metadata once the application is ready to serve traffic. */
    @EventListener(ApplicationReadyEvent.class)
    public void purgeOnStartup() {
        int removed = clientErrorService.purgeExpired();
        logRemoved(removed);
    }

    /** Removes metadata older than the fixed 30-day retention horizon every hour. */
    @Scheduled(fixedDelay = 1, initialDelay = 1, timeUnit = java.util.concurrent.TimeUnit.HOURS)
    public void purgeExpired() {
        try {
            int removed = clientErrorService.purgeExpired();
            logRemoved(removed);
        } catch (RuntimeException exception) {
            log.error("Scheduled client-error metadata purge failed", exception);
        }
    }

    private void logRemoved(int removed) {
        if (removed > 0) {
            log.info("Purged {} expired client-error metadata rows", removed);
        }
    }
}
