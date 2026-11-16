package ooo.klae.connex.backend.ai.mission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.ai.AiPrivacyMode;
import ooo.klae.connex.backend.ai.assistant.AiAssistantLoopException;
import ooo.klae.connex.backend.ai.assistant.AiChatPrivacyToken;
import ooo.klae.connex.backend.ai.assistant.AiChatQueryScope;
import ooo.klae.connex.backend.ai.assistant.AiChatQueuedTurn;
import ooo.klae.connex.backend.ai.assistant.AiChatResourceRegistry;
import ooo.klae.connex.backend.ai.lease.AiRunLease;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseGuard;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseKey;
import ooo.klae.connex.backend.ai.lease.AiRunLeaseSubject;
import ooo.klae.connex.backend.beans.AiChatMessage;

class AiAgentScopePolicyTest {
    private final AiAgentScopePolicy scope = new AiAgentScopePolicy("person", 41);

    @Test
    void recordScopeBlocksAccessibleOutsideRecordAndOtherKinds() {
        assertDoesNotThrow(() -> scope.requireRecord("person", 41));
        for (String kind : List.of("company", "deal", "task")) {
            assertEquals("mission_scope_refused", assertThrows(AiAssistantLoopException.class,
                    () -> scope.requireRecord(kind, 41)).detailReason());
        }
        assertThrows(AiAssistantLoopException.class, () -> scope.requireRecord("person", 42));
    }

    @ParameterizedTest
    @ValueSource(strings = {"create_activity", "create_task", "create_note", "add_tag", "remove_tag",
            "update_fields", "create_person", "create_deal", "create_report", "draft_workflow",
            "complete_task", "reschedule_task", "ask_member", "update_plan", "search_records",
            "search_persons", "get_pipeline_metrics"})
    void sliceOneCannotReachWriteQuestionSearchOrPlanTools(String name) {
        AiAssistantLoopException failure = assertThrows(AiAssistantLoopException.class, () -> scope.requireTool(name));
        assertEquals("mission_tool_refused", failure.detailReason());
        assertFalse(failure.recoverable());
    }

    @Test
    void declaredReadCeilingIsClosedAndAvailableWithoutMutatingReactiveCatalog() {
        AiAgentExecutionPolicy policy = policy(turn(17));
        assertTrue(AiAgentExecutionPolicy.permits("create_task"));
        policy.execute(() -> {
            for (String name : AiAgentScopePolicy.TOOLS) {
                assertDoesNotThrow(() -> scope.requireTool(name));
                assertTrue(AiAgentExecutionPolicy.permits(name));
            }
            assertFalse(AiAgentExecutionPolicy.permits("create_task"));
            assertFalse(AiAgentExecutionPolicy.permits("ask_member"));
            return null;
        });
        assertTrue(AiAgentExecutionPolicy.permits("create_task"));
    }

    @Test
    void callbackCannotBorrowAnotherTurnOrNestAnExecutionAndThreadIsCleared() {
        AiChatQueuedTurn turn = turn(17);
        AiAgentExecutionPolicy policy = policy(turn);
        assertThrows(IllegalArgumentException.class, () -> policy.execute(() -> {
            assertSame(policy, AiAgentExecutionPolicy.forTurn(turn));
            assertThrows(IllegalStateException.class, () -> AiAgentExecutionPolicy.forTurn(turn(18)));
            assertThrows(IllegalStateException.class, () -> policy.execute(() -> null));
            throw new IllegalArgumentException("test interruption");
        }));
        assertNull(AiAgentExecutionPolicy.current());
        assertNull(AiAgentExecutionPolicy.forTurn(turn));
    }

    @Test
    void preclaimScopeRestoresOuterContextAfterNestedFailureAndClearsThread() {
        AiAgentScopePolicy inner = new AiAgentScopePolicy("company", 42);
        assertNull(AiAgentScopePolicy.current());
        scope.evaluate(() -> {
            assertSame(scope, AiAgentScopePolicy.current());
            assertThrows(IllegalStateException.class, () -> inner.evaluate(() -> {
                assertSame(inner, AiAgentScopePolicy.current());
                throw new IllegalStateException("test interruption");
            }));
            assertSame(scope, AiAgentScopePolicy.current());
            return null;
        });
        assertNull(AiAgentScopePolicy.current());
    }

    @Test
    void readScopeRestoresOuterContextAfterNestedFailureAndClearsThread() {
        var inner = new AiAgentScopePolicy("company", 42);
        assertNull(AiAgentScopePolicy.current());
        scope.evaluateRead(() -> {
            assertSame(scope, AiAgentScopePolicy.current());
            assertThrows(IllegalStateException.class, () -> inner.evaluateRead(() -> {
                assertSame(inner, AiAgentScopePolicy.current());
                throw new IllegalStateException("read failed");
            }));
            assertSame(scope, AiAgentScopePolicy.current());
            return null;
        });
        assertNull(AiAgentScopePolicy.current());
    }

    @Test
    void preclaimScopeCannotOverrideAnInstalledExecutionBoundary() {
        AiAgentExecutionPolicy policy = policy(turn(17));
        policy.execute(() -> new AiAgentScopePolicy("company", 42).evaluate(() -> {
            assertSame(scope, AiAgentScopePolicy.current());
            assertThrows(AiAssistantLoopException.class, () -> AiAgentScopePolicy.current().requireRecord("company", 42));
            return null;
        }));
        assertNull(AiAgentScopePolicy.current());
    }

    @Test
    void preclaimAndExecutionScopesRejectPreviouslyIssuedOutsiderHandles() {
        AiChatResourceRegistry registry = new AiChatResourceRegistry();
        String selected = registry.register("person", 41);
        String outsider = registry.register("person", 42);
        scope.evaluate(() -> {
            assertEquals(41, registry.resolve(selected).id());
            assertThrows(AiAssistantLoopException.class, () -> registry.resolve(outsider));
            assertThrows(AiAssistantLoopException.class, () -> registry.register("company", 41));
            return null;
        });
        policy(turn(17)).execute(() -> {
            assertThrows(AiAssistantLoopException.class, () -> registry.resolve(outsider));
            assertThrows(AiAssistantLoopException.class, () -> registry.requireKnownCitations(List.of(outsider)));
            return null;
        });
        assertEquals(42, registry.resolve(outsider).id());
    }

    private AiAgentExecutionPolicy policy(AiChatQueuedTurn turn) {
        return new AiAgentExecutionPolicy(31, 37, 2, turn, new AiChatMessage(), scope, 8,
                Instant.parse("2026-10-15T12:03:00Z"),
                new AiRunLease(new AiRunLeaseKey(7, AiRunLeaseSubject.AGENT_RUN, 31), "test", 2),
                new AiRunLeaseGuard(Duration.ofMinutes(1)), mock(AiAgentRunPersistenceService.class),
                mock(AiAgentCapabilityGate.class));
    }

    private static AiChatQueuedTurn turn(int turnId) {
        return new AiChatQueuedTurn(7, 11, 13, turnId, 19, 3, 23, false, List.of(), List.of(),
                AiPrivacyMode.MASKED, false, AiChatQueryScope.none(), new AiChatPrivacyToken(7, 13, 4, 3, 1, 1L));
    }
}
