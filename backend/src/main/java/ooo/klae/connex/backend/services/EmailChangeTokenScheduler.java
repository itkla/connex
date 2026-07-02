package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.EmailChangeTokenMapper;

/**
 * Periodically purges expired and consumed email-change tokens so the table does
 * not accumulate spent rows.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.email-change",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class EmailChangeTokenScheduler {
    private static final Logger log = LoggerFactory.getLogger(EmailChangeTokenScheduler.class);

    private final EmailChangeTokenMapper emailChangeTokenMapper;

    @Scheduled(
        fixedDelayString = "${connex.email-change.cleanup-delay-ms:3600000}",
        initialDelayString = "${connex.email-change.initial-delay-ms:3600000}"
    )
    public void purgeExpired() {
        try {
            int removed = emailChangeTokenMapper.deleteExpired();
            if (removed > 0) {
                log.info("Purged {} expired/consumed email-change tokens", removed);
            }
        } catch (Exception exception) {
            log.error("Scheduled email-change token purge failed", exception);
        }
    }
}
