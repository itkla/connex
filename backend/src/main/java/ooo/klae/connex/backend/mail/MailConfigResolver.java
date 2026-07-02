package ooo.klae.connex.backend.mail;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.WorkspaceMailConfig;
import ooo.klae.connex.backend.mappers.MailConfigMapper;

/**
 * Resolves the effective SMTP settings for a send. Account-level mail uses the
 * instance default ({@code connex.mail.*}); workspace-scoped mail prefers the
 * workspace's own enabled config and otherwise falls back to the instance
 * default. Returns {@code null} when no usable config exists, which callers treat
 * as "sending disabled".
 */
@Component
@RequiredArgsConstructor
public class MailConfigResolver {

    private final MailProperties properties;
    private final MailConfigMapper mailConfigMapper;
    private final SecretCipher secretCipher;

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
     * The sender for a workspace: its own enabled config if present and usable,
     * else the instance default.
     * @param workspaceId the workspace whose mail is being sent
     * @return the resolved config, or null when nothing usable is configured
     */
    public ResolvedMailConfig resolveForWorkspace(int workspaceId) {
        WorkspaceMailConfig ws = mailConfigMapper.findByWorkspace(workspaceId);
        if (ws != null && ws.isEnabled()) {
            ResolvedMailConfig resolved = fromWorkspace(ws);
            if (resolved != null && resolved.usable()) {
                return resolved;
            }
        }
        return resolveInstance();
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
                properties.getWriteTimeoutMs());
    }

    private ResolvedMailConfig fromWorkspace(WorkspaceMailConfig ws) {
        String password = null;
        if (ws.getPasswordEnc() != null && !ws.getPasswordEnc().isBlank()) {
            password = secretCipher.decrypt(ws.getPasswordEnc());
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
                properties.getWriteTimeoutMs());
    }
}
