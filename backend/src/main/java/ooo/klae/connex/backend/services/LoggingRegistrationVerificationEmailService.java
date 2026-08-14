package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.User;

/**
 * Fallback delivery used when no real email provider is configured. It never sends mail; it only
 * records that a registration verification was requested. Raw verification credentials are never
 * logged. Runs asynchronously so registration does not block on delivery.
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

    @Override
    @Async
    public void sendVerificationEmail(User user, String rawToken) {
        log.warn("Registration verification requested but no email delivery is configured");
    }
}
