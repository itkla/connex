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
 * Real SMTP delivery of email-change verification links, active when
 * {@code connex.email-change.email-enabled=true} (the seam contract). That same flag
 * stands the dev {@link LoggingEmailChangeEmailService} down, so exactly one delivery
 * bean exists — no {@code @Primary} needed. The verification link is sent to the pending
 * new address through the instance sender ({@code connex.mail.*}).
 */
@Service
@ConditionalOnProperty(prefix = "connex.email-change", name = "email-enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpEmailChangeEmailService implements EmailChangeEmailService {

    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;

    @Value("${connex.email-change.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${connex.email-change.token-expiry-minutes:30}")
    private int tokenExpiryMinutes;

    @Override
    public void sendVerificationEmail(User user, String newEmail, String rawToken) {
        String link = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/auth/verify-email")
                .queryParam("token", rawToken)
                .build()
                .toUriString();
        String body = templateRenderer.render("email-change", "en", Map.of(
                "displayName", user.getDisplayName(),
                "newEmail", newEmail,
                "verifyUrl", link,
                "expiryMinutes", String.valueOf(tokenExpiryMinutes)));
        mailService.sendInstance(MailMessage.html(newEmail, "Confirm your new Connex email", body));
    }
}
