package ooo.klae.connex.backend.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import ooo.klae.connex.backend.beans.User;

/**
 * Development fallback that logs the reset link instead of sending an email.
 * Active only while {@code connex.password-reset.email-enabled} is false, which
 * is the default. This is the single, deliberate exception to the "never log a
 * token" rule and must not run in any environment where email is enabled.
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

    public LoggingPasswordResetEmailService(@Value("${connex.password-reset.base-url:http://localhost:3000}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public void sendResetEmail(User user, String rawToken) {
        String link = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/auth/reset-password")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
        log.info("Password reset link for user {} (email delivery disabled): {}", user.getUsername(), link);
    }
}
