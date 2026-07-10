package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiProviderSecretCipher;
import ooo.klae.connex.backend.beans.AiProviderConfig;
import ooo.klae.connex.backend.dto.AiProviderConfigDto;
import ooo.klae.connex.backend.dto.AiProviderConfigRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiProviderConfigMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AiProviderConfigServiceTest {
    private static final int WORKSPACE_ID = 7;
    private static final int ORG_ID = 3;
    private static final int ACTOR_ID = 42;

    @Mock private AiProviderConfigMapper aiProviderConfigMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AiProviderSecretCipher aiProviderSecretCipher;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    private AiProviderConfig stored;
    private AiProviderConfigService service;

    @BeforeEach
    void setUp() {
        service = new AiProviderConfigService(aiProviderConfigMapper, workspaceMapper, orgMemberService,
                aiProviderSecretCipher, auditService, sessionSecurityService, new ObjectMapper());
        lenient().when(workspaceMapper.getOrgId(WORKSPACE_ID)).thenReturn(ORG_ID);
        lenient().when(aiProviderConfigMapper.findByOrg(ORG_ID)).thenAnswer(invocation -> stored);
        lenient().when(aiProviderSecretCipher.isAvailable()).thenReturn(true);
        lenient().doAnswer(invocation -> {
            stored = copy(invocation.getArgument(0));
            stored.setUpdatedAt(LocalDateTime.now());
            return null;
        }).when(aiProviderConfigMapper).upsert(any(AiProviderConfig.class));
        lenient().doAnswer(invocation -> {
            stored = null;
            return null;
        }).when(aiProviderConfigMapper).deleteByOrg(ORG_ID);
    }

    @Test
    void nonAdminActor_isForbidden() {
        doThrow(new ForbiddenException("Requires an organization administrator role"))
                .when(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);

        assertThrows(ForbiddenException.class,
                () -> service.getForWorkspace(WORKSPACE_ID, ACTOR_ID));
        assertThrows(ForbiddenException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, validRequest()));
    }

    @Test
    void save_missingStepUp_isForbidden() {
        doThrow(new ForbiddenException("Recent authentication required"))
                .when(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);

        assertThrows(ForbiddenException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, validRequest()));
    }

    @Test
    void save_enabledWithoutAttestation_isRejected() {
        AiProviderConfigRequest request = validRequest();
        request.setNoTrainingAttested(false);

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        verify(aiProviderConfigMapper, never()).upsert(any());
    }

    @Test
    void save_enabledWithAttestationButNoCredential_isRejected() {
        AiProviderConfigRequest request = validRequest();
        request.setSecretAccessKey(null);
        request.setAccessKeyId(null);

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
    }

    @Test
    void save_unsupportedProviderAzureOpenAi_isRejected() {
        AiProviderConfigRequest request = validRequest();
        request.setProvider("azure_openai");

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
    }

    @Test
    void save_unsupportedRegion_isRejected() {
        AiProviderConfigRequest request = validRequest();
        request.setRegion("us");

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
    }

    @Test
    void save_validNewCredential_encryptsAndMasks() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:77");

        AiProviderConfigDto dto = service.save(WORKSPACE_ID, ACTOR_ID, validRequest());

        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiProviderConfig> configCaptor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(aiProviderSecretCipher).encryptCredential(eq(ORG_ID), plaintextCaptor.capture());
        verify(aiProviderConfigMapper).upsert(configCaptor.capture());
        assertTrue(plaintextCaptor.getValue().contains("\"accessKeyId\":\"AKIA_TEST\""));
        assertTrue(plaintextCaptor.getValue().contains("\"secretAccessKey\":\"abcd1234wxyz\""));
        assertEquals("wxyz", configCaptor.getValue().getCredentialLast4());
        assertEquals("secret:v1:77", configCaptor.getValue().getCredentialRef());
        assertNotNull(configCaptor.getValue().getAttestedAt());
        assertTrue(dto.isHasCredential());
        assertEquals("wxyz", dto.getCredentialLast4());
    }

    @Test
    void save_blankSecretOnUpdate_keepsStoredCredential() {
        stored = readyConfig();
        AiProviderConfigRequest request = validRequest();
        request.setSecretAccessKey(null);
        request.setAccessKeyId(null);
        request.setRegion("eu-west-1");

        AiProviderConfigDto dto = service.save(WORKSPACE_ID, ACTOR_ID, request);

        ArgumentCaptor<AiProviderConfig> captor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
        verify(aiProviderConfigMapper).upsert(captor.capture());
        assertEquals("secret:v1:88", captor.getValue().getCredentialRef());
        assertEquals("CDEF", captor.getValue().getCredentialLast4());
        assertEquals("eu-west-1", dto.getRegion());
        assertTrue(dto.isHasCredential());
    }

    @Test
    void revoke_deletesRowCredentialAndAudits() {
        stored = readyConfig();

        service.revoke(WORKSPACE_ID, ACTOR_ID);

        verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        verify(aiProviderConfigMapper).deleteByOrg(ORG_ID);
        verify(aiProviderSecretCipher).deleteCredentialReference(ORG_ID, "secret:v1:88");
        verify(auditService).record("org.ai_provider.revoke", "organization", ORG_ID, null,
                "Revoked AI provider configuration", null);
    }

    @Test
    void isReadyForOrg_requiresEveryReadinessCondition() {
        stored = null;
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        stored.setEnabled(false);
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        stored.setCredentialRef(null);
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        stored.setNoTrainingAttested(false);
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        stored.setRegion("apac");
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        when(aiProviderSecretCipher.isAvailable()).thenReturn(false);
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        when(aiProviderSecretCipher.isAvailable()).thenReturn(true);
        assertTrue(service.isReadyForOrg(ORG_ID));
    }

    private static AiProviderConfigRequest validRequest() {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("bedrock");
        request.setRegion("ap-northeast-1");
        request.setModelId("anthropic.claude-3-5-sonnet-20240620-v1:0");
        request.setAccessKeyId("AKIA_TEST");
        request.setSecretAccessKey("abcd1234wxyz");
        request.setNoTrainingAttested(true);
        request.setEnabled(true);
        return request;
    }

    private static AiProviderConfig readyConfig() {
        AiProviderConfig config = new AiProviderConfig();
        config.setOrgId(ORG_ID);
        config.setProvider("bedrock");
        config.setRegion("ap-northeast-1");
        config.setModelId("anthropic.claude-3-5-sonnet-20240620-v1:0");
        config.setCredentialRef("secret:v1:88");
        config.setCredentialLast4("CDEF");
        config.setNoTrainingAttested(true);
        config.setAttestedAt(LocalDateTime.now());
        config.setEnabled(true);
        return config;
    }

    private static AiProviderConfig copy(AiProviderConfig source) {
        AiProviderConfig copy = new AiProviderConfig();
        copy.setOrgId(source.getOrgId());
        copy.setProvider(source.getProvider());
        copy.setRegion(source.getRegion());
        copy.setModelId(source.getModelId());
        copy.setCredentialRef(source.getCredentialRef());
        copy.setCredentialLast4(source.getCredentialLast4());
        copy.setNoTrainingAttested(source.isNoTrainingAttested());
        copy.setAttestedAt(source.getAttestedAt());
        copy.setEnabled(source.isEnabled());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
