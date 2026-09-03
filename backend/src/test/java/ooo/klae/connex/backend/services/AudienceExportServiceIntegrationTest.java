package ooo.klae.connex.backend.services;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.BindException;
import org.springframework.web.context.WebApplicationContext;

import ooo.klae.connex.backend.beans.ConnectorConfig;
import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.AudienceSyncConnector;
import ooo.klae.connex.backend.delivery.ConnectorSecretCipher;
import ooo.klae.connex.backend.delivery.ConnectorConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.delivery.provider.list.HttpListConnector;
import ooo.klae.connex.backend.dto.CampaignAudienceExportDto;
import ooo.klae.connex.backend.dto.CampaignAudienceExportReconciliationRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceExportRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceRequest;
import ooo.klae.connex.backend.dto.CampaignDto;
import ooo.klae.connex.backend.dto.CampaignRequest;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ConnectorConfigMapper;
import ooo.klae.connex.backend.mappers.CampaignAudienceExportMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.support.AuthenticatedSessions;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AudienceExportServiceIntegrationTest extends CampaignRealDbTestSupport {

    private static final String PURPOSE = "product_update";
    private static final String CONNECTOR = HttpListConnector.PROVIDER_ID;

    @Autowired private AudienceExportService audienceExportService;
    @Autowired private CampaignService campaignService;
    @Autowired private ConsentService consentService;
    @Autowired private RoleService roleService;
    @Autowired private ConnectorConfigService connectorConfigService;
    @Autowired private ConnectorConfigMapper connectorConfigMapper;
    @Autowired private CampaignAudienceExportMapper campaignAudienceExportMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WebApplicationContext context;
    @Autowired @Qualifier("springSecurityFilterChain") private Filter springSecurityFilterChain;
    @MockitoBean private CapabilityRegistry capabilityRegistry;
    @MockitoBean private ConnectorSecretCipher connectorSecretCipher;
    @MockitoBean private DeliveryProviderRouter deliveryProviderRouter;
    @MockitoBean private WebAuthnService webAuthnService;

    private AudienceSyncConnector connector;
    private MockMvc mockMvc;
    private String endpoint;
    private String listId;
    private String credentialRef;

    @BeforeEach
    void configureConnector() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
        String fixtureId = workspace.getId() + "-" + unique();
        endpoint = "https://lists.example.test/v1/audience/" + fixtureId;
        listId = "product-updates-" + fixtureId;
        credentialRef = "secret:v1:" + fixtureId;
        connector = mock(AudienceSyncConnector.class);
        when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        connectorConfigMapper.upsert(connectorConfig(endpoint, listId, credentialRef, true));
        when(connectorSecretCipher.isAvailable()).thenReturn(true);
        when(connectorSecretCipher.decryptCredential(workspace.getId(), credentialRef))
                .thenReturn("integration-api-key");
        when(deliveryProviderRouter.connectorFor(CONNECTOR)).thenReturn(connector);
        when(webAuthnService.hasPasskey(anyInt())).thenReturn(true);
    }

    @Test
    void exportRechecksTheFrozenProductUpdatePurposeAfterRevocation() {
        String prefix = "export-purpose-" + unique();
        Company company = newCompany();
        Person eligible = person(company, prefix + "-eligible", prefix + "-eligible@example.com");
        Person revoked = person(company, prefix + "-revoked", prefix + "-revoked@example.com");
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Product update export", null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        var snapshot = campaignService.snapshotAudience(campaign.id());
        int snapshotId = campaignMapper.getSnapshot(
                workspace.getId(), campaign.id(), snapshot.version()).getId();
        consentService.setForPerson(revoked.getId(), new ContactChannelConsentRequest(
                "email", PURPOSE, "revoked", "manual", null, null));
        when(connector.pushAudience(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(AudiencePush.class)))
                .thenReturn(new AudiencePushResult(1, 0, "accepted"));
        ArgumentCaptor<AudiencePush> push = ArgumentCaptor.forClass(AudiencePush.class);

        CampaignAudienceExportDto result = audienceExportService.createExport(
                campaign.id(), new CampaignAudienceExportRequest(snapshot.version(), CONNECTOR));

        verify(connector).pushAudience(org.mockito.ArgumentMatchers.any(), push.capture());
        assertEquals(1, result.totalMembers());
        assertEquals(List.of(eligible.getEmail()),
                push.getValue().members().stream().map(member -> member.email()).toList());
        assertTrue(push.getValue().idempotencyKey().startsWith(
                "campaign-audience-" + snapshotId + "-v" + snapshot.version() + "-t"));
        assertTrue(push.getValue().idempotencyKey().contains("-m"));
        assertTrue(push.getValue().idempotencyKey().endsWith("-a1"));
        CampaignAudienceExport stored = campaignAudienceExportMapper.getExport(
                workspace.getId(), result.id());
        assertEquals("[" + eligible.getId() + "]", stored.getPushedMemberIdsJson());
        var audit = jdbcTemplate.queryForMap("""
                SELECT actor_id, action, entity_type, entity_id, target_label, outcome
                FROM audit_log
                WHERE workspace_id = ?
                  AND action = 'campaign.audience_export.create'
                  AND entity_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, workspace.getId(), campaign.id());
        assertEquals(currentUser.getId(), ((Number) audit.get("actor_id")).intValue());
        assertEquals("campaign.audience_export.create", audit.get("action"));
        assertEquals("campaign", audit.get("entity_type"));
        assertEquals(campaign.id(), ((Number) audit.get("entity_id")).intValue());
        assertEquals("Product update export", audit.get("target_label"));
        assertEquals("success", audit.get("outcome"));
    }

    @Test
    void allMembersRevokedBetweenTransactionsFailAndAllowAReplacementExport() throws Exception {
        String prefix = "export-consent-race-" + unique();
        Person contact = person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Consent race export");
        CountDownLatch releaseFinalRevalidation = new CountDownLatch(1);
        CountDownLatch preparationCommitted = pauseAfterPreparation(releaseFinalRevalidation);
        when(connector.pushAudience(any(), any()))
                .thenReturn(new AudiencePushResult(1, 0, "accepted"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(preparationCommitted.await(10, TimeUnit.SECONDS));
            consentService.setForPerson(contact.getId(), new ContactChannelConsentRequest(
                    "email", PURPOSE, "revoked", "manual", null, null));
            releaseFinalRevalidation.countDown();

            CampaignAudienceExportDto result = export.get(20, TimeUnit.SECONDS);
            assertEquals("failed", result.status());
            assertEquals(1, result.totalMembers());
            assertEquals(0, result.pushedCount());
            assertEquals(1, result.failedCount());
            assertFalse(campaignAudienceExportMapper.existsActiveForSnapshotConnector(
                    workspace.getId(), campaign.id(),
                    campaignMapper.getSnapshot(workspace.getId(), campaign.id(), 1).getId(), CONNECTOR));
            var audit = jdbcTemplate.queryForMap("""
                    SELECT action, outcome, changes
                    FROM audit_log
                    WHERE workspace_id = ?
                      AND action = 'campaign.audience_export.outcome'
                      AND entity_id = ?
                    ORDER BY id DESC
                    LIMIT 1
                    """, workspace.getId(), campaign.id());
            assertEquals("campaign.audience_export.outcome", audit.get("action"));
            assertEquals("success", audit.get("outcome"));
            JsonNode changes = objectMapper.readTree((String) audit.get("changes"));
            assertEquals(result.id(), changes.path("exportId").asInt());
            assertEquals("failed", changes.path("status").asText());
            assertEquals("no_eligible_members", changes.path("reason").asText());

            consentService.setForPerson(contact.getId(), new ContactChannelConsentRequest(
                    "email", PURPOSE, "granted", "manual", null, null));
            CampaignAudienceExportDto replacement = audienceExportService.createExport(
                    campaign.id(), new CampaignAudienceExportRequest(1, CONNECTOR));

            assertEquals("completed", replacement.status());
            assertEquals(1, replacement.pushedCount());
            verify(connector, times(1)).pushAudience(any(), any());
        } finally {
            releaseFinalRevalidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    void restrictionAppliedInTransactionAToTransactionBWindowIsNotPushed() throws Exception {
        String prefix = "export-restriction-race-" + unique();
        Person contact = person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Restriction race export");
        CountDownLatch releaseFinalRevalidation = new CountDownLatch(1);
        CountDownLatch preparationCommitted = pauseAfterPreparation(releaseFinalRevalidation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(preparationCommitted.await(10, TimeUnit.SECONDS));
            personMapper.updateProcessingRestrictions(workspace.getId(), contact.getId(), true, false);
            releaseFinalRevalidation.countDown();

            CampaignAudienceExportDto result = export.get(20, TimeUnit.SECONDS);
            assertEquals(1, result.totalMembers());
            assertEquals(0, result.pushedCount());
            assertEquals(1, result.failedCount());
            verify(connector, never()).pushAudience(any(), any());
        } finally {
            releaseFinalRevalidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    void roleRevokedInTransactionAToTransactionBWindowAbortsTheExport() throws Exception {
        String prefix = "export-role-race-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Role race export");
        CampaignActorRole target = newCampaignActor(workspace);
        CampaignActorRole revoker = newCampaignActor(workspace, List.of(
                Permission.CAMPAIGN_VIEW,
                Permission.CAMPAIGN_MANAGE,
                Permission.CONSENT_MANAGE,
                Permission.ROLE_MANAGE));
        CountDownLatch releaseFinalRevalidation = new CountDownLatch(1);
        CountDownLatch preparationCommitted = pauseAfterPreparation(releaseFinalRevalidation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(target.actor(), campaign.id()));
            assertTrue(preparationCommitted.await(10, TimeUnit.SECONDS));
            authenticateAs(revoker.actor(), workspace.getId());
            roleService.updateRole(
                    workspace.getId(), revoker.actor().getId(), target.role().getId(),
                    target.role().getName(), List.of(
                            Permission.CAMPAIGN_VIEW.name(),
                            Permission.CONSENT_MANAGE.name()));
            releaseFinalRevalidation.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> export.get(20, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof ForbiddenException);
            assertEquals(
                    "Requires the CAMPAIGN_MANAGE permission in this workspace",
                    failure.getCause().getMessage());
            verify(connector, never()).pushAudience(any(), any());
            authenticateAs(currentUser, workspace.getId());
            CampaignAudienceExportDto outcome = audienceExportService.listExports(campaign.id()).getFirst();
            assertEquals("failed", outcome.status());
            assertEquals(1, outcome.failedCount());
        } finally {
            releaseFinalRevalidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    void connectorDisabledInTransactionAToResolutionWindowAbortsBeforePush() throws Exception {
        String prefix = "export-connector-race-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Connector race export");
        CountDownLatch releaseFinalRevalidation = new CountDownLatch(1);
        CountDownLatch preparationCommitted = pauseAfterPreparation(releaseFinalRevalidation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(preparationCommitted.await(10, TimeUnit.SECONDS));
            connectorConfigMapper.upsert(connectorConfig(endpoint, listId, credentialRef, false));
            releaseFinalRevalidation.countDown();

            CampaignAudienceExportDto result = export.get(20, TimeUnit.SECONDS);
            assertEquals("failed", result.status());
            assertEquals(1, result.totalMembers());
            assertEquals(0, result.pushedCount());
            assertEquals(1, result.failedCount());
            verify(connector, never()).pushAudience(any(), any());
        } finally {
            releaseFinalRevalidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    void connectorDeletedInTransactionAToResolutionWindowAbortsBeforePush() throws Exception {
        String prefix = "export-connector-delete-race-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Connector delete race export");
        CountDownLatch releaseFinalRevalidation = new CountDownLatch(1);
        CountDownLatch preparationCommitted = pauseAfterPreparation(releaseFinalRevalidation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(preparationCommitted.await(10, TimeUnit.SECONDS));
            connectorConfigMapper.delete(workspace.getId(), CONNECTOR);
            releaseFinalRevalidation.countDown();

            CampaignAudienceExportDto result = export.get(20, TimeUnit.SECONDS);
            assertEquals("failed", result.status());
            assertEquals(1, result.failedCount());
            verify(connector, never()).pushAudience(any(), any());
        } finally {
            releaseFinalRevalidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    void connectorChangedInTransactionAToResolutionWindowUsesOnlyTheCurrentDestination() throws Exception {
        String prefix = "export-connector-change-race-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Connector change race export");
        CountDownLatch releaseFinalRevalidation = new CountDownLatch(1);
        CountDownLatch preparationCommitted = pauseAfterPreparation(releaseFinalRevalidation);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        String currentEndpoint = "https://current.example.test/v2/audience";
        String currentList = "current-list";
        String currentCredential = "secret:v1:992";
        try {
            when(connector.pushAudience(any(), any()))
                    .thenReturn(new AudiencePushResult(1, 0, "accepted"));
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(preparationCommitted.await(10, TimeUnit.SECONDS));
            connectorConfigMapper.upsert(
                    connectorConfig(currentEndpoint, currentList, currentCredential, true));
            when(connectorSecretCipher.decryptCredential(workspace.getId(), currentCredential))
                    .thenReturn("current-api-key");
            releaseFinalRevalidation.countDown();

            CampaignAudienceExportDto result = export.get(20, TimeUnit.SECONDS);
            ArgumentCaptor<ResolvedDeliveryProvider> target =
                    ArgumentCaptor.forClass(ResolvedDeliveryProvider.class);
            ArgumentCaptor<AudiencePush> push = ArgumentCaptor.forClass(AudiencePush.class);
            verify(connector).pushAudience(target.capture(), push.capture());
            assertEquals(currentEndpoint, target.getValue().endpoint());
            assertEquals("current-api-key", target.getValue().credentials().require("apiKey"));
            assertEquals(currentList, push.getValue().externalListId());
            assertEquals(currentList, result.externalListId());
        } finally {
            releaseFinalRevalidation.countDown();
            shutdown(executor);
        }
    }

    @Test
    void connectorRotationInResolutionToTransactionBFenceWindowAbortsBeforePush() throws Exception {
        String prefix = "export-connector-fence-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Connector fence export");
        CountDownLatch releaseResolution = new CountDownLatch(1);
        CountDownLatch resolvedRowRead = new CountDownLatch(1);
        doAnswer(invocation -> {
            resolvedRowRead.countDown();
            await(releaseResolution, "Audience export connector resolution did not resume");
            return "integration-api-key";
        }).when(connectorSecretCipher).decryptCredential(workspace.getId(), credentialRef);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> export = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(resolvedRowRead.await(10, TimeUnit.SECONDS));
            connectorConfigMapper.upsert(connectorConfig(
                    "https://rotated.example.test/v2/audience",
                    "rotated-list", "secret:v1:rotated", true));
            releaseResolution.countDown();

            CampaignAudienceExportDto result = export.get(20, TimeUnit.SECONDS);

            assertEquals("failed", result.status());
            verify(connector, never()).pushAudience(any(), any());
        } finally {
            releaseResolution.countDown();
            shutdown(executor);
        }
    }

    @Test
    void consentRevokedInsideTransactionBCommitToProviderAcceptanceWindowAppliesToTheNextExport()
            throws Exception {
        String prefix = "export-final-window-" + unique();
        Person contact = person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Final window export");
        CountDownLatch releaseProvider = new CountDownLatch(1);
        CountDownLatch providerCallStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            providerCallStarted.countDown();
            await(releaseProvider, "Audience export provider call did not resume");
            return new AudiencePushResult(1, 0, "accepted");
        }).when(connector).pushAudience(any(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> firstExport = executor.submit(() ->
                    createExportAs(currentUser, campaign.id()));
            assertTrue(providerCallStarted.await(10, TimeUnit.SECONDS));
            consentService.setForPerson(contact.getId(), new ContactChannelConsentRequest(
                    "email", PURPOSE, "revoked", "manual", null, null));
            releaseProvider.countDown();

            CampaignAudienceExportDto first = firstExport.get(20, TimeUnit.SECONDS);
            var nextSnapshot = campaignService.snapshotAudience(campaign.id());
            CampaignAudienceExportDto next = audienceExportService.createExport(
                    campaign.id(), new CampaignAudienceExportRequest(nextSnapshot.version(), CONNECTOR));

            assertEquals(1, first.pushedCount());
            assertEquals(0, next.totalMembers());
            verify(connector, times(1)).pushAudience(any(), any());
        } finally {
            releaseProvider.countDown();
            shutdown(executor);
        }
    }

    @Test
    void staleRunningLeaseIsProjectedWithoutAViewMutationAndBlocksSilentReExport() {
        String prefix = "export-stale-lease-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        String campaignName = "Stale lease export " + prefix;
        CampaignSnapshotFixture fixture = campaignSnapshotFixture(
                prefix, campaignName);
        CampaignDto campaign = fixture.campaign();
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setWorkspaceId(workspace.getId());
        export.setCampaignId(campaign.id());
        export.setSnapshotId(fixture.snapshotId());
        export.setConnector(CONNECTOR);
        export.setFrozenMemberIdsJson("[]");
        export.setPushedMemberIdsJson("[]");
        export.setStatus("running");
        export.setAttempt(1);
        export.setLeaseUntil(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        export.setCreatedById(currentUser.getId());
        campaignAudienceExportMapper.insertExport(export);
        jdbcTemplate.update("""
                UPDATE campaign_audience_export
                SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)
                WHERE workspace_id = ? AND id = ?
                """, workspace.getId(), export.getId());

        CampaignActorRole viewer = newCampaignActor(workspace, List.of(Permission.CAMPAIGN_VIEW));
        authenticateAs(viewer.actor(), workspace.getId());
        CampaignAudienceExportDto history = audienceExportService.listExports(campaign.id()).stream()
                .filter(candidate -> candidate.id() == export.getId())
                .findFirst()
                .orElseThrow();
        CampaignAudienceExportDto detail = audienceExportService.getExport(campaign.id(), export.getId());

        assertEquals("needs_reconciliation", history.status());
        assertEquals("needs_reconciliation", detail.status());
        assertEquals("running", jdbcTemplate.queryForObject("""
                SELECT status
                FROM campaign_audience_export
                WHERE workspace_id = ? AND id = ?
                """, String.class, workspace.getId(), export.getId()));
        authenticateAs(currentUser, workspace.getId());
        assertThrows(ooo.klae.connex.backend.exceptions.BadRequestException.class,
                () -> audienceExportService.createExport(
                        campaign.id(), new CampaignAudienceExportRequest(
                                fixture.snapshotVersion(), CONNECTOR)));
        assertEquals("needs_reconciliation", jdbcTemplate.queryForObject("""
                SELECT status
                FROM campaign_audience_export
                WHERE workspace_id = ? AND id = ?
                """, String.class, workspace.getId(), export.getId()));
        var audit = jdbcTemplate.queryForMap("""
                SELECT actor_id, action, entity_type, entity_id, target_label, outcome, changes
                FROM audit_log
                WHERE workspace_id = ?
                  AND action = 'campaign.audience_export.reconciliation_required'
                  AND entity_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, workspace.getId(), campaign.id());
        assertEquals(currentUser.getId(), ((Number) audit.get("actor_id")).intValue());
        assertEquals("campaign.audience_export.reconciliation_required", audit.get("action"));
        assertEquals("campaign", audit.get("entity_type"));
        assertEquals(campaign.id(), ((Number) audit.get("entity_id")).intValue());
        assertEquals(campaignName, audit.get("target_label"));
        assertEquals("success", audit.get("outcome"));
        JsonNode storedChanges = objectMapper.readTree((String) audit.get("changes"));
        assertEquals(export.getId(), storedChanges.path("exportId").asInt());
        assertEquals("running", storedChanges.path("previousStatus").asText());
        assertEquals("stale_lease", storedChanges.path("reason").asText());
        verify(connector, never()).pushAudience(any(), any());
    }

    @Test
    void exportHistoryMasksEligibilityDerivedOutcomeCountsWithoutConsentManage() {
        String prefix = "export-history-permission-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "History permission export");
        when(connector.pushAudience(any(), any()))
                .thenReturn(new AudiencePushResult(1, 0, "accepted"));
        CampaignAudienceExportDto created = audienceExportService.createExport(
                campaign.id(), new CampaignAudienceExportRequest(1, CONNECTOR));
        CampaignActorRole viewer = newCampaignActor(workspace, List.of(Permission.CAMPAIGN_VIEW));

        authenticateAs(viewer.actor(), workspace.getId());
        CampaignAudienceExportDto restricted = audienceExportService.listExports(campaign.id()).getFirst();

        assertEquals(created.totalMembers(), restricted.totalMembers());
        assertEquals(false, restricted.detailedCountsAvailable());
        assertNull(restricted.pushedCount());
        assertNull(restricted.failedCount());

        authenticateAs(currentUser, workspace.getId());
        CampaignAudienceExportDto detailed = audienceExportService.getExport(campaign.id(), created.id());
        assertEquals(true, detailed.detailedCountsAvailable());
        assertEquals(1, detailed.pushedCount());
        assertEquals(0, detailed.failedCount());
    }

    @Test
    void transactionCUsesCurrentLockedConsentPermissionForTheCreateResponse() throws Exception {
        String prefix = "export-outcome-permission-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Outcome permission export");
        CampaignActorRole target = newCampaignActor(workspace);
        CampaignActorRole revoker = newCampaignActor(workspace, List.of(
                Permission.CAMPAIGN_VIEW,
                Permission.CAMPAIGN_MANAGE,
                Permission.CONSENT_MANAGE,
                Permission.ROLE_MANAGE));
        CountDownLatch providerCallStarted = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        doAnswer(invocation -> {
            providerCallStarted.countDown();
            await(releaseProvider, "Audience export provider call did not resume");
            return new AudiencePushResult(1, 0, "accepted");
        }).when(connector).pushAudience(any(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CampaignAudienceExportDto> pending = executor.submit(() ->
                    createExportAs(target.actor(), campaign.id()));
            assertTrue(providerCallStarted.await(10, TimeUnit.SECONDS));
            authenticateAs(revoker.actor(), workspace.getId());
            roleService.updateRole(
                    workspace.getId(), revoker.actor().getId(), target.role().getId(),
                    target.role().getName(), List.of(
                            Permission.CAMPAIGN_VIEW.name(),
                            Permission.CAMPAIGN_MANAGE.name()));
            releaseProvider.countDown();

            CampaignAudienceExportDto result = pending.get(20, TimeUnit.SECONDS);

            assertEquals(1, result.totalMembers());
            assertEquals(false, result.detailedCountsAvailable());
            assertNull(result.pushedCount());
            assertNull(result.failedCount());
            CampaignAudienceExport stored = campaignAudienceExportMapper.getExport(
                    workspace.getId(), result.id());
            assertEquals("completed", stored.getStatus());
            assertEquals(1, stored.getPushedCount());
        } finally {
            releaseProvider.countDown();
            shutdown(executor);
            authenticateAs(currentUser, workspace.getId());
        }
    }

    @Test
    void providerConfirmedNoDeliveryAdvancesTheAttemptAndKeysTheExactDestinationPayload()
            throws BindException {
        String prefix = "export-reconciliation-retry-" + unique();
        Person contact = person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Reconciliation replacement export");
        when(connector.pushAudience(any(), any())).thenReturn(
                AudiencePushResult.ambiguous(1, "response unavailable"),
                new AudiencePushResult(1, 0, "accepted"));

        CampaignAudienceExportDto ambiguous = audienceExportService.createExport(
                campaign.id(), new CampaignAudienceExportRequest(1, CONNECTOR));
        CampaignAudienceExportDto resolved = audienceExportService.reconcileExport(
                campaign.id(), ambiguous.id(),
                new CampaignAudienceExportReconciliationRequest("not_delivered"));
        CampaignAudienceExportDto replacement = audienceExportService.createExport(
                campaign.id(), new CampaignAudienceExportRequest(1, CONNECTOR));
        ArgumentCaptor<AudiencePush> pushes = ArgumentCaptor.forClass(AudiencePush.class);
        verify(connector, times(2)).pushAudience(any(), pushes.capture());

        assertEquals("needs_reconciliation", ambiguous.status());
        assertEquals("failed", resolved.status());
        assertEquals(0, resolved.pushedCount());
        assertEquals(1, resolved.failedCount());
        assertEquals("completed", replacement.status());
        AudiencePush firstPush = pushes.getAllValues().get(0);
        AudiencePush replacementPush = pushes.getAllValues().get(1);
        CampaignAudienceExport firstStored = campaignAudienceExportMapper.getExport(
                workspace.getId(), ambiguous.id());
        CampaignAudienceExport replacementStored = campaignAudienceExportMapper.getExport(
                workspace.getId(), replacement.id());
        var target = connectorConfigService.resolveAudienceTargetForWorkspace(workspace.getId(), CONNECTOR);
        String rederived = AudienceExportService.idempotencyKey(
                firstStored, 1, target, List.of(contact.getId()), firstPush.members());
        String changedPayload = AudienceExportService.idempotencyKey(
                firstStored, 1, target, List.of(contact.getId()), List.of(
                        new ooo.klae.connex.backend.delivery.AudienceMember(
                                "changed@example.test", null, null)));
        int replacementAttempt = replacementStored.getAttempt();
        replacementStored.setAttempt(firstStored.getAttempt());
        String sameRequestOnAnotherRow = AudienceExportService.idempotencyKey(
                replacementStored, 1, target, List.of(contact.getId()), replacementPush.members());
        replacementStored.setAttempt(replacementAttempt);

        assertEquals(firstPush.idempotencyKey(), rederived);
        assertEquals(firstPush.idempotencyKey(), sameRequestOnAnotherRow);
        assertNotEquals(firstPush.idempotencyKey(), changedPayload);
        assertEquals(1, firstStored.getAttempt());
        assertEquals(2, replacementStored.getAttempt());
        assertTrue(firstPush.idempotencyKey().endsWith("-a1"));
        assertTrue(replacementPush.idempotencyKey().endsWith("-a2"));
        assertNotEquals(firstPush.idempotencyKey(), replacementPush.idempotencyKey());
    }

    @Test
    void providerConfirmedDeliveryCompletesWithoutDisclosingCountsThroughAuditHistory() throws Exception {
        String prefix = "export-reconciliation-delivered-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        String campaignName = "Reconciliation delivered export " + prefix;
        CampaignSnapshotFixture fixture = campaignSnapshotFixture(prefix, campaignName);
        CampaignDto campaign = fixture.campaign();
        when(connector.pushAudience(any(), any()))
                .thenReturn(AudiencePushResult.ambiguous(1, "response unavailable"));

        CampaignAudienceExportDto ambiguous = audienceExportService.createExport(
                campaign.id(), new CampaignAudienceExportRequest(fixture.snapshotVersion(), CONNECTOR));
        CampaignAudienceExportDto resolved = audienceExportService.reconcileExport(
                campaign.id(), ambiguous.id(),
                new CampaignAudienceExportReconciliationRequest("delivered"));
        CampaignAudienceExportDto retry = audienceExportService.reconcileExport(
                campaign.id(), ambiguous.id(),
                new CampaignAudienceExportReconciliationRequest("delivered"));

        assertEquals("completed", resolved.status());
        assertEquals(1, resolved.pushedCount());
        assertEquals(0, resolved.failedCount());
        assertEquals(resolved, retry);
        assertThrows(ooo.klae.connex.backend.exceptions.BadRequestException.class,
                () -> audienceExportService.reconcileExport(
                        campaign.id(), ambiguous.id(),
                        new CampaignAudienceExportReconciliationRequest("not_delivered")));
        assertThrows(ooo.klae.connex.backend.exceptions.BadRequestException.class,
                () -> audienceExportService.createExport(
                        campaign.id(), new CampaignAudienceExportRequest(
                                fixture.snapshotVersion(), CONNECTOR)));
        verify(connector, times(1)).pushAudience(any(), any());
        var audit = jdbcTemplate.queryForMap("""
                SELECT actor_id, action, entity_type, entity_id, target_label, outcome, changes
                FROM audit_log
                WHERE workspace_id = ?
                  AND action = 'campaign.audience_export.reconcile'
                  AND entity_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, workspace.getId(), campaign.id());
        assertEquals(currentUser.getId(), ((Number) audit.get("actor_id")).intValue());
        assertEquals("campaign.audience_export.reconcile", audit.get("action"));
        assertEquals("campaign", audit.get("entity_type"));
        assertEquals(campaign.id(), ((Number) audit.get("entity_id")).intValue());
        assertEquals(campaignName, audit.get("target_label"));
        assertEquals("success", audit.get("outcome"));
        JsonNode storedChanges = objectMapper.readTree((String) audit.get("changes"));
        assertEquals(ambiguous.id(), storedChanges.path("exportId").asInt());
        assertEquals("delivered", storedChanges.path("resolution").asText());
        assertTrue(storedChanges.path("countsKnown").asBoolean());
        assertFalse(storedChanges.has("pushed"));
        assertFalse(storedChanges.has("notPushed"));

        CampaignActorRole auditReader = newCampaignActor(workspace, List.of(Permission.AUDIT_READ));
        String auditJson = mockMvc.perform(authenticatedGet(auditReader.actor(), "/api/audit")
                        .param("entityType", "campaign")
                        .param("entityId", Integer.toString(campaign.id())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode disclosedChanges = requireAuditAction(
                objectMapper.readTree(auditJson), "campaign.audience_export.reconcile").path("changes");
        assertEquals(ambiguous.id(), disclosedChanges.path("exportId").asInt());
        assertEquals("delivered", disclosedChanges.path("resolution").asText());
        assertTrue(disclosedChanges.path("countsKnown").asBoolean());
        assertFalse(disclosedChanges.has("pushed"));
        assertFalse(disclosedChanges.has("notPushed"));

        byte[] csvBytes = mockMvc.perform(authenticatedGet(auditReader.actor(), "/api/audit/export")
                        .param("entityType", "campaign")
                        .param("entityId", Integer.toString(campaign.id())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(csvBytes, StandardCharsets.UTF_8);
        assertTrue(csv.contains("campaign.audience_export.reconcile"));
        assertTrue(csv.contains("countsKnown"));
        assertFalse(csv.contains("\"pushed\""));
        assertFalse(csv.contains("\"notPushed\""));
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM audit_log
                WHERE workspace_id = ?
                  AND action = 'campaign.audience_export.reconcile'
                  AND entity_id = ?
                """, Integer.class, workspace.getId(), campaign.id()));
    }

    @Test
    void legacyUnknownCountsRemainUnknownAfterProviderConfirmedDelivery() throws BindException {
        String prefix = "export-legacy-counts-" + unique();
        person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "Legacy count reconciliation");
        var snapshot = campaignMapper.getSnapshot(workspace.getId(), campaign.id(), 1);
        CampaignAudienceExport legacy = new CampaignAudienceExport();
        legacy.setWorkspaceId(workspace.getId());
        legacy.setCampaignId(campaign.id());
        legacy.setSnapshotId(snapshot.getId());
        legacy.setConnector(CONNECTOR);
        legacy.setExternalListId("legacy-list");
        legacy.setStatus("needs_reconciliation");
        legacy.setAttempt(1);
        legacy.setTotalMembers(1);
        legacy.setPushedCount(null);
        legacy.setFailedCount(null);
        legacy.setCreatedById(currentUser.getId());
        campaignAudienceExportMapper.insertExport(legacy);

        CampaignAudienceExportDto resolved = audienceExportService.reconcileExport(
                campaign.id(), legacy.getId(),
                new CampaignAudienceExportReconciliationRequest("delivered"));

        assertEquals("completed", resolved.status());
        assertEquals(false, resolved.detailedCountsKnown());
        assertEquals(false, resolved.detailedCountsAvailable());
        assertNull(resolved.pushedCount());
        assertNull(resolved.failedCount());
        CampaignAudienceExport stored = campaignAudienceExportMapper.getExport(
                workspace.getId(), legacy.getId());
        assertNull(stored.getPushedCount());
        assertNull(stored.getFailedCount());
    }

    @Test
    void reconciliationHttpBoundaryEnforcesValidationPermissionTenantAndDisclosure() throws Exception {
        String prefix = "export-http-reconciliation-" + unique();
        Person contact = person(newCompany(), prefix, prefix + "@example.com");
        CampaignDto campaign = campaignWithSnapshot(prefix, "HTTP reconciliation export");
        var snapshot = campaignMapper.getSnapshot(workspace.getId(), campaign.id(), 1);
        CampaignAudienceExport ambiguous = new CampaignAudienceExport();
        ambiguous.setWorkspaceId(workspace.getId());
        ambiguous.setCampaignId(campaign.id());
        ambiguous.setSnapshotId(snapshot.getId());
        ambiguous.setConnector(CONNECTOR);
        ambiguous.setExternalListId(listId);
        ambiguous.setFrozenMemberIdsJson("[" + contact.getId() + "]");
        ambiguous.setPushedMemberIdsJson("[" + contact.getId() + "]");
        ambiguous.setStatus("needs_reconciliation");
        ambiguous.setAttempt(1);
        ambiguous.setTotalMembers(1);
        ambiguous.setPushedCount(0);
        ambiguous.setFailedCount(1);
        ambiguous.setCreatedById(currentUser.getId());
        campaignAudienceExportMapper.insertExport(ambiguous);

        CampaignActorRole viewer = newCampaignActor(workspace, List.of(Permission.CAMPAIGN_VIEW));
        mockMvc.perform(reconcileRequest(viewer.actor(), campaign.id(), ambiguous.getId(), "unknown"))
                .andExpect(status().isForbidden());

        mockMvc.perform(reconcileRequest(currentUser, campaign.id(), ambiguous.getId(), "unknown"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Please fix the highlighted fields"))
                .andExpect(jsonPath("$.fieldErrors.resolution").exists())
                .andExpect(jsonPath("$.resolution").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(content().string(not(containsString("Exception"))));

        mockMvc.perform(reconcileRequest(viewer.actor(), campaign.id(), ambiguous.getId(), "delivered"))
                .andExpect(status().isForbidden());

        CampaignActorWorkspace foreign = newCampaignWorkspaceActor();
        authenticateAs(foreign.actor(), foreign.workspace().getId());
        CampaignDto foreignCampaign = campaignService.create(new CampaignRequest(
                "Foreign reconciliation export", null, "email", null, foreign.actor().getId(),
                null, null, null, null, null));
        authenticateAs(currentUser, workspace.getId());
        mockMvc.perform(reconcileRequest(
                        currentUser, foreignCampaign.id(), ambiguous.getId(), "delivered"))
                .andExpect(status().isNotFound());

        mockMvc.perform(reconcileRequest(currentUser, campaign.id(), ambiguous.getId(), "delivered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.pushedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.detailedCountsKnown").value(true))
                .andExpect(jsonPath("$.detailedCountsAvailable").value(true))
                .andExpect(jsonPath("$.frozenMemberIdsJson").doesNotExist())
                .andExpect(jsonPath("$.pushedMemberIdsJson").doesNotExist())
                .andExpect(jsonPath("$.attempt").doesNotExist())
                .andExpect(jsonPath("$.leaseUntil").doesNotExist());
    }

    @Test
    void exportCannotReadASnapshotFromAnotherWorkspace() {
        String prefix = "export-tenant-" + unique();
        person(newCompany(), prefix + "-contact", prefix + "@example.com");
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                "Tenant export", null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        campaignService.snapshotAudience(campaign.id());

        CampaignActorWorkspace other = newCampaignWorkspaceActor();
        authenticateAs(other.actor(), other.workspace().getId());

        assertThrows(ResourceNotFoundException.class, () -> audienceExportService.createExport(
                campaign.id(), new CampaignAudienceExportRequest(1, CONNECTOR)));
        verify(connector, never()).pushAudience(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private Person person(Company company, String name, String email) {
        Person person = new Person();
        person.setWorkspaceId(workspace.getId());
        person.setName(name);
        person.setEmail(email);
        person.setTitle("Campaign contact");
        person.setCompany(company);
        personMapper.insert(person);
        return person;
    }

    private ConnectorConfig connectorConfig(
            String endpoint, String externalListId, String credentialRef, boolean enabled) {
        ConnectorConfig config = new ConnectorConfig();
        config.setWorkspaceId(workspace.getId());
        config.setConnector(CONNECTOR);
        config.setEndpoint(endpoint);
        config.setExternalListId(externalListId);
        config.setCredentialRef(credentialRef);
        config.setCredentialLast4("test");
        config.setEnabled(enabled);
        config.setCreatedById(currentUser.getId());
        return config;
    }

    private CampaignDto campaignWithSnapshot(String prefix, String name) {
        return campaignSnapshotFixture(prefix, name).campaign();
    }

    private CampaignSnapshotFixture campaignSnapshotFixture(String prefix, String name) {
        CampaignDto campaign = campaignService.create(new CampaignRequest(
                name, null, "email", null, currentUser.getId(),
                null, null, null, null, null));
        campaignService.setAudience(campaign.id(), audience(prefix));
        var snapshot = campaignService.snapshotAudience(campaign.id());
        int snapshotId = campaignMapper.getSnapshot(
                workspace.getId(), campaign.id(), snapshot.version()).getId();
        return new CampaignSnapshotFixture(campaign, snapshot.version(), snapshotId);
    }

    private CampaignAudienceExportDto createExportAs(ooo.klae.connex.backend.beans.User actor, int campaignId) {
        authenticateAs(actor, workspace.getId());
        try {
            return audienceExportService.createExport(
                    campaignId, new CampaignAudienceExportRequest(1, CONNECTOR));
        } finally {
            clearAuthentication();
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reconcileRequest(
            ooo.klae.connex.backend.beans.User actor,
            int campaignId,
            int exportId,
            String resolution) {
        var principal = userMapper.getUserById(actor.getId());
        var authenticated = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        MockHttpSession session = authenticatedSession(principal);
        return post("/api/campaigns/{id}/exports/{exportId}/reconcile", campaignId, exportId)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(authentication(authenticated))
                .with(csrf().asHeader())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"" + resolution + "\"}");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authenticatedGet(
            ooo.klae.connex.backend.beans.User actor,
            String path) {
        var principal = userMapper.getUserById(actor.getId());
        var authenticated = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        MockHttpSession session = authenticatedSession(principal);
        return get(path)
                .header("X-Workspace-Id", workspace.getId())
                .session(session)
                .with(authentication(authenticated));
    }

    private static MockHttpSession authenticatedSession(ooo.klae.connex.backend.beans.User principal) {
        MockHttpSession session = AuthenticatedSessions.stampedSession(principal);
        long now = System.currentTimeMillis();
        session.setAttribute(SessionSecurityService.AUTHENTICATED_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.AUTHENTICATED_USER_ATTR, principal.getId());
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_AT_ATTR, now);
        session.setAttribute(SessionSecurityService.WEBAUTHN_STEP_UP_USER_ATTR, principal.getId());
        return session;
    }

    private static JsonNode requireAuditAction(JsonNode entries, String action) {
        for (JsonNode entry : entries) {
            if (action.equals(entry.path("action").asText())) {
                return entry;
            }
        }
        throw new IllegalStateException("Audit action was not disclosed: " + action);
    }

    private CountDownLatch pauseAfterPreparation(CountDownLatch releaseFinalRevalidation) {
        CountDownLatch preparationCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            preparationCommitted.countDown();
            await(releaseFinalRevalidation, "Audience export final revalidation did not resume");
            return connector;
        }).when(deliveryProviderRouter).connectorFor(CONNECTOR);
        return preparationCommitted;
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, exception);
        }
    }

    private static void shutdown(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static CampaignAudienceRequest audience(String prefix) {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("name");
        condition.setOp("starts_with");
        condition.setValue(prefix);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return new CampaignAudienceRequest("person", definition, "email", PURPOSE);
    }

    private record CampaignSnapshotFixture(
            CampaignDto campaign, int snapshotVersion, int snapshotId) {
    }
}
