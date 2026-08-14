package ooo.klae.connex.backend.services;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.OneTimeLinkFlowMapper;

/** Periodically removes expired short-lived browser flow grants. */
@Component
@RequiredArgsConstructor
public class OneTimeLinkFlowScheduler {

    private static final Logger log = LoggerFactory.getLogger(OneTimeLinkFlowScheduler.class);
    private static final Duration ABANDONED_CLAIM_RETENTION = Duration.ofDays(1);

    private final OneTimeLinkFlowMapper flowMapper;

    /** Purges expired grants while retaining active claims for a full recovery horizon. */
    @Scheduled(
        fixedDelayString = "${connex.one-time-link.cleanup-delay-ms:3600000}",
        initialDelayString = "${connex.one-time-link.cleanup-initial-delay-ms:3600000}")
    public void purgeExpired() {
        try {
            int removed = flowMapper.deleteExpired(ABANDONED_CLAIM_RETENTION.toSeconds());
            if (removed > 0) {
                log.info("Purged {} expired one-time-link flow grants", removed);
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled one-time-link flow purge failed", exception);
        }
    }
}
