package ooo.klae.connex.backend.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.ai.egress.AiEndpointAddressValidator;
import ooo.klae.connex.backend.beans.DeliveryProviderConfig;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.delivery.provider.esp.HttpEspDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.sms.SmsHttpDeliveryProvider;
import ooo.klae.connex.backend.dto.DeliveryProviderConfigRequest;
import ooo.klae.connex.backend.dto.DeliveryWebhookTokenDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mappers.DeliveryProviderConfigMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Unit tests for the delivery provider config service: credential-at-rest handling, ESP-over-SMTP
 * resolution and fallback, webhook token issuance and token resolution, and RBAC gating on the CRUD
 * surface.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryProviderConfigServiceTest {

    private static final int WORKSPACE = 7;
    private static final int ACTOR = 9;
    private static final String API_KEY = "esp_api_key_secret_1234";
    private static final String ENDPOINT = "https://esp.example.com/v1/send";
    private static final String SMS_API_KEY = "sms_api_key_secret_1234";
    private static final String SMS_ENDPOINT = "https://sms.example.com/v1/messages";

    @Mock private MailConfigResolver mailConfigResolver;
    @Mock private DeliveryProviderConfigMapper mapper;
    @Mock private DeliveryProviderSecretCipher cipher;
    @Mock private AiEndpointAddressValidator endpointValidator;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    private DeliveryProviderConfigService service() {
        return new DeliveryProviderConfigService(mailConfigResolver, mapper, cipher, endpointValidator,
                workspaceService, authService, auditService, sessionSecurityService);
    }

    private void currentWorkspaceAndActor() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(WORKSPACE);
        User actor = new User();
        actor.setId(ACTOR);
        when(authService.getCurrentUser()).thenReturn(actor);
    }

    private DeliveryProviderConfigRequest espRequest() {
        DeliveryProviderConfigRequest request = new DeliveryProviderConfigRequest();
        request.setChannel("email");
        request.setProvider(HttpEspDeliveryProvider.PROVIDER_ID);
        request.setEndpoint(ENDPOINT);
        request.setFromAddress("no-reply@sender.test");
        request.setApiKey(API_KEY);
        request.setEnabled(true);
        return request;
    }

    private DeliveryProviderConfigRequest smsRequest() {
        DeliveryProviderConfigRequest request = new DeliveryProviderConfigRequest();
        request.setChannel("sms");
        request.setProvider(SmsHttpDeliveryProvider.PROVIDER_ID);
        request.setEndpoint(SMS_ENDPOINT);
        request.setFromAddress("Connex");
        request.setApiKey(SMS_API_KEY);
        request.setEnabled(true);
        return request;
    }

    @Test
    void save_acceptsTheSmsProviderOnTheSmsChannelAndStoresOnlyASecretReference() {
        currentWorkspaceAndActor();
        when(endpointValidator.isFetchable("sms.example.com", false)).thenReturn(true);
        when(cipher.encryptCredential(WORKSPACE, SMS_API_KEY)).thenReturn("secret:v1:77");
        when(mapper.findByWorkspaceChannel(WORKSPACE, "sms")).thenReturn(null, enabledSms());

        service().save(smsRequest());

        ArgumentCaptor<DeliveryProviderConfig> captor = ArgumentCaptor.forClass(DeliveryProviderConfig.class);
        verify(mapper).upsert(captor.capture());
        DeliveryProviderConfig saved = captor.getValue();
        assertEquals(SmsHttpDeliveryProvider.PROVIDER_ID, saved.getProvider());
        assertEquals("sms", saved.getChannel());
        assertEquals("secret:v1:77", saved.getCredentialRef());
        assertEquals("1234", saved.getCredentialLast4());
        assertNotEquals(SMS_API_KEY, saved.getCredentialRef());
    }

    @Test
    void save_rejectsTheSmsProviderOnTheEmailChannel() {
        currentWorkspaceAndActor();
        DeliveryProviderConfigRequest request = smsRequest();
        request.setChannel("email");
        request.setFromAddress("no-reply@sender.test");

        assertThrows(BadRequestException.class, () -> service().save(request));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void save_rejectsTheEspProviderOnTheSmsChannel() {
        currentWorkspaceAndActor();
        DeliveryProviderConfigRequest request = espRequest();
        request.setChannel("sms");

        assertThrows(BadRequestException.class, () -> service().save(request));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void save_rejectsAnSmsSenderIdThatIsNotAValidSenderId() {
        currentWorkspaceAndActor();
        when(endpointValidator.isFetchable("sms.example.com", false)).thenReturn(true);
        DeliveryProviderConfigRequest request = smsRequest();
        request.setFromAddress("bad/sender!");

        assertThrows(BadRequestException.class, () -> service().save(request));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void resolveForWorkspace_resolvesAnEnabledSmsConfigWithItsDecryptedCredential() {
        when(mapper.findByWorkspaceChannel(WORKSPACE, "sms")).thenReturn(enabledSms());
        when(cipher.decryptCredential(WORKSPACE, "secret:v1:77")).thenReturn(SMS_API_KEY);

        ResolvedDeliveryProvider resolved = service().resolveForWorkspace(WORKSPACE, DeliveryChannel.SMS);

        assertEquals(SmsHttpDeliveryProvider.PROVIDER_ID, resolved.providerId());
        assertEquals(DeliveryChannel.SMS, resolved.channel());
        assertEquals(SMS_ENDPOINT, resolved.endpoint());
        assertEquals(SMS_API_KEY, resolved.credentials().require("apiKey"));
    }

    @Test
    void resolveForWorkspace_neverFallsBackToSmtpForSms() {
        when(mapper.findByWorkspaceChannel(WORKSPACE, "sms")).thenReturn(null);

        assertThrows(DeliveryProviderException.class,
                () -> service().resolveForWorkspace(WORKSPACE, DeliveryChannel.SMS));
        verify(mailConfigResolver, never()).resolveForWorkspace(WORKSPACE);
    }

    @Test
    void isReady_requiresAnEnabledSmsConfigWithAnEndpointCredentialAndCipher() {
        when(mapper.findByWorkspaceChannel(WORKSPACE, "sms")).thenReturn(enabledSms());
        when(cipher.isAvailable()).thenReturn(true);
        assertTrue(service().isReady(WORKSPACE, DeliveryChannel.SMS));
    }

    @Test
    void isReady_isFalseForSmsWhenTheConfigIsMissingOrDisabled() {
        DeliveryProviderConfig disabled = enabledSms();
        disabled.setEnabled(false);
        when(mapper.findByWorkspaceChannel(WORKSPACE, "sms")).thenReturn(null, disabled);

        assertFalse(service().isReady(WORKSPACE, DeliveryChannel.SMS));
        assertFalse(service().isReady(WORKSPACE, DeliveryChannel.SMS));
    }

    @Test
    void save_storesOnlyASecretReferenceAndLast4_neverThePlaintext() {
        currentWorkspaceAndActor();
        when(endpointValidator.isFetchable("esp.example.com", false)).thenReturn(true);
        when(cipher.encryptCredential(WORKSPACE, API_KEY)).thenReturn("secret:v1:55");
        when(mapper.findByWorkspaceChannel(WORKSPACE, "email")).thenReturn(null, enabledEsp());

        service().save(espRequest());

        ArgumentCaptor<DeliveryProviderConfig> captor = ArgumentCaptor.forClass(DeliveryProviderConfig.class);
        verify(mapper).upsert(captor.capture());
        DeliveryProviderConfig saved = captor.getValue();
        assertEquals("secret:v1:55", saved.getCredentialRef());
        assertEquals("1234", saved.getCredentialLast4());
        assertNotEquals(API_KEY, saved.getCredentialRef());
        assertFalse(API_KEY.equals(saved.getCredentialLast4()));
        verify(sessionSecurityService).requireRecentAuthentication(ACTOR);
    }

    @Test
    void save_enablingEspWithoutACredential_isRejected() {
        currentWorkspaceAndActor();
        when(endpointValidator.isFetchable("esp.example.com", false)).thenReturn(true);
        DeliveryProviderConfigRequest request = espRequest();
        request.setApiKey(null);

        assertThrows(RuntimeException.class, () -> service().save(request));
    }

    @Test
    void save_repointingEspEndpointToANewHostWithoutReenteringCredential_isRejected() {
        currentWorkspaceAndActor();
        when(mapper.findByWorkspaceChannel(WORKSPACE, "email")).thenReturn(enabledEsp());
        when(endpointValidator.isFetchable("evil.example.com", false)).thenReturn(true);
        DeliveryProviderConfigRequest request = espRequest();
        request.setApiKey(null);
        request.setEndpoint("https://evil.example.com/v1/send");

        assertThrows(BadRequestException.class, () -> service().save(request));
        verify(mapper, never()).upsert(any());
    }

    @Test
    void resolveForWorkspace_prefersAnEnabledEspConfigOverSmtp() {
        DeliveryProviderConfig config = enabledEsp();
        when(mapper.findByWorkspaceChannel(WORKSPACE, "email")).thenReturn(config);
        when(cipher.decryptCredential(WORKSPACE, "secret:v1:55")).thenReturn(API_KEY);

        ResolvedDeliveryProvider resolved = service().resolveForWorkspace(WORKSPACE, DeliveryChannel.EMAIL);

        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, resolved.providerId());
        assertEquals(ENDPOINT, resolved.endpoint());
        assertEquals(API_KEY, resolved.credentials().require("apiKey"));
    }

    @Test
    void resolveForWorkspace_fallsBackToSmtpWhenNoEspConfig() {
        when(mapper.findByWorkspaceChannel(WORKSPACE, "email")).thenReturn(null);
        ResolvedMailConfig mail = usableMail();
        when(mailConfigResolver.resolveForWorkspace(WORKSPACE)).thenReturn(mail);

        ResolvedDeliveryProvider resolved = service().resolveForWorkspace(WORKSPACE, DeliveryChannel.EMAIL);

        assertEquals("smtp", resolved.providerId());
    }

    @Test
    void isReady_reflectsEspReadinessAndSmtpFallback() {
        when(mapper.findByWorkspaceChannel(WORKSPACE, "email")).thenReturn(enabledEsp());
        when(cipher.isAvailable()).thenReturn(true);
        assertTrue(service().isReady(WORKSPACE, DeliveryChannel.EMAIL));
        assertFalse(service().isReady(WORKSPACE, DeliveryChannel.SMS));
    }

    @Test
    void issueWebhookToken_persistsOnlyTheHashAndReference_andRevealsTheRawPairOnce() {
        currentWorkspaceAndActor();
        DeliveryProviderConfig config = enabledEsp();
        when(mapper.findByWorkspaceChannel(WORKSPACE, "email")).thenReturn(config);
        when(cipher.encryptWebhookSecret(eq(WORKSPACE), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("secret:v1:88");

        DeliveryWebhookTokenDto reveal = service().issueWebhookToken("email");

        assertEquals(HttpEspDeliveryProvider.SIGNATURE_HEADER, reveal.signatureHeader());
        assertTrue(reveal.token().matches("[a-f0-9]{64}"));
        assertTrue(reveal.secret().matches("[a-f0-9]{64}"));
        ArgumentCaptor<DeliveryProviderConfig> captor = ArgumentCaptor.forClass(DeliveryProviderConfig.class);
        verify(mapper).upsert(captor.capture());
        assertEquals(sha256Hex(reveal.token()), captor.getValue().getWebhookTokenHash());
        assertEquals("secret:v1:88", captor.getValue().getWebhookSecretRef());
        assertNotEquals(reveal.secret(), captor.getValue().getWebhookSecretRef());
    }

    @Test
    void resolveByWebhookToken_hashesTheTokenAndCarriesTheDecryptedSecret() {
        String rawToken = "a".repeat(64);
        DeliveryProviderConfig config = enabledEsp();
        config.setWebhookSecretRef("secret:v1:88");
        when(mapper.findByWebhookTokenHash(sha256Hex(rawToken))).thenReturn(config);
        when(cipher.decryptWebhookSecret(WORKSPACE, "secret:v1:88")).thenReturn("whsec_raw");

        ResolvedDeliveryProvider resolved = service().resolveByWebhookToken(rawToken);

        assertEquals(WORKSPACE, resolved.workspaceId());
        assertEquals(HttpEspDeliveryProvider.PROVIDER_ID, resolved.providerId());
        assertEquals("whsec_raw", resolved.credentials().require("webhookSecret"));
    }

    @Test
    void resolveByWebhookToken_rejectsAnUnknownToken() {
        when(mapper.findByWebhookTokenHash(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        assertThrows(DeliveryProviderException.class, () -> service().resolveByWebhookToken("b".repeat(64)));
    }

    @Test
    void crudMethods_areGatedByWorkspaceSettingsPermission() throws Exception {
        assertPermission("list");
        assertPermission("save", DeliveryProviderConfigRequest.class);
        assertPermission("issueWebhookToken", String.class);
        assertPermission("delete", String.class);
    }

    private static void assertPermission(String method, Class<?>... args) throws Exception {
        Method target = DeliveryProviderConfigService.class.getMethod(method, args);
        RequirePermission annotation = target.getAnnotation(RequirePermission.class);
        assertTrue(annotation != null, method + " must be @RequirePermission gated");
        assertEquals(Permission.WORKSPACE_SETTINGS, annotation.value());
    }

    private DeliveryProviderConfig enabledSms() {
        DeliveryProviderConfig config = new DeliveryProviderConfig();
        config.setWorkspaceId(WORKSPACE);
        config.setChannel("sms");
        config.setProvider(SmsHttpDeliveryProvider.PROVIDER_ID);
        config.setEndpoint(SMS_ENDPOINT);
        config.setFromAddress("Connex");
        config.setCredentialRef("secret:v1:77");
        config.setCreatedById(ACTOR);
        config.setEnabled(true);
        return config;
    }

    private DeliveryProviderConfig enabledEsp() {
        DeliveryProviderConfig config = new DeliveryProviderConfig();
        config.setWorkspaceId(WORKSPACE);
        config.setChannel("email");
        config.setProvider(HttpEspDeliveryProvider.PROVIDER_ID);
        config.setEndpoint(ENDPOINT);
        config.setFromAddress("no-reply@sender.test");
        config.setCredentialRef("secret:v1:55");
        config.setCreatedById(ACTOR);
        config.setEnabled(true);
        return config;
    }

    private static ResolvedMailConfig usableMail() {
        return new ResolvedMailConfig("smtp.test", 587, "user", "pw", "no-reply@sender.test",
                "Connex", true, false, true, 10000, 10000, 10000, true);
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
