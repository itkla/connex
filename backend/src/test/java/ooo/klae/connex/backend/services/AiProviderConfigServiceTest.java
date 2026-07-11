package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.AiProperties;
import ooo.klae.connex.backend.ai.AiProviderSecretCipher;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.ResolvedAiProvider;
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

    @Mock private AiProperties aiProperties;
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
        service = new AiProviderConfigService(aiProperties, aiProviderConfigMapper, workspaceMapper,
                orgMemberService, aiProviderSecretCipher, auditService, sessionSecurityService,
                new ObjectMapper());
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
    void save_azureEndpointRequiresClosedAzureSuffix() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:azure");
        AiProviderConfigRequest valid = azureRequest();

        AiProviderConfigDto saved = service.save(WORKSPACE_ID, ACTOR_ID, valid);

        assertEquals("https://connex.openai.azure.com", saved.getEndpoint());
        assertEquals("contacts-prod", saved.getDeployment());
        assertEquals("2025-01-01-preview", saved.getApiVersion());

        for (String endpoint : List.of(
                "https://openai.azure.com",
                "https://connex.openai.azure.com.evil.test",
                "http://connex.openai.azure.com",
                "https://evil.test")) {
            AiProviderConfigRequest invalid = azureRequest();
            invalid.setEndpoint(endpoint);
            assertThrows(BadRequestException.class,
                    () -> service.save(WORKSPACE_ID, ACTOR_ID, invalid));
        }
    }

    @Test
    void save_vertexRejectsMalformedServiceAccountJson() {
        for (String credential : List.of(
                "{",
                "{}",
                "{\"type\":\"authorized_user\"}",
                "{\"type\":\"service_account\",\"client_email\":\"agent@example.test\"}")) {
            AiProviderConfigRequest request = vertexRequest();
            request.setServiceAccountJson(credential);

            assertThrows(BadRequestException.class,
                    () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        }
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
    }

    @Test
    void save_openAiCompatibleAllowsInternalEndpointAndNoCredential() {
        when(aiProperties.isAllowInternalEndpoints()).thenReturn(true);
        AiProviderConfigRequest request = openAiCompatibleRequest();

        AiProviderConfigDto saved = service.save(WORKSPACE_ID, ACTOR_ID, request);

        ArgumentCaptor<AiProviderConfig> configCaptor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(aiProviderConfigMapper).upsert(configCaptor.capture());
        assertEquals("openai_compatible", saved.getProvider());
        assertEquals("http://10.0.0.12:11434/v1", saved.getEndpoint());
        assertTrue(saved.isAllowInternalEndpoint());
        assertFalse(saved.isHasCredential());
        assertNull(saved.getCredentialLast4());
        assertTrue(configCaptor.getValue().isEnabled());
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
    }

    @Test
    void save_openAiCompatibleInternalEndpoint_rejectedWhenInstanceGateOff() {
        AiProviderConfigRequest request = openAiCompatibleRequest();

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        verify(aiProviderConfigMapper, never()).upsert(any(AiProviderConfig.class));
    }

    @Test
    void save_openAiCompatibleHttpEndpoint_requiresInternalAllowance() {
        AiProviderConfigRequest cleartext = openAiCompatibleRequest();
        cleartext.setEndpoint("http://llm.example.test/v1");
        cleartext.setAllowInternalEndpoint(false);

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, cleartext));

        AiProviderConfigRequest https = openAiCompatibleRequest();
        https.setEndpoint("https://llm.example.test/v1");
        https.setAllowInternalEndpoint(false);

        AiProviderConfigDto saved = service.save(WORKSPACE_ID, ACTOR_ID, https);
        assertEquals("https://llm.example.test/v1", saved.getEndpoint());
        assertFalse(saved.isAllowInternalEndpoint());
    }

    @Test
    void save_vertexForeignTokenUri_isRejected() {
        AiProviderConfigRequest request = vertexRequest();
        request.setServiceAccountJson("""
                {
                  "type": "service_account",
                  "client_email": "connex-agent@connex-prod1.iam.gserviceaccount.com",
                  "private_key": "sa-private-key-placeholder",
                  "token_uri": "https://attacker.example.test/token"
                }
                """);

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
    }

    @Test
    void save_unsupportedRegion_isRejected() {
        AiProviderConfigRequest request = validRequest();
        request.setRegion("us");

        assertThrows(BadRequestException.class,
                () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
    }

    @Test
    void save_modelIdsWithSlashOrConsecutiveDots_areRejected() {
        for (String modelId : List.of("anthropic.claude/../foo", "a/b", "anthropic..claude-v1:0")) {
            AiProviderConfigRequest request = validRequest();
            request.setModelId(modelId);

            assertThrows(BadRequestException.class,
                    () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        }
        verify(aiProviderConfigMapper, never()).upsert(any());
    }

    @Test
    void save_supportedClaudeModelIds_areAccepted() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:valid");
        for (String modelId : List.of(
                "anthropic.claude-3-5-sonnet-20240620-v1:0",
                "apac.anthropic.claude-sonnet-4-5-20250929-v1:0")) {
            AiProviderConfigRequest request = validRequest();
            request.setModelId(modelId);

            AiProviderConfigDto result = service.save(WORKSPACE_ID, ACTOR_ID, request);

            assertEquals(modelId, result.getModelId());
        }
    }

    @Test
    void save_credentialsWithUnsafeCharacters_areRejected() {
        AiProviderConfigRequest invalidAccessKey = validRequest();
        invalidAccessKey.setAccessKeyId("AKIA_TEST");
        AiProviderConfigRequest invalidSecret = validRequest();
        invalidSecret.setSecretAccessKey("secret value");
        AiProviderConfigRequest invalidSessionToken = validRequest();
        invalidSessionToken.setSessionToken("TOKEN\r\nInjected");

        for (AiProviderConfigRequest request : List.of(invalidAccessKey, invalidSecret, invalidSessionToken)) {
            BadRequestException exception = assertThrows(BadRequestException.class,
                    () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
            assertEquals("Invalid provider credentials", exception.getMessage());
        }
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
    }

    @Test
    void save_validNewCredential_encryptsAndMasks() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:77");

        AiProviderConfigDto dto = service.save(WORKSPACE_ID, ACTOR_ID, validRequest());

        ArgumentCaptor<String> plaintextCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AiProviderConfig> configCaptor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(aiProviderSecretCipher).encryptCredential(eq(ORG_ID), plaintextCaptor.capture());
        verify(aiProviderConfigMapper).upsert(configCaptor.capture());
        assertTrue(plaintextCaptor.getValue().contains("\"accessKeyId\":\"AKIATEST12345678\""));
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
        stored.setModelId(null);
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        when(aiProviderSecretCipher.isAvailable()).thenReturn(false);
        assertFalse(service.isReadyForOrg(ORG_ID));

        stored = readyConfig();
        when(aiProviderSecretCipher.isAvailable()).thenReturn(true);
        assertTrue(service.isReadyForOrg(ORG_ID));
    }

    @Test
    void savedProvidersAreAdapterReady() {
        lenient().when(aiProperties.isAllowInternalEndpoints()).thenReturn(true);
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any()))
                .thenReturn("secret:v1:provider");

        service.save(WORKSPACE_ID, ACTOR_ID, azureRequest());
        assertTrue(service.isReadyForOrg(ORG_ID));

        service.save(WORKSPACE_ID, ACTOR_ID, vertexRequest());
        assertTrue(service.isReadyForOrg(ORG_ID));

        service.save(WORKSPACE_ID, ACTOR_ID, openAiCompatibleRequest());
        assertTrue(service.isReadyForOrg(ORG_ID));

        service.save(WORKSPACE_ID, ACTOR_ID, validRequest());
        assertTrue(service.isReadyForOrg(ORG_ID));
    }

    @Test
    void resolveForOrg_decryptsAndParsesCredentials() {
        stored = readyConfig();
        when(aiProviderSecretCipher.decryptCredential(ORG_ID, "secret:v1:88"))
                .thenReturn("""
                        {
                          "accessKeyId": "AKIA_TEST",
                          "secretAccessKey": "SECRET_ACCESS_KEY",
                          "sessionToken": "SESSION_TOKEN"
                        }
                        """);

        ResolvedAiProvider resolved = service.resolveForOrg(ORG_ID);

        assertEquals("bedrock", resolved.provider());
        assertEquals("ap-northeast-1", resolved.region());
        assertEquals("anthropic.claude-3-5-sonnet-20240620-v1:0", resolved.modelId());
        assertNull(resolved.endpoint());
        assertNull(resolved.apiVersion());
        assertNull(resolved.deployment());
        assertNull(resolved.projectId());
        assertFalse(resolved.allowInternalEndpoint());
        assertEquals("AKIA_TEST", resolved.credentials().require("accessKeyId"));
        assertEquals("SECRET_ACCESS_KEY", resolved.credentials().require("secretAccessKey"));
        assertEquals("SESSION_TOKEN", resolved.credentials().get("sessionToken"));
        assertFalse(resolved.toString().contains("SECRET_ACCESS_KEY"));
        assertFalse(resolved.toString().contains("SESSION_TOKEN"));
    }

    @Test
    void resolveForOrg_notReady_isForbidden() {
        stored = readyConfig();
        stored.setEnabled(false);

        assertThrows(ForbiddenException.class, () -> service.resolveForOrg(ORG_ID));
        verify(aiProviderSecretCipher, never()).decryptCredential(eq(ORG_ID), any());
    }

    @Test
    void resolveForOrg_malformedBundle_throwsWithoutLeakingBundle() {
        stored = readyConfig();
        when(aiProviderSecretCipher.decryptCredential(ORG_ID, "secret:v1:88"))
                .thenReturn("{\"accessKeyId\":\"AKIA_TEST\",\"secretAccessKey\":\"SUPER_SECRET\"");

        AiProviderException exception = assertThrows(AiProviderException.class, () -> service.resolveForOrg(ORG_ID));

        assertFalse(exception.getMessage().contains("SUPER_SECRET"));
        assertFalse(String.valueOf(exception).contains("SUPER_SECRET"));
    }

    @Test
    void save_openAiCompatibleEndpointChange_dropsPreservedCredential() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:oaic");
        AiProviderConfigRequest first = genericRequest("https://vendor.example.test/v1", "sk-vendor-key-123");
        AiProviderConfigDto saved1 = service.save(WORKSPACE_ID, ACTOR_ID, first);
        assertTrue(saved1.isHasCredential());

        AiProviderConfigRequest moved = genericRequest("https://collector.attacker.test/v1", null);
        AiProviderConfigDto saved2 = service.save(WORKSPACE_ID, ACTOR_ID, moved);

        assertEquals("https://collector.attacker.test/v1", saved2.getEndpoint());
        assertFalse(saved2.isHasCredential());
        verify(aiProviderSecretCipher).deleteCredentialReference(ORG_ID, "secret:v1:oaic");
    }

    @Test
    void save_openAiCompatibleSameEndpointBlankKey_keepsCredential() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:oaic");
        service.save(WORKSPACE_ID, ACTOR_ID, genericRequest("https://vendor.example.test/v1", "sk-vendor-key-123"));

        AiProviderConfigDto saved = service.save(WORKSPACE_ID, ACTOR_ID,
                genericRequest("https://vendor.example.test/v1", null));

        assertTrue(saved.isHasCredential());
        verify(aiProviderSecretCipher, times(1)).encryptCredential(eq(ORG_ID), any());
    }

    @Test
    void save_endpointWithQueryString_isRejected() {
        AiProviderConfigRequest azure = azureRequest();
        azure.setEndpoint("https://connex.openai.azure.com?api-key=leak");
        assertThrows(BadRequestException.class, () -> service.save(WORKSPACE_ID, ACTOR_ID, azure));

        AiProviderConfigRequest generic = genericRequest("https://api.example.test/v1?api_key=leak", null);
        assertThrows(BadRequestException.class, () -> service.save(WORKSPACE_ID, ACTOR_ID, generic));
    }

    @Test
    void save_shortGenericKey_hasNoLast4Disclosure() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:short");
        AiProviderConfigDto saved = service.save(WORKSPACE_ID, ACTOR_ID,
                genericRequest("https://api.example.test/v1", "abc"));

        assertTrue(saved.isHasCredential());
        assertNull(saved.getCredentialLast4());
    }

    @Test
    void save_azureDeploymentDotSegments_areRejected() {
        for (String deployment : List.of(".", "..", "a..b")) {
            AiProviderConfigRequest request = azureRequest();
            request.setDeployment(deployment);
            assertThrows(BadRequestException.class, () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        }
    }

    @Test
    void save_vertexTwoDigitRegion_isAccepted() {
        when(aiProviderSecretCipher.encryptCredential(eq(ORG_ID), any())).thenReturn("secret:v1:vertex");
        AiProviderConfigRequest request = vertexRequest();
        request.setRegion("europe-west12");

        AiProviderConfigDto saved = service.save(WORKSPACE_ID, ACTOR_ID, request);

        assertEquals("europe-west12", saved.getRegion());
    }

    @Test
    void save_vertexUnsupportedModelId_isRejected() {
        for (String modelId : List.of("gemini/2.5-pro", "mistral-large", "gemini..pro")) {
            AiProviderConfigRequest request = vertexRequest();
            request.setModelId(modelId);
            assertThrows(BadRequestException.class, () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        }
        verify(aiProviderConfigMapper, never()).upsert(any());
    }

    @Test
    void save_vertexUnparseablePrivateKey_isRejected() {
        AiProviderConfigRequest request = vertexRequest();
        request.setServiceAccountJson("{\"type\":\"service_account\","
                + "\"client_email\":\"a@b.iam.gserviceaccount.com\","
                + "\"private_key\":\"" + PEM_HEADER + "\\nnotbase64!!!\\n" + PEM_FOOTER + "\","
                + "\"token_uri\":\"https://oauth2.googleapis.com/token\"}");

        assertThrows(BadRequestException.class, () -> service.save(WORKSPACE_ID, ACTOR_ID, request));
        verify(aiProviderSecretCipher, never()).encryptCredential(eq(ORG_ID), any());
    }

    private static AiProviderConfigRequest genericRequest(String endpoint, String apiKey) {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("openai_compatible");
        request.setEndpoint(endpoint);
        request.setModelId("gpt-4o");
        request.setApiKey(apiKey);
        request.setNoTrainingAttested(true);
        request.setEnabled(true);
        return request;
    }

    private static AiProviderConfigRequest validRequest() {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("bedrock");
        request.setRegion("ap-northeast-1");
        request.setModelId("anthropic.claude-3-5-sonnet-20240620-v1:0");
        request.setAccessKeyId("AKIATEST12345678");
        request.setSecretAccessKey("abcd1234wxyz");
        request.setNoTrainingAttested(true);
        request.setEnabled(true);
        return request;
    }

    private static AiProviderConfigRequest azureRequest() {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("azure_openai");
        request.setEndpoint("https://connex.openai.azure.com");
        request.setDeployment("contacts-prod");
        request.setApiVersion("2025-01-01-preview");
        request.setModelId("gpt-5.2");
        request.setApiKey("azure_api_key_1234");
        request.setNoTrainingAttested(true);
        request.setEnabled(true);
        return request;
    }

    private static AiProviderConfigRequest vertexRequest() {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("vertex");
        request.setProjectId("connex-prod1");
        request.setRegion("us-central1");
        request.setModelId("claude-sonnet-4@20250514");
        request.setServiceAccountJson("{"
                + "\"type\": \"service_account\","
                + "\"client_email\": \"connex-agent@connex-prod1.iam.gserviceaccount.com\","
                + "\"private_key\": \"" + PEM_HEADER + "\\n" + VALID_SA_PRIVATE_KEY_B64
                + "\\n" + PEM_FOOTER + "\","
                + "\"token_uri\": \"https://oauth2.googleapis.com/token\""
                + "}");
        request.setNoTrainingAttested(true);
        request.setEnabled(true);
        return request;
    }

    private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";
    private static final String VALID_SA_PRIVATE_KEY_B64 = generateRsaPkcs8Base64();

    private static String generateRsaPkcs8Base64() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return java.util.Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AiProviderConfigRequest openAiCompatibleRequest() {
        AiProviderConfigRequest request = new AiProviderConfigRequest();
        request.setProvider("openai_compatible");
        request.setEndpoint("http://10.0.0.12:11434/v1");
        request.setModelId("llama3.3:70b");
        request.setAllowInternalEndpoint(true);
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
        copy.setEndpoint(source.getEndpoint());
        copy.setApiVersion(source.getApiVersion());
        copy.setDeployment(source.getDeployment());
        copy.setProjectId(source.getProjectId());
        copy.setAllowInternalEndpoint(source.isAllowInternalEndpoint());
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
