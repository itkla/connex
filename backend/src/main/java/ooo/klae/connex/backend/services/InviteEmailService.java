package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;

/**
 * Emails workspace invite links to the invited address. Uses the workspace's own
 * sender (falling back to the instance default) via {@link MailService}, which is
 * async and failure-tolerant — a mail outage never blocks creating the invite. When
 * no sender is configured the invite is still created; only the email is skipped.
 */
@Service
@RequiredArgsConstructor
public class InviteEmailService {

    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;
    private final MailProperties mailProperties;

    /**
     * Sends the invite email for a freshly created token invite.
     * @param workspaceId the workspace the invite is for
     * @param workspaceName the workspace's display name
     * @param toEmail the invited address
     * @param inviterName the display name of the inviting member
     * @param role the role the invite grants
     * @param token the raw invite token
     */
    public void sendInvite(int workspaceId, String workspaceName, String toEmail,
            String inviterName, String role, String token) {
        String acceptUrl = UriComponentsBuilder.fromUriString(mailProperties.getAppBaseUrl())
                .path("/invite/" + token)
                .build()
                .toUriString();
        String body = templateRenderer.render("invite", "en", Map.of(
                "workspaceName", workspaceName == null ? "a workspace" : workspaceName,
                "inviterName", inviterName == null ? "A teammate" : inviterName,
                "role", role,
                "acceptUrl", acceptUrl));
        String subject = (inviterName == null ? "You've been invited" : inviterName + " invited you")
                + " to join " + (workspaceName == null ? "a workspace" : workspaceName) + " on Connex";
        mailService.sendForWorkspace(workspaceId, MailMessage.html(toEmail, subject, body));
    }
}
