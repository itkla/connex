package ooo.klae.connex.backend.delivery;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.beans.ConnectorConfig;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;
import ooo.klae.connex.backend.dto.ConnectorConfigDto;
import ooo.klae.connex.backend.dto.ConnectorConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.ConnectorConfigMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Manages the per-workspace {@code connector_config} rows for third-party marketing audience-sync
 * connectors and resolves the effective connector target for an export, fail-closed. The push
 * credential is held only as an opaque secret reference; the resolve path decrypts it into an
 * ephemeral {@link ResolvedDeliveryProvider} for the immediate push and never returns it to a client.
 * CRUD is permission-gated ({@code WORKSPACE_SETTINGS}) and audited; the resolve path is not gated —
 * its callers (the export choke point) are.
 */
@Service
@RequiredArgsConstructor
public class ConnectorConfigService {

    private static final String CREDENTIAL_KEY_API = "apiKey";
    private static final int MAX_ENDPOINT_LENGTH = 2048;
    private static final Pattern API_KEY = Pattern.compile("^[\\x21-\\x7e]{1,512}$");

    private final ConnectorConfigMapper connectorConfigMapper;
    private final ConnectorSecretCipher connectorSecretCipher;
    private final AiEndpointAddressValidator aiEndpointAddressValidator;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final SessionSecurityService sessionSecurityService;

    /**
     * Resolves the connector target a workspace should push to, decrypting the push credential.
     * @param workspaceId the workspace
     * @param connector the connector id
     * @return the resolved connector target carrying the decrypted credential
     * @throws DeliveryProviderException when the connector is not enabled or not fully configured
     */
    public ResolvedDeliveryProvider resolveForWorkspace(int workspaceId, String connector) {
        ConnectorConfig config = requireEnabledConfig(workspaceId, connector);
        if (isBlank(config.getEndpoint())) {
            throw new DeliveryProviderException("Connector endpoint is not configured");
        }
        if (isBlank(config.getCredentialRef())) {
            throw new DeliveryProviderException("Connector credential is not configured");
        }
        String apiKey = connectorSecretCipher.decryptCredential(workspaceId, config.getCredentialRef());
        if (isBlank(apiKey)) {
            throw new DeliveryProviderException("Connector credential is not configured");
        }
        return new ResolvedDeliveryProvider(
                config.getConnector(),
                DeliveryChannel.EMAIL,
                workspaceId,
                config.getEndpoint(),
                null,
                null,
                DeliveryCredentials.of(Map.of(CREDENTIAL_KEY_API, apiKey)));
    }

    /**
     * The external list id an enabled connector pushes into.
     * @param workspaceId the workspace
     * @param connector the connector id
     * @return the configured external list id, or null when unset
     * @throws DeliveryProviderException when the connector is not enabled
     */
    public String activeExternalListId(int workspaceId, String connector) {
        return requireEnabledConfig(workspaceId, connector).getExternalListId();
    }

    /**
     * Whether the workspace has a fully configured, enabled connector able to push.
     * @param workspaceId the workspace
     * @param connector the connector id
     * @return true when the connector can push
     */
    public boolean isReady(int workspaceId, String connector) {
        ConnectorConfig config = connectorConfigMapper.findByWorkspaceConnector(workspaceId, normalizeConnector(connector));
        return config != null && config.isEnabled()
                && !isBlank(config.getEndpoint())
                && !isBlank(config.getExternalListId())
                && !isBlank(config.getCredentialRef())
                && connectorSecretCipher.isAvailable();
    }

    /**
     * Lists the active workspace's connector settings, masked.
     * @return the masked connector settings, one per configured connector
     */
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public List<ConnectorConfigDto> list() {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return connectorConfigMapper.listByWorkspace(workspaceId).stream()
                .map(ConnectorConfigDto::from)
                .toList();
    }

