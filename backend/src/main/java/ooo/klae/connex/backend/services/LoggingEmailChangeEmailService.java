package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.User;

/**
 * Fallback delivery used when no real email provider is configured. It never sends mail; it only
 * records that an email change was requested. Raw verification credentials are never logged. Runs
 * asynchronously so the request does not block on delivery.
 */
@Service
@ConditionalOnProperty(
    prefix = "connex.email-change",
    name = "email-enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class LoggingEmailChangeEmailService implements EmailChangeEmailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailChangeEmailService.class);

    @Override
    @Async
    public void sendVerificationEmail(User user, String newEmail, String rawToken) {
        log.warn("Email change requested but no email delivery is configured");
    }
}
