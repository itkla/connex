package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiChatCitationProjector;
import ooo.klae.connex.backend.beans.AiAgentPrivacyBarrier;
import ooo.klae.connex.backend.beans.AiAgentRun;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.dto.AiAgentRunControlRequest;
import ooo.klae.connex.backend.exceptions.AiAgentGoneException;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiAgentRunMapper;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

class AiAgentRunServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private final AiAgentRunMapper runs = mock(AiAgentRunMapper.class);
    private final AiChatMapper chat = mock(AiChatMapper.class);
    private final ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService organizationAuthority =
            mock(ooo.klae.connex.backend.ai.assistant.AiAssistantPrivacyService.class);
    private final ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard privacyGuard =
            new ooo.klae.connex.backend.ai.assistant.AiChatPrivacyGuard(organizationAuthority);
    private final AiAgentRunPersistenceService persistence = mock(AiAgentRunPersistenceService.class);
    private final AiAgentCapabilityGate gate = mock(AiAgentCapabilityGate.class);
    private final AiAgentRealtimePublisher publisher = mock(AiAgentRealtimePublisher.class);
    private final AiChatCitationProjector citations = mock(AiChatCitationProjector.class);
    private final WorkspaceService workspaces = mock(WorkspaceService.class);
    private final AiAgentRun run = new AiAgentRun();
    private final AiChatSession session = new AiChatSession();
    private final AiAgentPrivacyBarrier barrier = new AiAgentPrivacyBarrier();
    private final org.springframework.transaction.PlatformTransactionManager transactions =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
    private final AiAgentRunService service = new AiAgentRunService(privacyGuard,
            mock(ooo.klae.connex.backend.ai.assistant.AiAssistantSessionReadAudit.class), transactions, runs, chat, persistence, gate, publisher,
            mock(AiAssistantToolExecutor.class), citations, new AiRestrictionEpoch(), workspaces,
            JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self",
                new org.springframework.beans.factory.ObjectProvider<AiAgentRunService>() {
                    @Override
                    public AiAgentRunService getObject() {
                        return service;
                    }
                });
        when(organizationAuthority.organizationId(org.mockito.ArgumentMatchers.anyInt())).thenReturn(1);
        when(organizationAuthority.current(1)).thenReturn(1L);
        when(organizationAuthority.freshCurrent(1)).thenReturn(1L);
        when(workspaces.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaces.getCurrentUserId()).thenReturn(11);
        when(workspaces.isMember(7, 11)).thenReturn(true);
        run.setId(31);
        run.setWorkspaceId(7);
        run.setOrganizationPrivacyGeneration(1L);
        run.setSessionId(13);
        run.setOwnerUserId(11);
        run.setMissionType("record_review_v1");
        run.setDefinitionVersion(1);
        run.setRuntimeContractVersion(1);
        run.setStatus("queued");
        run.setStateVersion(9007199254740993L);
        run.setPrivacyGeneration(4);
        run.setGoal("Review the selected record");
        run.setScopeJson("{\"kind\":\"record\",\"recordKind\":\"person\",\"recordId\":41}");
        run.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        run.setExecutionDeadlineAt(run.getCreatedAt().plusSeconds(900));
        run.setHardDeadlineAt(run.getExecutionDeadlineAt());
        run.setMaxSteps(8);
        run.setMaxTokens(131072);
        barrier.setWorkspaceId(7);
        barrier.setGeneration(4);
        when(runs.getRun(7, 31)).thenReturn(run);
        when(runs.lockRunForShare(7, 31)).thenReturn(run);
        session.setId(13);
        session.setWorkspaceId(7);
        session.setOrganizationPrivacyGeneration(1L);
        session.setCreatedByUserId(11);
        session.setVisibility("private");
        session.setStatus("active");
        when(chat.getMissionSessionForShare(7, 11, 13)).thenReturn(session);
        when(chat.lockPrivacyBarrierForShare(7)).thenReturn(barrier);
        when(chat.getAccessibleSessionById(7, 11, 13, 1L)).thenReturn(session);
        when(chat.getPrivacyBarrier(7)).thenReturn(barrier);
        when(chat.lockPrivacyBarrierForShare(7)).thenReturn(barrier);
        when(transactions.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
    }

    @Test
    void waitingCannotPauseOrResume() {
        run.setStatus("waiting_approval");
        var request = new AiAgentRunControlRequest(Long.toString(run.getStateVersion()));
        assertEquals("mission_state_conflict", assertThrows(ConflictException.class,
                () -> service.control(31, request, "pause")).getMessage());
        assertEquals("mission_state_conflict", assertThrows(ConflictException.class,
                () -> service.control(31, request, "resume")).getMessage());
        verifyNoInteractions(gate, persistence);
    }

    @Test
    void blockedGenerationRetainsSafeMetadataAndCancellation() {
        barrier.setBlocked(true);
        var result = service.get(31);
        assertTrue(result.contentWithheld());
        assertEquals("queued", result.state());
        assertEquals(List.of("cancel"), result.availableActions());
        assertNull(result.proposalCounts());
        assertNull(result.proposalMembershipRevision());
        assertThrows(AiAgentGoneException.class, () -> service.output(31));
    }

    @Test
    void unsupportedSchemaRetainsSafeMetadataAndCancellation() {
        run.setMissionType("record_preparation_v1");
        run.setRuntimeContractVersion(3);
        run.setCheckpointSchemaVersion(99);
        var result = service.get(31);
        assertTrue(result.contentWithheld());
        assertEquals(List.of("cancel"), result.availableActions());
        assertNull(result.proposalCounts());
        assertThrows(AiAgentGoneException.class, () -> service.output(31));
    }

    @Test
    void durableVersionRemainsDecimalStringBeyondJavascriptIntegerRange() {
        assertEquals("9007199254740993", service.get(31).stateVersion());
    }

    @Test
    void completedPurgeStillWithholdsOldGenerationContentAndCounts() {
        barrier.setGeneration(5);
        barrier.setBlocked(false);
        run.setReportMessageId(43);

        var dto = service.get(31);

        assertTrue(dto.contentWithheld());
        assertNull(dto.goal());
        assertNull(dto.scope());
        assertNull(dto.progress());
        assertFalse(dto.outputAvailable());
        assertEquals(List.of("cancel"), dto.availableActions());
        assertThrows(AiAgentGoneException.class, () -> service.output(31));
        verify(chat, never()).getMissionMessage(anyInt(), anyInt(), any());
    }

    @Test
    void unknownRuntimeContractWithholdsContentUntilACompatibleReaderIsAvailable() {
        run.setRuntimeContractVersion(2);

        var dto = service.get(31);

        assertTrue(dto.contentWithheld());
        assertNull(dto.goal());
        assertNull(dto.scope());
        assertNull(dto.progress());
        assertThrows(AiAgentGoneException.class, () -> service.output(31));
    }

    @Test
    void terminalTombstoneRemainsDiscoverableWithoutFormerSessionOrContent() {
        run.setSessionId(null);
        run.setStatus("cancelled");
        run.setPurgedAt(run.getCreatedAt());
        run.setWithheldAt(run.getCreatedAt());
        run.setScopeJson(null);
        run.setGoal(null);

        var dto = service.get(31);

        assertTrue(dto.contentWithheld());
        assertNull(dto.sessionId());
        assertTrue(dto.availableActions().isEmpty());
    }

    @Test
    void otherOwnerOrLostMembershipCannotReadKnownRun() {
        run.setOwnerUserId(12);
        assertThrows(ResourceNotFoundException.class, () -> service.get(31));
        run.setOwnerUserId(11);
        when(workspaces.isMember(7, 11)).thenReturn(false);
        assertThrows(ResourceNotFoundException.class, () -> service.output(31));
    }

    @Test
    void sharedSessionDepartedCreatorRefusesKnownIdAndDisappearsFromList() {
        session.setVisibility("shared");
        session.setCreatedByUserId(12);
        when(workspaces.isMember(7, 12)).thenReturn(false);
        when(runs.listRuns(7, 11, null, null, null, 21, 1L)).thenReturn(List.of(run));

        assertThrows(ResourceNotFoundException.class, () -> service.get(31));
        assertThrows(ResourceNotFoundException.class, () -> service.output(31));
        assertTrue(service.list(20, null, null, null).items().isEmpty());
        verify(chat, never()).getMissionMessage(anyInt(), anyInt(), any());
    }

    @Test
    void outputBeforePublicationIsConflictWithoutReportLookup() {
        assertEquals("mission_output_not_ready", assertThrows(ConflictException.class,
                () -> service.output(31)).getMessage());
        verify(chat, never()).getMissionMessage(anyInt(), anyInt(), any());
    }

    @Test
    void canonicalReportReturnsEmptyReviewAndReceiptCollections() {
        AiChatMessage report = report();
        when(chat.getMissionMessage(7, 31, "report")).thenReturn(report);
        when(citations.project(7, List.of(report))).thenReturn(new AiChatCitationProjector.Projection(Map.of(), Set.of()));

        var output = service.output(31);

        assertEquals("1", output.outputRevision());
        assertEquals("Grounded report", output.report().markdown());
        assertEquals(1, output.report().coverage().processed());
        assertTrue(output.proposalSet().frozen());
        assertEquals("0", output.proposalSet().revision());
        assertTrue(output.proposalSet().items().isEmpty());
        assertTrue(output.autoWriteReceipts().isEmpty());
    }

    @Test
    void citationWithholdingIsAdditionalToCurrentPrivacy() {
        AiChatMessage report = report();
        when(chat.getMissionMessage(7, 31, "report")).thenReturn(report);
        when(citations.project(7, List.of(report))).thenReturn(new AiChatCitationProjector.Projection(Map.of(), Set.of(43)));

        assertThrows(AiAgentGoneException.class, () -> service.output(31));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"version\":1,\"workspaceId\":8,\"owner\":11,\"sessionId\":13,\"state\":\"queued\",\"lastId\":20}",
            "{\"version\":1,\"workspaceId\":7,\"owner\":12,\"sessionId\":13,\"state\":\"queued\",\"lastId\":20}",
            "{\"version\":1,\"workspaceId\":7,\"owner\":11,\"sessionId\":14,\"state\":\"queued\",\"lastId\":20}",
            "{\"version\":1,\"workspaceId\":7,\"owner\":11,\"sessionId\":13,\"state\":\"done\",\"lastId\":20}",
            "{\"version\":2,\"workspaceId\":7,\"owner\":11,\"sessionId\":13,\"state\":\"queued\",\"lastId\":20}",
            "{\"version\":1,\"workspaceId\":7,\"owner\":11,\"sessionId\":13,\"state\":\"queued\",\"lastId\":0}"
    })
    void cursorCannotChangeItsWorkspaceOwnerFilterOrVersion(String value) {
        String cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));

        assertThrows(BadRequestException.class, () -> service.list(20, 13, "queued", cursor));
        verify(runs, never()).listRuns(anyInt(), anyInt(), any(), any(), any(), anyInt(), org.mockito.ArgumentMatchers.eq(1L));
    }

    @Test
    void cancelDoesNotRequireProviderReadiness() {
        when(persistence.control(run, run.getStateVersion(), "cancel")).thenReturn(run);

        service.control(31, new AiAgentRunControlRequest(Long.toString(run.getStateVersion())), "cancel");

        verify(persistence).control(run, run.getStateVersion(), "cancel");
        verifyNoInteractions(gate);
    }

    @Test
    void missionPageLocksContentBeforeOrganizationAndWorkspaceAuthority() {
        when(runs.listRuns(7, 11, null, null, null, 21, 1L)).thenReturn(List.of(run));

        var page = service.list(20, null, null, null);

        assertEquals(1, page.items().size());
        assertEquals(Long.toString(run.getStateVersion()), page.items().getFirst().stateVersion());
        var ordered = org.mockito.Mockito.inOrder(chat, runs, organizationAuthority);
        ordered.verify(chat).getMissionSessionForShare(7, 11, 13);
        ordered.verify(runs).lockRunForShare(7, 31);
        ordered.verify(organizationAuthority).retain(1, 1L);
        ordered.verify(chat).lockPrivacyBarrierForShare(7);
    }

    @Test
    void missionPageReleasesEachProjectionBeforeLockingTheNextSession() {
        AiAgentRun second = new AiAgentRun();
        org.springframework.beans.BeanUtils.copyProperties(run, second);
        second.setId(32);
        second.setSessionId(14);
        AiChatSession secondSession = new AiChatSession();
        org.springframework.beans.BeanUtils.copyProperties(session, secondSession);
        secondSession.setId(14);
        when(runs.listRuns(7, 11, null, null, null, 21, 1L)).thenReturn(List.of(second, run));
        when(runs.getRun(7, 32)).thenReturn(second);
        when(runs.lockRunForShare(7, 32)).thenReturn(second);
        when(chat.getAccessibleSessionById(7, 11, 14, 1L)).thenReturn(secondSession);
        when(chat.getMissionSessionForShare(7, 11, 14)).thenReturn(secondSession);
        when(transactions.getTransaction(any())).thenAnswer(invocation -> {
            org.springframework.transaction.TransactionDefinition definition = invocation.getArgument(0);
            assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                    definition.getPropagationBehavior());
            assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED,
                    definition.getIsolationLevel());
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        });

        assertEquals(List.of(32, 31), service.list(20, null, null, null).items().stream()
                .map(ooo.klae.connex.backend.dto.AiAgentRunDto::id).toList());

        var ordered = org.mockito.Mockito.inOrder(chat, runs, organizationAuthority, transactions);
        ordered.verify(chat).getMissionSessionForShare(7, 11, 14);
        ordered.verify(runs).lockRunForShare(7, 32);
        ordered.verify(organizationAuthority).retain(1, 1L);
        ordered.verify(chat).lockPrivacyBarrierForShare(7);
        ordered.verify(transactions).commit(any());
        ordered.verify(chat).getMissionSessionForShare(7, 11, 13);
        ordered.verify(runs).lockRunForShare(7, 31);
        ordered.verify(organizationAuthority).retain(1, 1L);
        ordered.verify(chat).lockPrivacyBarrierForShare(7);
        ordered.verify(transactions).commit(any());
    }

    @Test
    void missionPageOmitsADeletedCandidateWithoutFailingThePage() {
        when(runs.listRuns(7, 11, null, null, null, 21, 1L)).thenReturn(List.of(run));
        when(runs.getRun(7, 31)).thenReturn(null);

        assertTrue(service.list(20, null, null, null).items().isEmpty());
    }

    @Test
    void missionPageRechecksTheStateFilterAfterDiscovery() {
        when(runs.listRuns(7, 11, null, "queued", null, 21, 1L)).thenReturn(List.of(run));
        run.setStatus("cancelled");

        assertTrue(service.list(20, null, "queued", null).items().isEmpty());
    }

    private AiChatMessage report() {
        run.setReportMessageId(43);
        run.setStatus("done");
        run.setFinishedAt(run.getCreatedAt().plusSeconds(30));
        AiChatMessage report = new AiChatMessage();
        report.setId(43);
        report.setContent("Grounded report");
        report.setWorkspaceId(7);
        report.setOrganizationPrivacyGeneration(1L);
        report.setSessionId(13);
        report.setMissionRunId(31);
        report.setMissionMessageKind("report");
        report.setPrivacyGeneration(4L);
        return report;
    }
}
