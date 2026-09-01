package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.PasskeyBootstrapConfirmationTokenMapper;

/**
 * Periodically purges expired and consumed first-passkey enrollment confirmations so the table
 * does not accumulate spent rows.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "connex.security.privileged-mfa.bootstrap-confirmation",
    name = "scheduling-enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class PasskeyBootstrapConfirmationTokenScheduler {
    private static final Logger log =
        LoggerFactory.getLogger(PasskeyBootstrapConfirmationTokenScheduler.class);

    private final PasskeyBootstrapConfirmationTokenMapper tokenMapper;

    @Scheduled(
        fixedDelayString =
            "${connex.security.privileged-mfa.bootstrap-confirmation.cleanup-delay-ms:3600000}",
        initialDelayString =
            "${connex.security.privileged-mfa.bootstrap-confirmation.initial-delay-ms:3600000}"
    )
    public void purgeExpired() {
        try {
            int removed = tokenMapper.deleteExpired();
            if (removed > 0) {
                log.info("Purged {} expired/consumed passkey enrollment confirmations", removed);
            }
        } catch (Exception exception) {
            log.error("Scheduled passkey enrollment confirmation purge failed", exception);
        }
    }
}
