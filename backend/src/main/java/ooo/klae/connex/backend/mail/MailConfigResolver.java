package ooo.klae.connex.backend.mail;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mappers.MailConfigMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.secrets.SecretReference;

/**
 * Resolves the effective SMTP settings for a send. Account-level mail uses the
 * instance default ({@code connex.mail.*}); workspace-scoped mail uses that same
 * transport in managed mode, otherwise preferring the workspace's own enabled
 * config and falling back to the instance default. Returns {@code null} when no
 * usable config exists, which callers treat as "sending disabled"; a workspace whose row is gone
 * resolves to {@code null} for the same reason, so fire-and-forget senders keep that contract.
 *
 * <p>Workspace resolution locks the authenticated actor's {@code app_user} root for share when
 * present, then the workspace root for share. Both roots remain held through configuration and
 * secret reads so the endpoint and password come from one generation, and the secret store only
 * reacquires roots already held in mutation order. Background resolution without an actor takes
 * only the workspace root. Provider I/O follows after resolution. See {@code docs/backend/LOCKING.md}.
 */
@Component
@RequiredArgsConstructor
public class MailConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(MailConfigResolver.class);

    private final MailProperties properties;
    private final MailConfigMapper mailConfigMapper;
    private final SecretCipher secretCipher;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;
    private final String instanceConfigurationVersion = "instance:" + UUID.randomUUID();

    /**
     * The instance default sender, or {@code null} when mail is disabled or unconfigured.
     * @return the resolved instance config, or null
     */
    public ResolvedMailConfig resolveInstance() {
        if (!properties.isEnabled()) {
            return null;
        }
        ResolvedMailConfig instance = fromProperties();
        return instance != null && instance.usable() ? instance : null;
    }

    /**
     * The sender for a workspace: the instance config in managed mode, otherwise
     * its own enabled config if present and usable, then the instance default.
     * @param workspaceId the workspace whose mail is being sent
     * @return the resolved config, or null when nothing usable is configured
     */
    @Transactional
    public ResolvedMailConfig resolveForWorkspace(int workspaceId) {
        if (properties.isManaged()) {
            return resolveInstance();
        }
        if (!lockWorkspaceForResolution(workspaceId)) {
            return null;
        }
        WorkspaceMailConfig ws = mailConfigMapper.findByWorkspace(workspaceId);
        if (ws != null && ws.isEnabled()) {
            ResolvedMailConfig resolved = fromWorkspace(ws);
            if (resolved != null && resolved.usable()) {
                return resolved;
            }
            log.warn("Workspace {} has SMTP enabled but its config is unusable; "
                    + "falling back to the instance default sender", workspaceId);
        }
        return resolveInstance();
    }

    /**
     * Names the sender-selection mode that produced a resolved workspace config. Diagnostics
     * report this so the displayed mode always matches the branch {@link #resolveForWorkspace}
     * actually took, rather than a separately derived capability value.
     *
     * @param config the config returned by {@link #resolveForWorkspace}, possibly null
     * @return one of {@code managed}, {@code workspace_override}, {@code instance_default},
     *         or {@code unconfigured}
     */
    public String effectiveMode(ResolvedMailConfig config) {
        if (properties.isManaged()) {
            return "managed";
        }
        if (config == null || !config.usable()) {
            return "unconfigured";
        }
        return config.workspaceSupplied() ? "workspace_override" : "instance_default";
    }

    /**
     * The workspace's own sender only, with no instance fallback. Used by the test-send
     * action so it validates exactly what the workspace has configured. Returns no
     * override in managed mode.
     * @param workspaceId the workspace
     * @return the workspace's resolved config, or null when it has none enabled/usable
     */
    @Transactional
    public ResolvedMailConfig resolveWorkspaceOnly(int workspaceId) {
        if (properties.isManaged()) {
            return null;
        }
        if (!lockWorkspaceForResolution(workspaceId)) {
            return null;
        }
        WorkspaceMailConfig ws = mailConfigMapper.findByWorkspace(workspaceId);
        if (ws != null && ws.isEnabled()) {
            ResolvedMailConfig resolved = fromWorkspace(ws);
            if (resolved != null && resolved.usable()) {
                return resolved;
            }
        }
        return null;
    }

    private ResolvedMailConfig fromProperties() {
        String from = (properties.getFrom() == null || properties.getFrom().isBlank())
                ? properties.getUsername()
                : properties.getFrom();
        return new ResolvedMailConfig(
                properties.getHost(),
                properties.getPort(),
                properties.getUsername(),
                properties.getPassword(),
                from,
                properties.getFromName(),
                properties.isStarttls(),
                properties.isSsl(),
                properties.isAuth(),
                properties.getConnectionTimeoutMs(),
                properties.getTimeoutMs(),
                properties.getWriteTimeoutMs(),
                false,
                instanceConfigurationVersion,
                "instance-smtp:" + String.valueOf(properties.getUsername()));
    }

    private boolean lockWorkspaceForResolution(int workspaceId) {
        Integer actorId = currentActorId();
        if (actorId != null && userMapper.lockByIdForShare(actorId) == null) {
            return false;
        }
        return workspaceMapper.lockWorkspaceForShare(workspaceId) != null;
    }

    private static Integer currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof User user) {
            return user.getId();
        }
        return null;
    }

    private ResolvedMailConfig fromWorkspace(WorkspaceMailConfig ws) {
        String password = null;
        if (ws.isAuth() && ws.getPasswordEnc() != null && !ws.getPasswordEnc().isBlank()) {
            password = secretCipher.decryptForWorkspace(ws.getWorkspaceId(), ws.getPasswordEnc());
        }
        String from = (ws.getFromAddress() == null || ws.getFromAddress().isBlank())
                ? ws.getUsername()
                : ws.getFromAddress();
        return new ResolvedMailConfig(
                ws.getHost(),
                ws.getPort() == null ? properties.getPort() : ws.getPort(),
                ws.getUsername(),
                password,
                from,
                ws.getFromName() == null ? properties.getFromName() : ws.getFromName(),
                ws.isStarttls(),
                ws.isSsl(),
                ws.isAuth(),
                properties.getConnectionTimeoutMs(),
                properties.getTimeoutMs(),
                properties.getWriteTimeoutMs(),
                true,
                "workspace-smtp:" + ws.getWorkspaceId() + ":" + String.valueOf(ws.getUpdatedAt()),
                workspaceCredentialReference(ws));
    }

    private static String workspaceCredentialReference(WorkspaceMailConfig config) {
        String stored = config.getPasswordEnc();
        if (SecretReference.isReference(stored)) {
            return stored;
        }
        return "workspace-smtp:" + config.getWorkspaceId() + ":"
                + String.valueOf(config.getUsername());
    }
}
