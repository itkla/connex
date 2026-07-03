package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.RegistrationVerificationTokenMapper;

/**
 * Periodically purges expired and consumed registration-verification tokens so the
 * table does not accumulate spent rows.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.registration-verification",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class RegistrationVerificationTokenScheduler {
    private static final Logger log = LoggerFactory.getLogger(RegistrationVerificationTokenScheduler.class);

    private final RegistrationVerificationTokenMapper tokenMapper;

    @Scheduled(
        fixedDelayString = "${connex.registration-verification.cleanup-delay-ms:3600000}",
        initialDelayString = "${connex.registration-verification.initial-delay-ms:3600000}"
    )
    public void purgeExpired() {
        try {
            int removed = tokenMapper.deleteExpired();
            if (removed > 0) {
                log.info("Purged {} expired/consumed registration-verification tokens", removed);
            }
        } catch (Exception exception) {
            log.error("Scheduled registration-verification token purge failed", exception);
        }
    }
}
