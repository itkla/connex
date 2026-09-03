package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.SmartValidator;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceExport;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.controllers.CampaignExportController;
import ooo.klae.connex.backend.delivery.AudienceMember;
import ooo.klae.connex.backend.delivery.AudiencePush;
import ooo.klae.connex.backend.delivery.AudiencePushResult;
import ooo.klae.connex.backend.delivery.AudienceSyncConnector;
import ooo.klae.connex.backend.delivery.ConnectorConfigService;
import ooo.klae.connex.backend.delivery.DeliveryCredentials;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ResolvedAudienceTarget;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.CampaignAudienceExportDto;
import ooo.klae.connex.backend.dto.CampaignAudienceExportReconciliationRequest;
import ooo.klae.connex.backend.dto.CampaignAudienceExportRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.CampaignAudienceExportMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.services.AudienceEligibilityService.AudienceClassification;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantContext;
import tools.jackson.databind.ObjectMapper;

/** Unit tests for the transaction and fail-closed contracts at the audience-export choke point. */
@ExtendWith(MockitoExtension.class)
class AudienceExportServiceTest {

    private static final int WORKSPACE = 7;
    private static final int CAMPAIGN = 3;
    private static final int SNAPSHOT = 44;
    private static final int VERSION = 2;
    private static final int ACTOR = 9;
    private static final int EXPORT = 71;
    private static final String CONNECTOR = "http_list";
    private static final String LIST_ID = "list-9";
    private static final int CONFIG_ID = 81;
    private static final long CONFIG_VERSION = 4L;
    private static final Set<Permission> EXPORT_PERMISSIONS = Set.of(
            Permission.CAMPAIGN_MANAGE, Permission.CONSENT_MANAGE);

    @Mock private CampaignMapper campaignMapper;
    @Mock private CampaignAudienceExportMapper exportMapper;
    @Mock private PersonMapper personMapper;
    @Mock private AudienceEligibilityService audienceEligibilityService;
    @Mock private ConnectorConfigService connectorConfigService;
    @Mock private DeliveryProviderRouter deliveryProviderRouter;
    @Mock private CapabilityRegistry capabilityRegistry;
    @Mock private WorkspaceService workspaceService;
    @Mock private TenantContext tenantContext;
    @Mock private AuditService auditService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;
    @Mock private AudienceSyncConnector connector;
    @Mock private SmartValidator validator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CampaignAudienceExport storedExport;

