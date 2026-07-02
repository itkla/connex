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
 * records that an email change was requested. The full verification link (with the raw token) is
 * logged solely when {@code connex.email-change.log-link} is explicitly enabled — a local-development
 * aid that must never be turned on where logs are accessible, since the link is a bearer credential.
 * Runs asynchronously so the request does not block on delivery.
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

    private final String baseUrl;
    private final boolean logLink;

    public LoggingEmailChangeEmailService(
            @Value("${connex.email-change.base-url:http://localhost:3000}") String baseUrl,
            @Value("${connex.email-change.log-link:false}") boolean logLink) {
        this.baseUrl = baseUrl;
        this.logLink = logLink;
    }

    @Override
    @Async
    public void sendVerificationEmail(User user, String newEmail, String rawToken) {
        if (logLink) {
            String link = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/auth/verify-email")
                    .queryParam("token", rawToken)
                    .build()
                    .toUriString();
            log.info("Email-change verification link for user {} (dev link logging enabled): {}",
                    user.getUsername(), link);
            return;
        }
        log.warn("Email change requested for user {} but no email delivery is configured; "
                + "set connex.email-change.log-link=true in local dev to log the link.", user.getUsername());
    }
}
