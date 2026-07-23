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
class SmtpPasswordResetEmailServiceTest {

    @Mock private MailService mailService;

    private SmtpPasswordResetEmailService service;

    @BeforeEach
    void setUp() {
        service = new SmtpPasswordResetEmailService(mailService, new EmailTemplateRenderer());
        ReflectionTestUtils.setField(service, "baseUrl", "https://app.example.com");
        ReflectionTestUtils.setField(service, "tokenExpiryMinutes", 30);
    }

    @Test
    void japaneseUserReceivesLocalizedEscapedMessage() {
        User user = user("ja");
        user.setDisplayName("佐藤 <管理者>");

        service.sendResetEmail(user, "reset-token");

        MailMessage message = sentMessage();
        assertEquals("member@example.com", message.to());
        assertEquals("Connex パスワードのリセット", message.subject());
        assertTrue(message.htmlBody().contains("lang=\"ja\""));
        assertTrue(message.htmlBody().contains("パスワードをリセット"));
        assertTrue(message.htmlBody().contains("佐藤 &lt;管理者&gt;"));
        assertTrue(message.htmlBody().contains(
                "href=\"https://app.example.com/auth/reset-password?token=reset-token\""));
        assertFalse(message.htmlBody().contains("{{"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"en", "", "../../ja"})
    void untrustedOrEnglishLocaleFallsBackToEnglish(String locale) {
        service.sendResetEmail(user(locale), "reset-token");

        MailMessage message = sentMessage();
        assertEquals("Reset your Connex password", message.subject());
        assertTrue(message.htmlBody().contains("lang=\"en\""));
        assertTrue(message.htmlBody().contains("Reset your password"));
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