    /**
     * Returns one masked connector setting for the active workspace.
     * @param connector the connector id
     * @return the masked settings
     */
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public ConnectorConfigDto get(String connector) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        ConnectorConfig config =
                connectorConfigMapper.findByWorkspaceConnector(workspaceId, normalizeConnector(connector));
        if (config == null) {
            throw new BadRequestException("Connector is not configured");
        }
        return ConnectorConfigDto.from(config);
    }

    /**
     * Creates or updates the active workspace's connector settings. The push credential is write-only:
     * a blank credential preserves the stored one.
     * @param request the submitted settings
     * @return the saved masked settings
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public ConnectorConfigDto save(ConnectorConfigRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sessionSecurityService.requireRecentAuthentication(actorId);
        if (request == null) {
            throw new BadRequestException("Connector configuration is required");
        }
        String connector = normalizeConnector(request.getConnector());

        ConnectorConfig existing = connectorConfigMapper.findByWorkspaceConnector(workspaceId, connector);

        ConnectorConfig config = new ConnectorConfig();
        config.setWorkspaceId(workspaceId);
        config.setConnector(connector);
        config.setCreatedById(existing != null ? existing.getCreatedById() : actorId);
        config.setCredentialRef(existing != null ? existing.getCredentialRef() : null);
        config.setCredentialLast4(existing != null ? existing.getCredentialLast4() : null);
        config.setEndpoint(resolveEndpoint(request.getEndpoint()));
        config.setExternalListId(requireExternalListId(request.getExternalListId()));

        String newCredential = extractCredential(request.getApiKey(), config);
        boolean hasCredential = newCredential != null || !isBlank(config.getCredentialRef());
        if (request.isEnabled() && !hasCredential) {
            throw new BadRequestException("A stored push credential is required before enabling this connector");
        }
        if (newCredential != null) {
            config.setCredentialRef(connectorSecretCipher.encryptCredential(workspaceId, newCredential));
        }
        config.setEnabled(request.isEnabled());

        connectorConfigMapper.upsert(config);
        deleteReplacedCredential(workspaceId, existing, config);
        auditService.record("workspace.connector.save", "workspace", workspaceId, connector,
                "Updated connector settings", null);
        return ConnectorConfigDto.from(connectorConfigMapper.findByWorkspaceConnector(workspaceId, connector));
    }

    /**
     * Removes the active workspace's connector settings and deletes its stored credential.
     * @param connector the connector id
     */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public void delete(String connector) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        sessionSecurityService.requireRecentAuthentication(actorId);
        String normalized = normalizeConnector(connector);
        ConnectorConfig existing = connectorConfigMapper.findByWorkspaceConnector(workspaceId, normalized);
        connectorConfigMapper.delete(workspaceId, normalized);
        if (existing != null && !isBlank(existing.getCredentialRef())) {
            connectorSecretCipher.deleteCredentialReference(workspaceId, existing.getCredentialRef());
        }
        auditService.record("workspace.connector.delete", "workspace", workspaceId, normalized,
                "Removed connector settings", null);
    }

    private ConnectorConfig requireEnabledConfig(int workspaceId, String connector) {
        ConnectorConfig config =
                connectorConfigMapper.findByWorkspaceConnector(workspaceId, normalizeConnector(connector));
        if (config == null || !config.isEnabled()) {
            throw new DeliveryProviderException("Connector is not enabled");
        }
        return config;
    }

    private String extractCredential(String requested, ConnectorConfig config) {
        if (isBlank(requested)) {
            return null;
        }
        String apiKey = requested.trim();
        if (!API_KEY.matcher(apiKey).matches()) {
            throw new BadRequestException("Invalid connector credential");
        }
        config.setCredentialLast4(last4(apiKey));
        return apiKey;
    }

    private void deleteReplacedCredential(int workspaceId, ConnectorConfig existing, ConnectorConfig replacement) {
        if (existing == null || isBlank(existing.getCredentialRef())) {
            return;
        }
        if (!existing.getCredentialRef().equals(replacement.getCredentialRef())) {
            connectorSecretCipher.deleteCredentialReference(workspaceId, existing.getCredentialRef());
        }
    }

    private String resolveEndpoint(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A connector endpoint is required");
        }
        String endpoint = requested.trim();
        if (endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new BadRequestException("Invalid connector endpoint");
        }
        URI uri = parseHttpsEndpoint(endpoint);
        if (uri == null || !aiEndpointAddressValidator.isFetchable(uri.getHost(), false)) {
            throw new BadRequestException("Invalid connector endpoint");
        }
        return endpoint;
    }

    private static String requireExternalListId(String requested) {
        String value = trimToNull(requested);
        if (value == null) {
            throw new BadRequestException("An external list id is required");
        }
        if (value.length() > 255) {
            throw new BadRequestException("Invalid external list id");
        }
        return value;
    }

    private static String normalizeConnector(String requested) {
        if (isBlank(requested)) {
            throw new BadRequestException("A connector is required");
        }
        String connector = requested.trim().toLowerCase(Locale.ROOT);
        if (!HttpListConnector.PROVIDER_ID.equals(connector)) {
            throw new BadRequestException("Unsupported connector");
        }
        return connector;
    }

    private static URI parseHttpsEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || isBlank(uri.getHost()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawQuery() != null) {
                return null;
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String last4(String value) {
        if (value == null || value.length() < 8) {
            return null;
        }
        return value.substring(value.length() - 4);
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
