package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.PasswordResetTokenMapper;

/**
 * Periodically purges expired and consumed password reset tokens so the table
 * does not accumulate spent rows.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.password-reset",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PasswordResetTokenScheduler {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenScheduler.class);

    private final PasswordResetTokenMapper passwordResetTokenMapper;
    private final PasswordResetRateLimiter rateLimiter;

    @Scheduled(
        fixedDelayString = "${connex.password-reset.cleanup-delay-ms:3600000}",
        initialDelayString = "${connex.password-reset.initial-delay-ms:3600000}"
    )
    public void purgeExpired() {
        try {
            int removed = passwordResetTokenMapper.deleteExpired();
            if (removed > 0) {
                log.info("Purged {} expired/consumed password reset tokens", removed);
            }
            rateLimiter.evictStale(System.currentTimeMillis());
        } catch (Exception exception) {
            log.error("Scheduled password reset token purge failed", exception);
        }
    }
}
