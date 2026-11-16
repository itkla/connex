package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.ai.assistant.AiAssistantToolCallReadService;
import ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.beans.AiAgentAction;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentProposalDecisionRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.mappers.AiAgentActionMapper;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiAgentProposalReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-19T00:00:00Z");
    private final AiAssistantPrivacyService organizationPrivacy = mock(AiAssistantPrivacyService.class);
    private final AiChatPrivacyGuard privacyGuard = new AiChatPrivacyGuard(organizationPrivacy);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiAgentActionMapper actions = mock(AiAgentActionMapper.class);
    private final AiAgentProposalSetPort proposals = mock(AiAgentProposalSetPort.class);
    private final AiAssistantToolCallReadService cards = mock(AiAssistantToolCallReadService.class);
    private final AiAssistantToolExecutor tools = mock(AiAssistantToolExecutor.class);
    private final ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess recordScopes =
            ooo.klae.connex.backend.support.RecordVisibilityScopes.unfiltered();
    private final AiAgentProposalReadService service = spy(new AiAgentProposalReadService(privacyGuard, workspaces, chat, runs,
            actions, proposals, cards, mock(AiChatCitationProjector.class), tools,
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC), recordScopes, ooo.klae.connex.backend.support.TenantWorkScopes.passThrough(), new ooo.klae.connex.backend.support.TestTransactionManager()));
    private final AiAgentRun run = new AiAgentRun();
    private final AiChatSession session = new AiChatSession();
    private final AiAgentAction action = new AiAgentAction();

    @BeforeEach
    void setUp() {
        run.setId(31);
        run.setWorkspaceId(7);
        run.setSessionId(13);
        run.setOwnerUserId(11);
        run.setMissionType("record_preparation_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(3);
        run.setLocale("en");
        run.setPrivacyGeneration(4);
        run.setOrganizationPrivacyGeneration(1);
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"company\",\"recordId\":41}");
        run.setScopeFingerprint(AiAgentCapabilityGate.hash(run.getScopeJson()));
        new AiAgentContinuationCodec().initialize(run);
        run.setProposalMembershipRevision(3);
        run.setProposalFrozenAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        session.setId(13);
        session.setWorkspaceId(7);
        session.setOrganizationPrivacyGeneration(1);
        session.setCreatedByUserId(11);
        session.setStatus("active");
        session.setVisibility("private");
        action.setId(17);
        action.setActionSlot(UUID.randomUUID().toString());
        action.setPreparationId(UUID.randomUUID().toString());
        action.setOutputOrdinal(1);
        action.setPreparedAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC));
        when(organizationPrivacy.organizationId(7)).thenReturn(19);
        when(organizationPrivacy.retain(19, 1)).thenReturn(1L);
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentUserId()).thenReturn(11);
        when(workspaces.isMember(7, 11)).thenReturn(true);
        when(runs.getRun(7, 31)).thenReturn(run);
        when(runs.lockRunForShare(7, 31)).thenReturn(run);
        when(chat.getMissionSessionForShare(7, 11, 13)).thenReturn(session);
        var barrier = new AiAgentPrivacyBarrier();
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        when(chat.lockPrivacyBarrierForShare(7)).thenReturn(barrier);
        when(actions.getBySlot(7, 31, action.getActionSlot())).thenReturn(action);
    }

    @Test
    void inaccessiblePrimaryReturnsWithheldMetadataWithoutEscapingTheParticipant() {
        when(tools.pageContext(anyList(), any())).thenThrow(
                new ooo.klae.connex.backend.exceptions.ResourceNotFoundException("Company not found"));
        var metadata = assertDoesNotThrow(() -> service.metadata(run));
        org.junit.jupiter.api.Assertions.assertTrue(metadata.contentWithheld());
        org.junit.jupiter.api.Assertions.assertNull(metadata.counts());
    }

    @Test
    void missingVisibilitySnapshotRefusesContentBeforeAuthorizationLocks() {
        doThrow(new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("record visibility"))
                .when(recordScopes).getForWorkspace(7);

        assertThrows(ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException.class,
                () -> service.currentVersion(31));

        verify(workspaces, never()).lockAndRequirePermissionsSnapshot(anyInt(), any());
        verify(chat, never()).getMissionSessionForShare(anyInt(), anyInt(), anyInt());
    }

    @Test
    void metadataDoesNotDegradeVisibilitySnapshotInvariantFailure() {
        doThrow(new ooo.klae.connex.backend.exceptions.TenantScopeSnapshotMissingException("record visibility"))
                .when(recordScopes).getForWorkspace(7);

        assertThrows(ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException.class,
                () -> service.metadata(run));

        verify(organizationPrivacy, never()).retain(anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void malformedSourcesRefuseKnownItemBeforeCardProjection() {
        doReturn(new AiAgentProposalReadService.Authorized(run, session)).when(service).authorize(31);
        when(proposals.readFrozen(run)).thenThrow(new IllegalArgumentException("mission_continuation_refused"));
        assertThrows(AiAgentGoneException.class, () -> service.item(31, action.getActionSlot()));
        verify(cards, never()).projectMission(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void completeSourceInventoryIsValidatedBeforeAnyBatchItemCanExecute() {
        when(proposals.readFrozen(run)).thenThrow(new IllegalArgumentException("mission_continuation_refused"));
        assertThrows(AiAgentGoneException.class, () -> service.validateBatch(31, request()));
        verify(tools, never()).pageContext(anyList(), any());
        verify(cards, never()).projectMission(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void discardPrevalidationChecksProvenanceWithoutResolvingDomainTargets() {
        when(proposals.readFrozen(run)).thenReturn(List.of(action));
        assertDoesNotThrow(() -> service.validateBatch(31, request()));
        verify(proposals).readFrozen(run);
        verify(tools, never()).pageContext(anyList(), any());
    }

    @Test
    void organizationInvalidationWithholdsMetadataWhileWorkspaceBarrierRemainsCurrent() {
        doThrow(new ooo.klae.connex.backend.exceptions.ConflictException("assistant_privacy_unavailable"))
                .when(organizationPrivacy).retain(19, 1);
        var metadata = service.metadata(run);
        org.junit.jupiter.api.Assertions.assertTrue(metadata.contentWithheld());
        org.junit.jupiter.api.Assertions.assertNull(metadata.counts());
        verify(chat, never()).lockPrivacyBarrierForShare(anyInt());
        verify(proposals, never()).readFrozen(any());
    }

    @Test
    void preCutoverMissionCannotProjectKnownProposalOrCounts() {
        run.setOrganizationPrivacyGeneration(0);
        session.setOrganizationPrivacyGeneration(0);
        assertThrows(AiAgentGoneException.class, () -> service.item(31, action.getActionSlot()));
        org.junit.jupiter.api.Assertions.assertTrue(service.metadata(run).contentWithheld());
        verify(proposals, never()).readFrozen(any());
        verify(cards, never()).projectMission(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void organizationInvalidationRefusesDecisionBatchBeforeProposalMaterialization() {
        doThrow(new ooo.klae.connex.backend.exceptions.ConflictException("assistant_privacy_unavailable"))
                .when(organizationPrivacy).retain(19, 1);
        assertThrows(AiAgentGoneException.class, () -> service.validateBatch(31, request()));
        verify(proposals, never()).readFrozen(any());
        verify(tools, never()).pageContext(anyList(), any());
        verify(cards, never()).projectMission(any(), any(), anyInt(), anyString(), any());
    }

    @Test
    void decisionPrevalidationRetainsOriginalOrganizationBeforeWorkspaceAuthority() {
        when(proposals.readFrozen(run)).thenReturn(List.of(action));
        assertDoesNotThrow(() -> service.validateBatch(31, request()));
        var ordered = inOrder(organizationPrivacy, chat);
        ordered.verify(organizationPrivacy).retain(19, 1);
        ordered.verify(chat).lockPrivacyBarrierForShare(7);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void directReadsPrepareScopesBeforeOpeningTheirTenantTransactionsAndRestoreAfterFailure(boolean routed) {
        var context = new ooo.klae.connex.backend.tenant.TenantContext();
        var control = mock(ooo.klae.connex.backend.mappers.WorkspaceMapper.class);
        var resolver = mock(ooo.klae.connex.backend.tenant.TenantCatalogResolver.class);
        var transactions = new ooo.klae.connex.backend.support.TestTransactionManager();
        var tenantScopes = new ooo.klae.connex.backend.tenant.TenantWorkScope(context, resolver, control,
                mock(ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper.class),
                new ooo.klae.connex.backend.services.TenantWorkspaceScopeControlOperations(control, resolver));
        var visibility = new ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess(
                new ooo.klae.connex.backend.services.ShareWorkspaceControlAccess(control, tenantScopes, context, transactions), context);
        var boundary = new AiAgentProposalReadService(privacyGuard, workspaces, chat, runs, actions,
                proposals, cards, mock(AiChatCitationProjector.class), tools, JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC), visibility, tenantScopes, transactions);
        var anchor = new ooo.klae.connex.backend.beans.Workspace();
        anchor.setId(7);
        anchor.setOrgId(19);
        anchor.setName("Anchor");
        when(resolver.isCatalogPerPlacement()).thenReturn(routed);
        when(control.findOrganizationWorkspacesForShare(7)).thenAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return List.of(anchor);
        });
        when(control.findByOrgId(19)).thenReturn(List.of(anchor));
        org.mockito.Mockito.doAnswer(invocation -> {
            org.junit.jupiter.api.Assertions.assertTrue(
                    org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            org.junit.jupiter.api.Assertions.assertNotNull(context.getWorkspaceScopes());
            org.junit.jupiter.api.Assertions.assertEquals(routed ? "cnx_foreign" : null, context.getCatalog());
            org.junit.jupiter.api.Assertions.assertEquals("[7]", visibility.getForWorkspace(7).workspaceIdsJson());
            return null;
        }).when(workspaces).lockAndRequirePermissionsSnapshot(anyInt(), any());
        when(tools.pageContext(anyList(), any())).thenAnswer(invocation -> {
            invocation.<ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry>getArgument(1)
                    .register("company", 41);
            return null;
        });
        context.set(7, 19, 11, "member", routed ? "cnx_foreign" : null);
        try {
            org.junit.jupiter.api.Assertions.assertEquals("0", boundary.currentVersion(31));
            assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class, () -> boundary.output(31));
            when(proposals.readFrozen(run)).thenReturn(List.of(action));
            assertThrows(ooo.klae.connex.backend.exceptions.ConflictException.class,
                    () -> boundary.item(31, action.getActionSlot()));
            org.junit.jupiter.api.Assertions.assertEquals(3, transactions.beginCount());
            org.junit.jupiter.api.Assertions.assertNull(context.getWorkspaceScopes());
            org.junit.jupiter.api.Assertions.assertEquals(routed ? "cnx_foreign" : null, context.getCatalog());
            verify(control, org.mockito.Mockito.times(3)).findOrganizationWorkspacesForShare(7);
        } finally {
            context.clear();
        }
    }

    private AiAgentProposalDecisionRequest request() {
        return new AiAgentProposalDecisionRequest("3", List.of(new AiAgentProposalDecisionRequest.Item(
                action.getActionSlot(), action.getPreparationId(), "discard")));
    }
}
