package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.beans.ConnectorConfig;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;
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
 * Unit tests for the connector config service: credential-at-rest handling, connector-target
 * resolution, readiness, and RBAC gating on the CRUD surface.
 */
@ExtendWith(MockitoExtension.class)
class ConnectorConfigServiceTest {

    private static final int WORKSPACE = 7;
    private static final int ACTOR = 9;
    private static final String API_KEY = "list_api_key_secret_1234";
    private static final String ENDPOINT = "https://lists.example.com/v1/lists/add";
    private static final String LIST_ID = "list-9";

    @Mock private ConnectorConfigMapper mapper;
    @Mock private ConnectorSecretCipher cipher;
    @Mock private AiEndpointAddressValidator endpointValidator;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    private ConnectorConfigService service() {
        return new ConnectorConfigService(mapper, cipher, endpointValidator, workspaceService,
                authService, auditService, sessionSecurityService);
    }

    private void currentWorkspaceAndActor() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        User actor = new User();
        actor.setId(ACTOR);
        when(authService.getCurrentUser()).thenReturn(actor);
    }

    private ConnectorConfigRequest request() {
        ConnectorConfigRequest request = new ConnectorConfigRequest();
        request.setConnector(HttpListConnector.PROVIDER_ID);
        request.setEndpoint(ENDPOINT);
        request.setExternalListId(LIST_ID);
        request.setApiKey(API_KEY);
        request.setEnabled(true);
        return request;
    }

    private ConnectorConfig enabledConnector() {
        ConnectorConfig config = new ConnectorConfig();
        config.setWorkspaceId(WORKSPACE);
        config.setConnector(HttpListConnector.PROVIDER_ID);
        config.setEndpoint(ENDPOINT);
        config.setExternalListId(LIST_ID);
        config.setCredentialRef("secret:v1:55");
        config.setCreatedById(ACTOR);
        config.setEnabled(true);
        return config;
    }

    @Test
    void save_storesOnlyASecretReferenceAndLast4_neverThePlaintext() {
        currentWorkspaceAndActor();
        when(endpointValidator.isFetchable("lists.example.com", false)).thenReturn(true);
        when(cipher.encryptCredential(WORKSPACE, API_KEY)).thenReturn("secret:v1:55");
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(null, enabledConnector());

        service().save(request());

        ArgumentCaptor<ConnectorConfig> captor = ArgumentCaptor.forClass(ConnectorConfig.class);
        verify(mapper).upsert(captor.capture());
        ConnectorConfig saved = captor.getValue();
        assertEquals("secret:v1:55", saved.getCredentialRef());
        assertEquals("1234", saved.getCredentialLast4());
        assertNotEquals(API_KEY, saved.getCredentialRef());
        verify(sessionSecurityService).requireRecentAuthentication(ACTOR);
    }

    @Test
    void save_enablingWithoutACredential_isRejected() {
        currentWorkspaceAndActor();
        when(endpointValidator.isFetchable("lists.example.com", false)).thenReturn(true);
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(null);
        ConnectorConfigRequest request = request();
        request.setApiKey(null);

        assertThrows(RuntimeException.class, () -> service().save(request));
    }

    @Test
    void save_repointingEndpointToANewHostWithoutReenteringCredential_isRejected() {
        currentWorkspaceAndActor();
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(enabledConnector());
        when(endpointValidator.isFetchable("evil.example.com", false)).thenReturn(true);
        ConnectorConfigRequest request = request();
        request.setApiKey(null);
        request.setEndpoint("https://evil.example.com/v1/lists/add");

        assertThrows(BadRequestException.class, () -> service().save(request));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void save_updatingSameHostWithoutReenteringCredential_preservesTheStoredCredential() {
        currentWorkspaceAndActor();
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list"))
                .thenReturn(enabledConnector(), enabledConnector());
        when(endpointValidator.isFetchable("lists.example.com", false)).thenReturn(true);
        ConnectorConfigRequest request = request();
        request.setApiKey(null);
        request.setEndpoint("https://lists.example.com/v2/lists/add");

        service().save(request);

        ArgumentCaptor<ConnectorConfig> captor = ArgumentCaptor.forClass(ConnectorConfig.class);
        verify(mapper).upsert(captor.capture());
        assertEquals("secret:v1:55", captor.getValue().getCredentialRef());
    }

    @Test
    void resolveForWorkspace_decryptsTheCredentialForTheGivenWorkspace() {
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(enabledConnector());
        when(cipher.decryptCredential(WORKSPACE, "secret:v1:55")).thenReturn(API_KEY);

        ResolvedDeliveryProvider resolved = service().resolveForWorkspace(WORKSPACE, "http_list");

        assertEquals(HttpListConnector.PROVIDER_ID, resolved.providerId());
        assertEquals(ENDPOINT, resolved.endpoint());
        assertEquals(API_KEY, resolved.credentials().require("apiKey"));
        assertEquals(WORKSPACE, resolved.workspaceId());
    }

    @Test
    void resolveForWorkspace_failsClosedWhenNotEnabled() {
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(null);
        assertThrows(DeliveryProviderException.class, () -> service().resolveForWorkspace(WORKSPACE, "http_list"));
    }

    @Test
    void isReady_requiresEnabledEndpointListAndCredential() {
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(enabledConnector());
        when(cipher.isAvailable()).thenReturn(true);
        assertTrue(service().isReady(WORKSPACE, "http_list"));
    }

    @Test
    void isReady_falseWhenNoConfig() {
        when(mapper.findByWorkspaceConnector(WORKSPACE, "http_list")).thenReturn(null);
        assertFalse(service().isReady(WORKSPACE, "http_list"));
    }

    @Test
    void list_isScopedToTheCurrentWorkspace() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        when(mapper.listByWorkspace(WORKSPACE)).thenReturn(java.util.List.of(enabledConnector()));

        assertEquals(1, service().list().size());
        verify(mapper).listByWorkspace(WORKSPACE);
    }

    @Test
    void crudMethods_areGatedByWorkspaceSettingsPermission() throws Exception {
        assertPermission("list");
        assertPermission("get", String.class);
        assertPermission("save", ConnectorConfigRequest.class);
        assertPermission("delete", String.class);
    }

    private static void assertPermission(String method, Class<?>... args) throws Exception {
        Method target = ConnectorConfigService.class.getMethod(method, args);
        RequirePermission annotation = target.getAnnotation(RequirePermission.class);
        assertTrue(annotation != null, method + " must be @RequirePermission gated");
        assertEquals(Permission.WORKSPACE_SETTINGS, annotation.value());
    }
}
