package ooo.klae.connex.backend.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mappers.UserMapper;

/** Email links enter the authenticated recipient flow before selecting a record workspace. */
class EmailNotificationDispatcherTest {
    @Test
    void recordEmailUsesRecipientAuthorizedNotificationInsteadOfTheActiveWorkspace() {
        UserMapper users = mock(UserMapper.class);
        MailService mail = mock(MailService.class);
        MailProperties properties = new MailProperties();
        properties.setAppBaseUrl("https://connex.example");
        User recipient = new User();
        recipient.setEmail("recipient@example.invalid");
        when(users.getUserById(23)).thenReturn(recipient);
        var dispatcher = new EmailNotificationDispatcher(users, mail, new EmailTemplateRenderer(), properties);
        Notification notification = new Notification();
        notification.setId(41);
        notification.setWorkspaceId(7);
        notification.setRecipientId(23);
        notification.setTitle("Follow up");
        notification.setActionUrl("/records/contacts/19?task=31");

        assertEquals(1, dispatcher.dispatch(notification));

        ArgumentCaptor<MailMessage> messages = ArgumentCaptor.forClass(MailMessage.class);
        verify(mail).sendForWorkspace(org.mockito.ArgumentMatchers.eq(7), messages.capture());
        assertTrue(messages.getValue().htmlBody().contains("https://connex.example/notifications?open=41"));
        org.junit.jupiter.api.Assertions.assertFalse(messages.getValue().htmlBody().contains("/records/contacts/19"));
    }
}
