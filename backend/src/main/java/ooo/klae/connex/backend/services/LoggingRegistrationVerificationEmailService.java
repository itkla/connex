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
 * records that a registration verification was requested. The full verification link (with the raw
 * token) is logged solely when {@code connex.registration-verification.log-link} is explicitly
 * enabled — a local-development aid that must never be turned on where logs are accessible, since
 * the link is a bearer credential. Runs asynchronously so registration does not block on delivery.
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

    private final String baseUrl;
    private final boolean logLink;

    public LoggingRegistrationVerificationEmailService(
            @Value("${connex.registration-verification.base-url:http://localhost:3000}") String baseUrl,
            @Value("${connex.registration-verification.log-link:false}") boolean logLink) {
        this.baseUrl = baseUrl;
        this.logLink = logLink;
    }

    @Override
    @Async
    public void sendVerificationEmail(User user, String rawToken) {
        if (logLink) {
            String link = UriComponentsBuilder.fromUriString(baseUrl)
                    .path("/auth/confirm-email")
                    .queryParam("token", rawToken)
                    .build()
                    .toUriString();
            log.info("Registration verification link for user {} (dev link logging enabled): {}",
                    user.getUsername(), link);
            return;
        }
        log.warn("Registration verification requested for user {} but no email delivery is configured; "
                + "set connex.registration-verification.log-link=true in local dev to log the link.",
                user.getUsername());
    }
}
