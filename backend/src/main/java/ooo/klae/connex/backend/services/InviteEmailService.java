package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailDispatchOutcome;
import ooo.klae.connex.backend.mail.MailProperties;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Emails workspace invite links to the invited address. Uses the workspace's own
 * sender (falling back to the instance default) via {@link MailService}. Called after the
 * invitation transaction releases its resources, so a mail outage cannot roll it back or hold
 * membership locks. The request observes every submission result and can recover with its link.
 */
@Service
@RequiredArgsConstructor
public class InviteEmailService {

    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;
    private final MailProperties mailProperties;
    private final TenantWorkScope tenantWorkScope;

    /**
     * Sends the invite email for a freshly created token invite.
     * @param workspaceId the workspace the invite is for
     * @param workspaceName the workspace's display name
     * @param toEmail the invited address
     * @param inviterName the display name of the inviting member
     * @param role the role the invite grants
     * @param token the raw invite token
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MailDispatchOutcome sendInvite(int workspaceId, String workspaceName, String toEmail,
            String inviterName, String role, String token) {
        try {
            return tenantWorkScope.inWorkspace(workspaceId,
                    () -> submitInvite(workspaceId, workspaceName, toEmail, inviterName, role, token));
        } catch (RuntimeException exception) {
            return MailDispatchOutcome.FAILED;
        }
    }

    private MailDispatchOutcome submitInvite(int workspaceId, String workspaceName, String toEmail,
            String inviterName, String role, String token) {
        String acceptUrl = UriComponentsBuilder.fromUriString(mailProperties.getAppBaseUrl())
                .path("/invite")
                .fragment("token={token}")
                .encode()
                .buildAndExpand(token)
                .toUriString();
        String body = templateRenderer.render("invite", "en", Map.of(
                "workspaceName", workspaceName == null ? "a workspace" : workspaceName,
                "inviterName", inviterName == null ? "A teammate" : inviterName,
                "role", role,
                "acceptUrl", acceptUrl));
        String subject = (inviterName == null ? "You've been invited" : inviterName + " invited you")
                + " to join " + (workspaceName == null ? "a workspace" : workspaceName) + " on Connex";
        return mailService.submitForWorkspace(workspaceId, MailMessage.html(toEmail, subject, body));
    }
}