    @BeforeEach
    void executeTransactionCallbacks() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
    }

    private AudienceExportService service() {
        return new AudienceExportService(campaignMapper, exportMapper, personMapper,
                audienceEligibilityService, connectorConfigService, deliveryProviderRouter,
                capabilityRegistry, workspaceService, tenantContext, auditService,
                transactionManager, objectMapper, validator);
    }

    private CampaignAudienceExportRequest request() {
        return new CampaignAudienceExportRequest(VERSION, CONNECTOR);
    }

    private CampaignAudienceSnapshot primeCreateExport() {
        lenient().when(tenantContext.isResolved()).thenReturn(true);
        lenient().when(tenantContext.getWorkspaceId()).thenReturn(WORKSPACE);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(ACTOR);
        lenient().when(workspaceService.lockedMemberPermissionsFor(WORKSPACE, ACTOR))
                .thenReturn(EXPORT_PERMISSIONS);
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN);
        campaign.setName("Q3");
        lenient().when(campaignMapper.getCampaignForUpdate(WORKSPACE, CAMPAIGN)).thenReturn(campaign);
        lenient().when(capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)).thenReturn(true);
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setId(SNAPSHOT);
        snapshot.setRecordType("person");
        snapshot.setChannel("email");
        snapshot.setPurpose("product_update");
        lenient().when(campaignMapper.getSnapshotForShare(WORKSPACE, CAMPAIGN, VERSION)).thenReturn(snapshot);
        lenient().when(connectorConfigService.isReady(WORKSPACE, CONNECTOR)).thenReturn(true);
        lenient().when(campaignMapper.getSnapshotMembers(WORKSPACE, SNAPSHOT)).thenReturn(List.of(
                member(1, "included"), member(2, "included"), member(3, "excluded")));
        lenient().when(connectorConfigService.resolveAudienceTargetForWorkspace(WORKSPACE, CONNECTOR))
                .thenReturn(resolvedTarget());
        lenient().when(connectorConfigService.isCurrentAudienceTarget(
                WORKSPACE, CONNECTOR, resolvedTarget())).thenReturn(true);
        lenient().when(deliveryProviderRouter.connectorFor(CONNECTOR)).thenReturn(connector);
        lenient().doAnswer(invocation -> {
            storedExport = invocation.getArgument(0, CampaignAudienceExport.class);
            storedExport.setId(EXPORT);
            return null;
        }).when(exportMapper).insertExport(any(CampaignAudienceExport.class));
        lenient().when(exportMapper.getExportForUpdate(WORKSPACE, EXPORT)).thenAnswer(invocation -> storedExport);
        lenient().when(exportMapper.getExport(WORKSPACE, EXPORT)).thenAnswer(invocation -> storedExport);
        lenient().when(exportMapper.nextAttemptForSnapshotTarget(
                WORKSPACE, CAMPAIGN, SNAPSHOT, CONNECTOR, LIST_ID)).thenReturn(1);
        lenient().when(exportMapper.stagePush(any(CampaignAudienceExport.class))).thenReturn(1);
        lenient().when(exportMapper.updateOutcome(any(CampaignAudienceExport.class))).thenReturn(1);
        return snapshot;
    }

    @Test
    void createExportPersistsThePreparedSetAndFinalRecheckDropsAndRecordsRevokedContacts() {
        primeCreateExport();
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(
                        classification(List.of(1, 2)),
                        new AudienceClassification(
                                Set.of(), Set.of(), Set.of(), Set.of(2), List.of(1)));
        when(personMapper.getByIds(WORKSPACE, List.of(1)))
                .thenReturn(List.of(person(1, "Ada Lovelace", "a@dest.test")));
        ArgumentCaptor<AudiencePush> pushCaptor = ArgumentCaptor.forClass(AudiencePush.class);
        when(connector.pushAudience(eq(target()), pushCaptor.capture()))
                .thenReturn(new AudiencePushResult(1, 0, "connector accepted"));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        assertEquals("[1,2]", storedExport.getFrozenMemberIdsJson());
        assertEquals(2, storedExport.getTotalMembers());
        assertEquals(1, storedExport.getPushedCount());
        assertEquals(1, storedExport.getFailedCount());
        assertEquals("[1]", storedExport.getPushedMemberIdsJson());
        assertEquals("completed", dto.status());
        assertTrue(pushCaptor.getValue().idempotencyKey().startsWith(
                "campaign-audience-44-v2-t"));
        assertTrue(pushCaptor.getValue().idempotencyKey().contains("-m"));
        assertTrue(pushCaptor.getValue().idempotencyKey().endsWith("-a1"));
        assertEquals(List.of("a@dest.test"), pushCaptor.getValue().members().stream()
                .map(member -> member.email()).toList());
        verify(audienceEligibilityService, times(2)).classify(
                WORKSPACE, List.of(1, 2), "email", "product_update");
        verify(exportMapper).stagePush(storedExport);
        verify(exportMapper).updateOutcome(storedExport);

        ArgumentCaptor<TransactionDefinition> transactions =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, times(3)).getTransaction(transactions.capture());
        assertTrue(transactions.getAllValues().stream().allMatch(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    void createExportMapsAnAmbiguousProviderOutcomeToReconciliation() {
        primeCreateExport();
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(classification(List.of(1, 2)));
        when(personMapper.getByIds(WORKSPACE, List.of(1, 2))).thenReturn(List.of(
                person(1, "Ada Lovelace", "a@dest.test"),
                person(2, "Bo Ng", "b@dest.test")));
        when(connector.pushAudience(any(), any()))
                .thenReturn(AudiencePushResult.ambiguous(2, "response unavailable"));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        assertEquals("needs_reconciliation", dto.status());
        assertEquals(null, dto.failedCount());
        assertEquals(null, dto.pushedCount());
    }

    @Test
    void createExportMapsADefiniteNoSideEffectOutcomeToFailed() {
        primeCreateExport();
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(classification(List.of(1, 2)));
        when(personMapper.getByIds(WORKSPACE, List.of(1, 2))).thenReturn(List.of(
                person(1, "Ada Lovelace", "a@dest.test"),
                person(2, "Bo Ng", "b@dest.test")));
        when(connector.pushAudience(any(), any())).thenReturn(
                AudiencePushResult.definiteNoSideEffect(2, "unauthorized"));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        assertEquals("failed", dto.status());
        assertEquals(0, dto.pushedCount());
        assertEquals(2, dto.failedCount());
    }

    @Test
    void createExportMapsAConfirmedZeroAcceptanceToFailed() {
        primeCreateExport();
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(classification(List.of(1, 2)));
        when(personMapper.getByIds(WORKSPACE, List.of(1, 2))).thenReturn(List.of(
                person(1, "Ada Lovelace", "a@dest.test"),
                person(2, "Bo Ng", "b@dest.test")));
        when(connector.pushAudience(any(), any()))
                .thenReturn(new AudiencePushResult(0, 2, "connector accepted"));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        assertEquals("failed", dto.status());
        assertEquals(0, dto.pushedCount());
        assertEquals(2, dto.failedCount());
    }

    @Test
    void connectorExceptionDetailsNeverReachTheAudienceExportLog() {
        primeCreateExport();
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(classification(List.of(1)));
        when(personMapper.getByIds(WORKSPACE, List.of(1)))
                .thenReturn(List.of(person(1, "Ada Lovelace", "private-address@example.test")));
        when(connector.pushAudience(any(), any()))
                .thenThrow(new RuntimeException("private-address@example.test credential=secret-fragment"));
        Logger logger = (Logger) LoggerFactory.getLogger(AudienceExportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

            assertEquals("needs_reconciliation", dto.status());
            assertEquals(1, appender.list.size());
            String message = appender.list.getFirst().getFormattedMessage();
            assertEquals(
                    "Campaign audience export 71 failed in workspace 7 reason=connector_exception",
                    message);
            assertFalse(message.contains("private-address@example.test"));
            assertFalse(message.contains("secret-fragment"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void transactionCMasksCountsWhenConsentPermissionWasRevokedAfterThePush() {
        primeCreateExport();
        when(workspaceService.lockedMemberPermissionsFor(WORKSPACE, ACTOR))
                .thenReturn(EXPORT_PERMISSIONS, EXPORT_PERMISSIONS, Set.of(Permission.CAMPAIGN_MANAGE));
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(classification(List.of(1, 2)));
        when(personMapper.getByIds(WORKSPACE, List.of(1, 2))).thenReturn(List.of(
                person(1, "Ada Lovelace", "a@dest.test"),
                person(2, "Bo Ng", "b@dest.test")));
        when(connector.pushAudience(any(), any())).thenReturn(
                new AudiencePushResult(2, 0, "accepted"));

        CampaignAudienceExportDto dto = service().createExport(CAMPAIGN, request());

        assertEquals("completed", dto.status());
        assertEquals(2, dto.totalMembers());
        assertEquals(false, dto.detailedCountsAvailable());
        assertNull(dto.pushedCount());
        assertNull(dto.failedCount());
        assertEquals(2, storedExport.getPushedCount());
    }

    @Test
    void finalAuthorizationLossAbortsTheWholeExportAndRecordsNoPush() {
        primeCreateExport();
        when(workspaceService.lockedMemberPermissionsFor(WORKSPACE, ACTOR))
                .thenReturn(EXPORT_PERMISSIONS)
                .thenReturn(Set.of(Permission.CONSENT_MANAGE));
        when(audienceEligibilityService.classify(
                eq(WORKSPACE), anyList(), eq("email"), eq("product_update")))
                .thenReturn(classification(List.of(1, 2)));

        ForbiddenException exception = assertThrows(
                ForbiddenException.class, () -> service().createExport(CAMPAIGN, request()));

        assertEquals("Requires the CAMPAIGN_MANAGE permission in this workspace", exception.getMessage());
        assertEquals("failed", storedExport.getStatus());
        assertEquals(2, storedExport.getFailedCount());
        verify(connector, never()).pushAudience(any(), any());
    }

    @Test
    void createExportRejectsADuplicateUnderTheCampaignMutex() {
        primeCreateExport();
        when(exportMapper.existsActiveForSnapshotConnector(
                WORKSPACE, CAMPAIGN, SNAPSHOT, CONNECTOR)).thenReturn(true);

        assertThrows(BadRequestException.class, () -> service().createExport(CAMPAIGN, request()));

        verify(exportMapper, never()).insertExport(any());
        verify(connector, never()).pushAudience(any(), any());
    }

    @Test
    void createExportRejectsANonEmailSnapshotBeforeConnectorEgress() {
        CampaignAudienceSnapshot snapshot = primeCreateExport();
        snapshot.setChannel("sms");

        BadRequestException exception = assertThrows(
                BadRequestException.class, () -> service().createExport(CAMPAIGN, request()));

        assertEquals("Only email audience snapshots can be exported", exception.getMessage());
        verify(connectorConfigService, never()).isReady(anyInt(), any());
        verify(exportMapper, never()).insertExport(any());
        verify(connector, never()).pushAudience(any(), any());
    }

    @Test
    void createExportRequiresResolvedTenantContextWithoutWorkspaceFallback() {
        when(tenantContext.isResolved()).thenReturn(false);

        ForbiddenException exception = assertThrows(
                ForbiddenException.class, () -> service().createExport(CAMPAIGN, request()));

        assertEquals("A resolved workspace membership is required", exception.getMessage());
        verify(workspaceService, never()).getCurrentWorkspaceId();
        verify(campaignMapper, never()).getCampaignForUpdate(anyInt(), anyInt());
    }

    @Test
    void readAndWriteMethodsAreRbacGated() throws Exception {
        assertPermission("createExport", Permission.CAMPAIGN_MANAGE,
                int.class, CampaignAudienceExportRequest.class);
        assertPermission("listExports", Permission.CAMPAIGN_VIEW, int.class);
        assertPermission("getExport", Permission.CAMPAIGN_VIEW, int.class, int.class);
        assertPermission("reconcileExport", Permission.CAMPAIGN_MANAGE,
                int.class, int.class, CampaignAudienceExportReconciliationRequest.class);
        Method controllerMethod = CampaignExportController.class.getMethod(
                "reconcile", int.class, int.class,
                CampaignAudienceExportReconciliationRequest.class);
        assertEquals(Permission.CAMPAIGN_MANAGE,
                controllerMethod.getAnnotation(RequirePermission.class).value());
    }

    @Test
    void reconciliationMarksProviderConfirmedDeliveryWithTheRecordedRequestCounts() throws BindException {
        primeReconciliation("[1,2]", 3);

        CampaignAudienceExportDto dto = service().reconcileExport(
                CAMPAIGN, EXPORT,
                new CampaignAudienceExportReconciliationRequest("delivered"));

        assertEquals("completed", dto.status());
        assertEquals(2, dto.pushedCount());
        assertEquals(1, dto.failedCount());
        verify(exportMapper).resolveReconciliation(storedExport);
        ArgumentCaptor<Object> auditChanges = ArgumentCaptor.forClass(Object.class);
        verify(auditService).recordStrict(
                eq("campaign.audience_export.reconcile"), eq("campaign"), eq(CAMPAIGN),
                eq("Q3"), eq("Reconciled campaign audience export"), auditChanges.capture());
        assertEquals(Map.of(
                "exportId", EXPORT,
                "resolution", "delivered",
                "countsKnown", true), auditChanges.getValue());
        InOrder locks = inOrder(workspaceService, campaignMapper, exportMapper);
        locks.verify(workspaceService).lockedMemberPermissionsFor(WORKSPACE, ACTOR);
        locks.verify(campaignMapper).getCampaignForUpdate(WORKSPACE, CAMPAIGN);
        locks.verify(exportMapper).getByCampaign(WORKSPACE, CAMPAIGN);
        locks.verify(exportMapper).getExportForUpdate(WORKSPACE, EXPORT);
    }

    @Test
    void reconciliationMarksProviderConfirmedNoDeliveryAsAReExportableFailure() throws BindException {
        primeReconciliation("[1,2]", 3);

        CampaignAudienceExportDto dto = service().reconcileExport(
                CAMPAIGN, EXPORT,
                new CampaignAudienceExportReconciliationRequest("not_delivered"));

        assertEquals("failed", dto.status());
        assertEquals(0, dto.pushedCount());
        assertEquals(3, dto.failedCount());
    }

    @Test
    void reconciliationRetryReturnsTheMatchingTerminalResultWithoutAnotherTransitionOrAudit()
            throws BindException {
        primeReconciliation("[1,2]", 3);

        CampaignAudienceExportDto first = service().reconcileExport(
                CAMPAIGN, EXPORT,
                new CampaignAudienceExportReconciliationRequest("delivered"));
        CampaignAudienceExportDto retry = service().reconcileExport(
                CAMPAIGN, EXPORT,
                new CampaignAudienceExportReconciliationRequest("delivered"));

        assertEquals("completed", first.status());
        assertEquals(first, retry);
        verify(exportMapper, times(1)).resolveReconciliation(storedExport);
        verify(auditService, times(1)).recordStrict(
                eq("campaign.audience_export.reconcile"), eq("campaign"), eq(CAMPAIGN),
                eq("Q3"), eq("Reconciled campaign audience export"), any());
    }

    @Test
    void reconciliationRejectsAResolutionThatConflictsWithTheTerminalResult() throws BindException {
        primeReconciliation("[1,2]", 3);
        service().reconcileExport(
                CAMPAIGN, EXPORT,
                new CampaignAudienceExportReconciliationRequest("delivered"));

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> service().reconcileExport(
                        CAMPAIGN, EXPORT,
                        new CampaignAudienceExportReconciliationRequest("not_delivered")));

        assertEquals("This export was already reconciled with a different resolution", exception.getMessage());
        verify(exportMapper, times(1)).resolveReconciliation(storedExport);
    }

    @Test
    void reconciliationRequiresBothCurrentLockedPermissions() {
        primeReconciliation("[1,2]", 3);
        when(workspaceService.lockedMemberPermissionsFor(WORKSPACE, ACTOR))
                .thenReturn(Set.of(Permission.CAMPAIGN_MANAGE));

        ForbiddenException exception = assertThrows(
                ForbiddenException.class,
                () -> service().reconcileExport(
                        CAMPAIGN, EXPORT,
                        new CampaignAudienceExportReconciliationRequest("delivered")));

        assertEquals("Requires the CONSENT_MANAGE permission in this workspace", exception.getMessage());
        verify(campaignMapper, never()).getCampaignForUpdate(anyInt(), anyInt());
        verify(exportMapper, never()).resolveReconciliation(any());
    }

    @Test
    void idempotencyKeysBindTheDestinationPayloadAndPersistedAttemptButNotTheExportRow() {
        CampaignAudienceExport first = idempotencyExport(71, 1);
        CampaignAudienceExport sameRequest = idempotencyExport(72, 1);
        List<Integer> memberIds = List.of(19);
        List<AudienceMember> members = List.of(
                new AudienceMember("member@example.test", "Ada", "Lovelace"));

        String firstKey = AudienceExportService.idempotencyKey(
                first, VERSION, resolvedTarget(), memberIds, members);
        String retryKey = AudienceExportService.idempotencyKey(
                sameRequest, VERSION, resolvedTarget(), memberIds, members);
        String changedPayloadKey = AudienceExportService.idempotencyKey(
                sameRequest, VERSION, resolvedTarget(), memberIds,
                List.of(new AudienceMember("changed@example.test", "Ada", "Lovelace")));

        assertEquals(firstKey, retryKey);
        assertNotEquals(firstKey, changedPayloadKey);
        sameRequest.setAttempt(2);
        String replacementKey = AudienceExportService.idempotencyKey(
                sameRequest, VERSION, resolvedTarget(), memberIds, members);
        assertNotEquals(firstKey, replacementKey);
        assertTrue(firstKey.endsWith("-a1"));
        assertTrue(replacementKey.endsWith("-a2"));
    }

    private void primeReconciliation(String pushedMemberIdsJson, int totalMembers) {
        lenient().when(tenantContext.isResolved()).thenReturn(true);
        lenient().when(tenantContext.getWorkspaceId()).thenReturn(WORKSPACE);
        lenient().when(workspaceService.getCurrentUserId()).thenReturn(ACTOR);
        lenient().when(workspaceService.lockedMemberPermissionsFor(WORKSPACE, ACTOR))
                .thenReturn(EXPORT_PERMISSIONS);
        Campaign campaign = new Campaign();
        campaign.setId(CAMPAIGN);
        campaign.setName("Q3");
        lenient().when(campaignMapper.getCampaignForUpdate(WORKSPACE, CAMPAIGN)).thenReturn(campaign);
        storedExport = idempotencyExport(EXPORT, 1);
        storedExport.setCampaignId(CAMPAIGN);
        storedExport.setStatus("needs_reconciliation");
        storedExport.setExternalListId(LIST_ID);
        storedExport.setPushedMemberIdsJson(pushedMemberIdsJson);
        storedExport.setTotalMembers(totalMembers);
        lenient().when(exportMapper.getExportForUpdate(WORKSPACE, EXPORT)).thenReturn(storedExport);
        lenient().when(exportMapper.getExport(WORKSPACE, EXPORT)).thenReturn(storedExport);
        lenient().when(exportMapper.resolveReconciliation(storedExport)).thenReturn(1);
    }

    private static CampaignAudienceExport idempotencyExport(int exportId, int attempt) {
        CampaignAudienceExport export = new CampaignAudienceExport();
        export.setId(exportId);
        export.setWorkspaceId(WORKSPACE);
        export.setSnapshotId(SNAPSHOT);
        export.setConnector(CONNECTOR);
        export.setExternalListId(LIST_ID);
        export.setAttempt(attempt);
        return export;
    }

    private static void assertPermission(String method, Permission expected, Class<?>... args) throws Exception {
        Method target = AudienceExportService.class.getMethod(method, args);
        RequirePermission annotation = target.getAnnotation(RequirePermission.class);
        assertTrue(annotation != null, method + " must be @RequirePermission gated");
        assertEquals(expected, annotation.value());
    }

    private static AudienceClassification classification(List<Integer> includedIds) {
        return new AudienceClassification(Set.of(), Set.of(), Set.of(), Set.of(), includedIds);
    }

    private static CampaignAudienceMember member(int recordId, String status) {
        CampaignAudienceMember member = new CampaignAudienceMember();
        member.setRecordId(recordId);
        member.setRecordType("person");
        member.setStatus(status);
        return member;
    }

    private static Person person(int id, String name, String email) {
        Person person = new Person();
        person.setId(id);
        person.setName(name);
        person.setEmail(email);
        return person;
    }

    private static ResolvedDeliveryProvider target() {
        return ResolvedDeliveryProvider.of(CONNECTOR,
                ooo.klae.connex.backend.delivery.DeliveryChannel.EMAIL, WORKSPACE,
                DeliveryCredentials.of(java.util.Map.of("apiKey", "k")));
    }

    private static ResolvedAudienceTarget resolvedTarget() {
        return new ResolvedAudienceTarget(target(), LIST_ID, CONFIG_ID, CONFIG_VERSION);
    }
}
