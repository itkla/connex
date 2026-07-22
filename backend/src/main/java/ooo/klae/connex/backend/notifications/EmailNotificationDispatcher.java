package ooo.klae.connex.backend.notifications;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Delivers a generated notification to its recipient's email. Selected per user
 * via the {@code email} notification-preference channel and gated to first
 * occurrence by {@link NotificationDelivery}, which invokes this dispatcher only
 * after the notification transaction commits. Uses the workspace sender (falling
 * back to the instance default) through {@link MailService}, which is async and
 * failure-tolerant — an email failure never affects the in-app delivery.
 */
@Component
@RequiredArgsConstructor
public class EmailNotificationDispatcher implements NotificationDispatcher {

    private final UserMapper userMapper;
    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;
    private final MailProperties mailProperties;

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public int dispatch(Notification notification) {
        User recipient = userMapper.getUserById(notification.getRecipientId());
        if (recipient == null || recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            return 0;
        }

        String actionUrl = notification.getActionUrl() == null ? mailProperties.getAppBaseUrl()
                : UriComponentsBuilder.fromUriString(mailProperties.getAppBaseUrl())
                        .path(notification.getActionUrl())
                        .build()
                        .toUriString();

        String body = templateRenderer.render("notification", "en", Map.of(
                "title", notification.getTitle() == null ? "" : notification.getTitle(),
                "body", notification.getBody() == null ? "" : notification.getBody(),
                "actionUrl", actionUrl,
                "workspaceName", notification.getWorkspaceName() == null ? "Connex" : notification.getWorkspaceName()));

        String subject = notification.getTitle() == null || notification.getTitle().isBlank()
                ? "New notification in Connex"
                : notification.getTitle();

        mailService.sendForWorkspace(notification.getWorkspaceId(),
                MailMessage.html(recipient.getEmail(), subject, body));
        return 1;
    }
}
