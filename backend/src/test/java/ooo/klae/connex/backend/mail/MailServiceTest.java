package ooo.klae.connex.backend.mail;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock private MailConfigResolver resolver;
    @Mock private JavaMailSenderFactory senderFactory;
    @Mock private SmtpDestinationGuard smtpDestinationGuard;

    private MailService mailService;

    @BeforeEach
    void setUp() {
        mailService = new MailService(resolver, senderFactory, smtpDestinationGuard);
    }

    @Test
    void workspaceSendRevalidatesAndPinsDestinationImmediatelyBeforeDelivery() throws Exception {
        ResolvedMailConfig config = config(true);
        InetAddress address = InetAddress.getByName("203.0.113.10");
        when(resolver.resolveForWorkspace(7)).thenReturn(config);
        when(smtpDestinationGuard.resolveForSend(config)).thenReturn(address);
        when(senderFactory.forConfig(config, address)).thenThrow(new IllegalStateException("stop before transport"));

        mailService.sendForWorkspace(7, MailMessage.html("member@example.com", "Subject", "Body"));

        verify(smtpDestinationGuard).resolveForSend(config);
        verify(senderFactory).forConfig(config, address);
    }

    @Test
    void unusableConfigNeverReachesDestinationResolution() {
        ResolvedMailConfig config = new ResolvedMailConfig(
            null, 587, null, null, "sender@example.com", null,
            true, false, false, 1000, 1000, 1000, true);

        assertThrows(IllegalStateException.class,
            () -> mailService.sendNow(config, MailMessage.html("member@example.com", "Subject", "Body")));

        verify(smtpDestinationGuard, never()).resolveForSend(config);
    }

    private static ResolvedMailConfig config(boolean workspaceSupplied) {
        return new ResolvedMailConfig(
            "smtp.example.com", 587, null, null, "sender@example.com", "Connex",
            true, false, false, 1000, 1000, 1000, workspaceSupplied);
    }
}
