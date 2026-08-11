package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import ooo.klae.connex.backend.ai.AiGenerationTaskResult;
import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.notifications.AiChatRealtimePublisher;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AiChatAgentLoopServiceTest {
    private static final AiChatQueuedTurn TURN = new AiChatQueuedTurn(
            7, 11, 13, 17, 19, 1, 23L, true, List.of());

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private AiInvocationService invocationService;
    private AiAssistantToolExecutor toolExecutor;
    private AiAssistantWriteToolService writeToolService;
    private AiChatTurnPersistenceService persistenceService;
    private AiRestrictionEpoch restrictionEpoch;
    private WorkspaceService workspaceService;
    private AiChatAgentLoopService service;

    @BeforeEach
    void setUp() {
        invocationService = mock(AiInvocationService.class);
        toolExecutor = mock(AiAssistantToolExecutor.class);
        writeToolService = mock(AiAssistantWriteToolService.class);
        persistenceService = mock(AiChatTurnPersistenceService.class);
        restrictionEpoch = mock(AiRestrictionEpoch.class);
        workspaceService = mock(WorkspaceService.class);
        var catalog = new AiAssistantToolCatalog();
        var promptAssembler = new AiAssistantPromptAssembler(objectMapper, catalog);
        var identifierResolver = mock(AiAssistantIdentifierResolver.class);
        var publishers = new StaticListableBeanFactory()
                .getBeanProvider(AiChatRealtimePublisher.class);
        service = new AiChatAgentLoopService(
                invocationService,
                new AiAssistantStepGuard(catalog),
                catalog,
                toolExecutor,
                writeToolService,
                identifierResolver,
                promptAssembler,
                persistenceService,
                restrictionEpoch,
                workspaceService,
                objectMapper,
                publishers);
        AiChatMessage userMessage = new AiChatMessage();
        userMessage.setId(TURN.userMessageId());
        userMessage.setAuthorKind("user");
        userMessage.setContent("Summarize my pipeline");
        when(persistenceService.markRunning(TURN)).thenReturn(true);
        when(persistenceService.loadHistory(TURN, 50)).thenReturn(List.of(userMessage));
        when(toolExecutor.pageContext(any(), any())).thenReturn(
                new AiAssistantToolResult(Map.of(), List.of()));
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class))).thenReturn(
                new AiAssistantToolResult(Map.of("records", List.of()), List.of()));
        when(persistenceService.proposeTool(eq(TURN), anyInt(), any(), any())).thenReturn(29);
        when(persistenceService.finishTool(eq(TURN), anyInt(), any(), any())).thenReturn(true);
        when(restrictionEpoch.current(TURN.workspaceId())).thenReturn(TURN.restrictionEpoch());
    }

    @Test
    void sixToolStepsFailAtTheCapWithoutASeventhProviderCall() throws Exception {
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        objectMapper.readTree("{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(toolStep));

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals("step_cap_exceeded", result.reason());
        verify(invocationService, times(6)).completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class));
        verify(toolExecutor, times(6)).execute(
                eq("search_records"), any(JsonNode.class), any(), eq(true));
        verify(persistenceService, never()).resolve(
                eq(TURN), any(), any(), anyInt(), anyInt());
    }

    @Test
    void confirmTierToolPersistsApprovalCardWithoutAutoExecution() throws Exception {
        JsonNode args = objectMapper.readTree(
                "{\"handle\":\"r1\",\"owner\":\"Grace Hopper\","
                        + "\"idempotency_key\":\"owner-replay-1\"}");
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool("assign_owner", args), null);
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Approval is required.", List.of()));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(toolStep), parsed(finalStep));
        AiAssistantPreparedWrite write = new AiAssistantPreparedWrite(
                "assign_owner", AiAssistantToolCatalog.ToolTier.CONFIRM,
                "owner-replay-1", "deal", 41, "{\"resolved\":true}");
        AiAssistantToolProposal proposal =
                new AiAssistantToolProposal(29, "proposed", null, true);
        when(writeToolService.prepare(eq("assign_owner"), eq(args), any())).thenReturn(write);
        when(persistenceService.proposeWriteTool(TURN, write)).thenReturn(proposal);
        when(writeToolService.proposalResult(write, proposal)).thenReturn(
                new AiAssistantToolResult(
                        Map.of("toolCallId", 29, "status", "approval_required"), List.of()));
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        verify(persistenceService).proposeWriteTool(TURN, write);
        verify(writeToolService, never()).executeAuto(TURN, 29);
    }

    @Test
    void resolvedFinalPersistsTheDemaskedAnswerAndTokenTotals() throws Exception {
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(finalStep));
        when(persistenceService.resolve(
                eq(TURN), any(), any(), anyInt(), anyInt())).thenReturn(true);

        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);

        assertEquals(AiGenerationTaskResult.Outcome.RESOLVED, result.outcome());
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).resolve(
                eq(TURN), eq("Pipeline is healthy."), metadata.capture(), eq(3), eq(5));
        assertEquals(0, objectMapper.readTree(metadata.getValue()).get("citations").size());
        assertEquals(0, objectMapper.readTree(metadata.getValue()).get("resources").size());
    }

    @Test
    void malformedProviderQuotaAndProviderErrorsStayDistinct() {
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(new AiStructuredOutcome.Malformed<>("malformed_output", 1, 1, "stop"));
        assertTerminal("malformed_output");

        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenThrow(new TooManyRequestsException("quota"));
        assertTerminal("quota_exhausted");

        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenThrow(new AiProviderException("provider"));
        assertTerminal("provider_error");
    }

    @Test
    void revokedAiUseAndChangedRestrictionsRemainDistinctFromProviderFailure() {
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenThrow(new ForbiddenException("gate"));
        doThrow(new ForbiddenException("revoked")).when(workspaceService)
                .requirePermission(TURN.workspaceId(), TURN.userId(), Permission.AI_USE);

        assertTerminal("access_revoked");

        when(restrictionEpoch.current(TURN.workspaceId()))
                .thenReturn(TURN.restrictionEpoch() + 1);
        assertTerminal("restrictions_changed");
    }

    @Test
    void terminalTurnStopsBeforeTheProposedToolServiceCall() throws Exception {
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        objectMapper.readTree("{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(toolStep));
        doThrow(new ConflictException("Assistant turn is no longer active"))
                .when(persistenceService).requireRunning(TURN);

        assertTerminal("internal_error");

        verify(toolExecutor, never()).execute(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void toolServiceAndFinalPersistenceFailuresAreInternal() throws Exception {
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        objectMapper.readTree("{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(toolStep));
        when(toolExecutor.execute(any(), any(), any(), any(Boolean.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertTerminal("internal_error");

        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(finalStep));
        doThrow(new IllegalStateException("database unavailable")).when(persistenceService)
                .resolve(eq(TURN), any(), any(), anyInt(), anyInt());

        assertTerminal("internal_error");
    }

    @Test
    void finalCommitEpochChangeKeepsItsDistinctTerminalReason() {
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(finalStep));
        doThrow(new AiAssistantLoopException(
                "restrictions_changed", "restrictions_changed")).when(persistenceService)
                .resolve(eq(TURN), any(), any(), anyInt(), anyInt());

        assertTerminal("restrictions_changed");
    }

    @Test
    void unknownCitationFailsBeforeAnAssistantMessageIsCommitted() {
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Use this record.", List.of("r9")));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(finalStep));

        assertTerminal("malformed_output");

        verify(persistenceService, never()).resolve(
                eq(TURN), any(), any(), anyInt(), anyInt());
    }

    @Test
    void unknownToolHandleFailsBeforeDurableProposalOrDomainExecution() throws Exception {
        AiAssistantToolExecutor realExecutor = new AiAssistantToolExecutor(
                new AiAssistantToolCatalog(),
                mock(ooo.klae.connex.backend.services.SearchService.class),
                mock(ooo.klae.connex.backend.services.PersonService.class),
                mock(ooo.klae.connex.backend.services.CompanyService.class),
                mock(ooo.klae.connex.backend.services.DealService.class),
                mock(ooo.klae.connex.backend.services.ActivityService.class),
                mock(ooo.klae.connex.backend.services.TaskService.class),
                mock(ooo.klae.connex.backend.services.ScoringService.class),
                workspaceService,
                mock(PersonMapper.class),
                mock(CompanyMapper.class),
                mock(DealMapper.class),
                mock(AiAssistantDateResolver.class));
        service = new AiChatAgentLoopService(
                invocationService,
                new AiAssistantStepGuard(new AiAssistantToolCatalog()),
                new AiAssistantToolCatalog(),
                realExecutor,
                writeToolService,
                mock(AiAssistantIdentifierResolver.class),
                new AiAssistantPromptAssembler(objectMapper, new AiAssistantToolCatalog()),
                persistenceService,
                restrictionEpoch,
                workspaceService,
                objectMapper,
                new StaticListableBeanFactory().getBeanProvider(AiChatRealtimePublisher.class));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class), any(AiRawOutputGuard.class)))
                .thenReturn(parsed(new AiAssistantStep(
                        new AiAssistantStep.Tool(
                                "get_record", objectMapper.readTree("{\"handle\":\"r9\"}")),
                        null)));

        assertTerminal("malformed_output");

        verify(persistenceService, never()).proposeTool(eq(TURN), anyInt(), any(), any());
    }

    @Test
    void historyCharacterBudgetKeepsNewestUserMessageWholeAndTruncatesTheOldestBoundary() {
        AiChatMessage oldest = message(1, "a".repeat(60_000));
        AiChatMessage recent = message(2, "b".repeat(20_000));
        AiChatMessage initiating = message(TURN.userMessageId(), "c".repeat(16_000));

        List<AiChatMessage> bounded = AiChatAgentLoopService.boundedHistory(
                List.of(oldest, recent, initiating), TURN);

        assertEquals(3, bounded.size());
        assertEquals(64_000, bounded.stream().mapToInt(message -> message.getContent().length()).sum());
        assertEquals("a".repeat(28_000), bounded.get(0).getContent());
        assertEquals(recent.getContent(), bounded.get(1).getContent());
        assertEquals(initiating.getContent(), bounded.get(2).getContent());
    }

    private void assertTerminal(String reason) {
        AiGenerationTaskResult<AiChatTurnGenerationResult> result = service.run(TURN);
        assertEquals(AiGenerationTaskResult.Outcome.FAILED, result.outcome());
        assertEquals(reason, result.reason());
    }

    private static AiStructuredOutcome<AiAssistantStep> parsed(AiAssistantStep step) {
        return new AiStructuredOutcome.Parsed<>(step, 0, 3, 5, "stop");
    }

    private static AiChatMessage message(int id, String content) {
        AiChatMessage message = new AiChatMessage();
        message.setId(id);
        message.setAuthorKind("user");
        message.setContent(content);
        return message;
    }
}
