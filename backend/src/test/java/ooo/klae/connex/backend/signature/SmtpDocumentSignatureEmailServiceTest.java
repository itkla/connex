package ooo.klae.connex.backend.signature;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;

@ExtendWith(MockitoExtension.class)
class SmtpDocumentSignatureEmailServiceTest {
    @Mock MailService mailService;

    private SmtpDocumentSignatureEmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new SmtpDocumentSignatureEmailService(
            mailService, new EmailTemplateRenderer());
    }

    @Test
    void transportPreflightFailsClosedWhenNoEffectiveConfigExists() {
        when(mailService.hasUsableWorkspaceTransport(7)).thenReturn(false);

        assertThrows(ServiceUnavailableException.class, () -> emailService.requireTransport(7));

        verify(mailService, never()).sendForWorkspace(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(MailMessage.class));
    }

    @Test
    void configuredTransportPassesPreflightAndAllowsTheWorkspaceDispatch() {
        when(mailService.hasUsableWorkspaceTransport(7)).thenReturn(true);

        emailService.requireTransport(7);
        emailService.send(
            7,
            "Recipient",
            "recipient@example.test",
            "Proposal",
            null,
            "https://app.example/document-acceptance/token",
            "en");

        verify(mailService).sendForWorkspace(
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.any(MailMessage.class));
    }
}
