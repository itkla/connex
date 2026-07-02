package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;

/**
 * Real SMTP delivery of password-reset links, active when
 * {@code connex.password-reset.email-enabled=true} (the seam contract). That same flag
 * stands the dev {@link LoggingPasswordResetEmailService} down, so exactly one delivery
 * bean exists — no {@code @Primary} needed. Transport comes from the instance sender
 * ({@code connex.mail.*}); when none is configured {@link MailService} logs and skips.
 * Delivery is async and failure-tolerant, preserving the enumeration-safe timing of the
 * reset request.
 */
@Service
@ConditionalOnProperty(prefix = "connex.password-reset", name = "email-enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpPasswordResetEmailService implements PasswordResetEmailService {

    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;

    @Value("${connex.password-reset.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${connex.password-reset.token-expiry-minutes:30}")
    private int tokenExpiryMinutes;

    @Override
    public void sendResetEmail(User user, String rawToken) {
        String link = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/auth/reset-password")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
        String body = templateRenderer.render("password-reset", "en", Map.of(
                "displayName", user.getDisplayName(),
                "resetUrl", link,
                "expiryMinutes", String.valueOf(tokenExpiryMinutes)));
        mailService.sendInstance(MailMessage.html(user.getEmail(), "Reset your Connex password", body));
    }
}
