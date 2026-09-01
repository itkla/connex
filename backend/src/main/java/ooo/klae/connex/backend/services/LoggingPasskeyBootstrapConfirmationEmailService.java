package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.beans.User;

/**
 * Fallback delivery used when no real email provider is configured. It never sends mail; it only
 * records that a confirmation was requested. Raw bearers are never logged. Runs asynchronously so
 * the request does not block on delivery.
 */
@Service
@ConditionalOnProperty(
    prefix = "connex.security.privileged-mfa.bootstrap-confirmation",
    name = "email-enabled",
    havingValue = "false",
    matchIfMissing = true
)
public class LoggingPasskeyBootstrapConfirmationEmailService
        implements PasskeyBootstrapConfirmationEmailService {

    private static final Logger log =
        LoggerFactory.getLogger(LoggingPasskeyBootstrapConfirmationEmailService.class);

    @Override
    public boolean canDeliver() {
        return false;
    }

    @Override
    @Async
    public void sendConfirmationEmail(User user, String rawToken) {
        log.warn("Passkey enrollment confirmation requested but no email delivery is configured");
    }
}
