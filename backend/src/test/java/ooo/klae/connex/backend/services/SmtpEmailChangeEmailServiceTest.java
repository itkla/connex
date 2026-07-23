package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;

@ExtendWith(MockitoExtension.class)
class SmtpEmailChangeEmailServiceTest {

    @Mock private MailService mailService;

    private SmtpEmailChangeEmailService service;

    @BeforeEach
    void setUp() {
        service = new SmtpEmailChangeEmailService(mailService, new EmailTemplateRenderer());
        ReflectionTestUtils.setField(service, "baseUrl", "https://app.example.com");
        ReflectionTestUtils.setField(service, "tokenExpiryMinutes", 30);
    }

    @Test
    void japaneseUserReceivesLocalizedEscapedMessageAtPendingAddress() {
        User user = user("ja");
        user.setDisplayName("佐藤 <管理者>");

        service.sendVerificationEmail(user, "new+test@example.com", "change-token");

        MailMessage message = sentMessage();
        assertEquals("new+test@example.com", message.to());
        assertEquals("Connex の新しいメールアドレスを確認", message.subject());
        assertTrue(message.htmlBody().contains("lang=\"ja\""));
        assertTrue(message.htmlBody().contains("新しいメールアドレスを確認"));
        assertTrue(message.htmlBody().contains("佐藤 &lt;管理者&gt;"));
        assertTrue(message.htmlBody().contains("new+test@example.com"));
        assertTrue(message.htmlBody().contains(
                "href=\"https://app.example.com/auth/verify-email?token=change-token\""));
        assertFalse(message.htmlBody().contains("{{"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"en", "", "../../ja"})
    void untrustedOrEnglishLocaleFallsBackToEnglish(String locale) {
        service.sendVerificationEmail(user(locale), "new@example.com", "change-token");

        MailMessage message = sentMessage();
        assertEquals("Confirm your new Connex email", message.subject());
        assertTrue(message.htmlBody().contains("lang=\"en\""));
        assertTrue(message.htmlBody().contains("Confirm your new email"));
        assertFalse(message.htmlBody().contains("{{"));
    }

    private MailMessage sentMessage() {
        ArgumentCaptor<MailMessage> message = ArgumentCaptor.forClass(MailMessage.class);
        verify(mailService).sendInstance(message.capture());
        return message.getValue();
    }

    private static User user(String locale) {
        User user = new User();
        user.setEmail("member@example.com");
        user.setDisplayName("Taylor");
        user.setLocale(locale);
        return user;
    }
}
