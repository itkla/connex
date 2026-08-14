package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.User;

/**
 * Fallback delivery used when no real email provider is configured. It never sends mail; it only
 * records that a registration verification was requested. Raw verification credentials are never
 * logged, including when the legacy local-development
 * {@code connex.registration-verification.log-link} flag is enabled. Runs asynchronously so
 * registration does not block on delivery.
 */
@Service
@ConditionalOnProperty(
    prefix = "connex.registration-verification",
    name = "email-enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class LoggingRegistrationVerificationEmailService implements RegistrationVerificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingRegistrationVerificationEmailService.class);

    private final boolean logLink;

    public LoggingRegistrationVerificationEmailService(
            @Value("${connex.registration-verification.log-link:false}") boolean logLink) {
        this.logLink = logLink;
    }

    @Override
    @Async
    public void sendVerificationEmail(User user, String rawToken) {
        if (logLink) {
            log.warn("Registration verification requested for userId {}; raw link logging is disabled",
                user.getId());
            return;
        }
        log.warn("Registration verification requested for userId {} but no email delivery is configured",
            user.getId());
    }
}
