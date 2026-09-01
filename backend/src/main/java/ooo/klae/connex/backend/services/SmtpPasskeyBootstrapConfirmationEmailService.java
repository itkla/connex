package ooo.klae.connex.backend.services;

import java.util.Locale;
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
 * Real SMTP delivery of first-passkey enrollment confirmations, active when
 * {@code connex.security.privileged-mfa.bootstrap-confirmation.email-enabled=true} (the seam
 * contract). Delivery is synchronous and propagates transport failures: a confined account has
 * nowhere else to go, so silently dropping this message would be a soft lockout. That same flag
 * stands the
 * {@link LoggingPasskeyBootstrapConfirmationEmailService} down, so exactly one delivery bean
 * exists. The bearer rides in the URL fragment, so it never reaches a server log, a proxy, or a
 * {@code Referer} header.
 */
@Service
@ConditionalOnProperty(
    prefix = "connex.security.privileged-mfa.bootstrap-confirmation",
    name = "email-enabled",
    havingValue = "true")
@RequiredArgsConstructor
public class SmtpPasskeyBootstrapConfirmationEmailService
        implements PasskeyBootstrapConfirmationEmailService {

    private static final String ENGLISH_SUBJECT = "Confirm your Connex passkey enrollment";
    private static final String JAPANESE_SUBJECT = "Connex パスキー登録の確認";

    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${connex.security.privileged-mfa.bootstrap-confirmation.token-expiry-minutes:30}")
    private int tokenExpiryMinutes;

    @Override
    public boolean canDeliver() {
        return mailService.hasUsableInstanceTransport();
    }

    @Override
    public void sendConfirmationEmail(User user, String rawToken) {
        Locale locale = LocaleSupport.resolve(user.getLocale());
        String link = UriComponentsBuilder.fromUriString(baseUrl)
                .path("/auth/confirm-passkey")
                .fragment("token={token}")
                .encode()
                .buildAndExpand(rawToken)
                .toUriString();
        String body = templateRenderer.render(
                "passkey-bootstrap-confirmation", locale.getLanguage(), Map.of(
                        "displayName", user.getDisplayName(),
                        "confirmUrl", link,
                        "expiryMinutes", String.valueOf(tokenExpiryMinutes)));
        String subject = Locale.JAPANESE.equals(locale) ? JAPANESE_SUBJECT : ENGLISH_SUBJECT;
        mailService.sendInstanceNow(MailMessage.html(user.getEmail(), subject, body));
    }
}
