package ooo.klae.connex.backend.signature;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;

/** SMTP-backed document-link delivery enabled by the master signature operator gate. */
@Service
@ConditionalOnProperty(prefix = "connex.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SmtpDocumentSignatureEmailService implements DocumentSignatureEmailService {
    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;

    private static final String JAPANESE = "ja";

    @Override
    public void send(
            int workspaceId,
            String recipientName,
            String recipientEmail,
            String documentTitle,
            String message,
            String acceptanceUrl,
            String locale) {
        String language = JAPANESE.equalsIgnoreCase(locale) ? JAPANESE : "en";
        String body = templateRenderer.render("document-signature", language, Map.of(
            "recipientName", recipientName,
            "documentTitle", documentTitle,
            "message", message == null ? "" : message,
            "acceptanceUrl", acceptanceUrl));
        mailService.sendForWorkspace(
            workspaceId,
            MailMessage.html(
                recipientEmail,
                subjectFor(language, documentTitle),
                body));
    }

    private String subjectFor(String language, String documentTitle) {
        return JAPANESE.equals(language)
            ? documentTitle + " のご確認とご承諾のお願い"
            : "Review and accept " + documentTitle;
    }
}
