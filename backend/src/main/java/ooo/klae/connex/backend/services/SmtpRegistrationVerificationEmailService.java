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
 * Real SMTP delivery of registration verification links, active when
 * {@code connex.registration-verification.email-enabled=true} (the seam contract). That
 * same flag stands the dev {@link LoggingRegistrationVerificationEmailService} down, so
 * exactly one delivery bean exists. The link is sent to the account's own address through
 * the instance sender ({@code connex.mail.*}).
 */
@Service
@ConditionalOnProperty(prefix = "connex.registration-verification", name = "email-enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpRegistrationVerificationEmailService implements RegistrationVerificationEmailService {

    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;

    @Value("${connex.registration-verification.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${connex.registration-verification.token-expiry-minutes:1440}")
    private int tokenExpiryMinutes;

    @Override
    public void sendVerificationEmail(User user, String rawToken) {
        String link = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/auth/confirm-email")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
        String body = templateRenderer.render("verify-email", "en", Map.of(
                "displayName", user.getDisplayName(),
                "verifyUrl", link,
                "expiryMinutes", String.valueOf(tokenExpiryMinutes)));
        mailService.sendInstance(MailMessage.html(user.getEmail(), "Verify your Connex email", body));
    }
}
