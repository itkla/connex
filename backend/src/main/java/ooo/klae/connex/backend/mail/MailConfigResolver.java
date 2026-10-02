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
 *
 * <p>Readiness ({@link #canSendForWorkspace}, {@link #readinessForWorkspace}) takes the same roots and
 * selects the sender through the same decision, but never decrypts the workspace SMTP password: usability
 * depends only on the host and from address, and a selected override's stored password only has to be
 * decryptable as far as can be told without decrypting it. Decrypting there wrote an audited secret use
 * for every readiness check (#1932).
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
     * How a workspace's mail would be sent, selected as {@link #resolveForWorkspace} selects it but
     * without decrypting the workspace SMTP password.
     *
     * @param mode one of {@code managed}, {@code workspace_override}, {@code instance_default}, or
     *     {@code unconfigured}
     * @param ready whether a send would find a usable transport and a stored password whose row,
     *     algorithms and key-encryption key are all present
     */
    public record WorkspaceMailReadiness(String mode, boolean ready) {
    }

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
        Selection selection = select(workspaceId);
        if (selection.override() != null) {
            return fromWorkspace(selection.override(), decryptPassword(selection.override()));
        }
        return selection.instance();
    }

    /**
     * Whether a send for the workspace would find a usable transport, without decrypting the
     * workspace SMTP password or writing a secret-use audit.
     *
     * @param workspaceId the workspace whose mail would be sent
     * @return whether {@link #resolveForWorkspace} would return a usable config, unless a stored password
     *     whose row and key are present no longer decrypts
     */
    @Transactional
    public boolean canSendForWorkspace(int workspaceId) {
        return readiness(workspaceId).ready();
    }

    /**
     * The sender-selection mode and readiness for a workspace, through the same decision as
     * {@link #resolveForWorkspace}. A selected override whose stored password no longer resolves is not
     * ready and does not fall back to the instance default, because resolving it would fail rather than
     * fall back.
     *
     * @param workspaceId the workspace whose mail would be sent
     * @return the mode the sender would come from and whether it is ready
     */
    @Transactional
    public WorkspaceMailReadiness readinessForWorkspace(int workspaceId) {
        return readiness(workspaceId);
    }

    private WorkspaceMailReadiness readiness(int workspaceId) {
        Selection selection = select(workspaceId);
        if (selection.managed()) {
            return new WorkspaceMailReadiness("managed", selection.instance() != null);
        }
        if (selection.override() != null) {
            return passwordResolvable(selection.override())
                    ? new WorkspaceMailReadiness("workspace_override", true)
                    : new WorkspaceMailReadiness("unconfigured", false);
        }
        return selection.instance() != null
                ? new WorkspaceMailReadiness("instance_default", true)
                : new WorkspaceMailReadiness("unconfigured", false);
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
        if (ws != null && ws.isEnabled() && fromWorkspace(ws, null).usable()) {
            return fromWorkspace(ws, decryptPassword(ws));
        }
        return null;
    }

    private Selection select(int workspaceId) {
        if (properties.isManaged()) {
            return new Selection(true, null, resolveInstance());
        }
        if (!lockWorkspaceForResolution(workspaceId)) {
            return new Selection(false, null, null);
        }
        WorkspaceMailConfig ws = mailConfigMapper.findByWorkspace(workspaceId);
        if (ws != null && ws.isEnabled()) {
            if (fromWorkspace(ws, null).usable()) {
                return new Selection(false, ws, null);
            }
            log.warn("Workspace {} has SMTP enabled but its config is unusable; "
                    + "falling back to the instance default sender", workspaceId);
        }
        return new Selection(false, null, resolveInstance());
    }

    private String decryptPassword(WorkspaceMailConfig ws) {
        return hasStoredPassword(ws)
                ? secretCipher.decryptForWorkspace(ws.getWorkspaceId(), ws.getPasswordEnc())
                : null;
    }

    private boolean passwordResolvable(WorkspaceMailConfig ws) {
        return !hasStoredPassword(ws)
                || secretCipher.canResolveForWorkspace(ws.getWorkspaceId(), ws.getPasswordEnc());
    }

    private static boolean hasStoredPassword(WorkspaceMailConfig ws) {
        return ws.isAuth() && ws.getPasswordEnc() != null && !ws.getPasswordEnc().isBlank();
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

    private ResolvedMailConfig fromWorkspace(WorkspaceMailConfig ws, String password) {
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

    private record Selection(boolean managed, WorkspaceMailConfig override, ResolvedMailConfig instance) {
    }
}
