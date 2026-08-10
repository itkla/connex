package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.ai.AiInvocation;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiRawOutputGuard;
import ooo.klae.connex.backend.ai.AiRestrictionEpoch;
import ooo.klae.connex.backend.ai.AiStructuredOutcome;
import ooo.klae.connex.backend.ai.assistant.AiAssistantStep;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolExecutor;
import ooo.klae.connex.backend.ai.assistant.AiAssistantToolResult;
import ooo.klae.connex.backend.ai.assistant.AiChatAgentLoopService;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnPersistenceService;
import ooo.klae.connex.backend.ai.assistant.AiChatTurnTerminalCoordinator;
import ooo.klae.connex.backend.beans.AiChatMessage;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.beans.AiChatTurn;
import ooo.klae.connex.backend.dto.AiChatTurnCreateRequest;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import tools.jackson.databind.json.JsonMapper;

/** Proves final-answer persistence rolls back when restrictions advance during its commit window. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiChatRestrictionEpochCommitTest extends AbstractServiceTest {
    @Autowired private AiChatAgentLoopService agentLoopService;
    @Autowired private AiChatTurnPersistenceService persistenceService;
    @Autowired private AiChatTurnTerminalCoordinator terminalCoordinator;
    @Autowired private AiRestrictionEpoch restrictionEpoch;
    @Autowired private AiChatMapper chatMapper;
    @Autowired private SqlSessionTemplate sqlSessionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private AiInvocationService invocationService;
    @MockitoBean private AiAssistantToolExecutor toolExecutor;
    @MockitoSpyBean private AiChatMapper chatMapperSpy;

    @AfterEach
    void cleanUpDurableFixtures() {
        if (workspace != null && currentUser != null) {
            jdbcTemplate.update(
                    "DELETE FROM ai_chat_session WHERE workspace_id = ? AND created_by_user_id = ?",
                    workspace.getId(), currentUser.getId());
            jdbcTemplate.update(
                    "DELETE FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                    workspace.getId(), currentUser.getId());
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", currentUser.getId());
        }
    }

    @Test
    void epochBumpDuringFinalCommitRollsBackTheAnswerAndTerminalizesTheTurn() throws Exception {
        AiChatSession session = new AiChatSession();
        session.setWorkspaceId(workspace.getId());
        session.setCreatedByUserId(currentUser.getId());
        session.setTitle("Restriction fence " + unique());
        session.setVisibility("private");
        session.setStatus("active");
        chatMapper.insertSession(session);
        long expectedEpoch = restrictionEpoch.current(workspace.getId());
        AiChatQueuedTurn turn = persistenceService.queue(
                session.getId(),
                new AiChatTurnCreateRequest("Summarize my pipeline", List.of()),
                expectedEpoch);
        AiAssistantStep toolStep = new AiAssistantStep(
                new AiAssistantStep.Tool(
                        "search_records",
                        JsonMapper.builder().build().readTree(
                                "{\"query\":\"pipeline\",\"kinds\":[\"deal\"]}")),
                null);
        AiAssistantStep finalStep = new AiAssistantStep(
                null, new AiAssistantStep.FinalAnswer("Pipeline is healthy.", List.of()));
        when(invocationService.completeStructured(
                any(AiInvocation.class), eq(AiAssistantStep.class),
                any(AiRawOutputGuard.class)))
                .thenReturn(
                        new AiStructuredOutcome.Parsed<>(toolStep, 0, 3, 5, "stop"),
                        new AiStructuredOutcome.Parsed<>(finalStep, 0, 3, 5, "stop"));
        when(toolExecutor.pageContext(any(), any())).thenReturn(
                new AiAssistantToolResult(Map.of(), List.of()));
        when(toolExecutor.execute(any(), any(), any())).thenReturn(
                new AiAssistantToolResult(Map.of("records", List.of()), List.of()));
        AiChatMapper realChatMapper = sqlSessionTemplate.getMapper(AiChatMapper.class);
        AtomicBoolean epochBumped = new AtomicBoolean();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            doAnswer(invocation -> {
                AiChatMessage message = invocation.getArgument(0);
                if ("assistant".equals(message.getAuthorKind())) {
                    executor.submit(() -> restrictionEpoch.bump(workspace.getId()))
                            .get(1, TimeUnit.SECONDS);
                    epochBumped.set(true);
                }
                return realChatMapper.insertMessage(message);
            }).when(chatMapperSpy).insertMessage(any(AiChatMessage.class));

            var result = agentLoopService.run(turn);

            assertEquals("restrictions_changed", result.reason());
            verify(toolExecutor).execute(eq("search_records"), any(), any());
            terminalCoordinator.listener(turn).onTerminal(result.outcome(), result.reason());
        }

        List<AiChatMessage> messages = chatMapper.listMessages(
                workspace.getId(), session.getId(), 100, 0);
        AiChatTurn stored = Objects.requireNonNull(chatMapper.getTurnById(
                workspace.getId(), session.getId(), turn.turnId()));
        assertTrue(epochBumped.get());
        assertEquals(1, messages.size());
        assertEquals("user", messages.getFirst().getAuthorKind());
        assertEquals("failed", stored.getStatus());
        assertEquals("restrictions_changed", stored.getTerminalReason());
    }
}
