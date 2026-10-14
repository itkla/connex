package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.DeliveryProviderConfig;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.delivery.CampaignDispatchService;
import ooo.klae.connex.backend.delivery.CampaignSendWorker;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.DeliveryProviderSecretCipher;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.esp.HttpEspDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.DeliveryWebhookTokenDto;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.DeliveryProviderConfigMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Verifies dispatch and webhook secret-use attribution through the real secret store and audit log. */
@TestPropertySource(properties = {
    "connex.delivery.enabled=true",
    "connex.workflows.triggered-send.enabled=true",
    "connex.secret-store.key-id=dispatch-test",
    "connex.secret-store.master-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CampaignSecretUseAuditIntegrationTest extends CampaignRealDbTestSupport {

    private static final String PROVIDER = HttpEspDeliveryProvider.PROVIDER_ID;
    private static final String CREDENTIAL = "dispatch-test-credential";

    @Autowired private CampaignService campaignService;
    @Autowired private CampaignSendService campaignSendService;
    @Autowired private CampaignTriggeredSendService triggeredSendService;
    @Autowired private CampaignDispatchService dispatchService;
    @Autowired private CampaignSendWorker worker;
    @Autowired private CampaignDeliveryMapper deliveryMapper;
    @Autowired private DeliveryProviderConfigMapper configMapper;
    @Autowired private DeliveryProviderConfigService configService;
    @Autowired private DeliveryProviderSecretCipher secretCipher;
    @Autowired private DeliveryWebhookService webhookService;
    @Autowired private HttpEspDeliveryProvider espProvider;
    @Autowired private TenantWorkScope tenantWorkScope;
    @Autowired private AutomationScope automationScope;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private DeliveryProviderRouter router;
    @MockitoSpyBean private SystemActor systemActor;

    private User systemUser;

    @BeforeEach
    void configureProvider() {
        systemUser = systemActor.user();
        DeliveryProviderConfig config = new DeliveryProviderConfig();
        config.setWorkspaceId(workspace.getId());
        config.setChannel("email");
        config.setProvider(PROVIDER);
        config.setEndpoint("https://esp.example.test/send");
        config.setFromAddress("sender@example.test");
        config.setEnabled(true);
        config.setCredentialRef(secretCipher.encryptCredential(
                workspace.getId(), DeliveryChannel.EMAIL, CREDENTIAL));
        config.setCreatedById(currentUser.getId());
        configMapper.upsert(config, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dispatchSecretUseNamesSystemActorAndRestoresCallerBeforeEgress(boolean authenticatedCaller) {
        CampaignTriggeredSendService.EnrollmentResult send = enroll();
        if (!authenticatedCaller) {
            clearRequestContext();
            clearAuthentication();
        }
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        when(router.dispatcherFor(PROVIDER)).thenReturn(dispatcher);
        when(dispatcher.dispatch(any(), any())).thenAnswer(invocation -> {
            ResolvedDeliveryProvider target = invocation.getArgument(0);
            assertEquals(CREDENTIAL, target.credentials().require("apiKey"));
            assertFalse(automationScope.isActive());
            if (authenticatedCaller) {
                assertEquals(currentUser, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
                assertEquals(currentUser.getId(), tenantContext.getUserId());
            } else {
                assertNull(SecurityContextHolder.getContext().getAuthentication());
            }
            return DispatchReceipt.sent("dispatch-audit", null);
        });

        assertTrue(tenantWorkScope.inWorkspace(workspace.getId(),
                () -> dispatchService.processSend(workspace.getId(), send.sendId())));

        assertEquals("dispatched", deliveryMapper.getDelivery(
                workspace.getId(), send.deliveryId()).getStatus());
        verify(dispatcher).dispatch(any(), any());
        assertSystemUseRows("workspace.delivery.provider_credential.email");
        if (!authenticatedCaller) {
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertFalse(tenantContext.isResolved());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void webhookSigningSecretUseNamesSystemActorEvenWhenSignatureIsRejected(boolean validSignature)
            throws Exception {
        DeliveryWebhookTokenDto token = configService.issueWebhookToken("email");
        when(router.eventSourceFor(PROVIDER)).thenReturn(espProvider);
        byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = validSignature ? HexFormat.of().formatHex(mac.doFinal(body)) : "00";
        clearRequestContext();
        clearAuthentication();

        if (validSignature) {
            assertEquals(0, webhookService.ingest(PROVIDER, token.token(), body,
                    Map.of(HttpEspDeliveryProvider.SIGNATURE_HEADER, signature)));
        } else {
            assertThrows(DeliveryProviderException.class, () -> webhookService.ingest(
                    PROVIDER, token.token(), body, Map.of(HttpEspDeliveryProvider.SIGNATURE_HEADER, signature)));
        }

        assertSystemUseRows("workspace.delivery.webhook_secret");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(tenantContext.isResolved());
        assertFalse(automationScope.isActive());
    }

    @Test
    void missingSystemUserFailsOneSendAndWorkerContinuesToTheNextSend() {
        CampaignTriggeredSendService.EnrollmentResult first = enroll();
        CampaignTriggeredSendService.EnrollmentResult second = enroll();
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        when(router.dispatcherFor(PROVIDER)).thenReturn(dispatcher);
        when(dispatcher.dispatch(any(), any())).thenReturn(DispatchReceipt.sent("continued-send", null));
        SystemActor unprovisioned = new SystemActor(mock(UserMapper.class));
        clearInvocations(systemActor);
        doAnswer(invocation -> unprovisioned.user()).doCallRealMethod().when(systemActor).user();
        clearRequestContext();
        clearAuthentication();
        ReflectionTestUtils.setField(worker, "dispatchEnabled", true);
        try {
            worker.dispatch();
        } finally {
            ReflectionTestUtils.setField(worker, "dispatchEnabled", false);
        }

        List<String> statuses = List.of(
                deliveryMapper.getDelivery(workspace.getId(), first.deliveryId()).getStatus(),
                deliveryMapper.getDelivery(workspace.getId(), second.deliveryId()).getStatus());
        assertEquals(1, statuses.stream().filter("pending"::equals).count());
        assertEquals(1, statuses.stream().filter("dispatched"::equals).count());
        verify(systemActor, times(2)).user();
        verify(dispatcher).dispatch(any(), any());
        assertSystemUseRows("workspace.delivery.provider_credential.email");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_run WHERE workspace_id = ? AND job_name = 'campaign_send' "
                        + "AND status = 'failed' AND JSON_EXTRACT(detail, '$.failedCount') = 1",
                Integer.class, workspace.getId()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertFalse(tenantContext.isResolved());
    }

    private void assertSystemUseRows(String purpose) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT actor_id, actor_label, target_label FROM audit_log "
                        + "WHERE workspace_id = ? AND action = 'secret_store.secret.use'",
                workspace.getId());
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.getFirst();
        assertEquals(systemUser.getId(), row.get("actor_id"));
        assertEquals(systemUser.getDisplayName(), row.get("actor_label"));
        assertEquals(purpose, row.get("target_label"));
        Map<String, Object> origin = jdbcTemplate.queryForMap("""
                SELECT JSON_UNQUOTE(JSON_EXTRACT(context, '$.executionOrigin.source')) AS source,
                       JSON_UNQUOTE(JSON_EXTRACT(context, '$.executionOrigin.automation')) AS automation,
                       JSON_UNQUOTE(JSON_EXTRACT(context, '$.executionOrigin.executionMode')) AS execution_mode
                FROM audit_log
                WHERE workspace_id = ? AND action = 'secret_store.secret.use'
                """, workspace.getId());
        assertEquals(purpose.equals("workspace.delivery.webhook_secret")
            ? "delivery_webhook" : "campaign_dispatch", origin.get("source"));
        assertEquals("false", origin.get("automation"));
        assertEquals("system", origin.get("execution_mode"));
    }

    private CampaignTriggeredSendService.EnrollmentResult enroll() {
        String suffix = unique();
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName("Dispatch audit " + suffix);
        person.setEmail(suffix + "@example.test");
        person.setCompany(newCompany());
        personMapper.insert(person);
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Dispatch audit " + suffix, null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        CampaignMessageDto message = campaignSendService.createMessage(
                campaign.id(), new CampaignMessageRequest("Dispatch audit " + suffix, "email"));
        campaignSendService.addRevision(campaign.id(), message.id(), new CampaignMessageRevisionRequest(
                "en", "Hello", "<p>Hello</p><a href=\"{{unsubscribe_url}}\">unsubscribe</a>", null));
        CampaignTriggeredSendService.EnrollmentResult result = triggeredSendService.enroll(
                person.getId(), message.id(), 1);
        assertTrue(result.enrolled());
        return result;
    }
}
