package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import ooo.klae.connex.backend.beans.User;

/**
 * Fallback delivery used when no real email provider is configured. It never sends mail; it only
 * records that a reset was requested. The full reset link (with the raw token) is logged solely
 * when {@code connex.password-reset.log-link} is explicitly enabled — a local-development aid that
 * must never be turned on where logs are accessible, since the link is a bearer credential.
 * Runs asynchronously so response timing does not reveal whether the account exists.
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

    private final String baseUrl;
    private final boolean logLink;

    public LoggingPasswordResetEmailService(
            @Value("${connex.password-reset.base-url:http://localhost:3000}") String baseUrl,
            @Value("${connex.password-reset.log-link:false}") boolean logLink) {
        this.baseUrl = baseUrl;
        this.logLink = logLink;
    }

    @Override
    @Async
    public void sendResetEmail(User user, String rawToken) {
        if (logLink) {
            String link = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/auth/reset-password")
                    .queryParam("token", rawToken)
                    .build()
                    .toUriString();
            log.info("Password reset link for userId {} (dev link logging enabled): {}", user.getId(), link);
            return;
        }
        log.warn("Password reset requested for userId {} but no email delivery is configured; "
                + "set connex.password-reset.log-link=true in local dev to log the link.", user.getId());
    }
}
