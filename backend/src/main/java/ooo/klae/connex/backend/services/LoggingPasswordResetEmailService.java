package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.User;

/**
 * Fallback delivery used when no real email provider is configured. It never sends mail; it only
 * records that a reset was requested. Raw reset credentials are never logged. Runs asynchronously
 * so response timing does not reveal whether the account exists.
 */
@Service
@ConditionalOnProperty(
    prefix = "connex.password-reset",
    name = "email-enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class LoggingPasswordResetEmailService implements PasswordResetEmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetEmailService.class);

    @Override
    @Async
    public void sendResetEmail(User user, String rawToken) {
        log.warn("Password reset requested but no email delivery is configured");
    }
}
