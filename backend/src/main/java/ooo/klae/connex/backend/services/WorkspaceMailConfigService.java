package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Service;

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

    private final MailConfigMapper mailConfigMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;
    private final SecretCipher secretCipher;
    private final MailConfigResolver mailConfigResolver;
    private final MailService mailService;
    private final EmailTemplateRenderer templateRenderer;
    private final UserMapper userMapper;

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
    public MailConfigDto saveConfig(int workspaceId, int actorId, MailConfigRequest request) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);

        if (request.isEnabled()) {
            if (isBlank(request.getHost())) {
                throw new BadRequestException("SMTP host is required to enable workspace email");
            }
            if (isBlank(request.getFromAddress())) {
                throw new BadRequestException("A from address is required to enable workspace email");
            }
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

        if (!isBlank(request.getPassword())) {
            config.setPasswordEnc(secretCipher.encrypt(request.getPassword()));
        } else if (existing != null) {
            config.setPasswordEnc(existing.getPasswordEnc());
        }

        mailConfigMapper.upsert(config);
        auditService.record("workspace.mail_config.save", "workspace", workspaceId, config.getHost(),
                "Updated workspace email settings", null);
        return MailConfigDto.from(mailConfigMapper.findByWorkspace(workspaceId));
    }

    /**
     * Removes the workspace's SMTP config, reverting it to the instance default sender.
     * @param workspaceId the workspace
     * @param actorId the requesting user
     */
    public void deleteConfig(int workspaceId, int actorId) {
        workspaceService.requirePermission(workspaceId, actorId, Permission.WORKSPACE_SETTINGS);
        mailConfigMapper.delete(workspaceId);
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
        ResolvedMailConfig config = mailConfigResolver.resolveForWorkspace(workspaceId);
        if (config == null || !config.usable()) {
            return MailTestResult.failure("No SMTP configuration is enabled for this workspace or instance");
        }
        User actor = userMapper.getUserById(actorId);
        if (actor == null || isBlank(actor.getEmail())) {
            return MailTestResult.failure("Your account has no email address to send a test to");
        }
        try {
            String body = templateRenderer.render("test", "en",
                    Map.of("recipient", actor.getEmail()));
            mailService.sendNow(config, MailMessage.html(actor.getEmail(), "Connex email test", body));
            auditService.record("workspace.mail_config.test", "workspace", workspaceId, actor.getEmail(),
                    "Sent a test email", null);
            return MailTestResult.ok();
        } catch (Exception e) {
            return MailTestResult.failure(e.getMessage());
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
