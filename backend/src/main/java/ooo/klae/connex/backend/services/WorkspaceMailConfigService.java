package ooo.klae.connex.backend.services;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.dto.MailConfigDto;
import ooo.klae.connex.backend.dto.MailConfigRequest;
import ooo.klae.connex.backend.dto.MailTestResult;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.EmailTemplateRenderer;
import ooo.klae.connex.backend.mail.MailMessage;
import ooo.klae.connex.backend.mail.MailService;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.SecretCipher;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Owner/admin management of a workspace's own SMTP transport ({@code WORKSPACE_SETTINGS}).
 * The SMTP password is encrypted at rest and never returned; a blank password on
 * save keeps the stored one. Provides a synchronous "send test email" so an admin
 * can verify the transport before relying on it.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceMailConfigService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMailConfigService.class);

    private final MailConfigMapper mailConfigMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final SecretCipher secretCipher;
    private final MailConfigResolver mailConfigResolver;
    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;
    private final UserMapper userMapper;
    private final SessionSecurityService sessionSecurityService;
    private final SmtpDestinationGuard smtpDestinationGuard;

    /**
     * Returns the workspace's SMTP config for the settings page (password omitted).
     * @param workspaceId the workspace
     * @param actorId the requesting user
     * @return the config view
     */
    public MailConfigDto getConfig(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        return MailConfigDto.from(mailConfigMapper.findByWorkspace(workspaceId));
    }

    /**
     * Creates or updates the workspace's SMTP config. Requires host and from-address
     * when enabled; encrypts a supplied password and preserves the stored one when blank.
     * @param workspaceId the workspace
     * @param actorId the requesting user
     * @param request the submitted config
     * @return the saved config view
     */
    @Transactional
    public MailConfigDto saveConfig(int workspaceId, int actorId, MailConfigRequest request) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        sessionSecurityService.requireRecentAuthentication(actorId);

        if (request.isEnabled()) {
            if (isBlank(request.getHost())) {
                throw new BadRequestException("SMTP host is required to enable workspace email");
            }
            if (isBlank(request.getFromAddress())) {
                throw new BadRequestException("A from address is required to enable workspace email");
            }
            smtpDestinationGuard.requirePublicDestination(request.getHost(), request.getPort());
        }

        WorkspaceMailConfig existing = mailConfigMapper.findByWorkspace(workspaceId);

        WorkspaceMailConfig config = new WorkspaceMailConfig();
        config.setWorkspaceId(workspaceId);
        config.setEnabled(request.isEnabled());
        config.setHost(trimToNull(request.getHost()));
        config.setPort(request.getPort());
        config.setUsername(trimToNull(request.getUsername()));
        config.setFromAddress(trimToNull(request.getFromAddress()));
        config.setFromName(trimToNull(request.getFromName()));
        config.setStarttls(request.isStarttls());
        config.setSsl(request.isSsl());
        config.setAuth(request.isAuth());

        boolean clearStoredPassword = !request.isEnabled() || !request.isAuth();
        if (clearStoredPassword) {
            config.setPasswordEnc(null);
        } else if (!isBlank(request.getPassword())) {
            config.setPasswordEnc(secretCipher.encryptForWorkspace(workspaceId, request.getPassword()));
        } else if (existing != null) {
            config.setPasswordEnc(existing.getPasswordEnc());
        }

        mailConfigMapper.upsert(config);
        if (clearStoredPassword && existing != null) {
            secretCipher.deleteReferenceForWorkspace(workspaceId, existing.getPasswordEnc());
        }
        auditService.record("workspace.mail_config.save", "workspace", workspaceId, config.getHost(),
                "Updated workspace email settings", null);
        return MailConfigDto.from(mailConfigMapper.findByWorkspace(workspaceId));
    }

    /**
     * Removes the workspace's SMTP config, reverting it to the instance default sender.
     * @param workspaceId the workspace
     * @param actorId the requesting user
     */
    @Transactional
    public void deleteConfig(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        sessionSecurityService.requireRecentAuthentication(actorId);
        WorkspaceMailConfig existing = mailConfigMapper.findByWorkspace(workspaceId);
        mailConfigMapper.delete(workspaceId);
        if (existing != null) {
            secretCipher.deleteReferenceForWorkspace(workspaceId, existing.getPasswordEnc());
        }
        auditService.record("workspace.mail_config.delete", "workspace", workspaceId, null,
                "Removed workspace email settings", null);
    }

    /**
     * Sends a test email to the requesting user through the workspace's resolved sender,
     * returning the transport outcome.
     * @param workspaceId the workspace
     * @param actorId the requesting user
     * @return success, or failure carrying the error
     */
    public MailTestResult sendTest(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        sessionSecurityService.requireRecentAuthentication(actorId);
        User actor = userMapper.getUserById(actorId);
        if (actor == null || isBlank(actor.getEmail())) {
            return MailTestResult.failure("Your account has no email address to send a test to");
        }
        try {
            ResolvedMailConfig config = mailConfigResolver.resolveWorkspaceOnly(workspaceId);
            if (config == null || !config.usable()) {
                return MailTestResult.failure("Save an enabled SMTP configuration for this workspace first");
            }
            smtpDestinationGuard.requirePublicDestination(config.host(), config.port());
            String body = templateRenderer.render("test", "en", Map.of("recipient", actor.getEmail()));
            mailService.sendNow(config, MailMessage.html(actor.getEmail(), "Connex email test", body));
            auditService.record("workspace.mail_config.test", "workspace", workspaceId, actor.getEmail(),
                    "Sent a test email", null);
            return MailTestResult.ok();
        } catch (BadRequestException e) {
            return MailTestResult.failure(e.getMessage());
        } catch (Exception e) {
            log.warn("Test email for workspace {} failed: {}", workspaceId, e.getMessage());
            return MailTestResult.failure("Could not send the test email. Check the host, port, and credentials.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
